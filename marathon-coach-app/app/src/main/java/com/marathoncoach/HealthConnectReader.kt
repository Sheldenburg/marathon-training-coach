package com.marathoncoach

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Reads all available health data from Health Connect.
 * Samsung Health mirrors Galaxy Watch data into Health Connect automatically.
 *
 * Produces two kinds of JSON:
 *   - Activity JSON  : one file per exercise session (all types)
 *   - Daily JSON     : one file per calendar day (steps, sleep, weight, vitals…)
 */
class HealthConnectReader(private val context: Context) {

    private val client = HealthConnectClient.getOrCreate(context)
    private val zone = ZoneId.systemDefault()
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")

    // -------------------------------------------------------------------------
    // Activity sessions (all exercise types)
    // -------------------------------------------------------------------------

    suspend fun readActivities(from: Instant, to: Instant): List<JSONObject> {
        val sessions = client.readRecords(
            ReadRecordsRequest(
                recordType = ExerciseSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(from, to)
            )
        ).records

        return sessions.map { session -> buildActivityJson(session) }
    }

    private suspend fun buildActivityJson(session: ExerciseSessionRecord): JSONObject {
        val startZdt = session.startTime.atZone(zone)
        val durationSec = session.endTime.epochSecond - session.startTime.epochSecond

        // Heart rate
        val hrRecords = client.readRecords(
            ReadRecordsRequest(
                recordType = HeartRateRecord::class,
                timeRangeFilter = TimeRangeFilter.between(session.startTime, session.endTime)
            )
        ).records
        val hrSamples = hrRecords.flatMap { it.samples }.map { it.beatsPerMinute }

        // Speed / pace
        val speedRecords = client.readRecords(
            ReadRecordsRequest(
                recordType = SpeedRecord::class,
                timeRangeFilter = TimeRangeFilter.between(session.startTime, session.endTime)
            )
        ).records
        val speedSamples = speedRecords.flatMap { it.samples }

        // Calories
        val calRecords = client.readRecords(
            ReadRecordsRequest(
                recordType = ActiveCaloriesBurnedRecord::class,
                timeRangeFilter = TimeRangeFilter.between(session.startTime, session.endTime)
            )
        ).records
        val totalCalories = calRecords.sumOf { it.energy.inKilocalories }

        // Power (cycling / rowing)
        val powerRecords = client.readRecords(
            ReadRecordsRequest(
                recordType = PowerRecord::class,
                timeRangeFilter = TimeRangeFilter.between(session.startTime, session.endTime)
            )
        ).records
        val powerSamples = powerRecords.flatMap { it.samples }.map { it.power.inWatts }

        val distanceKm = computeDistanceKm(speedSamples, durationSec)
        val avgPaceSec = if (distanceKm > 0) (durationSec / distanceKm).toInt() else null

        return JSONObject().apply {
            put("date", startZdt.format(dateFmt))
            put("start_time", startZdt.format(timeFmt))
            put("exercise_type", exerciseTypeName(session.exerciseType))
            put("duration_min", roundTo1(durationSec / 60.0))
            put("distance_km", if (distanceKm > 0) roundTo2(distanceKm) else JSONObject.NULL)
            put("avg_pace_min_km", avgPaceSec?.let { formatPace(it) } ?: JSONObject.NULL)
            put("avg_hr_bpm", if (hrSamples.isNotEmpty()) hrSamples.average().toInt() else JSONObject.NULL)
            put("max_hr_bpm", if (hrSamples.isNotEmpty()) hrSamples.max() else JSONObject.NULL)
            put("calories_kcal", if (totalCalories > 0) roundTo1(totalCalories) else JSONObject.NULL)
            put("avg_power_watts", if (powerSamples.isNotEmpty()) powerSamples.average().toInt() else JSONObject.NULL)
            put("title", session.title ?: JSONObject.NULL)
            put("notes", session.notes ?: JSONObject.NULL)
            put("exported_at", Instant.now().toString())

            // Per-km splits (only meaningful for runs/walks)
            if (speedSamples.size >= 2) {
                put("splits_km", buildSplits(speedSamples, session.startTime))
            }
        }
    }

    // -------------------------------------------------------------------------
    // Daily summaries
    // -------------------------------------------------------------------------

    /**
     * Returns a daily summary JSON for every day between [from] and [to].
     * Keyed by date string so the caller can filename them.
     */
    suspend fun readDailySummaries(from: Instant, to: Instant): Map<String, JSONObject> {
        val results = mutableMapOf<String, JSONObject>()

        var cursor = from.atZone(zone).toLocalDate()
        val endDate = to.atZone(zone).toLocalDate()

        while (!cursor.isAfter(endDate)) {
            val dayStart = cursor.atStartOfDay(zone).toInstant()
            val dayEnd = cursor.plusDays(1).atStartOfDay(zone).toInstant()
            val dateStr = cursor.format(dateFmt)

            results[dateStr] = buildDailyJson(dateStr, dayStart, dayEnd)
            cursor = cursor.plusDays(1)
        }

        return results
    }

    private suspend fun buildDailyJson(
        dateStr: String,
        dayStart: Instant,
        dayEnd: Instant
    ): JSONObject {
        val timeRange = TimeRangeFilter.between(dayStart, dayEnd)

        // Steps
        val stepsRecords = client.readRecords(
            ReadRecordsRequest(StepsRecord::class, timeRange)
        ).records
        val totalSteps = stepsRecords.sumOf { it.count }

        // Active calories
        val activeCalRecords = client.readRecords(
            ReadRecordsRequest(ActiveCaloriesBurnedRecord::class, timeRange)
        ).records
        val activeCalories = activeCalRecords.sumOf { it.energy.inKilocalories }

        // Total calories
        val totalCalRecords = client.readRecords(
            ReadRecordsRequest(TotalCaloriesBurnedRecord::class, timeRange)
        ).records
        val totalCalories = totalCalRecords.sumOf { it.energy.inKilocalories }

        // Floors climbed
        val floorsRecords = client.readRecords(
            ReadRecordsRequest(FloorsClimbedRecord::class, timeRange)
        ).records
        val floors = floorsRecords.sumOf { it.floors }

        // Resting heart rate
        val restingHrRecords = client.readRecords(
            ReadRecordsRequest(RestingHeartRateRecord::class, timeRange)
        ).records
        val restingHr = restingHrRecords.lastOrNull()?.beatsPerMinute

        // Heart rate variability
        val hrvRecords = client.readRecords(
            ReadRecordsRequest(HeartRateVariabilityRmssdRecord::class, timeRange)
        ).records
        val hrv = hrvRecords.lastOrNull()?.heartRateVariabilityMillis

        // Weight
        val weightRecords = client.readRecords(
            ReadRecordsRequest(WeightRecord::class, timeRange)
        ).records
        val weight = weightRecords.lastOrNull()?.weight?.inKilograms

        // Body fat
        val bodyFatRecords = client.readRecords(
            ReadRecordsRequest(BodyFatRecord::class, timeRange)
        ).records
        val bodyFat = bodyFatRecords.lastOrNull()?.percentage?.value

        // Blood oxygen / SpO2
        val spo2Records = client.readRecords(
            ReadRecordsRequest(OxygenSaturationRecord::class, timeRange)
        ).records
        val spo2 = spo2Records.lastOrNull()?.percentage?.value

        // Blood pressure
        val bpRecords = client.readRecords(
            ReadRecordsRequest(BloodPressureRecord::class, timeRange)
        ).records
        val lastBp = bpRecords.lastOrNull()

        // Blood glucose
        val glucoseRecords = client.readRecords(
            ReadRecordsRequest(BloodGlucoseRecord::class, timeRange)
        ).records
        val glucose = glucoseRecords.lastOrNull()?.level?.inMillimolesPerLiter

        // VO2 max
        val vo2Records = client.readRecords(
            ReadRecordsRequest(Vo2MaxRecord::class, timeRange)
        ).records
        val vo2max = vo2Records.lastOrNull()?.vo2MillilitersPerMinuteKilogram

        // Sleep
        val sleepRecords = client.readRecords(
            ReadRecordsRequest(SleepSessionRecord::class, timeRange)
        ).records
        val sleepJson = buildSleepJson(sleepRecords)

        return JSONObject().apply {
            put("date", dateStr)
            put("steps", if (totalSteps > 0) totalSteps else JSONObject.NULL)
            put("floors_climbed", if (floors > 0) roundTo1(floors) else JSONObject.NULL)
            put("active_calories_kcal", if (activeCalories > 0) roundTo1(activeCalories) else JSONObject.NULL)
            put("total_calories_kcal", if (totalCalories > 0) roundTo1(totalCalories) else JSONObject.NULL)
            put("resting_hr_bpm", restingHr ?: JSONObject.NULL)
            put("hrv_rmssd_ms", hrv?.let { roundTo1(it) } ?: JSONObject.NULL)
            put("weight_kg", weight?.let { roundTo2(it) } ?: JSONObject.NULL)
            put("body_fat_pct", bodyFat?.let { roundTo1(it) } ?: JSONObject.NULL)
            put("spo2_pct", spo2?.let { roundTo1(it) } ?: JSONObject.NULL)
            put("blood_pressure", if (lastBp != null) JSONObject().apply {
                put("systolic_mmhg", lastBp.systolic.inMillimetersOfMercury.toInt())
                put("diastolic_mmhg", lastBp.diastolic.inMillimetersOfMercury.toInt())
            } else JSONObject.NULL)
            put("blood_glucose_mmol_l", glucose?.let { roundTo2(it) } ?: JSONObject.NULL)
            put("vo2_max", vo2max?.let { roundTo1(it) } ?: JSONObject.NULL)
            put("sleep", sleepJson)
            put("exported_at", Instant.now().toString())
        }
    }

    private fun buildSleepJson(sessions: List<SleepSessionRecord>): Any {
        if (sessions.isEmpty()) return JSONObject.NULL

        val main = sessions.maxByOrNull {
            it.endTime.epochSecond - it.startTime.epochSecond
        } ?: return JSONObject.NULL

        val durationMin = (main.endTime.epochSecond - main.startTime.epochSecond) / 60

        val stagesArray = JSONArray()
        main.stages.forEach { stage ->
            val stageDurationMin = (stage.endTime.epochSecond - stage.startTime.epochSecond) / 60
            stagesArray.put(JSONObject().apply {
                put("stage", sleepStageName(stage.stage))
                put("duration_min", stageDurationMin)
            })
        }

        return JSONObject().apply {
            put("start_time", main.startTime.atZone(zone).format(timeFmt))
            put("end_time", main.endTime.atZone(zone).format(timeFmt))
            put("duration_min", durationMin)
            put("stages", stagesArray)
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun computeDistanceKm(samples: List<SpeedRecord.Sample>, totalSec: Long): Double {
        if (samples.size < 2) return 0.0
        var meters = 0.0
        for (i in 1 until samples.size) {
            val dt = (samples[i].time.epochSecond - samples[i - 1].time.epochSecond).toDouble()
            val avg = (samples[i].speed.inMetersPerSecond + samples[i - 1].speed.inMetersPerSecond) / 2.0
            meters += avg * dt
        }
        return meters / 1000.0
    }

    private fun buildSplits(
        speedSamples: List<SpeedRecord.Sample>,
        sessionStart: Instant
    ): JSONArray {
        val splits = JSONArray()
        var kmIndex = 1
        var distanceMeters = 0.0
        var splitStart = sessionStart

        for (i in 1 until speedSamples.size) {
            val dt = (speedSamples[i].time.epochSecond - speedSamples[i - 1].time.epochSecond).toDouble()
            val avg = (speedSamples[i].speed.inMetersPerSecond + speedSamples[i - 1].speed.inMetersPerSecond) / 2.0
            distanceMeters += avg * dt

            if (distanceMeters >= kmIndex * 1000.0) {
                val splitEnd = speedSamples[i].time
                val splitDurationSec = (splitEnd.epochSecond - splitStart.epochSecond).toInt()
                splits.put(JSONObject().apply {
                    put("km", kmIndex)
                    put("pace_min_km", formatPace(splitDurationSec))
                    put("duration_sec", splitDurationSec)
                })
                kmIndex++
                splitStart = splitEnd
            }
        }
        return splits
    }

    private fun formatPace(secPerKm: Int): String {
        val min = secPerKm / 60
        val sec = secPerKm % 60
        return "$min:${sec.toString().padStart(2, '0')}"
    }

    private fun roundTo1(d: Double) = Math.round(d * 10) / 10.0
    private fun roundTo2(d: Double) = Math.round(d * 100) / 100.0

    private fun exerciseTypeName(type: Int): String = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "outdoor_run"
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "treadmill_run"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "walk"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "hike"
        ExerciseSessionRecord.EXERCISE_TYPE_CYCLING -> "cycling"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "swim_open_water"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "swim_pool"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "strength"
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "yoga"
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "elliptical"
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> "rowing"
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING -> "stair_climbing"
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "hiit"
        else -> "exercise_$type"
    }

    private fun sleepStageName(stage: Int): String = when (stage) {
        SleepSessionRecord.STAGE_TYPE_AWAKE -> "awake"
        SleepSessionRecord.STAGE_TYPE_SLEEPING -> "sleeping"
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "out_of_bed"
        SleepSessionRecord.STAGE_TYPE_LIGHT -> "light"
        SleepSessionRecord.STAGE_TYPE_DEEP -> "deep"
        SleepSessionRecord.STAGE_TYPE_REM -> "rem"
        else -> "unknown"
    }

    companion object {
        val PERMISSIONS = setOf(
            // Exercise & movement
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(FloorsClimbedRecord::class),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
            // Activity metrics
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(RestingHeartRateRecord::class),
            HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
            HealthPermission.getReadPermission(SpeedRecord::class),
            HealthPermission.getReadPermission(PowerRecord::class),
            HealthPermission.getReadPermission(Vo2MaxRecord::class),
            // Body metrics
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getReadPermission(BodyFatRecord::class),
            // Vitals
            HealthPermission.getReadPermission(OxygenSaturationRecord::class),
            HealthPermission.getReadPermission(BloodPressureRecord::class),
            HealthPermission.getReadPermission(BloodGlucoseRecord::class),
            // Sleep
            HealthPermission.getReadPermission(SleepSessionRecord::class),
        )
    }
}
