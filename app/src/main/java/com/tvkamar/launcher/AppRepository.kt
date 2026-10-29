package com.tvkamar.launcher

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.Uri
import android.provider.Settings
import android.util.DisplayMetrics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import java.util.concurrent.TimeUnit

/** One launchable app on the home screen. */
data class TvApp(
    val packageName: String,
    val label: String,
    /** 16:9 TV banner at the best density the app ships, if it has one. */
    val banner: ImageBitmap?,
    /** Tiny copy of the artwork; drawn stretched it gives a soft blurred backdrop. */
    val backdrop: ImageBitmap,
    val icon: ImageBitmap,
    /** Vibrant colour from the artwork, used for glows behind the hero. */
    val accent: Color,
    /** e.g. "Video · App" */
    val subtitle: String,
    val description: String,
    val isSystem: Boolean,
    val launchIntent: Intent,
)

/** A TV source: the tuner (Live TV) or an HDMI port. */
data class TvSource(val id: String, val label: String, val isTuner: Boolean, val intent: Intent)

/** A title from the watch history, ready to show in the carousel. */
data class WatchedItem(
    val key: String,
    val app: TvApp,
    val title: String,
    val subtitle: String?,
    /** Artwork the app shared for this title, if any. */
    val art: ImageBitmap?,
    val backdrop: ImageBitmap,
    val accent: Color,
    val watchedAt: Long,
    /** 0..1 through the title, when the app reported a duration. */
    val progress: Float?,
)

data class LauncherData(
    val apps: List<TvApp>,
    /** Recently watched titles, newest first. */
    val watched: List<WatchedItem>,
    /** Apps by most recent use, newest first. */
    val recent: List<TvApp>,
    val sources: List<TvSource>,
    val settingsIntent: Intent,
)

/** Packages shown first on a fresh install, in this order. */
private val DEFAULT_ORDER = listOf(
    "com.google.android.youtube.tv",
    "com.netflix.ninja",
    "com.apple.atve.androidtv.appletv",
    "com.amazon.amazonvideo.livingroom",
    "com.android.vending",
)

private const val MAX_WATCHED = 10

private const val TV_SETTINGS = "com.android.tv.settings"

/** Short blurbs for the hero; apps rarely ship a description of their own. */
private val DESCRIPTIONS = mapOf(
    "com.google.android.youtube.tv" to "Videos, music and live streams from creators everywhere.",
    "com.netflix.ninja" to "Series, films and documentaries, ready when you are.",
    "com.apple.atve.androidtv.appletv" to "Apple Originals, blockbuster movies and shows in 4K HDR.",
    "com.amazon.amazonvideo.livingroom" to "Prime Video originals, movies and live sport.",
    "com.android.vending" to "Find new apps and games for your TV.",
    "com.tcl.tv" to "Watch broadcast channels from your antenna or cable.",
    "com.tcl.dashboard" to "Picture modes, sound and quick TV controls.",
    TV_SETTINGS to "Network, display, sound and system settings.",
)

/** Favourites first, Settings last, everything else alphabetical in between. */
private fun defaultRank(pkg: String): Int = when (val i = DEFAULT_ORDER.indexOf(pkg)) {
    -1 -> if (pkg == TV_SETTINGS) Int.MAX_VALUE else Int.MAX_VALUE - 1
    else -> i
}

class AppRepository(private val context: Context) {
    private val pm: PackageManager = context.packageManager
    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    /** Loads apps (in saved order, hidden ones removed), recents and TV sources. Call off the main thread. */
    fun load(): LauncherData {
        val apps = queryTvApps().associateBy { it.packageName }
        val order = savedOrder().filter { it in apps } +
            apps.values
                .filter { it.packageName !in savedOrder() }
                .sortedWith(compareBy({ defaultRank(it.packageName) }, { it.label.lowercase() }))
                .map { it.packageName }
        saveOrder(order)
        val hidden = hidden()
        val visible = order.filter { it !in hidden }.mapNotNull { apps[it] }
        return LauncherData(
            apps = visible,
            watched = watchHistory(apps),
            recent = recentlyUsed(visible),
            sources = tvSources(),
            settingsIntent = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun queryTvApps(): List<TvApp> {
        val leanback = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0,
        )
        return leanback
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { ri ->
                val ai = ri.activityInfo
                val bannerBitmap = hiResBanner(ai)
                val iconBitmap = ai.loadIcon(pm).toBitmapOr(192, 192)
                val art = bannerBitmap ?: iconBitmap
                val isSystem = ai.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
                TvApp(
                    packageName = ai.packageName,
                    label = ri.loadLabel(pm).toString(),
                    banner = bannerBitmap?.asImageBitmap(),
                    backdrop = Bitmap.createScaledBitmap(art, 32, 18, true).asImageBitmap(),
                    icon = iconBitmap.asImageBitmap(),
                    accent = accentOf(art),
                    subtitle = listOf(categoryName(ai.applicationInfo), if (isSystem) "Built-in" else "App").joinToString(" · "),
                    description = DESCRIPTIONS[ai.packageName]
                        ?: ai.applicationInfo.loadDescription(pm)?.toString()
                        ?: "Open ${ri.loadLabel(pm)} on your TV.",
                    isSystem = isSystem,
                    launchIntent = Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                        .setClassName(ai.packageName, ai.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                )
            }
    }

    /** Asks the app's resources for its xxxhdpi banner so it stays sharp when shown large. */
    private fun hiResBanner(ai: ActivityInfo): Bitmap? {
        val id = if (ai.banner != 0) ai.banner else ai.applicationInfo.banner
        if (id == 0) return null
        val drawable = runCatching {
            pm.getResourcesForApplication(ai.applicationInfo).getDrawableForDensity(id, DisplayMetrics.DENSITY_XXXHIGH, null)
        }.getOrNull() ?: return null
        return (drawable as? BitmapDrawable)?.bitmap ?: drawable.toBitmap(960, 540)
    }

    private fun categoryName(info: ApplicationInfo): String = when (info.category) {
        ApplicationInfo.CATEGORY_VIDEO -> "Video"
        ApplicationInfo.CATEGORY_AUDIO -> "Music"
        ApplicationInfo.CATEGORY_GAME -> "Game"
        ApplicationInfo.CATEGORY_IMAGE -> "Photos"
        ApplicationInfo.CATEGORY_NEWS -> "News"
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Productivity"
        else -> if (info.packageName == TV_SETTINGS) "System" else "Entertainment"
    }

    // --- Watch history -------------------------------------------------------

    private fun watchHistory(apps: Map<String, TvApp>): List<WatchedItem> {
        val history = WatchHistory.get(context)
        return history.entries().mapNotNull { entry ->
            val app = apps[entry.packageName] ?: return@mapNotNull null
            val art = history.artFile(entry)?.let { BitmapFactory.decodeFile(it.path) }
            WatchedItem(
                key = entry.key,
                app = app,
                title = entry.title,
                subtitle = entry.subtitle,
                art = art?.asImageBitmap(),
                backdrop = art?.let { Bitmap.createScaledBitmap(it, 32, 18, true).asImageBitmap() } ?: app.backdrop,
                accent = art?.let(::accentOf) ?: app.accent,
                watchedAt = entry.watchedAt,
                progress = entry.progress,
            )
        }.take(MAX_WATCHED)
    }

    fun forgetWatched(key: String) = WatchHistory.get(context).remove(key)

    // --- Recently used ------------------------------------------------------

    /** Records a launch from Kamar; used when usage access hasn't been granted. */
    fun noteLaunch(pkg: String) = prefs.edit().putLong("used.$pkg", System.currentTimeMillis()).apply()

    private fun recentlyUsed(apps: List<TvApp>): List<TvApp> {
        val now = System.currentTimeMillis()
        // Needs: adb shell appops set com.tvkamar.launcher GET_USAGE_STATS allow
        val system = runCatching {
            (context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager)
                .queryAndAggregateUsageStats(now - TimeUnit.DAYS.toMillis(30), now)
                .mapValues { it.value.lastTimeUsed }
        }.getOrDefault(emptyMap())
        return apps
            .map { it to maxOf(system[it.packageName] ?: 0L, prefs.getLong("used.${it.packageName}", 0L)) }
            .filter { it.second > 0 && it.first.packageName != TV_SETTINGS }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(8)
    }

    // --- TV sources -----------------------------------------------------------

    private fun tvSources(): List<TvSource> {
        val tim = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager ?: return emptyList()
        return tim.tvInputList
            .filter { !it.isHidden(context) && (it.type == TvInputInfo.TYPE_TUNER || it.type == TvInputInfo.TYPE_HDMI) }
            .filter { it.type == TvInputInfo.TYPE_TUNER || it.parentId == null }
            .sortedBy { if (it.type == TvInputInfo.TYPE_TUNER) 0 else 1 }
            .map { input ->
                val tuner = input.type == TvInputInfo.TYPE_TUNER
                val label = input.loadCustomLabel(context)?.toString()?.takeIf { it.isNotBlank() }
                    ?: input.loadLabel(context).toString()
                // Tuner too: a plain launch of com.tcl.tv resumes the last input (often HDMI),
                // while the passthrough URI makes it switch to this input explicitly.
                val intent = Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(input.id))
                // CLEAR_TASK: the TV app ignores the new input if its existing task is just brought forward.
                TvSource(
                    input.id, if (tuner) "Live TV" else label, tuner,
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                )
            }
    }

    // --- Ordering & hiding -------------------------------------------------

    private fun savedOrder(): List<String> =
        prefs.getString("order", null)?.split(',')?.filter { it.isNotEmpty() } ?: emptyList()

    private fun saveOrder(order: List<String>) = prefs.edit().putString("order", order.joinToString(",")).apply()

    private fun hidden(): Set<String> = prefs.getStringSet("hidden", emptySet())!!

    /** Moves [pkg] by [delta] places among visible apps. */
    fun move(pkg: String, delta: Int) {
        val hidden = hidden()
        val order = savedOrder().toMutableList()
        val visible = order.filter { it !in hidden }
        val from = visible.indexOf(pkg)
        val to = (from + delta).coerceIn(0, visible.lastIndex)
        if (from < 0 || from == to) return
        val target = visible[to]
        order.remove(pkg)
        val targetIdx = order.indexOf(target)
        order.add(if (delta > 0) targetIdx + 1 else targetIdx, pkg)
        saveOrder(order)
    }

    fun hide(pkg: String) = prefs.edit().putStringSet("hidden", hidden() + pkg).apply()

    fun unhideAll() = prefs.edit().remove("hidden").apply()

    fun hiddenCount() = hidden().size

    fun appInfoIntent(pkg: String) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun uninstallIntent(pkg: String) =
        Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

private fun Drawable.toBitmapOr(fallbackW: Int, fallbackH: Int): Bitmap {
    val w = if (intrinsicWidth > 0) intrinsicWidth else fallbackW
    val h = if (intrinsicHeight > 0) intrinsicHeight else fallbackH
    return toBitmap(w, h)
}

private fun accentOf(image: Bitmap): Color {
    val palette = Palette.from(image).generate()
    val swatch = palette.vibrantSwatch ?: palette.dominantSwatch ?: return Color(0xFF26304F)
    return Color(swatch.rgb)
}
