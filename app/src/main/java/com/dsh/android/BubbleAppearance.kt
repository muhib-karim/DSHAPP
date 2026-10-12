package com.dsh.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * 悬浮球的**外观与行为配置** —— 插件改悬浮窗的唯一接缝。
 *
 * ## 为什么要有这个类
 *
 * 目标是「让 AI 改悬浮窗，像改自己的 web 界面一样简单」。DSH 的插件体系里，
 * 任何插件都能在页面里拿到 `window.DSHAndroid`，于是：
 *
 * ```js
 * // 一个插件想在设置页里给悬浮球换皮肤，只需这一行
 * window.DSHAndroid.bubbleApply(JSON.stringify({ shape: 'squircle', size: 72, bg: '#7C4DFF' }))
 * ```
 *
 * 原生侧收到后立即应用并落盘，**重启 App 后依然生效**（下次启动从
 * SharedPreferences 读回）。
 *
 * ## 字段一览（全部可选，缺省即保持当前值）
 *
 * | 字段 | 类型 | 语义 |
 * |---|---|---|
 * | `state` | string | `expanded` / `collapsed` / `hidden` / `auto` —— 直接改形态 |
 * | `shape` | string | `circle` / `square` / `rounded` —— 球的形状 |
 * | `size` | int(dp) | 球的直径，24~160 |
 * | `cornerRadius` | int(dp) | `rounded` 形状的圆角 |
 * | `opacity` | float | 整体不透明度 0.2~1.0 |
 * | `bg` | `#RRGGBB` | 球的底色（`avatar:'none'` 时才看得见） |
 * | `bg2` | `#RRGGBB` | 与 `bg` 组成竖直渐变；不填则纯色 |
 * | `border` | `#RRGGBB` | 描边色 |
 * | `borderWidth` | int(dp) | 描边宽度 0~8 |
 * | `avatar` | string | `default` / `none` / `data:image/…;base64,…` |
 * | `panelWidth` | int(dp) | 展开面板宽 180~（上限 = 屏幕宽 - 16） |
 * | `panelHeight` | int(dp) | 展开面板高 160~（上限 = 屏幕高 - 96） |
 * | `panelBg` | `#RRGGBB` | 面板底色 |
 * | `panelText` | `#RRGGBB` | 面板正文色 |
 * | `panelAccent` | `#RRGGBB` | 面板强调色（发送按钮、用户气泡） |
 * | `statusAutoHideMs` | int | 状态横条**内容无变化**多久后自动消失，0 = 不自动消失 |
 * | `edgeSnap` | bool | 是否启用「拖出屏幕即贴边折叠」 |
 *
 * ## 两条设计约束
 *
 * ① **非法值退回默认，未知字段忽略。** 插件写错一个字段不该让悬浮球消失 ——
 *    那是最糟的失败形态（用户看不到任何东西，也没有报错）。
 * ② **图片走 base64 data URI**，因为页面侧拿不到文件系统路径。为防有人塞一张
 *    10MB 的图进来，[MAX_AVATAR_BASE64] 卡住长度，超了直接忽略并记日志。
 */
data class BubbleAppearance(
    val state: String = STATE_AUTO,
    val shape: String = SHAPE_CIRCLE,
    val size: Int = 44,
    val cornerRadius: Int = 16,
    val opacity: Float = 1f,
    val bg: String = "",
    val bg2: String = "",
    val border: String = "",
    val borderWidth: Int = 0,
    val avatar: String = AVATAR_DEFAULT,
    val panelWidth: Int = 260,
    val panelHeight: Int = 0,
    val panelBg: String = "",
    val panelText: String = "",
    val panelAccent: String = "",
    val statusAutoHideMs: Int = 2000,
    val edgeSnap: Boolean = true,
) {

    companion object {
        private const val TAG = DshApplication.TAG

        /** 形态：跟随 App 前后台自动决定（默认）。 */
        const val STATE_AUTO = "auto"

        /** 形态：强制展开面板。 */
        const val STATE_EXPANDED = "expanded"

        /** 形态：强制收起成球。 */
        const val STATE_COLLAPSED = "collapsed"

        /** 形态：强制隐藏（连球都不显示）。 */
        const val STATE_HIDDEN = "hidden"

        const val SHAPE_CIRCLE = "circle"
        const val SHAPE_SQUARE = "square"
        const val SHAPE_ROUNDED = "rounded"

        /** 用内置的头像图片（默认）。 */
        const val AVATAR_DEFAULT = "default"

        /** 不显示头像，露出底色 —— 想用纯色/渐变球时选它。 */
        const val AVATAR_NONE = "none"

        /** 头像 base64 长度上限（约 512KB 原始数据）。超了忽略并记日志。 */
        const val MAX_AVATAR_BASE64 = 700_000

        /** 自定义头像的落盘文件名。内容变了会整体覆盖。 */
        const val AVATAR_FILE = "bubble-avatar.bin"

        /** 配置落盘的 SharedPreferences 名。与位置共用一个文件，减少一次 I/O。 */
        const val PREFS = "dsh_bubble"

        /** 整个配置对象的 key。 */
        const val KEY_APPEARANCE = "appearance"

        /**
         * 从 JSON 解析，**逐字段合并到 [base] 之上**。
         *
         * ⚠️ 语义是「合并」不是「替换」：插件只想改形状时不必重述其它 15 个字段。
         *   这与 [state] 的用法一致 —— 改一个字段就够。
         *
         * @param json 插件下发的 JSON 文本。
         * @param base 当前配置；字段缺失时保留这里的值。
         * @param context 只用于核对自定义头像的落盘文件还在不在；传 null 时按「在」处理。
         * @return 合并后的配置；JSON 不可解析时原样返回 [base]。
         */
        fun merge(json: String, base: BubbleAppearance, context: Context? = null): BubbleAppearance = try {
            val o = JSONObject(json)
            base.copy(
                state = enumOf(o, "state", base.state, STATE_AUTO, STATE_EXPANDED, STATE_COLLAPSED, STATE_HIDDEN),
                shape = enumOf(o, "shape", base.shape, SHAPE_CIRCLE, SHAPE_SQUARE, SHAPE_ROUNDED),
                size = clampInt(o, "size", base.size, 24, 160),
                cornerRadius = clampInt(o, "cornerRadius", base.cornerRadius, 0, 80),
                opacity = clampFloat(o, "opacity", base.opacity, 0.2f, 1f),
                bg = colorOf(o, "bg", base.bg),
                bg2 = colorOf(o, "bg2", base.bg2),
                border = colorOf(o, "border", base.border),
                borderWidth = clampInt(o, "borderWidth", base.borderWidth, 0, 8),
                avatar = avatarOf(o, "avatar", base.avatar, context),
                panelWidth = clampInt(o, "panelWidth", base.panelWidth, 180, 2000),
                panelHeight = clampInt(o, "panelHeight", base.panelHeight, 0, 4000),
                panelBg = colorOf(o, "panelBg", base.panelBg),
                panelText = colorOf(o, "panelText", base.panelText),
                panelAccent = colorOf(o, "panelAccent", base.panelAccent),
                statusAutoHideMs = clampInt(o, "statusAutoHideMs", base.statusAutoHideMs, 0, 60_000),
                edgeSnap = if (o.has("edgeSnap")) o.optBoolean("edgeSnap", base.edgeSnap) else base.edgeSnap,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "悬浮球外观配置解析失败（保持原配置）: ${t.message}")
            base
        }

        /** 枚举字段：不在白名单里就退回 [fallback]。 */
        private fun enumOf(
            o: JSONObject,
            key: String,
            fallback: String,
            vararg allowed: String,
        ): String {
            if (!o.has(key)) return fallback
            val v = o.optString(key, "")
            if (v in allowed) return v
            Log.w(TAG, "悬浮球外观配置的 $key 取值非法: $v（保持 ${fallback}）")
            return fallback
        }

        private fun clampInt(o: JSONObject, key: String, fallback: Int, min: Int, max: Int): Int {
            if (!o.has(key)) return fallback
            val v = o.optInt(key, fallback)
            return v.coerceIn(min, max)
        }

        private fun clampFloat(o: JSONObject, key: String, fallback: Float, min: Float, max: Float): Float {
            if (!o.has(key)) return fallback
            val v = o.optDouble(key, fallback.toDouble()).toFloat()
            if (v.isNaN()) return fallback
            return v.coerceIn(min, max)
        }

        /**
         * 颜色字段：只接受 `#RRGGBB` / `#AARRGGBB`，其它一律保持原值。
         *
         * ⚠️ 不用 `optString` + `Color.parseColor` 裸奔：parseColor 对非法串抛
         *   `IllegalArgumentException`，那会让整份配置被丢弃（其余字段一起回退）。
         */
        private fun colorOf(o: JSONObject, key: String, fallback: String): String {
            if (!o.has(key)) return fallback
            val v = o.optString(key, "").trim()
            if (v.isEmpty()) return ""
            if (!v.startsWith("#") || (v.length != 7 && v.length != 9)) {
                Log.w(TAG, "悬浮球外观配置的 $key 不是 #RRGGBB 形式: $v（保持 ${fallback}）")
                return fallback
            }
            return try {
                Color.parseColor(v)
                v
            } catch (t: Throwable) {
                Log.w(TAG, "悬浮球外观配置的 $key 解析失败: $v")
                fallback
            }
        }

        /**
         * 头像字段：`default` / `none` / data URI 三者之一。
         *
         * 非 data URI 的其它字符串一律当成 `default` —— 插件可能想传本地路径，
         * 但页面侧拿不到路径，静默退回内置头像比显示一块空白好。
         */
        private fun avatarOf(o: JSONObject, key: String, fallback: String, context: Context? = null): String {
            if (!o.has(key)) return fallback
            val v = o.optString(key, "").trim()
            if (v == AVATAR_NONE || v == AVATAR_DEFAULT) return v
            // ⚠️ **落盘用的标记必须能被读回来。**
            //
            // 这不是插件会主动传的值 —— 它是 [save] 自己写进去的（见那里的注释：
            // base64 太大不进 SharedPreferences，配置里只留一个文件名标记）。
            // 漏掉这一条时，回读会把标记判成非法值退回 `default`，于是：
            //   ① 自定义头像重启即丢（图片文件还在，却再没人引用它）；
            //   ② `bubbleAppearance()` 回读与 `bubbleApply()` 下发不一致，
            //      插件做「读-改-写」时会把头像悄悄洗掉。
            //
            // 文件真丢了（用户清数据）时退回 `default`，而不是留一个悬空引用。
            if (v == AVATAR_FILE) {
                val f = context?.let { File(it.filesDir, AVATAR_FILE) }
                return when {
                    // 没有 context（纯解析场景）：按「文件在」处理，保住标记。
                    f == null -> AVATAR_FILE
                    f.isFile && f.length() > 0L -> AVATAR_FILE
                    else -> AVATAR_DEFAULT
                }
            }
            if (!v.startsWith("data:image/")) return AVATAR_DEFAULT
            if (v.length > MAX_AVATAR_BASE64) {
                Log.w(TAG, "悬浮球自定义头像过大（${v.length} 字节 > $MAX_AVATAR_BASE64），已忽略")
                return fallback
            }
            return v
        }

        /**
         * 解码 data URI 形式的头像并落盘。
         *
         * ⚠️ **落盘而不是只留内存**：插件换的头像要能在 App 重启后仍然生效，
         *   否则用户会觉得「AI 换的皮肤过一会自己变回去了」。
         *
         * @return 落盘后的文件；非 data URI 或解码失败时返回 null。
         */
        fun writeAvatar(context: Context, avatar: String): File? {
            if (!avatar.startsWith("data:image/")) return null
            val comma = avatar.indexOf(',')
            if (comma < 0) return null
            val b64 = avatar.substring(comma + 1)
            val bytes = try {
                Base64.decode(b64, Base64.DEFAULT)
            } catch (t: Throwable) {
                Log.w(TAG, "悬浮球自定义头像 base64 解码失败: ${t.message}")
                return null
            }
            // ⚠️ 先解码一次确认是有效位图再落盘：把一堆垃圾字节写进去、下次启动
            //   才在 BitmapFactory 那里失败，会让问题从"插件写错了"变成"重启后
            //   头像莫名其妙没了"，难查得多。
            if (BitmapFactory.decodeByteArray(bytes, 0, bytes.size) == null) {
                Log.w(TAG, "悬浮球自定义头像不是有效图片，已忽略")
                return null
            }
            return try {
                val f = File(context.filesDir, AVATAR_FILE)
                f.writeBytes(bytes)
                Log.i(TAG, "已保存悬浮球自定义头像（${bytes.size} 字节）")
                f
            } catch (t: Throwable) {
                Log.w(TAG, "悬浮球自定义头像落盘失败: ${t.message}")
                null
            }
        }

        /**
         * 读回已落盘的自定义头像。
         *
         * ⚠️ 用 `inSampleSize` 采样：插件可能塞一张 4000×4000 的图，而球的显示
         *   尺寸最大 160dp（约 480px @xxhdpi）。不采样会白占十几 MB 堆。
         */
        fun readAvatar(context: Context, targetPx: Int): Bitmap? {
            val f = File(context.filesDir, AVATAR_FILE)
            if (!f.isFile || f.length() == 0L) return null
            return try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.absolutePath, bounds)
                var sample = 1
                while (bounds.outWidth / sample > targetPx * 2 && bounds.outHeight / sample > targetPx * 2) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeFile(f.absolutePath, opts)
            } catch (t: Throwable) {
                Log.w(TAG, "读取悬浮球自定义头像失败: ${t.message}")
                null
            }
        }

        /** 从 SharedPreferences 读回配置。缺失或损坏时返回默认值。 */
        fun load(context: Context): BubbleAppearance {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_APPEARANCE, null) ?: return BubbleAppearance()
            return merge(raw, BubbleAppearance(), context)
        }

        /** 落盘。**每次 apply 都写**：插件改完就该在重启后仍然生效。 */
        fun save(context: Context, appearance: BubbleAppearance) {
            val json = JSONObject().apply {
                put("state", appearance.state)
                put("shape", appearance.shape)
                put("size", appearance.size)
                put("cornerRadius", appearance.cornerRadius)
                put("opacity", appearance.opacity.toDouble())
                put("bg", appearance.bg)
                put("bg2", appearance.bg2)
                put("border", appearance.border)
                put("borderWidth", appearance.borderWidth)
                // ⚠️ 头像**不落进配置 JSON**：base64 可能几百 KB，写进
                //   SharedPreferences 会让每次读写都带上它。图片单独落盘（见
                //   writeAvatar），配置里只留一个标记表示"用自定义头像"。
                put("avatar", if (appearance.avatar.startsWith("data:image/")) AVATAR_FILE else appearance.avatar)
                put("panelWidth", appearance.panelWidth)
                put("panelHeight", appearance.panelHeight)
                put("panelBg", appearance.panelBg)
                put("panelText", appearance.panelText)
                put("panelAccent", appearance.panelAccent)
                put("statusAutoHideMs", appearance.statusAutoHideMs)
                put("edgeSnap", appearance.edgeSnap)
            }.toString()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_APPEARANCE, json).apply()
        }
    }

    /** 是否是「用自定义头像」——配置里存的是文件名标记，不是 data URI。 */
    val usesCustomAvatar: Boolean get() = avatar == AVATAR_FILE

    /** 面板宽度的有效值（按屏幕宽夹一次）。 */
    fun effectivePanelWidth(screenW: Int, density: Float): Int {
        val px = (panelWidth * density).toInt()
        return px.coerceIn((180 * density).toInt(), (screenW - (16 * density).toInt()).coerceAtLeast((180 * density).toInt()))
    }

    /** 面板高度；0 表示按内容自适应。 */
    fun effectivePanelHeight(screenH: Int, density: Float): Int {
        if (panelHeight <= 0) return 0
        val px = (panelHeight * density).toInt()
        return px.coerceIn((160 * density).toInt(), (screenH - (96 * density).toInt()).coerceAtLeast((160 * density).toInt()))
    }
}
