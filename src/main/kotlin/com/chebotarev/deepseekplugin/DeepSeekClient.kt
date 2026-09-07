package com.chebotarev.deepseekplugin

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Minimal HTTP client for the DeepSeek-TUI Runtime API.
 *
 * Expects `deepseek serve --http` running on localhost:7878 (default port).
 * See https://github.com/Hmbown/DeepSeek-TUI/blob/main/docs/RUNTIME_API.md
 */
class DeepSeekClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 7878,
) {
    private val baseUrl = "http://$host:$port"
    private val json = "application/json; charset=utf-8".toMediaType()
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // SSE streams are long-lived
        .build()

    /** True if the runtime API is reachable and healthy. */
    fun isServerUp(): Boolean {
        val req = Request.Builder().url("$baseUrl/health").get().build()
        return runCatching {
            client.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /**
     * Create a new thread. Returns the thread id, or throws on failure.
     */
    fun createThread(model: String?): String {
        val body = JsonObject()
        model?.let { body.addProperty("model", it) }
        val req = Request.Builder()
            .url("$baseUrl/v1/threads")
            .post(body.toString().toRequestBody(json))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("createThread failed: HTTP ${resp.code}")
            val data = JsonParser.parseString(resp.body?.string()).asJsonObject
            return data.get("id").asString
        }
    }

    /**
     * Send a user turn to a thread. Returns the turn id.
     */
    fun sendTurn(threadId: String, prompt: String): String {
        val body = JsonObject()
        body.addProperty("content", prompt)
        val req = Request.Builder()
            .url("$baseUrl/v1/threads/$threadId/turns")
            .post(body.toString().toRequestBody(json))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("sendTurn failed: HTTP ${resp.code}")
            val data = JsonParser.parseString(resp.body?.string()).asJsonObject
            // The API may return { id, ... } or { turn_id, ... } — tolerate both.
            return data.get("id")?.asString
                ?: data.get("turn_id")?.asString
                ?: ""
        }
    }

    /**
     * Stream events for a thread over SSE. [onDelta] receives incremental
     * assistant text; [onEvent] receives every raw event JSON for logging.
     * Blocks until the stream closes. Throws on connection failure.
     */
    fun streamEvents(threadId: String, onDelta: (String) -> Unit, onEvent: (String) -> Unit) {
        val req = Request.Builder()
            .url("$baseUrl/v1/threads/$threadId/events?since_seq=0")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("stream failed: HTTP ${resp.code}")
            resp.body?.let { body ->
                body.byteStream().bufferedReader().forEachLine { line ->
                    if (line.startsWith("data:")) {
                        val payload = line.removePrefix("data:").trim()
                        if (payload.isEmpty() || payload == "[DONE]") return@forEachLine
                        onEvent(payload)
                        runCatching {
                            val obj = JsonParser.parseString(payload).asJsonObject
                            val event = obj.get("event")?.asString ?: ""
                            val p = obj.getAsJsonObject("payload")
                            if (event == "item.delta" || event == "item.started") {
                                val delta = p?.get("delta")?.asString
                                if (!delta.isNullOrEmpty()) onDelta(delta)
                            }
                        }
                    }
                }
            }
        }
    }
}
