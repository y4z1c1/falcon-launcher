package dev.yusuf.falconlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/**
 * The launcher's glass material: a translucent dark tint with a soft top highlight and a hairline edge
 * that is brighter at the top, like light catching the rim. (TV GPUs don't offer real backdrop blur,
 * so the drifting background simply shows through.)
 */
fun Modifier.glass(shape: Shape, strong: Boolean = false): Modifier = this
    .background(
        Brush.verticalGradient(
            if (strong) listOf(Color(0xE6262833), Color(0xDE14151B)) else listOf(Color(0x33FFFFFF), Color(0x14FFFFFF))
        ),
        shape,
    )
    .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.24f), Color.White.copy(alpha = 0.06f))), shape)

/** One row in a glass dropdown. A `null` in the item list draws a divider. */
class MenuItem(
    val label: String,
    val icon: Painter? = null,
    val detail: String? = null,
    val onClick: () -> Unit,
)

/**
 * A dropdown anchored to whatever composable it's placed in: below it when there's room, otherwise
 * above, and kept on screen. Used for every menu in the launcher, so they all look and move the same.
 * Place it inside the anchor's content while it should be shown.
 */
@Composable
fun GlassDropdown(
    items: List<MenuItem?>,
    onDismiss: () -> Unit,
    title: String? = null,
    alignEnd: Boolean = false,
    width: Dp = 280.dp,
) {
    val density = LocalDensity.current
    val positioner = remember(alignEnd, density) {
        AnchoredPosition(gap = with(density) { 10.dp.roundToPx() }, margin = with(density) { 24.dp.roundToPx() }, alignEnd)
    }
    val first = remember { FocusRequester() }
    Popup(popupPositionProvider = positioner, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .ignoreHeldConfirm()
                .springIn()
                .width(width)
                .glass(Shapes.Menu, strong = true)
                .padding(8.dp),
        ) {
            title?.let {
                Text(
                    it,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            var firstPlaced = false
            items.forEach { item ->
                if (item == null) {
                    Box(
                        Modifier
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color.White.copy(alpha = 0.1f))
                    )
                    return@forEach
                }
                val isFirst = !firstPlaced
                firstPlaced = true
                MenuRow(
                    item = item,
                    onClick = {
                        onDismiss()
                        item.onClick()
                    },
                    modifier = if (isFirst) Modifier.focusRequester(first) else Modifier,
                )
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
private fun MenuRow(item: MenuItem, onClick: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(44.dp),
        shape = ClickableSurfaceDefaults.shape(Shapes.MenuItem),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            item.icon?.let {
                Icon(it, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(12.dp))
            }
            Text(
                item.label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            item.detail?.let {
                Text(it, fontSize = 13.sp, color = LocalContentColor.current.copy(alpha = 0.6f), maxLines = 1)
            }
        }
    }
}

/** Below the anchor if it fits, else above; start- or end-aligned to it; never off screen. */
private class AnchoredPosition(private val gap: Int, private val margin: Int, private val alignEnd: Boolean) :
    PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = (if (alignEnd) anchorBounds.right - popupContentSize.width else anchorBounds.left)
            .coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
        val below = anchorBounds.bottom + gap
        val above = anchorBounds.top - gap - popupContentSize.height
        val y = when {
            below + popupContentSize.height <= windowSize.height - margin -> below
            above >= margin -> above
            else -> (windowSize.height - popupContentSize.height) / 2
        }
        return IntOffset(x, y)
    }
}
