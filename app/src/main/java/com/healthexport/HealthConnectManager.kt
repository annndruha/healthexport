package com.healthexport

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.healthexport.models.BodyMeasurementData
import com.healthexport.models.DailyHealthData
import com.healthexport.models.ExerciseData
import com.healthexport.models.ExportConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.lang.reflect.Modifier
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong
import kotlin.reflect.KClass

/**
 * Thin wrapper over [HealthConnectClient] that reads the record types selected in [ExportConfig]
 * and groups them into one [DailyHealthData] per local calendar day.
 *
 * Map keys emitted here are the contract used by SheetsManager and CsvExporter.
 */
class HealthConnectManager(private val context: Context) {

    // Lazy so callers that only need getTimeZone() don't fail when Health Connect is unavailable.
    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    @Volatile
    private var saferExportMode = false

    private val readTypes: Set<KClass<out Record>> = setOf(
        // Activity
        ActiveCaloriesBurnedRecord::class,
        DistanceRecord::class,
        ElevationGainedRecord::class,
        ExerciseSessionRecord::class,
        FloorsClimbedRecord::class,
        PowerRecord::class,
        SpeedRecord::class,
        StepsRecord::class,
        TotalCaloriesBurnedRecord::class,
        Vo2MaxRecord::class,
        WheelchairPushesRecord::class,

        // Body Measurements
        BodyFatRecord::class,
        BoneMassRecord::class,
        HeightRecord::class,
        LeanBodyMassRecord::class,
        WeightRecord::class,

        // Cycle Tracking
        CervicalMucusRecord::class,
        MenstruationFlowRecord::class,
        MenstruationPeriodRecord::class,
        OvulationTestRecord::class,
        SexualActivityRecord::class,

        // Nutrition
        HydrationRecord::class,
        NutritionRecord::class,

        // Sleep
        SleepSessionRecord::class,

        // Vitals
        BloodGlucoseRecord::class,
        BloodPressureRecord::class,
        BodyTemperatureRecord::class,
        HeartRateRecord::class,
        HeartRateVariabilityRmssdRecord::class,
        OxygenSaturationRecord::class,
        RespiratoryRateRecord::class,
        RestingHeartRateRecord::class
    )

    val permissions: Set<String> = readTypes.map { HealthPermission.getReadPermission(it) }.toSet()

    private val dayFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val dateTimeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val dateTimeSecondsFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

    fun setSaferExportMode(enabled: Boolean) {
        saferExportMode = enabled
    }

    fun getTimeZone(): String = ZoneId.systemDefault().id

    /**
     * Reads the last [ExportConfig.daysBack] calendar days (including today), or everything
     * when daysBack < 0. Result is sorted by date ("yyyy-MM-dd").
     */
    suspend fun readHealthDataByDay(config: ExportConfig): Map<String, DailyHealthData> =
        withContext(Dispatchers.IO) {
            val zone = ZoneId.systemDefault()
            val granted = call { client.permissionController.getGrantedPermissions() }
            fun canRead(type: KClass<out Record>) = HealthPermission.getReadPermission(type) in granted

            val today = LocalDate.now(zone)
            val now = Instant.now()

            val startDay = if (config.daysBack < 0) {
                findEarliestDay(enabledTypes(config).filter(::canRead), zone) ?: return@withContext emptyMap()
            } else {
                today.minusDays((config.daysBack - 1).coerceAtLeast(0).toLong())
            }

            val range = Range(
                zone = zone,
                startDay = startDay,
                endDayExclusive = today.plusDays(1),
                start = startDay.atStartOfDay(zone).toInstant(),
                end = now
            )

            val activity = Buckets()
            val body = Buckets()
            val cycle = Buckets()
            val nutrition = Buckets()
            val sleep = Buckets()
            val vitals = Buckets()

            readActivity(config, range, ::canRead, activity)
            readBody(config, range, ::canRead, body)
            readCycle(config, range, ::canRead, cycle)
            readNutrition(config, range, ::canRead, nutrition)
            if (config.includeSleepSession && canRead(SleepSessionRecord::class)) readSleep(range, sleep)
            readVitals(config, range, ::canRead, vitals)

            val days = listOf(activity, body, cycle, nutrition, sleep, vitals)
                .flatMap { it.days() }
                .toSortedSet()

            days.associateWithTo(LinkedHashMap()) { day ->
                DailyHealthData(
                    date = day,
                    activityData = activity.build(day),
                    bodyMeasurementData = body.build(day),
                    cycleTrackingData = cycle.build(day),
                    nutritionData = nutrition.build(day),
                    sleepData = sleep.build(day),
                    vitalsData = vitals.build(day)
                )
            }
        }

    // -------------------------
    // Activity
    // -------------------------

    private suspend fun readActivity(
        config: ExportConfig,
        range: Range,
        canRead: (KClass<out Record>) -> Boolean,
        out: Buckets
    ) {
        if (config.includeSteps && canRead(StepsRecord::class)) {
            aggregateByDay(setOf(StepsRecord.COUNT_TOTAL), range) { day, r ->
                out.put(day, "Steps", r[StepsRecord.COUNT_TOTAL], r.origins())
            }
        }
        if (config.includeDistance && canRead(DistanceRecord::class)) {
            aggregateByDay(setOf(DistanceRecord.DISTANCE_TOTAL), range) { day, r ->
                out.put(day, "Distance", r[DistanceRecord.DISTANCE_TOTAL]?.inMeters?.round2(), r.origins())
            }
        }
        if (config.includeElevationGained && canRead(ElevationGainedRecord::class)) {
            aggregateByDay(setOf(ElevationGainedRecord.ELEVATION_GAINED_TOTAL), range) { day, r ->
                out.put(day, "Elevation", r[ElevationGainedRecord.ELEVATION_GAINED_TOTAL]?.inMeters?.round2(), r.origins())
            }
        }
        if (config.includeFloorsClimbed && canRead(FloorsClimbedRecord::class)) {
            aggregateByDay(setOf(FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL), range) { day, r ->
                out.put(day, "Floors climbed", r[FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL]?.round2(), r.origins())
            }
        }
        if (config.includeTotalCalories && canRead(TotalCaloriesBurnedRecord::class)) {
            aggregateByDay(setOf(TotalCaloriesBurnedRecord.ENERGY_TOTAL), range) { day, r ->
                out.put(day, "Total calories burned", r[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories?.round2(), r.origins())
            }
        }
        if (config.includeActiveCalories && canRead(ActiveCaloriesBurnedRecord::class)) {
            aggregateByDay(setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL), range) { day, r ->
                out.put(day, "Active calories burned", r[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories?.round2(), r.origins())
            }
        }
        if (config.includePower && canRead(PowerRecord::class)) {
            aggregateByDay(setOf(PowerRecord.POWER_MIN, PowerRecord.POWER_MAX, PowerRecord.POWER_AVG), range) { day, r ->
                out.putMinMaxAvg(
                    day, "Power", "W",
                    r[PowerRecord.POWER_MIN]?.inWatts,
                    r[PowerRecord.POWER_MAX]?.inWatts,
                    r[PowerRecord.POWER_AVG]?.inWatts,
                    r.origins()
                )
            }
        }
        if (config.includeSpeed && canRead(SpeedRecord::class)) {
            aggregateByDay(setOf(SpeedRecord.SPEED_MIN, SpeedRecord.SPEED_MAX, SpeedRecord.SPEED_AVG), range) { day, r ->
                out.putMinMaxAvg(
                    day, "Speed", "m/s",
                    r[SpeedRecord.SPEED_MIN]?.inMetersPerSecond,
                    r[SpeedRecord.SPEED_MAX]?.inMetersPerSecond,
                    r[SpeedRecord.SPEED_AVG]?.inMetersPerSecond,
                    r.origins()
                )
            }
        }
        if (config.includeWheelchairPushes && canRead(WheelchairPushesRecord::class)) {
            aggregateByDay(setOf(WheelchairPushesRecord.COUNT_TOTAL), range) { day, r ->
                out.put(day, "Wheelchair pushes", r[WheelchairPushesRecord.COUNT_TOTAL], r.origins())
            }
        }
        if (config.includeVO2Max && canRead(Vo2MaxRecord::class)) {
            val stats = HashMap<String, Stats>()
            readEach(Vo2MaxRecord::class, range.start, range.end) { rec ->
                stats.getOrPut(range.dayOf(rec.time)) { Stats() }
                    .add(rec.vo2MillilitersPerMinuteKilogram, rec.metadata.dataOrigin.packageName)
            }
            out.putStats(stats, "VO2 max", "ml/min/kg")
        }
        if (config.includeExercise && canRead(ExerciseSessionRecord::class)) {
            val byDay = HashMap<String, MutableList<ExerciseData>>()
            readEach(ExerciseSessionRecord::class, range.start, range.end) { rec ->
                val day = range.dayOf(rec.startTime)
                byDay.getOrPut(day) { mutableListOf() } += ExerciseData(
                    date = day,
                    startDateTime = rec.startTime.atZone(range.zone).format(dateTimeFmt),
                    exerciseName = exerciseName(rec),
                    durationMinutes = Duration.between(rec.startTime, rec.endTime).toMinutes()
                )
                out.addSource(day, rec.metadata.dataOrigin.packageName)
            }
            byDay.forEach { (day, list) -> out.put(day, "Exercises", list.sortedBy { it.startDateTime }) }
        }
    }

    private val exerciseTypeNames: Map<Int, String> by lazy {
        ExerciseSessionRecord::class.java.fields
            .filter {
                Modifier.isStatic(it.modifiers) &&
                        it.type == Int::class.javaPrimitiveType &&
                        it.name.startsWith("EXERCISE_TYPE_")
            }
            .associate { field ->
                field.getInt(null) to field.name
                    .removePrefix("EXERCISE_TYPE_")
                    .lowercase(Locale.US)
                    .replace('_', ' ')
                    .replaceFirstChar { it.titlecase(Locale.US) }
            }
    }

    private fun exerciseName(rec: ExerciseSessionRecord): String {
        val typeName = exerciseTypeNames[rec.exerciseType] ?: "Other"
        val title = rec.title?.trim().orEmpty()
        return if (title.isEmpty() || title.equals(typeName, ignoreCase = true)) typeName else "$typeName ($title)"
    }

    // -------------------------
    // Body Measurements
    // -------------------------

    private suspend fun readBody(
        config: ExportConfig,
        range: Range,
        canRead: (KClass<out Record>) -> Boolean,
        out: Buckets
    ) {
        // day -> dateTime -> (weight, bodyFat)
        val measurements = HashMap<String, MutableMap<String, Pair<String?, String?>>>()
        fun measurement(t: Instant, weight: String? = null, bodyFat: String? = null, origin: String) {
            val day = range.dayOf(t)
            val dt = t.atZone(range.zone).format(dateTimeSecondsFmt)
            val perDay = measurements.getOrPut(day) { sortedMapOf() }
            val prev = perDay[dt]
            perDay[dt] = (weight ?: prev?.first) to (bodyFat ?: prev?.second)
            out.addSource(day, origin)
        }

        if (config.includeWeight && canRead(WeightRecord::class)) {
            readEach(WeightRecord::class, range.start, range.end) { rec ->
                measurement(rec.time, weight = rec.weight.inKilograms.fmt2(), origin = rec.metadata.dataOrigin.packageName)
            }
        }
        if (config.includeBodyFat && canRead(BodyFatRecord::class)) {
            readEach(BodyFatRecord::class, range.start, range.end) { rec ->
                measurement(rec.time, bodyFat = rec.percentage.value.fmt2(), origin = rec.metadata.dataOrigin.packageName)
            }
        }

        // Extra fields are day-level strings: "HH:mm=value, HH:mm=value"
        val firstExtraTime = HashMap<String, Instant>()
        fun extra(key: String, t: Instant, value: Double, origin: String) {
            val day = range.dayOf(t)
            out.appendText(day, key, "${t.atZone(range.zone).format(timeFmt)}=${value.fmt2()}", origin)
            firstExtraTime.merge(day, t) { a, b -> minOf(a, b) }
        }

        if (config.includeBoneMass && canRead(BoneMassRecord::class)) {
            readEach(BoneMassRecord::class, range.start, range.end) { rec ->
                extra("Bone mass (kg)", rec.time, rec.mass.inKilograms, rec.metadata.dataOrigin.packageName)
            }
        }
        if (config.includeHeight && canRead(HeightRecord::class)) {
            readEach(HeightRecord::class, range.start, range.end) { rec ->
                extra("Height (m)", rec.time, rec.height.inMeters, rec.metadata.dataOrigin.packageName)
            }
        }
        if (config.includeLeanBodyMass && canRead(LeanBodyMassRecord::class)) {
            readEach(LeanBodyMassRecord::class, range.start, range.end) { rec ->
                extra("Lean body mass (kg)", rec.time, rec.mass.inKilograms, rec.metadata.dataOrigin.packageName)
            }
        }

        // Exporters emit rows only from "Measurements", so give extras-only days one row to ride on.
        firstExtraTime.forEach { (day, t) ->
            if (day !in measurements) {
                measurements[day] = sortedMapOf<String, Pair<String?, String?>>(
                    t.atZone(range.zone).format(dateTimeSecondsFmt) to (null to null)
                )
            }
        }

        measurements.forEach { (day, perDay) ->
            out.put(day, "Measurements", perDay.map { (dt, v) -> BodyMeasurementData(dt, v.first, v.second) })
        }
    }

    // -------------------------
    // Cycle Tracking
    // -------------------------

    private suspend fun readCycle(
        config: ExportConfig,
        range: Range,
        canRead: (KClass<out Record>) -> Boolean,
        out: Buckets
    ) {
        if (config.includeCervicalMucus && canRead(CervicalMucusRecord::class)) {
            readEach(CervicalMucusRecord::class, range.start, range.end) { rec ->
                val parts = listOfNotNull(mucusAppearance(rec.appearance), mucusSensation(rec.sensation))
                out.appendText(
                    range.dayOf(rec.time), "Cervical mucus",
                    parts.ifEmpty { listOf("Unknown") }.joinToString(" / "),
                    rec.metadata.dataOrigin.packageName
                )
            }
        }
        if (config.includeMenstruation && canRead(MenstruationFlowRecord::class)) {
            readEach(MenstruationFlowRecord::class, range.start, range.end) { rec ->
                out.appendText(range.dayOf(rec.time), "Menstruation flow", menstruationFlow(rec.flow), rec.metadata.dataOrigin.packageName)
            }
        }
        if (config.includeMenstruation && canRead(MenstruationPeriodRecord::class)) {
            // Periods can start before the range, so look back a bit and clip to the range.
            readEach(MenstruationPeriodRecord::class, range.start.minus(Duration.ofDays(31)), range.end) { rec ->
                var d = maxOf(rec.startTime.atZone(range.zone).toLocalDate(), range.startDay)
                val last = minOf(rec.endTime.minusMillis(1).atZone(range.zone).toLocalDate(), range.endDayExclusive.minusDays(1))
                while (d <= last) {
                    out.put(d.format(dayFmt), "Menstruation period", "Yes", listOf(rec.metadata.dataOrigin.packageName))
                    d = d.plusDays(1)
                }
            }
        }
        if (config.includeOvulationTest && canRead(OvulationTestRecord::class)) {
            readEach(OvulationTestRecord::class, range.start, range.end) { rec ->
                out.appendText(range.dayOf(rec.time), "Ovulation test", ovulationResult(rec.result), rec.metadata.dataOrigin.packageName)
            }
        }
        if (config.includeSexualActivity && canRead(SexualActivityRecord::class)) {
            readEach(SexualActivityRecord::class, range.start, range.end) { rec ->
                out.appendText(range.dayOf(rec.time), "Sexual activity", protectionUsed(rec.protectionUsed), rec.metadata.dataOrigin.packageName)
            }
        }
    }

    private fun mucusAppearance(v: Int): String? = when (v) {
        CervicalMucusRecord.APPEARANCE_DRY -> "Dry"
        CervicalMucusRecord.APPEARANCE_STICKY -> "Sticky"
        CervicalMucusRecord.APPEARANCE_CREAMY -> "Creamy"
        CervicalMucusRecord.APPEARANCE_WATERY -> "Watery"
        CervicalMucusRecord.APPEARANCE_EGG_WHITE -> "Egg white"
        CervicalMucusRecord.APPEARANCE_UNUSUAL -> "Unusual"
        else -> null
    }

    private fun mucusSensation(v: Int): String? = when (v) {
        CervicalMucusRecord.SENSATION_LIGHT -> "Light"
        CervicalMucusRecord.SENSATION_MEDIUM -> "Medium"
        CervicalMucusRecord.SENSATION_HEAVY -> "Heavy"
        else -> null
    }

    private fun menstruationFlow(v: Int): String = when (v) {
        MenstruationFlowRecord.FLOW_LIGHT -> "Light"
        MenstruationFlowRecord.FLOW_MEDIUM -> "Medium"
        MenstruationFlowRecord.FLOW_HEAVY -> "Heavy"
        else -> "Unknown"
    }

    private fun ovulationResult(v: Int): String = when (v) {
        OvulationTestRecord.RESULT_POSITIVE -> "Positive"
        OvulationTestRecord.RESULT_HIGH -> "High"
        OvulationTestRecord.RESULT_NEGATIVE -> "Negative"
        else -> "Inconclusive"
    }

    private fun protectionUsed(v: Int): String = when (v) {
        SexualActivityRecord.PROTECTION_USED_PROTECTED -> "Protected"
        SexualActivityRecord.PROTECTION_USED_UNPROTECTED -> "Unprotected"
        else -> "Yes"
    }

    // -------------------------
    // Nutrition
    // -------------------------

    private suspend fun readNutrition(
        config: ExportConfig,
        range: Range,
        canRead: (KClass<out Record>) -> Boolean,
        out: Buckets
    ) {
        if (config.includeHydration && canRead(HydrationRecord::class)) {
            aggregateByDay(setOf(HydrationRecord.VOLUME_TOTAL), range) { day, r ->
                out.put(day, "Hydration (L)", r[HydrationRecord.VOLUME_TOTAL]?.inLiters?.round2(), r.origins())
            }
        }
        if (config.includeNutrition && canRead(NutritionRecord::class)) {
            val metrics = setOf(
                NutritionRecord.ENERGY_TOTAL,
                NutritionRecord.PROTEIN_TOTAL,
                NutritionRecord.TOTAL_CARBOHYDRATE_TOTAL,
                NutritionRecord.TOTAL_FAT_TOTAL,
                NutritionRecord.SATURATED_FAT_TOTAL,
                NutritionRecord.SUGAR_TOTAL,
                NutritionRecord.DIETARY_FIBER_TOTAL,
                NutritionRecord.SODIUM_TOTAL,
                NutritionRecord.CAFFEINE_TOTAL
            )
            aggregateByDay(metrics, range) { day, r ->
                val origins = r.origins()
                out.put(day, "Energy (kcal)", r[NutritionRecord.ENERGY_TOTAL]?.inKilocalories?.round2(), origins)
                out.put(day, "Protein (g)", r[NutritionRecord.PROTEIN_TOTAL]?.inGrams?.round2(), origins)
                out.put(day, "Carbohydrates (g)", r[NutritionRecord.TOTAL_CARBOHYDRATE_TOTAL]?.inGrams?.round2(), origins)
                out.put(day, "Fat (g)", r[NutritionRecord.TOTAL_FAT_TOTAL]?.inGrams?.round2(), origins)
                out.put(day, "Saturated fat (g)", r[NutritionRecord.SATURATED_FAT_TOTAL]?.inGrams?.round2(), origins)
                out.put(day, "Sugar (g)", r[NutritionRecord.SUGAR_TOTAL]?.inGrams?.round2(), origins)
                out.put(day, "Fiber (g)", r[NutritionRecord.DIETARY_FIBER_TOTAL]?.inGrams?.round2(), origins)
                out.put(day, "Sodium (mg)", r[NutritionRecord.SODIUM_TOTAL]?.inMilligrams?.round2(), origins)
                out.put(day, "Caffeine (mg)", r[NutritionRecord.CAFFEINE_TOTAL]?.inMilligrams?.round2(), origins)
            }
        }
    }

    // -------------------------
    // Sleep (assigned to the day the session ends)
    // -------------------------

    private class SleepAcc {
        var start: Instant? = null
        var end: Instant? = null
        var lightMs = 0L
        var deepMs = 0L
        var remMs = 0L
        var awakeMs = 0L
        var asleepMs = 0L
    }

    private suspend fun readSleep(range: Range, out: Buckets) {
        val byDay = HashMap<String, SleepAcc>()

        // Sessions that end inside the range may start the evening before it.
        readEach(SleepSessionRecord::class, range.start.minus(Duration.ofDays(1)), range.end) { rec ->
            val endDay = rec.endTime.atZone(range.zone).toLocalDate()
            if (endDay < range.startDay) return@readEach

            val day = endDay.format(dayFmt)
            val acc = byDay.getOrPut(day) { SleepAcc() }
            acc.start = acc.start?.let { minOf(it, rec.startTime) } ?: rec.startTime
            acc.end = acc.end?.let { maxOf(it, rec.endTime) } ?: rec.endTime

            var stagedAwakeMs = 0L
            rec.stages.forEach { s ->
                val ms = Duration.between(s.startTime, s.endTime).toMillis()
                when (s.stage) {
                    SleepSessionRecord.STAGE_TYPE_LIGHT -> acc.lightMs += ms
                    SleepSessionRecord.STAGE_TYPE_DEEP -> acc.deepMs += ms
                    SleepSessionRecord.STAGE_TYPE_REM -> acc.remMs += ms
                    SleepSessionRecord.STAGE_TYPE_AWAKE,
                    SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
                    SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> stagedAwakeMs += ms
                }
            }
            acc.awakeMs += stagedAwakeMs
            acc.asleepMs += Duration.between(rec.startTime, rec.endTime).toMillis() - stagedAwakeMs

            out.addSource(day, rec.metadata.dataOrigin.packageName)
        }

        fun minutes(ms: Long) = Duration.ofMillis(ms).toMinutes()

        byDay.forEach { (day, acc) ->
            out.put(day, "Start time", acc.start?.atZone(range.zone)?.format(dateTimeFmt))
            out.put(day, "End time", acc.end?.atZone(range.zone)?.format(dateTimeFmt))
            out.put(day, "Light sleep (min)", minutes(acc.lightMs))
            out.put(day, "Deep sleep (min)", minutes(acc.deepMs))
            out.put(day, "REM sleep (min)", minutes(acc.remMs))
            out.put(day, "Awake (min)", minutes(acc.awakeMs))
            out.put(day, "Total sleep (min)", minutes(acc.asleepMs))
        }
    }

    // -------------------------
    // Vitals
    // -------------------------

    private suspend fun readVitals(
        config: ExportConfig,
        range: Range,
        canRead: (KClass<out Record>) -> Boolean,
        out: Buckets
    ) {
        if (config.includeHeartRate && canRead(HeartRateRecord::class)) {
            aggregateByDay(setOf(HeartRateRecord.BPM_MIN, HeartRateRecord.BPM_MAX, HeartRateRecord.BPM_AVG), range) { day, r ->
                out.putMinMaxAvg(
                    day, "Heart rate", "bpm",
                    r[HeartRateRecord.BPM_MIN], r[HeartRateRecord.BPM_MAX], r[HeartRateRecord.BPM_AVG],
                    r.origins()
                )
            }
        }
        if (config.includeRestingHeartRate && canRead(RestingHeartRateRecord::class)) {
            aggregateByDay(setOf(RestingHeartRateRecord.BPM_MIN, RestingHeartRateRecord.BPM_MAX, RestingHeartRateRecord.BPM_AVG), range) { day, r ->
                out.putMinMaxAvg(
                    day, "Resting heart rate", "bpm",
                    r[RestingHeartRateRecord.BPM_MIN], r[RestingHeartRateRecord.BPM_MAX], r[RestingHeartRateRecord.BPM_AVG],
                    r.origins()
                )
            }
        }
        if (config.includeHeartRateVariability && canRead(HeartRateVariabilityRmssdRecord::class)) {
            val stats = HashMap<String, Stats>()
            readEach(HeartRateVariabilityRmssdRecord::class, range.start, range.end) { rec ->
                stats.getOrPut(range.dayOf(rec.time)) { Stats() }
                    .add(rec.heartRateVariabilityMillis, rec.metadata.dataOrigin.packageName)
            }
            out.putStats(stats, "Heart rate variability", "ms")
        }
        if (config.includeOxygenSaturation && canRead(OxygenSaturationRecord::class)) {
            val stats = HashMap<String, Stats>()
            readEach(OxygenSaturationRecord::class, range.start, range.end) { rec ->
                stats.getOrPut(range.dayOf(rec.time)) { Stats() }
                    .add(rec.percentage.value, rec.metadata.dataOrigin.packageName)
            }
            out.putStats(stats, "Oxygen saturation", "%")
        }
        if (config.includeRespiratoryRate && canRead(RespiratoryRateRecord::class)) {
            val stats = HashMap<String, Stats>()
            readEach(RespiratoryRateRecord::class, range.start, range.end) { rec ->
                stats.getOrPut(range.dayOf(rec.time)) { Stats() }
                    .add(rec.rate, rec.metadata.dataOrigin.packageName)
            }
            out.putStats(stats, "Respiratory rate", "breaths/min")
        }

        // Point readings are day-level strings: "HH:mm=value, HH:mm=value"
        fun reading(t: Instant) = t.atZone(range.zone).format(timeFmt)

        if (config.includeBloodPressure && canRead(BloodPressureRecord::class)) {
            readEach(BloodPressureRecord::class, range.start, range.end) { rec ->
                val sys = rec.systolic.inMillimetersOfMercury.roundToLong()
                val dia = rec.diastolic.inMillimetersOfMercury.roundToLong()
                out.appendText(range.dayOf(rec.time), "Blood pressure (mmHg)", "${reading(rec.time)}=$sys/$dia", rec.metadata.dataOrigin.packageName)
            }
        }
        if (config.includeBloodGlucose && canRead(BloodGlucoseRecord::class)) {
            readEach(BloodGlucoseRecord::class, range.start, range.end) { rec ->
                out.appendText(
                    range.dayOf(rec.time), "Blood glucose (mmol/L)",
                    "${reading(rec.time)}=${rec.level.inMillimolesPerLiter.fmt2()}",
                    rec.metadata.dataOrigin.packageName
                )
            }
        }
        if (config.includeBodyTemperature && canRead(BodyTemperatureRecord::class)) {
            readEach(BodyTemperatureRecord::class, range.start, range.end) { rec ->
                out.appendText(
                    range.dayOf(rec.time), "Body temperature (°C)",
                    "${reading(rec.time)}=${rec.temperature.inCelsius.fmt2()}",
                    rec.metadata.dataOrigin.packageName
                )
            }
        }
    }

    // -------------------------
    // Range helpers
    // -------------------------

    private inner class Range(
        val zone: ZoneId,
        val startDay: LocalDate,
        val endDayExclusive: LocalDate,
        val start: Instant,
        val end: Instant
    ) {
        fun dayOf(t: Instant): String = t.atZone(zone).toLocalDate().format(dayFmt)
    }

    private fun enabledTypes(c: ExportConfig): List<KClass<out Record>> = buildList {
        if (c.includeActiveCalories) add(ActiveCaloriesBurnedRecord::class)
        if (c.includeDistance) add(DistanceRecord::class)
        if (c.includeElevationGained) add(ElevationGainedRecord::class)
        if (c.includeExercise) add(ExerciseSessionRecord::class)
        if (c.includeFloorsClimbed) add(FloorsClimbedRecord::class)
        if (c.includePower) add(PowerRecord::class)
        if (c.includeSpeed) add(SpeedRecord::class)
        if (c.includeSteps) add(StepsRecord::class)
        if (c.includeTotalCalories) add(TotalCaloriesBurnedRecord::class)
        if (c.includeVO2Max) add(Vo2MaxRecord::class)
        if (c.includeWheelchairPushes) add(WheelchairPushesRecord::class)

        if (c.includeBodyFat) add(BodyFatRecord::class)
        if (c.includeBoneMass) add(BoneMassRecord::class)
        if (c.includeHeight) add(HeightRecord::class)
        if (c.includeLeanBodyMass) add(LeanBodyMassRecord::class)
        if (c.includeWeight) add(WeightRecord::class)

        if (c.includeCervicalMucus) add(CervicalMucusRecord::class)
        if (c.includeMenstruation) {
            add(MenstruationFlowRecord::class)
            add(MenstruationPeriodRecord::class)
        }
        if (c.includeOvulationTest) add(OvulationTestRecord::class)
        if (c.includeSexualActivity) add(SexualActivityRecord::class)

        if (c.includeHydration) add(HydrationRecord::class)
        if (c.includeNutrition) add(NutritionRecord::class)

        if (c.includeSleepSession) add(SleepSessionRecord::class)

        if (c.includeBloodGlucose) add(BloodGlucoseRecord::class)
        if (c.includeBloodPressure) add(BloodPressureRecord::class)
        if (c.includeBodyTemperature) add(BodyTemperatureRecord::class)
        if (c.includeHeartRate) add(HeartRateRecord::class)
        if (c.includeHeartRateVariability) add(HeartRateVariabilityRmssdRecord::class)
        if (c.includeOxygenSaturation) add(OxygenSaturationRecord::class)
        if (c.includeRespiratoryRate) add(RespiratoryRateRecord::class)
        if (c.includeRestingHeartRate) add(RestingHeartRateRecord::class)
    }

    /** "All time" start: the day of the oldest record among the selected types (one tiny read per type). */
    private suspend fun findEarliestDay(types: List<KClass<out Record>>, zone: ZoneId): LocalDate? {
        val now = Instant.now()
        var earliest: Instant? = null
        types.forEach { type ->
            val resp = call {
                client.readRecords(
                    ReadRecordsRequest(
                        recordType = type,
                        timeRangeFilter = TimeRangeFilter.before(now),
                        ascendingOrder = true,
                        pageSize = 1
                    )
                )
            }
            resp.records.firstOrNull()?.let { rec ->
                val t = recordTime(rec)
                if (earliest == null || t < earliest) earliest = t
            }
        }
        return earliest?.atZone(zone)?.toLocalDate()
    }

    private fun recordTime(rec: Record): Instant = when (rec) {
        is InstantaneousRecord -> rec.time
        is IntervalRecord -> rec.startTime
        else -> Instant.now()
    }

    // -------------------------
    // Health Connect calls (paging, chunking, rate limits)
    // -------------------------

    private suspend fun aggregateByDay(
        metrics: Set<AggregateMetric<*>>,
        range: Range,
        consume: (String, AggregationResult) -> Unit
    ) {
        val chunkDays = if (saferExportMode) SAFER_CHUNK_DAYS else CHUNK_DAYS
        var from = range.startDay
        while (from < range.endDayExclusive) {
            val to = minOf(from.plusDays(chunkDays), range.endDayExclusive)
            val chunkStart = from
            val groups = call {
                client.aggregateGroupByPeriod(
                    AggregateGroupByPeriodRequest(
                        metrics = metrics,
                        timeRangeFilter = TimeRangeFilter.between(chunkStart.atStartOfDay(), to.atStartOfDay()),
                        timeRangeSlicer = Period.ofDays(1)
                    )
                )
            }
            groups.forEach { g ->
                if (metrics.any { it in g.result }) consume(g.startTime.toLocalDate().format(dayFmt), g.result)
            }
            from = to
        }
    }

    private suspend fun <T : Record> readEach(
        type: KClass<T>,
        start: Instant,
        end: Instant,
        consume: (T) -> Unit
    ) {
        val pageSize = if (saferExportMode) SAFER_PAGE_SIZE else PAGE_SIZE
        var pageToken: String? = null
        do {
            val token = pageToken
            val resp = call {
                client.readRecords(
                    ReadRecordsRequest(
                        recordType = type,
                        timeRangeFilter = TimeRangeFilter.between(start, end),
                        pageSize = pageSize,
                        pageToken = token
                    )
                )
            }
            resp.records.forEach(consume)
            pageToken = resp.pageToken
        } while (!pageToken.isNullOrEmpty())
    }

    /** Runs a Health Connect request, retrying with backoff on rate limiting. Safer mode also paces requests. */
    private suspend fun <R> call(block: suspend () -> R): R {
        val attempts = if (saferExportMode) SAFER_MAX_ATTEMPTS else MAX_ATTEMPTS
        var backoffMs = if (saferExportMode) SAFER_BACKOFF_MS else BACKOFF_MS
        repeat(attempts - 1) {
            if (saferExportMode) delay(SAFER_REQUEST_DELAY_MS)
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isRateLimited(e)) throw e
                delay(backoffMs)
                backoffMs *= 2
            }
        }
        if (saferExportMode) delay(SAFER_REQUEST_DELAY_MS)
        return block()
    }

    private fun isRateLimited(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { t ->
            val msg = t.message.orEmpty()
            msg.contains("rate limit", ignoreCase = true) || msg.contains("quota", ignoreCase = true)
        }

    // -------------------------
    // Per-day accumulation
    // -------------------------

    private class Stats {
        var min = Double.MAX_VALUE
        var max = -Double.MAX_VALUE
        var sum = 0.0
        var count = 0
        val origins = mutableSetOf<String>()

        fun add(v: Double, origin: String) {
            if (v < min) min = v
            if (v > max) max = v
            sum += v
            count++
            origins += origin
        }
    }

    private inner class Buckets {
        private val values = HashMap<String, LinkedHashMap<String, Any?>>()
        private val sources = HashMap<String, MutableSet<String>>()

        fun days(): Set<String> = values.keys

        fun put(day: String, key: String, value: Any?, origins: Collection<String> = emptyList()) {
            if (value == null) return
            values.getOrPut(day) { LinkedHashMap() }[key] = value
            if (origins.isNotEmpty()) sources.getOrPut(day) { sortedSetOf() } += origins
        }

        fun addSource(day: String, origin: String) {
            sources.getOrPut(day) { sortedSetOf() } += origin
        }

        fun appendText(day: String, key: String, text: String, origin: String) {
            val map = values.getOrPut(day) { LinkedHashMap() }
            val prev = map[key] as? String
            map[key] = if (prev.isNullOrEmpty()) text else "$prev, $text"
            addSource(day, origin)
        }

        fun putMinMaxAvg(
            day: String, label: String, unit: String,
            min: Number?, max: Number?, avg: Number?,
            origins: Collection<String>
        ) {
            put(day, "$label min ($unit)", min?.roundIfDouble(), origins)
            put(day, "$label max ($unit)", max?.roundIfDouble(), origins)
            put(day, "$label avg ($unit)", avg?.roundIfDouble(), origins)
        }

        fun putStats(stats: Map<String, Stats>, label: String, unit: String) {
            stats.forEach { (day, s) ->
                if (s.count > 0) putMinMaxAvg(day, label, unit, s.min, s.max, s.sum / s.count, s.origins)
            }
        }

        fun build(day: String): Map<String, Any?> {
            val v = values[day] ?: return emptyMap()
            if (v.isEmpty()) return emptyMap()
            val src = sources[day].orEmpty().joinToString(", ") { appLabel(it) }
            return LinkedHashMap<String, Any?>().apply {
                put("Source(s)", src)
                putAll(v)
            }
        }
    }

    private val appLabels = HashMap<String, String>()

    private fun appLabel(packageName: String): String = appLabels.getOrPut(packageName) {
        try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (_: Exception) {
            packageName
        }
    }

    private fun AggregationResult.origins(): List<String> = dataOrigins.map { it.packageName }

    private fun Double.round2(): Double = (this * 100.0).roundToLong() / 100.0

    private fun Double.fmt2(): String = String.format(Locale.US, "%.2f", this)

    private fun Number.roundIfDouble(): Number = if (this is Double) round2() else this

    private companion object {
        const val PAGE_SIZE = 5000
        const val SAFER_PAGE_SIZE = 1000
        const val CHUNK_DAYS = 90L
        const val SAFER_CHUNK_DAYS = 30L
        const val MAX_ATTEMPTS = 3
        const val SAFER_MAX_ATTEMPTS = 6
        const val BACKOFF_MS = 1_000L
        const val SAFER_BACKOFF_MS = 3_000L
        const val SAFER_REQUEST_DELAY_MS = 250L
    }
}
