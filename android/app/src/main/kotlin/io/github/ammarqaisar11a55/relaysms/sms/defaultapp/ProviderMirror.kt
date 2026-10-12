package io.github.ammarqaisar11a55.relaysms.sms.defaultapp

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.Telephony
import io.github.ammarqaisar11a55.relaysms.core.common.SafeLog
import io.github.ammarqaisar11a55.relaysms.core.database.MessageDao
import io.github.ammarqaisar11a55.relaysms.core.database.MessageDirection
import io.github.ammarqaisar11a55.relaysms.core.database.MessageEntity
import io.github.ammarqaisar11a55.relaysms.core.database.MessageStatus
import io.github.ammarqaisar11a55.relaysms.inbox.InboxMessage
import io.github.ammarqaisar11a55.relaysms.inbox.MessageMirror
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The few operations RelaySMS performs on Android's shared SMS store. */
interface SmsProviderGateway {
    /** @return the new row id, or null if Android refused the write (not the default SMS app). */
    fun insert(address: String, body: String, dateMillis: Long, read: Boolean, type: Int): Long?
    fun markRead(providerId: Long)
    fun delete(providerId: Long)
}

class AndroidSmsProviderGateway(private val context: Context) : SmsProviderGateway {
    override fun insert(address: String, body: String, dateMillis: Long, read: Boolean, type: Int): Long? = try {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, dateMillis)
            put(Telephony.Sms.DATE_SENT, dateMillis)
            put(Telephony.Sms.READ, if (read) 1 else 0)
            put(Telephony.Sms.SEEN, if (read) 1 else 0)
            put(Telephony.Sms.TYPE, type)
        }
        context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)?.let(ContentUris::parseId)?.takeIf { it > 0 }
    } catch (e: RuntimeException) {
        SafeLog.failure("sms_provider_insert_refused", e)
        null
    }

    override fun markRead(providerId: Long) {
        try {
            val values = ContentValues().apply { put(Telephony.Sms.READ, 1); put(Telephony.Sms.SEEN, 1) }
            context.contentResolver.update(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, providerId), values, null, null)
        } catch (e: RuntimeException) {
            SafeLog.failure("sms_provider_update_refused", e)
        }
    }

    override fun delete(providerId: Long) {
        try {
            context.contentResolver.delete(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, providerId), null, null)
        } catch (e: RuntimeException) {
            SafeLog.failure("sms_provider_delete_refused", e)
        }
    }
}

/**
 * While RelaySMS is the default SMS app, copies each inbox message into Android's SMS store
 * exactly once. The Room row remembers the provider row id; a message that already has one is
 * never inserted again, whatever is re-delivered or re-synchronised. When RelaySMS is not the
 * default app this class does nothing, and RelaySMS's own inbox works as before.
 *
 * Note for users (shown before the role is requested): Android's SMS store is not encrypted by
 * RelaySMS and can be read by other apps that hold the SMS permission.
 */
class ProviderMirror(
    private val dao: MessageDao,
    private val gateway: SmsProviderGateway,
    private val isDefaultSmsApp: () -> Boolean,
    private val reveal: (MessageEntity) -> InboxMessage?,
) : MessageMirror {
    private val mutex = Mutex()

    /** Mirrors everything not yet mirrored. Called after each stored message and when the role is gained. */
    override suspend fun onStored() {
        if (!isDefaultSmsApp()) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                // Bounded batches; a refused write stops the pass instead of spinning.
                repeat(50) {
                    val batch = dao.withoutProviderId(BATCH).filter { shouldMirror(it) }
                    if (batch.isEmpty()) return@withLock
                    for (row in batch) {
                        val message = reveal(row) ?: continue
                        val type = when {
                            row.direction == MessageDirection.INCOMING -> Telephony.Sms.MESSAGE_TYPE_INBOX
                            else -> Telephony.Sms.MESSAGE_TYPE_SENT
                        }
                        val id = gateway.insert(message.address, message.body, row.receivedAt, row.isRead, type) ?: return@withLock
                        dao.setProviderId(row.id, id)
                    }
                    if (batch.size < BATCH) return@withLock
                }
            }
        }
    }

    /** Outgoing messages enter the system store only once they were really sent. */
    private fun shouldMirror(row: MessageEntity): Boolean =
        row.direction == MessageDirection.INCOMING || row.status == MessageStatus.SENT || row.status == MessageStatus.DELIVERED

    override suspend fun onRead(providerId: Long) {
        if (isDefaultSmsApp()) withContext(Dispatchers.IO) { gateway.markRead(providerId) }
    }

    override suspend fun onDeleted(providerIds: List<Long>) {
        if (providerIds.isEmpty() || !isDefaultSmsApp()) return
        withContext(Dispatchers.IO) { providerIds.forEach(gateway::delete) }
    }

    private companion object {
        const val BATCH = 100
    }
}
