package com.dsh.android.update

import org.json.JSONObject

/** App updates from this fork's GitHub releases (pure parts: parsing and checks; download and install in UpdateCenter). */
object AppUpdater {
    const val RELEASES_URL = "https://api.github.com/repos/muhib-karim/DSHAPP/releases/latest"

    data class Release(val version: String, val apkName: String, val apkUrl: String, val apkSize: Long,
                       val sumsUrl: String?, val page: String, val notes: String)

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

    /** The lowercase sha256 of [apkName] in a `sha256sum` listing, or null. */
    fun sha256For(sums: String, apkName: String): String? = sums.lineSequence().mapNotNull { line ->
        val m = Regex("^([0-9a-fA-F]{64})\\s+[*]?(?:\\./)?(\\S+)$").find(line.trim()) ?: return@mapNotNull null
        if (m.groupValues[2] == apkName) m.groupValues[1].lowercase() else null
    }.firstOrNull()
}
