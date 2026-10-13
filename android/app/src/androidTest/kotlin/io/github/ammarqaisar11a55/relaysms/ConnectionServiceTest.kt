package io.github.ammarqaisar11a55.relaysms

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ammarqaisar11a55.relaysms.background.ConnectionService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression: stopping the background-connection service right after starting it crashed the app
 * with ForegroundServiceDidNotStartInTimeException (Android gives a started foreground service a
 * few seconds to call startForeground, and the old code destroyed it before it could).
 */
@RunWith(AndroidJUnit4::class)
class ConnectionServiceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun startingAndImmediatelyStoppingDoesNotCrash() {
        repeat(3) {
            assertTrue(ConnectionService.start(rule.activity))
            ConnectionService.stop(rule.activity)
        }
        // Longer than Android's deadline for startForeground(); a violation would kill the process
        // (and with it this test) within that time.
        Thread.sleep(12_000)
        rule.waitUntil(10_000) { !ConnectionService.isRunning }
        assertFalse(rule.activity.isFinishing)
    }

    @Test fun aStartedServiceRunsAndStopsOnRequest() {
        assertTrue(ConnectionService.start(rule.activity))
        rule.waitUntil(10_000) { ConnectionService.isRunning }
        ConnectionService.stop(rule.activity)
        rule.waitUntil(10_000) { !ConnectionService.isRunning }
        // Start again after a stop: the stop request must not linger.
        assertTrue(ConnectionService.start(rule.activity))
        rule.waitUntil(10_000) { ConnectionService.isRunning }
        ConnectionService.stop(rule.activity)
        rule.waitUntil(10_000) { !ConnectionService.isRunning }
    }
}
