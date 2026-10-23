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

    @Test fun failedCredentialRemovalPreservesAccountAndBridge() {
        val home = tmp.newFolder()
        DshCredentials.write(home, mapOf(DshCredentials.DEEPSEEK_API_KEY to "previous-key"))
        var mode = AccountConfig.Mode.API_KEY
        var token: String? = "previous-session"
        var stopped = false
        var success = false
        assertThrows(java.io.IOException::class.java) {
            success = changes.begin().signOut(
                clearCredentials = { throw java.io.IOException("disk full") },
                forgetAccount = { mode = AccountConfig.Mode.NONE; token = null },
                stopBridge = { stopped = true },
            )
        }
        assertEquals(AccountConfig.Mode.API_KEY, mode)
        assertEquals("previous-session", token)
        assertEquals("previous-key", DshCredentials.read(home, DshCredentials.DEEPSEEK_API_KEY))
        assertFalse(stopped)
        assertFalse(success)
    }

    @Test fun successfulSignOutRemovesBothKeysBeforeForgettingAccount() {
        val home = tmp.newFolder()
        DshCredentials.write(home, mapOf(DshCredentials.DEEPSEEK_API_KEY to "api", DshCredentials.WEB_BRIDGE_KEY to "bridge"))
        val order = mutableListOf<String>()
        assertTrue(changes.begin().signOut(
            clearCredentials = {
                DshCredentials.write(home, mapOf(DshCredentials.DEEPSEEK_API_KEY to null, DshCredentials.WEB_BRIDGE_KEY to null))
                order += "credentials"
            },
            forgetAccount = {
                assertNull(DshCredentials.read(home, DshCredentials.DEEPSEEK_API_KEY))
                assertNull(DshCredentials.read(home, DshCredentials.WEB_BRIDGE_KEY))
                order += "account"
            },
            stopBridge = { order += "bridge" },
        ))
        assertEquals(listOf("credentials", "account", "bridge"), order)
    }

    @Test fun supersededSignOutCannotEraseANewAccount() {
        val signOut = changes.begin()
        val login = changes.begin()
        assertTrue(login.commit {})
        assertFalse(signOut.isLatest)
        assertTrue(login.isLatest)
        assertFalse(signOut.signOut(
            clearCredentials = { fail("superseded") },
            forgetAccount = { fail("superseded") },
            stopBridge = { fail("superseded") },
        ))
    }
}
