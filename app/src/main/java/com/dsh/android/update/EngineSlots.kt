package com.dsh.android.update

import org.json.JSONObject
import java.io.File

/**
 * The harness on disk: `engine/` (running), `engine.next/` (being installed), `engine.prev/` (kept for rollback).
 *
 * A promoted harness stays "pending" until the engine reaches Ready once ([confirm]); a pending harness that fails to
 * boot is swapped back ([rollback]) and its version is never offered again. Pure file operations (JVM-tested).
 */
class EngineSlots(private val filesDir: File) {
    val engine = File(filesDir, "engine")
    val next = File(filesDir, "engine.next")
    val prev = File(filesDir, "engine.prev")
    private val pendingFile = File(filesDir, "engine.pending")
    private val badFile = File(filesDir, "engine.bad")

    /** `@deepseek-ai/dsh` version inside [dir]'s node_modules, or null. */
    fun versionIn(dir: File): String? = runCatching {
        JSONObject(File(dir, "node_modules/@deepseek-ai/dsh/package.json").readText()).optString("version").ifEmpty { null }
    }.getOrNull()

    fun installedVersion(): String? = versionIn(engine)

    /** `.stamp` of [dir] (`npm:<version>` for a harness installed by the updater). */
    fun stampOf(dir: File): String? = runCatching { File(dir, ".stamp").readText().trim() }.getOrNull()

    /** True when [dir] has a runnable harness (the entry the app launches). */
    fun isComplete(dir: File) = File(dir, "node_modules/@deepseek-ai/dsh/lib/bin.js").isFile

    /** Makes [next] the running harness, keeping the current one as [prev]; false (nothing changed) if [next] is incomplete. */
    fun promote(): Boolean {
        if (!isComplete(next)) return false
        val version = versionIn(next) ?: return false
        prev.deleteRecursively()
        if (engine.exists() && !engine.renameTo(prev)) return false
        if (!next.renameTo(engine)) {
            prev.renameTo(engine)
            return false
        }
        pendingFile.writeText(version)
        return true
    }

    /** Version promoted but not yet seen booting, or null. */
    fun pending(): String? = runCatching { pendingFile.readText().trim().ifEmpty { null } }.getOrNull()

    /** The promoted harness booted: it is the harness now ([prev] stays for a manual rollback until the next update). */
    fun confirm() { pendingFile.delete() }

    /** Restores [prev] after a pending harness failed to boot; its version is recorded as bad. False without [prev]. */
    fun rollback(): Boolean {
        val failed = pending() ?: installedVersion()
        if (!isComplete(prev)) return false
        val broken = File(filesDir, "engine.failed")
        broken.deleteRecursively()
        if (engine.exists() && !engine.renameTo(broken)) engine.deleteRecursively()
        if (!prev.renameTo(engine)) return false
        broken.deleteRecursively()
        pendingFile.delete()
        failed?.let(::markBad)
        return true
    }

    fun badVersions(): Set<String> = runCatching { badFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet() }.getOrDefault(emptySet())

    fun markBad(version: String) {
        if (version in badVersions()) return
        badFile.appendText(version + "\n")
    }
}
