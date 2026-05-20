package com.camerauploader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Size
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Launcher activity — shows a settings dialog and then disappears.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_PERMISSIONS = 100
    }

    private val requiredPermissions: Array<String>
        get() {
            val perms = mutableListOf(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                perms += Manifest.permission.POST_NOTIFICATIONS
            if (SettingsManager.isDaylightOnly(this))
                perms += Manifest.permission.ACCESS_COARSE_LOCATION
            return perms.toTypedArray()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlarmScheduler.cancel(this)
        loadResolutionsAndShowDialog()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        loadResolutionsAndShowDialog()
    }

    // ── Resolution loading ────────────────────────────────────────────────────

    private fun loadResolutionsAndShowDialog() {
        val progress = AlertDialog.Builder(this)
            .setMessage("Loading camera resolutions…")
            .setCancelable(false)
            .create()

        val cameraGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (cameraGranted) {
            progress.show()
            Thread {
                val sizes = ResolutionHelper.getSupportedSizes(applicationContext)
                runOnUiThread {
                    progress.dismiss()
                    showSettingsDialog(sizes)
                }
            }.start()
        } else {
            showSettingsDialog(emptyList())
        }
    }

    // ── Settings dialog ───────────────────────────────────────────────────────

    private fun showSettingsDialog(availableSizes: List<Size>) {
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

        // ── Resolution spinner ──
        val resLabel = label("Image resolution")
        val savedSize = s.getResolution(this)

        data class ResEntry(val size: Size?, val label: String)

        val resEntries = mutableListOf(ResEntry(null, "Device default (highest)"))
        availableSizes.forEach { resEntries += ResEntry(it, ResolutionHelper.format(it)) }

        val resSpinner = Spinner(this).apply {
            val adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                resEntries.map { it.label }
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setAdapter(adapter)
            val savedIndex = if (savedSize == null) 0
                else resEntries.indexOfFirst { it.size == savedSize }.takeIf { it >= 0 } ?: 0
            setSelection(savedIndex)
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

        // ── Day-by-day recording ──
        val dailyDirHeader = label("Day-by-day recording")
        val dailyDirCheck = CheckBox(this).apply {
            text = "Group captures by day (date-prefixed upload path)"
            isChecked = s.isDailyDirMode(this@MainActivity)
        }
        val mkcolCheck = CheckBox(this).apply {
            text = "    Create daily directory on server (WebDAV MKCOL)"
            isChecked = s.isDailyDirMkcol(this@MainActivity)
            isEnabled = dailyDirCheck.isChecked
        }
        dailyDirCheck.setOnCheckedChangeListener { _, checked -> mkcolCheck.isEnabled = checked }

        // ── Daylight hours ──
        val daylightHeader = label("Recording window")
        val daylightCheck = CheckBox(this).apply {
            text = "Daylight hours only (sunrise–sunset)"
            isChecked = s.isDaylightOnly(this@MainActivity)
        }
        val daylightOffsetLabel = label("Daylight offset (minutes)")
        val daylightOffsetInput = editText(
            value     = s.getDaylightOffsetMinutes(this).toString(),
            hint      = "0",
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        )
        val daylightNote = TextView(this).apply {
            text = "Positive offset widens the recording window (start earlier, stop later). " +
                   "Requires location permission (ACCESS_COARSE_LOCATION)."
            textSize = 11f
            setTextColor(0xFF888888.toInt())
            setPadding(0, dpToPx(2), 0, 0)
        }
        val locationInfo = TextView(this).apply {
            val lat = s.getLocationLat(this@MainActivity)
            text = if (lat.isNaN()) "Location: not yet acquired"
                   else "Location: %.4f, %.4f".format(lat, s.getLocationLon(this@MainActivity))
            textSize = 11f
            setTextColor(0xFF888888.toInt())
            setPadding(0, dpToPx(2), 0, 0)
        }

        // ── AV1 encoding parameters ──
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
            addView(resLabel)
            addView(resSpinner)
            addView(resNote)
            addView(modeLabel)
            addView(modeSpinner)
            addView(modeNote)
            addView(dailyDirHeader)
            addView(dailyDirCheck)
            addView(mkcolCheck)
            addView(daylightHeader)
            addView(daylightCheck)
            addView(daylightOffsetLabel)
            addView(daylightOffsetInput)
            addView(daylightNote)
            addView(locationInfo)
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

        AlertDialog.Builder(this)
            .setTitle(if (isFirstRun) "Configure uploader" else "Settings")
            .setView(ScrollView(this).apply { addView(container) })
            .setCancelable(!isFirstRun)
            .setPositiveButton("Save & Start") { _, _ ->
                val url = urlInput.text.toString().trim()
                val intervalSecs = intervalInput.text.toString().trim().toIntOrNull() ?: 0

                if (url.isBlank() || !url.startsWith("http")) {
                    toast("Please enter a valid URL starting with https://")
                    showSettingsDialog(availableSizes); return@setPositiveButton
                }
                if (intervalSecs < 1) {
                    toast("Interval must be at least 1 second")
                    showSettingsDialog(availableSizes); return@setPositiveButton
                }

                s.setUploadUrl(this, url)
                s.setIntervalSeconds(this, intervalSecs)
                s.setResolution(this, resEntries[resSpinner.selectedItemPosition].size)
                s.setUploadMode(this, modeEntries[modeSpinner.selectedItemPosition].first)
                s.setAuthCredentials(
                    this,
                    userInput.text.toString().trim(),
                    passInput.text.toString()
                )
                s.setRecordingEnabled(this, recordingEnabledCheck.isChecked)
                s.setDailyDirMode(this, dailyDirCheck.isChecked)
                s.setDailyDirMkcol(this, mkcolCheck.isChecked)
                s.setDaylightOnly(this, daylightCheck.isChecked)
                val offset = daylightOffsetInput.text.toString().trim().toIntOrNull() ?: 0
                s.setDaylightOffsetMinutes(this, offset)
                val crf = av1CrfInput.text.toString().trim().toIntOrNull() ?: 37
                s.setAv1Crf(this, crf)
                val encMode = av1ModeInput.text.toString().trim().toIntOrNull() ?: 10
                s.setAv1EncMode(this, encMode)

                proceedAfterSettingsSaved()
            }
            .apply {
                if (!isFirstRun)
                    setNegativeButton("Cancel") { _, _ -> finish() }
            }
            .setOnCancelListener { finish() }
            .show()
            .also { dialog ->
                dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                dialog.window?.setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT
                )
            }
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
            toast("Uploader running ✓")
            finish()
        } else {
            ActivityCompat.requestPermissions(this, requiredPermissions, REQUEST_PERMISSIONS)
        }
    }

    // ── Permission result ─────────────────────────────────────────────────────

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (allPermissionsGranted()) {
                restartUploaderService()
                toast("Uploader running ✓")
            } else {
                toast("Camera permission is required for the uploader to work.")
            }
            finish()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun allPermissionsGranted() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun restartUploaderService() {
        AlarmScheduler.scheduleNext(this)
        val intent = Intent(this, CameraUploaderService::class.java).apply {
            action = CameraUploaderWorker.ACTION_CAPTURE
        }
        startForegroundService(intent)
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
