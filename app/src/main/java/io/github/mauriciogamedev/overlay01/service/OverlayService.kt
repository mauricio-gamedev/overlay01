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
import io.github.mauriciogamedev.overlay01.MainActivity

class OverlayService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private val preferences by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private var overlayView: WebView? = null
    private var currentUrl: String? = null
    private var explicitStop = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        WebView.setWebContentsDebuggingEnabled(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                explicitStop = true
                preferences.edit().putBoolean(PREF_VISIBLE, false).apply()
                removeOverlay()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val requestedUrl = intent?.getStringExtra(EXTRA_URL)
            ?.trim()
            ?.takeIf(::isSupportedUrl)

        val savedUrl = preferences.getString(PREF_URL, "")
            ?.trim()
            ?.takeIf(::isSupportedUrl)

        val url = requestedUrl ?: savedUrl
        val shouldRestore = intent == null && preferences.getBoolean(PREF_VISIBLE, false)
        val shouldShow = intent?.action == ACTION_SHOW || intent?.action == ACTION_UPDATE || shouldRestore

        if (!shouldShow || url == null || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        explicitStop = false
        preferences.edit()
            .putString(PREF_URL, url)
            .putBoolean(PREF_VISIBLE, true)
            .apply()

        startOverlayForeground()
        showOrUpdateOverlay(url)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeOverlay()
        if (explicitStop) {
            preferences.edit().putBoolean(PREF_VISIBLE, false).apply()
        }
        super.onDestroy()
    }

    private fun showOrUpdateOverlay(url: String) {
        if (currentUrl == url && overlayView != null) {
            overlayView?.reload()
            updateNotification("Overlay ativa · layout atualizado")
            return
        }

        removeOverlay()
        currentUrl = url

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
                    updateNotification("Overlay ativa · toque livre no jogo")
                }

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail
                ): Boolean {
                    if (view === overlayView) {
                        runCatching { windowManager.removeViewImmediate(view) }
                        overlayView = null
                        runCatching { view.destroy() }

                        val urlToRestore = currentUrl
                        if (urlToRestore != null && preferences.getBoolean(PREF_VISIBLE, false)) {
                            mainHandler.postDelayed(
                                { showOrUpdateOverlay(urlToRestore) },
                                RENDERER_RESTART_DELAY_MS
                            )
                        }
                    }
                    return true
                }
            }
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
            windowManager.addView(webView, params)
            overlayView = webView
            webView.loadUrl(url)
        } catch (error: Throwable) {
            runCatching { webView.destroy() }
            overlayView = null
            currentUrl = null
            preferences.edit().putBoolean(PREF_VISIBLE, false).apply()
            updateNotification("Falha ao abrir overlay")
            stopSelf()
        }
    }

    private fun removeOverlay() {
        mainHandler.removeCallbacksAndMessages(null)
        val view = overlayView
        overlayView = null
        currentUrl = null

        if (view != null) {
            runCatching { windowManager.removeViewImmediate(view) }
            runCatching { view.stopLoading() }
            runCatching { view.loadUrl("about:blank") }
            runCatching { view.clearHistory() }
            runCatching { view.destroy() }
        }
    }

    private fun startOverlayForeground() {
        val notification = buildNotification("Overlay ativa · toque livre no jogo")
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

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
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
            .addAction(Notification.Action.Builder(null, "Desativar", stopIntent).build())
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Overlay ativa",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Mantém a overlay web ativa durante o jogo"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun isSupportedUrl(raw: String): Boolean {
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return false
        return uri.scheme?.lowercase() == "https" && !uri.host.isNullOrBlank()
    }

    companion object {
        const val ACTION_SHOW = "io.github.mauriciogamedev.overlay01.action.SHOW_OVERLAY"
        const val ACTION_UPDATE = "io.github.mauriciogamedev.overlay01.action.UPDATE_OVERLAY"
        const val ACTION_STOP = "io.github.mauriciogamedev.overlay01.action.STOP_OVERLAY"
        const val EXTRA_URL = "overlay_url"

        const val PREFS_NAME = "overlay01_settings"
        const val PREF_URL = "overlay_url"
        const val PREF_LOCKED = "overlay_locked"
        const val PREF_VISIBLE = "overlay_visible"

        private const val CHANNEL_ID = "overlay01_active"
        private const val NOTIFICATION_ID = 1001
        private const val RENDERER_RESTART_DELAY_MS = 600L
    }
}
