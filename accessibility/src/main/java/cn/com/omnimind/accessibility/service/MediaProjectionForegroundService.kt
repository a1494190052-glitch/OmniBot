package cn.com.omnimind.accessibility.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import cn.com.omnimind.accessibility.action.ScreenCaptureManager
import cn.com.omnimind.baselib.util.OmniLog

/**
 * Android 10 (API 29) 起，MediaProjection 必须在声明为 FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION 的前台服务中使用。
 * Android 14 起必须先获得用户授权，再启动本服务并创建 MediaProjection。
 */
class MediaProjectionForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ON_MEDIA_PROJECTION_RESULT -> {
                handleMediaProjectionResult(intent)
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun handleMediaProjectionResult(intent: Intent) {
        val captureManager = ScreenCaptureManager.getInstance()
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        @Suppress("DEPRECATION")
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (data == null || resultCode != android.app.Activity.RESULT_OK) {
            captureManager.onMediaProjectionReady()
            stopSelf()
            return
        }

        try {
            startForegroundWithNotification()
            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            captureManager.setMediaProjection(mediaProjection)
            updateNotificationContent("屏幕捕获已开启")
        } catch (error: SecurityException) {
            OmniLog.e(TAG, "Unable to start MediaProjection session", error)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } catch (error: IllegalStateException) {
            OmniLog.e(TAG, "Invalid MediaProjection session state", error)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } finally {
            captureManager.onMediaProjectionReady()
        }
    }

    private fun startForegroundWithNotification() {
        val channelId = "media_projection_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "屏幕录制",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = buildNotification("正在启动屏幕捕获…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotificationContent(text: String) {
        val notification = buildNotification(text)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(contentText: String): Notification {
        val channelId = "media_projection_channel"
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("屏幕录制")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "MediaProjectionFgService"
        const val ACTION_ON_MEDIA_PROJECTION_RESULT = "cn.com.omnimind.accessibility.MEDIA_PROJECTION_RESULT"
        const val ACTION_STOP = "cn.com.omnimind.accessibility.MEDIA_PROJECTION_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val NOTIFICATION_ID = 2001
    }
}
