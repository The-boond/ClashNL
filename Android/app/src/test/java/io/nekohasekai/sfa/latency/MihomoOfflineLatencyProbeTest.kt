package io.nekohasekai.sfa.latency

import io.nekohasekai.sfa.mihomo.MihomoDelayResult
import io.nekohasekai.sfa.mihomo.MihomoProbeSession
import io.nekohasekai.sfa.mihomo.MihomoProxyGroup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MihomoOfflineLatencyProbeTest {
    @Test
    fun samplesThroughHeadlessSessionAndClosesIt() = runBlocking {
        val session = FakeProbeSession()
        val probe = MihomoOfflineLatencyProbe(session)
        val target = LatencyTarget(1, "PROXY", "NODE", "wifi", LatencyResultSource.OFFLINE_PROBE)

        assertEquals(42L, probe.sample(target).latencyMs)
        probe.close()

        assertEquals("NODE", session.lastProxy)
        assertTrue(session.closed)
    }

    private class FakeProbeSession : MihomoProbeSession {
        var lastProxy: String? = null
        var closed = false

        override suspend fun getProxyGroups(): List<MihomoProxyGroup> = emptyList()

        override suspend fun testDelay(proxy: String, url: String, timeoutMillis: Long): MihomoDelayResult {
            lastProxy = proxy
            return MihomoDelayResult(42)
        }

        override suspend fun close() {
            closed = true
        }
    }
}
