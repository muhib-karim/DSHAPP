package com.dsh.android.webbridge

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.security.SecureRandom

/**
 * OpenAI chat-completions ⇄ DeepSeek web chat (pure). The web chat takes one prompt per new chat and has no tool calling,
 * so a request becomes one transcript prompt; when the request carries `tools`, the prompt adds a small protocol and
 * `<tool_call>{…}</tool_call>` blocks in the reply become OpenAI `tool_calls`, which lets dsh's agent loop run its tools.
 */
object OpenAiMapping {
    const val MODEL_CHAT = "deepseek-web-chat"
    const val MODEL_REASONER = "deepseek-web-reasoner"

    /** Prompts are kept below this many characters by dropping the oldest turns (system and last turn always stay). */
    const val MAX_PROMPT_CHARS = 120_000

    data class WebPrompt(val prompt: String, val thinking: Boolean, val search: Boolean, val hasTools: Boolean)
    data class ToolCall(val id: String, val name: String, val arguments: String)

    fun toPrompt(request: JSONObject): WebPrompt {
        val messages = request.optJSONArray("messages") ?: JSONArray()
        require(messages.length() > 0) { "messages is empty" }
        val model = request.optString("model")
        val thinking = model == MODEL_REASONER || request.has("reasoning_effort") && model != MODEL_CHAT
        val tools = request.optJSONArray("tools")?.takeIf { it.length() > 0 }

        val system = mutableListOf<String>()
        val turns = mutableListOf<String>()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val text = contentText(m.opt("content"))
            when (m.optString("role")) {
                "system", "developer" -> if (text.isNotBlank()) system += text
                "assistant" -> {
                    val calls = m.optJSONArray("tool_calls")
                    val rendered = buildString {
                        append(text)
                        if (calls != null) for (c in 0 until calls.length()) {
                            val fn = calls.optJSONObject(c)?.optJSONObject("function") ?: continue
                            if (isNotEmpty()) append('\n')
                            append("<tool_call>").append(toolCallJson(fn.optString("name"), fn.optString("arguments"))).append("</tool_call>")
                        }
                    }
                    turns += "[Assistant]\n$rendered"
                }
                "tool", "function" -> turns += "[Tool result ${m.optString("tool_call_id").ifEmpty { m.optString("name") }}]\n$text"
                else -> turns += "[User]\n$text"
            }
        }
        if (tools != null) system += toolProtocol(tools)

        if (system.isEmpty() && turns.size == 1 && turns[0].startsWith("[User]\n")) {
            val prompt = turns[0].removePrefix("[User]\n")
            require(prompt.length <= MAX_PROMPT_CHARS) { "Prompt is too large; shorten the last message." }
            return WebPrompt(prompt, thinking, false, false)
        }
        val head = if (system.isEmpty()) "" else "[System]\n" + system.joinToString("\n\n") + "\n\n"
        val tail = "\n\nReply as the assistant to the last message."
        val kept = ArrayDeque(turns)
        while (kept.size > 1 && head.length + kept.sumOf { it.length + 2 } + tail.length > MAX_PROMPT_CHARS) kept.removeFirst()
        val body = kept.joinToString("\n\n")
        require(head.length + body.length + tail.length <= MAX_PROMPT_CHARS) {
            "Prompt is too large; shorten the system instructions, tool definitions or last message."
        }
        return WebPrompt(head + body + tail, thinking, false, tools != null)
    }

    /**
     * Splits `<tool_call>` blocks, then DeepSeek's own DSML tool-call markup ([parseDsml]), out of a reply; malformed
     * blocks stay in the text.
     */
    fun parseToolCalls(reply: String): Pair<String, List<ToolCall>> {
        val calls = mutableListOf<ToolCall>()
        val text = TOOL_BLOCK.replace(reply) { match ->
            val json = runCatching { JSONObject(match.groupValues[1].trim()) }.getOrNull()
            val name = json?.optString("name").orEmpty()
            if (json == null || name.isEmpty()) return@replace match.value
            val args = when (val a = json.opt("arguments")) {
                is JSONObject -> a.toString()
                is String -> a.ifBlank { "{}" }
                null, JSONObject.NULL -> "{}"
                else -> a.toString()
            }
            calls += ToolCall("call_" + randomId(), name, args)
            ""
        }
        return parseDsml(text, calls).trim() to calls
    }

    /**
     * The web model sometimes ignores the `<tool_call>` protocol and answers in its native DSML markup:
     * `<｜DSML｜function_calls><｜DSML｜invoke name="bash"><｜DSML｜parameter name="command" string="true">ls</｜DSML｜parameter>
     * </｜DSML｜invoke></｜DSML｜function_calls>`. Each well-formed invoke becomes a [ToolCall] appended to [calls]; the
     * bar may be fullwidth (U+FF5C) or ASCII, tags may carry spaces, the wrapper may be `function_calls` or `calls`.
     * `string="false"` parameters are JSON values (number, boolean, object, array), anything else is a string. An invoke
     * without a name stays in the text. The bridge parses only complete replies, so tags split across stream chunks
     * are whole by the time this runs.
     */
    private fun parseDsml(text: String, calls: MutableList<ToolCall>): String {
        if (!DSML_MARK.containsMatchIn(text)) return text
        var found = false
        val rest = DSML_INVOKE.replace(text) { match ->
            val name = match.groupValues[1].trim()
            if (name.isEmpty()) return@replace match.value
            val args = JSONObject()
            for (param in DSML_PARAM.findAll(match.groupValues[2])) {
                val key = param.groupValues[1].trim()
                if (key.isEmpty()) continue
                val raw = param.groupValues[3].trim('\n', '\r')
                val json = DSML_STRING_ATTR.find(param.groupValues[2])?.groupValues?.get(1)?.lowercase() == "false"
                args.put(key, if (json) jsonValue(raw) else raw)
            }
            calls += ToolCall("call_" + randomId(), name, args.toString())
            found = true
            ""
        }
        return if (found) DSML_WRAPPER.replace(rest, "") else text
    }

    /** A `string="false"` value as JSON; text that is not exactly one JSON value stays a string. */
    private fun jsonValue(raw: String): Any = runCatching {
        val tokener = JSONTokener(raw.trim())
        val value = tokener.nextValue()
        if (tokener.nextClean() == 0.toChar()) value else raw
    }.getOrDefault(raw)

    fun completionJson(id: String, model: String, created: Long, text: String, reasoning: String,
                       calls: List<ToolCall>, promptTokens: Int, completionTokens: Int): JSONObject {
        val message = JSONObject().put("role", "assistant").put("content", if (text.isEmpty() && calls.isNotEmpty()) JSONObject.NULL else text)
        if (reasoning.isNotEmpty()) message.put("reasoning_content", reasoning)
        if (calls.isNotEmpty()) message.put("tool_calls", toolCallsJson(calls, withIndex = false))
        return JSONObject().put("id", id).put("object", "chat.completion").put("created", created).put("model", model)
            .put("choices", JSONArray().put(JSONObject().put("index", 0).put("message", message)
                .put("finish_reason", if (calls.isEmpty()) "stop" else "tool_calls")))
            .put("usage", usage(promptTokens, completionTokens))
    }

    fun chunkJson(id: String, model: String, created: Long, delta: JSONObject, finishReason: String?, usage: JSONObject? = null): JSONObject =
        JSONObject().put("id", id).put("object", "chat.completion.chunk").put("created", created).put("model", model)
            .put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", delta)
                .put("finish_reason", finishReason ?: JSONObject.NULL)))
            .apply { if (usage != null) put("usage", usage) }

    fun toolCallsJson(calls: List<ToolCall>, withIndex: Boolean): JSONArray = JSONArray().apply {
        calls.forEachIndexed { i, c ->
            put(JSONObject().apply { if (withIndex) put("index", i) }.put("id", c.id).put("type", "function")
                .put("function", JSONObject().put("name", c.name).put("arguments", c.arguments)))
        }
    }

    fun usage(prompt: Int, completion: Int): JSONObject =
        JSONObject().put("prompt_tokens", prompt).put("completion_tokens", completion).put("total_tokens", prompt + completion)

    /** Rough token estimate (the web chat reports none): about four characters per token. */
    fun estimateTokens(text: String) = (text.length + 3) / 4

    fun modelsJson(): JSONObject = JSONObject().put("object", "list").put("data", JSONArray()
        .put(JSONObject().put("id", MODEL_CHAT).put("object", "model").put("owned_by", "deepseek-web"))
        .put(JSONObject().put("id", MODEL_REASONER).put("object", "model").put("owned_by", "deepseek-web")))

    private fun contentText(content: Any?): String = when (content) {
        null, JSONObject.NULL -> ""
        is String -> content
        is JSONArray -> (0 until content.length()).mapNotNull { i ->
            val part = content.optJSONObject(i) ?: return@mapNotNull content.opt(i)?.toString()
            when (part.optString("type")) {
                "text", "input_text" -> part.optString("text")
                "image_url", "input_image", "image" -> "[image omitted]"
                else -> null
            }
        }.joinToString("\n")
        else -> content.toString()
    }

    private fun toolProtocol(tools: JSONArray): String = buildString {
        append("You can call tools. Available tools:\n")
        for (i in 0 until tools.length()) {
            val fn = tools.optJSONObject(i)?.optJSONObject("function") ?: continue
            append("- ").append(fn.optString("name"))
            fn.optString("description").takeIf { it.isNotBlank() }?.let { append(": ").append(it.lines().first().take(300)) }
            append("\n  parameters: ").append(fn.optJSONObject("parameters")?.toString() ?: "{}").append('\n')
        }
        append("To call tools, reply with one or more blocks exactly like\n")
        append("<tool_call>{\"name\": \"tool_name\", \"arguments\": {...}}</tool_call>\n")
        append("and write nothing after them. Each result comes back in a message that starts with [Tool result <id>]. ")
        append("When no tool is needed, answer normally without any <tool_call> block.")
    }

    private fun toolCallJson(name: String, arguments: String): String {
        val args: Any = runCatching { JSONObject(arguments) }.getOrElse { arguments }
        return "{\"name\":${JSONObject.quote(name)},\"arguments\":${if (args is JSONObject) args.toString() else JSONObject.quote(arguments)}}"
    }

    private val random = SecureRandom()
    private fun randomId() = (1..16).map { "abcdefghijklmnopqrstuvwxyz0123456789"[random.nextInt(36)] }.joinToString("")
    private val TOOL_BLOCK = Regex("<tool_call>(.*?)</tool_call>", RegexOption.DOT_MATCHES_ALL)

    /** `｜DSML｜` with a fullwidth or ASCII bar and optional spaces, as the web model writes it. */
    private const val DSML_BAR = """\s*[｜|]+\s*DSML\s*[｜|]+\s*"""
    private val DSML_MARK = Regex("""[｜|]\s*DSML\s*[｜|]""")
    private val DSML_INVOKE = Regex(
        """<${DSML_BAR}invoke\s+name\s*=\s*"([^"]*)"\s*>(.*?)<\s*/${DSML_BAR}invoke\s*>""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val DSML_PARAM = Regex(
        """<${DSML_BAR}parameter\s+name\s*=\s*"([^"]*)"((?:\s+[A-Za-z_-]+\s*=\s*"[^"]*")*)\s*>(.*?)<\s*/${DSML_BAR}parameter\s*>""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val DSML_STRING_ATTR = Regex("""string\s*=\s*"(true|false)"""", RegexOption.IGNORE_CASE)
    private val DSML_WRAPPER = Regex("""<\s*/?${DSML_BAR}(?:function_)?calls\s*>""")
}
