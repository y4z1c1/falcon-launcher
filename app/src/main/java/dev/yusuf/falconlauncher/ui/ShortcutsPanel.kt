package dev.yusuf.falconlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.yusuf.falconlauncher.R
import dev.yusuf.falconlauncher.data.LightShortcut
import dev.yusuf.falconlauncher.widgets.Room
import dev.yusuf.falconlauncher.widgets.WizBulb

/**
 * Settings › Light shortcuts: which lamp, room or all lights each remote number key switches.
 * Pick a key and choose from the dropdown.
 */
@Composable
fun ShortcutsPanel(
    shortcuts: Map<Int, LightShortcut>,
    bulbs: List<WizBulb>,
    names: Map<String, String>,
    rooms: Map<String, String>,
    onSet: (Int, LightShortcut) -> Unit,
    onDismiss: () -> Unit,
) {
    var editing by remember { mutableStateOf<Int?>(null) }
    val first = remember { FocusRequester() }
    fun describe(s: LightShortcut?) = when (s) {
        null, LightShortcut.None -> "Nothing"
        LightShortcut.AllLights -> "All lights"
        is LightShortcut.Lamp -> bulbs.firstOrNull { it.mac == s.mac }?.displayName(names) ?: "Missing lamp"
        is LightShortcut.InRoom -> s.room.label
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .springIn()
                .width(520.dp)
                .glass(Shapes.Dialog, strong = true)
                .padding(24.dp),
        ) {
            Text("Light shortcuts", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Press a number on the remote to switch lights, from any app except the TV app.",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(bottom = 10.dp),
            )
            LightShortcut.KEYS.chunked(5).let { (left, right) ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(left, right).forEach { column ->
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            column.forEach { key ->
                                Box {
                                    KeyRow(
                                        key = key,
                                        label = describe(shortcuts[key]),
                                        active = shortcuts[key] != null && shortcuts[key] != LightShortcut.None,
                                        onClick = { editing = key },
                                        modifier = if (key == 1) Modifier.focusRequester(first) else Modifier,
                                    )
                                    if (editing == key) {
                                        val bulbIcon = painterResource(R.drawable.ic_bulb)
                                        val roomIcon = rememberVectorPainter(Icons.Default.Home)
                                        val noneIcon = rememberVectorPainter(Icons.Default.Close)
                                        GlassDropdown(
                                            title = "Key $key",
                                            width = 260.dp,
                                            items = buildList {
                                                add(MenuItem("All lights", bulbIcon) { onSet(key, LightShortcut.AllLights) })
                                                Room.entries.filter { r -> bulbs.any { rooms[it.mac] == r.name } }.forEach { r ->
                                                    add(MenuItem(r.label, roomIcon) { onSet(key, LightShortcut.InRoom(r)) })
                                                }
                                                add(null)
                                                bulbs.forEach { b -> add(MenuItem(b.displayName(names), bulbIcon) { onSet(key, LightShortcut.Lamp(b.mac)) }) }
                                                add(null)
                                                add(MenuItem("Nothing", noneIcon) { onSet(key, LightShortcut.None) })
                                            },
                                            onDismiss = { editing = null },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
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
private fun KeyRow(key: Int, label: String, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(48.dp),
        shape = ClickableSurfaceDefaults.shape(Shapes.MenuItem),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.06f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
            val content = LocalContentColor.current
            Box(
                Modifier.size(30.dp).background(content.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text(key.toString(), fontSize = 15.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(12.dp))
            Text(
                label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (active) content else content.copy(alpha = 0.45f),
                maxLines = 1,
            )
        }
    }
}
