package io.nekohasekai.sfa.compose.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class AppThemeModeTest {
    @Test
    fun defaultsToFollowingTheSystem() {
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromValue(""))
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromValue("legacy-theme"))
    }

    @Test
    fun keepsOnlyLightAndDarkOverrides() {
        assertEquals(AppThemeMode.LIGHT, AppThemeMode.fromValue("light"))
        assertEquals(AppThemeMode.DARK, AppThemeMode.fromValue("dark"))
    }
}
