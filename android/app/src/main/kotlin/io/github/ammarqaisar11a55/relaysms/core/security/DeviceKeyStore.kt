package io.github.ammarqaisar11a55.relaysms.core.security

import android.content.Context
import com.google.crypto.tink.KeyTemplate
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.Parameters
import com.google.crypto.tink.integration.android.AndroidKeysetManager

/**
 * Creates and loads this device's long-term private keys. Each keyset is stored in a private
 * preferences file encrypted ("wrapped") by an AES-256-GCM key held in Android Keystore, so the
 * private key material is never written to disk in the clear and cannot be exported by the app.
 */
class DeviceKeyStore(context: Context) {
    private val appContext = context.applicationContext

    @Volatile private var cached: DeviceCrypto? = null

    fun exists(): Boolean = prefs().contains(HPKE_KEYSET) && prefs().contains(SIGNING_KEYSET)

    /**
     * Loads the device keys, creating them on first use. If existing keys cannot be unwrapped the
     * call fails; the caller must then reset the pairing, because a new identity is required.
     */
    @Synchronized
    fun loadOrCreate(): DeviceCrypto {
        cached?.let { return it }
        CryptoSetup.register()
        return TinkDeviceCrypto(handle(HPKE_KEYSET, CryptoSetup.HPKE_PARAMETERS), handle(SIGNING_KEYSET, CryptoSetup.SIGNING_PARAMETERS))
            .also { cached = it }
    }

    /** Deletes the device identity keys. Messages encrypted to the old key become unreadable. */
    @Synchronized
    fun delete() {
        cached = null
        prefs().edit().remove(HPKE_KEYSET).remove(SIGNING_KEYSET).commit()
    }

    private fun handle(name: String, parameters: Parameters): KeysetHandle =
        AndroidKeysetManager.Builder()
            .withSharedPref(appContext, name, LocalKeys.KEYSET_PREF_FILE)
            .withKeyTemplate(KeyTemplate.createFrom(parameters))
            .withMasterKeyUri(LocalKeys.KEYSTORE_MASTER_URI)
            .build()
            .keysetHandle

    private fun prefs() = appContext.getSharedPreferences(LocalKeys.KEYSET_PREF_FILE, Context.MODE_PRIVATE)

    private companion object {
        const val HPKE_KEYSET = "device_hpke_private"
        const val SIGNING_KEYSET = "device_signing_private"
    }
}
