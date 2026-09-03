package io.nekohasekai.sfa.compose.screen.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardIpTraceTest {
    @Test
    fun `parses Cloudflare trace fields`() {
        val trace =
            parseDashboardIpTrace(
                """
                fl=123
                ip=203.0.113.8
                loc=JP
                colo=NRT
                warp=off
                """.trimIndent(),
            )

        assertEquals("203.0.113.8", trace.ip)
        assertEquals("JP", trace.location)
        assertEquals("NRT", trace.colo)
    }

    @Test
    fun `optional blank fields are omitted`() {
        val trace = parseDashboardIpTrace("ip=2001:db8::1\nloc=\nmalformed")

        assertEquals("2001:db8::1", trace.ip)
        assertNull(trace.location)
        assertNull(trace.colo)
    }

    @Test(expected = IllegalStateException::class)
    fun `missing address is rejected`() {
        parseDashboardIpTrace("loc=US\ncolo=SJC")
    }

    @Test
    fun `unvalidated physical snapshot remains eligible for a pinned probe`() {
        assertEquals(true, shouldQueryDirectExit(hasUnderlyingNetwork = true, validated = false))
    }

    @Test
    fun `direct probe cannot run without a physical snapshot`() {
        assertEquals(false, shouldQueryDirectExit(hasUnderlyingNetwork = false, validated = true))
    }

    @Test
    fun `live selection for active local profile refreshes exits`() {
        assertEquals(
            DashboardSelectionAction.Refresh,
            dashboardSelectionAction(
                localSession = true,
                selectedProfileId = 7,
                changedProfileId = 7,
                serviceStarted = true,
            ),
        )
    }

    @Test
    fun `selection before started only invalidates stale proxy exit`() {
        assertEquals(
            DashboardSelectionAction.Invalidate,
            dashboardSelectionAction(
                localSession = true,
                selectedProfileId = 7,
                changedProfileId = 7,
                serviceStarted = false,
            ),
        )
    }

    @Test
    fun `remote state invalidates without making an out of scope request`() {
        assertEquals(
            DashboardSelectionAction.Invalidate,
            dashboardSelectionAction(
                localSession = false,
                selectedProfileId = 7,
                changedProfileId = 7,
                serviceStarted = true,
            ),
        )
    }

    @Test
    fun `different profile selection is ignored`() {
        assertEquals(
            DashboardSelectionAction.Ignore,
            dashboardSelectionAction(
                localSession = true,
                selectedProfileId = 7,
                changedProfileId = 8,
                serviceStarted = true,
            ),
        )
    }
}
