package io.nekohasekai.sfa.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class MihomoProfileContentTest {
    @Test
    fun normalizesYamlWithoutChangingItsStructure() {
        assertEquals("proxies: []\n", MihomoProfileContent.normalize("proxies: []\r\n"))
    }

    @Test
    fun preservesWhitespaceInsideYamlScalars() {
        assertEquals(
            "script: |\n  value with trailing spaces  \n",
            MihomoProfileContent.normalize("script: |\r\n  value with trailing spaces  \r\n"),
        )
    }

    @Test
    fun rejectsRetiredSingBoxJson() {
        assertThrows(IllegalArgumentException::class.java) {
            MihomoProfileContent.normalize("{\"outbounds\":[]}")
        }
    }

    @Test
    fun rejectsOversizedDeclaredStreamsBeforeReadingThem() {
        assertThrows(IllegalArgumentException::class.java) {
            MihomoProfileContent.readUtf8(
                ByteArrayInputStream(ByteArray(0)),
                MihomoProfileContent.MAX_PROFILE_BYTES.toLong() + 1L,
            )
        }
    }
}
