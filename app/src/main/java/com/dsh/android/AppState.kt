package com.dsh.android

import android.app.Activity
import android.content.Intent
import android.webkit.CookieManager
import android.webkit.WebStorage
import com.dsh.android.account.AccountConfig
import com.dsh.android.account.AccountStore
import com.dsh.android.account.DshCredentials
import com.dsh.android.account.SignInActivity
import com.dsh.android.log.DshLog
import com.dsh.android.webbridge.DeepSeekWebClient
import com.dsh.android.webbridge.WebAccount
import org.json.JSONObject
import java.io.File

/**
 * v1.3.0 settings sections (Account, About & updates) through the page bridge: [json] is the snapshot behind
 * `DSHAndroid.appState()`, [action] runs `DSHAndroid.appAction(name, arg)` on the main thread.
 */
object AppState {
    fun json(activity: Activity): String {
        val store = AccountStore(activity)
        val account = JSONObject().put("mode", store.mode.name.lowercase())
        if (store.mode == AccountConfig.Mode.WEB) account.put("label", store.webLabel ?: "DeepSeek account")
        val version = JSONObject()
            .put("app", BuildConfig.VERSION_NAME)
            .put("code", BuildConfig.VERSION_CODE)
            .put("flavor", if (BuildConfig.BUILT_IN_WEB_ENGINE) "gecko" else "system")
            .put("harness", harnessVersion(activity) ?: JSONObject.NULL)
        return JSONObject().put("account", account).put("version", version).toString()
    }

    fun action(activity: Activity, name: String, arg: String) {
        DshLog.i(DshApplication.TAG, "settings action: $name")
        when (name) {
            "accountSwitch" -> activity.startActivity(Intent(activity, SignInActivity::class.java).putExtra(SignInActivity.EXTRA_SWITCH, true))
            "accountSignOut" -> {
                signOut(activity)
                activity.startActivity(Intent(activity, SignInActivity::class.java))
            }
            else -> DshLog.w(DshApplication.TAG, "unknown settings action: $name")
        }
    }

    /** Forgets both sign-ins: web session (token, cookies, page storage) and API key; dsh restarts without them. */
    fun signOut(activity: Activity) {
        AccountStore(activity).signOut()
        WebAccount.stop()
        runCatching {
            DshCredentials.write(EngineManager.get(activity).dshHomeDir,
                mapOf(DshCredentials.DEEPSEEK_API_KEY to null, DshCredentials.WEB_BRIDGE_KEY to null))
        }.onFailure { DshLog.w(DshApplication.TAG, "sign-out: credentials not cleared: ${it.message}") }
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
        SignInActivity.restartEngine(activity)
    }

    /** Version of the installed `@deepseek-ai/dsh` (engine/node_modules/@deepseek-ai/dsh/package.json). */
    fun harnessVersion(activity: Activity): String? = runCatching {
        val pkg = File(activity.filesDir, "engine/node_modules/@deepseek-ai/dsh/package.json")
        JSONObject(pkg.readText()).optString("version").ifEmpty { null }
    }.getOrNull()
}
