package dev.yusuf.falconlauncher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import dev.yusuf.falconlauncher.service.HomeWatcherService
import dev.yusuf.falconlauncher.ui.FalconTheme
import dev.yusuf.falconlauncher.ui.HomeScreen

class MainActivity : ComponentActivity() {
    private val vm: LauncherViewModel by viewModels()
    private var homeSignal by mutableIntStateOf(0)
    private var serviceStatus by mutableStateOf(HomeWatcherService.Status.ON)
    private var wifiPermissionGranted by mutableStateOf(false)

    /** Bluetooth access for hearing the iPhone ring (Settings › Call muting). */
    private val bluetoothPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            (application as FalconApp).ancs.start()
        }

    fun requestBluetoothPermissions() = bluetoothPermissions.launch(dev.yusuf.falconlauncher.tv.AncsClient.PERMISSIONS)

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            wifiPermissionGranted = granted
            getPreferences(MODE_PRIVATE).edit { putBoolean(KEY_ASKED_LOCATION, true) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Back does nothing on the home screen.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
        // The service can connect a moment after the launcher opens (e.g. right after boot), so keep the status live.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                HomeWatcherService.connected.collect { serviceStatus = HomeWatcherService.status(this@MainActivity) }
            }
        }
        wifiPermissionGranted = hasLocationPermission()
        askForLocationOnce()
        setContent {
            FalconTheme {
                HomeScreen(
                    vm = vm,
                    homeSignal = homeSignal,
                    serviceStatus = serviceStatus,
                    wifiPermissionGranted = wifiPermissionGranted,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) homeSignal++
    }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** The Wi-Fi pill can only show the network name with location permission. Ask once, never nag. */
    private fun askForLocationOnce() {
        if (wifiPermissionGranted) return
        if (getPreferences(MODE_PRIVATE).getBoolean(KEY_ASKED_LOCATION, false)) return
        locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private companion object {
        const val KEY_ASKED_LOCATION = "asked_location"
    }
}
