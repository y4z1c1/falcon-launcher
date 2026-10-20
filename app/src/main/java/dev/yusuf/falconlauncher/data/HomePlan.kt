package dev.yusuf.falconlauncher.data

import android.content.Context
import androidx.compose.ui.geometry.Offset
import dev.yusuf.falconlauncher.R
import dev.yusuf.falconlauncher.widgets.Room
import org.json.JSONArray

/** A footprint with a height range, in meters (plan x = scan X, plan y = scan Z). */
class PlanBox(val poly: List<Offset>, val base: Float, val top: Float, val kind: String = "") {
    val center: Offset = Offset(poly.map { it.x }.average().toFloat(), poly.map { it.y }.average().toFloat())
}

class PlanRoom(val name: String, val poly: List<Offset>) {
    val center: Offset = Offset(poly.map { it.x }.average().toFloat(), poly.map { it.y }.average().toFloat())

    /** The launcher room this floor belongs to, by its name in PlanStudio. */
    val room: Room? = when (name.lowercase()) {
        "salon", "living room" -> Room.LIVING_ROOM
        "yatak odası", "bedroom" -> Room.BEDROOM
        "mutfak", "kitchen" -> Room.KITCHEN
        "banyo", "bathroom" -> Room.BATHROOM
        "antre", "entry", "hall" -> Room.ENTRY
        else -> null
    }

    fun contains(p: Offset): Boolean {
        var inside = false
        var j = poly.lastIndex
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }
}

/**
 * The home's floor plan, extracted from the PlanStudio scan and furnished layout
 * (res/raw/home_plan.json, built by tools/build_home_plan.py).
 */
class HomePlan(
    val rooms: List<PlanRoom>,
    val walls: List<PlanBox>,
    val furniture: List<PlanBox>,
    val doors: List<Pair<Offset, Offset>>,
    val windows: List<Pair<Offset, Offset>>,
) {
    val minX = rooms.flatMap { it.poly }.minOf { it.x }
    val maxX = rooms.flatMap { it.poly }.maxOf { it.x }
    val minY = rooms.flatMap { it.poly }.minOf { it.y }
    val maxY = rooms.flatMap { it.poly }.maxOf { it.y }

    fun roomAt(p: Offset): PlanRoom? = rooms.firstOrNull { it.contains(p) }

    companion object {
        fun load(context: Context): HomePlan {
            val json = org.json.JSONObject(context.resources.openRawResource(R.raw.home_plan).bufferedReader().use { it.readText() })
            fun pts(a: JSONArray) = List(a.length()) { a.getJSONArray(it).let { p -> Offset(p.getDouble(0).toFloat(), p.getDouble(1).toFloat()) } }
            fun seg(a: JSONArray) = pts(a).let { it[0] to it[1] }
            fun boxes(key: String) = json.getJSONArray(key).let { a ->
                List(a.length()) { i ->
                    val o = a.getJSONObject(i)
                    PlanBox(
                        pts(o.getJSONArray("poly")),
                        o.optDouble("base", 0.0).toFloat(),
                        o.getDouble("h").toFloat(),
                        o.optString("label", o.optString("kind")),
                    )
                }
            }
            val rooms = json.getJSONArray("rooms").let { a ->
                List(a.length()) { i -> a.getJSONObject(i).let { PlanRoom(it.getString("name"), pts(it.getJSONArray("poly"))) } }
            }
            val walls = json.getJSONArray("walls").let { a ->
                List(a.length()) { i ->
                    val o = a.getJSONObject(i); val r = o.getJSONArray("rect")
                    val x0 = r.getDouble(0).toFloat(); val y0 = r.getDouble(1).toFloat()
                    val x1 = r.getDouble(2).toFloat(); val y1 = r.getDouble(3).toFloat()
                    PlanBox(listOf(Offset(x0, y0), Offset(x1, y0), Offset(x1, y1), Offset(x0, y1)), 0f, o.getDouble("h").toFloat())
                }
            }
            val doors = json.getJSONArray("doors").let { a -> List(a.length()) { seg(a.getJSONArray(it)) } }
            val windows = json.getJSONArray("windows").let { a -> List(a.length()) { seg(a.getJSONArray(it)) } }
            return HomePlan(rooms, walls, boxes("fixtures") + boxes("furniture"), doors, windows)
        }
    }
}
