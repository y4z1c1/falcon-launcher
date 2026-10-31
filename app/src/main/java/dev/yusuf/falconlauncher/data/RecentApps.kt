package dev.yusuf.falconlauncher.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Apps in the order they were last in front, newest first: the app switcher's list. Android doesn't share
 * its own recents with apps, so the Home button's accessibility service reports each app as it comes to
 * the front. Kept across restarts.
 */
class RecentApps(context: Context) {
    private val prefs = context.getSharedPreferences("recent_apps", Context.MODE_PRIVATE)
    private val _packages = MutableStateFlow(
        prefs.getString(KEY, null)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
    )
    val packages: StateFlow<List<String>> = _packages.asStateFlow()

    fun onForeground(packageName: String) {
        if (_packages.value.firstOrNull() == packageName) return
        save((listOf(packageName) + (_packages.value - packageName)).take(MAX))
    }

    fun remove(packageName: String) {
        if (packageName in _packages.value) save(_packages.value - packageName)
    }

    private fun save(list: List<String>) {
        _packages.value = list
        prefs.edit { putString(KEY, list.joinToString(",")) }
    }

    private companion object {
        const val KEY = "packages"
        const val MAX = 12
    }
}
