package io.nekohasekai.sfa.account.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class XBoardAccountJsonParserTest {
    @Test
    fun `login parses bearer authorization`() {
        val session = XBoardAccountJsonParser.parseLogin(
            email = "user@example.com",
            body = """{"status":"success","message":"ok","data":{"auth_data":"Bearer TOKEN","token":"SUB_TOKEN"}}""",
        )

        assertEquals("user@example.com", session.email)
        assertEquals("Bearer TOKEN", session.authorization)
    }

    @Test
    fun `login rejects failed envelope without exposing fields`() {
        val error = assertThrows(AccountApiException::class.java) {
            XBoardAccountJsonParser.parseLogin(
                email = "user@example.com",
                body = """{"status":"fail","message":"账号或密码错误","data":null}""",
            )
        }

        assertEquals("账号或密码错误", error.message)
    }

    @Test
    fun `subscription accepts numeric strings and maps plan`() {
        val details = XBoardAccountJsonParser.parseSubscription(
            """
            {
              "status":"success",
              "message":"ok",
              "data":{
                "email":"user@example.com",
                "u":"100",
                "d":200,
                "transfer_enable":"1000",
                "expired_at":"2000000000",
                "next_reset_at":2000000100,
                "subscribe_url":"https://example.com/s/TOKEN",
                "plan":{
                  "id":3,
                  "name":"标准版",
                  "transfer_enable":1000,
                  "device_limit":"4",
                  "speed_limit":50
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals("user@example.com", details.email)
        assertEquals(300L, details.usedBytes)
        assertEquals(1000L, details.transferLimitBytes)
        assertEquals("标准版", details.plan?.name)
        assertEquals(4, details.plan?.deviceLimit)
        assertEquals(50, details.plan?.speedLimitMbps)
        assertFalse(details.isExpired(1_900_000_000L))
        assertTrue(details.isExpired(2_000_000_001L))
    }

    @Test
    fun `subscription rejects malformed response`() {
        assertThrows(AccountApiException::class.java) {
            XBoardAccountJsonParser.parseSubscription("<html>error</html>")
        }
    }

    @Test
    fun `base URL policy requires clean HTTPS origin`() {
        assertEquals(
            "https://nextnexus.qzz.io",
            XBoardAccountApi.validateBaseUrl("https://nextnexus.qzz.io/"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            XBoardAccountApi.validateBaseUrl("http://nextnexus.qzz.io")
        }
        assertThrows(IllegalArgumentException::class.java) {
            XBoardAccountApi.validateBaseUrl("https://user@nextnexus.qzz.io")
        }
        assertThrows(IllegalArgumentException::class.java) {
            XBoardAccountApi.validateBaseUrl("https://nextnexus.qzz.io?token=value")
        }
    }
}
