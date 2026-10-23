package com.dsh.android.webbridge

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.dsh.android.MainActivity
import com.dsh.android.R
import com.dsh.android.account.AccountChanges
import com.dsh.android.account.AccountConfig
import com.dsh.android.account.AccountStore
import com.dsh.android.account.DshCredentials
import com.dsh.android.account.SignInActivity
import com.dsh.android.EngineManager
import com.dsh.android.databinding.ActivityDeepseekLoginBinding
import com.dsh.android.log.DshLog
import org.json.JSONObject
import java.io.IOException
import kotlin.concurrent.thread

/**
 * "Continue with Google": DeepSeek's own sign-in page (chat.deepseek.com/sign_in, with its Google button) in an in-app
 * WebView. After sign-in the page keeps the session in `localStorage.userToken`; it is read on that origin only,
 * checked with `users/current`, encrypted into [AccountStore] and used by the local bridge ([WebAccount]).
 *
 * Google refuses OAuth inside embedded WebViews (`disallowed_useragent`); the app does not disguise its WebView.
 * "Use your browser" signs in at chat.deepseek.com in the phone's browser instead and brings the session back with a
 * bookmark (an explicit `mkdsh://session` intent to this package, accepted within 30 minutes) or by paste.
 */
class DeepSeekLoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDeepseekLoginBinding
    private lateinit var store: AccountStore
    private var web: WebView? = null
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var verifying = false
    private var lastRejected: String? = null
    private var attempt: AccountChanges.Attempt? = null
    private var previousToken: String? = null

    private val poll = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed || attempt?.isCurrent != true) return
            web?.evaluateJavascript(WebToken.CAPTURE_JS) { result -> WebToken.captureCandidate(result, previousToken)?.let(::verify) }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeepseekLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = AccountStore(this)
        attempt = AccountChanges.shared.begin()
        previousToken = store.webToken()
        if (previousToken != null) status(getString(R.string.login_switch_account))
        binding.btnClose.setOnClickListener { finish() }
        binding.btnUseBrowser.setOnClickListener { showBrowserRoute() }
        binding.btnOpenBrowser.setOnClickListener { openBrowser() }
        binding.btnCopyBookmark.setOnClickListener {
            val clip = getSystemService(ClipboardManager::class.java)
            clip?.setPrimaryClip(ClipData.newPlainText("DSH Handoff", WebToken.bookmarklet(packageName)))
            Toast.makeText(this, R.string.login_bookmark_copied, Toast.LENGTH_LONG).show()
        }
        binding.btnPasteContinue.setOnClickListener {
            val token = WebToken.fromPaste(binding.pasteInput.text?.toString())
            if (token == null) status(getString(R.string.login_bad_paste)) else verify(token)
        }
        if (!handleLink(intent)) setUpWebView()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /** `mkdsh://session` from the bookmark in the browser. */
    private fun handleLink(intent: Intent?): Boolean {
        val data = intent?.data ?: return false
        if (data.scheme != WebToken.LINK_SCHEME || data.host != WebToken.LINK_HOST) return false
        val link = WebToken.classifyLink(intent.getStringExtra(WebToken.EXTRA_TOKEN), store.browserSignInStartedAt, System.currentTimeMillis())
        // One link per browser sign-in: another app or page cannot keep sending sessions inside the window.
        store.browserSignInStartedAt = null
        showBrowserRoute()
        // A link can come from anything on the phone, so its account is shown and confirmed before it is used; a link
        // that did not follow a sign-in started here (bookmark run on its own, or after 30 minutes) says so.
        if (link == null) status(getString(R.string.login_bad_paste)) else verify(link.token, confirm = true, fromApp = link.fromApp)
        return true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setUpWebView() {
        val view = WebView(this)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        view.webViewClient = object : WebViewClient() {
            private var resetSession = previousToken != null
            override fun onPageFinished(v: WebView, url: String?) {
                if (resetSession && url != null && Uri.parse(url).host == "chat.deepseek.com") {
                    resetSession = false
                    // The encrypted account remains usable if this switch is canceled.
                    v.evaluateJavascript("localStorage.removeItem('userToken'); location.replace('https://chat.deepseek.com/sign_in');", null)
                }
            }
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                return uri.scheme != "https"   // only https pages (DeepSeek, Google) load here
            }
        }
        view.webChromeClient = object : android.webkit.WebChromeClient() {
            // Google's sign-in opens a popup window; show it in a dialog so the opener page gets its result.
            override fun onCreateWindow(v: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val popup = WebView(this@DeepSeekLoginActivity)
                popup.settings.javaScriptEnabled = true
                popup.settings.domStorageEnabled = true
                val dialog = Dialog(this@DeepSeekLoginActivity)
                dialog.setContentView(popup, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                popup.webViewClient = WebViewClient()
                popup.webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onCloseWindow(window: WebView) { dialog.dismiss(); window.destroy() }
                }
                dialog.setOnDismissListener { popup.destroy() }
                dialog.show()
                (resultMsg.obj as WebView.WebViewTransport).webView = popup
                resultMsg.sendToTarget()
                return true
            }
        }
        binding.webFrame.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view.loadUrl(WebToken.SIGN_IN_URL)
        web = view
        handler.postDelayed(poll, 1500)
    }

    private fun showBrowserRoute() {
        handler.removeCallbacks(poll)
        binding.webFrame.visibility = View.GONE
        binding.browserBar.visibility = View.GONE
        binding.browserPanel.visibility = View.VISIBLE
    }

    private fun openBrowser() {
        store.browserSignInStartedAt = System.currentTimeMillis()
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WebToken.SIGN_IN_URL))) }
    }

    private fun verify(token: String, confirm: Boolean = false, fromApp: Boolean = true) {
        val pending = attempt ?: return
        if (isFinishing || isDestroyed || !pending.isCurrent || verifying || token == lastRejected) return
        verifying = true
        status(getString(R.string.login_checking))
        thread(name = "web-sign-in") {
            val cookies = runCatching { CookieManager.getInstance().getCookie(DeepSeekWebClient.ORIGIN) }.getOrNull().orEmpty()
            val outcome: Result<JSONObject> = runCatching { DeepSeekWebClient(token = { token }, cookies = { cookies }).currentUser { !pending.isCurrent } }
            runOnUiThread {
                verifying = false
                if (isFinishing || isDestroyed || !pending.isCurrent) return@runOnUiThread
                val user = outcome.getOrNull()
                when {
                    user == null && outcome.exceptionOrNull() is IOException -> status(getString(R.string.login_offline))
                    user == null -> { lastRejected = token; status(getString(R.string.login_failed)) }
                    user.optBoolean("need_birthday", false) -> status(getString(R.string.login_birthday))
                    confirm -> {
                        verifying = true
                        AlertDialog.Builder(this)
                        .setTitle(if (fromApp) R.string.login_title else R.string.login_link_title)
                        .setMessage(getString(if (fromApp) R.string.login_confirm_account else R.string.login_link_confirm, WebToken.label(user)))
                        .setPositiveButton(R.string.signin_key_continue) { _, _ -> accept(token, user) }
                        .setNegativeButton(R.string.login_close) { _, _ -> status(getString(R.string.login_failed)) }
                        .setOnDismissListener { verifying = false }
                        .show()
                    }
                    else -> accept(token, user)
                }
            }
        }
    }

    private fun accept(token: String, user: JSONObject) {
        val pending = attempt ?: return
        if (isFinishing || isDestroyed) return
        val app = applicationContext
        val saved = runCatching {
            pending.commit {
                // Clear only the app-owned API key, preserving all other DSH credentials and models.
                if (store.mode == AccountConfig.Mode.API_KEY) {
                    DshCredentials.write(EngineManager.get(app).dshHomeDir, mapOf(DshCredentials.DEEPSEEK_API_KEY to null))
                }
                store.saveWebToken(token)
                store.webLabel = WebToken.label(user)
                store.browserSignInStartedAt = null
                store.mode = AccountConfig.Mode.WEB
            }
        }
        if (saved.isFailure) {
            status(getString(R.string.login_save_failed))
            attempt = AccountChanges.shared.begin()
            return
        }
        if (saved.getOrDefault(false)) {
            DshLog.i("DSH", "DeepSeek web account signed in")
            thread(name = "web-sign-in-apply") { SignInActivity.restartEngine(app) }
            setResult(RESULT_OK)
            // Opened straight from the browser link with the app closed: continue into the app, not back to the browser.
            if (isTaskRoot) startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    override fun finish() {
        attempt?.cancel()
        super.finish()
    }

    private fun status(text: String) {
        binding.loginStatus.text = text
        binding.loginStatus.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        attempt?.cancel()
        handler.removeCallbacks(poll)
        web?.destroy()
        web = null
        super.onDestroy()
    }
}
