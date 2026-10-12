package io.github.ammarqaisar11a55.relaysms

import io.github.ammarqaisar11a55.relaysms.core.security.CryptoUtil
import io.github.ammarqaisar11a55.relaysms.pairing.DeviceIdentity
import io.github.ammarqaisar11a55.relaysms.pairing.PairingProtocol
import io.github.ammarqaisar11a55.relaysms.pairing.QrPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingProtocolTest {
    private fun key(seed: Int) = CryptoUtil.b64(ByteArray(32) { (it + seed).toByte() })
    private val companion = DeviceIdentity("11111111-1111-4111-8111-111111111111", key(1), key(2))
    private val source = DeviceIdentity("22222222-2222-4222-8222-222222222222", key(3), key(4))
    private val pairingId = "33333333-3333-4333-8333-333333333333"
    private val payload = QrPayload(1, "https://relay.example.com", pairingId, "A".repeat(22), key(9), companion.deviceId, companion.hpkePublicKey, companion.signingPublicKey)

    @Test fun `qr payload round trips and carries no private material`() {
        val text = PairingProtocol.encodeQr(payload)
        assertTrue(text.startsWith("RSMS1:"))
        assertTrue("QR text should stay scannable", text.length < 700)
        assertEquals(payload, PairingProtocol.decodeQr(text, false))
        assertEquals(payload, PairingProtocol.decodeQr("  $text\n", false))
        val json = String(CryptoUtil.unb64(text.removePrefix("RSMS1:"))!!)
        assertFalse(json.contains("token"))
        assertFalse(json.contains("private"))
    }

    @Test fun `scanned text is validated strictly`() {
        fun enc(p: QrPayload) = PairingProtocol.encodeQr(p)
        assertNull(PairingProtocol.decodeQr("", false))
        assertNull(PairingProtocol.decodeQr("https://example.com", false))
        assertNull(PairingProtocol.decodeQr("RSMS1:not-base64!", false))
        assertNull(PairingProtocol.decodeQr("RSMS1:" + CryptoUtil.b64("{}".toByteArray()), false))
        assertNull(PairingProtocol.decodeQr("RSMS2:" + enc(payload).removePrefix("RSMS1:"), false))
        assertNull(PairingProtocol.decodeQr(enc(payload.copy(version = 2)), false))
        assertNull(PairingProtocol.decodeQr(enc(payload.copy(serverUrl = "http://relay.example.com")), false))
        assertNull(PairingProtocol.decodeQr(enc(payload.copy(serverUrl = "https://relay.example.com/")), false))
        assertNull(PairingProtocol.decodeQr(enc(payload.copy(serverUrl = "http://10.0.2.2:8080")), false))
        assertEquals("http://10.0.2.2:8080", PairingProtocol.decodeQr(enc(payload.copy(serverUrl = "http://10.0.2.2:8080")), true)?.serverUrl)
        assertNull(PairingProtocol.decodeQr(enc(payload.copy(pairingId = "nope")), false))
        assertNull(PairingProtocol.decodeQr(enc(payload.copy(companionId = "nope")), false))
        assertNull(PairingProtocol.decodeQr(enc(payload.copy(redeemSecret = "short")), false))
        assertNull(PairingProtocol.decodeQr(enc(payload.copy(macKey = CryptoUtil.b64(ByteArray(16)))), false))
        assertNull(PairingProtocol.decodeQr(enc(payload.copy(hpkePublicKey = CryptoUtil.b64(ByteArray(31)))), false))
        assertNull(PairingProtocol.decodeQr(enc(payload.copy(signingPublicKey = "!!!")), false))
        assertNull(PairingProtocol.decodeQr("RSMS1:" + "A".repeat(2000), false))
    }

    @Test fun `safety code is six digits and depends on every key and id`() {
        val code = PairingProtocol.safetyCode(pairingId, companion, source)
        assertTrue(Regex("^\\d{3} \\d{3}$").matches(code))
        assertEquals(code, PairingProtocol.safetyCode(pairingId, companion, source))
        val variants = listOf(
            PairingProtocol.safetyCode("44444444-4444-4444-8444-444444444444", companion, source),
            PairingProtocol.safetyCode(pairingId, companion.copy(hpkePublicKey = key(50)), source),
            PairingProtocol.safetyCode(pairingId, companion.copy(signingPublicKey = key(51)), source),
            PairingProtocol.safetyCode(pairingId, companion, source.copy(hpkePublicKey = key(52))),
            PairingProtocol.safetyCode(pairingId, companion, source.copy(signingPublicKey = key(53))),
            PairingProtocol.safetyCode(pairingId, companion, source.copy(deviceId = "55555555-5555-4555-8555-555555555555")),
            PairingProtocol.safetyCode(pairingId, source, companion),
        )
        // Six digits can collide by chance; require that the inputs matter in nearly every case.
        assertTrue(variants.count { it != code } >= variants.size - 1)
    }

    @Test fun `source proof needs the qr secret and binds the exact keys`() {
        val mac = ByteArray(32) { 7 }
        val proof = PairingProtocol.sourceProof(mac, pairingId, companion, source)
        assertEquals(32, proof.size)
        assertTrue(proof.contentEquals(PairingProtocol.sourceProof(mac, pairingId, companion, source)))
        assertFalse(proof.contentEquals(PairingProtocol.sourceProof(ByteArray(32) { 8 }, pairingId, companion, source)))
        assertFalse(proof.contentEquals(PairingProtocol.sourceProof(mac, pairingId, companion, source.copy(hpkePublicKey = key(60)))))
        assertFalse(proof.contentEquals(PairingProtocol.sourceProof(mac, pairingId, companion, source.copy(signingPublicKey = key(61)))))
        assertFalse(proof.contentEquals(PairingProtocol.sourceProof(mac, pairingId, companion.copy(hpkePublicKey = key(62)), source)))
    }

    @Test fun `fingerprint is short readable and key dependent`() {
        val fp = PairingProtocol.fingerprint(companion)
        assertTrue(Regex("^[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}$").matches(fp))
        assertNotEquals(fp, PairingProtocol.fingerprint(source))
        assertNotEquals(fp, PairingProtocol.fingerprint(companion.copy(signingPublicKey = key(70))))
    }
}
