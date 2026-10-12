package io.github.ammarqaisar11a55.relaysms.pairing

import android.content.Context
import io.github.ammarqaisar11a55.relaysms.core.security.CryptoUtil
import io.github.ammarqaisar11a55.relaysms.core.security.FieldCipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** This device's registration with one relay. [token] is a bearer credential. */
@Serializable
data class Identity(val serverUrl: String, val deviceId: String, val token: String, val role: String, val registeredAtMillis: Long)

/** The device this one is paired with, as verified during pairing. */
@Serializable
data class Peer(
    val deviceId: String,
    val hpkePublicKey: String,
    val signingPublicKey: String,
    val linkedAtMillis: Long,
    val safetyCode: String,
) {
    val identity: DeviceIdentity get() = DeviceIdentity(deviceId, hpkePublicKey, signingPublicKey)
}

interface IdentityStore {
    val identity: StateFlow<Identity?>
    val peer: StateFlow<Peer?>
    suspend fun setIdentity(value: Identity?)
    suspend fun setPeer(value: Peer?)
}

class InMemoryIdentityStore : IdentityStore {
    private val _identity = MutableStateFlow<Identity?>(null)
    private val _peer = MutableStateFlow<Peer?>(null)
    override val identity = _identity.asStateFlow()
    override val peer = _peer.asStateFlow()
    override suspend fun setIdentity(value: Identity?) { _identity.value = value }
    override suspend fun setPeer(value: Peer?) { _peer.value = value }
}

/**
 * Stores the device token and peer keys encrypted with the Keystore-backed local cipher, in a
 * private preferences file that is excluded from backup. Nothing is kept in plain text.
 */
class SecureIdentityStore(context: Context, private val cipher: () -> FieldCipher) : IdentityStore {
    private val prefs = context.applicationContext.getSharedPreferences("relaysms_identity", Context.MODE_PRIVATE)
    private val _identity = MutableStateFlow(read(KEY_IDENTITY, Identity.serializer()))
    private val _peer = MutableStateFlow(read(KEY_PEER, Peer.serializer()))

    override val identity: StateFlow<Identity?> = _identity.asStateFlow()
    override val peer: StateFlow<Peer?> = _peer.asStateFlow()

    override suspend fun setIdentity(value: Identity?) {
        write(KEY_IDENTITY, Identity.serializer(), value)
        _identity.value = value
    }

    override suspend fun setPeer(value: Peer?) {
        write(KEY_PEER, Peer.serializer(), value)
        _peer.value = value
    }

    private fun <T> read(key: String, serializer: KSerializer<T>): T? {
        val stored = prefs.getString(key, null) ?: return null
        return try {
            val plain = cipher().decrypt(CryptoUtil.unb64(stored) ?: return null, "identity:$key")
            Json.decodeFromString(serializer, String(plain, Charsets.UTF_8))
        } catch (e: Exception) {
            // Unreadable after key loss: treat as absent. A new pairing is required.
            null
        }
    }

    private fun <T> write(key: String, serializer: KSerializer<T>, value: T?) {
        val editor = prefs.edit()
        if (value == null) {
            editor.remove(key)
        } else {
            val plain = Json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8)
            editor.putString(key, CryptoUtil.b64(cipher().encrypt(plain, "identity:$key")))
        }
        editor.commit()
    }

    private companion object {
        const val KEY_IDENTITY = "identity"
        const val KEY_PEER = "peer"
    }
}
