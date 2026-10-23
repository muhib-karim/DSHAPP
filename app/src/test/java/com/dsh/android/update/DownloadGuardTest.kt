package com.dsh.android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch

class DownloadGuardTest {
    @Test fun progressIsPublishedOnlyWhileTheAttemptIsCurrent() {
        val guard = DownloadGuard()
        val generation = guard.begin()
        var progress = -1
        assertTrue(guard.publish(generation) { progress = 10 })
        assertEquals(10, progress)
        assertTrue(guard.cancel())
        assertFalse(guard.publish(generation) { progress = 20 })
        assertEquals(10, progress)
    }

    @Test fun cancelPreventsTheLateTerminalState() {
        val guard = DownloadGuard()
        val generation = guard.begin()
        assertTrue(guard.cancel())
        assertFalse(guard.finish(generation) { fail("a cancelled download must not publish ready or error") })
        assertFalse(guard.isActive(generation))
        assertFalse("cancelling twice is a no-op", guard.cancel())
    }

    @Test fun onlyOneTerminalStateIsCommitted() {
        val guard = DownloadGuard()
        val generation = guard.begin()
        var terminal = 0
        assertTrue(guard.finish(generation) { terminal += 1 })
        assertFalse(guard.finish(generation) { terminal += 1 })
        assertEquals(1, terminal)
    }

    @Test fun aNewAttemptSupersedesTheOldOne() {
        val guard = DownloadGuard()
        val first = guard.begin()
        val second = guard.begin()
        assertNotEquals(first, second)
        assertFalse(guard.isActive(first))
        assertFalse(guard.publish(first) { fail("stale progress") })
        assertFalse(guard.finish(first) { fail("stale result") })
        assertTrue(guard.finish(second) {})
    }

    @Test fun cancelWithoutADownloadDoesNothing() {
        val guard = DownloadGuard()
        assertFalse(guard.cancel())
        assertTrue(guard.isActive(guard.begin()))
    }

    @Test fun cancelAndFinishCannotBothWin() {
        repeat(200) {
            val guard = DownloadGuard()
            val generation = guard.begin()
            val start = CountDownLatch(1)
            var cancelled = false
            var finished = false
            val canceller = Thread { start.await(); if (guard.cancel()) cancelled = true }
            val finisher = Thread { start.await(); if (guard.finish(generation) {}) finished = true }
            canceller.start()
            finisher.start()
            start.countDown()
            canceller.join()
            finisher.join()
            assertTrue("exactly one of cancel/finish wins", cancelled != finished)
        }
    }
}
