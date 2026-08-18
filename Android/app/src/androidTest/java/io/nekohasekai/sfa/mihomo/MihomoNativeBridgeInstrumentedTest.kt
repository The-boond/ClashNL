package io.nekohasekai.sfa.mihomo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MihomoNativeBridgeInstrumentedTest {
    @Test
    fun loadsArm64BridgeAndValidatesMihomoYaml() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        MihomoNativeBridge.initialize(context)
        MihomoNativeBridge.validate(
            content =
            """
                proxies:
                  - name: TestDirect
                    type: direct
                proxy-groups:
                  - name: Proxy
                    type: select
                    proxies:
                      - TestDirect
                rules:
                  - MATCH,Proxy
            """.trimIndent(),
            controller = "127.0.0.1:19090",
            secret = "instrumentation-only-secret",
        )
    }

    @Test
    fun controllerReadsAndSelectsProxyGroupsThroughMihomoApi() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val controller = AndroidMihomoController(context)
        try {
            controller.start(
                MihomoStartRequest(
                    MihomoConfig(
                        """
                        proxies:
                          - name: TestDirect
                            type: direct
                        proxy-groups:
                          - name: Proxy
                            type: select
                            proxies:
                              - TestDirect
                              - DIRECT
                        rules:
                          - MATCH,Proxy
                        """.trimIndent(),
                    ),
                ),
            )
            val group = controller.getProxyGroups().first { it.name == "Proxy" }
            assertTrue(group.selectable)
            assertTrue("TestDirect" in group.proxies.map { it.name })
            controller.selectProxy("Proxy", "DIRECT")
            assertEquals("DIRECT", controller.getProxyGroups().first { it.name == "Proxy" }.selected)
        } finally {
            controller.stop()
        }
    }
}
