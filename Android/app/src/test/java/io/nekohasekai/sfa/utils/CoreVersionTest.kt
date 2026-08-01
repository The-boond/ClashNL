package io.nekohasekai.sfa.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class CoreVersionTest {
    @Test
    fun unknownEmbeddedVersionFallsBackToPinnedVersion() {
        assertEquals(
            "1.14.0-beta.2",
            CoreVersion.display("unknown", "1.14.0-beta.2"),
        )
    }

    @Test
    fun embeddedVersionWinsAfterTheCoreIsStampedCorrectly() {
        assertEquals(
            "1.14.0-beta.2",
            CoreVersion.display(" 1.14.0-beta.2 ", "fallback"),
        )
    }
}
