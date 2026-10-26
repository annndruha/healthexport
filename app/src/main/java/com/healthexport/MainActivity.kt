package com.healthexport

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Spanned
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import com.healthexport.models.ExportConfig
import com.healthexport.models.HealthReadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    // Category checkboxes
    private lateinit var checkCategoryActivity: CheckBox
    private lateinit var checkCategoryBodyMeasurements: CheckBox
    private lateinit var checkCategoryCycleTracking: CheckBox
    private lateinit var checkCategoryNutrition: CheckBox
    private lateinit var checkCategorySleep: CheckBox
    private lateinit var checkCategoryVitals: CheckBox

    // Activity sub-checkboxes
    private lateinit var checkActiveCalories: CheckBox
    private lateinit var checkDistance: CheckBox
    private lateinit var checkElevationGained: CheckBox
    private lateinit var checkExercise: CheckBox
    private lateinit var checkFloorsClimbed: CheckBox
    private lateinit var checkPower: CheckBox
    private lateinit var checkSpeed: CheckBox
    private lateinit var checkSteps: CheckBox
    private lateinit var checkTotalCalories: CheckBox
    private lateinit var checkVO2Max: CheckBox
    private lateinit var checkWheelchairPushes: CheckBox

    // Body Measurements
    private lateinit var checkBodyFat: CheckBox
    private lateinit var checkBoneMass: CheckBox
    private lateinit var checkHeight: CheckBox
    private lateinit var checkLeanBodyMass: CheckBox
    private lateinit var checkWeight: CheckBox

    // Cycle Tracking
    private lateinit var checkCervicalMucus: CheckBox
    private lateinit var checkMenstruation: CheckBox
    private lateinit var checkOvulationTest: CheckBox
    private lateinit var checkSexualActivity: CheckBox

    // Nutrition
    private lateinit var checkHydration: CheckBox
    private lateinit var checkNutrition: CheckBox

    // Sleep
    private lateinit var checkSleepSession: CheckBox

    // Vitals
    private lateinit var checkBloodGlucose: CheckBox
    private lateinit var checkBloodPressure: CheckBox
    private lateinit var checkBodyTemperature: CheckBox
    private lateinit var checkHeartRate: CheckBox
    private lateinit var checkHeartRateVariability: CheckBox
    private lateinit var checkOxygenSaturation: CheckBox
    private lateinit var checkRespiratoryRate: CheckBox
    private lateinit var checkRestingHeartRate: CheckBox

    // Expandable layouts
    private lateinit var layoutActivitySubtypes: LinearLayout
    private lateinit var layoutBodyMeasurementsSubtypes: LinearLayout
    private lateinit var layoutCycleTrackingSubtypes: LinearLayout
    private lateinit var layoutNutritionSubtypes: LinearLayout
    private lateinit var layoutSleepSubtypes: LinearLayout
    private lateinit var layoutVitalsSubtypes: LinearLayout

    // Health Connect access
    private lateinit var txtPermissionStatus: TextView
    private lateinit var btnGrantPermissions: Button
    private lateinit var btnGrantHistory: Button
    private lateinit var txtHistoryHint: TextView

    // Other UI
    private lateinit var spinnerDateRange: Spinner
    private lateinit var spinnerCsvSplit: Spinner
    private lateinit var switchSaferExport: SwitchMaterial

    private lateinit var btnExportCsv: Button
    private lateinit var txtStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var txtProgress: TextView

    private enum class StatusKind { INFO, SUCCESS, WARNING, ERROR }
    private var defaultStatusColors: ColorStateList? = null

    private lateinit var healthConnectManager: HealthConnectManager

    private val prefs by lazy { getSharedPreferences("health_export_prefs", MODE_PRIVATE) }

    private val exportInProgress = AtomicBoolean(false)

    private val allTimePosition = 4
    private fun isAllTimeSelected(): Boolean = spinnerDateRange.selectedItemPosition == allTimePosition

    private val healthPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { refreshPermissionStatus() }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCsvExport()
        } else {
            exportInProgress.set(false)
            showStatus("Storage permission denied: cannot save CSV files", StatusKind.ERROR)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        healthConnectManager = HealthConnectManager(this)

        bindViews()

        loadPrefsIntoUi()
        setupCategories()
        applyAsterisks()
        setupListeners()

        if (!healthConnectManager.isAvailable()) {
            txtPermissionStatus.text = "Health Connect is not available. Install or update Health Connect and reopen the app."
            showStatus("Health Connect is not available", StatusKind.ERROR)
            btnGrantPermissions.isEnabled = false
            btnGrantHistory.isEnabled = false
            btnExportCsv.isEnabled = false
            return
        }

        refreshPermissionStatus(requestIfNoneGranted = true)
    }

    override fun onResume() {
        super.onResume()
        // Permissions may have changed in Health Connect settings.
        if (healthConnectManager.isAvailable()) refreshPermissionStatus()
    }

    override fun onPause() {
        super.onPause()
        saveUiToPrefs()
    }

    private fun bindViews() {
        checkCategoryActivity = findViewById(R.id.checkCategoryActivity)
        checkCategoryBodyMeasurements = findViewById(R.id.checkCategoryBodyMeasurements)
        checkCategoryCycleTracking = findViewById(R.id.checkCategoryCycleTracking)
        checkCategoryNutrition = findViewById(R.id.checkCategoryNutrition)
        checkCategorySleep = findViewById(R.id.checkCategorySleep)
        checkCategoryVitals = findViewById(R.id.checkCategoryVitals)

        checkActiveCalories = findViewById(R.id.checkActiveCalories)
        checkDistance = findViewById(R.id.checkDistance)
        checkElevationGained = findViewById(R.id.checkElevationGained)
        checkExercise = findViewById(R.id.checkExercise)
        checkFloorsClimbed = findViewById(R.id.checkFloorsClimbed)
        checkPower = findViewById(R.id.checkPower)
        checkSpeed = findViewById(R.id.checkSpeed)
        checkSteps = findViewById(R.id.checkSteps)
        checkTotalCalories = findViewById(R.id.checkTotalCalories)
        checkVO2Max = findViewById(R.id.checkVO2Max)
        checkWheelchairPushes = findViewById(R.id.checkWheelchairPushes)

        checkBodyFat = findViewById(R.id.checkBodyFat)
        checkBoneMass = findViewById(R.id.checkBoneMass)
        checkHeight = findViewById(R.id.checkHeight)
        checkLeanBodyMass = findViewById(R.id.checkLeanBodyMass)
        checkWeight = findViewById(R.id.checkWeight)

        checkCervicalMucus = findViewById(R.id.checkCervicalMucus)
        checkMenstruation = findViewById(R.id.checkMenstruation)
        checkOvulationTest = findViewById(R.id.checkOvulationTest)
        checkSexualActivity = findViewById(R.id.checkSexualActivity)

        checkHydration = findViewById(R.id.checkHydration)
        checkNutrition = findViewById(R.id.checkNutrition)

        checkSleepSession = findViewById(R.id.checkSleepSession)

        checkBloodGlucose = findViewById(R.id.checkBloodGlucose)
        checkBloodPressure = findViewById(R.id.checkBloodPressure)
        checkBodyTemperature = findViewById(R.id.checkBodyTemperature)
        checkHeartRate = findViewById(R.id.checkHeartRate)
        checkHeartRateVariability = findViewById(R.id.checkHeartRateVariability)
        checkOxygenSaturation = findViewById(R.id.checkOxygenSaturation)
        checkRespiratoryRate = findViewById(R.id.checkRespiratoryRate)
        checkRestingHeartRate = findViewById(R.id.checkRestingHeartRate)

        layoutActivitySubtypes = findViewById(R.id.layoutActivitySubtypes)
        layoutBodyMeasurementsSubtypes = findViewById(R.id.layoutBodyMeasurementsSubtypes)
        layoutCycleTrackingSubtypes = findViewById(R.id.layoutCycleTrackingSubtypes)
        layoutNutritionSubtypes = findViewById(R.id.layoutNutritionSubtypes)
        layoutSleepSubtypes = findViewById(R.id.layoutSleepSubtypes)
        layoutVitalsSubtypes = findViewById(R.id.layoutVitalsSubtypes)

        txtPermissionStatus = findViewById(R.id.txtPermissionStatus)
        btnGrantPermissions = findViewById(R.id.btnGrantPermissions)
        btnGrantHistory = findViewById(R.id.btnGrantHistory)
        txtHistoryHint = findViewById(R.id.txtHistoryHint)

        spinnerDateRange = findViewById(R.id.spinnerDateRange)
        spinnerCsvSplit = findViewById(R.id.spinnerCsvSplit)
        switchSaferExport = findViewById(R.id.switchSaferExport)

        btnExportCsv = findViewById(R.id.btnExportCsv)
        txtStatus = findViewById(R.id.txtStatus)
        progressBar = findViewById(R.id.progressBar)
        txtProgress = findViewById(R.id.txtProgress)
        defaultStatusColors = txtStatus.textColors
    }

    // -------------------------
    // Categories
    // -------------------------

    private class Category(
        val title: String,
        val header: CheckBox,
        val content: LinearLayout,
        val items: List<CheckBox>
    )

    private lateinit var categories: List<Category>
    private var syncingCategories = false

    /**
     * A checked header means "export this category" and needs at least one checked item:
     * unchecking the last item turns the category off (the list stays open, so checking an item
     * turns it back on), and turning on a category with nothing selected selects all its items.
     * Only the user's header toggle expands/collapses the list.
     */
    private fun setupCategories() {
        categories = listOf(
            Category(
                "Activity", checkCategoryActivity, layoutActivitySubtypes,
                listOf(
                    checkActiveCalories, checkDistance, checkElevationGained, checkExercise,
                    checkFloorsClimbed, checkPower, checkSpeed, checkSteps, checkTotalCalories,
                    checkVO2Max, checkWheelchairPushes
                )
            ),
            Category(
                "Body Measurements", checkCategoryBodyMeasurements, layoutBodyMeasurementsSubtypes,
                listOf(checkBodyFat, checkBoneMass, checkHeight, checkLeanBodyMass, checkWeight)
            ),
            Category(
                "Cycle Tracking", checkCategoryCycleTracking, layoutCycleTrackingSubtypes,
                listOf(checkCervicalMucus, checkMenstruation, checkOvulationTest, checkSexualActivity)
            ),
            Category(
                "Nutrition", checkCategoryNutrition, layoutNutritionSubtypes,
                listOf(checkHydration, checkNutrition)
            ),
            Category(
                "Sleep", checkCategorySleep, layoutSleepSubtypes,
                listOf(checkSleepSession)
            ),
            Category(
                "Vitals", checkCategoryVitals, layoutVitalsSubtypes,
                listOf(
                    checkBloodGlucose, checkBloodPressure, checkBodyTemperature, checkHeartRate,
                    checkHeartRateVariability, checkOxygenSaturation, checkRespiratoryRate,
                    checkRestingHeartRate
                )
            )
        )

        categories.forEach { c ->
            // Saved state may have a category on with nothing selected.
            if (c.header.isChecked && c.items.none { it.isChecked }) c.header.isChecked = false
            c.content.visibility = if (c.header.isChecked) View.VISIBLE else View.GONE
            renderCategoryTitle(c)

            c.header.setOnCheckedChangeListener { _, checked ->
                if (syncingCategories) return@setOnCheckedChangeListener
                if (checked && c.items.none { it.isChecked }) {
                    syncingCategories = true
                    c.items.forEach { it.isChecked = true }
                    syncingCategories = false
                }
                c.content.visibility = if (checked) View.VISIBLE else View.GONE
                renderCategoryTitle(c)
            }

            c.items.forEach { item ->
                item.setOnCheckedChangeListener { _, _ ->
                    if (syncingCategories) return@setOnCheckedChangeListener
                    val anySelected = c.items.any { it.isChecked }
                    if (c.header.isChecked != anySelected) {
                        syncingCategories = true
                        c.header.isChecked = anySelected
                        syncingCategories = false
                    }
                    renderCategoryTitle(c)
                }
            }
        }
    }

    private fun renderCategoryTitle(c: Category) {
        val selected = c.items.count { it.isChecked }
        c.header.text = if (c.header.isChecked) {
            "${c.title}  ·  $selected of ${c.items.size} selected"
        } else {
            "${c.title}  ·  not exported"
        }
    }

    private fun formatTime(ms: Long): String =
        SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(ms))

    private fun lastExportText(): String {
        val last = prefs.getLong("last_export_manual", 0L)
        return if (last > 0L) formatTime(last) else "never"
    }

    private fun loadPrefsIntoUi() {
        showStatus("Ready. Last export: ${lastExportText()}")

        val saferEnabled = prefs.getBoolean("safer_export_enabled", false)
        switchSaferExport.isChecked = saferEnabled
        healthConnectManager.setSaferExportMode(saferEnabled)

        // Categories
        checkCategoryActivity.isChecked = prefs.getBoolean("cat_activity", true)
        checkCategoryBodyMeasurements.isChecked = prefs.getBoolean("cat_body", true)
        checkCategoryCycleTracking.isChecked = prefs.getBoolean("cat_cycle", true)
        checkCategoryNutrition.isChecked = prefs.getBoolean("cat_nutrition", true)
        checkCategorySleep.isChecked = prefs.getBoolean("cat_sleep", true)
        checkCategoryVitals.isChecked = prefs.getBoolean("cat_vitals", true)

        // Activity
        checkActiveCalories.isChecked = prefs.getBoolean("includeActiveCalories", true)
        checkDistance.isChecked = prefs.getBoolean("includeDistance", true)
        checkElevationGained.isChecked = prefs.getBoolean("includeElevationGained", true)
        checkExercise.isChecked = prefs.getBoolean("includeExercise", true)
        checkFloorsClimbed.isChecked = prefs.getBoolean("includeFloorsClimbed", true)
        checkPower.isChecked = prefs.getBoolean("includePower", true)
        checkSpeed.isChecked = prefs.getBoolean("includeSpeed", true)
        checkSteps.isChecked = prefs.getBoolean("includeSteps", true)
        checkTotalCalories.isChecked = prefs.getBoolean("includeTotalCalories", true)
        checkVO2Max.isChecked = prefs.getBoolean("includeVO2Max", true)
        checkWheelchairPushes.isChecked = prefs.getBoolean("includeWheelchairPushes", true)

        // Body
        checkBodyFat.isChecked = prefs.getBoolean("includeBodyFat", true)
        checkBoneMass.isChecked = prefs.getBoolean("includeBoneMass", true)
        checkHeight.isChecked = prefs.getBoolean("includeHeight", true)
        checkLeanBodyMass.isChecked = prefs.getBoolean("includeLeanBodyMass", true)
        checkWeight.isChecked = prefs.getBoolean("includeWeight", true)

        // Cycle
        checkCervicalMucus.isChecked = prefs.getBoolean("includeCervicalMucus", true)
        checkMenstruation.isChecked = prefs.getBoolean("includeMenstruation", true)
        checkOvulationTest.isChecked = prefs.getBoolean("includeOvulationTest", true)
        checkSexualActivity.isChecked = prefs.getBoolean("includeSexualActivity", true)

        // Nutrition
        checkHydration.isChecked = prefs.getBoolean("includeHydration", true)
        checkNutrition.isChecked = prefs.getBoolean("includeNutrition", true)

        // Sleep
        checkSleepSession.isChecked = prefs.getBoolean("includeSleepSession", true)

        // Vitals
        checkBloodGlucose.isChecked = prefs.getBoolean("includeBloodGlucose", true)
        checkBloodPressure.isChecked = prefs.getBoolean("includeBloodPressure", true)
        checkBodyTemperature.isChecked = prefs.getBoolean("includeBodyTemperature", true)

        // High volume defaults off
        checkHeartRate.isChecked = prefs.getBoolean("includeHeartRate", false)
        checkHeartRateVariability.isChecked = prefs.getBoolean("includeHeartRateVariability", false)
        checkOxygenSaturation.isChecked = prefs.getBoolean("includeOxygenSaturation", false)
        checkRespiratoryRate.isChecked = prefs.getBoolean("includeRespiratoryRate", false)

        checkRestingHeartRate.isChecked = prefs.getBoolean("includeRestingHeartRate", true)
    }

    private fun applyAsterisks() {
        fun hv(label: String): Spanned =
            HtmlCompat.fromHtml("$label <font color='#D32F2F'>*</font>", HtmlCompat.FROM_HTML_MODE_LEGACY)

        checkHeartRate.text = hv("Heart rate (min/max/avg)")
        checkHeartRateVariability.text = hv("Heart rate variability (min/max/avg)")
        checkOxygenSaturation.text = hv("Oxygen saturation (min/max/avg)")
        checkRespiratoryRate.text = hv("Respiratory rate (min/max/avg)")
    }

    private fun setupListeners() {
        btnGrantPermissions.setOnClickListener { requestHealthPermissions() }
        btnGrantHistory.setOnClickListener { requestHistoryPermission() }

        switchSaferExport.setOnCheckedChangeListener { _, enabled ->
            prefs.edit().putBoolean("safer_export_enabled", enabled).apply()
            healthConnectManager.setSaferExportMode(enabled)
        }

        btnExportCsv.setOnClickListener {
            if (categories.none { it.header.isChecked }) {
                showStatus("Nothing to export: select at least one data type", StatusKind.WARNING)
                return@setOnClickListener
            }
            if (!exportInProgress.compareAndSet(false, true)) {
                showStatus("Export already in progress", StatusKind.WARNING)
                return@setOnClickListener
            }
            saveUiToPrefs()
            if (needsStoragePermission()) {
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                startCsvExport()
            }
        }
    }

    // -------------------------
    // Permissions
    // -------------------------

    private fun refreshPermissionStatus(requestIfNoneGranted: Boolean = false) {
        lifecycleScope.launch {
            val granted = try {
                healthConnectManager.getGrantedPermissions()
            } catch (e: Exception) {
                txtPermissionStatus.text = "Could not check Health Connect permissions: ${e.message}"
                return@launch
            }

            val total = healthConnectManager.permissions.size
            val grantedCount = healthConnectManager.permissions.count { it in granted }
            val history = HealthConnectManager.PERMISSION_READ_HEALTH_DATA_HISTORY in granted

            txtPermissionStatus.text =
                "Data types allowed: $grantedCount of $total\n" +
                        "Data older than 30 days: " + if (history) "allowed" else "not allowed"

            btnGrantPermissions.visibility = if (grantedCount < total) View.VISIBLE else View.GONE
            btnGrantHistory.visibility = if (history) View.GONE else View.VISIBLE
            txtHistoryHint.visibility = if (history) View.GONE else View.VISIBLE

            if (requestIfNoneGranted && grantedCount == 0) requestHealthPermissions()
        }
    }

    private fun requestHealthPermissions() {
        try {
            healthPermissionLauncher.launch(healthConnectManager.permissions)
        } catch (e: ActivityNotFoundException) {
            showStatus("Cannot open Health Connect permissions: ${e.message}", StatusKind.ERROR)
        }
    }

    // Requested on its own so an older Health Connect that doesn't know it can't break the main request.
    private fun requestHistoryPermission() {
        try {
            healthPermissionLauncher.launch(setOf(HealthConnectManager.PERMISSION_READ_HEALTH_DATA_HISTORY))
        } catch (e: ActivityNotFoundException) {
            showStatus("Cannot open Health Connect permissions: ${e.message}", StatusKind.ERROR)
        }
    }

    private fun needsStoragePermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED

    // -------------------------
    // Export
    // -------------------------

    private fun saveUiToPrefs() {
        val e = prefs.edit()

        e.putBoolean("cat_activity", checkCategoryActivity.isChecked)
        e.putBoolean("cat_body", checkCategoryBodyMeasurements.isChecked)
        e.putBoolean("cat_cycle", checkCategoryCycleTracking.isChecked)
        e.putBoolean("cat_nutrition", checkCategoryNutrition.isChecked)
        e.putBoolean("cat_sleep", checkCategorySleep.isChecked)
        e.putBoolean("cat_vitals", checkCategoryVitals.isChecked)

        e.putBoolean("includeActiveCalories", checkActiveCalories.isChecked)
        e.putBoolean("includeDistance", checkDistance.isChecked)
        e.putBoolean("includeElevationGained", checkElevationGained.isChecked)
        e.putBoolean("includeExercise", checkExercise.isChecked)
        e.putBoolean("includeFloorsClimbed", checkFloorsClimbed.isChecked)
        e.putBoolean("includePower", checkPower.isChecked)
        e.putBoolean("includeSpeed", checkSpeed.isChecked)
        e.putBoolean("includeSteps", checkSteps.isChecked)
        e.putBoolean("includeTotalCalories", checkTotalCalories.isChecked)
        e.putBoolean("includeVO2Max", checkVO2Max.isChecked)
        e.putBoolean("includeWheelchairPushes", checkWheelchairPushes.isChecked)

        e.putBoolean("includeBodyFat", checkBodyFat.isChecked)
        e.putBoolean("includeBoneMass", checkBoneMass.isChecked)
        e.putBoolean("includeHeight", checkHeight.isChecked)
        e.putBoolean("includeLeanBodyMass", checkLeanBodyMass.isChecked)
        e.putBoolean("includeWeight", checkWeight.isChecked)

        e.putBoolean("includeCervicalMucus", checkCervicalMucus.isChecked)
        e.putBoolean("includeMenstruation", checkMenstruation.isChecked)
        e.putBoolean("includeOvulationTest", checkOvulationTest.isChecked)
        e.putBoolean("includeSexualActivity", checkSexualActivity.isChecked)

        e.putBoolean("includeHydration", checkHydration.isChecked)
        e.putBoolean("includeNutrition", checkNutrition.isChecked)

        e.putBoolean("includeSleepSession", checkSleepSession.isChecked)

        e.putBoolean("includeBloodGlucose", checkBloodGlucose.isChecked)
        e.putBoolean("includeBloodPressure", checkBloodPressure.isChecked)
        e.putBoolean("includeBodyTemperature", checkBodyTemperature.isChecked)
        e.putBoolean("includeHeartRate", checkHeartRate.isChecked)
        e.putBoolean("includeHeartRateVariability", checkHeartRateVariability.isChecked)
        e.putBoolean("includeOxygenSaturation", checkOxygenSaturation.isChecked)
        e.putBoolean("includeRespiratoryRate", checkRespiratoryRate.isChecked)
        e.putBoolean("includeRestingHeartRate", checkRestingHeartRate.isChecked)

        e.apply()
    }

    private fun buildConfigFromUi(): ExportConfig {
        // An unchecked category exports nothing, whatever its items say (they are kept for when it's turned back on).
        val act = checkCategoryActivity.isChecked
        val body = checkCategoryBodyMeasurements.isChecked
        val cycle = checkCategoryCycleTracking.isChecked
        val nutr = checkCategoryNutrition.isChecked
        val sleep = checkCategorySleep.isChecked
        val vit = checkCategoryVitals.isChecked

        return ExportConfig(
            includeActiveCalories = act && checkActiveCalories.isChecked,
            includeDistance = act && checkDistance.isChecked,
            includeElevationGained = act && checkElevationGained.isChecked,
            includeExercise = act && checkExercise.isChecked,
            includeFloorsClimbed = act && checkFloorsClimbed.isChecked,
            includePower = act && checkPower.isChecked,
            includeSpeed = act && checkSpeed.isChecked,
            includeSteps = act && checkSteps.isChecked,
            includeTotalCalories = act && checkTotalCalories.isChecked,
            includeVO2Max = act && checkVO2Max.isChecked,
            includeWheelchairPushes = act && checkWheelchairPushes.isChecked,

            includeBodyFat = body && checkBodyFat.isChecked,
            includeBoneMass = body && checkBoneMass.isChecked,
            includeHeight = body && checkHeight.isChecked,
            includeLeanBodyMass = body && checkLeanBodyMass.isChecked,
            includeWeight = body && checkWeight.isChecked,

            includeCervicalMucus = cycle && checkCervicalMucus.isChecked,
            includeMenstruation = cycle && checkMenstruation.isChecked,
            includeOvulationTest = cycle && checkOvulationTest.isChecked,
            includeSexualActivity = cycle && checkSexualActivity.isChecked,

            includeHydration = nutr && checkHydration.isChecked,
            includeNutrition = nutr && checkNutrition.isChecked,

            includeSleepSession = sleep && checkSleepSession.isChecked,

            includeBloodGlucose = vit && checkBloodGlucose.isChecked,
            includeBloodPressure = vit && checkBloodPressure.isChecked,
            includeBodyTemperature = vit && checkBodyTemperature.isChecked,
            includeHeartRate = vit && checkHeartRate.isChecked,
            includeHeartRateVariability = vit && checkHeartRateVariability.isChecked,
            includeOxygenSaturation = vit && checkOxygenSaturation.isChecked,
            includeRespiratoryRate = vit && checkRespiratoryRate.isChecked,
            includeRestingHeartRate = vit && checkRestingHeartRate.isChecked,

            daysBack = when (spinnerDateRange.selectedItemPosition) {
                0 -> 7
                1 -> 30
                2 -> 90
                3 -> 365
                4 -> -1
                else -> 7
            }
        )
    }

    private fun startCsvExport() {
        if (isAllTimeSelected()) showAllTimeWarningDialog { exportToCsv() } else exportToCsv()
    }

    private fun exportToCsv() {
        showProgress(true)
        showStatus("Exporting… Keep the app open")
        // Health Connect refuses reads once the app is in the background.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        lifecycleScope.launch {
            try {
                val config = buildConfigFromUi()
                val result = healthConnectManager.readHealthDataByDay(config) { label, fraction ->
                    runOnUiThread { updateProgress("Reading: $label", fraction * READ_SHARE) }
                }
                val dailyData = result.data

                updateProgress("Writing CSV files", READ_SHARE)

                val splitMode = when (spinnerCsvSplit.selectedItemPosition) {
                    1 -> CsvExporter.SplitMode.BY_MONTH
                    2 -> CsvExporter.SplitMode.BY_YEAR
                    else -> CsvExporter.SplitMode.NONE
                }

                val res = withContext(Dispatchers.IO) {
                    CsvExporter(this@MainActivity).exportSelectedToDownloads(
                        dailyData = dailyData,
                        splitMode = if (isAllTimeSelected()) splitMode else CsvExporter.SplitMode.NONE,
                        includeActivity = dailyData.values.any { it.activityData.isNotEmpty() },
                        includeBody = dailyData.values.any { it.bodyMeasurementData.isNotEmpty() },
                        includeSleep = dailyData.values.any { it.sleepData.isNotEmpty() },
                        includeVitals = dailyData.values.any { it.vitalsData.isNotEmpty() },
                        includeNutrition = dailyData.values.any { it.nutritionData.isNotEmpty() },
                        includeCycle = dailyData.values.any { it.cycleTrackingData.isNotEmpty() }
                    )
                }

                val kind = if (result.warnings.isEmpty()) StatusKind.SUCCESS else StatusKind.WARNING
                if (res.files.isEmpty()) {
                    showStatus("No data to export for the selected types and period", StatusKind.WARNING)
                } else {
                    val now = System.currentTimeMillis()
                    prefs.edit().putLong("last_export_manual", now).apply()
                    val days = dailyData.keys
                    showStatus(
                        "Export finished ${formatTime(now)}\n\n" +
                                "Saved ${res.files.size} file(s) to ${res.location}:\n" +
                                res.files.joinToString("\n") { "• ${it.first}" } +
                                "\n\nData: ${days.size} day(s), ${days.first()} … ${days.last()}" +
                                if (result.warnings.isEmpty()) "" else "\n\n${result.warnings.size} warning(s), see the report.",
                        kind
                    )
                }

                showReportDialog(result)
            } catch (e: HealthConnectManager.ExportInterruptedException) {
                showStatus(
                    (e.message ?: "Export interrupted") + "\n\nLast successful export: ${lastExportText()}",
                    StatusKind.ERROR
                )
            } catch (e: Exception) {
                showStatus(
                    "Export failed: ${e.message ?: e.javaClass.simpleName}\n\nLast successful export: ${lastExportText()}",
                    StatusKind.ERROR
                )
            } finally {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                showProgress(false)
                exportInProgress.set(false)
                refreshPermissionStatus()
            }
        }
    }

    // -------------------------
    // Export report dialog
    // -------------------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun showReportDialog(result: HealthReadResult) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }

        fun section(title: String) {
            content.addView(TextView(this).apply {
                text = title
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(12), 0, dp(4))
            })
        }

        fun line(text: String, color: Int? = null, sizeSp: Float = 14f) {
            content.addView(TextView(this).apply {
                this.text = text
                textSize = sizeSp
                color?.let { setTextColor(it) }
                setPadding(0, dp(2), 0, dp(2))
            })
        }

        section("Period")
        line("${result.fromDay} … ${result.toDay}")
        line(
            "Data older than 30 days: " + if (result.historyGranted) "allowed" else "not allowed",
            if (result.historyGranted) null else COLOR_WARNING
        )

        if (result.warnings.isNotEmpty()) {
            section("Warnings")
            result.warnings.forEach { line("• $it", COLOR_WARNING) }
        }

        section("Received from Health Connect")
        val table = TableLayout(this)
        table.addView(reportRow(listOf("Data type", "Count", "First", "Last"), header = true, shaded = true))
        result.coverage.forEachIndexed { i, c ->
            val cells = if (c.count == 0) {
                listOf(c.label, "—", "—", "—")
            } else {
                val unit = if (c.unit == "days") "d" else "rec"
                listOf(c.label, "${c.count} $unit", c.first.orEmpty(), c.last.orEmpty())
            }
            table.addView(reportRow(cells, header = false, shaded = i % 2 == 1, empty = c.count == 0))
        }
        content.addView(HorizontalScrollView(this).apply { addView(table) })
        line("d = days with data, rec = records", sizeSp = 12f)

        AlertDialog.Builder(this)
            .setTitle(if (result.warnings.isEmpty()) "Export report" else "Export finished with warnings")
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("OK", null)
            .show()
    }

    private fun reportRow(cells: List<String>, header: Boolean, shaded: Boolean, empty: Boolean = false): TableRow {
        val row = TableRow(this)
        if (shaded) row.setBackgroundColor(if (header) COLOR_HEADER_BG else COLOR_STRIPE_BG)
        cells.forEachIndexed { col, text ->
            row.addView(TextView(this).apply {
                this.text = text
                textSize = 13f
                setPadding(dp(6), dp(4), dp(6), dp(4))
                if (header) setTypeface(typeface, Typeface.BOLD)
                if (col == 1) gravity = Gravity.END
                if (empty) alpha = 0.5f
            })
        }
        return row
    }

    private fun showAllTimeWarningDialog(onContinue: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("All time export")
            .setMessage(
                "Exporting all time data can take a long time.\n\n" +
                        "Keep the app open and the screen on until it finishes.\n\nContinue?"
            )
            .setPositiveButton("Continue") { _, _ -> onContinue() }
            .setNegativeButton("Cancel") { _, _ -> exportInProgress.set(false) }
            .setOnCancelListener { exportInProgress.set(false) }
            .show()
    }

    private fun showProgress(show: Boolean) {
        progressBar.visibility = if (show) View.VISIBLE else View.GONE
        txtProgress.visibility = if (show) View.VISIBLE else View.GONE
        if (show) updateProgress("Starting", 0f)
        btnExportCsv.isEnabled = !show
    }

    private fun updateProgress(step: String, fraction: Float) {
        val value = (fraction.coerceIn(0f, 1f) * progressBar.max).toInt()
        progressBar.progress = value
        txtProgress.text = "$step… ${value * 100 / progressBar.max}%"
    }

    private fun showStatus(message: String, kind: StatusKind = StatusKind.INFO) {
        txtStatus.text = "Status: $message"
        when (kind) {
            StatusKind.INFO -> defaultStatusColors?.let { txtStatus.setTextColor(it) }
            StatusKind.SUCCESS -> txtStatus.setTextColor(COLOR_SUCCESS)
            StatusKind.WARNING -> txtStatus.setTextColor(COLOR_WARNING)
            StatusKind.ERROR -> txtStatus.setTextColor(COLOR_ERROR)
        }
    }

    private companion object {
        // Share of the progress bar used by reading; the rest is writing CSV files.
        const val READ_SHARE = 0.95f

        val COLOR_SUCCESS = Color.parseColor("#388E3C")
        val COLOR_WARNING = Color.parseColor("#F57C00")
        val COLOR_ERROR = Color.parseColor("#D32F2F")

        // Translucent grey reads well on both light and dark dialog backgrounds.
        val COLOR_HEADER_BG = Color.parseColor("#33808080")
        val COLOR_STRIPE_BG = Color.parseColor("#14808080")
    }
}
