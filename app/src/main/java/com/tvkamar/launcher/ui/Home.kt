package com.tvkamar.launcher.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvkamar.launcher.LauncherActions
import com.tvkamar.launcher.LauncherData
import com.tvkamar.launcher.TvApp

/** Apps per dock and per grid row, like Arc Launcher. */
const val DOCK_SIZE = 6
private val GAP = 18.dp
private val DOCK_PADDING = 16.dp
private val END_INSET = 36.dp
private val WATCH_CARD_WIDTH = 290.dp

/**
 * Home, laid out like Arc Launcher: wallpaper on top, a Watch Next row, a frosted dock with
 * the first [DOCK_SIZE] apps, and the rest of the apps in a grid below.
 * [contentStart] keeps everything clear of the sidebar rail.
 */
@Composable
fun HomePage(
    data: LauncherData,
    scroll: ScrollState,
    contentStart: Dp,
    dockRequester: FocusRequester,
    watchRequester: FocusRequester,
    homeTicks: Int,
    actions: LauncherActions,
    onMenu: (TvApp) -> Unit,
    onSlide: (Slide) -> Unit,
    onGridFocused: (Boolean) -> Unit,
) {
    LaunchedEffect(homeTicks) { scroll.animateScrollTo(0) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val tile = (maxWidth - contentStart - END_INSET - DOCK_PADDING * 2 - GAP * (DOCK_SIZE - 1)) / DOCK_SIZE
        val dockHeight = tile * 9f / 16f + DOCK_PADDING * 2
        val watchBlock = if (data.watched.isEmpty()) 0.dp else WATCH_CARD_WIDTH * 9f / 16f + 52.dp
        // Push the dock down so the wallpaper shows above it and the grid peeks in below.
        val top = (maxHeight - dockHeight - watchBlock - 64.dp).coerceAtLeast(84.dp)
        val dock = data.apps.take(DOCK_SIZE)
        val grid = data.apps.drop(DOCK_SIZE)

        MinimalScrolling {
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                Spacer(Modifier.height(top))

                if (data.watched.isNotEmpty()) {
                    Text(
                        "Watch Next",
                        Modifier.padding(start = contentStart + 4.dp, bottom = 12.dp),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 10f)),
                    )
                    val state = rememberLazyListState()
                    LaunchedEffect(data.watched.first().key) { state.scrollToItem(0) }
                    LazyRow(
                        state = state,
                        contentPadding = PaddingValues(start = contentStart, end = END_INSET),
                        horizontalArrangement = Arrangement.spacedBy(GAP),
                    ) {
                        items(data.watched, key = { "w." + it.key }) { item ->
                            WatchCard(
                                item, WATCH_CARD_WIDTH,
                                focusRequester = if (item == data.watched.first()) watchRequester else null,
                                onFocused = { if (it) onSlide(item.toSlide()) },
                                onClick = { actions.launch(item.app) },
                            )
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                }

                Dock(Modifier.padding(start = contentStart, end = END_INSET)) {
                    dock.forEach { app ->
                        key(app.packageName) {
                            BannerTile(
                                app, tile,
                                focusRequester = if (app == dock.first()) dockRequester else null,
                                onFocused = { if (it) onSlide(app.toSlide()) },
                                onClick = { actions.launch(app) },
                                onLongPress = { onMenu(app) },
                            )
                        }
                    }
                }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = contentStart + DOCK_PADDING, end = END_INSET + DOCK_PADDING, top = 24.dp)
                        .onFocusChanged { onGridFocused(it.hasFocus) },
                    verticalArrangement = Arrangement.spacedBy(GAP),
                ) {
                    grid.chunked(DOCK_SIZE).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                            row.forEach { app ->
                                key(app.packageName) {
                                    BannerTile(
                                        app, tile,
                                        onFocused = { if (it) onSlide(app.toSlide()) },
                                        onClick = { actions.launch(app) },
                                        onLongPress = { onMenu(app) },
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(48.dp))
            }
        }
    }
}

/** Frosted glass tray behind the favourite apps. */
@Composable
private fun Dock(modifier: Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .glassTint(shape)
            .padding(DOCK_PADDING),
        horizontalArrangement = Arrangement.spacedBy(GAP),
    ) { content() }
}
