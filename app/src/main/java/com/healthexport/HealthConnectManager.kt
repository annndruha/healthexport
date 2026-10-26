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
import com.healthexport.models.HealthReadResult
import com.healthexport.models.TypeCoverage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.lang.reflect.Method
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
 * Reads are split into time chunks; a failing chunk is skipped and reported in
 * [HealthReadResult.warnings] instead of failing the whole export.
 */
class HealthConnectManager(private val context: Context) {

    /** Thrown when Health Connect stops serving reads because the app left the foreground. */
    class ExportInterruptedException(message: String, cause: Throwable) : Exception(message, cause)

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

    /** Read permissions for all data types. History access is requested separately. */
    val permissions: Set<String> = readTypes.map { HealthPermission.getReadPermission(it) }.toSet()

    private val dayFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val dateTimeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val dateTimeSecondsFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

    fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    suspend fun getGrantedPermissions(): Set<String> =
        call { client.permissionController.getGrantedPermissions() }

    fun setSaferExportMode(enabled: Boolean) {
        saferExportMode = enabled
    }

    fun getTimeZone(): String = ZoneId.systemDefault().id

    /**
     * Reads the last [ExportConfig.daysBack] calendar days (including today), or everything
     * when daysBack < 0. Data is sorted by date ("yyyy-MM-dd").
     *
     * [onProgress] gets the current step and overall progress 0..1; it is called on a background thread.
     */
    suspend fun readHealthDataByDay(
        config: ExportConfig,
        onProgress: (label: String, fraction: Float) -> Unit = { _, _ -> }
    ): HealthReadResult =
        withContext(Dispatchers.IO) {
            val zone = ZoneId.systemDefault()
            val granted = getGrantedPermissions()
            val historyGranted = PERMISSION_READ_HEALTH_DATA_HISTORY in granted
            fun canRead(type: KClass<out Record>) = HealthPermission.getReadPermission(type) in granted

            val today = LocalDate.now(zone)
            val selected = enabledTypes(config)
            val readable = selected.filter(::canRead)

            val startDay = if (config.daysBack < 0) {
                onProgress("Looking for the oldest data", 0f)
                findEarliestDay(readable, zone) ?: today
            } else {
                today.minusDays((config.daysBack - 1).coerceAtLeast(0).toLong())
            }

            val s = Session(
                zone = zone,
                startDay = startDay,
                endDayExclusive = today.plusDays(1),
                start = startDay.atStartOfDay(zone).toInstant(),
                end = Instant.now(),
                historyGranted = historyGranted,
                // Every readable type is exactly one aggregateByDay / readEach call.
                totalTasks = readable.size,
                onProgress = onProgress
            )

            val missing = selected.filterNot(::canRead)
            if (missing.isNotEmpty()) {
                s.warn("No Health Connect permission for: " +
                        missing.joinToString { it.simpleName.orEmpty().removeSuffix("Record") })
            }
            if (!historyGranted && (config.daysBack < 0 || config.daysBack > 30)) {
                s.warn(HISTORY_HINT)
            }

            val activity = Buckets()
            val body = Buckets()
            val cycle = Buckets()
            val nutrition = Buckets()
            val sleep = Buckets()
            val vitals = Buckets()

            readActivity(config, s, ::canRead, activity)
            readBody(config, s, ::canRead, body)
            readCycle(config, s, ::canRead, cycle)
            readNutrition(config, s, ::canRead, nutrition)
            if (config.includeSleepSession && canRead(SleepSessionRecord::class)) readSleep(s, sleep)
            readVitals(config, s, ::canRead, vitals)

            val days = listOf(activity, body, cycle, nutrition, sleep, vitals)
                .flatMap { it.days() }
                .toSortedSet()

            val data = days.associateWithTo(LinkedHashMap()) { day ->
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

            HealthReadResult(
                data = data,
                warnings = s.warnings(),
                coverage = s.coverage(),
                fromDay = startDay.format(dayFmt),
                toDay = today.format(dayFmt),
                historyGranted = historyGranted
            )
        }

    // -------------------------
    // Activity
    // -------------------------

    private suspend fun readActivity(
        config: ExportConfig,
        s: Session,
        canRead: (KClass<out Record>) -> Boolean,
        out: Buckets
    ) {
        if (config.includeSteps && canRead(StepsRecord::class)) {
            aggregateByDay("Steps", setOf(StepsRecord.COUNT_TOTAL), s) { day, r ->
                out.put(day, "Steps", r[StepsRecord.COUNT_TOTAL], r.origins())
            }
        }
        if (config.includeDistance && canRead(DistanceRecord::class)) {
            aggregateByDay("Distance", setOf(DistanceRecord.DISTANCE_TOTAL), s) { day, r ->
                out.put(day, "Distance", r[DistanceRecord.DISTANCE_TOTAL]?.inMeters?.round2(), r.origins())
            }
        }
        if (config.includeElevationGained && canRead(ElevationGainedRecord::class)) {
            aggregateByDay("Elevation gained", setOf(ElevationGainedRecord.ELEVATION_GAINED_TOTAL), s) { day, r ->
                out.put(day, "Elevation", r[ElevationGainedRecord.ELEVATION_GAINED_TOTAL]?.inMeters?.round2(), r.origins())
            }
        }
        if (config.includeFloorsClimbed && canRead(FloorsClimbedRecord::class)) {
            aggregateByDay("Floors climbed", setOf(FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL), s) { day, r ->
                out.put(day, "Floors climbed", r[FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL]?.round2(), r.origins())
            }
        }
        if (config.includeTotalCalories && canRead(TotalCaloriesBurnedRecord::class)) {
            aggregateByDay("Total calories", setOf(TotalCaloriesBurnedRecord.ENERGY_TOTAL), s) { day, r ->
                out.put(day, "Total calories burned", r[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories?.round2(), r.origins())
            }
        }
        if (config.includeActiveCalories && canRead(ActiveCaloriesBurnedRecord::class)) {
            aggregateByDay("Active calories", setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL), s) { day, r ->
                out.put(day, "Active calories burned", r[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories?.round2(), r.origins())
            }
        }
        // Power and speed come from raw samples: some Health Connect versions reject their min/max
        // aggregation ("Unsupported aggregation type Speed_series_speed_min").
        if (config.includePower && canRead(PowerRecord::class)) {
            val stats = HashMap<String, Stats>()
            readEach("Power", PowerRecord::class, s.start, s.end, s, dedupe = true) { rec ->
                val origin = rec.metadata.dataOrigin.packageName
                rec.samples.forEach { smp ->
                    if (smp.time >= s.start) stats.getOrPut(s.dayOf(smp.time)) { Stats() }.add(smp.power.inWatts, origin)
                }
            }
            out.putStats(stats, "Power", "W")
        }
        if (config.includeSpeed && canRead(SpeedRecord::class)) {
            val stats = HashMap<String, Stats>()
            readEach("Speed", SpeedRecord::class, s.start, s.end, s, dedupe = true) { rec ->
                val origin = rec.metadata.dataOrigin.packageName
                rec.samples.forEach { smp ->
                    if (smp.time >= s.start) stats.getOrPut(s.dayOf(smp.time)) { Stats() }.add(smp.speed.inMetersPerSecond, origin)
                }
            }
            out.putStats(stats, "Speed", "m/s")
        }
        if (config.includeWheelchairPushes && canRead(WheelchairPushesRecord::class)) {
            aggregateByDay("Wheelchair pushes", setOf(WheelchairPushesRecord.COUNT_TOTAL), s) { day, r ->
                out.put(day, "Wheelchair pushes", r[WheelchairPushesRecord.COUNT_TOTAL], r.origins())
            }
        }
        if (config.includeVO2Max && canRead(Vo2MaxRecord::class)) {
            val stats = HashMap<String, Stats>()
            readEach("VO2 max", Vo2MaxRecord::class, s.start, s.end, s) { rec ->
                stats.getOrPut(s.dayOf(rec.time)) { Stats() }
                    .add(rec.vo2MillilitersPerMinuteKilogram, rec.metadata.dataOrigin.packageName)
            }
            out.putStats(stats, "VO2 max", "ml/min/kg")
        }
        if (config.includeExercise && canRead(ExerciseSessionRecord::class)) {
            val byDay = HashMap<String, MutableList<ExerciseData>>()
            readEach("Exercise", ExerciseSessionRecord::class, s.start, s.end, s, dedupe = true) { rec ->
                val day = s.dayOf(rec.startTime)
                byDay.getOrPut(day) { mutableListOf() } += ExerciseData(
                    date = day,
                    startDateTime = rec.startTime.atZone(s.zone).format(dateTimeFmt),
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
        s: Session,
        canRead: (KClass<out Record>) -> Boolean,
        out: Buckets
    ) {
        // day -> dateTime -> (weight, bodyFat)
        val measurements = HashMap<String, MutableMap<String, Pair<String?, String?>>>()
        fun measurement(t: Instant, weight: String? = null, bodyFat: String? = null, origin: String) {
            val day = s.dayOf(t)
            val dt = t.atZone(s.zone).format(dateTimeSecondsFmt)
            val perDay = measurements.getOrPut(day) { sortedMapOf() }
            val prev = perDay[dt]
            perDay[dt] = (weight ?: prev?.first) to (bodyFat ?: prev?.second)
            out.addSource(day, origin)
        }

        if (config.includeWeight && canRead(WeightRecord::class)) {
            readEach("Weight", WeightRecord::class, s.start, s.end, s) { rec ->
                measurement(rec.time, weight = rec.weight.inKilograms.fmt2(), origin = rec.metadata.dataOrigin.packageName)
            }
        }
        if (config.includeBodyFat && canRead(BodyFatRecord::class)) {
            readEach("Body fat", BodyFatRecord::class, s.start, s.end, s) { rec ->
                measurement(rec.time, bodyFat = rec.percentage.value.fmt2(), origin = rec.metadata.dataOrigin.packageName)
            }
        }

        // Extra fields are day-level strings: "HH:mm=value, HH:mm=value"
        val firstExtraTime = HashMap<String, Instant>()
        fun extra(key: String, t: Instant, value: Double, origin: String) {
            val day = s.dayOf(t)
            out.appendText(day, key, "${t.atZone(s.zone).format(timeFmt)}=${value.fmt2()}", origin)
            firstExtraTime.merge(day, t) { a, b -> minOf(a, b) }
        }

        if (config.includeBoneMass && canRead(BoneMassRecord::class)) {
            readEach("Bone mass", BoneMassRecord::class, s.start, s.end, s) { rec ->
                extra("Bone mass (kg)", rec.time, rec.mass.inKilograms, rec.metadata.dataOrigin.packageName)
            }
        }
        if (config.includeHeight && canRead(HeightRecord::class)) {
            readEach("Height", HeightRecord::class, s.start, s.end, s) { rec ->
                extra("Height (m)", rec.time, rec.height.inMeters, rec.metadata.dataOrigin.packageName)
            }
        }
        if (config.includeLeanBodyMass && canRead(LeanBodyMassRecord::class)) {
            readEach("Lean body mass", LeanBodyMassRecord::class, s.start, s.end, s) { rec ->
                extra("Lean body mass (kg)", rec.time, rec.mass.inKilograms, rec.metadata.dataOrigin.packageName)
            }
        }

        // The exporter emits rows only from "Measurements", so give extras-only days one row to ride on.
        firstExtraTime.forEach { (day, t) ->
            if (day !in measurements) {
                measurements[day] = sortedMapOf<String, Pair<String?, String?>>(
                    t.atZone(s.zone).format(dateTimeSecondsFmt) to (null to null)
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
        s: Session,
        canRead: (KClass<out Record>) -> Boolean,
        out: Buckets
    ) {
        if (config.includeCervicalMucus && canRead(CervicalMucusRecord::class)) {
            readEach("Cervical mucus", CervicalMucusRecord::class, s.start, s.end, s) { rec ->
                val parts = listOfNotNull(mucusAppearance(rec.appearance), mucusSensation(rec.sensation))
                out.appendText(
                    s.dayOf(rec.time), "Cervical mucus",
                    parts.ifEmpty { listOf("Unknown") }.joinToString(" / "),
                    rec.metadata.dataOrigin.packageName
                )
            }
        }
        if (config.includeMenstruation && canRead(MenstruationFlowRecord::class)) {
            readEach("Menstruation flow", MenstruationFlowRecord::class, s.start, s.end, s) { rec ->
                out.appendText(s.dayOf(rec.time), "Menstruation flow", menstruationFlow(rec.flow), rec.metadata.dataOrigin.packageName)
            }
        }
        if (config.includeMenstruation && canRead(MenstruationPeriodRecord::class)) {
            // Periods can start before the range, so look back a bit and clip to the range.
            val lookBack = s.start.minus(Duration.ofDays(31))
            readEach("Menstruation period", MenstruationPeriodRecord::class, lookBack, s.end, s, dedupe = true) { rec ->
                var d = maxOf(rec.startTime.atZone(s.zone).toLocalDate(), s.startDay)
                val last = minOf(rec.endTime.minusMillis(1).atZone(s.zone).toLocalDate(), s.endDayExclusive.minusDays(1))
                while (d <= last) {
                    out.put(d.format(dayFmt), "Menstruation period", "Yes", listOf(rec.metadata.dataOrigin.packageName))
                    d = d.plusDays(1)
                }
            }
        }
        if (config.includeOvulationTest && canRead(OvulationTestRecord::class)) {
            readEach("Ovulation test", OvulationTestRecord::class, s.start, s.end, s) { rec ->
                out.appendText(s.dayOf(rec.time), "Ovulation test", ovulationResult(rec.result), rec.metadata.dataOrigin.packageName)
            }
        }
        if (config.includeSexualActivity && canRead(SexualActivityRecord::class)) {
            readEach("Sexual activity", SexualActivityRecord::class, s.start, s.end, s) { rec ->
                out.appendText(s.dayOf(rec.time), "Sexual activity", protectionUsed(rec.protectionUsed), rec.metadata.dataOrigin.packageName)
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
        s: Session,
        canRead: (KClass<out Record>) -> Boolean,
        out: Buckets
    ) {
        if (config.includeHydration && canRead(HydrationRecord::class)) {
            aggregateByDay("Hydration", setOf(HydrationRecord.VOLUME_TOTAL), s) { day, r ->
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
            aggregateByDay("Nutrition", metrics, s) { day, r ->
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

    private suspend fun readSleep(s: Session, out: Buckets) {
        val byDay = HashMap<String, SleepAcc>()

        // Sessions that end inside the range may start the evening before it.
        readEach("Sleep", SleepSessionRecord::class, s.start.minus(Duration.ofDays(1)), s.end, s, dedupe = true) { rec ->
            val endDay = rec.endTime.atZone(s.zone).toLocalDate()
            if (endDay < s.startDay) return@readEach

            val day = endDay.format(dayFmt)
            val acc = byDay.getOrPut(day) { SleepAcc() }
            acc.start = acc.start?.let { minOf(it, rec.startTime) } ?: rec.startTime
            acc.end = acc.end?.let { maxOf(it, rec.endTime) } ?: rec.endTime

            var stagedAwakeMs = 0L
            rec.stages.forEach { st ->
                val ms = Duration.between(st.startTime, st.endTime).toMillis()
                when (st.stage) {
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
            out.put(day, "Start time", acc.start?.atZone(s.zone)?.format(dateTimeFmt))
            out.put(day, "End time", acc.end?.atZone(s.zone)?.format(dateTimeFmt))
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
        s: Session,
        canRead: (KClass<out Record>) -> Boolean,
        out: Buckets
    ) {
        if (config.includeHeartRate && canRead(HeartRateRecord::class)) {
            aggregateByDay("Heart rate", setOf(HeartRateRecord.BPM_MIN, HeartRateRecord.BPM_MAX, HeartRateRecord.BPM_AVG), s) { day, r ->
                out.putMinMaxAvg(
                    day, "Heart rate", "bpm",
                    r[HeartRateRecord.BPM_MIN], r[HeartRateRecord.BPM_MAX], r[HeartRateRecord.BPM_AVG],
                    r.origins()
                )
            }
        }
        if (config.includeRestingHeartRate && canRead(RestingHeartRateRecord::class)) {
            aggregateByDay("Resting heart rate", setOf(RestingHeartRateRecord.BPM_MIN, RestingHeartRateRecord.BPM_MAX, RestingHeartRateRecord.BPM_AVG), s) { day, r ->
                out.putMinMaxAvg(
                    day, "Resting heart rate", "bpm",
                    r[RestingHeartRateRecord.BPM_MIN], r[RestingHeartRateRecord.BPM_MAX], r[RestingHeartRateRecord.BPM_AVG],
                    r.origins()
                )
            }
        }
        if (config.includeHeartRateVariability && canRead(HeartRateVariabilityRmssdRecord::class)) {
            val stats = HashMap<String, Stats>()
            readEach("Heart rate variability", HeartRateVariabilityRmssdRecord::class, s.start, s.end, s) { rec ->
                stats.getOrPut(s.dayOf(rec.time)) { Stats() }
                    .add(rec.heartRateVariabilityMillis, rec.metadata.dataOrigin.packageName)
            }
            out.putStats(stats, "Heart rate variability", "ms")
        }
        if (config.includeOxygenSaturation && canRead(OxygenSaturationRecord::class)) {
            val stats = HashMap<String, Stats>()
            readEach("Oxygen saturation", OxygenSaturationRecord::class, s.start, s.end, s) { rec ->
                stats.getOrPut(s.dayOf(rec.time)) { Stats() }
                    .add(rec.percentage.value, rec.metadata.dataOrigin.packageName)
            }
            out.putStats(stats, "Oxygen saturation", "%")
        }
        if (config.includeRespiratoryRate && canRead(RespiratoryRateRecord::class)) {
            val stats = HashMap<String, Stats>()
            readEach("Respiratory rate", RespiratoryRateRecord::class, s.start, s.end, s) { rec ->
                stats.getOrPut(s.dayOf(rec.time)) { Stats() }
                    .add(rec.rate, rec.metadata.dataOrigin.packageName)
            }
            out.putStats(stats, "Respiratory rate", "breaths/min")
        }

        // Point readings are day-level strings: "HH:mm=value, HH:mm=value"
        fun reading(t: Instant) = t.atZone(s.zone).format(timeFmt)

        if (config.includeBloodPressure && canRead(BloodPressureRecord::class)) {
            readEach("Blood pressure", BloodPressureRecord::class, s.start, s.end, s) { rec ->
                val sys = rec.systolic.inMillimetersOfMercury.roundToLong()
                val dia = rec.diastolic.inMillimetersOfMercury.roundToLong()
                out.appendText(s.dayOf(rec.time), "Blood pressure (mmHg)", "${reading(rec.time)}=$sys/$dia", rec.metadata.dataOrigin.packageName)
            }
        }
        if (config.includeBloodGlucose && canRead(BloodGlucoseRecord::class)) {
            readEach("Blood glucose", BloodGlucoseRecord::class, s.start, s.end, s) { rec ->
                out.appendText(
                    s.dayOf(rec.time), "Blood glucose (mmol/L)",
                    "${reading(rec.time)}=${rec.level.inMillimolesPerLiter.fmt2()}",
                    rec.metadata.dataOrigin.packageName
                )
            }
        }
        if (config.includeBodyTemperature && canRead(BodyTemperatureRecord::class)) {
            readEach("Body temperature", BodyTemperatureRecord::class, s.start, s.end, s) { rec ->
                out.appendText(
                    s.dayOf(rec.time), "Body temperature (°C)",
                    "${reading(rec.time)}=${rec.temperature.inCelsius.fmt2()}",
                    rec.metadata.dataOrigin.packageName
                )
            }
        }
    }

    // -------------------------
    // Session: range + per-chunk failure tracking
    // -------------------------

    private class Failure(val firstMessage: String) {
        var count = 0
        var rateLimited = false
    }

    /** What was actually received for one data type: count and first/last day. */
    private class Coverage(val unit: String) {
        var count = 0
        var first: String? = null
        var last: String? = null
    }

    private inner class Session(
        val zone: ZoneId,
        val startDay: LocalDate,
        val endDayExclusive: LocalDate,
        val start: Instant,
        val end: Instant,
        val historyGranted: Boolean,
        private val totalTasks: Int,
        private val onProgress: (String, Float) -> Unit
    ) {
        private val notes = mutableListOf<String>()
        private val failures = LinkedHashMap<String, Failure>()
        private val coverage = LinkedHashMap<String, Coverage>()
        private var historySkippedChunks = 0
        private var doneTasks = 0

        fun dayOf(t: Instant): String = t.atZone(zone).toLocalDate().format(dayFmt)

        fun warn(message: String) {
            notes += message
        }

        fun progress(label: String, chunk: Int, chunks: Int) {
            val f = if (totalTasks == 0) 1f
            else (doneTasks + chunk.toFloat() / chunks.coerceAtLeast(1)) / totalTasks
            onProgress(label, f.coerceIn(0f, 1f))
        }

        fun taskDone() {
            doneTasks++
        }

        fun startCoverage(label: String, unit: String) {
            coverage.getOrPut(label) { Coverage(unit) }
        }

        fun cover(label: String, day: String) {
            val c = coverage[label] ?: return
            c.count++
            val first = c.first
            val last = c.last
            if (first == null || day < first) c.first = day
            if (last == null || day > last) c.last = day
        }

        fun coverage(): List<TypeCoverage> =
            coverage.map { (label, c) -> TypeCoverage(label, c.unit, c.count, c.first, c.last) }

        /** Records a failed chunk, or rethrows when the whole export must stop. */
        fun handle(label: String, e: Exception) {
            if (e is CancellationException || e is ExportInterruptedException) throw e
            when {
                isBackgroundError(e) -> throw ExportInterruptedException(
                    "Export interrupted: Health Connect only allows reading while the app is open. " +
                            "Keep the app in the foreground until the export finishes.", e
                )
                isHistoryRestricted(e) -> historySkippedChunks++
                else -> {
                    val f = failures.getOrPut(label) { Failure(e.message ?: e.javaClass.simpleName) }
                    f.count++
                    if (isRateLimited(e)) f.rateLimited = true
                }
            }
        }

        fun warnings(): List<String> = buildList {
            addAll(notes)
            if (historySkippedChunks > 0) {
                add("$historySkippedChunks period(s) were skipped because Health Connect refused to return data that old." +
                        if (historyGranted) "" else " $HISTORY_HINT")
            }
            failures.forEach { (label, f) ->
                add("$label: ${f.count} period(s) failed and were skipped (${f.firstMessage}).")
            }
            if (failures.values.any { it.rateLimited }) {
                add("Health Connect rate limit was hit. Enable \"Safer export mode\" and try again.")
            }
        }
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

    /**
     * "All time" start: the day of the oldest record among the selected types (one tiny read per type).
     * Without history access Health Connect itself limits how far back this can see.
     */
    private suspend fun findEarliestDay(types: List<KClass<out Record>>, zone: ZoneId): LocalDate? {
        val now = Instant.now()
        var earliest: Instant? = null
        types.forEach { type ->
            val first = try {
                call {
                    client.readRecords(
                        ReadRecordsRequest(
                            recordType = type,
                            timeRangeFilter = TimeRangeFilter.before(now),
                            ascendingOrder = true,
                            pageSize = 1
                        )
                    )
                }.records.firstOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            first?.let(::recordTime)?.let { t ->
                if (earliest == null || t < earliest) earliest = t
            }
        }
        return earliest?.atZone(zone)?.toLocalDate()
    }

    private val timeGetters = HashMap<Class<*>, Method?>()

    // InstantaneousRecord / IntervalRecord are internal, so read `time` or `startTime` via its getter.
    private fun recordTime(rec: Record): Instant? = try {
        val getter = synchronized(timeGetters) {
            timeGetters.getOrPut(rec.javaClass) {
                rec.javaClass.methods.firstOrNull {
                    it.parameterCount == 0 && (it.name == "getStartTime" || it.name == "getTime")
                }
            }
        }
        getter?.invoke(rec) as? Instant
    } catch (_: Exception) {
        null
    }

    // -------------------------
    // Health Connect calls (chunking, paging, rate limits)
    // -------------------------

    private suspend fun aggregateByDay(
        label: String,
        metrics: Set<AggregateMetric<*>>,
        s: Session,
        consume: (String, AggregationResult) -> Unit
    ) {
        val chunkDays = if (saferExportMode) SAFER_AGGREGATE_CHUNK_DAYS else AGGREGATE_CHUNK_DAYS
        val totalDays = Duration.between(s.startDay.atStartOfDay(), s.endDayExclusive.atStartOfDay()).toDays()
        val chunks = ((totalDays + chunkDays - 1) / chunkDays).toInt()
        s.startCoverage(label, "days")

        var chunk = 0
        var from = s.startDay
        while (from < s.endDayExclusive) {
            s.progress(label, chunk++, chunks)
            val to = minOf(from.plusDays(chunkDays), s.endDayExclusive)
            val chunkStart = from
            try {
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
                    if (metrics.any { it in g.result }) {
                        val day = g.startTime.toLocalDate().format(dayFmt)
                        s.cover(label, day)
                        consume(day, g.result)
                    }
                }
            } catch (e: Exception) {
                s.handle(label, e)
            }
            from = to
        }
        s.taskDone()
    }

    /**
     * Reads records in fixed time chunks. Interval records that cross a chunk boundary are returned
     * for both chunks, so pass [dedupe] for interval types.
     */
    private suspend fun <T : Record> readEach(
        label: String,
        type: KClass<T>,
        start: Instant,
        end: Instant,
        s: Session,
        dedupe: Boolean = false,
        consume: (T) -> Unit
    ) {
        val step = Duration.ofDays(if (saferExportMode) SAFER_RAW_CHUNK_DAYS else RAW_CHUNK_DAYS)
        val pageSize = if (saferExportMode) SAFER_PAGE_SIZE else PAGE_SIZE
        val seen = if (dedupe) HashSet<String>() else null
        val chunks = ((Duration.between(start, end).toMillis() + step.toMillis() - 1) / step.toMillis()).toInt()
        s.startCoverage(label, "records")

        var chunk = 0
        var from = start
        while (from < end) {
            s.progress(label, chunk++, chunks)
            val to = minOf(from.plus(step), end)
            val chunkStart = from
            try {
                var pageToken: String? = null
                do {
                    val token = pageToken
                    val resp = call {
                        client.readRecords(
                            ReadRecordsRequest(
                                recordType = type,
                                timeRangeFilter = TimeRangeFilter.between(chunkStart, to),
                                pageSize = pageSize,
                                pageToken = token
                            )
                        )
                    }
                    resp.records.forEach { rec ->
                        if (seen == null || seen.add(rec.metadata.id)) {
                            recordTime(rec)?.let { s.cover(label, s.dayOf(it)) }
                            consume(rec)
                        }
                    }
                    pageToken = resp.pageToken
                } while (!pageToken.isNullOrEmpty())
            } catch (e: Exception) {
                s.handle(label, e)
            }
            from = to
        }
        s.taskDone()
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

    private fun messages(e: Throwable): Sequence<String> =
        generateSequence(e) { it.cause }.map { it.message.orEmpty() }

    private fun isRateLimited(e: Throwable): Boolean = messages(e).any {
        it.contains("rate limit", ignoreCase = true) || it.contains("quota", ignoreCase = true)
    }

    private fun isBackgroundError(e: Throwable): Boolean = messages(e).any {
        it.contains("background", ignoreCase = true) || it.contains("foreground", ignoreCase = true)
    }

    // Our chunk ranges are always valid, so a "start must be before end" error means Health Connect
    // clipped the range to its history window and nothing was left.
    private fun isHistoryRestricted(e: Throwable): Boolean = messages(e).any {
        it.contains("history", ignoreCase = true) ||
                it.contains("older than 30", ignoreCase = true) ||
                it.contains("start time must be before end time", ignoreCase = true) ||
                it.contains("starttime must be before endtime", ignoreCase = true)
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
            stats.forEach { (day, st) ->
                if (st.count > 0) putMinMaxAvg(day, label, unit, st.min, st.max, st.sum / st.count, st.origins)
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

    companion object {
        /** Lets the app read data older than 30 days before permissions were first granted. */
        const val PERMISSION_READ_HEALTH_DATA_HISTORY = "android.permission.health.READ_HEALTH_DATA_HISTORY"

        private const val HISTORY_HINT =
            "\"Access past data\" is not granted: Health Connect only returns data from 30 days " +
                    "before this app was first given access. Tap \"Allow access to data older than 30 days\"."

        private const val PAGE_SIZE = 5000
        private const val SAFER_PAGE_SIZE = 1000
        private const val AGGREGATE_CHUNK_DAYS = 90L
        private const val SAFER_AGGREGATE_CHUNK_DAYS = 30L
        private const val RAW_CHUNK_DAYS = 31L
        private const val SAFER_RAW_CHUNK_DAYS = 7L
        private const val MAX_ATTEMPTS = 3
        private const val SAFER_MAX_ATTEMPTS = 6
        private const val BACKOFF_MS = 1_000L
        private const val SAFER_BACKOFF_MS = 3_000L
        private const val SAFER_REQUEST_DELAY_MS = 250L
    }
}
