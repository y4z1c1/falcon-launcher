package dev.yusuf.falconlauncher.widgets

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import dev.yusuf.falconlauncher.tv.BleTraffic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** The latest reading from the room's thermometer. */
data class IndoorClimate(
    val temperature: Float,
    val humidity: Int,
    /** Battery %, when the sensor reports it. */
    val battery: Int?,
    /** When it was read (System.currentTimeMillis). */
    val at: Long,
) {
    private val age: Long get() = System.currentTimeMillis() - at

    /** Recent enough to show at all (the room changes slowly; after a night in standby it's still a fair guess). */
    val isShown: Boolean get() = age < ClimateSensor.HIDE_MS

    /** Read in the last hour; older readings are shown dimmed until a new one comes in. */
    val isFresh: Boolean get() = age < ClimateSensor.FRESH_MS
}

/**
 * Reads a Xiaomi Bluetooth thermometer (e.g. the Mi Temperature and Humidity Monitor 2, LYWSD03MMC).
 *
 * The first sensor found is remembered and the only one read from then on. Sensors that broadcast their
 * values (custom firmware, older models) are simply listened to; Xiaomi's own firmware encrypts its
 * broadcasts, so for those the TV connects briefly every [GATT_INTERVAL_MS], takes one reading and lets go,
 * which keeps the coin cell going for months. Nothing is read while the TV is in standby.
 */
@SuppressLint("MissingPermission")
class ClimateSensor(private val context: Context) {
    enum class Status { OFF, NO_PERMISSION, SEARCHING, CONNECTING, OK, UNSUPPORTED }

    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val prefs = context.getSharedPreferences("climate", Context.MODE_PRIVATE)

    private val _climate = MutableStateFlow(loadLast())
    val climate: StateFlow<IndoorClimate?> = _climate.asStateFlow()
    private val _status = MutableStateFlow(Status.OFF)
    val status: StateFlow<Status> = _status.asStateFlow()

    private var running = false
    /** A scan or connection is under way; the next cycle is scheduled when it ends. */
    private var busy = false
    private var busySince = 0L
    private var scanning = false
    private var gatt: BluetoothGatt? = null
    private var connectFailures = 0
    /** Values gathered per sensor until both temperature and humidity are known. */
    private val partial = mutableMapOf<String, SensorValues>()
    /** Sensors with no readable values (encrypted, and without the readable characteristic). */
    private val unsupported = mutableSetOf<String>()

    fun hasPermissions(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Starts reading. Safe to call again, e.g. once Bluetooth permission is granted. */
    fun start(): Boolean = main.post {
        if (!running) {
            ContextCompat.registerReceiver(context, screenOn, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_EXPORTED)
            main.postDelayed(watchdog, WATCHDOG_MS)
        }
        running = true
        if (!busy) cycle.run()
    }

    /** The TV came out of standby: read right away (after a moment for Bluetooth to settle). */
    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            Log.d(TAG, "Screen on")
            main.postDelayed({ refreshIfStale() }, 3_000)
        }
    }

    /**
     * Every [WATCHDOG_MS]: a round that never finished (a Bluetooth callback that never came) is ended,
     * and a round that is overdue is started, so readings never stop for good.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            main.postDelayed(this, WATCHDOG_MS)
            if (!running) return
            val now = SystemClock.uptimeMillis()
            if (busy && now - busySince > STUCK_MS) {
                Log.w(TAG, "Round stuck (scanning=$scanning, gatt=${gatt != null}, connecting=${BleTraffic.sensorConnecting}, advertising=${BleTraffic.advertisePending}); starting over")
                stopScan()
                main.removeCallbacks(connectTimeout)
                BleTraffic.sensorConnecting = false
                gatt?.let { runCatching { it.disconnect() }; it.close() }
                gatt = null
                next(0)
            } else if (!busy && !main.hasCallbacks(cycle)) {
                Log.w(TAG, "No round scheduled; starting one")
                cycle.run()
            }
        }
    }

    /** Reads now if the last reading is getting old, e.g. when the home screen opens after standby. */
    fun refreshIfStale(): Boolean = main.post {
        val last = _climate.value
        if (running && !busy && (last == null || System.currentTimeMillis() - last.at > REFRESH_ON_OPEN_MS)) {
            Log.d(TAG, "Reading now (last one is old)")
            cycle.run()
        }
    }

    private val cycle = Runnable {
        main.removeCallbacksAndMessages(CYCLE)
        if (!running) return@Runnable
        if (!hasPermissions()) {
            _status.value = Status.NO_PERMISSION
            return@Runnable // start() runs again once permission is granted
        }
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled || !power.isInteractive) {
            Log.d(TAG, "Not now (bluetooth=${adapter?.isEnabled}, screen on=${power.isInteractive})")
            return@Runnable next(RETRY_MS)
        }
        busy = true
        busySince = SystemClock.uptimeMillis()
        val address = prefs.getString(KEY_ADDRESS, null)
        if (address != null && prefs.getBoolean(KEY_CONNECT, false) && connectFailures < MAX_CONNECT_FAILURES) {
            connect(address)
        } else {
            scan()
        }
    }

    /** Ends this round and schedules the next one in [delayMs]. */
    private fun next(delayMs: Long) {
        busy = false
        main.removeCallbacksAndMessages(CYCLE)
        if (running) main.postAtTime(cycle, CYCLE, SystemClock.uptimeMillis() + delayMs)
        Log.d(TAG, "Next round in ${delayMs / 1000}s")
    }

    // --- Listening for broadcasts ---

    private fun scan() {
        val scanner = manager?.adapter?.bluetoothLeScanner ?: return next(RETRY_MS)
        val filters = listOf(SensorAdverts.MIBEACON, SensorAdverts.CUSTOM, SensorAdverts.BTHOME).map {
            ScanFilter.Builder().setServiceData(ParcelUuid(uuid16(it)), ByteArray(0)).build()
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        val started = runCatching { scanner.startScan(filters, settings, scanCallback) }
            .onFailure { Log.w(TAG, "Scan failed", it) }
            .isSuccess
        if (!started) return next(RETRY_MS)
        Log.d(TAG, "Scanning")
        scanning = true
        partial.clear()
        if (_climate.value?.isFresh != true && _status.value != Status.UNSUPPORTED) _status.value = Status.SEARCHING
        main.postDelayed(scanTimeout, SCAN_MS)
    }

    private fun stopScan() {
        main.removeCallbacks(scanTimeout)
        if (!scanning) return
        scanning = false
        runCatching { manager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private val scanTimeout = Runnable {
        stopScan()
        Log.d(TAG, "No sensor heard")
        next(SEARCH_INTERVAL_MS)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post { onAdvert(result) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "Scan failed: $errorCode")
            main.post {
                if (!scanning) return@post
                scanning = false
                main.removeCallbacks(scanTimeout)
                next(RETRY_MS)
            }
        }
    }

    private fun onAdvert(result: ScanResult) {
        if (!scanning) return
        val address = result.device.address
        val saved = prefs.getString(KEY_ADDRESS, null)
        if (address in unsupported || (saved != null && address != saved)) return
        val record = result.scanRecord ?: return
        for ((uuid, data) in record.serviceData.orEmpty()) {
            val values = SensorAdverts.parse(uuid.uuid.shortId(), data) ?: continue
            if (values.productId != null && !SensorAdverts.isClimateSensor(values.productId, record.deviceName)) continue
            if (values.hasValues) {
                val merged = values.over(partial[address] ?: SensorValues())
                partial[address] = merged
                if (merged.temperature == null || merged.humidity == null) continue
                stopScan()
                remember(address, connect = false)
                publish(merged)
                next(ADVERT_INTERVAL_MS)
                return
            }
            if (values.encrypted || values.productId in SensorAdverts.GATT_MODELS) {
                // Xiaomi's own firmware: the values are only readable over a connection.
                Log.d(TAG, "Found ${record.deviceName ?: "sensor"} $address (product ${values.productId?.let { Integer.toHexString(it) }}); connecting")
                stopScan()
                connect(address)
                return
            }
        }
    }

    // --- Reading over a connection ---

    private fun connect(address: String) {
        // Never while call muting's advertising request is out: the TV's Bluetooth would crash.
        if (BleTraffic.advertisePending) {
            Log.d(TAG, "Waiting for call muting's advertising")
            main.postDelayed({ if (running && gatt == null) connect(address) }, 1_000)
            return
        }
        val device = runCatching { manager?.adapter?.getRemoteDevice(address) }.getOrNull()
            ?: return finishConnection(ok = false)
        Log.d(TAG, "Connecting to $address (misses so far: $connectFailures)")
        if (_climate.value?.isFresh != true) _status.value = Status.CONNECTING
        BleTraffic.sensors += address
        BleTraffic.sensorConnecting = true
        main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
        gatt = runCatching { device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE) }
            .onFailure { Log.w(TAG, "Connect failed", it) }
            .getOrNull()
        if (gatt == null) finishConnection(ok = false)
    }

    private val connectTimeout = Runnable {
        Log.d(TAG, "No reading in time")
        finishConnection(ok = false)
    }

    private fun finishConnection(ok: Boolean) {
        main.removeCallbacks(connectTimeout)
        BleTraffic.sensorConnecting = false
        gatt?.let {
            runCatching { it.disconnect() }
            it.close()
        }
        gatt = null
        if (ok) {
            connectFailures = 0
            next(GATT_INTERVAL_MS)
        } else {
            // After a few misses in a row (out of range, battery out), look for it by its broadcasts again.
            connectFailures++
            next(if (connectFailures < MAX_CONNECT_FAILURES) RETRY_MS else SEARCH_INTERVAL_MS)
            if (connectFailures > MAX_CONNECT_FAILURES) connectFailures = MAX_CONNECT_FAILURES
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (gatt !== g) return@post
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "Connected; looking for its values")
                        BleTraffic.sensorConnecting = false
                        g.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d(TAG, "Disconnected ($status)")
                        finishConnection(ok = false)
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val data = g.getService(SERVICE)?.getCharacteristic(DATA)
            if (data == null) {
                main.post {
                    if (gatt !== g) return@post
                    val address = g.device.address
                    Log.w(TAG, "$address has no readable values; its broadcasts need a bind key")
                    unsupported += address
                    if (prefs.getString(KEY_ADDRESS, null) == address) prefs.edit { remove(KEY_ADDRESS); remove(KEY_CONNECT) }
                    _status.value = Status.UNSUPPORTED
                    connectFailures = 0
                    finishConnection(ok = false)
                }
                return
            }
            Log.d(TAG, "Subscribing")
            g.setCharacteristicNotification(data, true)
            val cccd = data.getDescriptor(CCCD) ?: return
            val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, enable)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = enable
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }

        /** Subscribed: also ask for the value once, so there's no wait for the sensor's next notification. */
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "Subscribed ($status)")
            val data = descriptor.characteristic ?: return
            main.post { if (gatt === g) runCatching { g.readCharacteristic(data) } }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) onReading(g, characteristic, value)
        }

        @Deprecated("Pre-Android 13 callback")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            if (status == BluetoothGatt.GATT_SUCCESS) onReading(g, characteristic, characteristic.value ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) =
            onReading(g, characteristic, value)

        @Deprecated("Pre-Android 13 callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) =
            onReading(g, characteristic, characteristic.value ?: ByteArray(0))
    }

    /** The sensor sends a reading every few seconds once subscribed; one is enough. */
    private fun onReading(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid != DATA) return
        val values = SensorAdverts.parseGatt(value) ?: return
        main.post {
            if (gatt !== g) return@post
            remember(g.device.address, connect = true)
            publish(values)
            finishConnection(ok = true)
        }
    }

    // --- Results ---

    private fun remember(address: String, connect: Boolean) {
        if (prefs.getString(KEY_ADDRESS, null) == address && prefs.getBoolean(KEY_CONNECT, false) == connect) return
        Log.d(TAG, "Using sensor $address (${if (connect) "connect" else "broadcasts"})")
        prefs.edit { putString(KEY_ADDRESS, address); putBoolean(KEY_CONNECT, connect) }
    }

    private fun publish(values: SensorValues) {
        val reading = IndoorClimate(
            temperature = values.temperature ?: return,
            humidity = Math.round(values.humidity ?: return),
            battery = values.battery,
            at = System.currentTimeMillis(),
        )
        Log.d(TAG, "%.2f °C, %d %%, battery %s".format(reading.temperature, reading.humidity, reading.battery ?: "?"))
        _climate.value = reading
        _status.value = Status.OK
        prefs.edit {
            putFloat(KEY_TEMPERATURE, reading.temperature)
            putInt(KEY_HUMIDITY, reading.humidity)
            putInt(KEY_BATTERY, reading.battery ?: -1)
            putLong(KEY_AT, reading.at)
        }
    }

    /** The last reading, so the card isn't empty after the app restarts. */
    private fun loadLast(): IndoorClimate? {
        if (!prefs.contains(KEY_AT)) return null
        return IndoorClimate(
            temperature = prefs.getFloat(KEY_TEMPERATURE, 0f),
            humidity = prefs.getInt(KEY_HUMIDITY, 0),
            battery = prefs.getInt(KEY_BATTERY, -1).takeIf { it >= 0 },
            at = prefs.getLong(KEY_AT, 0),
        )
    }

    private fun UUID.shortId(): Int = (mostSignificantBits ushr 32).toInt() and 0xFFFF

    companion object {
        private const val TAG = "Climate"
        private val CYCLE = Any()

        private const val KEY_ADDRESS = "address"
        /** The remembered sensor is read over a connection (Xiaomi's firmware) rather than its broadcasts. */
        private const val KEY_CONNECT = "connect"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_HUMIDITY = "humidity"
        private const val KEY_BATTERY = "battery"
        private const val KEY_AT = "at"

        private const val SCAN_MS = 20_000L
        private const val CONNECT_TIMEOUT_MS = 45_000L
        private const val WATCHDOG_MS = 60_000L
        /** Longer than any round can take (scan, or wait for advertising plus connect timeout). */
        private const val STUCK_MS = 90_000L
        private const val MAX_CONNECT_FAILURES = 3
        private const val RETRY_MS = 60_000L
        private const val ADVERT_INTERVAL_MS = 2 * 60_000L
        private const val GATT_INTERVAL_MS = 10 * 60_000L
        private const val SEARCH_INTERVAL_MS = 3 * 60_000L
        private const val REFRESH_ON_OPEN_MS = 5 * 60_000L
        const val FRESH_MS = 60 * 60_000L
        const val HIDE_MS = 12 * 60 * 60_000L

        // Xiaomi's temperature and humidity service and its notify characteristic.
        private val SERVICE: UUID = UUID.fromString("ebe0ccb0-7a0a-4b0c-8a1a-6ff2997da3a6")
        private val DATA: UUID = UUID.fromString("ebe0ccc1-7a0a-4b0c-8a1a-6ff2997da3a6")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private fun uuid16(id: Int): UUID = UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(id))

        val PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            emptyArray()
        }
    }
}
