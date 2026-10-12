package com.dsh.android.web

import android.net.Uri
import android.view.View
import java.io.InputStream

/**
 * 「Web 内核」的宿主抽象 —— 两个 flavor 各实现一份。
 *
 * | flavor | 实现 | 内核 |
 * |---|---|---|
 * | `system` | [com.dsh.android.web.SystemWebHost] | 设备上的系统 WebView |
 * | `gecko` | [com.dsh.android.web.GeckoWebHost] | APK 内置的 GeckoView |
 *
 * [com.dsh.android.MainActivity] 只对着这个接口写一次，两个版本共用同一份启动、
 * 键盘预判、返回键、启动层逻辑。
 *
 * ## 为什么值得抽这一层
 *
 * 两个内核的差异**不是「换个控件」那么小**，而是三个结构性差异：
 *
 * 1. **JS 执行**：WebView 有 `evaluateJavascript`（带回调）；GeckoView **没有**
 *    任何等价 API，官方明确要求走 WebExtension。见 [evaluateJavascript]。
 * 2. **原生桥**：WebView 用 `addJavascriptInterface`（**同步**返回）；GeckoView
 *    只能走 native messaging（**异步**）。见 [com.dsh.android.web.PageBridge]。
 * 3. **首帧**：WebView 是 `postVisualStateCallback`；GeckoView 是
 *    `ContentDelegate.onFirstComposite`。见 [awaitFirstFrame]。
 *
 * ⚠️ 所有方法**必须在主线程调用**（两个内核的 View 操作都是 `@UiThread`）。
 */
interface WebHost {

    /**
     * 承载网页的 View，由宿主加进布局容器。
     *
     * 调用方只用它做两件与内核无关的事：改 `layoutParams`（键盘预判缩矮）、
     * `setBackgroundColor`（消除键盘升起时的异色闪块）。
     */
    val view: View

    /** 加载 URL。 */
    fun loadUrl(url: String)

    /**
     * 当前 URL；还没加载过任何页面时为 null。
     *
     * ⚠️ 语义是「最近一次发起加载的 URL」，不是内核内部的重定向结果 ——
     *   宿主只用它做「与上次相同就不重复加载」的去重判断（见 `observeEngine`）。
     */
    val currentUrl: String?

    /**
     * 执行一段 JS，并把结果回给 [callback]。
     *
     * ⚠️ **两个内核的结果编码必须一致**，否则宿主的解析会错。约定：
     *   结果是 **JSON 编码后的文本**（与 WebView 的 `evaluateJavascript` 相同）。
     *   于是 `"true"` / `"false"` / `"NO_INPUT"` 这些页面侧返回值，宿主用
     *   `result.trim('"')` 或 `contains(...)` 都能正确判定。
     *
     * ⚠️ GeckoView 侧这条通道**是异步的、且比 WebView 慢**（要过一次扩展的
     *   IPC）。调用方不应假设它「立刻」回来 —— 但也不该为它加超时：拿不到结果
     *   时的行为已由各调用点自己的兜底分支定义（例如返回键的「桥不存在则退出」）。
     *
     * @param callback 可为 null（不需要结果时）。**不保证一定被调用** ——
     *   GeckoView 的扩展还没就绪、页面已销毁时不会回调，调用方必须有兜底。
     */
    fun evaluateJavascript(script: String, callback: ((String?) -> Unit)? = null)

    /**
     * 等内核把当前内容**真正提交了一帧**，然后执行 [action]。
     *
     * 「DOM 就绪」与「像素上屏」之间有落差（真机实测约 1.6 秒），启动层必须等
     * 后者才收起，否则用户会看到「动画结束后闪过几帧白屏」。
     *
     * ⚠️ **不保证一定触发**（渲染进程崩溃、View 被销毁时不会来），调用方必须
     *   自备兜底超时 —— 启动层永久盖住界面是最糟的失败方式。
     * ⚠️ 已经提交过首帧之后再调用，应当**立刻**执行 [action]。
     */
    fun awaitFirstFrame(action: () -> Unit)

    /** 释放内核资源。 */
    fun destroy()

    /** 内核主版本号；0 = 读不到（此时宿主不做版本拦截）。 */
    val engineMajor: Int

    /** 内核描述（厂商 + 版本），只用于日志。 */
    val engineLabel: String
}

/**
 * 文件选择通道 —— 内核发出请求，宿主拉起系统选择器，再把结果交回内核。
 *
 * ⚠️ **三条路径都必须回调**（成功 / 取消 / 拉起失败），漏掉任何一条都会让页面
 *   永久卡在「等待选择」状态。取消必须回 `null` 而不是空数组 —— 空数组会被
 *   页面解读成「选了 0 个文件」并触发一次空提交。
 */
fun interface FileChooserChannel {
    fun launch(multiple: Boolean, onResult: (Array<Uri>?) -> Unit)
}

/**
 * 一个下载请求。
 *
 * ⚠️ 两个内核**触发下载的机制完全不同**，所以这个类只描述「要下什么」，不描述
 *   「谁触发的」：
 *   - WebView：`setDownloadListener`（`<a download>` 会落到它上面）
 *   - GeckoView：**没有** DownloadListener，改由 content script 拦 `<a download>`
 *     的点击、经原生桥转成一条 [DownloadRequest]
 *
 * 两条路的落点都是 `MainActivity.handleDownload()`，那里已验过 cookie 鉴权
 * （`/api/session.export` 是纯 cookie 鉴权，用系统 DownloadManager 拿不到）。
 */
data class DownloadRequest(
    val url: String,
    val userAgent: String?,
    val contentDisposition: String?,
    val mimeType: String?,
)

/**
 * 一次**内核已经代发**的下载响应（gecko 专用）。
 *
 * ⚠️ 与 [DownloadRequest] 的分工是**本质的**，不是风格差异：
 *   - [DownloadRequest]（system）：内核只给 URL，**宿主自己发 HTTP** ⇒ 宿主必须
 *     自己带上鉴权 cookie。
 *   - [DownloadStream]（gecko）：**内核已经把这次请求发完了**（cookie 由内核自动
 *     带上），宿主拿到的就是响应体流 ⇒ 宿主不需要、也**拿不到** cookie。
 *
 * 后者存在的唯一原因：GeckoView **没有任何读取 cookie 的 API** ——
 * `GeckoSession` / `GeckoRuntime` / `StorageController` 上只有 cookie-banner
 * 相关方法（javap 逐个查过），而 GeckoView 的 cookie 存在自己的
 * `files/mozilla/<profile>/cookies.sqlite` 里、**不写进** WebView 的 cookie jar。
 * 于是「宿主代发 + 自己带 cookie」这条路在 gecko 上根本走不通
 * （实测 gecko 版连 `app_webview/` 目录都不存在，WebView 从未初始化），
 * 只能反过来：**让内核自己发**，宿主只负责落盘。
 */
data class DownloadStream(
    val url: String,
    /** 已从 `Content-Disposition` 解出的文件名；解不出时由宿主兜底。 */
    val filename: String,
    val body: InputStream,
)

/**
 * 内核 → 宿主的回调集合。
 *
 * @property onPageFinished DOM 就绪（`WebViewClient.onPageFinished` /
 *   `ProgressDelegate.onPageStop`）。⚠️ 它**不代表界面可用** —— 实测之后还有约
 *   1.7 秒的插件激活期，那段由 [PageBridge.onUiReady] 收尾。
 * @property onConsole 内核控制台消息，转发到同一个 `DSH` TAG（前缀 `[web]`）。
 * @property onExternalUrl 非本地引擎的 URL（外链），交给系统浏览器。
 * @property onDownload 宿主**自己发 HTTP** 的下载（system 版）。
 * @property onDownloadStream 内核**已经代发**、只剩落盘的下载（gecko 版）。
 *   ⚠️ 两个内核各只会调其中一个 —— 见 [DownloadStream] 的说明。
 */
class WebHostCallbacks(
    val bridge: PageBridge,
    val onPageFinished: (String?) -> Unit,
    val onConsole: (String) -> Unit,
    val onExternalUrl: (String) -> Unit,
    val fileChooser: FileChooserChannel,
    val onDownload: (DownloadRequest) -> Unit,
    val onDownloadStream: (DownloadStream) -> Unit,
)
