package io.github.ammarqaisar11a55.relaysms

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.ammarqaisar11a55.relaysms.background.ConnectionService
import io.github.ammarqaisar11a55.relaysms.background.NetworkMonitor
import io.github.ammarqaisar11a55.relaysms.core.common.SafeLog
import io.github.ammarqaisar11a55.relaysms.core.database.AppDatabase
import io.github.ammarqaisar11a55.relaysms.core.model.DeviceRole
import io.github.ammarqaisar11a55.relaysms.core.network.ApiResult
import io.github.ammarqaisar11a55.relaysms.core.network.RelayApi
import io.github.ammarqaisar11a55.relaysms.core.network.RelayInfo
import io.github.ammarqaisar11a55.relaysms.transport.SyncOutcome
import io.github.ammarqaisar11a55.relaysms.core.prefs.AppPreferences
import io.github.ammarqaisar11a55.relaysms.core.security.DeviceKeyStore
import io.github.ammarqaisar11a55.relaysms.pairing.CredentialRotator
import io.github.ammarqaisar11a55.relaysms.pairing.IdentityStore
import io.github.ammarqaisar11a55.relaysms.security.AppLock
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
import io.github.ammarqaisar11a55.relaysms.feature.inbox.ClipboardClearer
import io.github.ammarqaisar11a55.relaysms.inbox.InboxRepository
import io.github.ammarqaisar11a55.relaysms.inbox.MessageMirror
import io.github.ammarqaisar11a55.relaysms.sms.defaultapp.AndroidSmsProviderGateway
import io.github.ammarqaisar11a55.relaysms.sms.defaultapp.DefaultSmsRole
import io.github.ammarqaisar11a55.relaysms.sms.defaultapp.ProviderMirror
import io.github.ammarqaisar11a55.relaysms.sms.defaultapp.SmsProviderGateway
import io.github.ammarqaisar11a55.relaysms.sms.send.AndroidSmsSender
import io.github.ammarqaisar11a55.relaysms.sms.send.EnvelopeQueue
import io.github.ammarqaisar11a55.relaysms.sms.send.OutgoingMessages
import io.github.ammarqaisar11a55.relaysms.sms.send.RoomSendRequestLog
import io.github.ammarqaisar11a55.relaysms.sms.send.SendIds
import io.github.ammarqaisar11a55.relaysms.sms.send.SendKinds
import io.github.ammarqaisar11a55.relaysms.sms.send.SendRequestHandler
import io.github.ammarqaisar11a55.relaysms.sms.send.SendResult
import io.github.ammarqaisar11a55.relaysms.sms.send.SendSettings
import io.github.ammarqaisar11a55.relaysms.sms.send.SendStatus
import io.github.ammarqaisar11a55.relaysms.sms.send.SmsSendOutcome
import io.github.ammarqaisar11a55.relaysms.notifications.MessageNotifier
import io.github.ammarqaisar11a55.relaysms.otp.OtpDetector
import kotlinx.coroutines.flow.MutableStateFlow
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

    val appLock = AppLock()

    val credentialRotator: CredentialRotator by lazy { CredentialRotator(identityStore, apiFactory) }

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
        ConnectionService.stop(appContext)
        preferences.setForwardingEnabled(false)
        withContext(Dispatchers.IO) { database.outboxDao().deletePending() }
        SafeLog.event("peer_removed")
    }

    val sourceStores: SourceStores by lazy { SourceStores(database, preferences, { localCipher }, { identityStore.peer.value != null }) }

    /** Seals each queued item for the paired device and uploads the ciphertext. */
    val outboxTransport: OutboxTransport by lazy {
        RelayOutboxTransport(identityStore, { deviceKeys.loadOrCreate() }, apiFactory, onAuthorizationLost = { linkMonitor.refresh() })
    }

    val smsProviderGateway: SmsProviderGateway by lazy { AndroidSmsProviderGateway(appContext) }

    /** Copies inbox messages into Android's SMS store, only while RelaySMS is the default SMS app. */
    val providerMirror: ProviderMirror by lazy {
        ProviderMirror(database.messageDao(), smsProviderGateway, { DefaultSmsRole.isHeld(appContext) }, { inboxRepository.reveal(it) })
    }

    val inboxRepository: InboxRepository by lazy {
        InboxRepository(
            database.messageDao(), localCipher, detectsOtp = OtpDetector::looksLikeOtp,
            mirror = object : MessageMirror {
                override suspend fun onStored() = providerMirror.onStored()
                override suspend fun onRead(providerId: Long) = providerMirror.onRead(providerId)
                override suspend fun onDeleted(providerIds: List<Long>) = providerMirror.onDeleted(providerIds)
            },
        )
    }

    val smsSender: AndroidSmsSender by lazy { AndroidSmsSender(appContext) }

    private val envelopeQueue: EnvelopeQueue by lazy { EnvelopeQueue(sourceStores, localCipher, deliveryScheduler) { System.currentTimeMillis() } }

    /** Companion phone: messages the user asked to send. */
    val outgoingMessages: OutgoingMessages by lazy {
        OutgoingMessages(inboxRepository, envelopeQueue, smsSender, isPaired = { identityStore.peer.value != null })
    }

    /** Source phone: acts on send requests from the paired companion, if the owner allowed it. */
    val sendRequestHandler: SendRequestHandler by lazy {
        SendRequestHandler(
            settings = object : SendSettings {
                override suspend fun enabled() = preferences.current().remoteSendingEnabled
                override suspend fun perHourLimit() = preferences.current().remoteSendPerHour
            },
            sender = smsSender,
            log = RoomSendRequestLog(database.sendRequestDao()),
            reporter = { result ->
                envelopeQueue.enqueue(SendIds.resultId(result.requestId, result.status), SendKinds.RESULT, SendResult.serializer(), result)
            },
        )
    }

    /** Verdict of the phone's SMS stack for a message this app sent, for either role. */
    suspend fun onSmsOutcome(requestId: String, outcome: SmsSendOutcome) {
        if (database.sendRequestDao().find(requestId) != null) {
            sendRequestHandler.onOutcome(requestId, outcome)
        } else {
            when (outcome) {
                SmsSendOutcome.Sent -> inboxRepository.applySendResult(requestId, SendStatus.SENT, null)
                SmsSendOutcome.Delivered -> inboxRepository.applySendResult(requestId, SendStatus.DELIVERED, null)
                is SmsSendOutcome.Failed -> inboxRepository.applySendResult(requestId, SendStatus.FAILED, outcome.reason)
            }
        }
    }

    /** Message to prefill in the compose screen, from an sms: link or another app's share. */
    val composeRequests = MutableStateFlow<Pair<String, String>?>(null)

    val clipboardClearer: ClipboardClearer by lazy {
        ClipboardClearer(appContext, appScope) { preferences.current().clipboardClearSeconds }
    }

    /** Erases messages past the retention period. Cheap; called at start and after each sync. */
    suspend fun applyRetention() {
        runCatching { inboxRepository.applyRetention(preferences.current().retentionDays) }
        runCatching { outgoingMessages.sweep() }
        runCatching { database.sendRequestDao().deleteOlderThan(System.currentTimeMillis() - 7L * 24 * 3_600_000) }
        // Catches up after the default SMS role was granted while the app was not running.
        runCatching { providerMirror.onStored() }
    }

    val relaySync: RelaySync by lazy {
        RelaySync(
            identityStore, { deviceKeys.loadOrCreate() }, apiFactory,
            handler = EnvelopeRouter({ preferences.current().role }, inboxRepository, sendRequestHandler),
            onAuthorizationLost = { linkMonitor.refresh() },
        )
    }

    /** Live hint channel. Connected only while the app is in the foreground. */
    val relaySocket: RelaySocket by lazy {
        RelaySocket(
            httpClient, identityStore, appScope,
            onEvent = ::onRelayEvent,
            onRejected = { linkMonitor.refresh() },
            relayInfo = { baseUrl ->
                when (val r = apiFactory(baseUrl, null).info()) {
                    is ApiResult.Ok -> r.value
                    // A relay without this endpoint predates poll mode: it speaks WebSocket.
                    is ApiResult.Error -> if (r.status == 404) RelayInfo() else null
                    ApiResult.Unreachable -> null
                }
            },
            poll = ::pollRelay,
        )
    }

    private var pollTicks = 0

    /** One round in poll mode. The link is re-checked every few rounds, not every time. */
    private suspend fun pollRelay(): Boolean {
        val outcome = relaySync.syncOnce()
        if (++pollTicks % 6 == 0) linkMonitor.refresh()
        return outcome != SyncOutcome.RETRY
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

    val messageNotifier: MessageNotifier by lazy { MessageNotifier(appContext) }

    @Volatile
    var appInForeground: Boolean = false
        private set

    /** Message id from a tapped notification, consumed by the navigation host. */
    val openMessageRequests = MutableStateFlow<String?>(null)

    private fun startNotifying() {
        appScope.launch {
            inboxRepository.arrivals.collect { message ->
                val settings = preferences.current()
                if (settings.role == DeviceRole.COMPANION) {
                    messageNotifier.onNewMessage(message, settings.notificationsEnabled, settings.notificationPreview, appInForeground)
                }
            }
        }
    }

    @Volatile private var backgroundConnectionActive = false

    /** The live connection is wanted while the app is on screen or the background service runs. */
    private fun updateSocket() {
        if (appInForeground || backgroundConnectionActive) relaySocket.start() else relaySocket.stop()
    }

    /** Called by [ConnectionService] when it starts and stops. */
    fun setBackgroundConnection(active: Boolean) {
        backgroundConnectionActive = active
        updateSocket()
    }

    /** Starts the background connection service if the user enabled it and a device is paired. */
    fun restoreBackgroundConnection() {
        appScope.launch {
            if (preferences.current().backgroundConnection && identityStore.peer.value != null) ConnectionService.start(appContext)
        }
    }

    val networkMonitor: NetworkMonitor by lazy {
        NetworkMonitor(appContext) {
            // Network is back: reconnect without waiting out the backoff, and flush both directions.
            relaySocket.reconnectNow()
            appScope.launch {
                runCatching { outboxDeliverer.runOnce() }
                runCatching { relaySync.syncOnce() }
            }
        }
    }

    fun onAppForeground() {
        appInForeground = true
        messageNotifier.clearAll()
        updateSocket()
        restoreBackgroundConnection()
        appScope.launch {
            relaySync.syncOnce()
            outboxDeliverer.runOnce()
        }
    }

    fun onAppBackground() {
        appInForeground = false
        appLock.onBackground()
        updateSocket()
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
        register(ResetCoordinator.ORDER_STOP_ACTIVITY + 1, "background connection") { ConnectionService.stop(appContext) }
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
        startNotifying()
        networkMonitor.start()
        appScope.launch(Dispatchers.IO) {
            // Touch Keystore-backed state off the main thread so the first screen does not wait for it.
            identityStore.identity.value
            if (database.outboxDao().pendingCount() > 0) deliveryScheduler.schedule()
            if (identityStore.identity.value != null) {
                linkMonitor.refresh()
                // Renew the relay credential when it is old. Skipped mid-pairing by its own checks.
                if (!pairingActivity.inProgress) runCatching { credentialRotator.rotateIfDue() }
            }
            if (identityStore.peer.value != null) SyncWorker.ensurePeriodic(appContext)
            applyRetention()
        }
    }
}

/** Build-dependent configuration, injected so that logic stays testable. */
data class Environment(
    val defaultServerUrl: String,
    val allowCleartextLocal: Boolean,
    val versionName: String,
)
