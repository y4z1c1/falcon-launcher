package dev.yusuf.falconlauncher.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Text
import dev.yusuf.falconlauncher.data.AppEntry

private val TileShape = Shapes.Tile

@Composable
fun AppTile(
    app: AppEntry,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    /** A [GlassDropdown] to anchor to this tile, when its menu is open. */
    menu: @Composable () -> Unit = {},
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .aspectRatio(16f / 9f)
            .alpha(if (dimmed) 0.35f else 1f)
            .longPressMenu(onMenu),
        shape = CardDefaults.shape(TileShape),
        scale = CardDefaults.scale(focusedScale = 1.1f, pressedScale = 1.04f),
        border = CardDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = TileShape),
        ),
        glow = CardDefaults.glow(
            focusedGlow = Glow(elevationColor = Color.Black.copy(alpha = 0.6f), elevation = 16.dp),
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
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize().padding(8.dp),
            ) {
                Image(bitmap = app.image, contentDescription = null, modifier = Modifier.size(40.dp))
                Text(
                    app.label,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        menu()
    }
}
