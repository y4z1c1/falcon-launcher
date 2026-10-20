package dev.yusuf.falconlauncher.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import dev.yusuf.falconlauncher.data.HomePlan
import dev.yusuf.falconlauncher.data.PlanRoom
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Walls facing the viewer are cut away at this height so the rooms stay readable from above. */
const val WALL_CUT = 1.0f

/** Back walls stand at the real ceiling height (from the scan), like a dollhouse cutaway. */
const val WALL_FULL = 2.6f

/** A wall edge faces the viewer (the +x +y direction) when it lies on that side of its room. */
private fun isNearWall(room: PlanRoom, a: Offset, b: Offset): Boolean {
    val mx = (a.x + b.x) / 2 - room.center.x
    val my = (a.y + b.y) / 2 - room.center.y
    return mx + my > 0f
}

fun wallHeight(room: PlanRoom, a: Offset, b: Offset) = if (isNearWall(room, a, b)) WALL_CUT else WALL_FULL
private val ISO_COS = cos(Math.PI / 6).toFloat()

/** Isometric (30°) coordinates of a plan point at height [h], in meters, before the camera's scale. */
fun isoPoint(x: Float, y: Float, h: Float) = Offset((x - y) * ISO_COS, (x + y) * 0.5f - h)

/** Arched windows as PlanStudio models them: 2.2 m tall, the arch's radius half the width. */
const val WINDOW_HEIGHT = 2.2f

/**
 * One prebuilt shape of the static scene, already in isometric units. Built once; each frame only applies
 * the camera's scale and offset, so camera glides and lamp changes don't rebuild any geometry.
 * [room] lets the camera fade rooms outside its focus; [focusBoost] brightens the focused room.
 */
class Prim(
    val path: Path,
    val fill: Color,
    val stroke: Color,
    val strokePx: Float,
    val room: PlanRoom? = null,
    val focusBoost: Float = 1f,
    val dimmed: Float = 0.4f,
)

class Scene(val floors: List<Prim>, val solids: List<Prim>)

fun polyline(points: List<Offset>, close: Boolean) = Path().apply {
    points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
    if (close) close()
}

/** Builds the whole static scene once: floors, then furniture, walls and windows back to front. */
fun buildScene(plan: HomePlan): Scene {
    val floors = plan.rooms.map { room ->
        Prim(polyline(room.poly.map { isoPoint(it.x, it.y, 0f) }, close = true), Color.White.copy(alpha = 0.045f), Color.Transparent, 0f, room, focusBoost = 1.8f, dimmed = 0.45f)
    }
    val solids = mutableListOf<Prim>()
    solids += furniturePrims(furnitureFrames(plan))

    // Walls: back walls at full height, walls facing the viewer cut low (a dollhouse cutaway).
    // Back walls go first so furniture and the low front walls draw over them.
    val back = mutableListOf<Prim>()
    val front = mutableListOf<Prim>()
    plan.rooms.sortedBy { it.center.x + it.center.y }.forEach { room ->
        val n = room.poly.size
        for (i in 0 until n) {
            val a = room.poly[i]; val b = room.poly[(i + 1) % n]
            val h = wallHeight(room, a, b)
            val target = if (h == WALL_FULL) back else front
            target += Prim(
                polyline(listOf(isoPoint(a.x, a.y, 0f), isoPoint(b.x, b.y, 0f), isoPoint(b.x, b.y, h), isoPoint(a.x, a.y, h)), close = true),
                Color.White.copy(alpha = if (h == WALL_FULL) 0.03f else 0.035f), Color.Transparent, 0f, room,
            )
            target += Prim(polyline(listOf(isoPoint(a.x, a.y, h), isoPoint(b.x, b.y, h)), close = false), Color.Transparent, Color.White.copy(alpha = if (h == WALL_FULL) 0.55f else 0.75f), 1.4f, room)
            // Corner post up to the taller of the two walls meeting there.
            val prev = room.poly[(i - 1 + n) % n]
            val post = maxOf(h, wallHeight(room, prev, a))
            target += Prim(polyline(listOf(isoPoint(a.x, a.y, 0f), isoPoint(a.x, a.y, post)), close = false), Color.Transparent, Color.White.copy(alpha = 0.32f), 1.2f, room)
        }
        front += Prim(polyline(room.poly.map { isoPoint(it.x, it.y, 0f) }, close = true), Color.Transparent, Color.White.copy(alpha = 0.3f), 1.4f, room)
    }
    val furniture = solids.toList()
    solids.clear()
    solids += back
    solids += furniture
    solids += front
    plan.doors.forEach { (a, b) ->
        solids += Prim(polyline(listOf(isoPoint(a.x, a.y, 0.02f), isoPoint(b.x, b.y, 0.02f)), close = false), Color.Transparent, Color.White.copy(alpha = 0.18f), 4f)
    }
    // Windows sit in the back walls: after them, before the furniture in front.
    var at = back.size
    plan.windows.forEach { (a, b) -> archWindow(a, b).let { w -> solids.addAll(at, w); at += w.size } }
    return Scene(floors, solids)
}

private val WindowGlass = Color(0xFF9CC9FF)

/** An arched window standing on the floor between [a] and [b]: frame, glass, and its muntin bars. */
private fun archWindow(a: Offset, b: Offset): List<Prim> {
    val w = (b - a).getDistance()
    val dir = (b - a) / w
    val r = w / 2
    val spring = WINDOW_HEIGHT - r
    fun at(along: Float, h: Float): Offset { val p = a + dir * along; return isoPoint(p.x, p.y, h) }
    val outline = buildList {
        add(at(0f, 0f))
        add(at(0f, spring))
        for (i in 0..24) {
            val t = PI.toFloat() * (1 - i / 24f)
            add(at(r + r * cos(t), spring + r * sin(t)))
        }
        add(at(w, 0f))
    }
    val bars = WindowGlass.copy(alpha = 0.55f)
    val rail = minOf(0.95f, spring * 0.62f)
    return listOf(
        Prim(polyline(outline, close = true), WindowGlass.copy(alpha = 0.07f), WindowGlass.copy(alpha = 0.85f), 2.2f),
        Prim(polyline(listOf(at(0f, spring), at(w, spring)), close = false), Color.Transparent, bars, 1.2f),
        Prim(polyline(listOf(at(0f, rail), at(w, rail)), close = false), Color.Transparent, bars, 1.2f),
        Prim(polyline(listOf(at(r, 0f), at(r, WINDOW_HEIGHT)), close = false), Color.Transparent, bars, 1.2f),
        Prim(polyline(listOf(at(w / 4, 0f), at(w / 4, spring)), close = false), Color.Transparent, bars, 1f),
        Prim(polyline(listOf(at(w * 3 / 4, 0f), at(w * 3 / 4, spring)), close = false), Color.Transparent, bars, 1f),
    )
}

/**
 * Draws prebuilt prims under the camera transform (already applied by the caller). Stroke widths are
 * divided by the scale so lines stay the same thickness on screen at any zoom.
 */
fun DrawScope.drawPrims(prims: List<Prim>, scale: Float, focus: PlanRoom?, lineBoost: Float = 1f) {
    val strokes = HashMap<Float, Stroke>()
    for (p in prims) {
        val k = when {
            focus == null || p.room == null -> 1f
            p.room === focus -> p.focusBoost
            else -> p.dimmed
        }
        if (p.fill.alpha > 0f) drawPath(p.path, p.fill.copy(alpha = (p.fill.alpha * k).coerceAtMost(1f)))
        if (p.stroke.alpha > 0f && p.strokePx > 0f) {
            val stroke = strokes.getOrPut(p.strokePx) { Stroke(p.strokePx * lineBoost / scale, cap = StrokeCap.Round, join = StrokeJoin.Round) }
            drawPath(p.path, p.stroke.copy(alpha = (p.stroke.alpha * k).coerceAtMost(1f)), style = stroke)
        }
    }
}

private operator fun Offset.div(d: Float) = Offset(x / d, y / d)

/** The static house rendered once into an image; [scale] is its pixels per isometric unit. */
class SceneImage(val image: androidx.compose.ui.graphics.ImageBitmap, val scale: Float, val origin: Offset)

/** How much sharper than the whole-house view the image is, so room close-ups stay crisp. */
const val SCENE_OVERSAMPLE = 1.3f

/**
 * Renders floors, furniture, walls and windows into a bitmap covering [view] (isometric units) at
 * [pxPerUnit]. Runs on a background thread; afterwards each frame is a single scaled image draw.
 */
fun renderSceneImage(scene: Scene, view: androidx.compose.ui.geometry.Rect, pxPerUnit: Float): SceneImage {
    val w = (view.width * pxPerUnit).toInt().coerceIn(1, 4096)
    val h = (view.height * pxPerUnit).toInt().coerceIn(1, 4096)
    val image = androidx.compose.ui.graphics.ImageBitmap(w, h)
    val canvas = androidx.compose.ui.graphics.Canvas(image)
    androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
        androidx.compose.ui.unit.Density(1f), androidx.compose.ui.unit.LayoutDirection.Ltr, canvas, androidx.compose.ui.geometry.Size(w.toFloat(), h.toFloat()),
    ) {
        translate(-view.left * pxPerUnit, -view.top * pxPerUnit) {
            scale(pxPerUnit, pxPerUnit, pivot = Offset.Zero) {
                drawPrims(scene.floors, pxPerUnit, null, SCENE_OVERSAMPLE)
                drawPrims(scene.solids, pxPerUnit, null, SCENE_OVERSAMPLE)
            }
        }
    }
    return SceneImage(image, pxPerUnit, Offset(view.left, view.top))
}

/** Convex outline of a room on screen (floor and wall tops), for dimming everything around it. */
fun roomHull(room: PlanRoom): List<Offset> {
    val pts = room.poly.flatMap { listOf(isoPoint(it.x, it.y, 0f), isoPoint(it.x, it.y, WALL_FULL)) }.sortedWith(compareBy({ it.x }, { it.y }))
    fun cross(o: Offset, a: Offset, b: Offset) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
    val lower = mutableListOf<Offset>()
    for (p in pts) { while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), p) <= 0) lower.removeAt(lower.lastIndex); lower += p }
    val upper = mutableListOf<Offset>()
    for (p in pts.reversed()) { while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), p) <= 0) upper.removeAt(upper.lastIndex); upper += p }
    return lower.dropLast(1) + upper.dropLast(1)
}
