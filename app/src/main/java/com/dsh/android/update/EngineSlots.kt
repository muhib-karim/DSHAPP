package com.dsh.android.update

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The harness on disk: `engine/` (running), `engine.next/` (being installed), `engine.prev/` (kept for rollback).
 *
 * A promoted harness stays "pending" until the engine reaches Ready once ([confirm]); a pending harness that fails to
 * boot is swapped back ([rollback]) and its version is never offered again. Pure file operations (JVM-tested).
 */
class EngineSlots(
    private val filesDir: File,
    private val move: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
) {
    val engine = File(filesDir, "engine")
    val next = File(filesDir, "engine.next")
    val prev = File(filesDir, "engine.prev")
    private val pendingFile = File(filesDir, "engine.pending")
    private val badFile = File(filesDir, "engine.bad")
    private val transactionFile = File(filesDir, "engine.transaction")
    private val broken = File(filesDir, "engine.failed")

    /** `@deepseek-ai/dsh` version inside [dir]'s node_modules, or null. */
    fun versionIn(dir: File): String? = runCatching {
        JSONObject(File(dir, "node_modules/@deepseek-ai/dsh/package.json").readText()).optString("version").ifEmpty { null }
    }.getOrNull()

    fun installedVersion(): String? = versionIn(engine)

    /** `.stamp` of [dir] (`npm:<version>` for a harness installed by the updater). */
    fun stampOf(dir: File): String? = runCatching { File(dir, ".stamp").readText().trim() }.getOrNull()

    /** True when [dir] has a runnable harness (the entry the app launches). */
    fun isComplete(dir: File) = File(dir, "node_modules/@deepseek-ai/dsh/lib/bin.js").isFile

    /** Promote only a complete candidate, journaling before the first destructive operation. */
    fun promote(): Boolean = synchronized(lock) {
        recoverInterrupted()
        if (pending() != null || !isComplete(next)) return@synchronized false
        val version = versionIn(next) ?: return@synchronized false
        writeTransaction("promote", version, installedVersion())
        if (!prev.deleteRecursively()) throw IOException("Could not clear the previous harness slot")
        if (engine.exists() && !move(engine, prev)) {
            clearTransaction()
            return@synchronized false
        }
        if (!move(next, engine)) {
            if (!prev.exists() || move(prev, engine)) clearTransaction()
            return@synchronized false
        }
        atomicWrite(pendingFile, version)
        clearTransaction()
        true
    }

    /** Version promoted but not yet seen booting, or null. */
    fun pending(): String? = runCatching { pendingFile.readText().trim().ifEmpty { null } }.getOrNull()

    /**
     * The complete previous harness that [rollback] would restore, or null.
     *
     * Read-only: an install ([next]) or a promoted harness that has not booted yet ([pending]) owns the rollback
     * window, so Settings must not offer the old version until that update settles. A transaction in progress also
     * blocks the offer: the engine that owns it recovers the interrupted rename first.
     */
    fun rollbackCandidate(): File? = synchronized(lock) {
        if (pending() != null || next.exists() || transactionFile.exists()) return@synchronized null
        prev.takeIf { isComplete(it) && versionIn(it) != null }
    }

    /** Version in [prev] when it can still be restored by hand, or null. */
    fun previousVersion(): String? = rollbackCandidate()?.let { versionIn(it) }

    /** The promoted harness booted: it is the harness now ([prev] stays for a manual rollback until the next update). */
    fun confirm() = synchronized(lock) {
        if (pendingFile.exists() && !pendingFile.delete()) throw IOException("Could not confirm the harness")
    }

    /** Restores [prev] after a pending harness failed to boot; its version is recorded as bad. False without [prev]. */
    fun rollback(): Boolean = synchronized(lock) {
        val failed = pending() ?: installedVersion()
        val previous = versionIn(prev)
        if (!isComplete(prev) || failed == null || previous == null) return@synchronized false
        writeTransaction("rollback", failed, previous)
        if (!broken.deleteRecursively()) return@synchronized false
        if (engine.exists() && !move(engine, broken)) return@synchronized false
        if (!move(prev, engine)) {
            // A failed restore must retain both complete trees; never delete the candidate as compensation.
            if (broken.exists()) move(broken, engine)
            return@synchronized false
        }
        finishRollback(failed)
        true
    }

    /** Finish a process-interrupted rename sequence before the app considers extracting its bundled engine. */
    fun recoverInterrupted() = synchronized(lock) {
        if (!transactionFile.exists()) return@synchronized
        val journal = JSONObject(transactionFile.readText())
        val version = journal.getString("version")
        val previous = journal.optString("previous").ifEmpty { null }
        when (journal.getString("operation")) {
            "promote" -> {
                when {
                    isComplete(engine) && installedVersion() == version -> atomicWrite(pendingFile, version)
                    isComplete(engine) && installedVersion() == previous -> confirm()
                    !engine.exists() && isComplete(prev) -> {
                        if (!move(prev, engine)) throw IOException("Could not restore the interrupted harness promotion")
                        confirm()
                    }
                    !engine.exists() && previous == null && isComplete(next) -> {
                        if (!move(next, engine)) throw IOException("Could not finish the interrupted harness promotion")
                        atomicWrite(pendingFile, version)
                    }
                    else -> throw IOException("Interrupted harness promotion needs recovery; previous files were preserved")
                }
                clearTransaction()
            }
            "rollback" -> {
                if (!(isComplete(engine) && installedVersion() == previous)) {
                    if (!isComplete(prev)) throw IOException("The previous harness is unavailable for recovery")
                    if (engine.exists()) {
                        if (!broken.deleteRecursively() || !move(engine, broken)) throw IOException("Could not move the failed harness aside")
                    }
                    if (!move(prev, engine)) {
                        if (broken.exists()) move(broken, engine)
                        throw IOException("Could not restore the previous harness")
                    }
                }
                finishRollback(version)
            }
            else -> throw IOException("Unknown harness transaction")
        }
    }

    private fun writeTransaction(operation: String, version: String, previous: String?) {
        atomicWrite(transactionFile, JSONObject().put("operation", operation).put("version", version)
            .put("previous", previous ?: "").toString())
    }

    private fun finishRollback(failed: String) {
        markBad(failed)
        confirm()
        clearTransaction()
        broken.deleteRecursively()
    }

    private fun clearTransaction() {
        if (transactionFile.exists() && !transactionFile.delete()) throw IOException("Could not finish the harness transaction")
    }

    private fun atomicWrite(file: File, value: String) {
        val temporary = File(filesDir, file.name + ".tmp")
        FileOutputStream(temporary).use { output -> output.write(value.toByteArray(Charsets.UTF_8)); output.fd.sync() }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    fun badVersions(): Set<String> = runCatching { badFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet() }.getOrDefault(emptySet())

    fun markBad(version: String) = synchronized(lock) {
        val versions = badVersions()
        if (version !in versions) atomicWrite(badFile, (versions + version).joinToString("\n", postfix = "\n"))
    }

    companion object {
        // Slots are opened by both the engine and the update center. All instances share the rename lock.
        private val lock = Any()
    }
}
