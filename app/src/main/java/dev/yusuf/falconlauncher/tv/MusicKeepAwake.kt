package dev.yusuf.falconlauncher.tv

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import dev.yusuf.falconlauncher.widgets.NowPlaying
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Keeps the screensaver and standby away while music plays. Both start after a stretch without remote
 * presses: the screensaver after the screen timeout (10 minutes here), and standby after Android TV's
 * "attentive timeout" (TCL's "TV has been inactive for a long time" countdown, after 4 hours), which wake
 * locks don't hold off. While a music app plays and the screen is on, this sends the TV an inert key through
 * [LocalAdb] a minute before the screensaver would start. Injected input counts as user activity, which
 * restarts both countdowns.
 *
 * Video apps are left alone, so falling asleep in front of a film still ends in the screensaver and standby.
 */
class MusicKeepAwake(
    context: Context,
    private val adb: LocalAdb,
    private val nowPlaying: StateFlow<NowPlaying?>,
) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val resolver = context.contentResolver
    private var lastPoke = 0L

    fun start(scope: CoroutineScope) {
        scope.launch {
            nowPlaying
                .map { it != null && it.isPlaying && !it.isLastPlayed && it.packageName in MUSIC_APPS }
                .distinctUntilChanged()
                .collectLatest { playing ->
                    // Poke right away too: music started from a phone (Spotify Connect) may begin late in the countdown.
                    while (playing) {
                        val now = SystemClock.elapsedRealtime()
                        if (power.isInteractive && now - lastPoke >= pokeEvery()) {
                            if (adb.pokeUserActivity()) {
                                lastPoke = now
                                Log.d(TAG, "Music playing: held off the screensaver and standby")
                            }
                        }
                        delay(CHECK_EVERY)
                    }
                }
        }
    }

    /** A minute before the screensaver would start (read each time, in case it's changed in Settings). */
    private fun pokeEvery(): Long {
        val screenTimeout = runCatching { Settings.System.getLong(resolver, Settings.System.SCREEN_OFF_TIMEOUT) }
            .getOrDefault(10.minutes.inWholeMilliseconds)
        return (screenTimeout - 1.minutes.inWholeMilliseconds)
            .coerceIn(MIN_POKE_EVERY.inWholeMilliseconds, MAX_POKE_EVERY.inWholeMilliseconds)
    }

    private companion object {
        const val TAG = "MusicKeepAwake"
        val MIN_POKE_EVERY = 30.seconds
        /** Well inside the 4-hour standby countdown, even with a long (or no) screensaver timeout. */
        val MAX_POKE_EVERY = 1.hours
        val CHECK_EVERY = 15.seconds

        val MUSIC_APPS = setOf(
            "com.spotify.tv.android",
            "com.spotify.music",
            "com.google.android.youtube.tvmusic",
            "com.google.android.apps.youtube.music",
            "com.apple.android.music",
            "deezer.android.tv",
            "deezer.android.app",
            "com.aspiro.tidal",
            "com.amazon.music.tv",
            "com.amazon.mp3",
            "com.soundcloud.android",
        )
    }
}
