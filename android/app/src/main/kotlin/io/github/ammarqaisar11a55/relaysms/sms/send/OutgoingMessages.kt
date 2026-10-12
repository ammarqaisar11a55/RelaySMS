package io.github.ammarqaisar11a55.relaysms.sms.send

import io.github.ammarqaisar11a55.relaysms.core.database.MessageOrigin
import io.github.ammarqaisar11a55.relaysms.core.database.OutboxEntity
import io.github.ammarqaisar11a55.relaysms.core.database.OutboxState
import io.github.ammarqaisar11a55.relaysms.core.database.SendRequestDao
import io.github.ammarqaisar11a55.relaysms.core.database.SendRequestEntity
import io.github.ammarqaisar11a55.relaysms.core.security.FieldCipher
import io.github.ammarqaisar11a55.relaysms.inbox.InboxRepository
import io.github.ammarqaisar11a55.relaysms.sms.DeliveryScheduler
import io.github.ammarqaisar11a55.relaysms.sms.IncomingSmsProcessor
import io.github.ammarqaisar11a55.relaysms.sms.OutboxWriter
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

enum class SendRoute {
    /** Through the paired source phone's SIM, end-to-end encrypted on the way there. */
    VIA_SOURCE,

    /** Through this phone's own SIM. */
    THIS_PHONE,
}

sealed interface SubmitResult {
    data class Queued(val messageId: String) : SubmitResult
    data class Refused(val reason: String) : SubmitResult
}

/** Puts an encrypted item of [kind] into the delivery queue for the paired device. */
class EnvelopeQueue(private val outbox: OutboxWriter, private val cipher: FieldCipher, private val scheduler: DeliveryScheduler, private val now: () -> Long) {
    suspend fun <T> enqueue(id: String, kind: String, serializer: KSerializer<T>, body: T) {
        val payload = cipher.encrypt(Json.encodeToString(serializer, body).toByteArray(Charsets.UTF_8), IncomingSmsProcessor.outboxContext(id))
        outbox.insertIfAbsent(OutboxEntity(id = id, kind = kind, payload = payload, state = OutboxState.PENDING, createdAt = now(), updatedAt = now()))
        scheduler.schedule()
    }
}

/** Companion phone: the user's own outgoing messages. Nothing is sent without an explicit call. */
class OutgoingMessages(
    private val inbox: InboxRepository,
    private val queue: EnvelopeQueue,
    private val localSender: SmsSender,
    private val isPaired: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun send(route: SendRoute, toRaw: String, text: String): SubmitResult {
        val to = RemoteSendPolicy.normalizeRecipient(toRaw) ?: return SubmitResult.Refused(SendFailure.INVALID_RECIPIENT)
        if (text.isBlank() || text.length > RemoteSendPolicy.MAX_TEXT_CHARS) return SubmitResult.Refused(SendFailure.INVALID_MESSAGE)
        val id = UUID.randomUUID().toString()
        return when (route) {
            SendRoute.VIA_SOURCE -> {
                if (!isPaired()) return SubmitResult.Refused(SendFailure.DISABLED)
                inbox.storeOutgoing(id, to, text, MessageOrigin.RELAY)
                val t = now()
                queue.enqueue(id, SendKinds.REQUEST, SendRequest.serializer(), SendRequest(to, text, t, t + REQUEST_VALIDITY_MS))
                SubmitResult.Queued(id)
            }
            SendRoute.THIS_PHONE -> {
                if (!localSender.hasPermission()) return SubmitResult.Refused(SendFailure.NO_PERMISSION)
                inbox.storeOutgoing(id, to, text, MessageOrigin.SIM)
                localSender.send(id, to, text)?.let { inbox.applySendResult(id, SendStatus.FAILED, it) }
                SubmitResult.Queued(id)
            }
        }
    }

    /** A message the source never answered for is shown as not sent, not as "sending" for ever. */
    suspend fun sweep() = inbox.failUnansweredOutgoing(REQUEST_VALIDITY_MS + GRACE_MS)

    companion object {
        /** The source phone must send within this time or not at all. */
        const val REQUEST_VALIDITY_MS = 60L * 60 * 1000
        private const val GRACE_MS = 10L * 60 * 1000
    }
}

/** Room-backed log of requests on the source phone. */
class RoomSendRequestLog(private val dao: SendRequestDao) : SendRequestLog {
    override suspend fun recordIfNew(requestId: String, nowMillis: Long): Boolean =
        dao.insertIfAbsent(SendRequestEntity(requestId, nowMillis, SendRequestHandler.STATUS_RECEIVED, null)) != -1L
    override suspend fun setStatus(requestId: String, status: String, reason: String?) = dao.setStatus(requestId, status, reason)
    override suspend fun find(requestId: String): Pair<String, String?>? = dao.find(requestId)?.let { it.status to it.reason }
    override suspend fun sentSince(sinceMillis: Long): Int = dao.sentSince(sinceMillis)
}
