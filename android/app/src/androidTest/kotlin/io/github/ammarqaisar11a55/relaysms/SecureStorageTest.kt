package io.github.ammarqaisar11a55.relaysms

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ammarqaisar11a55.relaysms.core.security.CryptoUtil
import io.github.ammarqaisar11a55.relaysms.core.security.DeviceKeyStore
import io.github.ammarqaisar11a55.relaysms.core.security.LocalCipherHolder
import io.github.ammarqaisar11a55.relaysms.pairing.Identity
import io.github.ammarqaisar11a55.relaysms.pairing.Peer
import io.github.ammarqaisar11a55.relaysms.pairing.SecureIdentityStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.security.GeneralSecurityException

/** Keystore-backed storage on a real Android runtime. */
@RunWith(AndroidJUnit4::class)
class SecureStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val container get() = (context as RelaySmsApp).container
    private val identity = Identity("https://relay.example.com", "00000000-0000-4000-8000-00000000000a", "id.secret-token-value-0123456789", "companion", 1)
    private val peer = Peer("00000000-0000-4000-8000-00000000000b", CryptoUtil.b64(ByteArray(32) { 1 }), CryptoUtil.b64(ByteArray(32) { 2 }), 2, "123 456")

    /** A second holder over the same files behaves like the next process start. */
    private fun afterRestart() = SecureIdentityStore(context) { LocalCipherHolder(context) }

    @Test fun identityAndPeerSurviveARestartAndAreEncryptedAtRest(): Unit = runBlocking {
        container.resetCoordinator.resetEverything()
        container.identityStore.setIdentity(identity)
        container.identityStore.setPeer(peer)

        val raw = context.getSharedPreferences("relaysms_identity", Context.MODE_PRIVATE).all.values.joinToString("|")
        assertFalse(raw.contains("secret-token-value"))
        assertFalse(raw.contains(peer.deviceId))
        assertFalse(raw.contains("relay.example.com"))

        val reopened = afterRestart()
        assertEquals(identity, reopened.identity.value)
        assertEquals(peer, reopened.peer.value)
        container.resetCoordinator.resetEverything()
    }

    @Test fun dataWrittenAfterAResetIsStillReadableAfterARestart(): Unit = runBlocking {
        // Regression: the reset deletes the local key. Later writes must use the new key, not a
        // cached handle to the deleted one, or they are lost on the next process start.
        container.localCipher.encrypt(byteArrayOf(1), "warm-up")
        container.resetCoordinator.resetEverything()
        container.identityStore.setIdentity(identity)
        container.identityStore.setPeer(peer)
        assertEquals(identity, afterRestart().identity.value)
        assertEquals(peer, afterRestart().peer.value)
        container.resetCoordinator.resetEverything()
        assertNull(afterRestart().identity.value)
        assertNull(afterRestart().peer.value)
    }

    @Test fun resetDestroysTheOldLocalKey(): Unit = runBlocking {
        val before = container.localCipher.encrypt("secret".toByteArray(), "ctx")
        assertArrayEquals("secret".toByteArray(), container.localCipher.decrypt(before, "ctx"))
        assertThrows(GeneralSecurityException::class.java) { container.localCipher.decrypt(before, "other") }
        container.resetCoordinator.resetEverything()
        assertThrows(GeneralSecurityException::class.java) { container.localCipher.decrypt(before, "ctx") }
    }

    @Test fun deviceKeysPersistUntilDeletedAndAreNotStoredInTheClear(): Unit = runBlocking {
        container.resetCoordinator.resetEverything()
        val first = container.deviceKeys.loadOrCreate()
        val again = DeviceKeyStore(context).loadOrCreate()
        assertArrayEquals(first.hpkePublicKey, again.hpkePublicKey)
        assertArrayEquals(first.signingPublicKey, again.signingPublicKey)
        // The same private key is behind both handles.
        val sealed = first.seal(first.hpkePublicKey, "x".toByteArray(), "i".toByteArray())
        assertArrayEquals("x".toByteArray(), again.open(sealed, "i".toByteArray()))

        container.deviceKeys.delete()
        val regenerated = DeviceKeyStore(context).loadOrCreate()
        assertFalse(first.hpkePublicKey.contentEquals(regenerated.hpkePublicKey))
        assertThrows(GeneralSecurityException::class.java) { regenerated.open(sealed, "i".toByteArray()) }
        container.resetCoordinator.resetEverything()
    }
}
