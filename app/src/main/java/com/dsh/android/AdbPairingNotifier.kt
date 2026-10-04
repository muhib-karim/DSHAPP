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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 无线调试配对的**通知栏输入**交互。
 *
 * ## 为什么配对码要用通知栏输入，而不是 App 里的输入框
 *
 * 配对码显示在**系统**的「使用配对码配对设备」界面上。用户要抄它，就必须离开
 * 我们的 App —— 而那个界面一离开（切走/返回），**配对端口随即消失**，回来再点
 * 配对就找不到端口了。
 *
 * 通知栏的 `RemoteInput`（直接回复）正好绕开这个死结：用户**不用切回 App**，
 * 在通知栏里直接把码敲进去。Shizuku 用的就是这个办法（`AdbPairingService`）。
 *
 * ## 交互时序
 *
 * 1. 用户先在系统里打开「开发者选项 → 无线调试 → 使用配对码配对设备」，**保持显示**；
 * 2. 用户回 App 的设置页点「配对」→ 本对象发一条通知；
 * 3. 后台发现 `_adb-tls-pairing._tcp` 的端口：找到就把通知换成带输入框的，
 *    没找到就提示用户先把那个界面打开（见 [start]）；
 * 4. 用户在通知栏回复 6 位码 → [AdbPairingReceiver] 收到 → 调
 *    [WirelessDebugging.pair]（走内置 adb 的 `adb pair`，SPAKE2 + TLS-PSK）；
 * 5. 结果写回通知。
 *
 * ⚠️ **配对码必须由用户输入，无法自动化**：SPAKE2 用那个码当共享密钥，
 * 设备端只显示给人看。这不是实现偷懒，是协议本身的要求。
 */
object AdbPairingNotifier {

    /** `RemoteInput` 的结果 key，发送端与接收端必须一致。 */
    const val EXTRA_REPLY = "com.dsh.android.pairing.code"

    /** 接收配对码的动作。 */
    const val ACTION_REPLY = "com.dsh.android.action.PAIRING_REPLY"

    /** 通知里带上配对端口，供接收端使用。 */
    const val EXTRA_PORT = "com.dsh.android.pairing.port"

    private const val CHANNEL_ID = "dsh-adb-pairing"
    private const val NOTIFICATION_ID = 1002

    /**
     * 发起配对：后台发现配对端口 → 发通知（找到端口则带输入框）。
     *
     * ⚠️ 立即返回，**不阻塞调用方**（发现最多 8 秒）。这是从 WebView 的 JS 桥
     * 直接调用的，绝不能卡住 JS 线程。
     */
    fun start(context: Context) {
        val app = context.applicationContext
        createChannel(app)
        DshApplication.applicationScope.launch {
            notify(app, app.getString(R.string.pairing_title), app.getString(R.string.pairing_searching), null)

            val port = withContext(Dispatchers.IO) { WirelessDebugging.discoverPairingPort(app) }
            if (port <= 0) {
                Log.w(DshApplication.TAG, "没发现配对端口，无线调试配对界面可能没打开")
                notify(app, app.getString(R.string.pairing_title), app.getString(R.string.pairing_no_port), null)
                return@launch
            }
            Log.i(DshApplication.TAG, "配对端口 $port，等待用户输入配对码")
            notify(app, app.getString(R.string.pairing_title), app.getString(R.string.pairing_waiting), port)
        }
    }

    /** 撤掉配对通知（用户在设置页取消、或配对已完成后调用）。 */
    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    /**
     * 收到配对码后执行配对。
     *
     * ⚠️ 由 [AdbPairingReceiver] 在 `goAsync()` 的后台线程里调用 ——
     * 发现 + 配对可能要十几秒，超过广播接收器的 10 秒上限。
     */
    internal fun pairWithCode(context: Context, port: Int, code: String) {
        val app = context.applicationContext
        notify(app, app.getString(R.string.pairing_title), app.getString(R.string.pairing_working), null)

        val manager = EngineManager.get(app)
        val result = manager.pairWirelessDebugging(code, port)
        Log.i(DshApplication.TAG, "配对结果 ok=${result.ok} msg=${result.message}")

        if (result.ok) {
            notify(app, app.getString(R.string.pairing_title), app.getString(R.string.pairing_ok), null)
        } else {
            // 失败时保留输入框让用户重试 —— 最常见的失败是码抄错，
            // 让用户重开一遍系统界面代价太大。
            notify(app, app.getString(R.string.pairing_failed_title), result.message, port)
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
     * @param port 配对端口；`null` 表示这条通知**不带输入框**（查找中 / 进行中 / 已完成）。
     */
    private fun notify(context: Context, title: String, text: String, port: Int?) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            // 配对是**一次性、有时效**的操作（端口会随系统界面关闭而消失），
            // 不该长期占着通知栏。
            .setAutoCancel(port == null)

        if (port != null) {
            builder.addAction(replyAction(context, port))
            builder.setOngoing(true)
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
}

/**
 * 接收用户在通知栏输入的配对码。
 *
 * ⚠️ 必须在 manifest 里声明（`exported="false"`）：通知可能在 App 进程已被回收时
 * 仍留在通知栏，动态注册的接收器那时收不到。
 */
class AdbPairingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AdbPairingNotifier.ACTION_REPLY) return

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
