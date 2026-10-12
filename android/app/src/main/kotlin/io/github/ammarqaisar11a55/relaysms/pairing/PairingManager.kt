package io.github.ammarqaisar11a55.relaysms.pairing

import io.github.ammarqaisar11a55.relaysms.core.model.DeviceRole
import io.github.ammarqaisar11a55.relaysms.core.network.ApiResult
import io.github.ammarqaisar11a55.relaysms.core.network.PublicDevice
import io.github.ammarqaisar11a55.relaysms.core.network.RedeemRequest
import io.github.ammarqaisar11a55.relaysms.core.network.RegisterRequest
import io.github.ammarqaisar11a55.relaysms.core.network.RelayApi
import io.github.ammarqaisar11a55.relaysms.core.network.ServerUrl
import io.github.ammarqaisar11a55.relaysms.core.security.CryptoUtil
import io.github.ammarqaisar11a55.relaysms.core.security.DeviceCrypto
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
import java.time.Instant

enum class PairingFailure {
    /** Scanned text is not a RelaySMS pairing code. */
    INVALID_CODE,
    EXPIRED,
    ALREADY_USED,
    CANCELLED,
    /** The relay could not be reached. */
    NETWORK,
    /** The relay answered with an unexpected error. */
    SERVER,
    RATE_LIMITED,
    /** The relay requires a registration code and none, or a wrong one, was given. */
    REGISTRATION_CODE,
    /** The relay returned keys that differ from the ones in the QR code. */
    KEY_MISMATCH,
    /** The source's keys could not be verified with the secret from this phone's QR code. */
    PROOF_INVALID,
    /** This phone's own keys could not be loaded. */
    LOCAL_KEYS,
}

sealed interface PairingState {
    data object Idle : PairingState
    data class Working(val step: String) : PairingState

    /** Companion: QR code on screen, waiting for the source to scan it. */
    data class ShowingCode(val qrText: String, val expiresAtMillis: Long) : PairingState

    /** Source: code scanned and validated locally; nothing sent yet. */
    data class ReviewPeer(val serverUrl: String, val peerFingerprint: String) : PairingState

    /** Both: compare [safetyCode] on the two screens. */
    data class Verify(
        val safetyCode: String,
        val peerFingerprint: String,
        val confirmedHere: Boolean,
        val expiresAtMillis: Long,
    ) : PairingState

    data class Completed(val peerFingerprint: String) : PairingState
    data class Failed(val reason: PairingFailure) : PairingState
}

/**
 * Runs the pairing protocol for either role (docs/PROTOCOL.md §3). Free of Android dependencies:
 * storage, cryptography and the HTTP client are injected.
 */
class PairingManager(
    private val role: DeviceRole,
    private val store: IdentityStore,
    private val crypto: () -> DeviceCrypto,
    private val api: (baseUrl: String, token: String?) -> RelayApi,
    private val allowCleartextLocal: Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val pollIntervalMs: Long = 2_000,
    private val random: SecureRandom = SecureRandom(),
) {
    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state.asStateFlow()

    private class Session(
        val api: RelayApi,
        val pairingId: String,
        val expiresAtMillis: Long,
        val self: DeviceIdentity,
        var peer: DeviceIdentity? = null,
        var safetyCode: String = "",
    )

    private var session: Session? = null
    private var scanned: QrPayload? = null

    fun reset() {
        session = null
        scanned = null
        _state.value = PairingState.Idle
    }

    private fun fail(reason: PairingFailure) {
        session = null
        _state.value = PairingState.Failed(reason)
    }

    private fun failureFor(error: ApiResult.Error): PairingFailure = when (error.code) {
        "pairing_expired" -> PairingFailure.EXPIRED
        "pairing_cancelled" -> PairingFailure.CANCELLED
        "pairing_already_redeemed" -> PairingFailure.ALREADY_USED
        "invalid_pairing_secret", "pairing_not_found", "invalid_request" -> PairingFailure.INVALID_CODE
        "registration_code_required" -> PairingFailure.REGISTRATION_CODE
        "rate_limited" -> PairingFailure.RATE_LIMITED
        else -> PairingFailure.SERVER
    }

    private sealed interface Registered {
        data class Ok(val identity: Identity, val api: RelayApi, val self: DeviceIdentity) : Registered
        data class Failed(val reason: PairingFailure) : Registered
    }

    /**
     * Makes sure this phone holds a valid registration on [serverUrl]. An existing registration is
     * reused when the relay still accepts it; otherwise a new one is created with the same keys.
     */
    private suspend fun ensureRegistered(serverUrl: String, registrationCode: String?): Registered {
        val keys = try {
            crypto()
        } catch (e: Exception) {
            return Registered.Failed(PairingFailure.LOCAL_KEYS)
        }
        val hpke = CryptoUtil.b64(keys.hpkePublicKey)
        val signing = CryptoUtil.b64(keys.signingPublicKey)
        val existing = store.identity.value
        if (existing != null && existing.serverUrl == serverUrl && existing.role == role.wireName) {
            val existingApi = api(serverUrl, existing.token)
            when (val me = existingApi.me()) {
                is ApiResult.Ok -> return Registered.Ok(existing, existingApi, DeviceIdentity(existing.deviceId, hpke, signing))
                ApiResult.Unreachable -> return Registered.Failed(PairingFailure.NETWORK)
                is ApiResult.Error -> if (me.status != 401) return Registered.Failed(failureFor(me))
            }
        } else if (existing != null) {
            // Moving to another relay or role: withdraw the old registration, best effort.
            api(existing.serverUrl, existing.token).deleteDevice()
        }
        if (existing != null) {
            store.setPeer(null)
            store.setIdentity(null)
        }
        val request = RegisterRequest(role.wireName, hpke, signing, registrationCode?.trim()?.takeIf { it.isNotEmpty() })
        return when (val r = api(serverUrl, null).register(request)) {
            is ApiResult.Ok -> {
                val identity = Identity(serverUrl, r.value.deviceId, r.value.token, role.wireName, now())
                store.setIdentity(identity)
                Registered.Ok(identity, api(serverUrl, identity.token), DeviceIdentity(identity.deviceId, hpke, signing))
            }
            ApiResult.Unreachable -> Registered.Failed(PairingFailure.NETWORK)
            is ApiResult.Error -> Registered.Failed(failureFor(r))
        }
    }

    // ---------------------------------------------------------------- companion

    /** Companion: registers if needed, opens a session and shows its QR code until it is scanned. */
    suspend fun startAsCompanion(serverUrlInput: String, registrationCode: String?) {
        check(role == DeviceRole.COMPANION)
        val url = (ServerUrl.parse(serverUrlInput, allowCleartextLocal) as? ServerUrl.Result.Valid)?.url
            ?: return fail(PairingFailure.INVALID_CODE)
        _state.value = PairingState.Working("Contacting the relay")
        val reg = when (val r = ensureRegistered(url, registrationCode)) {
            is Registered.Ok -> r
            is Registered.Failed -> return fail(r.reason)
        }
        val created = when (val r = reg.api.createPairing()) {
            is ApiResult.Ok -> r.value
            ApiResult.Unreachable -> return fail(PairingFailure.NETWORK)
            is ApiResult.Error -> return fail(failureFor(r))
        }
        val macKey = ByteArray(32).also(random::nextBytes)
        val expiresAt = parseInstant(created.expiresAt) ?: (now() + 300_000)
        val s = Session(reg.api, created.pairingId, expiresAt, reg.self)
        session = s
        _state.value = PairingState.ShowingCode(
            PairingProtocol.encodeQr(
                QrPayload(
                    PairingProtocol.VERSION, url, created.pairingId, created.secret, CryptoUtil.b64(macKey),
                    reg.self.deviceId, reg.self.hpkePublicKey, reg.self.signingPublicKey,
                ),
            ),
            expiresAt,
        )
        awaitRedemption(s, macKey)
    }

    private suspend fun awaitRedemption(s: Session, macKey: ByteArray) {
        while (now() < s.expiresAtMillis) {
            delay(pollIntervalMs)
            if (session !== s) return
            val view = when (val r = s.api.getPairing(s.pairingId)) {
                is ApiResult.Ok -> r.value
                ApiResult.Unreachable -> continue
                is ApiResult.Error -> return fail(failureFor(r))
            }
            when (view.status) {
                "pending" -> continue
                "redeemed" -> {
                    val source = view.source?.toIdentity()
                    val proof = view.sourceProof?.let(CryptoUtil::unb64)
                    // The relay is not trusted to tell us who scanned the code: the source's keys
                    // count only if they carry a MAC made with the secret from our own QR code.
                    val valid = source != null && proof != null &&
                        CryptoUtil.constantTimeEquals(proof, PairingProtocol.sourceProof(macKey, s.pairingId, s.self, source))
                    if (!valid || source == null) {
                        s.api.cancelPairing(s.pairingId)
                        return fail(PairingFailure.PROOF_INVALID)
                    }
                    s.peer = source
                    s.safetyCode = PairingProtocol.safetyCode(s.pairingId, s.self, source)
                    _state.value = PairingState.Verify(s.safetyCode, PairingProtocol.fingerprint(source), false, s.expiresAtMillis)
                    return
                }
                "cancelled" -> return fail(PairingFailure.CANCELLED)
                else -> return fail(PairingFailure.EXPIRED)
            }
        }
        if (session === s) fail(PairingFailure.EXPIRED)
    }

    // ------------------------------------------------------------------- source

    /** Source: validates scanned text locally. Nothing is sent anywhere yet. */
    fun reviewScanned(text: String) {
        check(role == DeviceRole.SOURCE)
        val payload = PairingProtocol.decodeQr(text, allowCleartextLocal) ?: return fail(PairingFailure.INVALID_CODE)
        scanned = payload
        _state.value = PairingState.ReviewPeer(payload.serverUrl, PairingProtocol.fingerprint(payload.companion))
    }

    /** Source: after the user approved the destination, redeem the code. */
    suspend fun redeemAsSource(registrationCode: String?) {
        check(role == DeviceRole.SOURCE)
        val payload = scanned ?: return fail(PairingFailure.INVALID_CODE)
        _state.value = PairingState.Working("Contacting the relay")
        val reg = when (val r = ensureRegistered(payload.serverUrl, registrationCode)) {
            is Registered.Ok -> r
            is Registered.Failed -> return fail(r.reason)
        }
        val macKey = CryptoUtil.unb64(payload.macKey) ?: return fail(PairingFailure.INVALID_CODE)
        val proof = PairingProtocol.sourceProof(macKey, payload.pairingId, payload.companion, reg.self)
        val redeemed = when (val r = reg.api.redeemPairing(payload.pairingId, RedeemRequest(payload.redeemSecret, CryptoUtil.b64(proof)))) {
            is ApiResult.Ok -> r.value
            ApiResult.Unreachable -> return fail(PairingFailure.NETWORK)
            is ApiResult.Error -> return fail(failureFor(r))
        }
        // The QR code is the authority on the companion's keys. If the relay disagrees, stop.
        if (redeemed.companion.toIdentity() != payload.companion) {
            reg.api.cancelPairing(payload.pairingId)
            return fail(PairingFailure.KEY_MISMATCH)
        }
        val s = Session(reg.api, payload.pairingId, parseInstant(redeemed.expiresAt) ?: (now() + 300_000), reg.self, payload.companion)
        s.safetyCode = PairingProtocol.safetyCode(s.pairingId, payload.companion, reg.self)
        session = s
        scanned = null
        _state.value = PairingState.Verify(s.safetyCode, PairingProtocol.fingerprint(payload.companion), false, s.expiresAtMillis)
    }

    // --------------------------------------------------------------------- both

    /** The user states that both phones show the same code. Completes once the other side agrees. */
    suspend fun confirm() {
        val s = session ?: return
        val peer = s.peer ?: return
        val fingerprint = PairingProtocol.fingerprint(peer)
        when (val r = s.api.confirmPairing(s.pairingId)) {
            is ApiResult.Ok -> Unit
            ApiResult.Unreachable -> return fail(PairingFailure.NETWORK)
            is ApiResult.Error -> return fail(failureFor(r))
        }
        _state.value = PairingState.Verify(s.safetyCode, fingerprint, true, s.expiresAtMillis)
        while (now() < s.expiresAtMillis) {
            if (session !== s) return
            when (val r = s.api.getPairing(s.pairingId)) {
                is ApiResult.Ok -> when (r.value.status) {
                    "completed" -> {
                        store.setPeer(Peer(peer.deviceId, peer.hpkePublicKey, peer.signingPublicKey, now(), s.safetyCode))
                        session = null
                        _state.value = PairingState.Completed(fingerprint)
                        return
                    }
                    "redeemed" -> Unit
                    "cancelled" -> return fail(PairingFailure.CANCELLED)
                    else -> return fail(PairingFailure.EXPIRED)
                }
                ApiResult.Unreachable -> Unit
                is ApiResult.Error -> return fail(failureFor(r))
            }
            delay(pollIntervalMs)
        }
        if (session === s) fail(PairingFailure.EXPIRED)
    }

    /** Abandons the session, e.g. when the codes differ. */
    suspend fun cancel() {
        val s = session
        session = null
        scanned = null
        _state.value = PairingState.Failed(PairingFailure.CANCELLED)
        s?.api?.cancelPairing(s.pairingId)
    }

    private fun PublicDevice.toIdentity() = DeviceIdentity(deviceId, hpkePublicKey, signingPublicKey)

    private fun parseInstant(text: String): Long? = runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
}
