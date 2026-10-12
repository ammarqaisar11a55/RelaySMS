package io.github.ammarqaisar11a55.relaysms

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ammarqaisar11a55.relaysms.core.model.DeviceRole
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression for v0.9.0: asking for any permission crashed the app with "Can only use lower 16
 * bits for requestCode", because the activity's base class came from an outdated library. These
 * tests go through the same Activity Result API calls the screens use and require that the app
 * survives them. Earlier tests granted permissions from outside the app and never took this path.
 */
@RunWith(AndroidJUnit4::class)
class PermissionPromptTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val container get() = (rule.activity.application as RelaySmsApp).container
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    @After fun clean(): Unit = runBlocking { container.resetCoordinator.resetEverything() }

    private fun dismissSystemUi() {
        Thread.sleep(1500)
        automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        Thread.sleep(1000)
    }

    @Test fun requestingARuntimePermissionDoesNotCrash() {
        // A permission that is granted is answered without asking Android, which would not
        // exercise the failing call. Use one that is not granted on this device.
        val permission = listOf(Manifest.permission.CAMERA, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.POST_NOTIFICATIONS)
            .firstOrNull { ContextCompat.checkSelfPermission(rule.activity, it) != PackageManager.PERMISSION_GRANTED }
        assumeTrue("every permission is already granted on this device", permission != null)
        var failure: Throwable? = null
        rule.runOnUiThread {
            try {
                rule.activity.activityResultRegistry.register("regression-permission", ActivityResultContracts.RequestPermission()) { }.launch(permission!!)
            } catch (t: Throwable) {
                failure = t
            }
        }
        dismissSystemUi()
        assertTrue("permission request threw: $failure", failure == null)
        assertFalse(rule.activity.isFinishing)
    }

    @Test fun startingAnActivityForAResultDoesNotCrash() {
        // The default-SMS-app request uses this second kind of launcher.
        var failure: Throwable? = null
        rule.runOnUiThread {
            try {
                rule.activity.activityResultRegistry.register("regression-activity", ActivityResultContracts.StartActivityForResult()) { }
                    .launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", rule.activity.packageName, null)))
            } catch (t: Throwable) {
                failure = t
            }
        }
        dismissSystemUi()
        assertTrue("activity launch threw: $failure", failure == null)
    }

    @Test fun theSourcePhonesOwnPermissionButtonsOpenAndroidsPromptWithoutCrashing(): Unit = runBlocking {
        assumeTrue(ContextCompat.checkSelfPermission(rule.activity, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED)
        container.resetCoordinator.resetEverything()
        container.preferences.setRole(DeviceRole.SOURCE)
        rule.waitUntil(20_000) { rule.onAllNodes(hasTestTag("sms_permission_request")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasTestTag("sms_permission_request")).performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("sms_permission_continue")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasTestTag("sms_permission_continue")).performClick()
        dismissSystemUi()
        // Still alive and showing the source home screen.
        rule.waitUntil(20_000) { rule.onAllNodes(hasTestTag("source_home")).fetchSemanticsNodes().isNotEmpty() }
    }
}
