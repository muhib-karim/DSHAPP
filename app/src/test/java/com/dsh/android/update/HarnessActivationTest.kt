package com.dsh.android.update

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HarnessActivationTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun candidate(): EngineSlots {
        val slots = EngineSlots(tmp.root)
        for ((dir, version) in listOf(slots.engine to "0.2.2", slots.next to "0.2.3")) {
            val pkg = File(dir, "node_modules/@deepseek-ai/dsh")
            File(pkg, "lib").mkdirs()
            File(pkg, "lib/bin.js").writeText("// fixture")
            File(pkg, "package.json").writeText("""{"version":"$version"}""")
        }
        assertTrue(slots.promote())
        return slots
    }

    @Test fun timedOutCandidateIsStoppedBeforeItsFilesAreReplaced() {
        val slots = candidate()
        // Inherit ignored TERM in sleep so this exercises the forced kill and reap path.
        val process = ProcessBuilder("sh", "-c", "trap '' TERM; echo ready; exec sleep 30").start()
        try {
            assertEquals("ready", process.inputStream.bufferedReader().readLine())
            val activation = HarnessActivation(slots) {
                assertEquals("0.2.3", slots.installedVersion())
                HarnessActivation.stopAndReap(process)
                assertFalse(process.isAlive)
            }
            assertEquals(HarnessActivation.Result.ROLLED_BACK, activation.failed())
            assertFalse(process.isAlive)
            assertEquals("0.2.2", slots.installedVersion())
            assertEquals(setOf("0.2.3"), slots.badVersions())
        } finally {
            process.destroyForcibly()
        }
    }

    @Test fun aProcessThatCannotBeStoppedDoesNotHaveItsFilesReplaced() {
        val slots = candidate()
        val activation = HarnessActivation(slots) { error("still alive") }
        assertEquals(HarnessActivation.Result.ROLLBACK_FAILED, activation.failed())
        assertEquals("0.2.3", slots.installedVersion())
        assertEquals("0.2.2", slots.versionIn(slots.prev))
        assertEquals("0.2.3", slots.pending())
    }

    @Test fun invalidCandidateConfigurationIsRejectedBeforeUserRepair() {
        val slots = candidate()
        val profile = File(tmp.root, "cordis.patch.yml").apply { writeText("user settings") }
        val activation = HarnessActivation(slots) {}
        for ((exit, required, skipped) in listOf(Triple(1, true, false), Triple(0, false, false), Triple(0, true, true))) {
            assertThrows(HarnessActivation.InvalidCandidate::class.java) {
                activation.validateConfiguration(exit, required, skipped)
                profile.writeText("repaired")
            }
            assertEquals("user settings", profile.readText())
        }
        assertEquals(HarnessActivation.Result.ROLLED_BACK, activation.failed())
        assertEquals("0.2.2", slots.installedVersion())
    }

    @Test fun degradedGatewayRollsBackInsteadOfConfirmingTheCandidate() {
        val slots = candidate()
        val activation = HarnessActivation(slots) {}
        assertEquals(HarnessActivation.Result.ROLLED_BACK, activation.ready(gatewayHealthy = false))
        assertEquals("0.2.2", slots.installedVersion())
        assertEquals(setOf("0.2.3"), slots.badVersions())
    }

    @Test fun unavailablePreflightRejectsTheCandidateWithoutRepairingUserFiles() {
        val slots = candidate()
        val activation = HarnessActivation(slots) {}
        assertThrows(HarnessActivation.InvalidCandidate::class.java) {
            activation.validateConfiguration(null, hasRequiredEntries = false, hasSkippedBundles = false)
        }
        assertEquals(HarnessActivation.Result.ROLLED_BACK, activation.failed())
        assertEquals("0.2.2", slots.installedVersion())
    }

    @Test fun exitedProcessCannotBeConfirmedEvenAfterPrintingTheUrl() {
        val slots = candidate()
        val activation = HarnessActivation(slots) {}
        assertEquals(HarnessActivation.Result.ROLLED_BACK, activation.ready(gatewayHealthy = true, processAlive = false))
        assertEquals("0.2.2", slots.installedVersion())
    }

    @Test fun healthyCandidateIsConfirmedAndOrdinaryFailuresKeepTheCurrentHarness() {
        val slots = candidate()
        val activation = HarnessActivation(slots) { fail("a healthy engine must stay running") }
        activation.validateConfiguration(0, hasRequiredEntries = true, hasSkippedBundles = false)
        assertEquals(HarnessActivation.Result.CONFIRMED, activation.ready(gatewayHealthy = true))
        assertNull(slots.pending())
        assertEquals(HarnessActivation.Result.NOT_PENDING, activation.failed())
        assertEquals("0.2.3", slots.installedVersion())
    }
}
