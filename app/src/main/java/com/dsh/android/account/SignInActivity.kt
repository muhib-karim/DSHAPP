package com.dsh.android.account

import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.dsh.android.EngineManager
import com.dsh.android.R
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
            binding.subtitle.setText(R.string.signin_replace_subtitle)
            binding.keyTitle.setText(R.string.signin_replace_title)
            binding.keyBody.setText(R.string.signin_replace_body)
            binding.btnLater.visibility = View.GONE
        } else if (intent.getBooleanExtra(EXTRA_SWITCH, false)) {
            binding.subtitle.setText(R.string.signin_switch_subtitle)
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
        binding.btnPasteKey.setOnClickListener { pasteKey() }
        binding.keyInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { submitKey(); true } else false
        }
        // Editing the field while a check is in flight makes that check stale: cancel it instead of later publishing a
        // result for text the user has already replaced.
        binding.keyInput.doAfterTextChanged { if (checking) cancelCheck() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { goBack() }
        })

        val restoring = savedInstanceState != null
        val showPanel = replaceMode || savedInstanceState?.getBoolean(STATE_KEY_PANEL) == true
        if (showPanel) {
            showKeyPanel(true, requestFocus = !restoring, clearError = !restoring)
            if (restoring) restoreKeyPanel(savedInstanceState!!)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val panelShown = binding.keyPanel.visibility == View.VISIBLE
        outState.putBoolean(STATE_KEY_PANEL, panelShown)
        if (panelShown) {
            outState.putString(STATE_KEY_TEXT, binding.keyInput.text?.toString().orEmpty())
            outState.putString(
                STATE_KEY_ERROR,
                if (binding.keyError.visibility == View.VISIBLE) binding.keyError.text?.toString() else null,
            )
            outState.putBoolean(STATE_KEY_FOCUS, binding.keyInput.hasFocus())
        }
        // A check started by the instance being torn down must not publish into its replacement. Cancelling here also
        // makes the pending attempt stale, so the check thread's late result cannot write the key during a rotation.
        if (isChangingConfigurations) cancelCheck()
    }

    /**
     * Back never commits anything: in replace mode it simply closes the screen, so a canceled replacement leaves the
     * existing key and account mode untouched. Outside replace mode it returns to the two-choice first screen.
     */
    private fun goBack() {
        if (replaceMode || binding.keyPanel.visibility != View.VISIBLE) finish() else showKeyPanel(false)
    }

    private fun showKeyPanel(show: Boolean, requestFocus: Boolean = true, clearError: Boolean = true) {
        if (!show) {
            cancelCheck()
            // The panel may have been scrolled to an error; the choice panel must be where the user comes back to.
            binding.root.post { binding.root.scrollTo(0, 0) }
        }
        if (clearError) clearKeyError()
        binding.choicePanel.visibility = if (show) View.GONE else View.VISIBLE
        binding.keyPanel.visibility = if (show) View.VISIBLE else View.GONE
        if (show && requestFocus) binding.keyInput.requestFocus()
    }

    private fun restoreKeyPanel(state: Bundle) {
        state.getString(STATE_KEY_TEXT)?.let { text ->
            binding.keyInput.setText(text)
            binding.keyInput.setSelection(text.length)
        }
        KeyEntry.restoredError(state.getCharSequence(STATE_KEY_ERROR))?.let { message ->
            showError(message, announce = false, focusField = false)
        }
        if (state.getBoolean(STATE_KEY_FOCUS)) binding.keyInput.requestFocus()
    }

    /**
     * An explicit tap is the only way the panel reads the clipboard: nothing is pasted automatically, and a missing,
     * non-text or blank clip leaves the field (and any typed key) exactly as it was.
     */
    private fun pasteKey() {
        val text = try {
            val clipboard = getSystemService(ClipboardManager::class.java) ?: return
            if (!clipboard.hasPrimaryClip()) return
            val clip = clipboard.primaryClip ?: return
            if (clip.itemCount == 0) return
            KeyEntry.fromClipboard(clip.getItemAt(0).text)
        } catch (_: RuntimeException) {
            null
        } ?: return
        binding.keyInput.setText(text)
        binding.keyInput.setSelection(text.length)
        binding.keyInput.requestFocus()
        clearKeyError()
    }

    private fun submitKey() {
        if (checking) return
        val key = KeyEntry.submitted(binding.keyInput.text)
        DshCredentials.problem(key)?.let { showError(it); return }
        val pending = AccountChanges.shared.begin().also { attempt = it }
        checking = true
        clearKeyError()
        binding.keyProgress.visibility = View.VISIBLE
        binding.btnKeyContinue.isEnabled = false
        val app = applicationContext
        thread(name = "api-key-check") {
            val result = ApiKeyCheck.check(key)
            // Publication happens on the lifecycle thread: Back/destroy queued before this result always wins.
            runOnUiThread {
                if (isFinishing || isDestroyed || isChangingConfigurations ||
                    attempt !== pending || !pending.isCurrent
                ) {
                    return@runOnUiThread
                }
                checking = false
                binding.keyProgress.visibility = View.GONE
                binding.btnKeyContinue.isEnabled = true
                if (result is KeyCheck.Failed) {
                    pending.cancel()
                    showError(result.message)
                    return@runOnUiThread
                }
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
                    // The exception text can carry a path; the panel shows one sentence the user can act on instead.
                    saved.isFailure -> showError(getString(R.string.signin_key_save_failed))
                    saved.getOrDefault(false) -> {
                        DshLog.i("DSH", if (replaceMode) "replaced API key: mode=API_KEY" else "signed in: mode=API_KEY")
                        thread(name = "sign-in-apply") { restartEngine(app) }
                        setResult(RESULT_OK)
                        finish()
                    }
                    // Another account transition consumed the attempt between the check and the write.
                    else -> showError(getString(R.string.signin_key_superseded))
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

    private fun clearKeyError() {
        binding.keyErrorTitle.visibility = View.GONE
        binding.keyError.text = null
        binding.keyError.visibility = View.GONE
    }

    /**
     * A heading says what failed, the message says why. The error is scrolled into view above the keyboard and, for
     * checks and writes triggered by the user, announced. A restored error is already where the user left it.
     */
    private fun showError(message: String, announce: Boolean = true, focusField: Boolean = true) {
        binding.keyErrorTitle.setText(
            if (replaceMode) R.string.signin_key_error_title_replace else R.string.signin_key_error_title,
        )
        binding.keyErrorTitle.visibility = View.VISIBLE
        binding.keyError.text = message
        binding.keyError.visibility = View.VISIBLE
        if (focusField) binding.keyInput.requestFocus()
        binding.keyError.post {
            binding.keyError.requestRectangleOnScreen(
                Rect(0, 0, binding.keyError.width, binding.keyError.height),
            )
            if (announce) binding.keyError.announceForAccessibility("${binding.keyErrorTitle.text}. $message")
        }
    }

    companion object {
        const val EXTRA_SWITCH = "switch"

        /** Open straight on the API-key panel to rotate the key; the saved account is kept on Back. */
        const val EXTRA_REPLACE_KEY = "replaceKey"
        private const val STATE_KEY_PANEL = "keyPanel"
        private const val STATE_KEY_TEXT = "keyText"
        private const val STATE_KEY_ERROR = "keyError"
        private const val STATE_KEY_FOCUS = "keyFocus"

        /** Has the engine pick up the new account config (now if Ready, after the boot in progress otherwise). */
        fun restartEngine(context: android.content.Context) {
            EngineManager.get(context).restartForConfigChange("account changed")
        }
    }
}
