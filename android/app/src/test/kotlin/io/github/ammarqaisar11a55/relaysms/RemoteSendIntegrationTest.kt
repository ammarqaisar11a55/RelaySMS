package io.github.ammarqaisar11a55.relaysms

import io.github.ammarqaisar11a55.relaysms.core.model.DeviceRole
import io.github.ammarqaisar11a55.relaysms.delivery.SendResult as UploadResult
import io.github.ammarqaisar11a55.relaysms.sms.send.SendFailure
import io.github.ammarqaisar11a55.relaysms.sms.send.SendStatus
import io.github.ammarqaisar11a55.relaysms.sms.send.SmsSendOutcome
import io.github.ammarqaisar11a55.relaysms.transport.SyncOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Sending an SMS from the companion through the source phone, across a real relay. */
class RemoteSendIntegrationTest {
    @Before fun requireRelay() = assumeRelay()

    @Test fun `a confirmed message travels encrypted to the source is sent once and the outcome returns`(): Unit = runBlocking {
        val source = TestPhone(DeviceRole.SOURCE); val companion = TestPhone(DeviceRole.COMPANION)
        pair(source, companion)
        val (id, upload) = companion.requestSend("+92 300 1234567", "Synthetic outgoing 5551")
        assertEquals(UploadResult.ACCEPTED, upload)
        // The relay holds only ciphertext of the request.
        val wire = (source.api.pendingMessages() as io.github.ammarqaisar11a55.relaysms.core.network.ApiResult.Ok).value.messages.single()
        assertFalse(wire.ciphertext.contains("5551"))
        assertFalse(wire.ciphertext.contains("923001234567"))

        assertEquals(SyncOutcome.COMPLETE, source.sync.syncOnce())
        assertEquals(listOf(Triple(id, "+923001234567", "Synthetic outgoing 5551")), source.smsSender.sent)
        // Nothing is claimed before the network answers.
        assertEquals(SyncOutcome.COMPLETE, companion.sync.syncOnce())
        assertTrue(companion.sendResults.isEmpty())

        source.sendHandler.onOutcome(id, SmsSendOutcome.Sent)
        assertEquals(SyncOutcome.COMPLETE, companion.sync.syncOnce())
        assertEquals(listOf(id to SendStatus.SENT), companion.sendResults.map { it.requestId to it.status })
        source.sendHandler.onOutcome(id, SmsSendOutcome.Delivered)
        companion.sync.syncOnce()
        assertEquals(listOf(SendStatus.SENT, SendStatus.DELIVERED), companion.sendResults.map { it.status })
    }

    @Test fun `a request uploaded twice or replayed is sent only once`(): Unit = runBlocking {
        val source = TestPhone(DeviceRole.SOURCE); val companion = TestPhone(DeviceRole.COMPANION)
        pair(source, companion)
        val (id, _) = companion.requestSend("03001234567", "Synthetic once")
        // Same id again: the relay treats it as a duplicate.
        assertEquals(UploadResult.ACCEPTED, companion.requestSend("03001234567", "Synthetic once", id).second)
        source.sync.syncOnce()
        source.sendHandler.onOutcome(id, SmsSendOutcome.Sent)
        // After the source acknowledged it, the same id is uploaded once more (a replay).
        assertEquals(UploadResult.ACCEPTED, companion.requestSend("03001234567", "Synthetic once", id).second)
        source.sync.syncOnce()
        assertEquals(1, source.smsSender.sent.size)
    }

    @Test fun `the source refuses when its owner has not allowed remote sending`(): Unit = runBlocking {
        val source = TestPhone(DeviceRole.SOURCE); val companion = TestPhone(DeviceRole.COMPANION)
        pair(source, companion)
        source.remoteSendingEnabled = false
        val (id, _) = companion.requestSend("03001234567", "Synthetic refused")
        source.sync.syncOnce()
        assertTrue(source.smsSender.sent.isEmpty())
        companion.sync.syncOnce()
        assertEquals(listOf(Triple(id, SendStatus.FAILED, SendFailure.DISABLED)), companion.sendResults.map { Triple(it.requestId, it.status, it.reason) })
    }

    @Test fun `only the paired companion can make the source send`(): Unit = runBlocking {
        val source = TestPhone(DeviceRole.SOURCE); val companion = TestPhone(DeviceRole.COMPANION)
        pair(source, companion)
        val strangerSource = TestPhone(DeviceRole.SOURCE); val stranger = TestPhone(DeviceRole.COMPANION)
        pair(strangerSource, stranger)
        // The stranger aims at our source: its own stored peer is swapped for ours.
        stranger.store.setPeer(companion.store.peer.value)
        assertEquals(UploadResult.REJECTED, stranger.requestSend("03001234567", "Synthetic injected").second)
        assertEquals(SyncOutcome.COMPLETE, source.sync.syncOnce())
        assertTrue(source.smsSender.sent.isEmpty())
        // A source cannot ask a companion to send either: the kind is ignored in that direction.
        source.transport.send(java.util.UUID.randomUUID().toString(), io.github.ammarqaisar11a55.relaysms.sms.send.SendKinds.REQUEST,
            """{"to":"03001234567","text":"x","createdAtMillis":${System.currentTimeMillis()},"expiresAtMillis":${System.currentTimeMillis() + 60000}}""".toByteArray())
        companion.sync.syncOnce()
        assertTrue(companion.smsSender.sent.isEmpty())
    }

    @Test fun `revoking the companion ends remote sending at once`(): Unit = runBlocking {
        val source = TestPhone(DeviceRole.SOURCE); val companion = TestPhone(DeviceRole.COMPANION)
        pair(source, companion)
        // A request is already waiting on the relay when the owner revokes.
        companion.requestSend("03001234567", "Synthetic queued before revocation")
        assertTrue(source.link.revokePeer())
        assertEquals(SyncOutcome.NOT_CONFIGURED, source.sync.syncOnce())
        assertEquals(UploadResult.REJECTED, companion.requestSend("03001234567", "Synthetic after revocation").second)
        assertTrue(source.smsSender.sent.isEmpty())
    }

    @Test fun `while the source is offline the request waits and nothing is reported as sent`(): Unit = runBlocking {
        val source = TestPhone(DeviceRole.SOURCE); val companion = TestPhone(DeviceRole.COMPANION)
        pair(source, companion)
        val (id, upload) = companion.requestSend("03001234567", "Synthetic waiting")
        assertEquals(UploadResult.ACCEPTED, upload)
        repeat(2) { companion.sync.syncOnce() }
        assertTrue(companion.sendResults.isEmpty())
        assertEquals(1, companion.link.refresh().awaitingPeer)
        // The source comes online later.
        source.sync.syncOnce()
        source.sendHandler.onOutcome(id, SmsSendOutcome.Failed(SendFailure.CARRIER_REJECTED))
        companion.sync.syncOnce()
        assertEquals(SendFailure.CARRIER_REJECTED, companion.sendResults.single().reason)
    }
}
