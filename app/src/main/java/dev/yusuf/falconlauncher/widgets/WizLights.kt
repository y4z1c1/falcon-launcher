package dev.yusuf.falconlauncher.widgets

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class WizBulb(
    val ip: String,
    val mac: String,
    val isOn: Boolean,
    val dimming: Int,
    /** False when the lamp stopped answering (powered off at the switch, out of Wi-Fi). */
    val reachable: Boolean = true,
) {
    /** WiZ keeps bulb names in its cloud, so until the user names one, bulbs are told apart by address. */
    val defaultName: String get() = "Lamp ." + ip.substringAfterLast('.')
}

/** Rooms the user has. Bulbs are assigned to them in the launcher (stored by MAC address). */
enum class Room(val label: String) {
    LIVING_ROOM("Oturma Odası"),
    BEDROOM("Yatak Odası"),
    KITCHEN("Mutfak"),
    BATHROOM("Banyo"),
    ENTRY("Antre"),
}

/**
 * Keeps an accurate, live picture of the WiZ lamps over their local UDP API (port 38899), no account
 * needed. One long-lived socket on port 38900 carries everything:
 *
 *  - **Push:** each lamp is registered (and re-registered every 15 s) to report its state to us the moment
 *    it changes (`syncPilot`), so changes from Google Home, the WiZ app or a switch show up at once.
 *  - **Polling:** `getPilot` to every known lamp, every 1.5 s while the lights page is open (live mode),
 *    every 8 s otherwise, as a safety net for lost packets.
 *  - **Commands** wait for the lamp's acknowledgement and retry; while one is in flight, older state
 *    reports can't overwrite what the user just did (no flicker back and forth).
 *  - Lamps that stop answering are marked unreachable instead of showing a stale state.
 */
class WizLights(private val context: Context) {
    private val known = context.getSharedPreferences("wiz_lamps", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private val states = LinkedHashMap<String, WizBulb>()
    private val lastSeen = HashMap<String, Long>()
    /** A command's expected outcome per lamp, and until when reports that disagree with it are ignored. */
    private val pending = HashMap<String, Pending>()

    private val acks = ConcurrentHashMap<Int, CompletableDeferred<Boolean>>()
    private val nextId = AtomicInteger(1000)
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var started = false
    @Volatile private var live = false

    private val _bulbs = MutableStateFlow<List<WizBulb>>(emptyList())
    val bulbs: StateFlow<List<WizBulb>> = _bulbs.asStateFlow()

    private class Pending(val on: Boolean?, val dimming: Int?, val until: Long)

    init {
        // Lamps seen before show up right away (as unknown-state until they answer).
        known.all.forEach { (mac, ip) -> (ip as? String)?.let { states[mac] = WizBulb(it, mac, isOn = false, dimming = 100, reachable = false) } }
        publish()
    }

    /** Opens the socket and starts listening, polling and registering. Safe to call more than once. */
    fun start() {
        if (started) return
        started = true
        scope.launch { run() }
    }

    /** Fast polling while the lights page is open. */
    fun setLive(on: Boolean) {
        live = on
        if (on) scope.launch { pollOnce() }
    }

    /** Broadcast search for lamps (new ones, or ones whose address changed). Returns after ~2 s. */
    suspend fun discover() = withContext(Dispatchers.IO) {
        start()
        repeat(3) {
            broadcast(GET_PILOT)
            delay(400)
        }
        delay(800)
    }

    private suspend fun run() {
        val s = openSocket() ?: return
        socket = s
        Thread({ receiveLoop(s) }, "wiz-receive").apply { isDaemon = true; start() }
        scope.launch { discover() }
        scope.launch {
            while (isActive) {
                pollOnce()
                delay(if (live) 1_500L else 8_000L)
            }
        }
        scope.launch {
            while (isActive) {
                register()
                delay(15_000L)
            }
        }
        scope.launch {
            while (isActive) {
                delay(60_000L)
                discover()
            }
        }
    }

    private fun openSocket(): DatagramSocket? = runCatching {
        DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            // Port 38900 is where lamps push state changes; fall back to any port (polling still works).
            runCatching { bind(InetSocketAddress(PUSH_PORT)) }.onFailure {
                Log.w(TAG, "Port $PUSH_PORT busy; live push disabled", it)
                bind(InetSocketAddress(0))
            }
        }
    }.onFailure { Log.e(TAG, "Couldn't open UDP socket", it) }.getOrNull()

    private fun receiveLoop(s: DatagramSocket) {
        val buffer = ByteArray(4096)
        while (!s.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            runCatching { s.receive(packet) }.onFailure { return }
            val ip = packet.address?.hostAddress ?: continue
            runCatching { handle(ip, JSONObject(String(packet.data, 0, packet.length))) }
        }
    }

    private fun handle(ip: String, json: JSONObject) {
        val method = json.optString("method")
        val result = json.optJSONObject("result")
        when {
            // Push from a lamp: state changed, or its periodic heartbeat.
            method == "syncPilot" || method == "firstBeat" -> json.optJSONObject("params")?.let { report(ip, it) }
            // Command acknowledgement.
            result != null && result.has("success") && !result.has("state") -> {
                val id = json.optInt("id", -1)
                acks.remove(id)?.complete(result.optBoolean("success"))
                result.optString("mac").takeIf { it.isNotEmpty() }?.let { seen(it, ip) }
            }
            // getPilot answer.
            result != null && result.has("mac") -> report(ip, result)
        }
    }

    private fun report(ip: String, p: JSONObject) {
        val mac = p.optString("mac").takeIf { it.isNotEmpty() } ?: return
        if (!p.has("state")) {
            seen(mac, ip)
            return
        }
        val isOn = p.optBoolean("state")
        val dimming = p.optInt("dimming", states[mac]?.dimming ?: 100)
        synchronized(lock) {
            val wait = pending[mac]
            val now = SystemClock.uptimeMillis()
            if (wait != null && now < wait.until) {
                val matches = (wait.on == null || wait.on == isOn) && (wait.dimming == null || !isOn || wait.dimming == dimming)
                if (!matches) {
                    lastSeen[mac] = now
                    return // an older report racing the user's command; the command wins
                }
                pending.remove(mac)
            }
            val isNew = mac !in states
            states[mac] = WizBulb(ip, mac, isOn, dimming, reachable = true)
            lastSeen[mac] = now
            if (isNew || known.getString(mac, null) != ip) {
                known.edit().putString(mac, ip).apply()
                if (isNew) scope.launch { register() }
            }
        }
        publish()
    }

    private fun seen(mac: String, ip: String) {
        synchronized(lock) {
            lastSeen[mac] = SystemClock.uptimeMillis()
            states[mac]?.let { if (!it.reachable || it.ip != ip) states[mac] = it.copy(ip = ip, reachable = true) }
        }
        publish()
    }

    private suspend fun pollOnce() {
        val targets = synchronized(lock) { states.values.map { it.ip } }
        targets.forEach { send(it, GET_PILOT) }
        // A lamp silent for a while is unreachable (switched off at the wall, or offline).
        val limit = if (live) 6_000L else 30_000L
        val now = SystemClock.uptimeMillis()
        var changed = false
        synchronized(lock) {
            states.values.toList().forEach { b ->
                val last = lastSeen[b.mac]
                if (b.reachable && (last == null || now - last > limit)) {
                    states[b.mac] = b.copy(reachable = false)
                    changed = true
                }
            }
        }
        if (changed) publish()
    }

    private fun register() {
        val phoneIp = localIp() ?: return
        val msg = JSONObject().put("method", "registration").put(
            "params",
            JSONObject().put("phoneMac", PHONE_MAC).put("register", true).put("phoneIp", phoneIp).put("id", "1"),
        ).toString()
        val targets = synchronized(lock) { states.values.map { it.ip } }
        targets.forEach { send(it, msg) }
    }

    suspend fun setAll(on: Boolean) = setGroup(_bulbs.value.map { it.mac }.toSet(), on = on)

    suspend fun setGroup(macs: Set<String>, on: Boolean? = null, dimming: Int? = null) = withContext(Dispatchers.IO) {
        // In parallel, so a whole room switches together.
        _bulbs.value.filter { it.mac in macs }.map { b -> launch { set(b, on, dimming) } }.forEach { it.join() }
    }

    suspend fun dimAll(delta: Int) = _bulbs.value.filter { it.isOn }.forEach {
        set(it, dimming = (it.dimming + delta).coerceIn(10, 100))
    }

    /** Blinks a bulb twice so the user can see which one it is, then restores it. */
    suspend fun identify(bulb: WizBulb) {
        val original = _bulbs.value.firstOrNull { it.mac == bulb.mac } ?: bulb
        repeat(2) {
            set(original, on = true, dimming = 100)
            delay(350)
            set(original, on = false)
            delay(350)
        }
        set(original, on = original.isOn, dimming = original.dimming)
    }

    /**
     * Sends a command and waits for the lamp to confirm it, retrying up to 3 times. The new state shows
     * immediately; if the lamp never confirms, polling corrects the display shortly after.
     */
    suspend fun set(bulb: WizBulb, on: Boolean? = null, dimming: Int? = null): Boolean = withContext(Dispatchers.IO) {
        start()
        val mac = bulb.mac
        synchronized(lock) {
            pending[mac] = Pending(on, dimming, SystemClock.uptimeMillis() + PENDING_MS)
            states[mac]?.let { states[mac] = it.copy(isOn = on ?: it.isOn, dimming = dimming ?: it.dimming) }
        }
        publish()
        val params = JSONObject()
        on?.let { params.put("state", it) }
        dimming?.let { params.put("dimming", it) }
        val ip = synchronized(lock) { states[mac]?.ip } ?: bulb.ip
        repeat(3) { attempt ->
            val id = nextId.incrementAndGet()
            val ack = CompletableDeferred<Boolean>()
            acks[id] = ack
            send(ip, JSONObject().put("id", id).put("method", "setPilot").put("params", params).toString())
            val ok = withTimeoutOrNull(ACK_TIMEOUT_MS + attempt * 200L) { ack.await() }
            acks.remove(id)
            if (ok == true) {
                send(ip, GET_PILOT) // confirm the resulting state
                return@withContext true
            }
        }
        Log.w(TAG, "No acknowledgement from $ip for $params")
        false
    }

    private fun send(ip: String, message: String) {
        val s = socket ?: return
        runCatching {
            val bytes = message.toByteArray()
            s.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(ip), PORT))
        }
    }

    private fun broadcast(message: String) {
        val s = socket ?: return
        val bytes = message.toByteArray()
        broadcastAddresses().forEach { addr -> runCatching { s.send(DatagramPacket(bytes, bytes.size, addr, PORT)) } }
    }

    private fun publish() {
        _bulbs.value = synchronized(lock) { states.values.sortedBy { it.ip.substringAfterLast('.').toIntOrNull() ?: 0 } }
    }

    private fun localIp(): String? = dev.yusuf.falconlauncher.util.localIpv4(context)

    /** The subnet broadcast address (more reliable on many routers) plus the global one. */
    private fun broadcastAddresses(): List<InetAddress> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val link = cm.getLinkProperties(cm.activeNetwork)
        val subnet = link?.linkAddresses?.firstOrNull { it.address is Inet4Address }?.let { la ->
            val ip = la.address.address
            val value = ((ip[0].toInt() and 0xFF) shl 24) or ((ip[1].toInt() and 0xFF) shl 16) or
                ((ip[2].toInt() and 0xFF) shl 8) or (ip[3].toInt() and 0xFF)
            val mask = if (la.prefixLength == 0) 0 else -1 shl (32 - la.prefixLength)
            val broadcast = value or mask.inv()
            InetAddress.getByAddress(
                byteArrayOf(
                    (broadcast ushr 24).toByte(), (broadcast ushr 16).toByte(),
                    (broadcast ushr 8).toByte(), broadcast.toByte(),
                )
            )
        }
        return listOfNotNull(subnet, runCatching { InetAddress.getByName("255.255.255.255") }.getOrNull()).distinct()
    }

    private companion object {
        const val TAG = "WizLights"
        const val PORT = 38899
        const val PUSH_PORT = 38900
        const val ACK_TIMEOUT_MS = 400L
        const val PENDING_MS = 3_000L
        /** WiZ only uses this to address its pushes; any fixed value works. */
        const val PHONE_MAC = "f4a1b2c3d4e5"
        const val GET_PILOT = """{"method":"getPilot","params":{}}"""
    }
}
