package com.dsh.android.log

import android.os.Build
import android.util.Log as AndroidLog
import com.dsh.android.BuildConfig
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 启动 / 干净退出标记 —— Android 10 上判定「上次为什么没了」的**唯一**办法。
 *
 * ## 为什么不能靠系统
 *
 * API 30 才有 `ApplicationExitInfo`（系统给的退出原因 + 堆栈）。本工程
 * `targetSdk = 28`（硬约束②，见 AGENTS.md），`minSdk = 26` —— 真机跑在
 * Android 10 上时**拿不到**这个 API。所以只能自己记。
 *
 * ## 判据
 *
 * 启动时写 `clean = false`；正常退出时写 `clean = true`。
 * 下次启动若读到 `clean == false`，就说明上次**没走到正常退出** ——
 * 崩溃、被 LMK 回收、被国产 ROM 冻结、断电、用户强停，都会留下这个标记。
 *
 * ## ⚠️ 诚实分级：这不是「崩溃」的同义词
 *
 * 「非正常结束」**不等于**崩溃。它可能是：
 *
 * - 真崩溃（那种情况 [CrashRecorder] 会另有一份带堆栈的文件）
 * - 低内存被系统回收（正常行为，尤其 App 在后台时）
 * - 国产 ROM 的电池优化把进程冻结/杀掉
 * - 用户从最近任务划掉（这条 `onTaskRemoved` 能补上，但国产 ROM 常不给回调）
 * - 断电 / 用户强停
 *
 * 所以诊断包里**如实写成「上次非正常结束（无堆栈）」**，绝不谎称崩溃。
 * 分析者据此知道「有异常，但没有堆栈」，而不是「这里有个崩溃我找不到堆栈」。
 *
 * ## 连续计数为什么要单独记
 *
 * 一次非正常结束是噪声（后台被回收很正常）。**连续**多次才说明是启动即崩的
 * 死循环 —— 那正是最需要立刻告诉用户的场景（App 打不开，用户以为彻底坏了）。
 */
object BootState {

    private const val FILE_NAME = "boot-state.json"

    /** 连续非正常结束多少次后，界面应当明确提示用户。 */
    const val UNSTABLE_THRESHOLD = 3

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var file: File? = null

    @Volatile
    private var state: State = State()

    /**
     * 本次启动的 id。
     *
     * ⚠️ 它是把「崩溃现场」与「那一次启动的日志」对上的唯一线索：一次会话里
     * 崩溃文件带 `bootId`，诊断包的 `manifest.json` 也带，分析者才能确定
     * 「这份崩溃属于这次启动」而不是上次残留的。
     */
    @Volatile
    var bootId: String = ""
        private set

    /**
     * 读取上次状态并开始记录本次启动。
     *
     * ⚠️ 必须在 `attachBaseContext()` 里、[LogStore.init] 之后**立刻**调用 ——
     * 晚于任何一次崩溃就没意义了。
     *
     * @return 上一次的结束情况，供界面/诊断包使用。
     */
    fun begin(logDir: File): Previous {
        file = File(logDir, FILE_NAME)
        val prev = read()
        val now = System.currentTimeMillis()
        bootId = "b-$now-" + Integer.toHexString((now xor (now ushr 32)).toInt() and 0xffff)

        // 判定上一次
        val unclean = prev != null && !prev.clean
        val consecutive = if (unclean) prev!!.consecutiveUncleanCount + 1 else 0

        state = State(
            bootId = bootId,
            startedAt = now,
            clean = false,
            consecutiveUncleanCount = consecutive,
            lastExit = prev?.let {
                LastExit(
                    at = it.startedAt,
                    clean = it.clean,
                    bootId = it.bootId,
                    reason = if (it.clean) "clean" else "unclean",
                )
            },
            versionName = VersionInfo.versionName,
            versionCode = VersionInfo.versionCode,
            flavor = VersionInfo.flavor,
        )
        write()

        val previous = Previous(
            known = prev != null,
            clean = prev?.clean ?: true,
            consecutiveUncleanCount = consecutive,
            bootId = prev?.bootId,
            startedAt = prev?.startedAt ?: 0L,
        )

        if (unclean) {
            // 记一条 Warn：这是「上次为什么没了」在日志里的落点。
            LogStore.app(
                'W',
                "Boot",
                "上次启动未正常结束（bootId=${prev!!.bootId}，连续第 $consecutive 次）" +
                    if (consecutive >= UNSTABLE_THRESHOLD) " —— 已达不稳定阈值，界面应提示用户" else "",
            )
            EventLog.record("boot.previous-unclean", mapOf("count" to consecutive.toString()))
        } else {
            EventLog.record("boot.start", mapOf("clean" to "true"))
        }
        return previous
    }

    /**
     * 标记为干净退出。
     *
     * ⚠️ **只在真的能确认时调用**：`MainActivity.onDestroy()` 且 `isFinishing`
     * （用户主动退出），以及 `onTaskRemoved`（从最近任务划掉）。**不要**在
     * `onStop()` 里调 —— 那只是切到后台，进程还活着，下一次进前台不该被当成
     * 「上次结束了」。
     *
     * ⚠️ 国产 ROM 经常不给 `onTaskRemoved` 回调（直接杀进程），所以「用户划掉」
     * 这条路径**不能保证**记上 —— 那种情况会退化成「非正常结束」。这是平台
     * 限制，如实记录即可，不要试图猜。
     */
    fun markCleanExit(reason: String) {
        if (state.clean) return
        state = state.copy(
            clean = true,
            consecutiveUncleanCount = 0,
            lastExit = LastExit(
                at = System.currentTimeMillis(),
                clean = true,
                bootId = bootId,
                reason = reason,
            ),
        )
        write()
    }

    /** 当前状态快照（诊断包用）。 */
    fun current(): State = state

    /** 上一次启动的情况（界面提示用）。 */
    fun previous(): Previous = state.lastExit?.let {
        Previous(
            known = true,
            clean = it.clean,
            consecutiveUncleanCount = state.consecutiveUncleanCount,
            bootId = it.bootId,
            startedAt = it.at,
        )
    } ?: Previous(known = false, clean = true, consecutiveUncleanCount = 0, bootId = null, startedAt = 0L)

    /** 启动至今的毫秒数（事件时间线用）。 */
    fun uptimeMs(): Long = System.currentTimeMillis() - state.startedAt

    // ── 落盘 ──────────────────────────────────────────────────────────────────

    private fun read(): State? {
        val f = file ?: return null
        if (!f.isFile) return null
        return try {
            val o = JSONObject(f.readText())
            State(
                bootId = o.optString("bootId", ""),
                startedAt = o.optLong("startedAt", 0L),
                clean = o.optBoolean("clean", true),
                consecutiveUncleanCount = o.optInt("consecutiveUncleanCount", 0),
                lastExit = o.optJSONObject("lastExit")?.let { e ->
                    LastExit(
                        at = e.optLong("at", 0L),
                        clean = e.optBoolean("clean", true),
                        bootId = e.optString("bootId", ""),
                        reason = e.optString("reason", ""),
                    )
                },
                versionName = o.optString("versionName", ""),
                versionCode = o.optInt("versionCode", 0),
                flavor = o.optString("flavor", ""),
            )
        } catch (t: Throwable) {
            // 坏文件不是错误：当成「没有历史」。不删它 —— 万一要人工看。
            AndroidLog.w(TAG, "读取 boot-state.json 失败（当作无历史）: ${t.message}")
            null
        }
    }

    /**
     * 原子写。
     *
     * ⚠️ **必须原子**：这个文件写的时刻恰恰是「马上要崩溃/被杀了」的时刻，
     * 半个 JSON 会让下次启动读不出来，于是「上次非正常结束」这条最关键的
     * 线索就丢了。先写临时文件再 rename。
     */
    private fun write() {
        val f = file ?: return
        try {
            val o = JSONObject().apply {
                put("bootId", state.bootId)
                put("startedAt", state.startedAt)
                put("clean", state.clean)
                put("consecutiveUncleanCount", state.consecutiveUncleanCount)
                put("versionName", state.versionName)
                put("versionCode", state.versionCode)
                put("flavor", state.flavor)
                state.lastExit?.let { e ->
                    put(
                        "lastExit",
                        JSONObject().apply {
                            put("at", e.at)
                            put("clean", e.clean)
                            put("bootId", e.bootId)
                            put("reason", e.reason)
                        },
                    )
                }
                put("updatedAt", System.currentTimeMillis())
            }
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(o.toString())
                tmp.delete()
            }
        } catch (t: Throwable) {
            AndroidLog.w(TAG, "写入 boot-state.json 失败: ${t.message}")
        }
    }

    // ── 数据结构 ──────────────────────────────────────────────────────────────

    data class State(
        val bootId: String = "",
        val startedAt: Long = 0L,
        val clean: Boolean = true,
        val consecutiveUncleanCount: Int = 0,
        val lastExit: LastExit? = null,
        val versionName: String = "",
        val versionCode: Int = 0,
        val flavor: String = "",
    )

    data class LastExit(
        val at: Long,
        val clean: Boolean,
        val bootId: String,
        val reason: String,
    )

    /** 上一次启动的情况，给界面用。 */
    data class Previous(
        val known: Boolean,
        val clean: Boolean,
        val consecutiveUncleanCount: Int,
        val bootId: String?,
        val startedAt: Long,
    ) {
        /** 上次非正常结束。⚠️ **不等于崩溃**，见类注释的诚实分级。 */
        val unclean: Boolean get() = known && !clean

        /** 连续非正常结束已达阈值 —— 界面该明确提示。 */
        val unstable: Boolean get() = unclean && consecutiveUncleanCount >= UNSTABLE_THRESHOLD

        /** 给界面的一句话（中文）。 */
        fun describe(): String = when {
            !known -> "首次启动，无历史记录"
            clean -> "上次正常退出（${fmt.format(Date(startedAt))}）"
            unstable -> "连续 $consecutiveUncleanCount 次未正常结束，建议查看日志"
            else -> "上次未正常结束（${fmt.format(Date(startedAt))}，无堆栈）"
        }
    }

    private const val TAG = "DSH/BootState"
}

/**
 * 版本信息的**唯一读取点**。
 *
 * ## 为什么要有这一层
 *
 * `BuildConfig` 是**构建期生成**的，`flavor` 字段两个 flavor 不一样
 * （`system` / `gecko`）。日志系统有四五处要写「App 版本」，各写各的迟早会
 * 不一致 —— 而诊断包里「哪个版本出的问题」必须唯一可信。
 *
 * ⚠️ 全部用 `runCatching` 兜住：诊断包在**启动极早期**就要能生成（崩溃路径），
 * 那时 `BuildConfig` 一定可用，但 `PackageManager` 未必 —— 拿不到就退回
 * 空串，绝不因为「查版本号失败」而让整个诊断包生成失败。
 */
object VersionInfo {

    val versionName: String = runCatching { BuildConfig.VERSION_NAME }.getOrDefault("")

    val versionCode: Int = runCatching { BuildConfig.VERSION_CODE }.getOrDefault(0)

    /** 两个 flavor 之一：`system`（系统 WebView）/ `gecko`（内置内核）。 */
    val flavor: String = runCatching {
        if (BuildConfig.BUILT_IN_WEB_ENGINE) "gecko" else "system"
    }.getOrDefault("")

    val applicationId: String = runCatching { BuildConfig.APPLICATION_ID }.getOrDefault("")

    /** `0.2.7 (14) system` 这种一行摘要。 */
    fun summary(): String = "$versionName ($versionCode) $flavor"

    /** 设备与系统的一行摘要。 */
    fun deviceSummary(): String =
        "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} " +
            "(API ${Build.VERSION.SDK_INT}) / ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}"
}
