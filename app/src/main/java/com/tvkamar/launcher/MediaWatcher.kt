package com.tvkamar.launcher

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import java.util.concurrent.Executors

/**
 * Reads other apps' media sessions (YouTube, Netflix, ...) and records what is playing or paused
 * into [WatchHistory], which feeds the Home carousel.
 *
 * Needs notification access, granted once over adb:
 *   adb shell cmd notification allow_listener com.tvkamar.launcher/com.tvkamar.launcher.MediaWatcher
 */
object SessionRecorder {
    private const val THROTTLE_MS = 30_000L
    private val IGNORED = setOf("com.android.bluetooth", "com.tvkamar.launcher")

    /** Last time each title was written, to avoid rewriting history on every state tick. */
    private val lastWrite = mutableMapOf<String, Long>()

    fun component(context: Context) = ComponentName(context, MediaWatcher::class.java)

    /** Current sessions, or empty if notification access hasn't been granted. */
    fun activeSessions(context: Context): List<MediaController> = runCatching {
        context.getSystemService(MediaSessionManager::class.java).getActiveSessions(component(context))
    }.getOrDefault(emptyList())

    /** Records every playing or paused session right now. Does disk/network work: call off the main thread. */
    fun recordAll(context: Context) = activeSessions(context).forEach { record(context, it, force = true) }

    /** Saves the session's title. [force] writes even if the same title was saved moments ago. */
    @Synchronized
    fun record(context: Context, controller: MediaController, force: Boolean) {
        if (controller.packageName in IGNORED) return
        val state = controller.playbackState ?: return
        if (state.state != PlaybackState.STATE_PLAYING && state.state != PlaybackState.STATE_PAUSED) return
        val metadata = controller.metadata ?: return
        val description = metadata.description
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: description?.title?.toString()
        if (title.isNullOrBlank()) return

        val key = "${controller.packageName}|$title"
        val now = SystemClock.elapsedRealtime()
        if (!force && now - (lastWrite[key] ?: 0L) < THROTTLE_MS) return
        lastWrite[key] = now

        val subtitle = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: description?.subtitle?.toString()
        val art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val artUri = metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            ?: description?.iconUri?.toString()

        runCatching {
            WatchHistory.get(context).record(
                controller.packageName, title, subtitle?.takeIf { it.isNotBlank() }, art, artUri,
                currentPosition(state), metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
            )
        }
    }

    private fun currentPosition(state: PlaybackState): Long {
        if (state.position < 0) return 0
        if (state.state != PlaybackState.STATE_PLAYING) return state.position
        val elapsed = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
        return state.position + (elapsed * state.playbackSpeed).toLong()
    }
}

/**
 * Live recording while other apps play. Android only binds this on devices that aren't
 * low-RAM; on this TV (ro.config.low_ram=true) it never runs, and Kamar instead calls
 * [SessionRecorder.recordAll] whenever the home screen comes back. The component is still
 * what notification access is granted to.
 */
class MediaWatcher : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val tracked = mutableMapOf<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private var sessions: MediaSessionManager? = null

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { track(it.orEmpty()) }

    override fun onListenerConnected() {
        val manager = getSystemService(MediaSessionManager::class.java) ?: return
        sessions = manager
        runCatching {
            manager.addOnActiveSessionsChangedListener(sessionsChanged, SessionRecorder.component(this), handler)
            track(manager.getActiveSessions(SessionRecorder.component(this)))
        }
    }

    override fun onListenerDisconnected() {
        sessions?.removeOnActiveSessionsChangedListener(sessionsChanged)
        tracked.values.forEach { (controller, callback) -> controller.unregisterCallback(callback) }
        tracked.clear()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun track(controllers: List<MediaController>) {
        val live = controllers.map { it.sessionToken }.toSet()
        (tracked.keys - live).forEach { token -> tracked.remove(token)?.let { (c, cb) -> c.unregisterCallback(cb) } }
        controllers.filter { it.sessionToken !in tracked }.forEach { controller ->
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = record(controller, force = false)
                override fun onMetadataChanged(metadata: MediaMetadata?) = record(controller, force = true)
            }
            controller.registerCallback(callback, handler)
            tracked[controller.sessionToken] = controller to callback
            record(controller, force = true)
        }
    }

    private fun record(controller: MediaController, force: Boolean) =
        io.execute { SessionRecorder.record(this, controller, force) }
}
