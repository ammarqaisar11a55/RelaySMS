package io.github.ammarqaisar11a55.relaysms

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ammarqaisar11a55.relaysms.core.model.DeviceRole
import io.github.ammarqaisar11a55.relaysms.notifications.CopyCodeReceiver
import io.github.ammarqaisar11a55.relaysms.notifications.MessageNotifier
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

@RunWith(AndroidJUnit4::class)
class CopyCodeNotificationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val container get() = (rule.activity.application as RelaySmsApp).container
    private val system get() = rule.activity.getSystemService(NotificationManager::class.java)
    private val notifier by lazy { MessageNotifier(rule.activity.applicationContext) }
    private val now = System.currentTimeMillis()

    @Before fun setUp(): Unit = runBlocking {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(rule.activity.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        container.resetCoordinator.resetEverything()
        container.preferences.setRole(DeviceRole.COMPANION)
        container.inboxRepository.storeIncoming("code", IncomingSms("TESTBANK", "Your OTP is 864209. Do not share it.", now, 1))
        container.inboxRepository.storeIncoming("plain", IncomingSms("Telenor", "Synthetic offer without any code", now - 1000, 1))
        system.cancelAll()
        rule.runOnUiThread { rule.activity.getSystemService(ClipboardManager::class.java).clearPrimaryClip() }
    }

    @After fun clean(): Unit = runBlocking { system.cancelAll(); container.resetCoordinator.resetEverything() }

    private suspend fun message(id: String) = container.inboxRepository.observe(id).first()!!
    private fun posted(): List<Notification> {
        Thread.sleep(600)
        return system.activeNotifications.filter { it.notification.channelId == MessageNotifier.CHANNEL_ID }.map { it.notification }
    }
    private fun clipboard(): String? {
        var text: String? = null
        rule.runOnUiThread { text = rule.activity.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() }
        return text
    }
    private fun tapCopy(id: String) = rule.activity.sendBroadcast(
        Intent(rule.activity, CopyCodeReceiver::class.java).setAction(CopyCodeReceiver.ACTION_COPY).putExtra(CopyCodeReceiver.EXTRA_MESSAGE, id),
    )

    @Test fun aMessageWithACodeGetsACopyButtonThatDoesNotContainTheCode(): Unit = runBlocking {
        notifier.onNewMessage(message("code"), enabled = true, showPreview = false, appInForeground = false, copyAction = true)
        val n = posted().single()
        assertEquals(listOf("Copy code"), n.actions.orEmpty().map { it.title.toString() })
        // Neither the visible text nor the action carries the digits or the sender.
        assertFalse(n.extras.toString().contains("864209"))
        assertFalse(n.actions[0].extras.toString().contains("864209"))
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        // The lock-screen version has no button at all.
        assertTrue(n.publicVersion.actions.isNullOrEmpty())
    }

    @Test fun noButtonWithoutACodeOrWhenTurnedOff(): Unit = runBlocking {
        notifier.onNewMessage(message("plain"), enabled = true, showPreview = false, appInForeground = false, copyAction = true)
        assertTrue(posted().single().actions.isNullOrEmpty())
        system.cancelAll()
        notifier.onNewMessage(message("code"), enabled = true, showPreview = false, appInForeground = false, copyAction = false)
        assertTrue(posted().single().actions.isNullOrEmpty())
    }

    @Test fun tappingTheButtonCopiesExactlyTheCodeAndNothingIsCopiedBeforehand(): Unit = runBlocking {
        notifier.onNewMessage(message("code"), enabled = true, showPreview = false, appInForeground = false, copyAction = true)
        posted()
        assertTrue("posting a notification must not touch the clipboard", clipboard().isNullOrEmpty())
        tapCopy("code")
        rule.waitUntil(10_000) { clipboard() == "864209" }
        rule.waitUntil(10_000) { runBlocking { message("code").isRead } }
        // A message without a code copies nothing.
        rule.runOnUiThread { rule.activity.getSystemService(ClipboardManager::class.java).clearPrimaryClip() }
        tapCopy("plain"); tapCopy("no-such-message")
        Thread.sleep(2500)
        assertTrue(clipboard().isNullOrEmpty())
    }

    @Test fun withTheAppLockOnTheButtonCopiesNothing(): Unit = runBlocking {
        container.appLock.unlock()
        container.preferences.setAppLock(true)
        tapCopy("code")
        Thread.sleep(2500)
        assertTrue(clipboard().isNullOrEmpty())
        container.preferences.setAppLock(false)
    }
}
