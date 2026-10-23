package com.dsh.android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {
    private fun lt(a: String, b: String) = assertTrue("$a < $b", Versions.compare(a, b) < 0 && Versions.compare(b, a) > 0)

    @Test fun releasesAndPrereleases() {
        lt("0.2.1-rc.2", "0.2.1-rc.3")
        lt("0.2.1-rc.9", "0.2.1-rc.10")
        lt("0.2.1-alpha.5", "0.2.1-rc.1")
        lt("0.2.1-rc.3", "0.2.1")
        lt("0.2.1", "0.2.2-alpha.1")
        lt("0.9.9", "0.10.0")
        lt("1.2.9", "1.3.0")
        lt("0.2.1-rc", "0.2.1-rc.1")
        lt("1.0.0-1", "1.0.0-alpha")
    }

    @Test fun equalAfterNormalising() {
        assertEquals(0, Versions.compare("v1.3.0", "1.3.0"))
        assertEquals(0, Versions.compare("1.3.0-gecko", "1.3.0"))
        assertEquals(0, Versions.compare("1.3", "1.3.0"))
        assertEquals(0, Versions.compare("npm:0.2.1-rc.3", "0.2.1-rc.3"))
        assertEquals(0, Versions.compare("1.3.0+build.7", "1.3.0"))
    }

    @Test fun isNewer() {
        assertTrue(Versions.isNewer("0.2.1-rc.3", "0.2.1-rc.2"))
        assertFalse(Versions.isNewer("0.2.1-rc.2", "0.2.1-rc.2"))
        assertFalse(Versions.isNewer("garbage", "0.2.1"))
        assertFalse(Versions.isNewer("", "0.2.1"))
    }

    @Test fun bundledHarnessFromTheAssetStamp() {
        assertEquals("0.2.1-rc.2", Versions.harnessOfAssetStamp("0.2.1-rc.2-1"))
        assertEquals("0.2.1", Versions.harnessOfAssetStamp("0.2.1-3"))
        assertEquals("0.2.1-rc.2", Versions.harnessOfAssetStamp("0.2.1-rc.2"))
        assertNull(Versions.harnessOfAssetStamp(""))
    }
}
