package com.dsh.android.webbridge

/**
 * DeepSeek's proof-of-work hash ("DeepSeekHashV1"), written here from its observable definition: a SHA3-256 sponge (rate
 * 136 bytes, padding 0x06 ... 0x80, 32-byte digest) whose Keccak-f[1600] permutation runs rounds 1 to 23 (round 0 is
 * skipped). The answer to a challenge is the first n below its difficulty whose digest of "<salt>_<expire_at>_<n>" equals
 * the challenge. Solving natively replaces loading DeepSeek's hashed worker script into a WebView.
 *
 * Ported verbatim (minus coroutines) from muhib-karim/deepseek-android `core/.../DeepSeekHashV1.kt` (MIT, same owner).
 */
object DeepSeekHashV1 {
    private const val RATE = 136
    private val ROUND_CONSTANTS = longArrayOf(
        0x0000000000000001L, 0x0000000000008082L, -0x7fffffffffff7f76L, -0x7fffffff7fff8000L,
        0x000000000000808bL, 0x0000000080000001L, -0x7fffffff7fff7f7fL, -0x7fffffffffff7ff7L,
        0x000000000000008aL, 0x0000000000000088L, 0x0000000080008009L, 0x000000008000000aL,
        0x000000008000808bL, -0x7fffffffffffff75L, -0x7fffffffffff7f77L, -0x7fffffffffff7ffdL,
        -0x7fffffffffff7ffeL, -0x7fffffffffffff80L, 0x000000000000800aL, -0x7fffffff7ffffff6L,
        -0x7fffffff7fff7f7fL, -0x7fffffffffff7f80L, 0x0000000080000001L, -0x7fffffff7fff7ff8L,
    )
    private val ROTATIONS = intArrayOf(1, 3, 6, 10, 15, 21, 28, 36, 45, 55, 2, 14, 27, 41, 56, 8, 25, 43, 62, 18, 39, 61, 20, 44)
    private val LANES = intArrayOf(10, 7, 11, 17, 18, 3, 5, 16, 8, 21, 24, 4, 15, 23, 19, 13, 12, 2, 20, 14, 22, 9, 6, 1)
    /** Cancellation and expiry are checked this often while solving. */
    private const val CHECK_EVERY = 4096

    /** The 32-byte digest of [input]. */
    fun hash(input: ByteArray): ByteArray {
        val state = LongArray(25)
        var offset = 0
        while (input.size - offset >= RATE) {
            for (i in 0 until RATE / 8) state[i] = state[i] xor readLane(input, offset + i * 8)
            permute(state)
            offset += RATE
        }
        val block = ByteArray(RATE)
        System.arraycopy(input, offset, block, 0, input.size - offset)
        pad(block, input.size - offset)
        for (i in 0 until RATE / 8) state[i] = state[i] xor readLane(block, i * 8)
        permute(state)
        return ByteArray(32).also { digest -> for (index in 0 until 32) digest[index] = (state[index / 8] ushr (8 * (index % 8))).toByte() }
    }

    /** The first matching n, or null; [checkpoint] runs every few thousand candidates (cancellation, deadlines). */
    fun answer(challenge: PowChallenge, checkpoint: () -> Unit = {}): Long? {
        val target = decodeHex(challenge.challenge) ?: return null
        val targetLanes = LongArray(4) { readLane(target, it * 8) }
        val prefix = "${challenge.salt}_${challenge.expireAt}_".toByteArray(Charsets.UTF_8)
        val absorbed = prefix.size / RATE * RATE
        val base = LongArray(25)
        var offset = 0
        while (offset < absorbed) {
            for (i in 0 until RATE / 8) base[i] = base[i] xor readLane(prefix, offset + i * 8)
            permute(base)
            offset += RATE
        }
        val tail = prefix.size - absorbed
        val block = ByteArray(RATE)
        System.arraycopy(prefix, absorbed, block, 0, tail)
        val digits = ByteArray(20)
        val state = LongArray(25)
        var n = 0L
        while (n < challenge.difficulty) {
            if (n % CHECK_EVERY == 0L) checkpoint()
            val count = writeDigits(n, digits)
            if (tail + count >= RATE) {
                // The candidate crosses a block boundary (only with very long salts): hash it the plain way.
                if (hash(prefix + digits.copyOfRange(digits.size - count, digits.size)).contentEquals(target)) return n
            } else {
                java.util.Arrays.fill(block, tail, RATE, 0)
                System.arraycopy(digits, digits.size - count, block, tail, count)
                pad(block, tail + count)
                System.arraycopy(base, 0, state, 0, 25)
                for (i in 0 until RATE / 8) state[i] = state[i] xor readLane(block, i * 8)
                permute(state)
                if (state[0] == targetLanes[0] && state[1] == targetLanes[1] && state[2] == targetLanes[2] && state[3] == targetLanes[3]) return n
            }
            n++
        }
        return null
    }

    /** SHA3 padding: 0x06 after the message, 0x80 in the last byte of the block (0x86 when they coincide). */
    private fun pad(block: ByteArray, length: Int) {
        block[length] = (block[length].toInt() xor 0x06).toByte()
        block[RATE - 1] = (block[RATE - 1].toInt() xor 0x80).toByte()
    }

    /** Decimal ASCII digits of [value] at the end of [into]; returns how many were written. */
    private fun writeDigits(value: Long, into: ByteArray): Int {
        var rest = value
        var index = into.size
        do {
            into[--index] = ('0'.code + (rest % 10).toInt()).toByte()
            rest /= 10
        } while (rest > 0)
        return into.size - index
    }

    private fun readLane(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 7 downTo 0) value = (value shl 8) or (bytes[offset + index].toLong() and 0xFF)
        return value
    }

    private fun decodeHex(text: String): ByteArray? {
        if (text.length != 64) return null
        val bytes = ByteArray(32)
        for (index in 0 until 32) {
            val high = Character.digit(text[index * 2], 16)
            val low = Character.digit(text[index * 2 + 1], 16)
            if (high < 0 || low < 0) return null
            bytes[index] = ((high shl 4) or low).toByte()
        }
        return bytes
    }

    /** Keccak-f[1600] without its first round (DeepSeekHashV1 starts at round index 1). */
    private fun permute(a: LongArray) {
        for (round in 1 until 24) {
            val c0 = a[0] xor a[5] xor a[10] xor a[15] xor a[20]
            val c1 = a[1] xor a[6] xor a[11] xor a[16] xor a[21]
            val c2 = a[2] xor a[7] xor a[12] xor a[17] xor a[22]
            val c3 = a[3] xor a[8] xor a[13] xor a[18] xor a[23]
            val c4 = a[4] xor a[9] xor a[14] xor a[19] xor a[24]
            val d0 = c4 xor java.lang.Long.rotateLeft(c1, 1)
            val d1 = c0 xor java.lang.Long.rotateLeft(c2, 1)
            val d2 = c1 xor java.lang.Long.rotateLeft(c3, 1)
            val d3 = c2 xor java.lang.Long.rotateLeft(c4, 1)
            val d4 = c3 xor java.lang.Long.rotateLeft(c0, 1)
            var row = 0
            while (row < 25) {
                a[row] = a[row] xor d0; a[row + 1] = a[row + 1] xor d1; a[row + 2] = a[row + 2] xor d2
                a[row + 3] = a[row + 3] xor d3; a[row + 4] = a[row + 4] xor d4
                row += 5
            }
            var carried = a[1]
            for (step in 0 until 24) {
                val target = LANES[step]
                val next = a[target]
                a[target] = java.lang.Long.rotateLeft(carried, ROTATIONS[step])
                carried = next
            }
            row = 0
            while (row < 25) {
                val b0 = a[row]; val b1 = a[row + 1]; val b2 = a[row + 2]; val b3 = a[row + 3]; val b4 = a[row + 4]
                a[row] = b0 xor (b1.inv() and b2)
                a[row + 1] = b1 xor (b2.inv() and b3)
                a[row + 2] = b2 xor (b3.inv() and b4)
                a[row + 3] = b3 xor (b4.inv() and b0)
                a[row + 4] = b4 xor (b0.inv() and b1)
                row += 5
            }
            a[0] = a[0] xor ROUND_CONSTANTS[round]
        }
    }
}
