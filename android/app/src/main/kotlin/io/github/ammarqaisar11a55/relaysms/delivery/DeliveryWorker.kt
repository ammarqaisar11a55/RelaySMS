package io.github.ammarqaisar11a55.relaysms.delivery

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.ammarqaisar11a55.relaysms.R
import io.github.ammarqaisar11a55.relaysms.RelaySmsApp
import io.github.ammarqaisar11a55.relaysms.core.common.SafeLog
import java.util.concurrent.TimeUnit

/** Durable, network-constrained upload of the outbox with exponential backoff. */
class DeliveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? RelaySmsApp ?: return Result.failure()
        return try {
            when (app.container.outboxDeliverer.runOnce()) {
                DeliveryRun.IDLE, DeliveryRun.WAITING_FOR_SETUP -> Result.success()
                DeliveryRun.RETRY -> Result.retry()
            }
        } catch (e: Exception) {
            SafeLog.failure("delivery_run_failed", e)
            Result.retry()
        }
    }

    /** Only used on Android 11 and older, where expedited work runs as a foreground service. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Message delivery", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentTitle("Forwarding messages")
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val UNIQUE_NAME = "outbox-delivery"
        private const val CHANNEL_ID = "delivery"
        private const val NOTIFICATION_ID = 1001

        /**
         * Requests a delivery run as soon as a network is available. If a run is already queued
         * or running, a follow-up run is appended so that items inserted meanwhile are not missed.
         */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<DeliveryWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
