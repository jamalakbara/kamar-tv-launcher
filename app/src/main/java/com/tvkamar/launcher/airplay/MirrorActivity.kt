package com.tvkamar.launcher.airplay

import android.app.Activity
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout

/** Full-screen AirPlay mirror: decodes the phone's H.264 stream straight onto a surface. */
class MirrorActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private var decoder: Thread? = null
    @Volatile private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surfaceView = SurfaceView(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        setContentView(root)
        surfaceView.holder.addCallback(this)
        Mirror.onStop = { runOnUiThread { finish() } }
        if (!Mirror.active) finish()
    }

    override fun onDestroy() {
        Mirror.onStop = null
        super.onDestroy()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        running = true
        decoder = Thread({ decodeLoop(holder.surface) }, "airplay-video").apply { start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false
        decoder?.join(1000)
        decoder = null
    }

    private fun decodeLoop(surface: Surface) {
        var codec: MediaCodec? = null
        var config: ByteArray? = null
        val info = MediaCodec.BufferInfo()
        try {
            // Start from the latest config so a late (or reopened) screen can still decode.
            var pending: VideoFrame? = Mirror.config
            while (running) {
                val frame = pending ?: Mirror.take(20)
                pending = null
                if (frame != null) {
                    if (frame.isConfig) {
                        if (codec == null || !frame.data.contentEquals(config)) {
                            codec?.release()
                            codec = newDecoder(surface)
                            config = frame.data
                            queue(codec, frame.data, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                        }
                    } else if (codec != null) {
                        queue(codec, frame.data, frame.ptsUs, 0)
                    }
                }
                codec?.let { drain(it, info) }
            }
        } catch (e: Exception) {
            Log.e("AirPlay", "Mirror decoder stopped", e)
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
        }
    }

    private fun newDecoder(surface: Surface): MediaCodec {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080).apply {
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        return MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, surface, null, 0)
            setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            start()
        }
    }

    private fun queue(codec: MediaCodec, data: ByteArray, ptsUs: Long, flags: Int) {
        // Wait up to ~100 ms for a free input buffer, rendering output meanwhile.
        val info = MediaCodec.BufferInfo()
        repeat(10) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) {
                val buffer = codec.getInputBuffer(index) ?: return
                buffer.clear()
                if (data.size > buffer.capacity()) {
                    Log.w("AirPlay", "Frame of ${data.size} bytes too big for decoder")
                    codec.queueInputBuffer(index, 0, 0, ptsUs, 0)
                    return
                }
                buffer.put(data)
                codec.queueInputBuffer(index, 0, data.size, ptsUs, flags)
                return
            }
            drain(codec, info)
        }
    }

    /** Renders every decoded frame right away; mirroring wants low latency, not pacing. */
    private fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index >= 0 -> codec.releaseOutputBuffer(index, true)
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> fitVideo(codec.outputFormat)
                else -> return
            }
        }
    }

    /** Letterboxes the surface to the phone's aspect ratio (portrait or landscape). */
    private fun fitVideo(format: MediaFormat) {
        val w = if (format.containsKey("crop-right")) format.getInteger("crop-right") - format.getInteger("crop-left") + 1
            else format.getInteger(MediaFormat.KEY_WIDTH)
        val h = if (format.containsKey("crop-bottom")) format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1
            else format.getInteger(MediaFormat.KEY_HEIGHT)
        if (w <= 0 || h <= 0) return
        runOnUiThread {
            val scale = minOf(root.width.toFloat() / w, root.height.toFloat() / h)
            surfaceView.layoutParams = FrameLayout.LayoutParams((w * scale).toInt(), (h * scale).toInt(), Gravity.CENTER)
        }
    }
}
