package com.sztu.schedule.data

import android.content.Context
import android.content.res.Configuration

/**
 * 外观（浅色 / 深色）在原生侧的持久化副本。
 *
 * 前端的 localStorage 是主题模式的唯一权威；原生只知道**当前实际显示**的深浅，
 * 用于：
 * - 让 [com.sztu.schedule.CropBackgroundActivity] 这类自有原生页取到同一个外观；
 * - 系统栏图标明暗（浅色外观用深色图标）。
 *
 * 只存一个布尔值，不含任何账号/会话信息，因此不进入备份排除清单。
 */
object AppearanceStore {
    private const val PREFS = "sztu_appearance"
    private const val KEY_DARK = "dark"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 前端主题切换后同步过来（桥方法 setSystemBars）。 */
    fun setDark(context: Context, dark: Boolean) {
        prefs(context).edit().putBoolean(KEY_DARK, dark).apply()
    }

    /**
     * 前端还没报过外观时按系统判断（深色模式 = night mask）。
     * 这样裁切页首帧不会出现「浅色系统栏 + 黑色画布」的割裂。
     */
    fun isDark(context: Context): Boolean {
        val p = prefs(context)
        if (p.contains(KEY_DARK)) return p.getBoolean(KEY_DARK, false)
        val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }
}
