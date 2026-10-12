package io.github.ammarqaisar11a55.relaysms.transport

import io.github.ammarqaisar11a55.relaysms.core.common.SafeLog
import io.github.ammarqaisar11a55.relaysms.core.model.DeviceRole
import io.github.ammarqaisar11a55.relaysms.inbox.InboxRepository
import io.github.ammarqaisar11a55.relaysms.sms.IncomingSms
import io.github.ammarqaisar11a55.relaysms.sms.send.SendKinds
import io.github.ammarqaisar11a55.relaysms.sms.send.SendRequest
import io.github.ammarqaisar11a55.relaysms.sms.send.SendRequestHandler
import io.github.ammarqaisar11a55.relaysms.sms.send.SendResult

/**
 * Dispatches verified envelopes by kind. Each role accepts only the kinds meant for it; anything
 * else is dropped (and reported as handled, so it leaves the relay's queue).
 */
class EnvelopeRouter(
    private val role: suspend () -> DeviceRole?,
    private val inbox: InboxRepository,
    /** Source phone: acts on send requests. Null where remote sending is not wired (tests). */
    private val sendRequests: SendRequestHandler? = null,
) : EnvelopeHandler {
    override suspend fun handle(envelope: Envelope): Boolean {
        val currentRole = role()
        when {
            envelope.kind == EnvelopeKind.SMS && currentRole == DeviceRole.COMPANION -> {
                val sms = MessageCodec.decodeBody(IncomingSms.serializer(), envelope)
                if (sms == null || !InboxRepository.isValid(sms)) {
                    SafeLog.event("envelope_dropped_invalid_sms")
                    return true
                }
                // Throws on storage failure: the message then stays on the relay and is retried.
                val stored = inbox.storeIncoming(envelope.id, sms)
                SafeLog.event(if (stored) "message_stored" else "message_duplicate")
            }
            envelope.kind == SendKinds.REQUEST && currentRole == DeviceRole.SOURCE && sendRequests != null -> {
                val request = MessageCodec.decodeBody(SendRequest.serializer(), envelope)
                if (request == null) SafeLog.event("envelope_dropped_invalid_send_request") else sendRequests.handle(envelope.id, request)
            }
            envelope.kind == SendKinds.RESULT && currentRole == DeviceRole.COMPANION -> {
                val result = MessageCodec.decodeBody(SendResult.serializer(), envelope)
                if (result != null) inbox.applySendResult(result.requestId, result.status, result.reason)
                SafeLog.event("send_result_applied")
            }
            else -> SafeLog.event("envelope_dropped_unexpected_kind")
        }
        return true
    }
}
