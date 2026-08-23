package io.nekohasekai.sfa.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RemoteProfileUrlPolicyTest {
    @Test
    fun acceptsHttpsWithHost() {
        assertEquals(
            "https://example.com/sub?token=abc",
            RemoteProfileUrlPolicy.validate("  https://example.com/sub?token=abc  "),
        )
    }

    @Test
    fun acceptsHttpOnlyForExplicitLoopbackHosts() {
        assertEquals(
            "HTTP://localhost:8080/profile",
            RemoteProfileUrlPolicy.validate("HTTP://localhost:8080/profile"),
        )
        assertEquals(
            "http://127.0.0.1/profile",
            RemoteProfileUrlPolicy.validate("http://127.0.0.1/profile"),
        )
        assertEquals(
            "http://[::1]:9090/profile",
            RemoteProfileUrlPolicy.validate("http://[::1]:9090/profile"),
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
    fun rejectsCleartextRemoteHostsAndLookalikes() {
        listOf(
            "http://example.com/profile",
            "http://192.168.1.2/profile",
            "http://127.0.0.2/profile",
            "http://localhost.example.com/profile",
        ).forEach { url ->
            assertThrows(IllegalArgumentException::class.java) {
                RemoteProfileUrlPolicy.validate(url)
            }
        }
    }

    @Test
    fun rejectsEmbeddedUserInformation() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteProfileUrlPolicy.validate("https://user:password@example.com/profile")
        }
    }
}
