package io.github.mauriciogamedev.overlay01.capture

import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import io.github.mauriciogamedev.overlay01.render.VerticalCompositor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Captures the device display directly into an external OpenGL texture.
 *
 * No application-overlay window is created. The captured texture is composited
 * off-screen into a vertical 9:16 canvas, so touches remain owned by the game.
 */
class ScreenCaptureEngine(
    private val projection: MediaProjection,
    initialWidth: Int,
    initialHeight: Int,
    private val densityDpi: Int,
    private val onError: (Throwable) -> Unit = {}
) {

    @Volatile
    private var width = initialWidth

    @Volatile
    private var height = initialHeight

    private val running = AtomicBoolean(false)
    private val frames = AtomicLong(0L)
    private val textureTransform = FloatArray(16)

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var compositor: VerticalCompositor? = null

    val frameCount: Long
        get() = frames.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return

        val captureThread = HandlerThread("Overlay01-GpuCapture").also {
            it.start()
            thread = it
        }
        val captureHandler = Handler(captureThread.looper).also { handler = it }

        captureHandler.post {
            try {
                initializeGl()
                compositor = VerticalCompositor(
                    outputWidth = OUTPUT_WIDTH,
                    outputHeight = OUTPUT_HEIGHT
                ).also { it.initialize() }
                createCaptureSurface()
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
            if (!running.get()) return@post
            if (newWidth == width && newHeight == height) return@post

            width = newWidth
            height = newHeight
            surfaceTexture?.setDefaultBufferSize(width, height)
            virtualDisplay?.resize(width, height, densityDpi)
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return

        val captureHandler = handler
        val captureThread = thread
        if (captureHandler == null || captureThread == null) return

        captureHandler.post {
            releaseOnCaptureThread()
            captureThread.quitSafely()
        }
    }

    private fun initializeGl() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "Unable to obtain EGL display" }

        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) {
            "Unable to initialize EGL"
        }
        eglDisplay = display

        val configAttributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val configCount = IntArray(1)
        check(
            EGL14.eglChooseConfig(
                display,
                configAttributes,
                0,
                configs,
                0,
                configs.size,
                configCount,
                0
            ) && configCount[0] > 0
        ) { "Unable to choose EGL config" }

        val config = checkNotNull(configs[0]) { "EGL config was null" }
        val contextAttributes = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        val context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            contextAttributes,
            0
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "Unable to create EGL context" }
        eglContext = context

        val pbufferAttributes = intArrayOf(
            EGL14.EGL_WIDTH, OUTPUT_WIDTH,
            EGL14.EGL_HEIGHT, OUTPUT_HEIGHT,
            EGL14.EGL_NONE
        )
        val pbuffer = EGL14.eglCreatePbufferSurface(
            display,
            config,
            pbufferAttributes,
            0
        )
        check(pbuffer != EGL14.EGL_NO_SURFACE) { "Unable to create EGL pbuffer" }
        eglSurface = pbuffer

        check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) {
            "Unable to make EGL context current"
        }
    }

    private fun createCaptureSurface() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        check(textureId != 0) { "Unable to allocate capture texture" }

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

        val captureTexture = SurfaceTexture(textureId).apply {
            setDefaultBufferSize(width, height)
            setOnFrameAvailableListener({ texture ->
                if (!running.get()) return@setOnFrameAvailableListener
                try {
                    texture.updateTexImage()
                    texture.getTransformMatrix(textureTransform)
                    compositor?.render(
                        externalTextureId = textureId,
                        textureTransform = textureTransform,
                        sourceWidth = width,
                        sourceHeight = height
                    )
                    GLES20.glFlush()
                    frames.incrementAndGet()
                } catch (error: Throwable) {
                    if (running.get()) onError(error)
                }
            }, handler)
        }
        surfaceTexture = captureTexture
        inputSurface = Surface(captureTexture)
    }

    private fun createVirtualDisplay(captureHandler: Handler) {
        val targetSurface = checkNotNull(inputSurface)
        virtualDisplay = projection.createVirtualDisplay(
            "Overlay01-Capture",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            targetSurface,
            null,
            captureHandler
        )
        checkNotNull(virtualDisplay) { "Unable to create MediaProjection virtual display" }
    }

    private fun releaseOnCaptureThread() {
        runCatching { surfaceTexture?.setOnFrameAvailableListener(null) }
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null

        runCatching { inputSurface?.release() }
        inputSurface = null

        runCatching { surfaceTexture?.release() }
        surfaceTexture = null

        runCatching { compositor?.release() }
        compositor = null

        if (textureId != 0) {
            runCatching { GLES20.glDeleteTextures(1, intArrayOf(textureId), 0) }
            textureId = 0
        }

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            runCatching {
                EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
            }
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                runCatching { EGL14.eglDestroySurface(eglDisplay, eglSurface) }
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                runCatching { EGL14.eglDestroyContext(eglDisplay, eglContext) }
            }
            runCatching { EGL14.eglTerminate(eglDisplay) }
        }

        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
        handler = null
        thread = null
    }

    companion object {
        const val OUTPUT_WIDTH = 720
        const val OUTPUT_HEIGHT = 1280
    }
}
