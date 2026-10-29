package com.tvkamar.launcher.ui

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.tvkamar.launcher.airplay.AirPlayService
import com.tvkamar.launcher.airplay.AirPlayState
import java.net.Inet4Address

/**
 * AirPlay settings in a panel on the left, like [WallpaperPanel]: turn the receiver on or off,
 * see the name it goes by (the TV's own device name), and whether it is reachable, with tips for
 * when a Mac or iPhone can't find it.
 */
@Composable
fun AirPlayPanel(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    var enabled by remember { mutableStateOf(AirPlayService.isEnabled(context)) }
    var name by remember { mutableStateOf(AirPlayService.name(context)) }
    // Follow renames made in the TV's settings while the panel is open.
    DisposableEffect(Unit) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { name = AirPlayService.name(context) }
        }
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.DEVICE_NAME), false, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    val state = AirPlayService.state
    val network = rememberNetworkKind()
    val address = remember(network) { ipv4Address(context) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        (LocalView.current.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)
        Box(
            Modifier.fillMaxSize().background(
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
                    Text("AirPlay", Modifier.padding(start = 14.dp, bottom = 2.dp), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(
                        statusLine(enabled, state, network),
                        Modifier.padding(start = 14.dp, end = 8.dp, bottom = 6.dp),
                        fontSize = 13.sp,
                        color = if (enabled && state is AirPlayState.On && network != NetworkKind.None) Color(0xFF30D158)
                            else Color.White.copy(alpha = 0.6f),
                    )

                    OptionRow("Allow screen mirroring", checked = enabled, modifier = Modifier.focusRequester(first)) {
                        enabled = !enabled
                        AirPlayService.setEnabled(context, enabled)
                    }

                    Section("Name")
                    ValueRow("TV name", name) { openRenameScreen(context) }
                    Hint("Shown under Screen Mirroring. It's the TV's own name, also used for Bluetooth and Chromecast; OK opens the TV's rename screen.")

                    Section("This TV")
                    ValueRow("Network", when (network) {
                        NetworkKind.Wifi -> "Wi-Fi"
                        NetworkKind.Ethernet -> "Ethernet"
                        NetworkKind.None -> "Not connected"
                    }) {}
                    ValueRow("IP address", address ?: "—") {}
                    if (state is AirPlayState.On) ValueRow("Port", state.port.toString()) {}

                    Section("Can't find ${name}?")
                    Hint(
                        "• The iPhone or Mac must be on the same Wi-Fi as the TV, not a guest network.\n" +
                            "• Turn off any VPN on the iPhone or Mac.\n" +
                            "• Mac: System Settings → Network → Firewall must not block all incoming connections.\n" +
                            "• Some routers block devices from seeing each other (“AP isolation” or “client isolation”). Turn that off.\n" +
                            "• Close and reopen Screen Mirroring to refresh the list.",
                    )

                    Spacer(Modifier.height(6.dp))
                    ValueRow("Done", "", onClick = onDismiss)
                }
            }
        }
    }
}

private fun statusLine(enabled: Boolean, state: AirPlayState, network: NetworkKind) = when {
    !enabled -> "Off — this TV is hidden from AirPlay"
    network == NetworkKind.None -> "The TV isn't connected to a network"
    state is AirPlayState.On -> "On — visible as “${state.name}”"
    state is AirPlayState.Failed -> "Not working: ${state.reason}"
    else -> "Starting…"
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        Modifier.padding(start = 14.dp, end = 8.dp, bottom = 4.dp),
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = Color.White.copy(alpha = 0.45f),
    )
}

/** The TV's own rename screen (Settings → Device Preferences → About → Device name). */
private fun openRenameScreen(context: Context) {
    val intents = listOf(Intent("android.settings.DEVICE_NAME"), Intent(Settings.ACTION_DEVICE_INFO_SETTINGS), Intent(Settings.ACTION_SETTINGS))
    for (intent in intents) {
        if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
}

private fun ipv4Address(context: Context): String? {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
    return props.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address }?.hostAddress
}
