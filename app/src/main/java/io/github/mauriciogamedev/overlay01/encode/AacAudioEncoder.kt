package io.github.mauriciogamedev.overlay01.encode

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class AacAudioEncoder(
    private val projection: MediaProjection,
    private val preferredMode: Mode,
    private val sampleRate: Int,
    private val bitrate: Int,
    private val onEncodedFrame: (ByteBuffer, MediaCodec.BufferInfo) -> Unit,
    private val onModeResolved: (Mode) -> Unit = {},
    private val onError: (Throwable) -> Unit = {}
) {
    enum class Mode { GAME, MICROPHONE }

    private val running = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var worker: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            val resolved = createAudioRecordWithFallback()
            val record = resolved.first
            val actualMode = resolved.second
            val encoder = createEncoder()
            audioRecord = record
            codec = encoder
            record.startRecording()
            encoder.start()
            onModeResolved(actualMode)
            worker = Thread({ encodeLoop(record, encoder) }, "Overlay01-AAC").also { it.start() }
        } catch (t: Throwable) {
            running.set(false)
            releaseInternal()
            onError(t)
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { audioRecord?.stop() }
        worker?.join(500)
        worker = null
        releaseInternal()
    }

    private fun createEncoder(): MediaCodec {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, CHANNEL_COUNT).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
    }

    private fun createAudioRecordWithFallback(): Pair<AudioRecord, Mode> {
        if (preferredMode == Mode.GAME && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { createGameAudioRecord() }
                .getOrNull()
                ?.let { return it to Mode.GAME }
        }
        return createMicrophoneRecord() to Mode.MICROPHONE
    }

    private fun baseAudioFormat(): AudioFormat = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(sampleRate)
        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
        .build()

    private fun bufferSize(): Int = max(
        AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
        8 * 1024
    )

    private fun createGameAudioRecord(): AudioRecord {
        val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        return AudioRecord.Builder()
            .setAudioFormat(baseAudioFormat())
            .setBufferSizeInBytes(bufferSize())
            .setAudioPlaybackCaptureConfig(capture)
            .build()
            .also { check(it.state == AudioRecord.STATE_INITIALIZED) { "Game audio capture unavailable" } }
    }

    private fun createMicrophoneRecord(): AudioRecord = AudioRecord.Builder()
        .setAudioSource(MediaRecorder.AudioSource.MIC)
        .setAudioFormat(baseAudioFormat())
        .setBufferSizeInBytes(bufferSize())
        .build()
        .also { check(it.state == AudioRecord.STATE_INITIALIZED) { "Microphone capture unavailable" } }

    private fun encodeLoop(record: AudioRecord, encoder: MediaCodec) {
        val pcm = ByteArray(bufferSize())
        val outInfo = MediaCodec.BufferInfo()
        var submittedSamples = 0L
        try {
            while (running.get()) {
                val read = record.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                if (read > 0) {
                    val index = encoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        encoder.getInputBuffer(index)?.apply {
                            clear()
                            put(pcm, 0, read)
                        }
                        val ptsUs = submittedSamples * 1_000_000L / sampleRate
                        submittedSamples += read / BYTES_PER_SAMPLE
                        encoder.queueInputBuffer(index, 0, read, ptsUs, 0)
                    }
                }
                drain(encoder, outInfo)
            }
        } catch (t: Throwable) {
            if (running.get()) onError(t)
        }
    }

    private fun drain(encoder: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val index = encoder.dequeueOutputBuffer(info, 0)
            if (index < 0) return
            val out = encoder.getOutputBuffer(index)
            if (out != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                val src = out.duplicate().apply {
                    position(info.offset)
                    limit(info.offset + info.size)
                }
                val copy = ByteBuffer.allocateDirect(info.size).apply {
                    put(src)
                    flip()
                }
                val copyInfo = MediaCodec.BufferInfo().apply {
                    set(0, copy.remaining(), info.presentationTimeUs, info.flags)
                }
                onEncodedFrame(copy, copyInfo)
            }
            encoder.releaseOutputBuffer(index, false)
        }
    }

    private fun releaseInternal() {
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }

    private companion object {
        const val CHANNEL_COUNT = 1
        const val BYTES_PER_SAMPLE = 2
    }
}
