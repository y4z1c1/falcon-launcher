package dev.yusuf.falconlauncher.tv

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Mutes the TV for the length of a phone call and restores it afterwards. Only sound Falcon muted is
 * restored: if the TV was already muted when the call started, it stays muted. If the "call ended" signal
 * never arrives, the sound comes back on its own after [SAFETY_MS].
 */
class CallMute(context: Context) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    private val safety = Runnable { end("safety timeout") }

    @Volatile var active = false
        private set
    private var wasMuted = false

    @Synchronized
    fun start(): Boolean {
        if (active) return true
        wasMuted = audio.isStreamMute(STREAM)
        if (!wasMuted) audio.adjustStreamVolume(STREAM, AudioManager.ADJUST_MUTE, 0)
        active = true
        main.removeCallbacks(safety)
        main.postDelayed(safety, SAFETY_MS)
        Log.d(TAG, "call started; was muted=$wasMuted, now muted=${audio.isStreamMute(STREAM)}")
        return audio.isStreamMute(STREAM)
    }

    @Synchronized
    fun end(reason: String = "call ended"): Boolean {
        if (!active) return false
        active = false
        main.removeCallbacks(safety)
        if (!wasMuted) audio.adjustStreamVolume(STREAM, AudioManager.ADJUST_UNMUTE, 0)
        Log.d(TAG, "$reason; muted=${audio.isStreamMute(STREAM)}")
        return true
    }

    val muted: Boolean get() = audio.isStreamMute(STREAM)

    /** Keeps the call muting going for [ms] more (e.g. once a call is answered). */
    @Synchronized
    fun extend(ms: Long) {
        if (!active) return
        main.removeCallbacks(safety)
        main.postDelayed(safety, ms)
        Log.d(TAG, "muting extended by ${ms / 1000}s")
    }

    /** The user took over the sound (e.g. pressed Mute): stop tracking, leave the sound as they set it. */
    @Synchronized
    fun forget() {
        if (!active) return
        active = false
        main.removeCallbacks(safety)
        Log.d(TAG, "user took over the sound")
    }

    companion object {
        private const val TAG = "CallMute"
        private const val STREAM = AudioManager.STREAM_MUSIC
        const val SAFETY_MS = 3 * 60 * 60 * 1000L
    }
}
