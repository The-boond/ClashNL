package io.nekohasekai.sfa.repository

import io.nekohasekai.sfa.database.Profile

/**
 * Owns remote subscription I/O for the single Mihomo runtime.
 */
object ProfileRemoteRepository {
    data class FetchResult(
        val content: String,
        val metadata: SubscriptionMetadata?,
    )

    data class UpdateResult(
        val contentChanged: Boolean,
        val metadata: SubscriptionMetadata?,
    )

    data class SaveResult(
        val profile: Profile,
        val contentChanged: Boolean,
        val remoteUrlChanged: Boolean,
    )

    fun fetch(url: String): FetchResult = MihomoRemoteProfileRepository.fetch(url).let {
        FetchResult(it.content, it.metadata)
    }

    suspend fun update(profile: Profile): UpdateResult {
        require(profile.typed.core == io.nekohasekai.sfa.database.ProfileCore.Mihomo) {
            "此版本仅支持 Mihomo 配置，请重新导入 Clash/Mihomo YAML 订阅"
        }
        return MihomoRemoteProfileRepository.update(profile).let {
            UpdateResult(it.contentChanged, it.metadata)
        }
    }

    suspend fun saveRemoteProfile(
        profileId: Long,
        expectedRemoteUrl: String,
        name: String,
        icon: String?,
        remoteUrl: String,
        autoUpdate: Boolean,
        autoUpdateInterval: Int,
    ): SaveResult = MihomoRemoteProfileRepository
        .saveRemoteProfile(
            profileId = profileId,
            expectedRemoteUrl = expectedRemoteUrl,
            name = name,
            icon = icon,
            remoteUrl = remoteUrl,
            autoUpdate = autoUpdate,
            autoUpdateInterval = autoUpdateInterval,
        ).let {
            SaveResult(
                profile = it.profile,
                contentChanged = it.contentChanged,
                remoteUrlChanged = it.remoteUrlChanged,
            )
        }
}
