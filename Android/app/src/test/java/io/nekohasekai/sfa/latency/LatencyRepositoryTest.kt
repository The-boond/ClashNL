package io.nekohasekai.sfa.latency

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatencyRepositoryTest {
    @After
    fun tearDown() {
        LatencyRepository.clearForTests()
    }

    @Test
    fun freshSuccessIsCacheHitAndOldSuccessExpires() {
        val target = target(network = "wifi:1")
        LatencyRepository.put(success(target, testedAt = 1_000L))

        assertEquals(1_000L, LatencyRepository.getFresh(target, now = 1_000L + 1_000L)?.testedAt)
        assertNull(
            LatencyRepository.getFresh(
                target,
                now = 1_000L + NodeLatencyResult.CACHE_VALIDITY_MS,
            ),
        )
        assertEquals(
            LatencyResultStatus.EXPIRED,
            LatencyRepository.getForDisplay(
                profileId = target.profileId,
                groupTag = target.groupTag,
                nodeTag = target.nodeTag,
                currentNetworkKey = target.networkKey,
                now = 1_000L + NodeLatencyResult.CACHE_VALIDITY_MS,
            )?.status,
        )
    }

    @Test
    fun networkChangeMarksPreviousResultsExpired() {
        val target = target(network = "wifi:1")
        LatencyRepository.put(success(target, testedAt = 5_000L))
        LatencyRepository.markNetworkChanged("cellular:2")

        assertEquals(
            LatencyResultStatus.EXPIRED,
            LatencyRepository.getForDisplay(
                target.profileId,
                target.groupTag,
                target.nodeTag,
                "cellular:2",
                now = 5_100L,
            )?.status,
        )
    }

    @Test
    fun nodeResultLookupSpansNetworks() {
        val target = target(network = "wifi:1")
        LatencyRepository.put(success(target, testedAt = 5_000L))

        assertEquals(true, LatencyRepository.hasResultForNode(1, "GROUP", "NODE"))
        assertEquals(false, LatencyRepository.hasResultForNode(2, "GROUP", "NODE"))
        assertEquals(false, LatencyRepository.hasResultForNode(1, "OTHER_GROUP", "NODE"))
        assertEquals(false, LatencyRepository.hasResultForNode(1, "GROUP", "OTHER_NODE"))
    }

    @Test
    fun profileUpdateAndNodeDeletionPruneOnlyInactiveKeys() {
        val first = target(profile = 7, group = "GROUP", node = "NODE_A", network = "wifi:1")
        val second = target(profile = 7, group = "GROUP", node = "NODE_B", network = "wifi:1")
        val oldGroup = target(profile = 7, group = "OLD_GROUP", node = "NODE_C", network = "wifi:1")
        val otherProfile = target(profile = 8, group = "GROUP", node = "NODE_A", network = "wifi:1")
        listOf(first, second, oldGroup, otherProfile).forEach { LatencyRepository.put(success(it, 1_000L)) }

        LatencyRepository.prune(7, mapOf("GROUP" to setOf("NODE_A")))

        assertEquals(LatencyResultStatus.SUCCESS, LatencyRepository.get(first.key)?.status)
        assertNull(LatencyRepository.get(second.key))
        assertNull(LatencyRepository.get(oldGroup.key))
        assertEquals(LatencyResultStatus.SUCCESS, LatencyRepository.get(otherProfile.key)?.status)
    }

    @Test
    fun cancelledAndTestingStatesRemainAvailableForDisplay() {
        val target = target(network = "wifi:1")
        LatencyRepository.markTesting(target, testedAt = 9_000L)
        assertEquals(
            LatencyResultStatus.TESTING,
            LatencyRepository.getForDisplay(1, "GROUP", "NODE", "wifi:1", 9_001L)?.status,
        )
        LatencyRepository.markCancelled(target, testedAt = 9_100L)
        assertEquals(
            LatencyResultStatus.CANCELLED,
            LatencyRepository.get(target.key)?.status,
        )
    }

    private fun target(
        profile: Long = 1,
        group: String = "GROUP",
        node: String = "NODE",
        network: String,
    ) = LatencyTarget(profile, group, node, network, LatencyResultSource.OFFLINE_PROBE)

    private fun success(target: LatencyTarget, testedAt: Long) = NodeLatencyResult(
        profileId = target.profileId,
        groupTag = target.groupTag,
        nodeTag = target.nodeTag,
        method = target.method,
        samplesMs = listOf(20L, 30L, 40L),
        medianMs = 30L,
        minMs = 20L,
        maxMs = 40L,
        firstConnectMs = 100L,
        failedSamples = 0,
        testedAt = testedAt,
        networkKey = target.networkKey,
        source = target.source,
        status = LatencyResultStatus.SUCCESS,
    )
}
