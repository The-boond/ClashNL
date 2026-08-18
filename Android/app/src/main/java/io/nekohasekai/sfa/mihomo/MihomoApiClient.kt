package io.nekohasekai.sfa.mihomo

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
import java.util.concurrent.TimeUnit

/** Internal authenticated adapter for Mihomo's loopback REST/WebSocket API. */
internal class MihomoApiClient(
    controllerPort: Int,
    private val secret: String,
    private val client: OkHttpClient = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build(),
) {
    private val baseUrl = HttpUrl.Builder().scheme("http").host("localhost").port(controllerPort).build()

    fun requireReady() {
        request("version").use { response ->
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
                        delay = detail?.optDelay(),
                    )
                },
            )
        }.toList()
    }

    fun selectProxy(group: String, proxy: String) {
        val body = JSONObject().put("name", proxy).toString().toRequestBody(JSON_MEDIA_TYPE)
        request("proxies", group, method = "PUT", body = body).use { response ->
            check(response.isSuccessful) { "Mihomo proxy selection failed (HTTP ${response.code})" }
        }
    }

    fun testDelay(proxy: String, url: String, timeoutMillis: Long): MihomoDelayResult {
        val response = request(
            "proxies",
            proxy,
            "delay",
            query = mapOf("url" to url, "timeout" to timeoutMillis.toString()),
        ).use(::requireJson)
        return MihomoDelayResult(response.getInt("delay"))
    }

    fun updateProxyProvider(name: String) {
        request("providers", "proxies", name, method = "PUT").use { response ->
            check(response.isSuccessful) { "Mihomo provider update failed (HTTP ${response.code})" }
        }
    }

    fun getConnections(): List<MihomoConnection> {
        val root = request("connections").use(::requireJson)
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
            )
        }.toList()
    }

    fun observeTraffic(): Flow<MihomoTraffic> = callbackFlow {
        val request = Request.Builder().url(baseUrl.newBuilder().addPathSegment("traffic").build())
            .header("Authorization", "Bearer $secret")
            .build()
        val socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = runCatching { JSONObject(text) }.getOrNull() ?: return
                    trySend(MihomoTraffic(message.optLong("up"), message.optLong("down")))
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit
            },
        )
        awaitClose { socket.close(1000, null) }
    }

    private fun request(
        vararg path: String,
        method: String = "GET",
        body: okhttp3.RequestBody? = null,
        query: Map<String, String> = emptyMap(),
    ): okhttp3.Response {
        val url = baseUrl.newBuilder().apply {
            path.forEach(::addPathSegment)
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        return client.newCall(
            Request.Builder().url(url).header("Authorization", "Bearer $secret").method(method, body).build(),
        ).execute()
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

    private fun JSONObject.optDelay(): Int? = optJSONObject("history")?.optJSONArray("delay")?.let { delays ->
        (0 until delays.length()).mapNotNull(delays::optInt).lastOrNull()?.takeIf { it > 0 }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
