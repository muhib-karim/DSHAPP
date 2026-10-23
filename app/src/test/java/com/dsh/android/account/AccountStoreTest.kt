package com.dsh.android.account

import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountStoreTest {
    private val prefs = FakePreferences()
    private val store = AccountStore(prefs)

    @Test fun expiredWebSessionIsRecordedOnceAndCanBeCleared() {
        assertFalse(store.webExpired)
        store.markWebExpired()
        assertTrue(store.webExpired)
        assertTrue("the flag must survive a new store over the same preferences", AccountStore(prefs).webExpired)
        store.clearWebExpired()
        assertFalse(store.webExpired)
    }

    @Test fun fullSignOutForgetsTheExpiredSession() {
        store.markWebExpired()
        store.signOut()
        assertFalse(store.webExpired)
        assertFalse(AccountStore(prefs).webExpired)
    }

    @Test fun switchingToAnApiKeyForgetsTheExpiredSession() {
        store.webLabel = "someone@example.com"
        store.markWebExpired()
        store.signOutWeb()
        assertFalse(store.webExpired)
        assertFalse(AccountStore(prefs).webExpired)
    }

    /** JVM tests cannot touch the Android Keystore, so only the flag paths are exercised here. */
    private class FakePreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()

        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue

        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (values[key] as? MutableSet<String>) ?: defValues

        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue

        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue

        override fun contains(key: String): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val puts = mutableMapOf<String, Any?>()
            private val removes = mutableSetOf<String>()
            private var clear = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                puts[key] = value
                return this
            }

            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor {
                puts[key] = values
                return this
            }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor {
                puts[key] = value
                return this
            }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor {
                puts[key] = value
                return this
            }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
                puts[key] = value
                return this
            }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                puts[key] = value
                return this
            }

            override fun remove(key: String): SharedPreferences.Editor {
                removes += key
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clear = true
                return this
            }

            override fun commit(): Boolean {
                flush()
                return true
            }

            override fun apply() = flush()

            private fun flush() {
                if (clear) values.clear()
                removes.forEach { values.remove(it) }
                values.putAll(puts)
            }
        }
    }
}
