package com.tvkamar.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL

/**
 * What the viewer has been watching, as recorded by [MediaWatcher] from apps' media sessions.
 * Stored as an index file plus one JPEG of artwork per title in the app's private files.
 */
class WatchHistory private constructor(private val context: Context) {

    data class Entry(
        val packageName: String,
        val title: String,
        val subtitle: String?,
        /** Artwork file in [dir], if the app shared any. */
        val artFile: String?,
        val watchedAt: Long,
        val positionMs: Long,
        val durationMs: Long,
    ) {
        val key get() = "$packageName|$title"
        val progress: Float? get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else null
    }

    private val dir = File(context.filesDir, "history").apply { mkdirs() }
    private val index = File(dir, "index.json")

    @Synchronized
    fun entries(): List<Entry> = runCatching {
        val array = JSONArray(index.readText())
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Entry(
                packageName = o.getString("pkg"),
                title = o.getString("title"),
                subtitle = o.optString("subtitle").ifEmpty { null },
                artFile = o.optString("art").ifEmpty { null },
                watchedAt = o.getLong("at"),
                positionMs = o.optLong("pos"),
                durationMs = o.optLong("dur"),
            )
        }
    }.getOrDefault(emptyList())

    fun artFile(entry: Entry): File? = entry.artFile?.let { File(dir, it) }?.takeIf { it.exists() }

    /**
     * Records that [title] is playing in [packageName]. Artwork is saved only the first time a
     * title is seen. Does network/disk work, so call off the main thread.
     */
    @Synchronized
    fun record(
        packageName: String,
        title: String,
        subtitle: String?,
        art: Bitmap?,
        artUri: String?,
        positionMs: Long,
        durationMs: Long,
    ) {
        val current = entries()
        val existing = current.firstOrNull { it.packageName == packageName && it.title == title }
        val artName = existing?.artFile?.takeIf { File(dir, it).exists() }
            ?: saveArt(art ?: artUri?.let(::fetchArt), "$packageName|$title")
        val entry = Entry(packageName, title, subtitle, artName, System.currentTimeMillis(), positionMs, durationMs)
        val updated = (listOf(entry) + current.filter { it.key != entry.key }).take(MAX_ENTRIES)
        write(updated)
        // Drop artwork no entry points at any more.
        val kept = updated.mapNotNull { it.artFile }.toSet()
        dir.listFiles { f -> f.name.endsWith(".jpg") && f.name !in kept }?.forEach { it.delete() }
    }

    @Synchronized
    fun remove(key: String) = write(entries().filter { it.key != key })

    private fun write(entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { e ->
            array.put(
                JSONObject()
                    .put("pkg", e.packageName)
                    .put("title", e.title)
                    .put("subtitle", e.subtitle ?: "")
                    .put("art", e.artFile ?: "")
                    .put("at", e.watchedAt)
                    .put("pos", e.positionMs)
                    .put("dur", e.durationMs),
            )
        }
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(array.toString())
        tmp.renameTo(index)
    }

    private fun saveArt(bitmap: Bitmap?, key: String): String? {
        bitmap ?: return null
        val scale = minOf(1f, 1280f / bitmap.width)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else bitmap
        val name = "${Integer.toHexString(key.hashCode())}.jpg"
        return runCatching {
            File(dir, name).outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            name
        }.getOrNull()
    }

    private fun fetchArt(uri: String): Bitmap? = runCatching {
        when {
            uri.startsWith("http://") || uri.startsWith("https://") -> {
                val connection = URL(uri).openConnection().apply {
                    connectTimeout = 5_000
                    readTimeout = 5_000
                }
                connection.getInputStream().use(BitmapFactory::decodeStream)
            }
            else -> context.contentResolver.openInputStream(Uri.parse(uri))?.use(BitmapFactory::decodeStream)
        }
    }.getOrNull()

    companion object {
        private const val MAX_ENTRIES = 20

        @Volatile private var instance: WatchHistory? = null

        fun get(context: Context): WatchHistory =
            instance ?: synchronized(this) { instance ?: WatchHistory(context.applicationContext).also { instance = it } }
    }
}
