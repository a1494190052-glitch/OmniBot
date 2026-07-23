package cn.com.omnimind.accessibility.action

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import BaseApplication
import cn.com.omnimind.accessibility.service.MediaProjectionForegroundService
import cn.com.omnimind.baselib.permission.ServiceRequest
import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class ScreenCaptureManager private constructor() {

    companion object {
        private const val TAG = "ScreenCaptureManager"
        private const val VIRTUAL_DISPLAY_NAME = "screen_capture"
        const val REQUEST_MEDIA_PROJECTION = 1001

        @Volatile
        private var instance: ScreenCaptureManager? = null

        fun getInstance(): ScreenCaptureManager {
            return instance ?: synchronized(this) {
                instance ?: ScreenCaptureManager().also { instance = it }
            }
        }

        private val screenshotMutex = Mutex()

        /** API 29+ 时由前台服务设置 MediaProjection 后回调，用于恢复挂起的协程 */
        @Volatile
        internal var pendingMediaProjectionContinuation: ((Boolean) -> Unit)? = null

        /** 专用线程 + Handler，供 ImageReader 回调使用，避免 API 29 上主线程 Looper 导致回调不触发 */
        private var imageHandlerThread: HandlerThread? = null
        private var imageHandler: Handler? = null

        private fun getImageHandler(): Handler {
            if (imageHandler == null) {
                imageHandlerThread = HandlerThread("ScreenCaptureImage").apply { start() }
                imageHandler = Handler(imageHandlerThread!!.looper)
            }
            return imageHandler!!
        }

        fun clearImageHandler() {
            imageHandlerThread?.quitSafely()
            imageHandlerThread = null
            imageHandler = null
        }
    }

    private val projectionLock = Any()

    @Volatile
    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureWidth: Int = 0
    private var captureHeight: Int = 0
    private var captureDensityDpi: Int = 0

    /**
     * 由 [MediaProjectionForegroundService] 在获得 MediaProjection 后调用（仅 API 29+）
     */
    fun setMediaProjection(projection: MediaProjection?) {
        if (projection == null) return
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                handleProjectionStopped(projection)
            }
        }
        projection.registerCallback(callback, getImageHandler())
        synchronized(projectionLock) {
            mediaProjection = projection
            mediaProjectionCallback = callback
        }
    }

    /**
     * 由 [MediaProjectionForegroundService] 在设置完 MediaProjection 或用户拒绝后调用，恢复挂起的协程
     */
    fun onMediaProjectionReady() {
        pendingMediaProjectionContinuation?.let { cont ->
            pendingMediaProjectionContinuation = null
            cont(hasPermission())
        }
    }

    /**
     * 在 Activity 中调用，拉起系统截屏授权弹窗。
     * Android 14 及以上必须先获得用户授权，再启动 mediaProjection 类型前台服务。
     */
    suspend fun requestScreenCapturePermission(): Boolean {
        if (hasPermission()) return true
        return suspendCancellableCoroutine { cont ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val ctx = BaseApplication.instance
                val completion: (Boolean) -> Unit = { granted ->
                    if (cont.isActive) cont.resume(granted)
                }
                pendingMediaProjectionContinuation = completion
                cont.invokeOnCancellation {
                    if (pendingMediaProjectionContinuation === completion) {
                        pendingMediaProjectionContinuation = null
                    }
                }
                CoroutineScope(Dispatchers.Main.immediate).launch {
                    if (!cont.isActive) return@launch
                    ServiceRequest.requestService(ctx, Context.MEDIA_PROJECTION_SERVICE) { resultCode, data ->
                        if (resultCode != Activity.RESULT_OK || data == null) {
                            completePendingPermissionRequest(false)
                            return@requestService
                        }
                        try {
                            ContextCompat.startForegroundService(
                                ctx,
                                Intent(ctx, MediaProjectionForegroundService::class.java)
                                    .setAction(MediaProjectionForegroundService.ACTION_ON_MEDIA_PROJECTION_RESULT)
                                    .putExtra(MediaProjectionForegroundService.EXTRA_RESULT_CODE, resultCode)
                                    .putExtra(MediaProjectionForegroundService.EXTRA_RESULT_DATA, data)
                            )
                        } catch (error: RuntimeException) {
                            OmniLog.e(TAG, "Unable to start MediaProjection foreground service", error)
                            completePendingPermissionRequest(false)
                        }
                    }
                }
            } else {
                ServiceRequest.requestService(
                    BaseApplication.instance,
                    Context.MEDIA_PROJECTION_SERVICE
                ) { resultCode, data ->
                    if (resultCode != Activity.RESULT_OK || data == null) {
                        cont.resume(false)
                        return@requestService
                    }
                    val mpm = BaseApplication.instance.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    setMediaProjection(mpm.getMediaProjection(resultCode, data))
                    cont.resume(hasPermission())
                }
            }
        }
    }

    fun hasPermission(): Boolean {
        return mediaProjection != null
    }

    /**
     * 截一张图，回调 Bitmap（在 IO/子线程回调）。
     * API 29 上部分机型 onImageAvailable 不触发，改为轮询 acquireLatestImage() 取帧。
     */
    suspend fun captureOnce(): Bitmap? {
        val projection = mediaProjection ?: return null
        return screenshotMutex.withLock {
            withTimeoutOrNull(2000L) {
                suspendCancellableCoroutine<Bitmap?> { cont ->
                    val session = try {
                        ensureCaptureSession(projection)
                    } catch (error: SecurityException) {
                        OmniLog.e(TAG, "Unable to create MediaProjection capture session", error)
                        cont.resume(null)
                        return@suspendCancellableCoroutine
                    } catch (error: IllegalStateException) {
                        OmniLog.e(TAG, "Invalid MediaProjection capture session", error)
                        cont.resume(null)
                        return@suspendCancellableCoroutine
                    }
                    if (session == null) {
                        cont.resume(null)
                        return@suspendCancellableCoroutine
                    }

                    var pollJob: Job? = null
                    cont.invokeOnCancellation {
                        pollJob?.cancel()
                    }

                    pollJob = CoroutineScope(Dispatchers.Default).launch {
                        delay(150)
                        var elapsed = 150L
                        val pollInterval = 80L
                        val timeout = 2000L
                        while (elapsed < timeout && !cont.isCancelled) {
                            delay(pollInterval)
                            elapsed += pollInterval
                            if (!cont.isCancelled) {
                                val image = try {
                                    session.imageReader.acquireLatestImage()
                                } catch (e: Exception) {
                                    null
                                }
                                if (image != null) {
                                    var bitmap: Bitmap? = null
                                    try {
                                        val plane = image.planes[0]
                                        val buffer = plane.buffer
                                        val pixelStride = plane.pixelStride
                                        val rowStride = plane.rowStride
                                        val rowPadding = rowStride - pixelStride * session.width
                                        val rowPixels = session.width + rowPadding / pixelStride
                                        bitmap = createBitmap(
                                            rowPixels,
                                            session.height,
                                            Bitmap.Config.ARGB_8888
                                        )
                                        bitmap.copyPixelsFromBuffer(buffer)
                                        if (rowPixels != session.width) {
                                            bitmap = Bitmap.createBitmap(
                                                bitmap,
                                                0,
                                                0,
                                                session.width,
                                                session.height
                                            )
                                        }
                                    } catch (e: Exception) {
                                        OmniLog.e(TAG, "Unable to decode MediaProjection frame", e)
                                    } finally {
                                        image.close()
                                    }
                                    if (cont.isActive) cont.resume(bitmap)
                                    return@launch
                                }
                            }
                        }
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        }
    }

    /**
     * 释放全部资源（比如在退出时调用）。
     * API 29+ 会同时停止 MediaProjection 前台服务，否则通知会一直存在。
     */
    fun release() {
        val projection: MediaProjection?
        val callback: MediaProjection.Callback?
        synchronized(projectionLock) {
            releaseCaptureSessionLocked()
            projection = mediaProjection
            callback = mediaProjectionCallback
            mediaProjection = null
            mediaProjectionCallback = null
        }
        if (projection != null && callback != null) {
            projection.unregisterCallback(callback)
        }
        projection?.stop()
        completePendingPermissionRequest(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            BaseApplication.instance.stopService(
                Intent(BaseApplication.instance, MediaProjectionForegroundService::class.java)
            )
        }
        clearImageHandler()
    }

    private fun ensureCaptureSession(projection: MediaProjection): CaptureSession? {
        val windowManager =
            BaseApplication.instance.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val display = BaseApplication.instance.display ?: windowManager.defaultDisplay
            display?.getRealMetrics(metrics)
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
        }
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val densityDpi = metrics.densityDpi
        if (width <= 0 || height <= 0 || densityDpi <= 0) return null

        synchronized(projectionLock) {
            if (mediaProjection !== projection) return null
            val sizeChanged = width != captureWidth || height != captureHeight ||
                densityDpi != captureDensityDpi
            if (virtualDisplay == null) {
                val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
                imageReader = reader
                virtualDisplay = projection.createVirtualDisplay(
                    VIRTUAL_DISPLAY_NAME,
                    width,
                    height,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    null
                )
                captureWidth = width
                captureHeight = height
                captureDensityDpi = densityDpi
            } else if (sizeChanged) {
                val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
                virtualDisplay?.setSurface(null)
                imageReader?.close()
                imageReader = reader
                virtualDisplay?.resize(width, height, densityDpi)
                virtualDisplay?.setSurface(reader.surface)
                captureWidth = width
                captureHeight = height
                captureDensityDpi = densityDpi
            }
            val reader = imageReader ?: return null
            return CaptureSession(reader, captureWidth, captureHeight)
        }
    }

    private fun handleProjectionStopped(projection: MediaProjection) {
        synchronized(projectionLock) {
            if (mediaProjection !== projection) return
            releaseCaptureSessionLocked()
            mediaProjection = null
            mediaProjectionCallback = null
        }
        completePendingPermissionRequest(false)
        BaseApplication.instance.stopService(
            Intent(BaseApplication.instance, MediaProjectionForegroundService::class.java)
        )
    }

    private fun releaseCaptureSessionLocked() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        captureWidth = 0
        captureHeight = 0
        captureDensityDpi = 0
    }

    private fun completePendingPermissionRequest(granted: Boolean) {
        val completion = pendingMediaProjectionContinuation ?: return
        pendingMediaProjectionContinuation = null
        completion(granted)
    }

    private data class CaptureSession(
        val imageReader: ImageReader,
        val width: Int,
        val height: Int,
    )

}
