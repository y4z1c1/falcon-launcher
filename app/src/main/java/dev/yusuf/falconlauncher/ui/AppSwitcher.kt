package dev.yusuf.falconlauncher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Text
import dev.yusuf.falconlauncher.data.AppEntry
import kotlinx.coroutines.launch

private val CardWidth = 300.dp

/**
 * Holding Home: recently used apps as large cards over a dimmed screen, with the app you came from focused.
 * OK switches to an app; Up closes it for good, sliding its card away like a swipe up on Apple TV.
 */
@Composable
fun AppSwitcher(
    apps: List<AppEntry>,
    recents: List<String>,
    current: String?,
    onOpen: (AppEntry) -> Unit,
    /** Stops the app; true if it worked (it then leaves [recents]). */
    onClose: suspend (AppEntry) -> Boolean,
) {
    val byPackage = remember(apps) { apps.associateBy { it.packageName } }
    val entries = remember(byPackage, recents) { recents.mapNotNull { byPackage[it] } }
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(emptySet<String>()) }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    var focused by remember { mutableStateOf<String?>(null) }
    var focusedIndex by remember { mutableIntStateOf(0) }
    val dim = remember { Animatable(0f) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = dim.value }
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Black.copy(alpha = 0.92f))))
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.springIn()) {
            if (entries.isEmpty() && apps.isNotEmpty()) {
                Text("No recent apps", fontSize = 18.sp, color = Color.White.copy(alpha = 0.6f))
            }
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally),
                contentPadding = PaddingValues(horizontal = 72.dp, vertical = 48.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                itemsIndexed(entries, key = { _, app -> app.packageName }) { index, app ->
                    val pkg = app.packageName
                    SwitcherCard(
                        app = app,
                        closing = pkg in closing,
                        onClick = { onOpen(app) },
                        onCloseRequest = {
                            if (pkg !in closing) {
                                closing = closing + pkg
                                scope.launch {
                                    onClose(app)
                                    closing = closing - pkg
                                }
                            }
                        },
                        modifier = Modifier.animateItem(),
                        cardModifier = Modifier
                            .focusRequester(requesters.getOrPut(pkg) { FocusRequester() })
                            .onFocusChanged {
                                if (it.isFocused) {
                                    focused = pkg
                                    focusedIndex = index
                                }
                            },
                    )
                }
            }
            if (entries.isNotEmpty()) {
                Text(
                    "OK  Open      ▲  Close      Back  Return",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.5f),
                )
            }
        }
    }

    LaunchedEffect(Unit) { dim.animateTo(1f, tween(180)) }
    // Start on the app you came from (or the most recent one), and when a card is closed, move to its neighbor.
    LaunchedEffect(entries) {
        if (entries.isEmpty() || entries.any { it.packageName == focused }) return@LaunchedEffect
        val target = if (focused == null) {
            entries.firstOrNull { it.packageName == current } ?: entries.first()
        } else {
            entries[focusedIndex.coerceAtMost(entries.lastIndex)]
        }
        repeat(10) {
            withFrameNanos { }
            if (runCatching { requesters.getValue(target.packageName).requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
}

@Composable
private fun SwitcherCard(
    app: AppEntry,
    closing: Boolean,
    onClick: () -> Unit,
    onCloseRequest: () -> Unit,
    modifier: Modifier,
    cardModifier: Modifier,
) {
    val lift by animateFloatAsState(if (closing) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 300f), label = "lift")
    var isFocused by remember { mutableStateOf(false) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(CardWidth)
            .graphicsLayer {
                translationY = -lift * 140.dp.toPx()
                alpha = 1f - 0.75f * lift
            },
    ) {
        Card(
            onClick = onClick,
            modifier = cardModifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .onFocusChanged { isFocused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    if (event.key != Key.DirectionUp) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) onCloseRequest()
                    true
                },
            shape = CardDefaults.shape(Shapes.Widget),
            scale = CardDefaults.scale(focusedScale = 1.08f, pressedScale = 1.03f),
            border = CardDefaults.border(
                focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = Shapes.Widget),
            ),
            glow = CardDefaults.glow(
                focusedGlow = Glow(elevationColor = Color.Black.copy(alpha = 0.6f), elevation = 20.dp),
            ),
            colors = CardDefaults.colors(containerColor = Color(0xFF22252E)),
        ) {
            if (app.isBanner) {
                Image(
                    bitmap = app.image,
                    contentDescription = app.label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize().background(app.accent.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                    Image(bitmap = app.image, contentDescription = app.label, modifier = Modifier.size(72.dp))
                }
            }
        }
        Text(
            app.label,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = Color.White.copy(alpha = if (isFocused) 1f else 0.55f),
            modifier = Modifier.padding(top = 20.dp),
        )
    }
}
