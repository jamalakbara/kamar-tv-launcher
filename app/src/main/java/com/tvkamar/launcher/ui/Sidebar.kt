package com.tvkamar.launcher.ui

import android.text.format.DateFormat
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Airplay
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Input
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvkamar.launcher.TvSource

enum class Page { Home, Apps }

val AppleBlue = Color(0xFF0A84FF)

/** Space the sidebar keeps from the screen edges. */
val SIDEBAR_MARGIN = 14.dp

/** Sidebar width when collapsed to an icon rail, and when open. */
val SIDEBAR_RAIL = 64.dp
val SIDEBAR_OPEN = 210.dp

/**
 * Floating glass panel on the left, like the tvOS Apple TV app sidebar. It shrinks to an
 * icon rail while you browse and opens up when focus moves into it ([expanded]).
 * Holding OK on any item pins it open ([pinned]) or back to auto-hide.
 */
@Composable
fun Sidebar(
    page: Page,
    expanded: Boolean,
    pinned: Boolean,
    /** Profile name, else the chosen Google account email; null before one is picked. */
    account: String?,
    avatar: ImageBitmap?,
    sources: List<TvSource>,
    pageRequesters: Map<Page, FocusRequester>,
    onPage: (Page) -> Unit,
    onAccount: () -> Unit,
    onSource: (TvSource) -> Unit,
    onSettings: () -> Unit,
    onWallpaper: () -> Unit,
    onAirPlay: () -> Unit,
    onTogglePin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(26.dp)
    val width by animateDpAsState(
        if (expanded) SIDEBAR_OPEN else SIDEBAR_RAIL,
        spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
        label = "sidebarWidth",
    )
    val labels by animateFloatAsState(if (expanded) 1f else 0f, label = "sidebarLabels")
    // Sits [SIDEBAR_MARGIN] in from the top-left corner (see KamarScreen).
    GlassPanel(shape, left = SIDEBAR_MARGIN, top = SIDEBAR_MARGIN, modifier = modifier.width(width).fillMaxHeight()) {
        Column(Modifier.fillMaxHeight().padding(horizontal = 8.dp, vertical = 14.dp)) {
            Header(account, avatar, labels, onAccount, onTogglePin)
            Column(
                Modifier.weight(1f).padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                SidebarItem(
                    "Home", { GlyphBadge(Icons.Rounded.PlayArrow, it) }, page == Page.Home, labels,
                    pageRequesters.getValue(Page.Home), onFocused = { onPage(Page.Home) }, onLongPress = onTogglePin,
                ) { onPage(Page.Home) }
                SidebarItem(
                    "Apps", { GlyphBadge(Icons.Rounded.Apps, it) }, page == Page.Apps, labels,
                    pageRequesters.getValue(Page.Apps), onFocused = { onPage(Page.Apps) }, onLongPress = onTogglePin,
                ) { onPage(Page.Apps) }

                if (sources.isNotEmpty()) {
                    SectionHeader("TV Inputs", labels)
                    sources.forEach { source ->
                        SidebarItem(
                            source.label,
                            { GlyphBadge(if (source.isTuner) Icons.Rounded.LiveTv else Icons.Rounded.Input, it) },
                            false,
                            labels,
                            onLongPress = onTogglePin,
                        ) { onSource(source) }
                    }
                }
            }
            SidebarItem("AirPlay", { GlyphBadge(Icons.Rounded.Airplay, it) }, false, labels, onLongPress = onTogglePin, onClick = onAirPlay)
            SidebarItem("Wallpaper", { GlyphBadge(Icons.Rounded.Wallpaper, it) }, false, labels, onLongPress = onTogglePin, onClick = onWallpaper)
            SidebarItem("Settings", { GlyphBadge(Icons.Rounded.Settings, it) }, false, labels, onLongPress = onTogglePin, onClick = onSettings)
            Text(
                if (pinned) "Pinned open · hold OK to auto-hide" else "Hold OK to keep open",
                Modifier.padding(start = 10.dp, top = 8.dp).alpha(labels * 0.45f),
                fontSize = 11.sp,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** Google account (pick it once to show it here) and the time. */
@Composable
private fun Header(account: String?, avatar: ImageBitmap?, labels: Float, onAccount: () -> Unit, onLongPress: () -> Unit) {
    val context = LocalContext.current
    val now = rememberNow()
    var focused by remember { mutableStateOf(false) }
    val time = DateFormat.format(if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm a", now).toString()
    val name = account?.substringBefore('@') ?: "Choose account"
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) Color.White else Color.Transparent)
            .remoteClickable(onFocusChange = { focused = it }, onClick = onAccount, onLongPress = onLongPress)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (avatar != null) {
            Image(
                avatar, "Profile picture",
                Modifier.size(32.dp).clip(CircleShape).background(Color.White),
                contentScale = ContentScale.Crop,
                // Shrinking a large picture to ~43 px needs high-quality filtering to avoid jaggies.
                filterQuality = FilterQuality.High,
            )
        } else {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Color(0xFFFF9F0A), Color(0xFFFF375F)))),
                contentAlignment = Alignment.Center,
            ) {
                Text(name.first().uppercase(), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            name,
            Modifier.weight(1f).alpha(labels),
            color = if (focused) Color.Black else Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            time,
            Modifier.alpha(labels),
            fontSize = 13.sp,
            maxLines = 1,
            softWrap = false,
            color = if (focused) Color.Black.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.6f),
        )
    }
}

/** Section title when open; a short divider in its place on the rail. */
@Composable
private fun SectionHeader(text: String, labels: Float) {
    Box(Modifier.padding(start = 10.dp, top = 14.dp, bottom = 4.dp)) {
        Text(
            text,
            Modifier.alpha(labels),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            color = Color.White.copy(alpha = 0.45f),
        )
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = 4.dp)
                .width(20.dp)
                .height(1.dp)
                .alpha(1f - labels)
                .background(Color.White.copy(alpha = 0.3f)),
        )
    }
}

@Composable
private fun SidebarItem(
    label: String,
    badge: @Composable (focused: Boolean) -> Unit,
    selected: Boolean,
    labels: Float,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
    onLongPress: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, label = "sidebarScale")
    val background = when {
        focused -> Color.White
        selected -> Color.White.copy(alpha = 0.16f)
        else -> Color.Transparent
    }
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .remoteClickable(
                onFocusChange = { focused = it; if (it) onFocused() },
                onClick = onClick,
                onLongPress = onLongPress,
                focusRequester = focusRequester,
            )
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        badge(focused)
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            Modifier.alpha(labels),
            color = if (focused) Color.Black else Color.White,
            fontSize = 15.sp,
            fontWeight = if (focused || selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Round grey badge holding a glyph, like the tvOS sidebar icons. */
@Composable
private fun GlyphBadge(icon: ImageVector, focused: Boolean) {
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(if (focused) Color.Black.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(17.dp), tint = if (focused) Color.Black else Color.White)
    }
}
