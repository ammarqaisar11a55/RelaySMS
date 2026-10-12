package io.github.ammarqaisar11a55.relaysms

import io.github.ammarqaisar11a55.relaysms.core.network.PendingMessage
import io.github.ammarqaisar11a55.relaysms.core.security.CryptoSetup
import io.github.ammarqaisar11a55.relaysms.core.security.CryptoUtil
import io.github.ammarqaisar11a55.relaysms.core.security.DeviceCrypto
import io.github.ammarqaisar11a55.relaysms.pairing.DeviceIdentity
import io.github.ammarqaisar11a55.relaysms.sms.IncomingSms
import io.github.ammarqaisar11a55.relaysms.transport.Backoff
import io.github.ammarqaisar11a55.relaysms.transport.Envelope
import io.github.ammarqaisar11a55.relaysms.transport.EnvelopeKind
import io.github.ammarqaisar11a55.relaysms.transport.MessageCodec
import io.github.ammarqaisar11a55.relaysms.transport.OpenResult
import io.github.ammarqaisar11a55.relaysms.transport.RejectReason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MessageCodecTest {
    private val sourceKeys = CryptoSetup.generateInMemory()
    private val companionKeys = CryptoSetup.generateInMemory()
    private fun identity(id: String, c: DeviceCrypto) = DeviceIdentity(id, CryptoUtil.b64(c.hpkePublicKey), CryptoUtil.b64(c.signingPublicKey))
    private val source = identity("11111111-1111-4111-8111-111111111111", sourceKeys)
    private val companion = identity("22222222-2222-4222-8222-222222222222", companionKeys)
    private val id = "33333333-3333-4333-8333-333333333333"
    private val sms = IncomingSms("TESTBANK", "Synthetic code 000000 — do not share. کوڈ", 1_700_000_000_123, 2, 1)
    private val envelope = Envelope(1, EnvelopeKind.SMS, id, 1_700_000_000_500, Json.encodeToJsonElement(sms))

    private fun sealed(e: Envelope = envelope) = MessageCodec.seal(sourceKeys, source.deviceId, companion, e)
        .let { PendingMessage(it.messageId, source.deviceId, it.ciphertext, it.signature, "2026-01-01T00:00:00.000Z") }

    private fun open(m: PendingMessage, by: DeviceCrypto = companionKeys, self: String = companion.deviceId, from: DeviceIdentity = source) =
        MessageCodec.open(by, self, from, m)

    private fun assertRejected(expected: RejectReason, result: OpenResult) = assertEquals(OpenResult.Rejected(expected), result)

    @Test fun `sealed sms opens to the identical sender body and timestamp`() {
        val m = sealed()
        val opened = (open(m) as OpenResult.Ok).envelope
        assertEquals(envelope, opened)
        assertEquals(sms, MessageCodec.decodeBody(IncomingSms.serializer(), opened))
    }

    @Test fun `ciphertext reveals no plaintext and differs every time`() {
        val a = sealed()
        val b = sealed()
        assertFalse(a.ciphertext == b.ciphertext)
        val raw = String(CryptoUtil.unb64(a.ciphertext)!!, Charsets.ISO_8859_1)
        for (secret in listOf("TESTBANK", "000000", "Synthetic")) assertFalse(raw.contains(secret))
    }

    @Test fun `only the paired recipient can open`() {
        val m = sealed()
        assertRejected(RejectReason.UNDECRYPTABLE, open(m, by = CryptoSetup.generateInMemory()))
        assertRejected(RejectReason.UNDECRYPTABLE, open(m, by = sourceKeys))
    }

    @Test fun `messages from anyone but the stored peer are rejected`() {
        val m = sealed()
        assertRejected(RejectReason.WRONG_SENDER, open(m.copy(senderId = "44444444-4444-4444-8444-444444444444")))
        // Same sender id but a different signing key than the one stored at pairing time.
        val impostor = identity(source.deviceId, CryptoSetup.generateInMemory())
        assertRejected(RejectReason.BAD_SIGNATURE, open(m, from = impostor))
        // Signed by an attacker who knows the companion's public key.
        val attacker = CryptoSetup.generateInMemory()
        val forged = MessageCodec.seal(attacker, source.deviceId, companion, envelope)
        assertRejected(RejectReason.BAD_SIGNATURE, open(PendingMessage(forged.messageId, source.deviceId, forged.ciphertext, forged.signature, "")))
    }

    @Test fun `tampering with any routed field is detected`() {
        val m = sealed()
        val ct = CryptoUtil.unb64(m.ciphertext)!!
        val flipped = CryptoUtil.b64(ct.clone().also { it[40] = (it[40].toInt() xor 1).toByte() })
        assertRejected(RejectReason.BAD_SIGNATURE, open(m.copy(ciphertext = flipped)))
        assertRejected(RejectReason.BAD_SIGNATURE, open(m.copy(messageId = "55555555-5555-4555-8555-555555555555")))
        assertRejected(RejectReason.BAD_SIGNATURE, open(m, self = "66666666-6666-4666-8666-666666666666"))
        assertRejected(RejectReason.BAD_SIGNATURE, open(m.copy(signature = CryptoUtil.b64(ByteArray(64)))))
        assertRejected(RejectReason.BAD_ENCODING, open(m.copy(signature = "short")))
        assertRejected(RejectReason.BAD_ENCODING, open(m.copy(ciphertext = "not base64 !!")))
        assertRejected(RejectReason.BAD_ENCODING, open(m.copy(ciphertext = "")))
        assertRejected(RejectReason.BAD_ENCODING, open(m.copy(messageId = "nope")))
        assertRejected(RejectReason.BAD_ENCODING, open(m.copy(ciphertext = CryptoUtil.b64(ByteArray(MessageCodec.MAX_CIPHERTEXT_BYTES + 1)))))
    }

    @Test fun `a valid ciphertext cannot be replayed under another id`() {
        // The sender itself re-signs an old ciphertext under a new id: the signature is valid,
        // but the id is bound into the encryption context, so decryption fails.
        val original = MessageCodec.seal(sourceKeys, source.deviceId, companion, envelope)
        val newId = "77777777-7777-4777-8777-777777777777"
        val ct = CryptoUtil.unb64(original.ciphertext)!!
        val sig = sourceKeys.sign(MessageCodec.signingInput(source.deviceId, companion.deviceId, newId, ct))
        assertRejected(RejectReason.UNDECRYPTABLE, open(PendingMessage(newId, source.deviceId, original.ciphertext, CryptoUtil.b64(sig), "")))
    }

    @Test fun `envelope contents are validated after decryption`() {
        assertRejected(RejectReason.UNSUPPORTED_VERSION, open(sealed(envelope.copy(v = 2))))
        assertRejected(RejectReason.MALFORMED, open(sealed(envelope.copy(body = JsonPrimitive("text")))))
        assertRejected(RejectReason.MALFORMED, open(sealed(envelope.copy(kind = ""))))
        // Inner id differs from the routed id: build it by sealing under one id and claiming another.
        val inner = envelope.copy(id = "88888888-8888-4888-8888-888888888888")
        val plaintext = Json.encodeToString(Envelope.serializer(), inner).toByteArray()
        val ct = sourceKeys.seal(companionKeys.hpkePublicKey, plaintext, MessageCodec.hpkeInfo(source.deviceId, companion.deviceId, id))
        val sig = sourceKeys.sign(MessageCodec.signingInput(source.deviceId, companion.deviceId, id, ct))
        assertRejected(RejectReason.ID_MISMATCH, open(PendingMessage(id, source.deviceId, CryptoUtil.b64(ct), CryptoUtil.b64(sig), "")))
        // Not JSON at all.
        val junk = sourceKeys.seal(companionKeys.hpkePublicKey, "garbage".toByteArray(), MessageCodec.hpkeInfo(source.deviceId, companion.deviceId, id))
        val junkSig = sourceKeys.sign(MessageCodec.signingInput(source.deviceId, companion.deviceId, id, junk))
        assertRejected(RejectReason.MALFORMED, open(PendingMessage(id, source.deviceId, CryptoUtil.b64(junk), CryptoUtil.b64(junkSig), "")))
    }

    @Test fun `largest supported sms fits under the ciphertext limit`() {
        val big = IncomingSms("TESTBANK", "ا".repeat(17_000), 1, 255)
        val m = sealed(envelope.copy(body = Json.encodeToJsonElement(big)))
        assertTrue(CryptoUtil.unb64(m.ciphertext)!!.size < MessageCodec.MAX_CIPHERTEXT_BYTES)
        assertTrue(open(m) is OpenResult.Ok)
    }

    @Test fun `backoff grows exponentially with jitter and is capped`() {
        val b = Backoff(baseMs = 1000, maxMs = 60_000, random = Random(1))
        val delays = List(12) { b.next() }
        assertTrue(delays[0] in 750..1250)
        assertTrue(delays[1] in 1500..2500)
        assertTrue(delays[3] in 6000..10_000)
        assertTrue(delays.all { it <= 75_000 })
        assertTrue(delays.last() >= 45_000)
        b.reset()
        assertTrue(b.next() in 750..1250)
        // Jitter: two devices do not retry in lockstep.
        val other = Backoff(baseMs = 1000, maxMs = 60_000, random = Random(2))
        assertFalse(List(12) { other.next() } == delays)
    }
}
