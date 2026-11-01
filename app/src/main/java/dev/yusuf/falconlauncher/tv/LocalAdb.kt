package dev.yusuf.falconlauncher.tv

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Runs shell commands through the TV's own adb daemon, over the network loopback, for the few things
 * an app may not do itself. Since Android 14 an app can't stop other apps, but `am force-stop` can.
 *
 * Needs network debugging on (it already is, for installing the launcher). The first time, the TV asks
 * whether to allow this app's key; choosing "Always allow" makes that permanent.
 */
class LocalAdb(context: Context) {
    private val keyDir = File(context.noBackupFilesDir, "adb")
    private val prefs = context.getSharedPreferences("local_adb", Context.MODE_PRIVATE)
    private val lock = Mutex()

    /** True once a command has gone through, i.e. the TV has allowed this app's key. */
    val authorized: Boolean get() = prefs.getBoolean(KEY_AUTHORIZED, false)

    suspend fun forceStop(packageName: String): Boolean {
        if (!PACKAGE_NAME.matches(packageName)) return false
        return shell("am force-stop $packageName") != null
    }

    /** Sends [INERT_KEY], which counts as the user touching the remote (see [MusicKeepAwake]). */
    suspend fun pokeUserActivity(): Boolean = shell("input keyevent $INERT_KEY") != null

    /** The command's output, or null if adb isn't reachable, the key wasn't allowed, or the command failed. */
    private suspend fun shell(command: String): String? = withContext(Dispatchers.IO) {
        lock.withLock {
            runCatching {
                Dadb.create(HOST, PORT, keyPair(), CONNECT_TIMEOUT_MS, AUTH_TIMEOUT_MS).use { adb ->
                    val response = adb.shell(command)
                    check(response.exitCode == 0) { response.allOutput }
                    response.output
                }
            }.onSuccess {
                prefs.edit { putBoolean(KEY_AUTHORIZED, true) }
            }.onFailure {
                Log.w(TAG, "adb shell '$command' failed", it)
            }.getOrNull()
        }
    }

    private fun keyPair(): AdbKeyPair {
        val private = File(keyDir, "adbkey")
        val public = File(keyDir, "adbkey.pub")
        if (!private.exists() || !public.exists()) AdbKeyPair.generate(private, public)
        return AdbKeyPair.read(private, public)
    }

    companion object {
        /** A key no app or screen reacts to; the screensaver ignores it too. */
        const val INERT_KEY = android.view.KeyEvent.KEYCODE_F12

        private const val TAG = "LocalAdb"
        private const val HOST = "127.0.0.1"
        private const val PORT = 5555
        private const val CONNECT_TIMEOUT_MS = 2_000
        /** Also how long the TV's "Allow debugging?" prompt has to be answered the first time. */
        private const val AUTH_TIMEOUT_MS = 30_000
        private const val KEY_AUTHORIZED = "authorized"
        private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")
    }
}
