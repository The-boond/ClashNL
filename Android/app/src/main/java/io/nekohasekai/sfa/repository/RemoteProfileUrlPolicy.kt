package io.nekohasekai.sfa.repository

import java.net.URI

object RemoteProfileUrlPolicy {
    fun validate(value: String): String {
        val normalized = value.trim()
        require(normalized.isNotEmpty()) { "Remote profile URL is empty" }

        val uri =
            runCatching { URI(normalized) }
                .getOrElse { throw IllegalArgumentException("Remote profile URL is invalid", it) }
        require(uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) {
            "Remote profile URL must use HTTP or HTTPS"
        }
        require(!uri.host.isNullOrBlank()) { "Remote profile URL must include a host" }
        require(uri.rawUserInfo == null) {
            "Remote profile URL must not include embedded user information"
        }
        return normalized
    }
}
