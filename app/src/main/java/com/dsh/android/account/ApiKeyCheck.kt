package com.dsh.android.account

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed class KeyCheck {
    object Ok : KeyCheck()
    data class Failed(val message: String) : KeyCheck()
}

/**
 * One cheap call to tell whether a DeepSeek platform key works before it is saved: `GET /models` costs nothing and
 * answers 401 for a wrong key. The key is never logged; messages never contain it.
 */
object ApiKeyCheck {
    const val MODELS_URL = "https://api.deepseek.com/models"

    /** [get] performs the GET and returns the HTTP status (throws IOException when DeepSeek is unreachable). */
    fun check(key: String, get: (url: String, headers: Map<String, String>) -> Int = ::httpStatus): KeyCheck {
        DshCredentials.problem(key)?.let { return KeyCheck.Failed(it) }
        val status = try {
            get(MODELS_URL, mapOf("Authorization" to "Bearer ${key.trim()}", "Accept" to "application/json"))
        } catch (e: IOException) {
            return KeyCheck.Failed("Could not reach api.deepseek.com. Check your connection and try again.")
        }
        return when (status) {
            in 200..299 -> KeyCheck.Ok
            401, 403 -> KeyCheck.Failed("DeepSeek rejected this key. Copy it again from platform.deepseek.com/api_keys.")
            402 -> KeyCheck.Failed("This key works but the account has no balance. Top up at platform.deepseek.com.")
            429 -> KeyCheck.Failed("DeepSeek is rate limiting this key. Wait a moment and try again.")
            else -> KeyCheck.Failed("DeepSeek answered HTTP $status. Try again in a moment.")
        }
    }

    private fun httpStatus(url: String, headers: Map<String, String>): Int {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = false
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            return conn.responseCode
        } finally {
            conn.disconnect()
        }
    }
}
