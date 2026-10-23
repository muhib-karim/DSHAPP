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
        assertEquals("DSH1.3.1-mk-gecko.apk", AppUpdater.parseRelease(release("v1.3.1"), gecko = true)!!.apkName)
    }

    @Test fun draftsPrereleasesAndJunkAreIgnored() {
        assertNull(AppUpdater.parseRelease(release("v1.4.0", draft = true), gecko = false))
        assertNull(AppUpdater.parseRelease(release("v1.4.0", pre = true), gecko = false))
        assertNull(AppUpdater.parseRelease("{\"message\":\"Not Found\"}", gecko = false))
        assertNull(AppUpdater.parseRelease("{\"tag_name\":\"v1.4.0\",\"assets\":[]}", gecko = false))
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
}
