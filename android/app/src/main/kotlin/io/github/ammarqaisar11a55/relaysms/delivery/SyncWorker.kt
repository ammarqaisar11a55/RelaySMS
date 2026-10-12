package io.github.ammarqaisar11a55.relaysms.delivery

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.ammarqaisar11a55.relaysms.R
import io.github.ammarqaisar11a55.relaysms.RelaySmsApp
import io.github.ammarqaisar11a55.relaysms.core.common.SafeLog
import io.github.ammarqaisar11a55.relaysms.transport.SyncOutcome
import java.util.concurrent.TimeUnit

/** Notification shown only while expedited work runs as a foreground service (Android 11 and older). */
internal fun workForegroundInfo(context: Context, title: String): ForegroundInfo {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(WORK_CHANNEL_ID, "Background sync", NotificationManager.IMPORTANCE_LOW))
    val notification = NotificationCompat.Builder(context, WORK_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_relay)
        .setContentTitle(title)
        .setOngoing(true)
        .build()
    return ForegroundInfo(1001, notification)
}

private const val WORK_CHANNEL_ID = "background_work"

/** Fetches, decrypts and stores messages waiting on the relay. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? RelaySmsApp ?: return Result.failure()
        return try {
            when (app.container.relaySync.syncOnce()) {
                SyncOutcome.COMPLETE, SyncOutcome.NOT_CONFIGURED, SyncOutcome.UNAUTHORIZED -> Result.success()
                SyncOutcome.RETRY -> Result.retry()
            }
        } catch (e: Exception) {
            SafeLog.failure("sync_run_failed", e)
            Result.retry()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = workForegroundInfo(applicationContext, "Checking for messages")

    companion object {
        private const val ONE_TIME = "relay-sync"
        private const val PERIODIC = "relay-sync-periodic"
        private val CONNECTED = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Sync as soon as a network is available. */
        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(CONNECTED)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONE_TIME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        /**
         * Safety net for when no live connection exists: Android runs this roughly every 15
         * minutes at its own discretion (less often in Doze). It is not a real-time mechanism.
         */
        fun ensurePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(CONNECTED).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        }
    }
}
