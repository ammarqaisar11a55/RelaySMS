package io.github.ammarqaisar11a55.relaysms

import io.github.ammarqaisar11a55.relaysms.core.model.DeviceRole
import io.github.ammarqaisar11a55.relaysms.core.network.ApiResult
import io.github.ammarqaisar11a55.relaysms.core.network.RelayApi
import io.github.ammarqaisar11a55.relaysms.core.security.CryptoSetup
import io.github.ammarqaisar11a55.relaysms.core.security.CryptoUtil
import io.github.ammarqaisar11a55.relaysms.pairing.LinkState
import io.github.ammarqaisar11a55.relaysms.pairing.PairingFailure
import io.github.ammarqaisar11a55.relaysms.pairing.PairingProtocol
import io.github.ammarqaisar11a55.relaysms.pairing.PairingState
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Both pairing roles run their real code against a real relay over HTTP. */
class PairingIntegrationTest {
    @Before fun requireRelay() = assumeRelay()

    @Test fun `two phones pair and each stores the other's verified keys`() = runBlocking {
        val source = TestPhone(DeviceRole.SOURCE)
        val companion = TestPhone(DeviceRole.COMPANION)
        val code = pair(source, companion)
        assertTrue(Regex("^\\d{3} \\d{3}$").matches(code))

        val sp = source.store.peer.value!!
        val cp = companion.store.peer.value!!
        assertEquals(companion.store.identity.value!!.deviceId, sp.deviceId)
        assertEquals(CryptoUtil.b64(companion.crypto.hpkePublicKey), sp.hpkePublicKey)
        assertEquals(CryptoUtil.b64(companion.crypto.signingPublicKey), sp.signingPublicKey)
        assertEquals(source.store.identity.value!!.deviceId, cp.deviceId)
        assertEquals(CryptoUtil.b64(source.crypto.signingPublicKey), cp.signingPublicKey)
        assertEquals(code, sp.safetyCode)
        assertEquals(code, cp.safetyCode)

        assertEquals(LinkState.PAIRED, source.link.refresh().state)
        assertEquals(LinkState.PAIRED, companion.link.refresh().state)
    }

    @Test fun `a pairing code cannot be used twice`() = runBlocking {
        val source = TestPhone(DeviceRole.SOURCE)
        val companion = TestPhone(DeviceRole.COMPANION)
        val job = launch { companion.pairing.startAsCompanion(relayTestUrl, null) }
        val qr = companion.pairing.await<PairingState.ShowingCode>().qrText
        source.pairing.reviewScanned(qr)
        source.pairing.redeemAsSource(null)
        source.pairing.await<PairingState.Verify>()

        val intruder = TestPhone(DeviceRole.SOURCE)
        intruder.pairing.reviewScanned(qr)
        intruder.pairing.redeemAsSource(null)
        assertEquals(PairingState.Failed(PairingFailure.ALREADY_USED), intruder.pairing.state.value)
        assertNull(intruder.store.peer.value)
        companion.pairing.cancel(); job.join()
    }

    @Test fun `nothing is linked until both users confirm, and a mismatch can be cancelled`() = runBlocking {
        val source = TestPhone(DeviceRole.SOURCE)
        val companion = TestPhone(DeviceRole.COMPANION)
        val job = launch { companion.pairing.startAsCompanion(relayTestUrl, null) }
        source.pairing.reviewScanned(companion.pairing.await<PairingState.ShowingCode>().qrText)
        source.pairing.redeemAsSource(null)
        companion.pairing.await<PairingState.Verify>(); job.join()

        val confirming = launch { source.pairing.confirm() }
        // The companion's user sees a different code and refuses.
        companion.pairing.cancel()
        confirming.join()
        assertEquals(PairingState.Failed(PairingFailure.CANCELLED), source.pairing.state.value)
        assertNull(source.store.peer.value)
        assertNull(companion.store.peer.value)
        assertNull((source.api.me() as ApiResult.Ok).value.peer)
    }

    @Test fun `a relay that substitutes the source's keys is detected by the companion`() = runBlocking {
        val attackerKey = CryptoUtil.b64(CryptoSetup.generateInMemory().hpkePublicKey)
        // Simulates a hostile relay: every pairing view shown to the companion has the source's
        // encryption key replaced with the attacker's.
        val tamper = Interceptor { chain ->
            val response = chain.proceed(chain.request())
            if (chain.request().method != "GET" || !chain.request().url.encodedPath.contains("/pairings/")) return@Interceptor response
            val body = response.body!!.string()
            val replaced = Regex("(\"source\":\\{[^}]*\"hpkePublicKey\":\")[^\"]+").replace(body) { it.groupValues[1] + attackerKey }
            response.newBuilder().body(replaced.toResponseBody("application/json".toMediaType())).build()
        }
        val companion = TestPhone(DeviceRole.COMPANION, RelayApi.newHttpClient().newBuilder().addInterceptor(tamper).build())
        val source = TestPhone(DeviceRole.SOURCE)
        val job = launch { companion.pairing.startAsCompanion(relayTestUrl, null) }
        source.pairing.reviewScanned(companion.pairing.await<PairingState.ShowingCode>().qrText)
        source.pairing.redeemAsSource(null)
        job.join()
        assertEquals(PairingState.Failed(PairingFailure.PROOF_INVALID), companion.pairing.state.value)
        assertNull(companion.store.peer.value)
        // The session was cancelled on the relay, so the source cannot complete it either.
        source.pairing.confirm()
        assertTrue(source.pairing.state.value is PairingState.Failed)
        assertNull(source.store.peer.value)
    }

    @Test fun `a qr code with swapped companion keys is rejected by the source`() = runBlocking {
        val companion = TestPhone(DeviceRole.COMPANION)
        val source = TestPhone(DeviceRole.SOURCE)
        val job = launch { companion.pairing.startAsCompanion(relayTestUrl, null) }
        val genuine = PairingProtocol.decodeQr(companion.pairing.await<PairingState.ShowingCode>().qrText, true)!!
        val forged = PairingProtocol.encodeQr(genuine.copy(hpkePublicKey = CryptoUtil.b64(CryptoSetup.generateInMemory().hpkePublicKey)))
        source.pairing.reviewScanned(forged)
        source.pairing.redeemAsSource(null)
        assertEquals(PairingState.Failed(PairingFailure.KEY_MISMATCH), source.pairing.state.value)
        assertNull(source.store.peer.value)
        job.join()
        assertTrue(companion.pairing.state.value is PairingState.Failed)
    }

    @Test fun `garbage scanned text fails locally without contacting the relay`() {
        val source = TestPhone(DeviceRole.SOURCE)
        source.pairing.reviewScanned("https://example.com/not-a-pairing-code")
        assertEquals(PairingState.Failed(PairingFailure.INVALID_CODE), source.pairing.state.value)
        assertNull(source.store.identity.value)
    }

    @Test fun `revocation by one phone is seen by the other and enforced by the relay`() = runBlocking {
        val source = TestPhone(DeviceRole.SOURCE)
        val companion = TestPhone(DeviceRole.COMPANION)
        pair(source, companion)

        assertTrue(source.link.revokePeer())
        assertNull(source.store.peer.value)
        assertEquals(1, source.peerRemovedCount)

        val status = companion.link.refresh()
        assertEquals(LinkState.REVOKED, status.state)
        assertNull(companion.store.peer.value)
        assertEquals(1, companion.peerRemovedCount)
        assertNull((companion.api.me() as ApiResult.Ok).value.peer)
        // Still registered: the companion can pair again without reinstalling.
        assertNotNull(companion.store.identity.value)
    }

    @Test fun `restarting the app keeps the same registration and pairing`() = runBlocking {
        val source = TestPhone(DeviceRole.SOURCE)
        val companion = TestPhone(DeviceRole.COMPANION)
        pair(source, companion)
        val idBefore = companion.store.identity.value!!.deviceId
        val peerBefore = companion.store.peer.value

        // "Restart": new manager objects over the same persisted state.
        companion.link = companion.newLinkMonitor()
        companion.pairing = companion.newPairingManager()
        assertEquals(LinkState.PAIRED_UNCONFIRMED, companion.link.status.value.state)
        assertEquals(LinkState.PAIRED, companion.link.refresh().state)
        assertEquals(idBefore, companion.store.identity.value!!.deviceId)
        assertEquals(peerBefore, companion.store.peer.value)
        assertEquals(source.store.identity.value!!.deviceId, (companion.api.me() as ApiResult.Ok).value.peer!!.deviceId)
    }

    @Test fun `a phone whose registration was deleted learns it was revoked`() = runBlocking {
        val source = TestPhone(DeviceRole.SOURCE)
        val companion = TestPhone(DeviceRole.COMPANION)
        pair(source, companion)
        // Someone with the companion's credential (e.g. the user on a wiped phone) deletes it.
        assertTrue(companion.api.deleteDevice() is ApiResult.Ok)
        assertEquals(LinkState.REVOKED, companion.link.refresh().state)
        assertNull(companion.store.identity.value)
        assertEquals(LinkState.REVOKED, source.link.refresh().state)
        assertTrue((source.api.pendingMessages() as ApiResult.Ok).value.messages.isEmpty())
    }

    @Test fun `link checks that run in the middle of pairing do not destroy the new link`() = runBlocking {
        // Regression: the relay announces the new link as soon as both sides confirm, a moment
        // before each phone has stored its peer. A link check in that window used to conclude
        // "unverified peer" and revoke the link that was just created.
        val source = TestPhone(DeviceRole.SOURCE)
        val companion = TestPhone(DeviceRole.COMPANION)
        val job = launch { companion.pairing.startAsCompanion(relayTestUrl, null) }
        val qr = companion.pairing.await<PairingState.ShowingCode>().qrText
        lastPairingId = PairingProtocol.decodeQr(qr, true)!!.pairingId
        source.pairing.reviewScanned(qr)
        source.pairing.redeemAsSource(null)
        companion.pairing.await<PairingState.Verify>(); job.join()
        // Complete the link on the relay behind the managers' backs, as the real server does
        // between a phone's confirm call and its next poll.
        assertTrue(source.api.confirmPairing(pairingIdOf(source)) is ApiResult.Ok)
        assertTrue(companion.api.confirmPairing(pairingIdOf(source)) is ApiResult.Ok)
        assertNotNull((source.api.me() as ApiResult.Ok).value.peer)
        assertNull(source.store.peer.value)
        source.link.refresh(); companion.link.refresh()
        assertNotNull("the fresh link must survive a concurrent check", (source.api.me() as ApiResult.Ok).value.peer)

        val a = launch { source.pairing.confirm() }
        val b = launch { companion.pairing.confirm() }
        a.join(); b.join()
        source.pairing.await<PairingState.Completed>()
        assertEquals(LinkState.PAIRED, source.link.refresh().state)
        assertEquals(LinkState.PAIRED, companion.link.refresh().state)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun pairingIdOf(source: TestPhone): String = lastPairingId

    private var lastPairingId = ""

    @Test fun `the relay never adopts a peer this phone did not verify`() = runBlocking {
        val source = TestPhone(DeviceRole.SOURCE)
        val companion = TestPhone(DeviceRole.COMPANION)
        pair(source, companion)
        // The source forgets its peer locally (e.g. key loss) while the relay still has the link.
        source.store.setPeer(null)
        val status = source.newLinkMonitor().refresh()
        assertEquals(LinkState.NOT_PAIRED, status.state)
        assertNull(source.store.peer.value)
        assertNull((source.api.me() as ApiResult.Ok).value.peer)
        assertEquals(LinkState.REVOKED, companion.link.refresh().state)
    }
}
