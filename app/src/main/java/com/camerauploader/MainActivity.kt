package com.camerauploader

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCharacteristics
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_PERMISSIONS    = 100
        private const val REQUEST_LOCATION_FILL  = 101
        private const val REQUEST_CAMERA_UPFRONT = 102
    }

    private var latInput: EditText? = null
    private var lonInput: EditText? = null
    private var pendingLocationFill = false

    // ── Preview overlay state ──
    private var rootFrame: FrameLayout? = null
    private var previewOverlay: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var previewTimeout: Runnable? = null

    private val requiredPermissions: Array<String>
        get() {
            val perms = mutableListOf(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                perms += Manifest.permission.POST_NOTIFICATIONS
            return perms.toTypedArray()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlarmScheduler.cancel(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (previewOverlay != null) {
                    cancelPreviewTimeout()
                    PreviewBus.onResult = null
                    dismissPreviewOverlay()
                } else {
                    finish()
                }
            }
        })
        loadResolutionsAndShowDialog()
    }

    override fun onDestroy() {
        super.onDestroy()
        PreviewBus.onResult = null
        cancelPreviewTimeout()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        loadResolutionsAndShowDialog()
    }

    // ── Resolution loading ────────────────────────────────────────────────────

    private fun loadResolutionsAndShowDialog() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_UPFRONT
            )
            return
        }
        setContentView(FrameLayout(this).apply {
            addView(ProgressBar(this@MainActivity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            })
        })
        Thread {
            val cameras  = ExposureHelper.getAvailableCameras(applicationContext)
            val storedId = SettingsManager.getCameraId(applicationContext)
            val initialCameraId = storedId
                ?: cameras.firstOrNull {
                    it.facing == CameraCharacteristics.LENS_FACING_BACK
                }?.cameraId
            val sizes     = ResolutionHelper.getSupportedSizes(
                applicationContext, cameraId = initialCameraId)
            val evRange   = ExposureHelper.getEvRange(applicationContext)
            val awbModes  = ExposureHelper.getAvailableAwbModes(applicationContext)
            val zoomRange = ExposureHelper.getZoomRatioRange(applicationContext, initialCameraId)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                showSettingsDialog(cameras, sizes, evRange, awbModes, zoomRange)
            }
        }.start()
    }

    // ── Settings dialog ───────────────────────────────────────────────────────

    private fun showSettingsDialog(
        cameras: List<ExposureHelper.CameraEntry> = emptyList(),
        availableSizes: List<Size>,
        evRange: ExposureHelper.EvRange?,
        awbModes: List<ExposureHelper.AwbMode> = emptyList(),
        initialZoomRange: Pair<Float, Float>? = null,
    ) {
        val s = SettingsManager
        val isFirstRun = !s.isConfigured(this)
        val pad = dpToPx(20)
        val halfPad = dpToPx(8)

        // ── Recording enabled ──
        val recordingEnabledCheck = CheckBox(this).apply {
            text = "Recording enabled"
            isChecked = s.isRecordingEnabled(this@MainActivity)
            setPadding(0, dpToPx(4), 0, dpToPx(8))
        }

        // ── Upload URL ──
        val urlLabel = label("Upload URL *")
        val urlInput = editText(
            value     = s.getUploadUrl(this),
            hint      = "https://example.com/upload",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        )

        // ── Interval ──
        val intervalLabel = label("Capture interval (seconds) *")
        val intervalInput = editText(
            value     = s.getIntervalSeconds(this).toString(),
            hint      = "${SettingsManager.DEFAULT_INTERVAL_SECONDS}",
            inputType = InputType.TYPE_CLASS_NUMBER
        )

        // ── Camera selector ──
        // Fall back to a placeholder entry when camera enumeration was skipped
        // (e.g. permission not yet granted).
        val cameraEntries: List<ExposureHelper.CameraEntry> = cameras.ifEmpty {
            listOf(ExposureHelper.CameraEntry(
                "", CameraCharacteristics.LENS_FACING_BACK, "Back (default)"))
        }
        val cameraLabel = label("Camera")
        val storedCameraId = SettingsManager.getCameraId(this)
        val cameraInitIdx = cameraEntries.indexOfFirst { it.cameraId == storedCameraId }
            .takeIf { it >= 0 }
            ?: cameraEntries.indexOfFirst {
                it.facing == CameraCharacteristics.LENS_FACING_BACK
            }.takeIf { it >= 0 }
            ?: 0
        val cameraSpinner = Spinner(this).apply {
            val adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                cameraEntries.map { it.label }
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setAdapter(adapter)
            setSelection(cameraInitIdx)
        }
        // Track which camera's resolutions are currently shown; prevents a redundant
        // background reload from the initial onItemSelected callback fired by setSelection().
        var resCurrentCameraId: String? =
            cameraEntries.getOrNull(cameraInitIdx)?.cameraId?.ifBlank { null }

        // ── Resolution spinner ──
        val resLabel = label("Image resolution")
        val savedSize = s.getResolution(this)

        data class ResEntry(val size: Size?, val label: String)

        val resEntries = mutableListOf(ResEntry(null, "Device default (highest)"))
        availableSizes.forEach { resEntries += ResEntry(it, ResolutionHelper.format(it)) }

        val resAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            resEntries.map { it.label }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val resSpinner = Spinner(this).apply {
            setAdapter(resAdapter)
            val savedIndex = if (savedSize == null) 0
                else resEntries.indexOfFirst { it.size == savedSize }.takeIf { it >= 0 } ?: 0
            setSelection(savedIndex)
        }

        // ── Zoom ratio ────────────────────────────────────────────────────────────
        // CONTROL_ZOOM_RATIO requires Android 11 (API 30). On older devices all
        // zoom widgets are shown but permanently disabled (greyed out).
        val ZOOM_STEPS = 200
        val supportsZoom = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

        // Mutable: updated whenever the user switches cameras via the spinner.
        var zoomRange: Pair<Float, Float>? = initialZoomRange

        val zoomHeader = label("Zoom ratio")
        val zoomNote = TextView(this).apply {
            textSize = 11f
            setTextColor(0xFF888888.toInt())
            setPadding(0, dpToPx(2), 0, 0)
        }
        val zoomValue = TextView(this).apply {
            textSize = 12f
            setPadding(0, dpToPx(2), 0, dpToPx(2))
        }
        val zoomSeek = SeekBar(this).apply { max = ZOOM_STEPS }

        /** Log-scale: equal slider distance for each doubling of zoom. */
        fun progressToZoom(p: Int, min: Float, max: Float): Float {
            val logMin = Math.log(min.coerceAtLeast(0.01).toDouble())
            val logMax = Math.log(max.toDouble())
            return Math.exp(logMin + (logMax - logMin) * p.toDouble() / ZOOM_STEPS)
                .toFloat().coerceIn(min, max)
        }
        fun zoomToProgress(ratio: Float, min: Float, max: Float): Int {
            val logMin = Math.log(min.coerceAtLeast(0.01).toDouble())
            val logMax = Math.log(max.toDouble())
            val logR   = Math.log(ratio.coerceIn(min, max).toDouble())
            return ((logR - logMin) / (logMax - logMin) * ZOOM_STEPS)
                .toInt().coerceIn(0, ZOOM_STEPS)
        }

        fun applyZoomRange(range: Pair<Float, Float>?) {
            zoomRange = range
            when {
                !supportsZoom -> {
                    zoomSeek.isEnabled  = false
                    zoomValue.text      = "1.00×"
                    zoomNote.text       = "Requires Android 11 or newer"
                }
                range == null -> {
                    zoomSeek.isEnabled  = false
                    zoomValue.text      = "1.00×"
                    zoomNote.text       = "Not supported by this camera"
                }
                else -> {
                    val savedRatio = SettingsManager.getZoomRatio(this)
                        .coerceIn(range.first, range.second)
                    val initP = zoomToProgress(savedRatio, range.first, range.second)
                    zoomSeek.isEnabled = true
                    zoomSeek.progress  = initP   // triggers onProgressChanged → reads updated zoomRange
                    zoomNote.text      = "Range: %.2f× – %.2f×".format(range.first, range.second)
                }
            }
        }

        zoomSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, p: Int, fromUser: Boolean) {
                val r = zoomRange ?: return
                zoomValue.text = "%.2f×".format(progressToZoom(p, r.first, r.second))
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        applyZoomRange(initialZoomRange)

        // Reload the resolution list whenever the user picks a different camera.
        cameraSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(p: AdapterView<*>?) {}
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val entry = cameraEntries.getOrNull(pos) ?: return
                val newId = entry.cameraId.ifBlank { null }
                if (newId == resCurrentCameraId) return  // suppress initial callback
                resCurrentCameraId = newId
                resSpinner.isEnabled = false
                if (supportsZoom) zoomSeek.isEnabled = false  // disable while reloading
                Thread {
                    val newSizes     = ResolutionHelper.getSupportedSizes(
                        applicationContext, cameraId = newId)
                    val newZoomRange = ExposureHelper.getZoomRatioRange(applicationContext, newId)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        resEntries.clear()
                        resEntries += ResEntry(null, "Device default (highest)")
                        newSizes.forEach { sz -> resEntries += ResEntry(sz, ResolutionHelper.format(sz)) }
                        resAdapter.clear()
                        resAdapter.addAll(resEntries.map { it.label })
                        resSpinner.isEnabled = true
                        // Preserve the closest previously-saved resolution on the new camera.
                        val stored = SettingsManager.getResolution(this@MainActivity)
                        val bestIdx = resEntries.indexOfFirst { it.size == stored }
                            .takeIf { it >= 0 }
                            ?: if (newSizes.isNotEmpty())
                                1 + (newSizes.indices.minByOrNull { i ->
                                    newSizes[i].penalty(stored) } ?: 0)
                               else 0
                        resSpinner.setSelection(bestIdx.coerceIn(0, resEntries.lastIndex))
                        applyZoomRange(newZoomRange)
                    }
                }.start()
            }
        }

        val resNote = TextView(this).apply {
            text = if (availableSizes.isEmpty())
                "Grant camera permission and re-open settings to see supported resolutions."
            else
                "${availableSizes.size} resolutions available."
            textSize = 11f
            setTextColor(0xFF888888.toInt())
            setPadding(0, dpToPx(2), 0, 0)
        }

        // ── Upload mode ──
        val modeLabel = label("Upload mode")
        val modeEntries = listOf(
            SettingsManager.UploadMode.JPEG to "JPEG (one image per capture)",
            SettingsManager.UploadMode.AV1  to "AV1 (one OBU chunk per capture)",
        )
        val modeSpinner = Spinner(this).apply {
            val adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                modeEntries.map { it.second }
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setAdapter(adapter)
            val current = s.getUploadMode(this@MainActivity)
            setSelection(modeEntries.indexOfFirst { it.first == current }.coerceAtLeast(0))
        }
        val modeNote = TextView(this).apply {
            text = "AV1 encodes each YUV frame and sends the OBU chunks as the " +
                "\"image\" field of a multipart POST, one POST per capture."
            textSize = 11f
            setTextColor(0xFF888888.toInt())
            setPadding(0, dpToPx(2), 0, 0)
        }

        // ── Camera focus ──────────────────────────────────────────────────────
        // Slider: progress MAX (right) = 0.0 diopters (infinity);
        //         progress 0   (left)  = FOCUS_MAX_DIOPTERS (closest, ~5 cm).
        val FOCUS_SEEK_MAX     = 200
        val FOCUS_MAX_DIOPTERS = 20.0f
        fun sliderToDiopters(p: Int)  = (1.0f - p.toFloat() / FOCUS_SEEK_MAX) * FOCUS_MAX_DIOPTERS
        fun dioptToLabel(d: Float)    = if (d < 0.05f) "∞ (infinity)"
                                        else "%.2f m  (%.0f cm)".format(1.0f / d, 100.0f / d)

        val focusHeader = label("Camera focus")

        val afCheck = CheckBox(this).apply {
            text = "Use auto-focus (AF)"
            isChecked = s.isAfEnabled(this@MainActivity)
            setPadding(0, dpToPx(4), 0, dpToPx(4))
        }

        val savedFocusDist = s.getFocusDistance(this@MainActivity)
        val savedFocusProgress = ((1.0f - savedFocusDist / FOCUS_MAX_DIOPTERS) * FOCUS_SEEK_MAX)
            .toInt().coerceIn(0, FOCUS_SEEK_MAX)

        val focusDistLabel = label("Focus distance (∞ at right end, closest at left end)").apply {
            isEnabled = !afCheck.isChecked
        }
        val focusDistValue = TextView(this).apply {
            text = dioptToLabel(savedFocusDist)
            textSize = 12f
            setPadding(0, dpToPx(2), 0, dpToPx(2))
            isEnabled = !afCheck.isChecked
        }
        val focusDistSeek = SeekBar(this).apply {
            max = FOCUS_SEEK_MAX
            progress = savedFocusProgress
            isEnabled = !afCheck.isChecked
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, p: Int, fromUser: Boolean) {
                    focusDistValue.text = dioptToLabel(sliderToDiopters(p))
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        }

        afCheck.setOnCheckedChangeListener { _, checked ->
            focusDistLabel.isEnabled = !checked
            focusDistSeek.isEnabled  = !checked
            focusDistValue.isEnabled = !checked
        }

        // ── Exposure compensation ─────────────────────────────────────────────
        // Slider spans the device's reported CONTROL_AE_COMPENSATION_RANGE.
        // progress 0 = range.min (darkest); progress max = range.max (brightest).
        // Only built when the device reports a usable range.
        var evHeader: TextView? = null
        var evValue:  TextView? = null
        var evSeek:   SeekBar?  = null
        if (evRange != null) {
            fun evLabel(comp: Int): String =
                if (comp == 0) "0 EV (no compensation)"
                else "%+.1f EV".format(comp * evRange.stepEv)

            val savedComp = s.getEvCompensation(this).coerceIn(evRange.min, evRange.max)
            evHeader = label("Exposure compensation")
            evValue = TextView(this).apply {
                text = evLabel(savedComp)
                textSize = 12f
                setPadding(0, dpToPx(2), 0, dpToPx(2))
            }
            evSeek = SeekBar(this).apply {
                max = evRange.max - evRange.min
                progress = savedComp - evRange.min
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, p: Int, fromUser: Boolean) {
                        evValue?.text = evLabel(evRange.min + p)
                    }
                    override fun onStartTrackingTouch(bar: SeekBar) {}
                    override fun onStopTrackingTouch(bar: SeekBar) {}
                })
            }
        }

        // ── White balance ─────────────────────────────────────────────────────
        // Spinner is populated from the modes reported by the device; hidden when
        // the list is empty (camera permission denied or no back camera found).
        var awbSpinner: Spinner? = null
        if (awbModes.isNotEmpty()) {
            val savedAwbMode = s.getAwbMode(this)
            awbSpinner = Spinner(this).apply {
                val adapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_item,
                    awbModes.map { it.label }
                ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                setAdapter(adapter)
                val savedIndex = awbModes.indexOfFirst { it.id == savedAwbMode }
                    .takeIf { it >= 0 } ?: 0
                setSelection(savedIndex)
            }
        }

        // ── Day-by-day recording ──────────────────────────────────────────────
        // Top-level: resets the AV1 encoder at each local-timezone day boundary.
        val dayByDayHeader = label("Day-by-day recording")
        val dayByDayCheck = CheckBox(this).apply {
            text = "Enable day-by-day mode"
            isChecked = s.isDayByDayMode(this@MainActivity)
        }

        // Sub-option: create per-day directories on the server via WebDAV MKCOL.
        val indentPx = dpToPx(24)
        val dailyDirCheck = CheckBox(this).apply {
            text = "Create daily directory on server (WebDAV MKCOL, date-prefixed path)"
            isChecked = s.isDailyDirMode(this@MainActivity)
            isEnabled = dayByDayCheck.isChecked
            setPadding(indentPx, 0, 0, 0)
        }

        // Sub-option: limit recording to the daylight window.
        val daylightCheck = CheckBox(this).apply {
            text = "Daylight hours only (sunrise–sunset)"
            isChecked = s.isDaylightOnly(this@MainActivity)
            isEnabled = dayByDayCheck.isChecked
            setPadding(indentPx, 0, 0, 0)
        }
        val daylightOffsetLabel = label("    Daylight offset (minutes)").apply {
            isEnabled = dayByDayCheck.isChecked && daylightCheck.isChecked
        }
        val daylightOffsetInput = editText(
            value     = s.getDaylightOffsetMinutes(this).toString(),
            hint      = "0",
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        ).apply {
            isEnabled = dayByDayCheck.isChecked && daylightCheck.isChecked
        }
        val daylightNote = TextView(this).apply {
            text = "Positive = wider window (start before sunrise, stop after sunset)."
            textSize = 11f
            setTextColor(0xFF888888.toInt())
            setPadding(indentPx, dpToPx(2), 0, 0)
        }
        val storedLat = s.getLocationLat(this@MainActivity)
        val storedLon = s.getLocationLon(this@MainActivity)
        val latLabel = label("    Latitude").apply {
            isEnabled = dayByDayCheck.isChecked && daylightCheck.isChecked
        }
        latInput = editText(
            value     = if (storedLat.isNaN()) "" else "%.6f".format(storedLat),
            hint      = "e.g. 37.7749",
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        ).also { it.isEnabled = dayByDayCheck.isChecked && daylightCheck.isChecked }
        val lonLabel = label("    Longitude").apply {
            isEnabled = dayByDayCheck.isChecked && daylightCheck.isChecked
        }
        lonInput = editText(
            value     = if (storedLon.isNaN()) "" else "%.6f".format(storedLon),
            hint      = "e.g. -122.4194",
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        ).also { it.isEnabled = dayByDayCheck.isChecked && daylightCheck.isChecked }
        val useDeviceLocationBtn = Button(this).apply {
            text = "Use device location"
            setPadding(indentPx, 0, 0, 0)
            isEnabled = dayByDayCheck.isChecked && daylightCheck.isChecked
            setOnClickListener { fillLocationFromDevice() }
        }

        fun updateDaylightSubItems() {
            val on = dayByDayCheck.isChecked && daylightCheck.isChecked
            daylightOffsetLabel.isEnabled = on
            daylightOffsetInput.isEnabled = on
            latLabel.isEnabled = on
            latInput?.isEnabled = on
            lonLabel.isEnabled = on
            lonInput?.isEnabled = on
            useDeviceLocationBtn.isEnabled = on
        }

        dayByDayCheck.setOnCheckedChangeListener { _, checked ->
            dailyDirCheck.isEnabled = checked
            daylightCheck.isEnabled = checked
            updateDaylightSubItems()
        }
        daylightCheck.setOnCheckedChangeListener { _, _ -> updateDaylightSubItems() }

        // ── AV1 encoding parameters ───────────────────────────────────────────
        val av1Header = label("AV1 encoding parameters")
        val av1CrfLabel = label("CRF (0–63, lower = better quality; default 37)")
        val av1CrfInput = editText(
            value     = s.getAv1Crf(this).toString(),
            hint      = "37",
            inputType = InputType.TYPE_CLASS_NUMBER
        )
        val av1ModeLabel = label("Encoding mode (0–10, lower = slower/better; default 10)")
        val av1ModeInput = editText(
            value     = s.getAv1EncMode(this).toString(),
            hint      = "10",
            inputType = InputType.TYPE_CLASS_NUMBER
        )
        val av1Note = TextView(this).apply {
            text = "Changes take effect on next service restart."
            textSize = 11f
            setTextColor(0xFF888888.toInt())
            setPadding(0, dpToPx(2), 0, 0)
        }

        // ── Basic Auth ──
        val authHeader = TextView(this).apply {
            text = "Basic Auth (optional)"
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dpToPx(12), 0, 0)
        }
        val authSubtitle = TextView(this).apply {
            text = "Leave blank to disable authentication."
            textSize = 12f
            setPadding(0, 0, 0, halfPad)
            setTextColor(0xFF888888.toInt())
        }

        val userLabel = label("Username")
        val userInput = editText(
            value     = s.getAuthUsername(this),
            hint      = "username",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        )

        val passLabel = label("Password")
        val passInput = editText(
            value     = s.getAuthPassword(this),
            hint      = "password",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )

        val showPassCheck = CheckBox(this).apply {
            text = "Show password"
            setOnCheckedChangeListener { _, checked ->
                passInput.inputType = if (checked)
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                else
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                passInput.setSelection(passInput.text.length)
            }
        }

        // ── Assemble layout ──
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, halfPad, pad, halfPad)
            addView(recordingEnabledCheck)
            addView(urlLabel)
            addView(urlInput)
            addView(intervalLabel)
            addView(intervalInput)
            addView(cameraLabel)
            addView(cameraSpinner)
            addView(resLabel)
            addView(resSpinner)
            addView(resNote)
            addView(modeLabel)
            addView(modeSpinner)
            addView(modeNote)
            addView(focusHeader)
            addView(afCheck)
            addView(focusDistLabel)
            addView(focusDistSeek)
            addView(focusDistValue)
            evHeader?.let { addView(it) }
            evSeek?.let   { addView(it) }
            evValue?.let  { addView(it) }
            awbSpinner?.let { addView(label("White balance")); addView(it) }
            addView(zoomHeader)
            addView(zoomSeek)
            addView(zoomValue)
            addView(zoomNote)
            addView(dayByDayHeader)
            addView(dayByDayCheck)
            addView(dailyDirCheck)
            addView(daylightCheck)
            addView(daylightOffsetLabel)
            addView(daylightOffsetInput)
            addView(daylightNote)
            addView(latLabel)
            addView(latInput!!)
            addView(lonLabel)
            addView(lonInput!!)
            addView(useDeviceLocationBtn)
            addView(av1Header)
            addView(av1CrfLabel)
            addView(av1CrfInput)
            addView(av1ModeLabel)
            addView(av1ModeInput)
            addView(av1Note)
            addView(authHeader)
            addView(authSubtitle)
            addView(userLabel)
            addView(userInput)
            addView(passLabel)
            addView(passInput)
            addView(showPassCheck)
        }

        // Validate (only when starting a recording) and persist all settings.
        // requireRecordingConfig=false (Preview) skips the URL/interval/location
        // checks so the camera can be previewed before a server is configured.
        fun collectAndSaveSettings(requireRecordingConfig: Boolean): Boolean {
            val url = urlInput.text.toString().trim()
            val intervalSecs = intervalInput.text.toString().trim().toIntOrNull() ?: 0
            if (requireRecordingConfig) {
                if (url.isBlank() || !url.startsWith("http")) {
                    toast("Please enter a valid URL starting with https://"); return false
                }
                if (intervalSecs < 1) {
                    toast("Interval must be at least 1 second"); return false
                }
                if (dayByDayCheck.isChecked && daylightCheck.isChecked) {
                    val latV = latInput?.text?.toString()?.trim()?.toDoubleOrNull()
                    val lonV = lonInput?.text?.toString()?.trim()?.toDoubleOrNull()
                    if (latV == null || latV !in -90.0..90.0 ||
                        lonV == null || lonV !in -180.0..180.0) {
                        toast("Enter a valid latitude (−90..90) and longitude (−180..180) for daylight mode")
                        return false
                    }
                }
            }

            s.setUploadUrl(this, url)
            s.setIntervalSeconds(this, intervalSecs)
            s.setCameraId(this, cameraEntries.getOrNull(
                cameraSpinner.selectedItemPosition)?.cameraId?.ifBlank { null })
            s.setResolution(this, resEntries[resSpinner.selectedItemPosition].size)
            s.setUploadMode(this, modeEntries[modeSpinner.selectedItemPosition].first)
            s.setAfEnabled(this, afCheck.isChecked)
            s.setFocusDistance(this, sliderToDiopters(focusDistSeek.progress))
            val seek = evSeek
            if (evRange != null && seek != null) {
                s.setEvCompensation(this, evRange.min + seek.progress)
            }
            val awbSpin = awbSpinner
            if (awbModes.isNotEmpty() && awbSpin != null) {
                s.setAwbMode(this, awbModes[awbSpin.selectedItemPosition].id)
            }
            val zr = zoomRange
            if (supportsZoom && zr != null) {
                s.setZoomRatio(this, progressToZoom(zoomSeek.progress, zr.first, zr.second))
            } else if (!supportsZoom) {
                s.setZoomRatio(this, 1.0f)  // reset to neutral on devices that can't use it
            }
            s.setRecordingEnabled(this, recordingEnabledCheck.isChecked)
            s.setDayByDayMode(this, dayByDayCheck.isChecked)
            s.setDailyDirMode(this, dailyDirCheck.isChecked)
            s.setDaylightOnly(this, daylightCheck.isChecked)
            val offset = daylightOffsetInput.text.toString().trim().toIntOrNull() ?: 0
            s.setDaylightOffsetMinutes(this, offset)
            val crf = av1CrfInput.text.toString().trim().toIntOrNull() ?: 37
            s.setAv1Crf(this, crf)
            val encMode = av1ModeInput.text.toString().trim().toIntOrNull() ?: 10
            s.setAv1EncMode(this, encMode)
            s.setAuthCredentials(
                this,
                userInput.text.toString().trim(),
                passInput.text.toString()
            )
            val latVal = latInput?.text?.toString()?.trim()?.toDoubleOrNull()
            val lonVal = lonInput?.text?.toString()?.trim()?.toDoubleOrNull()
            if (latVal != null && lonVal != null) s.setLocation(this, latVal.toFloat(), lonVal.toFloat())
            return true
        }

        // ── Assemble the full-screen layout ──
        val titleView = TextView(this).apply {
            text = if (isFirstRun) "Configure uploader" else "Settings"
            setTypeface(null, android.graphics.Typeface.BOLD)
            textSize = 20f
            setPadding(pad, pad, pad, halfPad)
        }

        fun barButton(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
            setOnClickListener { onClick() }
        }

        val buttonBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, halfPad, pad, halfPad)
            if (!isFirstRun) addView(barButton("Cancel") { finish() })
            addView(barButton("Preview") { if (collectAndSaveSettings(false)) startPreviewCapture() })
            addView(barButton("Save & Start") { if (collectAndSaveSettings(true)) proceedAfterSettingsSaved() })
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            addView(container)
        }

        val formColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(titleView)
            addView(scroll)
            addView(buttonBar)
        }

        previewOverlay = null
        cancelPreviewTimeout()
        val root = FrameLayout(this).apply { addView(formColumn) }
        rootFrame = root
        setContentView(root)
    }

    private fun proceedAfterSettingsSaved() {
        if (!SettingsManager.isRecordingEnabled(this)) {
            AlarmScheduler.cancel(this)
            toast("Recording disabled")
            finish()
            return
        }
        if (allPermissionsGranted()) {
            restartUploaderService()
            toast("Uploader running, first capture in 5s ✓")
            finish()
        } else {
            ActivityCompat.requestPermissions(this, requiredPermissions, REQUEST_PERMISSIONS)
        }
    }

    // ── Preview capture ─────────────────────────────────────────────────────

    private fun startPreviewCapture() {
        showCapturingOverlay()
        PreviewBus.onResult = { jpeg -> mainHandler.post { onPreviewResult(jpeg) } }
        armPreviewTimeout()
        val intent = Intent(this, CameraUploaderService::class.java).apply {
            action = CameraUploaderService.ACTION_PREVIEW_CAPTURE
        }
        startForegroundService(intent)
    }

    private fun armPreviewTimeout() {
        cancelPreviewTimeout()
        previewTimeout = Runnable {
            PreviewBus.onResult = null
            dismissPreviewOverlay()
            toast("Preview timed out (too dark or camera busy)")
        }.also { mainHandler.postDelayed(it, 12_000) }
    }

    private fun cancelPreviewTimeout() {
        previewTimeout?.let { mainHandler.removeCallbacks(it) }
        previewTimeout = null
    }

    private fun onPreviewResult(jpeg: ByteArray?) {
        if (isFinishing || isDestroyed) return
        cancelPreviewTimeout()
        if (jpeg == null) {
            dismissPreviewOverlay()
            toast("Preview capture failed")
            return
        }
        Thread {
            val bmp = runCatching { decodeDownsampled(jpeg) }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (bmp == null) {
                    dismissPreviewOverlay()
                    toast("Could not decode preview image")
                } else {
                    showImageOverlay(bmp)
                }
            }
        }.start()
    }

    /** Decode the captured JPEG downsampled to roughly the screen size to avoid OOM. */
    private fun decodeDownsampled(jpeg: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        val dm = resources.displayMetrics
        val reqW = dm.widthPixels.coerceAtLeast(1)
        val reqH = dm.heightPixels.coerceAtLeast(1)
        var sample = 1
        while (bounds.outWidth > 0 && bounds.outHeight > 0 &&
            bounds.outWidth / (sample * 2) >= reqW && bounds.outHeight / (sample * 2) >= reqH
        ) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
    }

    private fun showCapturingOverlay() {
        val root = rootFrame ?: return
        dismissPreviewOverlay()
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            isClickable = true  // swallow touches to the form behind
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
                addView(ProgressBar(this@MainActivity))
                addView(TextView(this@MainActivity).apply {
                    text = "Capturing…"
                    setPadding(0, dpToPx(8), 0, 0)
                })
            })
        }
        root.addView(overlay)
        previewOverlay = overlay
    }

    private fun showImageOverlay(bitmap: Bitmap) {
        val root = rootFrame ?: return
        dismissPreviewOverlay()
        val iv = ZoomableImageView(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            setImageBitmap(bitmap)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            // Pinch / double-tap to zoom, drag to pan; a single tap dismisses.
            onSingleTap = { dismissPreviewOverlay() }
        }
        root.addView(iv)
        previewOverlay = iv
        WindowCompat.getInsetsController(window, iv).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun dismissPreviewOverlay() {
        val overlay = previewOverlay ?: return
        rootFrame?.removeView(overlay)
        previewOverlay = null
        WindowCompat.getInsetsController(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    // ── Permission result ─────────────────────────────────────────────────────

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_CAMERA_UPFRONT -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                    loadResolutionsAndShowDialog()
                } else {
                    showSettingsDialog(availableSizes = emptyList(), evRange = null)
                }
            }
            REQUEST_LOCATION_FILL -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                    fillLocationFromDevice()
                } else {
                    toast("Location permission denied — enter coordinates manually")
                }
                pendingLocationFill = false
            }
            REQUEST_PERMISSIONS -> {
                if (allPermissionsGranted()) {
                    restartUploaderService()
                    toast("Uploader running, first capture in 5s ✓")
                } else {
                    toast("Camera permission is required for the uploader to work.")
                }
                finish()
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun allPermissionsGranted() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun restartUploaderService() {
        // Schedule the first capture after a 5-second delay (time to position the
        // camera); subsequent captures follow the normal interval from that point.
        AlarmScheduler.scheduleFirstCapture(this)
        val intent = Intent(this, CameraUploaderService::class.java).apply {
            action = CameraUploaderService.ACTION_SETTINGS_CHANGED
        }
        startForegroundService(intent)
    }

    @SuppressLint("MissingPermission")
    private fun fillLocationFromDevice() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            pendingLocationFill = true
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_LOCATION_FILL
            )
            return
        }
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val providers = listOf(
            LocationManager.PASSIVE_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
        )
        val loc = providers.firstNotNullOfOrNull { p ->
            runCatching { lm.getLastKnownLocation(p) }.getOrNull()
        }
        if (loc != null) {
            latInput?.setText("%.6f".format(loc.latitude))
            lonInput?.setText("%.6f".format(loc.longitude))
        } else {
            toast("Location not available — enter manually or try again later")
        }
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTypeface(null, android.graphics.Typeface.BOLD)
        setPadding(0, dpToPx(10), 0, dpToPx(2))
    }

    private fun editText(value: String, hint: String, inputType: Int) =
        EditText(this).apply {
            this.inputType = inputType
            this.hint = hint
            setText(value)
            setSelection(text.length)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density + 0.5f).toInt()
}
