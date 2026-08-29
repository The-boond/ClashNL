package io.nekohasekai.sfa.mihomo

data class MihomoConfig(
    val content: String,
)

data class MihomoStartRequest(
    val config: MihomoConfig,
    /** App-owned loopback HTTP proxy port. Null preserves the live port on reload. */
    val httpProxyPort: Int? = null,
    /** Protects core outbound sockets even when no TUN listener is requested. */
    val socketCallback: MihomoTunCallback? = null,
    val tun: MihomoTunDevice? = null,
    val tunFactory: (suspend () -> MihomoTunDevice)? = null,
)

data class MihomoTunDevice(
    val fileDescriptor: Int,
    val stack: String,
    val gateway: String,
    val dns: String,
    val callback: MihomoTunCallback,
)

interface MihomoTunCallback {
    fun protectSocket(fd: Int): Boolean

    fun querySocketUid(protocol: Int, source: String, target: String): Int
}

enum class MihomoRuntimeState {
    Stopped,
    Starting,
    Running,
    Stopping,
    Failed,
}

data class MihomoTraffic(
    val up: Long = 0,
    val down: Long = 0,
)

data class MihomoProxyGroup(
    val name: String,
    val type: String,
    val selected: String,
    val selectable: Boolean,
    val proxies: List<MihomoProxy> = emptyList(),
)

data class MihomoProxy(
    val name: String,
    val type: String,
    val alive: Boolean? = null,
    val delay: Int? = null,
)

data class MihomoDelayResult(
    val delayMillis: Int,
)

data class MihomoConnection(
    val id: String,
    val host: String,
    val network: String,
    val chains: List<String>,
    val upload: Long,
    val download: Long,
    val type: String = "",
    val sourceIp: String = "",
    val destinationIp: String = "",
    val sourcePort: String = "",
    val destinationPort: String = "",
    val inboundName: String = "",
    val inboundUser: String = "",
    val process: String = "",
    val processPath: String = "",
    val start: String = "",
    val rule: String = "",
    val rulePayload: String = "",
)

data class MihomoLogEntry(
    val level: String,
    val message: String,
)
