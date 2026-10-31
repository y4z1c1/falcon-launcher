package dev.yusuf.falconlauncher.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import dev.yusuf.falconlauncher.MainActivity
import dev.yusuf.falconlauncher.FalconApp
import dev.yusuf.falconlauncher.SwitcherActivity
import dev.yusuf.falconlauncher.data.LightShortcut
import android.widget.Toast
import dev.yusuf.falconlauncher.dream.FalconDream
import dev.yusuf.falconlauncher.tv.PicturePresets
import dev.yusuf.falconlauncher.ui.SystemActions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Google TV always sends the Home button to its own launcher. This service catches the Home key
 * before the system sees it and opens our launcher instead, so Google TV never flashes up.
 * As a fallback (e.g. after the screensaver), it also redirects when the stock launcher comes to the front.
 * Holding Home opens the app switcher instead.
 */
class HomeWatcherService : AccessibilityService() {
    private var lastRedirect = 0L
    /** The app in front, so number keys can be left to the TV app for channels. */
    @Volatile private var foreground: String? = null
    private val picturePresets by lazy { PicturePresets(this) }
    private val main = Handler(Looper.getMainLooper())
    /** Set once a Home press has been held long enough to open the app switcher; its release is then ignored. */
    private var homeHeld = false
    private val holdHome = Runnable {
        homeHeld = true
        openSwitcher()
    }

    override fun onServiceConnected() {
        _connected.value = true
        // Start loading the app list now so the launcher is ready before the first Home press.
        (application as FalconApp).repository
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            Log.d(TAG, "key ${KeyEvent.keyCodeToString(event.keyCode)}")
        }
        // Pressing Mute during call muting means the user takes over the sound; let the key do its job.
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE && event.action == KeyEvent.ACTION_DOWN) {
            (application as FalconApp).callMute.forget()
        }
        // Color buttons switch picture modes instead of doing whatever the current app does with them.
        if (picturePresets.handles(event.keyCode)) {
            picturePresets.onKey(event)
            return true
        }
        // Number keys switch lights from any app, except the TV app where they change channels.
        // Keys with no light action behave normally.
        if (event.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 && foreground !in NUMBER_KEY_APPS) {
            val app = application as FalconApp
            val key = event.keyCode - KeyEvent.KEYCODE_0
            if (app.lightShortcut(key) == LightShortcut.None) return false
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                app.runLightShortcut(key)?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
            }
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_HOME) {
            onHomeKey(event)
            return true
        }
        val action: () -> Unit = when (event.keyCode) {
            // The remote's TV button opens the cable receiver on HDMI 1 instead of the tuner app. On this TCL
            // remote it sends vendor key code 4001 (measured); BUTTON_3 is the YouTube button, so leave it alone.
            KeyEvent.KEYCODE_TV, TCL_TV_KEY -> {
                { openReceiver(this) }
            }
            else -> return false
        }
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) action()
        return true
    }

    /**
     * Press Home for the launcher, hold it for the app switcher. Like the color keys, the service never sees
     * key repeats, so a timer started on the press decides the hold.
     */
    private fun onHomeKey(event: KeyEvent) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                homeHeld = false
                main.removeCallbacks(holdHome)
                main.postDelayed(holdHome, HOLD_HOME_MS)
            }
            KeyEvent.ACTION_UP -> {
                main.removeCallbacks(holdHome)
                if (!homeHeld && !event.isCanceled) {
                    bypass = false
                    // Home during the screensaver ends it too, landing on the launcher's home page.
                    FalconDream.current?.goHome() ?: openLauncher()
                }
                homeHeld = false
            }
        }
    }

    private fun openSwitcher() {
        // The screensaver covers everything; holding Home there just ends it, like a press.
        FalconDream.current?.let { return it.goHome() }
        val behind = foreground?.takeIf { it != packageName && it !in STOCK_LAUNCHERS }
        startActivity(
            Intent(this, SwitcherActivity::class.java)
                .putExtra(SwitcherActivity.EXTRA_CURRENT, behind)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString()
        if (!pkg.startsWith("com.android.systemui")) foreground = pkg
        if (pkg != packageName && pkg !in STOCK_LAUNCHERS) recordRecent(pkg)
        if (pkg !in STOCK_LAUNCHERS) {
            // Any real app (or our launcher) in front ends a deliberate visit to the stock launcher.
            if (!pkg.startsWith("com.android.systemui")) bypass = false
            return
        }
        // Only the stock *home screen* is replaced. Google TV's other screens, like the quick-settings
        // dashboard the remote's Settings button opens, must be left alone.
        if (bypass || cls !in STOCK_HOME_ACTIVITIES) return
        Log.d(TAG, "Stock launcher appeared ($cls), redirecting")
        openLauncher()
    }

    /** Only launchable apps go in the switcher's list, not system dialogs or background pieces like voice search. */
    private fun recordRecent(pkg: String) {
        val app = application as FalconApp
        val launchable = app.repository.apps.value
        if (launchable == null || launchable.any { it.packageName == pkg }) app.recents.onForeground(pkg)
    }

    private fun openLauncher() {
        val now = SystemClock.uptimeMillis()
        if (now - lastRedirect < DEBOUNCE_MS) return
        lastRedirect = now
        startActivity(
            Intent(this, MainActivity::class.java)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        )
    }

    override fun onDestroy() {
        _connected.value = false
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    enum class Status { OFF, BLOCKED, ON }

    companion object {
        private const val TAG = "HomeWatcher"
        private const val DEBOUNCE_MS = 300L
        private const val HOLD_HOME_MS = 550L
        /** Apps where number keys keep their usual meaning (channel numbers). */
        private val NUMBER_KEY_APPS = setOf("com.tcl.tv")
        /** TCL's vendor key code for the remote's TV button. */
        private const val TCL_TV_KEY = 4001
        /**
         * Switches to HDMI 1 (the cable receiver). Used by the remote's TV button and the TV menu item.
         * Only on those explicit actions: TCL's TV app also plays AirPlay and other inputs, so the launcher
         * must never redirect it on its own (an earlier "safety net" that did this broke AirPlay).
         */
        fun openReceiver(context: Context) {
            val intent = SystemActions.hdmi1(context) ?: return
            runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { Log.w(TAG, "Couldn't open HDMI 1", it) }
        }

        private val STOCK_HOME_ACTIVITIES = setOf(
            "com.google.android.apps.tv.launcherx.home.HomeActivity",
            "com.google.android.tvlauncher.MainActivity",
            "com.google.android.leanbacklauncher.MainActivity",
        )

        val STOCK_LAUNCHERS = setOf(
            "com.google.android.apps.tv.launcherx",
            "com.google.android.tvlauncher",
            "com.google.android.leanbacklauncher",
        )

        /** True while Android has the service bound. TCL firmware can refuse to bind it even when it's enabled. */
        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        /** When true, the stock launcher is allowed to stay open until another app comes to the front. */
        @Volatile
        var bypass = false

        fun status(context: Context): Status = when {
            !isEnabled(context) -> Status.OFF
            !connected.value -> Status.BLOCKED
            else -> Status.ON
        }

        private fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val me = ComponentName(context, HomeWatcherService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
