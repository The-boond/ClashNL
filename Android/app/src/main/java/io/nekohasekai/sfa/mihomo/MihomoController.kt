package io.nekohasekai.sfa.mihomo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The only runtime-core contract exposed to application repositories and UI.
 * JNI and the Mihomo loopback API stay behind this boundary.
 */
interface MihomoController {
    val runtimeState: StateFlow<MihomoRuntimeState>
    val traffic: StateFlow<MihomoTraffic>
    val connections: StateFlow<List<MihomoConnection>>

    suspend fun validateConfig(config: MihomoConfig)

    /**
     * Opens a headless, exclusive core session for stopped-state operations.
     * The session never represents an active VPN and must always be closed.
     */
    suspend fun openProbe(config: MihomoConfig): MihomoProbeSession

    suspend fun start(request: MihomoStartRequest)

    suspend fun stop()

    suspend fun reload(request: MihomoStartRequest)

    suspend fun getProxyGroups(): List<MihomoProxyGroup>

    suspend fun getMode(): String

    suspend fun setMode(mode: String)

    suspend fun selectProxy(group: String, proxy: String)

    suspend fun testDelay(proxy: String, url: String, timeoutMillis: Long): MihomoDelayResult

    suspend fun updateProxyProvider(name: String)

    suspend fun closeConnection(id: String)

    suspend fun closeAllConnections()

    fun observeLogs(level: String = "debug"): Flow<MihomoLogEntry>
}

interface MihomoProbeSession {
    suspend fun getProxyGroups(): List<MihomoProxyGroup>

    suspend fun testDelay(proxy: String, url: String, timeoutMillis: Long): MihomoDelayResult

    suspend fun close()
}
