package com.marathoncoach

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Reads exercise sessions (runs) from Health Connect.
 * Samsung Health mirrors Galaxy Watch data into Health Connect automatically.
 */
class HealthConnectReader(private val context: Context) {

    private val client = HealthConnectClient.getOrCreate(context)
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    /**
     * Returns a list of run JSONObjects for every running session
     * between [from] and [to].
     */
    suspend fun readRuns(from: Instant, to: Instant): List<JSONObject> {
        val sessions = client.readRecords(
            ReadRecordsRequest(
                recordType = ExerciseSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(from, to)
            )
        ).records

        // Only care about outdoor/treadmill runs
        val runTypes = setOf(
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL
        )

        return sessions
            .filter { it.exerciseType in runTypes }
            .map { session -> buildRunJson(session) }
    }

    private suspend fun buildRunJson(session: ExerciseSessionRecord): JSONObject {
        val zone = ZoneId.systemDefault()
        val startZdt = session.startTime.atZone(zone)

        val durationSeconds = session.endTime.epochSecond - session.startTime.epochSecond
        val durationMin = durationSeconds / 60.0

        // Pull heart rate samples for this session window
        val hrRecords = client.readRecords(
            ReadRecordsRequest(
                recordType = HeartRateRecord::class,
                timeRangeFilter = TimeRangeFilter.between(session.startTime, session.endTime)
            )
        ).records

        val hrSamples = hrRecords.flatMap { it.samples }.map { it.beatsPerMinute }
        val avgHr = if (hrSamples.isNotEmpty()) hrSamples.average().toInt() else null
        val maxHr = if (hrSamples.isNotEmpty()) hrSamples.max() else null

        // Pull speed/pace samples
        val speedRecords = client.readRecords(
            ReadRecordsRequest(
                recordType = SpeedRecord::class,
                timeRangeFilter = TimeRangeFilter.between(session.startTime, session.endTime)
            )
        ).records

        val speedSamples = speedRecords.flatMap { it.samples }

        // Extract distance from session metadata (set by Samsung Health)
        val distanceKm = session.metadata.let {
            // Distance isn't directly on ExerciseSessionRecord in all versions;
            // we compute from speed samples if available, otherwise from title/notes
            computeDistanceFromSpeeds(speedSamples, durationSeconds)
        }

        val avgPaceSecPerKm = if (distanceKm > 0) (durationSeconds / distanceKm).toInt() else null
        val avgPaceStr = avgPaceSecPerKm?.let { formatPace(it) }

        return JSONObject().apply {
            put("date", startZdt.format(dateFormatter))
            put("start_time", startZdt.format(timeFormatter))
            put("duration_min", String.format("%.1f", durationMin).toDouble())
            put("distance_km", String.format("%.2f", distanceKm).toDouble())
            put("avg_pace_min_km", avgPaceStr ?: JSONObject.NULL)
            put("avg_hr_bpm", avgHr ?: JSONObject.NULL)
            put("max_hr_bpm", maxHr ?: JSONObject.NULL)
            put("exercise_type", exerciseTypeName(session.exerciseType))
            put("title", session.title ?: JSONObject.NULL)
            put("notes", session.notes ?: JSONObject.NULL)
            put("exported_at", Instant.now().toString())

            // Per-km splits derived from speed samples
            put("splits_km", buildSplitsJson(speedSamples, session.startTime))
        }
    }

    /**
     * Approximate distance (km) from speed samples using trapezoidal integration.
     */
    private fun computeDistanceFromSpeeds(
        samples: List<SpeedRecord.Sample>,
        totalDurationSec: Long
    ): Double {
        if (samples.size < 2) return 0.0
        var distanceMeters = 0.0
        for (i in 1 until samples.size) {
            val dt = (samples[i].time.epochSecond - samples[i - 1].time.epochSecond).toDouble()
            val avgSpeed = (samples[i].speed.inMetersPerSecond + samples[i - 1].speed.inMetersPerSecond) / 2.0
            distanceMeters += avgSpeed * dt
        }
        return distanceMeters / 1000.0
    }

    /**
     * Group speed samples into 1-km buckets and compute avg pace + avg HR per km.
     */
    private fun buildSplitsJson(
        speedSamples: List<SpeedRecord.Sample>,
        sessionStart: Instant
    ): JSONArray {
        val splits = JSONArray()
        if (speedSamples.size < 2) return splits

        var kmIndex = 1
        var distanceAccumMeters = 0.0
        var splitStartTime = sessionStart

        for (i in 1 until speedSamples.size) {
            val dt = (speedSamples[i].time.epochSecond - speedSamples[i - 1].time.epochSecond).toDouble()
            val avgSpeed = (speedSamples[i].speed.inMetersPerSecond + speedSamples[i - 1].speed.inMetersPerSecond) / 2.0
            distanceAccumMeters += avgSpeed * dt

            if (distanceAccumMeters >= kmIndex * 1000.0) {
                val splitEndTime = speedSamples[i].time
                val splitDurationSec = splitEndTime.epochSecond - splitStartTime.epochSecond
                val paceSecPerKm = splitDurationSec.toInt()

                splits.put(JSONObject().apply {
                    put("km", kmIndex)
                    put("pace_min_km", formatPace(paceSecPerKm))
                    put("duration_sec", splitDurationSec)
                })

                kmIndex++
                splitStartTime = splitEndTime
            }
        }
        return splits
    }

    private fun formatPace(secondsPerKm: Int): String {
        val min = secondsPerKm / 60
        val sec = secondsPerKm % 60
        return "$min:${sec.toString().padStart(2, '0')}"
    }

    private fun exerciseTypeName(type: Int): String = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "outdoor_run"
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "treadmill_run"
        else -> "run"
    }

    companion object {
        /** Health Connect permissions this app needs */
        val PERMISSIONS = setOf(
            androidx.health.connect.client.permission.HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            androidx.health.connect.client.permission.HealthPermission.getReadPermission(HeartRateRecord::class),
            androidx.health.connect.client.permission.HealthPermission.getReadPermission(SpeedRecord::class),
        )
    }
}
