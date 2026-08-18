package io.nekohasekai.sfa.account

import android.content.Context
import io.nekohasekai.sfa.account.model.AccountDetails
import io.nekohasekai.sfa.account.model.AccountProfileSyncResult
import io.nekohasekai.sfa.account.security.AccountSessionStore
import io.nekohasekai.sfa.bg.UpdateProfileWork
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.repository.ProfileRemoteRepository
import io.nekohasekai.sfa.repository.RemoteProfileUrlPolicy
import io.nekohasekai.sfa.utils.ProfileConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date

interface AccountProfileSync {
    suspend fun sync(details: AccountDetails): AccountProfileSyncResult?
}

class AccountProfileSynchronizer(
    private val context: Context,
    private val sessionStore: AccountSessionStore,
) : AccountProfileSync {
    override suspend fun sync(details: AccountDetails): AccountProfileSyncResult? = withContext(Dispatchers.IO) {
        val remoteUrl = details.subscribeUrl.takeIf { it.isNotBlank() } ?: return@withContext null
        RemoteProfileUrlPolicy.validate(remoteUrl)

        val profiles = ProfileManager.list()
        val managedProfile = profiles.firstOrNull { it.id == sessionStore.managedProfileId }
            ?: profiles.firstOrNull {
                it.typed.type == TypedProfile.Type.Remote && it.typed.remoteURL == remoteUrl
            }

        val result = if (managedProfile == null) {
            createProfile(details, remoteUrl)
        } else {
            updateProfile(managedProfile, details, remoteUrl)
        }
        sessionStore.managedProfileId = result.profileId
        UpdateProfileWork.reconfigureUpdater()
        result
    }

    private suspend fun createProfile(
        details: AccountDetails,
        remoteUrl: String,
    ): AccountProfileSyncResult {
        val typedProfile = TypedProfile().apply {
            type = TypedProfile.Type.Remote
            remoteURL = remoteUrl
            autoUpdate = true
            autoUpdateInterval = DEFAULT_UPDATE_INTERVAL_MINUTES
            lastUpdated = Date()
        }
        val fetched = ProfileRemoteRepository.fetch(typedProfile.core, remoteUrl)
        fetched.metadata?.replaceOn(typedProfile)
        details.applySubscriptionMetadata(typedProfile)
        val profile = Profile(
            name = managedProfileName(details),
            typed = typedProfile,
        ).apply {
            userOrder = ProfileManager.nextOrder()
        }
        val fileId = ProfileManager.nextFileID()
        val configFile = File(File(context.filesDir, "configs").also { it.mkdirs() }, "$fileId.json")
        typedProfile.path = configFile.path
        ProfileConfigStore.write(configFile, fetched.content)
        ProfileManager.create(profile, andSelect = Settings.selectedProfile == -1L)
        return AccountProfileSyncResult(
            profileId = profile.id,
            created = true,
            contentChanged = true,
        )
    }

    private suspend fun updateProfile(
        profile: Profile,
        details: AccountDetails,
        remoteUrl: String,
    ): AccountProfileSyncResult {
        profile.name = managedProfileName(details)
        profile.typed.remoteURL = remoteUrl
        profile.typed.autoUpdate = true
        profile.typed.autoUpdateInterval = DEFAULT_UPDATE_INTERVAL_MINUTES
        val update = ProfileRemoteRepository.update(profile)
        details.applySubscriptionMetadata(profile.typed)
        ProfileManager.update(profile)
        return AccountProfileSyncResult(
            profileId = profile.id,
            created = false,
            contentChanged = update.contentChanged,
        )
    }

    private fun managedProfileName(details: AccountDetails): String = details.plan?.name
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { "ClashNL · $it" }
        ?: DEFAULT_PROFILE_NAME

    private fun AccountDetails.applySubscriptionMetadata(profile: TypedProfile) {
        profile.subscriptionUpload = uploadedBytes
        profile.subscriptionDownload = downloadedBytes
        profile.subscriptionTotal = transferLimitBytes
        profile.subscriptionExpireAt = expiresAtEpochSeconds.toEpochMillis()
    }

    private fun Long.toEpochMillis(): Long = if (this <= 0L || this > Long.MAX_VALUE / 1000L) 0L else this * 1000L

    companion object {
        private const val DEFAULT_PROFILE_NAME = "ClashNL 账户订阅"
        private const val DEFAULT_UPDATE_INTERVAL_MINUTES = 60
    }
}
