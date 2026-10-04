package com.dsh.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
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

    /**
     * 最近一次已应用的页面底色。
     *
     * ⚠️ 由 `StatusBridge`（WebView 的 JS 线程）读写，必须 `@Volatile`；
     * 用 String 而不是 Int 是为了能区分"还没收到过"（空串）和"收到过黑色"。
     */
    @Volatile
    private var appliedBgHex = ""

    // ══ 修复 65：键盘升起时输入框被盖住 ══════════════════════════════════
    // 现象：点输入框后键盘从底部升起，但输入框要等约 200ms 才跳上去，
    //      中间几帧被键盘压住。
    //
    // 真机实测（Huawei CDY-TN90 / Android 10，各 2~3 轮一致）：
    //   · focus → 窗口变矮 = 180~220ms
    //   · 期间窗口高度 764 → 475，**只有 1 个 resize、无中间帧**
    //   · visualViewport 与 innerHeight 同一时刻变化；没有更早的页面信号
    //   · navigator.virtualKeyboard.boundingRect 恒为 0
    // → 这 200ms 内页面拿不到键盘高度，只能干等窗口变矮。
    //   逐帧 insets 回调要 API 30 的 WindowInsetsAnimation，本机（API 29）没有。
    //
    // 修法：页面在 `focusin`（点击那一刻，必然早于 insets）通知原生，
    //   原生按**上次实测的键盘高度**先把 WebView 变矮；真 insets 到达后校正并
    //   顺手把这次的实测高度记下来。所以是「预判 + 自学习」：
    //   高度因机器/输入法而异，不写死；第一次没有缓存值，仍会闪一下。

    /**
     * 上一次实测的键盘高度（px）。0 = 还没有缓存值，此时不做预判。
     *
     * ⚠️ 必须**持久化**（见 `loadImeHeight` / `saveImeHeight`）：只放内存的话，
     *   App 每次冷启动都会丢掉，于是"每次开机第一次点输入框都要闪一下"。
     */
    @Volatile
    private var lastImeHeightPx = 0

    /** 是否处于「预判缩矮」状态（此时 WebView 被手动改过 bottomMargin）。 */
    private var imePrejudging = false

    /**
     * 静止时的根视图高度（px），取历史最大值。
     *
     * 键盘只会让内容区变矮、不会变高，所以"历史最大"就是无键盘时的全高。
     * 与插件侧 `setupKeepFocusGuard` 的 `restVh` 是同一套判据（那里已真机验证）。
     */
    private var restHeightPx = 0

    /** 上一次看到的根视图宽度（px）；用于转屏时重置 `restHeightPx` 并换一份缓存。 */
    private var lastRootWidthPx = 0

    /** 预判兜底：超时仍未等到真 insets（说明键盘其实没弹）就恢复原状。 */
    private val imeHandler = Handler(Looper.getMainLooper())
    private val imeRestoreRunnable = Runnable { restoreFromPrejudge("超时未见 insets") }

    /** 启动层超时的 handler 与 runnable（见 [armOverlayTimeout]）。 */
    private val overlayHandler = Handler(Looper.getMainLooper())
    private val overlayTimeoutRunnable = Runnable {
        if (!overlayDismissed) {
            Log.w(DshApplication.TAG, "界面就绪回调超时，强制收起启动动画")
            hideOverlay()
        }
    }

    /**
     * 启动层是否已经收起。
     *
     * ⚠️ 一旦置位就不再放动画回来：引擎重启、`uiStage` 重复推送都不该让启动页
     *   重新盖住用户正在用的界面。
     */
    private var overlayDismissed = false

    /**
     * 键盘高度缓存的 key。
     *
     * ⚠️ 竖屏与横屏的键盘高度不同（横屏键盘更矮），必须分开存，
     *   否则转屏后预判会缩错高度。
     */
    private fun imePrefsKey(): String =
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            "ime_height_land"
        } else {
            "ime_height_port"
        }

    private fun loadImeHeight() {
        val v = getSharedPreferences(IME_PREFS, MODE_PRIVATE).getInt(imePrefsKey(), 0)
        lastImeHeightPx = if (v > 0) v else 0
    }

    private fun saveImeHeight(px: Int) {
        if (px <= 0) return
        getSharedPreferences(IME_PREFS, MODE_PRIVATE).edit().putInt(imePrefsKey(), px).apply()
    }

    /**
     * 页面通知「输入框即将获得焦点」——这是比键盘 insets 更早的信号。
     *
     * ⚠️ 运行在 WebView 的 JS 线程，必须切主线程再碰 View。
     */
    private fun onImeWillShow() {
        runOnUiThread { applyImePrejudge() }
    }

    /**
     * 用缓存高度提前把 WebView 缩矮，抵消「窗口变矮滞后约 200ms」造成的遮挡。
     *
     * 只改 WebView 的 `bottomMargin`，**不动窗口本身** —— 等真 insets 到达时
     * `adjustResize` 会正常缩小窗口，我们把 margin 归零即可无缝交接。
     */
    private fun applyImePrejudge() {
        if (imePrejudging) return
        val cached = lastImeHeightPx
        if (cached <= 0) return              // 还没学到高度：第一次只能让它闪
        if (currentImeHeightPx() > 0) return // 键盘已经在了：不用预判
        imePrejudging = true
        setWebViewBottomMargin(cached)
        Log.i(DshApplication.TAG, "键盘预判：提前缩矮 ${cached}px")
        imeHandler.removeCallbacks(imeRestoreRunnable)
        imeHandler.postDelayed(imeRestoreRunnable, IME_PREJUDGE_TIMEOUT_MS)
    }

    /** 退出预判状态，把 WebView 的 margin 还给系统布局。 */
    private fun restoreFromPrejudge(reason: String) {
        imeHandler.removeCallbacks(imeRestoreRunnable)
        if (!imePrejudging) return
        imePrejudging = false
        setWebViewBottomMargin(0)
        Log.i(DshApplication.TAG, "键盘预判结束（$reason）")
    }

    private fun setWebViewBottomMargin(px: Int) {
        val lp = binding.webView.layoutParams
        if (lp is ViewGroup.MarginLayoutParams && lp.bottomMargin != px) {
            lp.bottomMargin = px
            binding.webView.layoutParams = lp
        }
    }

    /**
     * 当前键盘占用的高度（px）；0 = 键盘不可见。
     *
     * ⚠️ **不能**用 `getWindowVisibleDisplayFrame()`：`adjustResize` 下窗口本身
     *   已经被缩小，可见区域始终等于窗口高度，算出来恒为 0。
     *   这里改用「静止高度 − 当前高度」，与插件侧已验证的判据一致。
     */
    private fun currentImeHeightPx(): Int {
        if (restHeightPx <= 0) return 0
        val hidden = restHeightPx - binding.root.height
        return if (hidden > IME_VISIBLE_THRESHOLD_PX) hidden else 0
    }

    /**
     * 监听根视图高度变化 —— 这就是 `adjustResize` 下"键盘来了"的唯一可靠信号。
     *
     * 真机实测：无键盘 764、键盘弹起 475（各 2~3 轮一致，且只有 1 次跳变）。
     */
    private fun observeImeInsets() {
        binding.root.addOnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val h = bottom - top
            val oldH = oldBottom - oldTop
            if (h <= 0) return@addOnLayoutChangeListener

            // ⚠️ 转屏时宽度也变，而"历史最大高度"是竖屏的，会算出一个巨大的
            //   「隐藏高度」被误判成键盘可见。宽度一变就重新起算，
            //   并换用该朝向的键盘高度缓存（横竖屏键盘高度不同）。
            val w = v.width
            if (w != lastRootWidthPx) {
                lastRootWidthPx = w
                restHeightPx = h
                loadImeHeight()
                return@addOnLayoutChangeListener
            }
            if (h > restHeightPx) restHeightPx = h

            val ime = currentImeHeightPx()
            if (ime > 0) {
                // 自学习：记下这台机器 + 当前输入法的真实高度，并**落盘**
                if (ime != lastImeHeightPx) {
                    lastImeHeightPx = ime
                    saveImeHeight(ime)
                    Log.i(DshApplication.TAG, "键盘高度已学习并保存: ${ime}px")
                }
                // 真 insets 到了：把预判设的 margin 交还给系统
                //（不归零的话 WebView 会比窗口再矮一截）
                if (imePrejudging) restoreFromPrejudge("真 insets 已到 ${oldH}→${h}")
            }
            // ⚠️ 这里**故意不写**「布局变化但 ime==0 就撤销预判」的分支：
            //   预判自己改 WebView 的 layoutParams 会让根视图重新布局并触发本回调，
            //   那种情况下 ime 必然为 0，一撤销就等于预判从未生效。
            //   真正的"点了输入框但键盘没弹"由 IME_PREJUDGE_TIMEOUT_MS 兜底。
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        engine = EngineManager.get(this)
        loadImeHeight()   // 修复 65：把上次学到的键盘高度取回来（跨冷启动有效）

        setupWebView()
        observeEngine()
        observeImeInsets()   // 修复 65：键盘高度自学习 + 预判交接
        // 前台服务要在引擎启动前拉起：它同时是「进程不被回收」和 WakeLock 的持有者，
        // 晚一步的话，引擎刚起就切后台仍可能被系统盯上。
        EngineService.start(this)
        ensureBatteryExempt()
        ensureStorageAccess()
        bootEngine()
    }

    /**
     * 外部存储读写权限的结果。
     *
     * 只记日志。拒绝不阻塞任何功能：`bash` 仍走 app 身份、`shell` 仍走 uid 2000，
     * 只是两者之间传文件要绕道 app 自己的外部目录
     * （`/sdcard/Android/data/com.dsh.android/files/`，那里的属主是 app 自己）。
     * 所以不做二次追问。
     */
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        Log.i(DshApplication.TAG, "外部存储权限结果: $granted")
    }

    /**
     * 申请外部存储读写。
     *
     * **只为一件事**：让 app 身份读得到 uid 2000（`shell` 工具）写进 `/sdcard` 的文件。
     *
     * uid 2000 在 `/sdcard` 上建的文件属主是 `root:sdcard_rw`、模式 `640` —— 这是
     * sdcardfs 的固定行为，`chmod 666` 改不动（真机实测）。而 app 进程默认的附加组里
     * 没有 `sdcard_rw`（实测引擎进程 `Groups: 3003 9997 20324 50324`），于是 `shell`
     * 截的图 `bash` 读不了，报 Permission denied。
     *
     * ⚠️ 机制**不是**「授权后进程多一个 GID」—— 实测同一个 pid 在授权前后 `Groups`
     * 一个字节都没变。真正变的是 **sdcardfs 按权限把 `/storage/emulated` 绑到不同的
     * runtime 视图**（真机 A/B，同一个进程）：
     *
     *     未授权 -> /storage/emulated ... gid=1015(sdcard_rw), mask=6   ← default 视图
     *     已授权 -> /storage/emulated ... gid=9997(everybody),  mask=7   ← write 视图
     *     对照：launcher（无写权限）-> gid=9997, mask=23
     *
     * 进程自带 9997 组、**不带** 1015 —— 未授权时它落在 gid=1015 的 default 视图里，
     * 组对不上，读不了；授权后切到 gid=9997 的 write 视图，组对上了，再配合
     * `derive_gid`（底层文件的 gid 1015 被派生映射成视图的 gid 9997），就凭自带的
     * 9997 组读得到那个 `root:sdcard_rw 640` 的文件。
     *
     * targetSdk = 28，系统仍走 legacy 存储模型（无分区存储），这两个权限在
     * Android 10 上照旧生效，不需要 MANAGE_EXTERNAL_STORAGE。
     */
    private fun ensureStorageAccess() {
        val needed = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) {
            Log.i(DshApplication.TAG, "外部存储权限已就绪")
            return
        }
        Log.i(DshApplication.TAG, "申请外部存储权限: ${needed.joinToString()}")
        storagePermissionLauncher.launch(needed.toTypedArray())
    }

    /**
     * 把本 App 加进「忽略电池优化」白名单。
     *
     * 前台服务 + WakeLock 只解决 Android 原生框架的回收，**国产 ROM 另有一套后台冻结**
     * （华为的「应用启动管理」、OPPO 的「耗电保护」），息屏几分钟就把进程冻住，
     * 长任务照样断。这个白名单是唯一由系统认可的免冻结标记，且必须由用户手动确认。
     *
     * 只在**尚未豁免**时弹一次，已豁免则静默返回，不反复打扰。
     */
    private fun ensureBatteryExempt() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Log.i(DshApplication.TAG, "已在电池优化白名单中")
            return
        }
        // 没有该 Activity 的 ROM（部分定制系统）会抛 ActivityNotFoundException
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
            Log.i(DshApplication.TAG, "已请求加入电池优化白名单")
        } catch (t: Throwable) {
            Log.w(DshApplication.TAG, "无法请求电池优化白名单: ${t.message}")
        }
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
                // DOM 到了，但移动端 UI 插件仍在激活（实测还有约 1.7s）——
                // 这段宿主本来完全感知不到，上报给启动层，动画才不会提前收尾。
                engine.notifyPageLoaded()
            }
        }

        // 插件用它把「AI 当前在干什么」推给通知栏（见 EngineService.updateStatus）。
        addJavascriptInterface(StatusBridge(), "DSHAndroid")

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
     * 页面 → 原生 的状态通道，暴露成 `window.DSHAndroid`。
     *
     * 只有本地引擎会被加载进这个 WebView（`shouldOverrideUrlLoading` 把外链全丢给系统
     * 浏览器），所以不存在"第三方页面拿到这个接口"的风险。
     *
     * ⚠️ 方法**运行在 WebView 的 JS 线程**上，不能碰 View；通知交给
     * `EngineService.updateStatus()`（内部只走 NotificationManager），
     * 底色则 `runOnUiThread` 切回主线程再设。
     */
    private inner class StatusBridge {
        @android.webkit.JavascriptInterface
        fun onStatus(json: String) {
            val status = EngineStatus.parse(json) ?: return
            EngineService.updateStatus(this@MainActivity, status)
            // 只在底色真变了时才切主线程，避免每次状态刷新都白 post 一个任务
            val bg = status.bg
            if (bg.isNotEmpty() && bg != appliedBgHex) {
                appliedBgHex = bg
                runOnUiThread { applyPageBackground(bg) }
            }
        }

        /**
         * 输入框即将获得焦点 —— 比键盘 insets 早约 200ms 的信号（见修复 65）。
         *
         * ⚠️ 运行在 WebView 的 JS 线程，`onImeWillShow()` 内部会切主线程。
         */
        @android.webkit.JavascriptInterface
        fun onImeWillShow() {
            this@MainActivity.onImeWillShow()
        }

        /**
         * 设置页点「配对无线调试」—— 发起 Android 11+ 的无线调试配对。
         *
         * ⚠️ 走 JS 桥而不是宿主半的 HTTP 路由：配对码要经**通知栏**输入，
         * 而通知是 Android 的能力，Node 侧够不到。这里立即返回，
         * 真正的发现在后台协程里做（见 [AdbPairingNotifier.start]）。
         *
         * 运行在 WebView 的 JS 线程，所以只做「发一条通知」这种轻量动作。
         */
        @android.webkit.JavascriptInterface
        fun startWirelessPairing() {
            Log.i(DshApplication.TAG, "设置页请求配对无线调试")
            AdbPairingNotifier.start(this@MainActivity)
        }

        /** 设置页取消配对（撤掉那条带输入框的通知）。 */
        @android.webkit.JavascriptInterface
        fun cancelWirelessPairing() {
            AdbPairingNotifier.cancel(this@MainActivity)
        }

        /**
         * 移动端 UI 插件激活完成 —— 界面真正可用的时刻。
         *
         * 这是启动动画该收尾的信号：`onPageFinished` 只代表 DOM 到了，实测之后
         * 还有约 1.7 秒的插件激活期（前端要解析 5.5MB 的 client.js 并挂载）。
         *
         * ⚠️ 运行在 WebView 的 JS 线程；`notifyUiReady` 只推一个 StateFlow，
         *   真正的收起动作由 `lifecycleScope` 在主线程做。
         */
        @android.webkit.JavascriptInterface
        fun onUiReady() {
            Log.i(DshApplication.TAG, "插件已激活（onUiReady）")
            engine.notifyUiReady()
        }

        /**
         * 设置页「切到固定端口」：把 adbd 切到 `adb tcpip 5555`。
         *
         * 切完之后特权通道**不再依赖无线调试开关**（实测关掉开关、关掉 Wi-Fi
         * 都不影响）。这是个阻塞操作（几秒），所以丢到后台线程，结果用 Toast 回。
         *
         * ⚠️ 不能直接在 JS 线程里做：它最多要等约 6 秒（切换 + 轮询端口就绪），
         * 会卡住 WebView 的 JS 线程。
         */
        @android.webkit.JavascriptInterface
        fun switchToTcpMode() {
            Log.i(DshApplication.TAG, "设置页请求切到固定 TCP 端口")
            val app = applicationContext
            Thread {
                val engine = EngineManager.get(app)
                val result = engine.switchToTcpMode()
                Log.i(DshApplication.TAG, "切到固定端口结果: ${result.ok} ${result.message}")
                runOnUiThread {
                    Toast.makeText(app, result.message, Toast.LENGTH_LONG).show()
                }
            }.start()
        }
    }

    /**
     * 把页面实际底色同步到 WebView 与根容器，消除键盘升起时的异色闪块。
     *
     * **为什么会闪**：`adjustResize` 让窗口变矮是逐帧动画的，WebView 跟着变矮，
     * 但页面重排要等下一帧 —— 这个空档里新露出的那条区域还没有内容覆盖，显示的
     * 就是 WebView 自己的背景色。原先写死的 `#0F1115` 与官方白色页面不一致，
     * 于是表现为"点输入框时下半屏先黑一下"。
     *
     * ⚠️ 颜色由页面上报，**不能写死**：官方主题可切换，写死只是把问题换个方向暴露。
     * ⚠️ 必须在主线程调用（`runOnUiThread`）。
     */
    private fun applyPageBackground(bg: String) {
        val color = try {
            Color.parseColor(bg)
        } catch (t: Throwable) {
            Log.w(DshApplication.TAG, "忽略非法页面底色: $bg")
            return
        }
        binding.webView.setBackgroundColor(color)
        // 根容器一起改：覆盖层隐藏与 WebView 首次绘制之间露出的就是它
        binding.root.setBackgroundColor(color)
        // 窗口底色也跟上：DecorView 与 WebView 是两层独立的合成，窗口缩小时
        // 两层各自的 Surface 更新并不同步，只改一层仍可能露出一帧旧色
        window.setBackgroundDrawable(ColorDrawable(color))
    }

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
                        showOverlay("准备中…", hint = "正在初始化本地环境")
                    }
                    is EngineManager.State.Extracting -> {
                        // 首次启动唯一有真实百分比的一段（实测约 15.5s）
                        showOverlay(
                            "首次启动，正在解包引擎 ${state.percent}%",
                            hint = "只需这一次，之后启动会快很多",
                            progress = state.percent / 100f,
                        )
                    }
                    is EngineManager.State.Starting -> {
                        showOverlay("正在启动引擎…", hint = "加载 Node 运行时与本地服务")
                    }
                    is EngineManager.State.Ready -> {
                        // ⚠️ 这里**不能**收起 overlay：引擎就绪只代表 HTTP 服务起来了，
                        //   页面还没加载、插件更没激活。实测后面还有约 3.8 秒。
                        showOverlay("正在加载界面…", hint = "渲染 Web GUI")
                        if (binding.webView.url != state.url) {
                            Log.i(DshApplication.TAG, "加载 Web GUI: ${state.url}")
                            binding.webView.loadUrl(state.url)
                            // ⚠️ 计时必须**从这一刻**起算，不能放在 onCreate。
                            //   引擎启动本身要 7~10 秒（首次还要先解包 15 秒），
                            //   从 onCreate 起算会让兜底在引擎还没起来时就触发，
                            //   把启动层提前收掉 —— 真机实测踩到过（超时 8s 触发于
                            //   引擎就绪前 2.5s）。这里只兜「页面加载 + 插件激活」，
                            //   实测共约 3.8 秒。
                            armOverlayTimeout()
                        }
                    }
                    is EngineManager.State.Failed -> {
                        showOverlay(
                            "引擎启动失败",
                            hint = state.message,
                            isError = true,
                        )
                    }
                }
            }
        }
        // 页面阶段：引擎就绪之后还有两段，宿主原本完全感知不到
        lifecycleScope.launch {
            engine.uiStage.collect { stage ->
                when (stage) {
                    is EngineManager.UiStage.None -> Unit
                    is EngineManager.UiStage.PageLoaded -> {
                        // 实测 onPageFinished 之后还有约 1.7s 插件激活期
                        showOverlay("正在准备界面…", hint = "激活移动端适配插件")
                    }
                    is EngineManager.UiStage.UiReady -> {
                        Log.i(DshApplication.TAG, "界面已就绪，收起启动动画")
                        hideOverlay()
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
                showOverlay("引擎启动异常", hint = t.message, isError = true)
            }
        }
    }

    /**
     * 显示启动层并更新文案。
     *
     * @param text     阶段名（主文案）
     * @param hint     补充说明；null 则隐藏该行
     * @param progress 解包进度 0..1；null 表示当前阶段无真实进度（只显示底环）
     * @param isError  失败态：环收成红色、不显示进度
     */
    private fun showOverlay(
        text: String,
        hint: String? = null,
        progress: Float? = null,
        isError: Boolean = false,
    ) {
        // 已经收起来过（淡出完成）就不再弹回来 —— 否则引擎重启会让动画闪一下
        if (overlayDismissed && !isError) return

        binding.overlay.animate().cancel()
        if (binding.overlay.visibility != View.VISIBLE) {
            binding.overlay.alpha = 1f
            binding.overlay.visibility = View.VISIBLE
        }
        binding.overlayText.text = text
        binding.overlayText.setTextColor(
            if (isError) Color.parseColor("#FF8A8A") else Color.parseColor("#8FA3C0")
        )
        binding.overlayHint.text = hint.orEmpty()
        binding.overlayHint.visibility = if (hint.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.bootAnim.setProgress(if (isError) -1f else (progress ?: -1f))
    }

    /**
     * 淡出并收起启动层。
     *
     * ⚠️ 必须淡出，不能直接 `GONE`：这一层是不透明的，硬切会让「深色启动页 →
     *   已渲染的 WebView」出现一次明显的闪白/闪黑。淡出同时让 WebView 的
     *   首帧在下面先画好，交接才自然。
     *
     * ⚠️ 淡出期间 `overlayDismissed` 立刻置位，防止后续状态回调（引擎重启、
     *   `uiStage` 重复推送）把动画又弹回来。
     */
    private fun hideOverlay() {
        if (overlayDismissed) return
        overlayDismissed = true
        binding.overlay.animate()
            .alpha(0f)
            .setDuration(OVERLAY_FADE_OUT_MS)
            .withEndAction {
                binding.overlay.visibility = View.GONE
                binding.overlay.alpha = 1f
            }
            .start()
    }

    /**
     * 启动层兜底超时。
     *
     * 插件激活的完成信号（`onUiReady`）来自 WebView 的 JS 桥，**它是不可靠的**：
     * 插件版本不匹配、JS 抛异常、桥被清理，任何一种都会让那个回调永远不来，
     * 于是启动层永久盖住界面 —— 那是最糟的失败方式（看起来像卡死）。
     *
     * 所以给一个上限：到点无条件收起。真机实测激活约 1.7 秒，取 8 秒留足余量，
     * 只有真出问题时才会走到这里。
     */
    private fun armOverlayTimeout() {
        overlayHandler.postDelayed(overlayTimeoutRunnable, UI_READY_TIMEOUT_MS)
    }

    override fun onDestroy() {
        // 引擎跟随进程存活，这里不停止，避免转屏/重建时反复冷启动。
        imeHandler.removeCallbacks(imeRestoreRunnable)
        overlayHandler.removeCallbacks(overlayTimeoutRunnable)
        super.onDestroy()
    }

    private companion object {
        /** 键盘高度缓存的 SharedPreferences 文件名。 */
        const val IME_PREFS = "dsh_ime"

        /**
         * 判定"键盘可见"的阈值（px）。
         *
         * 真机实测键盘高 289 CSS px × 3 = 867 物理 px，取 150 留足余量：
         * 只用来区分"有键盘"和"没键盘"，不参与高度计算。
         */
        const val IME_VISIBLE_THRESHOLD_PX = 150

        /**
         * 预判兜底超时（ms）。
         *
         * 实测真 insets 在 180~220ms 到达。取 700ms：即便本次点输入框没弹键盘
         * （比如只做选择操作），也不会让 WebView 长时间保持错误高度。
         */
        const val IME_PREJUDGE_TIMEOUT_MS = 700L

        /** 启动层淡出时长（ms）。太快像硬切，太慢显得拖沓。 */
        const val OVERLAY_FADE_OUT_MS = 420L

        /**
         * 界面就绪的兜底超时（ms）。
         *
         * ⚠️ 计时起点是「开始 `loadUrl`」那一刻（见 `observeEngine`），**不是**
         *   `onCreate` —— 引擎启动要 7~10 秒、首次还要先解包 15 秒，
         *   从 onCreate 起算会让兜底在引擎还没起来时就触发。
         *
         * 实测这段（页面加载 + 插件激活）共约 3.8 秒，取 12 秒留足余量：
         * 只在 `onUiReady` 回调丢失时才生效（插件异常、桥不可用），
         * 目的是避免启动层永久盖住界面。
         */
        const val UI_READY_TIMEOUT_MS = 12000L
    }
}
