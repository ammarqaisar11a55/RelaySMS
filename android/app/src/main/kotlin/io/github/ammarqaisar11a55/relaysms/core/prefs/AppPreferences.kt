package io.github.ammarqaisar11a55.relaysms.core.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.ammarqaisar11a55.relaysms.core.model.DeviceRole
import io.github.ammarqaisar11a55.relaysms.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/** Non-sensitive application settings. Never holds keys, tokens or message content. */
data class AppSettings(
    val role: DeviceRole? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Relay server base URL, already validated by [io.github.ammarqaisar11a55.relaysms.core.network.ServerUrl]. */
    val serverUrl: String = "",
) {
    val onboardingComplete: Boolean get() = role != null
}

class AppPreferences(private val store: DataStore<Preferences>) {

    val settings: Flow<AppSettings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            AppSettings(
                role = DeviceRole.fromWireName(p[KEY_ROLE]),
                themeMode = ThemeMode.fromKey(p[KEY_THEME]),
                serverUrl = p[KEY_SERVER_URL].orEmpty(),
            )
        }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setRole(role: DeviceRole) = store.edit { it[KEY_ROLE] = role.wireName }

    suspend fun setThemeMode(mode: ThemeMode) = store.edit { it[KEY_THEME] = mode.key }

    suspend fun setServerUrl(url: String) = store.edit { it[KEY_SERVER_URL] = url }

    /** Removes every stored preference, returning the app to first-launch state. */
    suspend fun clear() = store.edit { it.clear() }

    private companion object {
        val KEY_ROLE = stringPreferencesKey("device_role")
        val KEY_THEME = stringPreferencesKey("theme_mode")
        val KEY_SERVER_URL = stringPreferencesKey("server_url")
    }
}
