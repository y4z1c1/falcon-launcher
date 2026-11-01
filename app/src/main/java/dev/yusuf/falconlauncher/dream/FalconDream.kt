package dev.yusuf.falconlauncher.dream

import android.content.Intent
import android.service.dreams.DreamService
import android.view.KeyEvent
import dev.yusuf.falconlauncher.MainActivity
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.yusuf.falconlauncher.FalconApp
import dev.yusuf.falconlauncher.ui.DreamScreen
import dev.yusuf.falconlauncher.ui.FalconTheme

/**
 * Falcon's screensaver: the launcher's ambient glow, a large clock and Now Playing.
 * Back (and Home, via the Home-button service) go to the launcher's home page; any other button just
 * wakes up to whatever was on screen.
 */
class FalconDream : DreamService(), LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Interactive so the screensaver sees which button woke it (Back goes home, others just wake).
        isInteractive = true
        isFullscreen = true
        isScreenBright = true
        val app = application as FalconApp
        val view = ComposeView(this).apply {
            setContent {
                FalconTheme {
                    DreamScreen(nowPlaying = app.nowPlaying.state)
                }
            }
        }
        // DreamService isn't a lifecycle owner by default; Compose needs one on the view tree.
        window.decorView.setViewTreeLifecycleOwner(this)
        window.decorView.setViewTreeSavedStateRegistryOwner(this)
        setContentView(view)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        current = this
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun onDreamingStopped() {
        if (current === this) current = null
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        super.onDreamingStopped()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The launcher's own keep-awake key while music plays; not a person at the remote.
        if (event.keyCode == dev.yusuf.falconlauncher.tv.LocalAdb.INERT_KEY) return true
        if (event.action == KeyEvent.ACTION_UP) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) goHome() else finish()
        }
        return true
    }

    /** Ends the screensaver and opens the launcher's home page. */
    fun goHome() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    companion object {
        /** The screensaver while it's showing, so the Home-button service can end it. */
        @Volatile
        var current: FalconDream? = null
            private set
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }
}
