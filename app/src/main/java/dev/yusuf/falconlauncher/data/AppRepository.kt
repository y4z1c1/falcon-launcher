package dev.yusuf.falconlauncher.data

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ResolveInfo
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppEntry(
    val packageName: String,
    val label: String,
    val image: ImageBitmap,
    val isBanner: Boolean,
    /** Brand color picked from the banner, used to tint the home screen background. */
    val accent: Color,
)

/** Lists launchable apps (TV banners first, phone-style icons as fallback) and keeps the list fresh. */
class AppRepository(private val context: Context) {
    private val pm = context.packageManager
    private val _apps = MutableStateFlow<List<AppEntry>?>(null)
    val apps: StateFlow<List<AppEntry>?> = _apps.asStateFlow()

    private var scope: CoroutineScope? = null
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            scope?.launch { refresh() }
        }
    }

    fun start(scope: CoroutineScope) {
        this.scope = scope
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, packageReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        scope.launch { refresh() }
    }

    suspend fun refresh() {
        _apps.value = withContext(Dispatchers.Default) { load() }
    }

    private suspend fun load(): List<AppEntry> = coroutineScope {
        val byPackage = LinkedHashMap<String, ResolveInfo>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in pm.queryIntentActivities(intent, 0)) {
                val pkg = info.activityInfo.packageName
                if (pkg != context.packageName && pkg !in byPackage) byPackage[pkg] = info
            }
        }
        byPackage.map { (pkg, info) ->
            async { loadEntry(pkg, info) }
        }.awaitAll().filterNotNull().sortedBy { it.label.lowercase() }
    }

    private fun loadEntry(pkg: String, info: ResolveInfo): AppEntry? = runCatching {
        val activity = info.activityInfo
        val banner = activity.loadBanner(pm) ?: activity.applicationInfo.loadBanner(pm)
        val bitmap = if (banner != null) {
            banner.toBitmap(BANNER_W, BANNER_H)
        } else {
            activity.loadIcon(pm).toBitmap(ICON_SIZE, ICON_SIZE)
        }
        AppEntry(pkg, info.loadLabel(pm).toString(), bitmap.asImageBitmap(), banner != null, accentOf(bitmap))
    }.onFailure { Log.w(TAG, "Skipping $pkg", it) }.getOrNull()

    private fun accentOf(bitmap: Bitmap): Color {
        val palette = runCatching { Palette.from(bitmap).maximumColorCount(12).generate() }.getOrNull()
            ?: return DEFAULT_ACCENT
        val candidates = listOfNotNull(
            palette.vibrantSwatch, palette.darkVibrantSwatch, palette.dominantSwatch, palette.mutedSwatch,
        )
        // Skip near-white or near-black swatches (logo backgrounds); they make a dull glow.
        val pick = candidates.firstOrNull { ColorUtils.calculateLuminance(it.rgb) in 0.03..0.75 }
        return pick?.let { Color(it.rgb) } ?: DEFAULT_ACCENT
    }

    fun launch(packageName: String) {
        val intent = pm.getLeanbackLaunchIntentForPackage(packageName)
            ?: pm.getLaunchIntentForPackage(packageName)
            ?: return
        start(intent)
    }

    fun openAppInfo(packageName: String) {
        start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    fun start(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "No activity for $intent", e)
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "Not allowed to start $intent", e)
        false
    }

    companion object {
        const val TAG = "AppRepository"
        val DEFAULT_ACCENT = Color(0xFF34477A)
        const val BANNER_W = 320
        const val BANNER_H = 180
        const val ICON_SIZE = 160
    }
}
