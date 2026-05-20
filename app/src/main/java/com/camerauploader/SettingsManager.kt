package com.camerauploader

import android.content.Context
import android.util.Base64
import android.util.Size
import androidx.core.content.edit

/**
 * Thin wrapper around SharedPreferences for app settings.
 * All components read settings from here — single source of truth.
 */
object SettingsManager {

    private const val PREFS_NAME = "camera_uploader_prefs"

    private const val KEY_UPLOAD_URL    = "upload_url"
    private const val KEY_INTERVAL_SECS = "interval_seconds"
    private const val KEY_AUTH_USERNAME  = "auth_username"
    private const val KEY_RESOLUTION     = "resolution"
    private const val KEY_AUTH_PASSWORD = "auth_password"
    private const val KEY_UPLOAD_MODE   = "upload_mode"

    private const val KEY_RECORDING_ENABLED     = "recording_enabled"
    private const val KEY_DAILY_DIR_MODE        = "daily_dir_mode"
    private const val KEY_DAILY_DIR_MKCOL       = "daily_dir_mkcol"
    private const val KEY_DAYLIGHT_ONLY         = "daylight_only"
    private const val KEY_DAYLIGHT_OFFSET_MIN   = "daylight_offset_minutes"
    private const val KEY_DAYLIGHT_WINDOW_START = "daylight_window_start_ms"
    private const val KEY_DAYLIGHT_WINDOW_END   = "daylight_window_end_ms"
    private const val KEY_AV1_CRF              = "av1_crf"
    private const val KEY_AV1_ENC_MODE         = "av1_enc_mode"
    private const val KEY_LOCATION_LAT         = "location_lat"
    private const val KEY_LOCATION_LON         = "location_lon"

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

    // ── Resolution ───────────────────────────────────────────────────────────────

    /**
     * Saved as "WxH" (e.g. "1920x1080"). Empty string means "let CameraX decide"
     * which will use the highest available resolution.
     */
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

    /**
     * Returns a Base64-encoded "Basic ..." header value if a username is set,
     * or null if Basic Auth is not configured.
     */
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

    // ── Day-by-day recording ──────────────────────────────────────────────────

    fun isDailyDirMode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DAILY_DIR_MODE, false)

    fun setDailyDirMode(context: Context, v: Boolean) =
        prefs(context).edit { putBoolean(KEY_DAILY_DIR_MODE, v) }

    fun isDailyDirMkcol(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DAILY_DIR_MKCOL, false)

    fun setDailyDirMkcol(context: Context, v: Boolean) =
        prefs(context).edit { putBoolean(KEY_DAILY_DIR_MKCOL, v) }

    // ── Daylight-hours recording ──────────────────────────────────────────────

    fun isDaylightOnly(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DAYLIGHT_ONLY, false)

    fun setDaylightOnly(context: Context, v: Boolean) =
        prefs(context).edit { putBoolean(KEY_DAYLIGHT_ONLY, v) }

    fun getDaylightOffsetMinutes(context: Context): Int =
        prefs(context).getInt(KEY_DAYLIGHT_OFFSET_MIN, 0)

    fun setDaylightOffsetMinutes(context: Context, minutes: Int) =
        prefs(context).edit { putInt(KEY_DAYLIGHT_OFFSET_MIN, minutes) }

    /** Cached sunrise/sunset window as epoch-ms boundaries. Null = not yet computed. */
    fun getCachedDaylightWindow(context: Context): Pair<Long, Long>? {
        val start = prefs(context).getLong(KEY_DAYLIGHT_WINDOW_START, -1L)
        val end   = prefs(context).getLong(KEY_DAYLIGHT_WINDOW_END,   -1L)
        return if (start >= 0L && end >= 0L) Pair(start, end) else null
    }

    fun setCachedDaylightWindow(context: Context, windowStart: Long, windowEnd: Long) =
        prefs(context).edit {
            putLong(KEY_DAYLIGHT_WINDOW_START, windowStart)
            putLong(KEY_DAYLIGHT_WINDOW_END,   windowEnd)
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
        }

    fun hasLocation(context: Context): Boolean =
        !getLocationLat(context).isNaN()

    // ── AV1 encoding parameters ───────────────────────────────────────────────

    fun getAv1Crf(context: Context): Int =
        prefs(context).getInt(KEY_AV1_CRF, 37)

    fun setAv1Crf(context: Context, crf: Int) =
        prefs(context).edit { putInt(KEY_AV1_CRF, crf.coerceIn(0, 63)) }

    fun getAv1EncMode(context: Context): Int =
        prefs(context).getInt(KEY_AV1_ENC_MODE, 10)

    fun setAv1EncMode(context: Context, mode: Int) =
        prefs(context).edit { putInt(KEY_AV1_ENC_MODE, mode.coerceIn(0, 10)) }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
