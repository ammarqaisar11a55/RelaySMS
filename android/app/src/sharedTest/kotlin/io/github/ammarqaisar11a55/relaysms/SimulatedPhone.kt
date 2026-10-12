package io.github.ammarqaisar11a55.relaysms

import io.github.ammarqaisar11a55.relaysms.core.model.DeviceRole
import io.github.ammarqaisar11a55.relaysms.core.network.RelayApi
import io.github.ammarqaisar11a55.relaysms.core.security.CryptoSetup
import io.github.ammarqaisar11a55.relaysms.core.security.DeviceCrypto
import io.github.ammarqaisar11a55.relaysms.pairing.InMemoryIdentityStore
import io.github.ammarqaisar11a55.relaysms.pairing.LinkMonitor
import io.github.ammarqaisar11a55.relaysms.pairing.PairingActivity
import io.github.ammarqaisar11a55.relaysms.pairing.PairingManager
import io.github.ammarqaisar11a55.relaysms.delivery.SendResult
import io.github.ammarqaisar11a55.relaysms.pairing.PairingState
import io.github.ammarqaisar11a55.relaysms.sms.EventId
import io.github.ammarqaisar11a55.relaysms.sms.IncomingSms
import io.github.ammarqaisar11a55.relaysms.transport.EnvelopeKind
import io.github.ammarqaisar11a55.relaysms.transport.MessageCodec
import io.github.ammarqaisar11a55.relaysms.transport.RelayOutboxTransport
import io.github.ammarqaisar11a55.relaysms.transport.RelaySync
import kotlinx.serialization.json.Json
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

    val pairingActivity = PairingActivity()

    fun newPairingManager() = PairingManager(role, store, { crypto }, apiFactory, allowCleartextLocal = true, pollIntervalMs = 100, activity = pairingActivity)
    fun newLinkMonitor() = LinkMonitor(store, apiFactory, onPeerRemoved = { peerRemovedCount++ }, pairingActivity = pairingActivity)

    var pairing = newPairingManager()
    var link = newLinkMonitor()

    val api: RelayApi get() = store.identity.value!!.let { RelayApi(client, it.serverUrl, it.token) }

    /** Messages this phone decrypted and "stored", in arrival order, deduplicated by id like the inbox. */
    val received = LinkedHashMap<String, IncomingSms>()
    var handlerFailuresToInject = 0

    val sync = RelaySync(
        store, { crypto }, apiFactory,
        handler = { envelope ->
            if (handlerFailuresToInject > 0) {
                handlerFailuresToInject--
                throw java.io.IOException("simulated storage failure")
            }
            val sms = MessageCodec.decodeBody(IncomingSms.serializer(), envelope)
            if (envelope.kind == EnvelopeKind.SMS && sms != null) received.putIfAbsent(envelope.id, sms)
            true
        },
        onAuthorizationLost = { link.refresh() },
    )

    val transport = RelayOutboxTransport(store, { crypto }, apiFactory, onAuthorizationLost = { link.refresh() })

    /** Forwards one SMS exactly as the source app does: stable id, JSON body, seal, upload. */
    suspend fun forward(sms: IncomingSms): SendResult =
        transport.send(EventId.of(sms), EnvelopeKind.SMS, Json.encodeToString(IncomingSms.serializer(), sms).toByteArray())
}

suspend inline fun <reified T : PairingState> PairingManager.await(): T =
    withTimeout(20_000) { state.first { it is T || it is PairingState.Failed } }.let {
        it as? T ?: throw AssertionError("expected ${T::class.simpleName} but was $it")
    }

/** Runs the complete pairing between two simulated phones and returns the shared safety code. */
suspend fun CoroutineScope.pair(source: TestPhone, companion: TestPhone, relayUrl: String): String {
    val code = pairManagers(source.pairing, companion.pairing, relayUrl)
    source.link.onPaired(); companion.link.onPaired()
    return code
}

/** The pairing dance between any two managers, e.g. the real app's and a simulated phone's. */
suspend fun CoroutineScope.pairManagers(source: PairingManager, companion: PairingManager, relayUrl: String): String {
    val companionJob: Job = launch { companion.startAsCompanion(relayUrl, null) }
    val qr = companion.await<PairingState.ShowingCode>().qrText
    source.reviewScanned(qr)
    source.await<PairingState.ReviewPeer>()
    source.redeemAsSource(null)
    val s = source.await<PairingState.Verify>()
    val c = companion.await<PairingState.Verify>()
    companionJob.join()
    check(s.safetyCode == c.safetyCode) { "safety codes differ" }
    val a = launch { source.confirm() }
    val b = launch { companion.confirm() }
    a.join(); b.join()
    source.await<PairingState.Completed>()
    companion.await<PairingState.Completed>()
    return s.safetyCode
}
