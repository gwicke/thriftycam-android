package com.camerauploader

/**
 * One-shot, same-process hand-off of a preview JPEG from the capture service back
 * to the settings UI. The capture runs in [CameraUploaderService] (which owns the
 * camera); the Activity registers [onResult] before requesting a preview.
 *
 * [deliver] clears the callback before invoking it, so a result is delivered at
 * most once and no Activity reference is retained afterwards.
 */
object PreviewBus {
    @Volatile var onResult: ((ByteArray?) -> Unit)? = null

    fun deliver(jpeg: ByteArray?) {
        val cb = onResult
        onResult = null
        cb?.invoke(jpeg)
    }
}
