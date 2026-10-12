package io.github.ammarqaisar11a55.relaysms

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.ammarqaisar11a55.relaysms.core.model.DeviceRole
import io.github.ammarqaisar11a55.relaysms.core.model.ThemeMode
import io.github.ammarqaisar11a55.relaysms.core.prefs.AppPreferences
import io.github.ammarqaisar11a55.relaysms.core.prefs.AppSettings
import io.github.ammarqaisar11a55.relaysms.core.reset.ResetCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppPreferencesTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Opens the store, runs [block], then closes it — mimicking one app process lifetime. */
    private fun <T> withStore(file: File, block: suspend (AppPreferences) -> T): T = runBlocking {
        val job = Job()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
        try {
            block(AppPreferences(store))
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test fun `defaults describe a first launch`() {
        val file = File(tmp.root, "a.preferences_pb")
        val s = withStore(file) { it.current() }
        assertEquals(AppSettings(), s)
        assertNull(s.role)
        assertFalse(s.onboardingComplete)
    }

    @Test fun `role theme and server survive a restart`() {
        val file = File(tmp.root, "b.preferences_pb")
        withStore(file) {
            it.setRole(DeviceRole.COMPANION)
            it.setThemeMode(ThemeMode.DARK)
            it.setServerUrl("https://relay.example.com")
        }
        val s = withStore(file) { it.current() }
        assertEquals(DeviceRole.COMPANION, s.role)
        assertEquals(ThemeMode.DARK, s.themeMode)
        assertEquals("https://relay.example.com", s.serverUrl)
        assertTrue(s.onboardingComplete)
    }

    @Test fun `reset returns to first launch and survives a restart`() {
        val file = File(tmp.root, "c.preferences_pb")
        withStore(file) { prefs ->
            prefs.setRole(DeviceRole.SOURCE)
            val reset = ResetCoordinator().apply { register(ResetCoordinator.ORDER_CLEAR_PREFERENCES, "preferences") { prefs.clear() } }
            assertEquals(emptyList<String>(), reset.resetEverything())
        }
        assertEquals(AppSettings(), withStore(file) { it.current() })
    }

    @Test fun `reset runs steps in order and continues after a failure`() = runBlocking {
        val ran = mutableListOf<String>()
        val reset = ResetCoordinator().apply {
            register(ResetCoordinator.ORDER_WIPE_STORAGE, "storage") { ran += "storage" }
            register(ResetCoordinator.ORDER_REVOKE_REMOTE, "revoke") { ran += "revoke"; error("server unreachable") }
            register(ResetCoordinator.ORDER_STOP_ACTIVITY, "stop") { ran += "stop" }
            register(ResetCoordinator.ORDER_CLEAR_PREFERENCES, "preferences") { ran += "preferences" }
        }
        assertEquals(listOf("revoke"), reset.resetEverything())
        assertEquals(listOf("stop", "revoke", "storage", "preferences"), ran)
    }
}
