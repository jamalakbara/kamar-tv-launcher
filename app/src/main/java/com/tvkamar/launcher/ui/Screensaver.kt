package com.tvkamar.launcher.ui

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Arc Launcher's OLED screensaver: black screen with a dim clock that jumps to a new spot
 * every 30 s so nothing burns in. Any key closes it (handled by the caller).
 */
@Composable
fun Screensaver(visible: Boolean) {
    AnimatedVisibility(visible, enter = fadeIn(tween(1200)), exit = fadeOut(tween(250))) {
        val context = LocalContext.current
        val now = rememberNow()
        var spot by remember { mutableStateOf(0.5f to 0.5f) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(30_000)
                spot = Random.nextFloat() to Random.nextFloat()
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            // Keep the clock fully on screen: it is about 260 x 90 dp.
            val x = (maxWidth - 260.dp) * spot.first
            val y = (maxHeight - 90.dp) * spot.second
            Box(Modifier.offset(x, y)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        DateFormat.format(if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm", now).toString(),
                        fontSize = 56.sp,
                        fontWeight = FontWeight.Light,
                        color = Color.White.copy(alpha = 0.55f),
                    )
                    Text(
                        DateFormat.format("EEEE, d MMMM", now).toString(),
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.35f),
                    )
                }
            }
        }
    }
}
