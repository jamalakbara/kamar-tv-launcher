package com.tvkamar.launcher.airplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/** What the AirPlay page in the sidebar shows. */
sealed interface AirPlayState {
    data object Off : AirPlayState
    data object Starting : AirPlayState
    data class On(val name: String, val port: Int) : AirPlayState
    data class Failed(val reason: String) : AirPlayState
}

/**
 * Makes the TV show up as an AirPlay screen on iPhones and Macs: runs the receiver, publishes it
 * over Bonjour (mDNS) and opens [MirrorActivity] when someone starts mirroring.
 */
class AirPlayService : Service(), RaopServer.Listener {
    private val server = RaopServer(this)
    private var audio: AudioOut? = null
    private val registrations = mutableListOf<NsdManager.RegistrationListener>()

    /** Renaming the TV (Settings → Device Preferences → About → Device name) renames it for AirPlay too. */
    private val nameObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            val current = (state as? AirPlayState.On)?.name ?: return
            if (name(this@AirPlayService) != current) {
                Log.i(TAG, "TV renamed, announcing AirPlay again")
                tearDown()
                setUp()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        setUp()
        contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.DEVICE_NAME), false, nameObserver)
    }

    private fun setUp() {
        state = AirPlayState.Starting
        val prefs = getSharedPreferences("airplay", MODE_PRIVATE)
        val seed = prefs.getString("seed", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
            ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
        // Android hides the real MAC from apps, so make up a stable, locally administered one.
        val deviceId = prefs.getString("device_id", null) ?: ByteArray(6).let { mac ->
            SecureRandom().nextBytes(mac)
            mac[0] = ((mac[0].toInt() and 0xFC) or 0x02).toByte()
            mac.joinToString(":") { "%02X".format(it) }
        }
        prefs.edit()
            .putString("seed", Base64.encodeToString(seed, Base64.NO_WRAP))
            .putString("device_id", deviceId)
            .apply()

        val name = name(this)
        val pk = server.start(seed) ?: run {
            Log.e(TAG, "AirPlay receiver failed to start")
            state = AirPlayState.Failed("The receiver could not start")
            return
        }
        val pkHex = pk.joinToString("") { "%02x".format(it) }

        val airplay = linkedMapOf(
            "deviceid" to deviceId,
            "features" to "0x%X".format(FEATURES),
            "flags" to "0x4",
            "model" to MODEL,
            "pk" to pkHex,
            "pi" to "2e388006-13ba-4041-9a67-25dd4a43d536",
            "srcvers" to SOURCE_VERSION,
            "vv" to "2",
        )
        val raop = linkedMapOf(
            "ch" to "2", "cn" to "0,1,2,3", "da" to "true", "et" to "0,3,5", "vv" to "2",
            "ft" to "0x%X".format(FEATURES), "am" to MODEL, "md" to "0,1,2", "rhd" to "5.6.0.0",
            "pw" to "false", "sr" to "44100", "ss" to "16", "sv" to "false", "tp" to "UDP",
            "txtvers" to "1", "sf" to "0x4", "vs" to SOURCE_VERSION, "vn" to "65537", "pk" to pkHex,
        )
        server.setInfo(name, deviceId, FEATURES, 1920, 1080, 30, txtBytes(airplay), txtBytes(raop))
        val port = server.listen()
        if (port == 0) {
            Log.e(TAG, "AirPlay receiver could not open a port")
            state = AirPlayState.Failed("No network port is free")
            return
        }
        Log.i(TAG, "AirPlay receiver '$name' ($deviceId) on port $port")
        register(name, "_airplay._tcp", port, airplay)
        register(deviceId.replace(":", "") + "@" + name, "_raop._tcp", port, raop)
        state = AirPlayState.On(name, port)
    }

    private fun tearDown() {
        val nsd = getSystemService(NsdManager::class.java)
        registrations.forEach { runCatching { nsd.unregisterService(it) } }
        registrations.clear()
        Mirror.end()
        server.stop()
        audio?.release()
        audio = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService() needs its own startForeground(), even while already running;
        // otherwise Android kills the app ~10 s later. Kamar asks again each time the home screen opens.
        startInForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(nameObserver)
        tearDown()
        state = AirPlayState.Off
        super.onDestroy()
    }

    private fun register(name: String, type: String, port: Int, txt: Map<String, String>) {
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = type
            setPort(port)
            txt.forEach { (k, v) -> setAttribute(k, v) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { Log.i(TAG, "Published ${info.serviceName} $type") }
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) {
                Log.e(TAG, "Publishing $type failed: $error")
                state = AirPlayState.Failed("Could not announce the TV on the network (error $error)")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
        }
        getSystemService(NsdManager::class.java).registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        registrations += listener
    }

    // RaopServer.Listener — native threads.

    override fun onVideo(data: ByteArray, type: Int, ptsUs: Long) {
        val frame = VideoFrame(data, type == 0, ptsUs)
        if (frame.isConfig && !Mirror.active) {
            Mirror.begin()
            Mirror.offer(frame)
            startActivity(Intent(this, MirrorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else if (Mirror.active) {
            Mirror.offer(frame)
        }
    }

    override fun onVideoStop() {
        Mirror.end()
        audio?.flush()
    }

    override fun onAudio(pcm: ShortArray) {
        (audio ?: AudioOut().also { audio = it }).write(pcm)
    }

    override fun onVolume(db: Float) {
        audio?.setVolume(db)
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "AirPlay", NotificationManager.IMPORTANCE_MIN))
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setContentTitle("AirPlay is on")
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, notification)
        }
    }

    companion object {
        private const val TAG = "AirPlay"
        private const val CHANNEL = "airplay"
        const val DEFAULT_NAME = "Kamar TV"

        /** Screen mirroring + audio, legacy pairing (bit 27) on — same set as UxPlay. */
        private const val FEATURES = 0x5A7FFEE6L
        private const val MODEL = "AppleTV3,2"
        private const val SOURCE_VERSION = "220.68"

        /** Live receiver state, for the AirPlay page. */
        var state by mutableStateOf<AirPlayState>(AirPlayState.Off)
            private set

        private fun prefs(context: Context) = context.getSharedPreferences("airplay", Context.MODE_PRIVATE)

        fun isEnabled(context: Context) = prefs(context).getBoolean("enabled", true)

        /** The TV's own device name (also used for Bluetooth and Chromecast), as iPhones and Macs list it. */
        fun name(context: Context): String {
            val name = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.trim()
            return if (name.isNullOrEmpty()) DEFAULT_NAME else name.fitBonjour()
        }

        /**
         * Bonjour names are limited to 63 bytes of UTF-8, and the RAOP one adds a 13-byte
         * "<MAC>@" prefix; cut longer names on a character boundary.
         */
        private fun String.fitBonjour(): String {
            var end = length
            while (substring(0, end).toByteArray().size > 50) end--
            return substring(0, end).trimEnd()
        }

        /** Starts the receiver unless AirPlay was turned off. */
        fun start(context: Context) {
            if (!isEnabled(context)) return
            runCatching { context.startForegroundService(Intent(context, AirPlayService::class.java)) }
                .onFailure { Log.e(TAG, "Could not start AirPlay", it) }
        }

        fun setEnabled(context: Context, enabled: Boolean) {
            prefs(context).edit().putBoolean("enabled", enabled).apply()
            if (enabled) start(context) else context.stopService(Intent(context, AirPlayService::class.java))
        }

        /** DNS TXT wire format: each "key=value" prefixed by its length. */
        private fun txtBytes(txt: Map<String, String>): ByteArray {
            val out = ByteArrayOutputStream()
            txt.forEach { (k, v) ->
                val entry = "$k=$v".toByteArray()
                out.write(entry.size)
                out.write(entry)
            }
            return out.toByteArray()
        }
    }
}
