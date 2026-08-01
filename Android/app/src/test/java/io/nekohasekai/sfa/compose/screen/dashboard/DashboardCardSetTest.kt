package io.nekohasekai.sfa.compose.screen.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardCardSetTest {
    @Test
    fun keepsOnlyProxyModeAndTrafficAsConfigurableCards() {
        assertEquals(
            listOf(CardGroup.ProxyMode, CardGroup.TrafficStats),
            configurableDashboardCards,
        )
    }
}
