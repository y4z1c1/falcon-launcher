package dev.yusuf.falconlauncher

import android.app.Application
import dev.yusuf.falconlauncher.data.AppRepository
import dev.yusuf.falconlauncher.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
    val recents by lazy { dev.yusuf.falconlauncher.data.RecentApps(this) }
    val localAdb by lazy { dev.yusuf.falconlauncher.tv.LocalAdb(this) }

    /**
     * Stops an app for good (the app switcher's close) and drops it from the recents. Runs to the end
     * even if the switcher closes meanwhile, so the list never keeps an app that was stopped.
     */
    suspend fun closeApp(packageName: String): Boolean = appScope.async {
        localAdb.forceStop(packageName).also { if (it) recents.remove(packageName) }
    }.await()

    val callMute by lazy { dev.yusuf.falconlauncher.tv.CallMute(this, nowPlaying) }
    private val callMuteEnabledState by lazy { prefs.callMuteEnabled.stateIn(appScope, SharingStarted.Eagerly, true) }
    val callMuteEnabled: Boolean get() = callMuteEnabledState.value
    private val incomingCalls by lazy {
        dev.yusuf.falconlauncher.tv.IncomingCalls(callMute, { callMuteEnabled }) { text ->
            android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    /** Hears the iPhone ring over Bluetooth (ANCS) once paired. */
    val ancs by lazy { dev.yusuf.falconlauncher.tv.AncsClient(this) { incomingCalls.onEvent(it) } }

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
        callMuteEnabledState
        // Bluetooth ring detection runs while call muting is on (and once permissions are granted).
        appScope.launch { prefs.callMuteEnabled.collect { on -> if (on) ancs.start() else ancs.stop() } }
        dev.yusuf.falconlauncher.tv.MusicKeepAwake(this, localAdb, nowPlaying.state).start(appScope)
    }

    private companion object {
        val WEATHER_REFRESH = 30.minutes
        val WEATHER_RETRY = 1.minutes
    }
}
