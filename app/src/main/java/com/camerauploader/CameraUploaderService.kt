package com.camerauploader

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
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
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CameraUploaderService : Service(), LifecycleOwner {
    companion object {
        private const val TAG = "CameraUploaderService"
        private const val CHANNEL_ID = "camera_uploader_channel"
        private const val NOTIFICATION_ID = 1

        const val ACTION_CAPTURE = "com.camerauploader.ACTION_CAPTURE"
    }

    // ── Threads ───────────────────────────────────────────────────────────────
    private lateinit var workerThread: HandlerThread
    private lateinit var workerHandler: Handler

    // ── Camera ───────────────────────────────────────────────────────────────
    private var cameraProvider: ProcessCameraProvider? = null

    // ── Long-lived HTTP client ────────────────────────────────────────────────
    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    var captureTime = 0L

    // ── Executor: getPacket() loop (AV1) or HTTP POST (JPEG) ─────────────────
    private val uploadExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // ── Persistent AV1 encoder (null until first AV1 frame) ──────────────────
    @Volatile private var av1Encoder: Av1Encoder? = null
    private var isFirstAv1Frame = true

    // ── Day boundary tracking ─────────────────────────────────────────────────
    @Volatile private var lastCaptureDate: String = ""  // ISO date of last capture
    @Volatile private var lastMkcolDate: String = ""    // ISO date of last successful MKCOL

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
        if (SettingsManager.isDayByDayMode(this) && SettingsManager.isDaylightOnly(this))
            acquireLocationOnce()
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
        if (!isWithinRecordingWindow()) {
            Log.d(TAG, "Outside recording window — skipping capture")
            stopSelf()
            return START_NOT_STICKY
        }
        postWorker()
        return START_STICKY
    }

    private fun isWithinRecordingWindow(): Boolean {
        if (!SettingsManager.isDayByDayMode(this)) return true
        if (!SettingsManager.isDaylightOnly(this)) return true
        if (!SettingsManager.hasLocation(this)) return true
        val lat = SettingsManager.getLocationLat(this).toDouble()
        val lon = SettingsManager.getLocationLon(this).toDouble()
        val offsetMs = SettingsManager.getDaylightOffsetMinutes(this) * 60_000L
        val today = LocalDate.now(ZoneId.systemDefault())
        val (rise, set) = SunriseSunset.compute(today, lat, lon) ?: return true  // polar day
        val now = System.currentTimeMillis()
        return now >= (rise - offsetMs) && now < (set + offsetMs)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun postWorker() {
        captureTime = System.currentTimeMillis()
        CameraUploaderWorker(
            cameraProvider!!, lifecycleRegistry, this,
            this,
            this,
        ) { s -> this.updateNotification(s) }.run()
    }

    private fun setupCamera() {
        cameraProvider = ProcessCameraProvider.getInstance(this).get()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Location (one-time acquisition for daylight mode)
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun acquireLocationOnce() {
        if (SettingsManager.hasLocation(this)) return
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val providers = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        val loc = providers.firstNotNullOfOrNull { p ->
            runCatching { lm.getLastKnownLocation(p) }.getOrNull()
        }
        if (loc != null) {
            SettingsManager.setLocation(this, loc.latitude.toFloat(), loc.longitude.toFloat())
            Log.i(TAG, "Location cached: ${loc.latitude}, ${loc.longitude}")
        } else {
            Log.w(TAG, "Location not yet available — will retry on next start")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AV1 encoder pipeline
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Submit a captured I420 frame to the persistent AV1 encoder.
     * Opens the encoder lazily on the first call, and resets it at day boundaries
     * when day-by-day mode is active so each day starts with a fresh keyframe.
     */
    fun submitAv1Frame(frame: Av1Streamer.Frame) {
        val today = LocalDate.now(ZoneId.systemDefault()).toString()

        // Day boundary: detach current encoder so the reader loop drains and closes it,
        // then a fresh encoder (with a keyframe) is opened below.
        if (SettingsManager.isDayByDayMode(this)
            && lastCaptureDate.isNotEmpty()
            && lastCaptureDate != today
        ) {
            val old = av1Encoder
            av1Encoder = null      // reader loop will detect av1Encoder !== old and skip the field clear
            isFirstAv1Frame = true
            old?.sendEos()         // drains remaining packets, then reader loop closes 'old' via its local ref
        }
        lastCaptureDate = today

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
        captureTime = System.currentTimeMillis()
        val enc = av1Encoder ?: return
        if (isFirstAv1Frame) {
            isFirstAv1Frame = false
            enc.sendFirstFrame(frame.buf, frame.yStride, frame.uvStride)
        } else {
            enc.sendFrame(frame.buf, frame.yStride, frame.uvStride)
        }
    }

    /**
     * Persistent blocking loop on [uploadExecutor]: drains OBU packets from
     * the encoder and POSTs each one individually. Exits on EOS or error.
     * Uses the local [enc] reference rather than [av1Encoder] so that a
     * day-boundary reset (which nulls [av1Encoder] and calls sendEos) can
     * proceed safely without a race on the close call.
     */
    private fun startAv1ReaderLoop(enc: Av1Encoder) {
        uploadExecutor.execute {
            val pkt = Av1Encoder.Packet()
            while (true) {
                enc.getPacket(pkt)
                when (pkt.status) {
                    Av1Encoder.Status.OK -> pkt.payload?.let {
                        postImage(it, "video/AV1",
                            if (pkt.isKey) "key.av1" else "av1")
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

    /** Post a JPEG to [uploadExecutor]. */
    fun uploadJpeg(jpeg: ByteArray) {
        uploadExecutor.execute { postImage(jpeg, "image/jpeg", "jpg") }
    }

    /**
     * Shared multipart POST helper. Must be called from [uploadExecutor].
     * When day-by-day mode is enabled, appends the current ISO date to the
     * upload URL (e.g. https://example.com/upload/2026-05-19) and optionally
     * issues a WebDAV MKCOL to create that directory first.
     */
    private fun postImage(bytes: ByteArray, mimeType: String, ext: String) {
        val baseUrl = SettingsManager.getUploadUrl(this)
        if (baseUrl.isBlank()) {
            updateNotification("No URL — tap icon to configure.")
            return
        }

        val uploadUrl = if (SettingsManager.isDayByDayMode(this) && SettingsManager.isDailyDirMode(this)) {
            val date = LocalDate.now(ZoneId.systemDefault()).toString()  // "2026-05-19"
            ensureDayDirectory(baseUrl, date)
            "${baseUrl.trimEnd('/')}/$date"
        } else {
            baseUrl
        }

        updateNotification("Uploading ${bytes.size / 1024} KB…")
        val timestamp = System.currentTimeMillis()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "image", "$timestamp.$ext",
                bytes.toRequestBody(mimeType.toMediaType())
            )
            .addFormDataPart("timestamp", (timestamp - captureTime).toString())
            .addFormDataPart("batlevel", getBatteryLevel().toString())
            .build()
        val req = Request.Builder().url(uploadUrl).post(body)
        SettingsManager.getBasicAuthHeader(this)?.let { req.header("Authorization", it) }
        try {
            httpClient.newCall(req.build()).execute().use { resp ->
                if (resp.isSuccessful) {
                    Log.i(TAG, "Upload OK: ${resp.code}")
                    updateNotification(
                        "Last upload: ${DateFormat.format("HH:mm:ss", timestamp)} ✓"
                    )
                } else {
                    Log.w(TAG, "Upload failed: HTTP ${resp.code}")
                    updateNotification("Upload failed (HTTP ${resp.code})")
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "postImage IOException", e)
            updateNotification("Upload error")
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
