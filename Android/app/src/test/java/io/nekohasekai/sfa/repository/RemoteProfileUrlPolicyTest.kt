package io.nekohasekai.sfa.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RemoteProfileUrlPolicyTest {
    @Test
    fun acceptsHttpAndHttpsWithHost() {
        assertEquals(
            "https://example.com/sub?token=abc",
            RemoteProfileUrlPolicy.validate("  https://example.com/sub?token=abc  "),
        )
        assertEquals(
            "HTTP://example.com/profile",
            RemoteProfileUrlPolicy.validate("HTTP://example.com/profile"),
        )
    }

    @Test
    fun acceptsIpv6Host() {
        assertEquals(
            "https://[2001:db8::1]/profile",
            RemoteProfileUrlPolicy.validate("https://[2001:db8::1]/profile"),
        )
    }

    @Test
    fun rejectsUnsupportedOrIncompleteUrls() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteProfileUrlPolicy.validate("file:///tmp/profile.json")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteProfileUrlPolicy.validate("https:///profile.json")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteProfileUrlPolicy.validate("")
        }
    }

    @Test
    fun rejectsEmbeddedUserInformation() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteProfileUrlPolicy.validate("https://user:password@example.com/profile")
        }
    }
}
