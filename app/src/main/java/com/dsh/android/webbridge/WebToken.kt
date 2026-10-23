package com.dsh.android.webbridge

import org.json.JSONObject

/**
 * The DeepSeek web session ("userToken" in chat.deepseek.com's localStorage) and the browser hand-off used when Google
 * refuses to sign in inside an embedded WebView. Pure functions (JVM-tested); after muhib-karim/deepseek-android's
 * `Session.kt` / `BrowserSignIn.kt` (MIT).
 */
object WebToken {
    const val SIGN_IN_URL = "${DeepSeekWebClient.ORIGIN}/sign_in"
    const val LINK_SCHEME = "mkdsh"
    const val LINK_HOST = "session"
    const val EXTRA_TOKEN = "token"
    /** A token handed back by the browser link is only taken this long after the app opened the browser. */
    const val WINDOW_MILLIS = 30L * 60 * 1000

    /** Reads localStorage.userToken on chat.deepseek.com only; returns null elsewhere. */
    const val CAPTURE_JS = "(function(){if(location.origin!=='https://chat.deepseek.com')return null;" +
        "return localStorage.getItem('userToken');})()"

    private val TOKEN = Regex("[A-Za-z0-9._~+/-]+=*")
    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    /** A raw token or the stored JSON `{"value": "<token>", ...}` → the bearer token, or null. */
    fun parse(stored: String?): String? {
        var raw = stored?.trim() ?: return null
        if (raw.isEmpty() || raw == "null") return null
        if (raw.startsWith("{")) raw = runCatching { JSONObject(raw).optString("value") }.getOrNull()?.trim() ?: return null
        if (raw.length >= 2 && raw.first() == '"' && raw.last() == '"') raw = raw.substring(1, raw.length - 1)
        return raw.takeIf { it.length in 16..4096 && TOKEN.matches(it) }
    }

    /** `evaluateJavascript` returns a JSON-encoded string ("\"...\"" or "null"). */
    fun fromJavascriptResult(result: String?): String? {
        if (result == null || result == "null") return null
        val decoded = runCatching { JSONObject("{\"v\":$result}").optString("v") }.getOrNull() ?: return null
        return parse(decoded)
    }

    fun captureCandidate(result: String?, previousToken: String?): String? =
        fromJavascriptResult(result)?.takeUnless { it == previousToken }

    /** What a person pastes: the token, the page's stored JSON, or either with "Bearer " or quotes around it. */
    fun fromPaste(text: String?): String? {
        var raw = text?.trim() ?: return null
        if (raw.startsWith("Bearer ", ignoreCase = true)) raw = raw.substring(7).trim()
        if (raw.length >= 2 && raw.first() == '\'' && raw.last() == '\'') raw = raw.substring(1, raw.length - 1).trim()
        return parse(raw)
    }

    fun fromLink(extra: String?, startedAtMillis: Long?, nowMillis: Long): String? =
        if (startedAtMillis != null && nowMillis >= startedAtMillis && nowMillis - startedAtMillis <= WINDOW_MILLIS) parse(extra) else null

    /** A session from an `mkdsh://session` link; [fromApp] when it follows a browser sign-in this app started. */
    data class Link(val token: String, val fromApp: Boolean)

    /**
     * Any well-formed session from the link, so a bookmark run without starting from the app still works. The caller
     * always shows the account and asks before using it; [Link.fromApp] = false makes that question say the link did
     * not follow a sign-in started in DSH.
     */
    fun classifyLink(extra: String?, startedAtMillis: Long?, nowMillis: Long): Link? {
        val token = parse(extra) ?: return null
        return Link(token, fromLink(extra, startedAtMillis, nowMillis) != null)
    }

    /**
     * Bookmark for the browser route. On chat.deepseek.com it adds two buttons: one opens this app (an explicit intent
     * to [packageName] only) with the session, one copies it for the app's paste field. It sends nothing anywhere.
     */
    fun bookmarklet(packageName: String): String {
        require(PACKAGE.matches(packageName)) { "unsupported package name" }
        val js = "(function(){" +
            "if(location.hostname!=='chat.deepseek.com'){alert('Open chat.deepseek.com and sign in first.');return;}" +
            "var t=localStorage.getItem('userToken');" +
            "if(!t){alert('Sign in to DeepSeek on this page first, then open this bookmark again.');return;}" +
            "var v=t;try{var j=JSON.parse(t);if(j&&typeof j.value==='string')v=j.value;}catch(e){}" +
            "var old=document.getElementById('mkdsh-handoff');if(old)old.remove();" +
            "var box=document.createElement('div');box.id='mkdsh-handoff';" +
            "box.style.cssText='position:fixed;left:12px;right:12px;bottom:20px;z-index:2147483647;display:flex;flex-direction:column;gap:8px';" +
            "function btn(label){var b=document.createElement('a');b.textContent=label;" +
            "b.style.cssText='display:block;padding:14px;border-radius:12px;background:#4d6bfe;color:#fff;font:600 16px sans-serif;text-align:center;text-decoration:none';" +
            "box.appendChild(b);return b;}" +
            "var open=btn('Open in DSH');" +
            "open.href='intent://$LINK_HOST#Intent;scheme=$LINK_SCHEME;package=$packageName;S.$EXTRA_TOKEN='+encodeURIComponent(v)+';end';" +
            "var copy=btn('Copy session instead');copy.href='#';" +
            "copy.onclick=function(e){e.preventDefault();navigator.clipboard.writeText(v).then(function(){alert('Copied. In the app, tap Paste session.');},function(){prompt('Copy this, then paste it in the app:',v);});};" +
            "document.body.appendChild(box);})();"
        return "javascript:" + js.replace("%", "%25").replace(" ", "%20")
    }

    /** "Signed in as …" label from `users/current` (email or phone), never the token. */
    fun label(user: JSONObject): String =
        user.optString("email").ifBlank { user.optString("mobile_number") }.ifBlank { "DeepSeek account" }
}
