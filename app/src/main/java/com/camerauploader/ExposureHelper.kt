package com.camerauploader

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Log

/**
 * Reads the back camera's AE/AWB capabilities straight from
 * [CameraCharacteristics]. These are synchronous metadata queries that do not
 * open the camera, so they are cheap enough to call on any thread.
 */
object ExposureHelper {

    private const val TAG = "ExposureHelper"

    // ── Shared: back-camera characteristics ──────────────────────────────────

    private fun getBackCameraChars(context: Context): CameraCharacteristics? {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
        return try {
            val backId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: return null
            cm.getCameraCharacteristics(backId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read camera characteristics", e)
            null
        }
    }

    // ── AE exposure compensation ──────────────────────────────────────────────

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
        val chars = getBackCameraChars(context) ?: return null
        return try {
            val range = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: return null
            val step  = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP) ?: return null
            if (range.lower == 0 && range.upper == 0) return null  // comp unsupported
            EvRange(range.lower, range.upper, step.toDouble())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read EV compensation range", e)
            null
        }
    }

    // ── AWB modes ─────────────────────────────────────────────────────────────

    /** A Camera2 AWB mode the device supports, paired with a display label. */
    data class AwbMode(val id: Int, val label: String)

    // Preferred display order: outdoor → mixed → indoor → off.
    private val AWB_ORDER = listOf(
        CameraMetadata.CONTROL_AWB_MODE_AUTO,
        CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
        CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
        CameraMetadata.CONTROL_AWB_MODE_SHADE,
        CameraMetadata.CONTROL_AWB_MODE_TWILIGHT,
        CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
        CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT,
        CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT,
        CameraMetadata.CONTROL_AWB_MODE_OFF,
    )

    private val AWB_LABELS = mapOf(
        CameraMetadata.CONTROL_AWB_MODE_AUTO           to "Auto",
        CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT        to "Daylight",
        CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to "Cloudy daylight",
        CameraMetadata.CONTROL_AWB_MODE_SHADE           to "Shade",
        CameraMetadata.CONTROL_AWB_MODE_TWILIGHT        to "Twilight",
        CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT    to "Incandescent",
        CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT     to "Fluorescent",
        CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT to "Warm fluorescent",
        CameraMetadata.CONTROL_AWB_MODE_OFF             to "Off (no WB correction)",
    )

    /**
     * Returns the AWB modes supported by the back camera, in a stable display
     * order (outdoor → indoor → off). Auto is always included first if the
     * query fails. Returns an empty list only when no back camera is found.
     */
    fun getAvailableAwbModes(context: Context): List<AwbMode> {
        val chars = getBackCameraChars(context) ?: return emptyList()
        return try {
            val supported = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
                ?.toSet() ?: setOf(CameraMetadata.CONTROL_AWB_MODE_AUTO)
            AWB_ORDER
                .filter { it in supported }
                .map { AwbMode(it, AWB_LABELS[it] ?: "Mode $it") }
                .ifEmpty { listOf(AwbMode(CameraMetadata.CONTROL_AWB_MODE_AUTO, "Auto")) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read AWB modes", e)
            listOf(AwbMode(CameraMetadata.CONTROL_AWB_MODE_AUTO, "Auto"))
        }
    }
}
