package io.nekohasekai.sfa.latency

import io.nekohasekai.sfa.repository.MihomoProxyGroupRepository

/** Runs the existing latency coordinator against Mihomo's authenticated API. */
class MihomoLatencyProbe(
    private val repository: MihomoProxyGroupRepository,
    private val url: String = DEFAULT_TEST_URL,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : LatencyProbe {
    override suspend fun sample(target: LatencyTarget): LatencySample = repository
        .testDelay(target.nodeTag, url, timeoutMillis)
        .let { LatencySample.success(it.delayMillis.toLong()) }

    override suspend fun close() = Unit

    companion object {
        const val DEFAULT_TEST_URL = "https://www.gstatic.com/generate_204"
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L
    }
}
