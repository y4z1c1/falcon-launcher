package dev.yusuf.falconlauncher.ui

import androidx.compose.foundation.BorderStroke
import java.util.Locale
import androidx.tv.material3.Surface
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import dev.yusuf.falconlauncher.R
import dev.yusuf.falconlauncher.data.AppEntry

/** The dock's last tile: opens every other app. */
@Composable
fun AppsTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.aspectRatio(16f / 9f),
        shape = CardDefaults.shape(Shapes.Tile),
        scale = CardDefaults.scale(focusedScale = 1.1f, pressedScale = 1.04f),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = Shapes.Tile)),
        colors = CardDefaults.colors(containerColor = Color.White.copy(alpha = 0.1f), focusedContainerColor = Color.White.copy(alpha = 0.2f)),
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(painterResource(R.drawable.ic_apps), contentDescription = null, modifier = Modifier.size(30.dp))
            Text("Apps", fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** Every app, in a full-screen glass overlay. Back closes it; hold OK on an app for its menu. */
@Composable
fun AllAppsOverlay(
    apps: List<AppEntry>,
    hidden: Set<String>,
    onLaunch: (AppEntry) -> Unit,
    onMenu: (AppEntry, FocusRequester) -> Unit,
    menu: @Composable (AppEntry, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    var query by remember { mutableStateOf("") }
    val locale = remember { Locale.getDefault() }
    val shown = remember(apps, query) {
        val q = query.trim().lowercase(locale)
        if (q.isEmpty()) apps else apps.filter { it.label.lowercase(locale).contains(q) }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.9f))
                .springIn()
                .padding(horizontal = 56.dp, vertical = 40.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 20.dp, start = 4.dp, end = 12.dp)) {
                    Text("Apps", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    SearchField(query = query, onQueryChange = { query = it })
                }
                if (shown.isEmpty()) {
                    Text(
                        "No apps match \"$query\"",
                        fontSize = 15.sp,
                        color = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp),
                    )
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    contentPadding = PaddingValues(12.dp),
                ) {
                    itemsIndexed(shown, key = { _, app -> app.packageName }) { index, app ->
                        val requester = remember { FocusRequester() }
                        AppTile(
                            app = app,
                            onClick = {
                                onDismiss()
                                onLaunch(app)
                            },
                            onMenu = { onMenu(app, requester) },
                            dimmed = app.packageName in hidden,
                            menu = { menu(app, "all:" + app.packageName) },
                            modifier = Modifier
                                .focusRequester(requester)
                                .then(if (index == 0) Modifier.focusRequester(first) else Modifier),
                        )
                    }
                }
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

/**
 * A glass search bar for the D-pad. Moving onto it doesn't open the keyboard (Compose would otherwise pop
 * it up as soon as the field gets focus); pressing OK starts typing, and Done or Back returns.
 */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    val button = remember { FocusRequester() }
    val shape = Shapes.Button
    Surface(
        onClick = { editing = true },
        modifier = Modifier.width(380.dp).height(48.dp).focusRequester(button),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.1f),
            contentColor = Color.White,
            focusedContainerColor = Color.White.copy(alpha = 0.2f),
            focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Icon(rememberVectorPainter(Icons.Default.Search), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty() && !editing) {
                    Text("Search apps", fontSize = 15.sp, color = Color.White.copy(alpha = 0.5f))
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 15.sp, fontFamily = UiFont),
                    cursorBrush = SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { editing = false }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusProperties { canFocus = editing }
                        .focusRequester(field)
                        .onFocusChanged { if (!it.isFocused && editing) editing = false },
                )
            }
        }
    }
    LaunchedEffect(editing) {
        withFrameNanos { }
        runCatching { if (editing) field.requestFocus() else button.requestFocus() }
    }
}
