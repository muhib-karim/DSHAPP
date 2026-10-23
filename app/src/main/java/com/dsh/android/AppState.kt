package com.dsh.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.appcompat.app.AlertDialog
import com.dsh.android.account.AccountChanges
import com.dsh.android.account.AccountConfig
import com.dsh.android.account.AccountStore
import com.dsh.android.account.DshCredentials
import com.dsh.android.account.SignInActivity
import com.dsh.android.log.DshLog
import com.dsh.android.update.AppUpdater
import com.dsh.android.update.Diagnostics
import com.dsh.android.update.UpdateCenter
import com.dsh.android.webbridge.DeepSeekWebClient
import com.dsh.android.webbridge.WebAccount
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v1.3.0 settings sections (Account, About & updates) through the page bridge: [json] is the snapshot behind
 * `DSHAndroid.appState()`, [action] runs `DSHAndroid.appAction(name, arg)` on the main thread.
 */
object AppState {
    /** One manual restart request at a time; a second tap before the first one leaves the main thread is ignored. */
    private val restartInFlight = AtomicBoolean(false)

    fun json(activity: Activity): String {
        val store = AccountStore(activity)
        val account = JSONObject().put("mode", store.mode.name.lowercase())
        if (store.mode == AccountConfig.Mode.WEB) {
            account.put("label", store.webLabel ?: "DeepSeek account")
            account.put("webExpired", store.webExpired)
        }
        if (store.mode == AccountConfig.Mode.API_KEY) account.put("keyHint", apiKeyHint(activity) ?: JSONObject.NULL)
        val version = JSONObject()
            .put("app", BuildConfig.VERSION_NAME)
            .put("code", BuildConfig.VERSION_CODE)
            .put("flavor", if (BuildConfig.BUILT_IN_WEB_ENGINE) "gecko" else "system")
            .put("harness", harnessVersion(activity) ?: JSONObject.NULL)
            // Settings > About diagnostics: non-sensitive labels only (see [Diagnostics]).
            .put("android", Diagnostics.android(Build.VERSION.RELEASE, Build.VERSION.SDK_INT) ?: JSONObject.NULL)
            .put("device", Diagnostics.device(Build.MANUFACTURER, Build.MODEL) ?: JSONObject.NULL)
            .put("abi", Diagnostics.abi(Build.SUPPORTED_ABIS) ?: JSONObject.NULL)
        return JSONObject().put("account", account).put("version", version).put("update", UpdateCenter.json(activity)).toString()
    }

    fun action(activity: Activity, name: String, arg: String) {
        DshLog.i(DshApplication.TAG, "settings action: $name")
        when (name) {
            "accountSwitch" -> activity.startActivity(Intent(activity, SignInActivity::class.java).putExtra(SignInActivity.EXTRA_SWITCH, true))
            "accountReplaceKey" -> replaceApiKey(activity)
            "accountSignOut" -> signOut(activity) {
                if (!activity.isFinishing && !activity.isDestroyed) activity.startActivity(Intent(activity, SignInActivity::class.java))
            }
            "updateCheck" -> UpdateCenter.check(activity)
            "updateHarness" -> UpdateCenter.updateHarness(activity)
            "updateApp" -> UpdateCenter.updateApp(activity)
            "openReleasePage" -> openReleasePage(activity)
            "harnessRollback" -> restorePreviousHarness(activity, arg)
            // Settings > About: the host's own log viewer (crashes, engine log, export), not a copy of it in the page.
            "openLogs" -> activity.startActivity(LogViewerActivity.intent(activity))
            // Settings > Plugins / About: a change dsh can only apply on restart.
            "engineRestart" -> restartEngine(activity)
            else -> DshLog.w(DshApplication.TAG, "unknown settings action: $name")
        }
    }

    /**
     * Settings > About: restart the engine so dsh re-reads its config. Refused while a boot or a restart is already
     * running — two overlapping pipelines would race for the same port and process slot. A manual restart is the
     * right action after a plugin change and after the engine has died, so it is not limited to [EngineManager.State.Ready].
     */
    private fun restartEngine(activity: Activity) {
        if (!restartInFlight.compareAndSet(false, true)) {
            DshLog.i(DshApplication.TAG, "engine restart: already requested")
            return
        }
        val app = activity.applicationContext
        Thread({
            try {
                val engine = EngineManager.get(app)
                when (engine.state.value) {
                    is EngineManager.State.Starting, is EngineManager.State.Extracting, is EngineManager.State.Dead ->
                        DshLog.i(DshApplication.TAG, "engine restart: a start or restart is already in progress")
                    else -> engine.requestRestart("settings changed")
                }
            } catch (t: Throwable) {
                DshLog.w(DshApplication.TAG, "engine restart failed: ${t.message}")
            } finally {
                restartInFlight.set(false)
            }
        }, "engine-restart").start()
    }

    /**
     * Settings > About & updates: open the checked release's page. The page cannot supply a URL: [UpdateCenter]
     * returns only the stored `html_url`, and [AppUpdater.isTrustedReleasePage] re-checks scheme, host and path.
     */
    private fun openReleasePage(activity: Activity) {
        val page = UpdateCenter.releasePage()
        if (page == null || !AppUpdater.isTrustedReleasePage(page)) {
            DshLog.w(DshApplication.TAG, "release page: nothing trusted to open")
            return
        }
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(page))) }
            .onFailure { DshLog.w(DshApplication.TAG, "release page: could not open (${it.javaClass.simpleName})") }
    }

    /**
     * Settings > About & updates: swap `engine.prev` back in on a worker thread. The page can only name a version
     * (`0.2.1-rc.2`); [EngineManager.activatePreviousHarness] re-checks it against the slot and refuses while an update
     * is pending or installing. Only the validated version is logged.
     */
    private fun restorePreviousHarness(activity: Activity, arg: String) {
        val expected = arg.trim()
        if (!expected.matches(Regex("[0-9A-Za-z][0-9A-Za-z._+-]{0,63}"))) {
            DshLog.w(DshApplication.TAG, "harness restore: invalid version")
            return
        }
        val app = activity.applicationContext
        Thread({
            val restored = runCatching { EngineManager.get(app).activatePreviousHarness(expected) }
                .onFailure { DshLog.w(DshApplication.TAG, "harness restore $expected failed: ${it.message}") }
                .getOrDefault(false)
            DshLog.i(DshApplication.TAG, if (restored) "harness restored to $expected" else "harness restore $expected not applied")
        }, "harness-restore").start()
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

    /** Version of the installed `@deepseek-ai/dsh` (engine/node_modules/@deepseek-ai/dsh/package.json). */
    fun harnessVersion(activity: Activity): String? = runCatching {
        val pkg = File(activity.filesDir, "engine/node_modules/@deepseek-ai/dsh/package.json")
        JSONObject(pkg.readText()).optString("version").ifEmpty { null }
    }.getOrNull()

    /** Only the masked suffix of the saved key ever leaves the credentials file (see [DshCredentials.keyHint]). */
    private fun apiKeyHint(activity: Activity): String? = runCatching {
        DshCredentials.keyHint(DshCredentials.read(EngineManager.get(activity).dshHomeDir, DshCredentials.DEEPSEEK_API_KEY))
    }.getOrNull()
}
