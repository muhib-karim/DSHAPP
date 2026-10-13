package com.dsh.android.log

import android.util.Log as AndroidLog
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.withLock

/**
 * 分层日志存储 —— 整个日志系统的地基。
 *
 * ## 它解决的问题
 *
 * 改造前全工程的日志**只写 logcat**：进程一死（崩溃、被 LMK 回收、被国产 ROM 冻结、
 * 断电）现场全部消失，而用户能提供的只剩一句转述。诊断包、上传、AI 排查全都无从谈起。
 *
 * ## 三个文件各管一段，不是一个文件切三段
 *
 * | 文件 | 来源 | 写入 | 为什么 |
 * |---|---|---|---|
 * | `app.log` | 476 处 `DshLog.*` | **同步 + 每行 flush** | 崩溃处理器要在进程死掉前拿到完整现场；异步队列在崩溃路径上会丢最后几条，而那几条通常正是最关键的。引擎每轮只打几十行，代价可接受 |
 * | `engine.log` | 引擎 stdout+stderr | **异步缓冲** | 引擎会**高频**吐日志（每 token、每次工具调用），同步 flush 会拖慢回合。可容忍丢最后几条 |
 * | `web.log` | 网页 console | **异步 + 限流** | 页面脚本可能循环打日志，不限流会瞬间冲爆磁盘 |
 *
 * 分开而不是混在一个文件里：三者体量差几个数量级，混在一起时引擎的高频输出会把
 * 应用日志挤掉轮转，排查时最想看的「App 自己说了什么」反而最先丢。
 *
 * ## 三条不变量
 *
 * ① **写日志绝不能把 App 搞崩。** 任何异常都被吞掉并计数，连续失败就彻底关掉落盘、
 *    退回纯内存 —— 与改造前的行为一致。因为写日志失败而崩溃是灾难，而且那时
 *    崩溃现场也写不出来。
 * ② **崩溃路径用 `tryLock`。** 崩溃可能发生在**正持有日志锁**的线程上，硬等锁会
 *    死锁、把「闪退」变成「卡死」。拿不到锁就只写 crash 文件（见 [CrashRecorder]）。
 * ③ **轮转只保一代。** `app.log` → `app.log.1`，更老的不留 —— 否则长期使用会
 *    无限增长。`crash/` 是例外，见下。
 *
 * ## `crash/` 为什么不参与轮转
 *
 * 崩溃现场（堆栈 + 最后 200 行日志）比普通日志更值得保留：普通日志是「过程」，
 * 崩溃现场是「结论」。所以它单独配额、单独目录、按时间戳命名（一次崩溃一个文件，
 * 不会互相覆盖），只受总配额约束。
 */
object LogStore {

    /** 日志目录，由 [init] 注入。null = 纯内存模式（落盘不可用）。 */
    @Volatile
    private var dir: File? = null

    /** 进程内单调递增序号。用于把「同一条日志」在多个文件间对上，也给排查提供顺序。 */
    private val seq = AtomicLong(0)

    /**
     * 内存环形缓冲 —— 给日志界面看 + 崩溃现场取「最后 N 行」。
     *
     * ⚠️ **不只是为了界面**：崩溃时文件写入可能因为异步缓冲丢了最后几条，
     * 而内存里的这几条正好补上。所以它的大小要够覆盖一次典型崩溃前的活动量。
     */
    private val ring = ArrayDeque<Entry>()

    /**
     * 内存缓冲条数上限。
     *
     * ⚠️ 刻意是 `public`：`LogCommandBridge` 给模型的 `summary` 里要说明
     *   「缓冲只覆盖最近这么多条」—— 不写清楚，模型会把「缓冲里没有」当成
     *   「历史上没发生」。这个数字是那句免责声明的一部分，不能让两处各写一个。
     */
    const val RING_MAX = 4000

    /** 单文件上限。到了就轮转。 */
    private const val MAX_FILE_BYTES = 1L * 1024L * 1024L

    /** 轮转代数：`x.log` + `x.log.1`，更老的不留。 */
    private const val KEEP_GENERATIONS = 1

    /**
     * 日志目录总配额。
     *
     * ⚠️ 超了**删最老的**而不是拒绝写入：宁可少一段历史，也不要「日志系统自己把
     * 磁盘占满」—— 那会让引擎写会话失败，问题从「查不到」升级成「用不了」。
     */
    private const val TOTAL_QUOTA_BYTES = 32L * 1024L * 1024L

    /** 崩溃现场配额。独立于总配额之外单独把关，见类注释。 */
    private const val CRASH_QUOTA_BYTES = 8L * 1024L * 1024L
    private const val CRASH_MAX_FILES = 20

    /** 崩溃现场文件里附带的「最后 N 行日志」。 */
    const val CRASH_TAIL_LINES = 200

    /**
     * 同类重复日志的限流窗口与上限。
     *
     * ⚠️ 这是**防日志炸弹**的：页面脚本被循环调用时（本项目真机遇到过 console
     * 每帧打一行），一分钟能产出几十 MB。限流后只留首条 + 一条汇总计数。
     */
    private const val THROTTLE_WINDOW_MS = 1000L
    private const val THROTTLE_MAX_REPEAT = 20

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** 紧凑时间戳（文件名用，不能含冒号）。 */
    private val fileFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    // ── 写入句柄 ──────────────────────────────────────────────────────────────

    /** `app.log`：同步写，崩溃路径也要能用。 */
    @Volatile
    private var appWriter: BufferedWriter? = null
    @Volatile
    private var appBytes = 0L

    /** `engine.log` / `web.log`：异步写。 */
    private val queue = ArrayBlockingQueue<QueuedLine>(4096)
    @Volatile
    private var worker: Thread? = null
    @Volatile
    private var engineWriter: BufferedWriter? = null
    @Volatile
    private var engineBytes = 0L
    @Volatile
    private var webWriter: BufferedWriter? = null
    @Volatile
    private var webBytes = 0L

    @Volatile
    private var writeFailures = 0

    /** 上一次限流判据（tag+level 相同即视为同类）。 */
    private var lastThrottleKey = ""
    private var lastThrottleAt = 0L
    private var throttleCount = 0

    /** 被限流丢弃的总条数（写进诊断包，让分析者知道日志被抽过稀）。 */
    @Volatile
    var throttledTotal = 0L
        private set

    /** 落盘是否可用。false 时只有内存缓冲。 */
    val fileLoggingEnabled: Boolean get() = dir != null && appWriter != null

    /** 日志目录（诊断包用）。 */
    fun dirOrNull(): File? = dir

    /** 崩溃现场目录。 */
    fun crashDirOrNull(): File? = dir?.let { File(it, "crash") }

    // ── 生命周期 ──────────────────────────────────────────────────────────────

    /**
     * 挂上日志目录并开始落盘。
     *
     * ⚠️ 必须在 `Application.attachBaseContext()` 里调用（比 `onCreate` 还早），
     * 否则启动阶段的崩溃抓不到。这里**只做建目录 + 开文件**，不做任何耗时的事
     * —— 它挡在 App 启动路径上。
     *
     * @return 落盘是否可用。
     */
    fun init(logDir: File): Boolean {
        return try {
            if (!logDir.exists() && !logDir.mkdirs()) {
                AndroidLog.e(TAG, "日志目录创建失败: ${logDir.absolutePath}")
                return false
            }
            dir = logDir
            migrateLegacy(logDir)
            openApp(append = true)
            File(logDir, "crash").mkdirs()
            startWorker()
            enforceQuota()
            true
        } catch (t: Throwable) {
            // 落盘不可用就退回纯内存。**不要**在这里用 DshLog —— 它还没接上。
            dir = null
            AndroidLog.e(TAG, "LogStore.init 失败，退回纯内存: ${t.message}")
            false
        }
    }

    /**
     * 把老版本的 `boo.log` 挪成 `app.log.1`。
     *
     * ⚠️ **不能删**：老日志里可能有用户上次反馈问题时的那一段现场，而升级后的
     * 第一次排查恰恰需要它。改名保留一代是最小代价。
     */
    private fun migrateLegacy(logDir: File) {
        val old = File(logDir, "boo.log")
        if (!old.isFile) return
        try {
            val target = File(logDir, "app.log.1")
            if (target.exists()) target.delete()
            if (old.renameTo(target)) {
                AndroidLog.i(TAG, "已把旧的 boo.log 归档为 app.log.1")
            }
        } catch (_: Throwable) {
        }
        // 老的 last-crash.txt 一并归档到新位置，保持「崩溃现场独立留存」的语义
        val oldCrash = File(logDir, "last-crash.txt")
        if (oldCrash.isFile) {
            try {
                val crashDir = File(logDir, "crash").apply { mkdirs() }
                val target = File(crashDir, "${fileFmt.format(Date())}-legacy.txt")
                oldCrash.renameTo(target)
            } catch (_: Throwable) {
            }
        }
    }

    // ── 写入入口 ──────────────────────────────────────────────────────────────

    /**
     * 记一条**应用日志**（476 处 `DshLog.*` 的落点）。同步写，崩溃安全。
     *
     * @param level 级别字符 `V/D/I/W/E`（`wtf` 归一成 `E`）。
     */
    fun app(level: Char, tag: String, msg: String) {
        val safe = safeRedact(msg)
        val n = seq.incrementAndGet()
        val now = System.currentTimeMillis()
        val th = Thread.currentThread().name
        remember(Entry(now, level, tag, safe, n, th))
        if (throttled(level, tag, safe, now)) return
        writeApp(format(now, level, tag, safe, n, th))
    }

    /**
     * 记一条**引擎日志**（stdout/stderr）。异步。
     *
     * ⚠️ 引擎输出里带 token 的那行 URL 会被 [Redactor] 抹掉 —— 见
     * `urlSecretPattern` 的注释。
     */
    fun engine(line: String) {
        val safe = safeRedact(line)
        val n = seq.incrementAndGet()
        val now = System.currentTimeMillis()
        val th = Thread.currentThread().name
        remember(Entry(now, 'I', "engine", safe, n, th))
        enqueue(QueuedLine.Target.ENGINE, format(now, 'I', "engine", safe, n, th))
    }

    /** 记一条**网页 console 日志**。异步 + 限流。 */
    fun web(line: String) {
        val safe = safeRedact(line)
        val n = seq.incrementAndGet()
        val now = System.currentTimeMillis()
        val th = Thread.currentThread().name
        remember(Entry(now, 'I', "web", safe, n, th))
        if (throttled('I', "web", safe, now)) return
        enqueue(QueuedLine.Target.WEB, format(now, 'I', "web", safe, n, th))
    }

    /** 统一行格式：`时间 [级别] [tag] #seq 线程 正文`。 */
    private fun format(t: Long, level: Char, tag: String, msg: String, n: Long, thread: String): String =
        "${fmt.format(Date(t))} [$level] [$tag] #$n $thread $msg"

    // ── 内存缓冲 ──────────────────────────────────────────────────────────────

    private fun remember(e: Entry) {
        synchronized(ring) {
            if (ring.size >= RING_MAX) ring.removeFirst()
            ring.addLast(e)
        }
    }

    /** 全部内存日志（新 → 旧顺序无关，界面自己排）。 */
    fun entries(): List<Entry> = synchronized(ring) { ring.toList() }

    /** 最近 [n] 行，格式化成文本。崩溃现场与 AI 工具都用它。 */
    fun tail(n: Int, minLevel: Char? = null): List<String> {
        val list = synchronized(ring) { ring.toList() }
        val filtered = if (minLevel == null) list else list.filter { severity(it.level) >= severity(minLevel) }
        return filtered.takeLast(n.coerceAtLeast(0))
            .map { format(it.t, it.level, it.tag, it.msg, it.seq, it.thread) }
    }

    /** 级别严重度排序，供筛选比较。 */
    fun severity(level: Char): Int = when (level) {
        'E' -> 4
        'W' -> 3
        'I' -> 2
        'D' -> 1
        else -> 0
    }

    /** 清空内存缓冲（**不删文件** —— 见 [clearInMemory]）。 */
    private fun clearRing() {
        synchronized(ring) { ring.clear() }
    }

    /**
     * 用户点「清空」。
     *
     * ⚠️ **只清内存、不删文件**：文件是「用户点清空」之前的历史，正是排查时要的。
     * 若一并删掉，「先清空再复现」就永远看不到清空前的现场。
     */
    fun clearInMemory() {
        clearRing()
        app('I', "Log", "内存日志已由用户清空（文件保留）")
    }

    /**
     * 用户点「清空」——**连文件一起删**。
     *
     * ⚠️ 与 [clearInMemory] 是两个不同的语义，别混用：
     * - [clearInMemory]：只丢内存缓冲，文件保留。给「AI 想清掉噪音再复现」用 ——
     *   那时磁盘上的历史恰恰是证据。
     * - [clearAll]：文件也删。给**界面上那个按钮**用 —— 用户点了「清空」却发现
     *   重启后日志又回来了（因为文件还在、启动时会被重新读入），会认为功能坏了。
     *
     * 删的范围：`app.log` 及其轮转代、`engine.log`、`web.log`、崩溃现场、
     * 事件时间线、`export/` 与 `pending/`（都是本系统自己生成的，删掉不伤业务）。
     *
     * ⚠️ **不删 `device-id`**：那是安装期生成一次的匿名指纹，删了会让这台机器
     * 在服务器侧变成另一台设备，同一台机器的多次上报无法关联。
     *
     * @return 删除的文件数（界面用它给用户一个具体的回执）。
     */
    fun clearAll(logDir: File): Int {
        var n = 0
        // 先清内存，避免界面在这之后还从缓冲里读到旧内容。
        clearRing()
        val targets = listOf(
            APP_LOG, "$APP_LOG.1",
            ENGINE_LOG, "$ENGINE_LOG.1",
            WEB_LOG, "$WEB_LOG.1",
            EventLog.FILE_NAME,
            "last-crash.txt",
        )
        for (name in targets) {
            runCatching { if (File(logDir, name).delete()) n++ }
        }
        for (sub in listOf("crash", "export", "pending", "failed")) {
            runCatching {
                File(logDir, sub).listFiles()?.forEach { f ->
                    if (f.isFile && f.delete()) n++
                }
            }
        }
        // 清完之后立刻重开 app.log：不重开的话，writer 还指向已被删除的 inode，
        // 后续日志会写进一个「已经没有名字」的文件，界面上看不到任何新日志。
        //
        // ⚠️ **`engine.log` / `web.log` 必须一起重开**（踩过，代价是整段日志凭空消失）：
        //   这两者走 [engineWriterOf] / [webWriterOf] 的惰性创建，而它们的第一行是
        //   `engineWriter?.let { return it }` —— 只要引用非空就直接返回**旧** writer，
        //   永远不会自己发现文件已被删。于是此后的引擎输出全部写进那个已被 unlink
        //   的 inode（没有名字、进程一死就彻底消失）。
        //   真机证据：`20261013-232433-ee417cc3` 等 6 个包在 23:00:41 点了「清空」，
        //   之后引擎又输出了 32~97 行，而 `engine.log` 里**一行都没有**（0 字节）；
        //   同时 `app.log` 里那 32~97 行 `[engine]` 副本完好无损 —— 两份日志的分叉
        //   正好卡在清空那一刻，这就是判据。
        //   置空引用即可：下次写引擎/网页日志时惰性重开，落到新建的文件上。
        runCatching {
            lock.withLock {
                appWriter?.close()
                appWriter = null
                openApp(append = false)

                engineWriter?.close()
                engineWriter = null
                engineBytes = 0L

                webWriter?.close()
                webWriter = null
                webBytes = 0L
            }
        }
        // ⚠️ `events.jsonl` 也在这份删除清单里，所以 EventLog 的 writer 同样必须重开 ——
        //   它与 engine/web 是同一个失效模式（引用非空即写旧 writer），只是没有惰性
        //   创建、需要显式重开。见 [EventLog.reopen]。
        runCatching { EventLog.reopen(logDir) }
        app('I', "Log", "日志已由用户清空（删除 $n 个文件）")
        return n
    }

    // ── 落盘 ──────────────────────────────────────────────────────────────────

    private fun openApp(append: Boolean) {
        val d = dir ?: return
        val f = File(d, APP_LOG)
        appWriter = BufferedWriter(OutputStreamWriter(FileOutputStream(f, append), Charsets.UTF_8))
        appBytes = if (append) f.length() else 0L
    }

    /**
     * 同步追加一行到 `app.log`。
     *
     * ⚠️ `flush()` **每行都调**：这就是「崩溃时日志完整」的代价与保障。
     * 不 flush 的话最后几行留在用户态缓冲里，进程一死就没了 —— 而那几行
     * 恰恰是崩溃前发生的事。
     */
    private fun writeApp(line: String) {
        val w = appWriter ?: return
        try {
            lock.withLock {
                w.write(line)
                w.write("\n")
                w.flush()
                appBytes += line.length + 1
                if (appBytes >= MAX_FILE_BYTES) rotateApp()
            }
        } catch (t: Throwable) {
            noteFailure("app.log")
        }
    }

    private fun rotateApp() {
        try {
            appWriter?.close()
        } catch (_: Throwable) {
        }
        appWriter = null
        val d = dir ?: return
        rotateFiles(File(d, APP_LOG))
        openApp(append = false)
    }

    /** 通用的「当前 → .1」轮转。调用方已关闭 writer。 */
    private fun rotateFiles(cur: File) {
        for (gen in KEEP_GENERATIONS downTo 1) {
            val src = if (gen == 1) cur else File(cur.parentFile, "${cur.name}.${gen - 1}")
            val dst = File(cur.parentFile, "${cur.name}.$gen")
            if (src.exists()) {
                if (dst.exists()) dst.delete()
                src.renameTo(dst)
            }
        }
    }

    /**
     * 写日志失败计数。连续 3 次就彻底关掉落盘。
     *
     * ⚠️ 为什么是「关掉」而不是「继续重试」：磁盘满 / 只读时每条日志都会抛一次
     * 异常，那会**拖慢整个回合**（每条日志一次 IOException + 栈展开）。而日志
     * 本身是辅助功能，不该拖垮主流程。
     */
    private fun noteFailure(what: String) {
        writeFailures++
        if (writeFailures >= 3) {
            try {
                appWriter?.close()
            } catch (_: Throwable) {
            }
            appWriter = null
            AndroidLog.e(TAG, "LogStore: $what 连续写入失败 $writeFailures 次，已关掉落盘")
        }
    }

    /**
     * 投进异步队列。
     *
     * ⚠️ **队列满时丢弃并计数，绝不阻塞调用方。** 调用方是引擎的 stdout 读取线程
     * （见 `EngineManager` 里那段「不能用 use{} 读完 URL 就关流」的注释）：阻塞它
     * 会让引擎的 stdout 管道（64KB）写满，引擎**卡死在 write 上**。丢日志远好过
     * 卡引擎 —— 这是本方法最要紧的一条。
     */
    private fun enqueue(target: QueuedLine.Target, line: String) {
        if (!queue.offer(QueuedLine(target, line))) {
            droppedAsync++
        }
    }

    /** 因队列满而丢弃的异步日志条数（写进诊断包）。 */
    @Volatile
    var droppedAsync = 0L
        private set

    // ── 异步工作线程（engine / web） ──────────────────────────────────────────

    private class QueuedLine(val target: Target, val line: String) {
        enum class Target { ENGINE, WEB }
    }

    /**
     * 异步写入线程。
     *
     * ⚠️ 队列满时**丢弃并计数**，不阻塞调用方：调用方是引擎的输出读取线程，
     * 阻塞它会让引擎的 stdout 管道写满、进而卡死整个引擎（见 `EngineManager`
     * 里那段关于 64KB 管道缓冲的注释）。丢日志远好过卡引擎。
     */
    private fun startWorker() {
        if (worker != null) return
        val t = Thread({
            while (true) {
                try {
                    val item = queue.poll(1, TimeUnit.SECONDS) ?: continue
                    when (item.target) {
                        QueuedLine.Target.ENGINE -> writeAsync(engineWriterOf(), item.line, { n -> engineBytes = n }, { engineBytes })
                        QueuedLine.Target.WEB -> writeAsync(webWriterOf(), item.line, { n -> webBytes = n }, { webBytes })
                    }
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (_: Throwable) {
                    // 铁律 ①：工作线程绝不能因为写日志失败而死掉，否则此后所有
                    // 引擎日志都静默丢失，症状是「日志看着正常，但引擎那部分没了」。
                }
            }
        }, "dsh-log-writer").apply { isDaemon = true }
        worker = t
        t.start()
    }

    /** 惰性开文件 —— 引擎/网页日志第一次真的出现时才建，避免空文件。 */
    private fun engineWriterOf(): BufferedWriter? {
        engineWriter?.let { return it }
        lock.withLock {
            engineWriter?.let { return it }
            val d = dir ?: return null
            val f = File(d, ENGINE_LOG)
            engineWriter = BufferedWriter(OutputStreamWriter(FileOutputStream(f, true), Charsets.UTF_8))
            engineBytes = f.length()
            return engineWriter
        }
    }

    private fun webWriterOf(): BufferedWriter? {
        webWriter?.let { return it }
        lock.withLock {
            webWriter?.let { return it }
            val d = dir ?: return null
            val f = File(d, WEB_LOG)
            webWriter = BufferedWriter(OutputStreamWriter(FileOutputStream(f, true), Charsets.UTF_8))
            webBytes = f.length()
            return webWriter
        }
    }

    private fun writeAsync(
        w: BufferedWriter?,
        line: String,
        setBytes: (Long) -> Unit,
        getBytes: () -> Long,
    ) {
        if (w == null) return
        try {
            lock.withLock {
                w.write(line)
                w.write("\n")
                // 异步档**攒着不 flush**：靠 BufferedWriter 自己的缓冲 + 下面的
                // 阈值触发。这正是它与 app.log 的区别所在。
                val next = getBytes() + line.length + 1
                setBytes(next)
                if (next >= MAX_FILE_BYTES) {
                    w.flush()
                }
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * 把异步缓冲刷下去。**崩溃路径必须调用它**（见 [CrashRecorder]）——
     * 否则引擎日志的最后几行永远留在用户态缓冲里。
     *
     * ⚠️ 光 flush writer 是**不够**的：`enqueue()` 只是把行放进 `queue`，由
     * `dsh-log-writer` 线程慢慢取。调用它的线程与那个 worker 之间没有任何同步，
     * 所以此刻队列里可能还压着一批**根本没写进 writer** 的行 —— 它们不在
     * BufferedWriter 里，flush 自然刷不出来。
     *
     * 这里先自己把队列排干（`writeAsync` 内部有 `lock`，与 worker 并发是安全的），
     * 再 flush。排干的是「本方法被调用这一刻已经入队的行」，之后新入队的行照旧
     * 走 worker —— 打包这种一次性动作要的正是「此刻为止的完整快照」。
     */
    fun flushAsync() {
        try {
            lock.withLock {
                // ① 排干队列：直接自己写，不等 worker 调度。
                while (true) {
                    val item = queue.poll() ?: break
                    when (item.target) {
                        QueuedLine.Target.ENGINE ->
                            writeAsync(engineWriterOf(), item.line, { n -> engineBytes = n }, { engineBytes })
                        QueuedLine.Target.WEB ->
                            writeAsync(webWriterOf(), item.line, { n -> webBytes = n }, { webBytes })
                    }
                }
                // ② 再 flush 已经攒在 BufferedWriter 里的。
                engineWriter?.flush()
                webWriter?.flush()
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * 关掉全部写入并落盘。崩溃处理器的最后一步。
     *
     * ⚠️ 只 flush + close，**不删任何东西**。
     */
    fun flushAndClose() {
        try {
            lock.withLock {
                appWriter?.flush()
                appWriter?.close()
                engineWriter?.flush()
                engineWriter?.close()
                webWriter?.flush()
                webWriter?.close()
            }
        } catch (_: Throwable) {
        }
        appWriter = null
        engineWriter = null
        webWriter = null
    }

    // ── 崩溃路径专用的加锁入口 ────────────────────────────────────────────────

    /**
     * 日志锁 —— **本文件里所有写入路径共用的一把**。
     *
     * ⚠️ 用 `ReentrantLock` 而不是 `synchronized(this)`，唯一理由是崩溃路径需要
     * `tryLock`：Kotlin 的 `synchronized` 块**不可超时**，而崩溃路径上硬等锁会
     * 死锁（见 [CrashRecorder] 类注释约束①）。同一把锁必须被**所有**写入路径
     * 使用（`lock.withLock { }`），否则崩溃路径与正常路径会互相穿透 ——
     * 那样「拿不到锁」这个判据就是假的，现场文件会与 `app.log` 的写入打架。
     *
     * ⚠️ 读取路径（[entries] / [tail]）用的是**另一把**锁（`ring` 的监视器），
     * 这是刻意的：环形缓冲的读写与文件写入互不相干，共用一把会让「读日志」
     * 阻塞「写日志」—— 而日志界面恰恰是在出问题时打开的，那时写入最频繁。
     */
    private val lock = java.util.concurrent.locks.ReentrantLock()

    /**
     * 在 [timeoutMs] 内拿到日志锁则执行 [block]，否则**跳过并返回 false**。
     *
     * ⚠️ 返回 false 是**正常情况**，不是错误：崩溃恰好发生在持有日志锁的线程上
     * 时必然拿不到。调用方（[CrashRecorder]）对此已有设计 —— 现场文件那条路径
     * 不碰这把锁，所以信息不会丢。
     *
     * ⚠️ 不要在正常路径用这个方法（会引入不必要的超时语义）；正常路径直接
     *    `lock.withLock { }`。
     */
    fun withLockOrSkip(timeoutMs: Long, block: () -> Unit): Boolean {
        var acquired = false
        return try {
            acquired = lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)
            if (acquired) {
                block()
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        } finally {
            if (acquired) {
                runCatching { lock.unlock() }
            }
        }
    }

    // ── 限流 ──────────────────────────────────────────────────────────────────

    /**
     * 同类日志限流。返回 true 表示**这条应当被丢弃**。
     *
     * 判据是 `level + tag + 正文`完全相同（不是只比 tag）：同一个 tag 下正常会
     * 打很多不同的话，只比 tag 会把正常日志也限掉。
     *
     * ⚠️ 被丢弃的条数会累积到 [throttledTotal] 并写进诊断包 —— 分析者必须知道
     * 「这份日志被抽过稀」，否则会误判「这件事只发生了一次」。
     */
    private fun throttled(level: Char, tag: String, msg: String, now: Long): Boolean {
        val key = "$level|$tag|$msg"
        lock.withLock {
            if (key != lastThrottleKey || now - lastThrottleAt > THROTTLE_WINDOW_MS) {
                // 上一轮若发生过抑制，补一条汇总 —— 否则日志里会凭空少一截
                if (throttleCount > 0) {
                    val note = "… 上一条同类日志在 ${THROTTLE_WINDOW_MS}ms 内被抑制 $throttleCount 次"
                    throttledTotal += throttleCount
                    throttleCount = 0
                    writeAppThrottleNote(note)
                }
                lastThrottleKey = key
                lastThrottleAt = now
                return false
            }
            throttleCount++
            return throttleCount > THROTTLE_MAX_REPEAT
        }
    }

    private fun writeAppThrottleNote(note: String) {
        try {
            writeApp(
                format(
                    System.currentTimeMillis(), 'W', "Log", note, seq.incrementAndGet(),
                    Thread.currentThread().name,
                ),
            )
        } catch (_: Throwable) {
        }
    }

    // ── 配额 ──────────────────────────────────────────────────────────────────

    /**
     * 执行总配额与崩溃现场配额。**启动时调一次**即可 —— 运行期靠单文件轮转兜住，
     * 不必每条日志都 stat 整个目录（那才是真的拖慢）。
     */
    fun enforceQuota() {
        val d = dir ?: return
        try {
            enforceDirQuota(d, TOTAL_QUOTA_BYTES)
            crashDirOrNull()?.let { enforceCrashQuota(it) }
        } catch (_: Throwable) {
        }
    }

    /**
     * 顶层日志文件超配额就删最老的，直到降回配额以内。
     *
     * ⚠️ **只管顶层文件，不管任何子目录。** `candidates` 被 `it.isFile` 滤过，
     *   而 `crash/`、`pending/`、`export/`、`failed/` 都是目录 —— 于是它们
     *   **一个都不在清理范围内**。这正好是我们想要的结果（删了 `pending/` 就永远
     *   传不出去，删了 `export/` 等于骗用户），但它是 `it.isFile` 的**副产品**，
     *   不是靠某个白名单实现的。
     *
     * ⚠️ 原来的签名带一个 `skipDirs: Set<String>` 参数，注释写「`skipDirs` 里的
     *   子目录整个跳过」—— 那是**虚构的保证**：该参数从头到尾没参与过任何判断，
     *   唯一的用处是一句 `skipDirs.forEach { }`（空操作）。读者会以为「不在
     *   `skipDirs` 里的子目录会被清理」，从而以为 `failed/` 有配额兜底 ——
     *   实际上它和别的子目录一样完全不受管，上传持续失败时会一直涨。
     *   **已删掉这个死参数**，如实写在这里。
     *
     * ⚠️ 崩溃现场另有 [enforceCrashQuota] 单独把关（8MB / 20 份）；
     *   `pending/` 靠「上传成功即出队」收敛；**`failed/` 目前没有任何上限**。
     */
    private fun enforceDirQuota(d: File, quota: Long) {
        val files = d.listFiles() ?: return
        val candidates = files.filter { it.isFile }.sortedBy { it.lastModified() }
        var total = candidates.sumOf { it.length() }
        if (total <= quota) return
        for (f in candidates) {
            if (total <= quota) break
            // 内存里正在写的那两个文件不能删 —— 删了 writer 会写到已 unlink 的
            // inode 上，日志从此静默消失（文件系统里看不到，但也不报错）。
            if (f.name == APP_LOG || f.name == ENGINE_LOG || f.name == WEB_LOG) continue
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    /**
     * 崩溃现场配额：超过 [CRASH_MAX_FILES] 个或 [CRASH_QUOTA_BYTES] 字节时删最老的。
     *
     * ⚠️ 保留**最近**的而不是最早的：一次崩溃循环会产出几十个文件，最新那几个
     * 才是当前问题的现场。
     */
    private fun enforceCrashQuota(crashDir: File) {
        val files = crashDir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        var count = files.size
        for (f in files) {
            if (total <= CRASH_QUOTA_BYTES && count <= CRASH_MAX_FILES) break
            val len = f.length()
            if (f.delete()) {
                total -= len
                count--
            }
        }
    }

    // ── 脱敏 ──────────────────────────────────────────────────────────────────

    /**
     * 脱敏包装。**所有写入路径都必须过它**（[app] / [engine] / [web] 各自都调了）。
     *
     * ⚠️ 异常时返回原文而不是抛异常：脱敏正则理论上不会失败，但真失败了也
     * 不能让日志把自己搞崩（铁律 ①）。
     */
    private fun safeRedact(s: String): String = try {
        Redactor.redact(s)
    } catch (_: Throwable) {
        s
    }

    // ── 数据结构 ──────────────────────────────────────────────────────────────

    /**
     * 一条日志。`seq` 是进程内序号，用于跨文件对齐与排序。
     *
     * ⚠️ **`thread` 必须在写入时定格，不能在读取时取**（真机踩过，伪造了证据）。
     *   在 `214`/`215` 版之前本类没有这个字段，`tail()` 重新格式化时调的是
     *   `Thread.currentThread().name` —— 于是崩溃现场里那 200 行的线程名全是
     *   **读取者**（`dsh-log-watchdog`），而不是**写入者**。
     *
     *   铁证（同一时间戳、同一 `#1`，两份文件对比）：
     *   - `app.log`（写入时定格）：`... [W] [Boot] #1 main 上次启动未正常结束…`
     *   - 现场文件（读取时取）：`... [W] [Boot] #1 dsh-log-watchdog 上次启动未正常结束…`
     *
     *   后果很具体：崩溃现场的第 3 段是**信息量最大**的一段（「崩之前最后做的那件事」），
     *   而线程名正是判断「谁卡住了」的第一依据。把它写成读取者，等于让分析者
     *   顺着一个**从头到尾没参与过那些日志**的线程去查。
     */
    data class Entry(
        val t: Long,
        val level: Char,
        val tag: String,
        val msg: String,
        val seq: Long,
        val thread: String,
    )

    // ── 文件名（诊断包与工具都按这些名字找） ──────────────────────────────────

    const val APP_LOG = "app.log"
    const val ENGINE_LOG = "engine.log"
    const val WEB_LOG = "web.log"

    private const val TAG = "DSH/LogStore"
}
