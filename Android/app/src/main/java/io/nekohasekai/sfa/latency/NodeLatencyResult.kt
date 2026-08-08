package io.nekohasekai.sfa.latency

enum class LatencyTestMethod {
    PROXY_HTTP_HEAD,
}

enum class LatencyResultSource {
    LIVE_CORE,
    OFFLINE_PROBE,
}

enum class LatencyResultStatus {
    UNTESTED,
    TESTING,
    SUCCESS,
    TIMEOUT,
    FAILED,
    CANCELLED,
    EXPIRED,
    CACHED,
}

data class LatencyKey(
    val profileId: Long,
    val groupTag: String,
    val nodeTag: String,
    val networkKey: String,
)

data class LatencySample(
    val latencyMs: Long? = null,
    val timedOut: Boolean = false,
    val errorMessage: String? = null,
) {
    val isSuccess: Boolean
        get() = latencyMs != null && !timedOut

    companion object {
        fun success(latencyMs: Long) = LatencySample(latencyMs = latencyMs.coerceAtLeast(0))

        fun timeout(message: String? = null) = LatencySample(timedOut = true, errorMessage = message)

        fun failure(message: String? = null) = LatencySample(errorMessage = message)
    }
}

data class LatencyTarget(
    val profileId: Long,
    val groupTag: String,
    val nodeTag: String,
    val networkKey: String,
    val source: LatencyResultSource,
    val method: LatencyTestMethod = LatencyTestMethod.PROXY_HTTP_HEAD,
) {
    val key: LatencyKey
        get() = LatencyKey(profileId, groupTag, nodeTag, networkKey)
}

data class NodeLatencyResult(
    val profileId: Long,
    val groupTag: String,
    val nodeTag: String,
    val method: LatencyTestMethod,
    val samplesMs: List<Long>,
    val medianMs: Long?,
    val minMs: Long?,
    val maxMs: Long?,
    val firstConnectMs: Long?,
    val failedSamples: Int,
    val testedAt: Long,
    val networkKey: String,
    val source: LatencyResultSource,
    val status: LatencyResultStatus,
    val errorMessage: String? = null,
) {
    val key: LatencyKey
        get() = LatencyKey(profileId, groupTag, nodeTag, networkKey)

    fun isFresh(now: Long = System.currentTimeMillis()): Boolean = status == LatencyResultStatus.SUCCESS &&
        testedAt > 0 &&
        now - testedAt in 0 until CACHE_VALIDITY_MS

    fun asDisplayStatus(displayStatus: LatencyResultStatus): NodeLatencyResult = if (status == displayStatus) this else copy(status = displayStatus)

    companion object {
        const val STABLE_SAMPLE_COUNT = 3
        const val TOTAL_SAMPLE_COUNT = 4
        const val SAMPLE_TIMEOUT_MS = 5_000L
        const val CACHE_VALIDITY_MS = 10 * 60 * 1_000L

        fun testing(target: LatencyTarget, testedAt: Long = System.currentTimeMillis()): NodeLatencyResult = NodeLatencyResult(
            profileId = target.profileId,
            groupTag = target.groupTag,
            nodeTag = target.nodeTag,
            method = target.method,
            samplesMs = emptyList(),
            medianMs = null,
            minMs = null,
            maxMs = null,
            firstConnectMs = null,
            failedSamples = 0,
            testedAt = testedAt,
            networkKey = target.networkKey,
            source = target.source,
            status = LatencyResultStatus.TESTING,
        )

        fun cancelled(
            target: LatencyTarget,
            testedAt: Long = System.currentTimeMillis(),
            message: String? = null,
        ): NodeLatencyResult = testing(target, testedAt).copy(
            status = LatencyResultStatus.CANCELLED,
            errorMessage = message,
        )
    }
}
