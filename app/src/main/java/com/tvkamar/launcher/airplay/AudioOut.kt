package com.tvkamar.launcher.airplay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.pow

/** Plays the decoded AirPlay audio (44.1 kHz stereo PCM) on its own thread. */
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
                .setSampleRate(44100)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
        )
        .setBufferSizeInBytes(AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT) * 2)
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
        // Keep latency low: if we fall behind (~0.5 s queued) drop the backlog.
        if (queue.size > 45) queue.clear()
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
}
