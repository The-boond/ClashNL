package io.nekohasekai.sfa.runtime

import android.content.Context
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.bg.BoxService
import io.nekohasekai.sfa.bg.MihomoVpnService
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileCore
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.mihomo.MihomoConfig
import io.nekohasekai.sfa.mihomo.MihomoRuntimeRepository
import io.nekohasekai.sfa.mihomo.MihomoRuntimeState
import io.nekohasekai.sfa.mihomo.MihomoStartRequest
import java.io.File

/**
 * One narrowly scoped switch between the legacy sing-box service and Mihomo.
 *
 * Callers never infer a core from configuration text: the persisted profile
 * field is the sole authority. A transition is rejected while the other core
 * is active instead of starting two VPN/TUN owners at once.
 */
object ProfileRuntime {
    suspend fun selectedCore(): ProfileCore = selectedProfile()?.typed?.core ?: ProfileCore.SingBox

    suspend fun serviceClass(): Class<*> = when (selectedCore()) {
        ProfileCore.SingBox -> Settings.serviceClass()
        ProfileCore.Mihomo -> MihomoVpnService::class.java
    }

    suspend fun startSelected(context: Context) {
        val profile = selectedProfile() ?: error("No profile is selected")
        val target = profile.typed.core
        val active = activeCore()
        check(active == null || active == target || !isRunning(active, context)) {
            "Stop the active ${active?.name} profile before starting $target"
        }
        check(!otherCoreRunning(target, context)) {
            "The other VPN core is still active"
        }

        Settings.activeProfileCore = target.name
        when (target) {
            ProfileCore.SingBox -> BoxService.start()
            ProfileCore.Mihomo -> MihomoVpnService.start(context)
        }
    }

    suspend fun stopActive(context: Context) {
        when (activeCore() ?: selectedCore()) {
            ProfileCore.SingBox -> BoxService.stop()
            ProfileCore.Mihomo -> MihomoVpnService.stop(context)
        }
    }

    suspend fun reloadSelectedIfRunning(context: Context): Boolean {
        val profile = selectedProfile() ?: return false
        if (profile.id != Settings.selectedProfile) return false
        return when (profile.typed.core) {
            ProfileCore.SingBox -> {
                if (!BoxService.isRunning) return false
                Libbox.newStandaloneCommandClient().serviceReload()
                true
            }

            ProfileCore.Mihomo -> {
                val controller = MihomoRuntimeRepository.controller(context)
                if (controller.runtimeState.value != MihomoRuntimeState.Running) return false
                val content = File(profile.typed.path).readText()
                check(content.isNotBlank()) { "Selected Mihomo profile is empty" }
                controller.reload(MihomoStartRequest(MihomoConfig(content)))
                true
            }
        }
    }

    fun markStopped(core: ProfileCore) {
        if (activeCore() == core) {
            Settings.activeProfileCore = ""
        }
    }

    private suspend fun selectedProfile(): Profile? = ProfileManager.get(Settings.selectedProfile)

    private fun activeCore(): ProfileCore? = ProfileCore.entries.firstOrNull {
        it.name == Settings.activeProfileCore
    }

    private fun otherCoreRunning(target: ProfileCore, context: Context): Boolean = when (target) {
        ProfileCore.SingBox -> isRunning(ProfileCore.Mihomo, context)
        ProfileCore.Mihomo -> isRunning(ProfileCore.SingBox, context)
    }

    private fun isRunning(core: ProfileCore, context: Context): Boolean = when (core) {
        ProfileCore.SingBox -> BoxService.isRunning
        ProfileCore.Mihomo -> MihomoRuntimeRepository.controller(context).runtimeState.value != MihomoRuntimeState.Stopped
    }
}
