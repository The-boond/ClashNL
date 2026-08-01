package io.nekohasekai.sfa.compose.theme

import androidx.appcompat.app.AppCompatDelegate

enum class AppThemeMode(
    val value: String,
    val nightMode: Int,
) {
    SYSTEM("system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT("light", AppCompatDelegate.MODE_NIGHT_NO),
    DARK("dark", AppCompatDelegate.MODE_NIGHT_YES),
    ;

    companion object {
        fun fromValue(value: String): AppThemeMode = entries.firstOrNull { it.value == value } ?: SYSTEM
    }
}
