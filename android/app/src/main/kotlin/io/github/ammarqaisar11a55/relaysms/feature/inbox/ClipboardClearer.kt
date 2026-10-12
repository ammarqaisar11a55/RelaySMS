package io.github.ammarqaisar11a55.relaysms.feature.inbox

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Best-effort removal of a copied code from the clipboard after a delay chosen by the user.
 *
 * Limits, by platform design: Android only lets an app read the clipboard while it is in the
 * foreground, so the check "is our text still there" can only be made then. If it cannot be made,
 * nothing is cleared, because clearing blindly could destroy something the user copied later.
 * Keyboards and other apps may have seen the text before it is cleared.
 */
class ClipboardClearer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val delaySeconds: suspend () -> Int,
) {
    private var job: Job? = null

    fun schedule(copiedText: String) {
        job?.cancel()
        job = scope.launch(Dispatchers.Main) {
            val seconds = delaySeconds()
            if (seconds <= 0) return@launch
            delay(seconds * 1000L)
            clearIfStill(copiedText)
        }
    }

    /** @return true if the clipboard was cleared. */
    fun clearIfStill(copiedText: String): Boolean {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        val current = try {
            clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        } catch (e: SecurityException) {
            null
        }
        // Null also when the app is in the background: then we cannot know, so we do nothing.
        if (current == null || current != copiedText) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        return true
    }
}
