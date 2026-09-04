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

    @Test
    fun ruleModeChoosesUnreferencedRootRegardlessOfApiOrder() {
        val groups = listOf(
            group("AUTO", "Japan-Main", proxies = listOf("Japan-Main", "Japan-Backup")),
            group("PROXY", "Japan-Dedicated-TCP", proxies = listOf("AUTO", "Japan-Dedicated-TCP")),
            group("GLOBAL", "PROXY", proxies = listOf("PROXY", "DIRECT")),
        )

        assertEquals(
            DashboardProxySelection("PROXY", "Japan-Dedicated-TCP"),
            dashboardProxySelection("rule", groups),
        )
    }

    @Test
    fun ruleModeUsesProfileOrderWhenGroupGraphHasSeveralRoots() {
        val groups = listOf(
            group("AUTO", "Japan-Main", proxies = listOf("Japan-Main", "Japan-Backup")),
            group("PROXY", "Japan-Dedicated-TCP", proxies = listOf("Japan-Dedicated-TCP", "DIRECT")),
            group("GLOBAL", "PROXY", proxies = listOf("PROXY", "DIRECT")),
        )

        assertEquals(
            DashboardProxySelection("PROXY", "Japan-Dedicated-TCP"),
            dashboardProxySelection("rule", groups, preferredRuleGroup = "PROXY"),
        )
    }

    private fun group(
        name: String,
        selected: String,
        proxies: List<String> = emptyList(),
    ) = MihomoProxyGroup(
        name = name,
        type = "Selector",
        selected = selected,
        selectable = true,
        proxies = proxies.map { io.nekohasekai.sfa.mihomo.MihomoProxy(name = it, type = "") },
    )
}
