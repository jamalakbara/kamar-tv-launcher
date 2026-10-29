package com.tvkamar.launcher.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.text.format.DateFormat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Date

private val Shadowed = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 10f))

enum class NetworkKind { Wifi, Ethernet, None }

/** Current connection type, updated live. */
@Composable
fun rememberNetworkKind(): NetworkKind {
    val context = LocalContext.current
    val cm = remember { context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    var kind by remember { mutableStateOf(cm.kindOf(cm.activeNetwork)) }
    DisposableEffect(cm) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { kind = cm.kindOf(cm.activeNetwork) }
            override fun onLost(network: Network) { kind = cm.kindOf(cm.activeNetwork) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                kind = cm.kindOf(cm.activeNetwork)
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }
    return kind
}

private fun ConnectivityManager.kindOf(network: Network?): NetworkKind {
    val caps = network?.let { getNetworkCapabilities(it) } ?: return NetworkKind.None
    return when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.Ethernet
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.Wifi
        else -> NetworkKind.None
    }
}

/** Ticks every 10 s. */
@Composable
fun rememberNow(): Date {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(10_000)
        }
    }
    return now
}

/**
 * Arc Launcher's status bar: network shortcut on the left, big clock (and wallpaper credit) on the right.
 * [visible] false slides it away while the app grid is in use.
 */
@Composable
fun StatusBar(visible: Boolean, contentStart: Dp, credit: String?, downRequester: FocusRequester, onNetwork: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val now = rememberNow()
    val shown by animateFloatAsState(if (visible) 1f else 0f, label = "statusBar")
    val network = rememberNetworkKind()
    Row(
        modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = shown; translationY = -(1f - shown) * 40.dp.toPx() }
            .padding(start = contentStart, end = 36.dp, top = 18.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (visible) {
            var focused by remember { mutableStateOf(false) }
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (focused) Color.White else Color.Transparent)
                    .focusProperties { down = downRequester }
                    .remoteClickable(onFocusChange = { focused = it }, onClick = onNetwork),
                contentAlignment = Alignment.Center,
            ) {
                val icon = when (network) {
                    NetworkKind.Wifi -> Icons.Rounded.Wifi
                    NetworkKind.Ethernet -> Icons.Rounded.SettingsEthernet
                    NetworkKind.None -> Icons.Rounded.WifiOff
                }
                Icon(icon, "Network settings", Modifier.size(22.dp), tint = if (focused) Color.Black else Color.White)
            }
        }
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                DateFormat.format(if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm", now).toString(),
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
                style = Shadowed,
            )
            Text(
                DateFormat.format("EEEE, d MMMM", now).toString(),
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.75f),
                style = Shadowed,
            )
            // Who made the slideshow picture, like Arc Launcher.
            if (credit != null) {
                Text(
                    credit,
                    Modifier.padding(top = 6.dp),
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.65f),
                    style = Shadowed,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(2.dp))
    }
}
