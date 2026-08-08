package io.nekohasekai.sfa.latency

import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.sfa.bg.DefaultNetworkMonitor
import io.nekohasekai.sfa.bg.PlatformInterfaceWrapper
import io.nekohasekai.sfa.config.ClashConfigNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

object OfflineProbeConfig {
    private val json = Json {
        prettyPrint = false
        explicitNulls = false
    }
    private val forbiddenKeys = setOf(
        "auto_route",
        "strict_route",
        "set_system_proxy",
        "system_proxy",
        "system_proxy_enabled",
    )

    fun stripVpnIntegration(content: String): String {
        val root = json.parseToJsonElement(content.trim()).jsonObject
        return json.encodeToString(JsonElement.serializer(), stripObject(root))
    }

    fun normalizeAndStrip(content: String): String = stripVpnIntegration(ClashConfigNormalizer.normalize(content).content)

    private fun stripObject(value: JsonObject): JsonObject = buildJsonObject {
        value.forEach { (key, element) ->
            if (key == "inbounds" || key in forbiddenKeys) return@forEach
            put(key, stripElement(element))
        }
    }

    private fun stripElement(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> stripObject(value)
        is JsonArray -> buildJsonArray { value.forEach { add(stripElement(it)) } }
        else -> value
    }
}

class OfflineLatencyProbe(
    private val profileContent: String,
) : LatencyProbe {
    private var commandServer: CommandServer? = null
    private var networkMonitorStarted = false

    suspend fun start() {
        withContext(Dispatchers.IO) {
            DefaultNetworkMonitor.start()
            networkMonitorStarted = true
            val server = CommandServer(HeadlessCommandServerHandler, HeadlessPlatformInterface)
            try {
                server.start()
                server.startOrReloadService(
                    OfflineProbeConfig.normalizeAndStrip(profileContent),
                    OverrideOptions().apply { autoRedirect = false },
                )
                commandServer = server
            } catch (exception: Exception) {
                runCatching { server.closeService() }
                server.close()
                DefaultNetworkMonitor.stop()
                networkMonitorStarted = false
                throw exception
            }
        }
    }

    override suspend fun sample(target: LatencyTarget): LatencySample = withContext(Dispatchers.IO) {
        val server = commandServer ?: error("offline probe session is not started")
        LatencySample.success(server.urlTest(target.nodeTag).toLong())
    }

    override suspend fun close() {
        withContext(NonCancellable + Dispatchers.IO) {
            val server = commandServer
            commandServer = null
            if (server != null) {
                runCatching { server.closeService() }
                runCatching { server.close() }
            }
            if (networkMonitorStarted) {
                runCatching { DefaultNetworkMonitor.stop() }
                networkMonitorStarted = false
            }
        }
    }
}

private object HeadlessCommandServerHandler : CommandServerHandler {
    override fun connectSSHAgent(): Int = 0

    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus().apply {
        available = false
        enabled = false
    }

    override fun serviceReload() {}

    override fun serviceStop() {}

    override fun setSystemProxyEnabled(enabled: Boolean) {}

    override fun triggerNativeCrash() {}

    override fun writeDebugMessage(message: String?) {}
}

private object HeadlessPlatformInterface : PlatformInterfaceWrapper {
    override fun openTun(options: TunOptions): Int = error("offline probe does not open TUN")

    override fun sendNotification(notification: Notification) {}

    override fun usePlatformBridge(): Boolean = false

    override fun usePlatformShell(): Boolean = false

    override fun checkPlatformShell() {}
}
