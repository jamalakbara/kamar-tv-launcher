package com.tvkamar.launcher.airplay

/** Native AirPlay (RAOP) receiver from libkamar_airplay. Callbacks arrive on native threads. */
class RaopServer(private val listener: Listener) {
    interface Listener {
        /** [type] 0 = SPS/PPS codec config, 1 = frame; both Annex-B. [ptsUs] counts from the first frame. */
        fun onVideo(data: ByteArray, type: Int, ptsUs: Long)
        fun onVideoStop()
        /** 480 stereo frames of 16-bit PCM at 44.1 kHz. */
        fun onAudio(pcm: ShortArray)
        /** AirPlay volume in dB: -30..0, or -144 for mute. */
        fun onVolume(db: Float)
    }

    private var handle = 0L

    /** Starts the server with the receiver's persistent key [seed] (32 bytes); returns the public key. */
    fun start(seed: ByteArray): ByteArray? {
        if (handle == 0L) handle = nativeStart(seed)
        return if (handle != 0L) nativePublicKey(handle) else null
    }

    fun setInfo(name: String, deviceId: String, features: Long, width: Int, height: Int, maxFps: Int, airplayTxt: ByteArray, raopTxt: ByteArray) {
        if (handle != 0L) nativeSetInfo(handle, name, deviceId, features, width, height, maxFps, airplayTxt, raopTxt)
    }

    /** Opens the listening socket; returns its port or 0. */
    fun listen(): Int = if (handle != 0L) nativeListen(handle) else 0

    fun stop() {
        if (handle != 0L) nativeStop(handle)
        handle = 0L
    }

    // Called from native code.
    @Suppress("unused") private fun onVideo(data: ByteArray, type: Int, pts: Long) = listener.onVideo(data, type, pts)
    @Suppress("unused") private fun onVideoStop() = listener.onVideoStop()
    @Suppress("unused") private fun onAudio(pcm: ShortArray, pts: Long) = listener.onAudio(pcm)
    @Suppress("unused") private fun onVolume(volume: Float) = listener.onVolume(volume)

    private external fun nativeStart(seed: ByteArray): Long
    private external fun nativePublicKey(handle: Long): ByteArray
    private external fun nativeSetInfo(
        handle: Long, name: String, deviceId: String, features: Long,
        width: Int, height: Int, maxFps: Int, airplayTxt: ByteArray, raopTxt: ByteArray,
    )
    private external fun nativeListen(handle: Long): Int
    private external fun nativeStop(handle: Long)

    companion object {
        init {
            System.loadLibrary("kamar_airplay")
        }
    }
}
