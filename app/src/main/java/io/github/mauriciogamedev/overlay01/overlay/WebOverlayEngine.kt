package io.github.mauriciogamedev.overlay01.overlay

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Renders one URL overlay into an off-screen virtual display backed by an
 * external OpenGL texture. The WebView never appears over the game display,
 * therefore it cannot consume gameplay touches.
 */
class WebOverlayEngine(
    context: Context,
    private val glHandler: Handler,
    private val width: Int,
    private val height: Int,
    private val densityDpi: Int,
    private val onError: (Throwable) -> Unit = {}
) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val frameAvailable = AtomicBoolean(false)

    private val textureTransform = FloatArray(16)
    private var hasFrame = false

    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var webView: WebView? = null

    val externalTextureId: Int
        get() = textureId

    val hasUsableFrame: Boolean
        get() = hasFrame

    fun initializeGl(url: String) {
        if (url.isBlank()) return
        check(textureId == 0) { "WebOverlayEngine already initialized" }

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        check(textureId != 0) { "Unable to allocate web overlay texture" }

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        val overlayTexture = SurfaceTexture(textureId).apply {
            setDefaultBufferSize(width, height)
            setOnFrameAvailableListener({
                frameAvailable.set(true)
            }, glHandler)
        }
        surfaceTexture = overlayTexture
        surface = Surface(overlayTexture)

        mainHandler.post {
            runCatching { createWebDisplay(url) }
                .onFailure(onError)
        }
    }

    /** Must be called on the GL thread with the owning EGL context current. */
    fun updateTextureIfNeeded(): Boolean {
        val texture = surfaceTexture ?: return false
        if (!frameAvailable.getAndSet(false)) return hasFrame

        texture.updateTexImage()
        texture.getTransformMatrix(textureTransform)
        hasFrame = true
        return true
    }

    fun textureMatrix(): FloatArray = textureTransform

    fun releaseGl() {
        mainHandler.post {
            runCatching { webView?.stopLoading() }
            runCatching { presentation?.dismiss() }
            runCatching { webView?.destroy() }
            runCatching { virtualDisplay?.release() }

            webView = null
            presentation = null
            virtualDisplay = null
        }

        runCatching { surfaceTexture?.setOnFrameAvailableListener(null) }
        runCatching { surface?.release() }
        surface = null
        runCatching { surfaceTexture?.release() }
        surfaceTexture = null

        if (textureId != 0) {
            runCatching { GLES20.glDeleteTextures(1, intArrayOf(textureId), 0) }
            textureId = 0
        }

        hasFrame = false
        frameAvailable.set(false)
    }

    private fun createWebDisplay(url: String) {
        val targetSurface = checkNotNull(surface) { "Overlay surface was released" }
        val displayManager = appContext.getSystemService(DisplayManager::class.java)

        val displayFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION

        val vd = displayManager.createVirtualDisplay(
            "Overlay01-WebOverlay",
            width,
            height,
            densityDpi,
            targetSurface,
            displayFlags
        ) ?: error("Unable to create web overlay virtual display")

        virtualDisplay = vd

        val overlayPresentation = Presentation(
            appContext,
            vd.display,
            android.R.style.Theme_Material_Light_NoActionBar
        )

        overlayPresentation.window?.apply {
            setFormat(PixelFormat.TRANSLUCENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0f)
        }

        val overlayWebView = WebView(overlayPresentation.context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.setSupportZoom(false)
        }

        overlayPresentation.setContentView(overlayWebView)
        overlayPresentation.show()

        presentation = overlayPresentation
        webView = overlayWebView
        overlayWebView.loadUrl(url)
    }
}
