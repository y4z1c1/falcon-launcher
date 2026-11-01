package dev.yusuf.falconlauncher.dream

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.yusuf.falconlauncher.FalconApp
import dev.yusuf.falconlauncher.ui.DreamScreen
import dev.yusuf.falconlauncher.ui.FalconTheme

/** Shows the screensaver on demand (Settings › Screensaver). Any button closes it, like the real one. */
class ScreensaverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as FalconApp
        setContent {
            FalconTheme { DreamScreen(nowPlaying = app.nowPlaying.state) }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == dev.yusuf.falconlauncher.tv.LocalAdb.INERT_KEY) return true
        if (event.action == KeyEvent.ACTION_UP) finish()
        return true
    }
}
