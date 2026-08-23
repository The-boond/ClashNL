package io.nekohasekai.sfa.repository

import java.net.URI

object RemoteProfileUrlPolicy {
    fun validate(value: String): String {
        val normalized = value.trim()
        require(normalized.isNotEmpty()) { "Remote profile URL is empty" }

        val uri =
            runCatching { URI(normalized) }
                .getOrElse { throw IllegalArgumentException("Remote profile URL is invalid", it) }
        require(!uri.host.isNullOrBlank()) { "Remote profile URL must include a host" }
        require(uri.rawUserInfo == null) {
            "Remote profile URL must not include embedded user information"
        }
        val isHttps = uri.scheme.equals("https", ignoreCase = true)
        val isLoopbackHttp =
            uri.scheme.equals("http", ignoreCase = true) && isAllowedLoopbackHost(uri.host)
        require(isHttps || isLoopbackHttp) {
            "Remote profile URL must use HTTPS (HTTP is only allowed for loopback hosts)"
        }
        return normalized
    }

    private fun isAllowedLoopbackHost(host: String): Boolean = host.removePrefix("[").removeSuffix("]").lowercase() in
        setOf("localhost", "127.0.0.1", "::1")
}
