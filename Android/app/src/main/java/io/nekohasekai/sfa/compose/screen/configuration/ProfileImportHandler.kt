package io.nekohasekai.sfa.compose.screen.configuration

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.config.MihomoProfileContent
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileCore
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.mihomo.MihomoConfig
import io.nekohasekai.sfa.mihomo.MihomoRuntimeRepository
import io.nekohasekai.sfa.repository.ProfileRemoteRepository
import io.nekohasekai.sfa.repository.SubscriptionMetadataParser
import io.nekohasekai.sfa.utils.HTTPClient
import io.nekohasekai.sfa.utils.ProfileConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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
                context.contentResolver.openInputStream(uri)?.use { MihomoProfileContent.readUtf8(it) }
                    ?: return@withContext ImportResult.Error(context.getString(R.string.error_empty_file))
            val filename = getFileNameFromUri(uri)
            val normalized = runCatching { normalizeAndValidate(data) }
            normalized.getOrNull()?.let {
                return@withContext importConfiguration(it, filename)
            }

            ImportResult.Error(
                normalized.exceptionOrNull()?.message
                    ?: context.getString(R.string.error_decode_profile, "Unknown profile format"),
            )
        } catch (exception: Exception) {
            ImportResult.Error(exception.message ?: "Unknown error")
        }
    }

    suspend fun parseUri(uri: Uri): UriParseResult = withContext(Dispatchers.IO) {
        try {
            val data =
                context.contentResolver.openInputStream(uri)?.use { MihomoProfileContent.readUtf8(it) }
                    ?: return@withContext UriParseResult.Error(context.getString(R.string.error_empty_file))
            val filename = getFileNameFromUri(uri)

            if (runCatching { normalizeAndValidate(data) }.isSuccess) {
                return@withContext UriParseResult.Success(filename)
            }

            UriParseResult.Error(
                context.getString(R.string.error_decode_profile, "仅支持 Clash/Mihomo YAML"),
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

            if (runCatching { normalizeAndValidate(data) }.isSuccess) {
                return@withContext QRCodeParseResult.LocalProfile("ClashNl Profile")
            }
            QRCodeParseResult.Error(context.getString(R.string.error_decode_profile, "仅支持 Clash/Mihomo YAML"))
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

            val normalized = runCatching { normalizeAndValidate(data) }
            normalized.getOrNull()?.let {
                return@withContext importConfiguration(it, "ClashNl Profile")
            }
            ImportResult.Error(normalized.exceptionOrNull()?.message ?: "仅支持 Clash/Mihomo YAML")
        } catch (exception: Exception) {
            ImportResult.Error(exception.message ?: "Unknown error")
        }
    }

    suspend fun parseQRSData(data: ByteArray): QRSParseResult = withContext(Dispatchers.IO) {
        try {
            val dataString = MihomoProfileContent.decodeUtf8(data)
            if (runCatching { normalizeAndValidate(dataString) }.isSuccess) {
                return@withContext QRSParseResult.Success("ClashNl Profile")
            }
            QRSParseResult.Error(context.getString(R.string.error_decode_profile, "仅支持 Clash/Mihomo YAML"))
        } catch (exception: Exception) {
            QRSParseResult.Error(
                context.getString(R.string.error_decode_profile, exception.message ?: "Unknown profile format"),
            )
        }
    }

    suspend fun importFromQRSData(data: ByteArray): ImportResult = withContext(Dispatchers.IO) {
        try {
            val dataString = MihomoProfileContent.decodeUtf8(data)
            val normalized = runCatching { normalizeAndValidate(dataString) }
            normalized.getOrNull()?.let {
                return@withContext importConfiguration(it, "ClashNl Profile")
            }
            ImportResult.Error(normalized.exceptionOrNull()?.message ?: "仅支持 Clash/Mihomo YAML")
        } catch (exception: Exception) {
            ImportResult.Error(exception.message ?: "Unknown error")
        }
    }

    private suspend fun importRemoteProfile(name: String, url: String): ImportResult {
        val fetched = ProfileRemoteRepository.fetch(url)
        val normalized = normalizeAndValidate(fetched.content)
        val typedProfile =
            TypedProfile().apply {
                type = TypedProfile.Type.Remote
                core = ProfileCore.Mihomo
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
        val configFile = File(configDirectory, "$fileID.yaml")
        ProfileConfigStore.write(configFile, normalized)
        typedProfile.path = configFile.path

        ProfileManager.create(profile, andSelect = true)
        return ImportResult.Success(profile)
    }

    private suspend fun importConfiguration(configContent: String, profileName: String): ImportResult = try {
        val normalized = normalizeAndValidate(configContent)
        val typedProfile =
            TypedProfile().apply {
                type = TypedProfile.Type.Local
                core = ProfileCore.Mihomo
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
        val configFile = File(configDirectory, "$fileID.yaml")
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

    private suspend fun normalizeAndValidate(content: String): String {
        val normalized = MihomoProfileContent.normalize(content)
        MihomoRuntimeRepository.controller(context).validateConfig(MihomoConfig(normalized))
        return normalized
    }

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
