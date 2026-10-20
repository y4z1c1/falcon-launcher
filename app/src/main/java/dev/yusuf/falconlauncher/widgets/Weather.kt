package dev.yusuf.falconlauncher.widgets

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

data class Weather(
    val place: String,
    val temperature: Int,
    val high: Int,
    val low: Int,
    val code: Int,
    val isDay: Boolean,
) {
    /** WMO weather code as a short description and an emoji. */
    val summary: Pair<String, String>
        get() = when (code) {
            0 -> "Clear" to if (isDay) "☀️" else "🌙"
            1, 2 -> "Partly cloudy" to if (isDay) "⛅" else "☁️"
            3 -> "Cloudy" to "☁️"
            45, 48 -> "Fog" to "🌫️"
            in 51..57 -> "Drizzle" to "🌦️"
            in 61..67, in 80..82 -> "Rain" to "🌧️"
            in 71..77, 85, 86 -> "Snow" to "❄️"
            in 95..99 -> "Thunderstorm" to "⛈️"
            else -> "—" to "🌡️"
        }
}

/** Current weather from Open-Meteo (free, no API key). */
class WeatherSource {
    private val _state = MutableStateFlow<Weather?>(null)
    val state: StateFlow<Weather?> = _state.asStateFlow()

    private var cachedCity: String? = null
    private var cachedPlace: Triple<String, Double, Double>? = null

    /** Fetches the weather for [city]; returns false on failure (e.g. Wi-Fi not back yet after sleep). */
    suspend fun refresh(city: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val (place, lat, lon) = geocode(city) ?: error("Unknown city $city")
            val json = getJson(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code,is_day" +
                    "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1"
            )
            val current = json.getJSONObject("current")
            val daily = json.getJSONObject("daily")
            _state.value = Weather(
                place = place,
                temperature = current.getDouble("temperature_2m").roundToInt(),
                high = daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(),
                low = daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(),
                code = current.getInt("weather_code"),
                isDay = current.getInt("is_day") == 1,
            )
        }.onFailure { Log.w(TAG, "Weather refresh failed", it) }.isSuccess
    }

    private fun geocode(city: String): Triple<String, Double, Double>? {
        // "Label@lat,lon" gives an exact spot (Open-Meteo's search doesn't know neighborhoods).
        city.split('@').takeIf { it.size == 2 }?.let { (label, coords) ->
            coords.split(',').mapNotNull { it.trim().toDoubleOrNull() }.takeIf { it.size == 2 }?.let { return Triple(label.trim(), it[0], it[1]) }
        }
        if (city == cachedCity) cachedPlace?.let { return it }
        val name = URLEncoder.encode(city.substringBefore(',').trim(), "UTF-8")
        val results = getJson("https://geocoding-api.open-meteo.com/v1/search?name=$name&count=1&format=json")
            .optJSONArray("results") ?: return null
        val first = results.getJSONObject(0)
        val place = Triple(first.getString("name"), first.getDouble("latitude"), first.getDouble("longitude"))
        cachedCity = city
        cachedPlace = place
        return place
    }

    private fun getJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        return try {
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TAG = "Weather"
    }
}
