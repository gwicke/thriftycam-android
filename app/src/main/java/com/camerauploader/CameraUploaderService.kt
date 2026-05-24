package com.camerauploader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.text.format.DateFormat
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

class CameraUploaderService : Service(), LifecycleOwner {
    companion object {
        private const val TAG = "CameraUploaderService"
        private const val CHANNEL_ID = "camera_uploader_channel"
        private const val NOTIFICATION_ID = 1

        const val ACTION_CAPTURE             = "com.camerauploader.ACTION_CAPTURE"
        const val ACTION_SETTINGS_CHANGED   = "com.camerauploader.ACTION_SETTINGS_CHANGED"
        const val ACTION_PREVIEW_CAPTURE    = "com.camerauploader.ACTION_PREVIEW_CAPTURE"
        /** Lightweight action: push config.json to the server, nothing else. */
        const val ACTION_PUSH_REMOTE_CONFIG = "com.camerauploader.PUSH_REMOTE_CONFIG"
    }

    // ── Threads ───────────────────────────────────────────────────────────────
    private lateinit var workerThread: HandlerThread
    private lateinit var workerHandler: Handler

    // ── Camera ───────────────────────────────────────────────────────────────
    private var cameraProvider: ProcessCameraProvider? = null

    // Serializes capture cycles (scheduled + preview) that share the single
    // cameraProvider, so a preview can't yank the camera from an in-flight capture.
    @Volatile private var captureInProgress = false

    // ── Long-lived HTTP client ────────────────────────────────────────────────
    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // ── Capture-time FIFO ────────────────────────────────────────────────────
    // Passes the epoch-ms timestamp of each captured frame to the encoder reader
    // loop / upload thread so putImage uses the right value even when encoding is
    // slow.  Capacity = 2: if a third in-flight capture would be added, that
    // capture is aborted and the next alarm iteration retries.
    private val captureTimeFifo = LinkedBlockingDeque<Long>(2)

    // ── Executor: getPacket() loop (AV1) or HTTP POST (JPEG) ─────────────────
    private val uploadExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // ── Persistent AV1 encoder (null until first AV1 frame) ──────────────────
    @Volatile private var av1Encoder: Av1Encoder? = null
    private var isFirstAv1Frame = true

    // ── Day boundary tracking ─────────────────────────────────────────────────
    private var lastCaptureDate: String = ""         // written only in postWorker (main thread)
    @Volatile private var lastMkcolDate: String = "" // written from main + uploadExecutor threads

    // ── LifecycleOwner for CameraX ────────────────────────────────────────────
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    // ─────────────────────────────────────────────────────────────────────────
    // Service lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting capture…"))
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        setupCamera()
        workerThread = HandlerThread("CameraWorker").also { it.start() }
        workerHandler = Handler(workerThread.looper)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Flush encoder: sendEos runs after any in-flight sendFrame
        av1Encoder?.sendEos()
        // Reader loop exits on EOS; wait for it and any in-flight POST
        uploadExecutor.shutdown()
        try { uploadExecutor.awaitTermination(10, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        av1Encoder?.close()
        av1Encoder = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SETTINGS_CHANGED) {
            resetDayState()
            lastCaptureDate = ""
            // Config push is now triggered directly from collectAndSaveSettings()
            // in MainActivity via ACTION_PUSH_REMOTE_CONFIG, covering both the
            // "Save & Start" and "Preview" paths. Nothing extra needed here.
            // First capture is handled by the 5-second alarm scheduled by
            // AlarmScheduler.scheduleFirstCapture(); don't shoot immediately.
            return START_STICKY
        }
        if (intent?.action == ACTION_PUSH_REMOTE_CONFIG) {
            // Lightweight push: just PUT config.json, no alarm or state changes.
            if (SettingsManager.isRemoteConfigEnabled(this)) {
                uploadExecutor.execute { saveRemoteConfig() }
            }
            return START_STICKY
        }
        if (intent?.action == ACTION_PREVIEW_CAPTURE) {
            postWorker(previewMode = true)
            return START_NOT_STICKY  // never redeliver a preview after a kill
        }
        // Piggy-back a remote-config check on this wake-up. Runs on the single
        // upload thread before this cycle's image upload is queued, so any merged
        // setting takes effect for the upload that follows.
        if (shouldCheckRemoteConfig()) {
            uploadExecutor.execute { checkRemoteConfig() }
        }
        postWorker()
        return START_STICKY
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun postWorker(previewMode: Boolean = false) {
        if (captureInProgress) {
            // A capture cycle is already running. Reject a concurrent preview so it
            // doesn't unbind the camera mid-capture; let scheduled captures proceed
            // (they only overlap transiently and the new cycle rebinds cleanly).
            if (previewMode) {
                Log.w(TAG, "Preview requested while capture in progress — rejecting")
                deliverPreview(null)
                return
            }
        }
        captureInProgress = true

        // Day-state roll only applies to the recording path; a preview must never
        // mutate lastCaptureDate or reset the persistent AV1 encoder.
        if (!previewMode && SettingsManager.isDayByDayMode(this)) {
            val today = LocalDate.now(ZoneId.systemDefault()).toString()
            if (lastCaptureDate.isNotEmpty() && lastCaptureDate != today) {
                resetDayState()
            }
            lastCaptureDate = today
        }

        CameraCaptureManager(
            cameraProvider!!, lifecycleRegistry, this,
            this,
            this,
            { s -> this.updateNotification(s) },
            previewMode = previewMode,
            onCycleComplete = { captureInProgress = false },
        ).run()
    }

    /** Hand a freshly-captured preview JPEG (or null on failure) to the settings UI. */
    fun deliverPreview(jpeg: ByteArray?) {
        PreviewBus.deliver(jpeg)
    }

    /** Called at each day boundary: resets directory and encoder state. */
    private fun resetDayState() {
        lastMkcolDate = ""
        captureTimeFifo.clear()
        val old = av1Encoder
        av1Encoder = null
        isFirstAv1Frame = true
        old?.sendEos()  // reader loop drains remaining packets then closes via its local ref
    }

    private fun setupCamera() {
        cameraProvider = ProcessCameraProvider.getInstance(this).get()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AV1 encoder pipeline
    // ─────────────────────────────────────────────────────────────────────────

    /** Submit a captured I420 frame to the persistent AV1 encoder, opening it lazily. */
    fun submitAv1Frame(frame: Av1Streamer.Frame) {
        if (av1Encoder == null) {
            val enc = Av1Encoder.open(
                frame.width, frame.height,
                crf     = SettingsManager.getAv1Crf(this),
                encMode = SettingsManager.getAv1EncMode(this),
            )
            if (enc == null) {
                Log.e(TAG, "Failed to open AV1 encoder")
                updateNotification("AV1 encoder init failed")
                return
            }
            av1Encoder = enc
            isFirstAv1Frame = true
            startAv1ReaderLoop(enc)
        }
        val enc = av1Encoder ?: return
        if (!captureTimeFifo.offerLast(System.currentTimeMillis())) {
            Log.w(TAG, "Capture queue full (2 in-flight); skipping frame — encoder may be slow")
            updateNotification("Capture skipped — encoder busy")
            return
        }
        if (isFirstAv1Frame) {
            isFirstAv1Frame = false
            enc.sendFirstFrame(frame.buf, frame.yStride, frame.uvStride)
        } else {
            enc.sendFrame(frame.buf, frame.yStride, frame.uvStride)
        }
    }

    /**
     * Persistent blocking loop on [uploadExecutor]: drains OBU packets from the encoder
     * and PUTs each one individually. Uses the local [enc] reference rather than [av1Encoder]
     * so a day-boundary reset (which nulls [av1Encoder] and calls sendEos) is race-free.
     */
    private fun startAv1ReaderLoop(enc: Av1Encoder) {
        uploadExecutor.execute {
            val pkt = Av1Encoder.Packet()
            while (true) {
                enc.getPacket(pkt)
                when (pkt.status) {
                    Av1Encoder.Status.OK -> pkt.payload?.let { buf ->
                        val ct = captureTimeFifo.pollFirst() ?: System.currentTimeMillis()
                        putImage(buf, "video/AV1", if (pkt.isKey) "key.av1" else "av1", ct)
                    }
                    else -> {
                        enc.close()  // close via local ref (safe even after field was nulled)
                        if (av1Encoder === enc) av1Encoder = null
                        break
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Upload
    // ─────────────────────────────────────────────────────────────────────────

    /** PUT a JPEG to [uploadExecutor]. */
    fun uploadJpeg(jpeg: ByteArray) {
        val ct = System.currentTimeMillis()
        uploadExecutor.execute { putImage(jpeg, "image/jpeg", "jpg", ct) }
    }

    /**
     * PUT [bytes] to [baseUrl]/{date}/{timestamp}.[ext] (date segment only when daily-dir
     * mode is active). Sensor metadata travels in the [x-sensor-data] header as JSON so
     * the URL and body stay clean.
     */
    private fun putImage(bytes: ByteArray, mimeType: String, ext: String, captureTime: Long) {
        val baseUrl = SettingsManager.getUploadUrl(this)
        if (baseUrl.isBlank()) {
            updateNotification("No URL — tap icon to configure.")
            return
        }

        val timestamp = System.currentTimeMillis()
        val duration = timestamp - captureTime

        val dirUrl =
            if (SettingsManager.isDayByDayMode(this) && SettingsManager.isDailyDirMode(this)) {
                val date = LocalDate.now(ZoneId.systemDefault()).toString()  // "2026-05-19"
                ensureDayDirectory(baseUrl, date)
                "${baseUrl.trimEnd('/')}/$date"
            } else {
                baseUrl.trimEnd('/')
            }

        val bat = getBatteryLevel()
        val sensorData = buildString {
            append("{\"duration\":$duration")
            if (bat != null) append(",\"batlevel\":${"%.1f".format(bat)}")
            append("}")
        }

        updateNotification("Uploading ${bytes.size / 1024} KB…")
        val req = Request.Builder()
            .url("$dirUrl/$captureTime.$ext")
            .put(bytes.toRequestBody(mimeType.toMediaType()))
            .header("x-sensor-data", sensorData)
            .also {
                SettingsManager.getBasicAuthHeader(this)?.let { h -> it.header("Authorization", h) }
            }
            .build()
        var retries = 10
        var delay_ms = 100L
        while (retries > 0) {
            try {
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        Log.i(TAG, "Upload OK: ${resp.code}")
                        val nextMs = SettingsManager.getNextAlarmMs(this)
                        val nextStr = if (nextMs > 0) "  Next: ${
                            DateFormat.format(
                                "HH:mm:ss",
                                nextMs
                            )
                        }" else ""
                        updateNotification(
                            "Last: ${
                                DateFormat.format(
                                    "HH:mm:ss",
                                    timestamp
                                )
                            } ✓$nextStr"
                        )
                        retries = -1
                    } else {
                        Log.w(TAG, "Upload failed: HTTP ${resp.code}")
                        updateNotification("Upload failed (HTTP ${resp.code})")
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "putImage IOException", e)
                updateNotification("Upload error")
            }
            retries -= 1
            if (retries == 0) {
                // Restart the stream with a new keyframe, so we have no holes
                resetDayState()
            } else if (retries > 0) {
                Thread.sleep(delay_ms)
                delay_ms *= 2
            }
        }
    }

    /**
     * Issues a WebDAV MKCOL to create the day's directory on the server.
     * Skips the request if we already successfully created [date]'s directory
     * this session. Treats 405 (already exists) as success.
     */
    private fun ensureDayDirectory(baseUrl: String, date: String) {
        if (lastMkcolDate == date) return
        val dirUrl = "${baseUrl.trimEnd('/')}/$date/"
        val req = Request.Builder()
            .url(dirUrl)
            .method("MKCOL", null)
            .also { SettingsManager.getBasicAuthHeader(this)?.let { h -> it.header("Authorization", h) } }
            .build()
        try {
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful || resp.code == 405) {
                    lastMkcolDate = date
                    Log.d(TAG, "Day directory ready: $date (HTTP ${resp.code})")
                } else {
                    Log.w(TAG, "MKCOL $dirUrl → HTTP ${resp.code}")
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "MKCOL failed", e)
        }
    }

    private fun getBatteryLevel(): Float? {
        val intent = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let {
            applicationContext.registerReceiver(null, it)
        }
        return intent?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            level.toFloat() * 100f / scale.toFloat()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Remote config (config.json in the upload directory)
    // ─────────────────────────────────────────────────────────────────────────

    /** Whether this wake-up should fetch the remote config (honours the check interval). */
    private fun shouldCheckRemoteConfig(): Boolean {
        if (!SettingsManager.isRemoteConfigEnabled(this)) return false
        val checkHours = SettingsManager.getRemoteConfigCheckHours(this)
        if (checkHours <= 0f) return true  // every upload
        val sinceMs = System.currentTimeMillis() - SettingsManager.getRemoteConfigLastCheckMs(this)
        return sinceMs >= (checkHours * 3_600_000).toLong()
    }

    /** PUT the local (non-credential) config to config.json. Runs on [uploadExecutor]. */
    private fun saveRemoteConfig() {
        val baseUrl = SettingsManager.getUploadUrl(this)
        if (baseUrl.isBlank()) return
        val json = RemoteConfigManager.toJson(this)
        val req = Request.Builder()
            .url(RemoteConfigManager.configUrl(baseUrl))
            .put(json.toByteArray(Charsets.UTF_8).toRequestBody("application/json".toMediaType()))
            .also { SettingsManager.getBasicAuthHeader(this)?.let { h -> it.header("Authorization", h) } }
            .build()
        try {
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    // Force the next check to GET unconditionally and learn the real
                    // Last-Modified, since most servers omit it on a PUT response.
                    SettingsManager.clearRemoteConfigCache(this)
                    Log.d(TAG, "Remote config saved (HTTP ${resp.code})")
                } else {
                    Log.w(TAG, "Remote config PUT failed: HTTP ${resp.code}")
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "saveRemoteConfig failed", e)
        }
    }

    /**
     * Conditionally GET config.json. The If-Modified-Since header is only a
     * transport optimization; whether to apply the config is decided by the
     * config_version comparison inside [RemoteConfigManager.mergeFromJson].
     * Runs on [uploadExecutor].
     */
    private fun checkRemoteConfig() {
        val baseUrl = SettingsManager.getUploadUrl(this)
        if (baseUrl.isBlank()) return
        SettingsManager.setRemoteConfigLastCheckMs(this, System.currentTimeMillis())
        val lastMod = SettingsManager.getRemoteConfigLastModified(this)
        val req = Request.Builder()
            .url(RemoteConfigManager.configUrl(baseUrl))
            .also {
                SettingsManager.getBasicAuthHeader(this)?.let { h -> it.header("Authorization", h) }
                if (lastMod.isNotBlank()) it.header("If-Modified-Since", lastMod)
            }
            .build()
        try {
            httpClient.newCall(req).execute().use { resp ->
                when {
                    resp.code == 304 -> Log.d(TAG, "Remote config unchanged (304)")
                    !resp.isSuccessful -> Log.w(TAG, "Remote config GET failed: HTTP ${resp.code}")
                    else -> {
                        val body = resp.body?.string() ?: return
                        resp.header("Last-Modified")?.takeIf { it.isNotBlank() }?.let {
                            SettingsManager.setRemoteConfigLastModified(this, it)
                        }
                        applyMergedConfig(RemoteConfigManager.mergeFromJson(this, body))
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "checkRemoteConfig failed", e)
        }
    }

    /** React to a merged remote config: notify, and reschedule the alarm if timing changed. */
    private fun applyMergedConfig(changed: Set<String>) {
        if (changed.isEmpty()) return
        Log.i(TAG, "Remote config applied: $changed")
        updateNotification("Remote config updated (${changed.size} field${if (changed.size == 1) "" else "s"})")
        if ("interval_seconds" in changed || "recording_enabled" in changed) {
            if (SettingsManager.isRecordingEnabled(this)) {
                AlarmScheduler.scheduleNext(this)
            } else {
                AlarmScheduler.cancel(this)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Camera Uploader", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Background camera upload service" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Camera Uploader")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setSilent(true)
            .build()

    fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}
