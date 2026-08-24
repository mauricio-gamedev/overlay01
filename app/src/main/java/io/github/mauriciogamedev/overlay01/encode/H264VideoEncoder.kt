package io.github.mauriciogamedev.overlay01.encode

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class H264VideoEncoder(
    private val width: Int,
    private val height: Int,
    private val bitrate: Int,
    private val fps: Int,
    private val onVideoConfig: (ByteBuffer, ByteBuffer?) -> Unit,
    private val onEncodedFrame: (ByteBuffer, MediaCodec.BufferInfo) -> Unit,
    private val onError: (Throwable) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var codec: MediaCodec? = null
    private var outputThread: Thread? = null
    private var inputSurface: Surface? = null

    fun start(): Surface {
        check(!running.get()) { "Video encoder already started" }
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = encoder.createInputSurface()
        encoder.start()
        codec = encoder
        inputSurface = surface
        running.set(true)
        outputThread = Thread({ drainLoop(encoder) }, "Overlay01-H264").also { it.start() }
        return surface
    }

    fun requestKeyFrame() {
        runCatching {
            codec?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        outputThread?.join(500)
        outputThread = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { inputSurface?.release() }
        inputSurface = null
    }

    private fun drainLoop(encoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                when (val index = encoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = encoder.outputFormat
                        val sps = format.getByteBuffer("csd-0")
                        val pps = format.getByteBuffer("csd-1")
                        if (sps != null) onVideoConfig(cloneBuffer(sps), pps?.let(::cloneBuffer))
                    }
                    else -> if (index >= 0) {
                        val out = encoder.getOutputBuffer(index)
                        if (out != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val copy = copyRange(out, info.offset, info.size)
                            val copyInfo = MediaCodec.BufferInfo().apply {
                                set(0, copy.remaining(), info.presentationTimeUs, info.flags)
                            }
                            onEncodedFrame(copy, copyInfo)
                        }
                        encoder.releaseOutputBuffer(index, false)
                    }
                }
            }
        } catch (t: Throwable) {
            if (running.get()) onError(t)
        }
    }

    private fun copyRange(source: ByteBuffer, offset: Int, size: Int): ByteBuffer {
        val src = source.duplicate().apply {
            position(offset)
            limit(offset + size)
        }
        return ByteBuffer.allocateDirect(size).apply {
            put(src)
            flip()
        }
    }

    private fun cloneBuffer(source: ByteBuffer): ByteBuffer {
        val src = source.duplicate()
        return ByteBuffer.allocateDirect(src.remaining()).apply {
            put(src)
            flip()
        }
    }
}
