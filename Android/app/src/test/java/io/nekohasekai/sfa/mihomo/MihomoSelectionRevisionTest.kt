package io.nekohasekai.sfa.mihomo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MihomoSelectionRevisionTest {
    @Test
    fun `publish advances revision and exposes accepted selection`() {
        val previousRevision = MihomoSelectionRevision.currentRevision

        MihomoSelectionRevision.publish(profileId = 42L, group = "Proxy", proxy = "Tokyo")

        val change = MihomoSelectionRevision.changes.value
        assertNotNull(change)
        checkNotNull(change)
        assertTrue(change.revision > previousRevision)
        assertEquals(42L, change.profileId)
        assertEquals("Proxy", change.group)
        assertEquals("Tokyo", change.proxy)
    }
}
