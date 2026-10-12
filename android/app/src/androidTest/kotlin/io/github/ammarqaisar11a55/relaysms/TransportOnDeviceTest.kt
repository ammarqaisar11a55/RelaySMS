package io.github.ammarqaisar11a55.relaysms

import android.util.Log
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ammarqaisar11a55.relaysms.core.database.OutboxState
import io.github.ammarqaisar11a55.relaysms.core.model.DeviceRole
import io.github.ammarqaisar11a55.relaysms.delivery.SendResult
import io.github.ammarqaisar11a55.relaysms.sms.EventId
import io.github.ammarqaisar11a55.relaysms.sms.IncomingSms
import io.github.ammarqaisar11a55.relaysms.transport.SyncOutcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end transport with the real app on one side and a simulated phone on the other, through
 * a real relay. Run with scripts/device-e2e.sh (and scripts/emulator-forward-check.sh for the
 * test that needs SMS injected from the host).
 */
@RunWith(AndroidJUnit4::class)
class TransportOnDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val args = InstrumentationRegistry.getArguments()
    private val relayUrl: String = args.getString("relayUrl").orEmpty()
    private val container get() = (rule.activity.application as RelaySmsApp).container

    @Before fun setUp() = assumeTrue("relayUrl instrumentation argument not set", relayUrl.isNotEmpty())

    private fun pairAppAs(role: DeviceRole, peer: TestPhone) = runBlocking {
        container.resetCoordinator.resetEverything()
        container.preferences.setRole(role)
        val app = container.newPairingManager(role)
        if (role == DeviceRole.COMPANION) pairManagers(peer.pairing, app, relayUrl) else pairManagers(app, peer.pairing, relayUrl)
        peer.link.onPaired()
        container.onPairingCompleted()
    }

    @Test fun appAsCompanionReceivesDecryptsStoresAndShowsAForwardedSms() {
        val source = TestPhone(DeviceRole.SOURCE)
        pairAppAs(DeviceRole.COMPANION, source)
        val sms = IncomingSms("TESTBANK", "Synthetic code 864213. Valid for 5 minutes.", System.currentTimeMillis() - 4_000, 1)
        runBlocking {
            assertEquals(SendResult.ACCEPTED, source.forward(sms))
            // Uploading twice must not produce two inbox entries.
            assertEquals(SendResult.ACCEPTED, source.forward(sms))
            assertEquals(SyncOutcome.COMPLETE, container.relaySync.syncOnce())
            assertEquals(SyncOutcome.COMPLETE, container.relaySync.syncOnce())

            val stored = container.inboxRepository.messages.first()
            assertEquals(1, stored.size)
            assertEquals(EventId.of(sms), stored[0].id)
            assertEquals(sms.sender, stored[0].address)
            assertEquals(sms.body, stored[0].body)
            assertEquals(sms.receivedAtMillis, stored[0].receivedAtMillis)
            // Acknowledged on the relay.
            assertEquals(0, source.link.refresh().awaitingPeer)
        }
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("message_${EventId.of(sms)}")).fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Synthetic code 864213", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun appAsCompanionGetsALiveHintWhileOpenAndFetchesWithoutBeingAsked() {
        val source = TestPhone(DeviceRole.SOURCE)
        pairAppAs(DeviceRole.COMPANION, source)
        // The activity is in the foreground, so the hint channel connects by itself.
        rule.waitUntil(20_000) { container.relaySocket.state.value == io.github.ammarqaisar11a55.relaysms.transport.SocketState.CONNECTED }
        val sms = IncomingSms("5550100", "Synthetic live message", System.currentTimeMillis(), 1)
        runBlocking { assertEquals(SendResult.ACCEPTED, source.forward(sms)) }
        rule.waitUntil(20_000) { runBlocking { container.inboxRepository.count() } == 1 }
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Synthetic live message", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * Host-driven: scripts/emulator-forward-check.sh injects two real SMS into the emulator's
     * modem once this test logs E2E_READY. The app's own receiver, filter, outbox, worker and
     * transport must deliver exactly the allowed ones to the simulated companion.
     */
    @Test fun appAsSourceForwardsRealIncomingSmsToTheCompanion() {
        assumeTrue(args.getString("step") == "forward")
        val companion = TestPhone(DeviceRole.COMPANION)
        pairAppAs(DeviceRole.SOURCE, companion)
        runBlocking {
            container.sourceStores.addFilter("TESTBANK", System.currentTimeMillis())
            container.preferences.setForwardingEnabled(true)
        }
        val started = System.currentTimeMillis()
        Log.i("RelaySmsE2E", "E2E_READY")

        val expectedShort = "Synthetic code 246810"
        val expectedLong = (1..12).joinToString("") { "Multipart synthetic text %03d. ".format(it) }
        runBlocking {
            val deadline = System.currentTimeMillis() + 60_000
            while (companion.received.size < 2 && System.currentTimeMillis() < deadline) {
                companion.sync.syncOnce()
                delay(500)
            }
            val got = companion.received.values.toList()
            assertEquals("exactly the two allowed messages must arrive", 2, got.size)
            assertEquals(setOf(expectedShort, expectedLong), got.map { it.body }.toSet())
            assertTrue(got.all { it.sender == "TESTBANK" })
            assertTrue(got.all { it.receivedAtMillis in (started - 120_000)..(System.currentTimeMillis() + 120_000) })
            assertTrue("multipart message must be one entry with several parts", got.single { it.body == expectedLong }.partCount > 1)
            assertFalse("the filtered sender must not be forwarded", got.any { it.body.contains("personal") })

            // The source's queue is drained and holds no message content any more.
            val deadline2 = System.currentTimeMillis() + 20_000
            while (container.database.outboxDao().pendingCount() > 0 && System.currentTimeMillis() < deadline2) delay(300)
            assertEquals(0, container.database.outboxDao().pendingCount())
            assertEquals(2, container.database.outboxDao().observeCount(OutboxState.DELIVERED).first())
            assertEquals(1, container.preferences.stats.first().filtered)
        }
    }
}
