package io.nekohasekai.sfa.repository

import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.config.MihomoProfileContent
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileCore
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.mihomo.MihomoConfig
import io.nekohasekai.sfa.mihomo.MihomoRuntimeRepository
import io.nekohasekai.sfa.utils.HTTPClient
import io.nekohasekai.sfa.utils.ProfileConfigStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

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

    data class SaveResult(
        val profile: Profile,
        val contentChanged: Boolean,
        val remoteUrlChanged: Boolean,
    )

    private data class ConfigSnapshot(
        val existed: Boolean,
        val content: String,
    )

    private val profileLocks = ConcurrentHashMap<Long, Mutex>()

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
        val requestedUrl = profile.typed.remoteURL
        val fetched = fetchValidated(requestedUrl)
        return withProfileLock(profile.id) {
            val current = requireCurrentRemoteProfile(profile.id, requestedUrl)
            val updatedAt = Date()
            val contentChanged =
                persistProfile(
                    profile = current,
                    fetched = fetched,
                    persist = { ProfileManager.updateTyped(it.id, it.typed) },
                ) {
                    fetched.metadata?.replaceOn(current.typed)
                        ?: SubscriptionMetadata().replaceOn(current.typed)
                    current.typed.lastUpdated = updatedAt
                }
            copyRemoteUpdateState(current.typed, profile.typed)
            UpdateResult(contentChanged, fetched.metadata)
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
    ): SaveResult {
        require(autoUpdateInterval >= 15) { "Auto-update interval must be at least 15 minutes" }
        val validatedRemoteUrl = RemoteProfileUrlPolicy.validate(remoteUrl)
        val remoteUrlChanged = validatedRemoteUrl != expectedRemoteUrl.trim()
        val fetched = if (remoteUrlChanged) fetchValidated(validatedRemoteUrl) else null

        return withProfileLock(profileId) {
            val current = requireCurrentRemoteProfile(profileId, expectedRemoteUrl)
            val contentChanged =
                persistProfile(
                    profile = current,
                    fetched = fetched,
                    persist = ProfileManager::updateEditable,
                ) {
                    current.name = name
                    current.icon = icon
                    current.typed.remoteURL = validatedRemoteUrl
                    current.typed.autoUpdate = autoUpdate
                    current.typed.autoUpdateInterval = autoUpdateInterval
                    if (fetched != null) {
                        fetched.metadata?.replaceOn(current.typed)
                            ?: SubscriptionMetadata().replaceOn(current.typed)
                        current.typed.lastUpdated = Date()
                    }
                }
            SaveResult(current, contentChanged, remoteUrlChanged)
        }
    }

    private suspend fun fetchValidated(url: String): FetchResult {
        val fetched = fetch(url)
        MihomoRuntimeRepository
            .controller(Application.application)
            .validateConfig(MihomoConfig(fetched.content))
        return fetched
    }

    private suspend fun requireCurrentRemoteProfile(profileId: Long, expectedRemoteUrl: String): Profile {
        val current = ProfileManager.get(profileId) ?: error("Profile no longer exists")
        require(current.typed.type == TypedProfile.Type.Remote) { "Only remote profiles can be updated" }
        require(current.typed.core == ProfileCore.Mihomo) {
            "此版本仅支持 Mihomo 配置，请重新导入 Clash/Mihomo YAML 订阅"
        }
        requireUnchangedRemoteUrl(current.typed.remoteURL, expectedRemoteUrl)
        return current
    }

    private suspend fun persistProfile(
        profile: Profile,
        fetched: FetchResult?,
        persist: suspend (Profile) -> Int,
        applyChanges: () -> Unit,
    ): Boolean {
        val configFile = File(profile.typed.path)
        val snapshot =
            fetched?.let {
                ConfigSnapshot(
                    existed = configFile.isFile,
                    content = configFile.takeIf(File::isFile)?.readText().orEmpty(),
                )
            }
        var contentChanged = false
        try {
            if (fetched != null) {
                contentChanged = ProfileConfigStore.writeIfChanged(configFile, fetched.content)
            }
            applyChanges()
            check(persist(profile) == 1) { "Profile no longer exists" }
            return contentChanged
        } catch (exception: Exception) {
            if (contentChanged && snapshot != null) {
                restoreConfig(configFile, snapshot)
            }
            throw exception
        }
    }

    private fun restoreConfig(file: File, snapshot: ConfigSnapshot) {
        if (snapshot.existed) {
            ProfileConfigStore.write(file, snapshot.content)
        } else {
            file.delete()
            File(file.path + ".bak").delete()
        }
    }

    private suspend fun <T> withProfileLock(profileId: Long, block: suspend () -> T): T = profileLocks.computeIfAbsent(profileId) { Mutex() }.withLock { block() }

    private fun copyRemoteUpdateState(source: TypedProfile, target: TypedProfile) {
        target.lastUpdated = source.lastUpdated
        target.subscriptionUpload = source.subscriptionUpload
        target.subscriptionDownload = source.subscriptionDownload
        target.subscriptionTotal = source.subscriptionTotal
        target.subscriptionExpireAt = source.subscriptionExpireAt
        target.subscriptionUpdateIntervalMinutes = source.subscriptionUpdateIntervalMinutes
        target.subscriptionWebPageURL = source.subscriptionWebPageURL
    }

    internal fun requireUnchangedRemoteUrl(current: String, expected: String) {
        check(current == expected) { "Remote profile changed while it was being updated; please retry" }
    }

    private fun normalizeYaml(content: String): String = MihomoProfileContent.normalize(content)
}
