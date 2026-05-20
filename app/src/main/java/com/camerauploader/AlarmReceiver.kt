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
            Log.d(TAG, "Recording disabled — alarm chain stopped")
            return  // don't reschedule; chain stops here
        }

        // AlarmScheduler already ensured this alarm fires at the right time;
        // reschedule the next one and start the capture service unconditionally.
        AlarmScheduler.scheduleNext(context)

        val serviceIntent = Intent(context, CameraUploaderService::class.java)
        startForegroundService(context, serviceIntent)
    }
}
