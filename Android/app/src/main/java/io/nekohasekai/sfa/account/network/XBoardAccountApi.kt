package io.nekohasekai.sfa.account.network

import io.nekohasekai.sfa.account.model.AccountDetails
import io.nekohasekai.sfa.account.model.AccountOrder
import io.nekohasekai.sfa.account.model.AccountSession
import io.nekohasekai.sfa.account.model.BillingPeriod
import io.nekohasekai.sfa.account.model.CheckoutResult
import io.nekohasekai.sfa.account.model.PaymentMethod
import io.nekohasekai.sfa.account.model.PlanOffer
import io.nekohasekai.sfa.utils.HTTPClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

interface AccountApi {
    suspend fun login(email: String, password: String): AccountSession

    suspend fun getSubscription(authorization: String): AccountDetails

    suspend fun getPlans(authorization: String): List<PlanOffer>

    suspend fun getPaymentMethods(authorization: String): List<PaymentMethod>

    suspend fun createOrder(authorization: String, planId: Long, period: BillingPeriod): String

    suspend fun getPendingOrders(authorization: String): List<AccountOrder>

    suspend fun checkout(authorization: String, tradeNo: String, methodId: Long): CheckoutResult

    suspend fun checkOrder(authorization: String, tradeNo: String): Int

    suspend fun cancelOrder(authorization: String, tradeNo: String): Boolean
}

class XBoardAccountApi(
    baseUrl: String,
) : AccountApi {
    private val baseUrl = validateBaseUrl(baseUrl)

    override suspend fun login(email: String, password: String): AccountSession = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("email", email.trim())
            put("password", password)
        }.toString()
        val response = request(
            path = "/api/v1/passport/auth/login",
            method = "POST",
            body = body,
        )
        XBoardAccountJsonParser.parseLogin(email.trim(), response)
    }

    override suspend fun getSubscription(authorization: String): AccountDetails = withContext(Dispatchers.IO) {
        val response = request(
            path = "/api/v1/user/getSubscribe",
            method = "GET",
            authorization = authorization,
        )
        XBoardAccountJsonParser.parseSubscription(response)
    }

    override suspend fun getPlans(authorization: String): List<PlanOffer> = withContext(Dispatchers.IO) {
        XBoardAccountJsonParser.parsePlans(
            request(path = "/api/v1/user/plan/fetch", method = "GET", authorization = authorization),
        )
    }

    override suspend fun getPaymentMethods(authorization: String): List<PaymentMethod> = withContext(Dispatchers.IO) {
        XBoardAccountJsonParser.parsePaymentMethods(
            request(
                path = "/api/v1/user/order/getPaymentMethod",
                method = "GET",
                authorization = authorization,
            ),
        )
    }

    override suspend fun createOrder(
        authorization: String,
        planId: Long,
        period: BillingPeriod,
    ): String = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("plan_id", planId)
            put("period", period.apiValue)
        }.toString()
        XBoardAccountJsonParser.parseTradeNo(
            request(
                path = "/api/v1/user/order/save",
                method = "POST",
                body = body,
                authorization = authorization,
            ),
        )
    }

    override suspend fun getPendingOrders(authorization: String): List<AccountOrder> = withContext(Dispatchers.IO) {
        XBoardAccountJsonParser.parseOrders(
            request(
                path = "/api/v1/user/order/fetch?status=0",
                method = "GET",
                authorization = authorization,
            ),
        )
    }

    override suspend fun checkout(
        authorization: String,
        tradeNo: String,
        methodId: Long,
    ): CheckoutResult = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("trade_no", tradeNo)
            put("method", methodId)
        }.toString()
        XBoardAccountJsonParser.parseCheckout(
            request(
                path = "/api/v1/user/order/checkout",
                method = "POST",
                body = body,
                authorization = authorization,
            ),
        )
    }

    override suspend fun checkOrder(authorization: String, tradeNo: String): Int = withContext(Dispatchers.IO) {
        XBoardAccountJsonParser.parseOrderStatus(
            request(
                path = "/api/v1/user/order/check?trade_no=${encodeQueryValue(tradeNo)}",
                method = "GET",
                authorization = authorization,
            ),
        )
    }

    override suspend fun cancelOrder(authorization: String, tradeNo: String): Boolean = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("trade_no", tradeNo) }.toString()
        val cancelled = XBoardAccountJsonParser.parseSuccess(
            request(
                path = "/api/v1/user/order/cancel",
                method = "POST",
                body = body,
                authorization = authorization,
            ),
        )
        if (!cancelled) throw AccountApiException("取消订单失败")
        true
    }

    private fun request(
        path: String,
        method: String,
        body: String? = null,
        authorization: String? = null,
    ): String {
        val connection = URL("$baseUrl$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", HTTPClient.userAgent)
            authorization?.let { connection.setRequestProperty("Authorization", it) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (response.isBlank()) {
                throw AccountApiException("服务器响应为空（HTTP $statusCode）")
            }
            if (statusCode !in 200..299) {
                val message = XBoardAccountJsonParser.parseErrorMessage(response)
                    ?: "请求失败（HTTP $statusCode）"
                throw AccountApiException(message)
            }
            return response
        } catch (exception: AccountApiException) {
            throw exception
        } catch (exception: Exception) {
            throw AccountApiException(exception.message ?: "网络请求失败", exception)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 15_000
        private const val READ_TIMEOUT_MILLIS = 20_000

        private fun encodeQueryValue(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

        fun validateBaseUrl(value: String): String {
            val normalized = value.trim().trimEnd('/')
            val uri = runCatching { URI(normalized) }
                .getOrElse { throw IllegalArgumentException("Invalid account API base URL", it) }
            require(uri.scheme.equals("https", ignoreCase = true)) {
                "Account API base URL must use HTTPS"
            }
            require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null) {
                "Account API base URL must have a host and no user info"
            }
            require(uri.rawQuery == null && uri.rawFragment == null) {
                "Account API base URL must not contain query or fragment"
            }
            return normalized
        }
    }
}
