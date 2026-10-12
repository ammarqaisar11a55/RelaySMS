package io.github.ammarqaisar11a55.relaysms

import android.os.SystemClock
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ammarqaisar11a55.relaysms.core.model.DeviceRole
import io.github.ammarqaisar11a55.relaysms.core.security.CryptoSetup
import io.github.ammarqaisar11a55.relaysms.core.security.CryptoUtil
import io.github.ammarqaisar11a55.relaysms.pairing.Identity
import io.github.ammarqaisar11a55.relaysms.pairing.Peer
import io.github.ammarqaisar11a55.relaysms.sms.IncomingSms
import io.github.ammarqaisar11a55.relaysms.sms.SmsOutcome
import io.github.ammarqaisar11a55.relaysms.sms.SmsPart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Timings on a real Android runtime with the real database and Keystore-backed cipher. */
@RunWith(AndroidJUnit4::class)
class PerformanceOnDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val container get() = (rule.activity.application as RelaySmsApp).container

    @After fun clean(): Unit = runBlocking { container.resetCoordinator.resetEverything() }

    @Test fun inboxWithALargerHistoryLoadsScrollsAndSearches(): Unit = runBlocking {
        container.resetCoordinator.resetEverything()
        container.preferences.setRole(DeviceRole.COMPANION)
        val now = System.currentTimeMillis()
        val count = 1_500
        val insertMs = SystemClock.elapsedRealtime().let { start ->
            for (i in 0 until count) {
                container.inboxRepository.storeIncoming("m$i", IncomingSms(if (i % 4 == 0) "TESTBANK" else "Sender$i", "Synthetic message number $i", now - i * 1000L, 1))
            }
            SystemClock.elapsedRealtime() - start
        }
        val loadMs = SystemClock.elapsedRealtime().let { start ->
            assertEquals(count, container.inboxRepository.messages.first().size)
            SystemClock.elapsedRealtime() - start
        }
        // Generous bounds for a software-rendered emulator; a phone is several times faster.
        assertTrue("storing $count messages took $insertMs ms", insertMs < 120_000)
        assertTrue("loading and decrypting $count messages took $loadMs ms", loadMs < 15_000)

        rule.waitUntil(30_000) { rule.onAllNodes(hasTestTag("message_m0")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("inbox_list").performScrollToIndex(count - 1)
        rule.waitUntil(15_000) { rule.onAllNodes(hasTestTag("message_m${count - 1}")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("inbox_search_toggle").performClick()
        rule.onNodeWithTag("inbox_search").performTextInput("number 1499")
        rule.waitUntil(15_000) {
            rule.onAllNodes(hasTestTag("message_m1499")).fetchSemanticsNodes().isNotEmpty() &&
                rule.onAllNodes(hasTestTag("message_m1498")).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test fun handlingAnIncomingSmsIsFarInsideTheReceiverBudget(): Unit = runBlocking {
        container.resetCoordinator.resetEverything()
        container.preferences.setRole(DeviceRole.SOURCE)
        val peer = CryptoSetup.generateInMemory()
        container.identityStore.setIdentity(Identity("http://10.0.2.2:9", "00000000-0000-4000-8000-000000000002", "unused", "source", 0))
        container.identityStore.setPeer(Peer("00000000-0000-4000-8000-000000000001", CryptoUtil.b64(peer.hpkePublicKey), CryptoUtil.b64(peer.signingPublicKey), 1, "000 000"))
        container.preferences.setForwardingEnabled(true)
        container.preferences.setAllowlistOnly(false)
        // First call pays for opening the database and the Keystore-wrapped key.
        val first = SystemClock.elapsedRealtime().let { s ->
            assertEquals(SmsOutcome.QUEUED, container.incomingSmsProcessor.process(listOf(SmsPart("TESTBANK", "Synthetic warm-up", 1)), -1, 1))
            SystemClock.elapsedRealtime() - s
        }
        val times = (1..20).map { i ->
            val s = SystemClock.elapsedRealtime()
            assertEquals(SmsOutcome.QUEUED, container.incomingSmsProcessor.process(List(3) { SmsPart("TESTBANK", "Synthetic part $i-$it ".repeat(6), 1000L + i) }, -1, 1000L + i))
            SystemClock.elapsedRealtime() - s
        }
        // The receiver may run for about ten seconds; the app allows itself seven.
        assertTrue("first SMS took $first ms", first < 5_000)
        assertTrue("slowest of 20 took ${times.max()} ms", times.max() < 2_000)
        android.util.Log.i("RelaySmsPerf", "sms_processing first=${first}ms median=${times.sorted()[10]}ms max=${times.max()}ms")
    }
}
