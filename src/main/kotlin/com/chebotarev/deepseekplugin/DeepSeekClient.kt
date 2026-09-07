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
     *
     * [workspace] anchors the agent to the project root so file edits land in
     * the right place. [autoApprove] is enabled so approval-gated tools (file
     * writes, shell) run without an interactive approval UI, which the MVP
     * panel does not yet implement.
     */
    fun createThread(model: String?, workspace: String?, autoApprove: Boolean = true): String {
        val body = JsonObject()
        model?.let { body.addProperty("model", it) }
        if (!workspace.isNullOrBlank()) body.addProperty("workspace", workspace)
        body.addProperty("auto_approve", autoApprove)
        body.addProperty("allow_shell", true)
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
     * Stream events for a thread over SSE, starting after [sinceSeq].
     * [onDelta] receives incremental assistant text; [onEvent] receives every
     * raw event JSON for logging.
     *
     * Returns the last seen sequence number (cursor). The caller should pass
     * that value back as [sinceSeq] on the next call so history is not
     * replayed. Stops when the current turn reaches a terminal state
     * (turn.completed / turn.failed / turn.interrupted).
     */
    fun streamEvents(
        threadId: String,
        sinceSeq: Long,
        onDelta: (String) -> Unit,
        onEvent: (String) -> Unit,
    ): Long {
        var lastSeq = sinceSeq
        val req = Request.Builder()
            .url("$baseUrl/v1/threads/$threadId/events?since_seq=$sinceSeq")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("stream failed: HTTP ${resp.code}")
            resp.body?.let { body ->
                val reader = body.byteStream().bufferedReader()
                var done = false
                var line: String? = reader.readLine()
                while (line != null && !done) {
                    if (line.startsWith("data:")) {
                        val payload = line.removePrefix("data:").trim()
                        if (!payload.isEmpty() && payload != "[DONE]") {
                            onEvent(payload)
                            runCatching {
                                val obj = JsonParser.parseString(payload).asJsonObject
                                val seq = obj.get("seq")?.asLong
                                if (seq != null) lastSeq = seq
                                val event = obj.get("event")?.asString ?: ""
                                val p = obj.getAsJsonObject("payload")
                                when (event) {
                                    "item.delta", "item.started" -> {
                                        val delta = p?.get("delta")?.asString
                                        if (!delta.isNullOrEmpty()) onDelta(delta)
                                    }
                                    "turn.completed", "turn.failed", "turn.interrupted" ->
                                        done = true
                                }
                            }
                        }
                    }
                    line = reader.readLine()
                }
            }
        }
        return lastSeq
    }
}
