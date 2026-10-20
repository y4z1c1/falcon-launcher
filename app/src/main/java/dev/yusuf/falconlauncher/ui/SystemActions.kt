package dev.yusuf.falconlauncher.ui

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.provider.Settings
import dev.yusuf.falconlauncher.service.HomeWatcherService

/** Intents for the shortcuts in the top bar. Each returns null when the TV has nothing to handle it. */
object SystemActions {
    private val TCL_INPUT_PACKAGES = listOf("com.tcl.tvinput", "com.tcl.inputsource")
    /** Apps that play TV inputs; preferred so the TCL browser doesn't claim the request. */
    private val TV_APP_PACKAGES = listOf("com.tcl.tv", "com.google.android.tv", "com.android.tv")
    private val HDMI_1 = Regex("""^HDMI\s*1\b""", RegexOption.IGNORE_CASE)

    /** Switches the TV to HDMI 1, where the cable receiver is plugged in. */
    fun hdmi1(context: Context): Intent? {
        val tvInputs = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager ?: return null
        val hdmiInputs = tvInputs.tvInputList.filter { it.type == TvInputInfo.TYPE_HDMI && it.isPassthroughInput }
        val input = hdmiInputs.firstOrNull { HDMI_1.containsMatchIn(it.loadLabel(context).toString()) }
            ?: hdmiInputs.minByOrNull { it.id }
            ?: return null
        val intent = Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(input.id))
        val tvApp = TV_APP_PACKAGES.firstOrNull { Intent(intent).setPackage(it).resolveActivity(context.packageManager) != null }
        return intent.setPackage(tvApp)
    }

    fun inputs(context: Context): Intent? {
        val pm = context.packageManager
        val viewInputs = Intent("com.android.tv.action.VIEW_INPUTS")
        if (viewInputs.resolveActivity(pm) != null) return viewInputs
        return TCL_INPUT_PACKAGES.firstNotNullOfOrNull { pkg ->
            pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
        }
    }

    fun settings(): Intent = Intent(Settings.ACTION_SETTINGS)

    fun notificationAccess(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    fun network(): Intent = Intent(Settings.ACTION_WIFI_SETTINGS)

    fun accessibilitySettings(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    fun stockHome(context: Context): Intent? = HomeWatcherService.STOCK_LAUNCHERS.firstNotNullOfOrNull { pkg ->
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(pkg)
            .takeIf { it.resolveActivity(context.packageManager) != null }
    }
}
