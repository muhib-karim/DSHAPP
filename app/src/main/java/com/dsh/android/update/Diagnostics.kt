package com.dsh.android.update

/**
 * The non-sensitive build/runtime labels Settings > About shows behind an update card: Android release/SDK, device
 * manufacturer and model, and the first supported ABI. Nothing here identifies the user or the install — no serial
 * numbers, accounts, file paths or hashes — so the page may render it and a support reply may quote it.
 */
object Diagnostics {
    /** "14 (SDK 34)"; the SDK alone when the release string is missing; null when neither is known. */
    fun android(release: String?, sdk: Int): String? {
        val name = clean(release)
        return when {
            name != null && sdk > 0 -> "$name (SDK $sdk)"
            name != null -> name
            sdk > 0 -> "SDK $sdk"
            else -> null
        }
    }

    /** "Google Pixel 8"; the model alone when it already starts with (or lacks) the manufacturer; null when unknown. */
    fun device(manufacturer: String?, model: String?): String? {
        val make = clean(manufacturer)
        val name = clean(model)
        return when {
            name == null -> make
            make == null -> name
            name.startsWith(make, ignoreCase = true) -> name
            else -> "$make $name"
        }
    }

    /** The primary ABI (`arm64-v8a`), or null when the platform reports none. */
    fun abi(supported: Array<String>?): String? =
        supported?.firstOrNull { clean(it) != null }?.trim()

    private fun clean(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() && !it.equals("unknown", ignoreCase = true) }
}
