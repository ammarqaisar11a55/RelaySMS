package io.github.ammarqaisar11a55.relaysms.transport

import io.github.ammarqaisar11a55.relaysms.core.network.PendingMessage
import io.github.ammarqaisar11a55.relaysms.core.network.SubmitMessageRequest
import io.github.ammarqaisar11a55.relaysms.core.security.CryptoSetup
import io.github.ammarqaisar11a55.relaysms.core.security.CryptoUtil
import io.github.ammarqaisar11a55.relaysms.core.security.DeviceCrypto
import io.github.ammarqaisar11a55.relaysms.pairing.DeviceIdentity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The plaintext that is end-to-end encrypted between two paired devices (docs/PROTOCOL.md §5). */
@Serializable
data class Envelope(
    val v: Int,
    val kind: String,
    /** Must equal the message id the relay routed this ciphertext under. */
    val id: String,
    val sentAt: Long,
    val body: JsonElement,
)

object EnvelopeKind {
    /** Source -> companion: a forwarded incoming SMS. Body: [io.github.ammarqaisar11a55.relaysms.sms.IncomingSms]. */
    const val SMS = "sms"
}

enum class RejectReason { WRONG_SENDER, BAD_ENCODING, BAD_SIGNATURE, UNDECRYPTABLE, MALFORMED, UNSUPPORTED_VERSION, ID_MISMATCH }

sealed interface OpenResult {
    data class Ok(val envelope: Envelope) : OpenResult
    data class Rejected(val reason: RejectReason) : OpenResult
}

/** Seals and opens message envelopes. Pure functions over [DeviceCrypto]. */
object MessageCodec {
    const val VERSION = 1

    /** Larger than any envelope the app produces; bounds work done on hostile input. */
    const val MAX_CIPHERTEXT_BYTES = 131_072

    private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val JSON = Json { ignoreUnknownKeys = false; isLenient = false }

    fun hpkeInfo(senderId: String, recipientId: String, messageId: String): ByteArray =
        "relaysms/v1/hpke\n$senderId\n$recipientId\n$messageId".toByteArray(Charsets.UTF_8)

    fun signingInput(senderId: String, recipientId: String, messageId: String, ciphertext: ByteArray): ByteArray =
        "relaysms/v1/message\n$senderId\n$recipientId\n$messageId\n".toByteArray(Charsets.UTF_8) + ciphertext

    /** Encrypts [envelope] for [recipient] and signs the result as [senderId]. */
    fun seal(crypto: DeviceCrypto, senderId: String, recipient: DeviceIdentity, envelope: Envelope): SubmitMessageRequest {
        val recipientKey = requireNotNull(CryptoUtil.unb64(recipient.hpkePublicKey)) { "bad recipient key" }
        val plaintext = JSON.encodeToString(Envelope.serializer(), envelope).toByteArray(Charsets.UTF_8)
        val ciphertext = crypto.seal(recipientKey, plaintext, hpkeInfo(senderId, recipient.deviceId, envelope.id))
        val signature = crypto.sign(signingInput(senderId, recipient.deviceId, envelope.id, ciphertext))
        return SubmitMessageRequest(envelope.id, CryptoUtil.b64(ciphertext), CryptoUtil.b64(signature))
    }

    /**
     * Verifies and decrypts a message fetched from the relay. Everything in [message] is
     * untrusted: the signature is checked against the locally stored key of [sender], never a
     * key supplied by the relay.
     */
    fun open(crypto: DeviceCrypto, selfId: String, sender: DeviceIdentity, message: PendingMessage): OpenResult {
        if (message.senderId != sender.deviceId) return OpenResult.Rejected(RejectReason.WRONG_SENDER)
        if (!UUID.matches(message.messageId)) return OpenResult.Rejected(RejectReason.BAD_ENCODING)
        val ciphertext = CryptoUtil.unb64(message.ciphertext)
        val signature = CryptoUtil.unb64(message.signature)
        val signerKey = CryptoUtil.unb64(sender.signingPublicKey)
        if (ciphertext == null || signature == null || signerKey == null ||
            ciphertext.isEmpty() || ciphertext.size > MAX_CIPHERTEXT_BYTES || signature.size != CryptoSetup.SIGNATURE_BYTES
        ) {
            return OpenResult.Rejected(RejectReason.BAD_ENCODING)
        }
        if (!crypto.verify(signerKey, signingInput(sender.deviceId, selfId, message.messageId, ciphertext), signature)) {
            return OpenResult.Rejected(RejectReason.BAD_SIGNATURE)
        }
        val plaintext = try {
            crypto.open(ciphertext, hpkeInfo(sender.deviceId, selfId, message.messageId))
        } catch (e: Exception) {
            return OpenResult.Rejected(RejectReason.UNDECRYPTABLE)
        }
        val envelope = try {
            JSON.decodeFromString(Envelope.serializer(), String(plaintext, Charsets.UTF_8))
        } catch (e: Exception) {
            return OpenResult.Rejected(RejectReason.MALFORMED)
        }
        if (envelope.v != VERSION) return OpenResult.Rejected(RejectReason.UNSUPPORTED_VERSION)
        if (envelope.id != message.messageId) return OpenResult.Rejected(RejectReason.ID_MISMATCH)
        if (envelope.body !is JsonObject || envelope.kind.isEmpty() || envelope.kind.length > 32) return OpenResult.Rejected(RejectReason.MALFORMED)
        return OpenResult.Ok(envelope)
    }

    fun <T> decodeBody(serializer: kotlinx.serialization.KSerializer<T>, envelope: Envelope): T? =
        runCatching { JSON.decodeFromJsonElement(serializer, envelope.body) }.getOrNull()

    fun parseBody(json: String): JsonElement? = runCatching { JSON.parseToJsonElement(json) }.getOrNull()
}
