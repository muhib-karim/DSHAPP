package com.dsh.android.account

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AccountChangesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val changes = AccountChanges()

    @Test fun cancelOrRotationKeepsThePreviousCredential() {
        val home = tmp.newFolder()
        DshCredentials.write(home, mapOf(DshCredentials.DEEPSEEK_API_KEY to "previous-key"))
        val attempt = changes.begin()
        attempt.cancel()
        assertFalse(attempt.commit { DshCredentials.write(home, mapOf(DshCredentials.DEEPSEEK_API_KEY to "late-key")) })
        assertEquals("previous-key", DshCredentials.read(home, DshCredentials.DEEPSEEK_API_KEY))
    }

    @Test fun newerLoginSupersedesAnOldNetworkResult() {
        val home = tmp.newFolder()
        val old = changes.begin()
        val current = changes.begin()
        assertTrue(current.commit { DshCredentials.write(home, mapOf(DshCredentials.DEEPSEEK_API_KEY to "current-key")) })
        assertFalse(old.commit { DshCredentials.write(home, mapOf(DshCredentials.DEEPSEEK_API_KEY to "late-key")) })
        assertEquals("current-key", DshCredentials.read(home, DshCredentials.DEEPSEEK_API_KEY))
    }

    @Test fun signOutInvalidatesPendingWorkBeforeDeletingCredentials() {
        val home = tmp.newFolder()
        DshCredentials.write(home, mapOf(DshCredentials.DEEPSEEK_API_KEY to "previous-key"))
        val pending = changes.begin()
        val signOut = changes.begin()
        assertFalse(pending.commit { DshCredentials.write(home, mapOf(DshCredentials.DEEPSEEK_API_KEY to "late-key")) })
        assertTrue(signOut.commit { DshCredentials.write(home, mapOf(DshCredentials.DEEPSEEK_API_KEY to null)) })
        assertFalse(pending.commit { DshCredentials.write(home, mapOf(DshCredentials.DEEPSEEK_API_KEY to "late-key")) })
        assertNull(DshCredentials.read(home, DshCredentials.DEEPSEEK_API_KEY))
    }

    @Test fun acceptingAnAccountIsOneShotAndOldOwnerCannotCancelNewWork() {
        val previous = changes.begin()
        val current = changes.begin()
        previous.cancel()
        var writes = 0
        assertTrue(current.commit { writes++ })
        assertFalse(current.commit { writes++ })
        assertEquals(1, writes)
    }

    @Test fun failedCommitDoesNotAllowAStaleRetry() {
        val attempt = changes.begin()
        assertThrows(java.io.IOException::class.java) { attempt.commit { throw java.io.IOException("disk full") } }
        assertFalse(attempt.commit { fail("failed attempt must be retried through validation") })
    }
}
