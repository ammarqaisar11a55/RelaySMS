package io.github.ammarqaisar11a55.relaysms.sms.send

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.UUID

/** Envelope kinds used for sending an SMS through the paired source phone (docs/PROTOCOL.md §10). */
object SendKinds {
    /** Companion -> source: please send this SMS. The envelope id is the request id. */
    const val REQUEST = "send_request"

    /** Source -> companion: what happened to a request. */
    const val RESULT = "send_result"
}

@Serializable
data class SendRequest(
    val to: String,
    val text: String,
    val createdAtMillis: Long,
    /** The source must not send after this time. */
    val expiresAtMillis: Long,
)

object SendStatus {
    /** Handed to the mobile network by the source phone. Not proof that it was delivered. */
    const val SENT = "sent"

    /** The network confirmed delivery to the recipient's phone (only if the carrier reports it). */
    const val DELIVERED = "delivered"
    const val FAILED = "failed"
}

/** Why a request was not sent. Stable machine codes; the UI turns them into sentences. */
object SendFailure {
    const val DISABLED = "disabled"
    const val NO_PERMISSION = "no_permission"
    const val EXPIRED = "expired"
    const val INVALID_RECIPIENT = "invalid_recipient"
    const val INVALID_MESSAGE = "invalid_message"
    const val RATE_LIMITED = "rate_limited"
    const val NO_SERVICE = "no_service"
    const val RADIO_OFF = "radio_off"
    const val NO_SIM = "no_sim"
    /** The network or the SIM refused it: wrong number, no balance, barred, or another carrier error. */
    const val CARRIER_REJECTED = "carrier_rejected"
    /** The companion gave up waiting for an answer from the source phone. */
    const val NO_ANSWER = "no_answer"
    const val UNKNOWN = "unknown"
}

@Serializable
data class SendResult(val requestId: String, val status: String, val reason: String? = null, val atMillis: Long)

object SendIds {
    /** Stable id for a result, so that re-sending the same outcome is recognised as a duplicate. */
    fun resultId(requestId: String, status: String): String {
        val h = MessageDigest.getInstance("SHA-256").digest("relaysms-send-result-v1\n$requestId\n$status".toByteArray(Charsets.UTF_8))
        var msb = 0L
        var lsb = 0L
        for (i in 0 until 8) msb = (msb shl 8) or (h[i].toLong() and 0xff)
        for (i in 8 until 16) lsb = (lsb shl 8) or (h[i].toLong() and 0xff)
        msb = (msb and -0xf001L) or 0x8000L
        lsb = (lsb and 0x3fffffffffffffffL) or Long.MIN_VALUE
        return UUID(msb, lsb).toString()
    }
}

/** The source phone's rules for acting on a request from the paired companion. Pure. */
object RemoteSendPolicy {
    const val MAX_TEXT_CHARS = 1_000
    const val MAX_AGE_MS = 60L * 60 * 1000
    const val CLOCK_SKEW_MS = 5L * 60 * 1000
    const val DEFAULT_PER_HOUR = 20
    val PER_HOUR_CHOICES = listOf(5, 20, 60)

    /** Digits with an optional leading +; spaces, dashes, dots and brackets are tolerated and removed. */
    fun normalizeRecipient(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty() || t.length > 40) return null
        if (t.any { !(it.isDigit() || it in "+-() .") }) return null
        val plus = t.startsWith("+")
        if (t.count { it == '+' } > (if (plus) 1 else 0)) return null
        val digits = t.filter(Char::isDigit)
        if (digits.length !in 3..15) return null
        return (if (plus) "+" else "") + digits
    }

    /**
     * @return null when the request may be sent, otherwise the [SendFailure] code.
     * @param sentLastHour requests this phone actually sent in the last hour.
     */
    fun reject(
        request: SendRequest,
        nowMillis: Long,
        enabled: Boolean,
        hasSendPermission: Boolean,
        sentLastHour: Int,
        perHourLimit: Int,
    ): String? = when {
        !enabled -> SendFailure.DISABLED
        !hasSendPermission -> SendFailure.NO_PERMISSION
        request.expiresAtMillis <= nowMillis -> SendFailure.EXPIRED
        nowMillis - request.createdAtMillis > MAX_AGE_MS -> SendFailure.EXPIRED
        // A request "from the future" beyond normal clock drift is not trusted either.
        request.createdAtMillis - nowMillis > CLOCK_SKEW_MS -> SendFailure.EXPIRED
        request.expiresAtMillis - request.createdAtMillis > MAX_AGE_MS + CLOCK_SKEW_MS -> SendFailure.EXPIRED
        normalizeRecipient(request.to) == null -> SendFailure.INVALID_RECIPIENT
        request.text.isBlank() || request.text.length > MAX_TEXT_CHARS -> SendFailure.INVALID_MESSAGE
        sentLastHour >= perHourLimit -> SendFailure.RATE_LIMITED
        else -> null
    }

    fun describe(reason: String?): String = when (reason) {
        SendFailure.DISABLED -> "Sending through the source phone is turned off on that phone."
        SendFailure.NO_PERMISSION -> "The source phone has not been allowed to send SMS."
        SendFailure.EXPIRED -> "The source phone received the request too late, so it was not sent."
        SendFailure.INVALID_RECIPIENT -> "The number is not valid."
        SendFailure.INVALID_MESSAGE -> "The message is empty or too long."
        SendFailure.RATE_LIMITED -> "The source phone's hourly sending limit was reached."
        SendFailure.NO_SERVICE -> "The source phone had no mobile network."
        SendFailure.RADIO_OFF -> "The source phone's mobile radio was off (airplane mode)."
        SendFailure.NO_SIM -> "No usable SIM was found."
        SendFailure.CARRIER_REJECTED -> "The mobile network refused the message. Check the number and the SIM's balance."
        SendFailure.NO_ANSWER -> "The source phone did not answer in time. The message was not sent."
        else -> "The message could not be sent."
    }
}
