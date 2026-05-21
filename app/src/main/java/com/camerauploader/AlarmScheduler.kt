package com.camerauploader

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.time.LocalDate
import java.time.ZoneId

object AlarmScheduler {

    private const val TAG = "AlarmScheduler"
    private const val REQUEST_CODE = 0

    private enum class ThresholdType { SUNRISE, SUNSET, DAY_CHANGE }
    private data class DaylightThreshold(val ms: Long, val type: ThresholdType)

    /**
     * Schedule (or reschedule) the next alarm.
     *
     * - Recording disabled: stops the alarm chain entirely.
     * - Daylight-only mode with known location: RTC_WAKEUP alarm targeting the
     *   next recording-window boundary; no intermediate alarms outside the window.
     * - All other cases: ELAPSED_REALTIME_WAKEUP interval alarm (original behaviour).
     */
    fun scheduleNext(context: Context) {
        if (!SettingsManager.isRecordingEnabled(context)) {
            Log.d(TAG, "Recording disabled — alarm chain stopped")
            return
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = buildPendingIntent(context)

        if (SettingsManager.isDayByDayMode(context) &&
            SettingsManager.isDaylightOnly(context) &&
            SettingsManager.hasLocation(context)
        ) {
            scheduleDaylightAlarm(context, alarmManager, pending)
        } else {
            scheduleIntervalAlarm(context, alarmManager, pending)
        }
    }

    // ── Daylight-aware scheduling ─────────────────────────────────────────────

    /**
     * Finds the next solar event strictly after now, reusing the value cached in
     * [SettingsManager] while it remains in the future.
     *
     * Event order per day: SUNRISE (window opens) → SUNSET (window closes) →
     * DAY_CHANGE (solar midnight = midpoint of sunset/next-sunrise).
     *
     * Polar dates (null from [SunriseSunset.compute]) are skipped. Returns null
     * only when 9 consecutive dates are polar, which signals an interval-mode fallback.
     */
    private fun resolveNextThreshold(
        context: Context,
        lat: Double,
        lon: Double,
        offsetMs: Long,
    ): DaylightThreshold? {
        val now = System.currentTimeMillis()
        val tz  = ZoneId.systemDefault()

        // Reuse cached threshold while it's still in the future.
        val cached = SettingsManager.getNextThreshold(context)
        if (cached != null && now < cached.first) {
            val type = runCatching { ThresholdType.valueOf(cached.second) }.getOrNull()
                       ?: ThresholdType.SUNRISE
            return DaylightThreshold(cached.first, type)
        }

        // Scan forward to find the first event strictly after now.
        var date = LocalDate.now(tz)
        for (i in 0 until 9) {
            val pair = SunriseSunset.compute(date, lat, lon)
            if (pair == null) { date = date.plusDays(1); continue }  // polar: skip

            val rise = pair.first - offsetMs
            val set  = pair.second + offsetMs
            // Solar midnight = midpoint between today's sunset and tomorrow's sunrise.
            val tomorrowRise = SunriseSunset.compute(date.plusDays(1), lat, lon)
                ?.first?.let { it - offsetMs }
                ?: date.plusDays(1).atStartOfDay(tz).toInstant().toEpochMilli()
            val dayChange = (set + tomorrowRise) / 2

            val threshold = listOf(
                DaylightThreshold(rise,      ThresholdType.SUNRISE),
                DaylightThreshold(set,       ThresholdType.SUNSET),
                DaylightThreshold(dayChange, ThresholdType.DAY_CHANGE),
            ).firstOrNull { it.ms > now }

            if (threshold != null) {
                SettingsManager.setNextThreshold(context, threshold.ms, threshold.type.name)
                return threshold
            }
            date = date.plusDays(1)
        }
        return null  // 9 consecutive polar dates → caller falls back to interval mode
    }

    private fun scheduleDaylightAlarm(
        context: Context,
        alarmManager: AlarmManager,
        pending: PendingIntent,
    ) {
        val intervalMs = SettingsManager.getIntervalSeconds(context).toLong()
            .coerceAtLeast(1L) * 1_000L
        val offsetMs = SettingsManager.getDaylightOffsetMinutes(context) * 60_000L
        val lat = SettingsManager.getLocationLat(context).toDouble()
        val lon = SettingsManager.getLocationLon(context).toDouble()
        val now = System.currentTimeMillis()

        val threshold = resolveNextThreshold(context, lat, lon, offsetMs)
            ?: return scheduleIntervalAlarm(context, alarmManager, pending)

        val triggerAt = when (threshold.type) {
            // Outside the recording window: jump directly to the next boundary event.
            ThresholdType.SUNRISE, ThresholdType.DAY_CHANGE -> threshold.ms
            // Inside the recording window (SUNSET is next): next interval, capped at sunset.
            ThresholdType.SUNSET ->
                ((now / intervalMs + 1) * intervalMs).coerceAtMost(threshold.ms)
        }

        setAlarm(alarmManager, AlarmManager.RTC_WAKEUP, triggerAt, pending, context)
        Log.d(TAG, "Daylight alarm at ${java.util.Date(triggerAt)} " +
                   "(next ${threshold.type} at ${java.util.Date(threshold.ms)})")
    }

    // ── Interval scheduling (original behaviour) ──────────────────────────────

    private fun scheduleIntervalAlarm(
        context: Context,
        alarmManager: AlarmManager,
        pending: PendingIntent,
    ) {
        val intervalMs = SettingsManager.getIntervalSeconds(context).toLong()
            .coerceAtLeast(1L) * 1_000L
        val triggerAt = (SystemClock.elapsedRealtime() / intervalMs + 1) * intervalMs
        setAlarm(alarmManager, AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending, context)
        Log.d(TAG, "Interval alarm in ${intervalMs / 1000}s")
    }

    // ── Shared alarm setter ───────────────────────────────────────────────────

    private fun setAlarm(
        alarmManager: AlarmManager,
        type: Int,
        triggerAt: Long,
        pending: PendingIntent,
        context: Context,
    ) {
        // Convert to wall-clock epoch ms for display (elapsed-realtime alarms need adjustment).
        val wallClock = if (type == AlarmManager.RTC_WAKEUP) triggerAt
                        else System.currentTimeMillis() + (triggerAt - SystemClock.elapsedRealtime())
        SettingsManager.setNextAlarmMs(context, wallClock)

        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(type, triggerAt, pending)
                } else {
                    alarmManager.setAndAllowWhileIdle(type, triggerAt, pending)
                    Log.w(TAG, "Exact alarm permission not granted — using inexact alarm")
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                alarmManager.setExactAndAllowWhileIdle(type, triggerAt, pending)
            else ->
                alarmManager.setExact(type, triggerAt, pending)
        }
    }

    /** Cancel any pending alarm (call when recording is disabled). */
    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(buildPendingIntent(context))
        SettingsManager.setNextAlarmMs(context, 0L)
        SettingsManager.clearNextThreshold(context)
        Log.d(TAG, "Alarm cancelled")
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
