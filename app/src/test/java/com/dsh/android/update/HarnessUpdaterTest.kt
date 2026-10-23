package com.dsh.android.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HarnessUpdaterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val registry = """{"name":"@deepseek-ai/dsh","dist-tags":{"latest":"0.2.1-rc.3","next":"0.2.2-rc.1"},"versions":{}}"""

    @Test fun latestComesFromTheLatestDistTag() {
        assertEquals("0.2.1-rc.3", HarnessUpdater.latestFromRegistry(registry))
        assertNull(HarnessUpdater.latestFromRegistry("{}"))
        assertNull(HarnessUpdater.latestFromRegistry("not json"))
    }

    @Test fun checksAtMostDaily() {
        val day = HarnessUpdater.DAY_MS
        assertTrue(HarnessUpdater.shouldCheck(now = 10 * day, last = 0))
        assertFalse(HarnessUpdater.shouldCheck(now = 10 * day, last = 10 * day - 1000))
        assertTrue(HarnessUpdater.shouldCheck(now = 10 * day, last = 9 * day))
        assertTrue("clock moved back", HarnessUpdater.shouldCheck(now = 5 * day, last = 9 * day))
    }

    @Test fun badVersionIsNotOfferedAgain() {
        val updater = HarnessUpdater(EngineSlots(tmp.root))
        assertEquals("0.2.1-rc.3", updater.offer(current = "0.2.1-rc.2", latest = "0.2.1-rc.3"))
        EngineSlots(tmp.root).markBad("0.2.1-rc.3")
        assertNull(updater.offer(current = "0.2.1-rc.2", latest = "0.2.1-rc.3"))
        assertNull(updater.offer(current = "0.2.1-rc.3", latest = "0.2.1-rc.3"))
        assertNull(updater.offer(current = null, latest = null))
    }

    @Test fun pendingActivationCannotBeReplacedByAnotherUpdate() {
        File(tmp.root, "engine.pending").writeText("0.2.2")
        val slots = EngineSlots(tmp.root)
        assertNull(HarnessUpdater(slots).offer(current = "0.2.2", latest = "0.2.3"))
        slots.confirm()
        assertEquals("0.2.3", HarnessUpdater(slots).offer(current = "0.2.2", latest = "0.2.3"))
    }

    @Test fun nextSlotIsAPrivatePackageWithAHoistedLayout() {
        val slots = EngineSlots(tmp.root)
        slots.next.mkdirs(); File(slots.next, "leftover").writeText("x")
        HarnessUpdater(slots).prepareNext("0.2.1-rc.3")
        assertFalse(File(slots.next, "leftover").exists())
        val pkg = JSONObject(File(slots.next, "package.json").readText())
        assertEquals("0.2.1-rc.3", pkg.getJSONObject("dependencies").getString("@deepseek-ai/dsh"))
        assertTrue(pkg.getBoolean("private"))
        val npmrc = File(slots.next, ".npmrc").readText()
        assertTrue(npmrc.contains("node-linker=hoisted"))
        assertTrue(npmrc.contains("ignore-scripts=true"))
        assertTrue(HarnessUpdater.pnpmArgs().containsAll(listOf("install", "--ignore-scripts")))
    }

    @Test fun finishNextVerifiesTheInstalledVersionAndStampsIt() {
        val slots = EngineSlots(tmp.root)
        val updater = HarnessUpdater(slots)
        updater.prepareNext("0.2.1-rc.3")
        assertNotNull("nothing installed yet", updater.finishNext("0.2.1-rc.3"))
        val pkg = File(slots.next, "node_modules/@deepseek-ai/dsh")
        File(pkg, "lib").mkdirs(); File(pkg, "lib/bin.js").writeText("")
        File(pkg, "package.json").writeText("{\"version\":\"0.2.1-rc.4\"}")
        assertNotNull("wrong version", updater.finishNext("0.2.1-rc.3"))
        File(pkg, "package.json").writeText("{\"version\":\"0.2.1-rc.3\"}")
        assertNull(updater.finishNext("0.2.1-rc.3"))
        assertEquals("npm:0.2.1-rc.3", slots.stampOf(slots.next))
    }

    @Test fun anInstalledHarnessIsKeptOnlyWhileNewerThanTheBundledOne() {
        assertTrue(HarnessUpdater.keepInstalled(stamp = "npm:0.2.1-rc.3", assetStamp = "0.2.1-rc.2-1"))
        assertFalse(HarnessUpdater.keepInstalled(stamp = "npm:0.2.1-rc.3", assetStamp = "0.2.1-1"))
        assertFalse(HarnessUpdater.keepInstalled(stamp = "0.2.1-rc.2-1", assetStamp = "0.2.1-rc.2-1"))
        assertFalse(HarnessUpdater.keepInstalled(stamp = null, assetStamp = "0.2.1-rc.2-1"))
    }
}
