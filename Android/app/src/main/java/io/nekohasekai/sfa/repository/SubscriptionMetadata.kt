package io.nekohasekai.sfa.repository

import io.nekohasekai.sfa.database.TypedProfile
import java.net.URI
import kotlin.math.max

data class SubscriptionMetadata(
    val upload: Long? = null,
    val download: Long? = null,
    val total: Long? = null,
    val expireAtMillis: Long? = null,
    val updateIntervalMinutes: Int? = null,
    val webPageUrl: String? = null,
) {
    fun isEmpty(): Boolean = upload == null &&
        download == null &&
        total == null &&
        expireAtMillis == null &&
        updateIntervalMinutes == null &&
        webPageUrl == null

    fun replaceOn(profile: TypedProfile) {
        profile.subscriptionUpload = upload ?: -1L
        profile.subscriptionDownload = download ?: -1L
        profile.subscriptionTotal = total ?: -1L
        profile.subscriptionExpireAt = expireAtMillis ?: 0L
        profile.subscriptionUpdateIntervalMinutes = updateIntervalMinutes ?: 0
        profile.subscriptionWebPageURL = webPageUrl.orEmpty()
    }
}

object SubscriptionMetadataParser {
    private const val MIN_UPDATE_INTERVAL_MINUTES = 15
    private const val SECONDS_TO_MILLIS = 1000L
    private const val MAX_COMMENT_LINES = 64

    fun parse(headers: Map<String, String>, body: String? = null): SubscriptionMetadata? {
        val valuesByHeader = parseCommentMetadata(body).toMutableMap()
        headers.forEach { (key, value) ->
            if (key.isMetadataKey() && value.isNotBlank()) {
                valuesByHeader[key.lowercase()] = value.trim()
            }
        }

        val userInfo =
            valuesByHeader["subscription-userinfo"]
        val updateInterval =
            valuesByHeader["profile-update-interval"]
        val webPageUrl =
            valuesByHeader["profile-web-page-url"]

        val values =
            userInfo.orEmpty()
                .split(';')
                .mapNotNull { item ->
                    val separator = item.indexOf('=')
                    if (separator <= 0) return@mapNotNull null
                    item.substring(0, separator).trim().lowercase() to
                        item.substring(separator + 1).trim()
                }.toMap()

        val metadata =
            SubscriptionMetadata(
                upload = values["upload"].toNonNegativeLong(),
                download = values["download"].toNonNegativeLong(),
                total = values["total"].toNonNegativeLong(),
                expireAtMillis = values["expire"].toEpochMillis(),
                updateIntervalMinutes = updateInterval.toIntervalMinutes(),
                webPageUrl = webPageUrl.toSafeWebPageUrl(),
            )
        return metadata.takeUnless { it.isEmpty() }
    }

    private fun parseCommentMetadata(body: String?): Map<String, String> {
        if (body.isNullOrBlank()) return emptyMap()
        return body.lineSequence()
            .take(MAX_COMMENT_LINES)
            .mapNotNull { line ->
                val comment = line.trimStart().takeIf { it.startsWith('#') }
                    ?.removePrefix("#")
                    ?.trim()
                    ?: return@mapNotNull null
                val separator = comment.indexOf(':')
                if (separator <= 0) return@mapNotNull null
                val key = comment.substring(0, separator).trim().lowercase()
                val value = comment.substring(separator + 1).trim()
                if (!key.isMetadataKey() || value.isEmpty()) return@mapNotNull null
                key to value
            }.toMap()
    }

    private fun String.isMetadataKey(): Boolean = this.equals("subscription-userinfo", ignoreCase = true) ||
        this.equals("profile-update-interval", ignoreCase = true) ||
        this.equals("profile-web-page-url", ignoreCase = true)

    private fun String?.toNonNegativeLong(): Long? = this?.toLongOrNull()?.takeIf { it >= 0 }

    private fun String?.toEpochMillis(): Long? {
        val seconds = this?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        if (seconds > Long.MAX_VALUE / SECONDS_TO_MILLIS) return null
        return seconds * SECONDS_TO_MILLIS
    }

    private fun String?.toIntervalMinutes(): Int? {
        val hours = this?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val minutes =
            if (hours > Int.MAX_VALUE.toLong() / 60L) {
                Int.MAX_VALUE.toLong()
            } else {
                max(MIN_UPDATE_INTERVAL_MINUTES.toLong(), hours * 60L)
            }
        return minutes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun String?.toSafeWebPageUrl(): String? {
        val value = this?.trim().orEmpty()
        if (value.isEmpty()) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.scheme.equals("http", ignoreCase = true).not() &&
            uri.scheme.equals("https", ignoreCase = true).not()
        ) {
            return null
        }
        if (uri.host.isNullOrBlank() || uri.rawUserInfo != null) return null
        return value
    }
}
