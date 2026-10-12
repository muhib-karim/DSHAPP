package com.dsh.android.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.dsh.android.DshApplication

/**
 * `system` flavor：用设备上的系统 WebView 承载界面。
 *
 * 这一份是把 [com.dsh.android.MainActivity] 里原本直接写在 `setupWebView()` 中的
 * 逻辑原样搬过来，**行为一字不改** —— 原有的两个能力缺口（`onShowFileChooser`
 * 与 `setDownloadListener` 的默认实现静默失败）的修法也一并保留。
 *
 * 唯一的结构性变化是：文件选择与下载不再由本类自己 `startActivity`，而是通过
 * [WebHostCallbacks] 交回宿主 —— 因为 gecko 侧走的是完全不同的机制，而宿主的
 * 处理逻辑（cookie 鉴权下载、一次性回调收尾）两边必须共用一份。
 */
@SuppressLint("SetJavaScriptEnabled")
class SystemWebHost(
    context: Context,
    private val callbacks: WebHostCallbacks,
) : WebHost {

    private val webView = WebView(context)

    /**
     * 当前挂起的文件选择回调；null 表示没有等待中的选择。
     *
     * ⚠️ 回调是**一次性**的：必须在调用前取出并置空，否则第二次点击不会响应
     *   （WebView 会一直挂着等这个 callback）。
     */
    private var filePathCallbackRef: ValueCallback<Array<Uri>>? = null

    /** 最近一次发起加载的 URL（见 [WebHost.currentUrl] 的语义说明）。 */
    private var lastLoadedUrl: String? = null

    /** 是否已经提交过至少一帧（见 [awaitFirstFrame]）。 */
    private var painted = false

    /** 是否已有一个等待首帧的回调在飞，避免重复注册。 */
    private var paintPending = false

    /** `postVisualStateCallback` 的请求号，单调递增即可（它只是个标识）。 */
    private var paintRequestId = 0L

    /** 已经等在首帧上的动作（首帧到达时执行）。 */
    private var pendingPaintAction: (() -> Unit)? = null

    override val view: View get() = webView

    override val currentUrl: String? get() = lastLoadedUrl

    override val engineMajor: Int
        get() = runCatching { WebView.getCurrentWebViewPackage() }
            .getOrNull()?.versionName.orEmpty()
            .substringBefore('.').toIntOrNull() ?: 0

    override val engineLabel: String
        get() = runCatching { WebView.getCurrentWebViewPackage() }
            .getOrNull()?.let { "${it.packageName} ${it.versionName}" }
            .orEmpty()

    init {
        webView.settings.apply {
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
        webView.setBackgroundColor(Color.parseColor("#0F1115"))

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val url = request.url.toString()
                // 只允许留在本地引擎里；外链交给系统浏览器
                if (url.startsWith(LOCAL_ENGINE_PREFIX)) return false
                callbacks.onExternalUrl(url)
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                callbacks.onPageFinished(url)
            }
        }

        // 插件用它把「AI 当前在干什么」推给通知栏（见 EngineService.updateStatus）。
        webView.addJavascriptInterface(SystemPageBridge(callbacks.bridge), "DSHAndroid")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                callbacks.onConsole("${msg.message()} @${msg.lineNumber()}")
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
                // 否则 WebView 会一直挂着等这个 callback（后续选择器全部失效）。
                filePathCallbackRef?.onReceiveValue(null)
                filePathCallbackRef = filePathCallback

                callbacks.fileChooser.launch(
                    fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE,
                ) { uris ->
                    val cb = filePathCallbackRef ?: return@launch
                    filePathCallbackRef = null
                    cb.onReceiveValue(uris)
                }
                return true
            }
        }

        /**
         * 下载接管。
         *
         * ⚠️ **必须实现**：与 `onShowFileChooser` 同一类缺口 —— WebView 自己不会
         * 下载，默认实现直接丢弃，页面侧完全无感。症状是「下载 Session 日志」弹窗
         * 显示「浏览器正在下载 Session ZIP 文件」，但文件根本没落地。
         */
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            callbacks.onDownload(
                DownloadRequest(
                    url = url,
                    userAgent = userAgent,
                    contentDisposition = contentDisposition,
                    mimeType = mimeType,
                )
            )
        }
    }

    override fun loadUrl(url: String) {
        lastLoadedUrl = url
        webView.loadUrl(url)
    }

    override fun evaluateJavascript(script: String, callback: ((String?) -> Unit)?) {
        webView.evaluateJavascript(script) { result -> callback?.invoke(result) }
    }

    override fun awaitFirstFrame(action: () -> Unit) {
        if (painted) { action(); return }
        if (paintPending) return          // 已经在等了，避免重复注册
        paintPending = true
        pendingPaintAction = action

        // ⚠️ 第一个参数是 requestId，回调会原样带回 —— 它只是个标识，
        //   不是 layerType（写成 LAYER_TYPE_* 会让人误以为有语义）。
        paintRequestId++
        webView.postVisualStateCallback(
            paintRequestId,
            object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    if (painted) return
                    painted = true
                    paintPending = false
                    val a = pendingPaintAction
                    pendingPaintAction = null
                    a?.invoke()
                }
            },
        )
    }

    override fun destroy() {
        pendingPaintAction = null
        webView.destroy()
    }

    /** 供宿主读 cookie 用（下载要带 HttpOnly cookie，见 `MainActivity.handleDownload`）。 */
    fun cookieFor(url: String): String = CookieManager.getInstance().getCookie(url).orEmpty()

    private companion object {
        /** 本地引擎的 URL 前缀；其余一律当外链。 */
        const val LOCAL_ENGINE_PREFIX = "http://127.0.0.1"
    }
}

/**
 * 把 [PageBridge] 包成 `@JavascriptInterface` 能认的形态。
 *
 * ⚠️ 之所以要包一层而不是给接口本身打注解：注解只能落在**实现类的方法**上，
 *   而 [PageBridge] 是接口，两个 flavor 的实现类位置不同。包一层还能保证
 *   `window.DSHAndroid` 上暴露的方法名**恰好**是页面侧用的那 16 个，
 *   不多不少 —— 接口上将来加方法不会静默泄漏到页面里。
 *
 * ⚠️ 方法运行在 WebView 的 JS 线程上，**不能碰 View**；切主线程由 [PageBridge]
 *   的实现负责。
 */
private class SystemPageBridge(private val impl: PageBridge) {

    @android.webkit.JavascriptInterface
    fun onStatus(json: String) = impl.onStatus(json)

    @android.webkit.JavascriptInterface
    fun onImeWillShow() = impl.onImeWillShow()

    @android.webkit.JavascriptInterface
    fun startWirelessPairing() = impl.startWirelessPairing()

    @android.webkit.JavascriptInterface
    fun cancelWirelessPairing() = impl.cancelWirelessPairing()

    @android.webkit.JavascriptInterface
    fun pairingNotificationsEnabled(): Boolean = impl.pairingNotificationsEnabled()

    @android.webkit.JavascriptInterface
    fun onUiReady() = impl.onUiReady()

    @android.webkit.JavascriptInterface
    fun reportWebError(message: String) = impl.reportWebError(message)

    @android.webkit.JavascriptInterface
    fun switchToTcpMode() = impl.switchToTcpMode()

    @android.webkit.JavascriptInterface
    fun bubbleState(): String = impl.bubbleState()

    @android.webkit.JavascriptInterface
    fun bubbleSetEnabled(enabled: Boolean) = impl.bubbleSetEnabled(enabled)

    @android.webkit.JavascriptInterface
    fun bubbleRequestOverlay() = impl.bubbleRequestOverlay()

    /**
     * 会话转录上报。
     *
     * ⚠️ 运行在 WebView 的 JS 线程上 —— `FloatingBubble.onTranscript()` 内部切主线程，
     *   这里不能碰 View。
     */
    @android.webkit.JavascriptInterface
    fun onTranscript(json: String) = impl.onTranscript(json)

    /**
     * 插件下发悬浮球外观配置。
     *
     * ⚠️ system 版是**同步**返回 Boolean 的（`addJavascriptInterface` 支持基本类型），
     *   所以插件能立刻知道自己写错了没有。gecko 版只能异步，见那里的注释。
     */
    @android.webkit.JavascriptInterface
    fun bubbleApply(json: String): Boolean = impl.bubbleApply(json)

    @android.webkit.JavascriptInterface
    fun bubbleAppearance(): String = impl.bubbleAppearance()

    @android.webkit.JavascriptInterface
    fun rootState(): String = impl.rootState()

    @android.webkit.JavascriptInterface
    fun rootRecheck() = impl.rootRecheck()
}
