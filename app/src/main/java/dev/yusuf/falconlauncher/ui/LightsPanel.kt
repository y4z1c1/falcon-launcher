package dev.yusuf.falconlauncher.ui

import androidx.compose.foundation.Canvas
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.BlendMode
import dev.yusuf.falconlauncher.data.PlanRoom
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import dev.yusuf.falconlauncher.R
import dev.yusuf.falconlauncher.data.HomePlan
import dev.yusuf.falconlauncher.widgets.Room
import dev.yusuf.falconlauncher.widgets.WizBulb
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

val LightOn = Color(0xFFFFC857)


/** Callbacks from the lights panel, so the panel itself stays stateless. */
class LightsActions(
    val toggleBulb: (WizBulb) -> Unit,
    val setBulbBrightness: (WizBulb, Int) -> Unit,
    val setRoom: (macs: Set<String>, on: Boolean?, dimming: Int?) -> Unit,
    val identify: (WizBulb) -> Unit,
    val rename: (WizBulb) -> Unit,
    val rescan: () -> Unit,
    val place: (WizBulb, x: Float, y: Float, Room?) -> Unit,
    /** Live mode: faster state checks while the panel is open. */
    val live: (Boolean) -> Unit,
)

fun WizBulb.displayName(names: Map<String, String>) = names[mac] ?: defaultName

/**
 * The lights panel: your home's floor plan in isometric line style, with every lamp where it really
 * hangs, glowing on the floor while it's on. D-pad moves between lamps, OK switches one, hold OK for its
 * options. Lamps not on the plan yet are listed on the right; choosing one starts placement: move the
 * marker with the D-pad, OK drops it there (its room follows from where it lands), Back cancels.
 * States refresh every few seconds, so changes from Google Home show up too.
 */
@Composable
fun LightsPanel(
    plan: HomePlan,
    bulbs: List<WizBulb>,
    positions: Map<String, Offset>,
    rooms: Map<String, String>,
    names: Map<String, String>,
    actions: LightsActions,
    onDismiss: () -> Unit,
) {
    val placed = bulbs.filter { it.mac in positions }
    val unplaced = bulbs.filter { it.mac !in positions }
    var selectedMac by remember { mutableStateOf(placed.firstOrNull()?.mac) }
    val selected = placed.firstOrNull { it.mac == selectedMac } ?: placed.firstOrNull()
    var placing by remember { mutableStateOf<WizBulb?>(null) }
    var cursor by remember { mutableStateOf(Offset((plan.minX + plan.maxX) / 2, (plan.minY + plan.maxY) / 2)) }
    var menuOpen by remember { mutableStateOf(false) }
    var planFocused by remember { mutableStateOf(false) }
    val planFocus = remember { FocusRequester() }
    val firstPlaceButton = remember { FocusRequester() }

    DisposableEffect(Unit) {
        actions.live(true)
        onDispose { actions.live(false) }
    }
    LaunchedEffect(Unit) {
        repeat(10) {
            withFrameNanos { }
            val target = if (placed.isEmpty() && unplaced.isNotEmpty()) firstPlaceButton else planFocus
            if (runCatching { target.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    fun startPlacing(bulb: WizBulb) {
        placing = bulb
        cursor = positions[bulb.mac] ?: Offset((plan.minX + plan.maxX) / 2, (plan.minY + plan.maxY) / 2)
        actions.identify(bulb)
        runCatching { planFocus.requestFocus() }
    }

    // Drawn in the launcher's own window (not a dialog) so the TV composites one screen, not two.
    // Back while placing cancels placement (handled on the plan); otherwise it closes the page.
    BackHandler(enabled = placing == null) { onDismiss() }
    run {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF050608))
                .springIn(),
        ) {
            // The plan, full screen.
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .padding(top = 72.dp)
                    .focusRequester(planFocus)
                    .onFocusChanged { planFocused = it.isFocused }
                    .longPressMenu(if (placing == null && selected != null) ({ menuOpen = true }) else null)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown && event.key != Key.DirectionCenter && event.key != Key.Enter) {
                            return@onPreviewKeyEvent event.key == Key.Back && placing != null
                        }
                        val bulb = placing
                        if (bulb != null) {
                            when (event.key) {
                                Key.Back -> {
                                    if (event.type == KeyEventType.KeyUp) placing = null
                                    true
                                }
                                Key.DirectionCenter, Key.Enter -> {
                                    if (event.type == KeyEventType.KeyUp) {
                                        actions.place(bulb, cursor.x, cursor.y, plan.roomAt(cursor)?.room)
                                        selectedMac = bulb.mac
                                        placing = null
                                    }
                                    true
                                }
                                Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown -> {
                                    // Steps follow the screen, not the plan axes; holding a key speeds up.
                                    val step = 0.08f * (1f + minOf(event.nativeKeyEvent.repeatCount, 12) * 0.25f)
                                    val (sx, sy) = when (event.key) {
                                        Key.DirectionLeft -> -1f to 0f
                                        Key.DirectionRight -> 1f to 0f
                                        Key.DirectionUp -> 0f to -1f
                                        else -> 0f to 1f
                                    }
                                    cursor = Offset(
                                        (cursor.x + step * (sx + sy) / sqrt(2f)).coerceIn(plan.minX, plan.maxX),
                                        (cursor.y + step * (sy - sx) / sqrt(2f)).coerceIn(plan.minY, plan.maxY),
                                    )
                                    true
                                }
                                else -> false
                            }
                        } else {
                            when (event.key) {
                                Key.DirectionCenter, Key.Enter -> {
                                    if (event.type == KeyEventType.KeyUp) selected?.let(actions.toggleBulb)
                                    selected != null
                                }
                                Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown -> {
                                    // Move to the nearest lamp in that direction on screen; none → leave the plan.
                                    val from = selected ?: return@onPreviewKeyEvent false
                                    val next = nearestInDirection(plan, from, placed, positions, event.key)
                                    if (next != null) selectedMac = next.mac
                                    next != null
                                }
                                else -> false
                            }
                        }
                    }
                    .focusable(),
            ) {
                val density = LocalDensity.current
                val canvasSize = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
                // Camera: the whole house, or framed on the room being worked in (the selected lamp's room,
                // or the room under the cursor while placing), gliding between them.
                val focusRoom = when {
                    placing != null -> plan.roomAt(cursor)
                    planFocused && selected != null -> positions[selected.mac]?.let(plan::roomAt)
                    else -> null
                }
                val target = remember(focusRoom) { cameraRect(plan, focusRoom) }
                val spec = tween<Float>(750, easing = FastOutSlowInEasing)
                val camLeft by animateFloatAsState(target.left, spec, label = "camL")
                val camTop by animateFloatAsState(target.top, spec, label = "camT")
                val camRight by animateFloatAsState(target.right, spec, label = "camR")
                val camBottom by animateFloatAsState(target.bottom, spec, label = "camB")
                val margin = with(density) { 36.dp.toPx() }
                val scene = remember(plan) { buildScene(plan) }
                // The static house as one sharp image, rendered in the background; until then, vectors.
                val houseView = remember(plan) { cameraRect(plan, null) }
                var sceneImage by remember { mutableStateOf<SceneImage?>(null) }
                LaunchedEffect(scene, canvasSize) {
                    val houseScale = IsoProjection(houseView, canvasSize, margin).scale
                    sceneImage = withContext(Dispatchers.Default) { renderSceneImage(scene, houseView, houseScale * SCENE_OVERSAMPLE) }
                }
                val iso = IsoProjection(Rect(camLeft, camTop, camRight, camBottom), canvasSize, margin)
                Canvas(Modifier.fillMaxSize().clipToBounds()) {
                    val img = sceneImage
                    if (img != null) {
                        // One scaled image for the whole house: cheap for every frame of a camera glide.
                        val k = iso.scale / img.scale
                        withTransform({
                            translate(iso.ox + img.origin.x * iso.scale, iso.oy + img.origin.y * iso.scale)
                            scale(k, k, pivot = Offset.Zero)
                        }) { drawImage(img.image, filterQuality = FilterQuality.Low) }
                    } else {
                        withTransform({ translate(iso.ox, iso.oy); scale(iso.scale, iso.scale, pivot = Offset.Zero) }) {
                            drawPrims(scene.floors, iso.scale, null)
                            drawPrims(scene.solids, iso.scale, null)
                        }
                    }
                    // Light pools add onto the drawing like real light.
                    placed.forEach { b -> positions[b.mac]?.let { drawGlow(iso, it, b) } }
                    // Rooms outside the camera's focus fade back.
                    focusRoom?.let { room ->
                        val dim = Path().apply {
                            fillType = PathFillType.EvenOdd
                            addRect(Rect(Offset.Zero, size))
                            roomHull(room).forEachIndexed { i, p ->
                                val q = Offset(p.x * iso.scale + iso.ox, p.y * iso.scale + iso.oy)
                                if (i == 0) moveTo(q.x, q.y) else lineTo(q.x, q.y)
                            }
                            close()
                        }
                        drawPath(dim, Color(0xFF050608).copy(alpha = 0.55f))
                    }
                    placed.forEach { b ->
                        positions[b.mac]?.let { drawLamp(iso, it, b, selected = placing == null && b.mac == selected?.mac && planFocused) }
                    }
                    placing?.let { drawCursor(iso, cursor) }
                }
                // Labels and the options menu, anchored at the lamp on screen.
                val labelFor = placing?.let { it to cursor } ?: selected?.let { b -> positions[b.mac]?.let { b to it } }
                labelFor?.let { (bulb, at) ->
                    val p = iso.project(at.x, at.y, 0f)
                    Box(Modifier.offset { IntOffset(p.x.toInt(), p.y.toInt()) }.size(1.dp)) {
                        if (menuOpen && placing == null) {
                            GlassDropdown(
                                title = bulb.displayName(names),
                                items = buildList {
                                    add(MenuItem("Move on plan", painterResource(R.drawable.ic_open)) { startPlacing(bulb) })
                                    add(MenuItem("Blink to identify", painterResource(R.drawable.ic_bulb)) { actions.identify(bulb) })
                                    add(null)
                                    listOf(100, 70, 40, 10).forEach { level ->
                                        add(MenuItem("Brightness", painterResource(R.drawable.ic_brightness), "$level%") { actions.setBulbBrightness(bulb, level) })
                                    }
                                    add(null)
                                    add(MenuItem("Rename", rememberVectorPainter(Icons.Default.Edit)) { actions.rename(bulb) })
                                },
                                onDismiss = { menuOpen = false },
                            )
                        }
                    }
                }
                // Only while placing: a short hint, since the D-pad then moves the marker.
                placing?.let { bulb ->
                    StatusLine(
                        text = "Placing ${bulb.displayName(names)} · ${plan.roomAt(cursor)?.room?.label ?: "outside"}  —  OK to drop, Back to cancel",
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }

            // Controls along the top: totals, all/room switches, and a gear menu.
            TopControls(
                bulbs = bulbs,
                rooms = rooms,
                names = names,
                unplaced = unplaced,
                firstPlaceButton = firstPlaceButton,
                actions = actions,
                onPlace = ::startPlacing,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

@Composable
private fun StatusLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 13.sp,
        color = Color.White.copy(alpha = 0.75f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .padding(14.dp)
            .background(Color.Black.copy(alpha = 0.45f), Shapes.Field)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun TopControls(
    bulbs: List<WizBulb>,
    rooms: Map<String, String>,
    names: Map<String, String>,
    unplaced: List<WizBulb>,
    firstPlaceButton: FocusRequester,
    actions: LightsActions,
    onPlace: (WizBulb) -> Unit,
    modifier: Modifier = Modifier,
) {
    val anyOn = bulbs.any { it.isOn }
    var menu by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 20.dp),
    ) {
        Column(Modifier.padding(end = 12.dp)) {
            Text("Lights", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (bulbs.isEmpty()) "No WiZ lamps found" else "${bulbs.count { it.isOn }} of ${bulbs.size} on",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.6f),
            )
        }
        if (bulbs.isNotEmpty()) {
            GlassButton(if (anyOn) "Turn all off" else "Turn all on", onClick = { actions.setRoom(bulbs.map { it.mac }.toSet(), !anyOn, null) }, compact = true)
        }
        Room.entries.forEach { room ->
            val inRoom = bulbs.filter { rooms[it.mac] == room.name }
            if (inRoom.isEmpty()) return@forEach
            val roomOn = inRoom.any { it.isOn }
            GlassButton(
                "${room.label} · ${if (roomOn) "Turn off" else "Turn on"}",
                onClick = { actions.setRoom(inRoom.map { it.mac }.toSet(), !roomOn, null) },
                compact = true,
            )
        }
        Spacer(Modifier.weight(1f))
        Box {
            GlassIconButton(rememberVectorPainter(Icons.Default.Settings), "Light settings", onClick = { menu = true }, modifier = Modifier.focusRequester(firstPlaceButton))
            if (menu) {
                GlassDropdown(
                    alignEnd = true,
                    width = 300.dp,
                    items = buildList {
                        add(MenuItem("Search for lamps", rememberVectorPainter(Icons.Default.Refresh)) { actions.rescan() })
                        if (unplaced.isNotEmpty()) add(null)
                        unplaced.forEach { bulb ->
                            add(MenuItem("Place ${bulb.displayName(names)}", painterResource(R.drawable.ic_bulb)) { onPlace(bulb) })
                        }
                    },
                    onDismiss = { menu = false },
                )
            }
        }
    }
}


/**
 * The part of the isometric picture to show: the whole house, or one room with some breathing room
 * around it (never so tight that a tiny room like the entry fills the screen).
 */
private fun cameraRect(plan: HomePlan, room: PlanRoom?): Rect {
    fun bounds(pts: List<Offset>): Rect {
        val iso = pts.flatMap { listOf(isoPoint(it.x, it.y, 0f), isoPoint(it.x, it.y, WALL_FULL)) }
        return Rect(iso.minOf { it.x }, iso.minOf { it.y }, iso.maxOf { it.x }, iso.maxOf { it.y })
    }
    // The house view also fits the arched windows, which rise above the cut-away walls.
    val house = bounds(plan.rooms.flatMap { it.poly }).let { r ->
        val tops = plan.windows.flatMap { (a, b) -> listOf(isoPoint(a.x, a.y, WINDOW_HEIGHT), isoPoint(b.x, b.y, WINDOW_HEIGHT)) }
        if (tops.isEmpty()) r else Rect(minOf(r.left, tops.minOf { it.x }), minOf(r.top, tops.minOf { it.y }), maxOf(r.right, tops.maxOf { it.x }), r.bottom)
    }
    if (room == null) return house
    val r = bounds(room.poly)
    val w = maxOf(r.width * 1.5f, house.width * 0.5f)
    val h = maxOf(r.height * 1.5f, house.height * 0.5f)
    return Rect(r.center.x - w / 2, r.center.y - h / 2, r.center.x + w / 2, r.center.y + h / 2)
}

/** Maps plan meters to screen pixels so that [view] (in isometric units) fills the canvas. */
private class IsoProjection(view: Rect, size: Size, margin: Float) {
    private val reserve = 48f // room for the status line
    val scale = minOf((size.width - 2 * margin) / view.width, (size.height - 2 * margin - reserve) / view.height)
    val ox = size.width / 2 - view.center.x * scale
    val oy = (size.height - reserve) / 2 - view.center.y * scale

    fun project(x: Float, y: Float, h: Float): Offset = isoPoint(x, y, h).let { Offset(it.x * scale + ox, it.y * scale + oy) }
}

/** Warm light pooling on the floor around a lamp that's on, stronger when brighter. */
private fun DrawScope.drawGlow(iso: IsoProjection, at: Offset, bulb: WizBulb) {
    if (!bulb.isOn || !bulb.reachable) return
    val center = iso.project(at.x, at.y, 0f)
    val radius = iso.scale * 1.7f
    val strength = 0.25f + 0.45f * (bulb.dimming / 100f)
    // A circle on the floor looks like a flattened ellipse in isometric view.
    withTransform({ scale(1f, 0.58f, pivot = center) }) {
        drawCircle(
            blendMode = BlendMode.Screen,
            brush = Brush.radialGradient(
                0f to LightOn.copy(alpha = strength),
                0.55f to LightOn.copy(alpha = strength * 0.3f),
                1f to Color.Transparent,
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
        )
    }
}

private fun DrawScope.drawLamp(iso: IsoProjection, at: Offset, bulb: WizBulb, selected: Boolean) {
    val floor = iso.project(at.x, at.y, 0f)
    val head = iso.project(at.x, at.y, 0.55f)
    drawLine(Color.White.copy(alpha = 0.35f), floor, head, strokeWidth = 1.5f, cap = StrokeCap.Round)
    if (bulb.isOn) drawCircle(LightOn.copy(alpha = 0.35f), radius = 22f, center = head)
    drawCircle(if (!bulb.reachable) Color(0xFF1C1E24) else if (bulb.isOn) LightOn else Color(0xFF3A3D46), radius = 11f, center = head)
    drawCircle(Color.White.copy(alpha = if (bulb.isOn) 0.9f else 0.45f), radius = 11f, center = head, style = Stroke(2f))
    if (selected) drawCircle(Color.White, radius = 19f, center = head, style = Stroke(3f))
}

private fun DrawScope.drawCursor(iso: IsoProjection, at: Offset) {
    val floor = iso.project(at.x, at.y, 0f)
    val head = iso.project(at.x, at.y, 0.55f)
    withTransform({ scale(1f, 0.58f, pivot = floor) }) {
        drawCircle(Color.White.copy(alpha = 0.7f), radius = 26f, center = floor, style = Stroke(2.5f))
    }
    drawLine(Color.White, floor, head, strokeWidth = 2f, cap = StrokeCap.Round)
    drawCircle(LightOn, radius = 12f, center = head)
    drawCircle(Color.White, radius = 18f, center = head, style = Stroke(3f))
}

/**
 * Where a D-pad press goes, judged on screen (isometric) positions: lamps in the same room come first;
 * if there are none that way, it moves to the nearest room in that direction that has lamps, landing on
 * the lamp closest to the side you came from.
 */
private fun nearestInDirection(plan: HomePlan, from: WizBulb, lamps: List<WizBulb>, positions: Map<String, Offset>, key: Key): WizBulb? {
    val origin = positions[from.mac] ?: return null
    val fromRoom = plan.roomAt(origin)
    fun screen(p: Offset) = isoPoint(p.x, p.y, 0f)
    val o = screen(origin)
    val (dx, dy) = when (key) {
        Key.DirectionLeft -> -1f to 0f
        Key.DirectionRight -> 1f to 0f
        Key.DirectionUp -> 0f to -1f
        else -> 0f to 1f
    }
    val candidates = lamps.filter { it.mac != from.mac }.mapNotNull { lamp ->
        val pos = positions[lamp.mac] ?: return@mapNotNull null
        val p = screen(pos) - o
        val along = p.x * dx + p.y * dy
        val across = abs(p.x * dy - p.y * dx)
        if (along <= 0.05f || across > along * 2.2f) return@mapNotNull null
        val sameRoom = plan.roomAt(pos) === fromRoom
        // Distance with sideways drift weighted double; another room costs extra so the current one wins.
        lamp to (hypot(along, across * 2f) + if (sameRoom) 0f else 2.5f)
    }
    return candidates.minByOrNull { it.second }?.first
}
