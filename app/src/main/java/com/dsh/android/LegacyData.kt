package com.dsh.android

import android.content.Context
import com.dsh.android.log.DshLog
import java.io.File

/**
 * 1.3.2-en: one clean first launch over data another build of this package left behind (the fork's 1.3.1-en shares the
 * applicationId and signing key, so its files stay in place on update).
 *
 * Runs once per data generation, before the engine stamp is read ([EngineManager.ensureExtracted]):
 * - drops `engine/.stamp`, so the bundled engine is unpacked once more (1.3.1 left an extra runtime patch in `engine/`
 *   under the same stamp), and the harness-update slots 1.3.0/1.3.1 kept next to it;
 * - drops 1.3.1's browser-plugin overlays, which this build never mounts;
 * - removes a preference this build reads when it holds a value of another type (it would throw ClassCastException).
 *
 * Never touched: sessions, `.credentials.yaml`, the workspace, profiles, `dsh_account` (the web sign-in carries over).
 * Every step is best effort; a fresh install has nothing to remove.
 */
internal object LegacyData {
    private const val GENERATION = "1.3.2"
    private const val MARKER = ".data_gen"

    /** Preference files and keys this build reads, with the type it reads them as. */
    private val PREF_TYPES: Map<String, Map<String, Class<*>>> = mapOf(
        BubbleAppearance.PREFS to mapOf(
            "enabled" to java.lang.Boolean::class.java,
            "x" to java.lang.Integer::class.java,
            "y" to java.lang.Integer::class.java,
            "panel_w" to java.lang.Integer::class.java,
            "transcript_h" to java.lang.Integer::class.java,
            BubbleAppearance.KEY_APPEARANCE to java.lang.String::class.java,
        ),
        SystemBarAppearance.PREFS to mapOf(SystemBarAppearance.KEY_APPEARANCE to java.lang.String::class.java),
        "dsh-adb-pairing" to mapOf("pending" to java.lang.Boolean::class.java),
    )

    fun migrateOnce(context: Context, dshHome: File) {
        val marker = File(context.filesDir, MARKER)
        if (runCatching { marker.readText().trim() }.getOrNull() == GENERATION) return
        val files = context.filesDir
        step("engine stamp") { File(files, "engine/.stamp").delete() }
        for (name in listOf("engine.prev", "engine.next", "engine.staging")) step(name) { File(files, name).deleteRecursively() }
        for (name in listOf("engine.pending", "engine.bad", "engine.transaction", "engine.failed")) step(name) { File(files, name).delete() }
        for (name in listOf("browser-tool.patch.yml", "browser-pane.patch.yml")) step(name) { File(dshHome, name).delete() }
        for (name in listOf("browser-tool", "browser-pane")) step(name) { File(dshHome, name).deleteRecursively() }
        step("preferences") { resetMistypedPrefs(context) }
        step("marker") { marker.writeText(GENERATION) }
        DshLog.i(DshApplication.TAG, "legacy data: generation $GENERATION applied")
    }

    private fun resetMistypedPrefs(context: Context) {
        for ((file, keys) in PREF_TYPES) {
            val prefs = context.getSharedPreferences(file, Context.MODE_PRIVATE)
            val all = prefs.all
            val wrong = keys.filter { (key, type) -> all[key]?.let { !type.isInstance(it) } ?: false }.keys
            if (wrong.isNotEmpty()) {
                prefs.edit().apply { wrong.forEach { remove(it) } }.apply()
                DshLog.w(DshApplication.TAG, "legacy data: reset $file ${wrong.joinToString()}")
            }
        }
        // dsh_ime holds one Int per input method.
        val ime = context.getSharedPreferences(MainActivity.IME_PREFS, Context.MODE_PRIVATE)
        val wrongIme = ime.all.filterValues { it !is Int }.keys
        if (wrongIme.isNotEmpty()) {
            ime.edit().apply { wrongIme.forEach { remove(it) } }.apply()
            DshLog.w(DshApplication.TAG, "legacy data: reset ${MainActivity.IME_PREFS} (${wrongIme.size})")
        }
    }

    private fun step(name: String, block: () -> Unit) {
        runCatching(block).onFailure { DshLog.w(DshApplication.TAG, "legacy data: $name failed: ${it.message}") }
    }
}
