package io.nekohasekai.sfa.latency

import io.nekohasekai.sfa.mihomo.MihomoProbeSession

/** Uses an exclusive headless Mihomo session while Android's VPN is stopped. */
class MihomoOfflineLatencyProbe(
    private val session: MihomoProbeSession,
    private val url: String = MihomoLatencyProbe.DEFAULT_TEST_URL,
    private val timeoutMillis: Long = MihomoLatencyProbe.DEFAULT_TIMEOUT_MILLIS,
) : LatencyProbe {
    override suspend fun sample(target: LatencyTarget): LatencySample = session
        .testDelay(target.nodeTag, url, timeoutMillis)
        .let { LatencySample.success(it.delayMillis.toLong()) }

    override suspend fun close() = session.close()
}
