package com.dsh.android.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ApiKeyCheckTest {
    private fun checkWith(code: Int?, error: IOException? = null): Pair<KeyCheck, List<Pair<String, Map<String, String>>>> {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        val result = ApiKeyCheck.check("sk-0123456789abcdef") { url, headers ->
            calls += url to headers
            if (error != null) throw error
            code!!
        }
        return result to calls
    }

    @Test fun oneCheapModelsCallWithBearer() {
        val (result, calls) = checkWith(200)
        assertEquals(KeyCheck.Ok, result)
        assertEquals(1, calls.size)
        assertEquals("https://api.deepseek.com/models", calls[0].first)
        assertEquals("Bearer sk-0123456789abcdef", calls[0].second["Authorization"])
    }

    @Test fun rejectedKey() {
        val r = checkWith(401).first as KeyCheck.Failed
        assertTrue(r.message, r.message.contains("rejected"))
    }

    @Test fun noBalance() {
        val r = checkWith(402).first as KeyCheck.Failed
        assertTrue(r.message, r.message.contains("balance"))
    }

    @Test fun otherHttp() {
        val r = checkWith(503).first as KeyCheck.Failed
        assertTrue(r.message, r.message.contains("503"))
    }

    @Test fun offline() {
        val r = checkWith(null, IOException("Unable to resolve host")).first as KeyCheck.Failed
        assertTrue(r.message, r.message.contains("Could not reach"))
    }

    @Test fun badShapeNeverCallsTheNetwork() {
        var called = false
        val r = ApiKeyCheck.check("sk abc") { _, _ -> called = true; 200 }
        assertTrue(r is KeyCheck.Failed)
        assertEquals(false, called)
    }
}
