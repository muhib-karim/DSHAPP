package com.dsh.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
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
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
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
 * ## 形态
 *
 * 两种形态共用一个 window（靠 visibility 切换，不重建 View）：
 *
 * - **收起**：56dp 圆形按钮 + 一个状态圆点（实心绿=在跑，空心灰=空闲）。
 * - **展开**：300dp 面板 —— 当前动作 / 会话标题 / 最近输出（可滚动）/ 输入框 + 发送。
 *
 * 后续要做"桌宠"形态时，替换 [circleView] 里的内容即可，其余逻辑不用动。
 *
 * ## 三个必须知道的平台事实
 *
 * ### ① 权限是特殊的，拿不到就没救
 *
 * `SYSTEM_ALERT_WINDOW` 不能运行时请求。未授权时 `WindowManager.addView` 抛
 * `BadTokenException: permission denied for window type 2038`。所以 [show] 先查
 * [canDrawOverlays]，不满足就直接放弃（由设置页的引导卡负责引导用户去开）。
 *
 * ### ② 输入框要用 TYPE_APPLICATION_OVERLAY + FLAG_ALT_FOCUSABLE_IM
 *
 * 悬浮窗默认**抢不到输入法焦点**：`FLAG_NOT_FOCUSABLE` 让整个 window 收不到按键，
 * 而带上它之后 `EditText` 又永远弹不出键盘。正确组合是：
 *
 * ```
 * type    = TYPE_APPLICATION_OVERLAY (API 26+) / TYPE_PHONE (更低)
 * flags   = FLAG_NOT_TOUCH_MODAL | FLAG_ALT_FOCUSABLE_IM   ← 注意**不含** NOT_FOCUSABLE
 * ```
 *
 * `FLAG_ALT_FOCUSABLE_IM` 的语义是「有输入法时才可聚焦」——它让 window 平时不抢焦点
 * （下层 App 照常收按键），但点输入框时能拉起键盘。这是悬浮窗输入框的标准解法。
 *
 * ⚠️ 拖动时要把 `FLAG_NOT_TOUCH_MODAL` 加上并**同时**设 `FLAG_NOT_FOCUSABLE`，
 * 否则手势会被输入法层截走。本实现改用「按下即 setFlags 切一次」的做法，
 * 见 [applyTouchFlags]。
 *
 * ### ③ 拖动必须用绝对坐标，不能用 getRawX 的差值
 *
 * `WindowManager.updateViewLayout` 的 x/y 是相对**屏幕**的左上角，而 `getRawX()`
 * 是相对屏幕的触点。用 `rawX - downRawX` 得到的是位移量，必须**累加到按下时的
 * window 坐标**上。直接拿 `rawX` 当 x 会让气泡瞬移到手指位置（左下角对齐触点），
 * 这是最容易踩的坑。
 *
 * ## 状态来源
 *
 * 复用既有通道：`client.js` 的 `mpReportStatus()` → `DSHAndroid.onStatus()`
 * → [EngineService.updateStatus]。实测 App 退到后台后 WebView 的 `setInterval`
 * **照常运行**（按 HOME 后 12 秒走满 12 拍），所以状态是实时的，不需要另建引擎订阅。
 */
class FloatingBubble(private val context: Context) {

    companion object {
        private const val TAG = DshApplication.TAG

        /** 收起态直径（dp）。56 是 Material 的 FAB 标准尺寸，手指好点。 */
        private const val CIRCLE_DP = 56

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

        /** 位置持久化。只存 x/y 两个整数，不值得上 DataStore。 */
        private const val PREFS = "dsh_bubble"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"

        /**
         * 气泡是否应当显示。
         *
         * 由 `MainActivity` 在 `onStart`/`onStop` 里设置 —— 只有**退到后台**才显示，
         * 前台时 App 内已有完整界面，气泡纯属碍事。
         *
         * ⚠️ 做成进程级单例而不是每个 Activity 一份：`MainActivity` 转屏会重建，
         * 但气泡应当连续存在，不该闪一下。
         */
        @Volatile
        private var instance: FloatingBubble? = null

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

        /** App 退到后台 → 显示气泡（前提：用户开了开关且已授权）。 */
        fun onAppBackground(context: Context) {
            if (!isEnabled(context)) return
            if (!canDrawOverlays(context)) {
                Log.i(TAG, "气泡开关已开但未授权悬浮窗，不显示")
                return
            }
            val ctx = context.applicationContext
            main.post {
                if (instance?.isShown == true) return@post
                // 半路残留的实例（addView 失败、或转屏把 Activity 拆了）先收干净，
                // 否则旧 window 会一直挂在 WindowManager 上，用户看得见却点不动。
                instance?.hide()
                instance = FloatingBubble(ctx).also { it.show() }
            }
        }

        /** App 回到前台 → 收起气泡。 */
        fun onAppForeground() {
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

        /** 主线程 Handler。气泡的一切 UI 操作都必须回到这里。 */
        /** 主线程 Handler，所有对 View / window 的操作都走它。 */
    private val main = Handler(Looper.getMainLooper())
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var root: View? = null
    private var circleView: View? = null
    private var panelView: View? = null
    private var dotView: View? = null
    private var panelDot: View? = null
    private var panelAction: TextView? = null
    private var panelTitle: TextView? = null
    private var panelBody: TextView? = null
    private var panelScroll: ScrollView? = null
    private var panelInput: EditText? = null
    private var panelStop: View? = null

    /** 当前是否展开。 */
    private var expanded = false

    /** 是否已 addView（避免重复添加 / 重复移除）。 */
    private var attached = false

    /** 当前 window 的左上角坐标（px）。拖动时以它为基准累加位移。 */
    private var curX = 0
    private var curY = 0

    /** 拖动状态。 */
    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var downWinX = 0
    private var downWinY = 0
    private var moved = false

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()
    private fun dp(v: Float) = (v * density).roundToInt()

    private val tapSlop = dp(TAP_SLOP_DP)

    /** 最近一次状态，展开面板时用它填充。 */
    private var lastStatus: EngineStatus? = null

    // ══ 收起态的状态气泡（球上方那个小标签）══════════════════════════════
    //
    // 独立 window。理由见 layout/bubble_status.xml 的头注释：塞进球的 window
    // 会把 window 撑大，而 window 左上角是定位锚点 —— 球本身的位置会跟着偏。
    private var statusRoot: View? = null
    private var statusDot: View? = null
    private var statusText: TextView? = null

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

    /** 状态气泡当前显示的文字，用于去重（内容没变就不重复 setText/移动）。 */
    private var statusShown = ""

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

        val view = LayoutInflater.from(context).inflate(R.layout.bubble_window, null)
        root = view
        circleView = view.findViewById(R.id.bubbleCircle)
        panelView = view.findViewById(R.id.bubblePanel)
        dotView = view.findViewById(R.id.bubbleDot)
        panelDot = view.findViewById(R.id.panelDot)
        panelAction = view.findViewById(R.id.panelAction)
        panelTitle = view.findViewById(R.id.panelTitle)
        panelBody = view.findViewById(R.id.panelBody)
        panelScroll = view.findViewById(R.id.panelScroll)
        panelInput = view.findViewById(R.id.panelInput)
        panelStop = view.findViewById(R.id.panelStop)

        paintDot(dotView, running = false)
        paintDot(panelDot, running = false, small = true)

        // 位置：优先用上次记住的；没有就贴右侧中部
        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels
        curX = prefs.getInt(KEY_X, screenW - dp(CIRCLE_DP) - dp(EDGE_MARGIN_DP))
        curY = prefs.getInt(KEY_Y, (screenH * 0.42f).roundToInt())
        clampInto(curX, curY).let { curX = it.first; curY = it.second }

        installTouch(circleView!!)
        installPanelActions(view)

        val params = baseParams().apply { x = curX; y = curY }
        try {
            wm.addView(view, params)
            attached = true
            Log.i(TAG, "气泡已显示: ($curX, $curY)")
            // 主动拉一次当前状态：气泡是刚创建的，错过了之前所有的推送，
            // 不拉的话会停在"空闲"直到下一次状态变化（可能几十秒后）。
            EngineService.currentStatus()?.let { applyStatus(it) }
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
        runCatching { wm.removeViewImmediate(v) }
        root = null
        circleView = null
        panelView = null
        panelInput = null
        expanded = false
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
     */
    private fun showStatusBubble(text: String, running: Boolean) {
        if (text.isEmpty() || expanded) {
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
        }

        // 内容没变就不动 —— 状态每秒刷新一次，无脑 setText 会一直触发重排，
        // 表现为标签文字轻微抖动。
        if (statusShown != text) {
            statusShown = text
            statusText?.text = text
            paintDot(statusDot, running, small = true)
        } else if (running) {
            // 文案没变但运行态变了（比如从"空闲"变成"思考中"又变回来），
            // 点色也要跟上。
            paintDot(statusDot, running, small = true)
        }
        positionStatusBubble()
    }

    private fun hideStatusBubble() {
        val v = statusRoot ?: return
        statusRoot = null
        statusDot = null
        statusText = null
        statusShown = ""
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
            val circleW = root?.width?.takeIf { it > 0 } ?: dp(CIRCLE_DP)

            // 标签还没量过尺寸时（刚 addView）先按屏幕内夹一次，量完会再调一次。
            val labelW = v.width.takeIf { it > 0 } ?: 0
            val centered = curX + (circleW - labelW) / 2
            p.x = centered.coerceIn(0, (dm.widthPixels - labelW).coerceAtLeast(0))

            // 竖直方向：默认贴在球上方。上方空间不够（球贴着顶部）时改放球下方 ——
            // 否则标签会被状态栏挡住或直接跑出屏幕。
            val labelH = v.height.takeIf { it > 0 } ?: 0
            val above = curY - statusGapPx - labelH
            p.y = if (labelH > 0 && above < dp(TOP_MARGIN_DP)) {
                curY + circleH() + statusGapPx
            } else {
                above
            }
            runCatching { wm.updateViewLayout(v, p) }
        } finally {
            positioning = false
        }
    }

    /** 球的当前高度（量过就用实测值，否则退回标称直径）。 */
    private fun circleH(): Int = root?.height?.takeIf { it > 0 } ?: dp(CIRCLE_DP)

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
     * | 收起 | `NOT_FOCUSABLE` | 不抢焦点，下层应用照常收按键，也不弹键盘 |
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
    // 拖动 + 点击 + 边缘吸附
    // ══════════════════════════════════════════════════════════════════

    @SuppressLint("ClickableViewAccessibility")
    private fun installTouch(target: View) {
        target.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    downWinX = curX
                    downWinY = curY
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
                    val (nx, ny) = clampInto(downWinX + dx.roundToInt(), downWinY + dy.roundToInt())
                    moveTo(nx, ny)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasDrag = moved
                    dragging = false
                    if (!wasDrag) {
                        // 没移动 ⇒ 当成点击：收起态展开、展开态无操作
                        if (ev.actionMasked == MotionEvent.ACTION_UP) onTapped(target)
                    } else {
                        snapToEdge()
                    }
                    true
                }

                else -> false
            }
        }
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
        val w = v?.width?.takeIf { it > 0 } ?: dp(CIRCLE_DP)
        val h = v?.height?.takeIf { it > 0 } ?: dp(CIRCLE_DP)
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
        val w = v.width.takeIf { it > 0 } ?: dp(CIRCLE_DP)
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

    private fun onTapped(target: View) {
        if (target === circleView) expand() else Unit
    }

    // ══════════════════════════════════════════════════════════════════
    // 展开 / 收起
    // ══════════════════════════════════════════════════════════════════

    private fun expand() {
        if (expanded) return
        // ⚠️ **展开前先记住收起态的位置。**
        //
        // 展开时为了把 900px 宽的面板夹进屏幕，`curX` 会被改掉（贴右边时
        // 从 912 变成 180）。收起时如果只做一次 clamp，球就会停在**面板的左上角**
        // 而不是展开前的位置 —— 用户看到的是"点一下收起，球跑别处去了"。
        preExpandX = curX
        preExpandY = curY
        expanded = true
        circleView?.visibility = View.GONE
        panelView?.visibility = View.VISIBLE
        // 展开态才允许聚焦 —— 否则点输入框弹不出键盘（见 applyFocusFlags）。
        applyFocusFlags(focusable = true)
        // 展开态面板里已有同一份信息，收起态那个标签要让位。
        hideStatusBubble()
        lastStatus?.let { applyStatus(it) }
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
        // 回到收起态，状态标签重新露面。
        lastStatus?.let { refreshStatusBubble(it) }
        // 回到展开前的位置（不是面板左上角）。
        //
        // ⚠️ 这里**不能**用 `clampInto(curX, curY)`：那个重载读的是 `root` 当前尺寸，
        //   而此刻布局还没重测完，读到的可能仍是展开态的 900×798，算出来的夹取范围
        //   是错的。收起态尺寸是恒定的 56dp，直接按它算。
        root?.post {
            val side = dp(CIRCLE_DP)
            val (nx, ny) = clampInto(preExpandX, preExpandY, side, side)
            moveTo(nx, ny)
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

            // 正文：只在内容真变了时才 setText + 滚到底 —— 否则用户手动往上翻
            // 会被每秒一次的刷新强行拽回底部。
            val body = status.body
            panelBody?.let { tv ->
                if (tv.text?.toString() != body) {
                    tv.text = body.ifEmpty { "（暂无输出）" }
                    panelScroll?.post { panelScroll?.fullScroll(View.FOCUS_DOWN) }
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
     * 这里本来就要动态改色，做成资源反而要多传参数。白边层是外层（稍大），
     * 颜色层是内层，靠 `setLayerInset` 缩进去。
     *
     * ⚠️ 只换 background，**绝不碰 layoutParams**：`dotView` 在 FrameLayout 里、
     * `panelDot`/`statusDot` 在 LinearLayout 里，两者的 LayoutParams 类型不同，
     * 强行赋一个 FrameLayout.LayoutParams 会 `ClassCastException`。
     * 尺寸本来就写死在 XML（14dp / 8dp / 6dp），这里不需要管。
     */
    private fun paintDot(v: View?, running: Boolean, small: Boolean = false) {
        if (v == null) return
        // 小点（6~8dp）用 1dp 描边，大点（14dp）用 2dp —— 同样 2dp 在 8dp 的圆上
        // 会糊成一坨，看不出是空心。
        val strokePx = if (small) dp(1) else dp(2)
        val fill = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (running) {
                setColor(0xFF34C759.toInt())
            } else {
                setColor(Color.TRANSPARENT)
                setStroke(strokePx, 0xFFB9BFC7.toInt())
            }
        }
        if (small) {
            // 面板里的小点直接贴在浅色面板上，不需要白边
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

    val isShown: Boolean get() = attached
}
