package io.github.mauriciogamedev.overlay01.capture

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import io.github.mauriciogamedev.overlay01.overlay.WebOverlayEngine
import io.github.mauriciogamedev.overlay01.render.VerticalCompositor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ScreenCaptureEngine(
    context: Context,
    private val projection: MediaProjection,
    initialWidth: Int,
    initialHeight: Int,
    private val densityDpi: Int,
    private val encoderSurface: Surface,
    private val overlayUrl: String? = null,
    private val onError: (Throwable) -> Unit = {}
) {
    private val appContext = context.applicationContext
    @Volatile private var width = initialWidth
    @Volatile private var height = initialHeight
    private val running = AtomicBoolean(false)
    private val frames = AtomicLong(0L)
    private val gameTextureTransform = FloatArray(16)
    private var lastOutputNs = 0L
    private var firstPresentationSourceNs = 0L
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var setupEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var outputEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var gameTextureId = 0
    private var gameSurfaceTexture: SurfaceTexture? = null
    private var gameInputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var compositor: VerticalCompositor? = null
    private var webOverlay: WebOverlayEngine? = null
    val frameCount: Long get() = frames.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val captureThread = HandlerThread("Overlay01-GpuCapture").also { it.start(); thread = it }
        val captureHandler = Handler(captureThread.looper).also { handler = it }
        captureHandler.post {
            try {
                initializeGl()
                compositor = VerticalCompositor(OUTPUT_WIDTH, OUTPUT_HEIGHT).also { it.initialize() }
                createCaptureSurface()
                createWebOverlayIfNeeded(captureHandler)
                createVirtualDisplay(captureHandler)
            } catch (error: Throwable) {
                running.set(false)
                releaseOnCaptureThread()
                onError(error)
                captureThread.quitSafely()
            }
        }
    }

    fun resize(newWidth: Int, newHeight: Int) {
        if (newWidth <= 0 || newHeight <= 0) return
        handler?.post {
            if (!running.get() || (newWidth == width && newHeight == height)) return@post
            width = newWidth
            height = newHeight
            gameSurfaceTexture?.setDefaultBufferSize(width, height)
            virtualDisplay?.resize(width, height, densityDpi)
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        val captureHandler = handler ?: return
        val captureThread = thread ?: return
        val released = CountDownLatch(1)
        captureHandler.post {
            try { releaseOnCaptureThread() } finally {
                released.countDown()
                captureThread.quitSafely()
            }
        }
        released.await(750, TimeUnit.MILLISECONDS)
    }

    private fun initializeGl() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1))
        eglDisplay = display
        val attrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT or EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) && count[0] > 0)
        val config = checkNotNull(configs[0])
        eglContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT)
        setupEglSurface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(setupEglSurface != EGL14.EGL_NO_SURFACE)
        outputEglSurface = EGL14.eglCreateWindowSurface(display, config, encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
        check(outputEglSurface != EGL14.EGL_NO_SURFACE)
        check(EGL14.eglMakeCurrent(display, setupEglSurface, setupEglSurface, eglContext))
    }

    private fun createCaptureSurface() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        gameTextureId = textures[0]
        check(gameTextureId != 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, gameTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        val captureTexture = SurfaceTexture(gameTextureId).apply {
            setDefaultBufferSize(width, height)
            setOnFrameAvailableListener({ texture ->
                if (!running.get()) return@setOnFrameAvailableListener
                try {
                    texture.updateTexImage()
                    texture.getTransformMatrix(gameTextureTransform)
                    val sourceTimestamp = texture.timestamp.takeIf { it > 0L } ?: System.nanoTime()
                    if (sourceTimestamp - lastOutputNs >= FRAME_INTERVAL_NS) {
                        lastOutputNs = sourceTimestamp
                        if (firstPresentationSourceNs == 0L) firstPresentationSourceNs = sourceTimestamp
                        renderCompositeFrame((sourceTimestamp - firstPresentationSourceNs).coerceAtLeast(0L))
                    }
                } catch (error: Throwable) {
                    if (running.get()) onError(error)
                }
            }, handler)
        }
        gameSurfaceTexture = captureTexture
        gameInputSurface = Surface(captureTexture)
    }

    private fun createWebOverlayIfNeeded(captureHandler: Handler) {
        val url = overlayUrl?.trim().orEmpty()
        if (url.isBlank()) return
        webOverlay = WebOverlayEngine(context = appContext, glHandler = captureHandler, width = OUTPUT_WIDTH, height = OUTPUT_HEIGHT, densityDpi = densityDpi, onError = {}).also { it.initializeGl(url) }
    }

    private fun renderCompositeFrame(timestampNs: Long) {
        if (!EGL14.eglMakeCurrent(eglDisplay, outputEglSurface, outputEglSurface, eglContext)) return
        webOverlay?.updateTextureIfNeeded()
        val renderer = compositor ?: return
        renderer.renderGame(gameTextureId, gameTextureTransform, width, height)
        webOverlay?.let { overlay ->
            if (overlay.hasUsableFrame && overlay.externalTextureId != 0) renderer.renderOverlay(overlay.externalTextureId, overlay.textureMatrix())
        }
        EGLExt.eglPresentationTimeANDROID(eglDisplay, outputEglSurface, timestampNs)
        check(EGL14.eglSwapBuffers(eglDisplay, outputEglSurface))
        frames.incrementAndGet()
    }

    private fun createVirtualDisplay(captureHandler: Handler) {
        virtualDisplay = projection.createVirtualDisplay("Overlay01-Capture", width, height, densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, checkNotNull(gameInputSurface), null, captureHandler)
        checkNotNull(virtualDisplay)
    }

    private fun releaseOnCaptureThread() {
        runCatching { gameSurfaceTexture?.setOnFrameAvailableListener(null) }
        runCatching { virtualDisplay?.release() }; virtualDisplay = null
        runCatching { gameInputSurface?.release() }; gameInputSurface = null
        runCatching { gameSurfaceTexture?.release() }; gameSurfaceTexture = null
        runCatching { webOverlay?.releaseGl() }; webOverlay = null
        runCatching { compositor?.release() }; compositor = null
        if (gameTextureId != 0) { runCatching { GLES20.glDeleteTextures(1, intArrayOf(gameTextureId), 0) }; gameTextureId = 0 }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            runCatching { EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
            if (outputEglSurface != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(eglDisplay, outputEglSurface) }
            if (setupEglSurface != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(eglDisplay, setupEglSurface) }
            if (eglContext != EGL14.EGL_NO_CONTEXT) runCatching { EGL14.eglDestroyContext(eglDisplay, eglContext) }
            runCatching { EGL14.eglTerminate(eglDisplay) }
        }
        outputEglSurface = EGL14.EGL_NO_SURFACE
        setupEglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
        handler = null
        thread = null
    }

    companion object {
        const val OUTPUT_WIDTH = 720
        const val OUTPUT_HEIGHT = 1280
        private const val OUTPUT_FPS = 30L
        private const val FRAME_INTERVAL_NS = 1_000_000_000L / OUTPUT_FPS
    }
}
