package com.dsh.android

import android.content.Context
import com.dsh.android.log.DshLog
import org.json.JSONObject

/**
 * 键盘避让的**开关状态**与**无障碍权限**。
 *
 * 这两件事合成一个小对象，因为它们在设置页里是同一块：
 *
 * | 事实 | 存在哪 | 谁改 |
 * |---|---|---|
 * | 用户要不要这个功能 | SharedPreferences | 设置页开关（[setEnabled]） |
 * | 无障碍授权有没有 | `Settings.Secure` | 系统设置页（[requestPermission] 跳过去） |
 *
 * ## 为什么开关默认是开的
 *
 * 它**没有副作用**：没拿到无障碍权限时整个链路是空转（`BubbleA11yService` 收不到
 * 任何窗口信息，`FloatingBubble` 也不会弹）。默认开 = 用户授权完立刻就有行为，
 * 不需要再找一次开关。关掉它是"我知道这个功能，但我不想要"。
 *
 * ⚠️ 开关状态**不参与** `BubbleAppearance` 的 JSON：那个是 AI 可改的外观配置，
 *   而这个开关决定的是「要不要向系统要权限」，属于用户决策，不该让模型改写。
 */
internal object KeyboardAvoid {

    private const val TAG = "DSH"

    /** 与悬浮球共用一份 SharedPreferences（本来就是同一个功能的配置）。 */
    private const val PREFS = BubbleAppearance.PREFS
    private const val KEY_ENABLED = "keyboard_avoid_enabled"

    /** 功能开关（默认开）。 */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    /**
     * 改功能开关，并把新状态推给**正在显示的**那个悬浮球。
     *
     * ⚠️ 必须推：悬浮球实例里缓存了一份（每帧都要判，不能每帧读盘）。不推的话
     *   用户关掉开关后球仍会避让，直到下次显示才生效 —— 看起来像开关坏了。
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        DshLog.i(TAG, "键盘避让开关: $enabled")
        FloatingBubble.onKeyboardAvoidChanged(enabled)
    }

    /** 无障碍权限是否已授予（用户是否在系统设置里打开了「DSH 键盘哨兵」）。 */
    fun isPermissionGranted(context: Context): Boolean =
        BubbleA11yService.isEnabled(context)

    /** 跳到系统无障碍设置页，让用户打开「DSH 键盘哨兵」。 */
    fun requestPermission(context: Context) {
        DshLog.i(TAG, "设置页请求无障碍权限，跳转系统设置")
        BubbleA11yService.openSettings(context)
    }

    /**
     * 给设置页读的状态（JSON 字符串）。
     *
     * `enabled` 与 `a11y` 是**两个独立事实**，必须分开报 —— 与 `bubbleState` 里
     * `enabled` / `granted` 的分法完全同构：开关开着但没授权时，用户该看到的是
     * 「还差一步：去开权限」，而不是笼统的"不可用"。
     */
    fun stateJson(context: Context): String = try {
        JSONObject()
            .put("enabled", isEnabled(context))
            .put("a11y", isPermissionGranted(context))
            .toString()
    } catch (t: Throwable) {
        DshLog.w(TAG, "读取键盘避让状态失败: ${t.message}")
        "{}"
    }
}
