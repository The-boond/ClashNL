package io.nekohasekai.sfa.compose.screen.dashboard

import io.nekohasekai.sfa.mihomo.MihomoProxyGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardProxySelectionTest {
    @Test
    fun firstSelectionInUnusedRegionDoesNotReplaceActiveDefaultRoute() {
        val groups = listOf(
            group("Japan", "JP-2", listOf("JP-1", "JP-2")),
            group("Main", "US", listOf("Japan", "US")),
            group("GLOBAL", "Japan"),
        )
        assertEquals(
            DashboardProxySelection("Main", "US"),
            dashboardProxySelection("rule", groups, preferredRuleGroup = "Japan", defaultRuleTarget = "Main"),
        )
        val switched = groups.map { if (it.name == "Main") it.copy(selected = "Japan") else it }
        assertEquals(
            DashboardProxySelection("Main", "JP-2"),
            dashboardProxySelection("rule", switched, defaultRuleTarget = "Main"),
        )
    }

    @Test
    fun defaultRouteResolvesAutomaticGroupsAndIgnoresApiOrder() {
        val groups = listOf(
            group("Service", "JP"),
            group("Auto", "US").copy(type = "URLTest", selectable = false),
            group("Main", "Auto"),
        )
        for (ordered in listOf(groups, groups.reversed())) {
            assertEquals(
                DashboardProxySelection("Main", "US"),
                dashboardProxySelection("rule", ordered, defaultRuleTarget = "Main"),
            )
        }
    }

    @Test
    fun defaultRuleCanTargetDirectOrSingleProxy() {
        for (target in listOf("DIRECT", "REJECT", "US")) {
            assertEquals(
                DashboardProxySelection(target, target),
                dashboardProxySelection("rule", listOf(group("Unused", "JP")), defaultRuleTarget = target),
            )
        }
    }

    @Test
    fun globalAndDirectModesIgnoreDefaultRule() {
        val groups = listOf(group("GLOBAL", "US"), group("Main", "JP"))
        assertEquals(DashboardProxySelection("GLOBAL", "US"), dashboardProxySelection("global", groups, defaultRuleTarget = "Main"))
        assertEquals(DashboardProxySelection("DIRECT", "DIRECT"), dashboardProxySelection("direct", groups, defaultRuleTarget = "Main"))
    }

    @Test
    fun brokenSelectionChainsNeverReportAGroupAsANode() {
        assertNull(dashboardProxySelection("rule", listOf(group("Main", "Nested"), group("Nested", "Main")), defaultRuleTarget = "Main"))
        assertNull(dashboardProxySelection("rule", listOf(group("Main", "Nested"), group("Nested", "")), defaultRuleTarget = "Main"))
    }

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
