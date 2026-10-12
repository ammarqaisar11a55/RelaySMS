package io.github.ammarqaisar11a55.relaysms.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.ammarqaisar11a55.relaysms.MainActivity
import io.github.ammarqaisar11a55.relaysms.R
import io.github.ammarqaisar11a55.relaysms.RelaySmsApp
import io.github.ammarqaisar11a55.relaysms.core.common.SafeLog
import io.github.ammarqaisar11a55.relaysms.transport.SocketState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Optional foreground service that keeps the relay connection open while the app is not on
 * screen, so that a forwarded message can arrive within seconds instead of at the next periodic
 * sync. It exists because RelaySMS uses no push service; it is off unless the user turns it on,
 * and Android shows its ongoing notification for as long as it runs.
 *
 * It does not guarantee real-time delivery: in Doze, and under vendor battery managers, Android
 * can still suspend the network of an app whose battery use is restricted.
 */
class ConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val container = (application as RelaySmsApp).container
        ensureChannel(this)
        startInForeground(notification(container.relaySocket.state.value))
        container.setBackgroundConnection(true)
        scope.launch {
            container.relaySocket.state.collectLatest { state ->
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state))
            }
        }
        SafeLog.event("connection_service_started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val container = (application as RelaySmsApp).container
            container.appScope.launch { container.preferences.setBackgroundConnection(false) }
            stopSelf()
            return START_NOT_STICKY
        }
        // Sticky: if Android kills the process for memory, it restarts the service when it can.
        return START_STICKY
    }

    override fun onDestroy() {
        (application as RelaySmsApp).container.setBackgroundConnection(false)
        scope.cancel()
        SafeLog.event("connection_service_stopped")
        super.onDestroy()
    }

    private fun startInForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    /** The text states only what is true right now; it never says "connected" while reconnecting. */
    private fun notification(state: SocketState): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ConnectionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentTitle(if (state == SocketState.CONNECTED) "Connected to your relay" else "Waiting for the relay connection")
            .setContentText(if (state == SocketState.CONNECTED) "Ready to receive messages" else "Will reconnect automatically")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(0, "Turn off", stop)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "background_connection"
        private const val NOTIFICATION_ID = 1500
        private const val ACTION_STOP = "io.github.ammarqaisar11a55.relaysms.STOP_CONNECTION"

        private fun ensureChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL_ID, "Background connection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while RelaySMS stays connected to receive messages right away"
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        /**
         * Starts the service if Android allows it right now. Starting a foreground service from
         * the background is refused on Android 12+, except in situations such as boot; in that
         * case the periodic sync remains the fallback and the next app open starts the service.
         * @return false when the start was refused.
         */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java))
            true
        } catch (e: RuntimeException) {
            SafeLog.failure("connection_service_start_refused", e)
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }
    }
}
