package dev.yusuf.falconlauncher.tv

/**
 * Keeps Bluetooth LE commands from different features from overlapping. This TV's Bluetooth stack aborts
 * (and restarts Bluetooth) when a connection request and an advertising command are in flight together, so
 * call muting doesn't advertise while the climate sensor is connecting, and the sensor waits for an
 * advertising request to be answered. Main thread only.
 */
object BleTraffic {
    /** Call muting asked to advertise and hasn't heard back yet. */
    @Volatile var advertisePending = false

    /** The climate sensor asked to connect and hasn't heard back yet. */
    @Volatile var sensorConnecting = false

    /**
     * Sensors the TV connects to. Call muting hears about every LE link, these included, and would otherwise
     * take a sensor for an iPhone and try to pair with it.
     */
    val sensors: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())
}
