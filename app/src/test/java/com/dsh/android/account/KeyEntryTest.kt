package com.dsh.android.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyEntryTest {
    @Test fun pasteTrimsSurroundingWhitespaceAndLineBreaks() {
        assertEquals("sk-0123456789abcdef", KeyEntry.fromClipboard("  sk-0123456789abcdef \n"))
        assertEquals("sk-0123456789abcdef", KeyEntry.fromClipboard("\r\nsk-0123456789abcdef\r\n"))
        assertEquals("sk-0123456789abcdef", KeyEntry.fromClipboard("\t sk-0123456789abcdef"))
    }

    @Test fun pasteLeavesTheFieldAloneWhenTheClipIsMissingOrBlank() {
        assertNull(KeyEntry.fromClipboard(null))
        assertNull(KeyEntry.fromClipboard(""))
        assertNull(KeyEntry.fromClipboard("  \n\t "))
    }

    @Test fun pasteKeepsEverythingInsideTheValue() {
        val withSpace = "sk-0123 4567"
        assertEquals(withSpace, KeyEntry.fromClipboard(" $withSpace \n"))
        assertEquals("sk-ключ-0123456789", KeyEntry.fromClipboard("sk-ключ-0123456789"))
        assertEquals("sk-\"quoted\"", KeyEntry.fromClipboard("sk-\"quoted\""))
        // The characters that make a key unusable are preserved so the inline error can name them.
        assertEquals(withSpace, KeyEntry.fromClipboard(withSpace))
    }

    @Test fun aPastedKeyWithATrailingNewlineIsAccepted() {
        val key = "sk-0123456789abcdef" // gitleaks:allow (fake test fixture)
        assertNull(DshCredentials.problem(KeyEntry.fromClipboard("$key\n")))
        assertEquals(key, KeyEntry.fromClipboard("$key\n"))
    }

    @Test fun submittedValueHasNoSurroundingWhitespace() {
        assertEquals("", KeyEntry.submitted(null))
        assertEquals("", KeyEntry.submitted(" \n "))
        assertEquals("sk-0123456789abcdef", KeyEntry.submitted(" sk-0123456789abcdef\n"))
    }

    @Test fun restoredErrorIsNullUnlessThereWasAMessage() {
        assertNull(KeyEntry.restoredError(null))
        assertNull(KeyEntry.restoredError(""))
        assertNull(KeyEntry.restoredError("  \n"))
        assertEquals("Enter your DeepSeek API key.", KeyEntry.restoredError(" Enter your DeepSeek API key. "))
    }
}
