package io.github.ammarqaisar11a55.relaysms.pairing

import io.github.ammarqaisar11a55.relaysms.core.network.ApiResult
import io.github.ammarqaisar11a55.relaysms.core.network.RelayApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

enum class LinkState {
    NOT_PAIRED,

    /** A peer is stored locally and the relay confirmed the link in the last check. */
    PAIRED,

    /** A peer is stored locally but the relay could not be reached to confirm it. */
    PAIRED_UNCONFIRMED,

    /** The relay reported that the link or this device's registration no longer exists. */
    REVOKED,
}

data class LinkStatus(
    val state: LinkState = LinkState.NOT_PAIRED,
    val peerOnline: Boolean = false,
    val peerLastSeenAtMillis: Long? = null,
    /** Messages sent by this device that the peer has not acknowledged yet. */
    val awaitingPeer: Int = 0,
    /** When the relay last answered. Zero if it never has in this process. */
    val confirmedAtMillis: Long = 0,
)

/**
 * Keeps the locally stored pairing consistent with the relay and performs revocation. The relay
 * is the authority on whether a link exists; this phone is the authority on which keys it trusts.
 */
class LinkMonitor(
    private val store: IdentityStore,
    private val api: (baseUrl: String, token: String?) -> RelayApi,
    private val onPeerRemoved: suspend () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _status = MutableStateFlow(LinkStatus(if (store.peer.value != null) LinkState.PAIRED_UNCONFIRMED else LinkState.NOT_PAIRED))
    val status: StateFlow<LinkStatus> = _status.asStateFlow()
    private val mutex = Mutex()

    private fun apiFor(identity: Identity) = api(identity.serverUrl, identity.token)

    private suspend fun dropPeer(revoked: Boolean) {
        val had = store.peer.value != null
        store.setPeer(null)
        if (had) onPeerRemoved()
        _status.value = LinkStatus(if (revoked && had) LinkState.REVOKED else LinkState.NOT_PAIRED, confirmedAtMillis = now())
    }

    /** Asks the relay for the current link and reconciles local state with the answer. */
    suspend fun refresh(): LinkStatus = mutex.withLock {
        val identity = store.identity.value
        val local = store.peer.value
        if (identity == null) {
            if (local != null) dropPeer(revoked = false)
            if (_status.value.state != LinkState.REVOKED) _status.value = LinkStatus(LinkState.NOT_PAIRED)
            return@withLock _status.value
        }
        when (val me = apiFor(identity).me()) {
            ApiResult.Unreachable ->
                _status.value = _status.value.copy(state = if (local != null) LinkState.PAIRED_UNCONFIRMED else _status.value.state.takeIf { it == LinkState.REVOKED } ?: LinkState.NOT_PAIRED, peerOnline = false)
            is ApiResult.Error -> if (me.status == 401) {
                // This device's credential was revoked on the relay.
                store.setIdentity(null)
                dropPeer(revoked = true)
            } else {
                _status.value = _status.value.copy(peerOnline = false)
            }
            is ApiResult.Ok -> {
                val remote = me.value.peer
                when {
                    remote == null -> if (local != null) dropPeer(revoked = true) else if (_status.value.state != LinkState.REVOKED) _status.value = LinkStatus(LinkState.NOT_PAIRED, confirmedAtMillis = now())
                    local == null || remote.deviceId != local.deviceId ||
                        remote.hpkePublicKey != local.hpkePublicKey || remote.signingPublicKey != local.signingPublicKey -> {
                        // The relay names a peer whose keys this phone never verified. Never adopt
                        // keys from the relay: remove the link on both sides instead.
                        apiFor(identity).revokeLink()
                        dropPeer(revoked = true)
                    }
                    else -> _status.value = LinkStatus(
                        state = LinkState.PAIRED,
                        peerOnline = remote.online,
                        peerLastSeenAtMillis = runCatching { Instant.parse(remote.lastSeenAt).toEpochMilli() }.getOrNull(),
                        awaitingPeer = remote.awaitingPeer,
                        confirmedAtMillis = now(),
                    )
                }
            }
        }
        _status.value
    }

    /** Called after a pairing completed locally. */
    fun onPaired() {
        _status.value = LinkStatus(LinkState.PAIRED_UNCONFIRMED)
    }

    /**
     * Revokes the paired device on the relay, then forgets it locally.
     * @return false if the relay could not be reached; nothing is changed in that case, because
     * hiding the device locally while the relay still delivers to it would be misleading.
     */
    suspend fun revokePeer(): Boolean = mutex.withLock {
        val identity = store.identity.value
        if (identity != null) {
            when (val r = apiFor(identity).revokeLink()) {
                is ApiResult.Ok -> Unit
                is ApiResult.Error -> if (r.status != 401) return@withLock false
                ApiResult.Unreachable -> return@withLock false
            }
        }
        dropPeer(revoked = false)
        true
    }

    /** Forgets the pairing on this phone only. The relay-side link is removed at the next contact. */
    suspend fun clearLocalPairing() = mutex.withLock {
        dropPeer(revoked = false)
    }

    /**
     * Withdraws this device's registration from the relay (best effort) and forgets it locally.
     * @return true if the relay confirmed the deletion.
     */
    suspend fun deleteRegistration(): Boolean = mutex.withLock {
        val identity = store.identity.value
        val confirmed = identity != null && apiFor(identity).deleteDevice().let { it is ApiResult.Ok || (it is ApiResult.Error && it.status == 401) }
        store.setIdentity(null)
        dropPeer(revoked = false)
        confirmed
    }
}
