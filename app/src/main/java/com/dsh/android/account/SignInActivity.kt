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
    @Volatile private var checking = false

    private val webLogin = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) done(AccountConfig.Mode.WEB)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignInBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = AccountStore(this)
        if (intent.getBooleanExtra(EXTRA_SWITCH, false)) binding.subtitle.setText(com.dsh.android.R.string.signin_switch_subtitle)

        binding.btnGoogle.setOnClickListener { webLogin.launch(Intent(this, DeepSeekLoginActivity::class.java)) }
        binding.btnApiKey.setOnClickListener { showKeyPanel(true) }
        binding.btnLater.setOnClickListener {
            store.setUpLater()
            finish()
        }
        binding.btnKeyBack.setOnClickListener { showKeyPanel(false) }
        binding.btnGetKey.setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://platform.deepseek.com/api_keys"))) }
        }
        binding.btnKeyContinue.setOnClickListener { submitKey() }
        binding.keyInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { submitKey(); true } else false
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.keyPanel.visibility == View.VISIBLE) showKeyPanel(false) else finish()
            }
        })
        if (savedInstanceState?.getBoolean(STATE_KEY_PANEL) == true) showKeyPanel(true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_KEY_PANEL, binding.keyPanel.visibility == View.VISIBLE)
    }

    private fun showKeyPanel(show: Boolean) {
        binding.choicePanel.visibility = if (show) View.GONE else View.VISIBLE
        binding.keyPanel.visibility = if (show) View.VISIBLE else View.GONE
        binding.keyError.visibility = View.GONE
        if (show) binding.keyInput.requestFocus()
    }

    private fun submitKey() {
        if (checking) return
        val key = binding.keyInput.text?.toString().orEmpty().trim()
        DshCredentials.problem(key)?.let { showError(it); return }
        checking = true
        binding.keyError.visibility = View.GONE
        binding.keyProgress.visibility = View.VISIBLE
        binding.btnKeyContinue.isEnabled = false
        thread(name = "api-key-check") {
            val result = ApiKeyCheck.check(key)
            val saved = if (result == KeyCheck.Ok) runCatching {
                DshCredentials.write(EngineManager.get(this).dshHomeDir, mapOf(DshCredentials.DEEPSEEK_API_KEY to key))
            }.exceptionOrNull() else null
            runOnUiThread {
                checking = false
                binding.keyProgress.visibility = View.GONE
                binding.btnKeyContinue.isEnabled = true
                when {
                    isFinishing -> Unit
                    result is KeyCheck.Failed -> showError(result.message)
                    saved != null -> showError("Could not save the key: ${saved.message}")
                    else -> done(AccountConfig.Mode.API_KEY)
                }
            }
        }
    }

    private fun showError(message: String) {
        binding.keyError.text = message
        binding.keyError.visibility = View.VISIBLE
    }

    /** Remembers the mode; the engine is restarted so dsh reads the new provider and default model. */
    private fun done(mode: AccountConfig.Mode) {
        val previous = store.mode
        if (previous == AccountConfig.Mode.API_KEY && mode == AccountConfig.Mode.WEB) {
            runCatching { DshCredentials.write(EngineManager.get(this).dshHomeDir, mapOf(DshCredentials.DEEPSEEK_API_KEY to null)) }
        }
        if (mode == AccountConfig.Mode.API_KEY) store.signOutWeb()
        store.mode = mode
        DshLog.i("DSH", "signed in: mode=$mode (was $previous)")
        restartEngine(this)
        setResult(RESULT_OK)
        finish()
    }

    companion object {
        const val EXTRA_SWITCH = "switch"
        private const val STATE_KEY_PANEL = "keyPanel"

        /** Restarts a running or starting engine so it picks up the account config; a failed engine is left alone. */
        fun restartEngine(context: android.content.Context) {
            val engine = EngineManager.get(context)
            when (engine.state.value) {
                is EngineManager.State.Ready, EngineManager.State.Starting, is EngineManager.State.Extracting ->
                    engine.requestRestart("account changed")
                else -> Unit
            }
        }
    }
}
