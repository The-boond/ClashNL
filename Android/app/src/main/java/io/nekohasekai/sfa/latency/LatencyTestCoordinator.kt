package io.nekohasekai.sfa.latency

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

interface LatencyProbe {
    suspend fun sample(target: LatencyTarget): LatencySample

    suspend fun close()
}

fun interface LatencyProbeFactory {
    suspend fun create(): LatencyProbe
}

class LatencyTestCoordinator(
    private val repository: LatencyRepository = LatencyRepository,
    private val maxConcurrency: Int = MAX_CONCURRENCY,
    private val sampleTimeoutMs: Long = NodeLatencyResult.SAMPLE_TIMEOUT_MS,
) {
    fun start(
        scope: kotlinx.coroutines.CoroutineScope,
        targets: List<LatencyTarget>,
        probeFactory: LatencyProbeFactory,
        onUpdate: (NodeLatencyResult) -> Unit,
    ): Job = scope.launch(Dispatchers.IO) {
        run(targets, probeFactory, onUpdate)
    }

    suspend fun run(
        targets: List<LatencyTarget>,
        probeFactory: LatencyProbeFactory,
        onUpdate: (NodeLatencyResult) -> Unit,
    ) {
        if (targets.isEmpty()) return
        var probe: LatencyProbe? = null
        try {
            val openedProbe = try {
                probeFactory.create()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                targets.forEach { target ->
                    val result = LatencyAggregator.aggregate(
                        target = target,
                        firstConnect = LatencySample.failure(exception.message),
                        stableSamples = List(NodeLatencyResult.STABLE_SAMPLE_COUNT) {
                            LatencySample.failure(exception.message)
                        },
                    )
                    repository.put(result)
                    onUpdate(result)
                }
                return
            }
            probe = openedProbe
            val semaphore = Semaphore(maxConcurrency.coerceAtLeast(1))
            coroutineScope {
                targets.map { target ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            runTarget(target, openedProbe, onUpdate)
                        }
                    }
                }.awaitAll()
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { probe?.close() }
            }
        }
    }

    private suspend fun runTarget(
        target: LatencyTarget,
        probe: LatencyProbe,
        onUpdate: (NodeLatencyResult) -> Unit,
    ) {
        val fresh = repository.getFresh(target)
        if (fresh != null) {
            onUpdate(fresh.copy(status = LatencyResultStatus.CACHED))
            return
        }

        onUpdate(repository.markTesting(target))
        try {
            val firstConnect = sampleWithTimeout(probe, target)
            val stableSamples = buildList {
                repeat(NodeLatencyResult.STABLE_SAMPLE_COUNT) {
                    add(sampleWithTimeout(probe, target))
                }
            }
            val result = LatencyAggregator.aggregate(target, firstConnect, stableSamples)
            repository.put(result)
            onUpdate(result)
        } catch (cancelled: CancellationException) {
            repository.markCancelled(target, message = cancelled.message)
            onUpdate(repository.get(target.key) ?: NodeLatencyResult.cancelled(target))
            throw cancelled
        } catch (exception: Exception) {
            val result = LatencyAggregator.aggregate(
                target = target,
                firstConnect = LatencySample.failure(exception.message),
                stableSamples = List(NodeLatencyResult.STABLE_SAMPLE_COUNT) {
                    LatencySample.failure(exception.message)
                },
            )
            repository.put(result)
            onUpdate(result)
        }
    }

    private suspend fun sampleWithTimeout(
        probe: LatencyProbe,
        target: LatencyTarget,
    ): LatencySample = try {
        withTimeout(sampleTimeoutMs) {
            probe.sample(target)
        }
    } catch (timeout: TimeoutCancellationException) {
        LatencySample.timeout(timeout.message)
    } catch (cancelled: CancellationException) {
        currentCoroutineContext().ensureActive()
        throw cancelled
    } catch (exception: Exception) {
        LatencySample.failure(exception.message)
    }

    companion object {
        const val MAX_CONCURRENCY = 4
    }
}
