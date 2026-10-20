package dev.yusuf.falconlauncher.ui

import android.text.format.DateFormat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.yusuf.falconlauncher.data.AppRepository
import dev.yusuf.falconlauncher.widgets.NowPlaying
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/** How far the screensaver content wanders to avoid burn-in, and how often it moves. */
private const val DRIFT_DP = 28
private const val DRIFT_EVERY_MS = 60_000L

/**
 * The screensaver: the launcher's ambient glow behind a large clock, with Now Playing below it. Mostly black, for the mini-LED panel; the whole group glides to a new spot every minute.
 */
@Composable
fun DreamScreen(nowPlaying: StateFlow<NowPlaying?>) {
    val playing by nowPlaying.collectAsState()

    // Glow colors: from the artwork while music plays, otherwise the launcher's calm default.
    val artColors = playing?.takeIf { it.isPlaying }?.artColors
    val base = AppRepository.DEFAULT_ACCENT
    val targets = if (artColors != null) List(3) { Color(artColors.getOrElse(it) { artColors.first() }) } else listOf(base, base, base)
    val c0 = animateColorAsState(targets[0], tween(1200), label = "d0")
    val c1 = animateColorAsState(targets[1], tween(1200), label = "d1")
    val c2 = animateColorAsState(targets[2], tween(1200), label = "d2")

    var drift by remember { mutableStateOf(0 to 0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(DRIFT_EVERY_MS)
            drift = Random.nextInt(-DRIFT_DP, DRIFT_DP + 1) to Random.nextInt(-DRIFT_DP, DRIFT_DP + 1)
        }
    }
    val dx by animateDpAsState(drift.first.dp, tween(4_000), label = "dx")
    val dy by animateDpAsState(drift.second.dp, tween(4_000), label = "dy")

    Box(Modifier.fillMaxSize()) {
        AmbientBackground(colors = { listOf(c0.value, c1.value, c2.value) })
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().offset(dx, dy),
        ) {
            DreamClock()
            Spacer(Modifier.height(56.dp))
            playing?.let { DreamNowPlaying(it) }
        }
    }
}

@Composable
private fun DreamClock() {
    val context = LocalContext.current
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(60_000 - value % 60_000)
        }
    }
    val is24h = remember { DateFormat.is24HourFormat(context) }
    val locale = remember { Locale.getDefault() }
    val date = Date(now)
    Text(
        DateFormat.format(if (is24h) "HH:mm" else "h:mm", date).toString(),
        fontFamily = AnybodyWide,
        fontWeight = FontWeight.Bold,
        fontSize = 120.sp,
        letterSpacing = (-2).sp,
    )
    Text(
        DateFormat.format(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), date).toString()
            .replace(",", "").uppercase(locale),
        fontSize = 18.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 5.sp,
        color = Color.White.copy(alpha = 0.8f),
    )
}

@Composable
private fun DreamNowPlaying(item: NowPlaying) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.width(460.dp).glass(Shapes.Widget).padding(14.dp),
    ) {
        Artwork(item, size = 84.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!item.isLastPlayed) {
                    Equalizer(playing = item.isPlaying)
                    Spacer(Modifier.width(10.dp))
                }
                Text(item.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                if (item.isLastPlayed) "${item.artist}  ·  Last played" else item.artist,
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.65f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!item.isLastPlayed && item.durationMs > 0) {
                Spacer(Modifier.height(10.dp))
                Progress(item)
            }
        }
    }
}
