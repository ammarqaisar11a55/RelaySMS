package io.github.ammarqaisar11a55.relaysms.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.ammarqaisar11a55.relaysms.RelaySmsApp
import io.github.ammarqaisar11a55.relaysms.core.common.SafeLog

/**
 * After a restart of the phone or an update of the app: resume queued deliveries and periodic
 * sync, and bring the optional background connection back if the user had it on. Receiving this
 * broadcast starts the process, and process start already reschedules pending work.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as? RelaySmsApp ?: return
        SafeLog.event("boot_recovery")
        app.container.restoreBackgroundConnection()
    }
}
