package io.github.ammarqaisar11a55.relaysms

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.ammarqaisar11a55.relaysms.core.prefs.AppPreferences
import io.github.ammarqaisar11a55.relaysms.core.reset.ResetCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Hand-written dependency container. One instance lives for the whole process. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val environment = Environment(
        defaultServerUrl = BuildConfig.DEFAULT_SERVER_URL,
        allowCleartextLocal = BuildConfig.ALLOW_CLEARTEXT_LOCAL,
        versionName = BuildConfig.VERSION_NAME,
    )

    val preferences = AppPreferences(
        PreferenceDataStoreFactory.create { appContext.preferencesDataStoreFile("settings") },
    )

    val resetCoordinator = ResetCoordinator().apply {
        register(ResetCoordinator.ORDER_CLEAR_PREFERENCES, "preferences") { preferences.clear() }
    }
}

/** Build-dependent configuration, injected so that logic stays testable. */
data class Environment(
    val defaultServerUrl: String,
    val allowCleartextLocal: Boolean,
    val versionName: String,
)
