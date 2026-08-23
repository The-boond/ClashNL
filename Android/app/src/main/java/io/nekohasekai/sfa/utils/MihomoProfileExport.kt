package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileCore
import java.io.File
import java.net.URLEncoder

object MihomoProfileExport {
    const val CONTENT_TYPE = "application/yaml"

    fun read(profile: Profile): ByteArray {
        require(profile.typed.core == ProfileCore.Mihomo) {
            "Only Mihomo YAML profiles can be exported"
        }
        return File(profile.typed.path).readBytes()
    }

    fun fileName(profileName: String): String {
        val safeName =
            profileName
                .replace(Regex("[\\u0000-\\u001f\\\\/:*?\"<>|]"), "_")
                .trim()
                .trimEnd('.', ' ')
                .ifBlank { "profile" }
        return "$safeName.yaml"
    }

    fun remoteImportLink(profileName: String, remoteURL: String): String = "clashnl://import-remote-profile" +
        "?url=${encodeQueryParameter(remoteURL)}" +
        "&name=${encodeQueryParameter(profileName)}"

    private fun encodeQueryParameter(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}
