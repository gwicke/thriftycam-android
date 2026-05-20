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
        postWorker()
        return START_STICKY
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun postWorker() {
        captureTime = System.currentTimeMillis()

        if (SettingsManager.isDayByDayMode(this)) {
            val today = LocalDate.now(ZoneId.systemDefault()).toString()
            if (lastCaptureDate.isNotEmpty() && lastCaptureDate != today) {
                resetDayState()
            }
            lastCaptureDate = today
        }

        CameraUploaderWorker(
            cameraProvider!!, lifecycleRegistry, this,
            this,
            this,
        ) { s -> this.updateNotification(s) }.run()
    }

    /** Called at each day boundary: resets directory and encoder state. */
    private fun resetDayState() {
        lastMkcolDate = ""
        val old = av1Encoder
        av1Encoder = null
        isFirstAv1Frame = true
        old?.sendEos()  // reader loop drains remaining packets then closes via its local ref
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
                    Av1Encoder.Status.OK -> pkt.payload?.let {
                        putImage(it, "video/AV1", if (pkt.isKey) "key.av1" else "av1")
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
        uploadExecutor.execute { putImage(jpeg, "image/jpeg", "jpg") }
    }

    /**
     * PUT [bytes] to [baseUrl]/{date}/{timestamp}.[ext] (date segment only when daily-dir
     * mode is active). Sensor metadata travels in the [x-sensor-data] header as JSON so
     * the URL and body stay clean.
     */
    private fun putImage(bytes: ByteArray, mimeType: String, ext: String) {
        val baseUrl = SettingsManager.getUploadUrl(this)
        if (baseUrl.isBlank()) {
            updateNotification("No URL — tap icon to configure.")
            return
        }

        val timestamp = System.currentTimeMillis()
        val duration  = timestamp - captureTime

        val dirUrl = if (SettingsManager.isDayByDayMode(this) && SettingsManager.isDailyDirMode(this)) {
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
            .url("$dirUrl/$timestamp.$ext")
            .put(bytes.toRequestBody(mimeType.toMediaType()))
            .header("x-sensor-data", sensorData)
            .also { SettingsManager.getBasicAuthHeader(this)?.let { h -> it.header("Authorization", h) } }
            .build()
        try {
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    Log.i(TAG, "Upload OK: ${resp.code}")
                    updateNotification("Last upload: ${DateFormat.format("HH:mm:ss", timestamp)} ✓")
                } else {
                    Log.w(TAG, "Upload failed: HTTP ${resp.code}")
                    updateNotification("Upload failed (HTTP ${resp.code})")
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "putImage IOException", e)
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
