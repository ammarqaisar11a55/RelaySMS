package io.github.ammarqaisar11a55.relaysms

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.ammarqaisar11a55.relaysms.core.common.SafeLog
import io.github.ammarqaisar11a55.relaysms.core.database.AppDatabase
import io.github.ammarqaisar11a55.relaysms.core.prefs.AppPreferences
import io.github.ammarqaisar11a55.relaysms.core.security.FieldCipher
import io.github.ammarqaisar11a55.relaysms.core.security.LocalKeys
import io.github.ammarqaisar11a55.relaysms.delivery.DeliveryWorker
import io.github.ammarqaisar11a55.relaysms.delivery.OutboxDeliverer
import io.github.ammarqaisar11a55.relaysms.delivery.OutboxTransport
import io.github.ammarqaisar11a55.relaysms.delivery.SendResult
import io.github.ammarqaisar11a55.relaysms.sms.DeliveryScheduler
import io.github.ammarqaisar11a55.relaysms.sms.IncomingSmsProcessor
import io.github.ammarqaisar11a55.relaysms.sms.SourceStores
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    val database: AppDatabase by lazy { AppDatabase.build(appContext) }

    /**
     * Device-local storage cipher. Opened lazily because Keystore access is slow. If the keyset
     * had to be recreated, everything encrypted under the old key is unreadable and is discarded.
     */
    val localCipher: FieldCipher by lazy {
        val opened = LocalKeys.openLocalCipher(appContext)
        if (opened.recreated) {
            SafeLog.event("local_key_recreated")
            appScope.launch { discardLocallyEncryptedData() }
        }
        opened.cipher
    }

    val sourceStores: SourceStores by lazy { SourceStores(database, preferences) { localCipher } }

    /** Replaced by the relay transport once pairing and networking are configured. */
    @Volatile
    var outboxTransport: OutboxTransport = OutboxTransport { _, _, _ -> SendResult.NOT_CONFIGURED }

    val outboxDeliverer: OutboxDeliverer by lazy { OutboxDeliverer(database.outboxDao(), { localCipher }, { outboxTransport }) }

    val deliveryScheduler = DeliveryScheduler { DeliveryWorker.schedule(appContext) }

    val incomingSmsProcessor: IncomingSmsProcessor by lazy {
        IncomingSmsProcessor(
            config = sourceStores,
            filters = sourceStores,
            outbox = sourceStores,
            seenSenders = sourceStores,
            outcomes = sourceStores,
            cipher = object : FieldCipher {
                override fun encrypt(plaintext: ByteArray, context: String) = localCipher.encrypt(plaintext, context)
                override fun decrypt(ciphertext: ByteArray, context: String) = localCipher.decrypt(ciphertext, context)
            },
            scheduler = deliveryScheduler,
        )
    }

    val resetCoordinator = ResetCoordinator().apply {
        register(ResetCoordinator.ORDER_STOP_ACTIVITY, "forwarding") { preferences.setForwardingEnabled(false) }
        register(ResetCoordinator.ORDER_WIPE_STORAGE, "database") { withContext(Dispatchers.IO) { database.clearAllTables() } }
        register(ResetCoordinator.ORDER_WIPE_STORAGE + 1, "local keys") { LocalKeys.deleteLocalKeys(appContext) }
        register(ResetCoordinator.ORDER_CLEAR_PREFERENCES, "preferences") { preferences.clear() }
    }

    private suspend fun discardLocallyEncryptedData() {
        database.outboxDao().deleteAll()
        database.seenSenderDao().deleteAll()
    }

    /** Called once at process start: resume delivery of anything left in the queue. */
    fun onProcessStart() {
        appScope.launch {
            if (database.outboxDao().pendingCount() > 0) deliveryScheduler.schedule()
        }
    }
}

/** Build-dependent configuration, injected so that logic stays testable. */
data class Environment(
    val defaultServerUrl: String,
    val allowCleartextLocal: Boolean,
    val versionName: String,
)
