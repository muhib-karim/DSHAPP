package com.dsh.android.update

import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.MessageDigest

/** App updates from this fork's GitHub releases (pure parts: parsing and checks; download and install in UpdateCenter). */
object AppUpdater {
    const val RELEASES_URL = "https://api.github.com/repos/muhib-karim/DSHAPP/releases/latest"

    /** A release body is shown as at most this many characters; longer notes end with an ellipsis. */
    const val MAX_NOTES_CHARS = 4_000

    private const val RELEASE_PAGE_PREFIX = "/muhib-karim/DSHAPP/releases/"

    data class Release(val version: String, val apkName: String, val apkUrl: String, val apkSize: Long,
                       val sumsUrl: String?, val page: String, val notes: String)

    data class DownloadedApk(val release: Release, val file: File, val sha256: String) {
        fun isFor(candidate: Release) = file.isFile && release.version == candidate.version &&
            release.apkName == candidate.apkName && release.apkUrl == candidate.apkUrl

        /** Recheck before each installer handoff, without hashing the APK during UI polling. */
        fun verify(): Boolean = runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) } == sha256
        }.getOrDefault(false)
    }

    /** The latest published release and the APK of this flavor (`DSH<v>-mk.apk` / `DSH<v>-mk-gecko.apk`), or null. */
    fun parseRelease(json: String, gecko: Boolean): Release? = runCatching {
        val o = JSONObject(json)
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val version = Versions.normalise(o.optString("tag_name")).ifEmpty { return null }
        val assets = o.optJSONArray("assets") ?: return null
        val want = Regex("^DSH.+-mk${if (gecko) "-gecko" else ""}\\.apk$")
        var apk: JSONObject? = null
        var sums: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name")
            if (want.matches(name)) apk = a
            if (name == "SHA256SUMS.txt") sums = a.optString("browser_download_url").ifEmpty { null }
        }
        val chosen = apk ?: return null
        val url = chosen.optString("browser_download_url")
        if (!url.startsWith("https://")) return null
        Release(version, chosen.optString("name"), url, chosen.optLong("size"), sums, o.optString("html_url"), o.optString("body"))
    }.getOrNull()

    fun isUpdate(release: Release, currentVersionName: String) = Versions.isNewer(release.version, currentVersionName)

    /**
     * Release notes as safe plain text for the About card.
     *
     * GitHub bodies are Markdown, but the card never interprets them: normalise line endings, drop control
     * characters, keep at most one blank line between paragraphs, trim and cap the result. The cap leaves the
     * ellipsis inside [MAX_NOTES_CHARS] and never splits a surrogate pair.
     */
    fun displayNotes(notes: String): String {
        val normalised = notes.replace("\r\n", "\n").replace('\r', '\n')
        val cleaned = buildString(normalised.length) {
            var afterBlankLine = false
            for (raw in normalised.split('\n')) {
                val line = raw.filterNot { it.isISOControl() }.trimEnd()
                if (line.isBlank()) {
                    // At most one blank line, and none at the start or end.
                    if (isEmpty() || afterBlankLine) continue
                    afterBlankLine = true
                } else {
                    afterBlankLine = false
                }
                if (isNotEmpty()) append('\n')
                append(line)
            }
        }.trim()
        if (cleaned.length <= MAX_NOTES_CHARS) return cleaned
        var cut = MAX_NOTES_CHARS - 1
        if (cut > 0 && Character.isHighSurrogate(cleaned[cut - 1])) cut--
        return cleaned.substring(0, cut).trimEnd() + "\u2026"
    }

    /** True only for this fork's HTTPS release pages, with no credentials, port trick or path traversal. */
    fun isTrustedReleasePage(url: String): Boolean = runCatching {
        val uri = URI(url.trim())
        val path = uri.normalize().path ?: return false
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("github.com", ignoreCase = true) &&
            uri.userInfo == null && (uri.port == -1 || uri.port == 443) &&
            path.startsWith(RELEASE_PAGE_PREFIX) &&
            !path.contains("..") && !path.contains('%') && !path.contains('\\')
    }.getOrDefault(false)

    /**
     * Deletes everything left in the app-update cache except [keep] — the APK already handed to the system installer,
     * which may still be reading it. Returns how many entries were removed; a missing directory removes nothing.
     */
    fun cleanUpdateCache(dir: File, keep: File? = null): Int = runCatching {
        val keepPath = keep?.absolutePath
        dir.listFiles().orEmpty().count { file ->
            file.absolutePath != keepPath && runCatching { file.deleteRecursively() }.getOrDefault(false)
        }
    }.getOrDefault(0)

    /** The lowercase sha256 of [apkName] in a `sha256sum` listing, or null. */
    fun sha256For(sums: String, apkName: String): String? = sums.lineSequence().mapNotNull { line ->
        val m = Regex("^([0-9a-fA-F]{64})\\s+[*]?(?:\\./)?(\\S+)$").find(line.trim()) ?: return@mapNotNull null
        if (m.groupValues[2] == apkName) m.groupValues[1].lowercase() else null
    }.firstOrNull()
}
