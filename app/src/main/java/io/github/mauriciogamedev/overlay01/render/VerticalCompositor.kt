package io.github.mauriciogamedev.overlay01.render

import android.opengl.GLES11Ext
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.min

/**
 * Small GPU compositor for Overlay01.
 *
 * Pass 1 draws the captured game into a fixed 9:16 canvas without stretching.
 * Pass 2 can alpha-blend a full-canvas URL overlay texture on top.
 */
class VerticalCompositor(
    private val outputWidth: Int,
    private val outputHeight: Int
) {

    private var program = 0
    private var positionLocation = -1
    private var texCoordLocation = -1
    private var textureMatrixLocation = -1
    private var samplerLocation = -1

    private val positionBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(4 * 2 * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private val texCoordBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(4 * 2 * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(0f).put(0f)
            put(1f).put(0f)
            put(0f).put(1f)
            put(1f).put(1f)
            position(0)
        }

    fun initialize() {
        check(program == 0) { "VerticalCompositor already initialized" }

        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

        program = GLES20.glCreateProgram()
        check(program != 0) { "Unable to create compositor GL program" }

        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val info = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            program = 0
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
            error("Unable to link compositor program: $info")
        }

        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)

        positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordLocation = GLES20.glGetAttribLocation(program, "aTexCoord")
        textureMatrixLocation = GLES20.glGetUniformLocation(program, "uTexMatrix")
        samplerLocation = GLES20.glGetUniformLocation(program, "uTexture")

        check(positionLocation >= 0) { "Missing aPosition in compositor shader" }
        check(texCoordLocation >= 0) { "Missing aTexCoord in compositor shader" }
        check(textureMatrixLocation >= 0) { "Missing uTexMatrix in compositor shader" }
        check(samplerLocation >= 0) { "Missing uTexture in compositor shader" }
    }

    fun renderGame(
        externalTextureId: Int,
        textureTransform: FloatArray,
        sourceWidth: Int,
        sourceHeight: Int
    ) {
        check(program != 0) { "VerticalCompositor is not initialized" }
        if (sourceWidth <= 0 || sourceHeight <= 0) return

        val scale = min(
            outputWidth.toFloat() / sourceWidth.toFloat(),
            outputHeight.toFloat() / sourceHeight.toFloat()
        )
        val renderedWidth = sourceWidth * scale
        val renderedHeight = sourceHeight * scale
        val halfWidth = renderedWidth / outputWidth.toFloat()
        val halfHeight = renderedHeight / outputHeight.toFloat()

        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        drawExternalTexture(
            textureId = externalTextureId,
            textureTransform = textureTransform,
            halfWidth = halfWidth,
            halfHeight = halfHeight,
            blend = false
        )
    }

    fun renderOverlay(
        externalTextureId: Int,
        textureTransform: FloatArray
    ) {
        check(program != 0) { "VerticalCompositor is not initialized" }

        drawExternalTexture(
            textureId = externalTextureId,
            textureTransform = textureTransform,
            halfWidth = 1f,
            halfHeight = 1f,
            blend = true
        )
    }

    private fun drawExternalTexture(
        textureId: Int,
        textureTransform: FloatArray,
        halfWidth: Float,
        halfHeight: Float,
        blend: Boolean
    ) {
        positionBuffer.clear()
        positionBuffer.put(-halfWidth).put(-halfHeight)
        positionBuffer.put(halfWidth).put(-halfHeight)
        positionBuffer.put(-halfWidth).put(halfHeight)
        positionBuffer.put(halfWidth).put(halfHeight)
        positionBuffer.position(0)

        if (blend) {
            GLES20.glEnable(GLES20.GL_BLEND)
            // Android UI surfaces use premultiplied alpha.
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        } else {
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        GLES20.glUseProgram(program)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glVertexAttribPointer(
            positionLocation,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            positionBuffer
        )

        texCoordBuffer.position(0)
        GLES20.glEnableVertexAttribArray(texCoordLocation)
        GLES20.glVertexAttribPointer(
            texCoordLocation,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            texCoordBuffer
        )

        GLES20.glUniformMatrix4fv(
            textureMatrixLocation,
            1,
            false,
            textureTransform,
            0
        )

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(samplerLocation, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES20.glDisableVertexAttribArray(positionLocation)
        GLES20.glDisableVertexAttribArray(texCoordLocation)
        if (blend) GLES20.glDisable(GLES20.GL_BLEND)
    }

    fun release() {
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        check(shader != 0) { "Unable to create GL shader" }

        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val info = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("Unable to compile compositor shader: $info")
        }

        return shader
    }

    private companion object {
        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;

            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;

            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }
}
