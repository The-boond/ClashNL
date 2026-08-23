package io.nekohasekai.sfa.compose.screen.dashboard

import io.nekohasekai.sfa.mihomo.MihomoProxyGroup
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardProxySelectionTest {
    @Test
    fun ruleModeIgnoresMihomosSyntheticGlobalGroup() {
        val groups = listOf(group("GLOBAL", "DIRECT"), group("PROXY", "TEST-DIRECT"))

        assertEquals(
            DashboardProxySelection("PROXY", "TEST-DIRECT"),
            dashboardProxySelection("rule", groups),
        )
    }

    @Test
    fun globalModeResolvesNestedSelectionToLeafNode() {
        val groups = listOf(group("GLOBAL", "PROXY"), group("PROXY", "TEST-DIRECT"))

        assertEquals(
            DashboardProxySelection("GLOBAL", "TEST-DIRECT"),
            dashboardProxySelection("global", groups),
        )
    }

    @Test
    fun directModeAlwaysReportsDirect() {
        assertEquals(
            DashboardProxySelection("DIRECT", "DIRECT"),
            dashboardProxySelection("direct", listOf(group("GLOBAL", "PROXY"))),
        )
    }

    @Test
    fun unknownModeUsesRuleModeFallback() {
        val groups = listOf(group("GLOBAL", "DIRECT"), group("AUTO", "FASTEST"))

        assertEquals(
            DashboardProxySelection("AUTO", "FASTEST"),
            dashboardProxySelection("future-mode", groups),
        )
    }

    private fun group(name: String, selected: String) = MihomoProxyGroup(
        name = name,
        type = "Selector",
        selected = selected,
        selectable = true,
    )
}
