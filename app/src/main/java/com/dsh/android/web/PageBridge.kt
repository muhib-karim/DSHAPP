package com.dsh.android.web

/**
 * 页面 → 原生 的状态通道，页面侧看到的是 `window.DSHAndroid`。
 *
 * ## 为什么要有这个接口
 *
 * 两个 Web 内核版本（`system` = 系统 WebView、`gecko` = 内置 GeckoView）都要把
 * **同一组能力**暴露给页面，但暴露的机制完全不同：
 *
 * | | system | gecko |
 * |---|---|---|
 * | 注入方式 | `addJavascriptInterface(obj, "DSHAndroid")` | WebExtension content script 自己造 `window.DSHAndroid` |
 * | 调用语义 | **同步**（JS 直接拿到返回值） | 只能异步（native messaging） |
 *
 * 所以把「能力」抽成这个接口，两个 flavor 各自实现注入，[MainActivity] 只实现
 * 一次方法体。
 *
 * ⚠️ **方法签名必须与页面侧 `mobile-ui-plugin/dsh-android-mobile/lib/client.js`
 *   里 `window.DSHAndroid` 的用法逐字一致**（返回 String 的、返回 Boolean 的
 *   各有其用），改签名等于改协议。页面侧的具体调用点见各方法注释。
 *
 * ⚠️ 返回 String 的方法（[bubbleState] / [rootState]）返回的都是 **JSON 文本**，
 *   不是对象：`@JavascriptInterface` 只支持基本类型与 String，返回自定义对象
 *   会被序列化成 `undefined`（静默，不报错）。gecko 侧走 JSON 序列化同样返回
 *   文本，两边一致。
 */
interface PageBridge {

    /**
     * 页面状态上报（AI 在干什么 + 当前底色），驱动通知栏与系统栏配色。
     *
     * ⚠️ 运行在内核的 JS 线程上，**不能碰 View** —— 实现里必须自己切主线程。
     */
    fun onStatus(json: String)

    /**
     * composer 的输入框即将获得焦点 —— 比键盘 insets 早约 200ms 的信号。
     *
     * 页面侧在 `focusin` 里发（只在 `[data-composer-input]` 上），原生据此
     * 提前把 Web 视图缩矮，抵消「窗口变矮滞后约 200ms」造成的遮挡。
     */
    fun onImeWillShow()

    /** 设置页点「配对无线调试」。立即返回，真正的发现在后台协程里做。 */
    fun startWirelessPairing()

    /** 设置页取消配对（撤掉那条带输入框的通知）。 */
    fun cancelWirelessPairing()

    /**
     * 配对需要的通知是否可用。
     *
     * ⚠️ 这是**唯一一个同步返回值的非 String 方法**，而 gecko 侧的 native
     * messaging 只能异步 —— 所以 gecko 实现走「原生主动推送 + content script
     * 缓存」来维持同步语义，见 `GeckoPageBridge` 的注释。
     */
    fun pairingNotificationsEnabled(): Boolean

    /** 移动端 UI 插件激活完成 —— 界面真正可用的时刻，启动动画据此收尾。 */
    fun onUiReady()

    /** 页面侧脚本错误上报，用于把真实原因摆到启动层上。只记第一条。 */
    fun reportWebError(message: String)

    /** 设置页「切到固定端口」：把 adbd 切到 `adb tcpip 5555`（阻塞，需后台线程）。 */
    fun switchToTcpMode()

    /** 读悬浮气泡状态。返回 JSON：`{"enabled":bool,"granted":bool}`。 */
    fun bubbleState(): String

    /** 开关悬浮气泡。关掉时立刻收起已显示的窗口。 */
    fun bubbleSetEnabled(enabled: Boolean)

    /** 拉起系统的「显示在其他应用上层」授权页。 */
    fun bubbleRequestOverlay()

    /**
     * 会话转录上报 —— 悬浮窗展开态要显示的**完整**对话内容。
     *
     * 与 [onStatus] 分开的理由见 [com.dsh.android.ChatTranscript] 的头注释：
     * 状态是高频小体量（通知栏也在用），转录是低频大体量（只有展开面板要）。
     *
     * ⚠️ 运行在内核的 JS 线程上，**不能碰 View** —— 实现里必须自己切主线程。
     */
    fun onTranscript(json: String)

    /**
     * 插件下发悬浮球外观配置。返回是否已应用。
     *
     * 这是「让 AI 改悬浮窗，像改自己的 web 界面一样简单」的接缝：任何页面内的
     * 插件都可以直接
     *
     * ```js
     * window.DSHAndroid.bubbleApply(JSON.stringify({ shape: 'square', size: 72 }))
     * ```
     *
     * 字段与语义见 [com.dsh.android.BubbleAppearance]。**未知字段一律忽略**，
     * 非法值退回默认 —— 插件写错不该让悬浮球消失。
     *
     * ⚠️ system 版同步返回 Boolean；gecko 版只能异步，走「乐观返回 true」，
     *   见 `GeckoWebHost` 的注释。
     */
    fun bubbleApply(json: String): Boolean

    /** 读回当前外观配置（JSON 文本）。插件据此做「读取-修改-写回」。 */
    fun bubbleAppearance(): String

    /**
     * 读 root 探测结论。返回 JSON（`known/state/granted/detail/at/mounted`）。
     *
     * ⚠️ **只读缓存、不触发探测** —— 探测最多要等 15 秒，在 JS 线程上做会卡死页面。
     */
    fun rootState(): String

    /** 设置页点「重新检测」：清掉缓存并**在后台**强制重探一次。界面靠轮询 [rootState] 拿结论。 */
    fun rootRecheck()

    /**
     * 设置页点「运行日志与诊断」：拉起原生日志界面（**入口①**）。
     *
     * ## 为什么日志界面在原生侧、而不是做成一个页面插件
     *
     * 因为它要能在「页面根本打不开」的时候用。做成页面插件的话，插件没激活、
     * 前端 bundle 挂了、引擎没起来 —— 这三种情况恰好都是最需要看日志的时候，
     * 而那时页面插件自己也活不了。所以这个界面必须独立于 Web 层。
     *
     * 设置页这条入口覆盖的是另一半场景：**界面能开、但某个功能不正常**
     * （回复报错、插件没生效、点了没反应）。那时用户能进设置页，从这里进去
     * 最顺手。加上启动层那条，两条路合起来覆盖「界面能开」与「界面开不了」。
     *
     * ⚠️ 立即返回，不要阻塞 JS 线程：真正的 `startActivity` 在主线程做。
     */
    fun openLogs()

    /**
     * 读日志摘要，给设置页那一行做副标题。
     *
     * 返回 JSON 文本：
     * ```json
     * {"total":1234,"warn":12,"error":3,"crash":2,"pending":1,"lastExit":"上次正常退出"}
     * ```
     *
     * ⚠️ **必须便宜**：设置页每次打开都会调它，所以只读内存缓冲的计数与
     * `BootState`（一个几十字节的 JSON），**不做**完整诊断（那要读几个 MB 的
     * 文件算行数）。完整诊断只在用户点「导出诊断包」时跑。
     *
     * ⚠️ 同步返回 String（同 [bubbleState]）：`@JavascriptInterface` 只支持
     * 基本类型与 String，返回对象会静默变成 `undefined`。
     */
    fun logSummary(): String
}
