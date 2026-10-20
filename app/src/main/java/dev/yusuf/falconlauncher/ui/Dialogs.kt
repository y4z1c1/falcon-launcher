package dev.yusuf.falconlauncher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import dev.yusuf.falconlauncher.R
import dev.yusuf.falconlauncher.LauncherState
import dev.yusuf.falconlauncher.LauncherViewModel
import dev.yusuf.falconlauncher.data.AppEntry

/** Entrance for menus and panels: a quick fade with a soft spring scale, like tvOS. */
@Composable
fun Modifier.springIn(): Modifier {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = 420f)) }
    return graphicsLayer {
        alpha = progress.value.coerceIn(0f, 1f)
        val scale = 0.94f + 0.06f * progress.value
        scaleX = scale
        scaleY = scale
    }
}

/** The standard button look inside menus and panels: glass, turning white when focused. */
@Composable
fun GlassButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    Button(
        onClick = onClick,
        modifier = modifier,
        contentPadding = if (compact) PaddingValues(horizontal = 16.dp, vertical = 6.dp) else ButtonDefaults.ContentPadding,
        shape = ButtonDefaults.shape(Shapes.Button),
        colors = ButtonDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.1f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        scale = ButtonDefaults.scale(focusedScale = 1.04f),
    ) { Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
}

/** A square glass icon button (e.g. a gear), white when focused. */
@Composable
fun GlassIconButton(icon: androidx.compose.ui.graphics.painter.Painter, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.size(44.dp),
        shape = ButtonDefaults.shape(Shapes.Button),
        colors = ButtonDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.1f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        scale = ButtonDefaults.scale(focusedScale = 1.06f),
        contentPadding = PaddingValues(0.dp),
    ) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            androidx.tv.material3.Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp))
        }
    }
}

/** A one-line text prompt. The TV's on-screen keyboard opens with the field focused. */
@Composable
fun TextInputDialog(title: String, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val field = remember { FocusRequester() }
    val save = {
        onDismiss()
        onSave(text.text)
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .springIn()
                .width(400.dp)
                .glass(Shapes.Dialog, strong = true)
                .padding(24.dp),
        ) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 18.sp, fontFamily = UiFont),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.08f), Shapes.Field)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .focusRequester(field),
            )
            GlassButton("Save", onClick = save, modifier = Modifier.fillMaxWidth())
        }
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { field.requestFocus() }
    }
}

/** Items for an app's dropdown (hold OK or press Menu on an app). */
@Composable
fun appMenuItems(app: AppEntry, state: LauncherState, vm: LauncherViewModel): List<MenuItem?> {
    val pkg = app.packageName
    val isFavorite = pkg in state.favoriteSet
    val isHidden = pkg in state.hidden
    val favIndex = state.favorites.indexOfFirst { it.packageName == pkg }
    val open = painterResource(R.drawable.ic_open)
    val dockIcon = rememberVectorPainter(if (isFavorite) Icons.Default.Delete else Icons.Default.Add)
    val left = rememberVectorPainter(Icons.AutoMirrored.Filled.KeyboardArrowLeft)
    val right = rememberVectorPainter(Icons.AutoMirrored.Filled.KeyboardArrowRight)
    val hideIcon = painterResource(if (isHidden) R.drawable.ic_visibility else R.drawable.ic_visibility_off)
    val info = rememberVectorPainter(Icons.Default.Info)
    return buildList {
        add(MenuItem("Open", open) { vm.repository.launch(pkg) })
        add(null)
        add(MenuItem(if (isFavorite) "Remove from dock" else "Add to dock", dockIcon) { vm.toggleFavorite(pkg) })
        if (isFavorite && favIndex > 0) add(MenuItem("Move left", left) { vm.moveFavorite(pkg, -1) })
        if (isFavorite && favIndex in 0 until state.favorites.lastIndex) add(MenuItem("Move right", right) { vm.moveFavorite(pkg, 1) })
        add(MenuItem(if (isHidden) "Unhide" else "Hide", hideIcon) { vm.setHidden(pkg, !isHidden) })
        add(null)
        add(MenuItem("App info", info) { vm.repository.openAppInfo(pkg) })
    }
}
