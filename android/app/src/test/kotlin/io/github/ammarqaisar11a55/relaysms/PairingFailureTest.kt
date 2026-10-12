package io.github.ammarqaisar11a55.relaysms

import io.github.ammarqaisar11a55.relaysms.core.model.DeviceRole
import io.github.ammarqaisar11a55.relaysms.core.network.ApiResult
import io.github.ammarqaisar11a55.relaysms.core.network.RelayApi
import io.github.ammarqaisar11a55.relaysms.core.security.CryptoSetup
import io.github.ammarqaisar11a55.relaysms.core.security.CryptoUtil
import io.github.ammarqaisar11a55.relaysms.delivery.SendResult
import io.github.ammarqaisar11a55.relaysms.pairing.Identity
import io.github.ammarqaisar11a55.relaysms.pairing.InMemoryIdentityStore
import io.github.ammarqaisar11a55.relaysms.pairing.PairingFailure
import io.github.ammarqaisar11a55.relaysms.pairing.PairingManager
import io.github.ammarqaisar11a55.relaysms.pairing.PairingProtocol
import io.github.ammarqaisar11a55.relaysms.pairing.PairingState
import io.github.ammarqaisar11a55.relaysms.pairing.Peer
import io.github.ammarqaisar11a55.relaysms.pairing.QrPayload
import io.github.ammarqaisar11a55.relaysms.transport.EnvelopeKind
import io.github.ammarqaisar11a55.relaysms.transport.RelayOutboxTransport
import io.github.ammarqaisar11a55.relaysms.transport.RelaySync
import io.github.ammarqaisar11a55.relaysms.transport.SyncOutcome
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * How the app reacts to every kind of answer a relay can give, using a scripted local HTTP
 * server. Needs no real relay, so it also runs in a plain `testDebugUnitTest`.
 */
class PairingFailureTest {
    private val server = MockWebServer()
    private val client = RelayApi.newHttpClient()
    private val store = InMemoryIdentityStore()
    private val crypto = CryptoSetup.generateInMemory()
    private val apiFactory: (String, String?) -> RelayApi = { url, token -> RelayApi(client, url, token) }
    private lateinit var url: String

    @Before fun start() {
        server.start()
        url = "http://localhost:${server.port}"
    }

    @After fun stop() = server.shutdown()

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private fun error(code: Int, name: String) = json(code, """{"error":{"code":"$name","message":"x"}}""")
    private val registered = json(201, """{"deviceId":"11111111-1111-4111-8111-111111111111","token":"11111111-1111-4111-8111-111111111111.${"s".repeat(43)}"}""")

    private fun manager(role: DeviceRole) = PairingManager(role, store, { crypto }, apiFactory, allowCleartextLocal = true, pollIntervalMs = 20)

    private fun qr(): String {
        val other = CryptoSetup.generateInMemory()
        return PairingProtocol.encodeQr(
            QrPayload(1, url, "33333333-3333-4333-8333-333333333333", "A".repeat(22), CryptoUtil.b64(ByteArray(32)),
                "22222222-2222-4222-8222-222222222222", CryptoUtil.b64(other.hpkePublicKey), CryptoUtil.b64(other.signingPublicKey)),
        )
    }

    private fun redeemWith(response: MockResponse): PairingState = runBlocking {
        server.enqueue(registered)
        server.enqueue(response)
        val m = manager(DeviceRole.SOURCE)
        m.reviewScanned(qr())
        m.redeemAsSource(null)
        m.state.value
    }

    @Test fun `each relay error becomes the matching explanation`() {
        val cases = mapOf(
            error(410, "pairing_expired") to PairingFailure.EXPIRED,
            error(410, "pairing_cancelled") to PairingFailure.CANCELLED,
            error(409, "pairing_already_redeemed") to PairingFailure.ALREADY_USED,
            error(403, "invalid_pairing_secret") to PairingFailure.INVALID_CODE,
            error(404, "pairing_not_found") to PairingFailure.INVALID_CODE,
            error(429, "rate_limited") to PairingFailure.RATE_LIMITED,
            error(500, "internal_error") to PairingFailure.SERVER,
            json(502, "<html>bad gateway</html>") to PairingFailure.SERVER,
            json(200, "this is not json") to PairingFailure.NETWORK,
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) to PairingFailure.NETWORK,
        )
        for ((response, expected) in cases) {
            store.let { runBlocking { it.setIdentity(null) } }
            assertEquals(PairingState.Failed(expected), redeemWith(response))
            assertNull("no peer may be stored after a failed pairing", store.peer.value)
        }
    }

    @Test fun `a relay that demands a registration code is reported as such`() = runBlocking {
        server.enqueue(error(403, "registration_code_required"))
        val m = manager(DeviceRole.COMPANION)
        m.startAsCompanion(url, null)
        assertEquals(PairingState.Failed(PairingFailure.REGISTRATION_CODE), m.state.value)
        assertNull(store.identity.value)
        // The code the user then types is sent with the registration.
        server.takeRequest()
        server.enqueue(registered)
        server.enqueue(error(500, "internal_error"))
        m.reset()
        m.startAsCompanion(url, "  correct-horse-battery  ")
        assertTrue(server.takeRequest().body.readUtf8().contains("\"registrationCode\":\"correct-horse-battery\""))
    }

    @Test fun `a companion code that is never scanned ends as expired`() = runBlocking {
        server.enqueue(registered)
        server.enqueue(json(201, """{"pairingId":"33333333-3333-4333-8333-333333333333","secret":"${"A".repeat(22)}","expiresAt":"2000-01-01T00:00:00.000Z"}"""))
        val m = manager(DeviceRole.COMPANION)
        m.startAsCompanion(url, null)
        assertEquals(PairingState.Failed(PairingFailure.EXPIRED), m.state.value)
    }

    @Test fun `an unusable server address is rejected before any request`() = runBlocking {
        val m = manager(DeviceRole.COMPANION)
        m.startAsCompanion("ftp://example.com", null)
        assertEquals(PairingState.Failed(PairingFailure.INVALID_CODE), m.state.value)
        assertEquals(0, server.requestCount)
    }

    // ---- upload and sync reactions ----

    private suspend fun pairedStore(): InMemoryIdentityStore {
        val other = CryptoSetup.generateInMemory()
        store.setIdentity(Identity(url, "11111111-1111-4111-8111-111111111111", "11111111-1111-4111-8111-111111111111.${"s".repeat(43)}", "source", 1))
        store.setPeer(Peer("22222222-2222-4222-8222-222222222222", CryptoUtil.b64(other.hpkePublicKey), CryptoUtil.b64(other.signingPublicKey), 1, "000 000"))
        return store
    }

    @Test fun `upload results decide between done retry and give up`() = runBlocking {
        pairedStore()
        var lost = 0
        val transport = RelayOutboxTransport(store, { crypto }, apiFactory, onAuthorizationLost = { lost++ })
        val body = """{"sender":"TESTBANK","body":"Synthetic","receivedAtMillis":5,"partCount":1,"subscriptionId":-1}""".toByteArray()
        suspend fun send(response: MockResponse): SendResult {
            server.enqueue(response)
            return transport.send("44444444-4444-4444-8444-444444444444", EnvelopeKind.SMS, body)
        }
        assertEquals(SendResult.ACCEPTED, send(json(201, """{"status":"accepted"}""")))
        assertEquals(SendResult.ACCEPTED, send(json(200, """{"status":"duplicate"}""")))
        assertEquals(SendResult.RETRY_LATER, send(error(429, "queue_full")))
        assertEquals(SendResult.RETRY_LATER, send(error(429, "rate_limited")))
        assertEquals(SendResult.RETRY_LATER, send(error(503, "unavailable")))
        assertEquals(SendResult.RETRY_LATER, send(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)))
        assertEquals(SendResult.REJECTED, send(error(413, "payload_too_large")))
        assertEquals(SendResult.REJECTED, send(error(400, "invalid_signature")))
        assertEquals(0, lost)
        assertEquals(SendResult.REJECTED, send(error(409, "not_linked")))
        assertEquals(SendResult.REJECTED, send(error(401, "unauthorized")))
        assertEquals(2, lost)
        // The plaintext never appears in what was sent.
        repeat(server.requestCount) {
            val sent = server.takeRequest().body.readUtf8()
            assertTrue(!sent.contains("TESTBANK") && !sent.contains("Synthetic"))
        }
        // A body that is not a JSON object is never uploaded.
        val before = server.requestCount
        assertEquals(SendResult.REJECTED, transport.send("44444444-4444-4444-8444-444444444444", EnvelopeKind.SMS, "not json".toByteArray()))
        assertEquals(before, server.requestCount)
    }

    @Test fun `sync reactions to relay answers`() = runBlocking {
        pairedStore()
        var lost = 0
        val sync = RelaySync(store, { crypto }, apiFactory, handler = { true }, onAuthorizationLost = { lost++ })
        server.enqueue(json(200, """{"messages":[],"more":false}"""))
        assertEquals(SyncOutcome.COMPLETE, sync.syncOnce())
        server.enqueue(error(503, "unavailable"))
        assertEquals(SyncOutcome.RETRY, sync.syncOnce())
        server.enqueue(error(401, "unauthorized"))
        assertEquals(SyncOutcome.UNAUTHORIZED, sync.syncOnce())
        assertEquals(1, lost)
        // Junk entries are acknowledged away instead of blocking the queue for ever.
        server.enqueue(json(200, """{"messages":[{"messageId":"55555555-5555-4555-8555-555555555555","senderId":"22222222-2222-4222-8222-222222222222","ciphertext":"AAAA","signature":"AAAA","createdAt":"x"}],"more":false}"""))
        server.enqueue(json(200, """{"acknowledged":1}"""))
        assertEquals(SyncOutcome.COMPLETE, sync.syncOnce())
        assertEquals(1, sync.status.value.rejected)
        // A relay that claims "more" for ever cannot trap the device in a loop.
        repeat(60) { server.enqueue(json(200, """{"messages":[{"messageId":"55555555-5555-4555-8555-555555555555","senderId":"x","ciphertext":"AAAA","signature":"AAAA","createdAt":"x"}],"more":true}""")); server.enqueue(json(200, """{"acknowledged":1}""")) }
        assertEquals(SyncOutcome.RETRY, sync.syncOnce())
    }

    @Test fun `api client maps transport and protocol failures`() = runBlocking {
        val api = RelayApi(client, url, "t")
        server.enqueue(json(200, """{"deviceId":"d","role":"source"}"""))
        assertTrue(api.me() is ApiResult.Ok)
        assertEquals("Bearer t", server.takeRequest().getHeader("Authorization"))
        server.enqueue(json(404, "{}"))
        assertEquals(ApiResult.Error(404, "http_404"), api.me())
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(api.deleteDevice() is ApiResult.Ok)
        server.shutdown()
        assertEquals(ApiResult.Unreachable, api.me())
    }
}
