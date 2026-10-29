package com.tvkamar.launcher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.DialogProperties
import com.tvkamar.launcher.AerialCategory
import com.tvkamar.launcher.POPULAR_SUBREDDITS
import com.tvkamar.launcher.RedditSort
import com.tvkamar.launcher.SLIDESHOW_INTERVALS
import com.tvkamar.launcher.WallpaperSettings
import com.tvkamar.launcher.WallpaperSource
import com.tvkamar.launcher.WallhavenSort
import com.tvkamar.launcher.WallhavenTopic

/**
 * Wallpaper settings in a panel on the left, like Arc Launcher's settings: where the
 * picture comes from and the Reddit / Wallhaven / Aerials options. Every change applies at once.
 */
@Composable
fun WallpaperPanel(
    settings: WallpaperSettings,
    credit: String?,
    onChange: (WallpaperSettings) -> Unit,
    onNext: () -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Dialogs dim the whole screen by default, which turns the glass into a flat grey slab.
        (LocalView.current.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)
        Box(
            Modifier.fillMaxSize().background(
                // Same shade as when the sidebar opens over the page.
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.8f),
                    0.35f to Color.Black.copy(alpha = 0.45f),
                    0.6f to Color.Transparent,
                ),
            ),
        ) {
            GlassPanel(
                RoundedCornerShape(26.dp), left = SIDEBAR_MARGIN, top = SIDEBAR_MARGIN,
                Modifier.padding(SIDEBAR_MARGIN).width(380.dp).fillMaxHeight(),
            ) {
                Column(
                    Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text("Wallpaper", Modifier.padding(start = 14.dp, bottom = 2.dp), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    if (credit != null && settings.source != WallpaperSource.Artwork) {
                        Text(credit, Modifier.padding(start = 14.dp, bottom = 6.dp), fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
                    }

                    Section("Source")
                    WallpaperSource.entries.forEachIndexed { i, source ->
                        OptionRow(
                            source.label,
                            checked = settings.source == source,
                            radio = true,
                            modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                        ) { onChange(settings.copy(source = source)) }
                    }

                    if (settings.source != WallpaperSource.Artwork) {
                        Section("Slideshow")
                        // Aerials move on when each video ends.
                        if (settings.source != WallpaperSource.Aerials) {
                            ValueRow("Change every", intervalLabel(settings.intervalMin)) {
                                val next = SLIDESHOW_INTERVALS.getOrElse(SLIDESHOW_INTERVALS.indexOf(settings.intervalMin) + 1) { SLIDESHOW_INTERVALS.first() }
                                onChange(settings.copy(intervalMin = next))
                            }
                        }
                        val what = if (settings.source == WallpaperSource.Aerials) "video" else "picture"
                        ValueRow("Show the next $what now", "", onClick = onNext)
                    }

                    if (settings.source == WallpaperSource.Reddit) {
                        ValueRow("Order", settings.redditSort.label) {
                            val sorts = RedditSort.entries
                            onChange(settings.copy(redditSort = sorts[(settings.redditSort.ordinal + 1) % sorts.size]))
                        }
                        Section("Subreddits")
                        POPULAR_SUBREDDITS.forEach { sub ->
                            OptionRow("r/$sub", checked = sub in settings.subreddits) {
                                onChange(settings.copy(subreddits = settings.subreddits.toggle(sub)))
                            }
                        }
                    }

                    if (settings.source == WallpaperSource.Wallhaven) {
                        ValueRow("Order", settings.wallhavenSort.label) {
                            val sorts = WallhavenSort.entries
                            onChange(settings.copy(wallhavenSort = sorts[(settings.wallhavenSort.ordinal + 1) % sorts.size]))
                        }
                        Section("Topics")
                        WallhavenTopic.entries.forEach { t ->
                            OptionRow(t.label, checked = t in settings.wallhavenTopics) {
                                onChange(settings.copy(wallhavenTopics = settings.wallhavenTopics.toggle(t)))
                            }
                        }
                    }

                    if (settings.source == WallpaperSource.Aerials) {
                        Section("Categories")
                        AerialCategory.entries.forEach { c ->
                            OptionRow(c.label, checked = c in settings.aerialCategories) {
                                onChange(settings.copy(aerialCategories = settings.aerialCategories.toggle(c)))
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    ValueRow("Done", "", onClick = onDismiss)
                }
            }
        }
    }
}

private fun <T> Set<T>.toggle(item: T) = if (item in this) this - item else this + item

private fun intervalLabel(min: Int) = when {
    min < 60 -> "$min min"
    min == 60 -> "1 hour"
    else -> "${min / 60} hours"
}

@Composable
internal fun Section(text: String) {
    Text(
        text,
        Modifier.padding(start = 14.dp, top = 12.dp, bottom = 2.dp),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = Color.White.copy(alpha = 0.45f),
    )
}

/** Focus-highlighted row; white pill when focused, like the app menu. */
@Composable
internal fun PanelRow(modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable RowScope.(focused: Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.03f else 1f, label = "panelScale")
    Row(
        modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) Color.White else Color.Transparent)
            .remoteClickable(onFocusChange = { focused = it }, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content(focused) }
}

/** A choice with a tick (or radio dot when [radio]). */
@Composable
internal fun OptionRow(label: String, checked: Boolean, radio: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    PanelRow(modifier, onClick) { focused ->
        val fg = if (focused) Color.Black else Color.White
        Box(
            Modifier
                .size(20.dp)
                .clip(if (radio) CircleShape else RoundedCornerShape(6.dp))
                .background(if (checked) AppleBlue else Color.Transparent)
                .border(1.5.dp, if (checked) AppleBlue else fg.copy(alpha = 0.4f), if (radio) CircleShape else RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(Icons.Rounded.Check, null, Modifier.size(14.dp), tint = Color.White)
        }
        Spacer(Modifier.width(12.dp))
        Text(label, color = fg, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A setting whose value changes each time OK is pressed. */
@Composable
internal fun ValueRow(label: String, value: String, onClick: () -> Unit) {
    PanelRow(onClick = onClick) { focused ->
        val fg = if (focused) Color.Black else Color.White
        Text(label, Modifier.weight(1f), color = fg, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        Text(value, color = fg.copy(alpha = 0.6f), fontSize = 15.sp, maxLines = 1)
    }
}
