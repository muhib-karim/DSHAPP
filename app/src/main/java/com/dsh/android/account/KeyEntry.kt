package com.dsh.android.account

/**
 * Pure text handling for the first screen's API-key panel.
 *
 * The panel keeps the key in the field; this object only decides what an explicit Paste tap may put there and what
 * Continue submits. It never stores, persists or logs a key.
 */
object KeyEntry {
    /**
     * The text a Paste tap puts in the field, or null when the field must stay untouched (no clip, a non-text clip or
     * blank text). Surrounding whitespace and line breaks are removed because keys copied from a terminal or a web page
     * often arrive as `"sk-…\n"`. Characters inside the value are kept as they are, so a value that really contains a
     * space still reaches [DshCredentials.problem] and gets an explanation instead of being silently rewritten.
     */
    fun fromClipboard(clip: CharSequence?): String? {
        val text = clip?.toString()?.trim().orEmpty()
        return text.ifEmpty { null }
    }

    /** The value submitted from the field; the same trim [DshCredentials.problem] and the credentials writer apply. */
    fun submitted(text: CharSequence?): String = text?.toString()?.trim().orEmpty()

    /**
     * The inline error to restore after a rotation, if any. Only the message the user already saw is kept; it is never
     * combined with, or derived from, the key.
     */
    fun restoredError(message: CharSequence?): String? = message?.toString()?.trim()?.takeIf { it.isNotEmpty() }
}
