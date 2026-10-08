package com.dsh.android

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * root 权限探测。
 *
 * ## 为什么需要它
 *
 * `root` 工具**只在设备确实有 root 时才该被注册**。注册了却拿不到权限的工具
 * 比没有这个工具更糟：模型会反复去试一个必然失败的能力，用户看到一堆报错。
 * 所以宿主侧必须先探明，再决定挂不挂 overlay（见 [EngineManager.writeRootPatch]）。
 *
 * ## 探测方式：真跑一次，而不是查文件
 *
 * ⚠️ **「存在 `su` 文件」不等于「有 root」**。实测有三种假阳性：
 *
 * | 情况 | 文件在 | 真能提权 |
 * |---|---|---|
 * | ROM 自带占位 `su`（无 suid、无后端） | 是 | **否** |
 * | Magisk 装了但本应用**没被授权** | 是 | **否**（弹框/拒绝） |
 * | 用户装了 su 但 root 已失效（升级后未重启） | 是 | **否** |
 *
 * 所以判据只能是**端到端跑一次 `id -u` 并检查输出是不是 `0`** —— 与
 * `dsh-android-mobile` 那条特权探测路由同一个原则（那里也是宁可真跑一次）。
 *
 * ## 三种结果必须分开
 *
 * [State] 刻意不是布尔值：
 *
 * - `GRANTED` —— 确认有 root，注册工具。
 * - `DENIED` —— 有 `su` 但拿不到 uid 0（未授权 / 被拒 / 弹框超时）。**不注册**，
 *   但要在设置页告诉用户「去授权」。
 * - `ABSENT` —— 连 `su` 都没有。**不注册**，也不该提示用户去做什么。
 *
 * ⚠️ 把 `DENIED` 与 `ABSENT` 混成一个 `false` 会让设置页无法给出正确的下一步 ——
 * 这与密钥来源那处（`imported`/`generated`/`paired` 三态）是同一个教训。
 *
 * ## 缓存
 *
 * 探测要起进程、还可能弹授权框，**不能每次启动都做**。结果落盘到
 * `filesDir/root-state`，TTL 见 [CACHE_TTL_MS]。用户装了 Magisk 之后
 * 可以用设置页的「重新检测」强制刷新（[invalidate]）。
 */
object RootAccess {

    private const val TAG = DshApplication.TAG

    /** 探测结果落盘的文件名（`filesDir` 下）。 */
    private const val CACHE_FILE = "root-state"

    /**
     * 缓存有效期（ms）。
     *
     * 取 24 小时：root 状态极少变化（装/卸 Magisk、授权/撤销），一天一次的成本
     * （一次进程启动）可以忽略；而每次冷启动都探会让启动路径多一个可能弹框的
     * 阻塞操作 —— 那是不可接受的。
     */
    private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L

    /**
     * 探测超时（秒）。
     *
     * ⚠️ 必须够短：首次探测时 Magisk 会弹授权框，而用户此刻多半正在看启动页。
     * 等太久会拖住引擎启动。5 秒是「用户手快能点到」与「不拖慢启动」的折中；
     * 超时按 [State.DENIED] 处理（有 su 但没拿到），设置页会提示去授权。
     */
    private const val PROBE_TIMEOUT_SEC = 5L

    /** `su` 的候选路径。与 `assets/dsh-android-root.mjs` 里那份保持一致。 */
    private val SU_CANDIDATES = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/debug_ramdisk/su",
        "/magisk/.core/bin/su",
    )

    /** 探测结论。 */
    enum class State {
        /** 确认能拿到 uid 0。 */
        GRANTED,

        /** 有 `su`，但拿不到 uid 0（未授权 / 被拒 / 弹框超时）。 */
        DENIED,

        /** 设备上没有 `su`。 */
        ABSENT,
    }

    /** 一次探测的完整结果（结论 + 给用户看的诊断文本）。 */
    data class Result(val state: State, val detail: String) {
        /** 是否可用（只有 [State.GRANTED] 为真）。 */
        val granted: Boolean get() = state == State.GRANTED
    }

    /**
     * 缓存里的一次探测记录：结论 + **落盘时刻**。
     *
     * 带上时间戳是给设置页用的 —— 用户需要知道「这是刚测的还是昨天的」，
     * 否则一个 23 小时前的 `GRANTED` 会被当成当前事实。
     */
    data class Snapshot(val result: Result, val at: Long)

    /**
     * 取探测结果，必要时重新探测并落盘。
     *
     * ⚠️ **阻塞调用**（最多 [PROBE_TIMEOUT_SEC] 秒 + 进程启动开销），必须在
     *   后台线程调用。`EngineManager.start()` 跑在 `Dispatchers.IO` 上，可以直接调。
     *
     * @param force - 忽略缓存、强制重探（设置页的「重新检测」）。会先清缓存再跑，
     *   所以即使这次探测失败（比如又超时）也不会把旧的结论留在盘上误导界面。
     */
    fun detect(context: Context, force: Boolean = false): Result {
        if (force) invalidate(context)
        cached(context)?.let { return it }
        val fresh = probe()
        save(context, fresh)
        return fresh
    }

    /** 读缓存（未过期才返回），不触发探测。 */
    fun cached(context: Context): Result? {
        val snap = snapshot(context) ?: return null
        if (System.currentTimeMillis() - snap.at > CACHE_TTL_MS) return null
        return snap.result
    }

    /**
     * 读缓存原文，**不看 TTL**。
     *
     * ⚠️ 与 [cached] 的区别正在于不判过期：[cached] 是 [detect] 的判据，
     *   过期就必须重探；而设置页要显示的是「上次测出来的结论 + 什么时候测的」，
     *   过期与否由界面自己表达（提示用户点「重新检测」），不该在这里被吞成
     *   「没有数据」—— 那会让界面显示「尚未检测」，而用户明明刚看过结论。
     *
     * 不触发任何探测，可以安全地在 WebView 的 JS 线程上调用。
     */
    fun snapshot(context: Context): Snapshot? {
        val f = File(context.filesDir, CACHE_FILE)
        if (!f.isFile) return null
        return try {
            val lines = f.readLines()
            if (lines.size < 2) return null
            val at = lines[0].toLongOrNull() ?: return null
            val state = runCatching { State.valueOf(lines[1]) }.getOrNull() ?: return null
            Snapshot(Result(state, lines.drop(2).joinToString("\n")), at)
        } catch (t: Throwable) {
            Log.w(TAG, "读取 root 探测缓存失败: ${t.message}")
            null
        }
    }

    /**
     * 让缓存失效（设置页的「重新检测」）。
     *
     * 只删缓存文件，**不立即探测** —— 探测可能要弹授权框，必须由用户主动触发。
     */
    fun invalidate(context: Context) {
        val f = File(context.filesDir, CACHE_FILE)
        if (f.isFile && f.delete()) Log.i(TAG, "已清除 root 探测缓存")
    }

    private fun save(context: Context, result: Result) {
        try {
            File(context.filesDir, CACHE_FILE).writeText(
                buildString {
                    appendLine(System.currentTimeMillis().toString())
                    appendLine(result.state.name)
                    appendLine(result.detail)
                }
            )
        } catch (t: Throwable) {
            // 写不进缓存只是下次要再探一遍，不影响结论本身
            Log.w(TAG, "写入 root 探测缓存失败: ${t.message}")
        }
    }

    /**
     * 真跑一次 `su -c id -u`，检查输出是不是 `0`。
     *
     * ⚠️ **必须先找 `su` 再跑**：设备上完全没有 `su` 时直接跑会拿到
     * `IOException: Cannot run program`，那与「有 su 但被拒」是两回事，
     * 混在一起会让设置页给出错误的引导。
     */
    private fun probe(): Result {
        val su = SU_CANDIDATES.firstOrNull { File(it).exists() }
            ?: return Result(State.ABSENT, "设备上没有 su（已查: ${SU_CANDIDATES.joinToString()}）")

        return try {
            val pb = ProcessBuilder(su, "-c", "id -u")
                .redirectErrorStream(true)
            val process = pb.start()
            // ⚠️ **先 `waitFor(超时)`，再读输出** —— 顺序不能反。
            //   反过来的话 `readText()` 会一直阻塞到流关闭，而 su 弹授权框时
            //   流永远不会关闭 → 超时形同虚设、整个探测卡死（写这一版时踩到过）。
            //   先等是安全的：本命令输出只有几个字节，远小于 64KB 管道缓冲区，
            //   子进程不可能阻塞在 write 上。
            val finished = process.waitFor(PROBE_TIMEOUT_SEC, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                // 超时 = 弹了授权框没人点。这是**有 su 但没拿到**，不是「没 root」。
                return Result(
                    State.DENIED,
                    "su 等待超时（${PROBE_TIMEOUT_SEC}s）—— 多半是 root 授权框弹出来了没人点",
                )
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val uid = output.lineSequence().lastOrNull { it.isNotBlank() }?.trim() ?: ""
            if (uid == "0") {
                Result(State.GRANTED, "su=$su, id -u → 0")
            } else {
                Result(State.DENIED, "su=$su 可执行但身份不是 root（id -u → ${uid.ifEmpty { "空" }}）")
            }
        } catch (t: Throwable) {
            Result(State.DENIED, "执行 su 失败: ${t.message}")
        }
    }
}
