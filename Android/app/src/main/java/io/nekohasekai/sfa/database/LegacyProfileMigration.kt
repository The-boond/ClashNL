package io.nekohasekai.sfa.database

internal data class LegacyProfileMigrationPlan(
    val disableAutoUpdateProfileIds: Set<Long>,
    val replaceSelection: Boolean,
    val replacementProfileId: Long,
)

internal object LegacyProfileMigration {
    fun plan(profiles: List<Profile>, selectedProfileId: Long): LegacyProfileMigrationPlan {
        val disableAutoUpdateProfileIds =
            profiles
                .asSequence()
                .filter { it.typed.core == ProfileCore.SingBox && it.typed.autoUpdate }
                .mapTo(linkedSetOf()) { it.id }
        val selectedProfileIsMihomo =
            profiles.any { it.id == selectedProfileId && it.typed.core == ProfileCore.Mihomo }
        return LegacyProfileMigrationPlan(
            disableAutoUpdateProfileIds = disableAutoUpdateProfileIds,
            replaceSelection = !selectedProfileIsMihomo,
            replacementProfileId = profiles.firstOrNull { it.typed.core == ProfileCore.Mihomo }?.id ?: -1L,
        )
    }
}
