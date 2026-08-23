package io.nekohasekai.sfa.compose.screen.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardCardSetTest {
    @Test
    fun keepsNetworkModeProxyModeAndTrafficAsConfigurableCards() {
        assertEquals(
            listOf(CardGroup.NetworkSettings, CardGroup.ProxyMode, CardGroup.TrafficStats),
            configurableDashboardCards,
        )
    }
}
