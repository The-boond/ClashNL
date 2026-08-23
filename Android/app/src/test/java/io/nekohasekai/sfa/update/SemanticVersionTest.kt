package io.nekohasekai.sfa.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticVersionTest {
    @Test
    fun comparesCoreNumbersNumerically() {
        assertGreater("1.10.0", "1.9.99")
        assertGreater("2.0.0", "1.999999999999999999999999.999")
        assertGreater("999999999999999999999999.0.0", "10.0.0")
    }

    @Test
    fun followsSemverPrereleasePrecedence() {
        val ordered = listOf(
            "1.0.0-alpha",
            "1.0.0-alpha.1",
            "1.0.0-alpha.beta",
            "1.0.0-beta",
            "1.0.0-beta.2",
            "1.0.0-beta.11",
            "1.0.0-rc.1",
            "1.0.0",
        )

        ordered.zipWithNext().forEach { (older, newer) -> assertGreater(newer, older) }
        assertGreater("1.0.0-alpha.a", "1.0.0-alpha.9")
    }

    @Test
    fun ignoresBuildMetadata() {
        assertEquals(0, SemanticVersion.compare("1.2.3+build.1", "1.2.3+build.999"))
        assertEquals(0, SemanticVersion.compare("1.2.3-rc.1+abc", "1.2.3-rc.1+def"))
    }

    @Test
    fun acceptsTrimmedGitTagPrefix() {
        assertEquals(0, SemanticVersion.compare(" v1.2.3 ", "1.2.3"))
        assertEquals(0, SemanticVersion.compare("V1.2.3", "1.2.3"))
    }

    @Test
    fun rejectsMalformedVersions() {
        listOf(
            "",
            "v",
            "1",
            "1.2",
            "1.2.3.4",
            "01.2.3",
            "1.02.3",
            "1.2.03",
            "1.2.3-01",
            "1.2.3-",
            "1.2.3-alpha..1",
            "1.2.3+",
            "1.2.3+build..1",
            "1.2.3+one+two",
            "1.2.3_alpha",
            "1.2.3-测试",
        ).forEach { invalid ->
            assertNull("Expected '$invalid' to be rejected", SemanticVersion.parse(invalid))
        }
    }

    private fun assertGreater(left: String, right: String) {
        assertTrue("Expected $left > $right", requireNotNull(SemanticVersion.compare(left, right)) > 0)
        assertTrue("Expected $right < $left", requireNotNull(SemanticVersion.compare(right, left)) < 0)
    }
}
