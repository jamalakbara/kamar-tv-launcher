package com.tvkamar.launcher

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.random.Random

/** Where the Home wallpaper comes from. */
enum class WallpaperSource(val label: String) {
    Artwork("Off · your picture or the artwork"),
    Reddit("Reddit slideshow"),
    Wallhaven("Wallhaven slideshow"),
    Aerials("Apple TV Aerials (video)"),
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

/** Wallhaven listing order. */
enum class WallhavenSort(val label: String, val query: String) {
    TopMonth("Top this month", "sorting=toplist&topRange=1M"),
    TopYear("Top this year", "sorting=toplist&topRange=1y"),
    Favorites("Most favourited", "sorting=favorites"),
    Views("Most viewed", "sorting=views"),
    Random("Random", "sorting=random"),
}

/** Wallhaven search terms. */
enum class WallhavenTopic(val label: String, val query: String) {
    Landscapes("Landscapes", "landscape"),
    Nature("Nature", "nature"),
    Space("Space", "space"),
    Cities("Cities", "city"),
    Minimal("Minimal", "minimalism"),
    Abstract("Abstract", "abstract"),
    DigitalArt("Digital art", "digital art"),
}

/** Categories of Apple's aerial videos, by their key in Apple's list. */
enum class AerialCategory(val label: String, val key: String) {
    Landscapes("Landscapes", "AerialCategoryLandscapes"),
    Cities("Cities", "AerialCategoryCities"),
    Space("Space", "AerialCategorySpace"),
    Underwater("Underwater", "AerialCategoryUnderwater"),
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
    val wallhavenSort: WallhavenSort = WallhavenSort.TopYear,
    val wallhavenTopics: Set<WallhavenTopic> = setOf(WallhavenTopic.Landscapes, WallhavenTopic.Nature, WallhavenTopic.Space),
    val aerialCategories: Set<AerialCategory> = AerialCategory.entries.toSet(),
)

/** A downloaded slideshow picture and who made it. */
data class SlideshowImage(val file: File, val credit: String)

/** An Apple TV aerial video, streamed rather than downloaded. */
data class AerialVideo(val id: String, val url: String, val credit: String)

/**
 * Wallpaper slideshows: Reddit (as in Arc Launcher) and Wallhaven pictures, or Apple TV aerial
 * videos. Pictures are downloaded into the app's files; only the newest two are kept, so the
 * last one still shows when offline. Network calls block, so use it off the main thread.
 */
class WallpaperSlideshow(context: Context) {
    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "slideshow").apply { mkdirs() }

    /** Fetched Reddit posts per subreddit and sort, reused for 20 min to go easy on Reddit. */
    private val postCache = mutableMapOf<String, Pair<Long, List<RedditPost>>>()
    private var rateLimitedUntil = 0L

    /** Wallhaven results per topic, sort and page (same 20 min), and how many pages each search has. */
    private val wallhavenCache = mutableMapOf<String, Pair<Long, List<WallhavenImage>>>()
    private val wallhavenPages = mutableMapOf<String, Int>()

    fun settings(): WallpaperSettings {
        val d = WallpaperSettings()
        return WallpaperSettings(
            source = enumOr(prefs.getString("wp.source", null), d.source),
            intervalMin = prefs.getInt("wp.interval", d.intervalMin),
            subreddits = prefs.getStringSet("wp.subreddits", null) ?: d.subreddits,
            redditSort = enumOr(prefs.getString("wp.sort", null), d.redditSort),
            wallhavenSort = enumOr(prefs.getString("wp.whSort", null), d.wallhavenSort),
            wallhavenTopics = enumSet<WallhavenTopic>("wp.whTopics") ?: d.wallhavenTopics,
            aerialCategories = enumSet<AerialCategory>("wp.aerials") ?: d.aerialCategories,
        )
    }

    fun save(s: WallpaperSettings) {
        prefs.edit()
            .putString("wp.source", s.source.name)
            .putInt("wp.interval", s.intervalMin)
            .putStringSet("wp.subreddits", s.subreddits)
            .putString("wp.sort", s.redditSort.name)
            .putString("wp.whSort", s.wallhavenSort.name)
            .putStringSet("wp.whTopics", s.wallhavenTopics.map { it.name }.toSet())
            .putStringSet("wp.aerials", s.aerialCategories.map { it.name }.toSet())
            // Left over from the Unsplash slideshow, which is gone.
            .remove("wp.unsplashKey").remove("wp.day").remove("wp.night")
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumSet(key: String) = prefs.getStringSet(key, null)
        ?.mapNotNull { name -> enumValues<E>().firstOrNull { it.name == name } }?.toSet()

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
                WallpaperSource.Artwork, WallpaperSource.Aerials -> null
                WallpaperSource.Reddit -> nextReddit(s)
                WallpaperSource.Wallhaven -> nextWallhaven(s)
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

    // --- Wallhaven -----------------------------------------------------------

    private data class WallhavenImage(val id: String, val url: String)

    private fun nextWallhaven(s: WallpaperSettings): SlideshowImage? {
        val topics = s.wallhavenTopics.ifEmpty { WallpaperSettings().wallhavenTopics }.toList()
        val topic = topics[Random.nextInt(topics.size)]
        // Skip the pictures still on disk: the one on screen and the one before it.
        val fresh = wallhavenImages(topic, s.wallhavenSort).filterNot { File(dir, "w_${it.id}.jpg").exists() }
        for (image in fresh.shuffled().take(4)) {
            val file = download(image.url, "w_${image.id}") ?: continue
            return SlideshowImage(file, "${topic.label} · wallhaven.cc/w/${image.id}")
        }
        return null
    }

    /** General, safe-for-work, 16:9 pictures of at least 1080p, from a random one of the first few pages. */
    private fun wallhavenImages(topic: WallhavenTopic, sort: WallhavenSort): List<WallhavenImage> {
        val search = "$topic|$sort"
        val page = Random.nextInt(1, (wallhavenPages[search] ?: 1).coerceAtMost(5) + 1)
        val key = "$search|$page"
        if (sort != WallhavenSort.Random) {
            wallhavenCache[key]?.let { (at, images) -> if (System.currentTimeMillis() - at < 20 * 60_000L) return images }
        }

        val query = "q=${enc(topic.query)}&categories=100&purity=100&atleast=1920x1080&ratios=16x9&${sort.query}&page=$page"
        val conn = open("https://wallhaven.cc/api/v1/search?$query", AGENT)
        if (conn.responseCode != 200) {
            Log.w(TAG, "Wallhaven ${conn.responseCode}")
            return emptyList()
        }
        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        wallhavenPages[search] = json.getJSONObject("meta").optInt("last_page", 1)
        val data = json.getJSONArray("data")
        val images = (0 until data.length()).map { i ->
            val item = data.getJSONObject(i)
            WallhavenImage(item.getString("id"), item.getString("path"))
        }
        if (images.isNotEmpty()) wallhavenCache[key] = System.currentTimeMillis() to images
        return images
    }

    // --- Apple TV Aerials -----------------------------------------------------

    /** A random aerial from [s]'s categories, other than the one [currentId] names; null when offline. */
    fun nextAerial(s: WallpaperSettings, currentId: String?): AerialVideo? = runCatching {
        val categories = s.aerialCategories.ifEmpty { AerialCategory.entries.toSet() }.map { it.key }.toSet()
        val all = aerials()
        val pool = all.filter { it.second in categories }.ifEmpty { all }.map { it.first }
        pool.filter { it.id != currentId }.ifEmpty { pool }.randomOrNull()
    }.onFailure { Log.w(TAG, "aerials", it) }.getOrNull()

    /** Apple's list of aerials with each one's category, cached for a week. */
    private fun aerials(): List<Pair<AerialVideo, String>> {
        val cache = File(dir, "aerials.json")
        val stale = System.currentTimeMillis() - cache.lastModified() > 7 * 24 * 60 * 60_000L
        if (!cache.exists() || stale) {
            runCatching { fetchAerialList()?.let(cache::writeText) }.onFailure { Log.w(TAG, "aerial list", it) }
        }
        val json = JSONObject(cache.readText())
        val categoryKeys = json.getJSONArray("categories").let { list ->
            (0 until list.length()).associate { i -> list.getJSONObject(i).let { it.getString("id") to it.getString("localizedNameKey") } }
        }
        val assets = json.getJSONArray("assets")
        return (0 until assets.length()).mapNotNull { i ->
            val a = assets.getJSONObject(i)
            // 1080p H.264: every TV decodes it, and this one only has a 720p panel anyway.
            val url = a.optString("url-1080-H264").ifEmpty { return@mapNotNull null }
            val category = a.optJSONArray("categories")?.optString(0)?.let(categoryKeys::get).orEmpty()
            AerialVideo(a.getString("id"), url.replace("https://", "http://"), "${a.optString("accessibilityLabel")} · Apple TV Aerial") to category
        }
    }

    /** entries.json out of the tar the Apple TV downloads (about 2 MB). */
    private fun fetchAerialList(): String? {
        val conn = open(AERIAL_LIST, AGENT)
        if (conn.responseCode != 200) return null
        val tar = conn.inputStream.use { it.readBytes() }
        var at = 0
        while (at + 512 <= tar.size) {
            val name = String(tar, at, 100, Charsets.US_ASCII).substringBefore('\u0000')
            if (name.isEmpty()) break
            val size = String(tar, at + 124, 12, Charsets.US_ASCII).trim('\u0000', ' ').toLong(8).toInt()
            if (name.removePrefix("./") == "entries.json") return String(tar, at + 512, size, Charsets.UTF_8)
            at += 512 + (size + 511) / 512 * 512
        }
        return null
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
        // Over plain HTTP: Apple's servers use a certificate from Apple's own root, which Android doesn't trust.
        private const val AERIAL_LIST = "http://sylvan.apple.com/Aerials/resources-16.tar"
        // Reddit serves its RSS feeds to feed readers without a login.
        private const val RSS_AGENT = "Feedly/1.0 (+http://www.feedly.com/fetcher.html; like FeedFetcher-Google)"
    }
}
