package com.dsh.android.webbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/** Vectors from muhib-karim/deepseek-android (an independent C implementation whose answers the live service accepted). */
class DeepSeekHashV1Test {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun hash(text: String) = hex(DeepSeekHashV1.hash(text.toByteArray(Charsets.UTF_8)))
    private fun challenge(hash: String, salt: String, expireAt: Long, difficulty: Long) =
        PowChallenge("DeepSeekHashV1", hash, salt, difficulty, expireAt, "signature")

    @Test fun digests() {
        assertEquals("e594808bc5b7151ac160c6d39a02e0a8e261ed588578403099e3561dc40c26b3", hash(""))
        assertEquals("f841106c601ce9be9bc38525e90d4178d47f21dd8eb9f238fc55ffaa4ca94506", hash("abc"))
        assertEquals("3fc52c4ae40faa946b1bc0eeb747059a35fba6efaa3d616074e720d6e99436cd", hash("DeepSeekHashV1"))
    }

    @Test fun blockBoundaries() {
        assertEquals("1e3eb415bb0b3ecec08a6f71cf7c8d0591b5594752ba52802a2e60357834f998", hash("a".repeat(135)))
        assertEquals("680364b336f77918ed390287a581f96f1371599825acd1e348fa7649fcecbbab", hash("a".repeat(136)))
        assertEquals("dfeb7768d4d48053c083d849570e84cc8120520790f2d09cb56e40038081fab4", hash("a".repeat(137)))
        assertEquals("155f834e0a858121480ddc86615b716cffad9ca3144f14e247047a15206dadf6", hash("a".repeat(272)))
    }

    @Test fun differsFromStandardSha3OnlyByRoundZero() {
        val standard = hex(MessageDigest.getInstance("SHA3-256").digest(ByteArray(0)))
        assertEquals("a7ffc6f8bf1ed76651c14756a061d662f580ff4de43b49fa82d80a4b80f8434a", standard)
        assertNotEquals(standard, hash(""))
    }

    @Test fun answers() {
        val salt = "3f1c0e9a7b5d2c4e6a80"
        val expireAt = 1_792_534_088_468L
        assertEquals(0L, DeepSeekHashV1.answer(challenge("864d931034833de8d3398779f933947a649cd1ac16198fb75c0fc745024a7486", salt, expireAt, 144_000)))
        assertEquals(7L, DeepSeekHashV1.answer(challenge("91fbbaaf42e7cdbb7affb7ceda1fb3c0970abfcd0e4140f4d4b6f68335babe4e", salt, expireAt, 144_000)))
        assertEquals(41_643L, DeepSeekHashV1.answer(challenge("77ac0ee509096c07ef56e4542715c7cce5a4f285597c348980b3ca3a571f9730", salt, expireAt, 144_000)))
        assertEquals(999L, DeepSeekHashV1.answer(challenge("98d58bab99292d4226d3d2d279ed6ada87754fb7fb72805e10b46dee6d8d07ca", "e2e-salt", expireAt, 1000)))
        assertEquals(7L, DeepSeekHashV1.answer(challenge("b6c3a1591ebbc7c912763b66fd382e3aeece3c0f364e1f1d330c90f0a5a9b3be", "盐", 2000, 64)))
    }

    @Test fun lastPermittedNonceAndNothingAtTheDifficulty() {
        val target = "102c6086ea0fc9ff22995b4a29693b6ebf31f0007f167cdd0062b4437d3e4375"
        val started = System.nanoTime()
        assertEquals(143_999L, DeepSeekHashV1.answer(challenge(target, "3f1c0e9a7b5d2c4e6a80", 1_792_534_088_468L, 144_000)))
        assertTrue("worst case took too long", (System.nanoTime() - started) / 1_000_000 < 6_000)
        assertNull(DeepSeekHashV1.answer(challenge(target, "3f1c0e9a7b5d2c4e6a80", 1_792_534_088_468L, 143_999)))
    }

    @Test fun malformedChallengesHaveNoAnswer() {
        listOf("abcdef", "zz".repeat(32), "0".repeat(63)).forEach { assertNull(DeepSeekHashV1.answer(challenge(it, "salt", 1, 1000))) }
    }

    @Test fun checkpointCanAbortTheSearch() {
        var checks = 0
        assertThrows(InterruptedException::class.java) {
            DeepSeekHashV1.answer(challenge("0".repeat(64), "salt", 1, 9_000_000_000L)) { if (++checks > 3) throw InterruptedException() }
        }
    }
}
