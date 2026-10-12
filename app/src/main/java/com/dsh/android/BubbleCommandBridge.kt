package com.dsh.android

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 悬浮球的**命令信箱** —— 让 Node 进程里的模型工具能改到原生这个球。
 *
 * ## 为什么需要它
 *
 * 悬浮球的外观接口原先只有页面侧插件够得着（`window.DSHAndroid.bubbleApply()`），
 * 而模型跑在 **Node 进程**里，那里没有 `window`。于是「让 AI 改悬浮窗」在
 * 可发现性上是空的：模型既不知道有这个能力，也没有任何途径触达它。
 *
 * 本类补的就是这一段：`dsh-android-bubble` 插件把命令写进 `$DSH_HOME/bubble-cmd.json`，
 * 这里轮询读走、交给 [FloatingBubble.applyAppearance]，再把结果写回
 * `$DSH_HOME/bubble-result.json`。
 *
 * ## 为什么是文件而不是 socket / Binder
 *
 * 引擎 node 是 App 进程的**子进程**，两者同域、共享 app 私有目录 —— 文件是最短的
 * 那条路。本项目已有同构先例（`adb-wireless-port`：Kotlin 写、Node 读），这里只是
 * 把方向反过来。不选 AF_UNIX 抽象命名空间：Android 的 app 域对命名管道的策略
 * 随版本变（见 AGENTS.md 的沙箱说明），而文件在两个方向上都已经验过。
 *
 * ## 三条不变量
 *
 * ① **请求带自增 `id`，回执按 id 配对。** 没有它，「两个工具调用并发、后一个的
 *    回执被前一个读走」会让模型拿到张冠李戴的结果。
 * ② **先写结果、再删请求。** 反过来会丢回执：插件那边「没看到回执」与「原生还没处理」
 *    无法区分，只能超时重试。
 * ③ **命令文件损坏（写到一半）不是错误。** 插件用「写临时文件 + rename」发布，
 *    这里读到解析失败就下一轮再读，不要记错误日志刷屏。
 *
 * ⚠️ 轮询间隔 250ms 是**故意粗**的：这是给人按需改外观的通道（一天可能用几次），
 * 不值得为它做 inotify。真机实测一次改动的往返（含此间隔）在 300ms 内。
 */
object BubbleCommandBridge {

    private const val TAG = DshApplication.TAG

    /** 请求文件名。**必须与 `mobile-ui-plugin/dsh-android-bubble/lib/index.js` 一致。** */
    private const val CMD_FILE = "bubble-cmd.json"

    /** 回执文件名。同上，两边必须一起改。 */
    private const val RESULT_FILE = "bubble-result.json"

    /** 轮询间隔。见类注释里为什么是粗粒度。 */
    private const val POLL_MS = 250L

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "dsh-bubble-cmd").apply { isDaemon = true }
    }

    private var task: ScheduledFuture<*>? = null

    /** `$DSH_HOME`，由 [start] 传入；未启动时为 null。 */
    @Volatile
    private var home: File? = null

    /**
     * 启动轮询。由 `EngineManager` 在引擎启动前调用一次（幂等）。
     *
     * @param dshHome `$DSH_HOME`（`<filesDir>/home/.dsh`）。不存在时仍然启动 ——
     *   目录会在引擎初始化时被创建，早启动只是多几次空轮询。
     */
    @Synchronized
    fun start(dshHome: File) {
        home = dshHome
        if (task != null) return
        task = scheduler.scheduleWithFixedDelay(::pollOnce, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS)
        Log.i(TAG, "悬浮球命令信箱已启动: ${File(dshHome, CMD_FILE).absolutePath}")
    }

    /** 停掉轮询（App 进程退出时其实无所谓，这里给测试/重启留一个干净的入口）。 */
    @Synchronized
    fun stop() {
        task?.cancel(false)
        task = null
    }

    /**
     * 读一次信箱。跑在 `dsh-bubble-cmd` 线程上，所以**不能**在这里碰视图 ——
     * [FloatingBubble.applyAppearance] 内部自己会切主线程。
     */
    private fun pollOnce() {
        val dir = home ?: return
        val cmd = File(dir, CMD_FILE)
        if (!cmd.isFile) return

        val text = try {
            cmd.readText()
        } catch (t: Throwable) {
            // 读失败（权限/被删）：下一轮再看，不刷日志
            return
        }
        val json = try {
            JSONObject(text)
        } catch (t: Throwable) {
            // ③：写到一半或坏了，下一轮再读
            return
        }

        val id = json.optLong("id", -1L)
        if (id < 0L) {
            // 没有 id 的请求无法配对回执，直接丢弃（否则会卡住一个永远等不到答案的工具调用）
            Log.w(TAG, "悬浮球命令缺少 id，已丢弃")
            cmd.delete()
            return
        }

        val action = json.optString("action", "set")
        val ok: Boolean
        val message: String
        var appearance = ""
        try {
            if (action == "get") {
                appearance = FloatingBubble.currentAppearanceJson()
                ok = appearance != "{}"
                message = if (ok) "" else "悬浮球尚未就绪（appContext 未初始化）"
            } else {
                // 合并语义在 applyAppearance 内部（它会把字段并到当前配置上），
                // 所以这里只管把 fields 序列化过去。
                val fields = json.optJSONObject("fields") ?: JSONObject()
                ok = FloatingBubble.applyAppearance(fields.toString())
                appearance = FloatingBubble.currentAppearanceJson()
                message = if (ok) "" else "悬浮球尚未就绪（appContext 未初始化）"
            }
        } catch (t: Throwable) {
            Log.w(TAG, "执行悬浮球命令失败: ${t.message}")
            writeResult(dir, id, false, "原生侧执行失败: ${t.message}", "")
            cmd.delete()
            return
        }

        // ②：先写回执，再删请求。反过来的话插件会超时。
        writeResult(dir, id, ok, message, appearance)
        cmd.delete()
    }

    /**
     * 写回执。失败**不重试** —— 插件会在超时后自己用 `action:"get"` 复查，
     * 而那正是我们想要的语义（宁可让模型多查一次，也不要它拿一个假成功）。
     */
    private fun writeResult(dir: File, id: Long, ok: Boolean, message: String, appearance: String) {
        val out = JSONObject().apply {
            put("id", id)
            put("ok", ok)
            put("message", message)
            put("appearance", appearance)
        }
        try {
            val target = File(dir, RESULT_FILE)
            // 原子发布：插件可能正好读到这里，半个 JSON 会让它那一轮白等。
            val tmp = File(dir, "$RESULT_FILE.tmp")
            tmp.writeText(out.toString())
            if (!tmp.renameTo(target)) {
                target.writeText(out.toString())
                tmp.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "写入悬浮球命令回执失败: ${t.message}")
        }
    }
}
