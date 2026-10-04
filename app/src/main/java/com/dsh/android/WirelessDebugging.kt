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
        val mdns = AdbMdns(context, AdbMdns.TLS_CONNECT) { port -> writePort(portFile, port) }
        watcher = mdns
        mdns.start()
    }

    /** 停止常驻监听（引擎停止时调用，避免泄漏 listener 注册）。 */
    fun stopWatcher() {
        watcher?.stop()
        watcher = null
    }

    /**
     * 一次性发现连接端口（设置页「重新检测」用）。
     *
     * @return 端口号；未发现给 -1。
     */
    fun discoverConnectPort(context: Context, portFile: File, timeoutMs: Long = 5000): Int {
        val port = AdbMdns.discover(context, AdbMdns.TLS_CONNECT, timeoutMs)
        writePort(portFile, port)
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
     * @return 可展示给用户的结果。
     */
    fun pair(
        context: Context,
        nodeBin: File,
        runner: File,
        adbHome: File,
        dshHome: File,
        code: String,
    ): Result {
        if (!runner.isFile) return Result(false, "执行通道文件不存在：${runner.absolutePath}")
        if (!nodeBin.isFile) return Result(false, "Node 可执行文件不存在：${nodeBin.absolutePath}")

        val port = discoverPairingPort(context)
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
            Result(true, "配对成功，ADB 密钥已被设备信任。")
        } else {
            Result(false, pairingFailureReason(exit, text))
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
