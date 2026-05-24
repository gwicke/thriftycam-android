package com.camerauploader

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.time.Instant

/**
 * Serializes the app's settings (excluding credentials) to a JSON document that
 * lives at `config.json` in the upload directory, and merges an incoming remote
 * config back into local [SettingsManager] state.
 *
 * The authoritative version field is [KEY_VERSION] (`config_version`), an ISO-8601
 * UTC timestamp. A remote config is applied only when its `config_version` is
 * strictly newer than the local one; the HTTP `If-Modified-Since` exchange is a
 * pure transport optimization and never decides whether to merge.
 *
 * Credentials ([SettingsManager] username/password) are never written here and
 * never read from an incoming document.
 */
object RemoteConfigManager {

    private const val TAG = "RemoteConfigManager"
    const val CONFIG_FILENAME = "config.json"

    private const val KEY_VERSION = "config_version"

    fun configUrl(baseUrl: String) = "${baseUrl.trimEnd('/')}/$CONFIG_FILENAME"

    // ── Serialize ─────────────────────────────────────────────────────────────

    /** Serialize all non-credential settings to a pretty-printed JSON string. */
    fun toJson(context: Context): String {
        val s = SettingsManager
        return JSONObject().apply {
            put("upload_url",                s.getUploadUrl(context))
            put("interval_seconds",          s.getIntervalSeconds(context))
            put("resolution",                ResolutionHelper.serialize(s.getResolution(context)))
            put("upload_mode",               s.getUploadMode(context).name)
            put("recording_enabled",         s.isRecordingEnabled(context))
            put("day_by_day_mode",           s.isDayByDayMode(context))
            put("daily_dir_mode",            s.isDailyDirMode(context))
            put("daylight_only",             s.isDaylightOnly(context))
            put("daylight_offset_minutes",   s.getDaylightOffsetMinutes(context))
            val lat = s.getLocationLat(context)
            val lon = s.getLocationLon(context)
            put("location_lat", if (lat.isNaN()) JSONObject.NULL else lat.toDouble())
            put("location_lon", if (lon.isNaN()) JSONObject.NULL else lon.toDouble())
            put("af_enabled",                s.isAfEnabled(context))
            put("focus_distance",            s.getFocusDistance(context).toDouble())
            put("ev_compensation",           s.getEvCompensation(context))
            put("awb_mode",                  s.getAwbMode(context))
            put("zoom_ratio",                s.getZoomRatio(context).toDouble())
            val camId = s.getCameraId(context)
            put("camera_id", if (camId == null) JSONObject.NULL else camId)
            put("av1_crf",                   s.getAv1Crf(context))
            put("av1_enc_mode",              s.getAv1EncMode(context))
            put("remote_config_enabled",     s.isRemoteConfigEnabled(context))
            put("remote_config_check_hours", s.getRemoteConfigCheckHours(context).toDouble())
            put(KEY_VERSION,                 s.getConfigVersion(context))
        }.toString(2)  // 2-space indent — human-editable on the server
    }

    // ── Merge ─────────────────────────────────────────────────────────────────

    /**
     * Parse [json] and apply every present field to [SettingsManager].
     *
     * Only merges when the remote `config_version` is strictly newer than the
     * local one (both parsed as [Instant]; missing/unparseable local = epoch,
     * missing/unparseable remote = not-newer → never merge). After a successful
     * merge the local `config_version` is set to the remote value.
     *
     * Returns the set of keys whose values actually changed (empty = no-op).
     */
    fun mergeFromJson(context: Context, json: String): Set<String> {
        val s = SettingsManager
        return try {
            val obj = JSONObject(json)

            val remoteVersion = obj.optString(KEY_VERSION, "")
            if (parseVersion(remoteVersion) <= parseVersion(s.getConfigVersion(context))) {
                Log.d(TAG, "Remote config not newer (remote=$remoteVersion) — skipping merge")
                return emptySet()
            }

            val changed = mutableSetOf<String>()
            fun <T> sync(key: String, current: T, parsed: T, set: (T) -> Unit) {
                if (current != parsed) { set(parsed); changed += key }
            }

            if (obj.has("upload_url"))
                sync("upload_url", s.getUploadUrl(context), obj.getString("upload_url")) {
                    s.setUploadUrl(context, it)
                }
            if (obj.has("interval_seconds"))
                sync("interval_seconds", s.getIntervalSeconds(context),
                    obj.getInt("interval_seconds").coerceAtLeast(1)) {
                    s.setIntervalSeconds(context, it)
                }
            if (obj.has("resolution"))
                sync("resolution", ResolutionHelper.serialize(s.getResolution(context)),
                    obj.getString("resolution")) {
                    s.setResolution(context, ResolutionHelper.deserialize(it))
                }
            if (obj.has("upload_mode"))
                sync("upload_mode", s.getUploadMode(context).name, obj.getString("upload_mode")) {
                    s.setUploadMode(context, SettingsManager.UploadMode.fromString(it))
                }
            if (obj.has("recording_enabled"))
                sync("recording_enabled", s.isRecordingEnabled(context),
                    obj.getBoolean("recording_enabled")) {
                    s.setRecordingEnabled(context, it)
                }
            if (obj.has("day_by_day_mode"))
                sync("day_by_day_mode", s.isDayByDayMode(context),
                    obj.getBoolean("day_by_day_mode")) {
                    s.setDayByDayMode(context, it)
                }
            if (obj.has("daily_dir_mode"))
                sync("daily_dir_mode", s.isDailyDirMode(context),
                    obj.getBoolean("daily_dir_mode")) {
                    s.setDailyDirMode(context, it)
                }
            if (obj.has("daylight_only"))
                sync("daylight_only", s.isDaylightOnly(context),
                    obj.getBoolean("daylight_only")) {
                    s.setDaylightOnly(context, it)
                }
            if (obj.has("daylight_offset_minutes"))
                sync("daylight_offset_minutes", s.getDaylightOffsetMinutes(context),
                    obj.getInt("daylight_offset_minutes")) {
                    s.setDaylightOffsetMinutes(context, it)
                }
            if (obj.has("location_lat") && !obj.isNull("location_lat") &&
                obj.has("location_lon") && !obj.isNull("location_lon")) {
                val lat = obj.getDouble("location_lat").toFloat()
                val lon = obj.getDouble("location_lon").toFloat()
                if (s.getLocationLat(context) != lat || s.getLocationLon(context) != lon) {
                    s.setLocation(context, lat, lon); changed += "location"
                }
            }
            if (obj.has("af_enabled"))
                sync("af_enabled", s.isAfEnabled(context), obj.getBoolean("af_enabled")) {
                    s.setAfEnabled(context, it)
                }
            if (obj.has("focus_distance"))
                sync("focus_distance", s.getFocusDistance(context),
                    obj.getDouble("focus_distance").toFloat()) {
                    s.setFocusDistance(context, it)
                }
            if (obj.has("ev_compensation"))
                sync("ev_compensation", s.getEvCompensation(context),
                    obj.getInt("ev_compensation")) {
                    s.setEvCompensation(context, it)
                }
            if (obj.has("awb_mode"))
                sync("awb_mode", s.getAwbMode(context), obj.getInt("awb_mode")) {
                    s.setAwbMode(context, it)
                }
            if (obj.has("zoom_ratio"))
                sync("zoom_ratio", s.getZoomRatio(context),
                    obj.getDouble("zoom_ratio").toFloat()) {
                    s.setZoomRatio(context, it)
                }
            if (obj.has("camera_id"))
                sync("camera_id", s.getCameraId(context),
                    if (obj.isNull("camera_id")) null else obj.getString("camera_id")) {
                    s.setCameraId(context, it)
                }
            if (obj.has("av1_crf"))
                sync("av1_crf", s.getAv1Crf(context), obj.getInt("av1_crf")) {
                    s.setAv1Crf(context, it)
                }
            if (obj.has("av1_enc_mode"))
                sync("av1_enc_mode", s.getAv1EncMode(context), obj.getInt("av1_enc_mode")) {
                    s.setAv1EncMode(context, it)
                }
            if (obj.has("remote_config_enabled"))
                sync("remote_config_enabled", s.isRemoteConfigEnabled(context),
                    obj.getBoolean("remote_config_enabled")) {
                    s.setRemoteConfigEnabled(context, it)
                }
            if (obj.has("remote_config_check_hours"))
                sync("remote_config_check_hours", s.getRemoteConfigCheckHours(context),
                    obj.getDouble("remote_config_check_hours").toFloat()) {
                    s.setRemoteConfigCheckHours(context, it)
                }

            // Adopt the remote version so the timestamps stay in sync.
            s.setConfigVersion(context, remoteVersion)
            changed
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse/merge remote config", e)
            emptySet()
        }
    }

    /** Empty/missing/unparseable → [Instant.EPOCH] (oldest possible). */
    private fun parseVersion(iso: String): Instant =
        runCatching { Instant.parse(iso) }.getOrDefault(Instant.EPOCH)
}
