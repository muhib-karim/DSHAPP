package com.dsh.android.webbridge

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** One chat turn against a DeepSeek web account; what the local bridge needs (a fake in tests). */
interface WebChat {
    /**
     * Sends [prompt] in a new web chat and streams the reply into [onDelta] until it finishes.
     * Throws [WebServiceError] when DeepSeek refuses (expired session, rate limit, PoW) and IOException on network errors.
     */
    fun complete(prompt: String, thinking: Boolean, search: Boolean, cancelled: () -> Boolean, onDelta: (WebDelta) -> Unit)
}

/**
 * Client for chat.deepseek.com's web API (`/api/v0`), authenticated by the user token the web app keeps in
 * `localStorage.userToken`. Each completion needs a solved proof of work (`X-DS-PoW-Response`). Endpoints, headers and
 * body follow muhib-karim/deepseek-android (`DeepSeekWebClient.kt`, MIT). The token is never logged.
 */
class DeepSeekWebClient(
    private val token: () -> String?,
    private val cookies: () -> String = { "" },
    private val origin: String = ORIGIN,
) : WebChat {

    /** `GET users/current` → `biz_data` (email, mobile_number, need_birthday); throws when the token is not accepted. */
    fun currentUser(cancelled: () -> Boolean = { false }): JSONObject = bizData(request("GET", "users/current", null, cancelled))

    fun createSession(cancelled: () -> Boolean = { false }): String {
        val data = bizData(request("POST", "chat_session/create", JSONObject(), cancelled))
        return data.optString("id").ifEmpty { data.optJSONObject("chat_session")?.optString("id").orEmpty() }
            .ifEmpty { throw WebServiceError(-1, "DeepSeek did not return a chat id") }
    }

    /** Creates and solves a challenge for [targetPath]; retries once if it expired while solving. */
    fun powHeader(targetPath: String = PowChallenge.COMPLETION_PATH, cancelled: () -> Boolean = { false }): String {
        repeat(2) {
            val data = bizData(request("POST", "chat/create_pow_challenge", JSONObject().put("target_path", targetPath), cancelled))
            val challenge = data.optJSONObject("challenge")?.let(PowChallenge::fromJson)
                ?: throw WebServiceError(-2, "DeepSeek changed its proof of work; update the app")
            checkCancelled(cancelled)
            if (System.currentTimeMillis() >= challenge.expireAt) return@repeat
            val answer = DeepSeekHashV1.answer(challenge) { checkCancelled(cancelled) }
                ?: throw WebServiceError(-2, "DeepSeek changed its proof of work; update the app")
            if (System.currentTimeMillis() < challenge.expireAt) return challenge.header(answer, targetPath)
        }
        throw WebServiceError(40301, "proof of work expired twice")
    }

    override fun complete(prompt: String, thinking: Boolean, search: Boolean, cancelled: () -> Boolean, onDelta: (WebDelta) -> Unit) {
        checkCancelled(cancelled)
        val session = createSession(cancelled)
        val pow = powHeader(cancelled = cancelled)
        val body = JSONObject()
            .put("chat_session_id", session).put("parent_message_id", JSONObject.NULL).put("model_type", "default")
            .put("prompt", prompt).put("ref_file_ids", JSONArray()).put("thinking_enabled", thinking)
            .put("search_enabled", search).put("action", JSONObject.NULL).put("preempt", false)
        checkCancelled(cancelled)
        val conn = open("POST", "chat/completion")
        conn.setRequestProperty("X-DS-PoW-Response", pow)
        conn.setRequestProperty("Accept", "text/event-stream")
        val cancellation = ConnectionCancellation(conn, cancelled)
        try {
            checkCancelled(cancelled)
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            if (status !in 200..299) throw errorFor(status, readAll(conn))
            val contentType = conn.contentType.orEmpty()
            if (!contentType.contains("event-stream")) {
                // An error envelope instead of a stream.
                val text = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                runCatching { JSONObject(text) }.getOrNull()?.let { bizData(it) }
                throw WebServiceError(-1, "DeepSeek answered without a stream")
            }
            val parser = WebSseParser()
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                var event: String? = null
                val data = StringBuilder()
                while (true) {
                    checkCancelled(cancelled)
                    val line = reader.readLine() ?: break
                    when {
                        line.isEmpty() -> {
                            if (data.isNotEmpty() || event != null) parser.feed(event, data.toString()).forEach(onDelta)
                            event = null
                            data.setLength(0)
                            if (parser.finished) return
                        }
                        line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                        line.startsWith("data:") -> {
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(line.removePrefix("data:").removePrefix(" "))
                            if (data.length > MAX_EVENT) throw IOException("event too large")
                        }
                    }
                }
                if (data.isNotEmpty() || event != null) parser.feed(event, data.toString()).forEach(onDelta)
                checkCancelled(cancelled)
                if (!parser.finished) throw WebServiceError(-1, "DeepSeek stream ended before completion")
            }
        } finally {
            cancellation.close()
        }
    }

    private fun request(method: String, path: String, body: JSONObject?, cancelled: () -> Boolean = { false }): JSONObject {
        checkCancelled(cancelled)
        val conn = open(method, path)
        val cancellation = ConnectionCancellation(conn, cancelled)
        try {
            checkCancelled(cancelled)
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val text = readAll(conn)
            if (status !in 200..299) throw errorFor(status, text)
            return runCatching { JSONObject(text) }.getOrElse { throw WebServiceError(-1, "DeepSeek answered something unexpected") }
        } finally {
            cancellation.close()
        }
    }

    /** Closing a connection releases blocked connect/read calls; interruption alone does not do that on Android. */
    private class ConnectionCancellation(private val conn: HttpURLConnection, cancelled: () -> Boolean) : java.io.Closeable {
        private val done = java.util.concurrent.atomic.AtomicBoolean()
        private val owner = Thread.currentThread()
        private val watcher = Thread({
            while (!done.get()) {
                if (cancelled() || owner.isInterrupted) conn.disconnect()
                try { Thread.sleep(50) } catch (_: InterruptedException) { break }
            }
        }, "web-http-cancel").apply { isDaemon = true; start() }

        override fun close() {
            done.set(true)
            watcher.interrupt()
            conn.disconnect()
        }
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw IOException("cancelled")
    }

    private fun open(method: String, path: String): HttpURLConnection {
        val bearer = token() ?: throw WebServiceError(40003, "not signed in to a DeepSeek web account")
        val conn = URL("$origin/api/v0/$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 20_000
        conn.readTimeout = 90_000
        conn.instanceFollowRedirects = false
        conn.useCaches = false
        conn.setRequestProperty("Authorization", "Bearer $bearer")
        cookies().takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Cookie", it) }
        conn.setRequestProperty("Origin", ORIGIN)
        conn.setRequestProperty("Referer", "$ORIGIN/")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("x-client-platform", "web")
        conn.setRequestProperty("x-client-version", CLIENT_VERSION)
        conn.setRequestProperty("x-client-locale", "en_US")
        conn.setRequestProperty("x-client-bundle-id", "com.deepseek.chat")
        return conn
    }

    private fun readAll(conn: HttpURLConnection): String {
        val stream = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream) ?: return ""
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }.take(64 * 1024)
    }

    companion object {
        const val ORIGIN = "https://chat.deepseek.com"
        const val CLIENT_VERSION = "2.5.0"
        private const val MAX_EVENT = 2 * 1024 * 1024

        /** The envelope `{code, data: {biz_code, biz_data}}` → biz_data, or the error it carries. */
        fun bizData(envelope: JSONObject): JSONObject {
            val code = envelope.optInt("code", 0)
            if (code != 0) throw WebServiceError(code, envelope.optString("msg").ifEmpty { "DeepSeek web error $code" })
            val data = envelope.optJSONObject("data") ?: throw WebServiceError(-1, "DeepSeek answered without data")
            val biz = data.optInt("biz_code", 0)
            if (biz != 0) throw WebServiceError(biz, data.optString("biz_msg").ifEmpty { "DeepSeek web error $biz" })
            return data.optJSONObject("biz_data") ?: JSONObject()
        }

        /** An HTTP error from the web API as a [WebServiceError] (401/403 mean the session is gone). */
        fun errorFor(status: Int, body: String): WebServiceError {
            val code = runCatching { JSONObject(body).optInt("code", status) }.getOrDefault(status)
            return WebServiceError(if (code == 0) status else code, "DeepSeek web answered HTTP $status")
        }

        /** True when [error] means the user must sign in again. */
        fun isExpired(error: WebServiceError) = error.code == 401 || error.code == 403 || error.code in 40001..40003
    }
}
