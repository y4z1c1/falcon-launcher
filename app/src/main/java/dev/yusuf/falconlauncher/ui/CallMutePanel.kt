package dev.yusuf.falconlauncher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import dev.yusuf.falconlauncher.MainActivity
import dev.yusuf.falconlauncher.tv.AncsClient

/**
 * Settings › Call muting: on/off, a test, and pairing the iPhone over Bluetooth. Once paired, the TV mutes
 * while the iPhone rings and during any call, incoming or outgoing.
 */
@Composable
fun CallMutePanel(
    enabled: Boolean,
    ancsState: AncsClient.State,
    onToggle: (Boolean) -> Unit,
    onTest: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.springIn().width(600.dp).glass(Shapes.Dialog, strong = true).padding(24.dp),
        ) {
            Text("Call muting", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Mutes the TV while you're on a call with your iPhone, and restores the sound afterwards.",
                fontSize = 13.sp, color = Color.White.copy(alpha = 0.65f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton(if (enabled) "On · turn off" else "Off · turn on", onClick = { onToggle(!enabled) }, modifier = Modifier.focusRequester(first), compact = true)
                GlassButton("Test (3 s)", onClick = onTest, compact = true)
            }

            Section("IPHONE · BLUETOOTH")
            Text(
                when (ancsState) {
                    AncsClient.State.ACTIVE -> "Connected · the TV mutes while your iPhone rings and during calls."
                    AncsClient.State.CONNECTING -> "Connecting to your iPhone…"
                    AncsClient.State.WAITING -> "Waiting for your iPhone. On the iPhone, open Settings › Bluetooth, tap this TV " +
                        "under Other Devices, pair, and allow \"Share System Notifications\"."
                    AncsClient.State.NO_PERMISSION -> "Falcon needs Bluetooth access to hear your iPhone's calls."
                    AncsClient.State.OFF -> if (enabled) "Bluetooth is off on the TV." else "Turn call muting on to pair your iPhone."
                },
                fontSize = 14.sp,
            )
            if (ancsState == AncsClient.State.NO_PERMISSION) {
                GlassButton("Allow Bluetooth", onClick = { (context as? MainActivity)?.requestBluetoothPermissions() }, compact = true)
            }
        }
    }
    LaunchedEffect(Unit) {
        repeat(10) {
            withFrameNanos { }
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, color = Color.White.copy(alpha = 0.5f), modifier = Modifier.padding(top = 8.dp))
}
