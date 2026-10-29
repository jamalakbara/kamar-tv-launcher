package com.tvkamar.launcher.airplay

import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/** One H.264 access unit (or the SPS/PPS config) from the phone. */
class VideoFrame(val data: ByteArray, val isConfig: Boolean, val ptsUs: Long)

/**
 * Hands mirrored video from the receiver to [MirrorActivity]. Frames that arrive before the
 * screen is up are kept, so the decoder still starts at the codec config and first key frame.
 */
object Mirror {
    private const val MAX_QUEUED = 300

    private val frames = LinkedBlockingDeque<VideoFrame>()

    /** Latest SPS/PPS, replayed whenever a decoder (re)starts. */
    @Volatile var config: VideoFrame? = null
        private set

    /** True from the first frame of a session until the phone stops mirroring. */
    @Volatile var active = false
        private set

    /** Called on the native thread when the phone stops; the activity closes itself. */
    @Volatile var onStop: (() -> Unit)? = null

    fun begin() {
        frames.clear()
        config = null
        active = true
    }

    fun offer(frame: VideoFrame) {
        if (frame.isConfig) config = frame
        if (frames.size >= MAX_QUEUED) frames.clear() // stalled screen: drop the backlog, wait for the next config
        frames.offer(frame)
    }

    fun take(timeoutMs: Long): VideoFrame? = frames.poll(timeoutMs, TimeUnit.MILLISECONDS)

    fun end() {
        active = false
        frames.clear()
        onStop?.invoke()
    }
}
