package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.config.MihomoProfileContent
import io.nekohasekai.sfa.repository.RemoteProfileUrlPolicy
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
            userAgent += "; mihomo "
            userAgent += BuildConfig.CORE_VERSION
            userAgent += "; language "
            userAgent += Locale.getDefault().toLanguageTag().replace("-", "_")
            userAgent += ")"
            userAgent
        }

        /**
         * Subscription endpoints commonly select a response format from the
         * User-Agent. Request the Clash/Mihomo representation consistently.
         */
        val subscriptionUserAgent by lazy {
            "clash.meta/${BuildConfig.CORE_VERSION}"
        }

        /** Requests the Clash/Mihomo representation from a subscription endpoint. */
        val mihomoSubscriptionUserAgent by lazy {
            subscriptionUserAgent
        }
    }

    fun get(
        url: String,
        requestUserAgent: String = userAgent,
        networkRoute: AppHttpTransport.NetworkRoute = AppHttpTransport.NetworkRoute.Underlying,
    ): Response {
        val validatedUrl = RemoteProfileUrlPolicy.validate(url)
        val request = Request.Builder()
            .url(validatedUrl)
            .header("User-Agent", requestUserAgent)
            .header("Accept", "*/*")
            .build()
        try {
            return AppHttpTransport.execute(
                request,
                preferLocalSocks = true,
                networkRoute = networkRoute,
            ).use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("订阅请求失败（HTTP ${response.code}）")
                }
                RemoteProfileUrlPolicy.validate(response.request.url.toString())
                val body = response.body
                val content = body?.byteStream()?.use { stream ->
                    MihomoProfileContent.readUtf8(stream, body.contentLength())
                }.orEmpty()
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

    /** Queries through Android's active route so VPN diagnostics observe the real tunnel exit. */
    fun getStringViaActiveNetwork(url: String): String = get(url, networkRoute = AppHttpTransport.NetworkRoute.Active).content

    fun getSubscription(url: String): Response = get(url, subscriptionUserAgent)

    fun getMihomoSubscription(url: String): Response = get(url, mihomoSubscriptionUserAgent)

    fun getSubscriptionString(url: String): String = getSubscription(url).content

    override fun close() = Unit
}
