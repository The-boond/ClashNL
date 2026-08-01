package io.nekohasekai.sfa.compose.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationDestinationsTest {
    @Test
    fun primaryNavigationKeepsOnlyTheSimpleThreeStepWorkflow() {
        assertEquals(
            listOf(
                Screen.Subscriptions.route,
                Screen.Dashboard.route,
                Screen.Settings.route,
            ),
            bottomNavigationScreens.map { it.route },
        )
    }

    @Test
    fun dashboardIsTheDefaultDestination() {
        assertEquals(Screen.Dashboard.route, primaryStartDestination.route)
    }
}
