package dev.yusuf.falconlauncher.data

import dev.yusuf.falconlauncher.widgets.Room
import dev.yusuf.falconlauncher.widgets.WizBulb

/** What a remote number key does to the lights. */
sealed class LightShortcut {
    data object None : LightShortcut()
    data object AllLights : LightShortcut()
    data class Lamp(val mac: String) : LightShortcut()
    data class InRoom(val room: Room) : LightShortcut()

    fun encode(): String = when (this) {
        None -> "none"
        AllLights -> "all"
        is Lamp -> "lamp:$mac"
        is InRoom -> "room:${room.name}"
    }

    companion object {
        /** Number keys in the order shown on the settings page. */
        val KEYS = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 0)

        fun decode(s: String): LightShortcut = when {
            s == "all" -> AllLights
            s.startsWith("lamp:") -> Lamp(s.removePrefix("lamp:"))
            s.startsWith("room:") -> Room.entries.firstOrNull { it.name == s.removePrefix("room:") }?.let { InRoom(it) } ?: None
            else -> None
        }

        /**
         * The saved assignments, with defaults for keys never set: 1–5 switch the lamps one by one
         * (ordered by address, which stays stable), 0 switches every light, the rest do nothing.
         */
        fun resolve(saved: Map<Int, String>, bulbs: List<WizBulb>): Map<Int, LightShortcut> {
            val lamps = bulbs.sortedBy { it.ip.substringAfterLast('.').toIntOrNull() ?: 0 }
            return KEYS.associateWith { key ->
                saved[key]?.let(::decode) ?: when {
                    key == 0 -> AllLights
                    key in 1..lamps.size -> Lamp(lamps[key - 1].mac)
                    else -> None
                }
            }
        }
    }
}
