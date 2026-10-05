package com.dsh.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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

        /** 打开 App 的回调（气泡面板上那个「打开」）。 */
        @Volatile
        var openApp: (() -> Unit)? = null

        /** 主线程 Handler。气泡的一切 UI 操作都必须回到这里。 */
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

        paintDot(dotView, running = false)
        paintDot(panelDot, running = false)

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
        runCatching { wm.removeViewImmediate(v) }
        root = null
        circleView = null
        panelView = null
        panelInput = null
        expanded = false
    }

    private fun baseParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        overlayType(),
        // ⚠️ 不含 FLAG_NOT_FOCUSABLE —— 含了输入框永远弹不出键盘。
        //    靠 FLAG_ALT_FOCUSABLE_IM 实现"平时不抢焦点、点输入框才聚焦"。
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
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
    }

    /** 夹进可见区域（考虑状态栏与底部手势条）。 */
    private fun clampInto(x: Int, y: Int): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        val v = root
        val w = v?.width?.takeIf { it > 0 } ?: dp(CIRCLE_DP)
        val h = v?.height?.takeIf { it > 0 } ?: dp(CIRCLE_DP)
        val minY = dp(TOP_MARGIN_DP)
        val maxY = (dm.heightPixels - h - dp(BOTTOM_MARGIN_DP)).coerceAtLeast(minY)
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
        expanded = true
        circleView?.visibility = View.GONE
        panelView?.visibility = View.VISIBLE
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
        panelInput?.let {
            it.setText("")
            it.clearFocus()
        }
        panelView?.visibility = View.GONE
        circleView?.visibility = View.VISIBLE
        root?.post {
            val (nx, ny) = clampInto(curX, curY)
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
            paintDot(panelDot, status.running)

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
     * 状态点：在跑=实心绿，空闲=空心灰。
     *
     * ⚠️ 只换 background，**绝不碰 layoutParams**：`dotView` 在 FrameLayout 里、
     * `panelDot` 在 LinearLayout 里，两者的 LayoutParams 类型不同，
     * 强行赋一个 FrameLayout.LayoutParams 会 `ClassCastException`。
     * 尺寸本来就写死在 XML（22dp / 8dp），这里不需要管。
     */
    private fun paintDot(v: View?, running: Boolean) {
        if (v == null) return
        // 小点（8dp）用 1dp 描边，大点（22dp）用 2dp —— 同样 2dp 在 8dp 的圆上
        // 会糊成一坨，看不出是空心。
        val strokePx = if (v === dotView) dp(2) else dp(1)
        v.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (running) {
                setColor(0xFF34C759.toInt())
            } else {
                setColor(Color.TRANSPARENT)
                setStroke(strokePx, 0xFFB9BFC7.toInt())
            }
        }
    }

    val isShown: Boolean get() = attached
}
