package com.dsh.android.webbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class WebSseParserTest {
    /** Shape of a real DeepThink reply (synthetic text): ready, the response with a THINK fragment, appends, a new RESPONSE fragment, batch, finish. */
    private val stream = listOf(
        "ready" to """{"request_message_id":1,"response_message_id":2,"model_type":"default"}""",
        "update_session" to """{"updated_at":1792334765.83}""",
        null to """{"v":{"response":{"message_id":2,"parent_id":1,"thinking_enabled":true,"status":"WIP","fragments":[{"id":2,"type":"THINK","content":"We","elapsed_secs":null,"references":[]}]}}}""",
        null to """{"p":"response/fragments/-1/content","o":"APPEND","v":" need"}""",
        null to """{"v":" to"}""",
        null to """{"p":"response/fragments/-1/elapsed_secs","o":"SET","v":0.8}""",
        null to """{"p":"response/fragments","o":"APPEND","v":[{"id":3,"type":"RESPONSE","content":"Hel","references":[],"stage_id":1}]}""",
        null to """{"p":"response/fragments/-1/content","o":"APPEND","v":"lo"}""",
        null to """{"v":"!"}""",
        null to """{"p":"response","o":"BATCH","v":[{"p":"accumulated_token_usage","v":45},{"p":"quasi_status","v":"FINISHED"}]}""",
        null to """{"p":"response/status","o":"SET","v":"FINISHED"}""",
        "title" to """{"content":"Greeting"}""",
        "close" to """{"click_behavior":"none"}""",
    )

    private fun run(events: List<Pair<String?, String>>): List<WebDelta> {
        val parser = WebSseParser()
        return events.flatMap { (e, d) -> parser.feed(e, d) }
    }

    @Test fun thinkingAndTextInOrder() {
        val deltas = run(stream)
        assertEquals("We need to", deltas.filterIsInstance<WebDelta.Thinking>().joinToString("") { it.text })
        assertEquals("Hello!", deltas.filterIsInstance<WebDelta.Text>().joinToString("") { it.text })
        assertEquals(WebDelta.Finished, deltas.last())
        assertEquals(1, deltas.count { it == WebDelta.Finished })
    }

    @Test fun batchWithAStatusFinishes() {
        val deltas = run(listOf(
            null to """{"v":{"response":{"fragments":[{"type":"RESPONSE","content":"ok"}]}}}""",
            null to """{"p":"response","o":"BATCH","v":[{"p":"status","v":"FINISHED"}]}""",
        ))
        assertEquals(listOf(WebDelta.Text("ok"), WebDelta.Finished), deltas)
    }

    @Test fun searchFragmentsAreNotText() {
        val deltas = run(listOf(
            null to """{"v":{"response":{"fragments":[{"type":"SEARCH","content":"","results":[]}]}}}""",
            null to """{"p":"response/fragments/-1/results","o":"SET","v":[{"url":"https://x"}]}""",
            null to """{"p":"response/fragments","o":"APPEND","v":[{"type":"RESPONSE","content":"answer"}]}""",
        ))
        assertEquals(listOf(WebDelta.Text("answer")), deltas)
    }

    @Test fun serviceErrorsCarryTheirCode() {
        val e = assertThrows(WebServiceError::class.java) { WebSseParser().feed(null, """{"code":40003,"msg":"token expired","data":null}""") }
        assertEquals(40003, e.code)
    }

    @Test fun malformedEventsCannotBecomeSuccessfulReplies() {
        assertThrows(WebServiceError::class.java) { WebSseParser().feed(null, "not json") }
        assertThrows(WebServiceError::class.java) { WebSseParser().feed("error", """{"message":"interrupted"}""") }
    }

    @Test fun unsuccessfulTerminalStatusesFail() {
        for (status in listOf("INCOMPLETE", "INTERRUPTED", "CONTENT_FILTER", "FAILED", "ERROR", "CANCELLED", "CANCELED", "TIMEOUT")) {
            assertThrows(status, WebServiceError::class.java) {
                WebSseParser().feed(null, """{"p":"response/status","o":"SET","v":"$status"}""")
            }
            assertThrows(status, WebServiceError::class.java) {
                WebSseParser().feed(null, """{"v":{"response":{"status":"$status","fragments":[]}}}""")
            }
        }
    }

    @Test fun emptyFinishEventIsTerminal() {
        assertEquals(listOf(WebDelta.Finished), WebSseParser().feed("finish", ""))
    }

    @Test fun doneLinesFinishOnce() {
        val parser = WebSseParser()
        assertEquals(listOf(WebDelta.Finished), parser.feed(null, "[DONE]"))
        assertTrue(parser.finished)
    }
}
