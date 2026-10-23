package com.dsh.android.webbridge

import org.json.JSONArray
import org.json.JSONObject

/** What one SSE event of a web completion adds to the reply. */
sealed class WebDelta {
    data class Thinking(val text: String) : WebDelta()
    data class Text(val text: String) : WebDelta()
    object Finished : WebDelta()
}

/** The web service refused the request (its `code`), see [DeepSeekWebClient.errorFor]. */
class WebServiceError(val code: Int, message: String) : Exception(message)

/**
 * Reducer for chat.deepseek.com's completion stream. The reply is a list of fragments (`THINK`, `RESPONSE`, search and
 * tool steps); events patch it with `{"p": path, "o": APPEND|SET|BATCH, "v": value}`. A patch without `p`/`o` reuses the
 * previous top-level ones; BATCH children carry paths relative to the batch. Only THINK and RESPONSE text is surfaced;
 * search results, token counts and other metadata are ignored. Written for this app after the format documented in
 * muhib-karim/deepseek-android (`DeepSeekStream.kt`, MIT).
 */
class WebSseParser {
    private class Fragment(val type: String, val text: StringBuilder)

    private val fragments = mutableListOf<Fragment>()
    private var topPath = ""
    private var topOperation = "APPEND"
    private var topValueOperation = "APPEND"
    var finished = false
        private set

    fun feed(event: String?, data: String): List<WebDelta> {
        if (event in METADATA_EVENTS || finished) return emptyList()
        val trimmed = data.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed == "[DONE]") return finish()
        val json = try { JSONObject(trimmed) } catch (e: Exception) { return emptyList() }
        checkCode(json)
        val out = mutableListOf<WebDelta>()
        if (json.has("p") || json.has("o") || json.has("v")) {
            val path = if (json.has("p")) json.optString("p").trim('/') else topPath
            val value = json.opt("v")
            val operation = if (json.has("o")) json.optString("o")
            else if (topOperation == "BATCH" && value !is JSONArray) topValueOperation else topOperation
            if (json.has("p")) topPath = path
            topOperation = operation
            if (operation != "BATCH") topValueOperation = operation
            apply(path, operation, value, out)
        } else {
            apply("", "SET", json, out)
        }
        if (event == "finish" || event == "done") out += finish()
        return out
    }

    private fun finish(): List<WebDelta> {
        if (finished) return emptyList()
        finished = true
        return listOf(WebDelta.Finished)
    }

    private fun apply(path: String, operation: String, value: Any?, out: MutableList<WebDelta>) {
        if (value == null || value == JSONObject.NULL) return
        if (operation == "BATCH") {
            val list = value as? JSONArray ?: return
            var childPath = ""
            var childOperation = "SET"
            for (i in 0 until list.length()) {
                val patch = list.optJSONObject(i) ?: continue
                checkCode(patch)
                if (patch.has("p")) childPath = patch.optString("p").trim('/')
                if (patch.has("o")) childOperation = patch.optString("o")
                apply(listOf(path, childPath).filter { it.isNotEmpty() }.joinToString("/"), childOperation, patch.opt("v"), out)
            }
            return
        }
        when {
            path.isEmpty() -> (value as? JSONObject)?.optJSONObject("response")?.let { response(it, out) }
            path == "response" -> (value as? JSONObject)?.let { response(it, out) }
            path == "response/status" -> if (value == "FINISHED") out += finish()
            path == "response/fragments" -> {
                if (operation == "SET") fragments.clear()
                (value as? JSONArray)?.let { list -> for (i in 0 until list.length()) list.optJSONObject(i)?.let { add(it, out) } }
            }
            path.startsWith("response/fragments/") -> fragmentPatch(path, operation, value, out)
        }
    }

    private fun response(value: JSONObject, out: MutableList<WebDelta>) {
        value.optJSONArray("fragments")?.let { list ->
            fragments.clear()
            for (i in 0 until list.length()) list.optJSONObject(i)?.let { add(it, out) }
        }
        if (value.optString("status") == "FINISHED") out += finish()
    }

    private fun add(fragment: JSONObject, out: MutableList<WebDelta>) {
        val f = Fragment(fragment.optString("type"), StringBuilder())
        fragments += f
        append(f, fragment.optString("content"), out)
    }

    private fun fragmentPatch(path: String, operation: String, value: Any, out: MutableList<WebDelta>) {
        val match = FRAGMENT_PATH.matchEntire(path) ?: return
        val raw = match.groupValues[1].toInt()
        val index = if (raw < 0) fragments.size + raw else raw
        val fragment = fragments.getOrNull(index) ?: return
        when (match.groupValues[2]) {
            "" -> (value as? JSONObject)?.let { fragments[index] = Fragment(it.optString("type"), StringBuilder()); append(fragments[index], it.optString("content"), out) }
            "content" -> {
                val text = value as? String ?: return
                if (operation == "APPEND") append(fragment, text, out)
                else if (text.startsWith(fragment.text)) append(fragment, text.substring(fragment.text.length), out)
            }
        }
    }

    private fun append(fragment: Fragment, text: String, out: MutableList<WebDelta>) {
        if (text.isEmpty()) return
        fragment.text.append(text)
        when (fragment.type) {
            "THINK" -> out += WebDelta.Thinking(text)
            "RESPONSE" -> out += WebDelta.Text(text)
        }
    }

    private fun checkCode(json: JSONObject) {
        val code = (json.opt("code") as? Number)?.toInt() ?: return
        if (code != 0) throw WebServiceError(code, json.optString("msg").ifEmpty { "DeepSeek web error $code" })
    }

    private companion object {
        val METADATA_EVENTS = setOf("ready", "update_session", "title", "close", "update_file")
        val FRAGMENT_PATH = Regex("response/fragments/(-?[0-9]{1,6})(?:/([a-z_]{1,40}))?")
    }
}
