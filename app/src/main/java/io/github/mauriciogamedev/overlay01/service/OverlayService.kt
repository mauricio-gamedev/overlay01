package io.github.mauriciogamedev.overlay01.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import io.github.mauriciogamedev.overlay01.MainActivity

class OverlayService : Service() {

    private data class SlotRuntime(
        val index: Int,
        var view: WebView? = null,
        var currentUrl: String? = null,
        var scalePercent: Int = DEFAULT_SCALE_PERCENT,
        var xPercent: Int = DEFAULT_POSITION_PERCENT,
        var yPercent: Int = DEFAULT_POSITION_PERCENT,
        var restartRunnable: Runnable? = null
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private val preferences by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private val slots = arrayOf(SlotRuntime(1), SlotRuntime(2))
    private var overlayRoot: FrameLayout? = null
    private var explicitStop = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        WebView.setWebContentsDebuggingEnabled(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            explicitStop = true
            preferences.edit()
                .putBoolean(prefEnabled(1), false)
                .putBoolean(prefEnabled(2), false)
                .apply()
            removeAllOverlays()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val restoring = intent == null
        val shouldApply = intent?.action == ACTION_APPLY || restoring
        if (!shouldApply || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        val activeConfigs = (1..2).mapNotNull(::readConfig).filter { it.enabled }
        if (activeConfigs.isEmpty()) {
            removeAllOverlays()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        explicitStop = false
        startOverlayForeground()
        ensureOverlayRoot()

        for (config in activeConfigs) {
            syncSlot(config)
        }

        for (slot in slots) {
            if (activeConfigs.none { it.index == slot.index }) {
                removeSlot(slot)
            }
        }

        updateNotification(activeConfigs.size)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeAllOverlays()
        super.onDestroy()
    }

    private data class SlotConfig(
        val index: Int,
        val enabled: Boolean,
        val url: String,
        val scalePercent: Int,
        val xPercent: Int,
        val yPercent: Int
    )

    private fun readConfig(index: Int): SlotConfig? {
        val enabled = preferences.getBoolean(prefEnabled(index), false)
        val url = preferences.getString(prefUrl(index), "")
            ?.trim()
            ?.takeIf(::isSupportedUrl)
            ?: return if (enabled) null else SlotConfig(
                index,
                false,
                "",
                DEFAULT_SCALE_PERCENT,
                DEFAULT_POSITION_PERCENT,
                DEFAULT_POSITION_PERCENT
            )

        return SlotConfig(
            index = index,
            enabled = enabled,
            url = url,
            scalePercent = preferences
                .getInt(prefScale(index), DEFAULT_SCALE_PERCENT)
                .coerceIn(MIN_SCALE_PERCENT, MAX_SCALE_PERCENT),
            xPercent = preferences
                .getInt(prefX(index), DEFAULT_POSITION_PERCENT)
                .coerceIn(MIN_POSITION_PERCENT, MAX_POSITION_PERCENT),
            yPercent = preferences
                .getInt(prefY(index), DEFAULT_POSITION_PERCENT)
                .coerceIn(MIN_POSITION_PERCENT, MAX_POSITION_PERCENT)
        )
    }

    private fun ensureOverlayRoot() {
        if (overlayRoot != null) return

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            clipChildren = false
            clipToPadding = false
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        try {
            windowManager.addView(root, params)
            overlayRoot = root
        } catch (_: Throwable) {
            stopSelf()
        }
    }

    private fun syncSlot(config: SlotConfig) {
        val slot = slots[config.index - 1]
        slot.restartRunnable?.let(mainHandler::removeCallbacks)
        slot.restartRunnable = null

        slot.scalePercent = config.scalePercent
        slot.xPercent = config.xPercent
        slot.yPercent = config.yPercent

        val existing = slot.view
        if (existing != null && slot.currentUrl == config.url) {
            applyTransform(existing, slot)
            return
        }

        removeSlot(slot, clearCurrentUrl = false)
        slot.currentUrl = config.url

        val root = overlayRoot ?: return
        val themedContext = ContextThemeWrapper(
            this,
            android.R.style.Theme_Material_Light_NoActionBar
        )

        val webView = WebView(themedContext).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                mediaPlaybackRequiresUserGesture = false
                builtInZoomControls = false
                displayZoomControls = false
                setSupportZoom(false)
                useWideViewPort = true
                loadWithOverviewMode = true
                loadsImagesAutomatically = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setGeolocationEnabled(false)
                textZoom = 100
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val target = request?.url ?: return true
                    return target.scheme?.lowercase() != "https"
                }

                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript(
                        "(function(){" +
                            "document.documentElement.style.background='transparent';" +
                            "if(document.body){document.body.style.background='transparent';}" +
                        "})()",
                        null
                    )
                    applyTransform(view, slot)
                }

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail
                ): Boolean {
                    if (view === slot.view) {
                        runCatching { overlayRoot?.removeView(view) }
                        slot.view = null
                        runCatching { view.destroy() }
                        scheduleSlotRestart(slot)
                    }
                    return true
                }
            }
        }

        try {
            root.addView(
                webView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            slot.view = webView
            applyTransform(webView, slot)
            webView.loadUrl(config.url)
        } catch (_: Throwable) {
            runCatching { root.removeView(webView) }
            runCatching { webView.destroy() }
            slot.view = null
        }
    }

    private fun scheduleSlotRestart(slot: SlotRuntime) {
        val runnable = Runnable {
            slot.restartRunnable = null
            val config = readConfig(slot.index)
            if (config?.enabled == true && Settings.canDrawOverlays(this)) {
                ensureOverlayRoot()
                syncSlot(config)
            }
        }
        slot.restartRunnable = runnable
        mainHandler.postDelayed(runnable, RENDERER_RESTART_DELAY_MS)
    }

    private fun applyTransform(view: WebView, slot: SlotRuntime) {
        val scale = slot.scalePercent
            .coerceIn(MIN_SCALE_PERCENT, MAX_SCALE_PERCENT) / 100f
        val x = slot.xPercent
            .coerceIn(MIN_POSITION_PERCENT, MAX_POSITION_PERCENT)
        val y = slot.yPercent
            .coerceIn(MIN_POSITION_PERCENT, MAX_POSITION_PERCENT)
        val (screenWidth, screenHeight) = displaySize()

        view.pivotX = 0f
        view.pivotY = 0f
        view.scaleX = scale
        view.scaleY = scale
        view.translationX = screenWidth * (x / 100f)
        view.translationY = screenHeight * (y / 100f)
    }

    private fun displaySize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            resources.displayMetrics.widthPixels to resources.displayMetrics.heightPixels
        }
    }

    private fun removeSlot(slot: SlotRuntime, clearCurrentUrl: Boolean = true) {
        slot.restartRunnable?.let(mainHandler::removeCallbacks)
        slot.restartRunnable = null

        val view = slot.view
        slot.view = null
        if (clearCurrentUrl) slot.currentUrl = null

        if (view != null) {
            runCatching { overlayRoot?.removeView(view) }
            runCatching { view.stopLoading() }
            runCatching { view.loadUrl("about:blank") }
            runCatching { view.clearHistory() }
            runCatching { view.destroy() }
        }
    }

    private fun removeAllOverlays() {
        for (slot in slots) removeSlot(slot)

        val root = overlayRoot
        overlayRoot = null
        if (root != null) {
            runCatching { windowManager.removeViewImmediate(root) }
        }
    }

    private fun startOverlayForeground() {
        val notification = buildNotification("Overlays ativas · toque livre no jogo")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(activeCount: Int) {
        val text = if (activeCount == 1) {
            "1 overlay ativa · toque livre no jogo"
        } else {
            "$activeCount overlays ativas · toque livre no jogo"
        }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_online)
            .setContentTitle("Overlay01")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "Desativar todas", stopIntent).build())
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Overlay ativa",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Mantém as overlays web ativas durante o jogo"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun isSupportedUrl(raw: String): Boolean {
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return false
        return uri.scheme?.lowercase() == "https" && !uri.host.isNullOrBlank()
    }

    companion object {
        const val ACTION_APPLY = "io.github.mauriciogamedev.overlay01.action.APPLY_OVERLAYS"
        const val ACTION_STOP = "io.github.mauriciogamedev.overlay01.action.STOP_OVERLAYS"

        const val PREFS_NAME = "overlay01_settings"

        fun prefUrl(slot: Int) = "overlay_${slot}_url"
        fun prefEnabled(slot: Int) = "overlay_${slot}_enabled"
        fun prefLocked(slot: Int) = "overlay_${slot}_locked"
        fun prefScale(slot: Int) = "overlay_${slot}_scale_percent"
        fun prefX(slot: Int) = "overlay_${slot}_x_percent"
        fun prefY(slot: Int) = "overlay_${slot}_y_percent"

        const val LEGACY_PREF_URL = "overlay_url"
        const val LEGACY_PREF_LOCKED = "overlay_locked"
        const val LEGACY_PREF_VISIBLE = "overlay_visible"
        const val LEGACY_PREF_SCALE = "overlay_scale_percent"
        const val PREF_MIGRATED_V4 = "settings_migrated_v4"

        const val MIN_SCALE_PERCENT = 40
        const val MAX_SCALE_PERCENT = 100
        const val DEFAULT_SCALE_PERCENT = 100

        const val MIN_POSITION_PERCENT = -50
        const val MAX_POSITION_PERCENT = 50
        const val DEFAULT_POSITION_PERCENT = 0

        private const val CHANNEL_ID = "overlay01_active"
        private const val NOTIFICATION_ID = 1001
        private const val RENDERER_RESTART_DELAY_MS = 600L
    }
}
