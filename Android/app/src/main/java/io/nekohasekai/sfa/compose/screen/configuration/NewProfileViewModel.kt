package io.nekohasekai.sfa.compose.screen.configuration

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.bg.UpdateProfileWork
import io.nekohasekai.sfa.config.MihomoProfileContent
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileCore
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.mihomo.MihomoConfig
import io.nekohasekai.sfa.mihomo.MihomoRuntimeRepository
import io.nekohasekai.sfa.repository.ProfileRemoteRepository
import io.nekohasekai.sfa.repository.RemoteProfileUrlPolicy
import io.nekohasekai.sfa.utils.HTTPClient
import io.nekohasekai.sfa.utils.ProfileConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.Date

data class NewProfileUiState(
    val name: String = "",
    val profileType: ProfileType = ProfileType.Local,
    val profileSource: ProfileSource = ProfileSource.CreateNew,
    // Remote profile fields
    val remoteUrl: String = "",
    val autoUpdate: Boolean = true,
    val autoUpdateInterval: Int = 60,
    // File import
    val importUri: Uri? = null,
    val importFileName: String? = null,
    // QRS import
    val qrsData: ByteArray? = null,
    // State
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val isSuccess: Boolean = false,
    val createdProfile: Profile? = null,
    // Field errors
    val nameError: String? = null,
    val remoteUrlError: String? = null,
    val importError: String? = null,
)

enum class ProfileType {
    Local,
    Remote,
}

enum class ProfileSource {
    CreateNew,
    Import,
}

class NewProfileViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(NewProfileUiState())
    val uiState: StateFlow<NewProfileUiState> = _uiState.asStateFlow()

    fun initializeFromQRImport(name: String?, url: String?) {
        if (url != null) {
            val displayName =
                name
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    .orEmpty()
            _uiState.update {
                it.copy(
                    name = displayName,
                    profileType = ProfileType.Remote,
                    remoteUrl = url,
                )
            }
        }
    }

    fun initializeRemoteProfile() {
        _uiState.update {
            it.copy(
                profileType = ProfileType.Remote,
                name = it.name.trim(),
            )
        }
    }

    fun initializeFromQRSImport(name: String?, qrsData: ByteArray) {
        _uiState.update {
            it.copy(
                name = name ?: "",
                profileType = ProfileType.Local,
                profileSource = ProfileSource.Import,
                qrsData = qrsData,
            )
        }
    }

    fun updateName(name: String) {
        _uiState.update {
            it.copy(
                name = name,
                nameError = if (name.isNotBlank()) null else it.nameError,
            )
        }
    }

    fun updateProfileType(type: ProfileType) {
        _uiState.update { it.copy(profileType = type) }
    }

    fun updateProfileSource(source: ProfileSource) {
        _uiState.update {
            it.copy(
                profileSource = source,
                importError = null, // Clear import error when changing source
            )
        }
    }

    fun updateRemoteUrl(url: String) {
        _uiState.update {
            it.copy(
                remoteUrl = url,
                remoteUrlError = if (url.isNotBlank()) null else it.remoteUrlError,
            )
        }
    }

    fun updateAutoUpdate(enabled: Boolean) {
        _uiState.update { it.copy(autoUpdate = enabled) }
    }

    fun updateAutoUpdateInterval(interval: String) {
        val intValue = interval.toIntOrNull() ?: 60
        _uiState.update { it.copy(autoUpdateInterval = intValue.coerceAtLeast(15)) }
    }

    fun setImportUri(uri: Uri, fileName: String?) {
        _uiState.update {
            it.copy(
                importUri = uri,
                importFileName = fileName,
                importError = null, // Clear error when file is selected
                name =
                if (it.name.isEmpty()) {
                    fileName?.substringBeforeLast(".") ?: "Imported Profile"
                } else {
                    it.name
                },
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun validateAndCreateProfile(): Boolean {
        val context = getApplication<Application>()
        var state = _uiState.value

        // Clear previous errors
        _uiState.update {
            it.copy(
                errorMessage = null,
                nameError = null,
                remoteUrlError = null,
                importError = null,
            )
        }

        var hasError = false

        // Local profiles still need an explicit name. Remote subscriptions use
        // "Default subscription 01", "02", ... when this field is blank.
        if (state.profileType == ProfileType.Local && state.name.isBlank()) {
            _uiState.update { it.copy(nameError = context.getString(R.string.profile_input_required)) }
            hasError = true
        }

        // Validate based on profile type
        when (state.profileType) {
            ProfileType.Local -> {
                if (state.profileSource == ProfileSource.Import && state.importUri == null && state.qrsData == null) {
                    _uiState.update { it.copy(importError = context.getString(R.string.profile_input_required)) }
                    hasError = true
                }
            }
            ProfileType.Remote -> {
                if (state.remoteUrl.isBlank()) {
                    _uiState.update { it.copy(remoteUrlError = context.getString(R.string.profile_input_required)) }
                    hasError = true
                } else {
                    runCatching {
                        RemoteProfileUrlPolicy.validate(state.remoteUrl)
                    }.onFailure {
                        _uiState.update {
                            it.copy(remoteUrlError = context.getString(R.string.profile_invalid_subscription_url))
                        }
                        hasError = true
                    }
                }
            }
        }

        if (hasError) {
            return false
        }

        // If validation passes, create the profile
        createProfile()
        return true
    }

    private fun createProfile() {
        viewModelScope.launch {
            var state = _uiState.value
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }

            try {
                if (state.profileType == ProfileType.Remote && state.name.isBlank()) {
                    val generatedName =
                        withContext(Dispatchers.IO) {
                            ProfileNameGenerator.nextDefaultSubscriptionName(getApplication())
                        }
                    state = state.copy(name = generatedName)
                    _uiState.update { it.copy(name = generatedName, nameError = null) }
                }
                val profile =
                    withContext(Dispatchers.IO) {
                        when (state.profileType) {
                            ProfileType.Local -> createLocalProfile(state)
                            ProfileType.Remote -> createRemoteProfile(state)
                        }
                    }

                _uiState.update {
                    it.copy(
                        isSaving = false,
                        isSuccess = true,
                        createdProfile = profile,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = e.message ?: "Unknown error",
                    )
                }
            }
        }
    }

    private suspend fun createLocalProfile(state: NewProfileUiState): Profile {
        val context = getApplication<Application>()
        val typedProfile =
            TypedProfile().apply {
                type = TypedProfile.Type.Local
                core = ProfileCore.Mihomo
            }

        val profile =
            Profile(name = state.name, typed = typedProfile).apply {
                userOrder = ProfileManager.nextOrder()
            }

        val fileID = ProfileManager.nextFileID()
        val configDirectory = File(context.filesDir, "configs").also { it.mkdirs() }
        val configFile = File(configDirectory, "$fileID.yaml")
        typedProfile.path = configFile.path

        // Get config content
        val configContent =
            when (state.profileSource) {
                ProfileSource.CreateNew -> "proxies: []\nproxy-groups: []\nrules: []"
                ProfileSource.Import -> {
                    if (state.qrsData != null) {
                        String(state.qrsData)
                    } else {
                        state.importUri?.let { uri ->
                            val sourceURL = uri.toString()
                            when {
                                sourceURL.startsWith("content://") -> {
                                    val inputStream = context.contentResolver.openInputStream(uri) as InputStream
                                    inputStream.use { it.bufferedReader().readText() }
                                }
                                sourceURL.startsWith("file://") -> {
                                    File(Uri.parse(sourceURL).path!!).readText()
                                }
                                sourceURL.startsWith("http://") || sourceURL.startsWith("https://") -> {
                                    HTTPClient().use { it.getSubscriptionString(sourceURL) }
                                }
                                else -> throw Exception("Unsupported source: $sourceURL")
                            }
                        } ?: ""
                    }
                }
            }

        val normalized = MihomoProfileContent.normalize(configContent)
        if (state.profileSource == ProfileSource.Import) {
            MihomoRuntimeRepository.controller(context).validateConfig(MihomoConfig(normalized))
        }
        ProfileConfigStore.write(configFile, normalized)

        // Create profile in database and select it
        ProfileManager.create(profile, andSelect = true)

        return profile
    }

    private suspend fun createRemoteProfile(state: NewProfileUiState): Profile {
        val context = getApplication<Application>()
        val remoteUrl = RemoteProfileUrlPolicy.validate(state.remoteUrl)
        val typedProfile =
            TypedProfile().apply {
                type = TypedProfile.Type.Remote
                core = ProfileCore.Mihomo
                remoteURL = remoteUrl
                autoUpdate = state.autoUpdate
                autoUpdateInterval = state.autoUpdateInterval
                lastUpdated = Date()
            }

        val profile =
            Profile(name = state.name, typed = typedProfile).apply {
                userOrder = ProfileManager.nextOrder()
            }

        val fileID = ProfileManager.nextFileID()
        val configDirectory = File(context.filesDir, "configs").also { it.mkdirs() }
        val configFile = File(configDirectory, "$fileID.yaml")
        typedProfile.path = configFile.path

        // Fetch initial config - this MUST succeed for remote profiles
        val fetched = ProfileRemoteRepository.fetch(remoteUrl)
        MihomoRuntimeRepository.controller(context).validateConfig(MihomoConfig(fetched.content))
        fetched.metadata?.replaceOn(typedProfile)
        ProfileConfigStore.write(configFile, fetched.content)

        // Create profile in database and select it
        ProfileManager.create(profile, andSelect = true)

        // Reconfigure updater if auto-update is enabled
        if (state.autoUpdate) {
            UpdateProfileWork.reconfigureUpdater()
        }

        return profile
    }
}
