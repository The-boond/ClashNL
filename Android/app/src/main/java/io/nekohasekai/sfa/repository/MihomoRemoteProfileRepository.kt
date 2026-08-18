package io.nekohasekai.sfa.repository

import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.utils.HTTPClient
import io.nekohasekai.sfa.utils.ProfileConfigStore
import java.io.File
import java.util.Date

/**
 * Mihomo's profile store deliberately keeps the provider response as YAML.
 * Runtime proxy choices belong to Mihomo's profile store/API, rather than to
 * subscription text, so refreshes never rewrite `proxy-groups` selections.
 */
object MihomoRemoteProfileRepository {
    data class FetchResult(
        val content: String,
        val metadata: SubscriptionMetadata?,
    )

    data class UpdateResult(
        val contentChanged: Boolean,
        val metadata: SubscriptionMetadata?,
    )

    fun fetch(url: String): FetchResult {
        val validatedUrl = RemoteProfileUrlPolicy.validate(url)
        val response = HTTPClient().use { it.getMihomoSubscription(validatedUrl) }
        return FetchResult(
            content = normalizeYaml(response.content),
            metadata = SubscriptionMetadataParser.parse(response.headers, response.content),
        )
    }

    suspend fun update(profile: Profile): UpdateResult {
        require(profile.typed.type == TypedProfile.Type.Remote) {
            "Only remote profiles can be updated"
        }
        val fetched = fetch(profile.typed.remoteURL)
        val contentChanged = ProfileConfigStore.writeIfChanged(File(profile.typed.path), fetched.content)
        fetched.metadata?.replaceOn(profile.typed) ?: SubscriptionMetadata().replaceOn(profile.typed)
        profile.typed.lastUpdated = Date()
        ProfileManager.update(profile)
        return UpdateResult(contentChanged, fetched.metadata)
    }

    private fun normalizeYaml(content: String): String {
        val normalized = content.replace("\r\n", "\n").trim()
        require(normalized.isNotEmpty()) { "订阅响应为空" }
        require(!normalized.startsWith("{")) {
            "订阅返回的是 sing-box JSON，Mihomo 配置需要 Clash/Mihomo YAML"
        }
        return "$normalized\n"
    }
}
