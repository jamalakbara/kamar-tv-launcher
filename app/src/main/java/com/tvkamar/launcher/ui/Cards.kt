package com.tvkamar.launcher.ui

import android.view.KeyEvent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.tvkamar.launcher.TvApp
import com.tvkamar.launcher.WatchedItem

private val SELECT_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER,
    KeyEvent.KEYCODE_BUTTON_A,
)

/**
 * Makes an element focusable and clickable with the remote: OK clicks, holding OK
 * (or pressing Menu) calls [onLongPress].
 */
@Composable
fun Modifier.remoteClickable(
    onFocusChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
): Modifier {
    var longPressed by remember { mutableStateOf(false) }
    // Only a press that started here counts, so a key released after a dialog closes is ignored.
    var pressed by remember { mutableStateOf(false) }
    return this
        .onKeyEvent { event ->
            val e = event.nativeKeyEvent
            when {
                e.keyCode == KeyEvent.KEYCODE_MENU && onLongPress != null -> {
                    if (e.action == KeyEvent.ACTION_UP) onLongPress()
                    true
                }
                e.keyCode in SELECT_KEYS && e.action == KeyEvent.ACTION_DOWN -> {
                    if (e.repeatCount == 0) {
                        pressed = true
                        longPressed = false
                    }
                    if (e.repeatCount == 1 && onLongPress != null) {
                        longPressed = true
                        onLongPress()
                    }
                    true
                }
                e.keyCode in SELECT_KEYS && e.action == KeyEvent.ACTION_UP -> {
                    if (pressed && !longPressed) onClick()
                    pressed = false
                    longPressed = false
                    true
                }
                else -> false
            }
        }
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .onFocusChanged { onFocusChange(it.isFocused) }
        .focusable()
}

/** Shared lift-on-focus look for every card. */
@Composable
private fun CardFrame(
    width: Dp,
    focused: Boolean,
    modifier: Modifier,
    title: String,
    caption: String,
    content: @Composable () -> Unit,
) {
    val scale by animateFloatAsState(
        if (focused) 1.08f else 1f,
        spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow),
        label = "cardScale",
    )
    val elevation by animateDpAsState(if (focused) 24.dp else 0.dp, label = "cardElevation")
    val sheen by animateFloatAsState(if (focused) 1f else 0f, label = "cardSheen")
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.width(width).zIndex(if (focused) 1f else 0f)) {
        Box(
            modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .shadow(elevation, shape)
                .clip(shape)
                .border(1.dp, Color.White.copy(alpha = if (focused) 0.5f else 0.06f), shape),
        ) {
            content()
            // Glossy highlight across the focused card.
            Box(
                Modifier
                    .fillMaxSize()
                    .alpha(sheen)
                    .background(Brush.linearGradient(0f to Color.White.copy(alpha = 0.22f), 0.45f to Color.Transparent)),
            )
        }
        // Title and caption under every card, like the Apple TV app rows.
        Text(
            title,
            modifier = Modifier.padding(top = if (focused) 14.dp else 9.dp),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            caption,
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.55f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun AppCard(
    app: TvApp,
    width: Dp,
    onFocused: () -> Unit = {},
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    CardFrame(
        width = width,
        focused = focused,
        title = app.label,
        caption = app.subtitle,
        modifier = Modifier.remoteClickable(
            onFocusChange = { focused = it; if (it) onFocused() },
            onClick = onClick,
            onLongPress = onLongPress,
            focusRequester = focusRequester,
        ),
    ) { AppArtwork(app, iconFraction = 0.46f) }
}

/** The app's banner, or its icon on a colour gradient when it has none. */
@Composable
fun AppArtwork(app: TvApp, iconFraction: Float) {
    val banner = app.banner
    if (banner != null) {
        Image(banner, app.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        return
    }
    Box(
        Modifier.fillMaxSize().background(Brush.linearGradient(listOf(app.accent, app.accent.darken(0.5f)))),
        contentAlignment = Alignment.Center,
    ) {
        Image(app.icon, app.label, Modifier.fillMaxHeight(iconFraction).aspectRatio(1f))
    }
}

fun Color.darken(f: Float) = Color(red * (1 - f), green * (1 - f), blue * (1 - f), alpha)

/**
 * Arc Launcher focus ring: a white outer border with a dark inner one, so the focused
 * tile stands out on any wallpaper.
 */
fun Modifier.focusRing(focused: Boolean, shape: Shape): Modifier =
    if (!focused) border(1.dp, Color.White.copy(alpha = 0.08f), shape)
    else border(3.dp, Color.White, shape).padding(3.dp).border(1.5.dp, Color.Black.copy(alpha = 0.7f), shape)

/** Banner-only app tile for the dock and the app grid, like Arc Launcher. */
@Composable
fun BannerTile(
    app: TvApp,
    width: Dp,
    focusRequester: FocusRequester? = null,
    onFocused: (Boolean) -> Unit = {},
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (focused) 1.1f else 1f,
        spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow),
        label = "tileScale",
    )
    val elevation by animateDpAsState(if (focused) 18.dp else 2.dp, label = "tileElevation")
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .width(width)
            .aspectRatio(16f / 9f)
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(elevation, shape)
            .clip(shape)
            .remoteClickable(
                onFocusChange = { focused = it; onFocused(it) },
                onClick = onClick,
                onLongPress = onLongPress,
                focusRequester = focusRequester,
            )
            .focusRing(focused, shape),
    ) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(if (focused) 7.dp else 10.dp))) {
            AppArtwork(app, iconFraction = 0.5f)
        }
    }
}

/** Large Watch Next card: the title's artwork with its name and progress on top. */
@Composable
fun WatchCard(
    item: WatchedItem,
    width: Dp,
    focusRequester: FocusRequester? = null,
    onFocused: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, label = "watchScale")
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .width(width)
            .aspectRatio(16f / 9f)
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(if (focused) 20.dp else 2.dp, shape)
            .clip(shape)
            .remoteClickable(focusRequester = focusRequester, onFocusChange = { focused = it; onFocused(it) }, onClick = onClick)
            .focusRing(focused, shape),
    ) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(if (focused) 9.dp else 12.dp))) {
            val art = item.art
            if (art != null) Image(art, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else AppArtwork(item.app, iconFraction = 0.4f)
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f)),
                ),
            )
            Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    "${item.app.label} · ${watchedAgo(item)}",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    item.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.progress?.let { progress ->
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.25f)),
                    ) {
                        Box(
                            Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0.02f, 1f))
                                .background(Color.White),
                        )
                    }
                }
            }
        }
    }
}
