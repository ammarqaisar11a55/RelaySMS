package io.github.ammarqaisar11a55.relaysms.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import io.github.ammarqaisar11a55.relaysms.RelaySmsApp
import io.github.ammarqaisar11a55.relaysms.core.common.SafeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Receives SMS_RECEIVED on the source phone. Does only parsing here; filtering, encryption and
 * the queue insert run on a background dispatcher under goAsync(), and network delivery is left
 * to WorkManager. The manifest restricts senders of this broadcast to holders of BROADCAST_SMS.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val app = context.applicationContext as? RelaySmsApp ?: return
        val parts = SmsIntentParser.parts(intent)
        val subscriptionId = SmsIntentParser.subscriptionId(intent)
        val now = System.currentTimeMillis()
        val pending = goAsync()
        app.container.appScope.launch(Dispatchers.IO) {
            try {
                val outcome = withTimeoutOrNull(RECEIVER_BUDGET_MS) {
                    app.container.incomingSmsProcessor.process(parts, subscriptionId, now)
                }
                if (outcome == null) SafeLog.event("sms_event_timeout") else SafeLog.event("sms_event", outcome)
            } catch (e: Exception) {
                SafeLog.failure("sms_event_failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        /** Receivers are allowed about ten seconds; stay well inside it. */
        const val RECEIVER_BUDGET_MS = 7_000L
    }
}

object SmsIntentParser {
    fun parts(intent: Intent): List<SmsPart> = try {
        Telephony.Sms.Intents.getMessagesFromIntent(intent)
            ?.filterNotNull()
            ?.map { SmsPart(it.displayOriginatingAddress ?: it.originatingAddress, it.displayMessageBody ?: it.messageBody, it.timestampMillis) }
            .orEmpty()
    } catch (e: RuntimeException) {
        // Malformed PDUs make the platform parser throw; treat the event as unusable.
        emptyList()
    }

    fun subscriptionId(intent: Intent): Int =
        intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", intent.getIntExtra("subscription", -1))
}
