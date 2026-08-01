package io.nekohasekai.sfa.repository

import io.nekohasekai.sfa.config.ClashConfigNormalizer
import io.nekohasekai.sfa.config.ProfileNodeSelection
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.utils.HTTPClient
import io.nekohasekai.sfa.utils.ProfileConfigStore
import java.io.File
import java.util.Date

object RemoteProfileRepository {
    data class FetchResult(
        val content: String,
        val metadata: SubscriptionMetadata?,
    )

    data class UpdateResult(
        val contentChanged: Boolean,
        val metadata: SubscriptionMetadata?,
    )

    fun fetchNormalized(url: String): FetchResult {
        val validatedUrl = RemoteProfileUrlPolicy.validate(url)
        val response = HTTPClient().use { it.getSubscription(validatedUrl) }
        return FetchResult(
            content = ClashConfigNormalizer.normalize(response.content).content,
            metadata = SubscriptionMetadataParser.parse(response.headers, response.content),
        )
    }

    suspend fun update(profile: Profile): UpdateResult {
        require(profile.typed.type == TypedProfile.Type.Remote) {
            "Only remote profiles can be updated"
        }

        val fetched = fetchNormalized(profile.typed.remoteURL)
        val profileFile = File(profile.typed.path)
        val previousContent = profileFile.takeIf { it.isFile }?.readText().orEmpty()
        val content =
            if (previousContent.isBlank()) {
                fetched.content
            } else {
                ProfileNodeSelection.preserveSelections(previousContent, fetched.content)
            }
        val contentChanged =
            ProfileConfigStore.writeIfChanged(
                profileFile,
                content,
            )

        fetched.metadata?.replaceOn(profile.typed)
            ?: SubscriptionMetadata().replaceOn(profile.typed)
        profile.typed.lastUpdated = Date()
        ProfileManager.update(profile)
        return UpdateResult(contentChanged, fetched.metadata)
    }
}
