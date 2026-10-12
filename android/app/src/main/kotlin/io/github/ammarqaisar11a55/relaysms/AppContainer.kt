package io.github.ammarqaisar11a55.relaysms

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.ammarqaisar11a55.relaysms.core.common.SafeLog
import io.github.ammarqaisar11a55.relaysms.core.database.AppDatabase
import io.github.ammarqaisar11a55.relaysms.core.model.DeviceRole
import io.github.ammarqaisar11a55.relaysms.core.network.RelayApi
import io.github.ammarqaisar11a55.relaysms.core.prefs.AppPreferences
import io.github.ammarqaisar11a55.relaysms.core.security.DeviceKeyStore
import io.github.ammarqaisar11a55.relaysms.pairing.IdentityStore
import io.github.ammarqaisar11a55.relaysms.pairing.LinkMonitor
import io.github.ammarqaisar11a55.relaysms.pairing.PairingActivity
import io.github.ammarqaisar11a55.relaysms.pairing.PairingManager
import io.github.ammarqaisar11a55.relaysms.pairing.SecureIdentityStore
import okhttp3.OkHttpClient
import io.github.ammarqaisar11a55.relaysms.core.security.LocalCipherHolder
import io.github.ammarqaisar11a55.relaysms.delivery.DeliveryWorker
import io.github.ammarqaisar11a55.relaysms.delivery.OutboxDeliverer
import io.github.ammarqaisar11a55.relaysms.delivery.OutboxTransport
import io.github.ammarqaisar11a55.relaysms.delivery.SyncWorker
import io.github.ammarqaisar11a55.relaysms.inbox.InboxRepository
import io.github.ammarqaisar11a55.relaysms.transport.EnvelopeRouter
import io.github.ammarqaisar11a55.relaysms.transport.RelayOutboxTransport
import io.github.ammarqaisar11a55.relaysms.transport.RelaySocket
import io.github.ammarqaisar11a55.relaysms.transport.RelaySync
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
     * Device-local storage cipher, opened on first use because Keystore access is slow. If the
     * keyset had to be recreated, everything encrypted under the old key is unreadable and is
     * discarded.
     */
    val localCipher = LocalCipherHolder(appContext) {
        SafeLog.event("local_key_recreated")
        appScope.launch { discardLocallyEncryptedData() }
    }

    val httpClient: OkHttpClient by lazy { RelayApi.newHttpClient() }
    val apiFactory: (String, String?) -> RelayApi = { baseUrl, token -> RelayApi(httpClient, baseUrl, token) }

    /** Long-term identity keys of this device, wrapped by Android Keystore. */
    val deviceKeys: DeviceKeyStore by lazy { DeviceKeyStore(appContext) }

    val identityStore: IdentityStore by lazy { SecureIdentityStore(appContext) { localCipher } }

    private val pairingActivity = PairingActivity()

    val linkMonitor: LinkMonitor by lazy { LinkMonitor(identityStore, apiFactory, onPeerRemoved = ::onPeerRemoved, pairingActivity = pairingActivity) }

    fun newPairingManager(role: DeviceRole) =
        PairingManager(role, identityStore, { deviceKeys.loadOrCreate() }, apiFactory, environment.allowCleartextLocal, activity = pairingActivity)

    /** Bookkeeping after a pairing finished on this phone. */
    suspend fun onPairingCompleted() {
        identityStore.identity.value?.let { preferences.setServerUrl(it.serverUrl) }
        linkMonitor.onPaired()
        linkMonitor.refresh()
        SyncWorker.ensurePeriodic(appContext)
        relaySync.syncOnce()
    }

    /**
     * The paired device is gone (revoked here, revoked remotely, or replaced). Forwarding stops and
     * anything still queued for the old companion is destroyed, so it can never reach a new one.
     */
    private suspend fun onPeerRemoved() {
        preferences.setForwardingEnabled(false)
        withContext(Dispatchers.IO) { database.outboxDao().deletePending() }
        SafeLog.event("peer_removed")
    }

    val sourceStores: SourceStores by lazy { SourceStores(database, preferences, { localCipher }, { identityStore.peer.value != null }) }

    /** Seals each queued item for the paired device and uploads the ciphertext. */
    val outboxTransport: OutboxTransport by lazy {
        RelayOutboxTransport(identityStore, { deviceKeys.loadOrCreate() }, apiFactory, onAuthorizationLost = { linkMonitor.refresh() })
    }

    val inboxRepository: InboxRepository by lazy { InboxRepository(database.messageDao(), localCipher) }

    val relaySync: RelaySync by lazy {
        RelaySync(
            identityStore, { deviceKeys.loadOrCreate() }, apiFactory,
            handler = EnvelopeRouter({ preferences.current().role }, inboxRepository),
            onAuthorizationLost = { linkMonitor.refresh() },
        )
    }

    /** Live hint channel. Connected only while the app is in the foreground. */
    val relaySocket: RelaySocket by lazy {
        RelaySocket(httpClient, identityStore, appScope, onEvent = ::onRelayEvent, onRejected = { linkMonitor.refresh() })
    }

    private suspend fun onRelayEvent(type: String) {
        when (type) {
            RelaySocket.EVENT_CONNECTED -> {
                relaySync.syncOnce()
                outboxDeliverer.runOnce()
                linkMonitor.refresh()
            }
            RelaySocket.EVENT_MESSAGES -> relaySync.syncOnce()
            RelaySocket.EVENT_LINK, RelaySocket.EVENT_DELIVERY -> linkMonitor.refresh()
        }
    }

    fun onAppForeground() {
        relaySocket.start()
        appScope.launch {
            relaySync.syncOnce()
            outboxDeliverer.runOnce()
        }
    }

    fun onAppBackground() {
        relaySocket.stop()
    }

    val outboxDeliverer: OutboxDeliverer by lazy { OutboxDeliverer(database.outboxDao(), { localCipher }, { outboxTransport }) }

    /**
     * Durable delivery through WorkManager, plus an immediate attempt while this process is alive
     * so that a message does not wait for the scheduler when the network is already up.
     */
    val deliveryScheduler = DeliveryScheduler {
        DeliveryWorker.schedule(appContext)
        appScope.launch { runCatching { outboxDeliverer.runOnce() } }
    }

    val incomingSmsProcessor: IncomingSmsProcessor by lazy {
        IncomingSmsProcessor(
            config = sourceStores,
            filters = sourceStores,
            outbox = sourceStores,
            seenSenders = sourceStores,
            outcomes = sourceStores,
            cipher = localCipher,
            scheduler = deliveryScheduler,
        )
    }

    val resetCoordinator = ResetCoordinator().apply {
        register(ResetCoordinator.ORDER_STOP_ACTIVITY, "forwarding") { preferences.setForwardingEnabled(false) }
        register(ResetCoordinator.ORDER_REVOKE_REMOTE, "relay registration") { linkMonitor.deleteRegistration() }
        register(ResetCoordinator.ORDER_DELETE_KEYS, "device keys") { deviceKeys.delete() }
        register(ResetCoordinator.ORDER_WIPE_STORAGE, "database") { withContext(Dispatchers.IO) { database.clearAllTables() } }
        register(ResetCoordinator.ORDER_WIPE_STORAGE + 1, "local keys") { localCipher.destroy() }
        register(ResetCoordinator.ORDER_CLEAR_PREFERENCES, "preferences") { preferences.clear() }
    }

    private suspend fun discardLocallyEncryptedData() {
        database.outboxDao().deleteAll()
        database.seenSenderDao().deleteAll()
    }

    /** Called once at process start: resume delivery of anything left in the queue. */
    fun onProcessStart() {
        appScope.launch(Dispatchers.IO) {
            // Touch Keystore-backed state off the main thread so the first screen does not wait for it.
            identityStore.identity.value
            if (database.outboxDao().pendingCount() > 0) deliveryScheduler.schedule()
            if (identityStore.identity.value != null) linkMonitor.refresh()
            if (identityStore.peer.value != null) SyncWorker.ensurePeriodic(appContext)
        }
    }
}

/** Build-dependent configuration, injected so that logic stays testable. */
data class Environment(
    val defaultServerUrl: String,
    val allowCleartextLocal: Boolean,
    val versionName: String,
)
