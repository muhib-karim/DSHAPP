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
            // 进度基准用 assets 里的压缩包总长（约 52MB）。
            // ⚠️ 只能把 openFd 当作「可能失败」的优化：它仅对**未压缩**的资产有效，
            // 而 AGP 默认会对 assets 做 deflate 压缩，此时 openFd 抛
            // FileNotFoundException。拿不到长度就退化为不显示百分比，绝不能因此
            // 让整个解包失败。
            val total = try {
                context.assets.openFd(ASSET_NAME).use { it.length }
            } catch (t: Throwable) {
                Log.i(TAG, "资产未压缩长度不可用（${t.javaClass.simpleName}），进度按估算显示")
                0L
            }
            var lastPercent = -1
            context.assets.open(ASSET_NAME).use { input ->
                TarGz.extract(input, staging) { written ->
                    if (total > 0) {
                        // written 是解压后的字节数（约 229MB），按压缩比换算成进度
                        val pct = ((written * 100) / (total * 5)).toInt().coerceIn(0, 99)
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

    /** 启动引擎，等待其打印带 token 的 URL。 */
    suspend fun start() = withContext(Dispatchers.IO) {
        if (process?.isAlive == true) {
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
        // 附件模块的目录 fsync 会走到 /data/user/0（SELinux 拒绝 open）→ 附件发不出去
        patchAttachmentDurability()
        // 移动端 UI 适配插件：必须在 profile 解析前落盘，否则 --patch 挂不上
        ensureMobileUi()
        val mobileUiPatch = writeMobileUiPatch()
        // 沙箱：Android 无内核级后端，用自带 runner 保住 workspace-write 的写边界
        val sandboxPatch = writeSandboxPatch(ensureSandbox())
        // link 兼容层：SELinux 不给 app_data_file 的 link 权限，需在 libc 边界转译
        val linkCompat = ensureLinkCompat()

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
            add("--no-open")
        }

        Log.i(TAG, "启动引擎: ${cmd.joinToString(" ")}")

        val prefix = bootstrap.prefixDir.absolutePath

        val pb = ProcessBuilder(cmd)
            .directory(workspaceDir)
            .redirectErrorStream(true)

        pb.environment().apply {
            // Termux 环境：node 的 RUNPATH 已被等长重写成
            // /data/data/com.dsh.android//usr/lib（经别名软链解析到真实 prefix），
            // 但 LD_LIBRARY_PATH 是更可靠的一层 —— 万一某个 .so 的 RUNPATH 没覆盖到，
            // 这里仍然找得到。PREFIX/HOME 让 DSH 内部按 Termux 习惯推导路径。
            put("PREFIX", prefix)
            put("LD_LIBRARY_PATH", "$prefix/lib")
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
        _state.value = State.Idle
    }

    fun isRunning(): Boolean = process?.isAlive == true

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
        private const val MOBILE_UI_VERSION = "1.0.0-4"
        private const val MOBILE_UI_STAMP = ".mobile_ui_version"

        @Volatile
        private var instance: EngineManager? = null

        fun get(context: Context): EngineManager =
            instance ?: synchronized(this) {
                instance ?: EngineManager(context.applicationContext).also { instance = it }
            }
    }
}
