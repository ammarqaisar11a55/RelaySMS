package io.github.ammarqaisar11a55.relaysms.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.ammarqaisar11a55.relaysms.AppContainer
import io.github.ammarqaisar11a55.relaysms.core.model.DeviceRole
import io.github.ammarqaisar11a55.relaysms.core.model.ThemeMode
import io.github.ammarqaisar11a55.relaysms.core.prefs.AppSettings
import io.github.ammarqaisar11a55.relaysms.feature.filters.SenderFiltersScreen
import io.github.ammarqaisar11a55.relaysms.feature.inbox.ComposeScreen
import io.github.ammarqaisar11a55.relaysms.feature.inbox.InboxScreen
import io.github.ammarqaisar11a55.relaysms.feature.inbox.MessageDetailScreen
import io.github.ammarqaisar11a55.relaysms.feature.home.SourceHomeScreen
import io.github.ammarqaisar11a55.relaysms.feature.onboarding.RoleSelectionScreen
import io.github.ammarqaisar11a55.relaysms.feature.pairing.PairedDeviceScreen
import io.github.ammarqaisar11a55.relaysms.feature.pairing.PairingScreen
import io.github.ammarqaisar11a55.relaysms.feature.onboarding.WelcomeScreen
import io.github.ammarqaisar11a55.relaysms.feature.settings.SettingsScreen
import io.github.ammarqaisar11a55.relaysms.security.LockScreen
import io.github.ammarqaisar11a55.relaysms.ui.components.LoadingState
import io.github.ammarqaisar11a55.relaysms.ui.theme.RelayTheme

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

@Composable
fun AppRoot(container: AppContainer) {
    val settings: AppSettings? by container.preferences.settings.collectAsStateWithLifecycle(initialValue = null)
    CompositionLocalProvider(LocalAppContainer provides container) {
        RelayTheme(settings?.themeMode ?: ThemeMode.SYSTEM) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                val loaded = settings
                val locked by container.appLock.locked.collectAsStateWithLifecycle()
                if (loaded == null) {
                    LoadingState()
                } else if (loaded.appLock && loaded.role != null && locked) {
                    // Nothing below this point is composed while locked: no message is on screen.
                    LockScreen(onUnlocked = container.appLock::unlock)
                } else {
                    // Re-keying on the start destination rebuilds the back stack whenever the role
                    // is chosen or reset, so screens of the previous role can never be returned to.
                    key(Routes.startDestination(loaded)) { AppNavHost(loaded) }
                }
            }
        }
    }
}

@Composable
private fun AppNavHost(settings: AppSettings) {
    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack() }
    val container = LocalAppContainer.current
    val openMessage by container.openMessageRequests.collectAsStateWithLifecycle()
    val composeRequest by container.composeRequests.collectAsStateWithLifecycle()
    LaunchedEffect(composeRequest, settings.role) {
        // An sms: link opened RelaySMS. Only the companion role has a compose screen.
        if (composeRequest != null) {
            if (settings.role == DeviceRole.COMPANION) nav.navigate(Routes.COMPANION_COMPOSE) { launchSingleTop = true } else container.composeRequests.value = null
        }
    }
    LaunchedEffect(openMessage, settings.role) {
        val id = openMessage ?: return@LaunchedEffect
        container.openMessageRequests.value = null
        if (settings.role == DeviceRole.COMPANION) nav.navigate(Routes.message(id)) { launchSingleTop = true }
    }
    NavHost(navController = nav, startDestination = Routes.startDestination(settings)) {
        composable(Routes.WELCOME) { WelcomeScreen(onContinue = { nav.navigate(Routes.ROLE) }) }
        composable(Routes.ROLE) { RoleSelectionScreen(onBack = back) }
        composable(Routes.SOURCE_HOME) {
            SourceHomeScreen(
                settings,
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenFilters = { nav.navigate(Routes.SOURCE_FILTERS) },
                onOpenPairing = { nav.navigate(Routes.PAIRING) },
                onOpenDevice = { nav.navigate(Routes.PAIRED_DEVICE) },
            )
        }
        composable(Routes.SOURCE_FILTERS) { SenderFiltersScreen(settings, onBack = back) }
        composable(Routes.COMPANION_HOME) {
            InboxScreen(
                settings,
                onOpenMessage = { nav.navigate(Routes.message(it)) },
                onCompose = { nav.navigate(Routes.COMPANION_COMPOSE) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenPairing = { nav.navigate(Routes.PAIRING) },
                onOpenDevice = { nav.navigate(Routes.PAIRED_DEVICE) },
            )
        }
        composable(Routes.COMPANION_COMPOSE) {
            val prefill = remember { container.composeRequests.value.also { container.composeRequests.value = null } }
            ComposeScreen(initialTo = prefill?.first.orEmpty(), initialText = prefill?.second.orEmpty(), onBack = back)
        }
        composable(Routes.COMPANION_MESSAGE) { entry ->
            MessageDetailScreen(messageId = entry.arguments?.getString("id").orEmpty(), onBack = back)
        }
        composable(Routes.PAIRING) { PairingScreen(settings, onBack = back, onDone = back) }
        composable(Routes.PAIRED_DEVICE) { PairedDeviceScreen(settings, onBack = back, onPair = { nav.navigate(Routes.PAIRING) }) }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                settings = settings,
                onBack = back,
                onOpenDevice = { nav.navigate(Routes.PAIRED_DEVICE) },
                onOpenPairing = { nav.navigate(Routes.PAIRING) },
                onOpenFilters = { nav.navigate(Routes.SOURCE_FILTERS) },
            )
        }
    }
}
