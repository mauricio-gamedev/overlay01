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
import android.util.DisplayMetrics
import android.view.WindowManager
import io.github.mauriciogamedev.overlay01.MainActivity
import io.github.mauriciogamedev.overlay01.capture.ScreenCaptureEngine
import io.github.mauriciogamedev.overlay01.encode.AacAudioEncoder
import io.github.mauriciogamedev.overlay01.encode.H264VideoEncoder
import io.github.mauriciogamedev.overlay01.stream.RtmpPublisher
import java.util.concurrent.atomic.AtomicBoolean

class CaptureService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stopping = AtomicBoolean(false)
    private var mediaProjection: MediaProjection? = null
    private var captureEngine: ScreenCaptureEngine? = null
    private var videoEncoder: H264VideoEncoder? = null
    private var audioEncoder: AacAudioEncoder? = null
    private var publisher: RtmpPublisher? = null

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            mediaProjection = null
            shutdownPipeline(stopProjection = false)
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            captureEngine?.resize(width, height)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdownPipeline(stopProjection = true)
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || mediaProjection != null) return START_NOT_STICKY

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, INVALID_RESULT)
        val resultData = intent.readProjectionData()
        val endpoint = intent.getStringExtra(EXTRA_RTMP_ENDPOINT)?.trim().orEmpty()
        val overlayUrl = intent.getStringExtra(EXTRA_OVERLAY_URL)
            ?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        val audioMode = if (intent.getBooleanExtra(EXTRA_USE_MICROPHONE, false)) {
            AacAudioEncoder.Mode.MICROPHONE
        } else {
            AacAudioEncoder.Mode.GAME
        }

        if (resultCode == INVALID_RESULT || resultData == null ||
            !(endpoint.startsWith("rtmp://") || endpoint.startsWith("rtmps://"))) {
            stopSelf()
            return START_NOT_STICKY
        }

        stopping.set(false)
        startProjectionForeground("Starting live…")

        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = checkNotNull(manager.getMediaProjection(resultCode, resultData)) {
                "Unable to create MediaProjection session"
            }.also {
                it.registerCallback(callback, mainHandler)
            }
            mediaProjection = projection

            val rtmp = RtmpPublisher(object : RtmpPublisher.Listener {
                override fun onStateChanged(message: String, connected: Boolean) {
                    updateNotification(message)
                }
                override fun onRequestKeyFrame() {
                    videoEncoder?.requestKeyFrame()
                }
            })
            publisher = rtmp

            val video = H264VideoEncoder(
                width = RtmpPublisher.VIDEO_WIDTH,
                height = RtmpPublisher.VIDEO_HEIGHT,
                bitrate = RtmpPublisher.VIDEO_BITRATE,
                fps = RtmpPublisher.VIDEO_FPS,
                onVideoConfig = { sps, pps -> rtmp.setVideoConfig(sps, pps) },
                onEncodedFrame = { data, info -> rtmp.sendVideo(data, info) },
                onError = { mainHandler.post { shutdownPipeline(true) } }
            )
            videoEncoder = video
            val encoderSurface = video.start()

            val spec = resolveCaptureSpec()
            captureEngine = ScreenCaptureEngine(
                context = applicationContext,
                projection = projection,
                initialWidth = spec.width,
                initialHeight = spec.height,
                densityDpi = spec.densityDpi,
                encoderSurface = encoderSurface,
                overlayUrl = overlayUrl,
                onError = { mainHandler.post { shutdownPipeline(true) } }
            ).also { it.start() }

            audioEncoder = AacAudioEncoder(
                projection = projection,
                preferredMode = audioMode,
                sampleRate = RtmpPublisher.AUDIO_SAMPLE_RATE,
                bitrate = RtmpPublisher.AUDIO_BITRATE,
                onEncodedFrame = { data, info -> rtmp.sendAudio(data, info) },
                onModeResolved = { mode ->
                    updateNotification(
                        if (mode == AacAudioEncoder.Mode.GAME) "Live starting · game audio" else "Live starting · microphone"
                    )
                },
                onError = { updateNotification("Live active · audio unavailable") }
            ).also { it.start() }

            rtmp.connect(endpoint)
        } catch (_: Throwable) {
            shutdownPipeline(stopProjection = true)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        shutdownPipeline(stopProjection = true)
        super.onDestroy()
    }

    private fun shutdownPipeline(stopProjection: Boolean) {
        if (!stopping.compareAndSet(false, true)) return
        runCatching { audioEncoder?.stop() }; audioEncoder = null
        runCatching { captureEngine?.stop() }; captureEngine = null
        runCatching { videoEncoder?.stop() }; videoEncoder = null
        runCatching { publisher?.disconnect() }; publisher = null

        val projection = mediaProjection
        mediaProjection = null
        if (projection != null) {
            runCatching { projection.unregisterCallback(callback) }
            if (stopProjection) runCatching { projection.stop() }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun resolveCaptureSpec(): CaptureSpec {
        val densityDpi = resources.displayMetrics.densityDpi.coerceAtLeast(1)
        val windowManager = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            CaptureSpec(bounds.width().coerceAtLeast(1), bounds.height().coerceAtLeast(1), densityDpi)
        } else {
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
            CaptureSpec(metrics.widthPixels.coerceAtLeast(1), metrics.heightPixels.coerceAtLeast(1), metrics.densityDpi.coerceAtLeast(1))
        }
    }

    private fun startProjectionForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        mainHandler.post {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Overlay01")
            .setContentText(text)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .build()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Live streaming", NotificationManager.IMPORTANCE_LOW)
        )
    }

    @Suppress("DEPRECATION")
    private fun Intent.readProjectionData(): Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
    } else {
        getParcelableExtra(EXTRA_RESULT_DATA)
    }

    private data class CaptureSpec(val width: Int, val height: Int, val densityDpi: Int)

    companion object {
        const val ACTION_START = "io.github.mauriciogamedev.overlay01.action.START_CAPTURE"
        const val ACTION_STOP = "io.github.mauriciogamedev.overlay01.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        const val EXTRA_OVERLAY_URL = "overlay_url"
        const val EXTRA_RTMP_ENDPOINT = "rtmp_endpoint"
        const val EXTRA_USE_MICROPHONE = "use_microphone"

        private const val INVALID_RESULT = Int.MIN_VALUE
        private const val CHANNEL_ID = "overlay01_capture"
        private const val NOTIFICATION_ID = 1001
    }
}
