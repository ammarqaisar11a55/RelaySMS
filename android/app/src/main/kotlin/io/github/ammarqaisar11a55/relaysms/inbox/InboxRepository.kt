package io.github.ammarqaisar11a55.relaysms.inbox

import io.github.ammarqaisar11a55.relaysms.core.database.MessageDao
import io.github.ammarqaisar11a55.relaysms.core.database.MessageDirection
import io.github.ammarqaisar11a55.relaysms.core.database.MessageEntity
import io.github.ammarqaisar11a55.relaysms.core.database.MessageOrigin
import io.github.ammarqaisar11a55.relaysms.core.database.MessageStatus
import io.github.ammarqaisar11a55.relaysms.core.security.CryptoUtil
import io.github.ammarqaisar11a55.relaysms.core.security.FieldCipher
import io.github.ammarqaisar11a55.relaysms.sms.IncomingSms
import io.github.ammarqaisar11a55.relaysms.sms.SenderMatcher
import io.github.ammarqaisar11a55.relaysms.sms.SmsAssembler
import io.github.ammarqaisar11a55.relaysms.sms.send.SendFailure
import io.github.ammarqaisar11a55.relaysms.sms.send.SendStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.util.Collections

/** Keeps the system SMS store in step with RelaySMS's own inbox while RelaySMS is the default SMS app. */
interface MessageMirror {
    suspend fun onStored()
    suspend fun onRead(providerId: Long)
    suspend fun onDeleted(providerIds: List<Long>)
}

/** A decrypted inbox message, held in memory only. */
data class InboxMessage(
    val id: String,
    val address: String,
    val body: String,
    val receivedAtMillis: Long,
    val storedAtMillis: Long,
    val direction: Int,
    val origin: String,
    val status: String,
    val isRead: Boolean,
    val hasOtp: Boolean,
    val partCount: Int,
    val addressKey: String,
)

/**
 * RelaySMS's own message store. Addresses and bodies are encrypted before they reach Room and
 * decrypted only into memory. Insertion is idempotent on the message id, including for messages
 * the user has deleted.
 */
class InboxRepository(
    private val dao: MessageDao,
    private val cipher: FieldCipher,
    private val now: () -> Long = System::currentTimeMillis,
    /** Decides at store time whether the text looks like it carries a one-time code. */
    private val detectsOtp: (String) -> Boolean = { false },
    /** Copies messages into the system SMS store while RelaySMS is the default SMS app. */
    private val mirror: MessageMirror? = null,
) {
    private val _arrivals = MutableSharedFlow<InboxMessage>(extraBufferCapacity = 64)

    /** Emits each newly stored incoming message once. Used for notifications. */
    val arrivals: SharedFlow<InboxMessage> = _arrivals.asSharedFlow()

    private val cache: MutableMap<String, Pair<String, String>> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Pair<String, String>>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, String>>?) = size > CACHE_SIZE
        },
    )

    val messages: Flow<List<InboxMessage>> = dao.observeAll().map { rows -> rows.mapNotNull(::decrypt) }.flowOn(Dispatchers.Default)

    val unreadCount: Flow<Int> = dao.observeUnreadCount()

    fun observe(id: String): Flow<InboxMessage?> = dao.observe(id).map { it?.let(::decrypt) }.flowOn(Dispatchers.Default)

    /**
     * Stores an incoming message.
     * @return true if it was stored now, false if this id was already known (duplicate delivery).
     * @throws Exception on storage failure, so that the caller does not acknowledge the message.
     */
    suspend fun storeIncoming(id: String, sms: IncomingSms, origin: String = MessageOrigin.RELAY): Boolean {
        require(isValid(sms)) { "invalid message" }
        val t = now()
        val entity = MessageEntity(
            id = id,
            addressEnc = cipher.encrypt(sms.sender.toByteArray(Charsets.UTF_8), addressContext(id)),
            bodyEnc = cipher.encrypt(sms.body.toByteArray(Charsets.UTF_8), bodyContext(id)),
            addressKey = addressKey(sms.sender),
            receivedAt = sms.receivedAtMillis,
            storedAt = t,
            direction = MessageDirection.INCOMING,
            origin = origin,
            status = MessageStatus.RECEIVED,
            isRead = false,
            hasOtp = detectsOtp(sms.body),
            partCount = sms.partCount,
            subscriptionId = sms.subscriptionId,
            providerId = null,
            deletedAt = null,
        )
        if (dao.insertIfAbsent(entity) == -1L) return false
        cache[id] = sms.sender to sms.body
        _arrivals.tryEmit(toModel(entity, sms.sender, sms.body))
        mirror?.onStored()
        return true
    }

    /**
     * Records a message the user asked to send. It starts as pending and is shown as not sent
     * until a result arrives.
     */
    suspend fun storeOutgoing(id: String, to: String, text: String, origin: String): Boolean {
        val t = now()
        val entity = MessageEntity(
            id = id,
            addressEnc = cipher.encrypt(to.toByteArray(Charsets.UTF_8), addressContext(id)),
            bodyEnc = cipher.encrypt(text.toByteArray(Charsets.UTF_8), bodyContext(id)),
            addressKey = addressKey(to),
            receivedAt = t, storedAt = t,
            direction = MessageDirection.OUTGOING, origin = origin, status = MessageStatus.PENDING,
            isRead = true, hasOtp = false, partCount = 1, subscriptionId = -1, providerId = null, deletedAt = null,
        )
        if (dao.insertIfAbsent(entity) == -1L) return false
        cache[id] = to to text
        return true
    }

    /**
     * Applies a send outcome. Status only moves forward: pending -> sent -> delivered, or
     * pending -> failed. A late "sent" never replaces "delivered" or "failed".
     * @return true if the stored status changed.
     */
    suspend fun applySendResult(id: String, status: String, reason: String?): Boolean {
        val changed = when (status) {
            SendStatus.SENT -> dao.advanceStatus(id, MessageStatus.SENT, listOf(MessageStatus.PENDING))
            SendStatus.DELIVERED -> dao.advanceStatus(id, MessageStatus.DELIVERED, listOf(MessageStatus.PENDING, MessageStatus.SENT))
            SendStatus.FAILED -> dao.advanceStatus(id, MessageStatus.failed(reason), listOf(MessageStatus.PENDING))
            else -> 0
        } > 0
        if (changed && status != SendStatus.FAILED) mirror?.onStored()
        return changed
    }

    /** Outgoing messages nobody answered for: they are marked as not sent rather than left "sending". */
    suspend fun failUnansweredOutgoing(olderThanMillis: Long): Int =
        dao.failPendingOlderThan(now() - olderThanMillis, MessageStatus.failed(SendFailure.NO_ANSWER))

    /** Plaintext of a stored row, for the mirror. Null if erased or unreadable. */
    fun reveal(entity: MessageEntity): InboxMessage? = decrypt(entity)

    suspend fun markRead(id: String, read: Boolean = true) {
        dao.setRead(id, read)
        if (read) dao.find(id)?.providerId?.let { mirror?.onRead(it) }
    }

    suspend fun markAllRead() = dao.markAllRead()

    /** Erases the message content. The id is remembered so the message cannot be delivered again. */
    suspend fun delete(id: String) {
        dao.find(id)?.providerId?.let { mirror?.onDeleted(listOf(it)) }
        dao.tombstone(id, now())
        cache.remove(id)
    }

    suspend fun deleteAll() {
        mirror?.onDeleted(dao.allProviderIds().mapNotNull { it.providerId })
        dao.tombstoneAll(now())
        cache.clear()
    }

    suspend fun count(): Int = dao.count()

    /**
     * Applies the retention policy: erases messages whose original time is older than
     * [retentionDays] (0 = keep), and drops tombstones that are no longer needed for
     * de-duplication.
     * @return number of messages erased.
     */
    suspend fun applyRetention(retentionDays: Int): Int {
        val t = now()
        val erased = if (retentionDays > 0) {
            mirror?.onDeleted(dao.providerIdsOlderThan(t - retentionDays * DAY_MS).mapNotNull { it.providerId })
            dao.tombstoneOlderThan(t - retentionDays * DAY_MS, t)
        } else {
            0
        }
        dao.purgeTombstonesOlderThan(t - TOMBSTONE_DAYS * DAY_MS)
        if (erased > 0) cache.clear()
        // Catch up on anything not yet copied to the system SMS store, e.g. after the default SMS
        // role was granted or an earlier write was refused. A no-op when RelaySMS is not the default.
        mirror?.onStored()
        return erased
    }

    private fun decrypt(e: MessageEntity): InboxMessage? {
        val cached = cache[e.id]
        if (cached != null) return toModel(e, cached.first, cached.second)
        val addressEnc = e.addressEnc ?: return null
        val bodyEnc = e.bodyEnc ?: return null
        return try {
            val address = String(cipher.decrypt(addressEnc, addressContext(e.id)), Charsets.UTF_8)
            val body = String(cipher.decrypt(bodyEnc, bodyContext(e.id)), Charsets.UTF_8)
            cache[e.id] = address to body
            toModel(e, address, body)
        } catch (ex: Exception) {
            // Unreadable (local key lost). The row is skipped; it cannot be shown or recovered.
            null
        }
    }

    private fun toModel(e: MessageEntity, address: String, body: String) = InboxMessage(
        id = e.id, address = address, body = body, receivedAtMillis = e.receivedAt, storedAtMillis = e.storedAt,
        direction = e.direction, origin = e.origin, status = e.status, isRead = e.isRead, hasOtp = e.hasOtp,
        partCount = e.partCount, addressKey = e.addressKey,
    )

    companion object {
        private const val CACHE_SIZE = 3000
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** Longer than the relay can hold a message, so an erased message cannot come back. */
        const val TOMBSTONE_DAYS = 30L
        fun addressContext(id: String) = "message:$id:address"
        fun bodyContext(id: String) = "message:$id:body"

        fun addressKey(address: String): String =
            CryptoUtil.b64(CryptoUtil.sha256(("relaysms/address\n" + (SenderMatcher.normalize(address) ?: address)).toByteArray(Charsets.UTF_8))).take(22)

        fun isValid(sms: IncomingSms): Boolean =
            sms.sender.isNotBlank() && sms.sender.length <= SmsAssembler.MAX_SENDER_CHARS &&
                sms.body.isNotEmpty() && sms.body.length <= SmsAssembler.MAX_BODY_CHARS &&
                sms.receivedAtMillis > 0 && sms.partCount in 1..255
    }
}
