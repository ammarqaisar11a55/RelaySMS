package io.github.ammarqaisar11a55.relaysms.core.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
    /** Source phone: master switch. Off until the user turns it on. */
    val forwardingEnabled: Boolean = false,
    /** Source phone: when true only senders on the allowlist are forwarded. */
    val allowlistOnly: Boolean = true,
) {
    val onboardingComplete: Boolean get() = role != null
}

data class ForwardingStats(
    val received: Int = 0,
    val queued: Int = 0,
    val filtered: Int = 0,
    /** Malformed events and storage errors. */
    val notForwarded: Int = 0,
    val queueFull: Int = 0,
    val lastEventAtMillis: Long = 0L,
)

class AppPreferences(private val store: DataStore<Preferences>) {

    val settings: Flow<AppSettings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            AppSettings(
                role = DeviceRole.fromWireName(p[KEY_ROLE]),
                themeMode = ThemeMode.fromKey(p[KEY_THEME]),
                serverUrl = p[KEY_SERVER_URL].orEmpty(),
                forwardingEnabled = p[KEY_FORWARDING] ?: false,
                allowlistOnly = p[KEY_ALLOWLIST_ONLY] ?: true,
            )
        }

    /** Content-free counters describing what happened to incoming SMS events on the source phone. */
    val stats: Flow<ForwardingStats> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            ForwardingStats(
                received = p[KEY_STAT_RECEIVED] ?: 0,
                queued = p[KEY_STAT_QUEUED] ?: 0,
                filtered = p[KEY_STAT_FILTERED] ?: 0,
                notForwarded = p[KEY_STAT_DROPPED] ?: 0,
                queueFull = p[KEY_STAT_QUEUE_FULL] ?: 0,
                lastEventAtMillis = p[KEY_STAT_LAST_AT] ?: 0L,
            )
        }

    suspend fun setForwardingEnabled(enabled: Boolean) = store.edit { it[KEY_FORWARDING] = enabled }

    suspend fun setAllowlistOnly(enabled: Boolean) = store.edit { it[KEY_ALLOWLIST_ONLY] = enabled }

    suspend fun recordSmsEvent(queued: Boolean, filtered: Boolean, failed: Boolean, queueFull: Boolean, nowMillis: Long) = store.edit {
        it[KEY_STAT_RECEIVED] = (it[KEY_STAT_RECEIVED] ?: 0) + 1
        if (queued) it[KEY_STAT_QUEUED] = (it[KEY_STAT_QUEUED] ?: 0) + 1
        if (filtered) it[KEY_STAT_FILTERED] = (it[KEY_STAT_FILTERED] ?: 0) + 1
        if (failed) it[KEY_STAT_DROPPED] = (it[KEY_STAT_DROPPED] ?: 0) + 1
        if (queueFull) it[KEY_STAT_QUEUE_FULL] = (it[KEY_STAT_QUEUE_FULL] ?: 0) + 1
        it[KEY_STAT_LAST_AT] = nowMillis
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
        val KEY_FORWARDING = booleanPreferencesKey("forwarding_enabled")
        val KEY_ALLOWLIST_ONLY = booleanPreferencesKey("allowlist_only")
        val KEY_STAT_RECEIVED = intPreferencesKey("stat_received")
        val KEY_STAT_QUEUED = intPreferencesKey("stat_queued")
        val KEY_STAT_FILTERED = intPreferencesKey("stat_filtered")
        val KEY_STAT_DROPPED = intPreferencesKey("stat_not_forwarded")
        val KEY_STAT_QUEUE_FULL = intPreferencesKey("stat_queue_full")
        val KEY_STAT_LAST_AT = longPreferencesKey("stat_last_event_at")
    }
}
