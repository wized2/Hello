package com.endroid.hello.net

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class AudioEngine(
    private val context: Context,
    private val onCapture: (ByteArray, Int) -> Unit,
) {
    private val tag = "HelloAudio"
    private val capturing = AtomicBoolean(false)
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val minBuf = AudioRecord.getMinBufferSize(
        Protocol.SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    ).coerceAtLeast(Protocol.SAMPLE_RATE / 10 * 2) // ~100ms

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
            .setBufferSizeInBytes(outMin * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = true
        } catch (_: Exception) {}
        track?.play()
    }

    fun playPcm(pcm: ByteArray) {
        try {
            ensurePlayer()
            var off = 0
            while (off < pcm.size) {
                val n = track?.write(pcm, off, pcm.size - off) ?: break
                if (n <= 0) break
                off += n
            }
        } catch (e: Exception) {
            Log.w(tag, "play", e)
        }
    }

    fun startCapture() {
        if (!capturing.compareAndSet(false, true)) return
        thread(name = "hello-capture", isDaemon = true) {
            try {
                try {
                    am.mode = AudioManager.MODE_IN_COMMUNICATION
                    am.isSpeakerphoneOn = true
                } catch (_: Exception) {}
                val sources = listOf(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    MediaRecorder.AudioSource.MIC,
                    MediaRecorder.AudioSource.DEFAULT,
                )
                var rec: AudioRecord? = null
                for (src in sources) {
                    try {
                        val r = if (Build.VERSION.SDK_INT >= 23) {
                            AudioRecord.Builder()
                                .setAudioSource(src)
                                .setAudioFormat(
                                    AudioFormat.Builder()
                                        .setSampleRate(Protocol.SAMPLE_RATE)
                                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                        .build(),
                                )
                                .setBufferSizeInBytes(minBuf * 2)
                                .build()
                        } else {
                            @Suppress("DEPRECATION")
                            AudioRecord(
                                src,
                                Protocol.SAMPLE_RATE,
                                AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT,
                                minBuf * 2,
                            )
                        }
                        if (r.state == AudioRecord.STATE_INITIALIZED) {
                            rec = r
                            break
                        }
                        r.release()
                    } catch (e: Exception) {
                        Log.w(tag, "source $src", e)
                    }
                }
                if (rec == null) {
                    Log.e(tag, "No AudioRecord")
                    capturing.set(false)
                    return@thread
                }
                record = rec
                rec.startRecording()
                val buf = ByteArray(minBuf)
                while (capturing.get()) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) onCapture(buf, n)
                }
                try {
                    rec.stop()
                } catch (_: Exception) {}
                rec.release()
            } catch (e: Exception) {
                Log.e(tag, "capture", e)
            } finally {
                record = null
                capturing.set(false)
            }
        }
    }

    fun stopCapture() {
        capturing.set(false)
    }

    fun release() {
        stopCapture()
        try {
            track?.stop()
        } catch (_: Exception) {}
        try {
            track?.release()
        } catch (_: Exception) {}
        track = null
        try {
            am.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {}
    }
}
