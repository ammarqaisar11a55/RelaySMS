package io.github.ammarqaisar11a55.relaysms.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.ammarqaisar11a55.relaysms.AppContainer
import io.github.ammarqaisar11a55.relaysms.core.model.ThemeMode
import io.github.ammarqaisar11a55.relaysms.core.prefs.AppSettings
import io.github.ammarqaisar11a55.relaysms.feature.filters.SenderFiltersScreen
import io.github.ammarqaisar11a55.relaysms.feature.home.CompanionHomeScreen
import io.github.ammarqaisar11a55.relaysms.feature.home.SourceHomeScreen
import io.github.ammarqaisar11a55.relaysms.feature.onboarding.RoleSelectionScreen
import io.github.ammarqaisar11a55.relaysms.feature.onboarding.WelcomeScreen
import io.github.ammarqaisar11a55.relaysms.feature.settings.SettingsScreen
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
                if (loaded == null) {
                    LoadingState()
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
    NavHost(navController = nav, startDestination = Routes.startDestination(settings)) {
        composable(Routes.WELCOME) { WelcomeScreen(onContinue = { nav.navigate(Routes.ROLE) }) }
        composable(Routes.ROLE) { RoleSelectionScreen(onBack = back) }
        composable(Routes.SOURCE_HOME) {
            SourceHomeScreen(settings, onOpenSettings = { nav.navigate(Routes.SETTINGS) }, onOpenFilters = { nav.navigate(Routes.SOURCE_FILTERS) })
        }
        composable(Routes.SOURCE_FILTERS) { SenderFiltersScreen(settings, onBack = back) }
        composable(Routes.COMPANION_HOME) { CompanionHomeScreen(onOpenSettings = { nav.navigate(Routes.SETTINGS) }) }
        composable(Routes.SETTINGS) { SettingsScreen(settings = settings, onBack = back) }
    }
}
