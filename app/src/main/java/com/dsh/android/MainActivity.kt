package com.dsh.android

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
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
        }
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
