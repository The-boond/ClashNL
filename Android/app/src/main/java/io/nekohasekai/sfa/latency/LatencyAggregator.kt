package io.nekohasekai.sfa.latency

object LatencyAggregator {
    fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2
        }
    }

    fun aggregate(
        target: LatencyTarget,
        firstConnect: LatencySample,
        stableSamples: List<LatencySample>,
        testedAt: Long = System.currentTimeMillis(),
    ): NodeLatencyResult {
        val firstConnectMs = firstConnect.latencyMs
        val samplesMs = stableSamples.mapNotNull { it.latencyMs }.sorted()
        val allSamples = listOf(firstConnect) + stableSamples
        val failedSamples = allSamples.count { !it.isSuccess }
        val hasTimeout = allSamples.any { it.timedOut }
        val status = when {
            samplesMs.isNotEmpty() -> LatencyResultStatus.SUCCESS
            hasTimeout -> LatencyResultStatus.TIMEOUT
            else -> LatencyResultStatus.FAILED
        }
        val errorMessage = allSamples.mapNotNull { it.errorMessage }.firstOrNull()
        return NodeLatencyResult(
            profileId = target.profileId,
            groupTag = target.groupTag,
            nodeTag = target.nodeTag,
            method = target.method,
            samplesMs = samplesMs,
            medianMs = median(samplesMs),
            minMs = samplesMs.minOrNull(),
            maxMs = samplesMs.maxOrNull(),
            firstConnectMs = firstConnectMs,
            failedSamples = failedSamples,
            testedAt = testedAt,
            networkKey = target.networkKey,
            source = target.source,
            status = status,
            errorMessage = errorMessage,
        )
    }
}
