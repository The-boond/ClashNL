package io.nekohasekai.sfa.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayFormattersTest {
    @Test
    fun formatsTrafficWithoutNativeCore() {
        assertEquals("0 kB", formatBytes(0L))
        assertEquals("1.0 kB", formatBytes(1_000L))
        assertEquals("12 kB", formatBytes(12_345L))
        assertEquals("1.5 MB", formatBytes(1_500_000L))
        assertEquals("1.0 MB", formatMemoryBytes(1_024L * 1_024L))
    }

    @Test
    fun formatsDurationWithoutNativeCore() {
        assertEquals("999ms", formatDuration(999L))
        assertEquals("1.05s", formatDuration(1_050L))
        assertEquals("1m1s", formatDuration(61_000L))
    }

    @Test
    fun recognizesMihomoProxyTypes() {
        assertEquals("URLTest", proxyDisplayType("URLTest"))
        assertEquals("LoadBalance", proxyDisplayType("load-balance"))
        assertEquals("Reject", proxyDisplayType("Reject-Drop"))
        assertEquals("Custom", proxyDisplayType("Custom"))
    }
}
