package io.github.mauriciogamedev.overlay01.stream

import android.media.MediaCodec
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.rtmp.rtmp.RtmpClient
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thin RTMP/RTMPS transport wrapper.
 *
 * Encoding is intentionally kept outside RootEncoder: Overlay01 feeds its own
 * H.264/AAC MediaCodec output into RtmpClient so the compositor stays fully
 * under our control.
 */
class RtmpPublisher(
    private val listener: Listener
) {

    interface Listener {
        fun onStateChanged(message: String, connected: Boolean)
        fun onRequestKeyFrame()
    }

    private val connected = AtomicBoolean(false)
    private var sps: ByteBuffer? = null
    private var pps: ByteBuffer? = null

    private val connectChecker = object : ConnectChecker {
        override fun onConnectionStarted(url: String) {
            listener.onStateChanged("Connecting to live server…", false)
        }

        override fun onConnectionSuccess() {
            connected.set(true)
            pushVideoConfigIfReady()
            listener.onRequestKeyFrame()
            listener.onStateChanged("Live connected", true)
        }

        override fun onConnectionFailed(reason: String) {
            connected.set(false)
            if (client.shouldRetry(reason)) {
                listener.onStateChanged("Connection lost · retrying…", false)
                client.reConnect(RETRY_DELAY_MS)
            } else {
                listener.onStateChanged("Live connection failed: $reason", false)
            }
        }

        override fun onDisconnect() {
            connected.set(false)
            listener.onStateChanged("Live disconnected", false)
        }

        override fun onAuthError() {
            connected.set(false)
            listener.onStateChanged("Live authentication error", false)
        }

        override fun onAuthSuccess() {
            listener.onStateChanged("Live authentication accepted", connected.get())
        }
    }

    private val client: RtmpClient by lazy {
        RtmpClient(connectChecker).apply {
            setVideoCodec(VideoCodec.H264)
            setAudioCodec(AudioCodec.AAC)
            setVideoResolution(VIDEO_WIDTH, VIDEO_HEIGHT)
            setFps(VIDEO_FPS)
            setAudioInfo(AUDIO_SAMPLE_RATE, false)
            setReTries(RETRY_COUNT)
            setLogs(false)
            shouldFailOnRead = true
        }
    }

    val isConnected: Boolean
        get() = connected.get()

    fun connect(endpoint: String) {
        client.connect(endpoint)
    }

    fun disconnect() {
        connected.set(false)
        if (client.isStreaming) client.disconnect()
    }

    fun setVideoConfig(sps: ByteBuffer, pps: ByteBuffer?) {
        this.sps = cloneBuffer(sps)
        this.pps = pps?.let(::cloneBuffer)
        if (connected.get()) pushVideoConfigIfReady()
    }

    fun sendVideo(data: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (connected.get()) client.sendVideo(data, info)
    }

    fun sendAudio(data: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (connected.get()) client.sendAudio(data, info)
    }

    private fun pushVideoConfigIfReady() {
        val currentSps = sps ?: return
        client.setVideoInfo(
            currentSps.duplicate().apply { position(0) },
            pps?.duplicate()?.apply { position(0) },
            null
        )
    }

    private fun cloneBuffer(source: ByteBuffer): ByteBuffer {
        val src = source.duplicate()
        return ByteBuffer.allocateDirect(src.remaining()).apply {
            put(src)
            flip()
        }
    }

    companion object {
        const val VIDEO_WIDTH = 720
        const val VIDEO_HEIGHT = 1280
        const val VIDEO_FPS = 30
        const val VIDEO_BITRATE = 3_000_000
        const val AUDIO_SAMPLE_RATE = 44_100
        const val AUDIO_BITRATE = 128_000

        private const val RETRY_COUNT = 3
        private const val RETRY_DELAY_MS = 1_500L
    }
}
