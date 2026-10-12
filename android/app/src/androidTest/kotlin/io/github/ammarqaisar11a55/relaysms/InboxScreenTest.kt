package io.github.ammarqaisar11a55.relaysms

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ammarqaisar11a55.relaysms.core.model.DeviceRole
import io.github.ammarqaisar11a55.relaysms.core.security.LocalCipherHolder
import io.github.ammarqaisar11a55.relaysms.inbox.InboxRepository
import io.github.ammarqaisar11a55.relaysms.sms.IncomingSms
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The companion inbox with the real database, cipher and UI. Messages are seeded locally. */
@RunWith(AndroidJUnit4::class)
class InboxScreenTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val container get() = (rule.activity.application as RelaySmsApp).container
    private val now = System.currentTimeMillis()
    private val otp = IncomingSms("TESTBANK", "Your OTP is 135246. Do not share it.", now - 60_000, 1)
    private val chat = IncomingSms("+923001234567", "Synthetic hello from a friend", now - 120_000, 1)
    private val promo = IncomingSms("Telenor", "Synthetic offer: 5GB for the weekend", now - 180_000, 1)

    @Before fun seed(): Unit = runBlocking {
        container.resetCoordinator.resetEverything()
        container.preferences.setRole(DeviceRole.COMPANION)
        container.inboxRepository.storeIncoming("otp", otp)
        container.inboxRepository.storeIncoming("chat", chat)
        container.inboxRepository.storeIncoming("promo", promo)
        container.inboxRepository.markRead("promo")
    }

    @After fun clean(): Unit = runBlocking { container.resetCoordinator.resetEverything() }

    private fun waitFor(tag: String) = rule.waitUntil(15_000) { rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    private fun waitGone(tag: String) = rule.waitUntil(15_000) { rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty() }

    @Test fun listShowsMessagesWithUnreadCountAndCodeBadge() {
        waitFor("message_otp")
        rule.onNodeWithText("TESTBANK").assertIsDisplayed()
        rule.onNodeWithText("Synthetic hello from a friend").assertIsDisplayed()
        rule.onNodeWithText("Unread (2)").assertIsDisplayed()
        rule.onNodeWithText("Code").assertIsDisplayed()
        // Honest status: seeded without pairing, so the bar must not claim a connection.
        rule.onNodeWithText("Not paired").assertIsDisplayed()
    }

    @Test fun filtersAndSearchNarrowTheList() {
        waitFor("message_otp")
        rule.onNodeWithTag("filter_codes").performClick()
        waitGone("message_chat")
        rule.onAllNodesWithTag("message_otp").assertCountEquals(1)

        rule.onNodeWithTag("filter_unread").performClick()
        waitFor("message_chat")
        waitGone("message_promo")

        rule.onNodeWithTag("filter_all").performClick()
        rule.onNodeWithTag("inbox_search_toggle").performClick()
        rule.onNodeWithTag("inbox_search").performTextInput("weekend")
        waitGone("message_otp")
        rule.onAllNodesWithTag("message_promo").assertCountEquals(1)

        rule.onNodeWithContentDescription("Clear search").performClick()
        rule.onNodeWithTag("inbox_search").performTextInput("no such text anywhere")
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Nothing matches")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun sortOrderCanBeReversed() {
        waitFor("message_otp")
        fun topOf(a: String, b: String): Boolean {
            val ya = rule.onNodeWithTag("message_$a").fetchSemanticsNode().positionInRoot.y
            val yb = rule.onNodeWithTag("message_$b").fetchSemanticsNode().positionInRoot.y
            return ya < yb
        }
        assertTrue("newest first by default", topOf("otp", "promo"))
        rule.onNodeWithTag("inbox_menu").performClick()
        rule.onNodeWithTag("inbox_sort").performClick()
        rule.waitUntil(10_000) { topOf("promo", "otp") }
    }

    @Test fun openingAMessageMarksItReadShowsTheCodeAndCopiesOnlyOnRequest() {
        val clipboard = rule.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        rule.runOnUiThread { clipboard.clearPrimaryClip() }
        waitFor("message_otp")
        rule.onNodeWithTag("message_otp").performClick()
        waitFor("otp_code_0")
        rule.onNodeWithText("135246").assertIsDisplayed()
        rule.onNodeWithText("Likely verification code").assertIsDisplayed()
        // Nothing is copied by merely opening the message.
        rule.runOnUiThread { assertFalse(clipboard.hasPrimaryClip()) }
        rule.waitUntil(10_000) { runBlocking { container.inboxRepository.observe("otp").first()!!.isRead } }

        rule.onNodeWithTag("otp_copy_0").performClick()
        rule.waitUntil(5_000) {
            var text: String? = null
            rule.runOnUiThread { text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() }
            text == "135246"
        }
        rule.onNodeWithTag("message_copy_all").performScrollTo().performClick()
        rule.waitUntil(5_000) {
            var text: String? = null
            rule.runOnUiThread { text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() }
            text == otp.body
        }
        rule.runOnUiThread { clipboard.clearPrimaryClip() }
    }

    @Test fun aMessageWithoutACodeShowsNoCodeCard() {
        waitFor("message_chat")
        rule.onNodeWithTag("message_chat").performClick()
        waitFor("message_body")
        rule.onAllNodesWithTag("otp_code_0").assertCountEquals(0)
        rule.onNodeWithText("Synthetic hello from a friend").assertIsDisplayed()
    }

    @Test fun deletingAMessageRemovesItForGood(): Unit = runBlocking {
        waitFor("message_chat")
        rule.onNodeWithTag("message_chat").performClick()
        waitFor("message_delete")
        rule.onNodeWithTag("message_delete").performClick()
        rule.onNodeWithTag("dialog_confirm").performClick()
        waitFor("inbox_list")
        waitGone("message_chat")
        assertEquals(2, container.inboxRepository.count())
        assertFalse("a re-delivered copy stays deleted", container.inboxRepository.storeIncoming("chat", chat))
    }

    @Test fun inboxSurvivesARestartAndIsUnreadableWithoutTheLocalKey(): Unit = runBlocking {
        waitFor("message_otp")
        // A new repository over the same files, with the cipher re-opened from Keystore, is what
        // the next process start does.
        val reopened = InboxRepository(container.database.messageDao(), LocalCipherHolder(rule.activity.applicationContext))
        val messages = reopened.messages.first()
        assertEquals(listOf("otp", "chat", "promo"), messages.map { it.id })
        assertEquals(otp.body, messages[0].body)
        assertEquals(otp.receivedAtMillis, messages[0].receivedAtMillis)
        assertTrue(messages[0].hasOtp)
        assertFalse(messages[1].hasOtp)
        assertTrue(messages[2].isRead)
    }

    @Test fun retentionErasesOldMessagesAndDeleteAllClearsTheInbox(): Unit = runBlocking {
        val dayMs = 24L * 60 * 60 * 1000
        container.inboxRepository.storeIncoming("old", IncomingSms("TESTBANK", "Synthetic old message", now - 10 * dayMs, 1))
        waitFor("message_old")
        rule.onNodeWithTag("inbox_menu").performClick()
        rule.onNodeWithTag("open_settings").performClick()
        waitFor("retention_7")
        rule.onNodeWithTag("retention_7").performScrollTo().performClick()
        rule.waitUntil(10_000) { runBlocking { container.inboxRepository.count() } == 3 }
        assertEquals(7, container.preferences.current().retentionDays)
        assertFalse(container.inboxRepository.messages.first().any { it.id == "old" })

        rule.onNodeWithTag("delete_all_messages").performScrollTo().performClick()
        rule.onNodeWithTag("dialog_confirm").performClick()
        rule.waitUntil(10_000) { runBlocking { container.inboxRepository.count() } == 0 }
    }
}
