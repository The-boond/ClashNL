package io.nekohasekai.sfa.database

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileCoreTest {
    @Test
    fun unknownPersistedCoreFallsBackToSingBox() {
        assertEquals(ProfileCore.SingBox, ProfileCore.fromOrdinal(-1))
        assertEquals(ProfileCore.SingBox, ProfileCore.fromOrdinal(99))
    }

    @Test
    fun knownPersistedCoresKeepTheirStableOrder() {
        assertEquals(ProfileCore.SingBox, ProfileCore.fromOrdinal(0))
        assertEquals(ProfileCore.Mihomo, ProfileCore.fromOrdinal(1))
    }

    @Test
    fun newlyCreatedProfilesUseMihomo() {
        assertEquals(ProfileCore.Mihomo, TypedProfile().core)
    }
}
