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
 * Minimal HTTP client for the Codewhale (formerly DeepSeek-TUI) Runtime API.
 *
 * Expects `codewhale app-server --http` running on localhost:7878 (default).
 * See https://github.com/Hmbown/CodeWhale/blob/main/docs/RUNTIME_API.md
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
        body.addProperty("prompt", prompt)
        val req = Request.Builder()
            .url("$baseUrl/v1/threads/$threadId/turns")
            .post(body.toString().toRequestBody(json))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("sendTurn failed: HTTP ${resp.code}")
            val data = JsonParser.parseString(resp.body?.string()).asJsonObject
            // The API may return { thread, turn } — tolerate both shapes.
            val turn = data.get("turn")?.asJsonObject
            return turn?.get("id")?.asString
                ?: data.get("id")?.asString
                ?: data.get("turn_id")?.asString
                ?: ""
        }
    }

    /**
     * Stream events for a thread over SSE. [onDelta] receives incremental
     * assistant text; [onEvent] receives every raw event JSON for logging.
     * Returns when the turn reaches a terminal state (turn.completed /
     * turn.failed / turn.interrupted) or the connection closes.
     */
    fun streamEvents(threadId: String, onDelta: (String) -> Unit, onEvent: (String) -> Unit) {
        val req = Request.Builder()
            .url("$baseUrl/v1/threads/$threadId/events?since_seq=0")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("stream failed: HTTP ${resp.code}")
            resp.body?.let { body ->
                val reader = body.byteStream().bufferedReader()
                var line: String? = reader.readLine()
                while (line != null) {
                    if (line.startsWith("data:")) {
                        val payload = line.removePrefix("data:").trim()
                        if (!payload.isEmpty() && payload != "[DONE]") {
                            onEvent(payload)
                            var terminal = false
                            runCatching {
                                val obj = JsonParser.parseString(payload).asJsonObject
                                val event = obj.get("event")?.asString ?: ""
                                val p = obj.getAsJsonObject("payload")
                                when (event) {
                                    "item.delta", "item.started" -> {
                                        val delta = p?.get("delta")?.asString
                                        if (!delta.isNullOrEmpty()) onDelta(delta)
                                    }
                                    "turn.completed", "turn.failed", "turn.interrupted" ->
                                        terminal = true
                                }
                            }
                            if (terminal) return@use
                        }
                    }
                    line = reader.readLine()
                }
            }
        }
    }
}
