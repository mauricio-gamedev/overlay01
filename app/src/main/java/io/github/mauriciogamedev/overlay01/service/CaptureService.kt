package io.github.mauriciogamedev.overlay01.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import io.github.mauriciogamedev.overlay01.MainActivity

class CaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            mediaProjection = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopProjection()
            return START_NOT_STICKY
        }

        if (intent?.action != ACTION_START || mediaProjection != null) {
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, ActivityResultCodes.INVALID)
        val resultData = intent.readProjectionData()

        if (resultCode == ActivityResultCodes.INVALID || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startProjectionForeground()

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = manager.getMediaProjection(resultCode, resultData).also {
            it.registerCallback(callback, Handler(Looper.getMainLooper()))
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopProjection()
        super.onDestroy()
    }

    private fun stopProjection() {
        val projection = mediaProjection
        mediaProjection = null
        if (projection != null) {
            runCatching { projection.unregisterCallback(callback) }
            runCatching { projection.stop() }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startProjectionForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Overlay01")
            .setContentText("Screen capture session is active")
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Live capture",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    @Suppress("DEPRECATION")
    private fun Intent.readProjectionData(): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            getParcelableExtra(EXTRA_RESULT_DATA)
        }
    }

    private object ActivityResultCodes {
        const val INVALID = Int.MIN_VALUE
    }

    companion object {
        const val ACTION_START = "io.github.mauriciogamedev.overlay01.action.START_CAPTURE"
        const val ACTION_STOP = "io.github.mauriciogamedev.overlay01.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"

        private const val CHANNEL_ID = "overlay01_capture"
        private const val NOTIFICATION_ID = 1001
    }
}
