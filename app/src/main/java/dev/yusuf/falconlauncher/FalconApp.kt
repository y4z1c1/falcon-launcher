package dev.yusuf.falconlauncher

import android.app.Application
import dev.yusuf.falconlauncher.data.AppRepository
import dev.yusuf.falconlauncher.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import dev.yusuf.falconlauncher.data.LightShortcut
import dev.yusuf.falconlauncher.widgets.NowPlayingSource
import dev.yusuf.falconlauncher.widgets.WeatherSource
import dev.yusuf.falconlauncher.widgets.WizLights
import kotlin.time.Duration.Companion.minutes

/**
 * Holds the app list for the whole process. The accessibility service keeps this process alive,
 * so the list survives between Home presses instead of being rebuilt every time the launcher opens.
 */
class FalconApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val repository by lazy { AppRepository(this).also { it.start(appScope) } }
    val prefs by lazy { Prefs(this) }
    val nowPlaying by lazy { NowPlayingSource(this) }
    val weather = WeatherSource()
    val lights by lazy { WizLights(this) }
    val homePlan by lazy { dev.yusuf.falconlauncher.data.HomePlan.load(this) }

    // Kept warm so a remote number key can act instantly, from any app.
    private val savedShortcuts by lazy { prefs.lightShortcuts.stateIn(appScope, SharingStarted.Eagerly, emptyMap()) }
    private val bulbRooms by lazy { prefs.bulbRooms.stateIn(appScope, SharingStarted.Eagerly, emptyMap()) }
    private val bulbNames by lazy { prefs.bulbNames.stateIn(appScope, SharingStarted.Eagerly, emptyMap()) }

    fun lightShortcut(key: Int): LightShortcut = LightShortcut.resolve(savedShortcuts.value, lights.bulbs.value)[key] ?: LightShortcut.None

    /**
     * Runs a number key's light action: a lamp or room toggles; "all lights" turns everything off if any
     * lamp is on, otherwise on. Returns a short confirmation like "masa1 · On", or null if nothing to do.
     */
    fun runLightShortcut(key: Int): String? {
        val bulbs = lights.bulbs.value
        fun name(b: dev.yusuf.falconlauncher.widgets.WizBulb) = bulbNames.value[b.mac] ?: b.defaultName
        return when (val s = lightShortcut(key)) {
            LightShortcut.None -> null
            LightShortcut.AllLights -> {
                val on = bulbs.none { it.isOn }
                appScope.launch { lights.setAll(on) }
                "All lights · ${if (on) "On" else "Off"}"
            }
            is LightShortcut.Lamp -> {
                val b = bulbs.firstOrNull { it.mac == s.mac } ?: return "Lamp not found"
                appScope.launch { lights.set(b, on = !b.isOn) }
                "${name(b)} · ${if (b.isOn) "Off" else "On"}"
            }
            is LightShortcut.InRoom -> {
                val inRoom = bulbs.filter { bulbRooms.value[it.mac] == s.room.name }
                if (inRoom.isEmpty()) return "${s.room.label} · no lamps"
                val on = inRoom.none { it.isOn }
                appScope.launch { lights.setGroup(inRoom.map { it.mac }.toSet(), on = on) }
                "${s.room.label} · ${if (on) "On" else "Off"}"
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository
        nowPlaying.startIfAllowed()
        appScope.launch { prefs.applyDockPresetOnce() }
        appScope.launch {
            prefs.weatherCity.collectLatest { city ->
                while (true) {
                    // After a failure (often Wi-Fi still reconnecting after the TV wakes), retry soon.
                    val ok = weather.refresh(city)
                    delay(if (ok) WEATHER_REFRESH else WEATHER_RETRY)
                }
            }
        }
        lights.start()
        savedShortcuts; bulbRooms; bulbNames
    }

    private companion object {
        val WEATHER_REFRESH = 30.minutes
        val WEATHER_RETRY = 1.minutes
    }
}
