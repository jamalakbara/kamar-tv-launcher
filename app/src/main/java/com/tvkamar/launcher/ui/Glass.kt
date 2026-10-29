package com.tvkamar.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The picture behind everything (wallpaper or artwork). Panels draw it inside themselves, so they
 * show the wallpaper like the dock does instead of the cards they float over.
 */
val LocalGlassBackdrop = compositionLocalOf<ImageBitmap?> { null }

/** The aerial video as it plays, drawn by the glass instead of [LocalGlassBackdrop] when set. */
val LocalGlassVideo = compositionLocalOf<GraphicsLayer?> { null }

/** Light glass of the Home dock, shared by the sidebar and its panels so they all match. */
fun Modifier.glassTint(shape: Shape): Modifier = this
    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.10f))))
    .border(1.dp, Color.White.copy(alpha = 0.22f), shape)

/**
 * Glass like the dock, shared by the sidebar and the panels that open beside it. [left] and [top]
 * are where the panel sits on screen, so the picture inside lines up with the real one.
 */
@Composable
fun GlassPanel(shape: Shape, left: Dp, top: Dp, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val backdrop = LocalGlassBackdrop.current
    val video = LocalGlassVideo.current
    val screen = LocalConfiguration.current
    Box(modifier.clip(shape)) {
        if (backdrop != null || video != null) {
            Box(Modifier.matchParentSize()) {
                // Screen-sized and shaded like the real backdrop, so the glass shows exactly the picture behind it.
                Box(
                    Modifier
                        .wrapContentSize(Alignment.TopStart, unbounded = true)
                        .offset(-left, -top)
                        .size(screen.screenWidthDp.dp, screen.screenHeightDp.dp),
                ) {
                    if (video != null) {
                        // Replays the backdrop's recording, so the video moves behind the glass too.
                        // Panels are dialogs, a separate window that doesn't redraw when the video does: redraw every frame.
                        var frame by remember { mutableLongStateOf(0L) }
                        LaunchedEffect(video) { while (true) withFrameNanos { frame = it } }
                        Box(Modifier.matchParentSize().drawBehind { frame; drawLayer(video) })
                    } else if (backdrop != null) {
                        Image(backdrop, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                    }
                    BackdropShade()
                }
            }
        }
        Box(Modifier.matchParentSize().glassTint(shape))
        content()
    }
}
