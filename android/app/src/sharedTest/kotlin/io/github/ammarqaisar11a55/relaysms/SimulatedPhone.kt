package io.github.ammarqaisar11a55.relaysms

import io.github.ammarqaisar11a55.relaysms.core.model.DeviceRole
import io.github.ammarqaisar11a55.relaysms.core.network.RelayApi
import io.github.ammarqaisar11a55.relaysms.core.security.CryptoSetup
import io.github.ammarqaisar11a55.relaysms.core.security.DeviceCrypto
import io.github.ammarqaisar11a55.relaysms.pairing.InMemoryIdentityStore
import io.github.ammarqaisar11a55.relaysms.pairing.LinkMonitor
import io.github.ammarqaisar11a55.relaysms.pairing.PairingManager
import io.github.ammarqaisar11a55.relaysms.pairing.PairingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient

/**
 * One simulated phone running the app's real protocol code: real keys, real HTTP client,
 * in-memory storage. Used as the "other phone" in integration and on-device tests.
 */
class TestPhone(val role: DeviceRole, val client: OkHttpClient = RelayApi.newHttpClient()) {
    val crypto: DeviceCrypto = CryptoSetup.generateInMemory()
    val store = InMemoryIdentityStore()
    var peerRemovedCount = 0
    val apiFactory: (String, String?) -> RelayApi = { url, token -> RelayApi(client, url, token) }

    fun newPairingManager() = PairingManager(role, store, { crypto }, apiFactory, allowCleartextLocal = true, pollIntervalMs = 100)
    fun newLinkMonitor() = LinkMonitor(store, apiFactory, onPeerRemoved = { peerRemovedCount++ })

    var pairing = newPairingManager()
    var link = newLinkMonitor()

    val api: RelayApi get() = store.identity.value!!.let { RelayApi(client, it.serverUrl, it.token) }
}

suspend inline fun <reified T : PairingState> PairingManager.await(): T =
    withTimeout(20_000) { state.first { it is T || it is PairingState.Failed } }.let {
        it as? T ?: throw AssertionError("expected ${T::class.simpleName} but was $it")
    }

/** Runs the complete pairing between two simulated phones and returns the shared safety code. */
suspend fun CoroutineScope.pair(source: TestPhone, companion: TestPhone, relayUrl: String): String {
    val companionJob: Job = launch { companion.pairing.startAsCompanion(relayUrl, null) }
    val qr = companion.pairing.await<PairingState.ShowingCode>().qrText
    source.pairing.reviewScanned(qr)
    source.pairing.await<PairingState.ReviewPeer>()
    source.pairing.redeemAsSource(null)
    val s = source.pairing.await<PairingState.Verify>()
    val c = companion.pairing.await<PairingState.Verify>()
    companionJob.join()
    check(s.safetyCode == c.safetyCode) { "safety codes differ" }
    val a = launch { source.pairing.confirm() }
    val b = launch { companion.pairing.confirm() }
    a.join(); b.join()
    source.pairing.await<PairingState.Completed>()
    companion.pairing.await<PairingState.Completed>()
    source.link.onPaired(); companion.link.onPaired()
    return s.safetyCode
}
