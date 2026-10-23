package com.dsh.android.webbridge

import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local OpenAI-compatible endpoint for the DeepSeek web account: dsh's `llm-pi-ai` route `deepseek-web` talks to it.
 *
 * Bound to 127.0.0.1 on a random port; every request needs `Authorization: Bearer <per-install secret>` (compared in
 * constant time, checked before the body is read), so other apps on the phone cannot use the account. Plain HTTP/1.1,
 * one request per connection, at most [MAX_CONNECTIONS] at once; nothing a client sends can throw out of a handler.
 * Routes: `GET /v1/models`, `POST /v1/chat/completions` (`stream` true or false).
 */
class BridgeServer(
    private val secret: String,
    private val chat: () -> WebChat,
    private val log: (String) -> Unit = {},
) {
    private var socket: ServerSocket? = null
    private var pool: ExecutorService? = null
    private val clients = java.util.concurrent.ConcurrentHashMap<Socket, AtomicBoolean>()

    val port: Int get() = socket?.localPort ?: 0
    val boundAddress: InetAddress? get() = socket?.inetAddress
    val running: Boolean get() = socket?.isClosed == false

    @Synchronized
    fun start(port: Int = 0): Int {
        if (running) return this.port
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16)
        val executor = Executors.newCachedThreadPool { r -> Thread(r, "web-bridge").apply { isDaemon = true } }
        socket = server
        pool = executor
        executor.execute {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (e: IOException) { break }
                synchronized(this@BridgeServer) {
                    if (server.isClosed) client.close()
                    else {
                        val cancelled = AtomicBoolean(false)
                        clients[client] = cancelled
                        executor.execute {
                            try { handle(client, cancelled) }
                            catch (_: IOException) { /* stop may close the socket before the handler starts */ }
                            finally { clients.remove(client); runCatching { client.close() } }
                        }
                    }
                }
            }
        }
        log("web bridge listening on 127.0.0.1:${server.localPort}")
        return server.localPort
    }

    @Synchronized
    fun stop() {
        runCatching { socket?.close() }
        clients.forEach { (client, cancelled) -> cancelled.set(true); runCatching { client.close() } }
        clients.clear()
        pool?.shutdownNow()
        socket = null
        pool = null
    }

    private class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)
    private class HttpError(val status: Int, message: String) : Exception(message)

    private fun handle(client: Socket, cancelled: AtomicBoolean) {
        client.use { s ->
            s.soTimeout = 120_000
            val out = BufferedOutputStream(s.getOutputStream())
            if (!slots.tryAcquire()) {
                runCatching { error(out, 503, "too many connections") }
                return
            }
            try {
                val request = read(s.getInputStream())
                when {
                    request.method == "GET" && request.path == "/v1/models" -> json(out, 200, OpenAiMapping.modelsJson())
                    request.method == "POST" && request.path == "/v1/chat/completions" -> completion(out, request, s, cancelled)
                    else -> throw HttpError(404, "no route ${request.method} ${request.path}")
                }
            } catch (e: HttpError) {
                runCatching { error(out, e.status, e.message ?: "error") }
            } catch (e: IOException) {
                // client went away
            } catch (t: Throwable) {
                // Never let a request take the app process down (an uncaught throwable in a pool thread would).
                log("web bridge: ${t.javaClass.simpleName}: ${t.message}")
                runCatching { error(out, 500, "internal error") }
            } finally {
                slots.release()
            }
        }
    }

    /** At most [MAX_CONNECTIONS] requests at once; more get 503 instead of a thread each. */
    private val slots = java.util.concurrent.Semaphore(MAX_CONNECTIONS)

    private fun completion(out: OutputStream, request: Request, client: Socket, cancelled: AtomicBoolean) {
        val body = try { JSONObject(String(request.body, Charsets.UTF_8)) } catch (e: Exception) { throw HttpError(400, "body is not JSON") }
        val prompt = try { OpenAiMapping.toPrompt(body) } catch (e: IllegalArgumentException) { throw HttpError(400, e.message ?: "bad request") }
        val model = body.optString("model").ifEmpty { OpenAiMapping.MODEL_CHAT }
        val id = "chatcmpl-" + System.nanoTime().toString(36)
        val created = System.currentTimeMillis() / 1000
        val stream = body.optBoolean("stream", false)
        val promptTokens = OpenAiMapping.estimateTokens(prompt.prompt)
        val text = StringBuilder()
        val reasoning = StringBuilder()
        var headerSent = false
        var finished = false
        // The request body has been consumed. This connection accepts no next request, so EOF means cancellation,
        // including while tool output is buffered and no downstream writes can detect a disconnect yet.
        client.soTimeout = 0
        Thread({
            try { client.getInputStream().read() } catch (_: IOException) { }
            finally { cancelled.set(true) }
        }, "web-bridge-disconnect").apply { isDaemon = true; start() }

        fun sendChunk(delta: JSONObject, finish: String? = null, usage: JSONObject? = null) {
            if (!headerSent) {
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n").toByteArray())
                headerSent = true
            }
            out.write(("data: " + OpenAiMapping.chunkJson(id, model, created, delta, finish, usage) + "\n\n").toByteArray(Charsets.UTF_8))
            out.flush()
        }

        fun fail(status: Int, message: String) {
            if (cancelled.get()) return
            if (!headerSent) throw HttpError(status, message)
            out.write(("data: " + errorJson(status, message) + "\n\n").toByteArray(Charsets.UTF_8))
            out.flush()
        }

        try {
            chat().complete(prompt.prompt, prompt.thinking, prompt.search, { cancelled.get() }) { delta ->
                when (delta) {
                    is WebDelta.Thinking -> {
                        reasoning.append(delta.text)
                        if (stream) try { sendChunk(JSONObject().put("reasoning_content", delta.text)) } catch (e: IOException) { cancelled.set(true) }
                    }
                    is WebDelta.Text -> {
                        text.append(delta.text)
                        // With tools the reply must be complete before <tool_call> blocks can be split out.
                        if (stream && !prompt.hasTools) try { sendChunk(JSONObject().put("content", delta.text)) } catch (e: IOException) { cancelled.set(true) }
                    }
                    WebDelta.Finished -> finished = true
                }
            }
            if (!finished && !cancelled.get()) throw WebServiceError(-1, "DeepSeek reply ended before completion")
        } catch (e: WebServiceError) {
            val (status, message) = when {
                DeepSeekWebClient.isExpired(e) -> 401 to "DeepSeek web session expired: sign in again (Settings > Account)."
                e.code == 429 -> 429 to "DeepSeek web is rate limiting this account; wait a moment."
                e.code in 40300..40399 -> 502 to "DeepSeek rejected the proof of work; update the app."
                else -> 502 to "DeepSeek web error ${e.code}."
            }
            fail(status, message)
            return
        } catch (e: IOException) {
            fail(502, "Could not reach chat.deepseek.com.")
            return
        } catch (e: Exception) {
            fail(500, "Internal bridge error.")
            return
        }
        if (cancelled.get()) return

        val (content, calls) = if (prompt.hasTools) OpenAiMapping.parseToolCalls(text.toString()) else text.toString() to emptyList()
        val completionTokens = OpenAiMapping.estimateTokens(reasoning.toString() + text)
        if (!stream) {
            json(out, 200, OpenAiMapping.completionJson(id, model, created, content, reasoning.toString(), calls, promptTokens, completionTokens))
            return
        }
        if (prompt.hasTools) {
            if (content.isNotEmpty()) sendChunk(JSONObject().put("content", content))
            if (calls.isNotEmpty()) sendChunk(JSONObject().put("tool_calls", OpenAiMapping.toolCallsJson(calls, withIndex = true)))
        }
        sendChunk(JSONObject(), if (calls.isEmpty()) "stop" else "tool_calls", OpenAiMapping.usage(promptTokens, completionTokens))
        out.write("data: [DONE]\n\n".toByteArray())
        out.flush()
    }

    private fun authorized(header: String?): Boolean {
        val given = header?.trim()?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim() ?: return false
        return MessageDigest.isEqual(given.toByteArray(Charsets.UTF_8), secret.toByteArray(Charsets.UTF_8))
    }

    private fun read(input: InputStream): Request {
        val head = StringBuilder()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("closed")
            head.append(b.toChar())
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (head.length > MAX_HEAD) throw HttpError(431, "headers too large")
        }
        val lines = head.toString().split("\r\n")
        val parts = lines.first().split(" ")
        if (parts.size < 2) throw HttpError(400, "bad request line")
        val headers = lines.drop(1).filter { it.contains(':') }
            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        // Auth before anything is read or allocated for the body.
        if (!authorized(headers["authorization"])) throw HttpError(401, "missing or wrong bridge key")
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) throw HttpError(411, "send a Content-Length")
        val rawLength = headers["content-length"]
        val length = if (rawLength == null) 0L else rawLength.toLongOrNull()?.takeIf { it >= 0 } ?: throw HttpError(400, "bad Content-Length")
        if (length > MAX_BODY) throw HttpError(413, "request body over ${MAX_BODY / 1024 / 1024} MB")
        val body = ByteArray(length.toInt())
        var read = 0
        while (read < body.size) {
            val n = input.read(body, read, body.size - read)
            if (n < 0) throw IOException("closed")
            read += n
        }
        return Request(parts[0].uppercase(), parts[1].substringBefore('?'), headers, body)
    }

    private fun json(out: OutputStream, status: Int, body: JSONObject) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        out.write(("HTTP/1.1 $status ${reason(status)}\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun error(out: OutputStream, status: Int, message: String) =
        json(out, status, errorJson(status, message))

    private fun errorJson(status: Int, message: String) =
        JSONObject().put("error", JSONObject().put("message", message).put("type", "deepseek_web_bridge").put("code", status))

    private fun reason(status: Int) = when (status) {
        200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 411 -> "Length Required"; 500 -> "Internal Server Error"; 503 -> "Service Unavailable"
        413 -> "Payload Too Large"; 429 -> "Too Many Requests"; 431 -> "Request Header Fields Too Large"; else -> "Bad Gateway"
    }

    companion object {
        const val MAX_BODY = 8 * 1024 * 1024
        const val MAX_CONNECTIONS = 8
        private const val MAX_HEAD = 64 * 1024
    }
}
