package com.dsh.android.webbridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class PowChallengeTest {
    private val json = JSONObject("""{"algorithm":"DeepSeekHashV1","challenge":"${"ab".repeat(32)}","salt":"s1","difficulty":144000,"expire_at":1792534088468,"signature":"sig","target_path":"/api/v0/chat/completion"}""")

    @Test fun parsesTheChallenge() {
        val c = PowChallenge.fromJson(json)!!
        assertEquals(144_000L, c.difficulty)
        assertEquals(1_792_534_088_468L, c.expireAt)
        assertEquals("PowChallenge([redacted])", c.toString())
    }

    @Test fun headerIsBase64OfTheExactJson() {
        val header = PowChallenge.fromJson(json)!!.header(42)
        assertEquals(
            """{"algorithm":"DeepSeekHashV1","challenge":"${"ab".repeat(32)}","salt":"s1","answer":42,"signature":"sig","target_path":"/api/v0/chat/completion"}""",
            String(Base64.getDecoder().decode(header), Charsets.UTF_8),
        )
    }

    @Test fun unknownAlgorithmsAndMissingFieldsAreRefused() {
        assertNull(PowChallenge.fromJson(JSONObject(json.toString()).put("algorithm", "Other")))
        assertNull(PowChallenge.fromJson(JSONObject(json.toString()).apply { remove("difficulty") }))
        assertNull(PowChallenge.fromJson(JSONObject(json.toString()).put("challenge", "short")))
    }

    @Test fun answerOutsideTheDifficultyIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { PowChallenge.fromJson(json)!!.header(144_000) }
    }
}
