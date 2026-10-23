package com.dsh.android.webbridge

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BridgeServerTest {
    private val secret = "a".repeat(64)
    private var reply: (String, Boolean, (WebDelta) -> Unit) -> Unit = { _, _, on ->
        on(WebDelta.Thinking("hmm")); on(WebDelta.Text("Hel")); on(WebDelta.Text("lo")); on(WebDelta.Finished)
    }
    private val prompts = mutableListOf<String>()
    private val fake = object : WebChat {
        override fun complete(prompt: String, thinking: Boolean, search: Boolean, cancelled: () -> Boolean, onDelta: (WebDelta) -> Unit) {
            prompts += prompt
            reply(prompt, thinking, onDelta)
        }
    }
    private lateinit var server: BridgeServer

    @Before fun start() { server = BridgeServer(secret, { fake }); server.start() }
    @After fun stop() = server.stop()

    private fun call(path: String, body: String? = null, auth: String? = "Bearer $secret"): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:${server.port}$path").openConnection() as HttpURLConnection
        auth?.let { conn.setRequestProperty("Authorization", it) }
        if (body != null) {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = conn.responseCode
        val text = (if (code < 400) conn.inputStream else conn.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        return code to text
    }

    private val chat = """{"model":"deepseek-web-chat","messages":[{"role":"user","content":"Hi"}]}"""

    @Test fun listensOnLoopbackOnly() {
        assertTrue(server.port > 0)
        assertTrue(InetAddress.getByName("127.0.0.1") == server.boundAddress)
    }

    @Test fun rejectsWrongSecret() {
        assertEquals(401, call("/v1/models", auth = "Bearer nope").first)
        assertEquals(401, call("/v1/models", auth = null).first)
        assertTrue(prompts.isEmpty())
    }

    @Test fun listsModels() {
        val (code, body) = call("/v1/models")
        assertEquals(200, code)
        assertEquals("list", JSONObject(body).getString("object"))
    }

    @Test fun nonStreamCompletion() {
        val (code, body) = call("/v1/chat/completions", chat)
        assertEquals(200, code)
        val message = JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        assertEquals("Hello", message.getString("content"))
        assertEquals("hmm", message.getString("reasoning_content"))
        assertEquals(listOf("Hi"), prompts)
    }

    @Test fun streamsSseChunksAndDone() {
        val (code, body) = call("/v1/chat/completions", chat.replace("}]}", "}],\"stream\":true}"))
        assertEquals(200, code)
        val events = body.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals("data: [DONE]", events.last())
        val chunks = events.dropLast(1).map { JSONObject(it.removePrefix("data: ")) }
        val text = chunks.joinToString("") { it.getJSONArray("choices").getJSONObject(0).getJSONObject("delta").optString("content") }
        assertEquals("Hello", text)
        assertEquals("stop", chunks.last().getJSONArray("choices").getJSONObject(0).getString("finish_reason"))
    }

    @Test fun toolCallsComeBackAsToolCalls() {
        reply = { _, _, on -> on(WebDelta.Text("<tool_call>{\"name\":\"read_file\",\"arguments\":{\"path\":\"a\"}}</tool_call>")); on(WebDelta.Finished) }
        val body = """{"model":"deepseek-web-chat","stream":true,"messages":[{"role":"user","content":"Hi"}],"tools":[{"type":"function","function":{"name":"read_file","parameters":{"type":"object"}}}]}"""
        val (_, text) = call("/v1/chat/completions", body)
        assertTrue(text, text.contains("\"tool_calls\""))
        assertTrue(text.contains("\"finish_reason\":\"tool_calls\""))
        assertTrue(!text.contains("<tool_call>"))
    }

    @Test fun expiredSessionIs401WithAClearMessage() {
        reply = { _, _, _ -> throw WebServiceError(40003, "expired") }
        val (code, body) = call("/v1/chat/completions", chat)
        assertEquals(401, code)
        assertTrue(body.contains("sign in again"))
    }

    @Test fun oversizedBodyIs413() {
        val conn = URL("http://127.0.0.1:${server.port}/v1/chat/completions").openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer $secret")
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(BridgeServer.MAX_BODY + 1)
        val code = runCatching {
            conn.outputStream.use { out -> val buf = ByteArray(65536); var left = BridgeServer.MAX_BODY + 1; while (left > 0) { val n = minOf(left, buf.size); out.write(buf, 0, n); left -= n } }
            conn.responseCode
        }.getOrElse { runCatching { conn.responseCode }.getOrDefault(413) }
        assertEquals(413, code)
    }

    private fun raw(request: String): String = java.net.Socket("127.0.0.1", server.port).use { sock ->
        sock.soTimeout = 5000
        sock.getOutputStream().write(request.toByteArray())
        sock.getInputStream().readBytes().toString(Charsets.UTF_8)
    }

    @Test fun negativeOrJunkContentLengthIs400AndTheServerStaysUp() {
        val auth = "Authorization: Bearer $secret\r\n"
        assertTrue(raw("POST /v1/chat/completions HTTP/1.1\r\n${auth}Content-Length: -1\r\n\r\n").startsWith("HTTP/1.1 400"))
        assertTrue(raw("POST /v1/chat/completions HTTP/1.1\r\n${auth}Content-Length: abc\r\n\r\n").startsWith("HTTP/1.1 400"))
        assertEquals(200, call("/v1/models").first)
    }

    @Test fun authIsCheckedBeforeTheBodyIsRead() {
        // A huge declared body without the key is refused at once, not read.
        assertTrue(raw("POST /v1/chat/completions HTTP/1.1\r\nContent-Length: 999999999\r\n\r\n").startsWith("HTTP/1.1 401"))
    }

    @Test fun aThrowingUpstreamIs500NotACrash() {
        reply = { _, _, _ -> throw IllegalStateException("boom") }
        assertEquals(500, call("/v1/chat/completions", chat).first)
        assertEquals(200, call("/v1/models").first)
    }

    @Test fun unknownPathIs404() {
        assertEquals(404, call("/v1/embeddings", "{}").first)
    }

    @Test fun failureAfterStreamStartsSendsAnErrorInsteadOfSilentEof() {
        reply = { _, _, on -> on(WebDelta.Text("partial")); throw WebServiceError(-1, "interrupted") }
        val (_, body) = call("/v1/chat/completions", JSONObject(chat).put("stream", true).toString())
        val events = body.split("\n\n").filter { it.startsWith("data: ") }.map { JSONObject(it.removePrefix("data: ")) }
        assertTrue(body, events.last().has("error"))
        assertTrue(body, !body.contains("[DONE]"))
    }

    @Test fun partialToolCallsAreNotPublishedWithoutFinished() {
        reply = { _, _, on -> on(WebDelta.Text("<tool_call>{\"name\":\"read_file\",\"arguments\":{}}</tool_call>")) }
        val request = JSONObject(chat).put("stream", true).put("tools", org.json.JSONArray("""[{"type":"function","function":{"name":"read_file"}}]"""))
        val (code, body) = call("/v1/chat/completions", request.toString())
        assertEquals(502, code)
        assertTrue(!body.contains("\"tool_calls\""))
    }

    @Test fun stopCancelsAnActiveRequestAndClosesItsSocket() = cancellation(stopServer = true)

    @Test fun disconnectCancelsBufferedToolGeneration() = cancellation(stopServer = false)

    private fun cancellation(stopServer: Boolean) {
        server.stop()
        val entered = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val upstream = object : WebChat {
            override fun complete(prompt: String, thinking: Boolean, search: Boolean, cancelled: () -> Boolean, onDelta: (WebDelta) -> Unit) {
                entered.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (!cancelled() && System.nanoTime() < deadline) {
                    // Blocking HTTP reads and the PoW solver do not treat executor interruption as cancellation.
                    try { Thread.sleep(10) } catch (_: InterruptedException) { }
                }
                if (cancelled()) stopped.countDown()
            }
        }
        server = BridgeServer(secret, { upstream })
        server.start()
        java.net.Socket("127.0.0.1", server.port).use { socket ->
            socket.soTimeout = 1500
            val body = JSONObject(chat).put("stream", true).put("tools", org.json.JSONArray("""[{"type":"function","function":{"name":"read_file"}}]""")).toString()
            socket.getOutputStream().write(("POST /v1/chat/completions HTTP/1.1\r\nAuthorization: Bearer $secret\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body").toByteArray())
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            if (stopServer) server.stop() else socket.close()
            assertTrue("upstream work was not canceled", stopped.await(1, TimeUnit.SECONDS))
            if (stopServer) assertEquals(-1, socket.getInputStream().read())
        }
    }
}
