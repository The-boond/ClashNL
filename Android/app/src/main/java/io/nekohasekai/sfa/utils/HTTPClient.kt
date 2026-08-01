package io.nekohasekai.sfa.utils

import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.ktx.unwrap
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

    private val client = Libbox.newHTTPClient()

    init {
        client.modernTLS()
    }

    fun get(
        url: String,
        requestUserAgent: String = userAgent,
    ): Response {
        val request = client.newRequest()
        request.setUserAgent(requestUserAgent)
        request.setURL(url)
        val response = request.execute()
        val headers =
            listOf(
                "subscription-userinfo",
                "profile-update-interval",
                "profile-web-page-url",
            ).mapNotNull { key ->
                response.getHeader(key).trim().takeIf { it.isNotEmpty() }?.let { key to it }
            }.toMap()
        return Response(response.content.unwrap, headers)
    }

    fun getString(url: String): String = get(url).content

    fun getSubscription(url: String): Response = get(url, subscriptionUserAgent)

    fun getSubscriptionString(url: String): String = getSubscription(url).content

    override fun close() {
        client.close()
    }
}
