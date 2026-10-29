package com.tvkamar.launcher.ui

import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvkamar.launcher.LauncherActions
import com.tvkamar.launcher.LauncherData
import com.tvkamar.launcher.TvApp
import com.tvkamar.launcher.WallpaperSettings

private const val GRID_COLUMNS = 4
/** Gap between the sidebar and page content. */
private val CONTENT_INSET = 22.dp
/** Arc Launcher's screensaver starts after this long without a key press. */
private const val SCREENSAVER_AFTER_MS = 5 * 60_000L

@Composable
fun KamarScreen(
    data: LauncherData?,
    account: String?,
    avatar: ImageBitmap?,
    wallpaper: ImageBitmap?,
    wallpaperVideo: String?,
    wallpaperCredit: String?,
    wallpaperSettings: WallpaperSettings,
    sidebarPinned: Boolean,
    hiddenCount: Int,
    homeTicks: Int,
    actions: LauncherActions,
) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                if (data != null) {
                    Launcher(data, account, avatar, wallpaper, wallpaperVideo, wallpaperCredit, wallpaperSettings, sidebarPinned, hiddenCount, homeTicks, actions)
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Launcher(
    data: LauncherData,
    account: String?,
    avatar: ImageBitmap?,
    wallpaper: ImageBitmap?,
    wallpaperVideo: String?,
    wallpaperCredit: String?,
    wallpaperSettings: WallpaperSettings,
    sidebarPinned: Boolean,
    hiddenCount: Int,
    homeTicks: Int,
    actions: LauncherActions,
) {
    var page by remember { mutableStateOf(Page.Home) }
    var sidebarFocused by remember { mutableStateOf(false) }
    var gridFocused by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<Pair<TvApp, Int?>?>(null) }
    var wallpaperPanel by remember { mutableStateOf(false) }
    var airPlayPanel by remember { mutableStateOf(false) }
    val pageRequesters = remember { Page.entries.associateWith { FocusRequester() } }
    val dockRequester = remember { FocusRequester() }
    val watchRequester = remember { FocusRequester() }
    val firstAppRequester = remember { FocusRequester() }
    var resetTicks by remember { mutableIntStateOf(0) }
    var slide by remember { mutableStateOf<Slide?>(null) }
    val shownSlide = slide ?: data.watched.firstOrNull()?.toSlide() ?: data.apps.firstOrNull()?.toSlide()
    // The aerial video as the backdrop draws it, replayed live by the glass.
    val videoLayer = rememberGraphicsLayer()
    val glassVideo = if (wallpaperVideo != null) videoLayer else null
    // What the glass panels show through them otherwise: the wallpaper, or the artwork's soft backdrop.
    val glassBackdrop = wallpaper ?: shownSlide?.backdrop

    // Start (and Home button): Home page with the first dock app focused.
    LaunchedEffect(homeTicks, resetTicks) {
        page = Page.Home
        withFrameNanos { }
        runCatching { dockRequester.requestFocus() }
    }
    // Back: from content go to the sidebar; from the sidebar go back to the dock.
    BackHandler {
        if (sidebarFocused) {
            resetTicks++
        } else {
            runCatching { pageRequesters.getValue(page).requestFocus() }
        }
    }

    // OLED screensaver after a while without input, only while Kamar is on screen.
    var screensaver by remember { mutableStateOf(false) }
    var lastInput by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { lastInput = SystemClock.uptimeMillis(); screensaver = false }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            val idle = SystemClock.uptimeMillis() - lastInput
            if (idle > SCREENSAVER_AFTER_MS && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) screensaver = true
        }
    }

    val homeScroll = rememberScrollState()
    val density = LocalDensity.current
    // Darken the backdrop as the grid scrolls up over it, and keep the Apps page calm.
    val dim = when (page) {
        Page.Apps -> 0.55f
        Page.Home -> with(density) { (homeScroll.value / 260.dp.toPx()).coerceIn(0f, 1f) * 0.5f }
    }

    val sidebarExpanded = sidebarFocused || sidebarPinned
    // Pages make room for the rail, or for the whole sidebar when it is pinned open.
    val pageStart by animateDpAsState(
        SIDEBAR_MARGIN + if (sidebarPinned) SIDEBAR_OPEN else SIDEBAR_RAIL,
        label = "pageStart",
    )
    // Shade the page while the sidebar opens over it.
    val scrim by animateFloatAsState(if (sidebarExpanded && !sidebarPinned) 1f else 0f, label = "scrim")

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent {
                lastInput = SystemClock.uptimeMillis()
                if (screensaver) {
                    // The key that wakes the screen does nothing else.
                    if (it.type == KeyEventType.KeyUp) screensaver = false
                    true
                } else false
            },
    ) {
        Backdrop(
            shownSlide, wallpaper, wallpaperVideo, videoLayer, dim,
            onVideoEnded = actions::wallpaperVideoEnded,
        )

        // Pages start right of the sidebar: focus search only moves Right to things whose
        // bounds start right of the sidebar, and scroll containers are full-width groups.
        Box(Modifier.fillMaxSize().padding(start = pageStart)) {
            when (page) {
                Page.Home -> HomePage(
                    data = data,
                    scroll = homeScroll,
                    contentStart = CONTENT_INSET,
                    dockRequester = dockRequester,
                    watchRequester = watchRequester,
                    homeTicks = homeTicks + resetTicks,
                    actions = actions,
                    onMenu = { menu = it to DOCK_SIZE },
                    onSlide = { slide = it },
                    onGridFocused = { gridFocused = it },
                )
                Page.Apps -> AppsPage(data.apps, firstAppRequester, actions, onMenu = { menu = it to GRID_COLUMNS })
            }
            StatusBar(
                // The open sidebar shows the time itself, and the glass would show the bar through.
                visible = page == Page.Home && !gridFocused && scrim == 0f,
                contentStart = CONTENT_INSET,
                credit = wallpaperCredit,
                // Home's scroll column spans the whole page under the bar, so focus search finds
                // nothing below the network button; send Down to the first row instead.
                downRequester = if (data.watched.isNotEmpty()) watchRequester else dockRequester,
                onNetwork = { actions.open(Intent(Settings.ACTION_WIFI_SETTINGS)) },
            )
        }

        if (scrim > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = scrim }
                    .background(
                        Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 0.8f),
                            0.35f to Color.Black.copy(alpha = 0.45f),
                            0.6f to Color.Transparent,
                        ),
                    ),
            )
        }

        // Floats over the backdrop and content, like the tvOS sidebar.
        CompositionLocalProvider(LocalGlassBackdrop provides glassBackdrop, LocalGlassVideo provides glassVideo) {
            Sidebar(
                page = page,
                expanded = sidebarExpanded,
                pinned = sidebarPinned,
                account = account,
                avatar = avatar,
                sources = data.sources,
                pageRequesters = pageRequesters,
                onPage = { page = it },
                onAccount = actions::chooseAccount,
                onSource = { actions.open(it.intent) },
                onSettings = { actions.open(data.settingsIntent) },
                onWallpaper = { wallpaperPanel = true },
                onAirPlay = { airPlayPanel = true },
                onTogglePin = { actions.setSidebarPinned(!sidebarPinned) },
                modifier = Modifier
                    .padding(start = SIDEBAR_MARGIN, top = SIDEBAR_MARGIN, bottom = SIDEBAR_MARGIN)
                    .onFocusChanged { sidebarFocused = it.hasFocus }
                    // Entering the sidebar lands on the current page, not the nearest item.
                    .focusProperties {
                        enter = { pageRequesters.getValue(page) }
                        // The sidebar floats over the content, so point Right at the page explicitly.
                        exit = { direction ->
                            when {
                                direction != FocusDirection.Right -> FocusRequester.Default
                                page == Page.Home -> dockRequester
                                else -> firstAppRequester
                            }
                        }
                    }
                    .focusGroup(),
            )
        }

        Screensaver(screensaver)
    }

    if (wallpaperPanel) {
        CompositionLocalProvider(LocalGlassBackdrop provides glassBackdrop, LocalGlassVideo provides glassVideo) {
            WallpaperPanel(
                settings = wallpaperSettings,
                credit = wallpaperCredit,
                onChange = actions::setWallpaperSettings,
                onNext = actions::nextWallpaper,
                onDismiss = { wallpaperPanel = false },
            )
        }
    }

    if (airPlayPanel) {
        CompositionLocalProvider(LocalGlassBackdrop provides glassBackdrop, LocalGlassVideo provides glassVideo) {
            AirPlayPanel(onDismiss = { airPlayPanel = false })
        }
    }

    menu?.let { (app, rowSize) ->
        AppMenu(
            app = app,
            rowSize = rowSize,
            hiddenCount = hiddenCount,
            onDismiss = { menu = null },
            onOpen = { menu = null; actions.launch(app) },
            onMove = { delta -> menu = null; actions.move(app.packageName, delta) },
            onHide = { menu = null; actions.hide(app.packageName) },
            onUnhideAll = { menu = null; actions.unhideAll() },
            onAppInfo = { menu = null; actions.appInfo(app.packageName) },
            onUninstall = { menu = null; actions.uninstall(app.packageName) },
        )
    }
}

@Composable
private fun AppsPage(apps: List<TvApp>, firstRequester: FocusRequester, actions: LauncherActions, onMenu: (TvApp) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gap = 20.dp
        val cardWidth = (maxWidth - CONTENT_INSET - 36.dp - gap * (GRID_COLUMNS - 1)) / GRID_COLUMNS
        MinimalScrolling {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = CONTENT_INSET, end = 36.dp),
            ) {
                Text("Apps", Modifier.padding(top = 30.dp, bottom = 4.dp), fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Hold OK on an app to move, hide or uninstall it.",
                    Modifier.padding(bottom = 22.dp),
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.5f),
                )
                apps.chunked(GRID_COLUMNS).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEach { app ->
                            androidx.compose.runtime.key(app.packageName) {
                                AppCard(
                                    app, cardWidth,
                                    focusRequester = if (app == apps.first()) firstRequester else null,
                                    onClick = { actions.launch(app) },
                                    onLongPress = { onMenu(app) },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Spacer(Modifier.height(60.dp))
            }
        }
    }
}

/**
 * Scroll only as far as needed to reveal the focused item (plus a margin), instead of the
 * Android TV default that pins focus at 30% of the screen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MinimalScrolling(content: @Composable () -> Unit) {
    val margin = with(LocalDensity.current) { 48.dp.toPx() }
    val spec = remember(margin) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val trailing = offset + size
                return when {
                    offset >= 0 && trailing <= containerSize - margin -> 0f
                    offset < 0 -> offset - margin
                    else -> trailing - containerSize + margin
                }
            }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}
