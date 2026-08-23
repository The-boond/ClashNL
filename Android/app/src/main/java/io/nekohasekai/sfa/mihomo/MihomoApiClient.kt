package io.nekohasekai.sfa.mihomo

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Internal authenticated adapter for Mihomo's loopback REST/WebSocket API. */
internal class MihomoApiClient(
    controllerPort: Int,
    private val secret: String,
    private val restClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    private val baseUrl = HttpUrl.Builder().scheme("http").host("127.0.0.1").port(controllerPort).build()
    private val streamClient = restClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun requireReady() {
        request("version", callTimeoutMillis = READY_PROBE_TIMEOUT_MILLIS).use { response ->
            check(response.isSuccessful) { "Mihomo API is not ready (HTTP ${response.code})" }
        }
    }

    fun getProxyGroups(): List<MihomoProxyGroup> {
        val root = request("proxies").use(::requireJson)
        val proxies = root.optJSONObject("proxies") ?: return emptyList()
        return proxies.keys().asSequence().mapNotNull { name ->
            val proxy = proxies.optJSONObject(name) ?: return@mapNotNull null
            val type = proxy.optString("type")
            val all = proxy.optJSONArray("all") ?: return@mapNotNull null
            val selected = proxy.optString("now")
            MihomoProxyGroup(
                name = name,
                type = type,
                selected = selected,
                selectable = type.equals("Selector", ignoreCase = true),
                proxies = all.toStringList().map { item ->
                    val detail = proxies.optJSONObject(item)
                    MihomoProxy(
                        name = item,
                        type = detail?.optString("type").orEmpty(),
                        alive = detail?.optBooleanOrNull("alive"),
                        delay = detail?.optMihomoDelay(),
                    )
                },
            )
        }.toList()
    }

    fun getMode(): String = request("configs").use(::requireJson).optString("mode", "rule")

    fun requireHttpProxyPort(expectedPort: Int) {
        val actualPort = request("configs").use(::requireJson).optInt("port", 0)
        check(actualPort == expectedPort) {
            "Mihomo HTTP proxy listener did not start on the app-owned port"
        }
    }

    suspend fun selectProxy(group: String, proxy: String) {
        val body = JSONObject().put("name", proxy).toString().toRequestBody(JSON_MEDIA_TYPE)
        requestCancellable(
            "proxies",
            group,
            method = "PUT",
            body = body,
            callTimeoutMillis = CONTROL_CALL_TIMEOUT_MILLIS,
        ).use { response ->
            check(response.isSuccessful) { "Mihomo proxy selection failed (HTTP ${response.code})" }
        }
    }

    suspend fun testDelay(proxy: String, url: String, timeoutMillis: Long): MihomoDelayResult {
        val response = requestCancellable(
            "proxies",
            proxy,
            "delay",
            query = mapOf("url" to url, "timeout" to timeoutMillis.toString()),
            callTimeoutMillis = timeoutMillis.coerceAtLeast(1L) + DELAY_CALL_TIMEOUT_BUFFER_MILLIS,
        ).use(::requireJson)
        return MihomoDelayResult(response.getInt("delay"))
    }

    fun updateProxyProvider(name: String) {
        request("providers", "proxies", name, method = "PUT").use { response ->
            check(response.isSuccessful) { "Mihomo provider update failed (HTTP ${response.code})" }
        }
    }

    suspend fun getConnections(): List<MihomoConnection> {
        val root = requestCancellable("connections", callTimeoutMillis = CONTROL_CALL_TIMEOUT_MILLIS).use(::requireJson)
        val connections = root.optJSONArray("connections") ?: return emptyList()
        return connections.asSequence().map { item ->
            val connection = item as JSONObject
            val metadata = connection.optJSONObject("metadata")
            MihomoConnection(
                id = connection.optString("id"),
                host = metadata?.optString("host").orEmpty(),
                network = metadata?.optString("network").orEmpty(),
                chains = connection.optJSONArray("chains")?.toStringList().orEmpty(),
                upload = connection.optLong("upload"),
                download = connection.optLong("download"),
                type = metadata?.optString("type").orEmpty(),
                sourceIp = metadata?.optString("sourceIP").orEmpty(),
                destinationIp = metadata?.optString("destinationIP").orEmpty(),
                sourcePort = metadata?.optString("sourcePort").orEmpty(),
                destinationPort = metadata?.optString("destinationPort").orEmpty(),
                inboundName = metadata?.optString("inboundName").orEmpty(),
                inboundUser = metadata?.optString("inboundUser").orEmpty(),
                process = metadata?.optString("process").orEmpty(),
                processPath = metadata?.optString("processPath").orEmpty(),
                start = connection.optString("start"),
                rule = connection.optString("rule"),
                rulePayload = connection.optString("rulePayload"),
            )
        }.toList()
    }

    fun closeConnection(id: String) {
        request("connections", id, method = "DELETE").use { response ->
            check(response.isSuccessful) { "Mihomo connection close failed (HTTP ${response.code})" }
        }
    }

    fun closeAllConnections() {
        request("connections", method = "DELETE").use { response ->
            check(response.isSuccessful) { "Mihomo connections close failed (HTTP ${response.code})" }
        }
    }

    fun observeTraffic(): Flow<MihomoTraffic> = callbackFlow {
        val request = Request.Builder().url(baseUrl.newBuilder().addPathSegment("traffic").build())
            .header("Authorization", "Bearer $secret")
            .build()
        val socket = streamClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = runCatching { JSONObject(text) }.getOrNull() ?: return
                    trySend(MihomoTraffic(message.optLong("up"), message.optLong("down")))
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    close()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                    close(IOException("Mihomo traffic stream failed", t))
                }
            },
        )
        awaitClose { socket.close(1000, null) }
    }

    fun observeLogs(level: String): Flow<MihomoLogEntry> = callbackFlow {
        val request = Request.Builder()
            .url(
                baseUrl.newBuilder()
                    .addPathSegment("logs")
                    .addQueryParameter("level", level)
                    .build(),
            )
            .header("Authorization", "Bearer $secret")
            .build()
        val socket = streamClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = runCatching { JSONObject(text) }.getOrNull() ?: return
                    trySend(
                        MihomoLogEntry(
                            level = message.optString("type"),
                            message = message.optString("payload"),
                        ),
                    )
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    close()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                    close(IOException("Mihomo log stream failed", t))
                }
            },
        )
        awaitClose { socket.close(1000, null) }
    }

    private fun request(
        vararg path: String,
        method: String = "GET",
        body: okhttp3.RequestBody? = null,
        query: Map<String, String> = emptyMap(),
        callTimeoutMillis: Long? = null,
    ): okhttp3.Response = newCall(path, method, body, query, callTimeoutMillis).execute()

    private suspend fun requestCancellable(
        vararg path: String,
        method: String = "GET",
        body: okhttp3.RequestBody? = null,
        query: Map<String, String> = emptyMap(),
        callTimeoutMillis: Long? = null,
    ): okhttp3.Response = suspendCancellableCoroutine { continuation ->
        val call = newCall(path, method, body, query, callTimeoutMillis)
        val responseToClose = AtomicReference<okhttp3.Response?>()
        continuation.invokeOnCancellation {
            call.cancel()
            responseToClose.getAndSet(null)?.close()
        }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, exception: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.failure(exception))
                    }
                }

                override fun onResponse(call: Call, response: okhttp3.Response) {
                    responseToClose.set(response)
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.success(response))
                    } else {
                        responseToClose.set(null)
                        response.close()
                    }
                }
            },
        )
    }

    private fun newCall(
        path: Array<out String>,
        method: String,
        body: okhttp3.RequestBody?,
        query: Map<String, String>,
        callTimeoutMillis: Long?,
    ): Call {
        val url = baseUrl.newBuilder().apply {
            path.forEach(::addPathSegment)
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val call = restClient.newCall(
            Request.Builder().url(url).header("Authorization", "Bearer $secret").method(method, body).build(),
        )
        callTimeoutMillis?.let { call.timeout().timeout(it, TimeUnit.MILLISECONDS) }
        return call
    }

    private fun requireJson(response: okhttp3.Response): JSONObject {
        check(response.isSuccessful) { "Mihomo API failed (HTTP ${response.code})" }
        return JSONObject(response.body?.string().orEmpty())
    }

    private fun JSONArray.toStringList(): List<String> = buildList {
        for (index in 0 until length()) {
            optString(index).takeIf { it.isNotBlank() }?.let(::add)
        }
    }

    private fun JSONArray.asSequence(): Sequence<Any> = sequence {
        for (index in 0 until length()) {
            yield(get(index))
        }
    }

    private fun JSONObject.optBooleanOrNull(name: String): Boolean? = takeIf { has(name) && !isNull(name) }?.optBoolean(name)

    private companion object {
        const val READY_PROBE_TIMEOUT_MILLIS = 500L
        const val DELAY_CALL_TIMEOUT_BUFFER_MILLIS = 1_000L
        const val CONTROL_CALL_TIMEOUT_MILLIS = 5_000L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/** Returns the most recent usable delay from Mihomo's proxy history. */
internal fun JSONObject.optMihomoDelay(): Int? {
    val history = opt("history")
    if (history is JSONArray) {
        return history.lastPositiveDelay { entry ->
            if (entry is JSONObject) entry.opt("delay") else entry
        }
    }

    // Compatibility with the previously supported object-shaped history.
    if (history is JSONObject) {
        val delay = history.opt("delay")
        return if (delay is JSONArray) delay.lastPositiveDelay { it } else delay.toPositiveDelay()
    }

    return null
}

private inline fun JSONArray.lastPositiveDelay(valueOf: (Any?) -> Any?): Int? {
    for (index in length() - 1 downTo 0) {
        valueOf(opt(index)).toPositiveDelay()?.let { return it }
    }
    return null
}

private fun Any?.toPositiveDelay(): Int? {
    val value = when (this) {
        is Number -> toLong()
        is String -> toLongOrNull()
        else -> null
    } ?: return null
    return value.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
}
