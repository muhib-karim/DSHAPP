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

    private fun assertFalse(value: Boolean, message: String) = org.junit.Assert.assertFalse(message, value)
}
