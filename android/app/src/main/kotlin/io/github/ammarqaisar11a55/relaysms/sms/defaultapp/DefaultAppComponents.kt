package io.github.ammarqaisar11a55.relaysms.sms.defaultapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.provider.Telephony
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.ammarqaisar11a55.relaysms.R
import io.github.ammarqaisar11a55.relaysms.RelaySmsApp
import io.github.ammarqaisar11a55.relaysms.core.common.SafeLog
import io.github.ammarqaisar11a55.relaysms.core.database.MessageOrigin
import io.github.ammarqaisar11a55.relaysms.core.model.DeviceRole
import io.github.ammarqaisar11a55.relaysms.sms.EventId
import io.github.ammarqaisar11a55.relaysms.sms.SmsAssembler
import io.github.ammarqaisar11a55.relaysms.sms.SmsIntentParser
import io.github.ammarqaisar11a55.relaysms.sms.send.SendRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Receives SMS addressed to this phone's own SIM while RelaySMS is the default SMS app. Android
 * delivers SMS_DELIVER only to the default app, and that app alone is responsible for storing the
 * message in the system SMS store; if it did not, the message would be lost.
 */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val app = context.applicationContext as? RelaySmsApp ?: return
        val sms = SmsAssembler.assemble(SmsIntentParser.parts(intent), SmsIntentParser.subscriptionId(intent)) ?: return
        val pending = goAsync()
        app.container.appScope.launch(Dispatchers.IO) {
            try {
                withTimeoutOrNull(7_000) {
                    val c = app.container
                    if (c.preferences.current().role == DeviceRole.COMPANION) {
                        // Stored in RelaySMS's inbox; the mirror then writes the system copy once.
                        c.inboxRepository.storeIncoming(EventId.of(sms), sms, MessageOrigin.SIM)
                    } else {
                        c.smsProviderGateway.insert(sms.sender, sms.body, sms.receivedAtMillis, false, Telephony.Sms.MESSAGE_TYPE_INBOX)
                        c.messageNotifier.notifyGeneric("New SMS received")
                    }
                }
                SafeLog.event("sms_deliver_handled")
            } catch (e: Exception) {
                SafeLog.failure("sms_deliver_failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Required of a default SMS app. RelaySMS does not download or display MMS; it tells the user
 * that one arrived so that nothing disappears silently.
 */
class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val manager = NotificationManagerCompat.from(context)
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Unsupported messages", NotificationManager.IMPORTANCE_DEFAULT))
        try {
            manager.notify(
                3000,
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_relay)
                    .setContentTitle("A multimedia message (MMS) arrived")
                    .setContentText("RelaySMS cannot show MMS. Switch your default SMS app to read it.")
                    .setAutoCancel(true)
                    .build(),
            )
        } catch (e: SecurityException) {
            // Notifications are off; nothing else can be done for an MMS.
        }
        SafeLog.event("mms_received_unsupported")
    }

    private companion object {
        const val CHANNEL = "unsupported"
    }
}

/**
 * "Reply with a message" from the incoming-call screen. Only the system may bind (the manifest
 * requires SEND_RESPOND_VIA_MESSAGE). Sends through this phone's own SIM.
 */
class RespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as RelaySmsApp
        val to = intent?.data?.schemeSpecificPart?.substringBefore('?')
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (intent?.action == TelephonyManager.ACTION_RESPOND_VIA_MESSAGE && !to.isNullOrBlank() && !text.isNullOrBlank()) {
            app.container.appScope.launch {
                runCatching { app.container.outgoingMessages.send(SendRoute.THIS_PHONE, to, text) }
                stopSelf(startId)
            }
        } else {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
}
