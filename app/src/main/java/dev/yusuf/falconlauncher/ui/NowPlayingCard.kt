package dev.yusuf.falconlauncher.ui

import android.os.SystemClock
import androidx.compose.ui.viewinterop.AndroidView
import android.view.SurfaceView
import android.view.SurfaceHolder
import android.graphics.PixelFormat
import kotlin.math.sin
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.drawable.toBitmap
import dev.yusuf.falconlauncher.R
import dev.yusuf.falconlauncher.widgets.NowPlaying
import kotlinx.coroutines.delay

/**
 * Now Playing in the style of macOS's widget: artwork with the player app's icon in its corner; title,
 * "Artist – Album" and the equalizer on the right; big plain transport icons; and a progress bar with
 * elapsed and remaining time. Without anything playing it shows "Not Playing" (or asks for access);
 * for a remembered item from a closed app, Play reopens that app.
 */
@Composable
fun NowPlayingCard(
    nowPlaying: NowPlaying?,
    mediaAccess: Boolean,
    onOpen: () -> Unit,
    onAllowAccess: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    infoModifier: Modifier = Modifier,
    width: Dp = 600.dp,
) {
    val live = nowPlaying != null && !nowPlaying.isLastPlayed
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .width(width)
            .height(184.dp)
            .glass(Shapes.Widget)
            .padding(16.dp),
    ) {
        // The artwork is focusable: OK opens the playing app (or asks for access first).
        Surface(
            onClick = if (!mediaAccess) onAllowAccess else onOpen,
            modifier = infoModifier.size(152.dp),
            shape = ClickableSurfaceDefaults.shape(Shapes.Widget),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = Color.White,
                focusedContainerColor = Color.Transparent,
                focusedContentColor = Color.White,
            ),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = Shapes.Widget),
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        ) {
            Box(Modifier.fillMaxSize()) {
                Artwork(nowPlaying, size = 152.dp)
                nowPlaying?.packageName?.let { AppBadge(it, Modifier.align(Alignment.BottomEnd).padding(7.dp)) }
            }
        }
        Spacer(Modifier.width(22.dp))
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            !mediaAccess -> "Now Playing"
                            nowPlaying == null -> "Not Playing"
                            else -> nowPlaying.title
                        },
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when {
                            !mediaAccess -> "Press OK on the artwork to allow access"
                            nowPlaying == null -> "Press OK on the artwork to open Spotify"
                            nowPlaying.isLastPlayed -> listOf(nowPlaying.artist, "Last played" + appName(nowPlaying.packageName))
                                .filter { it.isNotBlank() }.joinToString("  ·  ")
                            nowPlaying.album.isNotBlank() -> "${nowPlaying.artist} – ${nowPlaying.album}"
                            else -> nowPlaying.artist
                        },
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (live) {
                    Spacer(Modifier.width(12.dp))
                    Equalizer(playing = nowPlaying!!.isPlaying)
                }
            }
            when {
                live -> Row(
                    horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TransportButton(painterResource(R.drawable.ic_fast_rewind), "Previous", onPrevious)
                    TransportButton(
                        painterResource(if (nowPlaying!!.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                        if (nowPlaying.isPlaying) "Pause" else "Play",
                        onPlayPause,
                        large = true,
                    )
                    TransportButton(painterResource(R.drawable.ic_fast_forward), "Next", onNext)
                }
                nowPlaying != null -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    // The app that played it is closed: Play reopens it.
                    TransportButton(painterResource(R.drawable.ic_play), "Open", onOpen, large = true)
                }
                else -> Spacer(Modifier.height(1.dp))
            }
            if (live && nowPlaying!!.durationMs > 0) {
                val accent = nowPlaying.artColors?.firstOrNull()?.let { Color(it) }
                Progress(nowPlaying, barColor = accent?.let { lighten(it) } ?: Color.White.copy(alpha = 0.85f))
            } else {
                Spacer(Modifier.height(1.dp))
            }
        }
    }
}

/** The playing app's icon, as a small badge in the artwork's corner (like macOS's widget). */
@Composable
private fun AppBadge(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val icon = remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    } ?: return
    Image(icon, contentDescription = null, modifier = modifier.size(30.dp).clip(RoundedCornerShape(8.dp)))
}

/** A brighter version of an artwork color, so the progress bar stays visible on the glass. */
private fun lighten(color: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    hsv[1] = (hsv[1] * 0.9f).coerceIn(0f, 1f)
    hsv[2] = maxOf(hsv[2], 0.85f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** " on YouTube" for the last-played label, or empty if the app is unknown. */
@Composable
private fun appName(packageName: String): String {
    val context = LocalContext.current
    return remember(packageName) {
        runCatching {
            " on " + context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0))
        }.getOrDefault("")
    }
}

@Composable
internal fun Artwork(nowPlaying: NowPlaying?, size: androidx.compose.ui.unit.Dp = 150.dp) {
    val modifier = Modifier.size(size).clip(Shapes.Widget)
    // Cross-fade only when the song changes; a sharper version of the same artwork swaps in place.
    val trackKey = nowPlaying?.let { it.title + "\u0000" + it.artist }
    val artByTrack = remember { mutableMapOf<String?, androidx.compose.ui.graphics.ImageBitmap?>() }
    artByTrack[trackKey] = nowPlaying?.art
    if (artByTrack.size > 4) artByTrack.keys.filter { it != trackKey }.take(artByTrack.size - 4).forEach(artByTrack::remove)
    Crossfade(trackKey, animationSpec = tween(350), label = "artwork") { key ->
        val art = artByTrack[key]
        if (art != null) {
            Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
        } else {
            Box(modifier.background(Color.White.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(R.drawable.ic_music),
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.45f),
                    modifier = Modifier.size(48.dp),
                )
            }
        }
    }
}

/** Progress bar with elapsed and remaining time; ticks once a second only while playing. */
@Composable
internal fun Progress(nowPlaying: NowPlaying, barColor: Color = Color.White.copy(alpha = 0.85f)) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(nowPlaying) {
        now = SystemClock.elapsedRealtime()
        while (nowPlaying.isPlaying) {
            delay(1_000 - (nowPlaying.positionAt(now) % 1_000))
            now = SystemClock.elapsedRealtime()
        }
    }
    val position = nowPlaying.positionAt(now)
    val fraction = (position.toFloat() / nowPlaying.durationMs).coerceIn(0f, 1f)
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.18f))
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(barColor, CircleShape)
            )
        }
        Spacer(Modifier.height(4.dp))
        Row {
            Text(formatTime(position), fontSize = 10.sp, color = Color.White.copy(alpha = 0.55f))
            Spacer(Modifier.weight(1f))
            Text("-" + formatTime(nowPlaying.durationMs - position), fontSize = 10.sp, color = Color.White.copy(alpha = 0.55f))
        }
    }
}

/**
 * Four bars bouncing at different speeds, like the playing indicator in Apple Music; short stubs when
 * paused. Drawn in its own tiny transparent surface on a background thread, so the animation never
 * makes the launcher UI redraw (which would cost a full frame of GPU time 30 times a second).
 */
@Composable
internal fun Equalizer(playing: Boolean) {
    val renderer = remember { EqualizerRenderer() }
    renderer.playing = playing
    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                setZOrderOnTop(true)
                holder.setFormat(PixelFormat.TRANSLUCENT)
                holder.addCallback(renderer)
            }
        },
        modifier = Modifier.size(width = 14.dp, height = 11.dp),
    )
}

private class EqualizerRenderer : SurfaceHolder.Callback {
    @Volatile
    var playing = false

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(217, 255, 255, 255)
    }
    private val speeds = floatArrayOf(7.3f, 9.1f, 6.2f, 8.4f)
    private val phases = floatArrayOf(0f, 1.7f, 3.1f, 4.6f)

    override fun surfaceCreated(holder: SurfaceHolder) {
        running = true
        thread = Thread({ loop(holder) }, "now-playing-eq").apply { start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false
        thread?.join(300)
        thread = null
    }

    private fun loop(holder: SurfaceHolder) {
        var level = 0f
        while (running) {
            // Ease the bars up when playing and down to stubs when paused.
            level += ((if (playing) 1f else 0f) - level) * 0.15f
            // CPU canvas: a GPU-locked SurfaceView canvas makes the main window redraw on this TV.
            val canvas = runCatching { holder.lockCanvas() }.getOrNull() ?: break
            try {
                canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                val t = SystemClock.uptimeMillis() / 1000f
                val w = canvas.width.toFloat()
                val h = canvas.height.toFloat()
                val gap = w * 0.12f
                val bar = (w - gap * 3) / 4
                for (i in 0 until 4) {
                    val wave = 0.5f + 0.5f * sin(t * speeds[i] + phases[i])
                    val barHeight = h * (0.22f + 0.78f * wave * level)
                    val left = i * (bar + gap)
                    canvas.drawRoundRect(left, h - barHeight, left + bar, h, bar / 2, bar / 2, paint)
                }
            } finally {
                runCatching { holder.unlockCanvasAndPost(canvas) }
            }
            // Idle once the bars have settled while paused.
            Thread.sleep(if (!playing && level < 0.01f) 250L else 33L)
        }
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

@Composable
private fun TransportButton(icon: Painter, description: String, onClick: () -> Unit, large: Boolean = false) {
    // Plain icons like macOS's widget; a white circle only appears behind the focused one.
    val size = if (large) 60.dp else 52.dp
    Surface(
        onClick = onClick,
        modifier = Modifier.size(size),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Color.White.copy(alpha = 0.9f),
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(if (large) 36.dp else 30.dp))
        }
    }
}
