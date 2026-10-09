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
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.dsh.android.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import org.json.JSONObject

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
        if (overlayDismissed) return@Runnable
        // ⚠️ 这里**不再静默收起**。原做法是「到点无条件收起」，理由是
        //   「启动层永久盖住界面是最糟的失败方式」—— 但在旧 WebView 内核上，
        //   收起之后露出来的是**一片白屏**（前端 bundle 解析失败、React 没挂载），
        //   用户看到的就是反馈里那个「卡在空白页」，连原因都看不到。
        //   所以改成把原因摆出来，并留一个点击出口（见 onCreate 里的
        //   `overlay.setOnClickListener`）：既说清问题，又不会被永久挡住。
        Log.w(
            DshApplication.TAG,
            "界面就绪回调超时（WebView 内核 $webViewMajor，页面错误: " +
                "${webError.ifEmpty { "无" }}）",
        )
        // 优先显示页面自己报出来的原因（最准确）；没有就退到「内核可能过旧」的推断。
        if (webError.isNotEmpty()) {
            showOverlay("界面加载失败", hint = "$webError（点一下这里可继续）", isError = true)
        } else {
            showOverlay(
                "界面没有加载出来",
                hint = "可能是系统 WebView 内核过旧（当前 $webViewMajor，需要 $MIN_WEBVIEW_MAJOR+）。" +
                    "请更新后重开 App；点一下这里可继续",
                isError = true,
            )
        }
    }

    /**
     * 启动层是否已经收起。
     *
     * ⚠️ 一旦置位就不再放动画回来：引擎重启、`uiStage` 重复推送都不该让启动页
     *   重新盖住用户正在用的界面。
     */
    private var overlayDismissed = false

    /** 当前 WebView 内核的主版本号；0 = 读不到（此时不做版本拦截）。 */
    private var webViewMajor = 0

    /**
     * 内核低于**当前实际生效的门槛** —— 前端 bundle 的语法它解析不了。
     *
     * 门槛不是固定的：`EngineManager.ensureWebViewCompat()` 会把官方 bundle 换成
     * 按 `chrome74` 降级编译的版本，换成功之后 83 这种旧内核也能解析。
     * 所以这里按「降级到底生效了没有」动态判定，见 [effectiveMinWebViewMajor]。
     *
     * 为 true 时不再 `loadUrl`（那只会在 12 秒后露出一片白屏），
     * 改成在启动层上直接说明原因（见 `observeEngine` 的 `Ready` 分支）。
     */
    private var webViewTooOld = false

    /**
     * 当前生效的内核门槛。
     *
     * - 降级前端已就位 → [MIN_WEBVIEW_MAJOR_COMPAT]（chrome74，实测 83 也跑得动）
     * - 降级没打上（资产缺失/解包失败）→ [MIN_WEBVIEW_MAJOR]（官方 bundle 的 94）
     *
     * ⚠️ 降级**失败**时必须退到 94：那时跑的就是官方 bundle，语法缺口原样存在。
     *   宁可提示用户升级内核，也不要放进一片白屏。
     */
    private val effectiveMinWebViewMajor: Int
        get() = if (engine.webViewCompatApplied) MIN_WEBVIEW_MAJOR_COMPAT else MIN_WEBVIEW_MAJOR

    /**
     * 页面侧上报的第一条脚本错误（见 `webview-polyfill.js` 的错误上报）。
     *
     * ⚠️ 由 `StatusBridge`（WebView 的 JS 线程）写、主线程读，必须 `@Volatile`。
     * 空串 = 页面没报过错。
     */
    @Volatile
    private var webError = ""

    /** WebView 是否已经提交过至少一帧（见 [awaitWebViewPainted]）。 */
    private var webViewPainted = false

    /** 是否已有一个等待首帧的回调在飞，避免重复注册。 */
    private var paintPending = false

    /** `postVisualStateCallback` 的请求号，单调递增即可（它只是个标识）。 */
    private var paintRequestId = 0L

    /** 等待 WebView 首帧的兜底超时（见 [awaitWebViewPainted]）。 */
    private val paintTimeoutRunnable = Runnable {
        if (!webViewPainted) {
            Log.w(DshApplication.TAG, "等待 WebView 首帧超时，仍收起启动动画")
            webViewPainted = true
            paintPending = false
            hideOverlay()
        }
    }

    /**
     * 键盘高度缓存的 key。
     *
     * ⚠️ 竖屏与横屏的键盘高度不同（横屏键盘更矮），必须分开存，
     *   否则转屏后预判会缩错高度。
     */
    private fun imePrefsKey(): String = imePrefsKeyOf(this)

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
        detectWebViewVersion()
        setupBackNavigation()
        installBubbleBridge()
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

    /**
     * 读当前 WebView 内核版本，判断它跑不跑得动前端 bundle。
     *
     * **为什么需要这一步**：官方前端 bundle 用了 Chromium 94 才有的语法
     * （class static block），旧内核在**解析阶段**就失败 —— 不是缺某个 API，
     * 而是整份 chunk 一行都不执行。这种失败在页面上完全无声：HTML 和 CSS 都正常，
     * `#root` 却永远是空的，用户只看到白屏，连线索都没有。
     *
     * 这里只负责「读版本 + 记日志 + 置标志」，怎么呈现交给 `observeEngine`。
     *
     * ⚠️ **不能在这里定 `webViewTooOld`**：此刻引擎还没启动，降级 bundle 也就
     *   还没落盘，[effectiveMinWebViewMajor] 会误报成 94。判定挪到
     *   `observeEngine` 的 `Ready` 分支（引擎就绪 = 降级已经打过了）。
     *
     * ⚠️ 读不到版本时**不做拦截**（`webViewMajor = 0`）：宁可维持原行为，
     *   也不能因为一次读取失败就把本来能用的设备挡在门外。
     */
    private fun detectWebViewVersion() {
        val pkg = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        val version = pkg?.versionName.orEmpty()
        webViewMajor = version.substringBefore('.').toIntOrNull() ?: 0
        Log.i(
            DshApplication.TAG,
            "WebView 内核: ${pkg?.packageName} $version" +
                "（官方 bundle 门槛 $MIN_WEBVIEW_MAJOR，降级后 $MIN_WEBVIEW_MAJOR_COMPAT）",
        )
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

    // ══ 系统返回键 ══════════════════════════════════════════════════════
    //
    // 现象：本 App 是一个 WebView 壳，界面里所有的"层级"（设置一级/二级、
    //   各种弹窗、侧栏抽屉、插件面板）**全部活在网页里**，Android 一无所知。
    //   于是系统返回键在**任何**界面下都直接退出 App —— 用户在设置二级页
    //   按返回，期望回一级，结果整个 App 没了。
    //
    // 做法：原生拦下返回键 → 问页面「这一层要不要消费」→ 页面返回 false 才退出。
    //   页面侧的状态机在 `mobile-ui-plugin/dsh-android-mobile/lib/client.js` §12，
    //   它按由内到外的顺序处理：重命名弹窗 → 设置二级 → 设置一级 → 关闭设置
    //   → 官方其它模态弹窗 → composer 菜单 → 侧栏抽屉 → 官方主面板 → 都没有则退出。
    //
    // ⚠️ **不能只靠 `webView.canGoBack()`**：这是个单页应用（SPA），路由不走
    //   history —— 实测从头到尾 `canGoBack()` 恒为 false，用它判等于没做。
    //
    // ⚠️ **回调是异步的**（`evaluateJavascript` 走 WebView 的 JS 线程），所以
    //   不能直接在回调里调 Activity 的复杂操作；这里只在"页面说没得关"时才
    //   收尾，动作单一，安全。
    //
    // ⚠️ **收尾动作是「退到后台」，不是 `finish()`**（0.2.0 起）。本 App 的核心
    //   状态全在 Node 引擎里：`finish()` 会走到 `onDestroy` → 停掉引擎，用户切回来
    //   要重新等引擎启动。而返回键在手机上的语义本来就是「离开这个界面」而不是
    //   「关掉这个应用」—— 启动器、微信、浏览器都是退到后台保留现场；真要退出
    //   可以用最近任务列表划掉。引擎不随 Activity 退后台而停（跑在 EngineService 里）。

    /** 是否有一次"问页面"的往返正在进行中（防止连按返回键叠加调用）。 */
    private var backQueryPending = false

    /**
     * 安装返回键处理。
     *
     * 用 `OnBackPressedCallback` 而不是覆写 `onBackPressed()`：
     * `AppCompatActivity` 会把返回键分发给注册的回调，`onBackPressed()` 那条路
     * 在 API 33+ 已被废弃，且与 `OnBackInvokedDispatcher`（Android 13+ 的
     * 预测式返回手势）不互通 —— 用回调注册才对两条路都生效。
     */
    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                queryPageBack()
            }
        })
    }

    /**
     * 问页面「有没有一层可以退」，有就消费掉，没有才退出 App。
     *
     * 页面侧的 `window.__DSH_MOBILE_BACK__()` 返回布尔值（`evaluateJavascript`
     * 会把它序列化成字符串 `"true"` / `"false"`）。
     *
     * ⚠️ **桥不存在时必须直接退出**，不能"当作页面说 true" —— 那会让返回键
     *   在插件还没激活（或激活失败）时**完全失效**，用户被困在 App 里出不去。
     *   反过来，插件没起来时页面里也确实没有任何自建层级需要关，退出是对的。
     */
    private fun queryPageBack() {
        val wv = binding.webView
        if (backQueryPending) return
        backQueryPending = true
        wv.evaluateJavascript(BACK_PROBE_JS) { result ->
            backQueryPending = false
            // 页面回 "true" ⇒ 已消费；其余（"false" / "null" / 异常）一律退到后台。
            val consumed = result != null && result.trim('"') == "true"
            if (!consumed) {
                Log.i(DshApplication.TAG, "返回键：页面已无可退层级，退到后台（不结束进程）")
                moveTaskToBack(true)
            }
        }
    }

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
         * 页面侧脚本错误上报（见 `webview-polyfill.js`）。
         *
         * 这类失败原本在设备上完全无声：页面起不来，但宿主收不到任何回调，
         * 只能靠 12 秒兜底超时收掉启动层，露给用户一片白屏。
         * 有了它，启动层就能把**真实原因**摆出来（见 `overlayTimeoutRunnable`）。
         *
         * ⚠️ 运行在 WebView 的 JS 线程，只写一个 volatile 字段，不碰 View。
         * ⚠️ 只记第一条：启动失败时后续错误多半是连锁反应。
         */
        @android.webkit.JavascriptInterface
        fun reportWebError(message: String) {
            if (webError.isNotEmpty()) return
            webError = message
            Log.w(DshApplication.TAG, "页面脚本错误: $message")
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

        // ══ 悬浮气泡：设置页的三个接口 ══════════════════════════════════
        //
        // 走 JS 桥而不是宿主半的 HTTP 路由：开关状态是**原生**的事实（SharedPreferences
        // + Settings.canDrawOverlays），Node 侧读不到；拉起系统授权页更是 Android 的能力。
        // 宿主半那条路（特权权限用的）是为「需要 spawn 进程探测」的场景准备的，这里不需要。

        /**
         * 读气泡当前状态。返回 JSON 字符串。
         *
         * ⚠️ 必须返回 String —— `@JavascriptInterface` 只支持基本类型和 String，
         *   返回自定义对象会被序列化成 `undefined`（静默，不报错）。
         *
         * `granted` 与 `enabled` 是**两个独立的事实**：用户可能已经授权但没开开关，
         * 也可能开了开关但授权被系统撤销了。分开报，设置页才能给出正确的下一步。
         */
        @android.webkit.JavascriptInterface
        fun bubbleState(): String = try {
            JSONObject()
                .put("enabled", FloatingBubble.isEnabled(this@MainActivity))
                .put("granted", FloatingBubble.canDrawOverlays(this@MainActivity))
                .toString()
        } catch (t: Throwable) {
            Log.w(DshApplication.TAG, "读取气泡状态失败: ${t.message}")
            "{}"
        }

        /** 开关气泡。关掉时立刻收起已显示的窗口，不用等下次切后台。 */
        @android.webkit.JavascriptInterface
        fun bubbleSetEnabled(enabled: Boolean) {
            Log.i(DshApplication.TAG, "设置页切换悬浮气泡: $enabled")
            FloatingBubble.setEnabled(this@MainActivity, enabled)
            if (!enabled) FloatingBubble.hideNow()
        }

        /**
         * 拉起系统的「显示在其他应用上层」授权页。
         *
         * ⚠️ `SYSTEM_ALERT_WINDOW` **不能运行时请求**（不是 runtime permission，
         *   `requestPermissions` 对它无效），只能把用户送到这个设置页手动开。
         *   个别 ROM 没有这个 Activity，所以要 catch 后回落到应用详情页。
         */
        @android.webkit.JavascriptInterface
        fun bubbleRequestOverlay() {
            Log.i(DshApplication.TAG, "设置页请求悬浮窗授权")
            runOnUiThread {
                val pkgUri = Uri.parse("package:$packageName")
                val ok = runCatching {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.isSuccess
                if (!ok) {
                    runCatching {
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            }
        }

        // ══ root（uid 0）：设置页的状态显示与重新检测 ══════════════════════
        //
        // 为什么必须显示出来：`root` 工具**只在检测到 root 时才注册**，所以没有
        // root 的设备上，用户在任何地方都看不到它的痕迹 —— 分不清「这台机器没有
        // root」与「有 root 但 App 没检测到」。状态与原因都得可见，否则用户连
        // 该不该去装/授权 Magisk 都无从判断。
        //
        // 走 JS 桥而不是宿主半的 HTTP 路由：探测结论落在 `filesDir`（原生私有目录），
        // 而「重新检测」要起 `su` 进程、可能弹授权框 —— 两件都是 Android 侧的事。

        /**
         * 读 root 探测结论。返回 JSON 字符串。
         *
         * ⚠️ 必须返回 String —— `@JavascriptInterface` 只支持基本类型和 String，
         *   返回自定义对象会被序列化成 `undefined`（静默，不报错）。
         *
         * ⚠️ **只读缓存、不触发探测**，这是有意的：本方法运行在 WebView 的 JS
         *   线程上，而探测最多要等 [RootAccess.PROBE_TIMEOUT_SEC] 秒，会卡住整个
         *   页面。要重新探测走 [rootRecheck]。
         *
         * `at` 是结论的落盘时刻（毫秒）：一个 23 小时前的结论与刚测出来的不是
         * 一回事，界面要能表达这个差别，所以时间戳必须一起给出去。
         */
        @android.webkit.JavascriptInterface
        fun rootState(): String = try {
            val snap = RootAccess.snapshot(this@MainActivity)
            JSONObject()
                .put("known", snap != null)
                .put("state", snap?.result?.state?.name ?: "")
                .put("granted", snap?.result?.granted ?: false)
                .put("detail", snap?.result?.detail ?: "")
                .put("at", snap?.at ?: 0L)
                .toString()
        } catch (t: Throwable) {
            Log.w(DshApplication.TAG, "读取 root 状态失败: ${t.message}")
            "{}"
        }

        /**
         * 设置页点「重新检测」：清掉缓存并**在后台**强制重探一次。
         *
         * ⚠️ 必须丢到后台线程：探测要起 `su` 进程，首次还会弹授权框（最多等
         *   [RootAccess.PROBE_TIMEOUT_SEC] 秒），在 JS 线程上做会卡死页面。
         * ⚠️ 立即返回、不在这里回结果：界面靠轮询 [rootState] 拿结论（见客户端半
         *   的 `recheckRoot()`）。所以**不能**同步等它跑完 —— 那样就退回了上面那个
         *   卡页面的问题。
         */
        @android.webkit.JavascriptInterface
        fun rootRecheck() {
            Log.i(DshApplication.TAG, "设置页请求重新检测 root")
            val app = applicationContext
            Thread {
                val result = RootAccess.detect(app, force = true)
                Log.i(DshApplication.TAG, "root 重新检测结果: ${result.state} ${result.detail}")
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
        // 系统栏只在启动层收起后才跟随，理由见 applySystemBarColor
        if (overlayDismissed) applySystemBarColor(bg)
    }

    /**
     * 把系统栏（状态栏 + 导航栏）的底色与图标明暗同步到页面实际底色。
     *
     * **为什么要做**：这两处原先在 `themes.xml` 里写死 `#0F1115`（深色），
     * 而官方 Web GUI 是浅色的 —— 于是屏幕顶部、底部各留一条黑边，与页面割裂。
     * 页面底色本来就经 `onStatus` 上报（见 [applyPageBackground]），这里把它一并
     * 应用到系统栏，App 主题切深色时也会自动跟上（不写死颜色）。
     *
     * ⚠️ **必须在启动层收起之后才调用**：启动页是深色的（`#0B0E18`），
     *   引擎起来之前就把系统栏刷成白色，会在顶部露一条白边（比黑边更刺眼）。
     *   所以 `applyPageBackground` 里用 `overlayDismissed` 拦一道。
     * ⚠️ 底色浅时**必须**把图标切成深色：状态栏图标默认是浅色，白底上会看不见。
     * ⚠️ 必须在主线程调用（`runOnUiThread`）。
     */
    private fun applySystemBarColor(bg: String) {
        val color = try {
            Color.parseColor(bg)
        } catch (t: Throwable) {
            return
        }
        window.statusBarColor = color
        window.navigationBarColor = color
        val light = isLightColor(color)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    /**
     * 判断颜色是否算「浅色」，用来决定系统栏图标该用深色还是浅色。
     *
     * 用 ITU-R BT.601 的感知亮度权重，不取 RGB 平均：人眼对绿最敏感、对蓝最不敏感，
     * 取平均会把纯蓝（`#0000FF`）误判成浅色，于是配上深色图标、在蓝底上糊成一片。
     */
    private fun isLightColor(color: Int): Boolean {
        val luminance = 0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)
        return luminance / 255.0 > 0.5
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
                        // 门槛在这里才定：引擎就绪 ⇒ ensureWebViewCompat() 已经跑过，
                        // 降级到底生效了没有此时才准（见 effectiveMinWebViewMajor）。
                        val minMajor = effectiveMinWebViewMajor
                        webViewTooOld = webViewMajor in 1 until minMajor
                        if (webViewTooOld) {
                            Log.w(
                                DshApplication.TAG,
                                "WebView 内核 $webViewMajor < 门槛 $minMajor" +
                                    "（降级前端${if (engine.webViewCompatApplied) "已" else "未"}生效），" +
                                    "前端无法解析，跳过加载",
                            )
                            showOverlay(
                                "系统 WebView 内核过旧",
                                hint = "当前内核 $webViewMajor，需要 $minMajor+；" +
                                    "请在应用商店更新「Android System WebView」后重开 App",
                                isError = true,
                            )
                            return@collect
                        }
                        if (webViewMajor in 1 until MIN_WEBVIEW_MAJOR) {
                            Log.i(
                                DshApplication.TAG,
                                "WebView 内核 $webViewMajor 低于官方门槛 $MIN_WEBVIEW_MAJOR，" +
                                    "但降级前端已生效，继续加载",
                            )
                        }
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
                        // ⚠️ 这里**不能**直接收起 —— DOM 就绪 ≠ 像素上屏。
                        //
                        // 真机实测（CDP `Page.startScreencast` 抓 WebView 内部帧）：
                        //   DOM 变成真实 UI   t=2154ms（body.innerText 已是"探索未至之境"）
                        //   屏幕真的画出 UI   t≈541s 附近，比 DOM 晚了约 1.6 秒
                        //   而旧代码在 onUiReady 里立刻收起 → 那一秒多里屏幕上是空的/启动页
                        //
                        // 所以必须等 WebView **把这一帧真正提交**再收。
                        // `postVisualStateCallback` 正是这个语义：回调触发时，
                        // 这一帧已经可以显示在屏幕上了。
                        awaitWebViewPainted { hideOverlay() }
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
     * 等 WebView 把当前内容**真正画到屏幕上**，然后执行 [action]。
     *
     * **为什么需要它**：DOM 就绪和像素上屏之间有一段落差。真机实测（用 CDP 的
     * `Page.startScreencast` 抓 WebView 内部帧，85 帧样本）：
     *
     * ```
     *   DOM 已是真实 UI（body.innerText = "探索未至之境…"）  t=2154ms
     *   WebView 帧从 1939B（启动页白底）跳到 31518B（真实 UI） t≈541s ← 落后 1.6 秒
     * ```
     *
     * 旧代码在 `onUiReady` 里立刻收起启动层，于是这 1.6 秒里屏幕上是启动页或空白，
     * 用户看到的就是"动画结束后闪过几帧白屏"。
     *
     * `postVisualStateCallback` 的语义正是「这一帧已经提交、可以显示了」，
     * 它比 `onPageFinished`、比任何 DOM 信号都更贴近"用户真的看到了"。
     *
     * ⚠️ 回调**不保证一定触发**（WebView 被销毁、渲染进程崩溃时不会来），
     *   所以配一个 [PAINT_TIMEOUT_MS] 兜底 —— 宁可早收也不能不收，
     *   启动层永久盖住界面是最糟的失败方式。
     *
     * ⚠️ 回调在**主线程**触发，可以直接碰 View。
     */
    private fun awaitWebViewPainted(action: () -> Unit) {
        if (webViewPainted) { action(); return }
        if (paintPending) return          // 已经在等了，避免重复注册
        paintPending = true

        val done = {
            if (!webViewPainted) {
                webViewPainted = true
                paintPending = false
                overlayHandler.removeCallbacks(paintTimeoutRunnable)
                Log.i(DshApplication.TAG, "WebView 已提交首帧，收起启动动画")
                action()
            }
        }

        // 兜底：到点无条件执行（正常路径实测约几十毫秒内就会回调）
        overlayHandler.postDelayed(paintTimeoutRunnable, PAINT_TIMEOUT_MS)
        // ⚠️ 第一个参数是 requestId，回调会原样带回 —— 它只是个标识，
        //   不是 layerType（写成 LAYER_TYPE_* 会让人误以为有语义）。
        paintRequestId++
        binding.webView.postVisualStateCallback(
            paintRequestId,
            object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) = done()
            },
        )
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
        // 只有**错误态**才给点击出口：正常启动期不给 —— 那时界面还没渲染好，
        // 让用户误点一下就把启动层收掉，只会提前露出白屏。
        // 错误态下界面已经确认起不来，点一下至少能让人自己看看（或按返回退出）。
        binding.overlay.setOnClickListener(if (isError) View.OnClickListener { hideOverlay() } else null)
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
        // 启动层收起 = 页面底色开始可见，此时才把系统栏交给页面底色
        // （之前一直保持 themes.xml 的深色，与深色启动页一致）。
        // 用最近一次上报的底色；还没收到过就保持主题里的深色不动。
        if (appliedBgHex.isNotEmpty()) applySystemBarColor(appliedBgHex)
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

    // ══ 悬浮气泡：注入通道 ══════════════════════════════════════════════
    //
    // 气泡里的输入框是**原生 EditText**，而真正的会话输入框是页面里的 Lexical
    // 编辑器（React 组件）。两者之间只能靠注入 —— 没有别的接缝：
    //   · 引擎的 HTTP API 不接受"替我发一条 prompt"（prompt 走 WebSocket RPC，
    //     还要带会话 id、附件、client-request 外壳，自己拼等于重写一个前端）
    //   · 直接改 React state 拿不到 fiber（跨 bundle，闭包里的 setState 不可达）
    //
    // 所以走 DOM 事件：把文本"打字"进官方 composer，再点它的发送按钮 ——
    // 官方前端完全感知不到区别，后续所有流程（附件、上下文、会话记录）都照旧。

    /**
     * 装好气泡的两个回调。
     *
     * ⚠️ `sendText` 必须在 `onDestroy` 里清掉：WebView 跟着 Activity 一起销毁，
     *   留着旧闭包会让气泡对着一个已死的 WebView 调 `evaluateJavascript`
     *   （不报错、静默无效），用户以为发出去了其实没有。
     */
    private fun installBubbleBridge() {
        FloatingBubble.sendText = { text -> sendToComposer(text) }
        FloatingBubble.stopRun = { stopComposerRun() }
        FloatingBubble.openApp = {
            runOnUiThread {
                startActivity(
                    Intent(this, MainActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
                )
            }
        }
    }

    /**
     * 把一段文本送进页面的官方 composer，并触发发送。
     *
     * **必须在主线程调用**（`evaluateJavascript` 的要求）—— 气泡的按钮回调本来
     * 就在主线程，这里不额外切。
     *
     * 分两步而不是一步，是因为**页面状态更新是异步的**：`beforeinput` 注入完，
     * 发送按钮的 `disabled` 属性要等 React 下一次渲染才变。实测约 450ms。
     * 同步点按钮会点到 disabled 的那个，表现为"文字进去了但没发出去"。
     */
    private fun sendToComposer(text: String): Boolean {
        val wv = binding.webView
        val quoted = JSONObject.quote(text)

        wv.evaluateJavascript(BUBBLE_INSERT_JS.replace(BUBBLE_TEXT_SLOT, quoted)) { result ->
            Log.i(DshApplication.TAG, "气泡注入结果: $result")
            // ⚠️ 找不到 composer 时必须让用户知道。静默失败的话，气泡会把输入框
            //   清空、看起来"发出去了"，而 App 里其实一个字都没进去。
            if (result != null && result.contains("NO_INPUT")) {
                Toast.makeText(this, "输入框未就绪，请打开 App 重试", Toast.LENGTH_LONG).show()
            }
        }

        bubbleHandler.postDelayed({
            wv.evaluateJavascript(BUBBLE_SEND_JS) { result ->
                Log.i(DshApplication.TAG, "气泡发送结果: $result")
            }
        }, BUBBLE_SEND_DELAY_MS)

        return true
    }

    /** 气泡的延时任务（注入后点发送）。独立于 ime/overlay 两个 handler，互不干扰。 */
    private val bubbleHandler = Handler(Looper.getMainLooper())

    /**
     * 中断当前这一轮（气泡里的「停止」）。
     *
     * 页面侧会先验主按钮此刻是不是「停止生成」—— 不是就拒绝，避免把草稿误发出去。
     */
    private fun stopComposerRun(): Boolean {
        binding.webView.evaluateJavascript(BUBBLE_STOP_JS) { result ->
            Log.i(DshApplication.TAG, "气泡停止结果: $result")
            if (result != null && result.contains("NOT_RUNNING")) {
                Toast.makeText(this, "AI 当前没在运行", Toast.LENGTH_SHORT).show()
            }
        }
        return true
    }

    override fun onDestroy() {
        // 引擎跟随进程存活，这里不停止，避免转屏/重建时反复冷启动。
        imeHandler.removeCallbacks(imeRestoreRunnable)
        overlayHandler.removeCallbacks(overlayTimeoutRunnable)
        overlayHandler.removeCallbacks(paintTimeoutRunnable)
        // ⚠️ 必须清掉：WebView 随 Activity 一起销毁，留着这些闭包会让气泡
        //   对着一具"尸体"调 evaluateJavascript（静默无效，用户以为发成功了）。
        FloatingBubble.sendText = null
        FloatingBubble.stopRun = null
        FloatingBubble.openApp = null
        bubbleHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ══ 悬浮气泡：仅在 App 退到后台时显示 ══════════════════════════════
    //
    // 用 onStart/onStop 而不是 onResume/onPause：后者在弹权限对话框、拉下通知栏、
    // 甚至息屏时都会触发，气泡会频繁闪。onStop 才是"用户真的离开了这个界面"。
    //
    // ⚠️ 转屏也会走 onStop→onStart，但那是**配置变更**，不是退到后台 ——
    //   不排除的话气泡会在转屏瞬间闪一下。

    override fun onStart() {
        super.onStart()
        FloatingBubble.onAppForeground()
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) return
        FloatingBubble.onAppBackground(this)
    }

    internal companion object {
        /** 键盘高度缓存的 SharedPreferences 文件名。 */
        const val IME_PREFS = "dsh_ime"

        /**
         * 键盘高度缓存的 key（按朝向分）。
         *
         * ⚠️ 悬浮气泡也要读这个值（它拿不到 IME insets，只能靠"输入框聚焦 ⇒
         *   键盘要来"来预留空间），所以做成 `Context` 入参的静态函数两边共用 ——
         *   写死两处的话，将来改 key 必然漏改一边，症状是气泡预判高度永远取不到值。
         */
        fun imePrefsKeyOf(context: Context): String =
            if (context.resources.configuration.orientation ==
                Configuration.ORIENTATION_LANDSCAPE
            ) {
                "ime_height_land"
            } else {
                "ime_height_port"
            }

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

        /**
         * WebView 内核的最低主版本号。
         *
         * DSH 官方前端的入口 bundle 里用了 **class static block**（`static { … }`），
         * 那是 **Chromium 94** 才支持的**语法**。语法补不了 ——
         * `webview-polyfill.js` 只能补运行时 API（缺函数时挂一个），
         * 补不了解析期语法：整份 chunk 在解析阶段直接失败，一行都不执行。
         *
         * 真机现象正是如此（用户反馈的 45 秒录屏，vivo Y52s / Android 10）：
         * HTML 到了（`onPageFinished` 触发，启动层走到「正在准备界面…」）、
         * CSS 也生效了（页面是官方前端的白底），但 `onUiReady` 永不到达
         * —— React 根本没挂载。12 秒后 [UI_READY_TIMEOUT_MS] 兜底收起启动层，
         * 露出来的就是一片白屏。
         *
         * 所以把门槛显式化：低于它就不再静默白屏，而是把原因摆到启动层上。
         * 取 94（static block 的引入版本）而不是更高：内核 94 上还缺的
         * `reportError`（95）是 API，由 polyfill 补（见 `webview-polyfill.js`）。
         */
        const val MIN_WEBVIEW_MAJOR = 94

        /**
         * 降级前端生效后的实际门槛。
         *
         * `EngineManager.ensureWebViewCompat()` 会把 bundle 换成按 **chrome74** 降级
         * 编译的版本（class static block 被展开、逻辑赋值被改写），于是门槛降到 74。
         * 取 74 而不是 83（反馈设备那个版本）是留一档余量：esbuild 的 `chrome74`
         * 目标保证不生成 74 之后才有的语法。
         *
         * ⚠️ **降级范围是「dist + 全部插件的 client bundle」**（v2 起）。只降 dist
         *   是不够的 —— 实测 71 个插件 bundle 里 68 个在 chrome74 下有语法缺口，
         *   而它们与 dist 是**各自独立编译**的。只覆盖 dist 时症状不是白屏，而是
         *   「某个插件静默不生效 / 某块 UI 永远空白」（线上真实反馈：
         *   `groups.toSorted is not a function` 打不开模型选择器、
         *   「模型」设置页整页空白）。
         *
         * ⚠️ 74 之后引入的 **API**（`Object.hasOwn` 93、`.at()` 92、
         *   `reportError` 95、`structuredClone` 98、`Intl.Segmenter` 87 …）
         *   由 `webview-polyfill.js` 兜底，两者配合才完整。
         *
         * ⚠️ 降级**没打上**时不能放行：那时跑的是官方 bundle，
         *   门槛必须退回 [MIN_WEBVIEW_MAJOR]（见 `effectiveMinWebViewMajor`）。
         */
        const val MIN_WEBVIEW_MAJOR_COMPAT = 74

        /**
         * 等待 WebView 提交首帧的兜底超时（ms）。
         *
         * `postVisualStateCallback` 正常路径实测在几十毫秒内就回调（它只是等下一帧
         * 提交），但**不保证一定触发** —— WebView 被销毁、渲染进程崩溃时不会来。
         * 取 1.5 秒：足够覆盖一次正常的帧提交，又不至于让用户白等。
         */
        const val PAINT_TIMEOUT_MS = 1500L

        // ══ 悬浮气泡：注入脚本 ══════════════════════════════════════════

        /** 文本占位符。用 `replace` 而不是字符串拼接，避免文本里的引号破坏 JS 语法。 */
        const val BUBBLE_TEXT_SLOT = "__DSH_BUBBLE_TEXT__"

        /**
         * 问页面「系统返回键该退哪一层」。
         *
         * 页面侧在 `mobile-ui-plugin/dsh-android-mobile/lib/client.js` §12 挂了
         * `window.__DSH_MOBILE_BACK__`，它返回布尔值：true = 已消费（原生不退出）。
         *
         * ⚠️ 桥不存在时返回 `false` ⇒ 原生直接退出。这是**故意的**：
         *   插件没激活时页面里没有任何自建层级，退出正是正确行为；若反过来
         *   当作"已消费"，返回键会彻底失效，用户被困在 App 里。
         *
         * ⚠️ 整段包 try/catch：页面里任何异常都该退化成"没得关"，而不是让
         *   `evaluateJavascript` 拿到 `null` —— 那虽然也走退出分支，但日志里
         *   看不到原因。
         */
        val BACK_PROBE_JS = """
            (function () {
              try {
                var fn = window.__DSH_MOBILE_BACK__;
                if (typeof fn !== 'function') { return false; }
                return fn() === true;
              } catch (e) {
                console.warn('[dsh-android] 返回键处理异常: ' + (e && e.message));
                return false;
              }
            })()
        """.trimIndent()

        /**
         * 注入后等多久再点发送（ms）。
         *
         * ⚠️ 这个延时**不能省**：`beforeinput` 是同步派发的，但 React 要等下一次
         * 渲染才会把发送按钮的 `disabled` 去掉。实测约 450ms，取 500 留余量。
         * 提前点会点到 disabled 的按钮 —— 症状是"文字进去了但没发出去"。
         */
        const val BUBBLE_SEND_DELAY_MS = 500L

        /**
         * 把文本写进官方 composer。
         *
         * ## 为什么是 `beforeinput` 事件
         *
         * composer 是 **Lexical** 编辑器（`data-lexical-editor="true"`），不是
         * textarea/input —— `value` 属性根本不存在，`innerText` 也只是渲染结果，
         * 直接改它 Lexical 的内部 model 完全不认。
         *
         * 真机逐一实测过五条路，只有这条全绿：
         *
         * | 方法 | 结果 |
         * |---|---|
         * | `execCommand("insertText")` | 单行可以，**换行符被吃掉**，且清不掉旧内容 |
         * | `execCommand("insertLineBreak")` | 文字错乱 |
         * | `execCommand("selectAll")` + `delete` | Lexical 完全不响应 |
         * | 真实 Ctrl+A + Backspace（CDP 键盘事件） | 同样无效 |
         * | 直接构造 Lexical 节点 | `Minified Lexical error #195`（跨 bundle 版本不匹配） |
         * | **`beforeinput` 事件** | **单行/多行/引号/反斜杠/emoji/超长文本全部正确** |
         *
         * Lexical 自己在 `contenteditable` 上监听 `beforeinput`，读到
         * `inputType: "insertText"` + `data` 就照常走它的插入流程 —— 所以这条路径
         * 等价于"用户用输入法打了这些字"，是最接近真实的注入方式。
         *
         * 清空走 `__lexicalEditor.update(() => root.clear())`：这是 Lexical 自己
         * 暴露在 DOM 节点上的编辑器实例，用它做事务是**官方 API**，不是 hack。
         *
         * ⚠️ 返回时读到的 `innerText` 是**上一帧**的内容（Lexical 异步渲染），
         *   别拿它判断成败 —— 那正是把发送拆成两步的原因。
         */
        val BUBBLE_INSERT_JS = """
            (function () {
              var t = $BUBBLE_TEXT_SLOT;
              var ce = document.querySelector('.uV2eYG_input');
              if (!ce) { return 'NO_INPUT'; }
              ce.focus();
              var ed = ce.__lexicalEditor;
              if (ed) {
                try {
                  ed.update(function () {
                    var r = ed.getEditorState()._nodeMap.get('root');
                    if (r && r.clear) { r.clear(); }
                  });
                } catch (e) { /* 清空失败不致命，下面照常插入 */ }
              }
              if (t) {
                ce.dispatchEvent(new InputEvent('beforeinput', {
                  inputType: 'insertText',
                  data: t,
                  bubbles: true,
                  cancelable: true,
                  composed: true
                }));
              }
              var btn = document.querySelector('.uV2eYG_primary');
              return JSON.stringify({
                len: ce.innerText ? ce.innerText.length : 0,
                sendDisabled: btn ? btn.disabled : null
              });
            })()
        """.trimIndent()

        /**
         * 点官方 composer 的发送按钮。
         *
         * ⚠️ 用 `disabled` 先判一次：按钮在无内容时是 `disabled` 的，点它不报错、
         *   也不发送 —— 静默失败最难查。这里如实把状态回报给日志。
         */
        val BUBBLE_SEND_JS = """
            (function () {
              var btn = document.querySelector('.uV2eYG_primary');
              if (!btn) { return 'NO_SEND_BTN'; }
              if (btn.disabled) { return 'SEND_DISABLED'; }
              btn.click();
              return 'SENT';
            })()
        """.trimIndent()

        /**
         * 中断当前这一轮（官方主按钮在运行中会变成「停止生成」）。
         *
         * ⚠️ **必须先验 `aria-label`**：同一个 `.uV2eYG_primary` 元素空闲时是
         *   「发送消息」。盲点它会把用户输入框里的草稿直接发出去 —— 那是破坏性的
         *   误操作，不是"没反应"那么轻。
         */
        val BUBBLE_STOP_JS = """
            (function () {
              var btn = document.querySelector('.uV2eYG_primary');
              if (!btn) { return 'NO_BTN'; }
              var label = btn.getAttribute('aria-label') || '';
              if (label.indexOf('停止') < 0 && !/stop/i.test(label)) {
                return 'NOT_RUNNING';
              }
              btn.click();
              return 'STOPPED';
            })()
        """.trimIndent()
    }
}
