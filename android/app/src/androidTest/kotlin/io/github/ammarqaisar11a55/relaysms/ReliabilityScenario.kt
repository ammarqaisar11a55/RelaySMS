package io.github.ammarqaisar11a55.relaysms

import android.Manifest
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ammarqaisar11a55.relaysms.core.model.DeviceRole
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Setup steps for scripts/emulator-reliability-check.sh. Each pairs the real app with a simulated
 * phone through the relay and saves that phone's state, so that a tool on the host can continue
 * as the peer while the app runs on its own, outside any test, and is locked, cut off from the
 * network, killed or rebooted.
 */
@RunWith(AndroidJUnit4::class)
class ReliabilityScenario {
    private val args = InstrumentationRegistry.getArguments()
    private val app: RelaySmsApp = ApplicationProvider.getApplicationContext()
    private val container get() = app.container

    private fun setup(appRole: DeviceRole, configure: suspend () -> Unit) = runBlocking {
        val relayUrl = args.getString("relayUrl").orEmpty()
        container.resetCoordinator.resetEverything()
        container.preferences.setRole(appRole)
        val peer = TestPhone(if (appRole == DeviceRole.SOURCE) DeviceRole.COMPANION else DeviceRole.SOURCE)
        val mine = container.newPairingManager(appRole)
        if (appRole == DeviceRole.SOURCE) pairManagers(mine, peer.pairing, relayUrl) else pairManagers(peer.pairing, mine, relayUrl)
        container.onPairingCompleted()
        configure()
        File(app.filesDir, "e2e-peer.json").writeText(peer.exportState())
    }

    @Test fun setupSource() {
        assumeTrue(args.getString("step") == "setupSource")
        setup(DeviceRole.SOURCE) {
            container.sourceStores.addFilter("TESTBANK", System.currentTimeMillis())
            container.preferences.setForwardingEnabled(true)
        }
    }

    @Test fun setupCompanion() {
        assumeTrue(args.getString("step") == "setupCompanion")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        setup(DeviceRole.COMPANION) {
            container.preferences.setBackgroundConnection(args.getString("background") != "off")
        }
    }
}
