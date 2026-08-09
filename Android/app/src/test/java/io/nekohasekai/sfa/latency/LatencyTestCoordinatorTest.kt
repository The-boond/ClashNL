package io.nekohasekai.sfa.latency

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class LatencyTestCoordinatorTest {
    @After
    fun tearDown() {
        LatencyRepository.clearForTests()
    }

    @Test
    fun coordinatorLimitsNodeConcurrencyAndReleasesProbe() = runBlocking {
        val probe = CountingProbe(delayMs = 10)
        val targets = (0 until 9).map { target("NODE_$it") }
        val results = Collections.synchronizedList(mutableListOf<NodeLatencyResult>())

        LatencyTestCoordinator(sampleTimeoutMs = 500).run(
            targets = targets,
            probeFactory = LatencyProbeFactory { probe },
            onUpdate = { results += it },
        )

        assertTrue(probe.maxActive.get() <= LatencyTestCoordinator.MAX_CONCURRENCY)
        assertEquals(9, results.count { it.status == LatencyResultStatus.SUCCESS })
        assertTrue(probe.closed)
    }

    @Test
    fun timeoutAndFailureSamplesProduceExplicitStates() = runBlocking {
        val timeoutProbe = object : LatencyProbe {
            override suspend fun sample(target: LatencyTarget): LatencySample {
                delay(100)
                return LatencySample.success(1)
            }

            override suspend fun close() {}
        }
        val target = target("TIMEOUT_NODE")
        val results = mutableListOf<NodeLatencyResult>()
        LatencyTestCoordinator(sampleTimeoutMs = 10).run(
            listOf(target),
            LatencyProbeFactory { timeoutProbe },
            results::add,
        )
        assertEquals(LatencyResultStatus.TIMEOUT, results.last().status)
        assertEquals(4, results.last().failedSamples)
    }

    @Test
    fun cancellationClosesProbeAndMarksActiveNode() = runBlocking {
        val probe = CountingProbe(delayMs = 5_000)
        val target = target("CANCEL_NODE")
        val updates = mutableListOf<NodeLatencyResult>()
        val job = launch {
            try {
                LatencyTestCoordinator(sampleTimeoutMs = 10_000).run(
                    listOf(target),
                    LatencyProbeFactory { probe },
                    updates::add,
                )
            } catch (_: CancellationException) {
            }
        }
        withTimeout(1_000) {
            while (updates.none { it.status == LatencyResultStatus.TESTING }) delay(5)
        }
        job.cancel()
        job.join()

        assertTrue(probe.closed)
        assertEquals(LatencyResultStatus.CANCELLED, LatencyRepository.get(target.key)?.status)
    }

    @Test
    fun freshCacheSkipsProbeSamples() = runBlocking {
        val target = target("CACHED_NODE")
        LatencyRepository.put(
            NodeLatencyResult(
                profileId = target.profileId,
                groupTag = target.groupTag,
                nodeTag = target.nodeTag,
                method = target.method,
                samplesMs = listOf(12L, 14L, 16L),
                medianMs = 14L,
                minMs = 12L,
                maxMs = 16L,
                firstConnectMs = 30L,
                failedSamples = 0,
                testedAt = System.currentTimeMillis(),
                networkKey = target.networkKey,
                source = target.source,
                status = LatencyResultStatus.SUCCESS,
            ),
        )
        val probe = CountingProbe(delayMs = 1)
        val updates = mutableListOf<NodeLatencyResult>()
        LatencyTestCoordinator().run(listOf(target), LatencyProbeFactory { probe }, updates::add)

        assertEquals(0, probe.samples.get())
        assertEquals(LatencyResultStatus.CACHED, updates.last().status)
    }

    private fun target(node: String) = LatencyTarget(
        profileId = 1,
        groupTag = "GROUP",
        nodeTag = node,
        networkKey = "wifi:1",
        source = LatencyResultSource.OFFLINE_PROBE,
    )

    private class CountingProbe(private val delayMs: Long) : LatencyProbe {
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val samples = AtomicInteger()
        var closed = false

        override suspend fun sample(target: LatencyTarget): LatencySample {
            samples.incrementAndGet()
            val current = active.incrementAndGet()
            maxActive.updateAndGet { previous -> maxOf(previous, current) }
            try {
                delay(delayMs)
                return LatencySample.success(20)
            } finally {
                active.decrementAndGet()
            }
        }

        override suspend fun close() {
            closed = true
        }
    }
}
