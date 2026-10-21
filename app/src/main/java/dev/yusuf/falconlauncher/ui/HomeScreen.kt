package dev.yusuf.falconlauncher.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import dev.yusuf.falconlauncher.LauncherViewModel
import dev.yusuf.falconlauncher.data.AppEntry
import dev.yusuf.falconlauncher.data.AppRepository
import dev.yusuf.falconlauncher.service.HomeWatcherService
import dev.yusuf.falconlauncher.widgets.NowPlaying
import dev.yusuf.falconlauncher.widgets.WizBulb

private const val SPOTIFY = "com.spotify.tv.android"
private val SidePadding = 56.dp
private val DockTileWidth = 172.dp
private val DockSpacing = 20.dp
private val DockPadding = 14.dp
private val WidgetGap = 16.dp
private val SideCardWidth = 312.dp

/**
 * One page, no scrolling: clock and Settings along the top, and centered below them Now Playing,
 * the weather and lights cards, and the dock. Everything else lives behind the dock's Apps tile.
 */
@Composable
fun HomeScreen(
    vm: LauncherViewModel,
    homeSignal: Int,
    serviceStatus: HomeWatcherService.Status,
    wifiPermissionGranted: Boolean,
) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val mediaAccess by vm.mediaAccess.collectAsStateWithLifecycle()
    val weather by vm.weather.collectAsStateWithLifecycle()
    val bulbs by vm.bulbs.collectAsStateWithLifecycle()
    val weatherCity by vm.weatherCity.collectAsStateWithLifecycle()
    val bulbRooms by vm.bulbRooms.collectAsStateWithLifecycle()
    val bulbNames by vm.bulbNames.collectAsStateWithLifecycle()
    val bulbPositions by vm.bulbPositions.collectAsStateWithLifecycle()
    val dockFocus = remember { FocusRequester() }
    val appsTileFocus = remember { FocusRequester() }
    // Explicit up-targets from the dock: the automatic search skipped the stacked cards on the right.
    val nowPlayingFocus = remember { FocusRequester() }
    val lightsFocus = remember { FocusRequester() }
    // Read only inside FeaturedBackdrop, so moving focus doesn't recompose the whole screen.
    val focusedApp = remember { mutableStateOf<AppEntry?>(null) }
    /** Which tile's dropdown is open, e.g. "dock:com.netflix.ninja" or "all:…" (an app can be in both). */
    var menuKey by remember { mutableStateOf<String?>(null) }
    var menuOrigin by remember { mutableStateOf<FocusRequester?>(null) }
    var restoreFocusTo by remember { mutableStateOf<FocusRequester?>(null) }
    var showLights by remember { mutableStateOf(false) }
    var showAllApps by remember { mutableStateOf(false) }
    var renameBulb by remember { mutableStateOf<WizBulb?>(null) }
    var showCityDialog by remember { mutableStateOf(false) }
    var showShortcuts by remember { mutableStateOf(false) }
    var showCallMute by remember { mutableStateOf(false) }
    val callMuteEnabled by vm.callMuteEnabled.collectAsStateWithLifecycle()
    val ancsState by vm.ancsState.collectAsStateWithLifecycle()
    val lightShortcuts by vm.lightShortcuts.collectAsStateWithLifecycle()

    val inputsIntent = remember { SystemActions.inputs(context) }
    val tvIntent = remember { SystemActions.hdmi1(context) }
    val stockHomeIntent = remember { SystemActions.stockHome(context) }

    // Every time Home brings us back, close the app overlay and focus the first dock app.
    LaunchedEffect(state.loaded, homeSignal) {
        if (!state.loaded) return@LaunchedEffect
        showAllApps = false
        focusFirstAvailable(dockFocus)
    }

    // A closed dialog leaves nothing focused, so put focus back where the menu was opened.
    LaunchedEffect(restoreFocusTo) {
        val target = restoreFocusTo ?: return@LaunchedEffect
        focusFirstAvailable(target, dockFocus)
        restoreFocusTo = null
    }

    fun openMenu(key: String, origin: FocusRequester) {
        menuKey = key
        menuOrigin = origin
    }

    val appMenu: @Composable (AppEntry, String) -> Unit = { app, key ->
        if (menuKey == key) {
            GlassDropdown(
                items = appMenuItems(app, state, vm),
                title = app.label,
                onDismiss = {
                    menuKey = null
                    restoreFocusTo = menuOrigin
                },
            )
        }
    }

    // Everything spans exactly the dock's width (its apps plus the Apps tile), so the clock, the cards
    // and the dock share the same left and right edges.
    val tiles = state.dock.size + 1
    val dockWidth = DockTileWidth * tiles + DockSpacing * (tiles - 1) + DockPadding * 2

    // While the lights page is open it's the only thing on screen (the home screen isn't drawn behind it).
    if (!showLights) Box(Modifier.fillMaxSize()) {
        FeaturedBackdrop(focusedApp, fallback = state.dock.firstOrNull(), nowPlaying = nowPlaying, paused = showLights || showAllApps)
        // The whole block (clock row, cards, dock) is centered both ways: equal space above and below.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(horizontal = SidePadding),
        ) {
            TopBar(
                modifier = Modifier.width(dockWidth),
                wifiPermissionGranted = wifiPermissionGranted,
                onTv = tvIntent?.let { { HomeWatcherService.openReceiver(context) } },
                onInputs = inputsIntent?.let { intent ->
                    {
                        HomeWatcherService.bypass = true
                        vm.repository.start(intent)
                    }
                },
                onNetwork = { vm.repository.start(SystemActions.network()) },
                onSettings = { vm.repository.start(SystemActions.settings()) },
                showHidden = state.showHidden,
                onToggleHidden = vm::toggleShowHidden,
                onLightShortcuts = { showShortcuts = true },
                onCallMute = { showCallMute = true },
                onScreensaver = {
                    context.startActivity(android.content.Intent(context, dev.yusuf.falconlauncher.dream.ScreensaverActivity::class.java))
                },
                onStockHome = stockHomeIntent?.let { intent ->
                    {
                        HomeWatcherService.bypass = true
                        vm.repository.start(intent)
                    }
                },
            )
            Spacer(Modifier.height(44.dp))
            // While the Home button service is down, fixing it matters more than the widgets.
            if (serviceStatus != HomeWatcherService.Status.ON) {
                ServiceHint(
                    status = serviceStatus,
                    onOpenAccessibility = { vm.repository.start(SystemActions.accessibilitySettings()) },
                    modifier = Modifier.width(928.dp).padding(bottom = 24.dp),
                )
            } else {
                // Music on the left, weather and lights stacked on the right, spanning the dock's width.
                Row(horizontalArrangement = Arrangement.spacedBy(WidgetGap), modifier = Modifier.padding(bottom = 28.dp)) {
                    NowPlayingCard(
                        nowPlaying = nowPlaying,
                        mediaAccess = mediaAccess,
                        onOpen = { vm.repository.launch(nowPlaying?.packageName?.takeIf { it.isNotBlank() } ?: SPOTIFY) },
                        onAllowAccess = { vm.repository.start(SystemActions.notificationAccess()) },
                        onPrevious = vm::previousTrack,
                        onPlayPause = vm::togglePlayPause,
                        onNext = vm::nextTrack,
                        infoModifier = Modifier.focusRequester(nowPlayingFocus),
                        width = dockWidth - WidgetGap - SideCardWidth,
                    )
                    WidgetColumn(
                        weather = weather,
                        bulbs = bulbs,
                        rooms = bulbRooms,
                        onWeatherClick = { showCityDialog = true },
                        onLightsClick = { showLights = true },
                        onLightsMenu = vm::toggleLights,
                        lightsModifier = Modifier.focusRequester(lightsFocus),
                    )
                }
            }
            Dock(
                apps = state.dock,
                firstFocus = dockFocus,
                appsTileFocus = appsTileFocus,
                upLeft = nowPlayingFocus,
                upRight = lightsFocus,
                onFocused = { app -> focusedApp.value = app },
                onClick = { vm.repository.launch(it.packageName) },
                onMenu = { app, origin -> openMenu("dock:" + app.packageName, origin) },
                onAllApps = { showAllApps = true },
                menu = appMenu,
            )
        }
    }

    if (showAllApps) {
        AllAppsOverlay(
            // Everything not already in the dock.
            apps = state.apps.filter { it.packageName !in state.favoriteSet },
            hidden = state.hidden,
            onLaunch = { vm.repository.launch(it.packageName) },
            onMenu = { app, origin -> openMenu("all:" + app.packageName, origin) },
            menu = appMenu,
            onDismiss = {
                showAllApps = false
                restoreFocusTo = appsTileFocus
            },
        )
    }

    if (showLights) {
        LightsPanel(
            plan = vm.homePlan,
            bulbs = bulbs,
            positions = bulbPositions,
            rooms = bulbRooms,
            names = bulbNames,
            actions = remember(vm) {
                LightsActions(
                    toggleBulb = { vm.setBulb(it, !it.isOn) },
                    setBulbBrightness = vm::setBulbBrightness,
                    setRoom = { macs, on, dimming -> vm.setRoomLights(macs, on, dimming) },
                    identify = vm::identifyBulb,
                    rename = { renameBulb = it },
                    rescan = vm::rescanLights,
                    place = { bulb, x, y, room -> vm.placeBulb(bulb, x, y, room) },
                    live = vm::setLightsLive,
                )
            },
            onDismiss = {
                showLights = false
                restoreFocusTo = dockFocus
            },
        )
    }

    renameBulb?.let { bulb ->
        TextInputDialog(
            title = "Lamp name",
            initial = bulb.displayName(bulbNames),
            onSave = { vm.renameBulb(bulb, it) },
            onDismiss = { renameBulb = null },
        )
    }

    if (showCallMute) {
        CallMutePanel(
            enabled = callMuteEnabled,
            ancsState = ancsState,
            onToggle = vm::setCallMuteEnabled,
            onTest = { vm.testCallMute() },
            onDismiss = {
                showCallMute = false
                restoreFocusTo = dockFocus
            },
        )
    }

    if (showShortcuts) {
        ShortcutsPanel(
            shortcuts = lightShortcuts,
            bulbs = bulbs,
            names = bulbNames,
            rooms = bulbRooms,
            onSet = vm::setLightShortcut,
            onDismiss = {
                showShortcuts = false
                restoreFocusTo = dockFocus
            },
        )
    }

    if (showCityDialog) {
        TextInputDialog(
            title = "Weather city",
            initial = weatherCity,
            onSave = vm::setWeatherCity,
            onDismiss = {
                showCityDialog = false
                restoreFocusTo = dockFocus
            },
        )
    }
}

/**
 * Background colors: from the playing song's artwork while music plays, otherwise from the focused
 * app (the main color plus two close shades). Each color animates, so changes blend smoothly.
 */
@Composable
private fun FeaturedBackdrop(focused: State<AppEntry?>, fallback: AppEntry?, nowPlaying: NowPlaying?, paused: Boolean = false) {
    val artColors = nowPlaying?.takeIf { it.isPlaying }?.artColors
    val appColor = (focused.value ?: fallback)?.accent ?: AppRepository.DEFAULT_ACCENT
    val targets = if (artColors != null) {
        List(3) { i -> Color(artColors.getOrElse(i) { artColors.first() }) }
    } else {
        listOf(appColor, appColor.shifted(12f, 1.1f), appColor.shifted(-8f, 0.75f))
    }
    val c0 = animateColorAsState(targets[0], tween(900), label = "bg0")
    val c1 = animateColorAsState(targets[1], tween(900), label = "bg1")
    val c2 = animateColorAsState(targets[2], tween(900), label = "bg2")
    AmbientBackground(colors = { listOf(c0.value, c1.value, c2.value) }, paused = paused)
}

private fun Color.shifted(degrees: Float, brightness: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(android.graphics.Color.argb(255, (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt()), hsv)
    hsv[0] = (hsv[0] + degrees + 360f) % 360f
    hsv[2] = (hsv[2] * brightness).coerceIn(0f, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

@Composable
private fun Dock(
    apps: List<AppEntry>,
    firstFocus: FocusRequester,
    appsTileFocus: FocusRequester,
    upLeft: FocusRequester,
    upRight: FocusRequester,
    onFocused: (AppEntry) -> Unit,
    onClick: (AppEntry) -> Unit,
    onMenu: (AppEntry, FocusRequester) -> Unit,
    onAllApps: () -> Unit,
    menu: @Composable (AppEntry, String) -> Unit,
) {
    val shape = Shapes.Dock
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(DockSpacing),
        contentPadding = PaddingValues(DockPadding),
        modifier = Modifier.glass(shape),
    ) {
        itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
            val requester = remember { FocusRequester() }
            AppTile(
                app = app,
                onClick = { onClick(app) },
                onMenu = { onMenu(app, requester) },
                menu = { menu(app, "dock:" + app.packageName) },
                modifier = Modifier
                    .width(DockTileWidth)
                    .focusRequester(requester)
                    .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                    .focusProperties { up = if (index < 2) upLeft else upRight }
                    .onFocusChanged { if (it.isFocused) onFocused(app) },
            )
        }
        item(key = "apps") {
            AppsTile(
                onClick = onAllApps,
                modifier = Modifier
                    .width(DockTileWidth)
                    .focusRequester(appsTileFocus)
                    .focusProperties { up = upRight },
            )
        }
    }
}


@Composable
private fun ServiceHint(
    status: HomeWatcherService.Status,
    onOpenAccessibility: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val blocked = status == HomeWatcherService.Status.BLOCKED
    val shape = Shapes.Widget
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(84.dp)
            .glass(shape)
            .padding(horizontal = 20.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (blocked) "The Home button service is blocked" else "Make the Home button open this launcher",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                if (blocked) {
                    "TCL blocks it from auto-starting. Run once from a computer: " +
                        "adb shell appops set dev.yusuf.falconlauncher AUTO_START allow — then turn the service off and on."
                } else {
                    "Turn on \"Falcon Launcher Home button\" in Accessibility settings."
                },
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.75f),
                maxLines = 2,
            )
        }
        GlassButton("Accessibility", onOpenAccessibility)
    }
}

/** Focuses the first target that accepts focus, retrying for a few frames while items are still being composed. */
private suspend fun focusFirstAvailable(vararg targets: FocusRequester) {
    repeat(10) {
        withFrameNanos { }
        if (targets.any { runCatching { it.requestFocus(FocusDirection.Enter) }.getOrDefault(false) }) return
    }
}
