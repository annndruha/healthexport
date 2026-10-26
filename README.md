
# !!THIS IS FORK with fixes, original repo: https://github.com/teqxnology/healthexport

### Description
Export your Health Connect data to CSV files. Because it pulls data directly from Health Connect, it is compatible with most health and fitness apps (Strava, Garmin, Fitbit, Coros, Samsung, Polar, Withings, Zepp/Amazfit, etc.). Choose which health metrics to include and the date range (up to all time). Supports the following data types:

##### Activity
- Active calories burned
- Distance
- Elevation gained
- Exercise sessions
- Floors climbed
- Power (min/max/avg)
- Speed (min/max/avg)
- Steps
- Total calories burned
- VO2 max (min/max/avg)
- Wheelchair pushes

##### Body measurements
- Body fat %
- Bone mass
- Height
- Lean body mass
- Weight

##### Cycle tracking
- Cervical mucus
- Menstruation (flow and periods)
- Ovulation test
- Sexual activity

##### Nutrition
- Hydration
- Nutrition (energy, protein, carbohydrates, fat, saturated fat, sugar, fiber, sodium, caffeine)

##### Sleep
- Sleep sessions (light, deep, REM sleep, awake & total)

##### Vitals
- Blood glucose
- Blood pressure
- Body temperature
- Heart rate (min/max/avg)
- Heart rate variability (min/max/avg)
- Oxygen saturation (min/max/avg)
- Respiratory rate (min/max/avg)
- Resting heart rate (min/max/avg)

### App Information
Android 9+

Build and install from source with Android Studio.

---

## Authorize Health Connect (read permissions)

### 1) Install/Update Health Connect
- Make sure **Health Connect** is installed and set up on your phone (on Android 14+ it is part of the system Settings).
- On older Android versions Health Connect is available on the Play Store.

### 2) Grant permissions
1. In the app, tap **Grant Health Connect permissions**
2. Allow read access for the data you want to export

The **Health Connect access** card shows how many data types are allowed. You can change permissions later in Health Connect → App permissions → Health to CSV.

✅ Without permission, that data type is skipped (the export report lists it).

### 3) Allow access to data older than 30 days
By default Health Connect only returns data starting 30 days before the app was first given access. To export your full history:
1. Tap **Allow access to data older than 30 days**
2. Allow **Access past data** in the Health Connect dialog

> ⚠️ This permission requires a recent Health Connect version. If the button has no effect, update Health Connect (or Android) and try again.

---

## Export CSV

1. Select your data categories and metrics (an unchecked category is not exported)
2. Choose a date range (Last 7 / 30 / 90 / 365 days or **All time**)
3. For **All time**, optionally split the files by month or by year
4. Tap **Export CSV to Downloads**
5. Keep the app open until the export finishes — Health Connect does not allow reading data in the background. The screen stays on during the export.

Files are saved to the phone's **Download** folder, one file per category, named after the moment the export started:

```
2026-10-26_22-09_Activity.csv
2026-10-26_22-09_Body_Measurements.csv
2026-10-26_22-09_Cycle_Tracking.csv
2026-10-26_22-09_Nutrition.csv
2026-10-26_22-09_Sleep.csv
2026-10-26_22-09_Vitals.csv
```

With splitting enabled the period is appended, e.g. `2026-10-26_22-09_Vitals_2025-03.csv`.

Every file has one row per day (Body measurements: one row per measurement, Activity: one row per exercise session) with `Source(s)` (apps the data came from) and `Timezone` columns.

### Export report
After the export the app shows:
- the saved file names and folder,
- for each data type: how many days/records were received and their first and last date,
- warnings, e.g. data types without permission or periods Health Connect refused to return.

A failing period is skipped and reported; the rest of the data is still exported.

---

## High-volume data warnings (important)

Some metrics generate *a lot* of records and can:
- take much longer,
- drain battery,
- trigger Health Connect rate limits.

⚠️ **High-volume examples (marked with \* in the app):**
- Heart rate (min/max/avg)
- HRV (min/max/avg)
- Oxygen saturation (min/max/avg)
- Respiratory rate (min/max/avg)

**Recommendation:** For large date ranges (especially **All time**), enable these only if you really need them.

---

## Safer Export Mode (slower but more reliable)

The **Safer export mode** toggle reduces rate-limit errors by exporting more slowly.

When enabled, the app will:
- ⏳ read in **smaller time chunks**
- 🐢 add a **short delay between requests**
- 📄 use smaller page sizes
- 🔁 retry rate-limited requests more times, with longer pauses

✅ Use this if the export report says the Health Connect rate limit was hit.

> ⏱️ Expect exports to take longer—this is normal.

---

## Tips & Troubleshooting

### Old data is missing
- Check the **Health Connect access** card: "Data older than 30 days" must be **allowed** (see above).
- Check the export report: it shows the first date Health Connect returned for each data type. If it is old for some types but recent for others, the source app only wrote recent data to Health Connect.

### Rate limit errors
- Turn on **Safer export mode**
- Reduce date range
- Disable high-volume metrics
- Try again later (quota replenishes over time)

### "Export interrupted"
- The app was sent to the background during the export. Start it again and keep the app open until it finishes.
