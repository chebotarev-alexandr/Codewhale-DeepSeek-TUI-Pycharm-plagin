package com.chebotarev.deepseekplugin

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the Codewhale (formerly DeepSeek-TUI) Runtime API.
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
     * [workspace] anchors the agent to the project root. [autoApprove] controls
     * whether approval-gated tools (file edits, shell) run without asking:
     * false = "Ask" posture, the turn pauses with an `approval.required` event
     * and the client resolves it via [resolveApproval].
     */
    fun createThread(model: String?, workspace: String?, autoApprove: Boolean = false): String {
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

    /** Toggle auto-approve on an existing thread (PATCH /v1/threads/{id}). */
    fun patchAutoApprove(threadId: String, autoApprove: Boolean) {
        val body = JsonObject()
        body.addProperty("auto_approve", autoApprove)
        val req = Request.Builder()
            .url("$baseUrl/v1/threads/$threadId")
            .patch(body.toString().toRequestBody(json))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("patchThread failed: HTTP ${resp.code}")
        }
    }

    /**
     * Resolve a pending tool approval.
     * [decision] is "allow" or "deny"; [remember] auto-applies the decision to
     * subsequent matching approvals.
     */
    fun resolveApproval(approvalId: String, decision: String, remember: Boolean = false) {
        val body = JsonObject()
        body.addProperty("decision", decision)
        body.addProperty("remember", remember)
        val req = Request.Builder()
            .url("$baseUrl/v1/approvals/$approvalId")
            .post(body.toString().toRequestBody(json))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string() ?: ""
                throw RuntimeException("resolveApproval failed: HTTP ${resp.code} $errBody")
            }
        }
    }

    /** Compact the conversation context (POST /v1/threads/{id}/compact). */
    fun compactThread(threadId: String) {
        val req = Request.Builder()
            .url("$baseUrl/v1/threads/$threadId/compact")
            .post("{}".toRequestBody(json))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("compact failed: HTTP ${resp.code}")
        }
    }

    /**
     * Undo the last turn: forks the thread with the last turn removed. Returns
     * the forked thread id (may differ from the original), or null if unknown.
     */
    fun undoThread(threadId: String): String? {
        val body = JsonObject()
        body.addProperty("depth", 0)
        val req = Request.Builder()
            .url("$baseUrl/v1/threads/$threadId/undo")
            .post(body.toString().toRequestBody(json))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("undo failed: HTTP ${resp.code}")
            val data = JsonParser.parseString(resp.body?.string()).asJsonObject
            return data.getAsJsonObject("thread")?.get("id")?.asString
                ?: data.get("id")?.asString
        }
    }

    /** Send a user turn to a thread. Returns the turn id. */
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
            val turn = data.get("turn")?.asJsonObject
            return turn?.get("id")?.asString
                ?: data.get("id")?.asString
                ?: data.get("turn_id")?.asString
                ?: ""
        }
    }

    /**
     * Stream events for a thread over SSE, starting after [sinceSeq].
     *
     * [onAnswerDelta] receives the assistant's actual answer text
     * (payload.kind == "agent_message"); [onReasoningDelta] receives reasoning
     * "thinking" text (payload.kind == "agent_reasoning").
     * [onApprovalRequired] receives (approvalId, description) when a tool needs
     * approval — the client must call [resolveApproval] to continue the turn.
     * [onEvent] receives every raw event JSON for logging.
     *
     * Returns the last seen sequence number (cursor). Stops when the current
     * turn reaches a terminal state.
     */
    fun streamEvents(
        threadId: String,
        sinceSeq: Long,
        onAnswerDelta: (String) -> Unit,
        onReasoningDelta: (String) -> Unit,
        onApprovalRequired: (String, String) -> Unit,
        onFileChange: (path: String?, detail: String?, summary: String?) -> Unit = { _, _, _ -> },
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
                                    "item.delta" -> {
                                        val delta = p?.get("delta")?.asString
                                        val kind = p?.get("kind")?.asString ?: ""
                                        if (!delta.isNullOrEmpty()) {
                                            when (kind) {
                                                "agent_reasoning" -> onReasoningDelta(delta)
                                                "agent_message" -> onAnswerDelta(delta)
                                            }
                                        }
                                    }
                                    "approval.required" -> {
                                        val id = approvalIdFrom(obj, p)
                                        if (id != null) onApprovalRequired(id, approvalDescFrom(p))
                                    }
                                    "item.completed" -> {
                                        val item = p?.getAsJsonObject("item")
                                        if (item?.get("kind")?.asString == "file_change") {
                                            onFileChange(
                                                filePathFrom(item.getAsJsonObject("metadata")),
                                                detailTextFrom(item.get("detail")),
                                                item.get("summary")?.takeIf { it.isJsonPrimitive }?.asString,
                                            )
                                        }
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

    /** `detail` is a string on the wire but may also arrive as an `edits[]` array. */
    private fun detailTextFrom(node: JsonElement?): String? = when {
        node == null || node.isJsonNull -> null
        node.isJsonPrimitive -> node.asString
        else -> node.toString()
    }

    private val PATH_KEYS = listOf("path", "file_path", "filePath", "file", "target_file")

    /**
     * Pull a file path out of item metadata. The runtime puts tool arguments in
     * `metadata.tool_input` as a JSON string, so a direct `metadata.path` lookup
     * finds nothing for the file-change items that most want an Open button.
     * Everything here is untrusted model output: parse defensively.
     */
    private fun filePathFrom(metadata: JsonObject?): String? {
        if (metadata == null) return null
        for (k in PATH_KEYS) {
            val v = metadata.get(k)
            if (v?.isJsonPrimitive == true && v.asJsonPrimitive.isString) {
                val s = v.asString.trim()
                if (s.isNotEmpty()) return s
            }
        }
        for (k in listOf("tool_input", "toolInput", "input", "arguments")) {
            val raw = metadata.get(k) ?: continue
            val jsonStr = when {
                raw.isJsonPrimitive && raw.asJsonPrimitive.isString -> raw.asString
                raw.isJsonObject -> raw.toString()
                else -> null
            } ?: continue
            runCatching {
                val parsed = JsonParser.parseString(jsonStr)
                if (parsed.isJsonObject) {
                    for (pk in PATH_KEYS) {
                        val pv = parsed.asJsonObject.get(pk)
                        if (pv?.isJsonPrimitive == true && pv.asJsonPrimitive.isString) {
                            val s = pv.asString.trim()
                            if (s.isNotEmpty()) return s
                        }
                    }
                }
            }
        }
        return null
    }

    private fun approvalIdFrom(obj: JsonObject, p: JsonObject?): String? {
        if (p != null) {
            p.get("approval_id")?.takeIf { it.isJsonPrimitive }?.let { return it.asString }
            p.get("id")?.takeIf { it.isJsonPrimitive }?.let { return it.asString }
            p.getAsJsonObject("approval")?.let { ap ->
                ap.get("id")?.takeIf { it.isJsonPrimitive }?.let { return it.asString }
                ap.get("approval_id")?.takeIf { it.isJsonPrimitive }?.let { return it.asString }
            }
        }
        obj.get("approval_id")?.takeIf { it.isJsonPrimitive }?.let { return it.asString }
        return null
    }

    private fun approvalDescFrom(p: JsonObject?): String {
        if (p == null) return "tool"
        val tool = p.getAsJsonObject("tool")
        val name = p.get("tool_name")?.takeIf { it.isJsonPrimitive }?.asString
            ?: p.get("name")?.takeIf { it.isJsonPrimitive }?.asString
            ?: tool?.get("name")?.takeIf { it.isJsonPrimitive }?.asString
            ?: "tool"
        val desc = p.get("description")?.takeIf { it.isJsonPrimitive }?.asString
            ?: p.get("intent_summary")?.takeIf { it.isJsonPrimitive }?.asString
            ?: p.get("input")?.toString()
            ?: p.get("tool_input")?.takeIf { it.isJsonPrimitive }?.asString
            ?: tool?.get("input")?.toString()
        val trimmed = desc?.take(300) ?: ""
        return if (trimmed.isBlank()) name else "$name  $trimmed"
    }
}
