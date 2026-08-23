package io.nekohasekai.sfa.latency

import io.nekohasekai.sfa.compose.model.Group
import io.nekohasekai.sfa.utils.CommandTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

data class LiveGroupUpdate(
    val sequence: Long,
    val groups: List<Group>,
)

class LiveCoreLatencyProbe(
    private val updates: Flow<LiveGroupUpdate>,
    private val currentSequence: () -> Long,
    private val trigger: suspend (String) -> Unit = { nodeTag ->
        CommandTarget.standaloneClient().urlTest(nodeTag)
    },
) : LatencyProbe {
    private val sampleMutex = Mutex()

    override suspend fun sample(target: LatencyTarget): LatencySample = sampleMutex.withLock {
        val baseline = currentSequence()
        trigger(target.nodeTag)
        withTimeout(NodeLatencyResult.SAMPLE_TIMEOUT_MS) {
            updates
                .filter { it.sequence > baseline }
                .mapNotNull { update ->
                    update.groups
                        .firstOrNull { it.tag == target.groupTag }
                        ?.items
                        ?.firstOrNull { it.tag == target.nodeTag }
                        ?.takeIf { it.urlTestTime > 0 && it.urlTestDelay > 0 }
                        ?.let { LatencySample.success(it.urlTestDelay.toLong()) }
                }.first()
        }
    }

    override suspend fun close() {
        // The probe owns no client or socket and only observes the existing
        // group stream.
    }
}
