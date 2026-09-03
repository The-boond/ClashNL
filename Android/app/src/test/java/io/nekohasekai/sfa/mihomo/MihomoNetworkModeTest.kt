package io.nekohasekai.sfa.mihomo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MihomoNetworkModeTest {
    @Test
    fun unknownAndLegacyValuesFallBackToVirtualNic() {
        assertEquals(MihomoNetworkMode.VirtualNic, MihomoNetworkMode.fromStorage(""))
        assertEquals(MihomoNetworkMode.VirtualNic, MihomoNetworkMode.fromStorage("vpn"))
        assertEquals(MihomoNetworkMode.VirtualNic, MihomoNetworkMode.fromStorage("unexpected"))
    }

    @Test
    fun storedValuesRoundTrip() {
        MihomoNetworkMode.entries.forEach { mode ->
            assertEquals(mode, MihomoNetworkMode.fromStorage(mode.storageValue))
        }
    }

    @Test
    fun onlyVirtualNicPinsUnderlyingAfterTunEstablishes() {
        assertTrue(MihomoNetworkMode.VirtualNic.pinsUnderlyingAfterEstablish())
        assertFalse(MihomoNetworkMode.SystemProxy.pinsUnderlyingAfterEstablish())
    }
}
