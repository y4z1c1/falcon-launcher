package dev.yusuf.falconlauncher.tv

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.yusuf.falconlauncher.widgets.NowPlayingSource

/**
 * Pauses whatever is playing and mutes the TV for the length of a phone call, then unmutes and plays it
 * again afterwards. Muting covers sources with no media controls (live TV, HDMI). Only what Falcon changed
 * is restored: if the TV was already muted, it stays muted, and media paused before the call stays paused.
 * If the "call ended" signal never arrives, everything comes back on its own after [SAFETY_MS].
 */
class CallMute(context: Context, private val media: NowPlayingSource) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    private val safety = Runnable { end("safety timeout") }

    @Volatile var active = false
        private set
    private var wasMuted = false
    /** Apps this call paused, to play again when it ends. */
    private var paused = emptySet<String>()

    /** True while the call has paused something. */
    val pausedMedia: Boolean get() = paused.isNotEmpty()

    @Synchronized
    fun start(): Boolean {
        if (active) return true
        paused = media.pauseAll()
        wasMuted = audio.isStreamMute(STREAM)
        if (!wasMuted) audio.adjustStreamVolume(STREAM, AudioManager.ADJUST_MUTE, 0)
        active = true
        main.removeCallbacks(safety)
        main.postDelayed(safety, SAFETY_MS)
        Log.d(TAG, "call started; paused=$paused, was muted=$wasMuted, now muted=${audio.isStreamMute(STREAM)}")
        return audio.isStreamMute(STREAM)
    }

    @Synchronized
    fun end(reason: String = "call ended"): Boolean {
        if (!active) return false
        active = false
        main.removeCallbacks(safety)
        if (!wasMuted) audio.adjustStreamVolume(STREAM, AudioManager.ADJUST_UNMUTE, 0)
        media.resume(paused)
        Log.d(TAG, "$reason; resumed=$paused, muted=${audio.isStreamMute(STREAM)}")
        paused = emptySet()
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

    /** The user took over the sound (e.g. pressed Mute): stop tracking, leave the sound and media as they are. */
    @Synchronized
    fun forget() {
        if (!active) return
        active = false
        paused = emptySet()
        main.removeCallbacks(safety)
        Log.d(TAG, "user took over the sound")
    }

    companion object {
        private const val TAG = "CallMute"
        private const val STREAM = AudioManager.STREAM_MUSIC
        const val SAFETY_MS = 3 * 60 * 60 * 1000L
    }
}
