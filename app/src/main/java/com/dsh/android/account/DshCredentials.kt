package com.dsh.android.account

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet

/**
 * dsh's own key store, `$DSH_HOME/.credentials.yaml` (`@deepseek-ai/dsh-credentials-local` 0.2.x).
 *
 * Format: `version: 1`, a `refs` map (credential reference → string) and a `records` map. dsh keeps its browser-session
 * signing secret in `records`, so every line outside the refs we set is kept byte for byte. dsh refuses to start when
 * the file has any group or other permission bit, so it is written owner-only (600), atomically (tmp + rename).
 *
 * Ported from muhib-karim/deepseek-android (feature/v1.3.0, `DshCredentials.java`, MIT), generalised to several refs.
 * Values are never logged.
 */
object DshCredentials {
    const val FILE_NAME = ".credentials.yaml"

    /** Read by the official provider `dsh-llm-deepseek-api-key` (`deepseek-official`). */
    const val DEEPSEEK_API_KEY = "DEEPSEEK_API_KEY"

    /** Per-install secret of the local web-account bridge (see `webbridge.WebAccount`). */
    const val WEB_BRIDGE_KEY = "DEEPSEEK_WEB_BRIDGE_KEY"

    fun file(dshHome: File) = File(dshHome, FILE_NAME)

    /** Why [key] cannot be a DeepSeek API key (shape only), or null. The message never echoes the key. */
    fun problem(key: String?): String? {
        val k = key?.trim().orEmpty()
        if (k.isEmpty()) return "Enter your DeepSeek API key."
        if (k.length < 8 || k.length > 512) return "That doesn't look like a DeepSeek API key."
        if (k.any { it <= ' ' || it == '"' || it == '\\' || it == '\'' || it > '~' }) {
            return "An API key has no spaces, quotes or line breaks."
        }
        return null
    }

    /** Sets refs (a null value removes the ref); other refs and all records are kept. */
    @Synchronized
    fun write(dshHome: File, refs: Map<String, String?>) {
        val set = refs.mapValues { it.value?.trim() }
        for ((name, value) in set) {
            require(NAME.matches(name)) { "bad ref name" }
            require(value == null || value.none { it < ' ' || it == '"' || it == '\\' }) { "bad ref value for $name" }
        }
        if (!dshHome.isDirectory && !dshHome.mkdirs() && !dshHome.isDirectory) throw IOException("cannot create $dshHome")
        val target = file(dshHome)
        val existing = if (target.isFile) target.readText() else null
        val body = render(existing, set)
        val tmp = File(dshHome, "$FILE_NAME.tmp-${System.nanoTime()}")
        Files.deleteIfExists(tmp.toPath())
        Files.createFile(tmp.toPath())
        ownerOnly(tmp)
        tmp.writeText(body)
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        ownerOnly(target)
    }

    /** The stored value of [name], or null. */
    fun read(dshHome: File, name: String): String? {
        val f = file(dshHome)
        if (!f.isFile) return null
        val lines = try { f.readLines() } catch (e: IOException) { return null }
        val refs = refsBlock(lines) ?: return null
        for (i in refs.first + 1 until refs.last + 1) {
            val m = REF_LINE.matchEntire(lines[i]) ?: continue
            if (unquote(m.groupValues[2]) == name) return unquote(m.groupValues[3].trim())
        }
        return null
    }

    /** The document with [set] applied to `refs` (null removes); every other line is kept as it was. */
    fun render(existing: String?, set: Map<String, String?>): String {
        val lines = if (existing == null || existing.isBlank() || existing.trim() == "{}") mutableListOf()
        else existing.trimEnd('\n').split("\n").toMutableList()
        if (lines.none { it.startsWith("version:") }) lines.add(0, "version: 1")
        var refs = refsBlock(lines)
        if (refs == null) {
            while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
            lines.add("refs:")
            refs = IntRange(lines.size - 1, lines.size - 1)
        }
        val header = refs.first
        val kept = mutableListOf<String>()
        for (i in header + 1..refs.last) {
            val m = REF_LINE.matchEntire(lines[i])
            if (m != null && unquote(m.groupValues[2]) in set.keys) continue
            kept += lines[i]
        }
        while (kept.isNotEmpty() && kept.last().isBlank()) kept.removeAt(kept.size - 1)
        for ((name, value) in set) if (value != null) kept += "  $name: \"$value\""
        val block = if (kept.none { it.isNotBlank() }) listOf("refs: {}") else listOf("refs:") + kept
        val out = lines.subList(0, header) + block + lines.subList(refs.last + 1, lines.size)
        return out.joinToString("\n").trimEnd('\n') + "\n"
    }

    /** Lines of the top-level `refs` mapping: header line .. last indented line (inclusive). */
    private fun refsBlock(lines: List<String>): IntRange? {
        val header = lines.indexOfFirst { it.trimEnd() == "refs:" || it.trimEnd() == "refs: {}" }
        if (header < 0) return null
        var last = header
        for (i in header + 1 until lines.size) {
            val line = lines[i]
            if (line.isNotBlank() && !line.startsWith(" ") && !line.startsWith("\t")) break
            last = i
        }
        while (last > header && lines[last].isBlank()) last--
        return IntRange(header, last)
    }

    private fun unquote(s: String): String =
        if (s.length >= 2 && (s.first() == '"' && s.last() == '"' || s.first() == '\'' && s.last() == '\'')) s.substring(1, s.length - 1) else s

    /** rw------- : Android's default file mode is 660, which dsh refuses. */
    private fun ownerOnly(f: File) {
        try {
            Files.setPosixFilePermissions(f.toPath(), EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        } catch (e: UnsupportedOperationException) {
            f.setReadable(false, false); f.setWritable(false, false); f.setExecutable(false, false)
            if (!f.setReadable(true, true) || !f.setWritable(true, true)) throw IOException("cannot make $f owner-only")
        }
    }

    private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val REF_LINE = Regex("^([ \\t]+)(\"[^\"]+\"|'[^']+'|[A-Za-z_][A-Za-z0-9_]*):(.*)$")
}
