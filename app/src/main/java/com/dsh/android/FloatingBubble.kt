package com.dsh.android

import android.annotation.SuppressLint
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 悬浮气泡：App 退到后台时浮在别的应用之上，让用户看得到 AI 在干什么、并能继续对话。
 *
 * ## 为什么需要
 *
 * 设备自动化场景下（让 AI 去操作微信、设置、别的 App），用户**必须离开本 App**，
 * 于是官方界面看不见了 —— 既不知道 AI 跑到哪一步，也没法中途喊停或补一句话。
 * 通知栏能显示状态，但点一下就跳走，没法交互。
 *
 * ## 形态（三种，共用一个 window，靠 visibility 切换，不重建 View）
 *
 * | 形态 | 触发 | 内容 |
 * |---|---|---|
 * | **收起** | 默认 | 球（尺寸/形状/底色/头像由插件配置）+ 状态圆点 |
 * | **贴边** | 把球**往屏幕外拖**后松手 | 只剩一条薄片贴在那一侧的边缘 |
 * | **展开** | 点球 / 点贴边薄片 | 可拖动、可缩放的面板，含**完整会话转录** + 输入框 |
 *
 * ## 本轮升级的五件事（用户明确要求）
 *
 * ① **状态横条无变化 2 秒后自动消失** —— 见 [scheduleStatusAutoHide]。原来是常驻的，
 *    只要 AI 在跑就一直挂着，屏幕上永远有一块东西。现在只有**内容真的变化**才
 *    重新计时，2 秒内没有新内容就淡出。
 *
 * ② **拖出屏幕自动贴边折叠** —— 见 [enterEdge] / [onTouchUp]。判定用**未经夹取的
 *    原始坐标**（`rawX`），因为夹取逻辑会把越界量吃掉，用夹取后的值永远判不出来。
 *
 * ③ **展开显示完整转录** —— 见 [applyTranscript]。原实现只有一个 TextView 显示
 *    「最后一条助手正文的前 400 字」，用户在自己的悬浮窗里**看不到自己发过什么、
 *    也看不到 AI 调了哪些工具**。现在由 [ChatTranscript] 驱动逐条渲染：用户消息、
 *    助手完整回复、每一次工具调用、错误，全都在。
 *
 * ④ **面板可拖动、可缩放** —— 拖动走顶栏（[R.id.panelHeader]），缩放走底部手柄
 *    （[R.id.panelResize]）。位置与尺寸都落盘，下次开还是那样。
 *
 * ⑤ **插件可改外观** —— 见 [applyAppearance] 与 [BubbleAppearance]。任何页面内的
 *    插件都能 `window.DSHAndroid.bubbleApply('{"shape":"square","size":72}')`
 *    即时改变悬浮球，配置落盘、重启仍在。
 *
 * ## 三个必须知道的平台事实
 *
 * ### ① 权限是特殊的，拿不到就没救
 *
 * `SYSTEM_ALERT_WINDOW` 不能运行时请求。未授权时 `WindowManager.addView` 抛
 * `BadTokenException: permission denied for window type 2038`。所以 [show] 先查
 * [canDrawOverlays]，不满足就直接放弃（由设置页的引导卡负责引导用户去开）。
 *
 * ### ② 输入框要用 TYPE_APPLICATION_OVERLAY，且展开态不能带 FLAG_NOT_FOCUSABLE
 *
 * 悬浮窗默认**抢不到输入法焦点**：`FLAG_NOT_FOCUSABLE` 让整个 window 收不到按键，
 * 而带上它之后 `EditText` 又永远弹不出键盘。正确组合是：
 *
 * ```
 * type    = TYPE_APPLICATION_OVERLAY (API 26+) / TYPE_PHONE (更低)
 * flags   = FLAG_NOT_TOUCH_MODAL + 展开态去掉 NOT_FOCUSABLE   ← 见 applyFocusFlags
 * ```
 *
 * ⚠️ 展开态**不能**设 `FLAG_ALT_FOCUSABLE_IM` —— 那正是键盘弹不出来的原因
 *   （该标志的语义是「把 NOT_FOCUSABLE 对输入法的含义取反」）。详见 [applyFocusFlags]。
 *
 * ### ③ 拖动必须用绝对坐标，不能用 getRawX 的差值
 *
 * `WindowManager.updateViewLayout` 的 x/y 是相对**屏幕**的左上角，而 `getRawX()`
 * 是相对屏幕的触点。用 `rawX - downRawX` 得到的是位移量，必须**累加到按下时的
 * window 坐标**上。直接拿 `rawX` 当 x 会让气泡瞬移到手指位置，这是最容易踩的坑。
 *
 * ## 状态来源
 *
 * 两条通道，都由 `client.js` 推送：
 *
 * | 通道 | 入口 | 消费者 |
 * |---|---|---|
 * | [EngineStatus]（≤400 字，高频） | `mpReportStatus()` → [onStatus] | 通知栏 + 状态横条 |
 * | [ChatTranscript]（完整，变化才推） | `mpReportTranscript()` → [onTranscript] | 展开面板 |
 *
 * 实测 App 退到后台后 WebView 的 `setInterval` **照常运行**（按 HOME 后 12 秒走满
 * 12 拍），所以两条都是实时的，不需要另建引擎订阅。
 */
class FloatingBubble(private val context: Context) {

    companion object {
        private const val TAG = DshApplication.TAG

        /** 状态横条自动消失的默认时长（ms）。插件可用 `statusAutoHideMs` 覆盖。 */
        private const val STATUS_AUTO_HIDE_MS = 2000

        /**
         * AI 推送的宽限期（ms）。
         *
         * 推送自己的停留计时器比横条的自动消失计时器**晚这么久**才触发清理：
         * 两者如果同时到期，谁先跑是不确定的 —— 若清理先跑，它会用引擎状态覆盖
         * 刚推送的内容，用户看到的是"推送闪了一下就变回工具调用状态"。
         * 晚一点清，保证推送完整停留。
         */
        private const val STATUS_PUSH_GRACE_MS = 400L

        /**
         * 贴边态薄片的宽度（dp）。
         *
         * 取 6：够窄（不挡下层应用）、够宽（手指点得到）。实测 4dp 在 xxhdpi 上
         * 只有 12px，容易点不中。
         */
        private const val EDGE_WIDTH_DP = 6

        /**
         * 贴边后距屏幕边缘的留白（dp）。
         *
         * 取 0：用户拖动时本来就会自己找舒服的位置，强制留白反而"够不到边"。
         * 但**竖直方向**会夹在 [TOP_MARGIN_DP] 与状态栏之下，避免压住状态栏。
         */
        private const val EDGE_MARGIN_DP = 0

        /** 顶部留白（dp）：不让气泡压住状态栏（那里的时间和电量用户要常看）。 */
        private const val TOP_MARGIN_DP = 8

        /** 底部留白（dp）：避开手势条 / 导航栏。 */
        private const val BOTTOM_MARGIN_DP = 48

        /** 判定"点击"而非"拖动"的位移阈值（dp）。与系统 ViewConfiguration 同量级。 */
        private const val TAP_SLOP_DP = 6

        /**
         * 判定"拖出屏幕"的越界阈值（dp）。
         *
         * 取球宽的一半：**明显往外拖**才触发贴边。阈值太小会让正常的边缘吸附
         * 误判成贴边（用户只是想把球放到边上，结果球变成了一条缝）。
         */
        private const val EDGE_OVERSHOOT_DP = 28

        /** 转录区最小高度（dp）。再矮就看不见内容了。 */
        private const val TRANSCRIPT_MIN_DP = 72

        /** 面板最小宽度（dp）。 */
        private const val PANEL_MIN_WIDTH_DP = 180

        /** 位置与外观持久化。都是小整数/小 JSON，不值得上 DataStore。 */
        private const val PREFS = BubbleAppearance.PREFS
        private const val KEY_X = "x"
        private const val KEY_Y = "y"

        /** 展开面板的宽度（px）落盘 key；0/缺失表示用外观配置里的值。 */
        private const val KEY_PANEL_W = "panel_w"

        /** 转录区高度（px）落盘 key。 */
        private const val KEY_TRANSCRIPT_H = "transcript_h"

        /**
         * 气泡是否应当显示。
         *
         * 由 `MainActivity` 在 `onStart`/`onStop` 里设置 —— 只有**退到后台**才显示，
         * 前台时 App 内已有完整界面，气泡纯属碍事。
         *
         * ⚠️ 例外：插件把 `state` 设成 `expanded` / `collapsed` 时，**前台也显示**。
         *   那是插件在明确要求「把这个悬浮窗摆出来」，如果前台一律不收，插件就没法
         *   让用户看到自己改的效果 —— 而"让 AI 改悬浮窗"正是这条通道存在的理由。
         *   默认的 `auto` 保持既有行为（只在后台显示）。
         *
         * ⚠️ 做成进程级单例而不是每个 Activity 一份：`MainActivity` 转屏会重建，
         *   但气泡应当连续存在，不该闪一下。
         */
        @Volatile
        private var instance: FloatingBubble? = null

        /**
         * application context。
         *
         * ⚠️ 必须自己存一份：插件可能在**没有 Activity** 的时候下发配置
         *   （App 在后台、Activity 已销毁），而那时只有 [instance] 里的 context 可用，
         *   但 instance 可能为 null。存 application context 不泄漏。
         */
        @Volatile
        private var appContext: Context? = null

        /**
         * 用户是否在设置里开了气泡。默认**关**。
         *
         * 默认关的理由：它需要用户去系统设置里授权悬浮窗，没授权时开了也没用，
         * 不如让用户主动开一次、顺带走完授权流程。
         */
        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean("enabled", enabled).apply()
            Log.i(TAG, "悬浮气泡开关: $enabled")
        }

        /** 系统是否已授予「显示在其他应用上层」。 */
        fun canDrawOverlays(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context)
            else true

        /**
         * 由 `MainActivity.onCreate` 调用一次，把 application context 记下来。
         *
         * ⚠️ **必须有这个入口，不能只在 [onAppBackground] 里赋值。** 真机实测踩到过：
         *   用户在前台（气泡还没显示过）时插件下发配置，[applyAppearance] 拿到的
         *   `appContext` 是 null ⇒ 直接 `return false`，配置**静默丢弃**。
         *   症状是「插件调了 bubbleApply，返回 false，什么也没发生」——
         *   而 App 一切到后台就又能用了，看起来像随机失效。
         */
        fun attach(context: Context) {
            val ctx = context.applicationContext
            appContext = ctx
            // 插件上次把形态设成了 expanded / collapsed（不是 auto）时，重启后也要
            // 摆出来 —— 否则「重启 App 后依然生效」这句承诺就只对外观成立、对形态
            // 不成立。`auto` 走 else 分支什么都不做，保持既有行为。
            main.post { reconcileVisibility(ctx, BubbleAppearance.load(ctx)) }
        }

        /** App 退到后台 → 显示气泡（前提：用户开了开关且已授权）。 */
        fun onAppBackground(context: Context) {
            if (!isEnabled(context)) return
            if (!canDrawOverlays(context)) {
                Log.i(TAG, "气泡开关已开但未授权悬浮窗，不显示")
                return
            }
            val ctx = context.applicationContext
            appContext = ctx
            if (BubbleAppearance.load(ctx).state == BubbleAppearance.STATE_HIDDEN) {
                Log.i(TAG, "插件要求隐藏悬浮球，不显示")
                return
            }
            main.post {
                if (instance?.isShown == true) return@post
                // 半路残留的实例（addView 失败、或转屏把 Activity 拆了）先收干净，
                // 否则旧 window 会一直挂在 WindowManager 上，用户看得见却点不动。
                instance?.hide()
                instance = FloatingBubble(ctx).also { it.show() }
            }
        }

        /**
         * App 回到前台 → 收起气泡。
         *
         * ⚠️ 插件显式指定了形态（`state != auto`）时**不收** —— 见 [instance] 的说明。
         */
        fun onAppForeground() {
            val ctx = appContext ?: return
            if (BubbleAppearance.load(ctx).state != BubbleAppearance.STATE_AUTO) {
                Log.i(TAG, "插件已指定悬浮球形态，回到前台也不收起")
                return
            }
            main.post {
                instance?.hide()
                instance = null
            }
        }

        /** 设置页关掉开关时立即生效，不用等下次切后台。 */
        fun hideNow() = onAppForeground()

        /**
         * 状态更新入口。由 `EngineService.updateStatus()` 顺带调用。
         *
         * ⚠️ 可能在**任意线程**被调用（JS 桥线程），内部自己切主线程。
         */
        fun onStatus(status: EngineStatus) {
            val cur = instance ?: return
            main.post { cur.applyStatus(status) }
        }

        /**
         * 会话转录更新入口。由页面侧 `mpReportTranscript()` 推送。
         *
         * ⚠️ 与 [onStatus] 一样可能在任意线程被调用（JS 桥线程），内部切主线程。
         */
        fun onTranscript(json: String) {
            val cur = instance ?: return
            val transcript = ChatTranscript.parse(json) ?: return
            main.post { cur.applyTranscript(transcript) }
        }

        /**
         * 插件下发外观配置。**这是「让 AI 改悬浮窗」的唯一入口。**
         *
         * @param json 见 [BubbleAppearance] 的字段表；未知字段忽略、非法值退回原值。
         * @return 是否已接受（不代表视图已刷新 —— 那是下一帧的事）。
         */
        fun applyAppearance(json: String): Boolean {
            val ctx = appContext ?: return false
            val next = BubbleAppearance.merge(json, BubbleAppearance.load(ctx), ctx)
            // 自定义头像单独落盘：base64 有几百 KB，不适合进 SharedPreferences。
            //
            // ⚠️ **落盘后必须把内存里的 data URI 换成文件标记。** 视图判定用的是
            //   `usesCustomAvatar`（即 `avatar == AVATAR_FILE`），data URI 不匹配 ⇒
            //   下发自定义头像的那一帧**不显示**它，要等下次 `load()` 才生效，
            //   表现为「AI 换了头像但没反应，重启才看见」。
            //   换掉还能顺带避免几百 KB 的字符串一直挂在实例上。
            var merged = next
            if (next.avatar.startsWith("data:image/")) {
                merged = if (BubbleAppearance.writeAvatar(ctx, next.avatar) != null) {
                    next.copy(avatar = BubbleAppearance.AVATAR_FILE)
                } else {
                    // 图不是有效位图（writeAvatar 已记日志）：只把头像退回改之前的值，
                    // 不能留一个指向不存在文件的引用 —— 那会让球变成一块空白。
                    next.copy(avatar = BubbleAppearance.load(ctx).avatar)
                }
            }
            BubbleAppearance.save(ctx, merged)
            Log.i(TAG, "插件下发悬浮球外观: state=${merged.state} shape=${merged.shape} size=${merged.size}")
            main.post {
                instance?.applyAppearance(merged)
                reconcileVisibility(ctx, merged)
            }
            return true
        }

        /**
         * AI 主动往状态气泡推一条消息。
         *
         * ⚠️ **不改持久配置**：这是"说一句话"，不是"改外观"。所以它不走
         *   `applyAppearance`、不落 SharedPreferences —— 否则下次启动气泡上会
         *   挂着上次推送的旧话。
         *
         * ⚠️ 气泡没显示时返回 false 而不是先把它显示出来：`state:"hidden"` 是
         *   插件明确要求"别出现"，为一条推送打破它是越权。
         *
         * @return 是否已推给一个正在显示的悬浮球。
         */
        fun pushStatusToActive(text: String, durationMs: Long, sticky: Boolean): Boolean {
            val cur = instance ?: return false
            main.post { cur.pushStatus(text, durationMs, sticky) }
            return true
        }

        /** 读回当前外观配置（JSON 文本）。插件据此做「读取-修改-写回」。 */        fun currentAppearanceJson(): String {
            val ctx = appContext ?: return "{}"
            val a = BubbleAppearance.load(ctx)
            return JSONObject().apply {
                put("state", a.state)
                put("shape", a.shape)
                // SVG 形状的 path 可能上千字符，读回时截断没意义（插件要靠它判断
                // "现在是不是同一个形状"），所以原样给。上限由 merge 侧把关。
                put("shapePath", a.shapePath)
                put("shapeViewBox", a.shapeViewBox)
                put("size", a.size)
                put("cornerRadius", a.cornerRadius)
                put("opacity", a.opacity.toDouble())
                put("bgOpacity", a.bgOpacity.toDouble())
                put("avatarOpacity", a.avatarOpacity.toDouble())
                put("avatarScale", a.avatarScale.toDouble())
                put("bg", a.bg)
                put("bg2", a.bg2)
                put("border", a.border)
                put("borderWidth", a.borderWidth)
                put("avatar", if (a.usesCustomAvatar) "custom" else a.avatar)
                put("frameCount", a.frameCount)
                put("frameIntervalMs", a.frameIntervalMs)
                put("frameLoop", a.frameLoop)
                put("spriteRows", a.spriteRows)
                put("spriteCols", a.spriteCols)
                put("spin", a.spin)
                put("pulse", a.pulse)
                put("panelWidth", a.panelWidth)
                put("panelHeight", a.panelHeight)
                put("panelBg", a.panelBg)
                put("panelText", a.panelText)
                put("panelAccent", a.panelAccent)
                put("statusAutoHideMs", a.statusAutoHideMs)
                put("edgeSnap", a.edgeSnap)
            }.toString()
        }

        /**
         * 按插件要求的形态决定要不要显示/隐藏窗口。
         *
         * 只在 [applyAppearance] 之后调 —— 它的语义是「配置变了，把窗口状态跟上去」。
         */
        private fun reconcileVisibility(ctx: Context, a: BubbleAppearance) {
            if (!isEnabled(ctx) || !canDrawOverlays(ctx)) return
            when (a.state) {
                BubbleAppearance.STATE_HIDDEN -> {
                    instance?.hide()
                    instance = null
                }
                BubbleAppearance.STATE_EXPANDED -> {
                    if (instance?.isShown != true) {
                        instance?.hide()
                        instance = FloatingBubble(ctx).also { it.show() }
                    }
                    instance?.expand()
                }
                BubbleAppearance.STATE_COLLAPSED -> {
                    if (instance?.isShown != true) {
                        instance?.hide()
                        instance = FloatingBubble(ctx).also { it.show() }
                    }
                    instance?.collapse()
                }
                else -> Unit
            }
        }

        /**
         * 发送文本的回调。由 `MainActivity` 注入 —— 气泡自己拿不到 WebView。
         *
         * 返回 true 表示已投递（不代表 AI 已回复）。
         */
        @Volatile
        var sendText: ((String) -> Boolean)? = null

        /**
         * 中断当前这一轮的回调。由 `MainActivity` 注入。
         *
         * 返回 true 表示已投递。页面侧会先验「主按钮此刻是不是停止键」，
         * 不是的话拒绝执行 —— 绝不能把用户的草稿误发出去。
         */
        @Volatile
        var stopRun: (() -> Boolean)? = null

        /** 打开 App 的回调（气泡面板上那个「打开」）。 */
        @Volatile
        var openApp: (() -> Unit)? = null

        /** 主线程 Handler，所有对 View / window 的操作都走它。 */
        private val main = Handler(Looper.getMainLooper())

        /** 球的默认底色（与 `bubble_circle_bg.xml` 一致）。 */
        const val DEFAULT_BALL_BG = 0xFFFFFFFF.toInt()

        /** 默认描边（8% 黑，与 XML 里的 `#14000000` 一致）。 */
        const val DEFAULT_BORDER = 0x14000000

        /** 默认面板底色。 */
        const val DEFAULT_PANEL_BG = 0xFFFFFFFF.toInt()

        /** 默认正文色。 */
        const val DEFAULT_TEXT = 0xFF1A1D21.toInt()

        /** 默认强调色（与「发送」按钮的 `#3B6FE0` 一致）。 */
        const val DEFAULT_ACCENT = 0xFF3B6FE0.toInt()

        /** 运行中状态点的颜色（原实心绿）。 */
        const val DEFAULT_RUNNING = 0xFF34C759.toInt()
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var root: View? = null
    private var circleView: View? = null
    private var avatarView: ImageView? = null
    private var panelView: View? = null
    private var dotView: View? = null
    private var panelDot: View? = null
    private var panelAction: TextView? = null
    private var panelTitle: TextView? = null
    private var panelScroll: ScrollView? = null
    private var panelTranscript: LinearLayout? = null
    private var panelInput: EditText? = null
    private var panelStop: View? = null
    private var panelHeader: View? = null
    private var panelResize: View? = null
    private var panelSend: TextView? = null

    /** 贴边态薄片与它上面的小点。 */
    private var edgeView: View? = null
    private var edgeDot: View? = null

    /**
     * 轮播播放器的当前帧下标。
     *
     * ⚠️ 跨 `applyAppearance()` 保留：AI 只改了个颜色时不该让轮播从头开始 ——
     *   那在用户眼里是"球闪了一下"。
     */
    private var frameIndex = 0

    /** 轮播已完成的循环数，用于 `frameLoop`。 */
    private var frameLoops = 0

    /** 下一次切帧的 Runnable（用来取消）。null = 没有在跑。 */
    private var frameRunnable: Runnable? = null

    /** 旋转动画是否已在跑（避免重复 start）。 */
    private var spinning = false

    /** 呼吸动画是否已在跑。 */
    private var pulsing = false

    /** 正在跑的旋转动画；null = 没在跑。改参数时要先 cancel 它。 */
    private var spinAnimator: ObjectAnimator? = null

    /** 当前旋转动画的每圈时长与终值，用来判断"配置真的变了没"。 */
    private var spinDuration = 0L
    private var spinTo = 0f

    /** 正在跑的呼吸动画（X / Y 各一个，必须同步）。 */
    private var pulseAnimator: ObjectAnimator? = null
    private var pulseAnimatorY: ObjectAnimator? = null

    /** 当前呼吸动画的基准缩放，用来判断"配置真的变了没"。 */
    private var pulseBase = 0f

    /** 当前是否展开。 */
    private var expanded = false

    /** AI 主动推送的文案；空串 = 没有推送，横条回到引擎状态。 */
    private var pushedStatus = ""

    /** 推送是否常驻（不自动消失）。 */
    private var pushedSticky = false

    /** 推送的停留时长（ms）；0 = 用外观配置里的 `statusAutoHideMs`。 */
    private var pushedDurationMs = 0L

    /** 是否处于贴边态。 */
    private var edgeCollapsed = false

    /**
     * 贴边在哪一侧：-1 = 左，+1 = 右。
     *
     * ⚠️ 必须记住而不是每帧重算：薄片贴着左边时它的中心在屏幕左侧，
     *   重算会得到「离左边更近」，看似等价；但薄片**宽度只有 6dp**，
     *   用户点它之后要弹回完整球，弹到哪一侧得看用户当初往哪边拖的，
     *   不能靠"现在离哪边近"倒推（球比薄片宽得多，结论会反）。
     */
    private var edgeSide = 1

    /** 是否已 addView（避免重复添加 / 重复移除）。 */
    private var attached = false

    /** 当前 window 的左上角坐标（px）。拖动时以它为基准累加位移。 */
    private var curX = 0
    private var curY = 0

    /**
     * 拖动中**未经夹取**的 window 坐标（px）。
     *
     * ⚠️ 这是「拖出屏幕自动贴边」判定的**唯一依据**。用 [curX]/[curY] 判不出来 ——
     *   它们已经被 [clampInto] 夹进可见区域，越界量在那一步就丢了，永远为 0。
     */
    private var rawX = 0
    private var rawY = 0

    /** 拖动状态。 */
    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var downWinX = 0
    private var downWinY = 0
    private var moved = false

    /** 缩放状态。 */
    private var resizing = false
    private var downWidth = 0
    private var downHeight = 0

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()
    private fun dp(v: Float) = (v * density).roundToInt()

    private val tapSlop = dp(TAP_SLOP_DP)

    /** 最近一次状态，展开面板时用它填充。 */
    private var lastStatus: EngineStatus? = null

    /** 最近一次转录。展开时用它填充（球形态下不渲染，省一次 View 树重建）。 */
    private var lastTranscript: ChatTranscript? = null

    /** 转录脏标记：球形态下收到新转录只置位，展开时才真正重建 View。 */
    private var transcriptDirty = true

    /** 已渲染的转录指纹，用来跳过重复渲染（状态每秒推一次，无脑重建会一直重排）。 */
    private var transcriptFingerprint = ""

    /**
     * 当前 [panelTranscript] 里逐行对应的 role 序列。
     *
     * 流式输出时每秒要重渲染好几次，用来判断能不能**复用已有行**（只改文本）：
     * role 序列一致 ⇒ 复用，避免 `removeAllViews()` 造成的整块闪烁。
     */
    private var renderedRoles: List<String> = emptyList()

    /** 当前外观配置。所有尺寸/颜色/形状都从这里取。 */
    private var appearance: BubbleAppearance = BubbleAppearance()

    // ══ 收起态的状态气泡（球上方那个小标签）══════════════════════════════
    //
    // 独立 window。理由见 layout/bubble_status.xml 的头注释：塞进球的 window
    // 会把 window 撑大，而 window 左上角是定位锚点 —— 球本身的位置会跟着偏。
    private var statusRoot: View? = null
    private var statusDot: View? = null
    private var statusText: TextView? = null

    /** 状态横条自动消失的计时器。 */
    private val statusHandler = Handler(Looper.getMainLooper())

    /**
     * 状态横条当前显示的「内容指纹」。
     *
     * ⚠️ 自动消失的**判据就是它变不变**：内容变了才重新计时。用户要的是
     *   「没有变化时 2 秒后自动消失」，所以状态每秒刷新但文字相同**不算**变化，
     *   计时器继续跑，2 秒后照样消失。见 [scheduleStatusAutoHide]。
     */
    private var statusKey = ""

    /**
     * 键盘高度变了（展开态把面板顶上去，保证输入框露在键盘之上）。
     *
     * ⚠️ 由**输入框焦点**驱动，不是由系统 insets 驱动：悬浮窗拿不到 IME insets
     *   （`TYPE_APPLICATION_OVERLAY` 不在 `adjustResize` 的适用范围里，API 29 上
     *   也没有 `WindowInsets.Type.ime()`）。所以用「输入框聚焦 ⇒ 键盘即将弹出」
     *   这个等价条件来预留空间。实测在这台机器上成立。
     */
    private fun applyImeHeight(heightPx: Int) {
        if (imeHeightPx == heightPx) return
        imeHeightPx = heightPx
        if (!expanded) return
        val (nx, ny) = clampInto(curX, curY)
        moveTo(nx, ny)
    }

    /**
     * 键盘高度（px）。
     *
     * 优先用 MainActivity 学到的**真实值**（它按「输入法高度 + 朝向」落盘，
     * 见 `MainActivity.saveImeHeight`）；取不到就按屏高 40% 估一个。
     * 估值的风险是留多了面板偏高，但比"输入框被键盘盖住"轻得多 ——
     * 后者会让用户完全看不见自己在打什么。
     */
    private fun keyboardHeightPx(): Int {
        val sp = context.getSharedPreferences(MainActivity.IME_PREFS, Context.MODE_PRIVATE)
        val learned = sp.getInt(MainActivity.imePrefsKeyOf(context), 0)
        if (learned > 0) return learned
        return (context.resources.displayMetrics.heightPixels * 0.4f).roundToInt()
    }

    /** 展开前（收起态）的位置。收起时用它还原 —— 见 `expand()` 里的说明。 */
    private var preExpandX = 0
    private var preExpandY = 0

    /** 防重入守卫：`positionStatusBubble` 会改 layoutParams，可能再触发 layout 回调。 */
    private var positioning = false

    /**
     * 输入法当前遮住屏幕底部多少像素（0 = 键盘没弹）。
     *
     * ⚠️ 悬浮窗**不会**被系统自动顶上去：`adjustPan`/`adjustResize` 只对 Activity 的
     *   window 生效，`TYPE_APPLICATION_OVERLAY` 不在其中。真机实测（华为 CDY-TN90 /
     *   Android 10）：键盘弹出后输入框被完全盖住，用户看不见自己打了什么。
     *   所以高度由 MainActivity 观测到之后**推**过来，这里自己挪。
     */
    @Volatile
    private var imeHeightPx = 0

    /** 状态气泡距球顶部的间距（dp）。 */
    private val statusGapPx = dp(8)

    /** 状态气泡的宽度上限（dp）。 */
    private val statusMaxWidthPx = dp(200)

    /** 状态标签的字符数上限。标签是单行小标签，太长会把球顶得很宽。 */
    private val statusMaxChars = 60

    // ══════════════════════════════════════════════════════════════════
    // 生命周期
    // ══════════════════════════════════════════════════════════════════

    @SuppressLint("InflateParams")
    fun show() {
        if (attached) return
        if (!canDrawOverlays(context)) {
            Log.w(TAG, "未授予悬浮窗权限，气泡不显示")
            return
        }

        appearance = BubbleAppearance.load(context)

        val view = LayoutInflater.from(context).inflate(R.layout.bubble_window, null)
        root = view
        circleView = view.findViewById(R.id.bubbleCircle)
        avatarView = view.findViewById(R.id.bubbleAvatar)
        panelView = view.findViewById(R.id.bubblePanel)
        dotView = view.findViewById(R.id.bubbleDot)
        panelDot = view.findViewById(R.id.panelDot)
        panelAction = view.findViewById(R.id.panelAction)
        panelTitle = view.findViewById(R.id.panelTitle)
        panelScroll = view.findViewById(R.id.panelScroll)
        panelTranscript = view.findViewById(R.id.panelTranscript)
        panelInput = view.findViewById(R.id.panelInput)
        panelStop = view.findViewById(R.id.panelStop)
        panelHeader = view.findViewById(R.id.panelHeader)
        panelResize = view.findViewById(R.id.panelResize)
        panelSend = view.findViewById(R.id.panelSend)
        edgeView = view.findViewById(R.id.bubbleEdge)
        edgeDot = view.findViewById(R.id.edgeDot)

        paintDot(dotView, running = false)
        paintDot(panelDot, running = false, small = true)
        paintDot(edgeDot, running = false, small = true)

        // 位置：优先用上次记住的；没有就贴右侧中部
        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels
        val sidePx = dp(appearance.size)
        curX = prefs.getInt(KEY_X, screenW - sidePx - dp(EDGE_MARGIN_DP))
        curY = prefs.getInt(KEY_Y, (screenH * 0.42f).roundToInt())

        applyAppearanceToViews()
        restorePanelSize()
        clampInto(curX, curY).let { curX = it.first; curY = it.second }

        installTouch(circleView!!)
        installEdgeTouch(edgeView!!)
        installPanelDrag(panelHeader!!)
        installPanelResize(panelResize!!)
        installPanelActions(view)

        val params = baseParams().apply { x = curX; y = curY }
        try {
            wm.addView(view, params)
            attached = true
            Log.i(TAG, "气泡已显示: ($curX, $curY) shape=${appearance.shape} size=${appearance.size}")
            // 主动拉一次当前状态：气泡是刚创建的，错过了之前所有的推送，
            // 不拉的话会停在"空闲"直到下一次状态变化（可能几十秒后）。
            EngineService.currentStatus()?.let { applyStatus(it) }
            // 插件要求的初始形态（非 auto 时生效）。
            when (appearance.state) {
                BubbleAppearance.STATE_EXPANDED -> expand()
                BubbleAppearance.STATE_COLLAPSED -> Unit
                else -> Unit
            }
        } catch (t: Throwable) {
            // 最常见的是没权限（BadTokenException）。不致命，只是没气泡。
            Log.w(TAG, "添加气泡失败: ${t.message}")
            root = null
        }
    }

    fun hide() {
        if (!attached) return
        val v = root ?: return
        attached = false
        hideStatusBubble()
        // 停掉轮播与动效：视图都要移除了，继续 postDelayed 只是空转耗电，
        // 而且回调里会碰到已经 detach 的 ImageView。
        stopFramePlayer()
        spinning = false
        pulsing = false
        spinAnimator?.cancel()
        spinAnimator = null
        spinDuration = 0L
        spinTo = 0f
        pulseAnimator?.cancel()
        pulseAnimatorY?.cancel()
        pulseAnimator = null
        pulseAnimatorY = null
        pulseBase = 0f
        // 推送标记也要清：下次显示气泡时若还留着旧推送，横条会先冒出一句
        // 上一轮的旧话，看起来像"卡住了"。
        statusHandler.removeCallbacks(pushedClearRunnable)
        pushedStatus = ""
        pushedSticky = false
        pushedDurationMs = 0L
        runCatching { wm.removeViewImmediate(v) }
        root = null
        circleView = null
        avatarView = null
        panelView = null
        panelInput = null
        edgeView = null
        expanded = false
        edgeCollapsed = false
    }

    // ══════════════════════════════════════════════════════════════════
    // 外观（插件可控）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 应用一份外观配置。
     *
     * ⚠️ 全程 try/catch：气泡是锦上添花，一份写错的配置不能让它消失。
     */
    fun applyAppearance(next: BubbleAppearance) {
        appearance = next
        try {
            applyAppearanceToViews()
            // ⚠️ **按新尺寸夹，不能读 `root.width`。** 这里刚改完 layoutParams，
            //   布局还没重测，`root.width` 仍是**旧**尺寸（可能是贴边态的 6dp，
            //   也可能是上一版的小球）。拿它算夹取范围，AI 一把球改大，球就会
            //   跑出屏幕右/下边缘 —— 用户看到的是"球消失了"。
            //   收起态尺寸就是 `appearance.size`，直接用它算。展开态不能用球尺寸
            //   （面板宽得多），交给下面那一帧用真实布局尺寸夹。
            if (!expanded) {
                val side = dp(appearance.size)
                clampInto(curX, curY, side, side).let { moveTo(it.first, it.second) }
            }
            // 展开态尺寸由面板决定，要等布局出来才知道；下一帧再夹一次兜住。
            root?.post {
                val (nx, ny) = clampInto(curX, curY)
                moveTo(nx, ny)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "应用悬浮球外观失败: ${t.message}")
        }
    }

    /** 把 [appearance] 刷到各个 View 上。 */
    private fun applyAppearanceToViews() {
        val side = dp(appearance.size)
        val dm = context.resources.displayMetrics

        // ── 球：尺寸、形状、底色、描边 ─────────────────────────────────
        circleView?.let { c ->
            (c.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.width = side
                lp.height = side
                c.layoutParams = lp
            }
            // 形状层：SVG path 优先，否则退回圆/方/圆角。
            c.background = ballBackground()
            // 背景层的透明度**单独**作用在背景 drawable 上，与整体 opacity 相乘 ——
            // 这正是「背景透明、图片不透明」能成立的原因。
            c.background?.alpha = (appearance.bgOpacity.coerceIn(0f, 1f) * 255).roundToInt()
            // 形状可以不是圆的，内容要跟着裁（见 clipToShape 的注释）。
            clipToShape(c)
            // 状态点跟着球缩放：球小到 24dp 时 14dp 的点会占掉一大半
            dotView?.let { d ->
                val dotPx = (appearance.size * 0.25f).roundToInt().coerceIn(8, 18)
                (d.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                    lp.width = dp(dotPx)
                    lp.height = dp(dotPx)
                    d.layoutParams = lp
                }
            }
        }

        // ── 内容层：内置头像 / 自定义图片 / 轮播帧 / 精灵图 ─────────────
        applyContentLayer(side)

        // ── 贴边薄片：高度跟随球，宽度固定 ─────────────────────────────
        edgeView?.let { e ->
            (e.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.width = dp(EDGE_WIDTH_DP)
                lp.height = side
                e.layoutParams = lp
            }
            e.background = edgeBackground()
            e.background?.alpha = (appearance.bgOpacity.coerceIn(0f, 1f) * 255).roundToInt()
        }

        // ── 整体不透明度（与两个分层透明度相乘）─────────────────────────
        root?.alpha = appearance.opacity.coerceIn(0.05f, 1f)

        // ── 动效层：旋转 / 呼吸 ────────────────────────────────────────
        applyMotion()

        // ── 面板：宽度、配色 ───────────────────────────────────────────
        panelView?.let { p ->
            val w = prefs.getInt(KEY_PANEL_W, 0).takeIf { it > 0 }
                ?: appearance.effectivePanelWidth(dm.widthPixels, density)
            (p.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.width = w.coerceIn(dp(PANEL_MIN_WIDTH_DP), dm.widthPixels)
                p.layoutParams = lp
            }
            p.background = panelBackground()
        }

        applyPanelColors()

        // ── 状态横条宽度上限随球走 ─────────────────────────────────────
        statusText?.maxWidth = statusMaxWidthPx
    }

    /**
     * 内容层（头像 / 轮播帧 / 精灵图）。
     *
     * 优先级：**轮播帧 > 自定义头像 > 内置头像**。轮播在跑时不去碰单张头像的
     * 逻辑，否则两套代码会互相覆盖 `setImageBitmap` 的结果。
     */
    private fun applyContentLayer(side: Int) {
        val iv = avatarView ?: return

        // 内容层的透明度单独作用在 ImageView 上（与整体 opacity 相乘）。
        iv.alpha = appearance.avatarOpacity.coerceIn(0f, 1f)
        // 内容缩放：0.2~2.0，>1 会溢出球，由球的裁剪兜住（这正是"图片比球大"想要的效果）。
        iv.scaleX = appearance.avatarScale
        iv.scaleY = appearance.avatarScale

        // 轮播帧：先停掉旧的播放器，再按新配置决定要不要重启。
        stopFramePlayer()

        if (appearance.frameCount > 0 && BubbleFrames.available(context)) {
            iv.visibility = View.VISIBLE
            showFrame(frameIndex.coerceIn(0, appearance.frameCount - 1), side)
            startFramePlayer()
            return
        }

        // 内容层整个去掉：`avatar:"none"` 且没有帧 —— 只留背景形状。
        if (appearance.avatar == BubbleAppearance.AVATAR_NONE) {
            iv.visibility = View.GONE
            return
        }

        if (appearance.usesCustomAvatar) {
            val bmp = BubbleAppearance.readAvatar(context, side)
            if (bmp != null) {
                iv.setImageBitmap(bmp)
                iv.visibility = View.VISIBLE
            } else {
                // 图片文件丢了（用户清了数据）—— 退回内置头像而不是留白。
                iv.setImageResource(R.drawable.bubble_avatar)
                iv.visibility = View.VISIBLE
            }
        } else {
            iv.setImageResource(R.drawable.bubble_avatar)
            iv.visibility = View.VISIBLE
        }
    }

    /**
     * 按形状裁剪球的子视图。
     *
     * ⚠️ 只在**非圆/非方**形状（即 SVG path）时才裁：圆和方分别用
     *   `OVAL`/`RECTANGLE` 的 outline 更省，且裁剪会带来一次离屏绘制。
     *
     * ⚠️ 裁剪只在 API 21+ 的 `ViewOutlineProvider` 上有效，而 path 可能非凸
     *   （星形、心形）—— 非凸 path 在 API 29 及以下无法作为 outline。此时
     *   **不裁剪**（形状层照常画），代价是内容可能溢出形状边缘。这是刻意的
     *   降级：宁可"图没被裁"也不要"整个球不显示"。
     */
    private fun clipToShape(view: View) {
        val usePath = appearance.shape == BubbleAppearance.SHAPE_PATH &&
            appearance.shapePath.isNotEmpty()
        if (!usePath) {
            view.clipToOutline = false
            return
        }
        val outline = BubbleShapeDrawable(
            pathData = appearance.shapePath,
            viewBox = appearance.shapeViewBox,
            fillStart = Color.TRANSPARENT,
            fillEnd = null,
            strokeColor = Color.TRANSPARENT,
            strokeWidth = 0f,
        )
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) {
                outline.setBounds(0, 0, v.width, v.height)
                outline.getOutline(o)
            }
        }
        view.invalidateOutline()
    }

    /** 显示轮播的第 [index] 帧。解码失败时保持上一帧，不留白。 */
    private fun showFrame(index: Int, side: Int) {
        val iv = avatarView ?: return
        val bmp = BubbleFrames.loadFrame(context, index, BubbleAppearance.spriteOf(appearance), side)
        if (bmp != null) iv.setImageBitmap(bmp)
    }

    /**
     * 启动轮播。
     *
     * ⚠️ 逐帧时长可以各不相同（`frameDurations`），所以用**自递归的 postDelayed**
     *   而不是 `scheduleAtFixedRate` —— 后者只能等间隔。
     *
     * ⚠️ `frameLoop >= 0` 时播完指定轮数就停在最后一帧（不回到第一帧）：
     *   那是"播一次作为提示"的用法，回到第一帧会让人以为它还在转。
     */
    private fun startFramePlayer() {
        val count = appearance.frameCount
        if (count <= 1) return
        val durations = BubbleFrames.durations(context, appearance.frameIntervalMs)
        val sprite = BubbleAppearance.spriteOf(appearance)
        val side = dp(appearance.size)

        val step = object : Runnable {
            override fun run() {
                // 配置变了（帧数/时长）就退出，由下一次 applyContentLayer 重启。
                if (appearance.frameCount != count || !expandedSafeForFrames()) return
                val next = frameIndex + 1
                if (next >= count) {
                    frameLoops++
                    if (appearance.frameLoop in 0 until frameLoops) {
                        // 播够了：停在最后一帧。
                        frameRunnable = null
                        return
                    }
                }
                frameIndex = next % count
                showFrame(frameIndex, side)
                val delay = (durations.getOrNull(frameIndex) ?: appearance.frameIntervalMs).toLong()
                root?.postDelayed(this, delay.coerceAtLeast(20L))
            }
        }
        frameRunnable = step
        val delay = (durations.getOrNull(frameIndex) ?: appearance.frameIntervalMs).toLong()
        root?.postDelayed(step, delay.coerceAtLeast(20L))
    }

    /** 帧播放器是否还该继续（球被移除/隐藏时停掉，别空转耗电）。 */
    private fun expandedSafeForFrames(): Boolean = attached && root != null

    /** 停掉轮播。幂等。 */
    private fun stopFramePlayer() {
        frameRunnable?.let { root?.removeCallbacks(it) }
        frameRunnable = null
    }

    /**
     * 动效层：旋转与呼吸。
     *
     * ⚠️ 两者都作用在 `avatarView`（内容层）而不是整个球：转整个球会把形状层
     *   也转起来，那对非圆形状（星形/心形）是灾难。
     *
     * ⚠️ **必须用两个独立的 `ObjectAnimator`，不能用 `view.animate()`。**
     *   `ViewPropertyAnimator` 对同一个 View 是**单例**，后一次 `.start()` 会
     *   取消前一次 —— 于是「旋转 + 呼吸」同时打开时只有一个生效，而且看不出
     *   是谁把谁取消了。`ObjectAnimator` 是独立对象，两个不同属性可以并行。
     *
     * ⚠️ 旋转用 `repeatCount = INFINITE` 而不是 `withEndAction` 递归：递归那种
     *   写法在"动画已在跑"的守卫下会转完一圈就停（守卫把它自己挡住了）。
     */
    private fun applyMotion() {
        val iv = avatarView ?: return

        // ── 旋转 ──────────────────────────────────────────────────────
        if (appearance.spin != 0) {
            // 每 360° 的时长 = 360 / 每秒角度。负角度即逆时针。
            val degPerSec = abs(appearance.spin).coerceAtLeast(1)
            val duration = (360f / degPerSec * 1000f).toLong().coerceAtLeast(200L)
            val to = if (appearance.spin > 0) 360f else -360f
            val changed = spinAnimator == null || spinDuration != duration || spinTo != to
            if (changed) {
                spinAnimator?.cancel()
                // 从 0 转到 ±360：`repeatMode = RESTART` 会把每轮重置回 0，
                // 所以终值必须正好是一整圈，否则每轮之间会跳一下。
                spinAnimator = ObjectAnimator.ofFloat(iv, View.ROTATION, 0f, to).apply {
                    this.duration = duration
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.RESTART
                    interpolator = LinearInterpolator()
                    start()
                }
                spinDuration = duration
                spinTo = to
                spinning = true
            }
        } else if (spinning) {
            spinAnimator?.cancel()
            spinAnimator = null
            spinDuration = 0L
            spinTo = 0f
            spinning = false
            iv.rotation = 0f
        }

        // ── 呼吸 ──────────────────────────────────────────────────────
        if (appearance.pulse) {
            val base = appearance.avatarScale
            val changed = pulseAnimator == null || pulseBase != base
            if (changed) {
                pulseAnimator?.cancel()
                pulseAnimator = ObjectAnimator.ofFloat(iv, View.SCALE_X, base, base * 1.12f).apply {
                    this.duration = 700
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                    interpolator = AccelerateDecelerateInterpolator()
                    start()
                }
                // 两个属性（X/Y）必须同步，否则会拉成椭圆。共用一个进度源最简单：
                // 再建一个只驱动 SCALE_Y 的、时长与相位完全一致的动画。
                pulseAnimatorY?.cancel()
                pulseAnimatorY = ObjectAnimator.ofFloat(iv, View.SCALE_Y, base, base * 1.12f).apply {
                    this.duration = 700
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                    interpolator = AccelerateDecelerateInterpolator()
                    start()
                }
                pulseBase = base
                pulsing = true
            }
        } else if (pulsing) {
            pulseAnimator?.cancel()
            pulseAnimatorY?.cancel()
            pulseAnimator = null
            pulseAnimatorY = null
            pulseBase = 0f
            pulsing = false
            iv.scaleX = appearance.avatarScale
            iv.scaleY = appearance.avatarScale
        }
    }

    /**
     * 球的底：形状 + 底色/渐变 + 描边，全部由 [appearance] 决定。
     *
     * ⚠️ `shape:"path"` 时返回 [BubbleShapeDrawable]（任意 SVG 形状），否则是
     *   原来那三种枚举形状的 [GradientDrawable]。两条分支的**描边与渐变语义
     *   必须一致**，否则 AI 把形状从 `circle` 改成 `path` 时颜色会变样。
     */
    private fun ballBackground(): Drawable {
        val c1 = colorOr(appearance.bg, DEFAULT_BALL_BG)
        val c2 = if (appearance.bg2.isNotEmpty()) colorOr(appearance.bg2, c1) else null
        val bw = if (appearance.borderWidth > 0) dp(appearance.borderWidth) else dp(1)
        val bc = if (appearance.border.isNotEmpty()) colorOr(appearance.border, DEFAULT_BORDER) else DEFAULT_BORDER

        // 任意形状：交给 SVG path 那条路。
        if (appearance.shape == BubbleAppearance.SHAPE_PATH && appearance.shapePath.isNotEmpty()) {
            return BubbleShapeDrawable(
                pathData = appearance.shapePath,
                viewBox = appearance.shapeViewBox,
                fillStart = c1,
                fillEnd = c2,
                strokeColor = bc,
                strokeWidth = bw.toFloat(),
            )
        }

        val d = GradientDrawable()
        when (appearance.shape) {
            BubbleAppearance.SHAPE_CIRCLE -> d.shape = GradientDrawable.OVAL
            BubbleAppearance.SHAPE_SQUARE -> {
                d.shape = GradientDrawable.RECTANGLE
                d.cornerRadius = 0f
            }
            else -> {
                d.shape = GradientDrawable.RECTANGLE
                d.cornerRadius = dp(appearance.cornerRadius).toFloat()
            }
        }
        if (c2 != null) {
            d.colors = intArrayOf(c1, c2)
            d.orientation = GradientDrawable.Orientation.TOP_BOTTOM
        } else {
            d.setColor(c1)
        }
        d.setStroke(bw, bc)
        return d
    }

    /** 贴边薄片的底：窄圆角条，颜色跟随球的底色。 */
    private fun edgeBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(3).toFloat()
        setColor(colorOr(appearance.bg, DEFAULT_BALL_BG))
        setStroke(dp(1), if (appearance.border.isNotEmpty()) colorOr(appearance.border, DEFAULT_BORDER) else DEFAULT_BORDER)
    }

    /** 面板的底：圆角 + 底色 + 淡描边。 */
    private fun panelBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(20).toFloat()
        setColor(colorOr(appearance.panelBg, DEFAULT_PANEL_BG))
        setStroke(dp(1), DEFAULT_BORDER)
    }

    /**
     * 面板内的文字与强调色。
     *
     * ⚠️ 正文色要**逐处设**：`panelAction` / `panelTitle` 等是独立 TextView，
     *   没有统一的父级可以设 textColor。漏一处就会出现"面板换了深色底、
     *   标题还是黑的"这种半截效果。
     */
    private fun applyPanelColors() {
        val text = colorOr(appearance.panelText, DEFAULT_TEXT)
        val accent = colorOr(appearance.panelAccent, DEFAULT_ACCENT)
        panelAction?.setTextColor(text)
        panelTitle?.setTextColor(text)
        panelInput?.setTextColor(text)
        panelSend?.let { s ->
            s.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(accent)
            }
        }
        // 转录区已渲染的行也要跟着换色 —— 否则插件改配色后要等下一次转录变化才生效。
        //
        // ⚠️ 必须连 `renderedRoles` 一起清掉：增量分支是"role 序列一致就复用旧行、
        //   只改文本"，而换配色改的正是**样式**。只清指纹的话这里会走复用，
        //   颜色永远换不过来。
        transcriptFingerprint = ""
        renderedRoles = emptyList()
        if (expanded) renderTranscript()
    }

    /** 颜色串 → int；空串或非法时退回 [fallback]。 */
    private fun colorOr(value: String, fallback: Int): Int {
        if (value.isEmpty()) return fallback
        return try {
            Color.parseColor(value)
        } catch (t: Throwable) {
            fallback
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 收起态的状态气泡
    // ══════════════════════════════════════════════════════════════════

    /**
     * 显示/更新球上方的状态标签。
     *
     * 传空串即隐藏 —— 空闲且没有动作时不该在屏幕上留一个空壳。
     *
     * ⚠️ **只在收起态显示**：展开态的面板里已经有同一份信息，再挂一个标签
     *   会重复，而且面板比球宽得多，标签会横在面板上方很怪。
     *   贴边态同理（薄片只有 6dp 宽，标签会把它整个盖住）。
     *
     * @param autoHideMs 覆盖外观配置里的停留时长；null = 用外观配置。
     *   0 = 常驻（AI 主动推送用得上：「这条别消失」）。
     */
    private fun showStatusBubble(text: String, running: Boolean, autoHideMs: Int? = null) {
        if (text.isEmpty() || expanded || edgeCollapsed) {
            hideStatusBubble()
            return
        }

        if (statusRoot == null) {
            val v = LayoutInflater.from(context).inflate(R.layout.bubble_status, null)
            statusRoot = v
            statusDot = v.findViewById(R.id.statusDot)
            statusText = v.findViewById(R.id.statusText)
            // 宽度上限靠 maxWidth 约束 TextView，而不是给 window 定死宽度 ——
            // 短文案要能收紧，长文案才截断。
            statusText?.maxWidth = statusMaxWidthPx
            // 点一下等于点球：展开面板。用户看到状态自然会想去点它。
            v.setOnClickListener { expand() }
            try {
                wm.addView(v, statusParams())
            } catch (t: Throwable) {
                Log.w(TAG, "添加状态气泡失败: ${t.message}")
                statusRoot = null
                return
            }
            // ⚠️ 量出真实尺寸前先藏起来，否则会先按"宽度 0"居中一次、量完再跳到
            //   正确位置 —— 用户看到的是标签从球中心"弹"到左边。
            v.alpha = 0f
            // 尺寸变化时重新摆位：文字变长/变短会改变标签宽度，宽度变了居中位置
            // 也得跟着变，否则标签会歪向一边（或探出屏幕）。
            //
            // ⚠️ 防重入：positionStatusBubble 会改 layoutParams，可能再触发一次
            //   layout 回调 —— 不加这个守卫就是无限递归（ANR）。
            v.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                if (!positioning) positionStatusBubble()
            }
            v.post {
                positionStatusBubble()
                v.animate().alpha(1f).setDuration(120).start()
            }
            // 刚创建时 statusKey 是空串，下面必然走到「内容变了」分支，计时器由此启动。
        }

        // ⚠️ 内容**真的变了**才重置自动消失计时器。
        //
        // 这正是用户要的语义：「工具调用状态横条没有变化时 两秒后自动消失」。
        // 状态是每 5 秒（外加 DOM 变化）推一次的，如果每次推送都重置计时器，
        // 横条就会永远挂着 —— 那正是改之前的行为，也是用户抱怨的点。
        //
        // key 里带上时长：AI 推一条"常驻"的消息时内容可能与引擎状态一致，
        // 不带时长的话会被判成"没变"而沿用旧的自动消失计时器。
        val key = "$text\u0000$running\u0000$autoHideMs"
        val changed = key != statusKey
        if (changed) {
            statusKey = key
            statusText?.text = text
            paintDot(statusDot, running, small = true)
            scheduleStatusAutoHide(autoHideMs)
        }
        positionStatusBubble()
    }

    /**
     * 排一次「内容无变化就自动消失」的计时。
     *
     * ⚠️ 只在内容变化时调用（见 [showStatusBubble]）。`<= 0` 表示常驻，
     *   此时不排计时器。
     */
    private fun scheduleStatusAutoHide(override: Int? = null) {
        statusHandler.removeCallbacks(statusHideRunnable)
        val ms = override ?: appearance.statusAutoHideMs
        if (ms <= 0) return
        statusHandler.postDelayed(statusHideRunnable, ms.toLong())
    }

    /**
     * 状态横条自动消失。
     *
     * ⚠️ 做成字段而不是每次 new：`removeCallbacks` 要靠同一个实例才能取消。
     *
     * ⚠️ 消失时**一并清掉 AI 推送标记**：横条都看不见了，这次推送就算结束。
     *   不清的话下一次引擎状态刷新（每几秒一次）会把它重新显示出来，
     *   用户看到的是"这条消息消失又冒出来、消失又冒出来"。
     */
    private val statusHideRunnable = Runnable {
        if (statusRoot == null) return@Runnable
        // 推送已经展示完了 —— 标记与它自己的清理计时器都该撤掉。
        statusHandler.removeCallbacks(pushedClearRunnable)
        pushedStatus = ""
        pushedSticky = false
        pushedDurationMs = 0L
        // 淡出而不是瞬移：横条就在用户视野边缘，突然消失会让人以为界面出问题了。
        statusRoot?.animate()?.alpha(0f)?.setDuration(160)?.withEndAction {
            hideStatusBubble()
        }?.start() ?: hideStatusBubble()
    }

    private fun hideStatusBubble() {
        statusHandler.removeCallbacks(statusHideRunnable)
        val v = statusRoot ?: return
        statusRoot = null
        statusDot = null
        statusText = null
        statusKey = ""
        runCatching { wm.removeViewImmediate(v) }
    }

    /**
     * 状态气泡的窗口参数。
     *
     * ⚠️ **不能加 `FLAG_NOT_TOUCHABLE`** —— 加了它就点不动了，而"点状态标签
     *   展开面板"是用户看到状态后最自然的下一步。代价是这一小块区域会挡住
     *   下层应用，但标签本身很小，且它只显示在有进展可看的时候。
     *
     * ⚠️ 保留 `FLAG_NOT_FOCUSABLE`：它只是显示，不该抢走下层应用的按键焦点。
     */
    private fun statusParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        overlayType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }

    /**
     * 把状态气泡摆到球的**正上方**。
     *
     * ⚠️ 必须跟着球一起移动（拖动、吸附动画里都要调），否则球跑了标签留在原地。
     *
     * 水平方向：与球居中对齐；贴边时会被夹进屏幕内（球在最左/最右时，
     * 居中对齐会让标签探出屏幕）。
     */
    private fun positionStatusBubble() {
        val v = statusRoot ?: return
        val p = v.layoutParams as? WindowManager.LayoutParams ?: return
        positioning = true
        try {
            val dm = context.resources.displayMetrics
            val circleW = circleWidth()

            // 标签还没量过尺寸时（刚 addView）先按屏幕内夹一次，量完会再调一次。
            val labelW = v.width.takeIf { it > 0 } ?: 0
            val centered = curX + (circleW - labelW) / 2
            p.x = centered.coerceIn(0, (dm.widthPixels - labelW).coerceAtLeast(0))

            // 竖直方向：默认贴在球上方。上方空间不够（球贴着顶部）时改放球下方 ——
            // 否则标签会被状态栏挡住或直接跑出屏幕。
            val labelH = v.height.takeIf { it > 0 } ?: 0
            val above = curY - statusGapPx - labelH
            p.y = if (labelH > 0 && above < dp(TOP_MARGIN_DP)) {
                curY + circleHeight() + statusGapPx
            } else {
                above
            }
            runCatching { wm.updateViewLayout(v, p) }
        } finally {
            positioning = false
        }
    }

    /** 球的当前宽度（量过就用实测值，否则退回配置里的尺寸）。 */
    private fun circleWidth(): Int = root?.width?.takeIf { !expanded && !edgeCollapsed && it > 0 }
        ?: dp(appearance.size)

    /** 球的当前高度（同上）。 */
    private fun circleHeight(): Int = root?.height?.takeIf { !expanded && !edgeCollapsed && it > 0 }
        ?: dp(appearance.size)

    private fun baseParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        overlayType(),
        // 初始按**收起态**配：不抢焦点。
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }

    /**
     * 按形态切换窗口的焦点标志。**输入法能不能弹出来完全取决于这里。**
     *
     * ## 之前为什么弹不出键盘
     *
     * 原实现固定带 `FLAG_ALT_FOCUSABLE_IM`，并注释成"平时不抢焦点、点输入框才聚焦"
     * —— **那个理解是反的**。该标志的语义是"把 `FLAG_NOT_FOCUSABLE` 对输入法的
     * 含义**取反**"，即：未设 `NOT_FOCUSABLE` 而设了它，窗口就表现为
     * **不需要与输入法交互**，系统因此不会为它弹出键盘。真机表现就是
     * 「点输入框毫无反应」。
     *
     * ## 正确组合
     *
     * | 形态 | 标志 | 效果 |
     * |---|---|---|
     * | 收起 / 贴边 | `NOT_FOCUSABLE` | 不抢焦点，下层应用照常收按键，也不弹键盘 |
     * | 展开 | 去掉 `NOT_FOCUSABLE` | 可聚焦 ⇒ 点输入框能拉起键盘 |
     *
     * ⚠️ 两态都要保留 `FLAG_NOT_TOUCH_MODAL`：它让窗口**之外**的触摸照常传给
     *   下层应用。去掉的话，气泡一显示就会把整个屏幕的触摸都吃掉。
     *
     * ⚠️ 展开态**不能**再设 `FLAG_ALT_FOCUSABLE_IM` —— 那正是键盘弹不出来的原因。
     *
     * @param focusable 是否允许这个窗口获得焦点（展开态为 true）。
     */
    private fun applyFocusFlags(focusable: Boolean) {
        val v = root ?: return
        val p = v.layoutParams as? WindowManager.LayoutParams ?: return
        p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        p.flags = p.flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM.inv()
        p.flags = if (focusable) {
            p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        runCatching { wm.updateViewLayout(v, p) }
    }

    /** API 26+ 用 TYPE_APPLICATION_OVERLAY；更低版本（本项目 minSdk 26）用不到旧值。 */
    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

    // ══════════════════════════════════════════════════════════════════
    // 拖动 + 点击 + 边缘吸附 + 拖出屏幕贴边
    // ══════════════════════════════════════════════════════════════════

    /**
     * 给一个 View 装「拖动 + 点击」手势。
     *
     * @param target 手势载体（球的 circleView）。
     * @param onTap 未移动就松手时的动作。
     * @param allowEdgeCollapse 松手时是否允许因"拖出屏幕"而进入贴边态。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun installTouch(
        target: View,
        onTap: () -> Unit = { expand() },
        allowEdgeCollapse: Boolean = true,
    ) {
        target.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    downWinX = curX
                    downWinY = curY
                    rawX = curX
                    rawY = curY
                    dragging = true
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return@setOnTouchListener false
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (!moved && (abs(dx) > tapSlop || abs(dy) > tapSlop)) moved = true
                    if (!moved) return@setOnTouchListener true
                    // ⚠️ 位移量累加到**按下时的 window 坐标**上，不能直接用 rawX/rawY
                    rawX = downWinX + dx.roundToInt()
                    rawY = downWinY + dy.roundToInt()
                    val (nx, ny) = clampInto(rawX, rawY)
                    moveTo(nx, ny)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasDrag = moved
                    dragging = false
                    if (!wasDrag) {
                        if (ev.actionMasked == MotionEvent.ACTION_UP) onTap()
                    } else {
                        onTouchUp(allowEdgeCollapse)
                    }
                    true
                }

                else -> false
            }
        }
    }

    /**
     * 拖动松手后的落位。
     *
     * 两种结局：
     * 1. **拖出了屏幕**（越界量超过 [EDGE_OVERSHOOT_DP]）→ 贴边折叠（[enterEdge]）。
     * 2. 否则吸附到最近的左右边缘（原有行为）。
     *
     * ⚠️ 判定必须用 [rawX]（未夹取）而不是 [curX]（已夹取）。夹取会把越界量抹掉，
     *   用 curX 判的结果永远是"没越界"，贴边永远不触发。
     */
    private fun onTouchUp(allowEdgeCollapse: Boolean) {
        val dm = context.resources.displayMetrics
        val w = root?.width?.takeIf { it > 0 } ?: dp(appearance.size)
        val overshootLeft = -rawX
        val overshootRight = rawX + w - dm.widthPixels
        val overshoot = maxOf(overshootLeft, overshootRight)

        if (allowEdgeCollapse && appearance.edgeSnap && overshoot >= dp(EDGE_OVERSHOOT_DP)) {
            enterEdge(if (overshootLeft > overshootRight) -1 else 1)
            return
        }
        snapToEdge()
    }

    private fun moveTo(x: Int, y: Int) {
        curX = x
        curY = y
        val v = root ?: return
        val p = v.layoutParams as? WindowManager.LayoutParams ?: return
        p.x = x
        p.y = y
        runCatching { wm.updateViewLayout(v, p) }
        // 状态标签跟着球走 —— 漏了这行的话，拖动时球跑了、标签留在原地。
        positionStatusBubble()
    }

    /** 夹进可见区域（考虑状态栏与底部手势条）。 */
    private fun clampInto(x: Int, y: Int): Pair<Int, Int> {
        val v = root
        val w = v?.width?.takeIf { it > 0 } ?: dp(appearance.size)
        val h = v?.height?.takeIf { it > 0 } ?: dp(appearance.size)
        return clampInto(x, y, w, h)
    }

    /**
     * 按**指定尺寸**夹进可见区域。
     *
     * ⚠️ 需要这个重载是因为切换形态时 `root` 的尺寸还是旧的那个：收起瞬间布局尚未
     *   重测，`root.width` 仍是展开态的 900 —— 拿它算夹取范围会把球留在面板左上角。
     */
    private fun clampInto(x: Int, y: Int, w: Int, h: Int): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        val minY = dp(TOP_MARGIN_DP)
        // 键盘弹出时可用高度要扣掉键盘，否则"夹进可见区域"会把面板留在键盘底下。
        val maxY = (dm.heightPixels - h - dp(BOTTOM_MARGIN_DP) - imeHeightPx).coerceAtLeast(minY)
        val nx = x.coerceIn(0, (dm.widthPixels - w).coerceAtLeast(0))
        val ny = y.coerceIn(minY, maxY)
        return nx to ny
    }

    /**
     * 松手后吸附到最近的左右边缘。
     *
     * 带一段短动画（120ms、8 帧左右）而不是瞬移：瞬移会让人以为"位置丢了"。
     * 用 Handler 逐帧而不是 ViewPropertyAnimator —— 后者动画的是 View 的
     * translationX/Y，而悬浮窗的位置由 WindowManager.LayoutParams 决定，
     * 两者会打架（松手瞬间闪回原位）。
     */
    private fun snapToEdge() {
        val dm = context.resources.displayMetrics
        val v = root ?: return
        val w = v.width.takeIf { it > 0 } ?: dp(appearance.size)
        val targetX = if (curX + w / 2 < dm.widthPixels / 2) dp(EDGE_MARGIN_DP)
        else dm.widthPixels - w - dp(EDGE_MARGIN_DP)
        val (_, ty) = clampInto(targetX, curY)
        animateTo(targetX, ty)
        prefs.edit().putInt(KEY_X, targetX).putInt(KEY_Y, ty).apply()
    }

    private fun animateTo(toX: Int, toY: Int) {
        val fromX = curX
        val fromY = curY
        val steps = 9
        val handler = Handler(Looper.getMainLooper())
        var i = 1
        val tick = object : Runnable {
            override fun run() {
                val t = i.toFloat() / steps
                // ease-out：起步快、收尾稳，像"被边缘吸过去"
                val e = 1f - (1f - t) * (1f - t)
                moveTo(
                    (fromX + (toX - fromX) * e).roundToInt(),
                    (fromY + (toY - fromY) * e).roundToInt(),
                )
                if (i++ < steps) handler.postDelayed(this, 14)
                else moveTo(toX, toY)
            }
        }
        handler.post(tick)
    }

    // ══════════════════════════════════════════════════════════════════
    // 贴边折叠态
    // ══════════════════════════════════════════════════════════════════

    /**
     * 进入贴边态：球与面板都收起来，只在 [side] 那一侧留一条薄片。
     *
     * 触发条件是「用户把球往屏幕外拖」（见 [onTouchUp]），不是「拖到边缘」——
     * 后者是原有的吸附行为，球仍然完整可见。
     *
     * ⚠️ 位置必须**贴住屏幕边**（x = 0 或 screenW - edgeW），不能沿用球的坐标：
     *   薄片只有 6dp 宽，沿用球的 x 会让它浮在半空中。
     */
    private fun enterEdge(side: Int) {
        val dm = context.resources.displayMetrics
        edgeSide = side
        expanded = false
        edgeCollapsed = true
        hideStatusBubble()
        applyFocusFlags(focusable = false)
        panelInput?.let {
            it.setText("")
            it.clearFocus()
        }
        panelView?.visibility = View.GONE
        circleView?.visibility = View.GONE
        edgeView?.visibility = View.VISIBLE

        val edgeW = dp(EDGE_WIDTH_DP)
        val x = if (side < 0) 0 else (dm.widthPixels - edgeW)
        val (nx, ny) = clampInto(x, curY, edgeW, dp(appearance.size))
        moveTo(nx, ny)
        prefs.edit().putInt(KEY_X, nx).putInt(KEY_Y, ny).apply()
        Log.i(TAG, "气泡已贴边折叠: side=$side x=$nx y=$ny")
    }

    /**
     * 退出贴边态，弹回完整球。
     *
     * ⚠️ 位置按 [edgeSide] 算，不是按薄片当前坐标 —— 薄片只有 6dp，它贴左边时
     *   中心在屏幕最左，直接用它的 x 会让球有一半在屏幕外。
     */
    private fun exitEdge() {
        if (!edgeCollapsed) return
        val dm = context.resources.displayMetrics
        edgeCollapsed = false
        edgeView?.visibility = View.GONE
        circleView?.visibility = View.VISIBLE

        val sidePx = dp(appearance.size)
        val x = if (edgeSide < 0) dp(EDGE_MARGIN_DP) else dm.widthPixels - sidePx - dp(EDGE_MARGIN_DP)
        val (nx, ny) = clampInto(x, curY, sidePx, sidePx)
        moveTo(nx, ny)
        prefs.edit().putInt(KEY_X, nx).putInt(KEY_Y, ny).apply()
        Log.i(TAG, "气泡已从贴边态弹出: side=$edgeSide x=$nx y=$ny")
    }

    /** 贴边薄片的触摸：只处理点击（拖动它没有意义 —— 它已经贴在边上了）。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun installEdgeTouch(target: View) {
        target.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    downWinX = curX
                    downWinY = curY
                    rawX = curX
                    rawY = curY
                    dragging = true
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return@setOnTouchListener false
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (!moved && (abs(dx) > tapSlop || abs(dy) > tapSlop)) moved = true
                    if (!moved) return@setOnTouchListener true
                    rawX = downWinX + dx.roundToInt()
                    rawY = downWinY + dy.roundToInt()
                    val (nx, ny) = clampInto(rawX, rawY)
                    moveTo(nx, ny)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasDrag = moved
                    dragging = false
                    if (!wasDrag) {
                        // 点它只把球弹回来，**不**直接展开面板。
                        //
                        // ⚠️ 用户实测反馈：贴边收起后点一下，期望先看到悬浮球，结果面板
                        //   直接铺开，观感是"球没了、被聊天窗口顶掉"。展开是球的下一步
                        //   动作，不该由这条 6dp 细条代劳。
                        if (ev.actionMasked == MotionEvent.ACTION_UP) exitEdge()
                    } else {
                        // 拖动薄片本身没意义，但用户可能是想把它挪个位置 —— 直接
                        // 弹回完整球，让他继续正常使用。
                        exitEdge()
                    }
                    true
                }

                else -> false
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 展开 / 收起
    // ══════════════════════════════════════════════════════════════════

    private fun expand() {
        if (expanded) return
        // ⚠️ **展开前先记住收起态的位置。**
        //
        // 展开时为了把 260dp 宽的面板夹进屏幕，`curX` 会被改掉（贴右边时
        // 从 912 变成 180）。收起时如果只做一次 clamp，球就会停在**面板的左上角**
        // 而不是展开前的位置 —— 用户看到的是"点一下收起，球跑别处去了"。
        preExpandX = curX
        preExpandY = curY
        // 贴边态点开时先弹回完整球，否则收起会回到一条缝。
        if (edgeCollapsed) {
            edgeCollapsed = false
            edgeView?.visibility = View.GONE
        }
        expanded = true
        circleView?.visibility = View.GONE
        panelView?.visibility = View.VISIBLE
        // 展开态才允许聚焦 —— 否则点输入框弹不出键盘（见 applyFocusFlags）。
        applyFocusFlags(focusable = true)
        // 展开态面板里已有同一份信息，收起态那个标签要让位。
        hideStatusBubble()
        lastStatus?.let { applyStatus(it) }
        // 转录在球形态下是"脏"的（没渲染），展开时补一次。
        if (transcriptDirty) renderTranscript()
        // 展开后重新夹一次：面板比圆大得多，原来贴边的位置会露到屏幕外
        root?.post {
            val (nx, ny) = clampInto(curX, curY)
            moveTo(nx, ny)
        }
    }

    private fun collapse() {
        if (!expanded) return
        expanded = false
        // 收起态不抢焦点：让下层应用照常收按键，也顺手收掉键盘。
        applyFocusFlags(focusable = false)
        panelInput?.let {
            it.setText("")
            it.clearFocus()
        }
        panelView?.visibility = View.GONE
        circleView?.visibility = View.VISIBLE
        // 回到收起态，状态标签重新露面（内容与之前相同，所以会重新计时 2 秒）。
        statusKey = ""
        lastStatus?.let { refreshStatusBubble(it) }
        // 回到展开前的位置（不是面板左上角）。
        //
        // ⚠️ 这里**不能**用 `clampInto(curX, curY)`：那个重载读的是 `root` 当前尺寸，
        //   而此刻布局还没重测完，读到的可能仍是展开态的 300×500，算出来的夹取范围
        //   是错的。收起态尺寸是恒定的（appearance.size），直接按它算。
        root?.post {
            val side = dp(appearance.size)
            val (nx, ny) = clampInto(preExpandX, preExpandY, side, side)
            moveTo(nx, ny)
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 面板拖动 + 缩放
    // ══════════════════════════════════════════════════════════════════

    /**
     * 面板拖动：手势载体是顶栏。
     *
     * ⚠️ 复用 [installTouch] 而不是另写一套：拖动逻辑（绝对坐标累加、点击/拖动
     *   判别、夹取）完全一样，只有"点击"的语义不同（顶栏点一下没有动作）。
     */
    private fun installPanelDrag(header: View) {
        installTouch(header, onTap = { /* 顶栏点一下不做任何事 */ }, allowEdgeCollapse = false)
    }

    /**
     * 面板缩放：拖底部手柄。
     *
     * 竖直方向改**转录区高度**（面板其余部分是固定高度），水平方向改面板宽度。
     *
     * ⚠️ 只改转录区高度、不改面板总高：面板是 `wrap_content`，改内容高度它自己会
     *   长高。反过来（直接给面板定高）会让输入框与顶栏被挤掉 —— 用户拖一下就把
     *   发送按钮拖没了，那是灾难。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun installPanelResize(grip: View) {
        grip.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    downWidth = panelView?.width ?: dp(appearance.panelWidth)
                    downHeight = panelScroll?.height ?: dp(200)
                    resizing = true
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!resizing) return@setOnTouchListener false
                    val dm = context.resources.displayMetrics
                    val dx = (ev.rawX - downRawX).roundToInt()
                    val dy = (ev.rawY - downRawY).roundToInt()

                    // 宽度：夹在 [最小宽, 屏幕宽] 之内
                    val maxW = dm.widthPixels
                    val newW = (downWidth + dx).coerceIn(dp(PANEL_MIN_WIDTH_DP), maxW)
                    panelView?.let { p ->
                        (p.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                            if (lp.width != newW) {
                                lp.width = newW
                                p.layoutParams = lp
                            }
                        }
                    }

                    // 高度：只改转录区
                    val maxH = (dm.heightPixels - dp(220)).coerceAtLeast(dp(TRANSCRIPT_MIN_DP))
                    val newH = (downHeight + dy).coerceIn(dp(TRANSCRIPT_MIN_DP), maxH)
                    panelScroll?.let { s ->
                        (s.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                            if (lp.height != newH) {
                                lp.height = newH
                                s.layoutParams = lp
                            }
                        }
                    }
                    // 面板变宽/变高后可能露出屏幕，拖完前先夹一次位置
                    val (nx, ny) = clampInto(curX, curY)
                    if (nx != curX || ny != curY) moveTo(nx, ny)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!resizing) return@setOnTouchListener false
                    resizing = false
                    persistPanelSize()
                    true
                }

                else -> false
            }
        }
    }

    /** 把当前面板宽度与转录区高度落盘（下次开还是这个尺寸）。 */
    private fun persistPanelSize() {
        val w = panelView?.width ?: 0
        val h = panelScroll?.height ?: 0
        prefs.edit()
            .putInt(KEY_PANEL_W, if (w > 0) w else 0)
            .putInt(KEY_TRANSCRIPT_H, if (h > 0) h else 0)
            .apply()
        // 顺手把「面板总高」写回外观配置：插件读 bubbleAppearance() 时能看到用户
        // 手动调过的大小，而不是它当初设的那个值。
        val total = panelView?.height ?: 0
        if (total > 0) {
            appearance = appearance.copy(panelHeight = (total / density).roundToInt())
            BubbleAppearance.save(context, appearance)
        }
        Log.i(TAG, "面板尺寸已保存: ${w}px × ${h}px（转录区）")
    }

    /** 恢复上次保存的面板尺寸。 */
    private fun restorePanelSize() {
        val dm = context.resources.displayMetrics
        prefs.getInt(KEY_PANEL_W, 0).takeIf { it > 0 }?.let { w ->
            panelView?.let { p ->
                (p.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                    lp.width = w.coerceIn(dp(PANEL_MIN_WIDTH_DP), dm.widthPixels)
                    p.layoutParams = lp
                }
            }
        }
        prefs.getInt(KEY_TRANSCRIPT_H, 0).takeIf { it > 0 }?.let { h ->
            panelScroll?.let { s ->
                (s.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                    lp.height = h
                    s.layoutParams = lp
                }
            }
        }
        // 插件设过 panelHeight（且用户没手动调过）时以插件为准。
        if (prefs.getInt(KEY_TRANSCRIPT_H, 0) <= 0 && appearance.panelHeight > 0) {
            panelView?.post { applyConfiguredPanelHeight() }
        }
    }

    /**
     * 按插件配置的 `panelHeight`（面板**总高**）反推转录区高度。
     *
     * ⚠️ 必须 post 到布局之后：固定部分（顶栏 + 标题 + 输入行 + 手柄）的实际高度
     *   只有量过才知道，配置里给的是总高。差值就是转录区该占的高度。
     */
    private fun applyConfiguredPanelHeight() {
        val total = appearance.effectivePanelHeight(
            context.resources.displayMetrics.heightPixels, density,
        )
        if (total <= 0) return
        val p = panelView ?: return
        val s = panelScroll ?: return
        val fixed = p.height - s.height
        if (fixed <= 0) return
        val target = (total - fixed).coerceAtLeast(dp(TRANSCRIPT_MIN_DP))
        (s.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.height = target
            s.layoutParams = lp
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 面板交互
    // ══════════════════════════════════════════════════════════════════

    private fun installPanelActions(view: View) {
        // 「打开」：回到 App（气泡会在 onStart 里被收起）
        view.findViewById<TextView>(R.id.panelOpenApp).setOnClickListener {
            openApp?.invoke()
        }

        // 「收起」：退回圆形
        view.findViewById<TextView>(R.id.panelCollapse).setOnClickListener { collapse() }

        // 「发送」：把输入框内容投给页面里的官方 composer
        view.findViewById<TextView>(R.id.panelSend).setOnClickListener { doSend() }

        // 「停止」：中断当前这一轮。页面侧会先验主按钮此刻是不是停止键 ——
        // 盲点它会把用户的草稿发出去。
        view.findViewById<TextView>(R.id.panelStop).setOnClickListener { doStop() }

        // 回车也发送。多行内容靠换行符一次性送进去（手机上打不出 Shift+Enter）。
        panelInput?.setOnEditorActionListener { _, actionId, event ->
            val isSend = actionId == EditorInfo.IME_ACTION_SEND ||
                (event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                    event.action == android.view.KeyEvent.ACTION_DOWN)
            if (isSend) {
                doSend()
                true
            } else false
        }

        // ⚠️ **点输入框必须主动拉一次输入法。**
        //
        // 悬浮窗不是 Activity，系统不会替它自动弹键盘：窗口拿到焦点 ≠ 输入法会
        // 出现。真机实测（华为 CDY-TN90 / Android 10）：只设可聚焦标志的话，
        // 点输入框有光标、能收到按键事件，但键盘始终不弹。
        //
        // 所以显式调 `showSoftInput`。放在 `post` 里是为了等窗口真正拿到焦点 ——
        // 展开动画/updateViewLayout 之后焦点才生效，同步调会静默失败。
        panelInput?.setOnFocusChangeListener { _, hasFocus ->
            // 聚焦 ⇒ 键盘要来，先给面板腾出空间（否则输入框被键盘盖住，用户看不见
            // 自己在打什么）；失焦 ⇒ 键盘要走，把空间还回去。高度用 MainActivity
            // 学到的真实值，取不到就按屏高的 40% 估一个 —— 宁可多留一点，
            // 也不能让输入框藏在键盘底下。
            applyImeHeight(if (hasFocus) keyboardHeightPx() else 0)
            if (hasFocus) {
                panelInput?.post {
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                        as? android.view.inputmethod.InputMethodManager
                    imm?.showSoftInput(
                        panelInput,
                        android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT,
                    )
                }
            }
        }

        // 点一下输入框本身也要触发上面那条 —— 已经聚焦时再点不会触发
        // onFocusChange，但用户很可能就是"再点一次想唤出键盘"。
        panelInput?.setOnClickListener {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(
                panelInput,
                android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT,
            )
        }
    }

    private fun doSend() {
        val input = panelInput ?: return
        val text = input.text?.toString().orEmpty().trim()
        if (text.isEmpty()) return
        val fn = sendText
        if (fn == null) {
            toast("发送通道未就绪")
            return
        }
        val ok = try {
            fn(text)
        } catch (t: Throwable) {
            Log.w(TAG, "气泡发送失败: ${t.message}")
            false
        }
        if (ok) {
            input.setText("")
            toast("已发送")
        } else {
            toast("发送失败，请打开 App")
        }
    }

    private fun toast(msg: String) {
        runCatching { android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show() }
    }

    /**
     * 中断当前这一轮。
     *
     * ⚠️ 只回报"已投递"：真正的执行结果由页面决定（它可能发现主按钮此刻不是
     *   停止键而拒绝）。日志里会有 `STOPPED` / `NOT_RUNNING` 可查。
     */
    private fun doStop() {
        val fn = stopRun
        if (fn == null) {
            toast("停止通道未就绪")
            return
        }
        val ok = try {
            fn()
        } catch (t: Throwable) {
            Log.w(TAG, "气泡停止失败: ${t.message}")
            false
        }
        toast(if (ok) "已请求中断" else "中断失败，请打开 App")
    }

    // ══════════════════════════════════════════════════════════════════
    // 状态渲染
    // ══════════════════════════════════════════════════════════════════

    /**
     * 把一份状态快照刷到气泡上。
     *
     * ⚠️ 全程 try/catch：气泡是锦上添花，任何渲染异常都不能影响引擎与主界面。
     */
    private fun applyStatus(status: EngineStatus) {
        lastStatus = status
        try {
            paintDot(dotView, status.running)
            paintDot(panelDot, status.running, small = true)
            paintDot(edgeDot, status.running, small = true)
            refreshStatusBubble(status)

            // 「停止」只在 AI 真在跑时出现。常驻的话用户会去点它 ——
            // 而空闲时那个位置的主按钮是「发送消息」，点下去会把草稿发出去。
            panelStop?.visibility = if (status.running) View.VISIBLE else View.GONE

            val action = when {
                status.text.isNotEmpty() -> status.text
                status.running -> "思考中…"
                else -> "空闲"
            }
            panelAction?.text = action

            panelTitle?.let {
                if (status.title.isNotEmpty()) {
                    it.text = status.title
                    it.visibility = View.VISIBLE
                } else {
                    it.visibility = View.GONE
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "刷新气泡状态失败: ${t.message}")
        }
    }

    /**
     * 按一份状态决定收起态标签显示什么。
     *
     * **空闲且没有动作时不显示** —— 一个写着「空闲」的标签对用户毫无价值，
     * 反而常驻在屏幕上碍事。只在真有进展可看时才冒出来。
     *
     * ⚠️ 正文（`body`）刻意不放进标签：它是整段助手消息，几行字根本装不下，
     *   截断后只剩一句没头没尾的话。标签只放**动作**（"正在运行命令 · sleep 10"
     *   这类短句），要看正文就展开面板。
     */
    private fun refreshStatusBubble(status: EngineStatus) {
        // AI 主动推的话优先。引擎状态每几秒就推一次，不给它让路的话
        // 推送会在下一帧就被冲掉 —— 那这个能力等于不存在。
        val pushed = pushedStatus
        if (pushed.isNotEmpty()) {
            showStatusBubble(pushed, status.running, pushedAutoHideMs())
            return
        }
        val text = when {
            status.text.isNotEmpty() -> status.text
            status.running -> "思考中…"
            // 空闲时若还有正文（上一轮的结论），也值得露一句 —— 但只取首行，
            // 且限长，否则标签会变成一大块。
            status.body.isNotEmpty() -> firstLine(status.body)
            else -> ""
        }
        showStatusBubble(text, status.running)
    }

    // ══════════════════════════════════════════════════════════════════
    // AI 主动推送状态消息
    // ══════════════════════════════════════════════════════════════════

    /**
     * 让 AI 往球上方的状态气泡推一条消息。
     *
     * 与引擎状态（[applyStatus]）是**两条独立的通道**：引擎状态是"页面里发生了什么"
     * 的只读镜像，这个是 AI 主动要说的话。推送期间前者让位（见 [refreshStatusBubble]）。
     *
     * @param text 要显示的文案；传空串即取消推送、立刻回到引擎状态。
     * @param durationMs 停留时长（ms）；≤0 表示用外观配置里的 `statusAutoHideMs`。
     * @param sticky 常驻，不自动消失（`durationMs` 被忽略）。
     */
    fun pushStatus(text: String, durationMs: Long, sticky: Boolean) {
        statusHandler.removeCallbacks(pushedClearRunnable)
        val trimmed = text.trim().take(200)
        if (trimmed.isEmpty()) {
            pushedStatus = ""
            pushedSticky = false
            pushedDurationMs = 0L
            lastStatus?.let { refreshStatusBubble(it) }
            return
        }
        pushedStatus = trimmed
        pushedSticky = sticky
        pushedDurationMs = if (durationMs > 0) durationMs else 0L
        if (!sticky) {
            // 时长未知（durationMs ≤ 0）时不排清除：交给 statusAutoHideMs 那套
            // 自动消失逻辑收尾，免得推送已经消失了推送标记还挂着。
            if (pushedDurationMs > 0) {
                statusHandler.postDelayed(pushedClearRunnable, pushedDurationMs + STATUS_PUSH_GRACE_MS)
            }
        }
        // 用当前状态刷新一次：这样即使引擎状态没变（key 相同），推送也能立刻上屏。
        lastStatus?.let { refreshStatusBubble(it) } ?: run {
            statusKey = ""
            showStatusBubble(pushedStatus, false, pushedAutoHideMs())
        }
    }

    /** 推送该停留多久（ms）；0 = 常驻（不自动消失）。 */
    private fun pushedAutoHideMs(): Int = when {
        pushedSticky -> 0
        pushedDurationMs > 0 -> pushedDurationMs.toInt()
        else -> appearance.statusAutoHideMs
    }

    /** 推送到期：清掉推送标记并回到引擎状态。 */
    private val pushedClearRunnable = Runnable {
        pushedStatus = ""
        pushedSticky = false
        pushedDurationMs = 0L
        // ⚠️ 先清 key 再刷新：否则内容相同的引擎状态会被 key 判成"没变"而不上屏，
        //   横条就一直空着（自动消失已经把它隐藏了，但状态其实还在变）。
        statusKey = ""
        lastStatus?.let { refreshStatusBubble(it) }
    }

    /** 取首行并限长。标签是单行小标签，多行内容会把球顶得老高。 */
    private fun firstLine(text: String): String {
        val line = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (line.length <= statusMaxChars) line else line.take(statusMaxChars - 1) + "…"
    }

    /**
     * 状态点：在跑=实心绿，空闲=空心灰。
     *
     * ## 大点带一圈白边（只有收起态的头像气泡需要）
     *
     * 头像的头发是深蓝色，灰/绿的圆点直接贴上去对比度很低、看不清。垫一圈白边
     * 把它从背景里"抠"出来 —— 与系统通知角标同一种手法。
     *
     * ⚠️ 白边用 `LayerDrawable` 套两层 Oval 实现，**不用 `<layer-list>` 资源**：
     *   这里本来就要动态改色，做成资源反而要多传参数。白边层是外层（稍大），
     *   颜色层是内层，靠 `setLayerInset` 缩进去。
     *
     * ⚠️ 只换 background，**绝不碰 layoutParams**：`dotView` 在 FrameLayout 里、
     *   `panelDot`/`statusDot`/`edgeDot` 在 LinearLayout/FrameLayout 里，
     *   两者的 LayoutParams 类型不同，强行赋一个 FrameLayout.LayoutParams 会
     *   `ClassCastException`。尺寸由 XML 与 [applyAppearanceToViews] 管。
     */
    private fun paintDot(v: View?, running: Boolean, small: Boolean = false) {
        if (v == null) return
        // 小点（4~8dp）用 1dp 描边，大点（14dp）用 2dp —— 同样 2dp 在 8dp 的圆上
        // 会糊成一坨，看不出是空心。
        val strokePx = if (small) dp(1) else dp(2)
        val fill = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (running) {
                setColor(colorOr(appearance.panelAccent, DEFAULT_RUNNING))
            } else {
                setColor(Color.TRANSPARENT)
                setStroke(strokePx, 0xFFB9BFC7.toInt())
            }
        }
        if (small) {
            // 面板/薄片里的小点直接贴在浅色底上，不需要白边
            v.background = fill
            return
        }
        val border = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFFFFFFF.toInt())
        }
        val inset = dp(2)
        v.background = LayerDrawable(arrayOf(border, fill)).apply {
            setLayerInset(1, inset, inset, inset, inset)
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 会话转录渲染（展开面板的核心内容）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 收到一份新的转录。
     *
     * ⚠️ 球形态下**不渲染**，只置脏标记 —— 面板不可见时重建几十个 View 是白费，
     *   而转录在 AI 跑动时每几秒就会推一次。展开时（[expand]）会补渲染。
     */
    private fun applyTranscript(transcript: ChatTranscript) {
        lastTranscript = transcript
        transcriptDirty = true
        if (expanded) renderTranscript()
    }

    /**
     * 把转录渲染成消息行列表。
     *
     * ## 为什么要按指纹跳过
     *
     * 状态通道每 5 秒推一次，转录通道在 DOM 变化时推得更密。如果每次都清空重建
     * 整个 View 树，用户会看到消息在闪、滚动位置也守不住。指纹相同就直接返回。
     *
     * ## 滚动位置
     *
     * 重建后如果用户**本来就在底部**，自动滚到底（流式输出时这是他要的）；
     * 如果他往上翻了在看历史，就**不要**动 —— 强行拽回底部是最恼人的行为。
     */
    private fun renderTranscript() {
        val container = panelTranscript ?: return
        val t = lastTranscript ?: return
        transcriptDirty = false

        val fp = t.items.joinToString("\u0001") { "${it.role}|${it.running}|${it.text}" }
        if (fp == transcriptFingerprint) return
        transcriptFingerprint = fp

        val scroll = panelScroll
        // 判定"重建前是否贴着底部"，重建后再决定要不要跟到底
        val wasAtBottom = scroll?.let { s ->
            val child = s.getChildAt(0)
            child == null || s.scrollY + s.height >= child.height - dp(24)
        } ?: true

        // ── 增量更新 ──
        //
        // 流式输出期间这条路径每秒要走好几次（页面侧节流 250ms），全量
        // `removeAllViews()` + 重建会让整块内容闪一下，正好把"流式"的观感毁掉。
        // 所以：**前缀 role 序列一致就复用已有 view，只改文本**；role 序列变了
        // （用户插话、条目整体前移）才退回全量重建。
        //
        // 复用之所以安全：同一个 role ⇒ 同一套样式（对齐 / 底色 / 字号），
        // 复用时不需要也不应该重设样式。
        val reuseCount = if (
            t.items.isNotEmpty() &&
            container.childCount == renderedRoles.size &&
            renderedRoles.size <= t.items.size &&
            t.items.take(renderedRoles.size).map { it.role } == renderedRoles
        ) renderedRoles.size else -1

        if (reuseCount < 0) {
            container.removeAllViews()
            renderedRoles = emptyList()
        }

        val inflater = LayoutInflater.from(context)
        for (i in t.items.indices) {
            val item = t.items[i]
            if (i < reuseCount) {
                updateItemText(container.getChildAt(i), item)
            } else {
                container.addView(renderItem(inflater, item))
            }
        }
        if (t.items.isEmpty() && container.childCount == 0) {
            container.addView(
                makeEmptyHint(),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        renderedRoles = t.items.map { it.role }

        if (wasAtBottom) {
            scroll?.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /**
     * 复用已有行时只更新文本（见 [renderTranscript] 的增量分支）。
     *
     * ⚠️ 标签走 [roleLabel]，与 [renderItem] 同源 —— 两处各写一份的话，
     *   改文案时必然漂移成"新建的行和复用的行长得不一样"。
     */
    private fun updateItemText(row: View, item: ChatTranscript.Item) {
        val roleView = row.findViewById<TextView>(R.id.msgRole) ?: return
        val bodyView = row.findViewById<TextView>(R.id.msgBody) ?: return
        if (bodyView.text?.toString() != item.text) bodyView.text = item.text
        val label = roleLabel(item)
        if (roleView.text?.toString() != label) roleView.text = label
    }

    /** 一条消息的角色标签。用户消息不显示（蓝色气泡本身就是标志）。 */
    private fun roleLabel(item: ChatTranscript.Item): String = when (item.role) {
        ChatTranscript.Role.TOOL -> if (item.running) "工具调用 · 进行中" else "工具调用"
        ChatTranscript.Role.ERROR -> "错误"
        ChatTranscript.Role.REASONING -> if (item.running) "AI 思考 · 进行中" else "AI 思考"
        ChatTranscript.Role.ASSISTANT -> if (item.running) "AI · 正在输出" else "AI"
        else -> item.role
    }

    /** 转录为空时的提示。空面板会让人以为界面坏了。 */
    private fun makeEmptyHint(): TextView = TextView(context).apply {
        text = "（这个会话还没有内容）"
        textSize = 12f
        setTextColor(colorOr(appearance.panelText, DEFAULT_TEXT) and 0x00FFFFFF or 0x66000000)
        gravity = Gravity.CENTER
        setPadding(0, dp(12), 0, dp(12))
    }

    /**
     * 渲染一条消息。
     *
     * 五种角色的排版：
     *
     * | role | 对齐 | 底色 | 文字 | 角色标签 |
     * |---|---|---|---|---|
     * | user | 右 | 强调色 | 白 | 不显示（蓝泡本身就是标志） |
     * | assistant | 左 | 白 | 深 | 「AI」 |
     * | tool | 左 | 浅灰 | 深灰 | 「工具调用」+ 运行中标记 |
     * | error | 左 | 浅红 | 深红 | 「错误」 |
     * | other | 左 | 白 | 深 | 原始 role |
     *
     * ⚠️ 左右对齐靠**行容器 gravity**，不是 margin：两者共用同一个 XML 模板，
     *   只有 gravity 与背景不同。
     * ⚠️ 正文 **不设 maxLines**：这里就是要看完整内容，行数多靠外层 ScrollView 滚。
     */
    private fun renderItem(inflater: LayoutInflater, item: ChatTranscript.Item): View {
        val row = inflater.inflate(R.layout.bubble_msg, panelTranscript, false) as LinearLayout
        val roleView = row.findViewById<TextView>(R.id.msgRole)
        val bodyView = row.findViewById<TextView>(R.id.msgBody)

        val accent = colorOr(appearance.panelAccent, DEFAULT_ACCENT)
        val textColor = colorOr(appearance.panelText, DEFAULT_TEXT)

        // 气泡宽度上限：面板宽度的 84%，留出左右各自的边距 —— 否则用户消息
        // （右对齐）会一直顶到面板最左边，跟助手消息糊在一起分不清。
        val panelW = panelView?.width?.takeIf { it > 0 }
            ?: appearance.effectivePanelWidth(context.resources.displayMetrics.widthPixels, density)
        val maxBubble = (panelW * 0.84f).roundToInt()

        when (item.role) {
            ChatTranscript.Role.USER -> {
                row.gravity = Gravity.END
                roleView.visibility = View.GONE
                bodyView.background = bubbleShape(accent, dp(14))
                bodyView.setTextColor(Color.WHITE)
                bodyView.maxWidth = maxBubble
                bodyView.text = item.text
            }

            ChatTranscript.Role.TOOL -> {
                row.gravity = Gravity.START
                roleView.visibility = View.VISIBLE
                roleView.text = roleLabel(item)
                roleView.setTextColor(0xFF8A9099.toInt())
                bodyView.background = bubbleShape(0xFFEDEFF2.toInt(), dp(10))
                bodyView.setTextColor(0xFF3A4048.toInt())
                bodyView.maxWidth = maxBubble
                bodyView.textSize = 11.5f
                bodyView.text = item.text
            }

            ChatTranscript.Role.ERROR -> {
                row.gravity = Gravity.START
                roleView.visibility = View.VISIBLE
                roleView.text = roleLabel(item)
                roleView.setTextColor(0xFFE5484D.toInt())
                bodyView.background = bubbleShape(0xFFFDECEC.toInt(), dp(10))
                bodyView.setTextColor(0xFF8A1F22.toInt())
                bodyView.maxWidth = maxBubble
                bodyView.text = item.text
            }

            ChatTranscript.Role.ASSISTANT -> {
                row.gravity = Gravity.START
                roleView.visibility = View.VISIBLE
                roleView.text = roleLabel(item)
                roleView.setTextColor(0xFF8A9099.toInt())
                bodyView.background = bubbleShape(0xFFFFFFFF.toInt(), dp(12))
                bodyView.setTextColor(textColor)
                bodyView.maxWidth = maxBubble
                bodyView.text = item.text
            }

            ChatTranscript.Role.REASONING -> {
                // 思考：灰底 + 深灰字 + 小一号，与正文（白底深字）一眼可分。
                // 不隐藏 —— 用户明确要求「微博里能看到的回复内容全部都要有」。
                row.gravity = Gravity.START
                roleView.visibility = View.VISIBLE
                roleView.text = roleLabel(item)
                roleView.setTextColor(0xFF8A9099.toInt())
                bodyView.background = bubbleShape(0xFFF2F3F5.toInt(), dp(10))
                bodyView.setTextColor(0xFF5A6068.toInt())
                bodyView.textSize = 12f
                bodyView.maxWidth = maxBubble
                bodyView.text = item.text
            }

            else -> {
                row.gravity = Gravity.START
                roleView.visibility = View.VISIBLE
                roleView.text = item.role
                roleView.setTextColor(0xFF8A9099.toInt())
                bodyView.background = bubbleShape(0xFFFFFFFF.toInt(), dp(12))
                bodyView.setTextColor(textColor)
                bodyView.maxWidth = maxBubble
                bodyView.text = item.text
            }
        }
        return row
    }

    /** 消息气泡的底：纯色 + 圆角。 */
    private fun bubbleShape(color: Int, radiusPx: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx.toFloat()
        setColor(color)
    }

    val isShown: Boolean get() = attached
}
