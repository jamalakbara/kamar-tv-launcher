package com.tvkamar.launcher

import android.accounts.AccountManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.tvkamar.launcher.airplay.AirPlayService
import com.tvkamar.launcher.ui.KamarScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var repo: AppRepository

    private var data by mutableStateOf<LauncherData?>(null)
    private var hiddenCount by mutableIntStateOf(0)
    private var account by mutableStateOf<String?>(null)

    /** Name shown instead of the email; Google doesn't share the real one with launchers. */
    private var profileName by mutableStateOf<String?>(null)

    /** Profile picture: adb push a square image to Android/data/com.tvkamar.launcher/files/avatar.png */
    private var avatar by mutableStateOf<ImageBitmap?>(null)

    /** Home wallpaper: adb push a picture to Android/data/com.tvkamar.launcher/files/wallpaper.jpg */
    private var wallpaper by mutableStateOf<ImageBitmap?>(null)

    private val slideshow by lazy { WallpaperSlideshow(this) }
    private var wallpaperSettings by mutableStateOf(WallpaperSettings())
    /** Current Reddit / Unsplash picture, and who made it. */
    private var slideshowPicture by mutableStateOf<ImageBitmap?>(null)
    private var wallpaperCredit by mutableStateOf<String?>(null)
    private var slideshowJob: Job? = null

    /** Sidebar stays open instead of collapsing to an icon rail. */
    private var sidebarPinned by mutableStateOf(false)

    /** System account picker; picking an account also lets Kamar see it from then on. */
    private val accountPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)?.let { name ->
            getSharedPreferences("launcher", MODE_PRIVATE).edit().putString("account", name).apply()
            account = name
        }
    }

    /** Bumped when Home is pressed while we are already showing, to jump back to the hero. */
    private var homeTicks by mutableIntStateOf(0)

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = reload()
    }

    private val actions = object : LauncherActions {
        override fun launch(app: TvApp) {
            repo.noteLaunch(app.packageName)
            startSafely(app.launchIntent)
        }
        override fun open(intent: Intent) = startSafely(intent)
        override fun move(pkg: String, delta: Int) { repo.move(pkg, delta); reload() }
        override fun hide(pkg: String) { repo.hide(pkg); reload() }
        override fun unhideAll() { repo.unhideAll(); reload() }
        override fun appInfo(pkg: String) = startSafely(repo.appInfoIntent(pkg))
        override fun uninstall(pkg: String) = startSafely(repo.uninstallIntent(pkg))
        override fun setWallpaperSettings(settings: WallpaperSettings) {
            val restart = settings.source != wallpaperSettings.source || settings.intervalMin != wallpaperSettings.intervalMin ||
                (settings.unsplashKey != wallpaperSettings.unsplashKey && slideshowPicture == null)
            slideshow.save(settings)
            wallpaperSettings = settings
            if (restart) startSlideshow()
        }
        override fun nextWallpaper() = startSlideshow(now = true)
        override fun setSidebarPinned(pinned: Boolean) {
            getSharedPreferences("launcher", MODE_PRIVATE).edit().putBoolean("sidebar_pinned", pinned).apply()
            sidebarPinned = pinned
        }
        override fun chooseAccount() {
            val current = AccountManager.get(this@MainActivity).getAccountsByType(GOOGLE).firstOrNull()
            runCatching {
                accountPicker.launch(
                    AccountManager.newChooseAccountIntent(current, null, arrayOf(GOOGLE), null, null, null, null),
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = AppRepository(this)
        AirPlayService.start(this)
        account = getSharedPreferences("launcher", MODE_PRIVATE).getString("account", null)
            ?: AccountManager.get(this).getAccountsByType(GOOGLE).firstOrNull()?.name
        profileName = getSharedPreferences("launcher", MODE_PRIVATE).getString("profile_name", null)
        sidebarPinned = getSharedPreferences("launcher", MODE_PRIVATE).getBoolean("sidebar_pinned", false)
        wallpaperSettings = slideshow.settings()
        saveProfileName(intent)
        saveUnsplashKey(intent)
        setContent {
            val slideshowOn = wallpaperSettings.source != WallpaperSource.Artwork
            KamarScreen(
                data, profileName ?: account, avatar,
                wallpaper = if (slideshowOn) slideshowPicture ?: wallpaper else wallpaper,
                wallpaperCredit = if (slideshowOn && slideshowPicture != null) wallpaperCredit else null,
                wallpaperSettings = wallpaperSettings,
                sidebarPinned = sidebarPinned,
                hiddenCount = hiddenCount,
                homeTicks = homeTicks,
                actions = actions,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        reload()
        startSlideshow()
    }

    override fun onStop() {
        slideshowJob?.cancel()
        unregisterReceiver(packageReceiver)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        saveProfileName(intent)
        saveUnsplashKey(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) homeTicks++
    }

    /** Set over adb: am start -n com.tvkamar.launcher/.MainActivity --es profile_name "Your Name" */
    private fun saveProfileName(intent: Intent) {
        val name = intent.getStringExtra("profile_name")?.trim() ?: return
        getSharedPreferences("launcher", MODE_PRIVATE).edit().putString("profile_name", name.ifEmpty { null }).apply()
        profileName = name.ifEmpty { null }
    }

    /** Set over adb: am start -n com.tvkamar.launcher/.MainActivity --es unsplash_key "KEY" */
    private fun saveUnsplashKey(intent: Intent) {
        val key = intent.getStringExtra("unsplash_key")?.trim() ?: return
        actions.setWallpaperSettings(wallpaperSettings.copy(unsplashKey = key))
    }

    /**
     * Runs the Reddit / Unsplash slideshow while Kamar is on screen: shows the last picture
     * straight away, then fetches a new one whenever the interval is up ([now] fetches at once).
     */
    private fun startSlideshow(now: Boolean = false) {
        slideshowJob?.cancel()
        val settings = wallpaperSettings
        if (settings.source == WallpaperSource.Artwork) {
            slideshowPicture = null
            wallpaperCredit = null
            return
        }
        slideshowJob = lifecycleScope.launch {
            var fetchNow = now
            val cached = withContext(Dispatchers.IO) { slideshow.current(settings.source) }
            if (cached != null) show(cached) else { slideshowPicture = null; fetchNow = true }
            while (true) {
                val wait = slideshow.lastFetch + settings.intervalMin * 60_000L - System.currentTimeMillis()
                if (!fetchNow && wait > 0) delay(wait)
                fetchNow = false
                val next = withContext(Dispatchers.IO) { slideshow.next(settings) }
                if (next != null) show(next) else delay(5 * 60_000L) // offline or rate limited: try again later
            }
        }
    }

    private suspend fun show(image: SlideshowImage) {
        slideshowPicture = withContext(Dispatchers.IO) { decodeScaled(image.file) } ?: return
        wallpaperCredit = image.credit
    }

    private var loading: Job? = null

    private fun reload() {
        // A newer reload wins; an older one finishing late must not overwrite fresher history.
        loading?.cancel()
        loading = lifecycleScope.launch {
            data = withContext(Dispatchers.IO) {
                // Catch what was just playing (apps keep their paused session after Home).
                SessionRecorder.recordAll(this@MainActivity)
                repo.load()
            }
            avatar = withContext(Dispatchers.IO) { loadPicture("avatar.png") }
            wallpaper = withContext(Dispatchers.IO) {
                listOf("wallpaper.jpg", "wallpaper.png", "wallpaper.webp").firstNotNullOfOrNull(::loadPicture)
            }
            hiddenCount = repo.hiddenCount()
        }
    }

    /** A picture pushed to the app's external files folder, scaled down to about screen size. */
    private fun loadPicture(name: String): ImageBitmap? =
        File(getExternalFilesDir(null), name).takeIf { it.exists() }?.let(::decodeScaled)

    private fun decodeScaled(file: File): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1280) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }

    private fun startSafely(intent: Intent) {
        runCatching { startActivity(intent) }
    }
}

/** Everything the UI can ask the launcher to do. */
interface LauncherActions {
    fun launch(app: TvApp)
    fun open(intent: Intent)
    fun move(pkg: String, delta: Int)
    fun hide(pkg: String)
    fun unhideAll()
    fun appInfo(pkg: String)
    fun uninstall(pkg: String)
    fun chooseAccount()
    fun setSidebarPinned(pinned: Boolean)
    fun setWallpaperSettings(settings: WallpaperSettings)
    fun nextWallpaper()
}

private const val GOOGLE = "com.google"
