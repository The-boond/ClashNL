package io.nekohasekai.sfa.compose.screen.dashboard

import io.nekohasekai.sfa.compose.model.Group
import io.nekohasekai.sfa.compose.screen.dashboard.groups.visibleForMode
import org.junit.Assert.assertEquals
import org.junit.Test

class GroupsPresentationTest {
    @Test
    fun ruleModeHidesSyntheticGlobalWhenSubscriptionHasSelectableGroups() {
        val groups = listOf(group("GLOBAL"), group("NL"), group("故障转移", selectable = false))

        assertEquals(listOf("NL", "故障转移"), groups.visibleForMode("rule").map { it.tag })
    }

    @Test
    fun globalModeKeepsGlobalGroup() {
        val groups = listOf(group("GLOBAL"), group("NL"))

        assertEquals(listOf("GLOBAL", "NL"), groups.visibleForMode("global").map { it.tag })
    }

    @Test
    fun latencyBandsUseProductThresholds() {
        assertEquals(LatencyColorBand.GREEN, latencyColorBand(250))
        assertEquals(LatencyColorBand.BLUE, latencyColorBand(251))
        assertEquals(LatencyColorBand.BLUE, latencyColorBand(350))
        assertEquals(LatencyColorBand.ORANGE, latencyColorBand(351))
        assertEquals(LatencyColorBand.ORANGE, latencyColorBand(600))
        assertEquals(LatencyColorBand.RED, latencyColorBand(601))
        assertEquals(LatencyColorBand.RED, latencyColorBand(Int.MAX_VALUE))
    }

    private fun group(tag: String, selectable: Boolean = true) = Group(
        tag = tag,
        type = "Selector",
        selectable = selectable,
        selected = "node",
        isExpand = false,
        items = emptyList(),
    )
}
