package com.dsh.android.webbridge

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors

/** Exercises the real HTTP framing, PoW and stream client against a local service. */
class DeepSeekWebClientTest {
    private lateinit var server: ServerSocket
    private val executor = Executors.newCachedThreadPool()
    private lateinit var client: DeepSeekWebClient
    private var stream = ""
    private var expiredFirst = false
    private var challenges = 0
    private var stallPath: String? = null
    private val stalled = java.util.concurrent.CountDownLatch(1)
    private val releaseStall = java.util.concurrent.CountDownLatch(1)
    private val deltas = mutableListOf<WebDelta>()

    @Before fun start() {
        server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        executor.execute { while (!server.isClosed) {
            val socket = try { server.accept() } catch (_: java.io.IOException) { break }
            executor.execute { socket.use {
            val reader = socket.getInputStream().bufferedReader()
            val path = reader.readLine().split(' ')[1]
            var length = 0
            while (true) {
                val header = reader.readLine() ?: break
                if (header.isEmpty()) break
                if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
            }
            repeat(length) { reader.read() }
            if (stallPath?.let { path.endsWith(it) } == true) {
                stalled.countDown()
                releaseStall.await(10, java.util.concurrent.TimeUnit.SECONDS)
                return@use
            }
            val streaming = path.endsWith("chat/completion")
            val body = when {
                path.endsWith("chat_session/create") -> envelope(JSONObject().put("id", "test-session"))
                path.endsWith("create_pow_challenge") -> {
                    challenges++
                    val expire = if (expiredFirst && challenges == 1) 1L else System.currentTimeMillis() + 60_000
                    val hash = DeepSeekHashV1.hash("salt_${expire}_0".toByteArray()).joinToString("") { "%02x".format(it) }
                    envelope(JSONObject().put("challenge", JSONObject().put("algorithm", "DeepSeekHashV1")
                        .put("challenge", if (expire == 1L) "0".repeat(64) else hash).put("salt", "salt")
                        .put("expire_at", expire).put("difficulty", 1).put("signature", "test-signature")))
                }
                else -> stream
            }.toByteArray()
            val type = if (streaming) "text/event-stream" else "application/json"
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(body)
                flush()
            }
            } }
        } }
        client = DeepSeekWebClient({ "test-token" }, origin = "http://127.0.0.1:${server.localPort}")
    }

    @After fun stop() { releaseStall.countDown(); server.close(); executor.shutdownNow() }

    private fun envelope(data: JSONObject) = JSONObject().put("code", 0)
        .put("data", JSONObject().put("biz_code", 0).put("biz_data", data)).toString()
    private fun complete() = client.complete("Hi", false, false, { false }, deltas::add)
    private fun text(value: String) = "data: " + JSONObject().put("v", JSONObject().put("response", JSONObject()
        .put("status", "WIP").put("fragments", org.json.JSONArray().put(JSONObject().put("type", "RESPONSE").put("content", value))))) + "\n\n"

    @Test fun completeStreamFinishesExactlyOnce() {
        stream = text("Hello") + "data: {\"p\":\"response/status\",\"o\":\"SET\",\"v\":\"FINISHED\"}\n\n"
        complete()
        assertEquals(listOf(WebDelta.Text("Hello"), WebDelta.Finished), deltas)
    }

    @Test fun eofAfterTextIsAnErrorNotSuccessfulCompletion() {
        stream = text("partial")
        assertThrows(WebServiceError::class.java) { complete() }
        assertFalse(deltas.contains(WebDelta.Finished))
    }

    @Test fun eofAfterACompleteToolBlockIsStillAnError() {
        stream = text("<tool_call>{\"name\":\"read_file\",\"arguments\":{}}</tool_call>")
        assertThrows(WebServiceError::class.java) { complete() }
        assertFalse(deltas.contains(WebDelta.Finished))
    }

    @Test fun failureStatusCannotBeHiddenByDone() {
        stream = text("partial") + "data: {\"p\":\"response/status\",\"o\":\"SET\",\"v\":\"INCOMPLETE\"}\n\ndata: [DONE]\n\n"
        assertThrows(WebServiceError::class.java) { complete() }
        assertFalse(deltas.contains(WebDelta.Finished))
    }

    @Test fun malformedEventCannotBeHiddenByDone() {
        stream = text("partial") + "data: not json\n\ndata: [DONE]\n\n"
        assertThrows(WebServiceError::class.java) { complete() }
        assertFalse(deltas.contains(WebDelta.Finished))
    }

    @Test fun cancelClosesAStalledSessionRequest() = cancelStalled("chat_session/create")
    @Test fun cancelClosesAStalledChallengeRequest() = cancelStalled("create_pow_challenge")
    @Test fun cancelClosesAStalledCompletionRequest() = cancelStalled("chat/completion")

    private fun cancelStalled(path: String) {
        stallPath = path
        val cancelled = java.util.concurrent.atomic.AtomicBoolean()
        val work = executor.submit {
            client.complete("Hi", false, false, { cancelled.get() }, deltas::add)
        }
        assertTrue(stalled.await(3, java.util.concurrent.TimeUnit.SECONDS))
        cancelled.set(true)
        val failure = assertThrows(java.util.concurrent.ExecutionException::class.java) {
            work.get(2, java.util.concurrent.TimeUnit.SECONDS)
        }
        assertTrue(failure.cause is java.io.IOException)
        assertFalse(deltas.contains(WebDelta.Finished))
    }

    @Test fun expiredChallengeIsRetriedBeforeSolving() {
        expiredFirst = true
        stream = "data: [DONE]\n\n"
        complete()
        assertEquals(2, challenges)
        assertEquals(listOf(WebDelta.Finished), deltas)
    }
}
