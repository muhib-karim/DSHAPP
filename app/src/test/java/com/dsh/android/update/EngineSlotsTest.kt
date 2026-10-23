package com.dsh.android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EngineSlotsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun harness(dir: File, version: String) {
        val pkg = File(dir, "node_modules/@deepseek-ai/dsh")
        File(pkg, "lib").mkdirs()
        File(pkg, "lib/bin.js").writeText("// $version")
        File(pkg, "package.json").writeText("{\"name\":\"@deepseek-ai/dsh\",\"version\":\"$version\"}")
    }

    @Test fun promoteKeepsPrevious() {
        val slots = EngineSlots(tmp.root)
        harness(slots.engine, "0.2.1-rc.2")
        harness(slots.next, "0.2.1-rc.3")
        assertTrue(slots.promote())
        assertEquals("0.2.1-rc.3", slots.installedVersion())
        assertEquals("0.2.1-rc.2", slots.versionIn(slots.prev))
        assertFalse(slots.next.exists())
        assertEquals("0.2.1-rc.3", slots.pending())
        slots.confirm()
        assertNull(slots.pending())
    }

    @Test fun rollbackRestoresPrevious() {
        val slots = EngineSlots(tmp.root)
        harness(slots.engine, "0.2.1-rc.2")
        harness(slots.next, "0.2.2")
        slots.promote()
        assertTrue(slots.rollback())
        assertEquals("0.2.1-rc.2", slots.installedVersion())
        assertNull(slots.pending())
        assertEquals(setOf("0.2.2"), slots.badVersions())
        assertFalse(File(tmp.root, "engine.failed").exists())
    }

    @Test fun incompleteNextIsNeverPromoted() {
        val slots = EngineSlots(tmp.root)
        harness(slots.engine, "0.2.1-rc.2")
        slots.next.mkdirs()
        assertFalse(slots.promote())
        assertEquals("0.2.1-rc.2", slots.installedVersion())
        assertNull(slots.pending())
    }

    @Test fun promoteWithoutAnExistingEngine() {
        val slots = EngineSlots(tmp.root)
        harness(slots.next, "0.2.2")
        assertTrue(slots.promote())
        assertEquals("0.2.2", slots.installedVersion())
        assertFalse(slots.rollback(), "nothing to roll back to")
    }

    @Test fun badVersionsAreRecordedOnce() {
        val slots = EngineSlots(tmp.root)
        slots.markBad("0.2.2"); slots.markBad("0.2.2"); slots.markBad("0.2.3")
        assertEquals(setOf("0.2.2", "0.2.3"), slots.badVersions())
    }

    @Test fun interruptedPromotionBeforeMovingCurrentKeepsTheCurrentHarness() {
        val slots = EngineSlots(tmp.root)
        harness(slots.engine, "0.2.2")
        harness(slots.next, "0.2.3")
        transaction("promote", "0.2.3", "0.2.2")
        EngineSlots(tmp.root).recoverInterrupted()
        assertEquals("0.2.2", slots.installedVersion())
        assertNull(slots.pending())
    }

    @Test fun interruptedPromotionAfterMovingCurrentRestoresThePreviousHarness() {
        val slots = EngineSlots(tmp.root)
        harness(slots.prev, "0.2.2")
        harness(slots.next, "0.2.3")
        transaction("promote", "0.2.3", "0.2.2")
        EngineSlots(tmp.root).recoverInterrupted()
        assertEquals("0.2.2", slots.installedVersion())
        assertNull(slots.pending())
        assertTrue(slots.badVersions().isEmpty())
    }

    @Test fun interruptedPromotionAfterMovingCandidateRetainsTheRollbackMarker() {
        val slots = EngineSlots(tmp.root)
        harness(slots.prev, "0.2.2")
        harness(slots.engine, "0.2.3")
        transaction("promote", "0.2.3", "0.2.2")
        EngineSlots(tmp.root).recoverInterrupted()
        assertEquals("0.2.3", slots.installedVersion())
        assertEquals("0.2.3", slots.pending())
        assertEquals("0.2.2", slots.versionIn(slots.prev))
        assertTrue(slots.rollback())
        assertEquals("0.2.2", slots.installedVersion())
    }

    @Test fun interruptedRollbackAfterMovingCandidateRestoresThePreviousHarness() {
        val slots = EngineSlots(tmp.root)
        harness(slots.prev, "0.2.2")
        harness(File(tmp.root, "engine.failed"), "0.2.3")
        File(tmp.root, "engine.pending").writeText("0.2.3")
        transaction("rollback", "0.2.3", "0.2.2")
        EngineSlots(tmp.root).recoverInterrupted()
        assertEquals("0.2.2", slots.installedVersion())
        assertNull(slots.pending())
        assertEquals(setOf("0.2.3"), slots.badVersions())
    }

    @Test fun interruptedRollbackAfterRestoringPreviousStillRecordsTheBadVersion() {
        val slots = EngineSlots(tmp.root)
        harness(slots.engine, "0.2.2")
        harness(File(tmp.root, "engine.failed"), "0.2.3")
        File(tmp.root, "engine.pending").writeText("0.2.3")
        transaction("rollback", "0.2.3", "0.2.2")
        EngineSlots(tmp.root).recoverInterrupted()
        assertEquals("0.2.2", slots.installedVersion())
        assertNull(slots.pending())
        assertEquals(setOf("0.2.3"), slots.badVersions())
    }

    @Test fun failedCandidateMoveRestoresTheCurrentHarness() {
        val slots = EngineSlots(tmp.root) { from, to ->
            if (from.name == "engine.next") false else from.renameTo(to)
        }
        harness(slots.engine, "0.2.2")
        harness(slots.next, "0.2.3")
        assertFalse(slots.promote())
        assertEquals("0.2.2", slots.installedVersion())
        assertEquals("0.2.3", slots.versionIn(slots.next))
        assertNull(slots.pending())
    }

    @Test fun failedRollbackMovePreservesBothHarnessesForRecovery() {
        val working = EngineSlots(tmp.root)
        harness(working.engine, "0.2.2")
        harness(working.next, "0.2.3")
        assertTrue(working.promote())
        val failing = EngineSlots(tmp.root) { from, to ->
            if (from.name == "engine.prev") false else from.renameTo(to)
        }
        assertFalse(failing.rollback())
        assertEquals("0.2.3", working.installedVersion())
        assertEquals("0.2.2", working.versionIn(working.prev))
        assertEquals("0.2.3", working.pending())
        working.recoverInterrupted()
        assertEquals("0.2.2", working.installedVersion())
        assertNull(working.pending())
    }

    @Test fun aPendingCandidateCannotDiscardTheLastKnownWorkingHarness() {
        val slots = EngineSlots(tmp.root)
        harness(slots.engine, "0.2.2")
        harness(slots.next, "0.2.3")
        assertTrue(slots.promote())
        harness(slots.next, "0.2.4")
        assertFalse(slots.promote())
        assertEquals("0.2.2", slots.versionIn(slots.prev))
        assertEquals("0.2.3", slots.pending())
    }

    @Test fun previousVersionIsOfferedOnlyAfterTheCandidateBooted() {
        val slots = EngineSlots(tmp.root)
        harness(slots.engine, "0.2.2")
        harness(slots.next, "0.2.3")
        assertTrue(slots.promote())
        assertNull("an unbooted candidate owns the rollback window", slots.previousVersion())
        assertNull(slots.rollbackCandidate())
        slots.confirm()
        assertEquals("0.2.2", slots.previousVersion())
        assertEquals(slots.prev, slots.rollbackCandidate())
    }

    @Test fun rollbackCandidateNeverRacesAnInstallOrARecovery() {
        val slots = EngineSlots(tmp.root)
        harness(slots.engine, "0.2.2")
        harness(slots.next, "0.2.3")
        slots.promote()
        slots.confirm()
        harness(slots.next, "0.2.4")
        assertNull("a queued candidate blocks the offer", slots.previousVersion())
        slots.next.deleteRecursively()
        assertEquals("0.2.2", slots.previousVersion())
        transaction("promote", "0.2.4", "0.2.3")
        assertNull("an interrupted rename is recovered by its owner first", slots.previousVersion())
    }

    @Test fun incompleteOrUnknownPreviousHarnessIsNotOffered() {
        val slots = EngineSlots(tmp.root)
        harness(slots.engine, "0.2.2")
        assertNull("no engine.prev yet", slots.previousVersion())
        harness(slots.prev, "0.2.1")
        File(slots.prev, "node_modules/@deepseek-ai/dsh/lib/bin.js").delete()
        assertNull("engine.prev without the entry point is unusable", slots.previousVersion())
        harness(slots.prev, "0.2.1")
        File(slots.prev, "node_modules/@deepseek-ai/dsh/package.json").writeText("not json")
        assertNull("engine.prev without a readable version is not a candidate", slots.previousVersion())
    }

    @Test fun rollbackCandidateReportsTheVersionTheRollbackWouldRestore() {
        val slots = EngineSlots(tmp.root)
        harness(slots.engine, "0.2.2")
        harness(slots.next, "0.2.3")
        slots.promote()
        slots.confirm()
        val offered = slots.previousVersion()
        assertEquals("0.2.2", offered)
        assertEquals(offered, slots.versionIn(slots.rollbackCandidate()!!))
        assertTrue(slots.rollback())
        assertEquals("0.2.2", slots.installedVersion())
        assertNull(slots.previousVersion())
    }

    private fun transaction(operation: String, version: String, previous: String) {
        File(tmp.root, "engine.transaction").writeText(
            """{"operation":"$operation","version":"$version","previous":"$previous"}"""
        )
    }

    private fun assertFalse(value: Boolean, message: String) = org.junit.Assert.assertFalse(message, value)
}
