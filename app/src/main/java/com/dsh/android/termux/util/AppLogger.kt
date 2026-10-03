package com.dsh.android.termux.util

import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用日志：内存环形缓冲（给日志页看）+ 落盘（给事后排查看）+ 崩溃捕获。
 *
 * ## 为什么必须落盘
 *
 * 改造前这里**纯内存**：`App 被杀 / 崩溃 / 用户手动强停` 之后现场全部消失，而
 * 「任务跑一半自己结束了」这类问题恰恰经常伴随进程重启。用户能提供的只剩一句
 * 转述，作者拿不到任何时间线。
 *
 * ## 三个约束
 *
 * 1. **落盘绝不能拖慢回合**。写入是**同步 append + 每行 flush**（约一次
 *    `write(2)`），因为崩溃处理器需要在进程死掉前拿到完整现场——异步队列在
 *    崩溃路径上会丢掉最后几条，而那几条通常正是最关键的。代价可接受：引擎每轮
 *    只打几行（不是每 token 一行）。
 * 2. **单个日志文件有上限**。`MAX_FILE_BYTES` 到了就轮转成 `.1`，只保留一代——
 *    否则长期使用会无限增长。
 * 3. **写不进去也不能崩**。`filesDir` 满 / 只读 / 权限异常时静默降级为纯内存，
 *    与改造前的行为一致。
 *
 * ⚠️ **日志会进诊断包并被分享出去**，所以 [redact] 会兜底抹掉形似密钥的串。
 * 这不是可选项：调用方只要有一次把 `apiKey` 拼进消息，用户的密钥就会跟着诊断包
 * 发到别人手里。
 */
object AppLogger {
    data class Entry(val t: Long, val level: Char, val tag: String, val msg: String) // D/I/W/E

    private val _logs = mutableListOf<Entry>()
    private const val MAX = 2000

    /** 单个日志文件上限；到了就轮转。 */
    private const val MAX_FILE_BYTES = 512L * 1024L

    /** 轮转代数：`boo.log` + `boo.log.1`，更老的不留。 */
    private const val KEEP_GENERATIONS = 1

    /** 崩溃现场的独立副本，不参与轮转——它比普通日志更值得保留。 */
    private const val CRASH_FILE = "last-crash.txt"

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** 日志目录，由 [init] 注入（`filesDir/logs`）。null = 纯内存模式。 */
    @Volatile
    private var logDir: File? = null

    @Volatile
    private var writer: BufferedWriter? = null

    @Volatile
    private var bytesWritten: Long = 0L

    @Volatile
    private var writeFailures: Int = 0

    /**
     * 挂上日志目录并开始落盘，同时安装崩溃处理器。
     *
     * ⚠️ 必须在 `Application.onCreate` 里调用，且**要早**——越早越好，否则启动
     * 阶段的崩溃抓不到。
     */
    fun init(dir: File) {
        try {
            if (!dir.exists()) dir.mkdirs()
            logDir = dir
            openWriter(append = true)
            reportPreviousCrash(dir)
        } catch (e: Throwable) {
            // 落盘不可用就退回纯内存。**不要**在这里用 Log.e 之外的任何东西——
            // 这时 AppLogger 自己还没准备好。
            logDir = null
            Log.e("Boo/Log", "AppLogger.init failed: ${e.message}")
        }
        installCrashHandler()
    }

    fun d(tag: String, msg: String) = add('D', tag, msg)
    fun i(tag: String, msg: String) = add('I', tag, msg)
    fun w(tag: String, msg: String) = add('W', tag, msg)
    fun e(tag: String, msg: String) = add('E', tag, msg)

    val entries: List<Entry> get() = synchronized(_logs) { _logs.toList() }

    fun clear() {
        synchronized(_logs) { _logs.clear() }
        // 只清内存、**不删文件**：文件是「用户点清空」之前的历史，正是排查时要的。
        // 若一并删掉，「先清空再复现」就永远看不到清空前的现场。
        i("Log", "in-memory log cleared by user (file kept)")
    }

    fun export(): String = synchronized(_logs) { format(_logs) }

    /** 日志目录（诊断包用）。 */
    fun logDirOrNull(): File? = logDir

    /** 上一次崩溃的现场文件（诊断包用）。 */
    fun crashFileOrNull(): File? = logDir?.let { File(it, CRASH_FILE) }

    private fun format(list: List<Entry>): String =
        list.joinToString("\n") { "${fmt.format(Date(it.t))} [${it.level}] [${it.tag}] ${it.msg}" }

    /**
     * 追加一条日志。
     *
     * ⚠️ 可见性是 `internal` 而非 `private`：`BooApp` 用它把引擎的 `Diag.Sink`
     * 接进来（引擎侧刻意不认识 android，见 `Diag` 的文档）。除此之外一律走
     * [d]/[i]/[w]/[e]。
     */
    internal fun add(level: Char, tag: String, msg: String) {
        val safe = redact(msg)
        synchronized(_logs) {
            if (_logs.size >= MAX) _logs.removeAt(0)
            _logs.add(Entry(System.currentTimeMillis(), level, tag, safe))
        }
        appendToFile(level, tag, safe)
        // 同时镜像到 logcat，过滤方式：adb logcat -s 'Boo/*'
        val line = "Boo/$tag"
        when (level) {
            'E' -> Log.e(line, safe)
            'W' -> Log.w(line, safe)
            'I' -> Log.i(line, safe)
            else -> Log.d(line, safe)
        }
    }

    // ── 落盘 ──────────────────────────────────────────────────────────────────

    private fun openWriter(append: Boolean) {
        val dir = logDir ?: return
        val f = File(dir, "boo.log")
        val fos = FileOutputStream(f, append)
        writer = BufferedWriter(OutputStreamWriter(fos, Charsets.UTF_8))
        bytesWritten = if (append) f.length() else 0L
    }

    private fun appendToFile(level: Char, tag: String, msg: String) {
        val w = writer ?: return
        try {
            synchronized(this) {
                val line = "${fmt.format(Date())} [${level}] [${tag}] ${msg}\n"
                w.write(line)
                w.flush()
                bytesWritten += line.length
                if (bytesWritten >= MAX_FILE_BYTES) rotate()
            }
        } catch (e: Throwable) {
            writeFailures++
            // 连续失败就彻底关掉落盘，避免每条日志都抛一次异常拖慢回合。
            if (writeFailures >= 3) {
                try { writer?.close() } catch (_: Throwable) {}
                writer = null
                Log.e("Boo/Log", "AppLogger: giving up on file logging after $writeFailures failures")
            }
        }
    }

    /** 轮转：`boo.log` → `boo.log.1`（旧的 `.1` 丢弃）。调用方已持有锁。 */
    private fun rotate() {
        try {
            writer?.close()
        } catch (_: Throwable) {
        }
        writer = null
        val dir = logDir ?: return
        val cur = File(dir, "boo.log")
        for (gen in KEEP_GENERATIONS downTo 1) {
            val src = if (gen == 1) cur else File(dir, "boo.log.${gen - 1}")
            val dst = File(dir, "boo.log.$gen")
            if (src.exists()) {
                if (dst.exists()) dst.delete()
                src.renameTo(dst)
            }
        }
        openWriter(append = false)
    }

    // ── 崩溃捕获 ──────────────────────────────────────────────────────────────

    /**
     * 安装进程级未捕获异常处理器。
     *
     * ⚠️ 处理完**必须**把异常交回默认处理器：不交回的话，进程会「吞掉」崩溃并
     * 卡在半死状态（ANR 而不是退出），用户的体验从「闪退」变成「卡住」——更糟。
     */
    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, err ->
            try {
                val stack = Log.getStackTraceString(err)
                add('E', "Crash", "uncaught on ${thread.name}: ${err::class.java.name}: ${err.message}")
                add('E', "Crash", stack)
                writeCrashCopy(stack)
                flushAndClose()
            } catch (_: Throwable) {
                // 崩溃处理器自己再崩就彻底没救了——静默，交给默认处理器。
            }
            prev?.uncaughtException(thread, err)
        }
    }

    /** 崩溃现场另存一份，独立于会轮转的 `boo.log`。 */
    private fun writeCrashCopy(stack: String) {
        val dir = logDir ?: return
        try {
            File(dir, CRASH_FILE).writeText(
                "time: ${fmt.format(Date())}\n\n$stack\n",
            )
        } catch (_: Throwable) {
        }
    }

    /**
     * 启动时把上一次的崩溃现场抬进日志。
     *
     * ⚠️ **读取后不删除**：用户可能重启几次才想起来导出，删掉就只剩最后一次。
     * 由诊断包导出时一并带上该文件，用户在日志页也能看到这条 Warn。
     */
    private fun reportPreviousCrash(dir: File) {
        val f = File(dir, CRASH_FILE)
        if (!f.exists()) return
        val text = try { f.readText() } catch (_: Throwable) { return }
        if (text.isBlank()) return
        add('W', "Crash", "上次运行发生过未捕获异常（详见诊断包 last-crash.txt）：")
        text.lineSequence().take(40).forEach { add('W', "Crash", "  $it") }
    }

    private fun flushAndClose() {
        try {
            synchronized(this) {
                writer?.flush()
                writer?.close()
            }
        } catch (_: Throwable) {
        }
        writer = null
    }

    // ── 脱敏 ──────────────────────────────────────────────────────────────────

    /**
     * 抹掉形似密钥的串。
     *
     * ⚠️ 这是**兜底**，不是许可证：调用方仍然不许主动把 `apiKey` 拼进日志。但日志
     * 会被打进诊断包分享给别人，一条漏网的密钥代价太高，所以这里做最后一道网。
     *
     * 覆盖两类：
     *  - `sk-`/`sk_` 等常见前缀的长 token；
     *  - `key`/`token`/`secret`/`password` 等键名后面跟的 `= "…"` / `: "…"` 值。
     */
    private val secretKeyPattern = Regex(
        "(?i)\\b(api[_-]?key|apikey|secret|token|password|passwd|authorization|bearer)" +
            "(\\s*[=:]\\s*)([\"']?)([A-Za-z0-9_\\-./+]{8,})\\3",
    )

    private val skTokenPattern = Regex("\\b(sk|sk-or|sk-ant|xai|gsk|AIza)[-_][A-Za-z0-9_\\-]{12,}")

    fun redact(s: String): String {
        if (s.isEmpty()) return s
        var out = s
        if (out.contains("sk-") || out.contains("sk_") || skTokenPattern.containsMatchIn(out)) {
            out = skTokenPattern.replace(out) { m -> m.value.take(6) + "***REDACTED***" }
        }
        if (secretKeyPattern.containsMatchIn(out)) {
            out = secretKeyPattern.replace(out) { m ->
                "${m.groupValues[1]}${m.groupValues[2]}${m.groupValues[3]}***REDACTED***${m.groupValues[3]}"
            }
        }
        return out
    }
}
