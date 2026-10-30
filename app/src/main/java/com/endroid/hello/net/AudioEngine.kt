package com.endroid.hello.net

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class AudioEngine(
    private val onCapture: (ByteArray, Int) -> Unit,
) {
    private val tag = "HelloAudio"
    private val capturing = AtomicBoolean(false)
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null

    private val minBuf = AudioRecord.getMinBufferSize(
        Protocol.SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    ).coerceAtLeast(Protocol.SAMPLE_RATE / 5)

    fun ensurePlayer() {
        if (track != null) return
        val outMin = AudioTrack.getMinBufferSize(
            Protocol.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(minBuf)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(Protocol.SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(outMin)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track?.play()
    }

    fun playPcm(pcm: ByteArray) {
        try {
            ensurePlayer()
            track?.write(pcm, 0, pcm.size)
        } catch (e: Exception) {
            Log.w(tag, "play", e)
        }
    }

    fun startCapture() {
        if (!capturing.compareAndSet(false, true)) return
        thread(name = "hello-capture", isDaemon = true) {
            try {
                val rec = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    Protocol.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2,
                )
                record = rec
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(tag, "AudioRecord not initialized")
                    capturing.set(false)
                    return@thread
                }
                rec.startRecording()
                val buf = ByteArray(minBuf)
                while (capturing.get()) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) onCapture(buf, n)
                }
                try { rec.stop() } catch (_: Exception) {}
                rec.release()
            } catch (e: Exception) {
                Log.e(tag, "capture", e)
            } finally {
                record = null
                capturing.set(false)
            }
        }
    }

    fun stopCapture() { capturing.set(false) }

    fun release() {
        stopCapture()
        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        track = null
    }
}
