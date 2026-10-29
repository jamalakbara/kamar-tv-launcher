package com.tvkamar.launcher.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale

/**
 * Full-screen picture behind everything. With a [wallpaper] it is shown edge to edge, like
 * Arc Launcher. Otherwise it follows the focused title or app like the Apple TV app: a
 * blurred wash of the artwork's colours fills the screen, the artwork itself sits on the
 * right and is feathered into that wash so there are no hard edges.
 *
 * [dim] (0..1) darkens it further, e.g. while the app grid is scrolled up over it.
 */
@Composable
fun Backdrop(slide: Slide?, wallpaper: ImageBitmap?, dim: Float) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (wallpaper != null) {
            // Cross-fade as the slideshow moves on.
            Crossfade(wallpaper, animationSpec = tween(1500), label = "wallpaper") { picture ->
                Image(picture, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        } else {
            Crossfade(slide, animationSpec = tween(800), label = "backdrop") { shown ->
                if (shown != null) BackdropArt(shown)
            }
        }
        BackdropShade()
        if (dim > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
    }
}

/** Darkens the left edge for the sidebar and the bottom under the rows. Also drawn in the glass. */
@Composable
fun BackdropShade() {
    // Left edge dark enough for the sidebar.
    Box(
        Modifier.fillMaxSize().background(
            Brush.horizontalGradient(
                0f to Color.Black.copy(alpha = 0.75f),
                0.25f to Color.Black.copy(alpha = 0.35f),
                0.5f to Color.Transparent,
            ),
        ),
    )
    // Bottom fades to near-black under the rows.
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                0.45f to Color.Transparent,
                0.75f to Color.Black.copy(alpha = 0.35f),
                1f to Color.Black.copy(alpha = 0.8f),
            ),
        ),
    )
}

@Composable
private fun BackdropArt(slide: Slide) {
    Box(Modifier.fillMaxSize()) {
        // 32x18 copy stretched to full screen: bilinear scaling turns it into a soft blur.
        Image(slide.backdrop, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))

        // The artwork, faded out towards every edge so it melts into the wash.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth(0.6f)
                .fillMaxHeight(0.78f)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.radialGradient(
                            0f to Color.Black,
                            0.5f to Color.Black,
                            1f to Color.Transparent,
                            center = Offset(size.width * 0.55f, size.height * 0.45f),
                            radius = size.width * 0.58f,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                    drawRect(
                        Brush.horizontalGradient(0f to Color.Transparent, 0.35f to Color.Black),
                        blendMode = BlendMode.DstIn,
                    )
                    drawRect(
                        // Fades top and bottom: video thumbnails often carry letterbox bars.
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.22f to Color.Black,
                            0.5f to Color.Black,
                            1f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            val art = slide.art
            if (art != null) {
                Image(art, slide.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                AppArtwork(slide.app, iconFraction = 0.4f)
            }
        }
        // Tone the artwork down a little so white logos don't glare.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f)))
    }
}
