package com.dsh.android.account

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.dsh.android.EngineManager
import com.dsh.android.databinding.ActivitySignInBinding
import com.dsh.android.log.DshLog
import com.dsh.android.webbridge.DeepSeekLoginActivity
import kotlin.concurrent.thread

/**
 * v1.3.0 first screen: "Continue with Google" (DeepSeek web account, see [DeepSeekLoginActivity]) or "Use API key"
 * (a DeepSeek platform key, checked once and written into dsh's own `.credentials.yaml`). Shown over MainActivity while
 * the engine boots; also opened from Settings > Account to switch accounts ([EXTRA_SWITCH]).
 */
class SignInActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySignInBinding
    private lateinit var store: AccountStore
    private var attempt: AccountChanges.Attempt? = null
    @Volatile private var checking = false

    /** Settings > Account "Replace API key": only the key changes; the account stays signed in on Back or failure. */
    private var replaceMode = false

    private val webLogin = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) { setResult(RESULT_OK); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignInBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = AccountStore(this)
        replaceMode = intent.getBooleanExtra(EXTRA_REPLACE_KEY, false) && store.mode == AccountConfig.Mode.API_KEY
        if (replaceMode) {
            binding.subtitle.setText(com.dsh.android.R.string.signin_replace_subtitle)
            binding.keyTitle.setText(com.dsh.android.R.string.signin_replace_title)
            binding.keyBody.setText(com.dsh.android.R.string.signin_replace_body)
            binding.btnLater.visibility = View.GONE
        } else if (intent.getBooleanExtra(EXTRA_SWITCH, false)) {
            binding.subtitle.setText(com.dsh.android.R.string.signin_switch_subtitle)
        }

        binding.btnGoogle.setOnClickListener {
            cancelCheck()
            webLogin.launch(Intent(this, DeepSeekLoginActivity::class.java).putExtra(EXTRA_SWITCH, intent.getBooleanExtra(EXTRA_SWITCH, false)))
        }
        binding.btnApiKey.setOnClickListener { showKeyPanel(true) }
        binding.btnLater.setOnClickListener {
            store.setUpLater()
            finish()
        }
        binding.btnKeyBack.setOnClickListener { goBack() }
        binding.btnGetKey.setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://platform.deepseek.com/api_keys"))) }
        }
        binding.btnKeyContinue.setOnClickListener { submitKey() }
        binding.keyInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { submitKey(); true } else false
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { goBack() }
        })
        if (replaceMode) showKeyPanel(true) else if (savedInstanceState?.getBoolean(STATE_KEY_PANEL) == true) showKeyPanel(true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_KEY_PANEL, binding.keyPanel.visibility == View.VISIBLE)
    }

    /**
     * Back never commits anything: in replace mode it simply closes the screen, so a canceled replacement leaves the
     * existing key and account mode untouched. Outside replace mode it returns to the two-choice first screen.
     */
    private fun goBack() {
        if (replaceMode || binding.keyPanel.visibility != View.VISIBLE) finish() else showKeyPanel(false)
    }

    private fun showKeyPanel(show: Boolean) {
        if (!show) cancelCheck()
        binding.choicePanel.visibility = if (show) View.GONE else View.VISIBLE
        binding.keyPanel.visibility = if (show) View.VISIBLE else View.GONE
        binding.keyError.visibility = View.GONE
        if (show) binding.keyInput.requestFocus()
    }

    private fun submitKey() {
        if (checking) return
        val key = binding.keyInput.text?.toString().orEmpty().trim()
        DshCredentials.problem(key)?.let { showError(it); return }
        val pending = AccountChanges.shared.begin().also { attempt = it }
        checking = true
        binding.keyError.visibility = View.GONE
        binding.keyProgress.visibility = View.VISIBLE
        binding.btnKeyContinue.isEnabled = false
        val app = applicationContext
        thread(name = "api-key-check") {
            val result = ApiKeyCheck.check(key)
            // Publication happens on the lifecycle thread: Back/destroy queued before this result always wins.
            runOnUiThread {
                if (isFinishing || isDestroyed || attempt !== pending || !pending.isCurrent) return@runOnUiThread
                checking = false
                binding.keyProgress.visibility = View.GONE
                binding.btnKeyContinue.isEnabled = true
                if (result is KeyCheck.Failed) { pending.cancel(); showError(result.message); return@runOnUiThread }
                val saved = runCatching {
                    pending.commit {
                        DshCredentials.write(EngineManager.get(app).dshHomeDir, mapOf(DshCredentials.DEEPSEEK_API_KEY to key))
                        if (!replaceMode) {
                            store.signOutWeb()
                            store.mode = AccountConfig.Mode.API_KEY
                        }
                    }
                }
                when {
                    saved.isFailure -> showError("Could not save the key: ${saved.exceptionOrNull()?.message}")
                    saved.getOrDefault(false) -> {
                        DshLog.i("DSH", if (replaceMode) "replaced API key: mode=API_KEY" else "signed in: mode=API_KEY")
                        thread(name = "sign-in-apply") { restartEngine(app) }
                        setResult(RESULT_OK)
                        finish()
                    }
                }
            }
        }
    }

    private fun cancelCheck() {
        attempt?.cancel()
        attempt = null
        checking = false
        binding.keyProgress.visibility = View.GONE
        binding.btnKeyContinue.isEnabled = true
    }

    override fun finish() {
        attempt?.cancel()
        super.finish()
    }

    override fun onDestroy() {
        attempt?.cancel()
        super.onDestroy()
    }

    private fun showError(message: String) {
        binding.keyError.text = message
        binding.keyError.visibility = View.VISIBLE
    }

    companion object {
        const val EXTRA_SWITCH = "switch"

        /** Open straight on the API-key panel to rotate the key; the saved account is kept on Back. */
        const val EXTRA_REPLACE_KEY = "replaceKey"
        private const val STATE_KEY_PANEL = "keyPanel"

        /** Has the engine pick up the new account config (now if Ready, after the boot in progress otherwise). */
        fun restartEngine(context: android.content.Context) {
            EngineManager.get(context).restartForConfigChange("account changed")
        }
    }
}
