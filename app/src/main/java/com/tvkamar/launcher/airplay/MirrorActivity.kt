package com.tvkamar.launcher.airplay

import android.app.Activity
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

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
        val callbacks = HandlerThread("airplay-codec").apply { start() }
        var decoder: Decoder? = null
        try {
            // Start from the latest config so a late (or reopened) screen can still decode.
            var pending: VideoFrame? = Mirror.config
            while (running) {
                val frame = pending ?: Mirror.take(50) ?: continue
                pending = null
                if (frame.isConfig) {
                    if (decoder == null || !frame.data.contentEquals(decoder.config)) {
                        decoder?.release()
                        decoder = Decoder(surface, frame.data, Handler(callbacks.looper), ::fitVideo)
                    }
                } else {
                    decoder?.queue(frame.data, frame.ptsUs, 0)
                }
            }
        } catch (e: Exception) {
            Log.e("AirPlay", "Mirror decoder stopped", e)
        } finally {
            decoder?.release()
            callbacks.quitSafely()
        }
    }

    /**
     * Hardware H.264 decoder in async mode: input buffers are handed over as soon as the codec
     * frees them and every decoded frame is rendered the moment it is ready, so nothing waits on
     * a polling loop. Mirroring wants low latency, not pacing.
     */
    private class Decoder(surface: Surface, val config: ByteArray, handler: Handler, private val onFormat: (MediaFormat) -> Unit) :
        MediaCodec.Callback() {
        private val inputs = LinkedBlockingQueue<Int>()
        private val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)

        init {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080).apply {
                if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            codec.setCallback(this, handler)
            codec.configure(format, surface, null, 0)
            codec.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            codec.start()
            queue(config, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
        }

        /** Queues one frame, waiting up to 100 ms for a free input buffer; drops it otherwise. */
        fun queue(data: ByteArray, ptsUs: Long, flags: Int) {
            val index = inputs.poll(100, TimeUnit.MILLISECONDS) ?: return
            val buffer = codec.getInputBuffer(index) ?: return
            buffer.clear()
            if (data.size > buffer.capacity()) {
                Log.w("AirPlay", "Frame of ${data.size} bytes too big for decoder")
                codec.queueInputBuffer(index, 0, 0, ptsUs, 0)
                return
            }
            buffer.put(data)
            codec.queueInputBuffer(index, 0, data.size, ptsUs, flags)
        }

        fun release() {
            runCatching { codec.stop() }
            codec.release()
        }

        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            inputs.offer(index)
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            // A callback can still arrive while the codec is being released.
            runCatching { codec.releaseOutputBuffer(index, true) }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = onFormat(format)

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e("AirPlay", "Mirror decoder error", e)
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
