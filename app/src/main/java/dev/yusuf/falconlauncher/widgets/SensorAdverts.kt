package dev.yusuf.falconlauncher.widgets

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What a Bluetooth temperature and humidity sensor said in one packet. Some sensors send temperature and
 * humidity in separate packets, so either may be missing.
 */
data class SensorValues(
    val temperature: Float? = null,
    val humidity: Float? = null,
    val battery: Int? = null,
    /** Xiaomi's product ID from a MiBeacon, e.g. 0x055B for the Mi Temperature and Humidity Monitor 2. */
    val productId: Int? = null,
    /** A MiBeacon whose values are encrypted with the sensor's bind key: read them over a connection instead. */
    val encrypted: Boolean = false,
) {
    val hasValues: Boolean get() = temperature != null || humidity != null

    /** Fills in what this packet lacks from [older] (temperature and humidity often arrive one at a time). */
    fun over(older: SensorValues) = copy(
        temperature = temperature ?: older.temperature,
        humidity = humidity ?: older.humidity,
        battery = battery ?: older.battery,
    )
}

/**
 * Reads the advertisement formats Xiaomi thermometers send:
 * - **MiBeacon** (service data 0xFE95): Xiaomi's own firmware. Older sensors (LYWSDCGQ) send plain values;
 *   newer ones (LYWSD03MMC and up) encrypt them, and those are read over a connection instead.
 * - **ATC1441 / pvvx custom** (0x181A) and **BTHome v2** (0xFCD2): the popular custom firmware for the
 *   LYWSD03MMC, flashed from a browser, which sends plain values a few times a minute.
 */
object SensorAdverts {
    const val MIBEACON = 0xFE95
    const val CUSTOM = 0x181A
    const val BTHOME = 0xFCD2

    /** Xiaomi models with the readable temperature and humidity characteristic (ebe0ccc1). */
    val GATT_MODELS = setOf(
        0x055B, // LYWSD03MMC, Mi Temperature and Humidity Monitor 2
        0x0387, // MHO-C401, the e-ink version
    )

    /** Xiaomi product IDs of temperature and humidity sensors (others, e.g. lamps, also send MiBeacons). */
    val CLIMATE_MODELS = GATT_MODELS + setOf(
        0x01AA, // LYWSDCGQ, the round one
        0x045B, // LYWSD02, the clock
        0x16E4, // LYWSD02MMC
        0x0347, // CGG1
        0x0B48, // CGG1, encrypted
        0x066F, // CGDK2
        0x0576, // CGD1
        0x2832, // MJWSD05MMC
    )

    private val CLIMATE_NAME = Regex("^(LYWSD|MHO-|MJWSD|CGG|CGDK|CGD1)")

    fun isClimateSensor(productId: Int?, name: String?): Boolean =
        productId in CLIMATE_MODELS || (name != null && CLIMATE_NAME.containsMatchIn(name))

    fun parse(uuid16: Int, d: ByteArray): SensorValues? = runCatching {
        when (uuid16) {
            CUSTOM -> parseCustom(d)
            BTHOME -> parseBtHome(d)
            MIBEACON -> parseMiBeacon(d)
            else -> null
        }
    }.getOrNull()

    /**
     * The notification of characteristic ebe0ccc1 on Xiaomi's own firmware: temperature (0.01 °C),
     * humidity (%) and battery voltage (mV).
     */
    fun parseGatt(d: ByteArray): SensorValues? {
        if (d.size < 3) return null
        val millivolts = if (d.size >= 5) u16le(d, 3) else null
        return SensorValues(
            temperature = s16le(d, 0) / 100f,
            humidity = u8(d, 2).toFloat(),
            // A CR2032 runs from about 3.0 V when new down to 2.1 V when empty.
            battery = millivolts?.let { ((it - 2100) / 9).coerceIn(0, 100) },
        ).takeIf { it.plausible() }
    }

    private fun parseCustom(d: ByteArray): SensorValues? = when (d.size) {
        // ATC1441: MAC, temperature (0.1 °C, big-endian), humidity %, battery %, battery mV, counter.
        13 -> SensorValues(temperature = s16be(d, 6) / 10f, humidity = u8(d, 8).toFloat(), battery = u8(d, 9))
        // pvvx: MAC, temperature (0.01 °C), humidity (0.01 %), battery mV, battery %, counter, flags.
        15 -> SensorValues(temperature = s16le(d, 6) / 100f, humidity = u16le(d, 8) / 100f, battery = u8(d, 12))
        else -> null
    }?.takeIf { it.plausible() }

    private fun parseBtHome(d: ByteArray): SensorValues? {
        if (d.isEmpty()) return null
        val info = u8(d, 0)
        if (info and 0x01 != 0 || info shr 5 != 2) return null // encrypted, or not version 2
        var values = SensorValues()
        var i = 1
        while (i < d.size) {
            val id = u8(d, i)
            val length = BTHOME_LENGTHS[id] ?: break // an object we can't skip: stop here
            val p = i + 1
            if (p + length > d.size) break
            values = when (id) {
                0x01 -> values.copy(battery = u8(d, p))
                0x02 -> values.copy(temperature = s16le(d, p) / 100f)
                0x03 -> values.copy(humidity = u16le(d, p) / 100f)
                0x2E -> values.copy(humidity = u8(d, p).toFloat())
                0x45 -> values.copy(temperature = s16le(d, p) / 10f)
                else -> values
            }
            i = p + length
        }
        return values.takeIf { it.hasValues && it.plausible() }
    }

    private fun parseMiBeacon(d: ByteArray): SensorValues? {
        if (d.size < 5) return null
        val control = u16le(d, 0)
        val productId = u16le(d, 2)
        val encrypted = control and 0x08 != 0
        var i = 5 // after frame control, product ID and frame counter
        if (control and 0x10 != 0) i += 6 // MAC address
        if (control and 0x20 != 0) { // capability, plus I/O capability when flagged
            if (i >= d.size) return SensorValues(productId = productId, encrypted = encrypted)
            val capability = u8(d, i)
            i += if (capability and 0x20 != 0) 3 else 1
        }
        if (encrypted || control and 0x40 == 0) return SensorValues(productId = productId, encrypted = encrypted)
        var values = SensorValues(productId = productId)
        while (i + 3 <= d.size) {
            val id = u16le(d, i)
            val length = u8(d, i + 2)
            val p = i + 3
            if (p + length > d.size) break
            values = when {
                id == 0x1004 && length >= 2 -> values.copy(temperature = s16le(d, p) / 10f)
                id == 0x1006 && length >= 2 -> values.copy(humidity = u16le(d, p) / 10f)
                id == 0x100D && length >= 4 -> values.copy(temperature = s16le(d, p) / 10f, humidity = u16le(d, p + 2) / 10f)
                id == 0x100A && length >= 1 -> values.copy(battery = u8(d, p))
                id == 0x4C01 && length >= 4 -> values.copy(temperature = f32le(d, p))
                id == 0x4C02 && length >= 1 -> values.copy(humidity = u8(d, p).toFloat())
                id == 0x4803 && length >= 1 -> values.copy(battery = u8(d, p))
                else -> values
            }
            i = p + length
        }
        return values.takeIf { !it.hasValues || it.plausible() }
    }

    /** Rejects garbage from a misread packet. */
    private fun SensorValues.plausible() =
        (temperature == null || temperature in -40f..85f) && (humidity == null || humidity in 0f..100f)

    /** Payload size of each BTHome v2 object ID, so unknown values can be skipped. */
    private val BTHOME_LENGTHS: Map<Int, Int> = buildMap {
        listOf(0x00, 0x01, 0x09, 0x0F, 0x10, 0x11, 0x2E, 0x2F, 0x3A, 0x46).forEach { put(it, 1) }
        (0x15..0x2D).forEach { put(it, 1) } // binary sensors
        listOf(0x02, 0x03, 0x06, 0x07, 0x08, 0x0C, 0x0D, 0x0E, 0x12, 0x13, 0x14, 0x3D, 0x3F, 0x40, 0x41, 0x43, 0x44, 0x45, 0x47, 0x48)
            .forEach { put(it, 2) }
        listOf(0x04, 0x05, 0x0A, 0x0B, 0x42).forEach { put(it, 3) }
        put(0x3E, 4)
    }

    private fun u8(d: ByteArray, i: Int) = d[i].toInt() and 0xFF
    private fun u16le(d: ByteArray, i: Int) = u8(d, i) or (u8(d, i + 1) shl 8)
    private fun s16le(d: ByteArray, i: Int) = u16le(d, i).toShort().toInt()
    private fun s16be(d: ByteArray, i: Int) = ((u8(d, i) shl 8) or u8(d, i + 1)).toShort().toInt()
    private fun f32le(d: ByteArray, i: Int) = ByteBuffer.wrap(d, i, 4).order(ByteOrder.LITTLE_ENDIAN).float
}
