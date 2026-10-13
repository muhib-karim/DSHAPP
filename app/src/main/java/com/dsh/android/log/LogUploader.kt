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
            if (!uploading.compareAndSet(false, true)) return
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

    /** 用同名 `.attempts` 文件记重试次数（不改动 zip 本身）。 */
    private fun bumpAttempts(bundle: File): Int = runCatching {
        val f = File(bundle.parentFile, "${bundle.name}.attempts")
        val n = (f.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() ?: 0) + 1
        f.writeText(n.toString())
        n
    }.getOrDefault(0)

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

    /**
     * 崩溃后落盘时调用：生成包、入待传队列。
     *
     * ⚠️ 由**崩溃处理器**调用，所以：不联网、不做重活、全程 `runCatching`。
     * 真正的上传在下次启动（见 [retryPending]）。
     *
     * @return 入队的包路径；失败返回 null。
     */
    fun stashForCrash(logDir: File, extraFiles: List<File>): File? {
        return try {
            val outDir = File(logDir, "export").apply { mkdirs() }
            val bundle = DiagnosticBundle.build(logDir, outDir, "crash", extraFiles) ?: return null
            enqueue(bundle, logDir)
        } catch (t: Throwable) {
            AndroidLog.w(TAG, "崩溃后暂存诊断包失败: ${t.message}")
            null
        }
    }
}
