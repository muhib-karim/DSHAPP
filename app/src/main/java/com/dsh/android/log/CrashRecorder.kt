package com.dsh.android.log

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log as AndroidLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 崩溃现场捕获 —— 四类「进程非正常消失」各留一份带上下文的现场。
 *
 * ## 为什么必须自己抓
 *
 * 本 App 的进程**很容易非正常消失**：内置引擎吃内存（node + 一个 WebView/GV 内核），
 * 国产 ROM 又会主动冻结/杀后台。而用户报上来的永远是同一句话：「它自己退了」。
 * 没有现场文件，那句话之外什么都查不了。
 *
 * ## 四类分别抓，因为「谁死了」决定了「怎么查」
 *
 * | 类型 | 捕获点 | 现场文件 |
 * |---|---|---|
 * | 未捕获异常 | `Thread.setDefaultUncaughtExceptionHandler` | `<ts>-uncaught.txt` |
 * | 渲染进程崩溃 | `WebHostCallbacks.onRendererGone` | `<ts>-renderer.txt` |
 * | 引擎进程退出 | `EngineManager` 的 stdout EOF（已有检测点） | `<ts>-engine-exit.txt` |
 * | 主线程卡死 | 自建 watchdog（见下） | `<ts>-anr.txt` |
 *
 * ⚠️ **四类不能合并成一种**：WebView 渲染进程崩溃时**本进程还活着**，能继续跑、
 * 能弹提示、能重载（`MainActivity` 已有这套恢复逻辑）。把它记成「App 崩溃」会
 * 让分析者去查一个根本不存在的进程崩溃。
 *
 * ## 每份现场固定包含四段（缺一段都会让排查卡住）
 *
 * 1. **元信息** —— 时间、类型、`bootId`、设备、App 版本、线程名
 * 2. **异常/原因** —— 有堆栈就写堆栈
 * 3. **最后 [LogStore.CRASH_TAIL_LINES] 行日志** —— ⚠️ 这是**最关键的一段**：
 *    文件日志可能因异步缓冲丢了最后几条，而内存环形缓冲正好补上。真机上
 *    「崩之前最后做的那件事」永远比堆栈本身更有信息量
 * 4. **正在跑的阶段** —— 从 [BootState] / [EventLog] 取，回答「崩在哪一步」
 *
 * ## 三条必须守住的实现约束
 *
 * ① **`tryLock` 而不是 `lock`。** 崩溃可能发生在**正持有日志锁**的线程上
 *    （比如就在 `LogStore.writeApp` 里抛了异常）。硬等锁会死锁 —— 而崩溃处理器
 *    死锁的后果是把「闪退」变成「永久卡死」，**比崩溃更糟**。拿不到锁就只写
 *    现场文件（那条路径不碰 `app.log` 的锁）。
 * ② **写现场用独立文件、独立句柄。** 不复用 `app.log` 的 writer —— 它可能正被
 *    死掉的线程持有。
 * ③ **处理完必须把异常交回默认处理器。** 不交回的话进程会「吞掉」崩溃并卡在
 *    半死状态（ANR 而不是退出），用户体验从「闪退」变成「卡住」——更糟。
 */
object CrashRecorder {

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** 文件名时间戳。不能含冒号（文件系统不允许）。 */
    private val fileFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /**
     * 拿日志锁的最长等待。见类注释约束①。
     *
     * 200ms 的取值依据：写一条日志是微秒级，200ms 足够任何正常持有的锁释放；
     * 而崩溃路径总共只有几秒预算（之后进程就没了），不能把预算耗在等锁上。
     */
    private const val LOCK_WAIT_MS = 200L

    /** 主线程 watchdog 的 ping 间隔与判定阈值。 */
    private const val WATCHDOG_PING_MS = 5_000L
    private const val WATCHDOG_STALL_MS = 15_000L

    /**
     * 「主线程卡死」与「整个进程被冻结」的分界。
     *
     * ⚠️ **这不只是措辞问题，是本判据能不能信的问题**（真机 44 份现场实测过）。
     *
     * ## 为什么必须有这条线
     *
     * watchdog 是**每 [WATCHDOG_PING_MS] 醒一次**再看 `lastPing` 的。所以：
     *
     * - 若真的是**主线程**卡住、watchdog 线程照常跑 ⇒ `gap` 只可能落在
     *   `(WATCHDOG_STALL_MS, WATCHDOG_STALL_MS + WATCHDOG_PING_MS]`
     *   = **15～20 秒**这个窗口里。它必然 >15 秒才发现，又必然 ≤20 秒，
     *   因为下一次醒来（+5 秒）就会记录。**这是硬上界，不是经验值。**
     * - 若 `gap` **远超** 20 秒 ⇒ 连 watchdog 线程自己都没在跑 ⇒ 那是**整个进程
     *   被系统冻住**（后台/锁屏被冻结、被 swap 出去），主线程根本没「卡」。
     *
     * 实测 44 份现场里 36 份的 `gap` 是 60 秒～888 秒（最大 14.8 分钟），
     * 而每一份的窗口内**整个进程一条日志都没有** —— 与「整进程冻结」完全吻合，
     * 与「主线程卡死」完全矛盾（真卡死时 watchdog 线程还会继续写 `app.log`）。
     *
     * 判错的代价是双向的：把冻结记成卡死 ⇒ 现场里一半是假警报，真问题被淹掉；
     * 而按「冻结」记下来正好解释了用户口中的「它自己卡住了」。
     */
    private const val WATCHDOG_FREEZE_MS = WATCHDOG_STALL_MS + WATCHDOG_PING_MS * 2

    @Volatile
    private var crashDir: File? = null

    /** 未捕获异常处理器是否已装（幂等判据）。 */
    @Volatile
    private var handlerInstalled = false

    /** watchdog 是否已启动（幂等判据）。 */
    @Volatile
    private var watchdogStarted = false

    /** 主线程最后一次回 ping 的时间。由主线程更新，watchdog 读。 */
    @Volatile
    private var lastPing = 0L

    /** 本次启动是否已经写过 ANR 现场 —— 卡死是持续状态，不要每 15 秒写一个文件。 */
    @Volatile
    private var anrRecordedThisBoot = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private val pingRunnable = Runnable { lastPing = System.currentTimeMillis() }

    // ── 安装 ──────────────────────────────────────────────────────────────────

    /**
     * 装上未捕获异常处理器与主线程 watchdog。
     *
     * ⚠️ 必须在 `attachBaseContext()` 里调用，**越早越好** —— 晚于第一次崩溃
     * 就等于没装。
     */
    fun install(logDir: File) {
        crashDir = File(logDir, "crash").apply { runCatching { mkdirs() } }
        installUncaughtHandler()
        startWatchdog()
    }

    private fun installUncaughtHandler() {
        if (handlerInstalled) return
        handlerInstalled = true
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, err ->
            try {
                val stack = AndroidLog.getStackTraceString(err)
                // ① 先落盘（同步）—— 顺序很重要：先写现场文件，再写 app.log。
                //    因为 app.log 那条可能因为拿不到锁而跳过，现场文件是主力。
                writeScene(
                    kind = "uncaught-exception",
                    reason = "${err.javaClass.name}: ${err.message}",
                    stack = stack,
                    threadName = thread.name,
                )
                // ② 再把堆栈喂给 app.log（拿得到锁才有）。这条让「崩溃」出现在
                //    正常的日志流里，用户按时间顺序翻日志时不会漏掉它。
                withLogLock {
                    LogStore.app('E', "Crash", "未捕获异常 on ${thread.name}: ${err.javaClass.name}: ${err.message}")
                    LogStore.app('E', "Crash", stack)
                }
                // ③ 刷盘。异步缓冲里还压着引擎/网页的最后几行。
                LogStore.flushAsync()
                EventLog.flushAndClose()
                LogStore.flushAndClose()
            } catch (_: Throwable) {
                // 崩溃处理器自己再崩就彻底没救了 —— 静默，交给默认处理器。
            }
            // 约束③：必须交回，否则从「闪退」变「卡住」。
            prev?.uncaughtException(thread, err)
        }
    }

    // ── 四类现场的写入入口 ────────────────────────────────────────────────────

    /**
     * 记录**渲染进程崩溃**（WebView 的 `onRenderProcessGone` / GeckoView 的 `onCrash`）。
     *
     * ⚠️ **本进程还活着**，所以这不是「App 崩溃」。区分 `didCrash` 很重要：
     * `false` 表示被系统回收（低内存时的正常行为），不该当成异常。
     */
    fun recordRendererGone(didCrash: Boolean, detail: String) {
        writeScene(
            kind = if (didCrash) "renderer-crash" else "renderer-reclaimed",
            reason = detail,
            stack = null,
            threadName = "ui",
            level = if (didCrash) 'E' else 'W',
        )
    }

    /** 记录**引擎进程退出**。`expected` = 是不是我们主动停的。 */
    fun recordEngineExit(exitCode: Int?, expected: Boolean, detail: String) {
        writeScene(
            kind = if (expected) "engine-exit-expected" else "engine-exit",
            reason = "exitCode=$exitCode $detail",
            stack = null,
            threadName = "engine-reader",
            level = if (expected) 'I' else 'W',
        )
    }

    /**
     * 记录**主线程卡死**（ANR 形态）。
     *
     * ⚠️ 拿不到系统 ANR 的完整堆栈（`ApplicationExitInfo` 需 API 30，本工程
     * `targetSdk = 28`），所以这里记的是「主线程超过 N 秒没响应」这个**事实** +
     * 内存里的最后若干行日志。不要伪造成「系统 ANR 堆栈」—— 那是编造证据。
     *
     * ⚠️ **超过 [WATCHDOG_FREEZE_MS] 的不要走这里**，那是整进程被冻结，
     * 见 [recordProcessFreeze] 的注释。分错类会让「真卡死」被假警报淹掉。
     */
    fun recordMainThreadStall(stalledMs: Long) {
        if (stalledMs > WATCHDOG_FREEZE_MS) {
            recordProcessFreeze(stalledMs)
            return
        }
        writeScene(
            kind = "main-thread-stall",
            reason = "主线程超过 ${stalledMs}ms 未响应（自建 watchdog 判定，非系统 ANR）",
            stack = null,
            threadName = "main",
            level = 'E',
        )
    }

    /**
     * 记录**整个进程被系统冻结**（不是主线程卡死）。
     *
     * 判据见 [WATCHDOG_FREEZE_MS]：`gap` 超过 watchdog 心跳能解释的上界，
     * 就只能是 watchdog 线程自己也被挂起了 —— 即进程整体失去 CPU，
     * 而不是主线程在某个同步调用里转不出来。
     *
     * ⚠️ **等级记 `W` 而不是 `E`**，且**不算硬崩溃**（见 [isHardCrash]）：
     * 被冻结是 Android 对后台进程的**正常治理**，不是我们的 bug。把它标红会让
     * 用户看到一片「崩溃」，而真崩溃反而找不到。
     *
     * ⚠️ 但仍要记下来，因为它**解释了用户抱怨的那个现象**（「它自己卡住了、
     * 回来就白屏」）。不记的话这类投诉在日志里完全无迹可寻。
     */
    fun recordProcessFreeze(frozenMs: Long) {
        writeScene(
            kind = "process-freeze",
            reason = "进程被系统冻结约 ${frozenMs}ms（watchdog 线程自身也停跑了，故非主线程卡死；" +
                "常见于后台被冻结或整机休眠）",
            stack = null,
            threadName = "dsh-log-watchdog",
            level = 'W',
        )
        // 也进时间线：现场文件按时间戳单独命名，翻时间线时看不到「这里冻过一次」，
        // 而冻结正是「切回来白屏/要重进」这类现象的直接解释。
        runCatching { EventLog.record("process.freeze", mapOf("ms" to frozenMs.toString())) }
    }

    /**
     * 通用入口：记一次「本进程之外的问题」。
     *
     * 给 `EngineManager` 的启动前体检、资产解包失败等**不至于崩溃但需要现场**的
     * 场景用。写出来的文件与崩溃现场同构，分析方式一致。
     */
    fun recordAnomaly(kind: String, reason: String, level: Char = 'W') {
        writeScene(kind = kind, reason = reason, stack = null, threadName = Thread.currentThread().name, level = level)
    }

    // ── 现场文件的写法 ────────────────────────────────────────────────────────

    /**
     * 写一份现场文件。**这是本类唯一的写入口**，四类都走它，格式因此完全一致。
     *
     * ⚠️ 全程 `runCatching`：写现场失败也绝不能抛 —— 调用方可能正在崩溃路径上。
     */
    private fun writeScene(
        kind: String,
        reason: String,
        stack: String?,
        threadName: String,
        level: Char = 'E',
    ) {
        runCatching {
            val dir = crashDir ?: return@runCatching
            if (!dir.exists()) dir.mkdirs()
            val now = System.currentTimeMillis()
            val name = "${fileFmt.format(Date(now))}-$kind.txt"
            val f = File(dir, name)

            val sb = StringBuilder()
            sb.append("time: ").append(fmt.format(Date(now))).append('\n')
            sb.append("kind: ").append(kind).append('\n')
            sb.append("bootId: ").append(BootState.bootId).append('\n')
            sb.append("uptimeMs: ").append(runCatching { BootState.uptimeMs() }.getOrDefault(0L)).append('\n')
            sb.append("device: ").append(VersionInfo.deviceSummary()).append('\n')
            sb.append("app: ").append(VersionInfo.summary()).append('\n')
            sb.append("thread: ").append(threadName).append('\n')
            sb.append("reason: ").append(reason).append('\n')

            if (stack != null) {
                sb.append("\n--- stack trace ---\n").append(stack).append('\n')
            }

            // ⚠️ 最后 200 行：这是整个现场里信息量最大的一段。
            sb.append("\n--- last ${LogStore.CRASH_TAIL_LINES} log lines ---\n")
            runCatching {
                LogStore.tail(LogStore.CRASH_TAIL_LINES).forEach { sb.append(it).append('\n') }
            }.onFailure { sb.append("(内存日志不可读: ${it.message})\n") }

            // 时间线尾部：回答「崩在哪一步」
            sb.append("\n--- recent events ---\n")
            runCatching {
                val d = LogStore.dirOrNull()
                if (d != null) {
                    EventLog.readAll(d).takeLast(20).forEach { sb.append(EventLog.describe(it)).append('\n') }
                }
            }.onFailure { sb.append("(事件时间线不可读: ${it.message})\n") }

            f.writeText(sb.toString())

            // 现场文件写完后，在 app.log 里留一条索引（拿得到锁才有）。
            // ⚠️ 让用户在日志界面顺着时间翻也能看到「这里发生过什么」。
            withLogLock {
                LogStore.app(level, "Crash", "已记录现场: crash/$name —— $reason")
            }
        }
    }

    /**
     * 在**有限时间内**拿到日志锁再执行 [block]；拿不到就跳过。
     *
     * 见类注释约束①。这里用 `synchronized` + 超时做不到（Kotlin 的 `synchronized`
     * 不可超时），所以借 `ReentrantLock.tryLock`。
     *
     * ⚠️ 锁对象必须与 `LogStore` 用的是**同一个** —— 所以 `LogStore` 暴露
     * [LogStore.withLockOrSkip]。本方法只是它的语义化封装，便于崩溃路径读起来
     * 意图明确。
     */
    private fun withLogLock(block: () -> Unit) {
        runCatching { LogStore.withLockOrSkip(LOCK_WAIT_MS, block) }
    }

    // ── 主线程 watchdog ───────────────────────────────────────────────────────

    /**
     * 主线程 watchdog。
     *
     * ## 为什么需要（本项目特有）
     *
     * 本 App 的主线程要干不少同步活：WebView 的 `evaluateJavascript` 回包、
     * 引擎状态回调、`FloatingBubble` 的窗口操作、SharedPreferences 读写。
     * 任何一处卡住，界面就整个冻住 —— 而用户描述成「卡死不动」时，我们手上
     * 什么都没有（logcat 里没有、文件里也没有）。
     *
     * ## 判定方式与它的诚实边界
     *
     * 每 [WATCHDOG_PING_MS] 让主线程回一个 ping；超过 [WATCHDOG_STALL_MS] 没回
     * 就判定卡死并写现场。
     *
     * ⚠️ **这是「主线程没响应」，不是系统的 ANR**。系统的 ANR 有完整堆栈
     * （在 `/data/anr/traces.txt`，需要 root 或 `READ_LOGS`），本工程 `targetSdk=28`
     * 拿不到。所以现场里如实写成「自建 watchdog 判定，非系统 ANR」——
     * **不伪造证据**。分析者据此知道「这里有个卡死，但堆栈要另想办法」。
     *
     * ⚠️ 一次启动只记一次：卡死是**持续状态**，不记的话每 15 秒产出一个文件，
     * 一分钟就把 `crash/` 配额撑满、把真正的崩溃现场挤掉。
     */
    private fun startWatchdog() {
        if (watchdogStarted) return
        watchdogStarted = true
        lastPing = System.currentTimeMillis()

        val t = Thread({
            while (true) {
                try {
                    Thread.sleep(WATCHDOG_PING_MS)
                    // 让主线程回 ping。post 成功不代表它跑了 —— 那正是我们要测的。
                    mainHandler.post(pingRunnable)
                    val gap = System.currentTimeMillis() - lastPing
                    if (gap > WATCHDOG_STALL_MS && !anrRecordedThisBoot) {
                        anrRecordedThisBoot = true
                        recordMainThreadStall(gap)
                    }
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (_: Throwable) {
                    // watchdog 自己绝不能死 —— 死了就再也发现不了卡死。
                }
            }
        }, "dsh-log-watchdog").apply { isDaemon = true }
        t.start()
    }

    /**
     * 等主线程回一次 ping。**测试与自检用**，业务路径不调。
     *
     * 返回 true = 主线程在 [timeoutMs] 内响应了。
     */
    fun pingMainThread(timeoutMs: Long): Boolean {
        val latch = CountDownLatch(1)
        mainHandler.post { latch.countDown() }
        return try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            false
        }
    }

    // ── 读取 ──────────────────────────────────────────────────────────────────

    /**
     * 列出全部崩溃现场，**新的在前**。
     *
     * 给日志界面与诊断包用。返回的是文件对象，调用方自己读内容 ——
     * 一次崩溃现场可能上百 KB，全读进内存没必要。
     */
    fun listScenes(): List<File> {
        val dir = crashDir ?: return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /** 崩溃现场数量（界面摘要用）。 */
    fun sceneCount(): Int = listScenes().size

    /**
     * 判断某个现场文件是不是「真崩溃」（有堆栈的那两类）。
     *
     * 界面据此决定标红还是标黄 —— 渲染进程被回收、引擎正常退出都不该是红色。
     *
     * ⚠️ `process-freeze` **刻意不在此列**：进程被系统冻结是后台治理的正常结果，
     * 不是本 App 的崩溃。它由 watchdog 记下来是为了解释「回来就白屏」这类现象，
     * 但标红会把真崩溃的比例冲淡（实测 44 份里 36 份是冻结，标红后满屏红）。
     */
    fun isHardCrash(f: File): Boolean =
        f.name.contains("uncaught-exception") || f.name.contains("renderer-crash") ||
            f.name.contains("main-thread-stall")
}
