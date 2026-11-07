package dev.yusuf.falconlauncher.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorAdvertsTest {
    private fun hex(s: String) = s.split(' ').map { it.toInt(16).toByte() }.toByteArray()

    @Test fun pvvxCustomFormat() {
        val v = SensorAdverts.parse(SensorAdverts.CUSTOM, hex("33 22 11 38 C1 A4 29 09 F2 12 86 0B 58 07 05"))!!
        assertEquals(23.45f, v.temperature!!, 0.001f)
        assertEquals(48.5f, v.humidity!!, 0.001f)
        assertEquals(88, v.battery)
    }

    @Test fun atc1441Format() {
        val v = SensorAdverts.parse(SensorAdverts.CUSTOM, hex("A4 C1 38 11 22 33 00 EA 30 55 0B 86 01"))!!
        assertEquals(23.4f, v.temperature!!, 0.001f)
        assertEquals(48f, v.humidity!!, 0.001f)
        assertEquals(85, v.battery)
    }

    @Test fun btHomeV2() {
        val v = SensorAdverts.parse(SensorAdverts.BTHOME, hex("40 00 01 01 5C 02 CA 09 03 BF 13"))!!
        assertEquals(25.06f, v.temperature!!, 0.001f)
        assertEquals(50.55f, v.humidity!!, 0.001f)
        assertEquals(92, v.battery)
    }

    @Test fun btHomeEncryptedIsSkipped() {
        assertNull(SensorAdverts.parse(SensorAdverts.BTHOME, hex("41 00 01 01 5C 02 CA 09")))
    }

    @Test fun plainMiBeacon() {
        // LYWSDCGQ: temperature and humidity object (0x100D).
        val v = SensorAdverts.parse(SensorAdverts.MIBEACON, hex("50 20 AA 01 DA 21 76 E0 A8 65 4C 0D 10 04 D2 00 AF 01"))!!
        assertEquals(0x01AA, v.productId)
        assertFalse(v.encrypted)
        assertEquals(21.0f, v.temperature!!, 0.001f)
        assertEquals(43.1f, v.humidity!!, 0.001f)
    }

    @Test fun plainMiBeaconTemperatureOnlyMerges() {
        val t = SensorAdverts.parse(SensorAdverts.MIBEACON, hex("50 20 AA 01 DB 21 76 E0 A8 65 4C 04 10 02 E1 00"))!!
        val h = SensorAdverts.parse(SensorAdverts.MIBEACON, hex("50 20 AA 01 DC 21 76 E0 A8 65 4C 06 10 02 B4 01"))!!
        val merged = h.over(t)
        assertEquals(22.5f, merged.temperature!!, 0.001f)
        assertEquals(43.6f, merged.humidity!!, 0.001f)
    }

    @Test fun encryptedMiBeacon() {
        // LYWSD03MMC on Xiaomi's firmware.
        val v = SensorAdverts.parse(SensorAdverts.MIBEACON, hex("58 58 5B 05 50 33 22 11 38 C1 A4 1A 2B 3C 4D 5E 6F 00 00 00 11 22 33 44"))!!
        assertTrue(v.encrypted)
        assertEquals(0x055B, v.productId)
        assertFalse(v.hasValues)
        assertTrue(SensorAdverts.isClimateSensor(v.productId, null))
    }

    @Test fun otherXiaomiDevicesAreNotSensors() {
        assertFalse(SensorAdverts.isClimateSensor(0x0153, "yeelink.light"))
        assertTrue(SensorAdverts.isClimateSensor(null, "LYWSD03MMC"))
    }

    @Test fun gattNotification() {
        val v = SensorAdverts.parseGatt(hex("4C 09 2F 86 0B"))!!
        assertEquals(23.8f, v.temperature!!, 0.001f)
        assertEquals(47f, v.humidity!!, 0.001f)
        assertEquals(94, v.battery)
    }

    @Test fun negativeTemperature() {
        val v = SensorAdverts.parseGatt(hex("06 FF 50 B8 0B"))!! // -2.50 °C
        assertEquals(-2.5f, v.temperature!!, 0.001f)
    }

    @Test fun garbageIsRejected() {
        assertNull(SensorAdverts.parse(SensorAdverts.CUSTOM, hex("00 01 02")))
        assertNull(SensorAdverts.parseGatt(hex("FF 7F 50")))
    }
}
