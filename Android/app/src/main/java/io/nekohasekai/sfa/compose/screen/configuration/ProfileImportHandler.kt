package io.nekohasekai.sfa.compose.screen.configuration

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.ProfileContent
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.config.ClashConfigNormalizer
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.repository.RemoteProfileRepository
import io.nekohasekai.sfa.repository.SubscriptionMetadataParser
import io.nekohasekai.sfa.utils.HTTPClient
import io.nekohasekai.sfa.utils.ProfileConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Date

class ProfileImportHandler(private val context: Context) {
    sealed class ImportResult {
        data class Success(val profile: Profile) : ImportResult()

        data class Error(val message: String) : ImportResult()
    }

    sealed class QRCodeParseResult {
        data class RemoteProfile(val name: String, val host: String, val url: String) : QRCodeParseResult()

        data class LocalProfile(val name: String) : QRCodeParseResult()

        data class Error(val message: String) : QRCodeParseResult()
    }

    sealed class QRSParseResult {
        data class Success(val name: String) : QRSParseResult()

        data class Error(val message: String) : QRSParseResult()
    }

    sealed class UriParseResult {
        data class Success(val name: String) : UriParseResult()

        data class Error(val message: String) : UriParseResult()
    }

    private data class RemoteProfileLink(
        val name: String,
        val host: String,
        val url: String,
    )

    suspend fun importFromUri(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        try {
            val data =
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@withContext ImportResult.Error(context.getString(R.string.error_empty_file))
            val filename = getFileNameFromUri(uri)
            val dataString = String(data, StandardCharsets.UTF_8)
            val normalized = runCatching { ClashConfigNormalizer.normalize(dataString) }
            normalized.getOrNull()?.let {
                return@withContext importConfiguration(it.content, filename)
            }

            val profileContent = runCatching { Libbox.decodeProfileContent(data) }
            profileContent.getOrNull()?.let {
                return@withContext importProfile(it)
            }

            ImportResult.Error(
                normalized.exceptionOrNull()?.message
                    ?: profileContent.exceptionOrNull()?.message
                    ?: context.getString(R.string.error_decode_profile, "Unknown profile format"),
            )
        } catch (exception: Exception) {
            ImportResult.Error(exception.message ?: "Unknown error")
        }
    }

    suspend fun parseUri(uri: Uri): UriParseResult = withContext(Dispatchers.IO) {
        try {
            val data =
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@withContext UriParseResult.Error(context.getString(R.string.error_empty_file))
            val filename = getFileNameFromUri(uri)
            val dataString = String(data, StandardCharsets.UTF_8)

            if (runCatching { ClashConfigNormalizer.normalize(dataString) }.isSuccess) {
                return@withContext UriParseResult.Success(filename)
            }

            val profileContent = runCatching { Libbox.decodeProfileContent(data) }
            profileContent.getOrNull()?.let {
                return@withContext UriParseResult.Success(it.name)
            }

            UriParseResult.Error(
                profileContent.exceptionOrNull()?.message
                    ?: context.getString(R.string.error_decode_profile, "Unknown profile format"),
            )
        } catch (exception: Exception) {
            UriParseResult.Error(exception.message ?: "Unknown error")
        }
    }

    suspend fun parseQRCode(data: String): QRCodeParseResult = withContext(Dispatchers.IO) {
        try {
            parseRemoteProfileLink(data)?.let {
                return@withContext QRCodeParseResult.RemoteProfile(it.name, it.host, it.url)
            }

            if (runCatching { ClashConfigNormalizer.normalize(data) }.isSuccess) {
                return@withContext QRCodeParseResult.LocalProfile("ClashNl Profile")
            }

            val content = Libbox.decodeProfileContent(data.toByteArray())
            QRCodeParseResult.LocalProfile(content.name)
        } catch (exception: Exception) {
            QRCodeParseResult.Error(
                context.getString(R.string.error_decode_profile, exception.message ?: "Unknown profile format"),
            )
        }
    }

    suspend fun importFromQRCode(data: String): ImportResult = withContext(Dispatchers.IO) {
        try {
            parseRemoteProfileLink(data)?.let {
                return@withContext importRemoteProfile(it.name, it.url)
            }

            val normalized = runCatching { ClashConfigNormalizer.normalize(data) }
            normalized.getOrNull()?.let {
                return@withContext importConfiguration(it.content, "ClashNl Profile")
            }

            importProfile(Libbox.decodeProfileContent(data.toByteArray()))
        } catch (exception: Exception) {
            ImportResult.Error(exception.message ?: "Unknown error")
        }
    }

    suspend fun parseQRSData(data: ByteArray): QRSParseResult = withContext(Dispatchers.IO) {
        try {
            val dataString = String(data, StandardCharsets.UTF_8)
            if (runCatching { ClashConfigNormalizer.normalize(dataString) }.isSuccess) {
                return@withContext QRSParseResult.Success("ClashNl Profile")
            }
            QRSParseResult.Success(Libbox.decodeProfileContent(data).name)
        } catch (exception: Exception) {
            QRSParseResult.Error(
                context.getString(R.string.error_decode_profile, exception.message ?: "Unknown profile format"),
            )
        }
    }

    suspend fun importFromQRSData(data: ByteArray): ImportResult = withContext(Dispatchers.IO) {
        try {
            val dataString = String(data, StandardCharsets.UTF_8)
            val normalized = runCatching { ClashConfigNormalizer.normalize(dataString) }
            normalized.getOrNull()?.let {
                return@withContext importConfiguration(it.content, "ClashNl Profile")
            }
            importProfile(Libbox.decodeProfileContent(data))
        } catch (exception: Exception) {
            ImportResult.Error(exception.message ?: "Unknown error")
        }
    }

    private suspend fun importProfile(content: ProfileContent): ImportResult {
        val typedProfile = TypedProfile()

        when (content.type) {
            Libbox.ProfileTypeLocal -> {
                typedProfile.type = TypedProfile.Type.Local
            }
            Libbox.ProfileTypeiCloud -> {
                return ImportResult.Error(context.getString(R.string.icloud_profile_unsupported))
            }
            Libbox.ProfileTypeRemote -> {
                typedProfile.type = TypedProfile.Type.Remote
                typedProfile.remoteURL = content.remotePath
                typedProfile.autoUpdate = content.autoUpdate
                typedProfile.autoUpdateInterval = content.autoUpdateInterval
                typedProfile.lastUpdated = Date(content.lastUpdated)
            }
        }
        val profileName =
            if (typedProfile.type == TypedProfile.Type.Remote && content.name.isBlank()) {
                ProfileNameGenerator.nextDefaultSubscriptionName(context)
            } else {
                content.name.ifBlank { context.getString(R.string.imported_profile_default_name) }
            }
        val profile = Profile(name = profileName, typed = typedProfile)
        profile.userOrder = ProfileManager.nextOrder()

        val source = if (content.config.isNotBlank()) {
            content.config
        } else if (typedProfile.type == TypedProfile.Type.Remote && typedProfile.remoteURL.isNotBlank()) {
            HTTPClient().use {
                val response = it.getSubscription(typedProfile.remoteURL)
                SubscriptionMetadataParser.parse(response.headers, response.content)?.replaceOn(typedProfile)
                response.content
            }
        } else {
            return ImportResult.Error(context.getString(R.string.error_empty_file))
        }
        val normalized = ClashConfigNormalizer.normalize(source).content

        val fileID = ProfileManager.nextFileID()
        val configDirectory = File(context.filesDir, "configs").also { it.mkdirs() }
        val configFile = File(configDirectory, "$fileID.json")
        ProfileConfigStore.write(configFile, normalized)
        typedProfile.path = configFile.path

        ProfileManager.create(profile, andSelect = true)
        return ImportResult.Success(profile)
    }

    private suspend fun importRemoteProfile(name: String, url: String): ImportResult {
        val fetched = RemoteProfileRepository.fetchNormalized(url)
        val typedProfile =
            TypedProfile().apply {
                type = TypedProfile.Type.Remote
                remoteURL = url
                autoUpdate = true
                autoUpdateInterval = 60
                lastUpdated = Date()
            }
        fetched.metadata?.replaceOn(typedProfile)
        val profileName =
            name.trim().ifEmpty {
                ProfileNameGenerator.nextDefaultSubscriptionName(context)
            }
        val profile =
            Profile(name = profileName, typed = typedProfile).apply {
                userOrder = ProfileManager.nextOrder()
            }

        val fileID = ProfileManager.nextFileID()
        val configDirectory = File(context.filesDir, "configs").also { it.mkdirs() }
        val configFile = File(configDirectory, "$fileID.json")
        ProfileConfigStore.write(configFile, fetched.content)
        typedProfile.path = configFile.path

        ProfileManager.create(profile, andSelect = true)
        return ImportResult.Success(profile)
    }

    private suspend fun importConfiguration(configContent: String, profileName: String): ImportResult = try {
        val normalized = ClashConfigNormalizer.normalize(configContent).content
        val typedProfile =
            TypedProfile().apply {
                type = TypedProfile.Type.Local
            }
        val profile =
            Profile(
                name = profileName.ifEmpty { "Imported Profile" },
                typed = typedProfile,
            ).apply {
                userOrder = ProfileManager.nextOrder()
            }

        val fileID = ProfileManager.nextFileID()
        val configDirectory = File(context.filesDir, "configs").also { it.mkdirs() }
        val configFile = File(configDirectory, "$fileID.json")
        ProfileConfigStore.write(configFile, normalized)
        typedProfile.path = configFile.path

        ProfileManager.create(profile, andSelect = true)
        ImportResult.Success(profile)
    } catch (exception: Exception) {
        ImportResult.Error(exception.message ?: "Unknown error importing configuration")
    }

    private fun parseRemoteProfileLink(value: String): RemoteProfileLink? {
        val uri = runCatching { Uri.parse(value.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null

        if (scheme == "http" || scheme == "https") {
            return RemoteProfileLink(
                name = "",
                host = extractHostFromUrl(value),
                url = value,
            )
        }

        if (scheme == "sing-box" && uri.host == "import-remote-profile") {
            val profileInfo = Libbox.parseRemoteProfileImportLink(value)
            return RemoteProfileLink(profileInfo.name, profileInfo.host, profileInfo.url)
        }

        val isClashInstallLink =
            (scheme == "clash" || scheme == "clashmeta") && uri.host == "install-config"
        val isClashNlLink = scheme == "clashnl" && uri.host == "import-remote-profile"
        if (!isClashInstallLink && !isClashNlLink) {
            return null
        }

        val remoteURL = uri.getQueryParameter("url")?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: throw IllegalArgumentException("Profile link does not contain a valid http(s) url")
        val name =
            uri.getQueryParameter("name")
                ?.takeIf { it.isNotBlank() }
                .orEmpty()
        return RemoteProfileLink(name, extractHostFromUrl(remoteURL), remoteURL)
    }

    private fun extractHostFromUrl(url: String): String = runCatching { Uri.parse(url).host }.getOrNull() ?: url

    private fun getFileNameFromUri(uri: Uri): String {
        var filename = "Imported Profile"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                filename =
                    cursor.getString(nameIndex)
                        ?.substringBeforeLast(".")
                        ?.takeIf { it.isNotEmpty() }
                        ?: filename
            }
        }

        if (filename == "Imported Profile") {
            uri.lastPathSegment?.let { segment ->
                filename =
                    segment
                        .substringBeforeLast(".")
                        .takeIf { it.isNotEmpty() }
                        ?: filename
            }
        }
        return filename
    }
}
