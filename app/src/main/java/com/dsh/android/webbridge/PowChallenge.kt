package com.dsh.android.webbridge

import org.json.JSONObject
import java.util.Base64

/** A proof-of-work challenge from `POST /api/v0/chat/create_pow_challenge` (`biz_data.challenge`). */
data class PowChallenge(
    val algorithm: String, val challenge: String, val salt: String,
    val difficulty: Long, val expireAt: Long, val signature: String,
) {
    /**
     * The `X-DS-PoW-Response` header: base64 of the JSON DeepSeek's web client sends, in the same key order.
     * DeepSeek checks it against the path the challenge was created for.
     */
    fun header(answer: Long, targetPath: String = COMPLETION_PATH): String {
        require(algorithm == ALGORITHM && answer in 0 until difficulty) { "answer outside the challenge" }
        val json = "{\"algorithm\":${JSONObject.quote(algorithm)},\"challenge\":${JSONObject.quote(challenge)}," +
            "\"salt\":${JSONObject.quote(salt)},\"answer\":$answer,\"signature\":${JSONObject.quote(signature)}," +
            "\"target_path\":${JSONObject.quote(targetPath)}}"
        return Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
    }

    override fun toString() = "PowChallenge([redacted])"

    companion object {
        const val ALGORITHM = "DeepSeekHashV1"
        const val COMPLETION_PATH = "/api/v0/chat/completion"

        /** Parses `biz_data.challenge`; null when a field is missing or the algorithm is not one this app solves. */
        fun fromJson(json: JSONObject): PowChallenge? {
            val algorithm = json.optString("algorithm")
            val challenge = json.optString("challenge")
            val salt = json.optString("salt")
            val signature = json.optString("signature")
            val difficulty = (json.opt("difficulty") as? Number)?.toLong() ?: return null
            val expireAt = (json.opt("expire_at") as? Number)?.toLong() ?: return null
            if (algorithm != ALGORITHM || challenge.length != 64 || salt.isEmpty() || signature.isEmpty()) return null
            if (difficulty !in 1..9_007_199_254_740_991L) return null
            return PowChallenge(algorithm, challenge, salt, difficulty, expireAt, signature)
        }
    }
}
