package io.github.ammarqaisar11a55.relaysms.sms.send

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import io.github.ammarqaisar11a55.relaysms.RelaySmsApp
import io.github.ammarqaisar11a55.relaysms.core.common.SafeLog
import kotlinx.coroutines.launch

/** Sends SMS through this phone's SIM with the platform SmsManager. */
class AndroidSmsSender(private val context: Context) : SmsSender {

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /** Whether this phone could send at all right now; null when it can, else the reason. */
    fun unavailableReason(): String? {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) return SendFailure.NO_SIM
        if (Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0) return SendFailure.RADIO_OFF
        val telephony = context.getSystemService(TelephonyManager::class.java)
        if (telephony == null || telephony.simState != TelephonyManager.SIM_STATE_READY) return SendFailure.NO_SIM
        return null
    }

    override fun send(requestId: String, to: String, text: String): String? {
        if (!hasPermission()) return SendFailure.NO_PERMISSION
        unavailableReason()?.let { return it }
        return try {
            val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
            val parts = manager.divideMessage(text)
            val sent = ArrayList<PendingIntent>(parts.size)
            val delivered = ArrayList<PendingIntent?>(parts.size)
            for (i in parts.indices) {
                val last = i == parts.size - 1
                sent += pending(SmsOutcomeReceiver.ACTION_SENT, requestId, i, last)
                // One delivery report is enough; ask for it on the last part only.
                delivered += if (last) pending(SmsOutcomeReceiver.ACTION_DELIVERED, requestId, i, true) else null
            }
            manager.sendMultipartTextMessage(to, null, parts, sent, delivered)
            null
        } catch (e: SecurityException) {
            SendFailure.NO_PERMISSION
        } catch (e: IllegalArgumentException) {
            SendFailure.INVALID_RECIPIENT
        } catch (e: RuntimeException) {
            SafeLog.failure("sms_send_failed", e)
            SendFailure.UNKNOWN
        }
    }

    private fun pending(action: String, requestId: String, part: Int, last: Boolean): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            (requestId + action + part).hashCode(),
            Intent(context, SmsOutcomeReceiver::class.java).setAction(action)
                .putExtra(SmsOutcomeReceiver.EXTRA_REQUEST, requestId).putExtra(SmsOutcomeReceiver.EXTRA_LAST, last),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

/**
 * Receives the SMS stack's verdict for each part. Not exported: only this app's own pending
 * intents reach it. A failure of any part fails the message; success of the last part means the
 * whole message was handed to the network.
 */
class SmsOutcomeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? RelaySmsApp ?: return
        val requestId = intent.getStringExtra(EXTRA_REQUEST) ?: return
        val last = intent.getBooleanExtra(EXTRA_LAST, false)
        val outcome: SmsSendOutcome? = when (intent.action) {
            ACTION_SENT -> when (resultCode) {
                Activity.RESULT_OK -> if (last) SmsSendOutcome.Sent else null
                SmsManager.RESULT_ERROR_NO_SERVICE -> SmsSendOutcome.Failed(SendFailure.NO_SERVICE)
                SmsManager.RESULT_ERROR_RADIO_OFF -> SmsSendOutcome.Failed(SendFailure.RADIO_OFF)
                else -> SmsSendOutcome.Failed(SendFailure.CARRIER_REJECTED)
            }
            ACTION_DELIVERED -> SmsSendOutcome.Delivered
            else -> null
        }
        if (outcome == null) return
        val pending = goAsync()
        app.container.appScope.launch {
            try {
                app.container.onSmsOutcome(requestId, outcome)
            } catch (e: Exception) {
                SafeLog.failure("sms_outcome_failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_SENT = "io.github.ammarqaisar11a55.relaysms.SMS_SENT"
        const val ACTION_DELIVERED = "io.github.ammarqaisar11a55.relaysms.SMS_DELIVERED"
        const val EXTRA_REQUEST = "request_id"
        const val EXTRA_LAST = "last_part"
    }
}
