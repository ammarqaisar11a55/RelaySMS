package io.github.ammarqaisar11a55.relaysms

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ammarqaisar11a55.relaysms.core.model.DeviceRole
import io.github.ammarqaisar11a55.relaysms.core.model.ThemeMode
import io.github.ammarqaisar11a55.relaysms.core.security.CryptoSetup
import io.github.ammarqaisar11a55.relaysms.core.security.CryptoUtil
import io.github.ammarqaisar11a55.relaysms.pairing.Identity
import io.github.ammarqaisar11a55.relaysms.pairing.Peer
import io.github.ammarqaisar11a55.relaysms.sms.IncomingSms
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Puts the app into a state with synthetic content for scripts/screenshots.sh, which then starts
 * the app normally and captures the screens for visual review. Screenshot blocking is turned off
 * here because the capture needs it; that is the only purpose of this class.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotScenario {
    private val args = InstrumentationRegistry.getArguments()
    private val container get() = ApplicationProvider.getApplicationContext<RelaySmsApp>().container

    @Test fun seed(): Unit = runBlocking {
        assumeTrue(args.getString("step") == "seed")
        container.resetCoordinator.resetEverything()
        val role = args.getString("role")
        container.preferences.setBlockScreenshots(false)
        if (role == "none") return@runBlocking
        container.preferences.setThemeMode(if (args.getString("theme") == "dark") ThemeMode.DARK else ThemeMode.LIGHT)
        container.preferences.setRole(if (role == "source") DeviceRole.SOURCE else DeviceRole.COMPANION)
        if (args.getString("paired") != "no") {
            val peer = CryptoSetup.generateInMemory()
            container.identityStore.setIdentity(Identity("http://10.0.2.2:9", "00000000-0000-4000-8000-000000000002", "unused", role!!, System.currentTimeMillis()))
            container.identityStore.setPeer(Peer("00000000-0000-4000-8000-000000000001", CryptoUtil.b64(peer.hpkePublicKey), CryptoUtil.b64(peer.signingPublicKey), System.currentTimeMillis(), "482 913"))
        }
        if (role == "source") {
            container.sourceStores.addFilter("TESTBANK", 1)
            container.sourceStores.addFilter("8558", 2)
            container.preferences.setForwardingEnabled(true)
        } else {
            val now = System.currentTimeMillis()
            val samples = listOf(
                Triple("TESTBANK", "Your OTP for login is 482913. It is valid for 5 minutes. Never share this code with anyone.", 2),
                Triple("Telenor", "Dear customer, your weekly bundle of 5GB has been activated and is valid until Sunday.", 45),
                Triple("+923001234567", "Synthetic message: see you at five near the main gate.", 190),
                Triple("8558", "Use verification code 771203 to confirm your number.", 60 * 26),
                Triple("TESTBANK", "PKR 2,500.00 was debited from your account ending 4321 at SAMPLE STORE.", 60 * 80),
            )
            samples.forEachIndexed { i, (sender, body, minutesAgo) ->
                container.inboxRepository.storeIncoming("shot$i", IncomingSms(sender, body, now - minutesAgo * 60_000L, 1))
            }
            container.inboxRepository.markRead("shot2")
            container.inboxRepository.markRead("shot4")
        }
    }
}
