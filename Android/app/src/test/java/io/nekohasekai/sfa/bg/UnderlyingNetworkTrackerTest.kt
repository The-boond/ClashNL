package io.nekohasekai.sfa.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnderlyingNetworkTrackerTest {
    @Test
    fun `platform preferred network wins fallback scoring`() {
        val selected =
            selectUnderlyingNetwork(
                listOf(
                    candidate("active", id = 1, active = true, validated = true),
                    candidate("platform", id = 2, platformPreferred = true),
                ),
            )

        assertEquals("platform", selected)
    }

    @Test
    fun `active network wins when platform callback has not reported`() {
        val selected =
            selectUnderlyingNetwork(
                listOf(
                    candidate("validated", id = 1, validated = true),
                    candidate("active", id = 2, active = true),
                ),
            )

        assertEquals("active", selected)
    }

    @Test
    fun `validated network wins before retaining an unvalidated current network`() {
        val selected =
            selectUnderlyingNetwork(
                listOf(
                    candidate("current", id = 1, current = true),
                    candidate("validated", id = 2, validated = true),
                ),
            )

        assertEquals("validated", selected)
    }

    @Test
    fun `current network stabilizes an otherwise equal selection`() {
        val selected =
            selectUnderlyingNetwork(
                listOf(
                    candidate("first", id = 1, validated = true),
                    candidate("current", id = 2, validated = true, current = true),
                ),
            )

        assertEquals("current", selected)
    }

    @Test
    fun `stable network handle breaks a complete tie`() {
        val selected =
            selectUnderlyingNetwork(
                listOf(
                    candidate("larger", id = 9),
                    candidate("smaller", id = 3),
                ),
            )

        assertEquals("smaller", selected)
    }

    @Test
    fun `empty candidates return no network`() {
        assertNull(selectUnderlyingNetwork<String>(emptyList()))
    }

    private fun candidate(
        name: String,
        id: Long,
        platformPreferred: Boolean = false,
        active: Boolean = false,
        validated: Boolean = false,
        current: Boolean = false,
    ) = UnderlyingNetworkCandidate(
        value = name,
        stableId = id,
        platformPreferred = platformPreferred,
        active = active,
        validated = validated,
        current = current,
    )
}
