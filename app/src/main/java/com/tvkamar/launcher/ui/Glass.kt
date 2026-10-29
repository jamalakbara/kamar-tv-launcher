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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The picture behind everything (wallpaper or artwork). Panels draw it inside themselves, so they
 * show the wallpaper like the dock does instead of the cards they float over.
 */
val LocalGlassBackdrop = compositionLocalOf<ImageBitmap?> { null }

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
    val screen = LocalConfiguration.current
    Box(modifier.clip(shape)) {
        if (backdrop != null) {
            Box(Modifier.matchParentSize()) {
                // Screen-sized and shaded like the real backdrop, so the glass shows exactly the picture behind it.
                Box(
                    Modifier
                        .wrapContentSize(Alignment.TopStart, unbounded = true)
                        .offset(-left, -top)
                        .size(screen.screenWidthDp.dp, screen.screenHeightDp.dp),
                ) {
                    Image(backdrop, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                    BackdropShade()
                }
            }
        }
        Box(Modifier.matchParentSize().glassTint(shape))
        content()
    }
}
