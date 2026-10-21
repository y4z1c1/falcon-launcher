package dev.yusuf.falconlauncher.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import dev.yusuf.falconlauncher.R
import kotlinx.coroutines.delay
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun TopBar(
    wifiPermissionGranted: Boolean,
    onTv: (() -> Unit)?,
    onInputs: (() -> Unit)?,
    onNetwork: () -> Unit,
    onSettings: () -> Unit,
    showHidden: Boolean,
    onToggleHidden: () -> Unit,
    onStockHome: (() -> Unit)?,
    onScreensaver: () -> Unit,
    onLightShortcuts: () -> Unit,
    onCallMute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val networkLabel = rememberNetworkLabel(wifiPermissionGranted)
    var open by remember { mutableStateOf(false) }
    val pill = remember { FocusRequester() }
    val entries = buildList {
        onTv?.let { add(MenuItem("TV", painterResource(R.drawable.ic_tv), "HDMI 1", it)) }
        onInputs?.let { add(MenuItem("Inputs", painterResource(R.drawable.ic_input), null, it)) }
        add(MenuItem("Wi-Fi", painterResource(R.drawable.ic_wifi), networkLabel, onNetwork))
        add(MenuItem("TV settings", rememberVectorPainter(Icons.Default.Settings), null, onSettings))
        add(null)
        add(MenuItem(if (showHidden) "Hide hidden apps" else "Show hidden apps", rememberVectorPainter(Icons.Default.Menu), null, onToggleHidden))
        onStockHome?.let { add(MenuItem("Google TV home", rememberVectorPainter(Icons.Default.Home), null, it)) }
        add(MenuItem("Light shortcuts", painterResource(R.drawable.ic_bulb), "0–9", onLightShortcuts))
        add(MenuItem("Call muting", rememberVectorPainter(Icons.Default.Call), null, onCallMute))
        add(MenuItem("Screensaver", rememberVectorPainter(Icons.Default.PlayArrow), null, onScreensaver))
    }

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Clock()
        Spacer(Modifier.weight(1f))
        Box {
            // Just the gear; it opens the Settings dropdown.
            Button(
                onClick = { open = true },
                modifier = Modifier.size(52.dp).focusRequester(pill),
                shape = ButtonDefaults.shape(Shapes.Button),
                colors = ButtonDefaults.colors(
                    containerColor = Color.White.copy(alpha = 0.12f),
                    contentColor = Color.White,
                    focusedContainerColor = Color.White,
                    focusedContentColor = Color.Black,
                ),
                scale = ButtonDefaults.scale(focusedScale = 1.08f),
                contentPadding = PaddingValues(0.dp),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(rememberVectorPainter(Icons.Default.Settings), contentDescription = "Settings", modifier = Modifier.size(24.dp))
                }
            }
            if (open) {
                GlassDropdown(entries, alignEnd = true, width = 300.dp, onDismiss = {
                    open = false
                    runCatching { pill.requestFocus() }
                })
            }
        }
    }
}

@Composable
private fun Clock() {
    val context = LocalContext.current
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(60_000 - value % 60_000)
        }
    }
    val is24h = remember { DateFormat.is24HourFormat(context) }
    val locale = remember { Locale.getDefault() }
    val date = Date(now)

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row {
                Text(
                    DateFormat.format(if (is24h) "HH:mm" else "h:mm", date).toString(),
                    fontSize = 60.sp,
                    fontWeight = FontWeight.Bold,
                    // A typeface designed wide (not a stretched one), so the digits stay crisp.
                    fontFamily = AnybodyWide,
                    letterSpacing = (-1).sp,
                    modifier = Modifier.alignByBaseline(),
                )
                if (!is24h) {
                    Text(
                        DateFormat.format("a", date).toString().uppercase(locale),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.alignByBaseline().padding(start = 6.dp),
                    )
                }
            }
            Box(
                Modifier
                    .padding(horizontal = 18.dp)
                    .width(1.dp)
                    .height(28.dp)
                    .background(Color.White.copy(alpha = 0.35f))
            )
            Text(
                DateFormat.format(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), date).toString()
                    .replace(",", "").uppercase(locale),
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                letterSpacing = 2.5.sp,
                color = Color.White.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun Pill(
    icon: Painter,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: Painter? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = ButtonDefaults.shape(Shapes.Button),
        colors = ButtonDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.12f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        scale = ButtonDefaults.scale(focusedScale = 1.06f),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        if (trailing != null) {
            Spacer(Modifier.width(6.dp))
            Icon(trailing, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
}

/** "Ethernet", the Wi-Fi network name (needs location permission), "Wi-Fi", or "Offline". */
@Composable
private fun rememberNetworkLabel(wifiPermissionGranted: Boolean): String {
    val context = LocalContext.current
    val label = remember { mutableStateOf("Offline") }
    DisposableEffect(context, wifiPermissionGranted) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = networkCallback { caps -> label.value = networkLabel(caps) }
        cm.registerDefaultNetworkCallback(callback)
        onDispose { cm.unregisterNetworkCallback(callback) }
    }
    return label.value
}

private fun networkLabel(caps: NetworkCapabilities?): String = when {
    caps == null -> "Offline"
    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
        val ssid = (caps.transportInfo as? WifiInfo)?.ssid?.removeSurrounding("\"")
        if (ssid.isNullOrBlank() || ssid == WifiManager.UNKNOWN_SSID) "Wi-Fi" else ssid
    }
    else -> "Online"
}

private fun networkCallback(onChange: (NetworkCapabilities?) -> Unit): ConnectivityManager.NetworkCallback =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onChange(caps)
            override fun onLost(network: Network) = onChange(null)
        }
    } else {
        object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onChange(caps)
            override fun onLost(network: Network) = onChange(null)
        }
    }
