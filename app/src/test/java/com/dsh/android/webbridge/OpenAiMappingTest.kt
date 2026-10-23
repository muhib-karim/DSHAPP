package com.dsh.android.webbridge

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class OpenAiMappingTest {
    private fun request(vararg messages: JSONObject, model: String = "deepseek-web-chat", tools: JSONArray? = null) =
        JSONObject().put("model", model).put("messages", JSONArray(messages.toList())).apply { if (tools != null) put("tools", tools) }
    private fun msg(role: String, content: Any) = JSONObject().put("role", role).put("content", content)

    @Test fun aSingleUserMessageIsSentAsIs() {
        val p = OpenAiMapping.toPrompt(request(msg("user", "Hello there")))
        assertEquals("Hello there", p.prompt)
        assertFalse(p.thinking)
        assertFalse(p.hasTools)
    }

    @Test fun reasonerModelTurnsThinkingOn() {
        assertTrue(OpenAiMapping.toPrompt(request(msg("user", "x"), model = "deepseek-web-reasoner")).thinking)
    }

    @Test fun systemAndHistoryAreFlattenedInOrder() {
        val p = OpenAiMapping.toPrompt(request(
            msg("system", "You are terse."),
            msg("user", "One"),
            msg("assistant", "Uno"),
            msg("user", JSONArray().put(JSONObject().put("type", "text").put("text", "Two"))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:..."))))
        ))
        val s = p.prompt
        assertTrue(s.indexOf("You are terse.") < s.indexOf("One"))
        assertTrue(s.indexOf("One") < s.indexOf("Uno"))
        assertTrue(s.indexOf("Uno") < s.indexOf("Two"))
        assertTrue(s.contains("[image omitted]"))
        assertTrue(s.contains("[User]") && s.contains("[Assistant]") && s.contains("[System]"))
    }

    private val tools = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", "read_file").put("description", "Read a file")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().put("path", JSONObject().put("type", "string"))))))

    @Test fun toolsAddTheProtocolAndToolTurnsAreRendered() {
        val call = JSONObject().put("id", "call_1").put("type", "function")
            .put("function", JSONObject().put("name", "read_file").put("arguments", "{\"path\":\"a.txt\"}"))
        val p = OpenAiMapping.toPrompt(request(
            msg("user", "Read a.txt"),
            JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls", JSONArray().put(call)),
            JSONObject().put("role", "tool").put("tool_call_id", "call_1").put("content", "file body"),
            tools = tools,
        ))
        assertTrue(p.hasTools)
        assertTrue(p.prompt.contains("read_file"))
        assertTrue(p.prompt.contains("<tool_call>"))
        assertTrue(p.prompt.contains("{\"name\":\"read_file\",\"arguments\":{\"path\":\"a.txt\"}}"))
        assertTrue(p.prompt.contains("[Tool result call_1]\nfile body"))
    }

    @Test fun toolCallBlockBecomesToolCalls() {
        val (text, calls) = OpenAiMapping.parseToolCalls(
            "Let me look.\n<tool_call>{\"name\": \"read_file\", \"arguments\": {\"path\": \"a.txt\"}}</tool_call>\n" +
                "<tool_call>\n{\"name\":\"list\",\"arguments\":\"{}\"}\n</tool_call>"
        )
        assertEquals("Let me look.", text)
        assertEquals(listOf("read_file", "list"), calls.map { it.name })
        assertEquals("{\"path\":\"a.txt\"}", calls[0].arguments)
        assertEquals("{}", calls[1].arguments)
        assertTrue(calls.all { it.id.startsWith("call_") })
        assertEquals(2, calls.map { it.id }.toSet().size)
    }

    @Test fun dsmlInvokesBecomeToolCalls() {
        val reply = "Checking.\n<｜DSML｜function_calls>\n" +
            "<｜DSML｜invoke name=\"bash\">\n" +
            "<｜DSML｜parameter name=\"command\" string=\"true\">ls -la /tmp && echo \"ok\"</｜DSML｜parameter>\n" +
            "<｜DSML｜parameter name=\"description\" string=\"true\">List files</｜DSML｜parameter>\n" +
            "</｜DSML｜invoke>\n" +
            "<｜DSML｜invoke name=\"read_file\">\n" +
            "<｜DSML｜parameter name=\"path\" string=\"true\">a.txt</｜DSML｜parameter>\n" +
            "<｜DSML｜parameter name=\"limit\" string=\"false\">20</｜DSML｜parameter>\n" +
            "<｜DSML｜parameter name=\"opts\" string=\"false\">{\"raw\": true}</｜DSML｜parameter>\n" +
            "</｜DSML｜invoke>\n" +
            "</｜DSML｜function_calls>"
        val (text, calls) = OpenAiMapping.parseToolCalls(reply)
        assertEquals("Checking.", text)
        assertEquals(listOf("bash", "read_file"), calls.map { it.name })
        val bash = JSONObject(calls[0].arguments)
        assertEquals("ls -la /tmp && echo \"ok\"", bash.getString("command"))
        assertEquals("List files", bash.getString("description"))
        val read = JSONObject(calls[1].arguments)
        assertEquals("a.txt", read.getString("path"))
        assertEquals(20, read.getInt("limit"))
        assertEquals(true, read.getJSONObject("opts").getBoolean("raw"))
        assertEquals(2, calls.map { it.id }.toSet().size)
    }

    @Test fun dsmlToleratesAsciiBarsSpacesAndTheShortWrapper() {
        // As seen on a phone: spaces inside the tags, `calls` instead of `function_calls`, ASCII and fullwidth bars.
        val reply = "< ｜DSML｜ invoke name=\"bash\">< ｜DSML｜ parameter name=\"command\" string=\"true\">echo hi</ ｜DSML｜ parameter>" +
            "</ ｜DSML｜ invoke>< |DSML| invoke name=\"glob\"><|DSML|parameter name=\"pattern\">*.svg</|DSML|parameter></|DSML|invoke>" +
            "</｜DSML｜calls>"
        val (text, calls) = OpenAiMapping.parseToolCalls(reply)
        assertEquals("", text)
        assertEquals(listOf("bash", "glob"), calls.map { it.name })
        assertEquals("echo hi", JSONObject(calls[0].arguments).getString("command"))
        assertEquals("*.svg", JSONObject(calls[1].arguments).getString("pattern"))
    }

    @Test fun dsmlStringFalseThatIsNotJsonStaysAString() {
        val (_, calls) = OpenAiMapping.parseToolCalls(
            "<｜DSML｜invoke name=\"t\"><｜DSML｜parameter name=\"n\" string=\"false\">12 apples</｜DSML｜parameter></｜DSML｜invoke>"
        )
        assertEquals("12 apples", JSONObject(calls.single().arguments).getString("n"))
    }

    @Test fun dsmlWithoutANameOrInvokeStaysText() {
        val noName = "<｜DSML｜invoke name=\" \"><｜DSML｜parameter name=\"a\">b</｜DSML｜parameter></｜DSML｜invoke>"
        val (text, calls) = OpenAiMapping.parseToolCalls(noName)
        assertTrue(calls.isEmpty())
        assertEquals(noName, text)
        val talk = "The marker <｜DSML｜function_calls> is how DeepSeek writes tools."
        assertEquals(talk to emptyList<OpenAiMapping.ToolCall>(), OpenAiMapping.parseToolCalls(talk))
    }

    @Test fun toolCallBlocksAndDsmlInOneReply() {
        val (text, calls) = OpenAiMapping.parseToolCalls(
            "<tool_call>{\"name\":\"a\",\"arguments\":{}}</tool_call>" +
                "<｜DSML｜function_calls><｜DSML｜invoke name=\"b\"></｜DSML｜invoke></｜DSML｜function_calls>"
        )
        assertEquals("", text)
        assertEquals(listOf("a", "b"), calls.map { it.name })
        assertEquals("{}", calls[1].arguments)
    }

    @Test fun malformedBlocksStayText() {
        val (text, calls) = OpenAiMapping.parseToolCalls("<tool_call>{not json}</tool_call> ok")
        assertTrue(calls.isEmpty())
        assertTrue(text.contains("{not json}"))
    }

    @Test fun completionJsonShape() {
        val calls = listOf(OpenAiMapping.ToolCall("call_a", "read_file", "{\"path\":\"x\"}"))
        val j = OpenAiMapping.completionJson("chatcmpl-1", "deepseek-web-chat", 100, "", "thought", calls, 10, 5)
        assertEquals("chat.completion", j.getString("object"))
        val choice = j.getJSONArray("choices").getJSONObject(0)
        assertEquals("tool_calls", choice.getString("finish_reason"))
        val message = choice.getJSONObject("message")
        assertEquals("assistant", message.getString("role"))
        assertEquals("thought", message.getString("reasoning_content"))
        assertEquals("read_file", message.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("name"))
        assertEquals(15, j.getJSONObject("usage").getInt("total_tokens"))
        val plain = OpenAiMapping.completionJson("chatcmpl-2", "m", 1, "hi", "", emptyList(), 1, 1)
        assertEquals("stop", plain.getJSONArray("choices").getJSONObject(0).getString("finish_reason"))
        assertFalse(plain.getJSONArray("choices").getJSONObject(0).getJSONObject("message").has("tool_calls"))
    }

    @Test fun chunkJsonShape() {
        val c = OpenAiMapping.chunkJson("chatcmpl-1", "m", 1, JSONObject().put("content", "Hel"), null)
        assertEquals("chat.completion.chunk", c.getString("object"))
        val choice = c.getJSONArray("choices").getJSONObject(0)
        assertEquals("Hel", choice.getJSONObject("delta").getString("content"))
        assertTrue(choice.isNull("finish_reason"))
    }

    @Test fun modelsList() {
        val ids = OpenAiMapping.modelsJson().getJSONArray("data").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }
        assertEquals(listOf("deepseek-web-chat", "deepseek-web-reasoner"), ids)
    }

    @Test fun overlongHistoryDropsTheOldestTurnsButKeepsSystemAndLast() {
        val big = "x".repeat(60_000)
        val p = OpenAiMapping.toPrompt(request(msg("system", "SYS"), *Array(6) { msg("user", "$it$big") }, msg("user", "LAST")))
        assertTrue(p.prompt.length <= OpenAiMapping.MAX_PROMPT_CHARS + 200)
        assertTrue(p.prompt.contains("SYS") && p.prompt.contains("LAST"))
        assertFalse(p.prompt.contains("0$big"))
    }

    @Test fun emptyMessagesAreRejected() {
        assertNull(runCatching { OpenAiMapping.toPrompt(JSONObject().put("messages", JSONArray())) }.getOrNull())
    }

    @Test fun oversizedSingleUserMessageIsRejectedWithoutTruncation() {
        assertThrows(IllegalArgumentException::class.java) {
            OpenAiMapping.toPrompt(request(msg("user", "x".repeat(120_001))))
        }
    }

    @Test fun oversizedSystemAndToolDefinitionsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            OpenAiMapping.toPrompt(request(msg("system", "x".repeat(120_001)), msg("user", "LAST")))
        }
        val hugeTools = JSONArray(tools.toString())
        hugeTools.getJSONObject(0).getJSONObject("function").getJSONObject("parameters").put("description", "x".repeat(120_001))
        assertThrows(IllegalArgumentException::class.java) {
            OpenAiMapping.toPrompt(request(msg("user", "LAST"), tools = hugeTools))
        }
    }

    @Test fun lastTurnIsNeverSilentlyCutToFitSystemPrompt() {
        assertThrows(IllegalArgumentException::class.java) {
            OpenAiMapping.toPrompt(request(msg("system", "x".repeat(60_000)), msg("user", "y".repeat(60_000))))
        }
    }
}
