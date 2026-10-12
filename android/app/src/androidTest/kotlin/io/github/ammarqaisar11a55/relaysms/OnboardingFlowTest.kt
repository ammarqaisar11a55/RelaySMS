package io.github.ammarqaisar11a55.relaysms

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val container get() = (rule.activity.application as RelaySmsApp).container

    @Before fun reset() = runBlocking {
        container.resetCoordinator.resetEverything()
        Unit
    }

    private fun waitForTag(tag: String) = rule.waitUntil(30_000) {
        rule.onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun companionOnboardingReachesCompanionHomeAndResetReturnsToWelcome() {
        waitForTag("welcome_continue")
        rule.onNodeWithText("Welcome to RelaySMS").assertIsDisplayed()
        rule.onNodeWithTag("welcome_continue").performScrollTo().performClick()
        rule.onNodeWithTag("role_confirm").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("role_companion").performScrollTo().performClick()
        rule.onNodeWithTag("role_confirm").performScrollTo().performClick()
        waitForTag("companion_home")
        rule.onNodeWithText("Not paired").assertIsDisplayed()

        rule.onNodeWithTag("inbox_menu").performClick()
        rule.onNodeWithTag("open_settings").performClick()
        rule.onNodeWithTag("theme_dark").performClick()
        rule.onNodeWithTag("reset_app").performScrollTo().performClick()
        rule.onNodeWithTag("dialog_confirm").performClick()
        waitForTag("welcome_continue")
    }

    @Test fun sourceOnboardingReachesSourceHome() {
        waitForTag("welcome_continue")
        rule.onNodeWithTag("welcome_continue").performScrollTo().performClick()
        rule.onNodeWithTag("role_source").performScrollTo().performClick()
        rule.onNodeWithTag("role_confirm").performScrollTo().performClick()
        waitForTag("source_home")
        // The permission card depends on device state; the pairing prompt is always present here.
        rule.onNodeWithTag("open_pairing").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("open_filters").performScrollTo().performClick()
        rule.onNodeWithTag("filter_input").performTextInput("TESTBANK")
        rule.onNodeWithTag("filter_add").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasText("TESTBANK")).fetchSemanticsNodes().isNotEmpty() }
    }
}
