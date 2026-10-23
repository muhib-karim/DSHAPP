package com.dsh.android.update

import org.json.JSONObject
import java.io.File

/**
 * In-app update of the DSH harness (`@deepseek-ai/dsh` from npm). The new version is installed with the bundled pnpm
 * into `engine.next/` (hoisted layout like the bundled `engine.bin`, no install scripts: the app's own Android patch
 * steps run on the new tree at launch), then promoted with the old one kept for rollback ([EngineSlots]).
 */
class HarnessUpdater(private val slots: EngineSlots) {

    /** [latest] when it is newer than [current] and has not failed to boot here before; otherwise null. */
    fun offer(current: String?, latest: String?): String? {
        if (current == null || latest == null) return null
        if (!Versions.isNewer(latest, current)) return null
        return latest.takeIf { it !in slots.badVersions() }
    }

    /** Empties `engine.next/` and writes the package that pnpm installs. */
    fun prepareNext(version: String) {
        slots.next.deleteRecursively()
        slots.next.mkdirs()
        File(slots.next, "package.json").writeText(
            JSONObject().put("name", "dsh-android-engine").put("private", true)
                .put("dependencies", JSONObject().put(PACKAGE, version)).toString(2) + "\n"
        )
        File(slots.next, ".npmrc").writeText(
            "node-linker=hoisted\nignore-scripts=true\nauto-install-peers=true\nstrict-peer-dependencies=false\n" +
                "fund=false\nupdate-notifier=false\n"
        )
    }

    /** Null when `engine.next/` holds [version] ready to launch (and stamps it); otherwise why not. */
    fun finishNext(version: String): String? {
        if (!slots.isComplete(slots.next)) return "the install did not produce @deepseek-ai/dsh/lib/bin.js"
        val got = slots.versionIn(slots.next)
        if (got != version) return "installed $got instead of $version"
        File(slots.next, ".stamp").writeText("npm:$version")
        return null
    }

    companion object {
        const val PACKAGE = "@deepseek-ai/dsh"
        const val REGISTRY_URL = "https://registry.npmjs.org/@deepseek-ai%2fdsh"
        const val DAY_MS = 24L * 60 * 60 * 1000

        fun latestFromRegistry(json: String): String? = runCatching {
            JSONObject(json).getJSONObject("dist-tags").getString("latest").ifBlank { null }
        }.getOrNull()

        /** At most once a day (and again if the clock moved back). */
        fun shouldCheck(now: Long, last: Long) = last <= 0 || now < last || now - last >= DAY_MS

        /** pnpm arguments, run in `engine.next/`. */
        fun pnpmArgs() = listOf("install", "--prod", "--ignore-scripts", "--config.node-linker=hoisted", "--reporter=append-only")

        /**
         * Whether an updater-installed harness (stamp `npm:<v>`) stays when the app starts with bundled [assetStamp]:
         * only while it is newer than the bundled harness (an APK with a newer engine replaces it).
         */
        fun keepInstalled(stamp: String?, assetStamp: String): Boolean {
            if (stamp == null || !stamp.startsWith("npm:")) return false
            val bundled = Versions.harnessOfAssetStamp(assetStamp) ?: return true
            return Versions.isNewer(stamp, bundled)
        }
    }
}
