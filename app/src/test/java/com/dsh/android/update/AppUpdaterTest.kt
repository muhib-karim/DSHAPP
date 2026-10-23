package com.dsh.android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

class AppUpdaterTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun release(tag: String, draft: Boolean = false, pre: Boolean = false) = """
        {"tag_name":"$tag","draft":$draft,"prerelease":$pre,"html_url":"https://github.com/muhib-karim/DSHAPP/releases/tag/$tag","body":"Notes",
         "assets":[
          {"name":"DSH${tag.removePrefix("v")}-mk.apk","browser_download_url":"https://github.com/x/DSH-mk.apk","size":120000000},
          {"name":"DSH${tag.removePrefix("v")}-mk-gecko.apk","browser_download_url":"https://github.com/x/DSH-mk-gecko.apk","size":200000000},
          {"name":"SHA256SUMS.txt","browser_download_url":"https://github.com/x/SHA256SUMS.txt","size":200}
         ]}
    """.trimIndent()

    @Test fun picksTheApkOfThisFlavor() {
        val system = AppUpdater.parseRelease(release("v1.3.1"), gecko = false)!!
        assertEquals("1.3.1", system.version)
        assertEquals("DSH1.3.1-mk.apk", system.apkName)
        assertEquals("https://github.com/x/DSH-mk.apk", system.apkUrl)
        assertEquals("https://github.com/x/SHA256SUMS.txt", system.sumsUrl)
        assertEquals("https://github.com/muhib-karim/DSHAPP/releases/tag/v1.3.1", system.page)
        assertEquals("Notes", system.notes)
        assertEquals("DSH1.3.1-mk-gecko.apk", AppUpdater.parseRelease(release("v1.3.1"), gecko = true)!!.apkName)
    }

    @Test fun draftsPrereleasesAndJunkAreIgnored() {
        assertNull(AppUpdater.parseRelease(release("v1.4.0", draft = true), gecko = false))
        assertNull(AppUpdater.parseRelease(release("v1.4.0", pre = true), gecko = false))
        assertNull(AppUpdater.parseRelease("{\"message\":\"Not Found\"}", gecko = false))
        assertNull(AppUpdater.parseRelease("{\"tag_name\":\"v1.4.0\",\"assets\":[]}", gecko = false))
    }

    @Test fun releaseNotesAreCleanedPlainText() {
        assertEquals("Title\n\nBody", AppUpdater.displayNotes("  Title\r\n\r\n\r\n\tBody\u0000\r\n"))
        assertEquals("one line", AppUpdater.displayNotes("one line\n\n\n"))
        assertEquals("", AppUpdater.displayNotes(""))
        assertEquals("", AppUpdater.displayNotes(" \r\n\t\u0000 "))
        assertEquals("", AppUpdater.parseRelease(release("v1.3.1").replace("\"body\":\"Notes\",", ""), false)!!.notes)
    }

    @Test fun longReleaseNotesAreCappedWithAnEllipsis() {
        val shown = AppUpdater.displayNotes("x".repeat(10_000))
        assertEquals(AppUpdater.MAX_NOTES_CHARS, shown.length)
        assertTrue(shown.endsWith("\u2026"))
        assertEquals("x".repeat(AppUpdater.MAX_NOTES_CHARS - 1), shown.dropLast(1))
    }

    @Test fun releaseNotesNeverSplitASurrogatePair() {
        val source = "a" + "\uD83D\uDE00".repeat(3_000)
        val shown = AppUpdater.displayNotes(source)
        assertTrue(shown.length <= AppUpdater.MAX_NOTES_CHARS)
        assertTrue(shown.endsWith("\u2026"))
        val kept = shown.dropLast(1)
        assertFalse(Character.isHighSurrogate(kept.last()))
        assertEquals(source.take(kept.length), kept)
    }

    @Test fun onlyThisForksHttpsReleasePagesAreTrusted() {
        listOf(
            "https://github.com/muhib-karim/DSHAPP/releases/tag/v1.3.1",
            "https://github.com/muhib-karim/DSHAPP/releases/latest",
            "https://github.com:443/muhib-karim/DSHAPP/releases/tag/v1.3.1",
            "https://GITHUB.com/muhib-karim/DSHAPP/releases/tag/v1.3.1",
        ).forEach { assertTrue(it, AppUpdater.isTrustedReleasePage(it)) }
        listOf(
            "http://github.com/muhib-karim/DSHAPP/releases/tag/v1.3.1",
            "https://evil.example/muhib-karim/DSHAPP/releases/tag/v1.3.1",
            "https://github.com.evil.example/muhib-karim/DSHAPP/releases/tag/v1.3.1",
            "https://user@github.com/muhib-karim/DSHAPP/releases/tag/v1.3.1",
            "https://github.com:8443/muhib-karim/DSHAPP/releases/tag/v1.3.1",
            "https://github.com/muhib-karim/DSHAPP/releases/../../evil",
            "https://github.com/muhib-karim/DSHAPP/releases/%2e%2e/evil",
            "https://github.com/other/DSHAPP/releases/tag/v1.3.1",
            "https://github.com/muhib-karim/DSHAPP/issues",
            "javascript:alert(1)",
            "",
        ).forEach { assertFalse(it, AppUpdater.isTrustedReleasePage(it)) }
    }

    @Test fun onlyNewerReleasesAreUpdates() {
        val r = AppUpdater.parseRelease(release("v1.3.1"), gecko = false)!!
        assertTrue(AppUpdater.isUpdate(r, "1.3.0"))
        assertTrue(AppUpdater.isUpdate(r, "1.3.0-gecko"))
        assertFalse(AppUpdater.isUpdate(r, "1.3.1"))
        assertFalse(AppUpdater.isUpdate(r, "1.4.0"))
    }

    @Test fun sha256FromTheSumsFile() {
        val a = "a".repeat(64); val b = "B".repeat(64)
        val sums = "$a  ./DSH1.3.1-mk.apk\n$b *DSH1.3.1-mk-gecko.apk\n"
        assertEquals(a, AppUpdater.sha256For(sums, "DSH1.3.1-mk.apk"))
        assertEquals("b".repeat(64), AppUpdater.sha256For(sums, "DSH1.3.1-mk-gecko.apk"))
        assertNull(AppUpdater.sha256For(sums, "DSH1.3.1.apk"))
        assertNull(AppUpdater.sha256For("zz  ./DSH1.3.1-mk.apk", "DSH1.3.1-mk.apk"))
    }

    @Test fun verifiedDownloadCanBeReusedAfterTheInstallerWasCancelled() {
        val rel = AppUpdater.parseRelease(release("v1.3.1"), false)!!
        val apk = File(tmp.root, rel.apkName).apply { writeText("abc") }
        val ready = AppUpdater.DownloadedApk(rel, apk, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        assertTrue(ready.isFor(rel))
        assertTrue(ready.verify())
        assertFalse(ready.isFor(AppUpdater.parseRelease(release("v1.3.2"), false)!!))
        assertFalse(ready.isFor(AppUpdater.parseRelease(release("v1.3.1"), true)!!))
    }

    @Test fun changedOrMissingDownloadedApkCannotReachTheInstaller() {
        val rel = AppUpdater.parseRelease(release("v1.3.1"), false)!!
        val apk = File(tmp.root, rel.apkName).apply { writeText("abc") }
        val ready = AppUpdater.DownloadedApk(rel, apk, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        apk.writeText("bad")
        assertFalse(ready.verify())
        apk.delete()
        assertFalse(ready.isFor(rel))
        assertFalse(ready.verify())
    }

    @Test fun cacheCleanupKeepsTheApkTheInstallerMayBeReading() {
        val dir = tmp.newFolder("updates")
        val installer = File(dir, "DSH1.3.1-mk.apk").apply { writeText("apk") }
        val partial = File(dir, "DSH1.4.0-mk.apk.7.part").apply { writeText("partial") }
        val sums = File(dir, "SHA256SUMS.txt").apply { writeText("sums") }
        val nested = File(dir, "old").apply { mkdirs() }
        File(nested, "junk").writeText("junk")

        assertEquals(3, AppUpdater.cleanUpdateCache(dir, keep = installer))
        assertTrue(installer.isFile)
        assertFalse(partial.exists())
        assertFalse(sums.exists())
        assertFalse(nested.exists())
        assertTrue(dir.isDirectory)
    }

    @Test fun cacheCleanupToleratesAMissingDirectory() {
        assertEquals(0, AppUpdater.cleanUpdateCache(File(tmp.root, "missing")))
    }
}
