package io.nekohasekai.sfa.account.network

import io.nekohasekai.sfa.account.model.AccountDetails
import io.nekohasekai.sfa.account.model.AccountOrder
import io.nekohasekai.sfa.account.model.AccountPlan
import io.nekohasekai.sfa.account.model.AccountSession
import io.nekohasekai.sfa.account.model.BillingPeriod
import io.nekohasekai.sfa.account.model.CheckoutResult
import io.nekohasekai.sfa.account.model.PaymentMethod
import io.nekohasekai.sfa.account.model.PlanOffer
import io.nekohasekai.sfa.account.model.PlanPrice
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

object XBoardAccountJsonParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseLogin(email: String, body: String): AccountSession {
        val data = parseDataObject(body)
        val authorization = data.string("auth_data")
            ?.takeIf { it.startsWith("Bearer ") && it.length > "Bearer ".length }
            ?: throw AccountApiException("登录响应缺少有效令牌")
        return AccountSession(email = email, authorization = authorization)
    }

    fun parseSubscription(body: String): AccountDetails {
        val data = parseDataObject(body)
        val planObject = data["plan"]?.objectOrNull()
        val transferLimit = data.long("transfer_enable") ?: 0L
        val plan = planObject?.let {
            AccountPlan(
                id = it.long("id") ?: 0L,
                name = it.string("name").orEmpty(),
                transferLimitBytes = it.long("transfer_enable") ?: transferLimit,
                deviceLimit = it.int("device_limit"),
                speedLimitMbps = it.int("speed_limit"),
            )
        }
        return AccountDetails(
            email = data.string("email").orEmpty(),
            plan = plan,
            uploadedBytes = data.long("u") ?: 0L,
            downloadedBytes = data.long("d") ?: 0L,
            transferLimitBytes = transferLimit,
            expiresAtEpochSeconds = data.long("expired_at") ?: 0L,
            nextResetAtEpochSeconds = data.long("next_reset_at") ?: 0L,
            subscribeUrl = data.string("subscribe_url").orEmpty(),
        )
    }

    fun parsePlans(body: String): List<PlanOffer> = parseData(body).arrayOrNull()
        ?.mapNotNull { it.objectOrNull() }
        ?.map { plan ->
            PlanOffer(
                id = plan.long("id") ?: 0L,
                name = plan.string("name").orEmpty(),
                content = plan.string("content").orEmpty(),
                transferLimitBytes = plan.long("transfer_enable") ?: 0L,
                deviceLimit = plan.int("device_limit"),
                speedLimitMbps = plan.int("speed_limit"),
                soldOut = plan.boolean("sold_out") ?: false,
                purchaseAvailable = plan.boolean("purchase_available") ?: false,
                renewAvailable = plan.boolean("renew") ?: false,
                prices = BillingPeriod.entries.mapNotNull { period ->
                    plan.long(period.apiValue)?.let { PlanPrice(period, it) }
                },
            )
        }
        ?: throw AccountApiException("服务器响应缺少套餐数据")

    fun parsePaymentMethods(body: String): List<PaymentMethod> = parseData(body).arrayOrNull()
        ?.mapNotNull { it.objectOrNull() }
        ?.map { method ->
            PaymentMethod(
                id = method.long("id") ?: 0L,
                name = method.string("name").orEmpty(),
                provider = method.string("payment").orEmpty(),
                handlingFeeFixedCents = method.long("handling_fee_fixed") ?: 0L,
                handlingFeePercent = method.double("handling_fee_percent") ?: 0.0,
            )
        }
        ?: throw AccountApiException("服务器响应缺少支付方式")

    fun parseTradeNo(body: String): String = parseData(body).primitiveContent()
        ?.takeIf { it.isNotBlank() }
        ?: throw AccountApiException("服务器响应缺少订单号")

    fun parseOrders(body: String): List<AccountOrder> = parseData(body).arrayOrNull()
        ?.mapNotNull { it.objectOrNull() }
        ?.map(::parseOrder)
        ?: throw AccountApiException("服务器响应缺少订单数据")

    fun parseCheckout(body: String): CheckoutResult {
        val root = parseRoot(body)
        val type = root.int("type") ?: throw AccountApiException("结算响应缺少类型")
        val data = root["data"]?.primitiveContent()
        if (type != CheckoutResult.TYPE_COMPLETED && data.isNullOrBlank()) {
            throw AccountApiException("结算响应缺少收银台数据")
        }
        return CheckoutResult(type = type, data = data)
    }

    fun parseOrderStatus(body: String): Int = parseData(body).primitiveContent()?.toIntOrNull()
        ?: throw AccountApiException("服务器响应缺少订单状态")

    fun parseSuccess(body: String): Boolean = parseData(body).primitiveContent()?.toBooleanStrictOrNull()
        ?: false

    fun parseErrorMessage(body: String): String? = runCatching {
        parseRoot(body).string("message")
    }.getOrNull()

    private fun parseOrder(order: JsonObject): AccountOrder {
        val plan = order["plan"]?.objectOrNull()
        return AccountOrder(
            tradeNo = order.string("trade_no").orEmpty(),
            planId = order.long("plan_id") ?: plan?.long("id") ?: 0L,
            planName = plan?.string("name").orEmpty(),
            period = order.string("period")?.let(BillingPeriod::fromApiValue),
            totalAmountCents = order.long("total_amount") ?: 0L,
            handlingAmountCents = order.long("handling_amount") ?: 0L,
            status = order.int("status") ?: 0,
            createdAtEpochSeconds = order.long("created_at") ?: 0L,
        )
    }

    private fun parseData(body: String): JsonElement {
        val root = parseRoot(body)
        val status = root.string("status")
        if (status != "success") {
            throw AccountApiException(root.string("message") ?: "请求失败")
        }
        return root["data"] ?: throw AccountApiException("服务器响应缺少数据")
    }

    private fun parseDataObject(body: String): JsonObject = parseData(body).objectOrNull()
        ?: throw AccountApiException("服务器响应缺少数据")

    private fun parseRoot(body: String): JsonObject = runCatching { json.parseToJsonElement(body).jsonObject }
        .getOrElse { throw AccountApiException("服务器返回了无效数据", it) }

    private fun JsonElement.arrayOrNull(): JsonArray? = if (this is JsonArray) this else null

    private fun JsonElement.objectOrNull(): JsonObject? = if (this is JsonObject) this else null

    private fun JsonElement.primitiveContent(): String? = takeUnless { it is JsonNull }
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()

    private fun JsonObject.string(key: String): String? = this[key]
        ?.takeUnless { it is JsonNull }
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()

    private fun JsonObject.long(key: String): Long? {
        val primitive = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive ?: return null
        return primitive.longOrNull ?: primitive.contentOrNull?.trim()?.toLongOrNull()
    }

    private fun JsonObject.int(key: String): Int? {
        val primitive = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive ?: return null
        return primitive.intOrNull ?: primitive.contentOrNull?.trim()?.toIntOrNull()
    }

    private fun JsonObject.double(key: String): Double? {
        val primitive = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive ?: return null
        return primitive.doubleOrNull ?: primitive.contentOrNull?.trim()?.toDoubleOrNull()
    }

    private fun JsonObject.boolean(key: String): Boolean? {
        val primitive = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive ?: return null
        return primitive.booleanOrNull
            ?: primitive.intOrNull?.let { it != 0 }
            ?: primitive.contentOrNull?.trim()?.let {
                when (it.lowercase()) {
                    "true", "1" -> true
                    "false", "0" -> false
                    else -> null
                }
            }
    }
}

class AccountApiException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
