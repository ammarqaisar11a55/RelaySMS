package io.github.ammarqaisar11a55.relaysms.sms.send

/** Result of handing a message to the phone's SMS stack. */
sealed interface SmsSendOutcome {
    data object Sent : SmsSendOutcome
    data object Delivered : SmsSendOutcome
    data class Failed(val reason: String) : SmsSendOutcome
}

/** Sends one SMS on this phone and reports outcomes later through [SmsOutcomeListener]. */
interface SmsSender {
    fun hasPermission(): Boolean

    /** @return a [SendFailure] code when the message could not even be handed over, else null. */
    fun send(requestId: String, to: String, text: String): String?
}

/** What the source phone remembers about a request, so that a replay is never sent twice. */
interface SendRequestLog {
    /** @return false when this id was already recorded. Must be atomic and survive restarts. */
    suspend fun recordIfNew(requestId: String, nowMillis: Long): Boolean
    suspend fun setStatus(requestId: String, status: String, reason: String?)
    suspend fun find(requestId: String): Pair<String, String?>?
    suspend fun sentSince(sinceMillis: Long): Int
}

interface SendSettings {
    suspend fun enabled(): Boolean
    suspend fun perHourLimit(): Int
}

fun interface ResultReporter {
    /** Queues an end-to-end encrypted result for the companion. Idempotent on (request, status). */
    suspend fun report(result: SendResult)
}

/**
 * Source phone: acts on a verified request from the paired companion. By the time a request
 * reaches this class its signature has been checked against the paired device's key and it was
 * decrypted with this phone's key, so only the paired companion can have produced it.
 */
class SendRequestHandler(
    private val settings: SendSettings,
    private val sender: SmsSender,
    private val log: SendRequestLog,
    private val reporter: ResultReporter,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun handle(requestId: String, request: SendRequest) {
        if (!log.recordIfNew(requestId, now())) {
            // A replayed or re-delivered request: never send again. Repeat the recorded answer in
            // case the first one was lost.
            log.find(requestId)?.let { (status, reason) ->
                if (status == SendStatus.SENT || status == SendStatus.DELIVERED || status == SendStatus.FAILED) {
                    reporter.report(SendResult(requestId, status, reason, now()))
                }
            }
            return
        }
        val t = now()
        val refusal = RemoteSendPolicy.reject(
            request, t, settings.enabled(), sender.hasPermission(), log.sentSince(t - 3_600_000L), settings.perHourLimit(),
        ) ?: sender.send(requestId, RemoteSendPolicy.normalizeRecipient(request.to)!!, request.text)
        if (refusal != null) {
            fail(requestId, refusal)
        } else {
            log.setStatus(requestId, STATUS_SENDING, null)
        }
    }

    /** Called when the phone's SMS stack reports what happened. */
    suspend fun onOutcome(requestId: String, outcome: SmsSendOutcome) {
        val current = log.find(requestId)?.first ?: return
        when (outcome) {
            SmsSendOutcome.Sent -> if (current == STATUS_SENDING) {
                log.setStatus(requestId, SendStatus.SENT, null)
                reporter.report(SendResult(requestId, SendStatus.SENT, null, now()))
            }
            SmsSendOutcome.Delivered -> if (current == SendStatus.SENT || current == STATUS_SENDING) {
                log.setStatus(requestId, SendStatus.DELIVERED, null)
                reporter.report(SendResult(requestId, SendStatus.DELIVERED, null, now()))
            }
            is SmsSendOutcome.Failed -> if (current == STATUS_SENDING) fail(requestId, outcome.reason)
        }
    }

    private suspend fun fail(requestId: String, reason: String) {
        log.setStatus(requestId, SendStatus.FAILED, reason)
        reporter.report(SendResult(requestId, SendStatus.FAILED, reason, now()))
    }

    companion object {
        const val STATUS_RECEIVED = "received"
        const val STATUS_SENDING = "sending"
    }
}
