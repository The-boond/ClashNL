package io.nekohasekai.sfa.mihomo

data class MihomoConfig(
    val content: String,
)

data class MihomoStartRequest(
    val config: MihomoConfig,
    val tun: MihomoTunDevice? = null,
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
)
