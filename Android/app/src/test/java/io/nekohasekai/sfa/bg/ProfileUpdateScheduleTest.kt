package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileCore
import io.nekohasekai.sfa.database.TypedProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileUpdateScheduleTest {
    @Test
    fun onlyMihomoRemoteProfilesWithAutoUpdateAreEligible() {
        val profile =
            Profile(
                typed =
                TypedProfile().apply {
                    type = TypedProfile.Type.Remote
                    core = ProfileCore.Mihomo
                    autoUpdate = true
                },
            )

        assertTrue(ProfileUpdateSchedule.isEligible(profile))
        profile.typed.core = ProfileCore.SingBox
        assertFalse(ProfileUpdateSchedule.isEligible(profile))
        profile.typed.core = ProfileCore.Mihomo
        profile.typed.autoUpdate = false
        assertFalse(ProfileUpdateSchedule.isEligible(profile))
        profile.typed.autoUpdate = true
        profile.typed.type = TypedProfile.Type.Local
        assertFalse(ProfileUpdateSchedule.isEligible(profile))
    }

    @Test
    fun clampsIntervalsAndUsesLongArithmetic() {
        assertEquals(15L, ProfileUpdateSchedule.intervalMinutes(-1))
        assertEquals(15L, ProfileUpdateSchedule.intervalMinutes(0))
        assertEquals(15L, ProfileUpdateSchedule.intervalMinutes(14))
        assertEquals(Int.MAX_VALUE.toLong(), ProfileUpdateSchedule.intervalMinutes(Int.MAX_VALUE))

        assertEquals(
            Int.MAX_VALUE.toLong() * 60L,
            ProfileUpdateSchedule.remainingDelaySeconds(
                intervalMinutes = Int.MAX_VALUE,
                lastUpdatedMillis = 0L,
                nowMillis = 0L,
            ),
        )
    }

    @Test
    fun calculatesDueStateWithoutNegativeOrOverflowedDelays() {
        val now = 1_000_000L
        assertEquals(
            15L * 60L,
            ProfileUpdateSchedule.remainingDelaySeconds(
                intervalMinutes = 15,
                lastUpdatedMillis = now + 60_000L,
                nowMillis = now,
            ),
        )
        assertFalse(ProfileUpdateSchedule.isDue(15, now - (14L * 60L * 1_000L), now))
        assertTrue(ProfileUpdateSchedule.isDue(15, now - (15L * 60L * 1_000L), now))
    }
}
