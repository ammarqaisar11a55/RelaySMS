package io.github.ammarqaisar11a55.relaysms.core.security

import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.PublicKeyVerify
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HpkePublicKey
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.signature.Ed25519Parameters
import com.google.crypto.tink.signature.Ed25519PublicKey
import com.google.crypto.tink.signature.SignatureConfig
import com.google.crypto.tink.util.Bytes
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The end-to-end cryptography of one device (see docs/PROTOCOL.md).
 *
 * - Encryption: HPKE (RFC 9180) base mode, DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, AES-256-GCM.
 * - Authenticity: Ed25519 signatures.
 *
 * All primitives come from Google Tink; nothing here implements cryptography itself.
 */
interface DeviceCrypto {
    /** Raw 32-byte X25519 public key. */
    val hpkePublicKey: ByteArray

    /** Raw 32-byte Ed25519 public key. */
    val signingPublicKey: ByteArray

    /** Encrypts for the holder of [recipientHpkePublicKey]. [contextInfo] must match on decryption. */
    fun seal(recipientHpkePublicKey: ByteArray, plaintext: ByteArray, contextInfo: ByteArray): ByteArray

    @Throws(GeneralSecurityException::class)
    fun open(ciphertext: ByteArray, contextInfo: ByteArray): ByteArray

    fun sign(data: ByteArray): ByteArray

    fun verify(signerPublicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean
}

object CryptoSetup {
    val HPKE_PARAMETERS: HpkeParameters = HpkeParameters.builder()
        .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
        .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
        .setAeadId(HpkeParameters.AeadId.AES_256_GCM)
        .setVariant(HpkeParameters.Variant.NO_PREFIX)
        .build()

    val SIGNING_PARAMETERS: Ed25519Parameters = Ed25519Parameters.create(Ed25519Parameters.Variant.NO_PREFIX)

    const val PUBLIC_KEY_BYTES = 32
    const val SIGNATURE_BYTES = 64

    @Volatile private var registered = false

    fun register() {
        if (registered) return
        synchronized(this) {
            if (registered) return
            AeadConfig.register()
            HybridConfig.register()
            SignatureConfig.register()
            registered = true
        }
    }

    /** Fresh in-memory key pairs. Production keys are created by the Keystore-backed key store. */
    fun generateInMemory(): TinkDeviceCrypto {
        register()
        return TinkDeviceCrypto(KeysetHandle.generateNew(HPKE_PARAMETERS), KeysetHandle.generateNew(SIGNING_PARAMETERS))
    }
}

class TinkDeviceCrypto(hpkePrivate: KeysetHandle, signingPrivate: KeysetHandle) : DeviceCrypto {
    init {
        CryptoSetup.register()
    }

    private val decrypter: HybridDecrypt = hpkePrivate.getPrimitive(HybridDecrypt::class.java)
    private val signer: PublicKeySign = signingPrivate.getPrimitive(PublicKeySign::class.java)

    override val hpkePublicKey: ByteArray =
        (hpkePrivate.publicKeysetHandle.getAt(0).key as HpkePublicKey).publicKeyBytes.toByteArray()

    override val signingPublicKey: ByteArray =
        (signingPrivate.publicKeysetHandle.getAt(0).key as Ed25519PublicKey).publicKeyBytes.toByteArray()

    override fun seal(recipientHpkePublicKey: ByteArray, plaintext: ByteArray, contextInfo: ByteArray): ByteArray {
        require(recipientHpkePublicKey.size == CryptoSetup.PUBLIC_KEY_BYTES) { "bad recipient key length" }
        val key = HpkePublicKey.create(CryptoSetup.HPKE_PARAMETERS, Bytes.copyFrom(recipientHpkePublicKey), null)
        val handle = KeysetHandle.newBuilder().addEntry(KeysetHandle.importKey(key).withRandomId().makePrimary()).build()
        return handle.getPrimitive(HybridEncrypt::class.java).encrypt(plaintext, contextInfo)
    }

    override fun open(ciphertext: ByteArray, contextInfo: ByteArray): ByteArray = decrypter.decrypt(ciphertext, contextInfo)

    override fun sign(data: ByteArray): ByteArray = signer.sign(data)

    override fun verify(signerPublicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
        if (signerPublicKey.size != CryptoSetup.PUBLIC_KEY_BYTES || signature.size != CryptoSetup.SIGNATURE_BYTES) return false
        return try {
            val key = Ed25519PublicKey.create(Ed25519Parameters.Variant.NO_PREFIX, Bytes.copyFrom(signerPublicKey), null)
            val handle = KeysetHandle.newBuilder().addEntry(KeysetHandle.importKey(key).withRandomId().makePrimary()).build()
            handle.getPrimitive(PublicKeyVerify::class.java).verify(signature, data)
            true
        } catch (e: GeneralSecurityException) {
            false
        }
    }
}

/** Small helpers over JCA primitives used for hashing, MACs and encoding. */
object CryptoUtil {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun b64(bytes: ByteArray): String = encoder.encodeToString(bytes)

    /** @return decoded bytes, or null if [text] is not base64url. */
    fun unb64(text: String): ByteArray? = try {
        if (text.any { !(it.isLetterOrDigit() && it.code < 128) && it != '-' && it != '_' }) null else decoder.decode(text)
    } catch (e: IllegalArgumentException) {
        null
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}
