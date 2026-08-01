package io.nekohasekai.sfa.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionMetadataTest {
    @Test
    fun parsesSubscriptionUserInfoAndProfileHeaders() {
        val metadata =
            SubscriptionMetadataParser.parse(
                mapOf(
                    "subscription-userinfo" to
                        "upload=1048576; download=2097152; total=10485760; expire=1735689600",
                    "profile-update-interval" to "6",
                    "profile-web-page-url" to "https://example.com/account",
                ),
            )

        requireNotNull(metadata)
        assertEquals(1048576L, metadata.upload)
        assertEquals(2097152L, metadata.download)
        assertEquals(10485760L, metadata.total)
        assertEquals(1735689600000L, metadata.expireAtMillis)
        assertEquals(360, metadata.updateIntervalMinutes)
        assertEquals("https://example.com/account", metadata.webPageUrl)
    }

    @Test
    fun clampsUpdateIntervalAndRejectsUnsafeWebPageUrl() {
        val metadata =
            SubscriptionMetadataParser.parse(
                mapOf(
                    "profile-update-interval" to "1",
                    "profile-web-page-url" to "https://user:pass@example.com/account",
                ),
            )

        requireNotNull(metadata)
        assertEquals(60, metadata.updateIntervalMinutes)
        assertNull(metadata.webPageUrl)
    }

    @Test
    fun ignoresMalformedOrEmptyMetadata() {
        assertNull(
            SubscriptionMetadataParser.parse(
                mapOf(
                    "subscription-userinfo" to "upload=nope; expire=-1",
                    "profile-update-interval" to "not-a-number",
                ),
            ),
        )
    }

    @Test
    fun saturatesUnreasonablyLargeUpdateInterval() {
        val metadata =
            SubscriptionMetadataParser.parse(
                mapOf("profile-update-interval" to Long.MAX_VALUE.toString()),
            )

        requireNotNull(metadata)
        assertEquals(Int.MAX_VALUE, metadata.updateIntervalMinutes)
    }

    @Test
    fun parsesBodyCommentsAndLetsHeadersTakePrecedence() {
        val metadata =
            SubscriptionMetadataParser.parse(
                headers =
                mapOf(
                    "Subscription-UserInfo" to "upload=9; download=8; total=100",
                ),
                body =
                """
                    # subscription-userinfo: upload=1; download=2; total=10
                    # profile-update-interval: 12
                    # profile-web-page-url: https://example.com/from-comment
                    proxies: []
                """.trimIndent(),
            )

        requireNotNull(metadata)
        assertEquals(9L, metadata.upload)
        assertEquals(8L, metadata.download)
        assertEquals(100L, metadata.total)
        assertEquals(720, metadata.updateIntervalMinutes)
        assertEquals("https://example.com/from-comment", metadata.webPageUrl)
    }
}
