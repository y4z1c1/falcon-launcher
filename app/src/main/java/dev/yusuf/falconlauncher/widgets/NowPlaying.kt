package dev.yusuf.falconlauncher.widgets

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NowPlaying(
    val packageName: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val art: ImageBitmap?,
    /** Up to three colors picked from the artwork, for tinting the home screen background. */
    val artColors: List<Int>?,
    val isPlaying: Boolean,
    val durationMs: Long,
    /** Position when it was last reported, and when (SystemClock.elapsedRealtime); see [positionAt]. */
    val positionMs: Long,
    val positionUpdatedAt: Long,
    val speed: Float,
    /** Not a live session: the last thing that played, remembered after its app closed. */
    val isLastPlayed: Boolean = false,
) {
    /** The playback position right now, extrapolated while playing. */
    fun positionAt(nowElapsed: Long): Long {
        val moved = if (isPlaying) ((nowElapsed - positionUpdatedAt) * speed).toLong() else 0L
        val pos = positionMs + moved
        return if (durationMs > 0) pos.coerceIn(0L, durationMs) else pos.coerceAtLeast(0L)
    }
}

/**
 * Follows whichever app played media most recently (Spotify, YouTube, …) through Android's media
 * sessions. When no app has a session any more, it shows the last thing that played, remembered on disk.
 */
class NowPlayingSource(private val context: Context) {
    private val sessions = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val listenerComponent = ComponentName(context, MediaListener::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var started = false
    private var lastBitmap: android.graphics.Bitmap? = null
    /** The song [lastBitmap] belongs to. */
    private var artTrackKey: String? = null
    private var pendingClear: Runnable? = null
    private val lastPlayed = LastPlayedStore(context)

    /** A light watcher on every session, so a different app starting to play takes over the card. */
    private val watchers = mutableMapOf<android.media.session.MediaSession.Token, Pair<MediaController, MediaController.Callback>>()

    private val _state = MutableStateFlow<NowPlaying?>(null)
    val state: StateFlow<NowPlaying?> = _state.asStateFlow()

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { follow(it.orEmpty()) }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() = follow(currentSessions())
    }

    fun hasAccess(): Boolean =
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    /** Starts following media sessions once notification access has been granted. Safe to call repeatedly. */
    fun startIfAllowed() {
        if (started || !hasAccess()) return
        runCatching {
            sessions.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, handler)
            started = true
            follow(currentSessions())
        }
    }

    fun togglePlayPause() {
        val controls = controller?.transportControls ?: return
        if (_state.value?.isPlaying == true) controls.pause() else controls.play()
    }

    /** Pauses every app that's playing (for a phone call). Returns their package names, for [resume]. */
    fun pauseAll(): Set<String> {
        val playing = currentSessions().filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        playing.forEach { it.transportControls.pause() }
        return playing.map { it.packageName }.toSet()
    }

    /** Plays again what [pauseAll] paused, unless it has been played, stopped or closed since. */
    fun resume(packages: Set<String>) {
        currentSessions()
            .filter { it.packageName in packages && it.playbackState?.state == PlaybackState.STATE_PAUSED }
            .forEach { it.transportControls.play() }
    }

    fun next() = controller?.transportControls?.skipToNext()
    fun previous() = controller?.transportControls?.skipToPrevious()

    private fun currentSessions(): List<MediaController> =
        runCatching { sessions.getActiveSessions(listenerComponent) }.getOrDefault(emptyList())

    private fun follow(controllers: List<MediaController>) {
        watch(controllers)
        // Android lists sessions most recently active first: take the first one playing, otherwise the most
        // recent one that has something to show.
        val pick = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull { !it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank() }
        if (pick?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(controllerCallback)
            controller = pick
            pick?.registerCallback(controllerCallback, handler)
        }
        publish()
    }

    private fun watch(controllers: List<MediaController>) {
        val tokens = controllers.map { it.sessionToken }.toSet()
        watchers.keys.filter { it !in tokens }.forEach { token ->
            watchers.remove(token)?.let { (c, cb) -> c.unregisterCallback(cb) }
        }
        controllers.filter { it.sessionToken !in watchers }.forEach { c ->
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    if (state?.state == PlaybackState.STATE_PLAYING && c.sessionToken != controller?.sessionToken) {
                        follow(currentSessions())
                    }
                }
            }
            c.registerCallback(cb, handler)
            watchers[c.sessionToken] = c to cb
        }
    }

    private fun publish() {
        val c = controller
        val metadata = c?.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (c == null || title.isNullOrBlank()) {
            // Nothing live: fall back to the last thing that played. Sessions blink out briefly while
            // switching tracks or apps, so only switch away from a live item if the gap lasts.
            val current = _state.value
            if (current == null || current.isLastPlayed) {
                if (current == null) _state.value = lastPlayed.load()
            } else if (pendingClear == null) {
                pendingClear = Runnable {
                    pendingClear = null
                    _state.value = lastPlayed.load()
                }.also { handler.postDelayed(it, CLEAR_DELAY_MS) }
            }
            return
        }
        pendingClear?.let(handler::removeCallbacks)
        pendingClear = null

        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty()
        val bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val previous = _state.value
        val sameTrack = previous != null && previous.title == title && previous.artist == artist
        // Apps resend the same artwork as a new Bitmap object with many updates; compare pixels so an
        // unchanged image doesn't count as new artwork (which re-ran the cross-fade and flashed the card).
        val unchanged = bitmap != null && lastBitmap.let { last ->
            last != null && (last === bitmap || (last.width == bitmap.width && last.height == bitmap.height && last.sameAs(bitmap)))
        }
        val trackKey = title + "\u0000" + artist
        // Spotify often announces a new song with the previous song's picture, then sends the real one.
        // So: a new song carrying the old picture shows the placeholder until its own art arrives; within
        // a song, a different picture always replaces the current one, except a smaller thumbnail of art
        // already shown for this song (sent after the full image, which looked like a flash).
        val staleFromPreviousSong = !sameTrack && unchanged && (artTrackKey != trackKey || previous?.packageName != c.packageName)
        val keepCurrent = sameTrack && previous?.art != null && artTrackKey == trackKey && (
            bitmap == null || unchanged ||
                bitmap.width * bitmap.height < (lastBitmap?.let { it.width * it.height } ?: 0)
            )
        val art = when {
            keepCurrent -> previous?.art
            bitmap == null || staleFromPreviousSong -> null
            else -> bitmap.asImageBitmap()
        }
        val artColors = when {
            keepCurrent -> previous?.artColors
            bitmap == null || staleFromPreviousSong -> null
            else -> colorsOf(bitmap)
        }
        if (bitmap != null && !keepCurrent && !staleFromPreviousSong) {
            lastBitmap = bitmap
            artTrackKey = trackKey
        }

        val next = NowPlaying(
            packageName = c.packageName,
            title = title,
            artist = artist,
            album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            art = art,
            artColors = artColors,
            isPlaying = c.playbackState?.state == PlaybackState.STATE_PLAYING,
            durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
            positionMs = c.playbackState?.position ?: 0L,
            positionUpdatedAt = c.playbackState?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            speed = c.playbackState?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
        )
        // Apps report their position many times a second. The card extrapolates progress itself, so only
        // publish real changes (new song, play/pause, artwork, a seek); every publish redraws the UI.
        if (previous != null && !previous.isLastPlayed && !isMeaningfulChange(previous, next)) return
        _state.value = next
        if (next.isPlaying) lastPlayed.save(next, if (keepCurrent) null else bitmap?.takeIf { !staleFromPreviousSong })
    }

    /** Vivid-to-dark colors from the artwork, skipping near-white and near-black (which make a dull glow). */
    private fun colorsOf(bitmap: android.graphics.Bitmap): List<Int>? = runCatching {
        val palette = androidx.palette.graphics.Palette.from(bitmap).maximumColorCount(16).generate()
        listOfNotNull(
            palette.vibrantSwatch, palette.darkVibrantSwatch, palette.lightVibrantSwatch,
            palette.mutedSwatch, palette.darkMutedSwatch, palette.dominantSwatch,
        ).map { it.rgb }
            .filter { androidx.core.graphics.ColorUtils.calculateLuminance(it) in 0.02..0.8 }
            .distinct()
            .take(3)
            .ifEmpty { null }
    }.getOrNull()

    private fun isMeaningfulChange(old: NowPlaying, new: NowPlaying): Boolean {
        if (old.title != new.title || old.artist != new.artist || old.packageName != new.packageName) return true
        if (old.isPlaying != new.isPlaying || old.isLastPlayed != new.isLastPlayed || old.art !== new.art || old.durationMs != new.durationMs) return true
        val now = SystemClock.elapsedRealtime()
        return kotlin.math.abs(old.positionAt(now) - new.positionAt(now)) > SEEK_THRESHOLD_MS
    }

    private companion object {
        const val CLEAR_DELAY_MS = 1_500L
        const val SEEK_THRESHOLD_MS = 1_500L
    }
}

/** Remembers the last song or video that played (text, colors and artwork), so it survives restarts. */
private class LastPlayedStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("last_played", Context.MODE_PRIVATE)
    private val artFile = java.io.File(context.filesDir, "last_played_art.png")
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var savedKey: String? = null

    /** Saves [item]; [art] is the new artwork bitmap when it changed, or null to keep the saved one. */
    fun save(item: NowPlaying, art: android.graphics.Bitmap?) {
        val key = item.packageName + "\u0000" + item.title + "\u0000" + item.artist
        if (key == savedKey && art == null) return
        val newItem = key != savedKey
        savedKey = key
        // The saved picture belongs to one item; a new item without its own picture must not reuse it.
        if (newItem && art == null) prefs.edit().remove("artKey").apply()
        prefs.edit()
            .putString("package", item.packageName)
            .putString("title", item.title)
            .putString("artist", item.artist)
            .putString("colors", item.artColors?.joinToString(",").orEmpty())
            .apply()
        if (art != null) {
            val copy = art.copy(android.graphics.Bitmap.Config.ARGB_8888, false) ?: return
            io.execute {
                runCatching {
                    artFile.outputStream().use { copy.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    prefs.edit().putString("artKey", key).apply()
                }
            }
        }
    }

    fun load(): NowPlaying? {
        val title = prefs.getString("title", null) ?: return null
        val key = prefs.getString("package", null).orEmpty() + "\u0000" + title + "\u0000" + prefs.getString("artist", null).orEmpty()
        val art = if (prefs.getString("artKey", null) == key) {
            runCatching { android.graphics.BitmapFactory.decodeFile(artFile.path)?.asImageBitmap() }.getOrNull()
        } else {
            null
        }
        return NowPlaying(
            packageName = prefs.getString("package", null).orEmpty(),
            title = title,
            artist = prefs.getString("artist", null).orEmpty(),
            art = art,
            artColors = if (art == null) null else prefs.getString("colors", null)?.split(',')?.mapNotNull { it.toIntOrNull() }?.ifEmpty { null },
            isPlaying = false,
            durationMs = 0,
            positionMs = 0,
            positionUpdatedAt = SystemClock.elapsedRealtime(),
            speed = 1f,
            isLastPlayed = true,
        )
    }
}
