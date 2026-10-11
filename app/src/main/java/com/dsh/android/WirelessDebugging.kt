package com.dsh.android

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 无线调试（Android 11+）的 Kotlin 侧支持：**端口发现**与**配对**。
 *
 * ## 为什么端口发现必须在 Kotlin 侧
 *
 * 无线调试的端口是随机高位端口，只能靠 mDNS 发现。而 Node 侧（`untrusted_app`
 * 域）**发不出 UDP 组播** —— 真机实测 `EPERM send EPERM 224.0.0.251:5353`。
 * `NsdManager` 是系统服务，请求经 Binder 交给系统域的 `mdnsd` 代发，不受该限制。
 *
 * 所以分工是：
 *
 * | 侧 | 做什么 |
 * |---|---|
 * | Kotlin（本文件） | `NsdManager` 发现端口 → 写 `$DSH_HOME/adb-wireless-port` |
 * | Node（`dsh-android-shell.mjs`） | 读端口文件 → 用内置 adb 连上去 |
 *
 * ⚠️ 真机实测（OPPO PHY110 / Android 15）：发现耗时 **81 毫秒**。
 * 这也是为什么**不扫端口** —— 最初的实现扫 32768–60999 要 20 秒，而端口每次
 * 重启/重开都会变，等于每条特权命令前都可能干等 20 秒。
 *
 * ## 为什么要常驻监听
 *
 * 端口会变（用户重开无线调试、重启、切换网络）。只在启动时发现一次的话，端口一变
 * 就永久失效，用户必须重启 App。所以引擎运行期间保持一个 `NsdManager` 监听，
 * 解析到新端口就更新文件 —— 监听本身由系统服务承担，开销可忽略。
 */
object WirelessDebugging {

    /** 端口文件名，相对 `$DSH_HOME`。Node 侧按同名读取。 */
    const val PORT_FILE = "adb-wireless-port"

    /** 切过去的固定 TCP 端口，与 `dsh-android-shell.mjs` 的 `PORT` 必须一致。 */
    const val DEFAULT_TCP_PORT = 5555

    /**
     * 启动自愈发现无线调试端口的**总预算**（ms）。
     *
     * 重启手机后 adbd 起得比一轮发现慢是常态，所以要允许多轮重试。
     * 取 15 秒：足够覆盖冷启动，又不会让启动线程长时间挂着（它本来就在后台线程）。
     */
    private const val TCP_PORT_DISCOVER_BUDGET_MS = 15_000L

    private const val TAG = DshApplication.TAG

    /** 常驻监听器；null 表示未启动。 */
    @Volatile
    private var watcher: AdbMdns? = null

    /**
     * 开始常驻监听 `_adb-tls-connect._tcp`，解析到端口就写进 [portFile]。
     *
     * 幂等：已在监听时直接返回。
     */
    fun startWatcher(context: Context, portFile: File) {
        if (watcher != null) return
        val mdns = AdbMdns(
            context = context,
            serviceType = AdbMdns.TLS_CONNECT,
            onFound = { port -> writePort(portFile, port) },
            // 服务消失（用户关了无线调试开关）→ 立刻清掉端口文件。
            // 不清的话设置页会一直拿着那个死端口显示绿灯。
            onLost = { writePort(portFile, -1) },
        )
        watcher = mdns
        mdns.start()
    }

    /** 停止常驻监听（引擎停止时调用，避免泄漏 listener 注册）。 */
    fun stopWatcher() {
        watcher?.stop()
        watcher = null
    }

    /**
     * 启动时自愈：5555 不在听、但无线调试可用 → 自动切一次固定端口。
     *
     * ## 为什么需要它
     *
     * `persist.adb.tcp.port` 写不进去（SELinux 拒绝），所以**重启手机后 5555 会消失**。
     * runner 里的「通道 1.5」只在模型调 `shell` 时才自愈 —— 用户打开设置页看到的是
     * 「身份变成 app 身份」，却不知道点一下「切到固定端口」就能修好。
     *
     * 这里在引擎启动时顺手补一次，让「重启后要手动点一下」这个前提消失。
     *
     * ⚠️ **阻塞调用**（最多约 10 秒：发现端口 + 切换 + 轮询就绪），必须在线程里调。
     * 失败不影响任何东西 —— legacy 通道、无线通道、app 身份都照旧。
     *
     * @return 是否真的切成功（已经是固定端口时也算成功）。
     */
    fun ensureTcpPort(
        context: Context,
        nodeBin: File,
        runner: File,
        adbHome: File,
        dshHome: File,
    ): Result {
        val portFile = File(dshHome, PORT_FILE)
        // 5555 已经在听就什么都不用做 —— 幂等，且避免每次启动都白跑一遍。
        if (isPortListening(DEFAULT_TCP_PORT)) {
            Log.i(TAG, "固定端口 $DEFAULT_TCP_PORT 已在监听，无需切换")
            return Result(true, "固定端口已在监听")
        }
        // ⚠️ **重试而不是只探一次。** 重启手机后 adbd 往往要好几秒才广播出
        //    `_adb-tls-connect._tcp`，而单次发现在 3 秒内落空是常态 —— 原来的
        //    「一次 3 秒、失败就放弃」等于让「重启后自动恢复」这条路径基本失效，
        //    用户看到的现象就是「重启后特权身份没了」。
        //    这里探 3 轮（每轮 3 秒，中间隔 2 秒），总预算约 13 秒，仍然远小于
        //    任何用户可感知的「卡住」。任一轮拿到端口就立刻往下走。
        val deadline = System.currentTimeMillis() + TCP_PORT_DISCOVER_BUDGET_MS
        var discovered = -1
        var attempt = 0
        while (discovered <= 0 && System.currentTimeMillis() < deadline) {
            attempt += 1
            discovered = discoverConnectPort(context, portFile, timeoutMs = 3000)
            if (discovered <= 0 && System.currentTimeMillis() < deadline) {
                Log.i(TAG, "第 $attempt 次未发现无线调试端口，2 秒后重试")
                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        if (discovered <= 0) {
            return Result(false, "无线调试不可用，跳过自动切换（已重试 $attempt 次）")
        }
        return switchToTcp(nodeBin, runner, adbHome, dshHome)
    }

    /** 本地回环端口是否有人监听（`bind` 失败 = 有人在听，与 [AdbMdns] 同判据）。 */
    private fun isPortListening(port: Int): Boolean = try {
        java.net.ServerSocket().use {
            it.bind(java.net.InetSocketAddress("127.0.0.1", port), 1)
            false
        }
    } catch (_: java.io.IOException) {
        true
    }

    /**
     * 一次性发现连接端口（设置页「重新检测」用）。
     *
     * @return 端口号；未发现给 -1。
     */
    fun discoverConnectPort(context: Context, portFile: File, timeoutMs: Long = 5000): Int {
        val port = AdbMdns.discover(context, AdbMdns.TLS_CONNECT, timeoutMs)
        // ⚠️ **没发现时不要动端口文件。** 一次「没探到」不等于「端口已失效」：
        //    无线调试刚配对/刚重启 adbd 时会短暂不可见，`NsdManager` 也常先失败
        //    一次再成功。原实现无条件 `writePort(port)`，超时就等于**删掉**常驻
        //    监听刚写好的好端口（见 writePort 的 `port <= 0` 分支），于是紧接着的
        //    补切拿不到端口 → 跳过 → 通道只剩无线调试 → 关 Wi-Fi 就断。
        //    真正确认失效的信号是 `AdbMdns.onServiceLost`，那条路径仍然会清文件。
        if (port > 0) writePort(portFile, port)
        else Log.i(TAG, "[mdns] 本次未发现连接端口（保留已有端口文件，等常驻监听更新）")
        return port
    }

    /**
     * 发现配对端口。
     *
     * ⚠️ **配对端口只在用户打开「使用配对码配对设备」那个界面时才存在**，
     * 界面一关端口就没了。所以顺序必须是：用户先打开那个界面 → 再在 App 里点配对。
     *
     * @return 端口号；未发现给 -1。
     */
    fun discoverPairingPort(context: Context, timeoutMs: Long = 8000): Int =
        AdbMdns.discover(context, AdbMdns.TLS_PAIRING, timeoutMs)

    /**
     * 执行配对：发现配对端口 → 调 runner 的 `--pair` 子命令。
     *
     * 配对走 SPAKE2 + TLS-PSK，Node 的 `tls` 模块实现不了，所以借内置 adb 的
     * `adb pair`（Google 自己的实现）。
     *
     * ⚠️ 这是个**阻塞**调用（发现最多 8 秒 + adb pair 若干秒），**不要在主线调用**。
     *
     * @param code 用户从系统「使用配对码配对设备」界面抄来的 6 位配对码。
     * @param knownPort 通知栏那条通知**已经带着**的配对端口（来自 `AdbMdns` 的发现）。
     *   传 `<= 0` 时本方法自己重新发现一次。
     *
     *   ⚠️ 为什么要让调用方把端口传进来：用户是在**通知栏**回复配对码的，而那个
     *   「使用配对码配对设备」界面在他回复时**多半已经切走或关掉了** —— 端口随之
     *   消失，重新发现必然落空，于是「明明已经找到端口、却报没找到端口」。
     *   通知里带下来的那个端口是**当时确实存在**的，直接用它成功率最高。
     * @return 可展示给用户的结果。
     */
    fun pair(
        context: Context,
        nodeBin: File,
        runner: File,
        adbHome: File,
        dshHome: File,
        code: String,
        knownPort: Int = -1,
    ): Result {
        if (!runner.isFile) return Result(false, "执行通道文件不存在：${runner.absolutePath}")
        if (!nodeBin.isFile) return Result(false, "Node 可执行文件不存在：${nodeBin.absolutePath}")

        // 优先用通知带下来的端口；没有再自己发现。
        val port = if (knownPort > 0) {
            Log.i(TAG, "使用通知携带的配对端口 $knownPort")
            knownPort
        } else {
            discoverPairingPort(context)
        }
        if (port <= 0) {
            return Result(
                false,
                "没找到配对端口。请先在系统「开发者选项 → 无线调试」里点开" +
                    "「使用配对码配对设备」，让那个界面保持显示，再回到这里点配对。",
            )
        }
        Log.i(TAG, "配对端口 $port，开始配对")

        val output = StringBuilder()
        val exit: Int = try {
            val pb = ProcessBuilder(
                nodeBin.absolutePath,
                runner.absolutePath,
                "--pair",
                port.toString(),
                code,
            )
            pb.redirectErrorStream(true)
            pb.environment().apply {
                put("DSH_HOME", dshHome.absolutePath)
                put("DSH_ADB_HOME", adbHome.absolutePath)
                // runner 只用内置 adb 与 Node 内置模块，PATH 给个最小可用集即可
                put("PATH", "/system/bin:/system/xbin")
                put("HOME", dshHome.parentFile?.absolutePath ?: dshHome.absolutePath)
            }
            val proc = pb.start()
            proc.inputStream.bufferedReader().forEachLine { line ->
                output.append(line).append('\n')
                Log.i(TAG, "[pair] $line")
            }
            proc.waitFor()
        } catch (t: Throwable) {
            Log.w(TAG, "配对失败", t)
            return Result(false, "启动配对进程失败：${t.message}")
        }

        val text = output.toString().trim()
        return if (exit == 0) {
            // ⚠️ 退出码 0 只代表**配对成功**，不代表通道可用。
            //
            //   配对成功后 runner 会立刻尝试切到固定端口 5555，而那一步**可能失败**
            //   （端口还没广播出来、无线调试刚好被关掉等，见 `switchToTcp()`）。
            //   失败时 runner 会往 stderr 打一句明确的警告，且因为 `redirectErrorStream`
            //   它就在 `text` 里。
            //
            //   必须把这两种结果**分开说**：只吞下 `exit == 0` 一律回「配对成功，特权身份
            //   已可用」，用户会看到通知栏宣告成功、而设置页重新检测却报检测不到 ——
            //   正是要根治的那个现象。诚实分流后，失败那种情况会直接告诉用户
            //   「还差一步，去点『切到固定端口』」。
            if (text.contains(SWITCH_TC_FAILED_MARK)) {
                Result(
                    true,
                    "配对成功，但还没切到固定端口 —— 现在只能靠无线调试，关掉 Wi-Fi 或" +
                        "无线调试开关后特权通道会失效。请回设置页点「切到固定端口」重试。",
                )
            } else {
                Result(true, "配对成功，ADB 密钥已被设备信任。")
            }
        } else {
            Result(false, pairingFailureReason(exit, text))
        }
    }

    /**
     * 把 adbd 切到固定的 TCP 端口（`adb tcpip 5555`）。
     *
     * ## 为什么需要它
     *
     * 无线调试的 TLS 端口**只在无线调试开关开着时才存在**，而且实测
     * **关掉开关会立刻掐断已建立的连接**（`adb_wifi_enabled=0` 之后已连上的
     * `_adb-tls-connect` 立刻变 `device offline`）。所以只靠无线调试的话，
     * 用户一关开关通道就断。
     *
     * `adb tcpip 5555` 把 adbd 重启到 TCP 模式，此后**关无线调试、关 Wi-Fi
     * 都不受影响**（走 127.0.0.1 回环）。实测 Android 15 上两者**可以并存** ——
     * `adb tcpip` 不会关闭无线调试，所以这是纯增益动作。
     *
     * ⚠️ 代价：`persist.adb.tcp.port` 实测为空，所以**重启手机后需要再切一次**。
     * 稳态下 `dsh-android-shell.mjs` 的通道级联会在发现 5555 不在听、而无线
     * 调试可用时自动重切，所以用户通常不用手动点。
     *
     * ⚠️ 阻塞调用，**不要在主线调用**。
     */
    fun switchToTcp(
        nodeBin: File,
        runner: File,
        adbHome: File,
        dshHome: File,
    ): Result {
        if (!runner.isFile) return Result(false, "执行通道文件不存在：${runner.absolutePath}")
        if (!nodeBin.isFile) return Result(false, "Node 可执行文件不存在：${nodeBin.absolutePath}")

        val output = StringBuilder()
        val exit: Int = try {
            val pb = ProcessBuilder(nodeBin.absolutePath, runner.absolutePath, "--switch-tcp")
            pb.redirectErrorStream(true)
            pb.environment().apply {
                put("DSH_HOME", dshHome.absolutePath)
                put("DSH_ADB_HOME", adbHome.absolutePath)
                put("PATH", "/system/bin:/system/xbin")
                put("HOME", dshHome.parentFile?.absolutePath ?: dshHome.absolutePath)
            }
            val proc = pb.start()
            proc.inputStream.bufferedReader().forEachLine { line ->
                output.append(line).append('\n')
                Log.i(TAG, "[switch-tcp] $line")
            }
            proc.waitFor()
        } catch (t: Throwable) {
            Log.w(TAG, "切换 TCP 模式失败", t)
            return Result(false, "启动切换进程失败：${t.message}")
        }

        return if (exit == 0) {
            Result(true, "已切到固定端口 5555 —— 现在关掉无线调试、关掉 Wi-Fi 都不影响特权通道。")
        } else {
            Result(false, "切换失败（退出码 $exit）。请确认无线调试开着，或先用电脑连一次。\n${output.toString().trim()}")
        }
    }

    /** 把 adb 的报错翻译成用户能照着做的说明。 */
    private fun pairingFailureReason(exit: Int, output: String): String = when {
        output.contains("Failed to parse pairing code") || output.contains("invalid pairing code") ->
            "配对码不对（必须是系统界面上显示的 6 位数字）。"
        output.contains("Connection refused") ->
            "配对端口连不上 —— 那个「使用配对码配对设备」的界面可能已经关了，请重新打开再试。"
        output.contains("already paired") ->
            "这台设备已经配过对了，可以直接用无线通道。"
        else ->
            "配对失败（退出码 $exit）。\n$output"
    }

    /**
     * runner 在「配对成功但没能切到固定端口」时打的 stderr 标记。
     *
     * ⚠️ **必须与 `assets/dsh-android-shell.mjs` 里那句话逐字对应。** 那边改文案
     * 而这边不改，判据就会静默失效 —— 于是又回到「通知栏宣告配对成功、界面却说
     * 检测不到」的状态，而且再也没有任何地方能提示原因。
     * （配对进程用 `redirectErrorStream(true)`，所以 stderr 就在 stdout 里。）
     */
    private const val SWITCH_TC_FAILED_MARK = "配对成功，但固定端口"

    /** 把端口写进文件；`port <= 0` 时**删除**文件（避免留下过期端口误导 Node 侧）。 */
    private fun writePort(portFile: File, port: Int) {
        try {
            if (port > 0) {
                portFile.parentFile?.mkdirs()
                portFile.writeText("$port\n")
                Log.i(TAG, "[mdns] 无线调试端口 $port 已写入 ${portFile.name}")
            } else if (portFile.exists()) {
                portFile.delete()
                Log.i(TAG, "[mdns] 无线调试端口不可用，已清除 ${portFile.name}")
            }
        } catch (t: Throwable) {
            // 写不进去只是无线通道不可用，legacy 与 app 身份都不受影响
            Log.w(TAG, "[mdns] 写端口文件失败: ${t.message}")
        }
    }

    /** 配对结果。 */
    data class Result(val ok: Boolean, val message: String)
}
