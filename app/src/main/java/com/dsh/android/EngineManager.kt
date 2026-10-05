package com.dsh.android

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 管理内置 DSH 引擎的生命周期。
 *
 * 架构：
 *   $PREFIX/bin/node（Termux 官方 arm64 构建，随 APK 分发并由 TermuxBootstrap 铺入）
 *     └─ engine/node_modules/@deepseek-ai/dsh/lib/bin.js --profile web --no-open
 *          └─ 本地 HTTP 服务，打印带 token 的 URL
 *
 * 引擎通过子进程运行，App 解析其 stdout 得到 URL 后交给 WebView 加载。
 *
 * ## 为什么 Node 用 Termux 的构建而不是自编译
 *
 * node 与它的全部依赖（ICU / OpenSSL / libc++ …）都由 Termux 官方仓库针对 Android
 * bionic 构建，随 APK 内置即可离线可用。自编译需要交叉编译整个 V8，且要处理
 * `import.meta.main`（需 Node ≥ 24.2）等版本约束，成本与风险都高得多。
 *
 * ⚠️ 这条路线要求 `targetSdk ≤ 28`：Android 10 起 targetSdk ≥ 29 的应用不允许
 * `execve()` 自身私有目录里的文件（W^X / SELinux app_data_file 禁止 execute_no_trans），
 * 而 `$PREFIX/bin/node` 正落在私有目录里。详见 app/build.gradle.kts 的注释。
 */
class EngineManager private constructor(private val context: Context) {

    sealed interface State {
        data object Idle : State
        data class Extracting(val percent: Int) : State
        data object Starting : State
        data class Ready(val url: String) : State
        data class Failed(val message: String) : State
    }

    /**
     * 页面阶段 —— 引擎就绪**之后**还要走的两段。
     *
     * 拆成独立的一条流而不是并进 [State]：`State.Ready` 带着 url，而
     * MainActivity 要靠它决定 `loadUrl`；如果把后续阶段也塞进同一个枚举，
     * 状态一推进 url 就丢了，`loadUrl` 的判断也跟着失效。
     *
     * 真机实测（OPPO PHY110 / Android 15，冷启动）各段耗时：
     * ```
     * responseEnd        231ms   ← 引擎已就绪，开始导航
     * domInteractive     450ms
     * domContentLoaded   557ms
     * loadEvent         2084ms   ← onPageFinished，此时前端 bundle 刚解析完
     * 插件激活收尾       3808ms   ← 移动端 UI 插件挂完，界面才真正可用
     * ```
     * 也就是说 `onPageFinished` 之后**还有约 1.7 秒**宿主完全感知不到，
     * 而那段时间屏幕上什么都不会变。加这两段就是为了让动画如实反映它。
     */
    sealed interface UiStage {
        /** 还没开始加载页面。 */
        data object None : UiStage

        /** `onPageFinished`：DOM 到了，但插件仍在激活。 */
        data object PageLoaded : UiStage

        /** 移动端 UI 插件已激活，界面可用 —— 启动动画到此收尾。 */
        data object UiReady : UiStage
    }

    private val _uiStage = MutableStateFlow<UiStage>(UiStage.None)
    val uiStage: StateFlow<UiStage> = _uiStage.asStateFlow()

    /**
     * 页面 DOM 就绪（`WebViewClient.onPageFinished`）。
     *
     * ⚠️ 幂等且**只前进不后退**：`onPageFinished` 会因转屏、iframe、
     *   重定向而多次触发，退回 None 会让动画倒放。
     */
    fun notifyPageLoaded() {
        if (_uiStage.value == UiStage.None) _uiStage.value = UiStage.PageLoaded
    }

    /** 移动端 UI 插件激活完成（由 WebView 的 JS 桥回调）。 */
    fun notifyUiReady() {
        _uiStage.value = UiStage.UiReady
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var process: Process? = null
    private val logLines = ArrayDeque<String>()

    /** Termux 环境：提供 node 可执行文件与 $PREFIX。 */
    private val bootstrap by lazy { com.dsh.android.termux.TermuxBootstrap(context) }

    /** 引擎解包目录：filesDir/engine */
    private val engineDir get() = File(context.filesDir, "engine")

    /** 引擎运行所需的可写 HOME（DSH 在此存放配置、凭据、会话）。 */
    private val homeDir get() = File(context.filesDir, "home")

    /** DSH_HOME：DSH 自己的数据目录。 */
    private val dshHome get() = File(homeDir, ".dsh")

    /** 引擎工作目录（agent 的文件操作以此为根）。 */
    private val workspaceDir get() = File(context.filesDir, "workspace")

    /** 版本戳：内容变化时触发重新解包。 */
    private val stampFile get() = File(engineDir, ".stamp")

    /**
     * 确保引擎资产已解包（幂等）。
     *
     * @param versionStamp 资产版本标识；与已解包版本一致时直接跳过。
     */
    suspend fun ensureExtracted(versionStamp: String) = withContext(Dispatchers.IO) {
        if (stampFile.isFile && stampFile.readText().trim() == versionStamp) {
            Log.i(TAG, "引擎资产已是最新（$versionStamp），跳过解包")
            return@withContext
        }

        _state.value = State.Extracting(0)
        // 先解到临时目录，成功后再原子替换，避免中途失败留下半成品
        val staging = File(context.filesDir, "engine.staging")
        staging.deleteRecursively()
        staging.mkdirs()

        try {
            // 进度基准：**tar.gz 压缩流的总字节数**（约 54 MB）。
            //
            // ⚠️ 这里原先用 `assets.openFd(ASSET_NAME).length`，而它**必然失败**：
            //   openFd 只对**未压缩**（stored）的资产有效，AGP 对 engine.bin 做了
            //   deflate，于是抛 FileNotFoundException → total=0 → 下面的
            //   `if (total > 0)` 恒假 → **进度永远停在 0%**。
            //   真机实测确认：首次启动那 15 秒里文案一直是「正在解包引擎 0%」。
            //
            // 改用 `ZipFile` 直接读 APK 自己的 zip 目录（`sourceDir` 就是 APK 路径），
            //   那里有权威的未压缩大小。
            //
            // ⚠️ 曾经想过读 gzip 尾部的 ISIZE 字段（那是解压后总字节数，实测精确），
            //   但那要**完整 inflate 一遍 262 MB** 才能摸到最后 4 字节 —— 白白让首次
            //   启动多花几秒。这里改成「基准 = 压缩流总长 + 实时统计已消费的压缩字节」，
            //   量纲天然一致，零额外成本。
            val total = archiveLength() ?: 0L
            if (total <= 0L) {
                Log.w(TAG, "读不到归档长度，本次解包不显示百分比")
            }
            var lastPercent = -1
            context.assets.open(ASSET_NAME).use { raw ->
                val counting = CountingInputStream(raw)
                TarGz.extract(counting, staging) {
                    if (total > 0L) {
                        val pct = ((counting.count * 100) / total).toInt().coerceIn(0, 99)
                        if (pct != lastPercent) {
                            lastPercent = pct
                            _state.value = State.Extracting(pct)
                        }
                    }
                }
            }
            File(staging, ".stamp").writeText(versionStamp)

            engineDir.deleteRecursively()
            if (!staging.renameTo(engineDir)) {
                throw java.io.IOException("无法将解包目录重命名为 $engineDir")
            }
            _state.value = State.Extracting(100)
            Log.i(TAG, "引擎资产解包完成：${engineDir.absolutePath}")
        } catch (t: Throwable) {
            staging.deleteRecursively()
            _state.value = State.Failed("引擎解包失败: ${t.message}")
            throw t
        }
    }

    /**
     * 读 `assets/engine.bin` 在 APK 里的**未压缩大小**（约 54 MB）。
     *
     * ⚠️ 为什么不用 `assets.openFd().length`：那只对未压缩资产有效，而 AGP 对
     *   engine.bin 做了 deflate，必然抛 FileNotFoundException。见调用点的注释。
     *
     * `context.applicationInfo.sourceDir` 就是 APK 的绝对路径，用 `ZipFile` 打开它
     * 即可拿到权威的 zip 目录项大小 —— 只读中央目录，不解压、不落盘，开销可忽略。
     *
     * ⚠️ 失败一律返回 null，**绝不能因此让解包失败** —— 没有百分比只是体验差，
     *   解包失败是功能不可用。
     */
    private fun archiveLength(): Long? = try {
        val apk = File(context.applicationInfo.sourceDir)
        java.util.zip.ZipFile(apk).use { zip ->
            // ⚠️ zip 里的条目全名带 `assets/` 前缀，直接用 ASSET_NAME 查会得到 null
            //   （且**不抛异常**，只是静默返回 null）。这里踩过一次。
            val entry = zip.getEntry("assets/$ASSET_NAME")
            val len = entry?.size ?: 0L
            Log.i(TAG, "engine.bin 归档长度 = $len 字节（entry=${entry?.name}）")
            len.takeIf { it > 0L }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "读归档长度失败: ${t.message}")
        null
    }

    /**
     * 只统计「从底层流读走了多少字节」的包装流。
     *
     * 用于把解包进度换算成**压缩流**的消费比例 —— 与 [archiveLength] 同一量纲。
     * 不改变任何读写语义，纯粹是个计数器。
     */
    private class CountingInputStream(
        private val delegate: java.io.InputStream,
    ) : java.io.InputStream() {
        var count = 0L
            private set

        override fun read(): Int {
            val b = delegate.read()
            if (b >= 0) count++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = delegate.read(b, off, len)
            if (n > 0) count += n
            return n
        }

        override fun available(): Int = delegate.available()
        override fun close() = delegate.close()
    }

    /**
     * 给 DSH 自带前端打兼容补丁（幂等）。
     *
     * DSH 前端按较新的 Chromium 编写，而设备上的 WebView 可能是旧版（本机是 114），
     * 缺 `Promise.withResolvers` / `AbortSignal.any` 等标准 API 时会直接抛 TypeError，
     * 界面起不来。这里在 `index.html` 的模块脚本之前插入一段 polyfill。
     *
     * 之所以改运行时目录而不是打包时改资产：引擎资产每次升级都会重新解包覆盖，
     * 放在这里可以跟着资产版本自动重打，不必重新制作 engine.bin。
     */
    private fun ensureWebCompat() {
        val dist = File(engineDir, "node_modules/@deepseek-ai/dsh-web-frontend/dist")
        val indexHtml = File(dist, "index.html")
        if (!indexHtml.isFile) {
            Log.w(TAG, "未找到前端入口，跳过 WebView 兼容补丁: ${indexHtml.absolutePath}")
            return
        }

        try {
            // polyfill 脚本本身每次都覆盖写：它随 APK 升级而变，代价只有几 KB
            val polyfill = File(dist, POLYFILL_FILE)
            context.assets.open(POLYFILL_FILE).use { input ->
                polyfill.outputStream().use { output -> input.copyTo(output) }
            }

            val html = indexHtml.readText()
            if (html.contains(POLYFILL_TAG)) {
                return
            }

            // 必须在 type="module" 的入口脚本之前执行：模块脚本是延迟执行的，
            // 但 polyfill 要保证在那之前就已挂好。
            val anchor = """<script type="module""""
            val idx = html.indexOf(anchor)
            if (idx < 0) {
                Log.w(TAG, "前端入口结构与预期不符，跳过 WebView 兼容补丁")
                return
            }
            val patched = html.substring(0, idx) + POLYFILL_TAG + "\n    " + html.substring(idx)
            indexHtml.writeText(patched)
            Log.i(TAG, "已注入 WebView 兼容补丁: $POLYFILL_FILE")
        } catch (t: Throwable) {
            // 补丁失败不阻断启动：新版 WebView 本来就不需要它
            Log.w(TAG, "注入 WebView 兼容补丁失败: ${t.message}")
        }
    }

    /**
     * 安装 android-arm64 的原生插件（幂等）。
     *
     * DSH 的 profile 解析依赖 `node-addon-require-builtin`：它通过 `dlsym` 拿到 Node
     * 私有的 `PrincipalRealm::builtin_module_require` 符号，再按 AArch64 机器码匹配出
     * Realm 里那个字段的偏移。该插件官方只发布 darwin / linux-glibc / win32 变体，
     * **没有 android**，而 Android 用的是 bionic，`__GLIBC__` 未定义，运行时会直接
     * 报 `No usable native binding found for node-addon-require-builtin-android-arm64`。
     *
     * 这里的做法是用 NDK 为 android-arm64 自行编译一份（复用 linux-glibc-arm64 的
     * AAPCS 匹配器 —— 纯指令匹配，与 libc 无关），按官方平台包的结构装进
     * `node_modules/node-addon-require-builtin-android-arm64`。
     *
     * 插件很小（约 90 KB），所以走独立的小资产，不必重打 52 MB 的 engine.bin。
     */
    private fun ensureNativeAddon() {
        val nodeModules = File(engineDir, "node_modules")
        if (!nodeModules.isDirectory) {
            Log.w(TAG, "引擎尚未解包，跳过原生插件安装")
            return
        }

        val target = File(nodeModules, ADDON_PACKAGE)
        val binary = File(target, "prebuilt/android-arm64-napi-v9.node")

        // 已装好就跳过。插件与 Node 大版本解耦（走 N-API），无需按 node 版本重装。
        if (binary.isFile && binary.length() > 0) {
            return
        }

        try {
            java.util.zip.ZipInputStream(context.assets.open(ADDON_ASSET).buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val out = File(target, entry.name)
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            Log.i(TAG, "已安装原生插件 $ADDON_PACKAGE (${binary.length()} 字节)")
        } catch (t: Throwable) {
            // 装不上就不阻断启动：让引擎自己报出更具体的加载失败原因
            Log.w(TAG, "安装原生插件失败: ${t.message}")
        }
    }

    /**
     * 安装 system 原生插件，并放行 android 平台（幂等）。
     *
     * 会话落盘要拿一把内核级的排他锁：`dsh-session-persistence-jsonl` 的
     * `SessionWriteLease.acquire()` 调 `@deepseek-ai/node-addon-system/flock` 的
     * `tryLockExclusive(fd)`，拿不到锁就抛 `SessionAlreadyOwnedError`，表现是每轮
     * 回复都以「本轮运行失败」告终。
     *
     * 该插件的 `lib/flock.js` 只白名单了 linux/darwin：
     *   `if (platform !== 'linux' && platform !== 'darwin') throw ...`
     * 而 Android 上 `process.platform === 'android'`，于是直接抛
     * `ERR_FLOCK_UNSUPPORTED_PLATFORM: flock is not supported on android-arm64`。
     * 官方也只发布 darwin/linux 变体，没有 android 平台包。
     *
     * 但 bionic 的 `sys/file.h` 里就有 `int flock(int, int)`，Android 是支持
     * flock(2) 的。所以这里做两件事：
     *   ① 用 NDK 编译一份 android-arm64 的 `bin/system.node`（源码就是官方
     *      `src/flock.c`，未做任何改动），按官方平台包结构装进
     *      `node_modules/@deepseek-ai/node-addon-system-android-arm64`；
     *   ② 把 `flock.js` 的平台白名单补上 `android`。
     *
     * ② 之所以改运行时文件而不是重打 engine.bin：引擎资产每次升级都会整体覆盖，
     * 放在这里可以跟着资产版本自动重打（与 [ensureWebCompat] 同样的理由）。
     *
     * 注意 flock.js 里 `filename` 只在 `platform === 'linux'` 时才会被改写为
     * `glibc/system.node` 或 `musl/system.node`；android 不满足该条件，保持
     * `system.node`，所以解析出的路径正是 `bin/system.node` —— 与本包结构一致，
     * 无需额外适配。
     */
    private fun ensureSystemAddon() {
        val nodeModules = File(engineDir, "node_modules")
        if (!nodeModules.isDirectory) {
            Log.w(TAG, "引擎尚未解包，跳过 system 插件安装")
            return
        }

        val target = File(nodeModules, SYSTEM_ADDON_PACKAGE)
        val binary = File(target, "bin/system.node")

        // 已装好就跳过。走 N-API，与 Node 大版本解耦。
        if (!(binary.isFile && binary.length() > 0)) {
            try {
                java.util.zip.ZipInputStream(context.assets.open(SYSTEM_ADDON_ASSET).buffered()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val out = File(target, entry.name)
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zis.copyTo(it) }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                Log.i(TAG, "已安装 system 插件 $SYSTEM_ADDON_PACKAGE (${binary.length()} 字节)")
            } catch (t: Throwable) {
                // 装不上就不阻断启动：让引擎自己报出更具体的加载失败原因
                Log.w(TAG, "安装 system 插件失败: ${t.message}")
            }
        }

        patchFlockPlatform()
    }

    /**
     * 安装 android 版 ripgrep（幂等）。
     *
     * `grep` / `glob` 两个工具都由 `dsh-tool-fs-search` 调 `@vscode/ripgrep`
     * 解析出的二进制来跑：
     *
     * ```
     * const platformPkg = `@vscode/ripgrep-${process.platform}-${arch}`;  // android-arm64
     * resolved = require.resolve(`${platformPkg}/bin/${binaryName}`);     // 抛错
     * ```
     *
     * 官方的 `optionalDependencies` 只列了 darwin/linux/win32 的 11 个变体，
     * **没有 android**。于是解析抛错，工具层把它包成
     * `Error: glob could not start its search command (ripgrep launch failed)`，
     * 两个工具全废 —— AI 只能改用 `read` 硬啃整个文件，浪费大量上下文。
     *
     * 这里装一个 `@vscode/ripgrep-android-arm64` 补上该平台包。二进制取自
     * 官方的 linux-arm64 变体（`@vscode/ripgrep-linux-arm64` 1.18.0）：
     * 它是**完全静态**的 aarch64 可执行文件（`readelf -d` 无 NEEDED），
     * 不依赖 glibc，因此能直接在 Android 上跑（设备实测 `rg --version` 正常）。
     * 这与 `TermuxBootstrap` 里 termux-exec 那套「静态二进制跨 libc」是同一道理。
     *
     * 无需改 `@vscode/ripgrep` 自身的代码：它按 `process.platform`/`process.arch`
     * 拼包名，装上 `android-arm64` 目录即可命中。也无需改任何版本戳 ——
     * 判定条件是「二进制存在且非空」。
     */
    private fun ensureRipgrep() {
        val nodeModules = File(engineDir, "node_modules")
        if (!nodeModules.isDirectory) {
            Log.w(TAG, "引擎尚未解包，跳过 ripgrep 安装")
            return
        }

        val target = File(nodeModules, RIPGREP_PACKAGE)
        val binary = File(target, "bin/rg")

        if (binary.isFile && binary.length() > 0) {
            return
        }

        try {
            java.util.zip.ZipInputStream(context.assets.open(RIPGREP_ASSET).buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val out = File(target, entry.name)
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            // 从 APK 资产解出来的文件默认没有执行位，必须显式补上
            binary.setExecutable(true, false)
            Log.i(TAG, "已安装 ripgrep $RIPGREP_PACKAGE (${binary.length()} 字节)")
        } catch (t: Throwable) {
            // 装不上不阻断启动：grep/glob 会各自报 SEARCH_FAILED，其余工具照常
            Log.w(TAG, "安装 ripgrep 失败: ${t.message}")
        }
    }

    /**
     * 安装 sharp 的 WebAssembly 变体（幂等）。
     *
     * **症状**：图片附件上传成功后点发送，提示 `prompt rejected (session/agent-busy)`。
     * 那个 `agent-busy` 是**兜底错误码**（`dsh-api-session-controller` 的
     * `admit()` 把非 RemoteError 一律包成它），真因藏在 `details.reason` 里：
     * `Could not load the "sharp" module using the android-arm64 runtime`。
     *
     * **根因**：`dsh-attachment-local` 用 sharp 做图片的元数据读取与缩放/重编码，
     * 而 sharp 的预编译二进制只覆盖 darwin/linux/linuxmusl/win32/freebsd，
     * **没有 android**。`sharp/dist/sharp.cjs` 按 `runtimePlatformArch()` 拼包名
     * （`${process.platform}${libc}-${process.arch}` = `android-arm64`）走 switch，
     * 落不进任何 case。
     *
     * **做法**：装 sharp 官方的 **wasm32 变体**。它正是 sharp 在
     * 「无预编译二进制」平台上的官方兜底 —— `sharp.cjs` 的 switch 走完之后，
     * 还有一条无条件的 `require("@img/sharp-wasm32/sharp.node")`（第 102-108 行），
     * 只要该包存在就能加载，**不需要改 sharp 自身一行代码**。
     * 它依赖 `@emnapi/runtime`（N-API 的 wasm 实现），故一并安装。
     *
     * 真机实测（`create` 1200×800 RGBA → metadata / resize / png+jpeg+webp 编码 /
     * 缩略图裁剪）全部通过，`sharp.versions` = 0.35.5 / libvips 8.18.7。
     *
     * 无需改版本戳 —— 与 ripgrep 同理，判定条件是「wasm 文件存在且非空」。
     */
    private fun ensureSharp() {
        val nodeModules = File(engineDir, "node_modules")
        if (!nodeModules.isDirectory) {
            Log.w(TAG, "引擎尚未解包，跳过 sharp 安装")
            return
        }

        val wasm = File(nodeModules, SHARP_WASM_PACKAGE).let { dir ->
            File(dir, "lib/sharp-wasm32-${SHARP_VERSION}.node.wasm")
        }
        if (wasm.isFile && wasm.length() > 0) {
            return
        }

        try {
            java.util.zip.ZipInputStream(context.assets.open(SHARP_ASSET).buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val out = File(nodeModules, entry.name)
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            Log.i(TAG, "已安装 sharp wasm 变体 ($SHARP_WASM_PACKAGE, ${wasm.length()} 字节)")
        } catch (t: Throwable) {
            // 装不上不阻断启动：仅图片附件会发送失败，其余功能照常
            Log.w(TAG, "安装 sharp wasm 变体失败: ${t.message}")
        }
    }

    /**
     * 安装 node-pty 的 android-arm64 原生绑定（幂等）。
     *
     * **症状**：侧栏终端面板打不开，显示
     * `终端错误：subprocess-local: terminal inspection is unsupported on platform android`
     * （那是另一个补丁负责的，见 [patchProcessInspector]；此处解决的是它背后
     * 真正缺的东西）。
     *
     * **根因**：`dsh-subprocess-local` 的终端后端走 `node-pty`，而 node-pty 的
     * `prebuilds/` 只有 `darwin-arm64` / `darwin-x64` / `linux-arm64` / `linux-x64` /
     * `win32-*`，**没有 android**。它的 `lib/utils.js` 按
     * `prebuilds/${process.platform}-${process.arch}` 拼目录名，所以在 Android 上
     * 会去找 `prebuilds/android-arm64/pty.node`，找不到就
     * `Failed to load native module: pty.node`。
     *
     * **做法**：用 NDK 从 node-pty 自带的 `src/unix/pty.cc` 直接编一份
     * android-arm64 的 `pty.node`，装到 `prebuilds/android-arm64/`。
     * 上游源码**无需任何改动** —— 它本来就按平台拼目录名。
     *
     * ⚠️ 必须 **`-static-libstdc++`**（静态链 libc++）。默认动态链会引入
     * `libc++_shared.so`，而 Termux 环境里**没有**这个库，加载即失败。
     * 静态链之后 `readelf -d` 只剩 `libm/libdl/libc` 三个 bionic 系统库。
     *
     * ⚠️ PTY 本身在 Android 上是**可用**的，别被"平台不支持"误导：真机在
     * app 的 SELinux 域（`untrusted_app`）里实测 `openpty` / `forkpty` /
     * `open("/dev/ptmx")` 全部成功。
     *
     * 真机实测：绑定加载成功，`pty.spawn(bash)` 起得来，写入
     * `echo PTY_WORKS_$((6*7))` 能收到 `PTY_WORKS_42` 回显。
     *
     * 无需改版本戳 —— 判定条件是「绑定存在且非空」。
     */
    private fun ensureNodePty() {
        val nodeModules = File(engineDir, "node_modules")
        if (!nodeModules.isDirectory) {
            Log.w(TAG, "引擎尚未解包，跳过 node-pty 安装")
            return
        }

        val target = File(nodeModules, "node-pty/prebuilds/android-arm64")
        val binding = File(target, "pty.node")
        if (binding.isFile && binding.length() > 0) {
            return
        }

        try {
            java.util.zip.ZipInputStream(context.assets.open(NODE_PTY_ASSET).buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        // 条目形如 node-pty/prebuilds/android-arm64/pty.node，直接相对 node_modules 落盘
                        val out = File(nodeModules, entry.name)
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            Log.i(TAG, "已安装 node-pty android-arm64 绑定 (${binding.length()} 字节)")
        } catch (t: Throwable) {
            // 装不上不阻断启动：只有终端面板用不了，其余功能照常
            Log.w(TAG, "安装 node-pty 绑定失败: ${t.message}")
        }
    }

    /**
     * 给 `dsh-subprocess-local` 的进程检查器补 android 分支（幂等）。
     *
     * **症状**：终端面板报
     * `subprocess-local: terminal inspection is unsupported on platform android`。
     *
     * **根因**：`createProcessInspector()` 是 linux/darwin/win32 三选一
     * （`runner-launch-*.js` 的 `createProcessInspector`），android 落不进任何分支
     * 就 `throw`。而 `spawnTerminal()` 第一件事就是调它。
     *
     * **做法**：让 android 复用 **Linux 实现**。这是有依据的，不是将就：
     *
     * - `LinuxProcessInspector` 读的全是 `/proc`，Android 就是 Linux 内核，
     *   格式一致（真机实测 `parseProcStat` 能正常解析）。
     * - 它需要的 syscall 表里**本来就有 arm64**（`SYSCALLS.arm64`），
     *   安卓 arm64 正好命中。
     * - app 域下 `/proc` **目录可读**，只是别的 uid 的进程条目会被 SELinux 挡掉；
     *   而 `LinuxProcessInspector` 对每个读不到的条目都是「跳过」
     *   （`readLinuxStat` 吞异常返回 `undefined`），不会因此抛错。
     *   代价是**只能看见自己 uid 的进程** —— 而终端 shell 及其子进程
     *   恰好全是本 app 的 uid，正是需要观察的那部分。
     *
     * 找不到锚点就跳过（引擎升级后结构变了也不让启动失败，届时日志里有提示）。
     */
    private fun patchProcessInspector() {
        val dir = File(engineDir, "node_modules/@deepseek-ai/dsh-subprocess-local/lib")
        if (!dir.isDirectory) {
            Log.w(TAG, "未找到 subprocess-local，跳过进程检查器平台补丁")
            return
        }

        // 实现体在一个带 hash 的 chunk 里（runner-launch-<hash>.js），名字随版本变，
        // 所以按内容找而不是按文件名找。
        // ⚠️ 必须先判「已打过补丁」再判「原文在不在」：打完之后原文就不存在了，
        //    顺序反了会把已完成的状态误报成「未找到」。
        val candidates = dir.listFiles { f -> f.isFile && f.name.startsWith("runner-launch-") && f.name.endsWith(".js") }
            ?.toList().orEmpty()
        val sources = candidates.map { it to it.readText() }
        if (sources.any { (_, text) -> text.contains(INSPECTOR_ANDROID_ANCHOR) }) {
            return
        }
        val entry = sources.firstOrNull { (_, text) -> text.contains(INSPECTOR_GUARD) }?.first
        if (entry == null) {
            Log.w(TAG, "未找到进程检查器的平台判断，跳过补丁")
            return
        }

        try {
            val source = entry.readText()
            entry.writeText(source.replace(INSPECTOR_GUARD, INSPECTOR_ANDROID_ANCHOR))
            Log.i(TAG, "已放行 subprocess-local 的 android 进程检查器: ${entry.name}")
        } catch (t: Throwable) {
            Log.w(TAG, "放行 subprocess-local 的 android 进程检查器失败: ${t.message}")
        }
    }

    /**
     * 让 `dsh-attachment-local` 的目录 fsync 容忍「祖先目录打不开」（幂等）。
     *
     * **症状**：图片/文件附件**上传成功**（缩略图都在），但点发送报
     * `prompt rejected (session/agent-busy)`。`agent-busy` 又是兜底错误码，
     * `details.reason` 里的真因是：
     *
     * ```
     * Error: EACCES: permission denied, open '/data/user/0'
     * ```
     *
     * **根因**：该包发布附件时要证明「目录项已落盘」，于是从 `$DSH_HOME` **逐级向上**
     * fsync 每一层父目录（`syncDirectory()` = `open(dir, O_RDONLY)` + `handle.sync()`），
     * 而 `ensureDurableHome()` 把 boundary 定为 `parse(home).root` —— 也就是 `/`，
     * 于是这趟遍历一定会走到应用目录之外。
     *
     * 但 **Android 的 SELinux 不允许应用 open 这些祖先目录**（真机实测，连
     * root 的 `/` 也是 EACCES，不是权限位的问题）：
     *
     * | 目录 | 模式 | `open(O_RDONLY)` |
     * |---|---|---|
     * | `/` | 755 | **EACCES** |
     * | `/data` | 771 | **EACCES** |
     * | `/data/user/0` | 771 | **EACCES** |
     * | `/data/data` | 771 | **EACCES** |
     * | `/data/data/com.dsh.android` | 700 | OK |
     *
     * 于是走到 `/data/user/0` 就抛 EACCES，整个附件发布失败。
     *
     * **做法**：只把「open 目录被拒（EACCES/EPERM）」这一种失败改成**跳过该层**
     * （`return`），其余 errno 照旧抛出。遍历本身会继续向上，最终自然收敛。
     *
     * ⚠️ **丢掉的只是「祖先目录项崩溃持久性」这一项加固，不是正确性** ——
     * 附件字节本身的 fsync（临时文件 `handle.sync()`）照旧执行，应用自己目录
     * （`$DSH_HOME` 及其以下，700、可 open）的 fsync 也照旧执行。
     * 而应用目录之外的目录项在 Android 上**本来就不可能**被 fsync，
     * 这不是取舍，是平台事实。
     *
     * ⚠️ 桌面端不受影响：`/`、`/home` 在 Linux 上都是可 open 的 755，
     * 这条 catch 分支根本不会触发。
     *
     * 找不到锚点就跳过（引擎升级后结构变了也不让启动失败，届时日志里有提示）。
     */
    private fun patchAttachmentDurability() {
        val entry = File(engineDir, "node_modules/@deepseek-ai/dsh-attachment-local/lib/index.js")
        if (!entry.isFile) {
            Log.w(TAG, "未找到附件模块入口，跳过目录 fsync 容错: ${entry.absolutePath}")
            return
        }

        try {
            val source = entry.readText()
            if (source.contains(ATTACH_SYNC_ANCHOR)) {
                return
            }
            if (!source.contains(ATTACH_SYNC_GUARD)) {
                Log.w(TAG, "附件模块的目录 fsync 结构与预期不符，跳过容错补丁")
                return
            }
            entry.writeText(source.replace(ATTACH_SYNC_GUARD, ATTACH_SYNC_ANCHOR))
            Log.i(TAG, "已放行附件模块的祖先目录 fsync: dsh-attachment-local")
        } catch (t: Throwable) {
            Log.w(TAG, "放行附件模块的祖先目录 fsync 失败: ${t.message}")
        }
    }

    /**
     * 安装 pnpm 到 `$PREFIX/bin`（幂等）。
     *
     * `plugin_manager` 的 `install_bundle` 通过 spawn `pnpm add` 装插件，而
     * Termux 的 `$PREFIX/bin` 里只有 dpkg/apt 那一套，**没有 pnpm/npm/npx/corepack**。
     * 症状是 `spawn pnpm ENOENT`，插件装不上 —— 实测 AI 因此退回手改 profile：
     * 把包塞进 `profiles/web/node_modules/` 并直接编辑用户的 `cordis.patch.yml`。
     * 那是绕过包管理器，会留下 `.bak` 残file 且不受版本管理。
     *
     * **为什么是 pnpm 11 的纯 JS 版，而不是 `@pnpm/exe.android-arm64`**：
     * exe 版（12.x）是 Rust 重写的自包含二进制，跑得起来（`pnpm --version` 正常），
     * 但它在 Android 上**做不了任何写操作** —— Rust std 在编译期把 Android 的
     * `File::lock_shared()` 判为 unsupported，而 pnpm 的 store 操作锁走的正是它：
     *
     * ```
     * ERR_PNPM_STORE_DIR_ACQUIRE_OPERATION_LOCK
     *   ╰─▶ Failed to acquire the store operation lock at ".../all-stores.lock":
     *       lock_shared() not supported
     * ```
     *
     * 注意这**不是**内核不给锁：设备实测 `flock(fd, LOCK_SH)` 返回 0，`fcntl` 记录锁
     * 也正常。是 pnpm 那个二进制的动态符号表里**只有 `fcntl`、根本没有 `flock`**
     * （`readelf --dyn-syms` 可验），纯属编译期判定，配置绕不过去。
     *
     * pnpm 11 是最后一个**纯 JS** 主线（`dist/pnpm.mjs` 单文件 bundle，无 Rust），
     * 锁走 JS 实现，真机实测 `pnpm add`（含本地路径包、`nodeLinker: hoisted`
     * 的真实 profile 布局）全部成功。代价是体积从单文件 46 MB 变成 5.9 MB 的 zip
     * —— 反而更小了。
     *
     * 装法：整包解到 `$PREFIX/lib/pnpm`，再在 `$PREFIX/bin/pnpm` 放一个
     * `exec node <pkg>/bin/pnpm.mjs "$@"` 的启动脚本。**不能**直接把 `bin/pnpm.mjs`
     * 软链到 `bin/pnpm`：Android 的 SELinux 不给 app 私有目录建符号链接（与
     * `link` 同一类限制），而且 shebang 在 Termux 里也不可靠。
     *
     * 校验走 `PNPM_VERSION` 版本戳（与 `NODE_VERSION`、`MOBILE_UI_VERSION` 同一套
     * 模式）：把版本写进 marker 文件，不一致才重解。
     */
    private fun ensurePnpm() {
        val bin = File(bootstrap.prefixDir, "bin/pnpm")
        val stamp = File(bootstrap.prefixDir, PNPM_STAMP)

        if (bin.isFile && bin.length() > 0 &&
            stamp.isFile && stamp.readText().trim() == PNPM_VERSION
        ) {
            return
        }

        try {
            val libDir = File(bootstrap.prefixDir, PNPM_DIR)
            libDir.deleteRecursively()
            libDir.mkdirs()

            var entryCount = 0
            java.util.zip.ZipInputStream(context.assets.open(PNPM_ASSET).buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val out = File(libDir, entry.name)
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                        entryCount++
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            val entryJs = File(libDir, "bin/pnpm.mjs")
            if (!entryJs.isFile) {
                throw IllegalStateException("资产里没有 bin/pnpm.mjs")
            }

            // $PREFIX/bin/pnpm：node 是绝对路径，免得依赖 PATH
            bin.parentFile?.mkdirs()
            bin.writeText(
                "#!/system/bin/sh\n" +
                    "exec " + bootstrap.nodeBinary.absolutePath +
                    " " + entryJs.absolutePath + " \"$@\"\n"
            )
            bin.setReadable(true, false)
            bin.setExecutable(true, false)

            stamp.writeText(PNPM_VERSION)
            Log.i(TAG, "已安装 pnpm $PNPM_VERSION ($entryCount 个文件, ${bin.length()} 字节启动脚本)")
        } catch (t: Throwable) {
            // 装不上不阻断启动：插件管理器的 install_bundle 会报 ENOENT
            Log.w(TAG, "安装 pnpm 失败: ${t.message}")
        }
    }

    /**
     * 解出 android 沙箱的两个组件（幂等），返回 runner 路径。
     *
     * 背景：DSH 的沙箱层按平台挑后端
     * （`dsh-sandbox-local` 的 `PLATFORM_CHAINS`：linux→bwrap/landlock、
     * darwin→seatbelt、win32→windows-acl），Android 落不进任何一条 ——
     * 设备内核 4.14 没有 Landlock（需 5.13+），没有 bwrap，`unshare -U` 因缺
     * CAP_SYS_ADMIN 而 EPERM。`confine()` 是 **fail-closed** 的，于是
     * `workspace-write` 下 bash 直接不可用：
     *
     * ```
     * sandbox mode "workspace-write" is requested but no sandbox backend is
     * usable on this host; refusing to run the command unconfined.
     * ```
     *
     * 但该 provider 留了操作员口子 `Config.runnerCommand`：非空时**不做平台
     * 探测**，直接把 runner 当成 bwrap 等价物并按 bwrap 方言拼参数
     * （`[...runnerCommand, ...bwrapProfileArgs(policy), "--", ...argv]`）。
     * 所以不必改引擎源码，只要提供一个听得懂那套参数的 runner。
     *
     * 两个组件（源码在 `app/src/main/jni/`）：
     *   · `boo-sandbox-run`   —— 解析 bwrap 方言，把 `--bind` 翻译成
     *     `BOO_SANDBOX_ALLOW`，再 exec 真实命令；
     *   · `libboosandbox.so`  —— LD_PRELOAD 写拦截器，在 libc 边界挡住
     *     allowlist 之外的写（解释器内部的写同样覆盖）。
     *
     * ⚠️ 与真 bwrap 的差异：`--unshare-pid` 无法实现（同样缺 CAP_SYS_ADMIN），
     * 所以 PID 命名空间没有隔离。**文件写边界是完整的**，那是本方案承诺的部分。
     * 详见 `boo-sandbox-run.c` 头部。
     */
    private fun ensureSandbox(): File? {
        return try {
            val runner = File(context.filesDir, SANDBOX_RUNNER_FILE)
            context.assets.open(SANDBOX_RUNNER_ASSET).use { input ->
                runner.outputStream().use { output -> input.copyTo(output) }
            }
            runner.setReadable(true, false)
            runner.setExecutable(true, false)

            val lib = File(context.filesDir, SANDBOX_LIB_FILE)
            context.assets.open(SANDBOX_LIB_ASSET).use { input ->
                lib.outputStream().use { output -> input.copyTo(output) }
            }
            lib.setReadable(true, false)

            Log.i(TAG, "已解出沙箱 runner ${runner.name} (${runner.length()} 字节) + ${lib.name} (${lib.length()} 字节)")
            runner
        } catch (t: Throwable) {
            Log.w(TAG, "解出沙箱组件失败: ${t.message}")
            null
        }
    }

    /**
     * 写沙箱 overlay，把 `sandbox` 行的 `runnerCommand` 指向上面的 runner（幂等）。
     *
     * 该 overlay 作为 `--patch` 传给引擎，在 profile 层之后应用，因此能覆盖
     * `dsh-base/cordis.patch.yml` 里的 `sandbox` 行（patch 是整行替换 config，
     * 不是合并）。
     *
     * `runnerFailureSignatures` **不能留空**。provider 构造函数里有一对硬校验：
     *
     * ```js
     * if (runner.length > 0 && runnerFailureSignatures.length === 0)
     *   throw new Error("sandbox-local: runnerCommand requires at least one runnerFailureSignatures entry");
     * ```
     *
     * 它映射到 `ConfinedArgv.runnerFailureRules[].fatalSignatures` —— 判定
     * 「runner 自己挂了、命令根本没跑起来」的 stderr 子串（与 bwrap 的 `"bwrap: "`、
     * landlock 的 `"landlock-launcher: "` 同构）。填错/留空的后果不是降级而是
     * **整个 sandbox 服务注册失败**：`4 entries did not activate`，然后
     * `bash-sandbox` → `permission` → `ptc-runtime` 全部 pending，
     * 表现为新建会话失败：
     *
     * ```
     * agent-preset/invalid: tool-bash: waiting for shell workflow-ptc:
     *   waiting for ptcRuntime tool-workflow: waiting for workflowEngine
     * ```
     *
     * `boo-sandbox-run.c` 的每条 fail-closed 路径都用 `boo-sandbox-run: ` 前缀
     * （与 bwrap 同名同格式），所以这里就填它。**改 runner 的输出前缀必须同步改这里。**
     *
     * 沙箱「拒绝」与「runner 失败」是两回事：前者靠 provider 内置的
     * `DENIAL_SIGNATURES.runnerCommand = ["read-only file system",
     * "permission denied"]` 识别，而 `libboosandbox.so` 的 deny 输出正好含
     * `permission denied`。
     *
     * 为什么不用 profile 自己的 `cordis.patch.yml`：那层归 DSH 初始化、也可能
     * 承载用户改动（本机上 AI 就往里插过自己的插件）。`--patch` 层完全由本 App
     * 拥有，且优先级更高。
     */
    private fun writeSandboxPatch(runner: File?): File? {
        if (runner == null) return null
        val file = File(dshHome, SANDBOX_PATCH)
        val yaml = buildString {
            appendLine("# 由 DSH Android 客户端生成，请勿手改（每次启动会整体覆盖）。")
            appendLine("# Android 没有内核级沙箱后端（无 bwrap / Landlock 需 5.13+，设备为 4.14），")
            appendLine("# 故用自带 runner + LD_PRELOAD 写拦截器保住 workspace-write 的写边界。")
            appendLine("# 详见 app/src/main/jni/boo-sandbox-run.c。")
            appendLine("- id: sandbox")
            appendLine("  name: '@deepseek-ai/dsh-sandbox-local'")
            appendLine("  config:")
            appendLine("    runnerCommand:")
            appendLine("      - '${runner.absolutePath}'")
            appendLine("    runnerFailureSignatures:")
            appendLine("      - 'boo-sandbox-run: '")
        }
        return try {
            if (!file.isFile || file.readText() != yaml) {
                file.writeText(yaml)
                Log.i(TAG, "已写入沙箱 overlay: ${file.name}")
            }
            file
        } catch (t: Throwable) {
            Log.w(TAG, "写入沙箱 overlay 失败: ${t.message}")
            null
        }
    }

    /**
     * 把 `flock.js` 的平台白名单补上 android（幂等）。
     *
     * 只替换那一处判断，不动其它任何逻辑；找不到锚点就跳过（引擎升级后结构变了
     * 也不要因此让启动失败，届时日志里会有明确提示）。
     */
    private fun patchFlockPlatform() {
        val flock = File(engineDir, "node_modules/@deepseek-ai/node-addon-system/lib/flock.js")
        if (!flock.isFile) {
            Log.w(TAG, "未找到 flock 入口，跳过 android 平台放行: ${flock.absolutePath}")
            return
        }

        try {
            val source = flock.readText()
            if (source.contains(FLOCK_ANDROID_ANCHOR)) {
                return
            }
            if (!source.contains(FLOCK_PLATFORM_GUARD)) {
                Log.w(TAG, "flock 平台判断结构与预期不符，跳过 android 平台放行")
                return
            }
            flock.writeText(source.replace(FLOCK_PLATFORM_GUARD, FLOCK_ANDROID_ANCHOR))
            Log.i(TAG, "已放行 flock 的 android 平台: ${flock.name}")
        } catch (t: Throwable) {
            Log.w(TAG, "放行 flock 的 android 平台失败: ${t.message}")
        }
    }

    /**
     * 安装移动端 UI 适配插件（幂等）。
     *
     * DSH 自带的前端是桌面布局：侧栏常驻、顶栏按鼠标悬停设计、消息操作按钮
     * 一长排。窄屏手机上这些要么挤成一团，要么根本点不到。
     *
     * 做法与桌面端一致：把一个声明了 `dsh.client` 的插件包落盘，再用 `--patch`
     * 传一层 overlay 把它挂上。客户端半由 `@deepseek-ai/dsh-client-modules`
     * 自动编入浏览器侧的 combo bundle，宿主半（lib/index.js）只是占位 —— 包必须
     * 有 Node 侧入口，否则 Cordis import 浏览器代码会当场抛 `window is not defined`。
     *
     * 插件自身用 `[data-mobile]` 属性作用域 + `matchMedia` 判据，只在窄屏且
     * 非鼠标设备时生效，宽屏/桌面窗口下官方布局一字不变。
     *
     * ## ⚠️ 为什么解到 `$DSH_HOME/mobile-ui/`，而不是 profile 的 `node_modules`
     *
     * 这是**踩过的坑**。原先解到 `profiles/web/node_modules/dsh-android-mobile`，
     * 而该包**不在** profile 的 `dependencies` 里（写进去会让 DSH 去 registry 拉
     * 这个本地包，桌面端同样刻意不写）。后果是：**用户只要用插件管理器装一次
     * 插件，pnpm 就按「多余依赖」把它挪进 `node_modules/.ignored/`**。
     *
     * 包一挪走，`--patch` 里那个按包名解析的 `name` 就找不到模块，客户端半不再
     * 编入浏览器 —— 而 `injectStyles()` 注入的 `<style>` 随插件卸载被一并移除，
     * **整个移动端适配（53 条 `[data-mobile]` 规则）全部消失**，界面静默退回
     * 桌面布局。表现就是：侧栏不再是抽屉而是常驻 56px 图标条（正文只剩 304px），
     * 且工具卡片/执行过程里的长文本左右两端都被裁出屏幕。
     *
     * 更要命的是它**不报错**：宿主半照常 apply，页面上甚至残留着上一轮渲染的
     * `.mp-nav-btn` / `.mp-drawer-mask` 元素，只是 CSS 没了（`position` 全是
     * `static`），看着像"插件装了一半"。
     *
     * 所以现在把包放在 pnpm 管不到的地方，并在 overlay 里用 `file://` 绝对 URL
     * 挂载 —— `dsh-client-modules.locatePkgJson()` 显式支持 pathLike 说明符
     * （`.` / `file:` / 绝对路径），从模块向上找到最近的 `package.json` 即认定为
     * 该包。这样 pnpm 怎么装插件都不会再动到它。
     */
    private fun ensureMobileUi() {
        val target = File(dshHome, "$MOBILE_UI_DIR/$MOBILE_UI_PACKAGE")
        val clientJs = File(target, "lib/client.js")
        val stamp = File(target, MOBILE_UI_STAMP)

        // 版本戳一致才跳过：插件随 APK 升级而变，只看文件存在会留下旧版
        if (clientJs.isFile && clientJs.length() > 0 &&
            stamp.isFile && stamp.readText().trim() == MOBILE_UI_VERSION
        ) {
            return
        }

        try {
            target.deleteRecursively()
            java.util.zip.ZipInputStream(context.assets.open(MOBILE_UI_ASSET).buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val out = File(target, entry.name)
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            stamp.writeText(MOBILE_UI_VERSION)
            Log.i(TAG, "已安装移动端 UI 插件 $MOBILE_UI_PACKAGE $MOBILE_UI_VERSION (${clientJs.length()} 字节)")
        } catch (t: Throwable) {
            // 装不上就不阻断启动：宁可给桌面布局，也不要白屏
            Log.w(TAG, "安装移动端 UI 插件失败: ${t.message}")
        }

        // 清理旧位置：早先版本解到 profile 的 node_modules 下，留在那里既没用，
        // 又会被插件清单扫到；pnpm 也可能把它当成待驱逐的"多余依赖"。
        File(dshHome, "profiles/web/node_modules/$MOBILE_UI_PACKAGE").deleteRecursively()
    }

    /**
     * 写出挂载移动端 UI 插件的 overlay，返回其路径；不可用时返回 null。
     *
     * 走 `--patch` 而不是改 profile 自己的 `cordis.patch.yml`：那个文件由 DSH
     * 初始化并可能承载用户配置，覆写它会丢东西；而 `--patch` 层在 profile 层
     * **之后**应用，优先级更高，且完全由本 App 拥有，升级时整体覆盖即可。
     *
     * `name` 用 `file://` 绝对 URL 而不是裸包名 —— 包在 pnpm 管辖之外，按包名
     * 解析不到（原因见 ensureMobileUi() 的注释）。YAML 里带 `:` 的标量必须加引号。
     */
    private fun writeMobileUiPatch(): File? {
        val patch = File(dshHome, MOBILE_UI_PATCH)
        val entry = File(dshHome, "$MOBILE_UI_DIR/$MOBILE_UI_PACKAGE/lib/index.js")
        if (!entry.isFile) {
            Log.w(TAG, "移动端 UI 插件入口不存在，跳过挂载: ${entry.absolutePath}")
            return null
        }
        return try {
            // 手工拼 `file://` + 绝对路径（三斜杠）而不是 File.toURI()：后者给的是
            // `file:/data/...`（单斜杠），虽然多数解析器能接受，但 ESM 侧要的是标准
            // 文件 URL。路径全为 ASCII 且无保留字符，不需要百分号编码。
            val url = "file://${entry.absolutePath}"
            patch.writeText(
                """
                # 由 DSH Android 客户端生成，请勿手改（每次启动会整体覆盖）。
                # 挂载移动端 UI 适配插件；插件包随 APK 分发，已解到 .dsh/${MOBILE_UI_DIR}。
                # 用 file:// 绝对路径挂载：包刻意放在 profile 的 node_modules 之外，
                # 否则用户每次用插件管理器装东西都会被 pnpm 挪进 .ignored/。
                - insert:
                    - id: $MOBILE_UI_PACKAGE
                      name: '$url'
                """.trimIndent() + "\n"
            )
            patch
        } catch (t: Throwable) {
            Log.w(TAG, "写入移动端 UI overlay 失败: ${t.message}")
            null
        }
    }

    /**
     * 把 link()/linkat() 的语义转译层解到真实文件路径（幂等），返回其路径。
     *
     * 为什么需要：SELinux 的 `untrusted_app(_27)` 域对 `app_data_file` **没有
     * `link` 权限**（AOSP 为防沙箱数据外泄主动移除），所以 node 进程里任何
     * `link()` 都返回 EACCES。而 DSH 上游用硬链接做独占原子发布，并靠 EEXIST
     * 判定「已被别人发布」：
     *
     * ```
     * try { await link(staged, currentPath); }
     * catch (e) { if (e.code === 'EEXIST') return false; throw e; }  // ← EACCES 走这里
     * ```
     *
     * 表现就是每轮回复都以「本轮运行失败 EACCES: permission denied, link」告终。
     *
     * 策略改不了，但**不需要让 link 成功，只需要让调用方不依赖它**：在 libc 边界
     * 把 link 转译成「复制 + renameat2(RENAME_NOREPLACE) 发布」，同时保住独占
     * （目标存在→EEXIST）与保源（真 link 不消耗源文件）两个语义。详见
     * `app/src/main/jni/linkcompat.c` 的头部注释。
     *
     * 为什么解到文件而不是走 jniLibs：`LD_PRELOAD` 只接受真实文件路径，而
     * AGP 默认 `extractNativeLibs=false` 时 `nativeLibraryDir` 是空目录。本工程
     * 虽设了 `extractNativeLibs=true`，但资产化能避免 AGP 对 native 库做 strip /
     * 改名，也与 `engine.bin` / `node-runtime.bin` 的处理方式一致。
     */
    private fun ensureLinkCompat(): File? {
        val dest = File(context.filesDir, LINKCOMPAT_FILE)
        return try {
            // 每次都覆盖写：库只有 10KB，而它随 APK 升级而变，比对哈希不值当
            context.assets.open(LINKCOMPAT_ASSET).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest.setReadable(true, false)
            dest.setExecutable(true, false)
            Log.i(TAG, "已解出 link 兼容层 $LINKCOMPAT_FILE (${dest.length()} 字节)")
            dest
        } catch (t: Throwable) {
            Log.w(TAG, "解出 link 兼容层失败: ${t.message}")
            null
        }
    }

    /**
     * 解出 uid 2000 执行通道（`dsh-android-shell.mjs`，每次启动覆盖写）。
     *
     * 该脚本直连本机 adbd（`127.0.0.1:5555`）走 ADB 协议认证，从而以 shell
     * 身份（uid 2000）执行命令 —— 这是 `screencap` / `input` / `pm uninstall` /
     * `settings` / `getevent` 等操作唯一可行的路径，app 域全部做不到
     * （详见 `assets/dsh-android-shell.mjs` 头部的实测对照表）。
     *
     * ⚠️ 没有密钥时**不报错**：脚本自己会回落为本地执行（app 域），
     * 也就是「授权了走 shell，没授权走普通的」。所以这里返回 null 只是
     * 让引擎不挂 overlay，而不是失败。
     */
    private fun ensurePrivilegedShell(): File? {
        val dest = File(context.filesDir, PRIVILEGED_RUNNER_FILE)
        return try {
            // 每次都覆盖写：脚本 20KB 且随 APK 升级而变，比对哈希不值当
            context.assets.open(PRIVILEGED_RUNNER_ASSET).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest.setReadable(true, false)
            Log.i(TAG, "已解出 uid 2000 执行通道 $PRIVILEGED_RUNNER_FILE (${dest.length()} 字节)")
            dest
        } catch (t: Throwable) {
            // 解不出来只是没有特权通道，不该拖垮引擎启动
            Log.w(TAG, "解出 uid 2000 执行通道失败: ${t.message}")
            null
        }
    }

    /**
     * 解出 uid 2000 特权工具插件（`shell-tool.zip` → `$DSH_HOME/shell-tool/dsh-android-shell/`）。
     *
     * 该插件在 `apply()` 里注册一个名为 `shell` 的**独立工具**，它永远以 uid 2000
     * 执行；`bash` 工具则永远保持 app 身份。两者的机制与取舍见插件源码头部注释
     * （`mobile-ui-plugin/dsh-android-shell/lib/index.js`）。
     *
     * ⚠️ **为什么解到 `$DSH_HOME/shell-tool/` 而不是 profile 的 `node_modules`**：
     * 与移动端 UI 插件同一个坑 —— 那个目录归 pnpm 管，而本包不在 profile 的
     * `dependencies` 里，用户每装一次插件就会被挪进 `node_modules/.ignored/`。
     * 详见 [ensureMobileUi]。
     *
     * 与移动端 UI 插件不同，这里**不做版本戳**：`lib/index.js` 每次启动覆盖写，
     * 逻辑与 linkcompat/boosandbox 那类「每次覆盖」的资产一致 —— 包很小（单文件），
     * 比对哈希不值当。
     */
    private fun ensurePrivilegedPlugin() {
        val target = File(dshHome, "$SHELL_TOOL_DIR/$SHELL_TOOL_PACKAGE")
        val entry = File(target, "lib/index.js")
        try {
            context.assets.open(SHELL_TOOL_ASSET).use { input ->
                java.util.zip.ZipInputStream(input.buffered()).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        if (!e.isDirectory) {
                            val out = File(target, e.name)
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zis.copyTo(it) }
                        }
                        zis.closeEntry()
                        e = zis.nextEntry
                    }
                }
            }
            Log.i(TAG, "已安装 uid 2000 特权工具插件 $SHELL_TOOL_PACKAGE (${entry.length()} 字节)")
        } catch (t: Throwable) {
            // 装不上就只是没有 shell 工具，不该拖垮引擎启动
            Log.w(TAG, "安装 uid 2000 特权工具插件失败: ${t.message}")
        }
    }

    /**
     * 解出无线调试用的 adb 工具链（`adb-wireless-arm64.zip` → `filesDir/adbwire/`）。
     *
     * ## 为什么需要一个 adb 二进制
     *
     * Android 11 起有「无线调试」，它和 legacy `adb tcpip 5555` 是两回事：
     * 端口随机、走 TLS、**重启后依然有效**（legacy 的在 Android 10 上重启即失效）。
     * 后者才是「免 PC」的关键。
     *
     * 但配对（pairing）走 **SPAKE2 + TLS-PSK**，而 Node 的 `tls` 模块**不支持
     * external PSK**，自己实现等于手写密码学。所以直接内置 Termux 官方
     * `android-tools` 里的 `adb`，复用 Google 自己的实现。
     *
     * ## 为什么带一堆 .so
     *
     * 那个 adb 是动态链接的，NEEDED 里有 `libprotobuf` / `libfmt` / `libabsl_*`
     * / `libbrotli*` / `libc++_shared` / `libz.so.1` / `liblz4` / `libzstd.so.1`。
     * 它的 RUNPATH 指向 Termux 的 `/data/data/com.termux/files/usr/lib`（本 App 里
     * 不存在），所以必须把库一并带上，并由 runner 设 `LD_LIBRARY_PATH`。
     *
     * ⚠️ **这里的 `LD_LIBRARY_PATH` 只作用于 adb 这一个子进程**，与引擎进程
     * 那处「刻意不设 LD_LIBRARY_PATH」的结论不冲突 —— 那条结论针对的是**引擎进程
     * 全局**设置该变量会污染系统二进制（见 [runEngine] 里的长注释）。adb 是自带
     * 全套依赖的自洽单元，且只跑它自己。
     *
     * ⚠️ **两个库必须按 SONAME 改名**：bootstrap 里是 `libz.so.1.3.2` 与
     * `libzstd.so.1.5.7`，而 adb 找的是 `libz.so.1` / `libzstd.so.1`。
     * Android 的 SELinux 不给 app 私有目录建符号链接，所以打包时就直接放了
     * SONAME 名字的副本（见资产构建步骤）。
     *
     * ⚠️ **`libc++_shared.so` 必须用与 adb 同期的那份**（来自 `libc++` 包），
     * 不能用 bootstrap 里那份：bootstrap 的是 9 月版，缺
     * `_ZNSt6__ndk113__hash_memoryEPKvm`，加载时报
     * `cannot locate symbol ... referenced by .../bin/adb`。
     *
     * @return adb 所在目录，失败给 `null`。
     */
    private fun ensureAdbWireless(): File? {
        val dest = File(context.filesDir, ADB_WIRELESS_DIR)
        val adb = File(dest, "bin/adb")
        // 幂等判据与 ripgrep / node-pty 一致：只查文件存在且非空。
        // 每次启动解 105 个文件 / 5.26MB 会拖慢启动，而这套工具链不随版本变。
        if (adb.isFile && adb.length() > 0) {
            adb.setExecutable(true, false)
            File(dest, "tmp").mkdirs()
            File(dest, "home").mkdirs()
            return dest
        }
        try {
            context.assets.open(ADB_WIRELESS_ASSET).use { input ->
                java.util.zip.ZipInputStream(input.buffered()).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        if (!e.isDirectory) {
                            val out = File(dest, e.name)
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zis.copyTo(it) }
                        }
                        zis.closeEntry()
                        e = zis.nextEntry
                    }
                }
            }
            // ⚠️ 从 APK 资产解出来的文件**没有执行位**，必须显式设，
            // 否则报 EACCES 而不是「找不到」。
            adb.setExecutable(true, false)
            File(dest, "tmp").mkdirs()
            File(dest, "home").mkdirs()
            Log.i(TAG, "已解出无线调试 adb 工具链 ($ADB_WIRELESS_DIR, adb ${adb.length()} 字节)")
            return dest
        } catch (t: Throwable) {
            // 解不出来只是没有无线通道，legacy 与 app 身份都不受影响
            Log.w(TAG, "解出无线调试 adb 工具链失败: ${t.message}")
            return null
        }
    }

    /**
     * 写出挂载 uid 2000 特权工具的 overlay，返回其路径；不可用时返回 null。
     *
     * 走 `--patch` 与移动端 UI 插件同样的理由（不改用户自己的 profile 配置、
     * 优先级更高、完全由本 App 拥有）。`name` 用 `file://` 绝对 URL，因为包
     * 刻意放在 pnpm 管辖之外，按包名解析不到。
     *
     * @param available 特权通道是否可用。不可用时**不挂载** —— 插件本身在
     *   没有 `DSH_PRIV_RUNNER` 时也不会注册工具，但少挂一层就少一处噪音。
     */
    private fun writePrivilegedPatch(available: Boolean): File? {
        val patch = File(dshHome, SHELL_TOOL_PATCH)
        val entry = File(dshHome, "$SHELL_TOOL_DIR/$SHELL_TOOL_PACKAGE/lib/index.js")
        if (!available || !entry.isFile) {
            // 通道不可用：把上次可能留下的 overlay 删掉，否则引擎会挂上一个
            // 因为找不到文件而报错的模块（或者更糟，挂上旧的残留版本）。
            if (patch.isFile) patch.delete()
            if (!available) Log.i(TAG, "uid 2000 通道不可用，跳过挂载 shell 工具")
            else Log.w(TAG, "uid 2000 特权工具入口不存在，跳过挂载: ${entry.absolutePath}")
            return null
        }
        return try {
            val url = "file://${entry.absolutePath}"
            patch.writeText(
                """
                # 由 DSH Android 客户端生成，请勿手改（每次启动会整体覆盖）。
                # 挂载 uid 2000 特权工具插件：注册一个独立的 `shell` 工具，
                # 由模型按需选择；`bash` 工具不受影响，永远走 app 身份。
                - insert:
                    - id: $SHELL_TOOL_PACKAGE
                      name: '$url'
                """.trimIndent() + "\n"
            )
            Log.i(TAG, "已写入 uid 2000 特权工具 overlay: ${patch.name}")
            patch
        } catch (t: Throwable) {
            Log.w(TAG, "写入 uid 2000 特权工具 overlay 失败: ${t.message}")
            null
        }
    }

    /**
     * 往 `$DSH_HOME/AGENTS.md` 写入特权身份说明（幂等）。
     *
     * ## 为什么写这个文件
     *
     * `@deepseek-ai/dsh-agent-instructions` 会自动读 `$DSH_HOME/AGENTS.md`
     * （默认候选名 `AGENTS.md` / `CLAUDE.md`）并注入系统提示词 —— 这是**零代码**
     * 的提示词通道，不需要改引擎、也不需要挂 overlay。
     *
     * ⚠️ 该文件归 DSH 的指令机制所有，用户也可能自己往里写东西。所以本方法
     * **只管理自己那一段**（`BEGIN`/`END` 标记之间），标记之外的内容原样保留。
     *
     * ## 为什么必须让模型知道
     *
     * 实测（见 `assets/dsh-android-shell.mjs` 头部对照表）app 域与 uid 2000 的
     * 差异**不体现在退出码上**：`input keyevent` 在 app 域返回 **rc=0 但屏幕不动**，
     * `dumpsys battery` 在 app 域返回 **rc=0 但输出 `Can't find service`**。
     * 模型若不知道自己在哪个身份下，会基于假前提继续推理、越走越错。
     *
     * @param available 特权通道是否可用（runner 已解出）。不可用时只写「当前不可用」，
     *   免得模型去试一个必然回落的功能。
     */
    private fun ensurePrivilegedPrompt(available: Boolean) {
        val file = File(dshHome, INSTRUCTIONS_FILE)
        try {
            val existing = if (file.isFile) file.readText() else ""
            val body = if (available) PRIVILEGED_PROMPT_ENABLED else PRIVILEGED_PROMPT_DISABLED
            val section = buildString {
                appendLine(PRIVILEGED_PROMPT_BEGIN)
                appendLine(body.trimEnd())
                appendLine(PRIVILEGED_PROMPT_END)
            }

            val begin = existing.indexOf(PRIVILEGED_PROMPT_BEGIN)
            val end = existing.indexOf(PRIVILEGED_PROMPT_END)
            val updated = if (begin >= 0 && end > begin) {
                // 已有本段：整体替换，保留前后用户内容
                existing.substring(0, begin) + section + existing.substring(end + PRIVILEGED_PROMPT_END.length).trimStart('\n', '\r')
            } else if (existing.isBlank()) {
                section
            } else {
                existing.trimEnd() + "\n\n" + section
            }

            if (updated != existing) {
                dshHome.mkdirs()
                file.writeText(updated)
                Log.i(TAG, "已写入特权身份说明: ${file.name}（通道可用=$available）")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "写入特权身份说明失败: ${t.message}")
        }
    }

    /** 启动引擎，等待其打印带 token 的 URL。 */
    suspend fun start() = withContext(Dispatchers.IO) {        if (process?.isAlive == true) {
            Log.i(TAG, "引擎已在运行")
            return@withContext
        }

        _state.value = State.Starting
        logLines.clear()

        homeDir.mkdirs()
        dshHome.mkdirs()
        workspaceDir.mkdirs()

        // 前端补丁要在 WebView 首次加载前落盘，所以放在启动引擎这一步里
        ensureWebCompat()
        // 原生插件是引擎启动的前提（DSH 的 profile 解析依赖它），必须早于进程启动
        ensureNativeAddon()
        // system 插件（会话写入锁）：缺了它每轮回复都会以「本轮运行失败」告终
        ensureSystemAddon()
        // ripgrep：官方无 android 平台包，缺了 grep/glob 两个工具全废
        ensureRipgrep()
        // sharp：官方无 android 预编译二进制，缺了图片附件发送会以 agent-busy 失败
        ensureSharp()
        // node-pty：官方 prebuilds 无 android，缺了侧栏终端打不开
        ensureNodePty()
        // 终端后端的进程检查器只认 linux/darwin/win32，android 要显式放行
        patchProcessInspector()
        // 附件模块的目录 fsync 会走到 /data/user/0（SELinux 拒绝 open）→ 附件发不出去
        patchAttachmentDurability()
        // 移动端 UI 适配插件：必须在 profile 解析前落盘，否则 --patch 挂不上
        ensureMobileUi()
        val mobileUiPatch = writeMobileUiPatch()
        // 沙箱：Android 无内核级后端，用自带 runner 保住 workspace-write 的写边界
        val sandboxPatch = writeSandboxPatch(ensureSandbox())
        // link 兼容层：SELinux 不给 app_data_file 的 link 权限，需在 libc 边界转译
        val linkCompat = ensureLinkCompat()
        // uid 2000 通道：截图/点击/卸载/settings 等 app 域做不到的操作要靠它。
        // 未导入密钥时脚本自己回落为本地执行，不影响任何现有功能。
        val privRunner = ensurePrivilegedShell()
        // 无线调试（Android 11+）用的 adb 工具链：让 uid 2000 通道在
        // 「只开了无线调试、没做 adb tcpip」的设备上也能用。见 ensureAdbWireless()。
        val adbWireless = ensureAdbWireless()
        // 把该通道注册成独立的 `shell` 工具（**不是**改 bash 的行为）：
        // 默认走 app 身份，模型读工具描述后自己决定要不要用 2000。见 ensurePrivilegedPlugin()。
        ensurePrivilegedPlugin()
        val privPatch = writePrivilegedPatch(privRunner != null)
        // 让模型知道「有特权身份可选、什么时候该用、用了会失去什么」
        ensurePrivilegedPrompt(privRunner != null)

        // Termux 环境必须先就位：node 从 $PREFIX/bin 来，缺失时下面的检查会给出明确报错。
        if (!bootstrap.installNodeRuntime()) {
            _state.value = State.Failed(
                "Termux 环境未就绪，无法启动引擎。\n" +
                    "prefix=${bootstrap.prefixDir.absolutePath}\n" +
                    "node=${bootstrap.nodeBinary.absolutePath}"
            )
            return@withContext
        }

        val nodeBin = bootstrap.nodeBinary
        if (!nodeBin.isFile) {
            _state.value = State.Failed("找不到 Node 可执行文件: ${nodeBin.absolutePath}")
            return@withContext
        }

        // pnpm 要落到 $PREFIX/bin，必须等 Termux 环境就位之后再装
        ensurePnpm()

        val binJs = File(engineDir, "node_modules/@deepseek-ai/dsh/lib/bin.js")
        if (!binJs.isFile) {
            _state.value = State.Failed("找不到引擎入口: ${binJs.absolutePath}")
            return@withContext
        }

        val cmd = buildList {
            add(nodeBin.absolutePath)
            add(binJs.absolutePath)
            add("--profile"); add("web")
            // 移动端 UI 适配：作为 profile 层之上的 overlay 挂载
            if (mobileUiPatch != null) {
                add("--patch"); add(mobileUiPatch.absolutePath)
            }
            // 沙箱 runner：同样是 overlay（--patch 可重复，按顺序应用）
            if (sandboxPatch != null) {
                add("--patch"); add(sandboxPatch.absolutePath)
            }
            // uid 2000 特权工具：也是 overlay，注册成独立的 `shell` 工具
            if (privPatch != null) {
                add("--patch"); add(privPatch.absolutePath)
            }
            add("--no-open")
        }

        Log.i(TAG, "启动引擎: ${cmd.joinToString(" ")}")

        val prefix = bootstrap.prefixDir.absolutePath

        val pb = ProcessBuilder(cmd)
            .directory(workspaceDir)
            .redirectErrorStream(true)

        pb.environment().apply {
            // ⚠️ 刻意**不设** LD_LIBRARY_PATH。
            //
            // 这里原先设了 `LD_LIBRARY_PATH=$prefix/lib`，当作「RUNPATH 没覆盖到时
            // 的兜底」。但它对 Termux 二进制**完全无用**（真机全量对比 $PREFIX/bin 下
            // 305 个 ELF，带与不带 LD_LIBRARY_PATH 的输出**零差异** —— Termux 的
            // RUNPATH 已被等长重写成 /data/data/com.dsh.android//usr/lib，自己找得到），
            // 却会**污染系统二进制**：它们本来该去 /system/lib64 找依赖，被这行带去
            // Termux 的 lib 目录，于是 screencap / input / app_process 全部
            // `CANNOT LINK EXECUTABLE "screencap": cannot locate symbol
            //  "CrcGenerateTable" referenced by "/system/lib64/libunwindstack.so"`。
            //
            // 后果不是「少了个功能」而是**误导**：screencap 在 app 域本来就因权限失败，
            // 现在失败得更早、错误信息指向动态库，模型会被带偏去反复试
            // `env -u LD_PRELOAD` 之类的无用方向（真机会话日志里实测发生过）。
            //
            // 去掉后 node 与 4 个原生模块（require-builtin / flock / node-pty /
            // sharp-wasm32）、bash/dpkg/pnpm/rg 全部实测正常。
            // PREFIX/HOME 让 DSH 内部按 Termux 习惯推导路径，与库搜索无关，保留。
            put("PREFIX", prefix)
            put("DSH_HOME", dshHome.absolutePath)
            put("HOME", homeDir.absolutePath)
            put("TMPDIR", context.cacheDir.absolutePath)
            // $PREFIX/bin 必须排在最前：node 自己 spawn 子进程（如 shell 工具）时
            // 要能找到 Termux 的 sh/bash 而不是系统的。
            put("PATH", "$prefix/bin:$prefix/bin/applets:/system/bin:/system/xbin")
            // CA 证书在 Termux 私有 prefix 里，否则 HTTPS 请求会因证书链失败
            put("SSL_CERT_FILE", "$prefix/etc/tls/cert.pem")
            put("CURL_CA_BUNDLE", "$prefix/etc/tls/cert.pem")
            put("NODE_OPTIONS", "--max-old-space-size=1024")
            // link()/linkat() 语义转译：SELinux 不允许 untrusted_app 对 app_data_file
            // 做硬链接，而 DSH 上游用硬链接做独占原子发布。见 [ensureLinkCompat]。
            //
            // ⚠️ 只加在本进程：LD_PRELOAD 会一并继承给 node spawn 的子进程（如 shell
            // 工具）。对本库无害（只 hook link/linkat，且先试原生、失败才转译），
            // 但若将来要往 LD_PRELOAD 里追加别的库，务必确认不会破坏系统二进制。
            if (linkCompat != null) {
                put("LD_PRELOAD", linkCompat.absolutePath)
            }
            // 沙箱 runner 靠它定位 LD_PRELOAD 拦截器。runner 会把它追加到子进程
            // 的 LD_PRELOAD 里（保留这里已有的 link 兼容层，两个库都生效）。
            put("BOO_SANDBOX_LIB", File(context.filesDir, SANDBOX_LIB_FILE).absolutePath)
            // uid 2000 通道脚本的路径，供特权插件读取（见 [ensurePrivilegedPlugin]）。
            // 经环境变量传递而不是让 Kotlin 拼 JS 字面量：路径里可能有反斜杠与引号。
            privRunner?.let { put("DSH_PRIV_RUNNER", it.absolutePath) }
            // 无线调试用的 adb 工具链目录，runner 靠它决定要不要启用无线通道。
            // 缺失时 runner 只走 legacy 5555，行为与改动前完全一致。
            adbWireless?.let { put("DSH_ADB_HOME", it.absolutePath) }
        }

        // 无线调试端口由 Kotlin 侧发现（Node 侧发不出 UDP 组播，见 WirelessDebugging）。
        // 常驻监听：端口会随用户重开无线调试/重启/切网络而变，只在启动时发现一次的话
        // 一变就永久失效。监听本身由系统 mdnsd 承担，开销可忽略。
        if (adbWireless != null) {
            WirelessDebugging.startWatcher(context, File(dshHome, WirelessDebugging.PORT_FILE))
            // 启动自愈：重启手机后 5555 会消失（`persist.adb.tcp.port` 写不进去），
            // 这里顺手切一次，省掉「用户得自己点一下切到固定端口」这一步。
            // 已在监听时直接返回，无额外开销；失败也不影响任何通道。
            val adbHomeRef = adbWireless
            val runnerRef = privRunner
            if (runnerRef != null) {
                Thread {
                    runCatching {
                        WirelessDebugging.ensureTcpPort(
                            context = context,
                            nodeBin = bootstrap.nodeBinary,
                            runner = runnerRef,
                            adbHome = adbHomeRef,
                            dshHome = dshHome,
                        )
                    }.onFailure { Log.w(TAG, "启动自动切固定端口失败: ${it.message}") }
                }.start()
            }
        }

        val proc = try {
            pb.start()
        } catch (t: Throwable) {
            _state.value = State.Failed("引擎进程启动失败: ${t.message}")
            return@withContext
        }
        process = proc

        // 只用一个后台线程从头到尾持续消费 stdout。
        // 不能用 use{} 读完 URL 就关流：引擎之后仍在写日志，64KB 管道缓冲写满后
        // 会阻塞在 write 上导致引擎彻底卡死；重复打开同一 inputStream 也会失败。
        val urlPattern = Regex("""dsh web: (http://127\.0\.0\.1:\d+/\?token=[A-Za-z0-9_\-]+)""")
        val readyLatch = java.util.concurrent.CountDownLatch(1)
        var readyUrl: String? = null

        Thread {
            try {
                proc.inputStream.bufferedReader().useLines { seq ->
                    seq.forEach { line ->
                        Log.i(TAG, "[engine] $line")
                        synchronized(logLines) {
                            logLines.addLast(line)
                            while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
                        }
                        if (readyUrl == null) {
                            val m = urlPattern.find(line)
                            if (m != null) {
                                readyUrl = m.groupValues[1]
                                readyLatch.countDown()
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "引擎输出流结束: ${t.message}")
            } finally {
                // 流关闭（引擎退出）时也要唤醒等待方，避免白等到超时
                readyLatch.countDown()
            }
        }.apply { isDaemon = true; name = "dsh-engine-output" }.start()

        val got = readyLatch.await(READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val url = readyUrl

        if (got && url != null) {
            _state.value = State.Ready(url)
            Log.i(TAG, "引擎就绪: $url")
        } else {
            val tail = synchronized(logLines) { logLines.joinToString("\n") }
            val reason = if (proc.isAlive) "超时未打印 URL" else "引擎进程已退出"
            _state.value = State.Failed("引擎启动失败（$reason）。最后输出:\n$tail")
        }
    }

    fun stop() {
        process?.let { p ->
            Log.i(TAG, "停止引擎")
            p.destroy()
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly()
            }
        }
        process = null
        // 引擎停了就别再占着 mDNS 监听（NsdManager 的 listener 注册是全局资源）
        WirelessDebugging.stopWatcher()
        _state.value = State.Idle
    }

    fun isRunning(): Boolean = process?.isAlive == true

    /**
     * 用配对码把本 App 的 ADB 密钥授权给设备（无线调试配对，Android 11+）。
     *
     * ⚠️ **阻塞调用**（发现端口 + SPAKE2 握手 + adb 启动，可能十几秒），
     * 必须在后台线程调用。调用方见 [AdbPairingNotifier]（走 `goAsync()`）。
     *
     * @param code 用户从系统「使用配对码配对设备」界面抄来的 6 位码。
     * @param port 配对端口；`<= 0` 时由 [WirelessDebugging.pair] 自己重新发现。
     */
    fun pairWirelessDebugging(code: String, port: Int): WirelessDebugging.Result {
        val runner = File(context.filesDir, PRIVILEGED_RUNNER_FILE)
        val adbHome = File(context.filesDir, ADB_WIRELESS_DIR)
        if (!adbHome.isDirectory) {
            return WirelessDebugging.Result(
                false,
                "内置 adb 工具链未就绪（$ADB_WIRELESS_DIR 不存在），无线调试不可用。",
            )
        }
        val result = WirelessDebugging.pair(
            context = context,
            nodeBin = bootstrap.nodeBinary,
            runner = runner,
            adbHome = adbHome,
            dshHome = dshHome,
            code = code,
        )
        // 配对成功后立刻发现一次连接端口并落盘，这样用户不必再等常驻监听
        // 撞上「无线调试已开启」那一刻。失败也无所谓，监听器会补上。
        if (result.ok) {
            val fresh = WirelessDebugging.discoverConnectPort(
                context,
                File(dshHome, WirelessDebugging.PORT_FILE),
            )
            // ⚠️ runner 在 `--pair` 里已经自己切过一次，但**那一次用的是配对前的
            // 旧端口**：无线调试在配对过程中会重启 adbd，端口随之改变，于是
            // `adb -s 127.0.0.1:<旧端口> tcpip 5555` 必然失败，用户看到「配对成功
            // 但身份还是 app」。这里拿着**刚发现的新端口**补切一次，把那条缝补上。
            //
            // 已经切成 5555 时 `switchToTcp` 是幂等的（先探 legacy，通就返回 true），
            // 所以重复调用没有代价。
            if (fresh > 0) {
                val switched = WirelessDebugging.switchToTcp(
                    nodeBin = bootstrap.nodeBinary,
                    runner = runner,
                    adbHome = adbHome,
                    dshHome = dshHome,
                )
                if (!switched.ok) {
                    Log.w(TAG, "配对成功但补切固定端口失败: ${switched.message}")
                }
            }
        }
        return result
    }

    /**
     * 把 adbd 切到固定的 TCP 端口（`adb tcpip 5555`）。
     *
     * 切完之后特权通道**不再依赖无线调试开关** —— 实测关掉无线调试、关掉 Wi-Fi
     * 都不影响（走 127.0.0.1 回环）。详见 [WirelessDebugging.switchToTcp]。
     *
     * ⚠️ **阻塞调用**（最多约 6 秒：切换 + 轮询端口就绪），必须在后台线程调用。
     */
    fun switchToTcpMode(): WirelessDebugging.Result {
        val runner = File(context.filesDir, PRIVILEGED_RUNNER_FILE)
        val adbHome = File(context.filesDir, ADB_WIRELESS_DIR)
        if (!adbHome.isDirectory) {
            return WirelessDebugging.Result(
                false,
                "内置 adb 工具链未就绪（$ADB_WIRELESS_DIR 不存在），无线调试不可用。",
            )
        }
        return WirelessDebugging.switchToTcp(
            nodeBin = bootstrap.nodeBinary,
            runner = runner,
            adbHome = adbHome,
            dshHome = dshHome,
        )
    }

    fun recentLog(): String = synchronized(logLines) { logLines.joinToString("\n") }

    companion object {
        private const val TAG = "DSH"
        // 注意：不能用 .tar.gz 作后缀 —— AGP 会把它当作 gzip 资源自动解压并改名为 .tar，
        // 导致运行时按原名找不到。用中性后缀避免 AGP 的格式推断。
        private const val ASSET_NAME = "engine.bin"
        private const val READY_TIMEOUT_MS = 120_000L
        private const val MAX_LOG_LINES = 400

        /** WebView 兼容补丁：资产名与注入到 index.html 里的标签。 */
        private const val POLYFILL_FILE = "webview-polyfill.js"
        private const val POLYFILL_TAG = """<script src="./$POLYFILL_FILE"></script>"""

        /** 自编译的 android-arm64 原生插件（官方无此变体）。 */
        private const val ADDON_ASSET = "node-addon-android-arm64.zip"
        private const val ADDON_PACKAGE = "node-addon-require-builtin-android-arm64"

        /** system 插件（会话写入锁 flock）：同样是自编译的 android-arm64 变体。 */
        private const val SYSTEM_ADDON_ASSET = "node-addon-system-android-arm64.zip"
        private const val SYSTEM_ADDON_PACKAGE = "@deepseek-ai/node-addon-system-android-arm64"

        /**
         * flock.js 里平台白名单的原样文本，以及放行 android 之后的文本。
         * 两者都必须与官方发布的 flock.js 逐字一致；引擎升级后若此处不再匹配，
         * [patchFlockPlatform] 会跳过并在日志里提示，不会让启动失败。
         */
        private const val FLOCK_PLATFORM_GUARD =
            "if (platform !== 'linux' && platform !== 'darwin') {"
        private const val FLOCK_ANDROID_ANCHOR =
            "if (platform !== 'linux' && platform !== 'darwin' && platform !== 'android') {"

        /**
         * `dsh-attachment-local/lib/index.js` 里 `syncDirectory()` 的原样文本，
         * 以及容忍祖先目录打不开之后的文本。见 [patchAttachmentDurability]。
         *
         * ⚠️ 缩进是**一个制表符**，与官方发布件逐字一致；引擎升级后若不再匹配，
         * 补丁会跳过并在日志里提示，不会让启动失败。
         */
        private const val ATTACH_SYNC_GUARD =
            "\tconst handle = await open(path, constants.O_RDONLY);"
        private const val ATTACH_SYNC_ANCHOR =
            "\tlet handle;\n" +
                "\ttry {\n" +
                "\t\thandle = await open(path, constants.O_RDONLY);\n" +
                "\t} catch (err) {\n" +
                "\t\t/* Android: 祖先目录（/data、/data/user/0 等）被 SELinux 拒绝 open，\n" +
                "\t\t   而这些目录项的崩溃持久性在应用沙箱内本来就不可达，跳过即可。 */\n" +
                "\t\tif (err && (err.code === \"EACCES\" || err.code === \"EPERM\")) return;\n" +
                "\t\tthrow err;\n" +
                "\t}"

        /** 移动端 UI 适配插件：包名、资产名，以及挂载它的 overlay 文件名。 */
        private const val MOBILE_UI_ASSET = "mobile-ui.zip"
        private const val MOBILE_UI_PACKAGE = "dsh-android-mobile"
        private const val MOBILE_UI_PATCH = "mobile-ui.patch.yml"

        /**
         * 插件解包目录，相对 `$DSH_HOME`。
         *
         * ⚠️ **刻意不放在 profile 的 `node_modules` 下** —— 那里归 pnpm 管，而本包
         * 不在 profile 的 `dependencies` 里（写了会去 registry 拉），于是**用户每装一次
         * 插件，pnpm 就把它挪进 `node_modules/.ignored/`**，`--patch` 里的包名随即解析
         * 失败 → 客户端半不编入 → 界面静默退回桌面布局。详见 ensureMobileUi()。
         */
        private const val MOBILE_UI_DIR = "mobile-ui"

        /**
         * link()/linkat() 语义转译层（LD_PRELOAD）。
         * 资产名用中性后缀，避免 AGP 按扩展名做格式推断（同 engine.bin 的坑）。
         */
        private const val LINKCOMPAT_ASSET = "linkcompat.bin"
        private const val LINKCOMPAT_FILE = "liblinkcompat.so"

        /** ripgrep：官方 `@vscode/ripgrep` 没有 android 平台包，这里补一个。 */
        private const val RIPGREP_ASSET = "ripgrep-android-arm64.zip"
        private const val RIPGREP_PACKAGE = "@vscode/ripgrep-android-arm64"

        /** sharp 的 wasm32 变体（官方兜底，见 `ensureSharp()`）。 */
        private const val SHARP_ASSET = "sharp-wasm32.zip"
        private const val SHARP_WASM_PACKAGE = "@img/sharp-wasm32"
        private const val SHARP_VERSION = "0.35.5"

        /** node-pty：官方 prebuilds 没有 android，自编译一份（见 `ensureNodePty()`）。 */
        private const val NODE_PTY_ASSET = "node-pty-android-arm64.zip"

        /**
         * `dsh-subprocess-local` 里 `createProcessInspector()` 的平台判断原文，
         * 以及放行 android 之后的文本。见 [patchProcessInspector]。
         *
         * ⚠️ 缩进是**一个制表符**，与官方发布件逐字一致。
         * ⚠️ 文本里的 `${platform}` 是**引擎源码**里的模板插值，不是 Kotlin 的，
         * 所以 `$` 必须转义成 `\$`，否则 Kotlin 会当成自己的变量去解析。
         */
        private const val INSPECTOR_GUARD =
            "\tif (platform === \"win32\") return createWindowsProcessInspector();\n" +
                "\tthrow new Error(`subprocess-local: terminal inspection is unsupported on platform \${platform}`);"
        private const val INSPECTOR_ANDROID_ANCHOR =
            "\tif (platform === \"win32\") return createWindowsProcessInspector();\n" +
                "\t/* Android 就是 Linux 内核，/proc 格式一致，且 SYSCALLS 表里本来就有 arm64。\n" +
                "\t   app 域下 /proc 目录可读，只是别的 uid 的条目会被 SELinux 挡掉 ——\n" +
                "\t   LinuxProcessInspector 对读不到的条目一律跳过，不抛错。\n" +
                "\t   代价是只能看见自己 uid 的进程，而终端 shell 及其子进程全是本 app 的 uid。 */\n" +
                "\tif (platform === \"android\") return new LinuxProcessInspector(arch, internals);\n" +
                "\tthrow new Error(`subprocess-local: terminal inspection is unsupported on platform \${platform}`);"

        /** 沙箱：runner（bwrap 方言翻译）+ LD_PRELOAD 写拦截器。 */
        private const val SANDBOX_RUNNER_ASSET = "boo-sandbox-run.bin"
        private const val SANDBOX_RUNNER_FILE = "boo-sandbox-run"
        private const val SANDBOX_LIB_ASSET = "boosandbox.bin"
        private const val SANDBOX_LIB_FILE = "libboosandbox.so"
        private const val SANDBOX_PATCH = "sandbox.patch.yml"

        /** pnpm：Termux 的 $PREFIX/bin 里没有，插件管理器靠它装 bundle。 */
        private const val PNPM_ASSET = "pnpm-android-arm64.zip"
        private const val PNPM_VERSION = "11.28.3"
        private const val PNPM_STAMP = ".pnpm_version"

        /** pnpm 整包解到 `$PREFIX/lib/pnpm`，`bin/pnpm` 只是个 exec 它的脚本。 */
        private const val PNPM_DIR = "lib/pnpm"

        /**
         * 插件内容版本：改了 `mobile-ui.zip` 里的任何东西都必须改这里，
         * 否则设备上已解包的旧版不会被覆盖（同 TermuxBootstrap.NODE_VERSION 的道理）。
         */
        private const val MOBILE_UI_VERSION = "1.0.0-31"
        private const val MOBILE_UI_STAMP = ".mobile_ui_version"

        // ── uid 2000 特权通道 ────────────────────────────────────────
        //
        // 把 shell 身份执行注册成一个**独立的 `shell` 工具**（不是改 bash 的行为）。
        // 机制、实测数据与取舍见 assets/dsh-android-shell.mjs 与
        // mobile-ui-plugin/dsh-android-shell/lib/index.js 的头部注释。

        /** uid 2000 执行通道脚本（直连本机 adbd，走 ADB 协议）。 */
        private const val PRIVILEGED_RUNNER_ASSET = "dsh-android-shell.mjs"
        private const val PRIVILEGED_RUNNER_FILE = "dsh-android-shell.mjs"

        /** 注册 `shell` 工具的 host 插件；打包成 zip 随 APK 分发。 */
        private const val SHELL_TOOL_ASSET = "shell-tool.zip"
        private const val SHELL_TOOL_PACKAGE = "dsh-android-shell"
        private const val SHELL_TOOL_PATCH = "shell-tool.patch.yml"

        /**
         * 插件解包目录，相对 `$DSH_HOME`。
         *
         * ⚠️ **刻意不放在 profile 的 `node_modules` 下** —— 与移动端 UI 插件同一个坑：
         * 那里归 pnpm 管，而本包不在 profile 的 `dependencies` 里，用户每装一次插件
         * 就会被挪进 `node_modules/.ignored/`，overlay 里的包名随即解析失败。
         * 详见 [ensureMobileUi]。
         */
        private const val SHELL_TOOL_DIR = "shell-tool"

        /**
         * 无线调试（Android 11+）用的 adb 工具链。
         *
         * 取自 Termux 官方 `android-tools` 包的 `adb`（37.0.0），外加它 NEEDED 的
         * 全部 `.so`（`libprotobuf` / `libfmt` / `libabsl_*` / `libbrotli*` /
         * `libc++_shared` / `libz.so.1` / `liblz4` / `libzstd.so.1`）。
         * 理由与踩过的坑见 [ensureAdbWireless]。
         */
        private const val ADB_WIRELESS_ASSET = "adb-wireless-arm64.zip"
        private const val ADB_WIRELESS_DIR = "adbwire"

        /** 特权身份说明写进这个文件 —— agent-instructions 会自动读它并注入系统提示词。 */
        private const val INSTRUCTIONS_FILE = "AGENTS.md"

        private const val PRIVILEGED_PROMPT_BEGIN = "<!-- dsh-android:privileged BEGIN（本段由客户端生成，勿手改）-->"
        private const val PRIVILEGED_PROMPT_END = "<!-- dsh-android:privileged END -->"

        private val PRIVILEGED_PROMPT_ENABLED = """
            ## 特权身份（uid 2000 / shell）

            这台设备上你有**两个工具**可跑命令，默认的那个是受限的：

            | 工具 | 身份 | 能力 |
            |---|---|---|
            | `bash`（默认） | 应用身份 | 读写工作区、跑脚本、文件操作；有写沙箱与 link 转译保护 |
            | `shell` | shell 身份（uid 2000） | 系统级操作；**无写沙箱、无 link 转译** |

            ### 什么时候该用 `shell`

            需要**操作设备本身**时。这些在应用身份下**做不到**，而且失败方式很隐蔽：

            | 操作 | 应用身份的真实表现 | `shell` |
            |---|---|---|
            | 截图 `screencap` | rc=1，**stderr 完全为空**，只留下 0 字节文件 | rc=0，真图 |
            | 点击/滑动 `input` | **rc=0 但屏幕纹丝不动** | rc=0，真的生效 |
            | 卸载应用 `pm uninstall` | `SecurityException: INTERACT_ACROSS_USERS_FULL` | 走到包管理器 |
            | 读写系统设置 `settings` | `SecurityException: INTERACT_ACROSS_USERS` | 正常 |
            | 强制停止 `am force-stop` | `SecurityException: FORCE_STOP_PACKAGES` | 正常 |
            | 查看电池/窗口 `dumpsys` `wm` | rc=**0**，输出 `Can't find service` | 完整状态 |
            | 读输入设备 `getevent` | `/dev/input Permission denied` | 能枚举设备 |
            | 列出全部进程 `ps -A` | rc=0，**只看得见自己 uid 的 6 个进程** | 674 行 |

            ⚠️ **注意退出码骗人**：`input` 在应用身份下返回 0 却什么都没做；
            `dumpsys`/`ps` 返回 0 但结果是残缺的。所以**不要**用「退出码是不是 0」
            来判断命令有没有真正生效——上面的操作如果结果可疑，直接改用 `shell` 重试。

            ### 怎么用

            换工具名即可，**命令原文一字不改**：

            ```
            shell(command="screencap -p /sdcard/s.png",
                  description="截图到共享目录")
            ```

            ### ⚠️ 用了 `shell` 会失去什么

            shell 身份**读不到应用私有目录**（`/data/user/0/com.dsh.android` 是
            `drwx------`），因此：

            - **工作区、`${'$'}PREFIX`、`${'$'}DSH_HOME` 全部不可访问**。不要用 `shell` 跑
              `ls`、`cat`、相对路径这类依赖工作目录的命令——它们会失败或看到空目录。
              （`shell` 的 `workdir` 参数只对 `/sdcard` 这类共享路径有意义。）
            - **写沙箱不生效**：`touch /sdcard/x` 这类命令会真的写下去，不再被拦。
            - **link 转译不生效**：硬链接语义回到系统默认（应用私有目录里会 EACCES）。

            也就是说：**`shell` 换来的是特权，失去的是隔离**。只在该用的时候用。
            纯粹的读文件、写文件、跑脚本、装依赖，一律留在默认的 `bash`。

            ⚠️ 若 `shell` 的输出里出现 `${'$'}[dsh-adb] uid 2000 不可用`，说明特权通道
            此刻不可用，那条命令实际是以应用身份跑的——不要据此认为特权生效了，
            如实告诉用户需要重新引导。
        """.trimIndent()

        private val PRIVILEGED_PROMPT_DISABLED = """
            ## 特权身份（uid 2000 / shell）—— 当前不可用

            本机的 shell 身份通道**尚未启用**：还没有导入 ADB 密钥，或设备上的
            adbd 未开启 / 未授权。**`shell` 工具因此没有被注册**，你只有 `bash`。

            需要用户先用 USB 连接电脑执行一次引导（把电脑上已授权的 ADB 私钥推到设备）：

            ```
            adb push %USERPROFILE%\.android\adbkey /sdcard/dsh_adbkey
            ```

            然后重启本 App 即可。在此之前：

            - **不要**尝试用任何方式绕过应用身份去执行特权命令，那只会回落成应用身份。
            - 截图、点击屏幕、卸载应用、改系统设置、读 `/dev/input` 这类操作
              **当前一律做不到**，如实告诉用户并说明需要上面那次引导，不要反复重试。
        """.trimIndent()

        @Volatile
        private var instance: EngineManager? = null

        fun get(context: Context): EngineManager =
            instance ?: synchronized(this) {
                instance ?: EngineManager(context.applicationContext).also { instance = it }
            }
    }
}
