package io.github.ammarqaisar11a55.relaysms.core.security

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.security.GeneralSecurityException
import java.security.KeyStore

/**
 * Authenticated encryption for data stored on this device. [context] is bound to the ciphertext
 * as associated data, so a value cannot be moved to another row or column and still decrypt.
 */
interface FieldCipher {
    fun encrypt(plaintext: ByteArray, context: String): ByteArray

    @Throws(GeneralSecurityException::class)
    fun decrypt(ciphertext: ByteArray, context: String): ByteArray
}

class TinkFieldCipher(private val aead: Aead) : FieldCipher {
    override fun encrypt(plaintext: ByteArray, context: String): ByteArray =
        aead.encrypt(plaintext, context.toByteArray(Charsets.UTF_8))

    override fun decrypt(ciphertext: ByteArray, context: String): ByteArray =
        aead.decrypt(ciphertext, context.toByteArray(Charsets.UTF_8))
}

/**
 * Process-wide access to the local storage cipher. The cipher is opened on first use and can be
 * discarded, which matters after a reset: the old keyset is deleted, and anything written
 * afterwards must be encrypted under the new one or it would be unreadable on the next start.
 */
class LocalCipherHolder(private val context: Context, private val onRecreated: () -> Unit = {}) : FieldCipher {
    @Volatile private var cached: FieldCipher? = null

    private fun current(): FieldCipher {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val opened = LocalKeys.openLocalCipher(context)
            cached = opened.cipher
            if (opened.recreated) onRecreated()
            return opened.cipher
        }
    }

    override fun encrypt(plaintext: ByteArray, context: String): ByteArray = current().encrypt(plaintext, context)

    override fun decrypt(ciphertext: ByteArray, context: String): ByteArray = current().decrypt(ciphertext, context)

    /** Deletes the keyset and forgets the open cipher. The next use creates a fresh key. */
    fun destroy() {
        synchronized(this) {
            LocalKeys.deleteLocalKeys(context)
            cached = null
        }
    }
}

/**
 * Builds the device-local AES-256-GCM keyset. The keyset is stored in a private preferences
 * file, wrapped by a key that lives in Android Keystore and never leaves it.
 */
object LocalKeys {
    private const val MASTER_KEY_ALIAS = "relaysms_local_master"
    private const val MASTER_KEY_URI = "android-keystore://$MASTER_KEY_ALIAS"
    private const val PREF_FILE = "relaysms_keysets"
    private const val LOCAL_KEYSET = "local_storage_aead"

    data class Opened(val cipher: FieldCipher, val recreated: Boolean)

    /**
     * Opens the local storage cipher. If the stored keyset can no longer be unwrapped (for
     * example the Keystore key was lost), the keyset is recreated and [Opened.recreated] is true:
     * data encrypted under the old key is unrecoverable and must be discarded by the caller.
     */
    fun openLocalCipher(context: Context): Opened {
        AeadConfig.register()
        return try {
            Opened(TinkFieldCipher(build(context)), recreated = false)
        } catch (e: Exception) {
            deleteLocalKeys(context)
            Opened(TinkFieldCipher(build(context)), recreated = true)
        }
    }

    private fun build(context: Context): Aead =
        AndroidKeysetManager.Builder()
            .withSharedPref(context.applicationContext, LOCAL_KEYSET, PREF_FILE)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)

    /** Deletes the local keyset and its Keystore wrapping key. Used by reset and recovery. */
    fun deleteLocalKeys(context: Context) {
        context.applicationContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).edit().remove(LOCAL_KEYSET).commit()
        try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(MASTER_KEY_ALIAS)
        } catch (e: Exception) {
            // Nothing else can be done; a fresh key is generated on next use.
        }
    }

    const val KEYSET_PREF_FILE = PREF_FILE
    const val KEYSTORE_MASTER_URI = MASTER_KEY_URI
}
