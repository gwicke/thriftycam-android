package com.camerauploader

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * Manages the AlarmManager alarm that triggers captures.
 *
 * Normal mode: ELAPSED_REALTIME_WAKEUP alarm on a rolling interval basis.
 * Daylight-only mode: RTC_WAKEUP alarms tied to the sunrise/sunset window.
 *   The window boundaries are computed once via [SunriseSunset] and cached in
 *   [SettingsManager] as epoch-ms timestamps; they are only recomputed when the
 *   cached window end has passed.
 */
object AlarmScheduler {

    private const val TAG = "AlarmScheduler"
    private const val REQUEST_CODE = 0

    fun scheduleNext(context: Context) {
        if (!SettingsManager.isRecordingEnabled(context)) {
            Log.d(TAG, "Recording disabled — not scheduling alarm")
            return
        }
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = buildPendingIntent(context)

        if (SettingsManager.isDaylightOnly(context) && SettingsManager.hasLocation(context)) {
            scheduleDaylightAlarm(context, alarmManager, pending)
        } else {
            scheduleIntervalAlarm(context, alarmManager, pending)
        }
    }

    /** Cancel any pending alarm (call when the user disables recording). */
    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(buildPendingIntent(context))
        Log.d(TAG, "Alarm cancelled")
    }

    /**
     * Resolves the active daylight recording window, using cached boundary timestamps.
     * Only recomputes when [System.currentTimeMillis] >= cached windowEnd.
     * Returns null only on polar day/night (sun never rises/sets).
     */
    private fun resolveDaylightWindow(context: Context): Pair<Long, Long>? {
        val lat = SettingsManager.getLocationLat(context).toDouble()
        val lon = SettingsManager.getLocationLon(context).toDouble()
        val offsetMs = SettingsManager.getDaylightOffsetMinutes(context) * 60_000L
        val now = System.currentTimeMillis()

        val cached = SettingsManager.getCachedDaylightWindow(context)
        if (cached != null && now < cached.second) return cached  // still valid

        fun windowFor(date: java.time.LocalDate): Pair<Long, Long>? {
            val (rise, set) = SunriseSunset.compute(date, lat, lon) ?: return null
            return Pair(rise - offsetMs, set + offsetMs)
        }

        val today = java.time.LocalDate.now()
        val todayWindow = windowFor(today)
        val window = when {
            todayWindow != null && now < todayWindow.second -> todayWindow
            else -> windowFor(today.plusDays(1))
        } ?: return null

        SettingsManager.setCachedDaylightWindow(context, window.first, window.second)
        Log.d(TAG, "Daylight window computed: ${java.util.Date(window.first)} – ${java.util.Date(window.second)}")
        return window
    }

    private fun scheduleDaylightAlarm(
        context: Context, alarmManager: AlarmManager, pending: PendingIntent
    ) {
        val window = resolveDaylightWindow(context)
            ?: return scheduleIntervalAlarm(context, alarmManager, pending)  // polar: fall back

        val intervalMs = SettingsManager.getIntervalSeconds(context).toLong().coerceAtLeast(1L) * 1_000L
        val now = System.currentTimeMillis()

        val triggerAt = when {
            now < window.first  -> window.first   // before dawn: wake at dawn
            now >= window.second -> window.first  // after dusk: window is already tomorrow's
            else -> ((now / intervalMs + 1) * intervalMs).coerceAtMost(window.second)
        }

        setAlarm(alarmManager, AlarmManager.RTC_WAKEUP, triggerAt, pending, context)
        Log.d(TAG, "Daylight alarm at ${java.util.Date(triggerAt)}")
    }

    private fun scheduleIntervalAlarm(
        context: Context, alarmManager: AlarmManager, pending: PendingIntent
    ) {
        val intervalMs = SettingsManager.getIntervalSeconds(context).toLong()
            .coerceAtLeast(1L) * 1_000L
        val triggerAt = (SystemClock.elapsedRealtime() / intervalMs + 1) * intervalMs
        setAlarm(alarmManager, AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending, context)
        Log.d(TAG, "Interval alarm in ${intervalMs / 1000}s")
    }

    private fun setAlarm(
        alarmManager: AlarmManager, type: Int, triggerAt: Long,
        pending: PendingIntent, context: Context
    ) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (alarmManager.canScheduleExactAlarms())
                    alarmManager.setExactAndAllowWhileIdle(type, triggerAt, pending)
                else
                    alarmManager.setAndAllowWhileIdle(type, triggerAt, pending)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                alarmManager.setExactAndAllowWhileIdle(type, triggerAt, pending)
            else ->
                alarmManager.setExact(type, triggerAt, pending)
        }
    }

    private fun buildPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else
            PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }
}
