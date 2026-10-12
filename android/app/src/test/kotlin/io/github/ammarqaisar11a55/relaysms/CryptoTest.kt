package io.github.ammarqaisar11a55.relaysms

import io.github.ammarqaisar11a55.relaysms.core.security.CryptoSetup
import io.github.ammarqaisar11a55.relaysms.core.security.CryptoUtil
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException

class CryptoTest {
    private val alice = CryptoSetup.generateInMemory()
    private val bob = CryptoSetup.generateInMemory()
    private val info = "relaysms/v1/test".toByteArray()

    @Test fun `public keys are raw 32 byte values`() {
        assertEquals(32, alice.hpkePublicKey.size)
        assertEquals(32, alice.signingPublicKey.size)
        assertFalse(alice.hpkePublicKey.contentEquals(bob.hpkePublicKey))
    }

    @Test fun `only the intended recipient can open a sealed message`() {
        val plaintext = "Synthetic code 000000".toByteArray()
        val sealed = alice.seal(bob.hpkePublicKey, plaintext, info)
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("000000"))
        assertArrayEquals(plaintext, bob.open(sealed, info))
        assertThrows(GeneralSecurityException::class.java) { alice.open(sealed, info) }
        assertThrows(GeneralSecurityException::class.java) { CryptoSetup.generateInMemory().open(sealed, info) }
    }

    @Test fun `sealing is randomised`() {
        val p = "same".toByteArray()
        assertFalse(alice.seal(bob.hpkePublicKey, p, info).contentEquals(alice.seal(bob.hpkePublicKey, p, info)))
    }

    @Test fun `tampering and context changes are detected`() {
        val sealed = alice.seal(bob.hpkePublicKey, "payload".toByteArray(), info)
        assertThrows(GeneralSecurityException::class.java) { bob.open(sealed, "other context".toByteArray()) }
        for (i in listOf(0, 31, 32, sealed.size - 1)) {
            val bad = sealed.clone().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertThrows(GeneralSecurityException::class.java) { bob.open(bad, info) }
        }
        assertThrows(GeneralSecurityException::class.java) { bob.open(sealed.copyOf(sealed.size - 1), info) }
        assertThrows(GeneralSecurityException::class.java) { bob.open(ByteArray(0), info) }
    }

    @Test fun `signatures verify only for the signer and the exact data`() {
        val data = "transcript".toByteArray()
        val sig = alice.sign(data)
        assertEquals(64, sig.size)
        assertTrue(bob.verify(alice.signingPublicKey, data, sig))
        assertFalse(bob.verify(bob.signingPublicKey, data, sig))
        assertFalse(bob.verify(alice.signingPublicKey, "transcripT".toByteArray(), sig))
        assertFalse(bob.verify(alice.signingPublicKey, data, sig.clone().also { it[5] = (it[5].toInt() xor 1).toByte() }))
        assertFalse(bob.verify(alice.signingPublicKey, data, ByteArray(10)))
        assertFalse(bob.verify(ByteArray(5), data, sig))
    }

    @Test fun `base64url helper rejects foreign alphabets`() {
        val bytes = ByteArray(40) { (it * 7).toByte() }
        assertArrayEquals(bytes, CryptoUtil.unb64(CryptoUtil.b64(bytes)))
        assertFalse(CryptoUtil.b64(bytes).contains('='))
        assertNull(CryptoUtil.unb64("ab+/"))
        assertNull(CryptoUtil.unb64("has space"))
        assertNull(CryptoUtil.unb64("pad=="))
    }
}
