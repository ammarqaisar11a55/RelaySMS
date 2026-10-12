package io.github.ammarqaisar11a55.relaysms.sms.defaultapp

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.provider.Telephony

/**
 * The Android "default SMS app" role. RelaySMS only ever asks for it through the system's own
 * dialog, after the user chose to, and reports what Android says afterwards; it never assumes the
 * role was granted.
 */
object DefaultSmsRole {
    /** Whether this device offers an SMS role at all (false on devices without telephony support). */
    fun isAvailable(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(RoleManager::class.java)?.isRoleAvailable(RoleManager.ROLE_SMS) == true
        } else {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        }

    fun isHeld(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) == true
        } else {
            Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        }

    /** Intent for Android's confirmation dialog, or null when a direct request is not possible. */
    fun requestIntent(context: Context): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val manager = context.getSystemService(RoleManager::class.java)
            if (manager != null && manager.isRoleAvailable(RoleManager.ROLE_SMS)) manager.createRequestRoleIntent(RoleManager.ROLE_SMS) else null
        } else {
            @Suppress("DEPRECATION")
            Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
        }

    /** The system screen where the user picks default apps, including switching back. */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
