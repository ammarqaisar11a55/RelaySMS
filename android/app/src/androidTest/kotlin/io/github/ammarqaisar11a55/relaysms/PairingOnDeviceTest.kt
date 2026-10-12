package io.github.ammarqaisar11a55.relaysms

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ammarqaisar11a55.relaysms.core.model.DeviceRole
import io.github.ammarqaisar11a55.relaysms.core.security.CryptoUtil
import io.github.ammarqaisar11a55.relaysms.pairing.PairingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pairs the real app (real UI, Keystore-backed keys, encrypted identity store) with a simulated
 * peer phone through a real relay. Run with scripts/device-e2e.sh, which starts the relay and
 * passes its address as the "relayUrl" instrumentation argument.
 */
@RunWith(AndroidJUnit4::class)
class PairingOnDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val relayUrl: String = InstrumentationRegistry.getArguments().getString("relayUrl").orEmpty()
    private val container get() = (rule.activity.application as RelaySmsApp).container
    private val peerScope = CoroutineScope(Dispatchers.IO)

    @Before fun setUp() {
        assumeTrue("relayUrl instrumentation argument not set", relayUrl.isNotEmpty())
    }

    private fun becomeRole(role: DeviceRole) = runBlocking {
        container.resetCoordinator.resetEverything()
        container.preferences.setRole(role)
    }

    private fun waitFor(tag: String, timeout: Long = 20_000) =
        rule.waitUntil(timeout) { rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    private fun textOf(tag: String): String =
        rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)!!.joinToString("") { it.text }

    @Test fun appAsCompanionPairsWithASourceAndSeesItsRevocation() {
        becomeRole(DeviceRole.COMPANION)
        waitFor("open_pairing")
        rule.onNodeWithTag("open_pairing").performClick()
        rule.onNodeWithTag("pair_server_url").performTextReplacement(relayUrl)
        rule.onNodeWithTag("pair_start").performScrollTo().performClick()
        waitFor("pair_qr")
        rule.onNodeWithTag("pair_show_text").performScrollTo().performClick()
        val qrText = textOf("pair_text")

        val source = TestPhone(DeviceRole.SOURCE)
        val code = runBlocking {
            source.pairing.reviewScanned(qrText)
            source.pairing.redeemAsSource(null)
            source.pairing.await<PairingState.Verify>().safetyCode
        }
        waitFor("pair_safety_code")
        assertEquals("both phones must show the same code", code, textOf("pair_safety_code"))

        val sourceConfirm = peerScope.async { source.pairing.confirm() }
        rule.onNodeWithTag("pair_confirm").performScrollTo().performClick()
        waitFor("pair_done")
        runBlocking { sourceConfirm.await() }
        assertEquals(PairingState.Completed::class, source.pairing.state.value::class)

        // The app stored the simulated source's keys, and the source stored the app's real keys.
        val peer = container.identityStore.peer.value!!
        assertEquals(source.store.identity.value!!.deviceId, peer.deviceId)
        assertEquals(CryptoUtil.b64(source.crypto.signingPublicKey), peer.signingPublicKey)
        assertEquals(CryptoUtil.b64(container.deviceKeys.loadOrCreate().hpkePublicKey), source.store.peer.value!!.hpkePublicKey)
        assertEquals(code, peer.safetyCode)

        // Credentials and keys are not readable in the app's preference files.
        val token = container.identityStore.identity.value!!.token.substringAfter('.')
        for (file in listOf("relaysms_identity", "relaysms_keysets")) {
            val all = rule.activity.getSharedPreferences(file, Context.MODE_PRIVATE).all.values.joinToString("|")
            assertFalse("token must not be stored in plain text in $file", all.contains(token))
            assertFalse(all.contains(peer.signingPublicKey))
        }

        rule.onNodeWithTag("pair_finish").performScrollTo().performClick()
        waitFor("connection_bar")
        rule.onNodeWithTag("connection_bar").performClick()
        waitFor("device_revoke")

        // The source revokes; the app must notice and stop presenting itself as paired.
        runBlocking { source.link.revokePeer() }
        rule.onNodeWithTag("device_refresh").performScrollTo().performClick()
        rule.waitUntil(20_000) { rule.onAllNodes(hasText("Pairing was removed")).fetchSemanticsNodes().isNotEmpty() }
        assertNull(container.identityStore.peer.value)
        assertNotNull("still registered, can pair again", container.identityStore.identity.value)
    }

    @Test fun appAsSourcePairsByEnteringThePairingTextAndCanRevoke() {
        becomeRole(DeviceRole.SOURCE)
        val companion = TestPhone(DeviceRole.COMPANION)
        peerScope.launch { companion.pairing.startAsCompanion(relayUrl, null) }
        val qrText = runBlocking { companion.pairing.await<PairingState.ShowingCode>().qrText }

        waitFor("open_pairing")
        rule.onNodeWithTag("open_pairing").performScrollTo().performClick()
        waitFor("pair_manual_toggle")
        rule.onNodeWithTag("pair_manual_toggle").performScrollTo().performClick()
        rule.onNodeWithTag("pair_manual_text").performTextInput(qrText)
        rule.onNodeWithTag("pair_manual_continue").performScrollTo().performClick()
        waitFor("pair_redeem")
        rule.onNodeWithTag("pair_redeem").performScrollTo().performClick()
        waitFor("pair_safety_code")
        val code = runBlocking { companion.pairing.await<PairingState.Verify>().safetyCode }
        assertEquals(code, textOf("pair_safety_code"))

        val companionConfirm = peerScope.async { companion.pairing.confirm() }
        rule.onNodeWithTag("pair_confirm").performScrollTo().performClick()
        waitFor("pair_done")
        runBlocking { companionConfirm.await() }
        assertEquals(companion.store.identity.value!!.deviceId, container.identityStore.peer.value!!.deviceId)

        // Forwarding is never switched on by pairing.
        assertFalse(runBlocking { container.preferences.current().forwardingEnabled })

        rule.onNodeWithTag("pair_finish").performScrollTo().performClick()
        waitFor("open_device")
        rule.onNodeWithTag("open_device").performScrollTo().performClick()
        waitFor("device_revoke")
        rule.onNodeWithTag("device_revoke").performScrollTo().performClick()
        rule.onNodeWithTag("dialog_confirm").performClick()
        rule.waitUntil(20_000) { container.identityStore.peer.value == null }
        // Enforced by the relay, not just hidden locally.
        assertEquals(io.github.ammarqaisar11a55.relaysms.pairing.LinkState.REVOKED, runBlocking { companion.link.refresh().state })
    }

    @Test fun invalidPairingTextIsRejectedWithAnExplanation() {
        becomeRole(DeviceRole.SOURCE)
        waitFor("open_pairing")
        rule.onNodeWithTag("open_pairing").performScrollTo().performClick()
        waitFor("pair_manual_toggle")
        rule.onNodeWithTag("pair_manual_toggle").performScrollTo().performClick()
        rule.onNodeWithTag("pair_manual_text").performTextInput("https://example.com/not-a-code")
        rule.onNodeWithTag("pair_manual_continue").performScrollTo().performClick()
        waitFor("pair_failed")
        assertNull(container.identityStore.identity.value)
    }
}
