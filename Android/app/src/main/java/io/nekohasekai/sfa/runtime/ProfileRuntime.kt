package io.nekohasekai.sfa.runtime

import android.content.Context
import io.nekohasekai.sfa.bg.MihomoVpnService
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileCore
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.mihomo.MihomoConfig
import io.nekohasekai.sfa.mihomo.MihomoOfflineSelectionStore
import io.nekohasekai.sfa.mihomo.MihomoRuntimeRepository
import io.nekohasekai.sfa.mihomo.MihomoRuntimeState
import io.nekohasekai.sfa.mihomo.MihomoStartRequest
import java.io.File

/**
 * The app has one VPN runtime: Mihomo. A persisted sing-box profile is kept as
 * user data for recovery, but can never be started by this version.
 */
object ProfileRuntime {
    private const val LEGACY_PROFILE_MESSAGE = "此版本仅支持 Mihomo 配置，请重新导入 Clash/Mihomo YAML 订阅"

    suspend fun serviceClass(): Class<*> = MihomoVpnService::class.java

    suspend fun startSelected(context: Context) {
        val profile = selectedProfile() ?: error("No profile is selected")
        requireMihomo(profile)
        MihomoVpnService.start(context)
    }

    suspend fun stopActive(context: Context) {
        MihomoVpnService.stop(context)
    }

    suspend fun reloadSelectedIfRunning(context: Context): Boolean {
        val profile = selectedProfile() ?: return false
        if (profile.id != Settings.selectedProfile) return false
        requireMihomo(profile)
        val controller = MihomoRuntimeRepository.controller(context)
        if (controller.runtimeState.value != MihomoRuntimeState.Running) return false
        val content = File(profile.typed.path).readText()
        check(content.isNotBlank()) { "Selected Mihomo profile is empty" }
        controller.reload(MihomoStartRequest(MihomoConfig(content)))
        MihomoOfflineSelectionStore.applyPending(profile, controller)
        Settings.mihomoClashMode.takeIf { it in setOf("rule", "global", "direct") }?.let { mode ->
            controller.setMode(mode)
        }
        return true
    }

    fun markStopped(core: ProfileCore) {
        if (activeCore() == core) {
            Settings.activeProfileCore = ""
        }
    }

    private suspend fun selectedProfile(): Profile? = ProfileManager.get(Settings.selectedProfile)

    private fun activeCore(): ProfileCore? = ProfileCore.entries.firstOrNull { it.name == Settings.activeProfileCore }

    private fun requireMihomo(profile: Profile) {
        require(profile.typed.core == ProfileCore.Mihomo) { LEGACY_PROFILE_MESSAGE }
    }
}
