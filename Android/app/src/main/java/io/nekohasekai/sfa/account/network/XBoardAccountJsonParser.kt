package io.nekohasekai.sfa.account.network

import io.nekohasekai.sfa.account.model.AccountDetails
import io.nekohasekai.sfa.account.model.AccountPlan
import io.nekohasekai.sfa.account.model.AccountSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

object XBoardAccountJsonParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseLogin(email: String, body: String): AccountSession {
        val data = parseData(body)
        val authorization = data.string("auth_data")
            ?.takeIf { it.startsWith("Bearer ") && it.length > "Bearer ".length }
            ?: throw AccountApiException("登录响应缺少有效令牌")
        return AccountSession(email = email, authorization = authorization)
    }

    fun parseSubscription(body: String): AccountDetails {
        val data = parseData(body)
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

    private fun parseData(body: String): JsonObject {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw AccountApiException("服务器返回了无效数据", it) }
        val status = root.string("status")
        if (status != "success") {
            throw AccountApiException(root.string("message") ?: "请求失败")
        }
        return root["data"]?.objectOrNull()
            ?: throw AccountApiException("服务器响应缺少数据")
    }

    private fun JsonElement.objectOrNull(): JsonObject? = if (this is JsonObject) this else null

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
}

class AccountApiException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
