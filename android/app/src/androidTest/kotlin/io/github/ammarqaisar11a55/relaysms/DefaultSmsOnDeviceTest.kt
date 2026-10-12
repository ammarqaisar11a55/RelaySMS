package io.github.ammarqaisar11a55.relaysms

import android.net.Uri
import android.provider.Telephony
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ammarqaisar11a55.relaysms.core.database.AppDatabase
import io.github.ammarqaisar11a55.relaysms.core.database.MessageOrigin
import io.github.ammarqaisar11a55.relaysms.core.database.MessageStatus
import io.github.ammarqaisar11a55.relaysms.core.database.OutboxKind
import io.github.ammarqaisar11a55.relaysms.core.model.DeviceRole
import io.github.ammarqaisar11a55.relaysms.core.security.CryptoSetup
import io.github.ammarqaisar11a55.relaysms.core.security.CryptoUtil
import io.github.ammarqaisar11a55.relaysms.inbox.InboxRepository
import io.github.ammarqaisar11a55.relaysms.inbox.MessageMirror
import io.github.ammarqaisar11a55.relaysms.pairing.Identity
import io.github.ammarqaisar11a55.relaysms.pairing.Peer
import io.github.ammarqaisar11a55.relaysms.sms.IncomingSms
import io.github.ammarqaisar11a55.relaysms.sms.defaultapp.DefaultSmsRole
import io.github.ammarqaisar11a55.relaysms.sms.defaultapp.ProviderMirror
import io.github.ammarqaisar11a55.relaysms.sms.defaultapp.SmsProviderGateway
import io.github.ammarqaisar11a55.relaysms.sms.send.SendKinds
import io.github.ammarqaisar11a55.relaysms.sms.send.SendStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DefaultSmsOnDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val container get() = (rule.activity.application as RelaySmsApp).container
    private val args = InstrumentationRegistry.getArguments()
    private val now = System.currentTimeMillis()
    private val sms = IncomingSms("TESTBANK", "Synthetic mirrored 314159", now - 5_000, 1)

    @After fun clean(): Unit = runBlocking { container.resetCoordinator.resetEverything() }

    private fun waitFor(tag: String) = rule.waitUntil(20_000) { rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    // ---- mirroring rules, with a recording stand-in for Android's SMS store ----

    private class RecordingGateway : SmsProviderGateway {
        data class Row(val address: String, val body: String, val date: Long, var read: Boolean, val type: Int)
        val rows = LinkedHashMap<Long, Row>()
        var refuse = false
        private var next = 100L
        override fun insert(address: String, body: String, dateMillis: Long, read: Boolean, type: Int): Long? {
            if (refuse) return null
            rows[++next] = Row(address, body, dateMillis, read, type)
            return next
        }
        override fun markRead(providerId: Long) { rows[providerId]?.read = true }
        override fun delete(providerId: Long) { rows.remove(providerId) }
    }

    private fun mirroredRepo(gateway: RecordingGateway, isDefault: () -> Boolean): Pair<AppDatabase, InboxRepository> {
        val db = Room.inMemoryDatabaseBuilder(rule.activity, AppDatabase::class.java).build()
        lateinit var repo: InboxRepository
        val mirror = ProviderMirror(db.messageDao(), gateway, isDefault) { repo.reveal(it) }
        repo = InboxRepository(db.messageDao(), container.localCipher, mirror = object : MessageMirror {
            override suspend fun onStored() = mirror.onStored()
            override suspend fun onRead(providerId: Long) = mirror.onRead(providerId)
            override suspend fun onDeleted(providerIds: List<Long>) = mirror.onDeleted(providerIds)
        })
        return db to repo
    }

    @Test fun whileDefaultEachMessageIsCopiedToTheSystemStoreExactlyOnce(): Unit = runBlocking {
        val gateway = RecordingGateway()
        val (db, repo) = mirroredRepo(gateway) { true }
        try {
            assertTrue(repo.storeIncoming("m1", sms))
            val row = gateway.rows.values.single()
            assertEquals(RecordingGateway.Row("TESTBANK", "Synthetic mirrored 314159", sms.receivedAtMillis, false, Telephony.Sms.MESSAGE_TYPE_INBOX), row)
            // Re-delivery and repeated synchronisation add nothing.
            assertFalse(repo.storeIncoming("m1", sms))
            repeat(3) { repo.applyRetention(0) }
            assertTrue(repo.storeIncoming("m2", sms.copy(body = "Second synthetic", receivedAtMillis = now)))
            assertEquals(2, gateway.rows.size)
            // Read state and deletion follow RelaySMS's inbox.
            repo.markRead("m1")
            assertTrue(gateway.rows.values.first().read)
            repo.delete("m1")
            assertEquals(listOf("Second synthetic"), gateway.rows.values.map { it.body })
            // Retention removes the system copy together with the message.
            repo.storeIncoming("old", sms.copy(body = "Old synthetic", receivedAtMillis = now - 40L * 24 * 3_600_000))
            assertEquals(2, gateway.rows.size)
            repo.applyRetention(30)
            assertEquals(listOf("Second synthetic"), gateway.rows.values.map { it.body })
            repo.deleteAll()
            assertTrue(gateway.rows.isEmpty())
        } finally {
            db.close()
        }
    }

    @Test fun withoutTheRoleNothingIsWrittenAndGainingItLaterBackfillsOnce(): Unit = runBlocking {
        val gateway = RecordingGateway()
        var isDefault = false
        val (db, repo) = mirroredRepo(gateway) { isDefault }
        try {
            repo.storeIncoming("m1", sms)
            repo.storeIncoming("m2", sms.copy(body = "Second synthetic", receivedAtMillis = now))
            repo.markRead("m1"); repo.delete("m2")
            assertTrue("companion-only mode must not touch the system store", gateway.rows.isEmpty())
            assertEquals(1, repo.count())

            isDefault = true
            repo.applyRetention(0)
            assertEquals(listOf("Synthetic mirrored 314159"), gateway.rows.values.map { it.body })
            assertTrue("the read state is carried over", gateway.rows.values.single().read)

            // Switching away again: RelaySMS keeps its own inbox and stops writing.
            isDefault = false
            repo.storeIncoming("m3", sms.copy(body = "Third synthetic", receivedAtMillis = now + 1))
            assertEquals(1, gateway.rows.size)
            assertEquals(2, repo.count())
            assertEquals(listOf("Third synthetic", "Synthetic mirrored 314159"), repo.messages.first().map { it.body })
        } finally {
            db.close()
        }
    }

    @Test fun aRefusedWriteIsRetriedLaterAndOutgoingMessagesAreMirroredOnlyOnceSent(): Unit = runBlocking {
        val gateway = RecordingGateway().apply { refuse = true }
        val (db, repo) = mirroredRepo(gateway) { true }
        try {
            repo.storeIncoming("m1", sms)
            assertTrue(gateway.rows.isEmpty())
            assertNull(db.messageDao().find("m1")!!.providerId)
            gateway.refuse = false
            repo.applyRetention(0)
            assertEquals(1, gateway.rows.size)

            repo.storeOutgoing("o1", "+923001234567", "Synthetic outgoing", MessageOrigin.RELAY)
            repo.applyRetention(0)
            assertEquals("a pending message is not in the system store", 1, gateway.rows.size)
            assertTrue(repo.applySendResult("o1", SendStatus.SENT, null))
            assertEquals(Telephony.Sms.MESSAGE_TYPE_SENT, gateway.rows.values.last().type)
            assertEquals(2, gateway.rows.size)
            // Results only move forward.
            assertTrue(repo.applySendResult("o1", SendStatus.DELIVERED, null))
            assertFalse(repo.applySendResult("o1", SendStatus.SENT, null))
            assertFalse(repo.applySendResult("o1", SendStatus.FAILED, "carrier_rejected"))
            assertEquals(MessageStatus.DELIVERED, db.messageDao().find("o1")!!.status)
            assertEquals(2, gateway.rows.size)
        } finally {
            db.close()
        }
    }

    // ---- the real Android SMS store; needs the role, which the scenario script grants ----

    private fun systemRows(body: String): Int =
        rule.activity.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID), "${Telephony.Sms.BODY} = ?", arrayOf(body), null)
            ?.use { it.count } ?: -1

    @Test fun asDefaultAppForwardedMessagesReachAndroidsSmsStoreOnceAndFollowDeletion(): Unit = runBlocking {
        assumeTrue("RelaySMS is not the default SMS app on this device", DefaultSmsRole.isHeld(rule.activity))
        container.resetCoordinator.resetEverything()
        container.preferences.setRole(DeviceRole.COMPANION)
        val body = "Synthetic system store ${System.nanoTime()}"
        val message = IncomingSms("TESTBANK", body, now - 1000, 1)
        assertTrue(container.inboxRepository.storeIncoming("sys1", message))
        assertEquals(1, systemRows(body))
        assertFalse(container.inboxRepository.storeIncoming("sys1", message))
        container.applyRetention()
        assertEquals("no duplicate after re-sync", 1, systemRows(body))
        // Sender and time are preserved in the system copy.
        rule.activity.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.DATE, Telephony.Sms.READ), "${Telephony.Sms.BODY} = ?", arrayOf(body), null,
        )!!.use {
            assertTrue(it.moveToFirst())
            assertEquals("TESTBANK", it.getString(0)); assertEquals(message.receivedAtMillis, it.getLong(1)); assertEquals(0, it.getInt(2))
        }
        container.inboxRepository.delete("sys1")
        assertEquals(0, systemRows(body))
    }

    @Test fun defaultRoleStatusIsReadFromAndroid() {
        val expected = args.getString("expectDefault")
        assumeTrue(expected != null)
        assertEquals(expected == "yes", DefaultSmsRole.isHeld(rule.activity))
        assertTrue(DefaultSmsRole.isAvailable(rule.activity))
    }

    // ---- compose screen ----

    private fun becomeCompanion(paired: Boolean) = runBlocking {
        container.resetCoordinator.resetEverything()
        container.preferences.setRole(DeviceRole.COMPANION)
        if (paired) {
            val peer = CryptoSetup.generateInMemory()
            container.identityStore.setIdentity(Identity("http://10.0.2.2:9", "00000000-0000-4000-8000-000000000002", "unused", "companion", now))
            container.identityStore.setPeer(Peer("00000000-0000-4000-8000-000000000001", CryptoUtil.b64(peer.hpkePublicKey), CryptoUtil.b64(peer.signingPublicKey), now, "000 000"))
        }
    }

    @Test fun nothingIsSentWithoutConfirmationAndAQueuedMessageIsShownAsNotSentYet() {
        becomeCompanion(paired = true)
        waitFor("inbox_compose")
        rule.onNodeWithTag("inbox_compose").performClick()
        waitFor("compose_to")
        rule.onNodeWithTag("compose_review").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("compose_to").performTextInput("0300 1234567")
        rule.onNodeWithTag("compose_text").performTextInput("Synthetic outgoing text")
        rule.onNodeWithTag("compose_review").performScrollTo().performClick()
        // The dialog names the route and the number; until it is confirmed nothing is queued.
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("from your paired source phone", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, runBlocking { container.database.outboxDao().pendingCount() })
        rule.onNodeWithTag("dialog_confirm").performClick()
        rule.waitUntil(15_000) { runBlocking { container.database.outboxDao().pendingCount() } == 1 }
        val queued = runBlocking { container.database.outboxDao().nextPending(5) }.single()
        assertEquals(SendKinds.REQUEST, queued.kind)
        assertFalse("queued request is encrypted at rest", String(queued.payload!!, Charsets.ISO_8859_1).contains("Synthetic"))
        assertFalse(queued.kind == OutboxKind.SMS)
        rule.waitUntil(15_000) { rule.onAllNodes(hasText("Waiting for source phone · not sent yet")).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasText("To: 03001234567"))[0].assertIsDisplayed()
        val stored = runBlocking { container.inboxRepository.messages.first() }.single()
        assertEquals(MessageStatus.PENDING, stored.status)
    }

    @Test fun composeExplainsWhyThisPhoneCannotSendWhenUnpairedAndWithoutTheRole() {
        assumeTrue(!DefaultSmsRole.isHeld(rule.activity))
        becomeCompanion(paired = false)
        waitFor("inbox_compose")
        rule.onNodeWithTag("inbox_compose").performClick()
        waitFor("compose_to")
        rule.onNodeWithTag("compose_to").performTextInput("03001234567")
        rule.onNodeWithTag("compose_text").performTextInput("Synthetic text")
        waitFor("compose_unavailable")
        rule.onNodeWithTag("compose_review").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("route_source").assertIsNotEnabled()
        assertEquals(0, runBlocking { container.inboxRepository.count() })
    }

    @Test fun anSmsLinkOpensComposePrefilledAndSendsNothingByItself() {
        becomeCompanion(paired = true)
        waitFor("inbox_compose")
        rule.runOnUiThread {
            rule.activity.onNewIntentForTest(android.content.Intent(android.content.Intent.ACTION_SENDTO, Uri.parse("smsto:03001234567")).putExtra("sms_body", "Synthetic prefilled"))
        }
        waitFor("compose_to")
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Synthetic prefilled")).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasText("03001234567"))[0].assertIsDisplayed()
        assertEquals(0, runBlocking { container.database.outboxDao().pendingCount() })
        assertEquals(0, runBlocking { container.inboxRepository.count() })
    }

    @Test fun settingsShowTheRealRoleStateAndRemoteSendingStaysOffWithoutPermission() {
        becomeCompanion(paired = true)
        waitFor("inbox_menu")
        rule.onNodeWithTag("inbox_menu").performClick()
        rule.onNodeWithTag("open_settings").performClick()
        waitFor("default_sms_status")
        val held = DefaultSmsRole.isHeld(rule.activity)
        rule.onNodeWithTag("default_sms_status").performScrollTo()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(if (held) "Yes" else "No")).fetchSemanticsNodes().isNotEmpty() }
        if (!held) rule.onNodeWithTag("default_sms_set").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("default_sms_open_settings").performScrollTo().assertIsDisplayed()
        assertFalse(runBlocking { container.preferences.current().remoteSendingEnabled })
    }
}
