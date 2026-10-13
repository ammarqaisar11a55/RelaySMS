package io.github.ammarqaisar11a55.relaysms.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.ammarqaisar11a55.relaysms.MainActivity
import io.github.ammarqaisar11a55.relaysms.R
import io.github.ammarqaisar11a55.relaysms.inbox.InboxMessage

/** What a new-message notification says. Pure, so the privacy rules can be unit tested. */
data class NotificationText(
    val title: String,
    val text: String,
    /** Shown on a locked screen. Never contains the sender or any message text. */
    val lockScreenTitle: String,
    val lockScreenText: String,
    /** Whether [text] carries message content and should be expandable. */
    val expandable: Boolean,
) {
    companion object {
        const val GENERIC_TITLE = "RelaySMS"

        /**
         * @param showPreview the user's opt-in to see sender and text. Off by default.
         * @param unseenCount new messages since the app was last opened, including this one.
         */
        fun of(sender: String, body: String, showPreview: Boolean, unseenCount: Int): NotificationText {
            val generic = if (unseenCount > 1) "$unseenCount new messages" else "New message received"
            return if (showPreview) {
                NotificationText(sender, body, GENERIC_TITLE, generic, expandable = true)
            } else {
                NotificationText(GENERIC_TITLE, generic, GENERIC_TITLE, generic, expandable = false)
            }
        }
    }
}

/**
 * Posts local notifications for newly stored messages. Nothing here talks to a push service: the
 * message was already fetched and decrypted by the app, and the notification is built on-device.
 */
class MessageNotifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)
    private var unseen = 0

    fun canPost(): Boolean {
        val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return permitted && manager.areNotificationsEnabled()
    }

    @Synchronized
    fun onNewMessage(message: InboxMessage, enabled: Boolean, showPreview: Boolean, appInForeground: Boolean, copyAction: Boolean = false) {
        // While the user is looking at the app the inbox itself is the notification.
        if (!enabled || appInForeground || !canPost()) return
        ensureChannel()
        unseen++
        val text = NotificationText.of(message.address, message.body, showPreview, unseen)
        val open = PendingIntent.getActivity(
            context,
            if (showPreview) message.id.hashCode() else 0,
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply { if (showPreview) putExtra(EXTRA_OPEN_MESSAGE, message.id) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val lockScreen = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentTitle(text.lockScreenTitle)
            .setContentText(text.lockScreenText)
            .build()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentTitle(text.title)
            .setContentText(text.text)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setWhen(message.receivedAtMillis)
            .setShowWhen(true)
            // Content is hidden on a secure lock screen; the generic version is shown instead.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(lockScreen)
            .setGroup(GROUP)
        if (text.expandable) builder.setStyle(NotificationCompat.BigTextStyle().bigText(text.text))
        if (copyAction && message.hasOtp) {
            // One tap copies the code without opening the app. The intent names the message
            // only; the code is not placed in the notification. Android hides actions together
            // with the content on a secure lock screen.
            val copy = PendingIntent.getBroadcast(
                context, message.id.hashCode(),
                Intent(context, CopyCodeReceiver::class.java).setAction(CopyCodeReceiver.ACTION_COPY).putExtra(CopyCodeReceiver.EXTRA_MESSAGE, message.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "Copy code", copy)
        }
        try {
            if (showPreview) {
                manager.notify(message.id.hashCode(), builder.build())
                manager.notify(
                    SUMMARY_ID,
                    NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_stat_relay)
                        .setContentTitle(text.lockScreenTitle)
                        .setContentText(text.lockScreenText)
                        .setGroup(GROUP).setGroupSummary(true).setAutoCancel(true)
                        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(lockScreen)
                        .build(),
                )
            } else {
                // One notification, updated in place, so that nothing about individual messages shows.
                manager.notify(SUMMARY_ID, builder.build())
            }
        } catch (e: SecurityException) {
            // Permission was revoked between the check and the call. The message is stored regardless.
        }
    }

    /** A content-free notice, used when a message was stored outside RelaySMS's own inbox. */
    fun notifyGeneric(text: String) {
        if (!canPost()) return
        ensureChannel()
        try {
            manager.notify(
                SUMMARY_ID,
                NotificationCompat.Builder(context, CHANNEL_ID).setSmallIcon(R.drawable.ic_stat_relay)
                    .setContentTitle(NotificationText.GENERIC_TITLE).setContentText(text).setAutoCancel(true)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build(),
            )
        } catch (e: SecurityException) {
            // Permission revoked meanwhile.
        }
    }

    /** The user is looking at the inbox: clear what was announced. */
    @Synchronized
    fun clearAll() {
        unseen = 0
        manager.cancelAll()
    }

    fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "New messages", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Announces messages forwarded from your source phone"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "messages"
        const val EXTRA_OPEN_MESSAGE = "open_message_id"
        const val SUMMARY_ID = 2000
        private const val GROUP = "relaysms_messages"
    }
}
