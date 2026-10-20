package dev.yusuf.falconlauncher.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import dev.yusuf.falconlauncher.data.HomePlan
import dev.yusuf.falconlauncher.data.PlanBox
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Furniture drawn at most this tall, matching the cut-away walls. */
private const val CUT = 0.92f

/**
 * One furniture footprint in its own frame: u runs along its back edge (0..U meters), v away from the
 * back toward its front (0..V meters), h is height. The back is the edge against the nearest wall, since
 * the scan doesn't say which way things face.
 */
class FurnitureFrame(val kind: String, private val c0: Offset, private val ax: Offset, private val az: Offset, val U: Float, val V: Float, val base: Float, val top: Float) {
    fun plan(u: Float, v: Float) = Offset(c0.x + ax.x * u + az.x * v, c0.y + ax.y * u + az.y * v)
    val center get() = plan(U / 2, V / 2)
}

/** Prepares each furniture footprint once: its kind and which edge is its back. */
fun furnitureFrames(plan: HomePlan): List<FurnitureFrame> = plan.furniture.mapNotNull { box ->
    if (box.poly.size != 4 || box.base >= CUT || box.kind.startsWith("stove")) return@mapNotNull null
    val c = box.poly
    val kind = box.kind.lowercase()
    // Which edges may be the back: sofas lean on a long side, beds on a short side, the rest on any.
    val lens = List(4) { i -> (c[(i + 1) % 4] - c[i]).getDistance() }
    val candidates = when {
        "sofa" in kind -> (0 until 4).filter { lens[it] >= lens.max() - 0.01f }
        "bed" in kind -> (0 until 4).filter { lens[it] <= lens.min() + 0.01f }
        else -> (0 until 4).toList()
    }
    val back = candidates.minBy { i -> wallDistance(plan, (c[i] + c[(i + 1) % 4]) / 2f) }
    val p0 = c[back]; val p1 = c[(back + 1) % 4]; val p3 = c[(back + 3) % 4]
    val U = (p1 - p0).getDistance(); val V = (p3 - p0).getDistance()
    FurnitureFrame(kind, p0, (p1 - p0) / U, (p3 - p0) / V, U, V, box.base, box.top)
}

private fun wallDistance(plan: HomePlan, p: Offset): Float = plan.walls.minOf { w ->
    val xs = w.poly.map { it.x }; val ys = w.poly.map { it.y }
    val dx = maxOf(xs.min() - p.x, 0f, p.x - xs.max())
    val dy = maxOf(ys.min() - p.y, 0f, p.y - ys.max())
    hypot(dx, dy)
}

/** One drawable part of a model: a box in the item's frame, or a flat loop (circle, inset) on a surface. */
private sealed class Part(val depth: Float) {
    class Block(val u0: Float, val u1: Float, val v0: Float, val v1: Float, val h0: Float, val h1: Float, val fill: Float, val line: Float, val color: Color, depth: Float) : Part(depth)
    class Loop(val points: List<Triple<Float, Float, Float>>, val fill: Float, val line: Float, val color: Color, depth: Float) : Part(depth)
}

private class ModelBuilder(val f: FurnitureFrame) {
    val parts = mutableListOf<Part>()
    val U = f.U
    val V = f.V
    val H = minOf(f.top, CUT)

    fun block(u0: Float, u1: Float, v0: Float, v1: Float, h0: Float, h1: Float, fill: Float = 0.06f, line: Float = 0.26f, color: Color = Color.White) {
        val c = f.plan((u0 + u1) / 2, (v0 + v1) / 2)
        parts += Part.Block(u0, u1, v0, v1, minOf(h0, CUT), minOf(h1, CUT), fill, line, color, c.x + c.y + h1 * 0.01f)
    }

    /** A circle on the top surface at height h. */
    fun topCircle(cu: Float, cv: Float, r: Float, h: Float, fill: Float = 0f, line: Float = 0.35f, color: Color = Color.White, rv: Float = r) {
        val pts = List(24) { i -> val a = 2 * PI.toFloat() * i / 24; Triple(cu + r * cos(a), cv + rv * sin(a), minOf(h, CUT)) }
        val c = f.plan(cu, cv)
        parts += Part.Loop(pts, fill, line, color, c.x + c.y + 0.001f)
    }

    /** A circle on the front face (v = V). */
    fun frontCircle(cu: Float, ch: Float, r: Float, line: Float = 0.4f) {
        val pts = List(24) { i -> val a = 2 * PI.toFloat() * i / 24; Triple(cu + r * cos(a), V, minOf(ch + r * sin(a), CUT)) }
        val c = f.plan(cu, V)
        parts += Part.Loop(pts, 0.04f, line, Color.White, c.x + c.y + 0.002f)
    }

    /** A rectangle drawn on the front face, e.g. a door seam or an oven window. */
    fun frontRect(u0: Float, u1: Float, h0: Float, h1: Float, line: Float = 0.3f, fill: Float = 0f) {
        val pts = listOf(Triple(u0, V, h0), Triple(u1, V, h0), Triple(u1, V, minOf(h1, CUT)), Triple(u0, V, minOf(h1, CUT)))
        val c = f.plan((u0 + u1) / 2, V)
        parts += Part.Loop(pts, fill, line, Color.White, c.x + c.y + 0.002f)
    }

    /** A rectangle on a top surface, e.g. a sink basin or cushion seam. */
    fun topRect(u0: Float, u1: Float, v0: Float, v1: Float, h: Float, line: Float = 0.3f, fill: Float = 0f, color: Color = Color.White) {
        val hh = minOf(h, CUT)
        val pts = listOf(Triple(u0, v0, hh), Triple(u1, v0, hh), Triple(u1, v1, hh), Triple(u0, v1, hh))
        val c = f.plan((u0 + u1) / 2, (v0 + v1) / 2)
        parts += Part.Loop(pts, fill, line, color, c.x + c.y + 0.001f)
    }
}

private val Warm = Color(0xFFE9D9C4)

/** Builds the simplified model for one piece of furniture. */
private fun ModelBuilder.build() {
    val k = f.kind
    when {
        "sofa" in k -> {
            // Seat depth, with a chaise reaching the full depth at one end.
            val back = 0.22f; val arm = 0.18f; val seat = minOf(0.95f, V); val chaise = minOf(0.9f, U * 0.35f)
            block(0f, U, 0f, back, 0f, 0.78f, fill = 0.07f)                         // backrest
            block(0f, arm, back, seat, 0f, 0.6f)                                     // left arm
            block(arm, U - chaise, back, seat, 0f, 0.36f)                            // seat base
            block(U - chaise, U, back, V, 0f, 0.36f)                                 // chaise base
            val cushions = 2
            val cw = (U - chaise - arm) / cushions
            repeat(cushions) { i -> block(arm + cw * i + 0.02f, arm + cw * (i + 1) - 0.02f, back + 0.02f, seat - 0.03f, 0.36f, 0.46f, fill = 0.1f, line = 0.32f, color = Warm) }
            block(U - chaise + 0.02f, U - 0.03f, back + 0.02f, V - 0.03f, 0.36f, 0.46f, fill = 0.1f, line = 0.32f, color = Warm)
        }
        "bed" in k -> {
            block(0f, U, 0f, 0.08f, 0f, 0.9f, fill = 0.08f)                          // headboard
            block(0f, U, 0.08f, V, 0f, 0.28f)                                        // frame
            block(0.04f, U - 0.04f, 0.1f, V - 0.04f, 0.28f, 0.46f, fill = 0.09f, color = Warm) // mattress
            val pw = (U - 0.24f) / 2
            block(0.08f, 0.08f + pw, 0.14f, 0.5f, 0.46f, 0.56f, fill = 0.14f, line = 0.35f, color = Warm)
            block(U - 0.08f - pw, U - 0.08f, 0.14f, 0.5f, 0.46f, 0.56f, fill = 0.14f, line = 0.35f, color = Warm)
            block(0.04f, U - 0.04f, V * 0.55f, V - 0.04f, 0.46f, 0.5f, fill = 0.12f, line = 0.25f, color = Warm) // blanket fold
        }
        k.startsWith("masa") || "table" in k -> {
            val leg = 0.05f
            listOf(0f to 0f, U - leg to 0f, 0f to V - leg, U - leg to V - leg).forEach { (u, v) -> block(u, u + leg, v, v + leg, 0f, 0.72f, fill = 0.08f, line = 0.3f) }
            block(0f, U, 0f, V, 0.72f, 0.76f, fill = 0.1f, line = 0.35f, color = Warm)
        }
        "chair" in k -> {
            block(U / 2 - 0.03f, U / 2 + 0.03f, V / 2 - 0.03f, V / 2 + 0.03f, 0f, 0.42f)          // post
            block(0.06f, U - 0.06f, 0.06f, V - 0.06f, 0.42f, 0.48f, fill = 0.1f, color = Warm)   // seat
            block(0.08f, U - 0.08f, 0.02f, 0.08f, 0.48f, 0.9f, fill = 0.08f)                     // back
        }
        k.contains("makine") || "washer" in k -> {
            block(0f, U, 0f, V, 0f, H, fill = 0.08f)
            frontCircle(U / 2, H * 0.45f, minOf(U, H) * 0.28f)
            frontRect(0.06f, U - 0.06f, H - 0.12f, H - 0.04f, line = 0.25f)
        }
        "fridge" in k -> {
            block(0f, U, 0f, V, 0f, H, fill = 0.08f)
            frontRect(0.03f, U - 0.03f, 0.03f, H * 0.62f, line = 0.28f)
            frontRect(0.03f, U - 0.03f, H * 0.64f, H - 0.03f, line = 0.28f)
            frontRect(U - 0.1f, U - 0.07f, H * 0.4f, H * 0.58f, line = 0.5f, fill = 0.2f)
        }
        k == "tv" -> {
            block(U * 0.45f, U * 0.55f, 0f, V, f.base, f.base + 0.08f)                           // stand
            block(0f, U, 0f, minOf(V, 0.05f), f.base + 0.08f, minOf(f.top, CUT), fill = 0.5f, line = 0.45f, color = Color(0xFF0B0E16))
        }
        k.contains("bench") || "low1" in k || "alçak" in k -> cabinet(doors = maxOf(1, (U / 0.5f).toInt()))
        k.contains("dolab") || k.contains("dolap") || k.startsWith("storage") || "wardrobe" in k || "cabinet" in k -> cabinet(doors = maxOf(1, (U / 0.5f).toInt()))
        k.startsWith("oven") -> {
            block(0f, U, 0f, V, 0f, H, fill = 0.08f)
            frontRect(0.08f, U - 0.08f, H * 0.18f, H * 0.62f, line = 0.35f, fill = 0.25f)
            listOf(0.27f to 0.3f, 0.73f to 0.3f, 0.27f to 0.7f, 0.73f to 0.7f).forEach { (a, b) -> topCircle(U * a, V * b, minOf(U, V) * 0.13f, H, line = 0.45f) }
        }
        k.startsWith("sink") -> {
            // The basin sits in the counter below it: draw only the bowl outline on the counter top.
            topRect(0.05f, U - 0.05f, 0.05f, V - 0.05f, H, line = 0.45f, fill = 0.1f, color = Color(0xFFBFD8FF))
            topCircle(U / 2, 0.1f, 0.03f, H + 0.01f, line = 0.6f)
        }
        k.startsWith("toilet") -> {
            block(0.08f, U - 0.08f, 0f, 0.18f, 0f, 0.75f, fill = 0.08f)                          // tank
            block(U * 0.25f, U * 0.75f, 0.16f, V * 0.75f, 0f, 0.38f)                              // base
            topCircle(U / 2, V * 0.58f, U * 0.34f, 0.4f, fill = 0.08f, line = 0.4f, rv = V * 0.36f) // seat
        }
        else -> block(0f, U, 0f, V, 0f, H)
    }
}

private fun ModelBuilder.cabinet(doors: Int) {
    block(0f, U, 0f, V, 0f, H, fill = 0.07f)
    val dw = U / doors
    repeat(doors) { i ->
        frontRect(dw * i + 0.02f, dw * (i + 1) - 0.02f, 0.04f, H - 0.03f, line = 0.22f)
        val hu = if (i % 2 == 0) dw * (i + 1) - 0.06f else dw * i + 0.04f
        frontRect(hu, hu + 0.02f, H * 0.45f, H * 0.6f, line = 0.45f, fill = 0.3f)
    }
}

/** All furniture as prebuilt primitives in isometric units, back to front. Built once per plan. */
fun furniturePrims(frames: List<FurnitureFrame>): List<Prim> {
    val out = mutableListOf<Prim>()
    fun p(o: Offset, h: Float) = isoPoint(o.x, o.y, h)
    frames.sortedBy { it.center.x + it.center.y }.forEach { f ->
        val b = ModelBuilder(f).apply { build() }
        b.parts.sortedBy { it.depth }.forEach { part ->
            when (part) {
                is Part.Block -> {
                    if (part.h1 <= part.h0) return@forEach
                    val corners = listOf(part.u0 to part.v0, part.u1 to part.v0, part.u1 to part.v1, part.u0 to part.v1).map { (u, v) -> f.plan(u, v) }
                    val center = f.plan((part.u0 + part.u1) / 2, (part.v0 + part.v1) / 2)
                    for (i in 0 until 4) {
                        val a = corners[i]; val c = corners[(i + 1) % 4]
                        val mid = Offset((a.x + c.x) / 2 - center.x, (a.y + c.y) / 2 - center.y)
                        if (mid.x + mid.y <= 0f) continue // only faces turned toward the viewer
                        out += Prim(
                            polyline(listOf(p(a, part.h0), p(c, part.h0), p(c, part.h1), p(a, part.h1)), close = true),
                            part.color.copy(alpha = part.fill * 0.7f), part.color.copy(alpha = part.line * 0.8f), 1.2f,
                        )
                    }
                    out += Prim(polyline(corners.map { p(it, part.h1) }, close = true), part.color.copy(alpha = part.fill * 1.4f), part.color.copy(alpha = part.line), 1.2f)
                }
                is Part.Loop -> out += Prim(
                    polyline(part.points.map { (u, v, h) -> p(f.plan(u, v), h) }, close = true),
                    part.color.copy(alpha = part.fill), part.color.copy(alpha = part.line), 1.2f,
                )
            }
        }
    }
    return out
}

private operator fun Offset.div(d: Float) = Offset(x / d, y / d)
