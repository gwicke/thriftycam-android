package com.camerauploader

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.time.ZoneId

object AlarmScheduler {

    private const val TAG = "AlarmScheduler"
    private const val REQUEST_CODE = 0
    private const val FIRST_CAPTURE_DELAY_MS = 5_000L

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
     * Caching wrapper around [findNextThreshold]. Reuses the threshold stored in
     * [SettingsManager] while it remains in the future, then recomputes and persists.
     */
    private fun resolveNextThreshold(
        context: Context,
        lat: Double,
        lon: Double,
        offsetMs: Long,
    ): DaylightThreshold? {
        val now = System.currentTimeMillis()

        // Reuse cached threshold while it's still in the future.
        val cached = SettingsManager.getNextThreshold(context)
        if (cached != null && now < cached.first) {
            val type = runCatching { ThresholdType.valueOf(cached.second) }.getOrNull()
                       ?: ThresholdType.SUNRISE
            return DaylightThreshold(cached.first, type)
        }

        val threshold = findNextThreshold(now, lat, lon, offsetMs, ZoneId.systemDefault())
            ?: return null
        SettingsManager.setNextThreshold(context, threshold.ms, threshold.type.name)
        return threshold
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
            // Outside the recording window: jump directly to when recording opens.
            ThresholdType.SUNRISE -> threshold.ms
            // Inside the recording window (or at a day-boundary within it): next
            // interval capture, capped at the boundary so we don't overshoot.
            ThresholdType.SUNSET, ThresholdType.DAY_CHANGE ->
                ((now / intervalMs + 1) * intervalMs).coerceAtMost(threshold.ms)
        }

        setAlarm(alarmManager, AlarmManager.RTC_WAKEUP, triggerAt, pending, context)
        Log.d(TAG, "Daylight alarm at ${java.util.Date(triggerAt)} " +
                   "(next ${threshold.type} at ${java.util.Date(threshold.ms)})")
    }

    // ── First-capture: fixed 5-second delay from UI start ────────────────────

    /**
     * Schedule the very first capture [FIRST_CAPTURE_DELAY_MS] after the user
     * presses "Save & Start". This gives time to position the camera before the
     * first shot. The alarm fires [AlarmReceiver], which then calls [scheduleNext]
     * to resume normal interval scheduling.
     */
    fun scheduleFirstCapture(context: Context) {
        if (!SettingsManager.isRecordingEnabled(context)) {
            Log.d(TAG, "Recording disabled — not scheduling first capture")
            return
        }
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = buildPendingIntent(context)
        val triggerAt = SystemClock.elapsedRealtime() + FIRST_CAPTURE_DELAY_MS
        setAlarm(alarmManager, AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending, context)
        Log.d(TAG, "First capture in ${FIRST_CAPTURE_DELAY_MS / 1000}s")
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
