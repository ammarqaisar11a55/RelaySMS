package io.github.ammarqaisar11a55.relaysms.transport

import io.github.ammarqaisar11a55.relaysms.core.network.ApiResult
import io.github.ammarqaisar11a55.relaysms.core.network.RelayApi
import io.github.ammarqaisar11a55.relaysms.core.security.DeviceCrypto
import io.github.ammarqaisar11a55.relaysms.pairing.IdentityStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Receives a verified, decrypted envelope. */
fun interface EnvelopeHandler {
    /**
     * Durably stores or acts on [envelope]. Must be idempotent on the envelope id.
     * @return true when the envelope was handled (including "already had it" and "unknown kind,
     * ignored"). Throw, or return false, only when a later retry could succeed.
     */
    suspend fun handle(envelope: Envelope): Boolean
}

enum class SyncOutcome {
    /** Everything pending was fetched, stored and acknowledged. */
    COMPLETE,

    /** Not paired or not registered. */
    NOT_CONFIGURED,

    /** A temporary problem; some messages may remain on the relay. */
    RETRY,

    /** The relay no longer accepts this device. */
    UNAUTHORIZED,
}

data class SyncStatus(
    val running: Boolean = false,
    val lastOutcome: SyncOutcome? = null,
    /** Last time the relay was reached and the queue was drained. Zero if never in this process. */
    val lastCompleteAtMillis: Long = 0,
    val stored: Int = 0,
    /** Messages that failed verification or decryption and were dropped. */
    val rejected: Int = 0,
)

/**
 * Pulls this device's pending ciphertext from the relay, verifies and decrypts each message,
 * hands it to [handler] and acknowledges only what was handled durably.
 */
class RelaySync(
    private val store: IdentityStore,
    private val crypto: () -> DeviceCrypto,
    private val api: (baseUrl: String, token: String?) -> RelayApi,
    private val handler: EnvelopeHandler,
    private val onAuthorizationLost: suspend () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    suspend fun syncOnce(): SyncOutcome = mutex.withLock {
        _status.update { it.copy(running = true) }
        val outcome = try {
            drain()
        } catch (e: kotlinx.coroutines.CancellationException) {
            _status.update { it.copy(running = false) }
            throw e
        } catch (e: Exception) {
            SyncOutcome.RETRY
        }
        _status.update {
            it.copy(running = false, lastOutcome = outcome, lastCompleteAtMillis = if (outcome == SyncOutcome.COMPLETE) now() else it.lastCompleteAtMillis)
        }
        outcome
    }

    private suspend fun drain(): SyncOutcome {
        val identity = store.identity.value ?: return SyncOutcome.NOT_CONFIGURED
        val peer = store.peer.value ?: return SyncOutcome.NOT_CONFIGURED
        val relay = api(identity.serverUrl, identity.token)
        // Bounded so that a relay that always claims "more" cannot hold the device in a loop.
        repeat(MAX_PAGES) {
            val page = when (val r = relay.pendingMessages(PAGE_SIZE)) {
                is ApiResult.Ok -> r.value
                ApiResult.Unreachable -> return SyncOutcome.RETRY
                is ApiResult.Error -> {
                    if (r.status == 401) {
                        onAuthorizationLost()
                        return SyncOutcome.UNAUTHORIZED
                    }
                    return SyncOutcome.RETRY
                }
            }
            if (page.messages.isEmpty()) return SyncOutcome.COMPLETE
            val handled = ArrayList<String>(page.messages.size)
            var stored = 0
            var rejected = 0
            var failed = false
            for (m in page.messages) {
                when (val opened = MessageCodec.open(crypto(), identity.deviceId, peer.identity, m)) {
                    is OpenResult.Ok -> if (handler.handle(opened.envelope)) {
                        handled += m.messageId
                        stored++
                    } else {
                        failed = true
                    }
                    // A message that fails verification can never become valid: drop it from
                    // the queue so that it does not block the messages behind it.
                    is OpenResult.Rejected -> {
                        handled += m.messageId
                        rejected++
                    }
                }
                if (failed) break
            }
            _status.update { it.copy(stored = it.stored + stored, rejected = it.rejected + rejected) }
            if (handled.isNotEmpty()) {
                // Only after durable handling. If this fails the messages come again and are
                // recognised as duplicates.
                when (relay.acknowledge(handled)) {
                    is ApiResult.Ok -> Unit
                    else -> return SyncOutcome.RETRY
                }
            }
            if (failed) return SyncOutcome.RETRY
            if (!page.more) return SyncOutcome.COMPLETE
        }
        return SyncOutcome.RETRY
    }

    private companion object {
        const val PAGE_SIZE = 50
        const val MAX_PAGES = 40
    }
}
