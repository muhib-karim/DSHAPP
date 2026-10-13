package com.dsh.android.log

import android.util.Log as AndroidLog
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

/**
 * 结构化事件时间线 —— 把「人肉翻日志」变成「一眼看出卡在哪一步」。
 *
 * ## 为什么光有日志不够
 *
 * 几千行日志里真正决定性的只有十几条：「Termux 引导开始/完成」「引擎启动/就绪/退出」
 * 「WebView 开始加载/加载完成/插件激活」。其余都是细节。而这些里程碑现在**散落在
 * 各处**、格式各异、有的还是英文，分析者要自己拼时间线。
 *
 * 本类把这些里程碑抽成**结构化的、机器可读的**一行一个 JSON —— 诊断包里的
 * `timeline.jsonl` 就是它。于是「引擎起来了但界面没加载」这种结论可以直接从
 * 时间线读出来，不必看日志正文。
 *
 * ## 与日志的分工（刻意分开，不是重复）
 *
 * | | 日志（app.log 等） | 事件（timeline.jsonl） |
 * |---|---|---|
 * | 内容 | 过程细节，给人看 | 里程碑，给机器+人看 |
 * | 体量 | 几万行 | 十几行 |
 * | 格式 | 文本 | JSON |
 * | 用途 | 查「具体发生了什么」 | 查「卡在哪一步、耗时多少」 |
 *
 * ## 为什么带 `elapsedMs`
 *
 * 本项目的故障有一大类是**慢**而不是**错**：「启动要 40 秒」「界面加载不出来」
 * 实际是某个步骤卡住了。时间线里带上「距启动多少毫秒」，慢在哪一步一目了然 ——
 * 只看绝对时间戳是看不出这个的。
 */
object EventLog {

    /** 事件文件名。**公开**给 `LogStore.clearAll` 用 —— 清空日志时要一并删掉它。 */
    const val FILE_NAME = "events.jsonl"

    /** 单个事件文件上限。事件很少，1 MB 够存几万条。 */
    private const val MAX_BYTES = 1L * 1024L * 1024L

    @Volatile
    private var writer: BufferedWriter? = null

    @Volatile
    private var bytes = 0L

    @Volatile
    private var file: File? = null

    /** 落盘是否可用。不可用时事件只进 app.log（降级但信息不丢）。 */
    private val enabled: Boolean get() = writer != null

    /**
     * 打开事件文件。在 [LogStore.init] 之后调用。
     *
     * ⚠️ 追加而不是覆盖：一次崩溃重启后，上一次启动的时间线正是要看的 ——
     * 覆盖掉就永远看不到「上次卡在哪一步」。
     */
    fun init(logDir: File) {
        try {
            if (!logDir.exists()) logDir.mkdirs()
            val f = File(logDir, FILE_NAME)
            file = f
            writer = BufferedWriter(OutputStreamWriter(FileOutputStream(f, true), Charsets.UTF_8))
            bytes = f.length()
        } catch (t: Throwable) {
            AndroidLog.w(TAG, "EventLog.init 失败（事件只进 app.log）: ${t.message}")
            writer = null
        }
    }

    /**
     * 记一个事件。
     *
     * @param name 事件名，**点分小写**（`engine.ready` / `web.load.done`）。
     *   刻意用英文而不是中文：它是机器读的键，将来做统计/聚合时不用再翻译。
     *   面向用户的说明在日志里另有一条中文。
     * @param fields 附加字段。**值必须是短字符串**（别塞日志正文）。
     */
    fun record(name: String, fields: Map<String, String> = emptyMap()) {
        val t = System.currentTimeMillis()
        val elapsed = try {
            BootState.uptimeMs()
        } catch (_: Throwable) {
            0L
        }
        // 同时进 app.log：事件文件损坏/丢失时，日志里还有一条中文轨迹。
        // ⚠️ 这是**有意的双写**，不是冗余 —— 两者用途不同（见类注释的分工表）。
        val detail = if (fields.isEmpty()) "" else " " + fields.entries.joinToString(" ") { "${it.key}=${it.value}" }
        LogStore.app('I', "Event", "$name$detail (t+${elapsed}ms)")

        val w = writer ?: return
        try {
            val o = JSONObject().apply {
                put("t", t)
                put("elapsedMs", elapsed)
                put("name", name)
                put("bootId", BootState.bootId)
                if (fields.isNotEmpty()) {
                    put("fields", JSONObject().apply { fields.forEach { (k, v) -> put(k, v) } })
                }
            }
            synchronized(this) {
                w.write(o.toString())
                w.write("\n")
                w.flush()
                bytes += o.length() + 1
                if (bytes >= MAX_BYTES) rotate()
            }
        } catch (_: Throwable) {
            // 事件写不进去不致命（app.log 里那条已经落了）
        }
    }

    private fun rotate() {
        try {
            writer?.close()
        } catch (_: Throwable) {
        }
        writer = null
        val f = file ?: return
        try {
            val old = File(f.parentFile, "$FILE_NAME.1")
            if (old.exists()) old.delete()
            f.renameTo(old)
            writer = BufferedWriter(OutputStreamWriter(FileOutputStream(f, false), Charsets.UTF_8))
            bytes = 0L
        } catch (_: Throwable) {
        }
    }

    /**
     * 读回事件（诊断包与日志界面用）。
     *
     * ⚠️ 坏行**跳过**而不是整体失败：`.jsonl` 的意义就是「一行坏了不影响别的行」。
     * 若因为一行半截 JSON 就让整个时间线读不出来，那才是真丢信息。
     */
    fun readAll(logDir: File): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        for (name in listOf("$FILE_NAME.1", FILE_NAME)) {
            val f = File(logDir, name)
            if (!f.isFile) continue
            try {
                f.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    try {
                        out.add(JSONObject(line))
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return out
    }

    /**
     * 时间线的一行人类可读摘要。日志界面与诊断包的 `summary` 用。
     */
    fun describe(o: JSONObject): String {
        val elapsed = o.optLong("elapsedMs", 0L)
        val name = o.optString("name", "")
        val fields = o.optJSONObject("fields")
        val detail = if (fields == null) "" else
            fields.keys().asSequence().joinToString(" ") { "$it=${fields.optString(it)}" }
        return "[+${elapsed}ms] $name ${detail}".trim()
    }

    fun flushAndClose() {
        try {
            synchronized(this) { writer?.flush() }
            writer?.close()
        } catch (_: Throwable) {
        }
        writer = null
    }

    private const val TAG = "DSH/EventLog"
}
