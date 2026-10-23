package com.dsh.android.account

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import com.dsh.android.MainActivity
import com.dsh.android.R
import com.dsh.android.log.DshLog

/**
 * Long-press launcher shortcut "DeepSeek account": opens the app with the sign-in screen on top in switch mode
 * (sign in again, change the web account or the API key). A dynamic shortcut, so its intents follow the real
 * applicationId of each flavor.
 */
object AccountShortcut {
    /** Set on the MainActivity intent of the shortcut: the first-screen gate stays quiet, the shortcut opens sign-in. */
    const val EXTRA_FROM_SHORTCUT = "accountShortcut"
    private const val ID = "deepseek-account"

    fun publish(context: Context) {
        runCatching {
            val manager = context.getSystemService(ShortcutManager::class.java) ?: return
            val main = Intent(Intent.ACTION_MAIN).setClass(context, MainActivity::class.java)
                .putExtra(EXTRA_FROM_SHORTCUT, true)
            val signIn = Intent(Intent.ACTION_VIEW).setClass(context, SignInActivity::class.java)
                .putExtra(SignInActivity.EXTRA_SWITCH, true)
            val label = context.getString(R.string.shortcut_account)
            val info = ShortcutInfo.Builder(context, ID)
                .setShortLabel(label)
                .setLongLabel(label)
                .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
                .setIntents(arrayOf(main, signIn))
                .build()
            manager.dynamicShortcuts = listOf(info)
        }.onFailure { DshLog.w(com.dsh.android.DshApplication.TAG, "account shortcut not published: ${it.message}") }
    }
}
