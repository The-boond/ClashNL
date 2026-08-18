package io.nekohasekai.sfa.repository

import io.nekohasekai.sfa.mihomo.MihomoController
import io.nekohasekai.sfa.mihomo.MihomoDelayResult
import io.nekohasekai.sfa.mihomo.MihomoProxyGroup

/** Application-facing proxy API; UI code has no knowledge of JNI or HTTP. */
class MihomoProxyGroupRepository(
    private val controller: MihomoController,
) {
    suspend fun getGroups(): List<MihomoProxyGroup> = controller.getProxyGroups()

    suspend fun select(group: String, proxy: String) = controller.selectProxy(group, proxy)

    suspend fun testDelay(proxy: String, url: String, timeoutMillis: Long): MihomoDelayResult = controller.testDelay(proxy, url, timeoutMillis)

    suspend fun updateProvider(name: String) = controller.updateProxyProvider(name)
}
