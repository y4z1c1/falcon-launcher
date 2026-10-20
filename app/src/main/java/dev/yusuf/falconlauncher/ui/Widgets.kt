package dev.yusuf.falconlauncher.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.yusuf.falconlauncher.R
import dev.yusuf.falconlauncher.widgets.NowPlaying
import dev.yusuf.falconlauncher.widgets.Weather
import dev.yusuf.falconlauncher.widgets.Room
import dev.yusuf.falconlauncher.widgets.WizBulb

private val WidgetShape = Shapes.Widget
private val WidgetHeight = 84.dp

@Composable
fun WidgetColumn(
    weather: Weather?,
    bulbs: List<WizBulb>,
    rooms: Map<String, String>,
    onWeatherClick: () -> Unit,
    onLightsClick: () -> Unit,
    onLightsMenu: () -> Unit,
    modifier: Modifier = Modifier,
    lightsModifier: Modifier = Modifier,
) {
    // Stacked to the right of Now Playing; together exactly as tall as it (2 × 84 + 16 = 184dp).
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = modifier) {
        WidgetCard(width = 312.dp, onClick = onWeatherClick) {
            WeatherContent(weather)
        }
        WidgetCard(width = 312.dp, onClick = onLightsClick, onMenu = onLightsMenu, modifier = lightsModifier) {
            LightsContent(bulbs, rooms)
        }
    }
}

@Composable
private fun WidgetCard(
    width: Dp,
    onClick: () -> Unit,
    onMenu: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .glass(WidgetShape)
            .width(width)
            .height(WidgetHeight)
            .longPressMenu(onMenu),
        shape = ClickableSurfaceDefaults.shape(WidgetShape),
        // Same glass as the dock and the music card (drawn by Modifier.glass); focus adds a light wash
        // and a white outline on top.
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            focusedContainerColor = Color.White.copy(alpha = 0.12f),
            focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border.None,
            focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = WidgetShape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp),
            content = content,
        )
    }
}

@Composable
internal fun WeatherContent(weather: Weather?) {
    if (weather == null) {
        Text("🌡️", fontSize = 30.sp)
        Column {
            Text("Weather", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text("Loading…", fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
        }
        return
    }
    val (description, emoji) = weather.summary
    Text(emoji, fontSize = 32.sp)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${weather.temperature}°", fontSize = 24.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(8.dp))
            Text(
                "H ${weather.high}°  L ${weather.low}°",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
        Text(
            "${weather.place} · $description",
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LightsContent(bulbs: List<WizBulb>, rooms: Map<String, String>) {
    val onCount = bulbs.count { it.isOn }
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (onCount > 0) LightOn.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_bulb),
            contentDescription = null,
            tint = if (onCount > 0) LightOn else Color.White.copy(alpha = 0.6f),
            modifier = Modifier.size(24.dp),
        )
    }
    val perRoom = Room.entries.mapNotNull { room ->
        val inRoom = bulbs.filter { rooms[it.mac] == room.name }
        if (inRoom.isEmpty()) null else room.label to inRoom.count { it.isOn }
    }
    Column {
        if (perRoom.isEmpty()) {
            Text("Lights", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(
                when {
                    bulbs.isEmpty() -> "No WiZ lights found"
                    onCount == 0 -> "All off"
                    onCount == bulbs.size -> "All on"
                    else -> "$onCount of ${bulbs.size} on"
                },
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.7f),
            )
        } else {
            perRoom.forEach { (label, on) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (on == 0) "Off" else "$on on",
                        fontSize = 12.sp,
                        color = if (on == 0) Color.White.copy(alpha = 0.55f) else LightOn,
                    )
                }
            }
        }
    }
}
