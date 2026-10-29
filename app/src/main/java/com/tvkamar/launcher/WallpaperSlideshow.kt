package com.tvkamar.launcher

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import kotlin.random.Random

/** Where the Home wallpaper comes from. */
enum class WallpaperSource(val label: String) {
    Artwork("Off · your picture or the artwork"),
    Reddit("Reddit slideshow"),
    Unsplash("Unsplash slideshow"),
}

/** Reddit listing order, as in Arc Launcher. */
enum class RedditSort(val label: String, val rssPath: String) {
    Hot("Hot", "hot.rss"),
    TopDay("Top today", "top.rss?t=day"),
    TopWeek("Top this week", "top.rss?t=week"),
    TopMonth("Top this month", "top.rss?t=month"),
    TopAll("Top of all time", "top.rss?t=all"),
    New("New", "new.rss"),
}

/** Unsplash editorial topics, plus a Milky Way collection since there is no space topic. */
enum class UnsplashCategory(val label: String, val param: String, val id: String) {
    Wallpapers("Wallpapers", "topics", "bo8jQKTaE0Y"),
    Nature("Nature", "topics", "6sMVjTLSkeQ"),
    Travel("Travel", "topics", "Fzo3zuOHN6w"),
    Architecture("Architecture", "topics", "M8jVbLbTRws"),
    Renders("3D Renders", "topics", "CDwuwXJAbEw"),
    Textures("Textures", "topics", "iUIsnVtjB0Y"),
    MilkyWay("Milky Way", "collections", "2286207"),
}

val POPULAR_SUBREDDITS = listOf(
    "wallpapers", "wallpaper", "EarthPorn", "spaceporn", "CityPorn", "MinimalWallpaper", "ImaginaryLandscapes", "Art",
)

/** Change intervals offered, in minutes (5 min to 24 h). */
val SLIDESHOW_INTERVALS = listOf(5, 15, 30, 60, 180, 720, 1440)

data class WallpaperSettings(
    val source: WallpaperSource = WallpaperSource.Artwork,
    val intervalMin: Int = 30,
    val subreddits: Set<String> = setOf("wallpapers", "wallpaper", "EarthPorn", "spaceporn"),
    val redditSort: RedditSort = RedditSort.Hot,
    val unsplashKey: String = "",
    val dayCategories: Set<UnsplashCategory> = setOf(UnsplashCategory.Nature, UnsplashCategory.Wallpapers, UnsplashCategory.Renders),
    val nightCategories: Set<UnsplashCategory> = setOf(UnsplashCategory.MilkyWay),
)

/** A downloaded slideshow picture and who made it. */
data class SlideshowImage(val file: File, val credit: String)

/**
 * Arc Launcher's Reddit and Unsplash wallpaper slideshows. Pictures are downloaded into the
 * app's files; only the newest two are kept, so the last one still shows when offline.
 * Network calls block, so use it off the main thread.
 */
class WallpaperSlideshow(context: Context) {
    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "slideshow").apply { mkdirs() }

    /** Fetched Reddit posts per subreddit and sort, reused for 20 min to go easy on Reddit. */
    private val postCache = mutableMapOf<String, Pair<Long, List<RedditPost>>>()
    private var rateLimitedUntil = 0L

    fun settings(): WallpaperSettings {
        val d = WallpaperSettings()
        return WallpaperSettings(
            source = enumOr(prefs.getString("wp.source", null), d.source),
            intervalMin = prefs.getInt("wp.interval", d.intervalMin),
            subreddits = prefs.getStringSet("wp.subreddits", null) ?: d.subreddits,
            redditSort = enumOr(prefs.getString("wp.sort", null), d.redditSort),
            unsplashKey = prefs.getString("wp.unsplashKey", null) ?: d.unsplashKey,
            dayCategories = categories("wp.day") ?: d.dayCategories,
            nightCategories = categories("wp.night") ?: d.nightCategories,
        )
    }

    fun save(s: WallpaperSettings) {
        prefs.edit()
            .putString("wp.source", s.source.name)
            .putInt("wp.interval", s.intervalMin)
            .putStringSet("wp.subreddits", s.subreddits)
            .putString("wp.sort", s.redditSort.name)
            .putString("wp.unsplashKey", s.unsplashKey)
            .putStringSet("wp.day", s.dayCategories.map { it.name }.toSet())
            .putStringSet("wp.night", s.nightCategories.map { it.name }.toSet())
            .apply()
    }

    private fun categories(key: String) = prefs.getStringSet(key, null)
        ?.mapNotNull { name -> UnsplashCategory.entries.firstOrNull { it.name == name } }?.toSet()

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    /** When the current picture was fetched, to resume the interval after a restart. */
    var lastFetch: Long
        get() = prefs.getLong("wp.lastFetch", 0L)
        private set(value) = prefs.edit().putLong("wp.lastFetch", value).apply()

    /** The picture on screen last time, shown straight away before (or without) the network. */
    fun current(source: WallpaperSource): SlideshowImage? {
        if (prefs.getString("wp.currentSource", null) != source.name) return null
        val file = dir.listFiles { f -> f.name.endsWith(".jpg") }?.maxByOrNull { it.lastModified() } ?: return null
        return SlideshowImage(file, prefs.getString("wp.credit", null).orEmpty())
    }

    /** Downloads the next picture for [s]; null on any failure (the current one stays). */
    fun next(s: WallpaperSettings): SlideshowImage? {
        val image = runCatching {
            when (s.source) {
                WallpaperSource.Artwork -> null
                WallpaperSource.Reddit -> nextReddit(s)
                WallpaperSource.Unsplash -> nextUnsplash(s)
            }
        }.onFailure { Log.w(TAG, "slideshow", it) }.getOrNull() ?: return null
        prefs.edit().putString("wp.credit", image.credit).putString("wp.currentSource", s.source.name).apply()
        lastFetch = System.currentTimeMillis()
        prune()
        return image
    }

    // --- Reddit ---------------------------------------------------------------

    private fun nextReddit(s: WallpaperSettings): SlideshowImage? {
        val subs = s.subreddits.ifEmpty { WallpaperSettings().subreddits }.toList()
        val sub = subs[Random.nextInt(subs.size)]
        for (post in redditPosts(sub, s.redditSort).shuffled().take(6)) {
            val file = download(post.url, "r_${post.id}") ?: continue
            // Landscape only: portrait pictures look poor on a TV.
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, o)
            if (o.outWidth <= 0 || o.outWidth < o.outHeight) { file.delete(); continue }
            return SlideshowImage(file, "r/$sub · u/${post.author}")
        }
        return null
    }

    private data class RedditPost(val id: String, val url: String, val author: String)

    /** Image posts from the public RSS feed (the JSON API wants a login). */
    private fun redditPosts(sub: String, sort: RedditSort): List<RedditPost> {
        val key = "${sub.lowercase()}|$sort"
        postCache[key]?.let { (at, posts) -> if (System.currentTimeMillis() - at < 20 * 60_000L) return posts }
        if (System.currentTimeMillis() < rateLimitedUntil) return emptyList()

        val sep = if ('?' in sort.rssPath) '&' else '?'
        val conn = open("https://www.reddit.com/r/$sub/${sort.rssPath}${sep}limit=40", RSS_AGENT)
        conn.setRequestProperty("Accept", "application/atom+xml,application/xml,text/xml")
        if (conn.responseCode == 429) {
            rateLimitedUntil = System.currentTimeMillis() + 60_000L
            return emptyList()
        }
        if (conn.responseCode != 200) return emptyList()
        val xml = conn.inputStream.bufferedReader().use { it.readText() }

        val posts = Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
            val block = unescape(m.groupValues[1])
            val id = Regex("<id>(?:t3_)?(.*?)</id>").find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val author = Regex("<author>\\s*<name>(?:/u/)?(.*?)</name>").find(block)?.groupValues?.get(1).orEmpty()
            val link = Regex("href=[\"'](https?://[^\\s\"<>]+)[\"']>\\[link]").find(block)?.groupValues?.get(1)
                ?.takeIf(::isImageUrl)
            val url = link ?: Regex("https://(?:i\\.redd\\.it|i\\.imgur\\.com)/[\\w.-]+\\.(?:jpg|jpeg|png|webp)")
                .find(block)?.value ?: return@mapNotNull null
            RedditPost(id, url, author)
        }.toList()
        if (posts.isNotEmpty()) postCache[key] = System.currentTimeMillis() to posts
        return posts
    }

    private fun isImageUrl(url: String): Boolean {
        val lower = url.lowercase().substringBefore('?')
        return listOf(".jpg", ".jpeg", ".png", ".webp").any(lower::endsWith) ||
            URL(url).host in setOf("i.redd.it", "i.imgur.com", "preview.redd.it")
    }

    private fun unescape(s: String) = s.replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    // --- Unsplash -------------------------------------------------------------

    private fun nextUnsplash(s: WallpaperSettings): SlideshowImage? {
        if (s.unsplashKey.isBlank()) return null
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val night = hour >= 19 || hour < 6
        val defaults = WallpaperSettings()
        val pool = (if (night) s.nightCategories else s.dayCategories)
            .ifEmpty { if (night) defaults.nightCategories else defaults.dayCategories }.toList()
        val category = pool[Random.nextInt(pool.size)]

        val query = "${category.param}=${enc(category.id)}&orientation=landscape&content_filter=high"
        val conn = open("https://api.unsplash.com/photos/random?$query", AGENT)
        conn.setRequestProperty("Authorization", "Client-ID ${s.unsplashKey.trim()}")
        conn.setRequestProperty("Accept-Version", "v1")
        if (conn.responseCode != 200) {
            Log.w(TAG, "Unsplash ${conn.responseCode}")
            return null
        }
        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val id = json.getString("id")
        val url = json.getJSONObject("urls").getString("raw") + "&w=1920&h=1080&fit=crop&fm=jpg&q=80"
        val author = json.getJSONObject("user").getString("name")
        val file = download(url, "u_$id") ?: return null

        // Required by the Unsplash API guidelines when a photo is used.
        runCatching {
            val ping = open(json.getJSONObject("links").getString("download_location"), AGENT)
            ping.setRequestProperty("Authorization", "Client-ID ${s.unsplashKey.trim()}")
            ping.responseCode
        }
        return SlideshowImage(file, "Photo by $author on Unsplash")
    }

    // --- Files ------------------------------------------------------------------

    private fun download(url: String, name: String): File? {
        val conn = open(url, AGENT)
        if (conn.responseCode != 200) return null
        // Write under a temp name first so a half-written file is never shown.
        val tmp = File(dir, "$name.part")
        conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        val file = File(dir, "$name.jpg")
        return if (tmp.length() > 0 && tmp.renameTo(file)) file.also { it.setLastModified(System.currentTimeMillis()) } else null
    }

    /** Keeps the picture on screen and the one before it. */
    private fun prune() {
        val files = dir.listFiles() ?: return
        files.filter { it.name.endsWith(".part") }.forEach { it.delete() }
        files.filter { it.name.endsWith(".jpg") }.sortedByDescending { it.lastModified() }.drop(2).forEach { it.delete() }
    }

    private fun open(url: String, agent: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 20_000
        setRequestProperty("User-Agent", agent)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val TAG = "KamarWallpaper"
        private const val AGENT = "android:com.tvkamar.launcher:v1.0"
        // Reddit serves its RSS feeds to feed readers without a login.
        private const val RSS_AGENT = "Feedly/1.0 (+http://www.feedly.com/fetcher.html; like FeedFetcher-Google)"
    }
}
