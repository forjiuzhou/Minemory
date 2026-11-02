package dev.owner.ownlog.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import dev.owner.ownlog.OwnLogApp
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine

class ScreenOcrService : Service() {
    private val imageThread = HandlerThread("ownlog-frames")
    private lateinit var imageHandler: Handler
    private val captureMutex = Mutex()
    private var projection: MediaProjection? = null
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            projection = null
            CaptureBridge.setProjectionActive(false)
            CaptureReminder.sync(this@ScreenOcrService)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        imageThread.start()
        imageHandler = Handler(imageThread.looper)
        CaptureBridge.attachProjection(this)
        Notifier.ensureChannel(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val notification = Notifier.ongoing(
                this,
                "自记正在按需截屏",
                "只在需要 OCR 的应用里，或界面没有可读文字时，偶尔截一张并在本机识别。",
            )
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    Notifier.OCR_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                )
            } else {
                startForeground(Notifier.OCR_ID, notification)
            }
        } catch (error: Exception) {
            Log.w(TAG, "foreground start failed: ${error.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        val code = intent?.getIntExtra(EXTRA_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val data = resultData(intent)
        if (projection == null && code != Int.MIN_VALUE && data != null) {
            try {
                val manager = getSystemService(MediaProjectionManager::class.java)
                val created = manager.getMediaProjection(code, data)
                created.registerCallback(callback, Handler(Looper.getMainLooper()))
                projection = created
                CaptureBridge.setProjectionActive(true)
                CaptureReminder.sync(this)
                (application as? OwnLogApp)?.appScope?.launch {
                    (application as OwnLogApp).settings.setCollectorError(null)
                }
            } catch (error: Exception) {
                Log.w(TAG, "media projection failed: ${error.message}")
                (application as? OwnLogApp)?.appScope?.launch {
                    (application as OwnLogApp).settings.setCollectorError("屏幕采集授权失败，请再点一次授权截屏。")
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        CaptureBridge.detachProjection(this)
        CaptureReminder.sync(this)
        releaseProjection()
        imageThread.quitSafely()
        super.onDestroy()
    }

    internal suspend fun captureFrame(): Bitmap? {
        val current = projection ?: return null
        return captureMutex.withLock {
            suspendCancellableCoroutine { continuation ->
                val metrics = resources.displayMetrics
                val width = metrics.widthPixels.coerceAtLeast(1)
                val height = metrics.heightPixels.coerceAtLeast(1)
                val dpi = metrics.densityDpi.coerceAtLeast(1)
                val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
                var display: VirtualDisplay? = null
                val finished = AtomicBoolean(false)
                fun finish(bitmap: Bitmap?): Boolean {
                    if (!finished.compareAndSet(false, true)) return false
                    try {
                        display?.release()
                    } catch (_: Exception) {
                    }
                    try {
                        reader.close()
                    } catch (_: Exception) {
                    }
                    if (continuation.isActive) continuation.resume(bitmap) else bitmap?.recycle()
                    return true
                }
                try {
                    display = current.createVirtualDisplay(
                        "ownlog-ocr",
                        width,
                        height,
                        dpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        reader.surface,
                        null,
                        imageHandler,
                    )
                } catch (error: Exception) {
                    Log.w(TAG, "virtual display failed: ${error.message}")
                    finish(null)
                    return@suspendCancellableCoroutine
                }
                var seen = 0
                reader.setOnImageAvailableListener({ imageReader ->
                    val image = try {
                        imageReader.acquireLatestImage()
                    } catch (_: Exception) {
                        null
                    } ?: return@setOnImageAvailableListener
                    seen += 1
                    if (seen < 2) {
                        image.close()
                        return@setOnImageAvailableListener
                    }
                    val bitmap = try {
                        image.toBitmap(width, height)
                    } catch (_: Exception) {
                        null
                    } finally {
                        image.close()
                    }
                    if (bitmap != null && !finish(bitmap)) bitmap.recycle()
                    if (bitmap == null) finish(null)
                }, imageHandler)
                continuation.invokeOnCancellation { finish(null) }
                imageHandler.postDelayed({ finish(null) }, 2_500)
            }
        }
    }

    private fun releaseProjection() {
        val current = projection ?: return
        projection = null
        CaptureBridge.setProjectionActive(false)
        try {
            current.unregisterCallback(callback)
        } catch (_: Exception) {
        }
        try {
            current.stop()
        } catch (_: Exception) {
        }
    }

    private fun resultData(intent: Intent?): Intent? {
        intent ?: return null
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA) as? Intent
        }
    }

    companion object {
        const val EXTRA_CODE = "result_code"
        const val EXTRA_DATA = "result_data"
        private const val TAG = "OwnLog"

        private fun Image.toBitmap(width: Int, height: Int): Bitmap {
            val plane = planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val paddedWidth = width + (rowPadding / pixelStride).coerceAtLeast(0)
            val full = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            full.copyPixelsFromBuffer(buffer)
            if (paddedWidth == width) return full
            val cropped = Bitmap.createBitmap(full, 0, 0, width, height)
            full.recycle()
            return cropped
        }
    }
}
