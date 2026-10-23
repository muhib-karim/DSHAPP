package com.dsh.android.webbridge

import android.content.Context
import android.webkit.CookieManager
import com.dsh.android.account.AccountConfig
import com.dsh.android.account.AccountStore
import com.dsh.android.log.DshLog

/**
 * The DeepSeek web account in the app process: the local [BridgeServer] that dsh's `deepseek-web` route calls, fed by a
 * [DeepSeekWebClient] that reads the stored user token and the WebView's chat.deepseek.com cookies on every request.
 * Runs only while the web account is the chosen sign-in; the port is random per process.
 */
object WebAccount {
    @Volatile private var server: BridgeServer? = null

    /** Starts the bridge when the web account is signed in; returns its port, or null (and stops it) otherwise. */
    @Synchronized
    fun ensureStarted(context: Context): Int? {
        val app = context.applicationContext
        val store = AccountStore(app)
        if (store.mode != AccountConfig.Mode.WEB || store.webToken() == null) {
            stop()
            return null
        }
        server?.takeIf { it.running }?.let { return it.port }
        val created = BridgeServer(
            secret = store.bridgeSecret(),
            chat = { client(app) },
            onSessionExpired = {
                if (!store.webExpired) DshLog.i(TAG, "web session expired; Settings > Account asks to sign in again")
                store.markWebExpired()
            },
        ) { DshLog.i(TAG, it) }
        return try {
            created.start()
            server = created
            created.port
        } catch (t: Throwable) {
            DshLog.w(TAG, "web bridge failed to start: ${t.message}")
            null
        }
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
    }

    fun client(context: Context): DeepSeekWebClient {
        val app = context.applicationContext
        return DeepSeekWebClient(
            token = { AccountStore(app).webToken() },
            cookies = { runCatching { CookieManager.getInstance().getCookie(DeepSeekWebClient.ORIGIN) }.getOrNull().orEmpty() },
        )
    }

    private const val TAG = "DSH"
}
