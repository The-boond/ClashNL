package io.nekohasekai.sfa.mihomo

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MihomoApiClientTest {
    @Test
    fun `history array uses last valid positive delay`() {
        val proxy = JSONObject(
            """
            {
              "history": [
                {"time": "2026-08-24T00:00:00Z", "delay": 41},
                {"time": "2026-08-24T00:01:00Z", "delay": 0},
                {"time": "2026-08-24T00:02:00Z", "delay": 73},
                {"time": "2026-08-24T00:03:00Z", "delay": -1},
                {"time": "2026-08-24T00:04:00Z"}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(73, proxy.optMihomoDelay())
    }

    @Test
    fun `object shaped history remains compatible`() {
        val arrayHistory = JSONObject("""{"history":{"delay":[18,0,"32",null]}}""")
        val scalarHistory = JSONObject("""{"history":{"delay":27}}""")

        assertEquals(32, arrayHistory.optMihomoDelay())
        assertEquals(27, scalarHistory.optMihomoDelay())
    }

    @Test
    fun `history without a positive delay returns null`() {
        val proxy = JSONObject("""{"history":[{"delay":0},{"delay":-2},{"delay":"bad"},null]}""")

        assertNull(proxy.optMihomoDelay())
    }
}
