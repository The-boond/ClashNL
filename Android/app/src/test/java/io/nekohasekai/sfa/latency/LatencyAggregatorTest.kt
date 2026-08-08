package io.nekohasekai.sfa.latency

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatencyAggregatorTest {
    @Test
    fun medianUsesSortedStableSamples() {
        assertEquals(20L, LatencyAggregator.median(listOf(30, 10, 20)))
        assertEquals(25L, LatencyAggregator.median(listOf(10, 40)))
        assertNull(LatencyAggregator.median(emptyList()))
    }

    @Test
    fun failuresAreCountedWithoutDiscardingSuccessfulStableSamples() {
        val result = LatencyAggregator.aggregate(
            target = target(),
            firstConnect = LatencySample.timeout("first timeout"),
            stableSamples = listOf(
                LatencySample.success(30),
                LatencySample.failure("connection reset"),
                LatencySample.success(10),
            ),
        )

        assertEquals(LatencyResultStatus.SUCCESS, result.status)
        assertEquals(listOf(10L, 30L), result.samplesMs)
        assertEquals(20L, result.medianMs)
        assertEquals(2, result.failedSamples)
        assertNull(result.firstConnectMs)
    }

    @Test
    fun allTimeoutsProduceTimeoutStatus() {
        val timeout = LatencySample.timeout()
        val result = LatencyAggregator.aggregate(
            target = target(),
            firstConnect = timeout,
            stableSamples = listOf(timeout, timeout, timeout),
        )

        assertEquals(LatencyResultStatus.TIMEOUT, result.status)
        assertEquals(4, result.failedSamples)
        assertNull(result.medianMs)
    }

    private fun target() = LatencyTarget(
        profileId = 1,
        groupTag = "GROUP",
        nodeTag = "NODE",
        networkKey = "wifi:1",
        source = LatencyResultSource.OFFLINE_PROBE,
    )
}
