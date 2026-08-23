package io.nekohasekai.sfa.vendor

import android.os.Build
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.update.SemanticVersion
import io.nekohasekai.sfa.update.UpdateInfo
import io.nekohasekai.sfa.update.UpdateTrack
import io.nekohasekai.sfa.utils.AppHttpTransport
import io.nekohasekai.sfa.utils.HTTPClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.Closeable
import java.io.IOException
import java.util.Locale

class GitHubUpdateChecker : Closeable {
    companion object {
        private const val RELEASES_URL = "https://api.github.com/repos/The-boond/ClashNL/releases"
        private const val METADATA_FILENAME = "ClashNL-version-metadata.json"
        private val KNOWN_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun checkUpdate(track: UpdateTrack, githubToken: String): UpdateInfo? {
        val releases = getReleases(githubToken)
        var selected: ReleaseCandidate? = null

        for (release in releases) {
            if (!isReleaseInTrack(release, track)) {
                continue
            }
            val metadata = runCatching { downloadMetadata(release) }.getOrNull() ?: continue
            val apkAsset = findCompatibleApk(release) ?: continue
            if (!isNewerThanCurrent(metadata.versionName)) {
                continue
            }
            val currentBest = selected
            if (currentBest == null || isBetterVersion(metadata, currentBest.metadata)) {
                selected = ReleaseCandidate(release, metadata, apkAsset)
            }
        }

        val candidate = selected ?: return null
        val release = candidate.release
        val metadata = candidate.metadata

        return UpdateInfo(
            versionCode = metadata.versionCode,
            versionName = metadata.versionName,
            downloadUrl = candidate.apkAsset.browserDownloadUrl,
            releaseUrl = release.htmlUrl,
            releaseNotes = release.body,
            isPrerelease = release.prerelease,
            fileSize = candidate.apkAsset.size,
        )
    }

    private fun findCompatibleApk(release: GitHubRelease): GitHubAsset? {
        val isLegacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
        val candidates = release.assets.filter { asset ->
            val name = asset.name.lowercase(Locale.ROOT)
            name.endsWith(".apk") &&
                asset.browserDownloadUrl.isNotBlank() &&
                !name.contains("play") &&
                name.contains("legacy-android-5") == isLegacy
        }
        for (abi in Build.SUPPORTED_ABIS) {
            candidates.find { assetMatchesAbi(it.name, abi) }?.let { return it }
        }
        candidates.find { it.name.contains("universal", ignoreCase = true) }?.let { return it }
        return candidates.find { asset ->
            KNOWN_ABIS.none { abi -> assetMatchesAbi(asset.name, abi) }
        }
    }

    private fun assetMatchesAbi(assetName: String, abi: String): Boolean = assetName.endsWith("-$abi.apk", ignoreCase = true) ||
        assetName.contains("-$abi-", ignoreCase = true)

    private fun getReleases(githubToken: String): List<GitHubRelease> {
        val request = Request.Builder()
            .url(RELEASES_URL)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", HTTPClient.userAgent)
        val token = githubToken.trim()
        if (token.isNotEmpty()) {
            request.header("Authorization", "Bearer $token")
        }

        return executeString(request.build()).let(json::decodeFromString)
    }

    private fun isReleaseInTrack(release: GitHubRelease, track: UpdateTrack): Boolean {
        if (release.draft) {
            return false
        }
        return when (track) {
            UpdateTrack.STABLE -> !release.prerelease
            UpdateTrack.BETA -> true
        }
    }

    private fun isNewerThanCurrent(versionName: String): Boolean = SemanticVersion.compare(versionName, BuildConfig.VERSION_NAME)?.let { it > 0 } == true

    private fun isBetterVersion(version: VersionMetadata, other: VersionMetadata): Boolean {
        when (SemanticVersion.compare(version.versionName, other.versionName)) {
            null -> return false
            in Int.MIN_VALUE until 0 -> return false
            in 1..Int.MAX_VALUE -> return true
        }
        return version.versionCode > other.versionCode
    }

    private fun downloadMetadata(release: GitHubRelease): VersionMetadata? {
        val metadataAsset = release.assets.find { it.name == METADATA_FILENAME }
            ?: return null

        val request = Request.Builder()
            .url(metadataAsset.browserDownloadUrl)
            .header("Accept", "application/json")
            .header("User-Agent", HTTPClient.userAgent)
            .build()

        return json.decodeFromString<VersionMetadata>(executeString(request))
    }

    private fun executeString(request: Request): String = AppHttpTransport.execute(request, preferLocalSocks = true).use { response ->
        if (!response.isSuccessful) {
            throw IOException("GitHub request failed (HTTP ${response.code})")
        }
        response.body?.string() ?: throw IOException("GitHub returned an empty response")
    }

    override fun close() {
        // AppHttpTransport is stateless; retained for existing use-call sites.
    }

    @Serializable
    data class GitHubRelease(
        @SerialName("tag_name") val tagName: String = "",
        val name: String = "",
        val body: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        @SerialName("html_url") val htmlUrl: String = "",
        val assets: List<GitHubAsset> = emptyList(),
    )

    @Serializable
    data class GitHubAsset(
        val name: String = "",
        @SerialName("browser_download_url") val browserDownloadUrl: String = "",
        val size: Long = 0,
    )

    @Serializable
    data class VersionMetadata(
        @SerialName("version_code") val versionCode: Int = 0,
        @SerialName("version_name") val versionName: String = "",
    )

    private data class ReleaseCandidate(
        val release: GitHubRelease,
        val metadata: VersionMetadata,
        val apkAsset: GitHubAsset,
    )
}
