package com.dsh.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 无线调试配对的**通知栏输入**交互。
 *
 * ## 为什么配对码要用通知栏输入，而不是 App 里的输入框
 *
 * 配对码显示在**系统**的「使用配对码配对设备」界面上。用户要抄它，就必须离开
 * 我们的 App。而通知栏的 `RemoteInput`（直接回复）让用户**不用切回 App** 就能
 * 把码敲进去。Shizuku 用的就是这个办法（`AdbPairingService`）。
 *
 * ## 交互时序
 *
 * 1. 用户在设置页点「配对无线调试」；
 * 2. 本对象发一条**常驻**通知，提示去系统里打开「使用配对码配对设备」；
 * 3. 后台**一直**监听 `_adb-tls-pairing._tcp`，直到用户取消 —— 见下面那条 ⚠️；
 * 4. 端口一出现就把通知换成带输入框的，用户直接在通知栏回复 6 位码；
 * 5. [AdbPairingReceiver] 收到 → 调 [WirelessDebugging.pair]（内置 adb 的
 *    `adb pair`，SPAKE2 + TLS-PSK）→ 结果写回通知。
 *
 * ## ⚠️ 为什么必须「一直监听」，而不是「发现一次，超时就放弃」
 *
 * 这是**真机反馈修掉的缺陷**：原实现是「点配对 → 一次性发现 8 秒 → 没找到就报
 * 没找到」。但用户此刻**正在系统设置里翻找**那个无线调试界面 —— 翻到「开发者选项」
 * 再进「无线调试」再点「使用配对码配对设备」，远超 8 秒。于是：
 *
 * ```
 * 用户刚找到那个按钮 → 通知栏已经显示「没找到端口」→ 只能回 App 重按一次配对
 * ```
 *
 * 而重按一次**还是同样的结果**，因为端口本来就还没出现。这个来回毫无意义。
 *
 * 现在改成：注册**一次**常驻 mDNS 发现，端口一出现立刻反应；用户开着那个界面多久
 * 都行。**唯一的结束条件是用户主动取消**（设置页的「取消配对」，或通知上的取消）。
 *
 * ⚠️ **配对码必须由用户输入，无法自动化**：SPAKE2 用那个码当共享密钥，
 * 设备端只显示给人看。这不是实现偷懒，是协议本身的要求。
 */
object AdbPairingNotifier {

    /** `RemoteInput` 的结果 key，发送端与接收端必须一致。 */
    const val EXTRA_REPLY = "com.dsh.android.pairing.code"

    /** 接收配对码的动作。 */
    const val ACTION_REPLY = "com.dsh.android.action.PAIRING_REPLY"

    /** 取消配对的动作（通知栏上的取消按钮）。 */
    const val ACTION_CANCEL = "com.dsh.android.action.PAIRING_CANCEL"

    /** 通知里带上配对端口，供接收端使用。 */
    const val EXTRA_PORT = "com.dsh.android.pairing.port"

    private const val CHANNEL_ID = "dsh-adb-pairing"
    private const val NOTIFICATION_ID = 1002

    /** 端口轮询间隔。发现是异步的，300ms 足够及时又不费电。 */
    private const val POLL_INTERVAL_MS = 300L

    /**
     * 当前常驻监听任务；null 表示没有在等。
     *
     * ⚠️ 它是**唯一**的结束开关：只有 [cancel]（用户主动取消）与配对成功会清掉它。
     * 不要给它加超时 —— 那正是本次要修掉的缺陷。
     */
    @Volatile
    private var watchJob: Job? = null

    /**
     * 发起配对：发常驻通知 + 后台一直监听配对端口，直到用户取消。
     *
     * ⚠️ 立即返回，**不阻塞调用方**。这是从 WebView 的 JS 桥直接调用的，
     * 绝不能卡住 JS 线程。
     */
    fun start(context: Context) {
        val app = context.applicationContext
        createChannel(app)
        // 幂等：用户可能连点几次。已经在等就不要再起一个 —— 两个任务会互相
        // 覆盖通知，取消时也只能停掉其中一个，留下一个永远退不出的监听。
        if (watchJob?.isActive == true) {
            Log.i(DshApplication.TAG, "配对监听已在进行中，忽略重复请求")
            return
        }
        watchJob = DshApplication.applicationScope.launch {
            runWatch(app)
        }
    }

    /**
     * 常驻监听配对端口，直到被取消。
     *
     * 只在**端口发生变化**时重发通知（而不是每 300ms 刷一次），避免通知栏闪烁。
     */
    private suspend fun runWatch(app: Context) {
        notify(app, app.getString(R.string.pairing_title), app.getString(R.string.pairing_waiting_open), null, true)

        // ⚠️ 用**一个常驻的** AdbMdns 实例，而不是反复调 `AdbMdns.discover(timeout)`：
        //   后者每次都要重新注册 discovery（`NsdManager` 同一 listener 不能重复注册，
        //   所以每次都新建对象），中间存在「上一次已停、下一次未注册」的空窗期，
        //   端口恰好在空窗期出现就会错过，要再等一轮。常驻注册没有空窗。
        var mdns = AdbMdns(app, AdbMdns.TLS_PAIRING)
        mdns.start()

        var lastPort = -1
        try {
            while (currentCoroutineContext().isActive) {
                val port = mdns.port
                if (port > 0 && port != lastPort) {
                    lastPort = port
                    Log.i(DshApplication.TAG, "配对端口 $port，等待用户输入配对码")
                    notify(
                        app,
                        app.getString(R.string.pairing_title),
                        app.getString(R.string.pairing_waiting),
                        port,
                        true,
                    )
                }
                // 看门狗：`NsdManager` 在系统重启 mdnsd 等情况下会回调
                // onStartDiscoveryFailed 把发现静默掐掉。常驻等待必须能自己恢复，
                // 否则用户按提示开了无线调试，这边却早已不再监听。
                if (!mdns.isRunning) {
                    Log.i(DshApplication.TAG, "[mdns] 配对发现已停止，重建监听")
                    mdns.stop()
                    mdns = AdbMdns(app, AdbMdns.TLS_PAIRING)
                    mdns.start()
                }
                delay(POLL_INTERVAL_MS)
            }
        } finally {
            mdns.stop()
        }
    }

    /**
     * 停止监听并撤掉配对通知（用户在设置页点「取消配对」，或配对已成功）。
     *
     * ⚠️ 这是**唯一**能让常驻监听停下来的用户动作（另加配对成功）。
     */
    fun cancel(context: Context) {
        stopWatch()
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    /** 只停监听，不动通知（配对成功后要留着那条成功提示）。 */
    private fun stopWatch() {
        watchJob?.cancel()
        watchJob = null
    }

    /**
     * 收到配对码后执行配对。
     *
     * ⚠️ 由 [AdbPairingReceiver] 在 `goAsync()` 的后台线程里调用 ——
     * 发现 + 配对可能要十几秒，超过广播接收器的 10 秒上限。
     */
    internal fun pairWithCode(context: Context, port: Int, code: String) {
        val app = context.applicationContext
        notify(app, app.getString(R.string.pairing_title), app.getString(R.string.pairing_working), null, true)

        val manager = EngineManager.get(app)
        val result = manager.pairWirelessDebugging(code, port)
        Log.i(DshApplication.TAG, "配对结果 ok=${result.ok} msg=${result.message}")

        if (result.ok) {
            // 成功了就没什么可等的了。停掉监听，否则那条常驻通知会一直挂着。
            stopWatch()
            notify(app, app.getString(R.string.pairing_title), app.getString(R.string.pairing_ok), null, false)
        } else {
            // 失败时保留输入框让用户重试 —— 最常见的失败是码抄错，
            // 让用户重开一遍系统界面代价太大。
            //
            // ⚠️ 监听本身**不停**：那个「使用配对码配对设备」界面可能还开着，
            //   端口还在，用户改一位数字重发就行。
            notify(app, app.getString(R.string.pairing_failed_title), result.message, port, true)
        }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        // ⚠️ 必须 HIGH：配对是**需要用户立刻操作**的一次性交互，
        //    用引擎那条 IMPORTANCE_LOW 的渠道会静默躺在通知栏里没人看见。
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.pairing_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.pairing_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * 发/更新配对通知。
     *
     * @param port 配对端口；非 null 时带输入框。
     * @param ongoing 是否常驻（划不掉）。等待端口与等待输入码两种状态都必须常驻 ——
     *   前者是「正在等你操作」的指引，被划掉用户就不知道该去哪开无线调试了；
     *   后者更不用说。终态（成功）才让它自己走掉。
     */
    private fun notify(context: Context, title: String, text: String, port: Int?, ongoing: Boolean) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setAutoCancel(!ongoing)

        if (port != null) {
            builder.addAction(replyAction(context, port))
        }
        if (ongoing) {
            builder.setOngoing(true)
            // ⚠️ 常驻通知**划不掉**，所以必须给一个取消出口：万一进程被系统回收，
            //   监听已经没了，那条通知却还挂着，用户就只能去强制停止应用。
            builder.addAction(cancelAction(context))
        }

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        } catch (t: Throwable) {
            // 通知发不出去不该让配对流程崩掉（Android 13+ 未授权通知权限时会抛）
            Log.w(DshApplication.TAG, "配对通知发送失败: ${t.message}")
        }
    }

    /** 带「直接回复」输入框的通知动作。 */
    private fun replyAction(context: Context, port: Int): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(EXTRA_REPLY)
            .setLabel(context.getString(R.string.pairing_input_label))
            .build()

        val intent = Intent(context, AdbPairingReceiver::class.java).apply {
            action = ACTION_REPLY
            putExtra(EXTRA_PORT, port)
        }
        // ⚠️ **必须 MUTABLE**：RemoteInput 的结果是系统在投递时**写进 Intent** 的，
        //    immutable 的 PendingIntent 会让系统无法回填，表现是「输入框能打字，
        //    但回复后什么都没发生」。API 31 起这是硬性要求。
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val pending = PendingIntent.getBroadcast(context, port, intent, flags)

        return NotificationCompat.Action.Builder(
            0,
            context.getString(R.string.pairing_reply),
            pending,
        ).addRemoteInput(remoteInput).build()
    }

    /** 「取消」动作：停掉常驻监听并撤掉通知。 */
    private fun cancelAction(context: Context): NotificationCompat.Action {
        val intent = Intent(context, AdbPairingReceiver::class.java).apply {
            action = ACTION_CANCEL
        }
        val pending = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0),
        )
        return NotificationCompat.Action.Builder(0, context.getString(R.string.pairing_stop), pending).build()
    }
}

/**
 * 接收用户在通知栏输入的配对码，以及通知上的取消动作。
 *
 * ⚠️ 必须在 manifest 里声明（`exported="false"`）：通知可能在 App 进程已被回收时
 * 仍留在通知栏，动态注册的接收器那时收不到。
 */
class AdbPairingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AdbPairingNotifier.ACTION_CANCEL -> {
                Log.i(DshApplication.TAG, "用户在通知栏取消了配对")
                AdbPairingNotifier.cancel(context)
                return
            }
            AdbPairingNotifier.ACTION_REPLY -> Unit
            else -> return
        }

        val results = RemoteInput.getResultsFromIntent(intent)
        val code = results?.getCharSequence(AdbPairingNotifier.EXTRA_REPLY)?.toString()?.trim().orEmpty()
        val port = intent.getIntExtra(AdbPairingNotifier.EXTRA_PORT, -1)

        if (code.isEmpty()) {
            Log.w(DshApplication.TAG, "配对码为空，忽略这次回复")
            return
        }
        if (port <= 0) {
            Log.w(DshApplication.TAG, "配对通知没带端口，无法配对")
            return
        }

        // ⚠️ 配对要十几秒（发现端口 + SPAKE2 握手 + adb 启动），远超广播接收器
        //    的 10 秒上限。不 goAsync() 的话进程会被判为 ANR。
        val pending = goAsync()
        Thread {
            try {
                AdbPairingNotifier.pairWithCode(context, port, code)
            } catch (t: Throwable) {
                Log.w(DshApplication.TAG, "配对流程异常", t)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
