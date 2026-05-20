package com.camerauploader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat.startForegroundService
import java.time.LocalDate
import java.time.ZoneId

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!SettingsManager.isRecordingEnabled(context)) {
            Log.d(TAG, "Recording disabled — alarm chain stopped")
            return  // don't reschedule; chain stops here
        }

        // Always reschedule before any early return so the chain continues.
        AlarmScheduler.scheduleNext(context)

        if (!isWithinRecordingWindow(context)) {
            Log.d(TAG, "Outside daylight window — skipping capture")
            return
        }

        val serviceIntent = Intent(context, CameraUploaderService::class.java)
        startForegroundService(context, serviceIntent)
    }

    /**
     * Returns false only when daylight-only mode is active, location is known, and
     * the sun is currently below the horizon (adjusted for offset).
     * Polar day (compute returns null) always allows capture.
     */
    private fun isWithinRecordingWindow(context: Context): Boolean {
        if (!SettingsManager.isDayByDayMode(context)) return true
        if (!SettingsManager.isDaylightOnly(context)) return true
        if (!SettingsManager.hasLocation(context)) return true  // no location yet: allow

        val lat = SettingsManager.getLocationLat(context).toDouble()
        val lon = SettingsManager.getLocationLon(context).toDouble()
        val offsetMs = SettingsManager.getDaylightOffsetMinutes(context) * 60_000L
        val today = LocalDate.now(ZoneId.systemDefault())
        val (rise, set) = SunriseSunset.compute(today, lat, lon) ?: return true  // polar day: allow

        val now = System.currentTimeMillis()
        return now >= (rise - offsetMs) && now < (set + offsetMs)
    }
}
