package io.github.ammarqaisar11a55.relaysms

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ammarqaisar11a55.relaysms.core.model.DeviceRole
import io.github.ammarqaisar11a55.relaysms.sms.IncomingSms
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecurityOnDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val container get() = (rule.activity.application as RelaySmsApp).container
    private val pm get() = rule.activity.packageManager
    private val pkg get() = rule.activity.packageName

    @After fun clean(): Unit = runBlocking { container.resetCoordinator.resetEverything() }

    @Test fun onlyTheLauncherAndTheGuardedSmsReceiverAreReachableFromOtherApps() {
        val info = pm.getPackageInfo(
            pkg,
            PackageManager.GET_ACTIVITIES or PackageManager.GET_RECEIVERS or PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS,
        )
        val ours = "io.github.ammarqaisar11a55.relaysms."
        val exportedActivities = info.activities.orEmpty().filter { it.exported && it.name.startsWith(ours) }.map { it.name.removePrefix(ours) }
        assertEquals(listOf("MainActivity"), exportedActivities)

        val exportedReceivers = info.receivers.orEmpty().filter { it.exported && it.name.startsWith(ours) }
        assertEquals(listOf("sms.SmsReceiver"), exportedReceivers.map { it.name.removePrefix(ours) })
        // Only the system holds BROADCAST_SMS, so no app can fake an incoming SMS.
        assertEquals("android.permission.BROADCAST_SMS", exportedReceivers.single().permission)

        assertTrue(info.services.orEmpty().none { it.exported && it.name.startsWith(ours) })
        assertTrue(info.providers.orEmpty().none { it.exported && it.name.startsWith(ours) })
        // Third-party components that are exported must at least demand a permission.
        val unguarded = info.receivers.orEmpty().filter { it.exported && !it.name.startsWith(ours) && it.permission == null }.map { it.name } +
            info.services.orEmpty().filter { it.exported && it.permission == null }.map { it.name }
        assertEquals(emptyList<String>(), unguarded)
    }

    @Test fun backupIsOffAndNoUnexpectedPermissionIsDeclared() {
        val app = pm.getApplicationInfo(pkg, 0)
        assertEquals(0, app.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        val declared = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
            .filterNot { it.endsWith(".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION") }.toSet()
        val allowed = setOf(
            "android.permission.RECEIVE_SMS", "android.permission.CAMERA", "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE", "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_REMOTE_MESSAGING", "android.permission.POST_NOTIFICATIONS",
            "android.permission.RECEIVE_BOOT_COMPLETED", "android.permission.WAKE_LOCK", "android.permission.USE_BIOMETRIC",
            "android.permission.USE_FINGERPRINT",
        )
        assertEquals("permissions beyond the documented set", emptySet<String>(), declared - allowed)
        for (never in listOf("READ_CONTACTS", "ACCESS_FINE_LOCATION", "READ_SMS", "SEND_SMS", "BIND_ACCESSIBILITY_SERVICE", "READ_PHONE_STATE")) {
            assertTrue("$never must not be declared", declared.none { it.endsWith(never) })
        }
    }

    @Test fun screenshotsAreBlockedByDefaultAndTheSettingControlsIt() {
        fun secure(): Boolean {
            var flags = 0
            rule.runOnUiThread { flags = rule.activity.window.attributes.flags }
            return flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        }
        rule.waitUntil(5_000) { secure() }
        runBlocking { container.preferences.setBlockScreenshots(false) }
        rule.waitUntil(5_000) { !secure() }
        // A screen with pairing secrets forces protection whatever the setting says.
        rule.activity.windowSecurity.acquire()
        rule.waitUntil(5_000) { secure() }
        rule.activity.windowSecurity.release()
        rule.waitUntil(5_000) { !secure() }
        runBlocking { container.preferences.setBlockScreenshots(true) }
        rule.waitUntil(5_000) { secure() }
    }

    @Test fun whileLockedNoMessageIsOnScreenAndUnlockingRestoresTheInbox(): Unit = runBlocking {
        container.resetCoordinator.resetEverything()
        container.preferences.setRole(DeviceRole.COMPANION)
        container.inboxRepository.storeIncoming("m1", IncomingSms("TESTBANK", "Synthetic code 424242", System.currentTimeMillis(), 1))
        rule.waitUntil(15_000) { rule.onAllNodes(hasTestTag("message_m1")).fetchSemanticsNodes().isNotEmpty() }

        container.appLock.onBackground()
        container.preferences.setAppLock(true)
        rule.waitUntil(15_000) { rule.onAllNodes(hasTestTag("lock_screen")).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithTag("message_m1").assertCountEquals(0)
        rule.onAllNodesWithText("Synthetic code 424242", substring = true).assertCountEquals(0)
        rule.onAllNodesWithText("TESTBANK").assertCountEquals(0)

        container.appLock.unlock()
        rule.waitUntil(15_000) { rule.onAllNodes(hasTestTag("message_m1")).fetchSemanticsNodes().isNotEmpty() }
        // Leaving the app closes the gate again.
        container.appLock.onBackground()
        rule.waitUntil(15_000) { rule.onAllNodes(hasTestTag("lock_screen")).fetchSemanticsNodes().isNotEmpty() }
        assertNotNull(container.appLock)
    }
}
