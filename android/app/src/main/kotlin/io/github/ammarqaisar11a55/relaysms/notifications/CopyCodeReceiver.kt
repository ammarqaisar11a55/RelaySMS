package io.github.ammarqaisar11a55.relaysms.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import io.github.ammarqaisar11a55.relaysms.RelaySmsApp
import io.github.ammarqaisar11a55.relaysms.core.common.SafeLog
import io.github.ammarqaisar11a55.relaysms.feature.inbox.SecureClipboard
import io.github.ammarqaisar11a55.relaysms.otp.OtpDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The "Copy code" button on a new-message notification. Not exported: only this app's own
 * notification can trigger it. The notification carries just the message id; the code is looked
 * up and decrypted here, at the moment the user taps, and goes nowhere but the clipboard.
 */
class CopyCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_COPY) return
        val app = context.applicationContext as? RelaySmsApp ?: return
        val id = intent.getStringExtra(EXTRA_MESSAGE)?.takeIf { it.length <= 64 } ?: return
        val pending = goAsync()
        app.container.appScope.launch {
            try {
                // With the app lock on, codes are reachable only after unlocking the app.
                if (app.container.preferences.current().appLock) return@launch
                val message = withTimeoutOrNull(5_000) { app.container.inboxRepository.observe(id).first() }
                val code = message?.let { OtpDetector.detect(it.body).best?.code }
                withContext(Dispatchers.Main) {
                    if (code != null) {
                        SecureClipboard.copy(context, code)
                        Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "No code found. Open RelaySMS to read the message.", Toast.LENGTH_LONG).show()
                    }
                }
                if (message != null) app.container.inboxRepository.markRead(id)
                SafeLog.event(if (code != null) "code_copied_from_notification" else "code_copy_no_code")
            } catch (e: Exception) {
                SafeLog.failure("code_copy_failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_COPY = "io.github.ammarqaisar11a55.relaysms.COPY_CODE"
        const val EXTRA_MESSAGE = "message_id"
    }
}
