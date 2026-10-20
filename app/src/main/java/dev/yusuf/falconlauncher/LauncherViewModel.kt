package dev.yusuf.falconlauncher

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.yusuf.falconlauncher.data.AppEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import dev.yusuf.falconlauncher.widgets.NowPlaying
import dev.yusuf.falconlauncher.widgets.Weather
import dev.yusuf.falconlauncher.widgets.Room
import dev.yusuf.falconlauncher.widgets.WizBulb
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LauncherState(
    val loaded: Boolean = false,
    /** Apps in the dock: the user's favorites, or the first few apps until they pin some. */
    val dock: List<AppEntry> = emptyList(),
    val favorites: List<AppEntry> = emptyList(),
    val apps: List<AppEntry> = emptyList(),
    val favoriteSet: Set<String> = emptySet(),
    val hidden: Set<String> = emptySet(),
    val showHidden: Boolean = false,
)

class LauncherViewModel(app: Application) : AndroidViewModel(app) {
    private val falconApp = app as FalconApp
    val repository = falconApp.repository
    private val prefs = falconApp.prefs
    private val showHidden = MutableStateFlow(false)
    private var lastLightScan = 0L

    private val nowPlayingSource = falconApp.nowPlaying
    private val lights = falconApp.lights
    val nowPlaying: StateFlow<NowPlaying?> = nowPlayingSource.state
    val weather: StateFlow<Weather?> = falconApp.weather.state
    val bulbs: StateFlow<List<WizBulb>> = lights.bulbs
    val bulbRooms: StateFlow<Map<String, String>> = prefs.bulbRooms.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val bulbNames: StateFlow<Map<String, String>> = prefs.bulbNames.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val bulbPositions = prefs.bulbPositions.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val homePlan get() = falconApp.homePlan
    val weatherCity: StateFlow<String> = prefs.weatherCity.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    private val _mediaAccess = MutableStateFlow(nowPlayingSource.hasAccess())
    val mediaAccess: StateFlow<Boolean> = _mediaAccess.asStateFlow()

    val state: StateFlow<LauncherState> =
        combine(repository.apps, prefs.favorites, prefs.hidden, showHidden) { apps, favs, hidden, showHidden ->
            if (apps == null) return@combine LauncherState()
            val byPackage = apps.associateBy { it.packageName }
            val favorites = favs.mapNotNull { byPackage[it] }
            val visible = apps.filter { showHidden || it.packageName !in hidden }
            LauncherState(
                loaded = true,
                dock = favorites.ifEmpty { visible.take(DEFAULT_DOCK_SIZE) },
                favorites = favorites,
                apps = visible,
                favoriteSet = favs.toSet(),
                hidden = hidden,
                showHidden = showHidden,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, LauncherState())

    fun toggleFavorite(pkg: String) = viewModelScope.launch { prefs.toggleFavorite(pkg) }
    fun moveFavorite(pkg: String, delta: Int) = viewModelScope.launch { prefs.moveFavorite(pkg, delta) }
    fun setHidden(pkg: String, hide: Boolean) = viewModelScope.launch { prefs.setHidden(pkg, hide) }
    fun toggleShowHidden() = showHidden.update { !it }

    /** Called when the launcher comes to the front: pick up newly granted access and rescan lights now and then. */
    fun onResume() {
        _mediaAccess.value = nowPlayingSource.hasAccess()
        nowPlayingSource.startIfAllowed()
        // Opening the launcher with no weather yet (e.g. right after the TV wakes) fetches it now.
        if (falconApp.weather.state.value == null && weatherCity.value.isNotBlank()) {
            viewModelScope.launch { falconApp.weather.refresh(weatherCity.value) }
        }
        val now = System.currentTimeMillis()
        if (now - lastLightScan > LIGHT_RESCAN_MS) {
            lastLightScan = now
            viewModelScope.launch { lights.discover() }
        }
    }

    fun togglePlayPause() = nowPlayingSource.togglePlayPause()
    fun nextTrack() = nowPlayingSource.next()
    fun previousTrack() = nowPlayingSource.previous()

    fun toggleLights() = viewModelScope.launch {
        if (bulbs.value.isEmpty()) lights.discover() else lights.setAll(on = bulbs.value.none { it.isOn })
    }
    fun toggleLightsTo(on: Boolean) = viewModelScope.launch { lights.setAll(on) }
    fun dimLights(delta: Int) = viewModelScope.launch { lights.dimAll(delta) }
    fun setBulb(bulb: WizBulb, on: Boolean) = viewModelScope.launch { lights.set(bulb, on = on) }
    fun rescanLights() = viewModelScope.launch { lights.discover() }
    fun setLightsLive(on: Boolean) = lights.setLive(on)

    val lightShortcuts = combine(prefs.lightShortcuts, lights.bulbs) { saved, bulbs -> dev.yusuf.falconlauncher.data.LightShortcut.resolve(saved, bulbs) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    fun setLightShortcut(key: Int, shortcut: dev.yusuf.falconlauncher.data.LightShortcut) =
        viewModelScope.launch { prefs.setLightShortcut(key, shortcut.encode()) }
    fun setRoomLights(macs: Set<String>, on: Boolean? = null, dimming: Int? = null) =
        viewModelScope.launch { lights.setGroup(macs, on, dimming) }
    fun setBulbBrightness(bulb: WizBulb, dimming: Int) = viewModelScope.launch { lights.set(bulb, on = true, dimming = dimming) }
    fun identifyBulb(bulb: WizBulb) = viewModelScope.launch { lights.identify(bulb) }
    fun assignRoom(bulb: WizBulb, room: Room) = viewModelScope.launch { prefs.setBulbRoom(bulb.mac, room.name) }
    /** Places a lamp on the plan; its room follows from where it is. */
    fun placeBulb(bulb: WizBulb, x: Float, y: Float, room: Room?) = viewModelScope.launch {
        prefs.setBulbPosition(bulb.mac, x, y)
        room?.let { prefs.setBulbRoom(bulb.mac, it.name) }
    }

    fun renameBulb(bulb: WizBulb, name: String) = viewModelScope.launch { prefs.setBulbName(bulb.mac, name) }

    fun setWeatherCity(city: String) = viewModelScope.launch { prefs.setWeatherCity(city) }

    private companion object {
        const val DEFAULT_DOCK_SIZE = 5
        const val LIGHT_RESCAN_MS = 60_000L
    }
}
