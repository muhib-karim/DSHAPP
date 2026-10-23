package com.dsh.android.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class DshCredentialsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Suppress("UNCHECKED_CAST")
    private fun refs(doc: String): Map<String, Any?> =
        (Yaml().load<Map<String, Any?>>(doc)["refs"] as Map<String, Any?>?) ?: emptyMap()

    @Test fun writesVersionAndKeyOnAnEmptyHome() {
        val home = tmp.newFolder("dsh")
        DshCredentials.write(home, mapOf(DshCredentials.DEEPSEEK_API_KEY to "sk-test-0123456789"))
        val doc = DshCredentials.file(home).readText()
        assertEquals("version: 1\nrefs:\n  DEEPSEEK_API_KEY: \"sk-test-0123456789\"\n", doc)
        assertEquals("sk-test-0123456789", refs(doc)["DEEPSEEK_API_KEY"])
    }

    @Test fun fileIsOwnerOnly() {
        val home = tmp.newFolder("dsh")
        DshCredentials.write(home, mapOf(DshCredentials.DEEPSEEK_API_KEY to "sk-test-0123456789"))
        val perms = Files.getPosixFilePermissions(DshCredentials.file(home).toPath())
        assertEquals("rw-------", PosixFilePermissions.toString(perms))
    }

    @Test fun preservesRecordsAndOtherRefs() {
        val existing = """
            version: 1
            refs:
              MY_GW_API_KEY: gw-secret
            records:
              client-connection/browser-session:
                kind: grant
                payload:
                  version: 1
                  secret: abcDEF123
        """.trimIndent() + "\n"
        val out = DshCredentials.render(existing, mapOf("DEEPSEEK_API_KEY" to "sk-new-0123456789"))
        assertTrue(out.contains("records:\n  client-connection/browser-session:\n    kind: grant\n    payload:\n      version: 1\n      secret: abcDEF123\n"))
        val parsed = Yaml().load<Map<String, Any?>>(out)
        assertEquals(mapOf("MY_GW_API_KEY" to "gw-secret", "DEEPSEEK_API_KEY" to "sk-new-0123456789"), parsed["refs"])
        assertNotNull(parsed["records"])
    }

    @Test fun replacesAnExistingValueInsteadOfDuplicating() {
        val first = DshCredentials.render(null, mapOf("DEEPSEEK_API_KEY" to "sk-one-0123456789"))
        val second = DshCredentials.render(first, mapOf("DEEPSEEK_API_KEY" to "sk-two-0123456789"))
        assertEquals(1, Regex("DEEPSEEK_API_KEY").findAll(second).count())
        assertEquals("sk-two-0123456789", refs(second)["DEEPSEEK_API_KEY"])
    }

    @Test fun removingTheKeyKeepsOtherRefsAndLeavesAValidMap() {
        val both = DshCredentials.render(null, mapOf("DEEPSEEK_API_KEY" to "sk-one-0123456789", "OTHER" to "x-1"))
        val removed = DshCredentials.render(both, mapOf("DEEPSEEK_API_KEY" to null))
        assertEquals(mapOf("OTHER" to "x-1"), refs(removed))
        val empty = DshCredentials.render(removed, mapOf("OTHER" to null))
        assertTrue(empty.contains("refs: {}"))
        assertEquals(emptyMap<String, Any?>(), refs(empty))
    }

    @Test fun refsAfterRecordsAreFound() {
        val existing = "version: 1\nrecords:\n  a/b:\n    kind: grant\nrefs:\n  DEEPSEEK_API_KEY: old-value-1\n"
        val out = DshCredentials.render(existing, mapOf("DEEPSEEK_API_KEY" to "sk-new-0123456789"))
        assertEquals("sk-new-0123456789", refs(out)["DEEPSEEK_API_KEY"])
        assertEquals(1, Regex("refs:").findAll(out).count())
    }

    @Test fun readsAStoredRef() {
        val home = tmp.newFolder("dsh")
        assertNull(DshCredentials.read(home, "DEEPSEEK_API_KEY"))
        DshCredentials.write(home, mapOf("DEEPSEEK_API_KEY" to "sk-test-0123456789"))
        assertEquals("sk-test-0123456789", DshCredentials.read(home, "DEEPSEEK_API_KEY"))
    }

    @Test fun hintShowsOnlyTheLastFourCharacters() {
        val key = "sk-0123456789abcdef" // gitleaks:allow (fake test fixture)
        val hint = DshCredentials.keyHint(key)
        assertEquals("••••cdef", hint)
        assertFalse("the hint must not contain the whole key", hint!!.contains(key))
        assertFalse("the hint must not expose an early part of the key", hint.contains(key.take(6)))
        assertEquals("••••6789", DshCredentials.keyHint("  sk-test-0123456789 \n"))
    }

    @Test fun hintIsNullWhenThereIsNoUsableKey() {
        assertNull(DshCredentials.keyHint(null))
        assertNull(DshCredentials.keyHint(""))
        assertNull(DshCredentials.keyHint("   "))
        assertNull(DshCredentials.keyHint("short"))
        assertNull(DshCredentials.keyHint("sk-0123"))
        assertNull(DshCredentials.keyHint("sk-test has a space"))
        assertNull(DshCredentials.keyHint("sk-ключ-0123456789"))
    }

    @Test fun keyShapeProblems() {
        assertNotNull(DshCredentials.problem(""))
        assertNotNull(DshCredentials.problem("sk abc defgh"))
        assertNotNull(DshCredentials.problem("short"))
        assertNotNull(DshCredentials.problem("sk-\"quoted\"-key"))
        assertNotNull(DshCredentials.problem("sk-ключ-0123456789"))
        assertNull(DshCredentials.problem("sk-0123456789abcdef"))
        assertNull(DshCredentials.problem("  sk-0123456789abcdef \n"))
        assertFalse(DshCredentials.problem("sk abc")!!.contains("sk abc"))
    }
}
