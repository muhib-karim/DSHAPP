package com.dsh.android.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
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
        // ── 远程调试通道（给 UI 调试工具用，对用户不可见）────────────────
        //
        // 打开它，`webview_devtools_remote_<pid>` 这个抽象 socket 才会出现，
        // 于是**同一个页面世界**可以经 CDP 被读和点 —— 也就是 `ui` 工具那条
        // 信箱链路的另一半（原生 → 探针）可以在真机上被独立验证。
        //
        // ⚠️ 这不改变任何界面行为：它只多开一个**只监听本机**的调试 socket，
        //   不开任何端口、不监听网络，页面与用户看到的东西一模一样。
        //   Android 官方的定位就是「开发期开关」，此处常开是因为这个 App
        //   本身就是自用调试机 —— 而 `ui` 工具在没有它的时候无法被验证。
        WebView.setWebContentsDebuggingEnabled(true)
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
            // ⚠️ **必须开多窗口**，否则页面里的 `window.open` 被内核直接丢弃。
            //
            // 根因链（三处叠加，缺一不可）：
            //   1. 官方 `dsh-client-ui-chat` 的 `linkOpening` 默认值是 `"sidebar"`；
            //   2. 但侧栏浏览器插件 `@deepseek-ai/dsh-client-ui-sidebar-browser` 在
            //      `dsh-web-app/cordis.patch.yml` 里被 `disabled: !!js
            //      "ctx.get('profileContext')?.name !== 'desktop'"` 关掉（本 App 是
            //      web profile，不是 desktop）；
            //   3. 于是 `openExternalLink` 走 else 分支 —— 源码原文
            //      `window.open(url, "_blank", "noopener,noreferrer")`。
            //
            // 而 WebView 在 `setSupportMultipleWindows(false)`（**默认值**）时，
            // `window.open` 会**静默失败**：不报错、不打日志、不触发
            // `shouldOverrideUrlLoading`。用户看到的就是「AI 给的链接点不动，
            // 只能复制到浏览器」。
            //
            // ⚠️ 只开这一项**不够** —— 开了之后 `window.open` 会走
            //   `WebChromeClient.onCreateWindow`，而那个方法的**默认实现同样返回
            //   false 表示"不创建新窗口"**。两个必须配对改，见下面的 onCreateWindow。
            setSupportMultipleWindows(true)
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

            /**
             * 渲染进程死了。
             *
             * ## ⚠️ 不覆写这个方法，默认行为是**杀掉整个 App**
             *
             * 官方文档写得很直白：`onRenderProcessGone` 的默认实现返回 `false`，
             * 于是框架把**宿主应用的整个进程**一并终止。用户看到的就是「App 突然
             * 自己退了」，且没有任何日志能指向真正的死因（进程都没了）。
             *
             * 覆写并返回 `true` = 「这个 WebView 的坏状态我认了，我自己收拾」，
             * 框架就不会杀进程。
             *
             * ## 两种触发，都不是 App 的 bug
             *
             * - `didCrash = true`：渲染进程自己崩了（内核 bug、OOM）。
             * - `didCrash = false`：**被系统回收**。低内存时系统会主动收掉后台
             *   WebView 的渲染进程以省内存，这是正常的资源管理，不是错误。
             *
             * 两种情况下这个 WebView 都已经画不出东西了，界面必须重载才会回来
             * （见 `MainActivity.onRendererGone`）。
             *
             * ⚠️ 返回 `true` 之后**必须**用 `destroy()` 或重载来收拾这个 WebView：
             *   官方要求「不再使用它」。这里走重载 —— 同一个 `WebView` 实例在
             *   `onRenderProcessGone` 之后重新 `loadUrl` 是可行的，内核会为它
             *   拉起一个新的渲染进程（这正是「恢复」的实现方式）。
             */
            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail,
            ): Boolean {
                Log.w(
                    DshApplication.TAG,
                    "WebView 渲染进程已终止（didCrash=${detail.didCrash()}，" +
                        "rendererPriorityAtExit=${detail.rendererPriorityAtExit()}）",
                )
                callbacks.onRendererGone(detail.didCrash())
                // true = 已处理，别杀我的进程（见上面的 ⚠️）
                return true
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
             * 页面请求麦克风 —— **必须覆写**。
             *
             * ⚠️ 默认实现直接 `deny()`：不弹框、不打日志、页面只拿到
             *   `NotAllowedError`。前端把那个错误翻译成 `RecordingError("permission")`，
             *   界面上永远显示「麦克风权限被拒绝」—— **即使 Android 权限早就授过了**。
             *   这是本项目第三处「默认实现静默失败」（前两处是 ⑦ 的文件选择器、
             *   ⑦.5 的下载器）。
             *
             * ## 只放行音频，别的照旧拒绝
             *
             * 本 App 只有一个页面、只用到录音。摄像头、地理位置、通知这些内核
             * 会来问的权限**一个都不放行** —— 收紧到「只开需要的那一个」，
             * 比"反正都是自己人、全放行"安全得多，而且以后加权限时也必须
             * 显式写在这里，不会静默变成放行。
             *
             * ⚠️ **`grant()` 必须在 Android 权限真的拿到之后才调**（见
             *   [MicrophoneChannel]）：先放行、后申请的话，内核认为已经批准、
             *   音频却录不到，症状变成「录出来是静音」—— 那比直接拒绝难查得多。
             */
            override fun onPermissionRequest(request: PermissionRequest) {
                val wantsMic = request.resources.any {
                    it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                }
                if (!wantsMic) {
                    Log.i(
                        DshApplication.TAG,
                        "页面请求内核权限 ${request.resources.joinToString()}，不属于麦克风，拒绝",
                    )
                    request.deny()
                    return
                }
                Log.i(DshApplication.TAG, "页面请求麦克风，转交宿主申请 Android 权限")
                callbacks.microphone.request { granted ->
                    // ⚠️ 回调可能在任意线程（宿主那边走了权限 launcher 的线程）
                    webView.post {
                        if (granted) {
                            Log.i(DshApplication.TAG, "麦克风授权：允许")
                            // ⚠️ `grant()` 吃的是**要放行的资源列表**，不是 origin
                            //   也不是布尔值 —— 签名是 `grant(String[] resources)`。
                            //   把 `request.resources` 原样回传即可（上游就是这么做
                            //   的，见 WebView 文档的 onPermissionRequest 示例）。
                            request.grant(request.resources)
                        } else {
                            Log.w(DshApplication.TAG, "麦克风授权：用户拒绝")
                            request.deny()
                        }
                    }
                }
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

            /**
             * 接管 `window.open` —— 官方前端打开外链走的就是这条路。
             *
             * ## 为什么需要它
             *
             * `setSupportMultipleWindows(true)` 只解决「内核不再丢弃这次调用」，接下来
             * 内核会回调本方法，而**默认实现返回 false = 不创建新窗口**，页面依然什么
             * 都看不到。两处必须配对（见 `settings` 里的注释）。
             *
             * ## 做法：一律交给系统浏览器，**不在应用内开第二个窗口**
             *
             * 本 App 只有一个 WebView、一个 `WebHostCallbacks`，再开一个窗口意味着
             * 要管理第二个内核实例的生命周期、第二个 `DSHAndroid` 桥、返回键归属 ——
             * 而用户想要的只是「点开链接看到内容」，系统浏览器本来就干得更好。
             *
             * ⚠️ **必须从 `WebView.HitTestResult` 里取目标 URL**，不能用最后一个
             *   参数 `resultMsg` —— 那条路给的是 `Handler` 消息（可能带一个空
             *   `WebView`），拿不到 URL。标准做法就是读 `view.hitTestResult.extra`。
             *
             * ⚠️ **拿到 URL 后必须回 `false`**（不创建窗口），否则内核会认为我们接管了，
             *   等着我们往那个空 `WebView` 里 `loadUrl`，结果是一个白窗口。
             */
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message,
            ): Boolean {
                // 目标 URL 只能从 hitTestResult 里拿（见上面的 ⚠️）。
                val url = view.hitTestResult?.extra
                if (url.isNullOrEmpty()) {
                    // 拿不到 URL（例如页面用 `window.open("")` 或异步触发）：
                    // 记一笔日志就放弃。**不能**回 true —— 那会留下一个白窗口。
                    Log.w(DshApplication.TAG, "window.open 拿不到目标 URL，忽略")
                    return false
                }
                // 本地引擎的 URL 不该走这里（那是 SPA 内部导航）。真出现时回 false
                // 让内核丢弃这次新窗口请求，页面自身的导航不受影响。
                if (url.startsWith(LOCAL_ENGINE_PREFIX)) {
                    Log.i(DshApplication.TAG, "window.open 指向本地引擎，忽略: $url")
                    return false
                }
                Log.i(DshApplication.TAG, "window.open 外链转系统浏览器: $url")
                callbacks.onExternalUrl(url)
                return false
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

    /**
     * 重新加载当前 URL（渲染进程崩溃后的恢复路径）。
     *
     * ⚠️ **不接受「没有 URL」这一步**：`lastLoadedUrl` 为空说明还没加载过任何
     *   页面，那时崩溃是无从恢复的（引擎都还没起来），静默返回即可 ——
     *   硬 `reload()` 一个空白 WebView 没有任何意义。
     *
     * ⚠️ 重载后要把 `painted` 复位：新渲染进程的第一帧还没提交，启动层的
     *   「等首帧」逻辑若沿用旧的 `painted = true` 会立刻收层，用户就看到白屏。
     */
    override fun reload() {
        val url = lastLoadedUrl
        if (url.isNullOrEmpty()) {
            Log.w(DshApplication.TAG, "渲染进程已死但没有可用的 URL，无法重载")
            return
        }
        Log.i(DshApplication.TAG, "重载界面: $url")
        painted = false
        paintPending = false
        pendingPaintAction = null
        webView.loadUrl(url)
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
