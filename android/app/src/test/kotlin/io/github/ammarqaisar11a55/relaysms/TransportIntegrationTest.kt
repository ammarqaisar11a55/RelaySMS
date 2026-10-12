package io.github.ammarqaisar11a55.relaysms

import io.github.ammarqaisar11a55.relaysms.core.model.DeviceRole
import io.github.ammarqaisar11a55.relaysms.core.network.ApiResult
import io.github.ammarqaisar11a55.relaysms.core.network.RelayApi
import io.github.ammarqaisar11a55.relaysms.delivery.SendResult
import io.github.ammarqaisar11a55.relaysms.sms.EventId
import io.github.ammarqaisar11a55.relaysms.sms.IncomingSms
import io.github.ammarqaisar11a55.relaysms.transport.SyncOutcome
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** The source's upload path and the companion's sync path, run against a real relay. */
class TransportIntegrationTest {
    @Before fun requireRelay() = assumeRelay()

    private val sms = IncomingSms("TESTBANK", "Synthetic code 135790. Valid for 5 minutes.", 1_790_000_000_123, 1, -1)

    @Test fun `an sms reaches the companion with identical sender body and timestamp`() = runBlocking {
        val source = TestPhone(DeviceRole.SOURCE); val companion = TestPhone(DeviceRole.COMPANION)
        pair(source, companion)
        val multipart = IncomingSms("+923001234567", "Long synthetic text. ".repeat(30), 1_790_000_111_000, 4, 2)
        assertEquals(SendResult.ACCEPTED, source.forward(sms))
        assertEquals(SendResult.ACCEPTED, source.forward(multipart))

        // What the relay holds and hands out is ciphertext only.
        val pending = (companion.api.pendingMessages() as ApiResult.Ok).value.messages
        assertEquals(2, pending.size)
        val wire = pending.joinToString("|") { it.ciphertext + it.signature + it.messageId }
        for (secret in listOf("TESTBANK", "135790", "Synthetic", "923001234567")) assertFalse(wire.contains(secret))
        assertEquals(2, source.link.refresh().awaitingPeer)

        assertEquals(SyncOutcome.COMPLETE, companion.sync.syncOnce())
        assertEquals(listOf(sms, multipart), companion.received.values.toList())
        assertEquals(listOf(EventId.of(sms), EventId.of(multipart)), companion.received.keys.toList())
        // Acknowledged: nothing is left on the relay.
        assertTrue((companion.api.pendingMessages() as ApiResult.Ok).value.messages.isEmpty())
        assertEquals(0, source.link.refresh().awaitingPeer)
        assertEquals(2, companion.sync.status.value.stored)
    }

    @Test fun `repeated uploads and repeated syncs never duplicate a message`() = runBlocking {
        val source = TestPhone(DeviceRole.SOURCE); val companion = TestPhone(DeviceRole.COMPANION)
        pair(source, companion)
        repeat(3) { assertEquals(SendResult.ACCEPTED, source.forward(sms)) }
        assertEquals(1, (companion.api.pendingMessages() as ApiResult.Ok).value.messages.size)
        assertEquals(SyncOutcome.COMPLETE, companion.sync.syncOnce())
        // The source lost the relay's answer and uploads again after the companion acknowledged.
        assertEquals(SendResult.ACCEPTED, source.forward(sms))
        assertEquals(SyncOutcome.COMPLETE, companion.sync.syncOnce())
        assertEquals(1, companion.received.size)
        assertTrue((companion.api.pendingMessages() as ApiResult.Ok).value.messages.isEmpty())
    }

    @Test fun `a lost acknowledgement causes redelivery but no duplicate entry`() = runBlocking {
        val dropAck = AtomicInteger(1)
        val flaky = Interceptor { chain ->
            if (chain.request().url.encodedPath.endsWith("/messages/ack") && dropAck.getAndDecrement() > 0) throw IOException("connection lost")
            chain.proceed(chain.request())
        }
        val source = TestPhone(DeviceRole.SOURCE)
        val companion = TestPhone(DeviceRole.COMPANION, RelayApi.newHttpClient().newBuilder().addInterceptor(flaky).retryOnConnectionFailure(false).build())
        pair(source, companion)
        source.forward(sms)
        assertEquals(SyncOutcome.RETRY, companion.sync.syncOnce())
        assertEquals(1, companion.received.size)
        // Still on the relay because the acknowledgement never arrived.
        assertEquals(1, (companion.api.pendingMessages() as ApiResult.Ok).value.messages.size)
        assertEquals(SyncOutcome.COMPLETE, companion.sync.syncOnce())
        assertEquals(1, companion.received.size)
        assertTrue((companion.api.pendingMessages() as ApiResult.Ok).value.messages.isEmpty())
    }

    @Test fun `a storage failure on the companion leaves the message on the relay`() = runBlocking {
        val source = TestPhone(DeviceRole.SOURCE); val companion = TestPhone(DeviceRole.COMPANION)
        pair(source, companion)
        source.forward(sms)
        companion.handlerFailuresToInject = 1
        assertEquals(SyncOutcome.RETRY, companion.sync.syncOnce())
        assertTrue(companion.received.isEmpty())
        assertEquals(1, (companion.api.pendingMessages() as ApiResult.Ok).value.messages.size)
        assertEquals(SyncOutcome.COMPLETE, companion.sync.syncOnce())
        assertEquals(listOf(sms), companion.received.values.toList())
    }

    @Test fun `messages queue while the companion is offline and arrive in order later`() = runBlocking {
        val source = TestPhone(DeviceRole.SOURCE); val companion = TestPhone(DeviceRole.COMPANION)
        pair(source, companion)
        val batch = (1..60).map { IncomingSms("TESTBANK", "Synthetic message number $it", 1_790_000_000_000 + it, 1) }
        batch.forEach { assertEquals(SendResult.ACCEPTED, source.forward(it)) }
        // More than one page (50) is drained in a single sync.
        assertEquals(SyncOutcome.COMPLETE, companion.sync.syncOnce())
        assertEquals(batch, companion.received.values.toList())
    }

    @Test fun `an unreachable relay means retry later not loss`() = runBlocking {
        val offline = Interceptor { chain ->
            if (chain.request().url.encodedPath.endsWith("/messages") || chain.request().url.encodedPath.contains("/messages/pending")) throw IOException("offline")
            chain.proceed(chain.request())
        }
        val client = RelayApi.newHttpClient().newBuilder().addInterceptor(offline).retryOnConnectionFailure(false).build()
        val source = TestPhone(DeviceRole.SOURCE, client); val companion = TestPhone(DeviceRole.COMPANION, client)
        pair(source, companion)
        assertEquals(SendResult.RETRY_LATER, source.forward(sms))
        assertEquals(SyncOutcome.RETRY, companion.sync.syncOnce())
        assertEquals(0L, companion.sync.status.value.lastCompleteAtMillis)
    }

    @Test fun `an unpaired phone sends and receives nothing`() = runBlocking {
        val source = TestPhone(DeviceRole.SOURCE)
        assertEquals(SendResult.NOT_CONFIGURED, source.forward(sms))
        assertEquals(SyncOutcome.NOT_CONFIGURED, source.sync.syncOnce())
    }

    @Test fun `after revocation the source is refused and the companion receives nothing more`() = runBlocking {
        val source = TestPhone(DeviceRole.SOURCE); val companion = TestPhone(DeviceRole.COMPANION)
        pair(source, companion)
        assertEquals(SendResult.ACCEPTED, source.forward(sms))
        // Revoked from the companion while a message is still queued on the relay.
        assertTrue(companion.link.revokePeer())
        assertEquals(SyncOutcome.NOT_CONFIGURED, companion.sync.syncOnce())
        assertTrue(companion.received.isEmpty())

        // The source still believes it is paired; the relay refuses and the source drops its peer.
        val later = sms.copy(receivedAtMillis = sms.receivedAtMillis + 1)
        assertEquals(SendResult.REJECTED, source.forward(later))
        assertNull(source.store.peer.value)
        assertEquals(1, source.peerRemovedCount)
        assertEquals(SendResult.NOT_CONFIGURED, source.forward(later))
    }

    @Test fun `ciphertext altered by the relay is dropped and does not block later messages`() = runBlocking {
        val corruptFirst = AtomicInteger(1)
        val hostile = Interceptor { chain ->
            val response = chain.proceed(chain.request())
            if (!chain.request().url.encodedPath.contains("/messages/pending") || corruptFirst.getAndDecrement() <= 0) return@Interceptor response
            val body = response.body!!.string()
            // Flip one character of the first ciphertext.
            val marker = "\"ciphertext\":\""
            val at = body.indexOf(marker) + marker.length
            val altered = body.substring(0, at) + (if (body[at] == 'A') 'B' else 'A') + body.substring(at + 1)
            response.newBuilder().body(altered.toResponseBody("application/json".toMediaType())).build()
        }
        val source = TestPhone(DeviceRole.SOURCE)
        val companion = TestPhone(DeviceRole.COMPANION, RelayApi.newHttpClient().newBuilder().addInterceptor(hostile).build())
        pair(source, companion)
        val second = sms.copy(body = "Second synthetic message", receivedAtMillis = sms.receivedAtMillis + 5)
        source.forward(sms); source.forward(second)
        assertEquals(SyncOutcome.COMPLETE, companion.sync.syncOnce())
        assertEquals(listOf(second), companion.received.values.toList())
        assertEquals(1, companion.sync.status.value.rejected)
    }

    @Test fun `a third device cannot read or inject messages of a pair`() = runBlocking {
        val source = TestPhone(DeviceRole.SOURCE); val companion = TestPhone(DeviceRole.COMPANION)
        pair(source, companion)
        val otherSource = TestPhone(DeviceRole.SOURCE); val otherCompanion = TestPhone(DeviceRole.COMPANION)
        pair(otherSource, otherCompanion)
        source.forward(sms)
        assertEquals(SyncOutcome.COMPLETE, otherCompanion.sync.syncOnce())
        assertTrue(otherCompanion.received.isEmpty())
        // Even if the other pair's source believed our companion were its peer, the relay routes
        // by its own link table and checks the signature against that recipient: the upload is
        // refused, and nothing reaches either companion.
        otherSource.store.setPeer(source.store.peer.value)
        assertEquals(SendResult.REJECTED, otherSource.forward(sms.copy(body = "injected")))
        assertEquals(SyncOutcome.COMPLETE, companion.sync.syncOnce())
        assertEquals(listOf(sms), companion.received.values.toList())
        assertTrue((otherCompanion.api.pendingMessages() as ApiResult.Ok).value.messages.isEmpty())
    }
}
