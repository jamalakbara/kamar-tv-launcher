package com.tvkamar.launcher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tvkamar.launcher.TvApp

/** Options for one app, shown when OK is held (or Menu is pressed) on a tile. */
@Composable
fun AppMenu(
    app: TvApp,
    /** Apps per row where the menu was opened; null hides the up/down moves. */
    rowSize: Int?,
    hiddenCount: Int,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onMove: (Int) -> Unit,
    onHide: () -> Unit,
    onUnhideAll: () -> Unit,
    onAppInfo: () -> Unit,
    onUninstall: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .width(380.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(Color(0xF02A2C33))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    app.label,
                    modifier = Modifier.padding(start = 14.dp, bottom = 8.dp),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
                MenuItem("Open", onOpen, Modifier.focusRequester(first))
                MenuItem("Move Left", { onMove(-1) })
                MenuItem("Move Right", { onMove(1) })
                if (rowSize != null) {
                    MenuItem("Move Up a Row", { onMove(-rowSize) })
                    MenuItem("Move Down a Row", { onMove(rowSize) })
                }
                MenuItem("Hide App", onHide)
                MenuItem("App Info", onAppInfo)
                if (!app.isSystem) MenuItem("Uninstall", onUninstall, destructive = true)
                if (hiddenCount > 0) MenuItem("Show Hidden Apps ($hiddenCount)", onUnhideAll)
                Spacer(Modifier.height(2.dp))
                MenuItem("Cancel", onDismiss)
            }
        }
    }
}

@Composable
private fun MenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, label = "menuScale")
    val textColor = when {
        focused -> Color.Black
        destructive -> Color(0xFFFF6B6B)
        else -> Color.White
    }
    Box(
        modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) Color.White else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(text, color = textColor, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}
