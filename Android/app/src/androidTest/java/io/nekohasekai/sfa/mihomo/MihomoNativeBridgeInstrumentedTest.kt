package io.nekohasekai.sfa.mihomo

import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sfa.compose.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class MihomoNativeBridgeInstrumentedTest {
    @Test
    fun doesNotLoadLegacyLibboxIntoTheMihomoProcess() {
        val loadedLibraries = java.io.File("/proc/self/maps").readText()
        assertFalse("libbox.so must not share a Go runtime process with Mihomo", "libbox.so" in loadedLibraries)
    }

    @Test
    fun mainActivityDoesNotLoadLegacyLibbox() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val componentName = "${instrumentation.targetContext.packageName}/${MainActivity::class.java.name}"
        val launchResult = instrumentation.uiAutomation.executeShellCommand("am start -W -n $componentName").readText()
        assertTrue("MainActivity shell launch failed: $launchResult", "Status: ok" in launchResult)

        instrumentation.waitForIdleSync()
        val loadedLibraries = java.io.File("/proc/self/maps").readText()
        assertFalse("MainActivity must not initialize the retired libbox runtime", "libbox.so" in loadedLibraries)

        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
    }

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
    fun installsVerifiedBundledGeoDatabase() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        MihomoNativeBridge.initialize(context)
        val database = java.io.File(context.filesDir, "mihomo/Country.mmdb")

        assertTrue("Bundled GeoIP database was not installed", database.isFile)
        val digest = MessageDigest.getInstance("SHA-256").digest(database.readBytes())
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        assertEquals(
            "248b91c2c9cf46c9ae75b27ab8bee7b44d3f745b6a1372da75b2120494284f7b",
            digest,
        )
    }

    @Test
    fun bundledGeoDatabaseLoadsGeoIpDnsFallbackWithoutNetwork() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val controller = MihomoRuntimeRepository.controller(context)
        val session = controller.openProbe(
            MihomoConfig(
                """
                dns:
                  enable: true
                  nameserver:
                    - 1.1.1.1
                  fallback:
                    - 8.8.8.8
                  fallback-filter:
                    geoip: true
                    geoip-code: CN
                proxies:
                  - name: GeoDirect
                    type: direct
                proxy-groups:
                  - name: Proxy
                    type: select
                    proxies:
                      - GeoDirect
                rules:
                  - MATCH,Proxy
                """.trimIndent(),
            ),
        )
        try {
            assertTrue(session.getProxyGroups().any { it.name == "Proxy" })
        } finally {
            session.close()
        }
    }

    @Test
    fun readsStoppedProfileProxyGroupsWithoutStartingVpn() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        MihomoNativeBridge.initialize(context)
        val groups = MihomoNativeBridge.describeProxyGroups(
            content =
            """
                proxies:
                  - name: Japan-01
                    type: direct
                  - name: Japan-02
                    type: direct
                proxy-groups:
                  - name: Proxy
                    type: select
                    proxies:
                      - Japan-01
                      - Japan-02
                rules:
                  - MATCH,Proxy
            """.trimIndent(),
        )

        assertEquals("Proxy", groups.single().name)
        assertEquals("Japan-01", groups.single().selected)
        assertEquals(listOf("Japan-01", "Japan-02"), groups.single().proxies.map { it.name })
        assertTrue(groups.single().selectable)
    }

    @Test
    fun controllerReadsAndSelectsProxyGroupsThroughMihomoApi() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val controller = MihomoRuntimeRepository.controller(context)
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
            assertEquals("rule", controller.getMode())
            controller.setMode("global")
            assertEquals("global", controller.getMode())
            controller.setMode("direct")
            assertEquals("direct", controller.getMode())
            controller.setMode("rule")
            assertEquals("rule", controller.getMode())
        } finally {
            controller.stop()
        }
    }

    @Test
    fun appOwnedHttpProxyWorksAndSubscriptionListenersStayDisabled() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val appPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val subscriptionPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val target = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val targetFailure = AtomicReference<Throwable?>()
        val responder = thread(isDaemon = true) {
            runCatching {
                target.use { server ->
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        while (reader.readLine()?.isNotEmpty() == true) {
                            // Consume the proxied request headers.
                        }
                        socket.getOutputStream().write(
                            "HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray(),
                        )
                    }
                }
            }.exceptionOrNull()?.let(targetFailure::set)
        }
        val controller = MihomoRuntimeRepository.controller(context)
        try {
            controller.start(
                MihomoStartRequest(
                    config = MihomoConfig(
                        """
                        port: $subscriptionPort
                        allow-lan: true
                        authentication:
                          - attacker:controlled
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
                    ),
                    httpProxyPort = appPort,
                ),
            )

            Socket("127.0.0.1", appPort).use { socket ->
                socket.soTimeout = 5_000
                socket.getOutputStream().write(
                    (
                        "GET http://127.0.0.1:${target.localPort}/probe HTTP/1.1\r\n" +
                            "Host: 127.0.0.1:${target.localPort}\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(),
                )
                val status = socket.getInputStream().bufferedReader().readLine()
                assertTrue("App-owned HTTP proxy did not relay the request: $status", status?.contains("204") == true)
            }
            val subscriptionListenerOpen = runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", subscriptionPort), 250) }
            }.isSuccess
            assertFalse("Subscription-defined HTTP listener must be replaced", subscriptionListenerOpen)
            responder.join(2_000)
            assertFalse("HTTP target responder did not finish", responder.isAlive)
            targetFailure.get()?.let { throw AssertionError("HTTP target responder failed", it) }
        } finally {
            controller.stop()
            runCatching { target.close() }
        }
        Unit
    }

    @Test
    fun headlessProbeTestsDelayWithoutAdvertisingARunningVpn() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val controller = MihomoRuntimeRepository.controller(context)
        val session = controller.openProbe(configWithProxy("OfflineDirect"))
        try {
            assertEquals(MihomoRuntimeState.Stopped, controller.runtimeState.value)
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                val responderFailure = AtomicReference<Throwable?>()
                val responder = thread(isDaemon = true) {
                    runCatching {
                        server.accept().use { socket ->
                            val reader = socket.getInputStream().bufferedReader()
                            while (reader.readLine()?.isNotEmpty() == true) {
                                // Consume the request headers.
                            }
                            // Mihomo treats a measured delay of exactly 0 ms as a failed
                            // test, which a same-process loopback server can otherwise hit.
                            Thread.sleep(25)
                            socket.getOutputStream().write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray())
                        }
                    }.exceptionOrNull()?.let(responderFailure::set)
                }
                val result = session.testDelay("OfflineDirect", "http://127.0.0.1:${server.localPort}", 5_000)
                assertTrue(result.delayMillis >= 0)
                responder.join(2_000)
                assertFalse("Loopback responder did not finish", responder.isAlive)
                responderFailure.get()?.let { throw AssertionError("Loopback responder failed", it) }
            }
        } finally {
            session.close()
        }
        assertEquals(MihomoRuntimeState.Stopped, controller.runtimeState.value)
    }

    @Test
    fun subscriptionCannotOpenTunnelListeners() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val controller = MihomoRuntimeRepository.controller(context)
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
                        tunnels:
                          - tcp,127.0.0.1:$port,127.0.0.1:9,DIRECT
                        rules:
                          - MATCH,Proxy
                        """.trimIndent(),
                    ),
                ),
            )
            val connected = runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 250) }
            }.isSuccess
            assertFalse("Subscription-defined tunnel listener must be disabled", connected)
        } finally {
            controller.stop()
        }
    }

    @Test
    fun controllerReloadsAndRestartsOnItsPersistentLoopbackEndpoint() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val controller = MihomoRuntimeRepository.controller(context)
        val first = configWithProxy("FirstDirect")
        val second = configWithProxy("SecondDirect")
        try {
            controller.start(MihomoStartRequest(first))
            assertTrue("FirstDirect" in controller.getProxyGroups().single { it.name == "Proxy" }.proxies.map { it.name })

            controller.reload(MihomoStartRequest(second))
            assertTrue("SecondDirect" in controller.getProxyGroups().single { it.name == "Proxy" }.proxies.map { it.name })

            controller.stop()
            controller.start(MihomoStartRequest(first))
            assertTrue("FirstDirect" in controller.getProxyGroups().single { it.name == "Proxy" }.proxies.map { it.name })
        } finally {
            controller.stop()
        }
    }

    private fun configWithProxy(name: String) = MihomoConfig(
        """
        proxies:
          - name: $name
            type: direct
        proxy-groups:
          - name: Proxy
            type: select
            proxies:
              - $name
              - DIRECT
        rules:
          - MATCH,Proxy
        """.trimIndent(),
    )

    private fun ParcelFileDescriptor.readText(): String = ParcelFileDescriptor.AutoCloseInputStream(this).bufferedReader().use { it.readText() }
}
