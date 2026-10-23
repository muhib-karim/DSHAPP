package com.dsh.android.update

import java.security.MessageDigest

/**
 * Pure identity checks for a downloaded update APK, before it is handed to the system installer.
 *
 * HTTPS plus the release checksum proves *what* was downloaded; it does not prove that the archive is this app.
 * A wrong-flavor APK (`com.mkdsh.gecko` in the system build) or an APK signed with another key would otherwise be
 * accepted by the downloader and fail late in the installer, leaving the About card in a misleading state.
 *
 * [problem] takes already-extracted values so it can be unit-tested on the JVM; Android extraction lives in
 * `UpdateCenter`. The rules: the archive declares the same package name as the installed app, and at least one of
 * its signing certificates is one of the app's current signers (key-rotation history included).
 */
object ApkIdentity {
    /** User-facing, retry-safe message. It names neither paths nor certificates. */
    const val INVALID_MESSAGE = "The downloaded APK is not a valid DSH update."

    /** Lowercase hex SHA-256 of a signing certificate (the same form Android reports for package signers). */
    fun certificateSha256(certificate: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(certificate).joinToString("") { "%02x".format(it) }

    /**
     * Null when the archive may replace [expectedPackage]; otherwise a short, log-safe reason. [installedCertificates]
     * and [archiveCertificates] are lowercase (or any-case) SHA-256 digests of the signing certificates.
     */
    fun problem(
        expectedPackage: String,
        installedCertificates: Set<String>,
        archivePackage: String?,
        archiveCertificates: Collection<String>?,
    ): String? {
        if (expectedPackage.isBlank()) return "the installed package name is unknown"
        if (archivePackage.isNullOrBlank()) return "the download is not an APK"
        if (archivePackage != expectedPackage) return "the APK belongs to another package"
        val installed = installedCertificates.mapNotNull { it.trim().lowercase().ifEmpty { null } }.toSet()
        if (installed.isEmpty()) return "the installed signing certificate is unavailable"
        val archive = archiveCertificates.orEmpty().mapNotNull { it.trim().lowercase().ifEmpty { null } }.toSet()
        if (archive.isEmpty()) return "the downloaded APK is not signed"
        if (archive.none { it in installed }) return "the APK is signed by another key"
        return null
    }
}
