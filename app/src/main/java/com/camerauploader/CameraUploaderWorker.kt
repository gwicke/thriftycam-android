package com.camerauploader

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraFilter
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.util.concurrent.atomic.AtomicInteger

@ExperimentalCamera2Interop
class CameraUploaderWorker(
    val cameraProvider: ProcessCameraProvider,
    val lifecycleRegistry: LifecycleRegistry,
    val lifeOwner: LifecycleOwner,
    val applicationContext: android.content.Context,
    val service: CameraUploaderService,
    val updateNotification: (String) -> Unit,
    private val previewMode: Boolean = false,
    private val onCycleComplete: () -> Unit = {},
) {

    // ── Preflight state machine constants ────────────────────────────────────
    private object State {
        const val PREVIEW       = 0
        const val WAITING_3A    = 1
        const val PICTURE_TAKEN = 2
    }

    companion object {
        private const val TAG = "CameraUploaderWorker"
        const val ACTION_CAPTURE = "com.camerauploader.ACTION_CAPTURE"
    }

    lateinit var imageCapture: ImageCapture

    val yuvConverter: Yuv420Converter = Yuv420Converter

    private val uploadMode: SettingsManager.UploadMode
        get() = SettingsManager.getUploadMode(applicationContext)

    // Preview always captures a JPEG (trivially decodable, never touches the AV1
    // encoder), regardless of the configured upload mode.
    private val effectiveMode: SettingsManager.UploadMode
        get() = if (previewMode) SettingsManager.UploadMode.JPEG else uploadMode

    // ─────────────────────────────────────────────────────────────────────────
    // Outer cycle
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("RestrictedApi")
    fun run() {
        Log.d(TAG, "Capture cycle started (mode=$uploadMode)")
        updateNotification("Preflight — warming up 3A…")

        val previewBuilder = Preview.Builder()
        installCaptureCallback(previewBuilder)

        val savedSize = SettingsManager.getResolution(applicationContext)
        val resolutionSelector = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
            .setResolutionFilter { sizes, _ ->
                return@setResolutionFilter sizes.sortedByClosestTo(savedSize)
            }
            .build()

        val preview = previewBuilder
            .setResolutionSelector(resolutionSelector)
            .build().also { p ->
                p.setSurfaceProvider { req ->
                    val st = android.graphics.SurfaceTexture(0).apply {
                        setDefaultBufferSize(req.resolution.width, req.resolution.height)
                    }
                    val surface = android.view.Surface(st)
                    req.provideSurface(
                        surface,
                        ContextCompat.getMainExecutor(service)
                    ) { surface.release(); st.release() }
                }
            }
        val captureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .setTargetRotation(android.view.Surface.ROTATION_90)
            .setResolutionSelector(resolutionSelector)

        val afEnabled     = SettingsManager.isAfEnabled(applicationContext)
        val focusDistance = SettingsManager.getFocusDistance(applicationContext)
        val evComp        = SettingsManager.getEvCompensation(applicationContext)
        val awbMode       = SettingsManager.getAwbMode(applicationContext)

        fun <T> apply3A(ext: Camera2Interop.Extender<T>) {
            ext.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            if (afEnabled) {
                ext.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            } else {
                ext.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                   .setCaptureRequestOption(
                        CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
            }
            ext.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
               .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AWB_MODE, awbMode)
            if (evComp != 0) {
                ext.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evComp)
            }
        }
        fun <T> triggerPrecapture(ext: Camera2Interop.Extender<T>) {
            ext.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
        }
        apply3A(Camera2Interop.Extender(previewBuilder))
        triggerPrecapture(Camera2Interop.Extender(previewBuilder))
        apply3A(Camera2Interop.Extender(captureBuilder))

        val secondaryUseCase: androidx.camera.core.UseCase = when (effectiveMode) {
            SettingsManager.UploadMode.JPEG -> {
                imageCapture = captureBuilder.setJpegQuality(85).build()
                imageCapture
            }
            SettingsManager.UploadMode.AV1 -> {
                imageCapture = captureBuilder.setBufferFormat(ImageFormat.YUV_420_888).build()
                imageCapture
            }
        }

        try {
            cameraProvider.unbindAll()
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            val storedCameraId = SettingsManager.getCameraId(applicationContext)
            val cameraSelector = if (storedCameraId.isNullOrBlank()) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.Builder()
                    .addCameraFilter(Camera2CameraFilter.createCameraFilter { cams ->
                        cams.filterTo(mutableListOf()) {
                            Camera2CameraInfo.from(it).cameraId == storedCameraId
                        }
                    })
                    .build()
            }
            cameraProvider.bindToLifecycle(
                lifeOwner,
                cameraSelector,
                preview, secondaryUseCase
            )
            beginConvergenceWatch()
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed", e)
            shutdownCamera()
            if (previewMode) service.deliverPreview(null)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Camera2Interop: install CaptureCallback on Preview
    // ─────────────────────────────────────────────────────────────────────────

    private val captureState = AtomicInteger(State.PREVIEW)
    @Volatile private var stateEnteredAt = 0L

    private fun installCaptureCallback(previewBuilder: Preview.Builder) {
        Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(
            object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: android.hardware.camera2.CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    processCaptureResult(result)
                }
            }
        )
    }

    private fun processCaptureResult(result: CaptureResult) {
        if (captureState.get() != State.WAITING_3A) return

        val afState = result.get(CaptureResult.CONTROL_AF_STATE)
        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
        val elapsed = System.currentTimeMillis() - stateEnteredAt
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
        val exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)

        val afReady = !SettingsManager.isAfEnabled(applicationContext)
            || afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
            || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
            || afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED

        val aeReady = aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED
            || aeState == CaptureResult.CONTROL_AE_STATE_LOCKED
            || (Build.VERSION.SDK_INT < 29 && aeState == null)

        if (afReady && aeReady) {
            Log.d(TAG, "3A converged in ${elapsed}ms (af=$afState ae=$aeState)")
            captureState.set(State.PICTURE_TAKEN)
            val isTooDark = iso != null && iso > 1600
                && exposureTimeNs != null && exposureTimeNs > 33_333_333
            // Preview must always produce an image; only the scheduled path bails on dark scenes.
            if (!previewMode && (isTooDark or (aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED))) {
                updateNotification("Too dark! (iso=$iso exposure=$exposureTimeNs)")
                Log.d(TAG, "Too dark! (iso=$iso exposure=$exposureTimeNs ae=$aeState)")
                onCycleComplete()  // terminal for this cycle — release the busy flag
                return
            }
            shootImageAndShutdown(imageCapture)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // State machine trigger
    // ─────────────────────────────────────────────────────────────────────────

    private fun beginConvergenceWatch() {
        val afEnabled = SettingsManager.isAfEnabled(applicationContext)
        updateNotification(if (afEnabled) "Converging AF + AE…" else "Converging AE…")
        Log.d(TAG, "State → WAITING_3A (af=$afEnabled)")
        stateEnteredAt = System.currentTimeMillis()
        captureState.set(State.WAITING_3A)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Final capture
    // ─────────────────────────────────────────────────────────────────────────

    private fun shootImageAndShutdown(imageCapture: ImageCapture) {
        updateNotification("Capturing image…")
        Log.d(TAG, "State → PICTURE_TAKEN — firing takePicture")
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(service),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    if (previewMode) {
                        // Preview: hand the JPEG to the UI overlay; never upload.
                        val bytes = runCatching { imageProxyToBytes(image) }.getOrNull()
                        image.close()
                        shutdownCamera()
                        service.deliverPreview(bytes)
                    } else if (effectiveMode == SettingsManager.UploadMode.JPEG) {
                        val bytes = runCatching { imageProxyToBytes(image) }.getOrNull()
                        image.close()
                        shutdownCamera()
                        if (bytes != null) service.uploadJpeg(bytes)
                    } else if (effectiveMode == SettingsManager.UploadMode.AV1) {
                        val frame = try {
                            yuvConverter.toI420(image)
                        } catch (t: Throwable) {
                            Log.e(TAG, "YUV → I420 conversion failed", t)
                            updateNotification("AV1 conversion failed")
                            null
                        } finally {
                            image.close()
                            shutdownCamera()
                        }
                        frame?.let { service.submitAv1Frame(it) }
                    }
                }
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "takePicture failed: ${exc.message}", exc)
                    shutdownCamera()
                    if (previewMode) service.deliverPreview(null)
                }
            }
        )
    }


    private fun shutdownCamera() {
        // cameraProvider.unbindAll() and lifecycleRegistry must run on main thread
        runCatching { cameraProvider.unbindAll() }
        captureState.set(State.PREVIEW)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        onCycleComplete()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Image conversion
    // ─────────────────────────────────────────────────────────────────────────

    private fun imageProxyToBytes(image: ImageProxy): ByteArray {
        val buffer = image.planes[0].buffer
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }
}
