package com.tvkamar.launcher.airplay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.pow

/**
 * Plays the decoded AirPlay audio (44.1 kHz stereo PCM) on its own thread.
 *
 * Mirrored video is shown as soon as it is decoded, so audio has to stay just as close to live:
 * whatever sits in the queue or the track's buffer is heard that much later than the picture.
 * The track buffer is kept at the device minimum (writes block once it is full) and the queue
 * in front of it to about [MAX_QUEUED_MS]; anything beyond that is dropped.
 */
class AudioOut {
    private val queue = LinkedBlockingQueue<ShortArray>()
    @Volatile private var running = true

    private val track = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
        )
        .setBufferSizeInBytes(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT))
        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    private val thread = Thread({
        track.play()
        while (running) {
            val pcm = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
            track.write(pcm, 0, pcm.size)
        }
        track.stop()
        track.release()
    }, "airplay-audio").apply { start() }

    fun write(pcm: ShortArray) {
        // Each packet is ~11.6 ms; if we fall behind, drop the backlog and play the newest.
        if (queue.size >= MAX_QUEUED_MS / 12) queue.clear()
        queue.offer(pcm)
    }

    /** AirPlay volume: -30..0 dB, -144 = mute. */
    fun setVolume(db: Float) {
        track.setVolume(if (db <= -30f) 0f else 10f.pow(db / 20f))
    }

    fun flush() = queue.clear()

    fun release() {
        running = false
        thread.join(500)
    }

    private companion object {
        const val RATE = 44100
        const val MAX_QUEUED_MS = 100
    }
}
