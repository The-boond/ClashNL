package io.nekohasekai.sfa.repository

import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileCore

/**
 * Routes remote subscription I/O by the core that owns the profile file.
 *
 * Keeping this decision beside the stored profile prevents a generic refresh
 * path from normalizing a Mihomo YAML response as sing-box JSON.
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

    fun fetch(core: ProfileCore, url: String): FetchResult = when (core) {
        ProfileCore.SingBox -> RemoteProfileRepository.fetchNormalized(url).let {
            FetchResult(it.content, it.metadata)
        }

        ProfileCore.Mihomo -> MihomoRemoteProfileRepository.fetch(url).let {
            FetchResult(it.content, it.metadata)
        }
    }

    suspend fun update(profile: Profile): UpdateResult = when (profile.typed.core) {
        ProfileCore.SingBox -> RemoteProfileRepository.update(profile).let {
            UpdateResult(it.contentChanged, it.metadata)
        }

        ProfileCore.Mihomo -> MihomoRemoteProfileRepository.update(profile).let {
            UpdateResult(it.contentChanged, it.metadata)
        }
    }
}
