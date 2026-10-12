package io.github.ammarqaisar11a55.relaysms.background

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import io.github.ammarqaisar11a55.relaysms.feature.home.openAppSettings

object BatteryOptimization {
    /** True when the user has set RelaySMS's battery use to "Unrestricted". */
    fun isUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /**
     * Opens Android's list of battery-optimisation exceptions, where the user can choose
     * RelaySMS. The app does not request the exemption itself and cannot change it.
     */
    fun openSettings(context: Context) {
        try {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            openAppSettings(context)
        }
    }
}
