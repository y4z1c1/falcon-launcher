package dev.yusuf.falconlauncher.tv

import android.util.Log

/**
 * Switches TCL picture modes through TCL's own picture-quality SDK (the `com.tcl.tv.display`
 * shared library, declared optional in the manifest). Reflection keeps the build free of TCL's jar
 * and makes this a no-op on TVs without it.
 */
object PictureModes {
    private const val TAG = "PictureModes"

    private val manager: Any? by lazy {
        runCatching { Class.forName("com.tcl.tv.pq.TvPqManager").getMethod("getInstance").invoke(null) }
            .onFailure { Log.w(TAG, "TCL picture SDK unavailable", it) }
            .getOrNull()
    }

    fun current(): Int? = manager?.let { m ->
        runCatching { m.javaClass.getMethod("getPictureMode").invoke(m) as Int }
            .onFailure { Log.w(TAG, "getPictureMode failed", it) }
            .getOrNull()
    }

    /**
     * Applies [mode] to the current source and saves it, like choosing it in TCL's menu. Mode numbers are
     * TCL's internal ones (as returned by [current]); found by experiment, the third int is the mode.
     */
    fun set(mode: Int): Boolean {
        val m = manager ?: return false
        return runCatching {
            val applyCls = Class.forName("com.tcl.tv.pq.EnApplyType")
            val fieldCls = Class.forName("com.tcl.tv.pq.EnPicModeFieldType")
            val actCls = Class.forName("com.tcl.tv.pq.EnTclActType")
            m.javaClass.getMethod(
                "setPictureMode", Int::class.javaPrimitiveType, applyCls, Int::class.javaPrimitiveType, fieldCls, actCls,
            ).invoke(
                m, 0, enumValue(applyCls, "EN_APPLY_CURRENT"), mode,
                enumValue(fieldCls, "EN_PICTURE_MODE_FIELD_NORMAL"), enumValue(actCls, "EN_TCL_ACT_EXEC_SAVE"),
            ) as Boolean
        }.onFailure { Log.w(TAG, "setPictureMode($mode) failed", it) }.getOrDefault(false)
    }

    private fun enumValue(cls: Class<*>, name: String): Any =
        cls.enumConstants!!.first { (it as Enum<*>).name == name }
}
