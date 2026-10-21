package dev.yusuf.falconlauncher.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "launcher")

/** Persisted launcher choices: ordered favorites and hidden apps. */
class Prefs(context: Context) {
    private val store = context.dataStore
    private val defaultCity = context.getString(dev.yusuf.falconlauncher.R.string.default_weather_city)

    val favorites: Flow<List<String>> = store.data.map { prefs ->
        prefs[FAVORITES]?.split(',')?.filter { it.isNotBlank() }.orEmpty()
    }

    val hidden: Flow<Set<String>> = store.data.map { it[HIDDEN].orEmpty() }

    val weatherCity: Flow<String> = store.data.map { it[WEATHER_CITY] ?: defaultCity }.distinctUntilChanged()

    /** Which room each WiZ bulb is in, keyed by MAC address. WiZ only keeps rooms in its cloud. */
    val bulbRooms: Flow<Map<String, String>> = store.data.map { decodeMap(it[BULB_ROOMS]) }.distinctUntilChanged()
    val bulbNames: Flow<Map<String, String>> = store.data.map { decodeMap(it[BULB_NAMES]) }.distinctUntilChanged()

    /** Where each lamp sits on the home plan (meters), keyed by MAC address. */
    val bulbPositions: Flow<Map<String, androidx.compose.ui.geometry.Offset>> = store.data.map { prefs ->
        decodeMap(prefs[BULB_POSITIONS]).mapNotNull { (mac, v) ->
            v.split(',').mapNotNull { it.toFloatOrNull() }.takeIf { it.size == 2 }?.let { mac to androidx.compose.ui.geometry.Offset(it[0], it[1]) }
        }.toMap()
    }.distinctUntilChanged()

    suspend fun setBulbPosition(mac: String, x: Float, y: Float) = store.edit {
        it[BULB_POSITIONS] = encodeMap(decodeMap(it[BULB_POSITIONS]) + (mac to "%.3f,%.3f".format(java.util.Locale.ROOT, x, y)))
    }

    /** Remote number key → light action (see LightShortcut.encode). Keys never set use the defaults. */
    val lightShortcuts: Flow<Map<Int, String>> = store.data.map { prefs ->
        decodeMap(prefs[LIGHT_SHORTCUTS]).mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }.toMap()
    }.distinctUntilChanged()

    suspend fun setLightShortcut(key: Int, action: String) = store.edit {
        it[LIGHT_SHORTCUTS] = encodeMap(decodeMap(it[LIGHT_SHORTCUTS]) + (key.toString() to action))
    }

    /** Call muting (iPhone over Bluetooth): on by default. */
    val callMuteEnabled: Flow<Boolean> = store.data.map { it[CALL_MUTE] ?: true }.distinctUntilChanged()
    suspend fun setCallMuteEnabled(on: Boolean) = store.edit { it[CALL_MUTE] = on }


    suspend fun setBulbRoom(mac: String, room: String) = store.edit { it[BULB_ROOMS] = encodeMap(decodeMap(it[BULB_ROOMS]) + (mac to room)) }
    suspend fun setBulbName(mac: String, name: String) = store.edit {
        val names = decodeMap(it[BULB_NAMES])
        it[BULB_NAMES] = encodeMap(if (name.isBlank()) names - mac else names + (mac to name.trim()))
    }

    private fun decodeMap(entries: Set<String>?): Map<String, String> =
        entries.orEmpty().mapNotNull { e -> e.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()

    private fun encodeMap(map: Map<String, String>): Set<String> = map.map { (k, v) -> "$k=$v" }.toSet()

    suspend fun setWeatherCity(city: String) {
        store.edit { it[WEATHER_CITY] = city.trim().ifEmpty { defaultCity } }
    }

    /** One-time dock setup the user asked for; applied once, after which the dock is theirs to change. */
    suspend fun applyDockPresetOnce() {
        store.edit { prefs ->
            if (prefs[DOCK_PRESET_APPLIED] == true) return@edit
            prefs[FAVORITES] = DOCK_PRESET.joinToString(",")
            prefs[DOCK_PRESET_APPLIED] = true
        }
    }

    suspend fun toggleFavorite(pkg: String) = editFavorites { favs ->
        if (pkg in favs) favs - pkg else favs + pkg
    }

    suspend fun moveFavorite(pkg: String, delta: Int) = editFavorites { favs ->
        val from = favs.indexOf(pkg)
        val to = from + delta
        if (from < 0 || to !in favs.indices) favs
        else favs.toMutableList().apply { add(to, removeAt(from)) }
    }

    suspend fun setHidden(pkg: String, hide: Boolean) {
        store.edit { prefs ->
            val current = prefs[HIDDEN].orEmpty()
            prefs[HIDDEN] = if (hide) current + pkg else current - pkg
        }
    }

    private suspend fun editFavorites(transform: (List<String>) -> List<String>) {
        store.edit { prefs ->
            val current = prefs[FAVORITES]?.split(',')?.filter { it.isNotBlank() }.orEmpty()
            prefs[FAVORITES] = transform(current).joinToString(",")
        }
    }

    private companion object {
        val FAVORITES = stringPreferencesKey("favorites")
        val HIDDEN = stringSetPreferencesKey("hidden")
        val WEATHER_CITY = stringPreferencesKey("weather_city")
        val DOCK_PRESET_APPLIED = booleanPreferencesKey("dock_preset_v1")
        val DOCK_PRESET = listOf(
            "com.spotify.tv.android",
            "com.google.android.youtube.tv",
            "com.beonetv.tod",
            "com.amazon.amazonvideo.livingroom",
        )
        val BULB_ROOMS = stringSetPreferencesKey("bulb_rooms")
        val BULB_NAMES = stringSetPreferencesKey("bulb_names")
        val BULB_POSITIONS = stringSetPreferencesKey("bulb_positions")
        val LIGHT_SHORTCUTS = stringSetPreferencesKey("light_shortcuts")
        val CALL_MUTE = booleanPreferencesKey("call_mute")
    }
}
