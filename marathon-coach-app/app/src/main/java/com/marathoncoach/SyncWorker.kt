package com.marathoncoach

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Background job that:
 *   1. Reads any new runs from Health Connect (last 7 days to catch anything missed).
 *   2. Uploads each run as a dated JSON file to Google Drive.
 *
 * Scheduled to run every 6 hours when there's an internet connection.
 * WorkManager respects Doze mode — on Samsung, you should also set
 * battery optimization to "Unrestricted" for this app.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val reader = HealthConnectReader(context)
    private val uploader = DriveUploader(context)
    private val fileDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm")

    override suspend fun doWork(): Result {
        Log.d(TAG, "SyncWorker started")

        if (!uploader.isAuthorized) {
            Log.w(TAG, "Not authorized — skipping sync. Open the app to authorize.")
            return Result.success()  // Don't retry; user needs to open app first
        }

        return try {
            // Look back 7 days to catch any runs missed due to phone being offline
            val to = Instant.now()
            val from = to.minusSeconds(7 * 24 * 60 * 60)

            val runs = reader.readRuns(from, to)
            Log.d(TAG, "Found ${runs.size} runs in Health Connect")

            var uploadedCount = 0
            for (run in runs) {
                val date = run.getString("date")
                val startTime = run.getString("start_time").replace(":", "-")
                val filename = "run_${date}_${startTime}.json"

                val ok = uploader.uploadRun(filename, run)
                if (ok) uploadedCount++
            }

            Log.d(TAG, "Sync complete — uploaded $uploadedCount new run(s)")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val WORK_NAME = "run_sync"

        /**
         * Schedules the sync to run every 6 hours, only when internet is available.
         * Calling this multiple times is safe — KEEP_EXISTING won't reschedule if already running.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )

            Log.d(TAG, "Sync scheduled every 6 hours")
        }

        /** Run a one-off sync immediately (e.g. when user taps "Sync now"). */
        fun runNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = androidx.work.OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
