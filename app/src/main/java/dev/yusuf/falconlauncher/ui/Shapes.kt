package dev.yusuf.falconlauncher.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Apple-style "continuous" rounded rectangle (a squircle-like curve with no visible
 * seam where the straight edge meets the corner), using the curve constants
 * reverse-engineered from iOS's UIBezierPath(roundedRect:cornerRadius:).
 */
class ContinuousRoundedShape(private val radius: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val maxRadius = minOf(size.width, size.height) / 2f / EDGE
        val r = minOf(with(density) { radius.toPx() }, maxRadius)
        val w = size.width
        val h = size.height

        // Each corner is drawn from points given as (distance along the incoming edge, distance into the corner),
        // mapped into place for that corner. Order: top-right, bottom-right, bottom-left, top-left.
        val corners: List<(Float, Float) -> Pair<Float, Float>> = listOf(
            { a, b -> (w - a * r) to (b * r) },
            { a, b -> (w - b * r) to (h - a * r) },
            { a, b -> (a * r) to (h - b * r) },
            { a, b -> (b * r) to (a * r) },
        )
        val path = Path().apply {
            moveTo(EDGE * r, 0f)
            for (at in corners) {
                lineTo(at(EDGE, 0f))
                cubicTo(at(1.08849323f, 0f), at(0.86840689f, 0f), at(0.63149399f, 0.07491100f))
                cubicTo(at(0.37282392f, 0.16905899f), at(0.16906013f, 0.37282401f), at(0.07491176f, 0.63149399f))
                cubicTo(at(0f, 0.86840701f), at(0f, 1.08849299f), at(0f, EDGE))
            }
            close()
        }
        return Outline.Generic(path)
    }

    private fun Path.lineTo(p: Pair<Float, Float>) = lineTo(p.first, p.second)

    private fun Path.cubicTo(c1: Pair<Float, Float>, c2: Pair<Float, Float>, end: Pair<Float, Float>) =
        cubicTo(c1.first, c1.second, c2.first, c2.second, end.first, end.second)

    override fun equals(other: Any?) = other is ContinuousRoundedShape && other.radius == radius
    override fun hashCode() = radius.hashCode()

    private companion object {
        /** How far along each edge the corner curve starts, in multiples of the radius. */
        const val EDGE = 1.52866483f
    }
}

/**
 * Corner radii used across the launcher: small and tight.
 *
 * Plain rounded rectangles on purpose: Android clips those analytically, while custom path shapes
 * (like [ContinuousRoundedShape]) force a clip mask per tile per frame, which cost this TV's GPU
 * several milliseconds a frame. At these small radii the squircle difference isn't visible anyway.
 */
object Shapes {
    val Tile = RoundedCornerShape(12.dp)
    val Dock = RoundedCornerShape(22.dp)
    val Widget = RoundedCornerShape(16.dp)
    val Dialog = RoundedCornerShape(20.dp)
    val Menu = RoundedCornerShape(16.dp)
    val Button = RoundedCornerShape(12.dp)
    val Field = RoundedCornerShape(10.dp)
    val MenuItem = RoundedCornerShape(10.dp)
    val Art = RoundedCornerShape(8.dp)
}
