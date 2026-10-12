package io.github.ammarqaisar11a55.relaysms.pairing

import io.github.ammarqaisar11a55.relaysms.core.network.ServerUrl
import io.github.ammarqaisar11a55.relaysms.core.security.CryptoSetup
import io.github.ammarqaisar11a55.relaysms.core.security.CryptoUtil
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Public identity of one device as used in pairing transcripts. Keys are base64url, 32 bytes each. */
data class DeviceIdentity(val deviceId: String, val hpkePublicKey: String, val signingPublicKey: String)

/**
 * Content of the pairing QR code shown by the companion. It carries only public keys and
 * short-lived, single-use secrets. It never contains a private key or a device token.
 */
@Serializable
data class QrPayload(
    @SerialName("v") val version: Int,
    @SerialName("u") val serverUrl: String,
    @SerialName("p") val pairingId: String,
    /** Single-use secret presented to the relay to redeem the session. */
    @SerialName("s") val redeemSecret: String,
    /** MAC key shared only by the two phones. Never sent to the relay. */
    @SerialName("m") val macKey: String,
    @SerialName("d") val companionId: String,
    @SerialName("h") val hpkePublicKey: String,
    @SerialName("k") val signingPublicKey: String,
) {
    val companion: DeviceIdentity get() = DeviceIdentity(companionId, hpkePublicKey, signingPublicKey)
}

object PairingProtocol {
    const val VERSION = 1
    private const val PREFIX = "RSMS1:"
    private const val MAX_QR_CHARS = 1200
    private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val JSON = Json { ignoreUnknownKeys = false }

    fun encodeQr(payload: QrPayload): String =
        PREFIX + CryptoUtil.b64(JSON.encodeToString(QrPayload.serializer(), payload).toByteArray(Charsets.UTF_8))

    /**
     * Parses and strictly validates scanned text. Anything unexpected yields null: the scanner is
     * an untrusted input channel.
     */
    fun decodeQr(text: String, allowCleartextLocal: Boolean): QrPayload? {
        val t = text.trim()
        if (!t.startsWith(PREFIX) || t.length > MAX_QR_CHARS) return null
        val bytes = CryptoUtil.unb64(t.removePrefix(PREFIX)) ?: return null
        val p = runCatching { JSON.decodeFromString(QrPayload.serializer(), String(bytes, Charsets.UTF_8)) }.getOrNull() ?: return null
        if (p.version != VERSION) return null
        val url = ServerUrl.parse(p.serverUrl, allowCleartextLocal) as? ServerUrl.Result.Valid ?: return null
        if (url.url != p.serverUrl) return null
        if (!UUID.matches(p.pairingId) || !UUID.matches(p.companionId)) return null
        if (p.redeemSecret.length !in 16..64 || CryptoUtil.unb64(p.redeemSecret) == null) return null
        if (CryptoUtil.unb64(p.macKey)?.size != 32) return null
        if (CryptoUtil.unb64(p.hpkePublicKey)?.size != CryptoSetup.PUBLIC_KEY_BYTES) return null
        if (CryptoUtil.unb64(p.signingPublicKey)?.size != CryptoSetup.PUBLIC_KEY_BYTES) return null
        return p
    }

    private fun transcript(label: String, pairingId: String, companion: DeviceIdentity, source: DeviceIdentity): ByteArray =
        listOf(
            label, pairingId,
            companion.deviceId, companion.hpkePublicKey, companion.signingPublicKey,
            source.deviceId, source.hpkePublicKey, source.signingPublicKey,
        ).joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8)

    /**
     * Proof that the source's keys were chosen by whoever scanned the QR code. The relay carries
     * it to the companion but cannot compute it, because it never sees [macKey].
     */
    fun sourceProof(macKey: ByteArray, pairingId: String, companion: DeviceIdentity, source: DeviceIdentity): ByteArray =
        CryptoUtil.hmacSha256(macKey, transcript("relaysms/v1/pairing-proof", pairingId, companion, source))

    /**
     * Six-digit code both phones display for the user to compare. It is derived from every public
     * value of the session, so the two screens agree only if both phones see the same keys.
     */
    fun safetyCode(pairingId: String, companion: DeviceIdentity, source: DeviceIdentity): String {
        val h = CryptoUtil.sha256(transcript("relaysms/v1/safety-code", pairingId, companion, source))
        var n = 0L
        for (i in 0 until 7) n = (n shl 8) or (h[i].toLong() and 0xff)
        val digits = (n % 1_000_000).toString().padStart(6, '0')
        return digits.substring(0, 3) + " " + digits.substring(3)
    }

    /** Short, human-readable identifier of a device's keys, e.g. "3F2A-91BC-07DE". */
    fun fingerprint(device: DeviceIdentity): String {
        val h = CryptoUtil.sha256("relaysms/v1/fingerprint\n${device.deviceId}\n${device.hpkePublicKey}\n${device.signingPublicKey}\n".toByteArray())
        return h.take(6).joinToString("") { "%02X".format(it) }.chunked(4).joinToString("-")
    }
}
