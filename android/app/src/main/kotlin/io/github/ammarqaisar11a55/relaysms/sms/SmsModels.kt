package io.github.ammarqaisar11a55.relaysms.sms

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.UUID

/** One PDU of an incoming SMS as delivered by the platform. */
data class SmsPart(val sender: String?, val body: String?, val timestampMillis: Long)

/** A complete incoming message after multipart reassembly. */
@Serializable
data class IncomingSms(
    val sender: String,
    val body: String,
    val receivedAtMillis: Long,
    val partCount: Int,
    val subscriptionId: Int = -1,
)

object SmsAssembler {
    /** Far above any real concatenated SMS (255 parts x 153 chars); guards against garbage input. */
    const val MAX_BODY_CHARS = 40_000
    const val MAX_SENDER_CHARS = 64

    /**
     * Joins the parts of a single SMS_RECEIVED broadcast. The platform delivers all parts of a
     * concatenated message together and in order, so the bodies are appended as given.
     *
     * @return null when the broadcast is unusable (no parts, no sender, inconsistent senders,
     * empty or oversized content).
     */
    fun assemble(parts: List<SmsPart>, subscriptionId: Int = -1): IncomingSms? {
        if (parts.isEmpty()) return null
        val sender = parts.first().sender?.trim().orEmpty()
        if (sender.isEmpty() || sender.length > MAX_SENDER_CHARS) return null
        if (parts.any { it.sender?.trim() != sender }) return null
        val body = buildString { for (p in parts) append(p.body.orEmpty()) }
        if (body.isEmpty() || body.length > MAX_BODY_CHARS) return null
        val timestamp = parts.first().timestampMillis
        if (timestamp <= 0L) return null
        return IncomingSms(sender, body, timestamp, parts.size, subscriptionId)
    }
}

object EventId {
    /**
     * Stable identifier for one SMS event. The same message delivered twice (duplicate broadcast,
     * retry after a crash) yields the same id, which is what makes forwarding idempotent.
     * The id is a hash, so it reveals nothing about the content to the relay.
     */
    fun of(sms: IncomingSms): String {
        val md = MessageDigest.getInstance("SHA-256")
        fun field(s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            md.update(b.size.toString().toByteArray(Charsets.US_ASCII))
            md.update(':'.code.toByte())
            md.update(b)
        }
        field("relaysms-sms-event-v1")
        field(sms.sender)
        field(sms.receivedAtMillis.toString())
        field(sms.subscriptionId.toString())
        field(sms.body)
        val h = md.digest()
        var msb = 0L
        var lsb = 0L
        for (i in 0 until 8) msb = (msb shl 8) or (h[i].toLong() and 0xff)
        for (i in 8 until 16) lsb = (lsb shl 8) or (h[i].toLong() and 0xff)
        // Stamp as a version 8 (custom) / RFC 4122 variant UUID.
        msb = (msb and -0xf001L) or 0x8000L
        lsb = (lsb and 0x3fffffffffffffffL) or Long.MIN_VALUE
        return UUID(msb, lsb).toString()
    }
}
