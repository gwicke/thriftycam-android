package com.camerauploader

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log

/**
 * Reads the back camera's AE exposure-compensation capabilities straight from
 * [CameraCharacteristics]. This is a synchronous metadata query that does not
 * open the camera, so it is cheap enough to call on any thread.
 */
object ExposureHelper {

    private const val TAG = "ExposureHelper"

    /**
     * @param min     lowest selectable compensation value, in device steps
     * @param max     highest selectable compensation value, in device steps
     * @param stepEv  EV change per step (e.g. 1/6 or 1/3)
     */
    data class EvRange(val min: Int, val max: Int, val stepEv: Double)

    /**
     * Returns the back camera's EV-compensation range, or null when the device
     * does not support exposure compensation (range is [0, 0]) or no back
     * camera is available.
     */
    fun getEvRange(context: Context): EvRange? {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
        return try {
            val backId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: return null
            val chars = cm.getCameraCharacteristics(backId)
            val range = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: return null
            val step  = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP) ?: return null
            if (range.lower == 0 && range.upper == 0) return null  // comp unsupported
            EvRange(range.lower, range.upper, step.toDouble())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read EV compensation range", e)
            null
        }
    }
}
