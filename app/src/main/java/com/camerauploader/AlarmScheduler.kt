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
        val tz  = ZoneId.systemDefault()

        fun windowFor(date: LocalDate): Pair<Long, Long>? {
            val (rise, set) = SunriseSunset.compute(date, lat, lon) ?: return null
            return Pair(rise - offsetMs, set + offsetMs)
        }

        // Returns the start of the next day that has a sunrise, or falls back to
        // tomorrow's local midnight (handles multi-day polar-night runs).
        fun nextWindowStart(from: LocalDate): Long {
            for (i in 1..7) {
                val w = windowFor(from.plusDays(i.toLong()))
                if (w != null) return w.first
            }
            return from.plusDays(1).atStartOfDay(tz).toInstant().toEpochMilli()
        }

        val today  = LocalDate.now(tz)
        val window = windowFor(today)

        if (window == null) {
            // Polar day: record all 24 h at the normal interval.
            return scheduleIntervalAlarm(context, alarmManager, pending)
        }

        val triggerAt: Long = when {
            // Before dawn: jump straight to window start — no intermediate alarms.
            now < window.first -> window.first
            // After dusk: jump to next day's window start — skip the night entirely.
            now >= window.second -> nextWindowStart(today)
            // Within window: next interval capture, capped at window end.
            else -> ((now / intervalMs + 1) * intervalMs).coerceAtMost(window.second)
        }

        setAlarm(alarmManager, AlarmManager.RTC_WAKEUP, triggerAt, pending, context)
        Log.d(TAG, "Daylight alarm at ${java.util.Date(triggerAt)}")
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
