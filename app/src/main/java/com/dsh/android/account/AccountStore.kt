package com.dsh.android.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Which sign-in the user chose on the first screen, plus the DeepSeek web session.
 *
 * The web user token and the bridge secret are encrypted with an AES-GCM key that lives in the Android Keystore (never
 * leaves it). The API key itself is not stored here: it lives only in dsh's own `.credentials.yaml` (mode 600).
 */
class AccountStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var mode: AccountConfig.Mode
        get() = runCatching { AccountConfig.Mode.valueOf(prefs.getString(KEY_MODE, null) ?: "") }.getOrDefault(AccountConfig.Mode.NONE)
        set(value) { prefs.edit().putString(KEY_MODE, value.name).apply() }

    /** True until the user signed in or chose "Set up later" once. */
    fun needsFirstScreen(): Boolean = mode == AccountConfig.Mode.NONE && !prefs.getBoolean(KEY_LATER, false)

    fun setUpLater() { prefs.edit().putBoolean(KEY_LATER, true).apply() }

    /** Shown in Settings > Account (email or phone of the web account, never the token). */
    var webLabel: String?
        get() = prefs.getString(KEY_WEB_LABEL, null)
        set(value) { prefs.edit().putString(KEY_WEB_LABEL, value).apply() }

    fun webToken(): String? = decrypt(prefs.getString(KEY_WEB_TOKEN, null))

    fun saveWebToken(token: String) { prefs.edit().putString(KEY_WEB_TOKEN, encrypt(token)).apply() }

    /** Forget the account: back to the first screen on the next launch. */
    fun signOut() {
        prefs.edit().remove(KEY_WEB_TOKEN).remove(KEY_WEB_LABEL).remove(KEY_LATER)
            .putString(KEY_MODE, AccountConfig.Mode.NONE.name).apply()
    }

    /** Per-install secret the local bridge requires as its bearer token (64 hex chars, created once). */
    fun bridgeSecret(): String {
        decrypt(prefs.getString(KEY_BRIDGE_SECRET, null))?.let { return it }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val secret = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_BRIDGE_SECRET, encrypt(secret)).apply()
        return secret
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String?): String? {
        if (stored.isNullOrEmpty()) return null
        return runCatching {
            val sealed = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12))
            String(cipher.doFinal(sealed, 12, sealed.size - 12), Charsets.UTF_8)
        }.getOrNull()
    }

    private companion object {
        const val PREFS = "dsh_account"
        const val KEY_MODE = "mode"
        const val KEY_LATER = "set_up_later"
        const val KEY_WEB_TOKEN = "web_token"
        const val KEY_WEB_LABEL = "web_label"
        const val KEY_BRIDGE_SECRET = "bridge_secret"
        const val KEY_ALIAS = "dsh_account_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
