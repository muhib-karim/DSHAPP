package com.dsh.android.log

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 诊断包 —— **本日志系统的最终交付物**。
 *
 * ## 为什么不是「把日志文件打包发过来」
 *
 * 裸日志的问题不是「不够全」而是「定位不了」：几万行里混着正常输出、引擎的
 * 逐 token 输出、网页 console，而决定性的事实（版本戳对不对、插件激活了没有、
 * 上次为什么退出）**没有任何一处集中说明**。分析者要自己拼，用户也不懂该提供什么。
 *
 * 所以交付物是**带结论的包**：日志 + 环境快照 + 崩溃现场 + **`diagnosis.json`
 * （自动跑的检查项与结论）**。目标是「拿到包就知道问题在哪」，而不是「拿到包
 * 才开始查」。
 *
 * ## 包内结构（分析者只需看前三个）
 *
 * ```
 * manifest.json       包元信息 + 覆盖范围（日志从哪到哪、有没有被截断）
 * diagnosis.json      ★ 自动结论：逐项检查 + 汇总
 * environment.json    设备 / 系统 / App / 内存 / 磁盘 / 权限
 * timeline.jsonl      事件时间线（回答「卡在哪一步、耗时多少」）
 * app.log[.1]         应用日志（476 处 Log.*）
 * engine.log          引擎 stdout+stderr
 * web.log             网页 console
 * crashes/            崩溃现场（堆栈 + 最后 200 行日志）
 * files.json          关键文件的存在性/大小（回答「资产解包了没有」）
 * ```
 *
 * ⚠️ 上面那段里**绝不能出现裸露的注释起始符号**（例如把「crashes/」与
 * 「星号.txt」直接连写）—— Kotlin 的块注释会**嵌套**，多出的那一个会把 KDoc
 * 提前闭合、吞掉文件后半段，症状是一连串莫名其妙的 `Unresolved reference`
 * 加 `Unclosed comment`。这是 AGENTS.md 里记过的陷阱，本项目已经踩过两次
 * （第二次就是这段说明自己写的）。
 *
 * ## 体积上限与「诚实截断」
 *
 * 上限 8 MB。超了就**从最不重要的开始丢**（web.log → 旧代日志 → app.log 头部），
 * 并且在 `manifest.completeness` 里**如实标注丢了什么**。
 *
 * ⚠️ 这一条不能省：一个被截断的包如果不说明截断，分析者会得出「日志里没有
 * 这条错误，所以没发生过」的**错误结论**。宁可让包难看，也不能让它骗人。
 */
object DiagnosticBundle {

    /** 诊断包体积上限。超过就按优先级丢内容，见 [build]。 */
    private const val MAX_BUNDLE_BYTES = 8L * 1024L * 1024L

    /**
     * 最多收几份引擎启动诊断（`startup-*.log`）。
     *
     * 引擎每次启动失败写一份，攒久了上百份；最近 5 份足以覆盖「最近为什么起不来」。
     */
    private const val MAX_STARTUP_LOGS = 5

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /**
     * 生成诊断包。
     *
     * @param logDir 日志目录（`filesDir/logs`）。
     * @param outDir 输出目录（`logs/export` 或 `logs/pending`）。
     * @param reason 为什么生成：`crash` / `manual` / `startup-retry`。会写进 manifest，
     *   服务器据此分流。
     * @param checks 额外的检查项（由 `EngineManager` 提供，它知道资产/补丁的状态）。
     *   本类自己也会跑一批通用检查（见 [runChecks]）。
     * @return 生成的 zip；失败返回 null（调用方负责提示用户）。
     */
    fun build(
        logDir: File,
        outDir: File,
        reason: String,
        extraFiles: List<File> = emptyList(),
        checks: List<Check> = emptyList(),
    ): File? {
        return try {
            // ⚠️ **打包前必须先把异步日志刷下去**（踩过，代价是 19 份真机诊断包全部缺
            //   `engine.log` / `web.log`）。
            //
            //   `engine.log` / `web.log` 是**异步缓冲**写的（见 `LogStore.writeAsync`：
            //   「异步档**攒着不 flush**」），它们只在累积到 1MB 时才 flush 一次。
            //   而 `build()` 是**进程内即时读文件**的：此刻这两份文件在磁盘上要么
            //   还不存在，要么长度是 0 —— 于是 `writeLogFile` 第 186/188 行的
            //   `if (!f.isFile) return 0L` / `if (size <= 0) return 0L` **静默跳过**，
            //   包里连条目都没有，`completeness` 里也一个字都不记。
            //
            //   症状极具误导性：`app.log` 里明明有几百行 `[engine] …`（那是
            //   `Log.i(TAG, "[engine] $line")` 打给 logcat 的副本，同步落盘），
            //   让人以为引擎日志已经收进来了，而真正带完整上下文的那份从来没进过包。
            //
            //   放在 `build()` 入口而不是各个调用点，是因为调用点有三个
            //   （`LogViewerActivity` 两处、`LogCommandBridge`、`LogUploader`），
            //   逐个加必然漏 —— 这里加一次就全覆盖。
            LogStore.flushAsync()

            if (!outDir.exists()) outDir.mkdirs()
            val now = System.currentTimeMillis()
            val name = "diagnostic-${fileFmt.format(Date(now))}-${BootState.bootId}.zip"
            val out = File(outDir, name)

            val completeness = JSONObject()
            val entriesWritten = mutableListOf<String>()
            var budget = MAX_BUNDLE_BYTES

            ZipOutputStream(out.outputStream().buffered()).use { zos ->
                // ── ① 先写体积小、价值高的（保证它们一定进包）──
                budget -= writeEntry(zos, "environment.json", EnvironmentSnapshot.collect(LogContext.get()).toString(2), entriesWritten)

                val diagnosis = runChecks(logDir, checks)
                budget -= writeEntry(zos, "diagnosis.json", diagnosis.toString(2), entriesWritten)

                val timeline = buildTimeline(logDir, completeness)
                budget -= writeEntry(zos, "timeline.jsonl", timeline, entriesWritten)

                // ── ② 崩溃现场（价值高、体量中等）──
                budget -= writeCrashes(zos, logDir, entriesWritten, completeness)

                // ── ③ 关键文件状态 ──
                if (extraFiles.isNotEmpty()) {
                    budget -= writeEntry(
                        zos, "files.json",
                        EnvironmentSnapshot.collectFileStates(extraFiles).toString(2),
                        entriesWritten,
                    )
                }

                // ── ④ 日志（体量最大，按重要性从低到高丢）──
                // 顺序刻意是 web → 旧代 → app：web.log 最吵、信息密度最低。
                budget -= writeLogFile(zos, logDir, LogStore.WEB_LOG, budget, entriesWritten, completeness)
                budget -= writeLogFile(zos, logDir, "${LogStore.WEB_LOG}.1", budget, entriesWritten, completeness)
                budget -= writeLogFile(zos, logDir, "${LogStore.ENGINE_LOG}.1", budget, entriesWritten, completeness)
                budget -= writeLogFile(zos, logDir, LogStore.ENGINE_LOG, budget, entriesWritten, completeness)
                budget -= writeLogFile(zos, logDir, "${LogStore.APP_LOG}.1", budget, entriesWritten, completeness)
                budget -= writeLogFile(zos, logDir, LogStore.APP_LOG, budget, entriesWritten, completeness)

                // ── ④.5 引擎自己写的启动诊断 ──
                budget -= writeEngineStartupLogs(zos, entriesWritten, completeness)

                // ── ⑤ manifest 最后写（此时才知道完整情况）──
                val manifest = JSONObject().apply {
                    put("schemaVersion", SCHEMA_VERSION)
                    put("generatedAt", now)
                    put("generatedAtText", fmt.format(Date(now)))
                    put("reason", reason)
                    put("bootId", BootState.bootId)
                    put("app", VersionInfo.summary())
                    put("device", VersionInfo.deviceSummary())
                    put("entries", JSONArray(entriesWritten))
                    put("completeness", completeness)
                    put("limits", JSONObject().apply {
                        put("maxBundleBytes", MAX_BUNDLE_BYTES)
                        put("crashTailLines", LogStore.CRASH_TAIL_LINES)
                    })
                }
                // ⚠️ manifest 必须**第一个**被解压出来看（分析者的入口），
                //    zip 里顺序无所谓，但内容要在最前面写进注释说明。
                writeEntry(zos, "manifest.json", manifest.toString(2), entriesWritten)
            }

            out
        } catch (t: Throwable) {
            // 生成失败不抛：调用方会提示用户，而不是让日志界面崩掉。
            android.util.Log.e("DSH/Diagnostic", "诊断包生成失败: ${t.message}", t)
            null
        }
    }

    // ── 各项写入 ──────────────────────────────────────────────────────────────

    /**
     * 写一个 zip 条目，返回消耗的字节数（估算）。
     *
     * ⚠️ 单个条目超预算就**整个跳过**并在 completeness 里记一笔，而不是写一半
     * —— 半个 JSON 会让分析者以为包是坏的。
     */
    private fun writeEntry(
        zos: ZipOutputStream,
        name: String,
        content: String,
        written: MutableList<String>,
    ): Long {
        val bytes = content.toByteArray(Charsets.UTF_8)
        zos.putNextEntry(ZipEntry(name))
        zos.write(bytes)
        zos.closeEntry()
        written.add(name)
        return bytes.size.toLong()
    }

    /**
     * 写一个日志文件，**超预算则截断**。
     *
     * ⚠️ 截断的是**头部**（保留尾部）：日志最有价值的是「最后发生了什么」，
     * 而不是开机时的初始化。这与 `EngineService.truncateTail` 的取舍一致。
     */
    private fun writeLogFile(
        zos: ZipOutputStream,
        logDir: File,
        name: String,
        budget: Long,
        written: MutableList<String>,
        completeness: JSONObject,
    ): Long {
        val f = File(logDir, name)
        // ⚠️ **缺文件必须留痕**，不能静默 return（踩过）：19 份真机诊断包全都没有
        //   `engine.log` / `web.log`，而 `completeness` 里一个字都没记，导致
        //   「包里没有」和「日志系统没记」两种情况**从产物上无法区分**，
        //   白烧了一轮排查。现在把「不存在 / 空 / 跳过」三种情况都写进 completeness，
        //   分析者一眼能看出是哪一种。
        if (!f.isFile) {
            completeness.put("${name}_absent", "文件不存在")
            return 0L
        }
        val size = f.length()
        if (size <= 0) {
            completeness.put("${name}_empty", "文件存在但长度为 0")
            return 0L
        }

        return try {
            if (size <= budget) {
                zos.putNextEntry(ZipEntry(name))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
                written.add(name)
                recordRange(completeness, name, f, truncated = false)
                size
            } else if (budget > 64 * 1024) {
                // 保留尾部 budget 字节
                val keep = budget
                val text = f.readText()
                val cut = text.takeLast(keep.toInt().coerceAtMost(text.length))
                val content = "…（本文件前 ${text.length - cut.length} 字符因体积上限被截断）…\n$cut"
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
                written.add(name)
                recordRange(completeness, name, f, truncated = true)
                keep
            } else {
                completeness.put("${name}_skipped", "体积预算不足，已跳过")
                recordRange(completeness, name, f, truncated = true)
                0L
            }
        } catch (t: Throwable) {
            // ⚠️ **不能静默吞**（原来是 `catch (_: Throwable) { 0L }`）：那样这个
            //   日志文件会**从包里消失且 completeness 里一个字都没有**，
            //   分析者看到的现象与「这个文件本来就不存在」完全一样 ——
            //   又是一次「包在骗人」。现在把异常类型记进 completeness。
            completeness.put("${name}_failed", "读取失败: ${t.javaClass.simpleName}: ${t.message}")
            0L
        }
    }

    /**
     * 收**引擎自己写的**启动诊断（`$DSH_HOME/logs/startup-*.log`）。
     *
     * ## 🔴 为什么必须收：这是「引擎起不来」唯一的一手证据
     *
     * 引擎启动失败时，它自己在 stderr 最后一行打出：
     *
     * ```
     * [engine] Full diagnostics: <$DSH_HOME>/logs/startup-2026-10-13T15-51-43.032Z-<uuid>.log
     * ```
     *
     * 那份文件里是引擎的**完整**自检（哪些插件激活了、哪些在等哪个服务、
     * 哪个 provider 注册失败）。而我们包里的 `engine.log` 只有 stdout/stderr
     * 的**前若干行**，恰好在那行 `Full diagnostics` 之后就断了 ——
     * 也就是说 80 场 `engine-start-failed` 里，**决定性的那份文件从来没被收进来过**。
     *
     * ## 为什么要单独写，不能复用 [writeLogFile]
     *
     * [writeLogFile] 的入参是 `logDir` 下的固定文件名；而这一批在**另一个目录**
     * （`$DSH_HOME/logs`，不是 `logsDir`），文件名还带时间戳与 uuid。
     *
     * ## 只收最近 N 份
     *
     * 引擎**每次启动失败**都写一份，攒久了会有几百份。收最近 [MAX_STARTUP_LOGS]
     * 份即可覆盖「最近几次为什么起不来」，再多只是让包变大。
     * 按 mtime 倒序取，并在 `completeness` 里如实记下「一共几份、收了几份」——
     * 不能让分析者以为「包里只有 3 份 = 只失败过 3 次」。
     */
    private fun writeEngineStartupLogs(
        zos: ZipOutputStream,
        written: MutableList<String>,
        completeness: JSONObject,
    ): Long {
        val ctx = LogContext.get()
        if (ctx == null) {
            completeness.put("engineStartupLogs_absent", "LogContext 未装，取不到 filesDir")
            return 0L
        }
        val dir = File(ctx.filesDir, "home/.dsh/logs")
        if (!dir.isDirectory) {
            completeness.put("engineStartupLogs_absent", "目录不存在（引擎从未写过启动诊断）")
            return 0L
        }
        val all = dir.listFiles { f: File -> f.isFile && f.name.startsWith("startup-") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
        if (all.isEmpty()) {
            completeness.put("engineStartupLogs_empty", "目录存在但没有任何 startup-*.log")
            return 0L
        }

        val picked = all.take(MAX_STARTUP_LOGS)
        var total = 0L
        var n = 0
        for (f in picked) {
            runCatching {
                val entryName = "engine-startup/${f.name}"
                zos.putNextEntry(ZipEntry(entryName))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
                written.add(entryName)
                total += f.length()
                n++
            }
        }
        // ⚠️ 两个数都要记：只记「收了几份」会让「一共失败过 200 次」看起来像 3 次。
        completeness.put("engineStartupLogs", JSONObject().apply {
            put("totalOnDevice", all.size)
            put("included", n)
            put("newest", fmt.format(Date(picked.first().lastModified())))
            put("oldestIncluded", fmt.format(Date(picked.last().lastModified())))
        })
        return total
    }

    /**
     * 记录一个日志文件的覆盖范围。
     *
     * ⚠️ 这是 `completeness` 里最重要的信息：分析者必须能回答「这份日志覆盖了
     * 哪段时间」。否则「日志里没有这条错误」可能只是**那段时间不在包里**。
     */
    private fun recordRange(completeness: JSONObject, name: String, f: File, truncated: Boolean) {
        runCatching {
            completeness.put(
                name,
                JSONObject().apply {
                    put("sizeBytes", f.length())
                    put("truncated", truncated)
                    put("lastModified", f.lastModified())
                    put("lastModifiedText", fmt.format(Date(f.lastModified())))
                    put("lines", countLines(f))
                },
            )
        }
    }

    private fun countLines(f: File): Long = runCatching {
        f.bufferedReader().useLines { it.count().toLong() }
    }.getOrDefault(-1L)

    /** 写全部崩溃现场。 */
    private fun writeCrashes(
        zos: ZipOutputStream,
        logDir: File,
        written: MutableList<String>,
        completeness: JSONObject,
    ): Long {
        val scenes = CrashRecorder.listScenes()
        var total = 0L
        val index = JSONArray()
        for (f in scenes) {
            runCatching {
                val entryName = "crashes/${f.name}"
                zos.putNextEntry(ZipEntry(entryName))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
                written.add(entryName)
                total += f.length()
                index.put(
                    JSONObject().apply {
                        put("file", entryName)
                        put("sizeBytes", f.length())
                        put("at", f.lastModified())
                        put("atText", fmt.format(Date(f.lastModified())))
                        put("hardCrash", CrashRecorder.isHardCrash(f))
                    },
                )
            }
        }
        if (index.length() > 0) {
            completeness.put("crashScenes", index.length())
        }
        return total
    }

    private fun buildTimeline(logDir: File, completeness: JSONObject): String {
        val events = EventLog.readAll(logDir)
        completeness.put("eventCount", events.size)
        return events.joinToString("\n") { it.toString() }
    }

    // ── 自动结论 ──────────────────────────────────────────────────────────────

    /** 一项检查的结论。`status` 只有三种：ok / warn / fail。 */
    data class Check(val id: String, val status: String, val detail: String) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("status", status)
            put("detail", detail)
        }
    }

    /**
     * 跑通用检查 + 调用方给的检查，产出 `diagnosis.json`。
     *
     * ## 设计原则：只报**事实**，不猜原因
     *
     * 例如「1 个插件未激活: dsh-green-theme」是事实；「因为插件代码有语法错误」
     * 是猜测。自动结论里只写前者 —— 后者交给分析者（或 AI）看日志判断。
     *
     * ⚠️ 编造原因比不给结论更糟：它会把人引到错误的方向，而且看起来很可信。
     */
    /**
     * 把一批现场文件按类别汇总成 `engine-start-failed×70, engine-exit×16` 这样的短语。
     *
     * 实现在 [CrashRecorder.summarizeKinds]（`logs` 工具也要用同一套口径）。
     */
    private fun sceneKindSummary(scenes: List<File>): String = CrashRecorder.summarizeKinds(scenes)

    private fun runChecks(logDir: File, extra: List<Check>): JSONObject {
        val checks = mutableListOf<Check>()

        // ① 上次退出类型
        runCatching {
            val prev = BootState.previous()
            checks.add(
                when {
                    !prev.known -> Check("last-exit", "ok", "首次启动，无历史记录")
                    prev.clean -> Check("last-exit", "ok", "上次正常退出")
                    prev.unstable -> Check(
                        "last-exit", "fail",
                        "连续 ${prev.consecutiveUncleanCount} 次未正常结束 —— 疑似启动即崩的死循环",
                    )
                    else -> Check("last-exit", "warn", "上次非正常结束（无堆栈；可能是崩溃/被系统回收/被 ROM 冻结/断电）")
                },
            )
        }

        // ② 崩溃现场
        runCatching {
            val scenes = CrashRecorder.listScenes()
            val hard = scenes.count { CrashRecorder.isHardCrash(it) }
            checks.add(
                when {
                    scenes.isEmpty() -> Check("crash-scenes", "ok", "没有崩溃现场文件")
                    hard > 0 -> Check("crash-scenes", "fail", "有 $hard 份崩溃现场（共 ${scenes.size} 份），详见 crashes/")
                    // ⚠️ **必须列出实际类别，不能举例子。** 原来这里写的是
                    //   「（渲染进程被回收/引擎正常退出等）」—— 而真机 22 个 warn 包
                    //   里那两个例子出现 **0** 次，实际全是 `engine-start-failed`(70)
                    //   与 `engine-exit`(16)。也就是说文案把「引擎起不来」这种
                    //   `EngineManager` 自己注释成「本 App 最严重的一类故障」的事，
                    //   报告成了「引擎正常退出等」—— 分析者据此会直接跳过它。
                    //   例子是猜测，类别是事实；这里只报事实。
                    else -> Check(
                        "crash-scenes", "warn",
                        "有 ${scenes.size} 份非崩溃现场（${sceneKindSummary(scenes)}），详见 crashes/",
                    )
                },
            )
        }

        // ③ 日志被抽稀情况 —— 分析者必须知道
        runCatching {
            val throttled = LogStore.throttledTotal
            val dropped = LogStore.droppedAsync
            if (throttled > 0 || dropped > 0) {
                checks.add(
                    Check(
                        "log-completeness", "warn",
                        "日志被抽稀：限流丢弃 $throttled 条，队列满丢弃 $dropped 条（说明有高频重复日志）",
                    ),
                )
            } else {
                checks.add(Check("log-completeness", "ok", "日志未发生限流或丢弃"))
            }
        }

        // ④ 落盘是否可用
        runCatching {
            checks.add(
                if (LogStore.fileLoggingEnabled) {
                    Check("file-logging", "ok", "文件日志可用")
                } else {
                    Check("file-logging", "fail", "文件日志不可用（磁盘满/只读/权限），只有内存缓冲")
                },
            )
        }

        // ⑤ 磁盘空间 —— 解包失败的常见原因
        runCatching {
            val ctx = LogContext.get()
            if (ctx != null) {
                val fs = android.os.StatFs(ctx.filesDir.absolutePath)
                val freeMb = fs.availableBytes / (1024 * 1024)
                checks.add(
                    if (freeMb < 200) {
                        Check("disk-space", "warn", "数据分区剩余 ${freeMb}MB，低于 200MB —— 解包可能失败")
                    } else {
                        Check("disk-space", "ok", "数据分区剩余 ${freeMb}MB")
                    },
                )
            }
        }

        // ⑥ 内存
        runCatching {
            val rt = Runtime.getRuntime()
            val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            val maxMb = rt.maxMemory() / (1024 * 1024)
            checks.add(
                if (usedMb * 100 / maxMb > 85) {
                    Check("heap", "warn", "堆占用 ${usedMb}MB / ${maxMb}MB（超过 85%），有 OOM 风险")
                } else {
                    Check("heap", "ok", "堆占用 ${usedMb}MB / ${maxMb}MB")
                },
            )
        }

        // ⑦ 调用方给的检查（资产版本戳、补丁锚点、插件激活 —— EngineManager 知道这些）
        checks.addAll(extra)

        val ok = checks.count { it.status == "ok" }
        val warn = checks.count { it.status == "warn" }
        val fail = checks.count { it.status == "fail" }

        return JSONObject().apply {
            put("generatedAt", System.currentTimeMillis())
            put("generatedAtText", fmt.format(Date()))
            put("bootId", BootState.bootId)
            put("summary", JSONObject().apply {
                put("ok", ok)
                put("warn", warn)
                put("fail", fail)
                // 一句话结论，给不看细节的人
                put(
                    "headline",
                    when {
                        fail > 0 -> "发现 $fail 项严重问题，详见 checks"
                        warn > 0 -> "发现 $warn 项需要注意的情况"
                        else -> "未发现异常"
                    },
                )
            })
            put("checks", JSONArray(checks.map { it.toJson() }))
        }
    }

    /**
     * 从 `diagnosis.json` 里提炼一行人类可读的摘要。
     *
     * 给日志界面顶部与上传成功提示用 —— 用户看到「发现 2 项严重问题」比看到
     * 文件名有用得多。
     */
    fun summarize(diagnosisJson: String): String = runCatching {
        val o = JSONObject(diagnosisJson)
        val s = o.optJSONObject("summary") ?: return@runCatching ""
        "ok=${s.optInt("ok")} warn=${s.optInt("warn")} fail=${s.optInt("fail")} —— ${s.optString("headline")}"
    }.getOrDefault("")

    /** 诊断包格式版本。将来改结构时服务器靠它分流。 */
    const val SCHEMA_VERSION = 1
}
