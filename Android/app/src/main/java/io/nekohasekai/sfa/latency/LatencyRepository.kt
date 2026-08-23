package io.nekohasekai.sfa.latency

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object LatencyRepository {
    private val access = Any()
    private val values = LinkedHashMap<LatencyKey, NodeLatencyResult>()
    private val _results = MutableStateFlow<Map<LatencyKey, NodeLatencyResult>>(emptyMap())

    val results = _results.asStateFlow()

    fun put(result: NodeLatencyResult) {
        synchronized(access) {
            values[result.key] = result
            publishLocked()
        }
    }

    fun markTesting(target: LatencyTarget, testedAt: Long = System.currentTimeMillis()): NodeLatencyResult {
        val result = NodeLatencyResult.testing(target, testedAt)
        put(result)
        return result
    }

    fun markCancelled(
        target: LatencyTarget,
        testedAt: Long = System.currentTimeMillis(),
        message: String? = null,
    ): NodeLatencyResult {
        val result = NodeLatencyResult.cancelled(target, testedAt, message)
        put(result)
        return result
    }

    fun get(key: LatencyKey): NodeLatencyResult? = synchronized(access) { values[key] }

    fun hasResultForNode(profileId: Long, groupTag: String, nodeTag: String): Boolean = synchronized(access) {
        values.keys.any { key ->
            key.profileId == profileId && key.groupTag == groupTag && key.nodeTag == nodeTag
        }
    }

    fun getFresh(target: LatencyTarget, now: Long = System.currentTimeMillis()): NodeLatencyResult? = synchronized(access) {
        values[target.key]?.takeIf { it.source == target.source && it.isFresh(now) }
    }

    fun getForDisplay(
        profileId: Long,
        groupTag: String,
        nodeTag: String,
        currentNetworkKey: String,
        now: Long = System.currentTimeMillis(),
    ): NodeLatencyResult? = synchronized(access) {
        val exactKey = LatencyKey(profileId, groupTag, nodeTag, currentNetworkKey)
        val exact = values[exactKey]
        if (exact != null) {
            return@synchronized displayValue(exact, currentNetworkKey, now)
        }
        values.values
            .asSequence()
            .filter { it.profileId == profileId && it.groupTag == groupTag && it.nodeTag == nodeTag }
            .maxByOrNull { it.testedAt }
            ?.let { displayValue(it, currentNetworkKey, now) }
    }

    fun markNetworkChanged(currentNetworkKey: String) {
        synchronized(access) {
            var changed = false
            values.entries.forEach { entry ->
                val value = entry.value
                if (value.networkKey != currentNetworkKey &&
                    value.status in setOf(LatencyResultStatus.SUCCESS, LatencyResultStatus.CACHED)
                ) {
                    entry.setValue(value.copy(status = LatencyResultStatus.EXPIRED))
                    changed = true
                }
            }
            if (changed) publishLocked()
        }
    }

    fun prune(profileId: Long, activeGroups: Map<String, Set<String>>) {
        synchronized(access) {
            val iterator = values.entries.iterator()
            var changed = false
            while (iterator.hasNext()) {
                val value = iterator.next().value
                val activeNodes = activeGroups[value.groupTag]
                if (value.profileId == profileId &&
                    (activeNodes == null || value.nodeTag !in activeNodes)
                ) {
                    iterator.remove()
                    changed = true
                }
            }
            if (changed) publishLocked()
        }
    }

    fun clearProfile(profileId: Long) {
        synchronized(access) {
            val changed = values.keys.removeIf { it.profileId == profileId }
            if (changed) publishLocked()
        }
    }

    fun clearForTests() {
        synchronized(access) {
            values.clear()
            publishLocked()
        }
    }

    private fun displayValue(
        value: NodeLatencyResult,
        currentNetworkKey: String,
        now: Long,
    ): NodeLatencyResult {
        if (value.networkKey != currentNetworkKey) {
            return value.copy(status = LatencyResultStatus.EXPIRED)
        }
        return if (value.isFresh(now)) {
            value.copy(status = LatencyResultStatus.CACHED)
        } else if (value.status == LatencyResultStatus.SUCCESS || value.status == LatencyResultStatus.CACHED) {
            value.copy(status = LatencyResultStatus.EXPIRED)
        } else {
            value
        }
    }

    private fun publishLocked() {
        _results.value = values.toMap()
    }
}
