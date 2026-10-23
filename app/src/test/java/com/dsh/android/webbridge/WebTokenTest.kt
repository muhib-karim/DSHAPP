package com.dsh.android.webbridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebTokenTest {
    private val token = "eyJhbGciOiJIUzI1NiJ9.payload_part-1.sig"

    @Test fun parsesRawAndStoredJson() {
        assertEquals(token, WebToken.parse(token))
        assertEquals(token, WebToken.parse("""{"value":"$token","__version":"0"}"""))
        assertNull(WebToken.parse(null))
        assertNull(WebToken.parse("null"))
        assertNull(WebToken.parse("short"))
        assertNull(WebToken.parse("has spaces in it 0123456789"))
    }

    @Test fun javascriptResultIsJsonEncoded() {
        val stored = """{"value":"$token"}"""
        assertEquals(token, WebToken.fromJavascriptResult(JSONObject.quote(stored)))
        assertNull(WebToken.fromJavascriptResult("null"))
    }

    @Test fun pasteAcceptsBearerAndQuotes() {
        assertEquals(token, WebToken.fromPaste("  Bearer $token  "))
        assertEquals(token, WebToken.fromPaste("'$token'"))
        assertEquals(token, WebToken.fromPaste("\"$token\""))
    }

    @Test fun linkOnlyWithinTheWindow() {
        val start = 1_000_000L
        assertEquals(token, WebToken.fromLink(token, start, start + 60_000))
        assertNull(WebToken.fromLink(token, start, start + WebToken.WINDOW_MILLIS + 1))
        assertNull(WebToken.fromLink(token, null, start))
        assertNull(WebToken.fromLink(token, start, start - 1))
    }

    @Test fun coldOrLateLinksAreKeptButMarkedNotFromTheApp() {
        val token = "a".repeat(40)
        val start = 1_000_000L
        assertEquals(WebToken.Link(token, fromApp = true), WebToken.classifyLink(token, start, start + 60_000))
        // Bookmark run without starting from the app, or after the window: still usable, after the user confirms.
        assertEquals(WebToken.Link(token, fromApp = false), WebToken.classifyLink(token, null, start))
        assertEquals(WebToken.Link(token, fromApp = false), WebToken.classifyLink(token, start, start + WebToken.WINDOW_MILLIS + 1))
        assertEquals(WebToken.Link(token, fromApp = false), WebToken.classifyLink(token, start, start - 1))
        assertNull(WebToken.classifyLink("not a token", start, start))
        assertNull(WebToken.classifyLink(null, null, start))
    }

    @Test fun bookmarkletKeepsItsPanelButtonsAndFollowsTheGeckoPackage() {
        val js = java.net.URLDecoder.decode(WebToken.bookmarklet("com.mkdsh.gecko").removePrefix("javascript:").replace("+", "%2B"), "UTF-8")
        assertTrue(js.contains("btn('Open in DSH')"))
        assertTrue(js.contains("btn('Copy session instead')"))
        assertTrue(js.contains("package=com.mkdsh.gecko;S.token="))
    }

    @Test fun bookmarkletTargetsThisPackageOnly() {
        val b = WebToken.bookmarklet("com.mkdsh.droid")
        assertTrue(b.startsWith("javascript:"))
        assertTrue(b.contains("package=com.mkdsh.droid"))
        assertTrue(b.contains("scheme=mkdsh"))
        assertTrue(!b.contains(" "))
    }

    @Test fun labelNeverUsesTheToken() {
        assertEquals("a@b.c", WebToken.label(JSONObject().put("email", "a@b.c")))
        assertEquals("+86 1", WebToken.label(JSONObject().put("email", "").put("mobile_number", "+86 1")))
        assertEquals("DeepSeek account", WebToken.label(JSONObject()))
    }

    @Test fun switchNeverAutomaticallyAcceptsThePreviousPageSession() {
        val old = JSONObject.quote(token)
        assertNull(WebToken.captureCandidate(old, token))
        val next = "new-account-session-token"
        assertEquals(next, WebToken.captureCandidate(JSONObject.quote(next), token))
        assertEquals(token, WebToken.captureCandidate(old, null))
    }
}
