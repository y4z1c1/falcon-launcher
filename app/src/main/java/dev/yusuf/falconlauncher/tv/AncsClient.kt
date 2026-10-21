package dev.yusuf.falconlauncher.tv

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Hears the iPhone ring through Apple's notification sharing over Bluetooth LE (ANCS), the protocol
 * smartwatches and fitness bands use. The TV advertises itself as an ANCS accessory; once the iPhone pairs
 * with it (Settings › Bluetooth) and shares system notifications, every notification's arrival and removal
 * is reported, including the dedicated "Incoming Call" category, the moment the phone starts ringing.
 *
 * Only notification events (category + id) are read, never their content.
 */
@SuppressLint("MissingPermission")
class AncsClient(private val context: Context, private val onEvent: (AncsEvent) -> Unit) {
    enum class State { OFF, NO_PERMISSION, WAITING, CONNECTING, ACTIVE }

    /** One ANCS notification event: [event] 0 added / 1 modified / 2 removed, [category] e.g. 1 incoming call, 12 active call. */
    data class AncsEvent(val event: Int, val category: Int, val uid: Int)

    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val prefs = context.getSharedPreferences("ancs", Context.MODE_PRIVATE)
    private var server: BluetoothGattServer? = null
    private var gatt: BluetoothGatt? = null
    private var advertising = false
    private var started = false
    /** Peers that turned out not to be iPhones (e.g. the TV's own Bluetooth remote); never tried again. */
    private val ignored = mutableSetOf<String>()
    private var subscribeAttempts = 0
    /** Call muting is on: keep the link alive (watchdog, Bluetooth restarts). */
    private var wanted = false
    private var connectingSince = 0L

    private val _state = MutableStateFlow(State.OFF)
    val state: StateFlow<State> = _state.asStateFlow()

    fun hasPermissions(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Starts advertising and reconnects to the paired iPhone. Safe to call again (e.g. after permissions). */
    fun start(): Boolean = main.post {
        wanted = true
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, WATCHDOG_MS)
        if (!hasPermissions()) {
            _state.value = State.NO_PERMISSION
            return@post
        }
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            _state.value = State.OFF
            return@post
        }
        if (!started) {
            started = true
            ContextCompat.registerReceiver(context, bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
            ContextCompat.registerReceiver(context, adapterReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
            server = runCatching { manager.openGattServer(context, serverCallback) }.getOrNull()
        }
        if (_state.value != State.ACTIVE && _state.value != State.CONNECTING) _state.value = State.WAITING
        advertise()
        // The iPhone we paired with before: reconnect to it directly.
        reconnectPhone()
    }

    fun stop(): Boolean = main.post {
        wanted = false
        main.removeCallbacks(watchdog)
        runCatching { manager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        advertising = false
        gatt?.close()
        gatt = null
        _state.value = State.OFF
    }

    private fun advertise() {
        if (advertising) return
        val advertiser = manager?.adapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // Soliciting the ANCS service is what makes the iPhone offer to share notifications with us.
        val data = AdvertiseData.Builder().addServiceSolicitationUuid(ParcelUuid(ANCS_SERVICE)).build()
        val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        runCatching { advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback) }
            .onFailure { Log.w(TAG, "Advertising failed", it) }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            advertising = true
            Log.d(TAG, "Advertising as an ANCS accessory")
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            Log.w(TAG, "Advertising failed: $errorCode")
        }
    }

    /** The iPhone connects to our advertisement; we then talk to its ANCS service over the same link. */
    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.d(TAG, "Peer ${device.address} state $newState")
            if (newState != BluetoothProfile.STATE_CONNECTED || device.address in ignored) return
            main.post { if (gatt == null) connect(device, autoConnect = false) }
        }
    }

    /** Background-connects to the iPhone we paired with; Android completes it whenever the phone is in range. */
    private fun reconnectPhone() {
        if (gatt != null) return
        val address = prefs.getString(KEY_PHONE, null) ?: return
        runCatching { manager?.adapter?.getRemoteDevice(address) }.getOrNull()?.let { connect(it, autoConnect = true) }
    }

    /**
     * Every [WATCHDOG_MS] while call muting is on: advertise again if it stopped, reconnect to the iPhone,
     * and start over a link that hangs mid-connect (e.g. after the TV's standby).
     */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!wanted) return
            main.postDelayed(this, WATCHDOG_MS)
            val adapter = manager?.adapter
            if (!hasPermissions() || adapter == null || !adapter.isEnabled) return
            if (!advertising) advertise()
            val g = gatt
            when {
                _state.value == State.ACTIVE -> Unit
                g == null -> reconnectPhone()
                _state.value == State.CONNECTING && SystemClock.uptimeMillis() - connectingSince > STUCK_MS &&
                    g.device.address == prefs.getString(KEY_PHONE, null) && isConnected(g.device) -> {
                    // Connected but never got to receiving notifications: start the link over.
                    Log.d(TAG, "Link stuck; reconnecting")
                    g.close()
                    gatt = null
                    reconnectPhone()
                }
            }
        }
    }

    private fun isConnected(device: BluetoothDevice) =
        manager?.getConnectionState(device, BluetoothProfile.GATT) == BluetoothProfile.STATE_CONNECTED

    /** Bluetooth turned off and on again (standby, reboot, settings): set everything up afresh. */
    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> main.post {
                    advertising = false
                    gatt?.close()
                    gatt = null
                    server?.close()
                    server = null
                    _state.value = State.OFF
                }
                BluetoothAdapter.STATE_ON -> main.postDelayed({
                    if (!wanted) return@postDelayed
                    if (server == null) server = runCatching { manager?.openGattServer(context, serverCallback) }.getOrNull()
                    start()
                }, 1_500)
            }
        }
    }

    private fun connect(device: BluetoothDevice, autoConnect: Boolean) {
        _state.value = State.CONNECTING
        connectingSince = SystemClock.uptimeMillis()
        gatt = device.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                main.post { g.discoverServices() }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "iPhone disconnected ($status)")
                main.post {
                    g.close()
                    if (gatt === g) gatt = null
                    if (!wanted) return@post
                    _state.value = State.WAITING
                    // Keep waiting: the paired iPhone reconnects when it's back in range.
                    reconnectPhone()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val source = g.getService(ANCS_SERVICE)?.getCharacteristic(NOTIFICATION_SOURCE)
            if (source == null) {
                if (g.device.address == prefs.getString(KEY_PHONE, null)) {
                    // Our iPhone: ANCS appears once the link is encrypted; look again shortly.
                    main.postDelayed({ if (gatt === g) g.discoverServices() }, 2_000)
                    return
                }
                if (g.device.bondState == BluetoothDevice.BOND_BONDED) {
                    // Already paired yet no ANCS: not an iPhone (the TV remote, a keyboard...). Let it go.
                    Log.d(TAG, "Ignoring ${g.device.address}: no ANCS")
                    main.post { drop(g) }
                    return
                }
                // ANCS only shows up once the link is paired and encrypted.
                Log.d(TAG, "No ANCS yet; pairing")
                if (g.device.bondState == BluetoothDevice.BOND_NONE) g.device.createBond()
                return
            }
            subscribe(g, source)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    Log.d(TAG, "Receiving iPhone notifications")
                    subscribeAttempts = 0
                    prefs.edit().putString(KEY_PHONE, g.device.address).apply()
                    _state.value = State.ACTIVE
                }
                BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION, BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION -> {
                    Log.d(TAG, "Pairing needed ($status)")
                    if (g.device.bondState == BluetoothDevice.BOND_NONE) g.device.createBond()
                }
                else -> {
                    // Often a busy link right after (re)connecting: retry, then start the link over.
                    Log.w(TAG, "Subscribe failed: $status")
                    if (++subscribeAttempts <= 3) {
                        main.postDelayed({ if (gatt === g) g.discoverServices() }, 1_000L * subscribeAttempts)
                    } else {
                        subscribeAttempts = 0
                        main.post { g.disconnect() }
                    }
                }
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) =
            handle(characteristic, value)

        @Deprecated("Pre-Android 13 callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) =
            handle(characteristic, characteristic.value ?: ByteArray(0))
    }

    private fun drop(g: BluetoothGatt) {
        ignored += g.device.address
        g.close()
        if (gatt === g) gatt = null
        _state.value = State.WAITING
    }

    private fun subscribe(g: BluetoothGatt, source: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(source, true)
        val cccd = source.getDescriptor(CCCD) ?: return
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

    private fun handle(characteristic: BluetoothGattCharacteristic, v: ByteArray) {
        if (characteristic.uuid != NOTIFICATION_SOURCE || v.size < 8) return
        val flags = v[1].toInt() and 0xFF
        val category = v[2].toInt() and 0xFF
        // Ignore notifications that were already there when we connected, except a call still in progress.
        if (flags and FLAG_PRE_EXISTING != 0 && category != CATEGORY_INCOMING_CALL && category != CATEGORY_ACTIVE_CALL) return
        val uid = (v[4].toInt() and 0xFF) or ((v[5].toInt() and 0xFF) shl 8) or ((v[6].toInt() and 0xFF) shl 16) or ((v[7].toInt() and 0xFF) shl 24)
        val event = AncsEvent(v[0].toInt() and 0xFF, category, uid)
        Log.d(TAG, "event=${event.event} category=${event.category} uid=$uid")
        main.post { onEvent(event) }
    }

    /** After pairing completes, discover again: ANCS is visible now. */
    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
            if (state == BluetoothDevice.BOND_BONDED) main.postDelayed({ gatt?.discoverServices() }, 800)
        }
    }

    companion object {
        private const val TAG = "Ancs"
        private const val KEY_PHONE = "phone"
        private const val FLAG_PRE_EXISTING = 0x04
        private const val WATCHDOG_MS = 30_000L
        private const val STUCK_MS = 45_000L
        const val EVENT_ADDED = 0
        const val EVENT_REMOVED = 2
        const val CATEGORY_INCOMING_CALL = 1
        const val CATEGORY_MISSED_CALL = 2
        const val CATEGORY_ACTIVE_CALL = 12

        val ANCS_SERVICE: UUID = UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0")
        val NOTIFICATION_SOURCE: UUID = UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        val PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            emptyArray()
        }
    }
}
