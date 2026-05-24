package com.camerauploader

import android.content.Context
import android.util.Base64
import android.util.Size
import androidx.core.content.edit

object SettingsManager {

    private const val PREFS_NAME = "camera_uploader_prefs"

    private const val KEY_UPLOAD_URL          = "upload_url"
    private const val KEY_INTERVAL_SECS       = "interval_seconds"
    private const val KEY_AUTH_USERNAME       = "auth_username"
    private const val KEY_RESOLUTION          = "resolution"
    private const val KEY_AUTH_PASSWORD       = "auth_password"
    private const val KEY_UPLOAD_MODE         = "upload_mode"
    private const val KEY_RECORDING_ENABLED   = "recording_enabled"
    private const val KEY_DAY_BY_DAY_MODE     = "day_by_day_mode"
    private const val KEY_DAILY_DIR_MODE      = "daily_dir_mode"   // sub: date-prefix + MKCOL
    private const val KEY_DAYLIGHT_ONLY       = "daylight_only"    // sub: daylight window
    private const val KEY_DAYLIGHT_OFFSET_MIN = "daylight_offset_minutes"
    private const val KEY_AV1_CRF             = "av1_crf"
    private const val KEY_AV1_ENC_MODE        = "av1_enc_mode"
    private const val KEY_LOCATION_LAT        = "location_lat"
    private const val KEY_LOCATION_LON        = "location_lon"
    private const val KEY_NEXT_ALARM_MS         = "next_alarm_ms"
    private const val KEY_NEXT_THRESHOLD_MS     = "next_threshold_ms"
    private const val KEY_NEXT_THRESHOLD_TYPE   = "next_threshold_type"

    const val DEFAULT_INTERVAL_SECONDS = 300

    /** Upload modes selectable from the settings dialog. */
    enum class UploadMode {
        /** One JPEG per capture, multipart/form-data POST per image. */
        JPEG,
        /** One AV1 OBU chunk per capture, multipart/form-data POST per image. */
        AV1;

        companion object {
            fun fromString(s: String?): UploadMode =
                entries.firstOrNull { it.name == s } ?: JPEG
        }
    }

    fun getUploadMode(context: Context): UploadMode =
        UploadMode.fromString(prefs(context).getString(KEY_UPLOAD_MODE, null))

    fun setUploadMode(context: Context, mode: UploadMode) =
        prefs(context).edit { putString(KEY_UPLOAD_MODE, mode.name) }

    // ── Upload URL ────────────────────────────────────────────────────────────

    fun getUploadUrl(context: Context): String =
        prefs(context).getString(KEY_UPLOAD_URL, "") ?: ""

    fun setUploadUrl(context: Context, url: String) =
        prefs(context).edit { putString(KEY_UPLOAD_URL, url.trim()) }

    fun isConfigured(context: Context): Boolean =
        getUploadUrl(context).isNotBlank()

    // ── Capture interval ──────────────────────────────────────────────────────

    fun getIntervalSeconds(context: Context): Int =
        prefs(context).getInt(KEY_INTERVAL_SECS, DEFAULT_INTERVAL_SECONDS)

    fun setIntervalSeconds(context: Context, seconds: Int) =
        prefs(context).edit { putInt(KEY_INTERVAL_SECS, seconds.coerceAtLeast(1)) }

    // ── Resolution ────────────────────────────────────────────────────────────

    fun getResolution(context: Context): Size {
        val raw = prefs(context).getString(KEY_RESOLUTION, "") ?: ""
        return if (raw.isBlank()) ResolutionHelper.default() else ResolutionHelper.deserialize(raw)
    }

    fun setResolution(context: Context, size: android.util.Size?) =
        prefs(context).edit {
            putString(KEY_RESOLUTION, if (size == null) "" else ResolutionHelper.serialize(size))
        }

    // ── Basic Auth ────────────────────────────────────────────────────────────

    fun getAuthUsername(context: Context): String =
        prefs(context).getString(KEY_AUTH_USERNAME, "") ?: ""

    fun getAuthPassword(context: Context): String =
        prefs(context).getString(KEY_AUTH_PASSWORD, "") ?: ""

    fun setAuthCredentials(context: Context, username: String, password: String) =
        prefs(context).edit {
            putString(KEY_AUTH_USERNAME, username)
            putString(KEY_AUTH_PASSWORD, password)
        }

    fun getBasicAuthHeader(context: Context): String? {
        val user = getAuthUsername(context)
        val pass = getAuthPassword(context)
        if (user.isBlank()) return null
        val encoded = Base64.encodeToString(
            "$user:$pass".toByteArray(Charsets.UTF_8), Base64.NO_WRAP
        )
        return "Basic $encoded"
    }

    // ── Recording enabled ─────────────────────────────────────────────────────

    fun isRecordingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RECORDING_ENABLED, true)

    fun setRecordingEnabled(context: Context, v: Boolean) =
        prefs(context).edit { putBoolean(KEY_RECORDING_ENABLED, v) }

    // ── Day-by-day mode ───────────────────────────────────────────────────────

    /** Top-level toggle: resets encoder + directory tracking at each day boundary. */
    fun isDayByDayMode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DAY_BY_DAY_MODE, false)

    fun setDayByDayMode(context: Context, v: Boolean) =
        prefs(context).edit { putBoolean(KEY_DAY_BY_DAY_MODE, v) }

    /**
     * Sub-option of day-by-day: prefix the upload path with the ISO date and
     * issue a WebDAV MKCOL to create the directory on first use each day.
     */
    fun isDailyDirMode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DAILY_DIR_MODE, false)

    fun setDailyDirMode(context: Context, v: Boolean) =
        prefs(context).edit { putBoolean(KEY_DAILY_DIR_MODE, v) }

    // ── Daylight-hours recording (sub-option of day-by-day) ───────────────────

    fun isDaylightOnly(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DAYLIGHT_ONLY, false)

    fun setDaylightOnly(context: Context, v: Boolean) =
        prefs(context).edit { putBoolean(KEY_DAYLIGHT_ONLY, v) }

    fun getDaylightOffsetMinutes(context: Context): Int =
        prefs(context).getInt(KEY_DAYLIGHT_OFFSET_MIN, 0)

    fun setDaylightOffsetMinutes(context: Context, minutes: Int) =
        prefs(context).edit {
            putInt(KEY_DAYLIGHT_OFFSET_MIN, minutes)
            putLong(KEY_NEXT_THRESHOLD_MS, -1L)
        }

    // ── Location (cached once for sunrise/sunset) ─────────────────────────────

    fun getLocationLat(context: Context): Float =
        prefs(context).getFloat(KEY_LOCATION_LAT, Float.NaN)

    fun getLocationLon(context: Context): Float =
        prefs(context).getFloat(KEY_LOCATION_LON, Float.NaN)

    fun setLocation(context: Context, lat: Float, lon: Float) =
        prefs(context).edit {
            putFloat(KEY_LOCATION_LAT, lat)
            putFloat(KEY_LOCATION_LON, lon)
            putLong(KEY_NEXT_THRESHOLD_MS, -1L)
        }

    fun hasLocation(context: Context): Boolean =
        !getLocationLat(context).isNaN()

    // ── Camera focus ──────────────────────────────────────────────────────────

    private const val KEY_AF_ENABLED     = "af_enabled"
    private const val KEY_FOCUS_DISTANCE = "focus_distance"  // Camera2 diopters; 0.0 = infinity

    fun isAfEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AF_ENABLED, true)

    fun setAfEnabled(context: Context, v: Boolean) =
        prefs(context).edit { putBoolean(KEY_AF_ENABLED, v) }

    /** Focus distance in Camera2 diopters (m⁻¹): 0.0 = infinity, higher = closer. */
    fun getFocusDistance(context: Context): Float =
        prefs(context).getFloat(KEY_FOCUS_DISTANCE, 0.0f)

    fun setFocusDistance(context: Context, distance: Float) =
        prefs(context).edit { putFloat(KEY_FOCUS_DISTANCE, distance.coerceAtLeast(0.0f)) }

    // ── Exposure compensation ──────────────────────────────────────────────────

    private const val KEY_EV_COMPENSATION = "ev_compensation"  // Camera2 AE comp steps; 0 = none

    /** AE exposure-compensation value in device steps (0 = no compensation). */
    fun getEvCompensation(context: Context): Int =
        prefs(context).getInt(KEY_EV_COMPENSATION, 0)

    fun setEvCompensation(context: Context, steps: Int) =
        prefs(context).edit { putInt(KEY_EV_COMPENSATION, steps) }

    // ── White balance (AWB) mode ───────────────────────────────────────────────

    private const val KEY_AWB_MODE = "awb_mode"  // Camera2 CONTROL_AWB_MODE_* constant; 1 = AUTO

    /** Camera2 AWB mode constant (CONTROL_AWB_MODE_*). Default 1 = AUTO. */
    fun getAwbMode(context: Context): Int =
        prefs(context).getInt(KEY_AWB_MODE, 1)  // 1 = CONTROL_AWB_MODE_AUTO

    fun setAwbMode(context: Context, mode: Int) =
        prefs(context).edit { putInt(KEY_AWB_MODE, mode) }

    // ── Camera selector ───────────────────────────────────────────────────────

    private const val KEY_CAMERA_ID = "camera_id"   // null = DEFAULT_BACK_CAMERA

    /**
     * Returns the stored camera ID to open, or null when the user has not chosen
     * a specific camera (the default back-facing camera is used in that case).
     */
    fun getCameraId(context: Context): String? =
        prefs(context).getString(KEY_CAMERA_ID, null)

    fun setCameraId(context: Context, id: String?) =
        prefs(context).edit {
            if (id == null) remove(KEY_CAMERA_ID) else putString(KEY_CAMERA_ID, id)
        }

    // ── AV1 encoding parameters ───────────────────────────────────────────────

    fun getAv1Crf(context: Context): Int =
        prefs(context).getInt(KEY_AV1_CRF, 37)

    fun setAv1Crf(context: Context, crf: Int) =
        prefs(context).edit { putInt(KEY_AV1_CRF, crf.coerceIn(0, 63)) }

    fun getAv1EncMode(context: Context): Int =
        prefs(context).getInt(KEY_AV1_ENC_MODE, 10)

    fun setAv1EncMode(context: Context, mode: Int) =
        prefs(context).edit { putInt(KEY_AV1_ENC_MODE, mode.coerceIn(0, 10)) }

    // ── Next scheduled alarm (wall-clock epoch ms) ────────────────────────────

    fun getNextAlarmMs(context: Context): Long =
        prefs(context).getLong(KEY_NEXT_ALARM_MS, 0L)

    fun setNextAlarmMs(context: Context, epochMs: Long) =
        prefs(context).edit { putLong(KEY_NEXT_ALARM_MS, epochMs) }

    // ── Daylight threshold cache ──────────────────────────────────────────────
    // Stores the next SUNRISE / SUNSET / DAY_CHANGE event time so AlarmScheduler
    // can reuse it across alarm firings without recomputing solar times.

    fun getNextThreshold(context: Context): Pair<Long, String>? {
        val ms   = prefs(context).getLong(KEY_NEXT_THRESHOLD_MS, -1L)
        if (ms < 0) return null
        val type = prefs(context).getString(KEY_NEXT_THRESHOLD_TYPE, "") ?: ""
        return if (type.isBlank()) null else Pair(ms, type)
    }

    fun setNextThreshold(context: Context, ms: Long, type: String) =
        prefs(context).edit {
            putLong(KEY_NEXT_THRESHOLD_MS, ms)
            putString(KEY_NEXT_THRESHOLD_TYPE, type)
        }

    fun clearNextThreshold(context: Context) =
        prefs(context).edit { putLong(KEY_NEXT_THRESHOLD_MS, -1L) }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
