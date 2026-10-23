package com.dsh.android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkIdentityTest {
    private val pkg = "com.mkdsh.droid"
    private val appSigner = "a".repeat(64)
    private val otherSigner = "b".repeat(64)

    @Test fun matchingPackageAndSignerAreAccepted() {
        assertNull(ApkIdentity.problem(pkg, setOf(appSigner), pkg, listOf(appSigner)))
    }

    @Test fun anyCurrentSignerIsEnoughAfterKeyRotation() {
        assertNull(ApkIdentity.problem(pkg, setOf(appSigner, otherSigner), pkg, listOf(otherSigner)))
    }

    @Test fun anotherPackageIsRejected() {
        assertEquals("the APK belongs to another package",
            ApkIdentity.problem(pkg, setOf(appSigner), "com.mkdsh.gecko", listOf(appSigner)))
        assertNotNull(ApkIdentity.problem(pkg, setOf(appSigner), null, listOf(appSigner)))
        assertNotNull(ApkIdentity.problem(pkg, setOf(appSigner), "", listOf(appSigner)))
    }

    @Test fun anotherSignerIsRejected() {
        assertEquals("the APK is signed by another key",
            ApkIdentity.problem(pkg, setOf(appSigner), pkg, listOf(otherSigner)))
    }

    @Test fun missingArchiveSignersAreRejected() {
        assertEquals("the downloaded APK is not signed", ApkIdentity.problem(pkg, setOf(appSigner), pkg, null))
        assertEquals("the downloaded APK is not signed", ApkIdentity.problem(pkg, setOf(appSigner), pkg, emptyList()))
        assertEquals("the downloaded APK is not signed", ApkIdentity.problem(pkg, setOf(appSigner), pkg, listOf("  ")))
    }

    @Test fun anEmptyInstalledSignerSetIsRejected() {
        assertEquals("the installed signing certificate is unavailable",
            ApkIdentity.problem(pkg, emptySet(), pkg, listOf(appSigner)))
        assertEquals("the installed signing certificate is unavailable",
            ApkIdentity.problem(pkg, setOf(" "), pkg, listOf(appSigner)))
    }

    @Test fun anUnknownInstalledPackageIsRejected() {
        assertNotNull(ApkIdentity.problem("", setOf(appSigner), pkg, listOf(appSigner)))
    }

    @Test fun digestIsLowercaseHex() {
        // SHA-256("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ApkIdentity.certificateSha256("abc".toByteArray()))
        val digest = ApkIdentity.certificateSha256(byteArrayOf(0, 1, 2, 3))
        assertEquals(64, digest.length)
        assertTrue(digest.all { it in "0123456789abcdef" })
    }

    @Test fun certificateComparisonsIgnoreCaseAndSurroundingSpace() {
        assertNull(ApkIdentity.problem(pkg, setOf(" ${appSigner.uppercase()} "), pkg, listOf(appSigner)))
    }

    @Test fun theInvalidMessageIsTheOneTheAboutCardShows() {
        assertEquals("The downloaded APK is not a valid DSH update.", ApkIdentity.INVALID_MESSAGE)
    }
}
