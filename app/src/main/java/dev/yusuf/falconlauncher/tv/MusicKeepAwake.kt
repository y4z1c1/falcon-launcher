package dev.yusuf.falconlauncher.tv

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
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

/**
 * Keeps the TV from putting itself on standby while music plays. Android TV's "attentive timeout" (TCL's
 * "TV has been inactive for a long time" countdown, after 4 hours) only counts remote presses: wake locks
 * don't hold it off, so an evening of Spotify ends in standby. While a music app plays and the screen is
 * on, this sends the TV an inert key now and then through [LocalAdb]. Injected input counts as user
 * activity, which restarts the countdown.
 *
 * Video apps are left alone, so falling asleep in front of a film still ends in standby.
 */
class MusicKeepAwake(
    context: Context,
    private val adb: LocalAdb,
    private val nowPlaying: StateFlow<NowPlaying?>,
) {
    private val power = context.getSystemService(PowerManager::class.java)
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
                        if (power.isInteractive && now - lastPoke >= POKE_EVERY.inWholeMilliseconds) {
                            if (adb.pokeUserActivity()) lastPoke = now
                        }
                        delay(CHECK_EVERY)
                    }
                }
        }
    }

    private companion object {
        /** Well inside the 4-hour countdown, and rare enough not to hold off the screensaver for long. */
        val POKE_EVERY = 1.hours
        val CHECK_EVERY = 5.minutes

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
