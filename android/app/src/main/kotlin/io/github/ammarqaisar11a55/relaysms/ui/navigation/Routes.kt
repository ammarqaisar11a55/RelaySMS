package io.github.ammarqaisar11a55.relaysms.ui.navigation

import io.github.ammarqaisar11a55.relaysms.core.model.DeviceRole
import io.github.ammarqaisar11a55.relaysms.core.prefs.AppSettings

object Routes {
    const val WELCOME = "onboarding/welcome"
    const val ROLE = "onboarding/role"
    const val SOURCE_HOME = "source/home"
    const val COMPANION_HOME = "companion/home"
    const val SETTINGS = "settings"

    /** The first screen for the stored configuration. Role-specific screens are unreachable until a role is chosen. */
    fun startDestination(settings: AppSettings): String = when (settings.role) {
        null -> WELCOME
        DeviceRole.SOURCE -> SOURCE_HOME
        DeviceRole.COMPANION -> COMPANION_HOME
    }

    /** Whether [route] may be shown for [role]; guards against cross-role navigation. */
    fun isAllowed(route: String, role: DeviceRole?): Boolean = when {
        route.startsWith("onboarding/") -> role == null
        route.startsWith("source/") -> role == DeviceRole.SOURCE
        route.startsWith("companion/") -> role == DeviceRole.COMPANION
        else -> role != null
    }
}
