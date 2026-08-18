package io.nekohasekai.sfa.mihomo

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

    suspend fun start(request: MihomoStartRequest)

    suspend fun stop()

    suspend fun reload(request: MihomoStartRequest)

    suspend fun getProxyGroups(): List<MihomoProxyGroup>

    suspend fun selectProxy(group: String, proxy: String)

    suspend fun testDelay(proxy: String, url: String, timeoutMillis: Long): MihomoDelayResult

    suspend fun updateProxyProvider(name: String)
}
