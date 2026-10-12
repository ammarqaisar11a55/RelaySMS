package io.github.ammarqaisar11a55.relaysms.pairing

import io.github.ammarqaisar11a55.relaysms.core.network.ApiResult
import io.github.ammarqaisar11a55.relaysms.core.network.RelayApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class RotationResult { NOT_REGISTERED, NOT_DUE, ROTATED, FAILED }

/**
 * Replaces this device's relay credential at regular intervals, so that a credential copied from
 * the phone at some point stops being useful.
 *
 * Order of operations matters: the new credential is stored before anything else, then used once.
 * The relay keeps the old credential valid until that first use, so a lost answer or a crash
 * between the steps cannot lock the device out.
 */
class CredentialRotator(
    private val store: IdentityStore,
    private val api: (baseUrl: String, token: String?) -> RelayApi,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxAgeMillis: Long = DEFAULT_MAX_AGE_MS,
) {
    private val mutex = Mutex()

    fun issuedAt(identity: Identity): Long = if (identity.tokenIssuedAtMillis > 0) identity.tokenIssuedAtMillis else identity.registeredAtMillis

    suspend fun rotateIfDue(force: Boolean = false): RotationResult = mutex.withLock {
        val identity = store.identity.value ?: return@withLock RotationResult.NOT_REGISTERED
        if (!force && now() - issuedAt(identity) < maxAgeMillis) return@withLock RotationResult.NOT_DUE
        val rotated = when (val r = api(identity.serverUrl, identity.token).rotateToken()) {
            is ApiResult.Ok -> r.value.token
            else -> return@withLock RotationResult.FAILED
        }
        // A pairing reset may have happened while the request was in flight.
        if (store.identity.value?.deviceId != identity.deviceId) return@withLock RotationResult.FAILED
        store.setIdentity(identity.copy(token = rotated, tokenIssuedAtMillis = now()))
        // First use confirms the new credential to the relay, which then retires the old one.
        api(identity.serverUrl, rotated).me()
        RotationResult.ROTATED
    }

    companion object {
        const val DEFAULT_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
    }
}
