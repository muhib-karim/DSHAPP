package com.dsh.android.log

import android.util.Log as AndroidLog
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 诊断包上传 —— 把「用户报问题」从「来回问三轮」压成「一次上传」。
 *
 * ## 上传时机（三种，都是必需的）
 *
 * | 时机 | 触发 | 为什么需要 |
 * |---|---|---|
 * | 崩溃后 | 下次启动时扫 `pending/` 自动补传 | ⚠️ 见下 —— 崩溃当场**传不出去** |
 * | 手动 | 用户在日志界面点「上传」 | 用户主动反馈时立刻到手，不等重启 |
 * | 重试 | 启动时扫 `pending/` | 上次没网/服务器没起来 |
 *
 * ## ⚠️ 崩溃时为什么不能当场上传（这是平台限制，不是实现偷懒）
 *
 * 崩溃处理器跑在**即将死掉的进程**里，只有几秒预算，而上传要：
 * 建连接（可能几秒）→ 传 8MB（移动网络下几十秒）→ 等响应。
 * 在这期间进程就被系统收走了，而且 Android 不允许在崩溃路径上可靠地起后台任务
 * （`WorkManager` 也要等下次启动）。
 *
 * 所以崩溃时的正确做法是**只落盘 + 标记待传**，真正的上传交给**下次启动**。
 *
 * ⚠️ **诚实说明它的边界**：如果用户崩溃后再也不打开 App，这个包就传不出去。
 * 没有任何绕过的办法（除非有常驻的独立进程 —— 那远超本功能的收益）。
 * 所以界面上的手动上传入口**不是可选项**，是必需的补充。
 *
 * ## 与服务器解耦
 *
 * 端点与令牌都可由调用方设置（[configure]）。未配置时所有上传请求**立即返回
 * 明确失败**（`NOT_CONFIGURED`），而不是静默不做事 —— 静默失败会让用户以为
 * 「点了没反应」，那是最难查的一类反馈。
 */
object LogUploader {

    private const val TAG = "DSH/LogUploader"

    /** 待传目录（`logs/pending`）。 */
    private const val PENDING_DIR = "pending"

    /** 手动导出目录（`logs/export`）。 */
    private const val EXPORT_DIR = "export"

    /**
     * 已打包现场集合的标记文件（`logs/crash/.queued`）。
     *
     * ⚠️ **不能以 `.txt` 结尾** —— `CrashRecorder.listScenes()` 与
     *   [collectCrashScenes] 都按 `.txt` 认现场文件，用 .txt 会让标记文件
     *   把自己算进集合，签名永远在变、每次启动都重复打包。
     */
    private const val QUEUED_MARKER = ".queued"

    /** 重试上限。超过就移到 `failed/`，不再自动重试（避免无限占用流量）。 */
    private const val MAX_ATTEMPTS = 3

    /** 单次上传的超时。移动网络下 8MB 需要的时间。 */
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 120_000

    /** 上传端点。null = 未配置。 */
    @Volatile
    private var endpoint: String? = null

    /** 鉴权令牌。null = 不带。 */
    @Volatile
    private var token: String? = null

    /** 匿名设备指纹 —— 用来把同一台机器的多次上报关联起来。 */
    @Volatile
    private var deviceId: String = ""

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dsh-log-upload").apply { isDaemon = true }
    }

    /** 是否正在上传（界面据此禁按钮，避免重复点）。 */
    private val uploading = AtomicBoolean(false)

    val isUploading: Boolean get() = uploading.get()

    /**
     * 配置上传参数。
     *
     * @param endpoint 服务器地址；空串或 null = 未配置。
     * @param token 鉴权令牌；可空。
     */
    fun configure(endpoint: String?, token: String?, logDir: File) {
        this.endpoint = endpoint?.takeIf { it.isNotBlank() }
        this.token = token?.takeIf { it.isNotBlank() }
        // 设备指纹：**不是** IMEI/序列号这类需要权限的标识（本工程 targetSdk=28，
        // 也不该收集）。用「安装时生成一次的随机 UUID」—— 它能区分设备，
        // 但无法反查到人，且卸载重装即变。
        deviceId = loadOrCreateDeviceId(logDir)
    }

    private fun loadOrCreateDeviceId(logDir: File): String = runCatching {
        val f = File(logDir, "device-id")
        if (f.isFile) {
            f.readText().trim().takeIf { it.isNotEmpty() }?.let { return@runCatching it }
        }
        val id = UUID.randomUUID().toString()
        runCatching { f.writeText(id) }
        id
    }.getOrDefault("")

    /** 上传是否可用（端点已配置）。 */
    val configured: Boolean get() = !endpoint.isNullOrBlank()

    /** 待传包数量（界面显示「N 份待上传」）。 */
    fun pendingCount(logDir: File): Int =
        File(logDir, PENDING_DIR).listFiles()?.count { it.isFile && it.name.endsWith(".zip") } ?: 0

    /**
     * 把诊断包放进待传队列。
     *
     * ⚠️ **不是复制而是移动**：包已经在 `export/` 里放着，再复制一份会双倍占用
     * 磁盘。移动失败（跨文件系统）时才退回复制。
     */
    fun enqueue(bundle: File, logDir: File): File? = runCatching {
        val dir = File(logDir, PENDING_DIR).apply { mkdirs() }
        val target = File(dir, bundle.name)
        if (target.absolutePath == bundle.absolutePath) return@runCatching target
        if (bundle.renameTo(target)) return@runCatching target
        bundle.copyTo(target, overwrite = true)
        bundle.delete()
        target
    }.getOrNull()

    /**
     * 上传一个包。**回调保证被调用一次**（成功或失败都调）。
     *
     * @param onResult (成功?, 给用户看的说明)
     */
    fun upload(bundle: File, reason: String, onResult: (Boolean, String) -> Unit) {
        val ep = endpoint
        if (ep.isNullOrBlank()) {
            // 未配置 → 明确失败，不静默。见类注释。
            onResult(false, "尚未配置日志服务器，已保存到本地（可在日志界面导出）")
            return
        }
        if (!uploading.compareAndSet(false, true)) {
            onResult(false, "已有上传在进行中，请稍候")
            return
        }
        executor.execute {
            try {
                val (ok, msg) = doUpload(ep, bundle, reason)
                // ⚠️ 成功后**必须删掉 pending/ 里的那一份**，否则下次启动
                //   [retryPending] 会把同一个包再传一遍。
                //
                //   这个坑真踩过（服务器上留了铁证）：两份包的 sha256 完全相同，
                //   一份 reason=manual、一份 reason=startup-retry，间隔 2 分钟 ——
                //   同一份诊断包被传了两次，白白浪费用户流量，也让服务端多一份重复。
                //   手动上传与自动补传必须共用「成功即出队」这一个语义。
                if (ok) runCatching { bundle.delete() }
                onResult(ok, msg)
            } catch (t: Throwable) {
                onResult(false, "上传失败：${t.message}")
            } finally {
                uploading.set(false)
            }
        }
    }

    /**
     * 启动时补传：把 `pending/` 里的包逐个传出去，成功的删掉。
     *
     * ⚠️ **必须异步、必须在引擎启动之外**：它会发网络请求，放在启动路径上会
     * 拖慢启动（而用户此刻正等着界面出来）。调用方自己起线程或用
     * [com.dsh.android.DshApplication.applicationScope]。
     *
     * ⚠️ 失败**不删除**：留着下次再试。只有重试次数超限才移到 `failed/`。
     */
    fun retryPending(logDir: File, onEach: ((File, Boolean, String) -> Unit)? = null) {
        if (!configured) return
        val dir = File(logDir, PENDING_DIR)
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".zip") } ?: return
        if (files.isEmpty()) return
        // 按时间从旧到新：先传早的，让服务器按顺序看到问题演变。
        for (f in files.sortedBy { it.lastModified() }) {
            // ⚠️ **被别的上传占着时要说话，不能静默放弃整批。**
            //   原来这里是 `return`：手动导出正在上传时，整批补传一份都不做，
            //   而且 `onEach` 不触发、一行日志都没有 —— 调用方
            //   （`DshApplication.uploadPendingBundles`）打完「发现 N 份待上传」
            //   之后就再无输出，看起来像「传完了」或「本来就没事」。
            //   现在记一条 Warn 说明原因，并把这次放弃如实说出来。
            if (!uploading.compareAndSet(false, true)) {
                LogStore.app(
                    'W', "Upload",
                    "已有上传在进行中，本次补传跳过（待传 ${files.size} 份，下次启动会再试）",
                )
                return
            }
            try {
                val (ok, msg) = doUpload(endpoint!!, f, "startup-retry")
                onEach?.invoke(f, ok, msg)
                if (ok) {
                    f.delete()
                } else {
                    val attempts = bumpAttempts(f)
                    if (attempts >= MAX_ATTEMPTS) {
                        val failedDir = File(dir.parentFile, "failed").apply { mkdirs() }
                        f.renameTo(File(failedDir, f.name))
                        LogStore.app('W', "Upload", "待传包重试 $attempts 次仍失败，已移入 failed/: ${f.name}")
                    }
                }
            } finally {
                uploading.set(false)
            }
        }
    }

    /**
     * 用同名 `.attempts` 文件记重试次数（不改动 zip 本身）。
     *
     * ⚠️ **写失败时不能返回 0。** 调用处是 `if (attempts >= MAX_ATTEMPTS)`，返回 0
     *   会让这个判据**恒假**，于是 `MAX_ATTEMPTS` 与 `failed/` 永不生效 ——
     *   每个启动都把全部 pending 重试一遍，无限耗流量，且这条失败无任何日志。
     *   而 `.attempts` 写不进去的典型原因正是**磁盘满**，也就是本系统要处理的核心
     *   故障场景 —— 最需要它生效的时候它恰好失效。
     *
     *   修法：计数先自增、再尝试落盘；**落盘失败也把自增后的值返回**（内存里的判断
     *   依然正确），并记一条 Warn 让分析者知道持久化没成功（下次启动会从头计）。
     */
    private fun bumpAttempts(bundle: File): Int = runCatching {
        val f = File(bundle.parentFile, "${bundle.name}.attempts")
        val n = (f.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() ?: 0) + 1
        runCatching { f.writeText(n.toString()) }
            .onFailure { t ->
                LogStore.app(
                    'W', "Upload",
                    "重试计数无法落盘（${t.javaClass.simpleName}: ${t.message}），" +
                        "本次按第 $n 次处理；下次启动会从头计。",
                )
            }
        n
    }.getOrDefault(MAX_ATTEMPTS)

    /**
     * 启动时把**尚未打包**的崩溃现场打成诊断包并入队。
     *
     * ## 为什么必须在这里做，而不是在崩溃处理器里
     *
     * 崩溃处理器只有几秒预算，而打一个包要读几 MB 日志、压缩、写盘 —— 是重活。
     * 原来的设计里 `CrashRecorder` **从来没有调用过** [stashForCrash]（那个方法
     * 定义了却无人调用），于是 `pending/` 永远空着，`retryPending` 什么都传不出去：
     * **「崩溃自动上传」这条链路从来没有生效过**。服务器上收到的包 `reason` 只有
     * `manual` 与 `startup-retry`，没有任何一个 `crash`，就是铁证。
     *
     * 所以改成：崩溃当场只写现场文件（快、可靠），**下次启动**在这里补打包 + 入队。
     * 与 [retryPending] 的注释所述边界一致 —— 用户崩溃后再也不打开 App，
     * 这个包就传不出去，那是平台限制。
     *
     * ## 幂等判据
     *
     * `crash/.queued` 里记着**已打包过的现场文件名**（排序后）。集合没变就跳过，
     * 于是同一批现场不会被反复打包。有新现场（或清空后又有）才重新打包。
     *
     * ⚠️ **返回 null 有两种含义，必须区分**：①「没有新现场」（正常，常态）；
     *   ②「有新现场但打包/入队失败」（异常，**必须留痕**）。原来这两者都只是
     *   一个 `return@runCatching null`，而唯一的调用方
     *   （`DshApplication.uploadPendingBundles`）**忽略返回值** —— 于是磁盘满 /
     *   `export/` 建不出来时，那批崩溃现场**永远不会被上传，也一个字都不说**，
     *   表现与「本来就没有现场」完全相同。崩溃现场是这个系统的核心数据，
     *   悄悄不传等于把用户的求助信号丢掉。
     *   ②现在各记一条 Warn（`W` 而不是 `E`：不传不等于崩，但用户/分析者必须知道）。
     *
     * @return 入队的包；没有新现场时返回 null（正常）；失败时也返回 null，但会记日志。
     */
    fun collectCrashScenes(logDir: File): File? = runCatching {
        val crashDir = File(logDir, "crash")
        // ⚠️ 只认 .txt：标记文件本身不能是 .txt，否则会被自己算进「现场集合」。
        val scenes = crashDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".txt") }
            ?.sortedBy { it.name }
            ?: return@runCatching null
        if (scenes.isEmpty()) return@runCatching null

        val marker = File(crashDir, QUEUED_MARKER)
        val signature = scenes.joinToString("\n") { it.name }
        val prev = runCatching { marker.takeIf { it.isFile }?.readText() }.getOrNull()
        if (prev == signature) return@runCatching null // 没有新现场（常态）

        val outDir = File(logDir, EXPORT_DIR).apply { mkdirs() }
        val bundle = DiagnosticBundle.build(logDir, outDir, "crash")
        if (bundle == null) {
            LogStore.app('W', "Upload", "崩溃现场打包失败，本次 ${scenes.size} 份现场不会上传（下次启动会重试）")
            return@runCatching null
        }
        val queued = enqueue(bundle, logDir)
        if (queued == null) {
            LogStore.app('W', "Upload", "诊断包入队失败（${bundle.name}），本次 ${scenes.size} 份现场不会上传")
            return@runCatching null
        }
        // ⚠️ 标记写在**入队成功之后**：反过来会「标记了但没入队」，那批现场就
        //   永远不会再被尝试。
        runCatching { marker.writeText(signature) }
        LogStore.app('I', "Upload", "崩溃现场已打包入队: ${queued.name}（${scenes.size} 份现场）")
        queued
    }.onFailure { t ->
        // 外层兜底：上面任何一步意外抛异常（`listFiles` 权限、OOM…）也要留痕。
        LogStore.app('W', "Upload", "收集崩溃现场时异常（本次不会上传）: ${t.javaClass.simpleName}: ${t.message}")
    }.getOrNull()

    // ── 真正的上传 ────────────────────────────────────────────────────────────

    /**
     * 发一次 multipart/form-data 请求。
     *
     * ## 为什么手写 multipart 而不用现成库
     *
     * 本工程**没有任何网络库依赖**（连 OkHttp 都没有，全工程用 `HttpURLConnection`，
     * 见 `MainActivity.handleDownload`）。为一个上传功能引入依赖不划算，而
     * multipart 的格式很简单。照抄项目既有写法。
     *
     * @return (成功?, 给用户看的说明)
     */
    private fun doUpload(ep: String, bundle: File, reason: String): Pair<Boolean, String> {
        val boundary = "----DSHLog" + UUID.randomUUID().toString().replace("-", "")
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(ep).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                token?.let { setRequestProperty("Authorization", "Bearer $it") }
            }

            conn.outputStream.buffered().use { out ->
                fun field(name: String, value: String) {
                    out.write("--$boundary\r\n".toByteArray())
                    out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
                    out.write(value.toByteArray(Charsets.UTF_8))
                    out.write("\r\n".toByteArray())
                }
                field("device", deviceId)
                field("version", VersionInfo.versionName)
                field("versionCode", VersionInfo.versionCode.toString())
                field("flavor", VersionInfo.flavor)
                field("reason", reason)
                field("schema", DiagnosticBundle.SCHEMA_VERSION.toString())
                field("bootId", BootState.bootId)

                out.write("--$boundary\r\n".toByteArray())
                out.write(
                    ("Content-Disposition: form-data; name=\"file\"; filename=\"${bundle.name}\"\r\n" +
                        "Content-Type: application/zip\r\n\r\n").toByteArray(),
                )
                bundle.inputStream().use { it.copyTo(out) }
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }

            val code = conn.responseCode
            if (code in 200..299) {
                val body = runCatching { conn.inputStream.bufferedReader().readText().take(500) }.getOrDefault("")
                // 服务器若回了 id，把它显示给用户 —— 反馈问题时报这个编号最快。
                val id = runCatching { JSONObject(body).optString("id", "") }.getOrDefault("")
                LogStore.app('I', "Upload", "诊断包上传成功: ${bundle.name} code=$code id=$id")
                Pair(true, if (id.isNotEmpty()) "上传成功，编号 $id" else "上传成功（HTTP $code）")
            } else {
                // ⚠️ `errorStream` 本身可能为 null（连接就断了），readText 的结果也是可空的，
                //   所以这里必须补一个 `?: ""`，否则拼接时拿到的是 null 字面量。
                val err = runCatching { conn.errorStream?.bufferedReader()?.readText()?.take(300) }
                    .getOrNull().orEmpty()
                LogStore.app('W', "Upload", "诊断包上传失败: ${bundle.name} code=$code $err")
                Pair(false, "上传失败：HTTP $code ${err.take(120)}")
            }
        } catch (t: Throwable) {
            LogStore.app('W', "Upload", "诊断包上传异常: ${bundle.name} ${t.message}")
            Pair(false, "上传失败：${t.message ?: t.javaClass.simpleName}（已留在本地，稍后自动重试）")
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
