package com.tvkamar.launcher.airplay

import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/** One H.264 access unit (or the SPS/PPS config) from the phone. */
class VideoFrame(val data: ByteArray, val isConfig: Boolean, val ptsUs: Long) {
    /** True if the frame holds an IDR slice, which the decoder can start from. */
    val isKey: Boolean by lazy {
        // Annex-B: look at the NAL type after each 00 00 01 start code.
        for (i in 0 until data.size - 3) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1 &&
                data[i + 3].toInt() and 0x1F == 5
            ) return@lazy true
        }
        false
    }
}

/**
 * Hands mirrored video from the receiver to [MirrorActivity]. Frames that arrive before the
 * screen is up are kept, so the decoder still starts at the codec config and first key frame.
 * If the decoder falls behind, the backlog is dropped and the stream resumes at the next key
 * frame, so the picture jumps back to live instead of staying late.
 */
object Mirror {
    /** About half a second at 60 fps. */
    private const val MAX_QUEUED = 30

    /** Give up waiting for a key frame after this long and carry on with what comes. */
    private const val MAX_KEY_WAIT_US = 2_000_000L

    private var skipFromUs = -1L

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
        skipFromUs = -1
        active = true
    }

    fun offer(frame: VideoFrame) {
        if (frame.isConfig) config = frame
        if (frames.size >= MAX_QUEUED) {
            frames.clear()
            if (skipFromUs < 0) skipFromUs = frame.ptsUs
        }
        if (skipFromUs >= 0) {
            if (!frame.isConfig && !frame.isKey && frame.ptsUs - skipFromUs < MAX_KEY_WAIT_US) return
            skipFromUs = -1
        }
        frames.offer(frame)
    }

    fun take(timeoutMs: Long): VideoFrame? = frames.poll(timeoutMs, TimeUnit.MILLISECONDS)

    fun end() {
        active = false
        frames.clear()
        onStop?.invoke()
    }
}
