package com.dsh.android

import android.app.Activity
import android.content.Intent
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.appcompat.app.AlertDialog
import com.dsh.android.account.AccountChanges
import com.dsh.android.account.AccountConfig
import com.dsh.android.account.AccountStore
import com.dsh.android.account.DshCredentials
import com.dsh.android.account.SignInActivity
import com.dsh.android.log.DshLog
import com.dsh.android.webbridge.DeepSeekWebClient
import com.dsh.android.webbridge.WebAccount
import org.json.JSONObject

/**
 * Settings > Account (ported from v1.3.0) through the page bridge: [json] is the snapshot behind
 * `DSHAndroid.appState()`, [action] runs `DSHAndroid.appAction(name, arg)` on the main thread.
 */
object AppState {
    fun json(activity: Activity): String {
        val store = AccountStore(activity)
        val account = JSONObject().put("mode", store.mode.name.lowercase())
        if (store.mode == AccountConfig.Mode.WEB) {
            account.put("label", store.webLabel ?: "DeepSeek account")
            account.put("webExpired", store.webExpired)
        }
        if (store.mode == AccountConfig.Mode.API_KEY) account.put("keyHint", apiKeyHint(activity) ?: JSONObject.NULL)
        return JSONObject().put("account", account).toString()
    }

    fun action(activity: Activity, name: String, arg: String) {
        DshLog.i(DshApplication.TAG, "settings action: $name")
        when (name) {
            "accountSwitch" -> activity.startActivity(Intent(activity, SignInActivity::class.java).putExtra(SignInActivity.EXTRA_SWITCH, true))
            "accountReplaceKey" -> replaceApiKey(activity)
            "accountSignOut" -> signOut(activity) {
                if (!activity.isFinishing && !activity.isDestroyed) activity.startActivity(Intent(activity, SignInActivity::class.java))
            }
            else -> DshLog.w(DshApplication.TAG, "unknown settings action: $name")
        }
    }

    /**
     * Settings > Account: change only the API key. The account mode is not touched, and nothing is signed out, so the
     * user keeps their DSH setup while the key rotates. Refused unless an API key is the current sign-in.
     */
    private fun replaceApiKey(activity: Activity) {
        if (AccountStore(activity).mode != AccountConfig.Mode.API_KEY) {
            DshLog.w(DshApplication.TAG, "replace key: not signed in with an API key")
            return
        }
        activity.startActivity(Intent(activity, SignInActivity::class.java).putExtra(SignInActivity.EXTRA_REPLACE_KEY, true))
    }

    /**
     * Forgets both sign-ins: web session (token, cookies, page storage) and API key; dsh restarts without them.
     * Cookies and page storage are cleared on the main thread, files off it; [then] runs on the main thread.
     */
    fun signOut(activity: Activity, then: () -> Unit = {}) {
        val app = activity.applicationContext
        val signOut = AccountChanges.shared.begin()
        Thread({
            val result = runCatching {
                signOut.signOut(
                    clearCredentials = {
                        DshCredentials.write(EngineManager.get(app).dshHomeDir,
                            mapOf(DshCredentials.DEEPSEEK_API_KEY to null, DshCredentials.WEB_BRIDGE_KEY to null))
                    },
                    forgetAccount = { AccountStore(app).signOut() },
                    stopBridge = { WebAccount.stop() },
                )
            }
            if (result.getOrDefault(false)) {
                SignInActivity.restartEngine(app)
                activity.runOnUiThread {
                    if (!signOut.isLatest) return@runOnUiThread
                    clearWebSession()
                    then()
                }
            } else if (result.isFailure) {
                DshLog.w(DshApplication.TAG, "sign-out: credentials could not be cleared")
                activity.runOnUiThread {
                    if (signOut.isLatest && !activity.isFinishing && !activity.isDestroyed) {
                        AlertDialog.Builder(activity).setTitle("Could not sign out")
                            .setMessage("Your saved credentials could not be removed. Free some storage and try signing out again.")
                            .setPositiveButton(android.R.string.ok, null).show()
                    }
                }
            }
        }, "sign-out").start()
    }

    private fun clearWebSession() {
        runCatching {
            val cookies = CookieManager.getInstance()
            val host = DeepSeekWebClient.ORIGIN.removePrefix("https://")
            cookies.getCookie(DeepSeekWebClient.ORIGIN)?.split(';')?.map { it.substringBefore('=').trim() }?.filter { it.isNotEmpty() }?.forEach { name ->
                for (domain in listOf(host, ".deepseek.com")) {
                    cookies.setCookie(DeepSeekWebClient.ORIGIN, "$name=; Max-Age=0; Path=/; Domain=$domain")
                }
                cookies.setCookie(DeepSeekWebClient.ORIGIN, "$name=; Max-Age=0; Path=/")
            }
            cookies.flush()
            WebStorage.getInstance().deleteOrigin(DeepSeekWebClient.ORIGIN)
        }
    }

    /** Only the masked suffix of the saved key ever leaves the credentials file (see [DshCredentials.keyHint]). */
    private fun apiKeyHint(activity: Activity): String? = runCatching {
        DshCredentials.keyHint(DshCredentials.read(EngineManager.get(activity).dshHomeDir, DshCredentials.DEEPSEEK_API_KEY))
    }.getOrNull()
}
