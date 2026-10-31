package dev.yusuf.falconlauncher

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.yusuf.falconlauncher.ui.AppSwitcher
import dev.yusuf.falconlauncher.ui.FalconTheme

/**
 * The app switcher, opened by holding Home: recently used apps as cards over whatever is on screen.
 * It runs in its own translucent task, so the app you came from stays visible behind it.
 */
class SwitcherActivity : ComponentActivity() {
    /** The app that was in front when Home was held, if any. */
    private var current: String? = null
    private var closedCurrent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as FalconApp
        current = intent.getStringExtra(EXTRA_CURRENT)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = dismiss()
        })
        setContent {
            val apps by app.repository.apps.collectAsState()
            val recents by app.recents.packages.collectAsState()
            FalconTheme {
                AppSwitcher(
                    apps = apps.orEmpty(),
                    recents = recents,
                    current = current,
                    onOpen = { entry ->
                        app.repository.launch(entry.packageName)
                        finish()
                    },
                    onClose = { entry ->
                        if (!app.localAdb.authorized) toast("If the TV asks to allow debugging, choose Always allow")
                        val closed = app.closeApp(entry.packageName)
                        if (closed && entry.packageName == current) closedCurrent = true
                        if (!closed) toast("Couldn't close ${entry.label}. Is network debugging on?")
                        closed
                    },
                )
            }
        }
    }

    /** Back returns to the app behind; if that app was just closed, there's nothing to return to but home. */
    private fun dismiss() {
        if (closedCurrent) {
            startActivity(Intent(this, MainActivity::class.java).addCategory(Intent.CATEGORY_HOME))
        }
        finish()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_CURRENT = "current"
    }
}
