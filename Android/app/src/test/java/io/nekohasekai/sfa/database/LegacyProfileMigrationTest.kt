package io.nekohasekai.sfa.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyProfileMigrationTest {
    @Test
    fun disablesOnlyLegacyAutoUpdatesAndReplacesLegacySelection() {
        val profiles =
            listOf(
                profile(1, ProfileCore.SingBox, autoUpdate = true),
                profile(2, ProfileCore.SingBox, autoUpdate = false),
                profile(3, ProfileCore.Mihomo, autoUpdate = true),
                profile(4, ProfileCore.Mihomo, autoUpdate = false),
            )

        val plan = LegacyProfileMigration.plan(profiles, selectedProfileId = 1)

        assertEquals(setOf(1L), plan.disableAutoUpdateProfileIds)
        assertTrue(plan.replaceSelection)
        assertEquals(3L, plan.replacementProfileId)
    }

    @Test
    fun keepsAValidMihomoSelection() {
        val profiles =
            listOf(
                profile(1, ProfileCore.SingBox, autoUpdate = true),
                profile(2, ProfileCore.Mihomo, autoUpdate = false),
            )

        val plan = LegacyProfileMigration.plan(profiles, selectedProfileId = 2)

        assertFalse(plan.replaceSelection)
        assertEquals(2L, plan.replacementProfileId)
    }

    @Test
    fun clearsSelectionWhenNoMihomoProfileExists() {
        val plan =
            LegacyProfileMigration.plan(
                profiles = listOf(profile(1, ProfileCore.SingBox, autoUpdate = true)),
                selectedProfileId = 99,
            )

        assertTrue(plan.replaceSelection)
        assertEquals(-1L, plan.replacementProfileId)
    }

    private fun profile(id: Long, core: ProfileCore, autoUpdate: Boolean): Profile = Profile(id = id).apply {
        typed.core = core
        typed.autoUpdate = autoUpdate
    }
}
