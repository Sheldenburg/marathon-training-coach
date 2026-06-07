package com.marathoncoach

import android.content.Context
import android.util.Log
import androidx.work.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Background job that syncs all health data to Google Drive every 6 hours.
 *
 * Folder layout in Drive:
 *   RunningCoach/
 *   ├── activities/   ← one JSON per exercise session (all types)
 *   └── daily/        ← one JSON per calendar day (steps, sleep, weight, vitals…)
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val reader = HealthConnectReader(context)
    private val uploader = DriveUploader(context)
    private val timeFmt = DateTimeFormatter.ofPattern("HH-mm-ss")
    private val zone = ZoneId.systemDefault()

    override suspend fun doWork(): Result {
        Log.d(TAG, "SyncWorker started")

        if (!uploader.isAuthorized) {
            Log.w(TAG, "Not authorized — open the app to connect Google Drive first")
            return Result.success()
        }

        return try {
            val to = Instant.now()
            val from = to.minusSeconds(7 * 24 * 60 * 60)  // 7-day lookback to catch missed syncs

            var uploaded = 0

            // --- Activities (all exercise types) ---
            val activities = reader.readActivities(from, to)
            Log.d(TAG, "Found ${activities.size} activity sessions")
            for (activity in activities) {
                val date = activity.getString("date")
                val time = activity.getString("start_time").replace(":", "-")
                val type = activity.getString("exercise_type")
                val filename = "activities/activity_${date}_${time}_${type}.json"
                if (uploader.uploadRun(filename, activity)) uploaded++
            }

            // --- Daily summaries ---
            val dailySummaries = reader.readDailySummaries(from, to)
            Log.d(TAG, "Generating ${dailySummaries.size} daily summaries")
            for ((dateStr, summary) in dailySummaries) {
                val filename = "daily/daily_${dateStr}.json"
                // Daily summaries are overwritten each sync (data accumulates during the day)
                if (uploader.uploadOrUpdate(filename, summary)) uploaded++
            }

            Log.d(TAG, "Sync complete — $uploaded file(s) written to Drive")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val WORK_NAME = "health_sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.d(TAG, "Sync scheduled every 6 hours")
        }

        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
