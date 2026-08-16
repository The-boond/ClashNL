package io.nekohasekai.sfa.utils

import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.BuildConfig
import okhttp3.Request
import java.io.Closeable
import java.util.Locale

class HTTPClient : Closeable {
    data class Response(
        val content: String,
        val headers: Map<String, String>,
    )

    companion object {
        val userAgent by lazy {
            var userAgent = "ClashNl-Android/"
            userAgent += BuildConfig.VERSION_NAME
            userAgent += " ("
            userAgent += BuildConfig.VERSION_CODE
            userAgent += "; sing-box "
            userAgent += Libbox.version()
            userAgent += "; language "
            userAgent += Locale.getDefault().toLanguageTag().replace("-", "_")
            userAgent += ")"
            userAgent
        }

        /**
         * Subscription endpoints commonly select a response format from the
         * User-Agent. Keep this token compatible with sing-box providers while
         * retaining the branded UA for ordinary diagnostics and API calls.
         */
        val subscriptionUserAgent by lazy {
            "sing-box/${Libbox.version()}"
        }
    }

    fun get(
        url: String,
        requestUserAgent: String = userAgent,
    ): Response {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", requestUserAgent)
            .header("Accept", "*/*")
            .build()
        try {
            return AppHttpTransport.execute(request).use { response ->
                val content = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("订阅请求失败（HTTP ${response.code}）")
                }
                if (content.isBlank()) {
                    throw IllegalStateException("订阅响应为空")
                }
                val headers =
                    listOf(
                        "subscription-userinfo",
                        "profile-update-interval",
                        "profile-web-page-url",
                    ).mapNotNull { key ->
                        response.header(key)?.trim()?.takeIf { it.isNotEmpty() }?.let { key to it }
                    }.toMap()
                Response(content, headers)
            }
        } catch (exception: IllegalStateException) {
            throw exception
        } catch (exception: Exception) {
            throw IllegalStateException("订阅同步失败，请检查网络后重试", exception)
        }
    }

    fun getString(url: String): String = get(url).content

    fun getSubscription(url: String): Response = get(url, subscriptionUserAgent)

    fun getSubscriptionString(url: String): String = getSubscription(url).content

    override fun close() = Unit
}
