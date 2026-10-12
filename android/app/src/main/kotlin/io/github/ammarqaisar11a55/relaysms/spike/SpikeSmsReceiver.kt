package io.github.ammarqaisar11a55.relaysms.spike

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/** Phase Zero receiver: proves SMS_RECEIVED reaches the app. Records no message content. */
class SpikeSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)?.size ?: 0
        } catch (e: RuntimeException) {
            0
        }
        if (parts == 0) return
        SpikeResultStore(context).recordEvent(parts)
        Log.i(TAG, "SMS event detected (parts=$parts)")
    }

    private companion object {
        const val TAG = "RelaySmsSpike"
    }
}
