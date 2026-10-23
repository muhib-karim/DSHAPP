package com.dsh.android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Settings > About diagnostics: readable labels only, never a serial, account, path or hash. */
class DiagnosticsTest {
    @Test
    fun androidShowsReleaseAndSdk() {
        assertEquals("14 (SDK 34)", Diagnostics.android("14", 34))
        assertEquals("14", Diagnostics.android("14", 0))
        assertEquals("SDK 34", Diagnostics.android("", 34))
        assertEquals("SDK 34", Diagnostics.android(null, 34))
        assertNull(Diagnostics.android(null, 0))
    }

    @Test
    fun blankAndPlaceholderValuesAreDropped() {
        assertNull(Diagnostics.android("  ", 0))
        assertNull(Diagnostics.android("unknown", 0))
        assertNull(Diagnostics.device("unknown", "  "))
    }

    @Test
    fun deviceJoinsManufacturerAndModelOnce() {
        assertEquals("Google Pixel 8", Diagnostics.device("Google", "Pixel 8"))
        assertEquals("Xiaomi 14", Diagnostics.device("Xiaomi", "Xiaomi 14"))
        // A model that already names its maker is kept once, verbatim.
        assertEquals("samsung SM-S918B", Diagnostics.device("Samsung", "samsung SM-S918B"))
        assertEquals("Pixel 8", Diagnostics.device(null, "Pixel 8"))
        assertEquals("Google", Diagnostics.device("Google", null))
        assertNull(Diagnostics.device(null, null))
    }

    @Test
    fun abiUsesThePrimarySupportedValue() {
        assertEquals("arm64-v8a", Diagnostics.abi(arrayOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("x86_64", Diagnostics.abi(arrayOf("", "x86_64")))
        assertNull(Diagnostics.abi(emptyArray()))
        assertNull(Diagnostics.abi(null))
    }
}
