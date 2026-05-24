package com.carnet.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager

/**
 * Owns a MediaProjection + VirtualDisplay + ImageReader chain and exposes the latest
 * captured screen as a Bitmap for the OverlayEffect compositor.
 *
 * Prototype-grade: the ImageReader -> Bitmap copy each frame is expensive and won't
 * hold 30fps for a full-screen overlay. Production should bind the MediaProjection
 * Surface into a GL-based CameraEffect and avoid the bitmap copy entirely.
 */
class ScreenCaptureSource(
    context: Context,
    private val projection: MediaProjection,
) {
    @Volatile var latestBitmap: Bitmap? = null
        private set

    private val appContext = context.applicationContext
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var thread: HandlerThread? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped externally")
            release()
        }
    }

    fun start() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getRealMetrics(metrics)
        val w = (metrics.widthPixels / SCALE).coerceAtLeast(1)
        val h = (metrics.heightPixels / SCALE).coerceAtLeast(1)
        val density = metrics.densityDpi

        val handlerThread = HandlerThread("Carnet-ScreenCapture").apply { start() }
        val handler = Handler(handlerThread.looper)
        thread = handlerThread

        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r -> drainTo(r) }, handler)
        imageReader = reader

        projection.registerCallback(projectionCallback, handler)
        // Flags = 0 on purpose. AUTO_MIRROR registers the virtual display as a real
        // mirror of the main display, which trips a system display-configuration change
        // — CameraX listens for that and rebinds its use cases, briefly disconnecting
        // the camera client and killing any active VideoCapture recording with
        // ERROR_SOURCE_INACTIVE (code 4). MediaProjection populates the surface itself,
        // so AUTO_MIRROR is redundant here.
        virtualDisplay = projection.createVirtualDisplay(
            "Carnet-Screen",
            w, h, density,
            0,
            reader.surface,
            null,
            handler,
        )
        Log.i(TAG, "screen capture started: ${w}x${h} @ $density dpi")
    }

    private fun drainTo(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPaddingPx = (rowStride - pixelStride * image.width) / pixelStride
            val padded = Bitmap.createBitmap(
                image.width + rowPaddingPx,
                image.height,
                Bitmap.Config.ARGB_8888,
            )
            padded.copyPixelsFromBuffer(plane.buffer)
            val frame = if (rowPaddingPx == 0) {
                padded
            } else {
                Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            }
            // Don't recycle the previous bitmap — the overlay thread may still be reading it
            // when we swap. Let GC reclaim once no compositor frame holds a reference.
            latestBitmap = frame
        } catch (t: Throwable) {
            Log.w(TAG, "drainTo failed", t)
        } finally {
            image.close()
        }
    }

    fun release() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        runCatching { projection.unregisterCallback(projectionCallback) }
        runCatching { projection.stop() }
        thread?.quitSafely()
        thread = null
        latestBitmap = null
    }

    companion object {
        private const val TAG = "Carnet"
        // Downsample to keep the per-frame bitmap copy manageable in this Canvas-based
        // prototype. A ~1/3 of a 2640x1080 Pixel 9a screen is still ample for a PiP overlay
        // on a 1080p video frame, and roughly 9× cheaper than capturing native resolution.
        private const val SCALE = 3
    }
}
