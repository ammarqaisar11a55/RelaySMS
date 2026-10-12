package io.github.ammarqaisar11a55.relaysms

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ammarqaisar11a55.relaysms.core.database.MessageDirection
import io.github.ammarqaisar11a55.relaysms.core.database.MessageOrigin
import io.github.ammarqaisar11a55.relaysms.core.database.MessageStatus
import io.github.ammarqaisar11a55.relaysms.core.model.DeviceRole
import io.github.ammarqaisar11a55.relaysms.feature.inbox.ClipboardClearer
import io.github.ammarqaisar11a55.relaysms.feature.inbox.SecureClipboard
import io.github.ammarqaisar11a55.relaysms.inbox.InboxMessage
import io.github.ammarqaisar11a55.relaysms.notifications.MessageNotifier
import io.github.ammarqaisar11a55.relaysms.sms.IncomingSms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationAndClipboardTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val context: Context get() = rule.activity.applicationContext
    private val container get() = (rule.activity.application as RelaySmsApp).container
    private val system get() = context.getSystemService(NotificationManager::class.java)
    private val notifier by lazy { MessageNotifier(context) }
    private val message = InboxMessage(
        "n1", "TESTBANK", "Your OTP is 135246. Do not share it.", System.currentTimeMillis(), System.currentTimeMillis(),
        MessageDirection.INCOMING, MessageOrigin.RELAY, MessageStatus.RECEIVED, false, true, 1, "k",
    )

    @Before fun setUp(): Unit = runBlocking {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        container.resetCoordinator.resetEverything()
        container.preferences.setRole(DeviceRole.COMPANION)
        system.cancelAll()
    }

    @After fun tearDown(): Unit = runBlocking {
        system.cancelAll()
        container.resetCoordinator.resetEverything()
    }

    private fun active(): List<Notification> {
        // The notification service applies posts asynchronously.
        Thread.sleep(600)
        return system.activeNotifications.filter { it.notification.channelId == MessageNotifier.CHANNEL_ID }.map { it.notification }
    }

    private fun Notification.visibleText() = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT)
        .joinToString("|") { extras.getCharSequence(it)?.toString().orEmpty() }

    @Test fun defaultNotificationIsGenericAndCarriesNoMessageContent() {
        assertTrue(notifier.canPost())
        notifier.onNewMessage(message, enabled = true, showPreview = false, appInForeground = false)
        val n = active().single()
        assertEquals("RelaySMS|New message received|", n.visibleText())
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        notifier.onNewMessage(message.copy(id = "n2"), enabled = true, showPreview = false, appInForeground = false)
        val updated = active().single()
        assertEquals("RelaySMS|2 new messages|", updated.visibleText())
        for (secret in listOf("TESTBANK", "135246")) assertFalse(updated.extras.toString().contains(secret))
    }

    @Test fun previewShowsContentInTheShadeButTheLockScreenVersionStaysGeneric() {
        notifier.onNewMessage(message, enabled = true, showPreview = true, appInForeground = false)
        val all = active()
        val detailed = all.single { it.extras.getCharSequence(Notification.EXTRA_TITLE) == "TESTBANK" }
        assertTrue(detailed.visibleText().contains("135246"))
        assertEquals(Notification.VISIBILITY_PRIVATE, detailed.visibility)
        val publicVersion = detailed.publicVersion
        assertNotNull(publicVersion)
        assertEquals("RelaySMS|New message received|", publicVersion!!.visibleText())
        assertFalse(publicVersion.extras.toString().contains("135246"))
    }

    @Test fun nothingIsPostedWhenDisabledOrWhenTheAppIsOpenAndClearRemovesAll() {
        notifier.onNewMessage(message, enabled = false, showPreview = true, appInForeground = false)
        assertTrue(active().isEmpty())
        notifier.onNewMessage(message, enabled = true, showPreview = true, appInForeground = true)
        assertTrue(active().isEmpty())
        notifier.onNewMessage(message, enabled = true, showPreview = false, appInForeground = false)
        assertEquals(1, active().size)
        notifier.clearAll()
        assertTrue(active().isEmpty())
    }

    @Test fun turningNotificationsOffDoesNotStopMessagesBeingStored(): Unit = runBlocking {
        rule.onNodeWithTag("inbox_menu").performClick()
        rule.onNodeWithTag("open_settings").performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("notifications_enabled")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("notification_preview").performScrollTo().performClick()
        rule.waitUntil(5_000) { runBlocking { container.preferences.current().notificationPreview } }
        rule.onNodeWithTag("notifications_enabled").performScrollTo().performClick()
        rule.waitUntil(5_000) { runBlocking { !container.preferences.current().notificationsEnabled } }

        assertTrue(container.inboxRepository.storeIncoming("stored-anyway", IncomingSms("TESTBANK", "Synthetic stored message", System.currentTimeMillis(), 1)))
        assertEquals(1, container.inboxRepository.count())
        assertTrue(active().isEmpty())
    }

    private fun clipboardText(): String? {
        var text: String? = null
        rule.runOnUiThread {
            val cb = context.getSystemService(ClipboardManager::class.java)
            text = cb.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        }
        return text
    }

    @Test fun aCopiedCodeIsClearedAfterTheDelayButLaterCopiesAreLeftAlone() {
        val clearer = ClipboardClearer(context, CoroutineScope(Dispatchers.Default)) { 1 }
        rule.runOnUiThread { SecureClipboard.copy(context, "135246") }
        clearer.schedule("135246")
        assertEquals("135246", clipboardText())
        rule.waitUntil(6_000) { clipboardText().isNullOrEmpty() }

        // The user copies something else before the timer fires: it must survive.
        rule.runOnUiThread { SecureClipboard.copy(context, "135246") }
        clearer.schedule("135246")
        rule.runOnUiThread {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", "my own text"))
        }
        Thread.sleep(2_500)
        assertEquals("my own text", clipboardText())

        // "Do not clear" really does nothing.
        val never = ClipboardClearer(context, CoroutineScope(Dispatchers.Default)) { 0 }
        rule.runOnUiThread { SecureClipboard.copy(context, "777888") }
        never.schedule("777888")
        Thread.sleep(1_500)
        assertEquals("777888", clipboardText())
        rule.runOnUiThread { context.getSystemService(ClipboardManager::class.java).clearPrimaryClip() }
    }

    @Test fun copiedTextIsFlaggedSensitive() {
        rule.runOnUiThread {
            SecureClipboard.copy(context, "135246")
            val description = context.getSystemService(ClipboardManager::class.java).primaryClipDescription!!
            assertTrue(description.extras.getBoolean("android.content.extra.IS_SENSITIVE"))
            context.getSystemService(ClipboardManager::class.java).clearPrimaryClip()
        }
    }
}
