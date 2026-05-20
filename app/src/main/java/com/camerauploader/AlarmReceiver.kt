package com.camerauploader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat.startForegroundService

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!SettingsManager.isRecordingEnabled(context)) {
            Log.d(TAG, "Recording disabled — stopping alarm chain")
            return  // do not reschedule; chain stops here
        }

        AlarmScheduler.scheduleNext(context)  // always reschedule before any early return

        if (!isWithinRecordingWindow(context)) {
            Log.d(TAG, "Outside daylight window — skipping capture")
            return
        }

        val serviceIntent = Intent(context, CameraUploaderService::class.java)
        startForegroundService(context, serviceIntent)
    }

    private fun isWithinRecordingWindow(context: Context): Boolean {
        if (!SettingsManager.isDaylightOnly(context)) return true
        if (!SettingsManager.hasLocation(context)) return true  // no location yet: allow capture
        val window = SettingsManager.getCachedDaylightWindow(context) ?: return true  // no cache: allow
        val now = System.currentTimeMillis()
        return now >= window.first && now < window.second
    }
}
