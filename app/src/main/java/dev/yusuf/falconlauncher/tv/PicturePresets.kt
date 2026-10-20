package dev.yusuf.falconlauncher.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.Toast

/**
 * The remote's color buttons as picture-mode presets, like car radio presets:
 * press to switch to the saved mode, hold for 2 seconds to save the current mode to that button.
 */
class PicturePresets(private val context: Context) {
    private val prefs = context.getSharedPreferences("picture_presets", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private var savedThisPress = false
    private var pendingSave: Runnable? = null

    fun handles(keyCode: Int) = keyCode in COLORS

    /**
     * Called for every down/up of a color key; always consumes it. Android generates key repeats after
     * the accessibility filter, so the service never sees a "held" key: a timer started on the press
     * decides the hold instead.
     */
    fun onKey(event: KeyEvent) {
        val color = COLORS[event.keyCode] ?: return
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                savedThisPress = false
                pendingSave?.let(main::removeCallbacks)
                pendingSave = Runnable {
                    savedThisPress = true
                    save(color)
                }.also { main.postDelayed(it, HOLD_MS) }
            }
            KeyEvent.ACTION_UP -> {
                pendingSave?.let(main::removeCallbacks)
                pendingSave = null
                if (!savedThisPress && !event.isCanceled) apply(color)
            }
        }
    }

    private fun apply(color: String) {
        val mode = prefs.getInt(color, -1).takeIf { it >= 0 } ?: DEFAULTS[color]
        if (mode == null) {
            toast("Hold $color for 2 seconds to save the current picture mode")
            return
        }
        toast(if (PictureModes.set(mode)) "Picture mode: ${label(color)}" else "Couldn't change the picture mode")
    }

    private fun save(color: String) {
        val mode = PictureModes.current() ?: return toast("Couldn't read the picture mode")
        prefs.edit().putInt(color, mode).apply()
        toast("Saved this picture mode to $color")
    }

    private fun label(color: String) = prefs.getString("$color.label", null) ?: LABELS[color] ?: color

    private fun toast(text: String) {
        main.post { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }

    private companion object {
        const val HOLD_MS = 2_000L

        val COLORS = mapOf(
            KeyEvent.KEYCODE_PROG_RED to "red",
            KeyEvent.KEYCODE_PROG_GREEN to "green",
            KeyEvent.KEYCODE_PROG_YELLOW to "yellow",
            KeyEvent.KEYCODE_PROG_BLUE to "blue",
        )

        /** What the user wants on each button (TCL's Turkish menu names). */
        val LABELS = mapOf("red" to "Akıllı", "green" to "Spor", "yellow" to "Sinema", "blue" to "Standart")

        /** TCL mode numbers for those, once known; until then the user saves them by holding the button. */
        val DEFAULTS = emptyMap<String, Int>()
    }
}
