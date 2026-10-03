package com.dsh.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.dsh.android.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

/**
 * 主界面：启动内置引擎，然后把 DSH 自带的 Web GUI 加载进 WebView。
 *
 * 刻意不自绘聊天 UI —— 直接复用 DSH 官方前端，保证功能与桌面端一致。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var engine: EngineManager

    /** 资产版本戳：改了引擎内容就改这里，App 会重新解包。 */
    private val assetStamp = "0.2.0-rc.2-1"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        engine = EngineManager.get(this)

        setupWebView()
        observeEngine()
        bootEngine()
    }

    private fun setupWebView() = with(binding.webView) {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            // 引擎跑在 http://127.0.0.1，属于非安全上下文，
            // 需要允许混合内容，否则部分 API 被浏览器策略拦掉。
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            textZoom = 100
        }
        setBackgroundColor(Color.parseColor("#0F1115"))

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val url = request.url.toString()
                // 只允许留在本地引擎里；外链交给系统浏览器
                return if (url.startsWith("http://127.0.0.1")) {
                    false
                } else {
                    runCatching {
                        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, request.url))
                    }
                    true
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                Log.i(DshApplication.TAG, "WebView 加载完成: $url")
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                Log.i(DshApplication.TAG, "[web] ${msg.message()} @${msg.lineNumber()}")
                return true
            }

            /**
             * 文件选择器接管。
             *
             * ⚠️ **必须实现**：WebView 的默认实现直接 `callback.onReceiveValue(null)`，
             * 页面侧完全无感 —— 症状就是用户反馈的「点『文件』静默一点反应都没有」。
             * WebView 自身不弹任何选择器，必须由宿主 App 拉起。
             */
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                // 上一次还没回调完就再来一次时，必须先把它收掉，
                // 否则 WebView 会一直挂着等这个 callback（表现为后续选择器全部失效）。
                filePathCallbackRef?.onReceiveValue(null)
                filePathCallbackRef = filePathCallback

                val intent = runCatching { fileChooserParams.createIntent() }.getOrElse {
                    Log.w(DshApplication.TAG, "构造文件选择 Intent 失败", it)
                    filePathCallbackRef = null
                    filePathCallback.onReceiveValue(null)
                    return false
                }
                // 官方建议：允许一次选取多个（页面里那个 input 是 multiple）。
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: ActivityNotFoundException) {
                    // 设备上没有能响应 ACTION_GET_CONTENT 的应用 —— 如实记日志并归还 callback，
                    // 否则页面会永远停在"等待选择"状态。
                    Log.w(DshApplication.TAG, "没有可用的文件选择器", e)
                    filePathCallbackRef = null
                    filePathCallback.onReceiveValue(null)
                    false
                }
            }
        }
    }

    /** 当前挂起的文件选择回调；null 表示没有等待中的选择。 */
    private var filePathCallbackRef: ValueCallback<Array<Uri>>? = null

    /**
     * 文件选择结果。`resultCode`/`data` 一律要回给 WebView —— **成功、取消、失败
     * 三条路径都必须回调**，漏掉任何一条都会让页面永久卡在等待状态。
     */
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallbackRef ?: return@registerForActivityResult
        filePathCallbackRef = null
        callback.onReceiveValue(parseFileChooserResult(result.resultCode, result.data))
    }

    /**
     * 把 Activity 结果翻译成 WebView 要的 `Array<Uri>`。
     *
     * 取消（`RESULT_CANCELED`）必须回 `null`，而不是空数组 —— 空数组会让页面
     * 以为"选了 0 个文件"并触发一次空提交。
     */
    private fun parseFileChooserResult(resultCode: Int, data: Intent?): Array<Uri>? {
        if (resultCode != Activity.RESULT_OK || data == null) return null
        // 多选走 clipData，单选走 data。
        data.clipData?.let { clip ->
            return Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
        }
        return data.data?.let { arrayOf(it) }
    }

    private fun observeEngine() {
        lifecycleScope.launch {
            engine.state.collect { state ->
                when (state) {
                    is EngineManager.State.Idle -> {
                        showOverlay("准备中…")
                    }
                    is EngineManager.State.Extracting -> {
                        showOverlay("首次启动，正在解包引擎 ${state.percent}%")
                    }
                    is EngineManager.State.Starting -> {
                        showOverlay("引擎启动中…")
                    }
                    is EngineManager.State.Ready -> {
                        hideOverlay()
                        if (binding.webView.url != state.url) {
                            Log.i(DshApplication.TAG, "加载 Web GUI: ${state.url}")
                            binding.webView.loadUrl(state.url)
                        }
                    }
                    is EngineManager.State.Failed -> {
                        showOverlay("引擎启动失败\n\n${state.message}", true)
                    }
                }
            }
        }
    }

    private fun bootEngine() {
        lifecycleScope.launch {
            try {
                engine.ensureExtracted(assetStamp)
                engine.start()
            } catch (t: Throwable) {
                Log.e(DshApplication.TAG, "引擎启动异常", t)
                showOverlay("引擎启动异常: ${t.message}", true)
            }
        }
    }

    private fun showOverlay(text: String, isError: Boolean = false) {
        binding.overlay.visibility = View.VISIBLE
        binding.overlayText.text = text
        binding.overlayText.setTextColor(
            if (isError) Color.parseColor("#FF6B6B") else Color.parseColor("#9AA4B2")
        )
        binding.overlayProgress.visibility =
            if (isError) View.GONE else View.VISIBLE
    }

    private fun hideOverlay() {
        binding.overlay.visibility = View.GONE
    }

    override fun onDestroy() {
        // 引擎跟随进程存活，这里不停止，避免转屏/重建时反复冷启动。
        super.onDestroy()
    }
}
