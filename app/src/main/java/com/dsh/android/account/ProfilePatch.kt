package com.dsh.android.account

/**
 * Line-level edits of dsh's per-profile patch layer, `$DSH_HOME/profiles/web/cordis.patch.yml`: a top-level YAML
 * sequence of `- id: <row>` entries. dsh's own settings editor rewrites this file, so edits touch only the entry (or the
 * provider route) they own and keep every other line as it was. Indentation is read from the file, not assumed.
 */
object ProfilePatch {
    const val DEFAULT_MODEL_ID = "agent-default-model"
    const val DEFAULT_MODEL_PACKAGE = "@deepseek-ai/dsh-agent-default-model"
    const val PI_AI_ID = "llm-pi-ai"
    const val PI_AI_PACKAGE = "@deepseek-ai/dsh-llm-pi-ai"

    /** Provider of the `agent-default-model` row, or null when the row is absent. */
    fun defaultModelProvider(body: String): String? {
        val lines = body.lines()
        val block = entryBlock(lines, DEFAULT_MODEL_ID) ?: return null
        return block.drop(1).map { lines[it].trim() }.firstOrNull { it.startsWith("provider:") }
            ?.removePrefix("provider:")?.trim()?.let(::unquote)
    }

    /**
     * Points the default model at [provider]/[model]. When [replaceOnly] is given, an existing row is replaced only if
     * its provider is in that set (the app's own seed), so a model the user picked in Settings stays.
     */
    fun setDefaultModel(body: String, provider: String, model: String, replaceOnly: Set<String>?): String {
        val lines = body.lines().toMutableList()
        val block = entryBlock(lines, DEFAULT_MODEL_ID)
        if (block != null) {
            val current = defaultModelProvider(body)
            if (replaceOnly != null && current !in replaceOnly) return body
            val indent = indentOf(lines[block.first])
            val replacement = defaultModelEntry(indent, provider, model)
            if (lines.subList(block.first, block.last + 1) == replacement) return body
            repeat(block.last - block.first + 1) { lines.removeAt(block.first) }
            lines.addAll(block.first, replacement)
            return finish(lines)
        }
        return append(lines, defaultModelEntry(0, provider, model))
    }

    /** Adds or replaces `llm-pi-ai` → `config.providers.<route>` with [routeYaml] (a mapping written at indent 0). */
    fun upsertPiAiRoute(body: String, route: String, routeYaml: String): String {
        val routeLines = routeYaml.trimEnd().lines()
        val lines = body.lines().toMutableList()
        val entry = entryBlock(lines, PI_AI_ID)
            ?: return append(lines, listOf("- id: $PI_AI_ID", "  name: '$PI_AI_PACKAGE'", "  config:", "    providers:", "      $route:") +
                routeLines.map { "        $it".trimEnd() })
        val keyIndent = indentOf(lines[entry.first]) + 2
        val config = childLine(lines, entry, keyIndent, "config")
        if (config == null || lines[config].trim() == "config: {}") {
            val block = listOf("${pad(keyIndent)}config:", "${pad(keyIndent + 2)}providers:", "${pad(keyIndent + 4)}$route:") +
                routeLines.map { (pad(keyIndent + 6) + it).trimEnd() }
            if (config != null) lines.removeAt(config)
            lines.addAll(config ?: (entry.last + 1), block)
            return finish(lines)
        }
        val configBlock = childBlock(lines, config)
        val providersIndent = childIndent(lines, configBlock, indentOf(lines[config]))
        val providers = childLine(lines, configBlock, providersIndent, "providers")
        if (providers == null || lines[providers].trim() == "providers: {}") {
            val block = listOf("${pad(providersIndent)}providers:", "${pad(providersIndent + 2)}$route:") +
                routeLines.map { (pad(providersIndent + 4) + it).trimEnd() }
            if (providers != null) lines.removeAt(providers)
            lines.addAll(providers ?: (config + 1), block)
            return finish(lines)
        }
        val providersBlock = childBlock(lines, providers)
        val routeIndent = childIndent(lines, providersBlock, providersIndent)
        val newRoute = listOf("${pad(routeIndent)}$route:") + routeLines.map { (pad(routeIndent + 2) + it).trimEnd() }
        val existing = childLine(lines, providersBlock, routeIndent, route)
        if (existing != null) {
            val old = childBlock(lines, existing)
            if (lines.subList(old.first, old.last + 1) == newRoute) return body
            repeat(old.last - old.first + 1) { lines.removeAt(old.first) }
            lines.addAll(old.first, newRoute)
        } else {
            lines.addAll(providersBlock.last + 1, newRoute)
        }
        return finish(lines)
    }

    /** Removes `llm-pi-ai` → `config.providers.<route>`; an emptied providers map becomes `providers: {}`. */
    fun removePiAiRoute(body: String, route: String): String {
        val lines = body.lines().toMutableList()
        val entry = entryBlock(lines, PI_AI_ID) ?: return body
        val config = childLine(lines, entry, indentOf(lines[entry.first]) + 2, "config") ?: return body
        val configBlock = childBlock(lines, config)
        val providers = childLine(lines, configBlock, childIndent(lines, configBlock, indentOf(lines[config])), "providers") ?: return body
        val providersBlock = childBlock(lines, providers)
        val existing = childLine(lines, providersBlock, childIndent(lines, providersBlock, indentOf(lines[providers])), route) ?: return body
        val old = childBlock(lines, existing)
        repeat(old.last - old.first + 1) { lines.removeAt(old.first) }
        if (childBlock(lines, providers).last == providers) lines[providers] = lines[providers].trimEnd() + " {}"
        return finish(lines)
    }

    private fun defaultModelEntry(indent: Int, provider: String, model: String) = listOf(
        "${pad(indent)}- id: $DEFAULT_MODEL_ID",
        "${pad(indent)}  name: '$DEFAULT_MODEL_PACKAGE'",
        "${pad(indent)}  config:",
        "${pad(indent)}    provider: $provider",
        "${pad(indent)}    model: $model",
    )

    /** Appends an entry; drops the engine template's top-level `[]` (a flow `[]` followed by a block entry is invalid). */
    private fun append(lines: MutableList<String>, entry: List<String>): String {
        lines.indexOfFirst { it.trim() == "[]" && indentOf(it) == 0 }.takeIf { it >= 0 }?.let { lines.removeAt(it) }
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
        lines.addAll(entry)
        return finish(lines)
    }

    private fun finish(lines: List<String>) = lines.joinToString("\n").trimEnd('\n', ' ') + "\n"

    /** Lines of the `- id: <id>` entry: its first line .. its last non-blank line. */
    private fun entryBlock(lines: List<String>, id: String): IntRange? {
        val start = lines.indexOfFirst { isEntryIdLine(it, id) }
        if (start < 0) return null
        val indent = indentOf(lines[start])
        var last = start
        for (i in start + 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            if (indentOf(line) <= indent) break
            last = i
        }
        return IntRange(start, last)
    }

    /** A mapping key's line and the lines nested under it. */
    private fun childBlock(lines: List<String>, keyLine: Int): IntRange {
        val indent = indentOf(lines[keyLine])
        var last = keyLine
        for (i in keyLine + 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            if (indentOf(line) <= indent) break
            last = i
        }
        return IntRange(keyLine, last)
    }

    /** Indent of the children inside [block] (whose header has [parentIndent]); parent + 2 when it has none. */
    private fun childIndent(lines: List<String>, block: IntRange, parentIndent: Int): Int =
        (block.first + 1..block.last).map { lines[it] }.firstOrNull { it.isNotBlank() && !it.trimStart().startsWith("#") }
            ?.let(::indentOf)?.takeIf { it > parentIndent } ?: (parentIndent + 2)

    private fun childLine(lines: List<String>, block: IntRange, indent: Int, key: String): Int? {
        for (i in block.first..block.last) {
            val line = lines[i]
            if (indentOf(line) != indent) continue
            val t = line.trim().removePrefix("- ").trimStart()
            if (t.startsWith("$key:") || t.startsWith("\"$key\":") || t.startsWith("'$key':")) return i
        }
        return null
    }

    private fun isEntryIdLine(line: String, id: String): Boolean {
        val t = line.trimStart()
        if (!t.startsWith("-")) return false
        val rest = t.removePrefix("-").trimStart()
        if (!rest.startsWith("id:")) return false
        return unquote(rest.removePrefix("id:").substringBefore(" #").trim()) == id
    }

    private fun indentOf(line: String) = line.length - line.trimStart(' ').length
    private fun pad(n: Int) = " ".repeat(n)
    private fun unquote(s: String) =
        if (s.length >= 2 && (s.first() == '"' && s.last() == '"' || s.first() == '\'' && s.last() == '\'')) s.substring(1, s.length - 1) else s
}
