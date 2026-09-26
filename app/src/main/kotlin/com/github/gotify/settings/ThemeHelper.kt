package com.github.gotify.settings

import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.github.gotify.R

/**
 * 主题偏好的读取与应用。
 *
 * 偏好里存的是与语言无关的稳定 key（[THEME_SYSTEM] / [THEME_LIGHT] / [THEME_DARK]），
 * 这样切换系统语言后仍能还原出正确的主题。旧版本写入的是本地化文案
 * （Light / Dark / System Default / 浅色 / 深色 / 跟随系统），
 * [normalize] 会把这些旧值换算成稳定 key 并在下次应用主题时写回，
 * 未知值一律按跟随系统处理。
 */
internal object ThemeHelper {
    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    private val lightValues = setOf("light", "浅色")
    private val darkValues = setOf("dark", "深色")

    fun setTheme(context: Context, newTheme: String) {
        val theme = normalize(newTheme)
        AppCompatDelegate.setDefaultNightMode(ofKey(theme))
        migrate(context, newTheme, theme)
    }

    /** 把偏好中的旧值换算成稳定 key；无法识别时回落到跟随系统。 */
    fun normalize(newTheme: String?): String {
        val value = newTheme?.trim().orEmpty()
        return when {
            lightValues.any { it.equals(value, ignoreCase = true) } -> THEME_LIGHT
            darkValues.any { it.equals(value, ignoreCase = true) } -> THEME_DARK
            else -> THEME_SYSTEM
        }
    }

    private fun ofKey(theme: String): Int {
        return when (theme) {
            THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES

            THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO

            else -> if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY
            } else {
                AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        }
    }

    private fun migrate(context: Context, stored: String, theme: String) {
        if (stored == theme) {
            return
        }
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit { putString(context.getString(R.string.setting_key_theme), theme) }
    }
}
