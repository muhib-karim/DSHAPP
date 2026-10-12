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
 * ## 分层模型（这是「AI 想改什么都能改」的关键）
 *
 * 球由**三个独立的层**叠成，每层各有自己的透明度与开关 —— 而不是原来那种
 * 「一个底 + 一张图」的耦合写法：
 *
 * ```
 *   ┌─ 形状层   shape / shapePath / bg / bg2 / border / bgOpacity
 *   │    ↑ 可以是一条任意 SVG path（星形、心形、logo 轮廓），也可以是圆/方/圆角
 *   ├─ 内容层   avatar / frames / avatarOpacity / avatarScale
 *   │    ↑ 可以是内置头像、自定义图片、**轮播多帧**、**精灵图切片**，或整层去掉
 *   └─ 动效层   spin / pulse
 * ```
 *
 * 三层的透明度**相乘**关系：[opacity] 是整体，[bgOpacity] 只压背景，
 * [avatarOpacity] 只压内容。所以「背景透明但图片不透明」= `bgOpacity:0` +
 * `avatarOpacity:1`，这正是用户点名要的那一种。
 *
 * ## 字段一览（全部可选，缺省即保持当前值）
 *
 * | 字段 | 类型 | 语义 |
 * |---|---|---|
 * | `state` | string | `expanded` / `collapsed` / `hidden` / `auto` —— 直接改形态 |
 * | `shape` | string | `circle` / `square` / `rounded` / **`path`**（用 `shapePath`） |
 * | `shapePath` | string | SVG path 的 `d`，如 `M50 0 L61 35 L98 35 L68 57 L79 91 L50 70 L21 91 L32 57 L2 35 L39 35 Z` |
 * | `shapeViewBox` | string | `shapePath` 的坐标系，默认 `0 0 100 100` |
 * | `size` | int(dp) | 球的直径，24~160 |
 * | `cornerRadius` | int(dp) | `rounded` 形状的圆角 |
 * | `opacity` | float | **整体**不透明度 0.05~1.0 |
 * | `bgOpacity` | float | **只压背景层** 0~1（0 = 背景全透明，露出下层应用） |
 * | `avatarOpacity` | float | **只压内容层** 0~1 |
 * | `avatarScale` | float | 内容层相对球的缩放 0.2~2.0（>1 会溢出球，由球裁剪） |
 * | `bg` | `#RRGGBB` | 球的底色（`avatar:'none'` 时才看得见） |
 * | `bg2` | `#RRGGBB` | 与 `bg` 组成竖直渐变；不填则纯色 |
 * | `border` | `#RRGGBB` | 描边色 |
 * | `borderWidth` | int(dp) | 描边宽度 0~8 |
 * | `avatar` | string | `default` / `none` / `data:image/…;base64,…` |
 * | `frames` | string[] | **轮播帧**：每项一个 data URI。传 `[]` 清空轮播 |
 * | `frameIntervalMs` | int | 每帧停留时长（ms）20~60000；被 `frameDurations` 覆盖 |
 * | `frameDurations` | int[] | 逐帧停留时长；长度不足的帧用 `frameIntervalMs` |
 * | `spriteRows` / `spriteCols` | int | **精灵图网格**：每帧是同一张大图的第 N 格 |
 * | `frameLoop` | int | 轮播循环次数，-1 = 无限 |
 * | `spin` | int | 每秒旋转角度（度），正数顺时针，0 = 不转 |
 * | `pulse` | bool | 呼吸缩放动画 |
 * | `panelWidth` | int(dp) | 展开面板宽 180~（上限 = 屏幕宽 - 16） |
 * | `panelHeight` | int(dp) | 展开面板高 160~（上限 = 屏幕高 - 96） |
 * | `panelBg` | `#RRGGBB` | 面板底色 |
 * | `panelText` | `#RRGGBB` | 面板正文色 |
 * | `panelAccent` | `#RRGGBB` | 面板强调色（发送按钮、用户气泡） |
 * | `statusAutoHideMs` | int | 状态横条**内容无变化**多久后自动消失，0 = 不自动消失 |
 * | `edgeSnap` | bool | 是否启用「拖出屏幕即贴边折叠」 |
 *
 * ## 三条设计约束
 *
 * ① **非法值退回默认，未知字段忽略。** 插件写错一个字段不该让悬浮球消失 ——
 *    那是最糟的失败形态（用户看不到任何东西，也没有报错）。
 * ② **图片走 base64 data URI**，因为页面侧拿不到文件系统路径。为防有人塞一张
 *    10MB 的图进来，[MAX_AVATAR_BASE64] 卡住长度，超了直接忽略并记日志。
 * ③ **帧集不进 SharedPreferences**（那是几十张图的体量），只存元信息；
 *    帧字节由 [BubbleFrames] 落在 `filesDir/bubble-frames/` 下。见 [save]。
 */
data class BubbleAppearance(
    val state: String = STATE_AUTO,
    val shape: String = SHAPE_CIRCLE,
    val shapePath: String = "",
    val shapeViewBox: String = BubbleShapeDrawable.DEFAULT_VIEW_BOX,
    val size: Int = 44,
    val cornerRadius: Int = 16,
    val opacity: Float = 1f,
    val bgOpacity: Float = 1f,
    val avatarOpacity: Float = 1f,
    val avatarScale: Float = 1f,
    val bg: String = "",
    val bg2: String = "",
    val border: String = "",
    val borderWidth: Int = 0,
    val avatar: String = AVATAR_DEFAULT,
    val frameCount: Int = 0,
    val frameIntervalMs: Int = 400,
    val spriteRows: Int = 0,
    val spriteCols: Int = 0,
    val frameLoop: Int = -1,
    /**
     * 当前动作名（桌面宠物用）。
     *
     * ⚠️ **空串 = 跟随 [actionMap] 自动切换**，非空 = 插件/脚本钦定这个动作。
     *   这正是「AI 直接让宠物做某个动作」与「让宠物跟着运行状态自己动」两种用法
     *   的分界：前者显式传 `action`，后者只配 `actionMap`。
     */
    val action: String = "",
    /**
     * 状态 → 动作名 的映射（桌面宠物的核心）。
     *
     * key 是**运行状态**（见 [STATE_IDLE] 等），value 是 [BubbleFrames] 里的动作名。
     * 只配关心的那几个即可，没配的状态退回 [BubbleFrames.DEFAULT_ACTION]。
     *
     * 例：`{"thinking":"think","working":"work","waiting":"wait"}`
     */
    val actionMap: Map<String, String> = emptyMap(),
    val spin: Int = 0,
    val pulse: Boolean = false,
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

        // ── 运行状态（桌面宠物的动作选择依据）───────────────────────────
        //
        // ⚠️ 这几个是**运行状态**，与上面的 `state`（窗口形态）是两回事：
        //   一个是「球长什么样」，一个是「AI 在干什么」。
        //   刻意只有 5 个：每多一个，插件就多一处要维护的映射，而映射错了
        //   用户看到的是「宠物在做错的动作」，比少一个状态难查。

        /** 空闲：没在跑，等你输入。 */
        const val RUN_IDLE = "idle"

        /** 思考：模型在生成（还没有工具在跑）。 */
        const val RUN_THINKING = "thinking"

        /** 干活：有工具/命令正在执行。 */
        const val RUN_WORKING = "working"

        /** 等待：等用户确认、等输入、等外部事件。 */
        const val RUN_WAITING = "waiting"

        /** 出错：上一轮失败了。 */
        const val RUN_ERROR = "error"

        /** 全部运行状态。用于校验 [actionMap] 的 key。 */
        val RUN_STATES = listOf(RUN_IDLE, RUN_THINKING, RUN_WORKING, RUN_WAITING, RUN_ERROR)

        const val SHAPE_CIRCLE = "circle"
        const val SHAPE_SQUARE = "square"
        const val SHAPE_ROUNDED = "rounded"

        /**
         * 形状由 [shapePath] 那条 SVG path 描述 —— 任意形状。
         *
         * ⚠️ 这是「AI 想改什么形状都能改」的入口：圆/方/圆角只是三个预设，
         *   而 path 能画星形、心形、盾牌、云朵、任意 logo 轮廓。
         */
        const val SHAPE_PATH = "path"

        /** 用内置的头像图片（默认）。 */
        const val AVATAR_DEFAULT = "default"

        /** 不显示头像，露出底色 —— 想用纯色/渐变球时选它。 */
        const val AVATAR_NONE = "none"

        /** 头像 base64 长度上限（约 512KB 原始数据）。超了忽略并记日志。 */
        const val MAX_AVATAR_BASE64 = 700_000

        /** 自定义头像的落盘文件名。内容变了会整体覆盖。 */
        const val AVATAR_FILE = "bubble-avatar.bin"

        /**
         * SVG path 文本的长度上限。
         *
         * 一条 100dp 的球上画不出那么多细节，而每次解析都在主线程 —— 4000 字符
         * 已经足够描述很复杂的轮廓（对比：五角星的 path 约 100 字符）。
         */
        const val MAX_SHAPE_PATH_CHARS = 4000

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

            // 轮播帧要先处理：它可能**改变帧数**，而帧数决定 sprite 网格是否还有意义。
            var merged = base.copy(
                state = enumOf(o, "state", base.state, STATE_AUTO, STATE_EXPANDED, STATE_COLLAPSED, STATE_HIDDEN),
                shape = enumOf(
                    o, "shape", base.shape,
                    SHAPE_CIRCLE, SHAPE_SQUARE, SHAPE_ROUNDED, SHAPE_PATH,
                ),
                shapePath = textOf(o, "shapePath", base.shapePath, MAX_SHAPE_PATH_CHARS),
                shapeViewBox = textOf(o, "shapeViewBox", base.shapeViewBox, 64),
                size = clampInt(o, "size", base.size, 24, 160),
                cornerRadius = clampInt(o, "cornerRadius", base.cornerRadius, 0, 80),
                opacity = clampFloat(o, "opacity", base.opacity, 0.05f, 1f),
                bgOpacity = clampFloat(o, "bgOpacity", base.bgOpacity, 0f, 1f),
                avatarOpacity = clampFloat(o, "avatarOpacity", base.avatarOpacity, 0f, 1f),
                avatarScale = clampFloat(o, "avatarScale", base.avatarScale, 0.2f, 2f),
                bg = colorOf(o, "bg", base.bg),
                bg2 = colorOf(o, "bg2", base.bg2),
                border = colorOf(o, "border", base.border),
                borderWidth = clampInt(o, "borderWidth", base.borderWidth, 0, 8),
                avatar = avatarOf(o, "avatar", base.avatar, context),
                // ⚠️ `frameCount` 必须在这里认：`load()` 就是拿磁盘 JSON 走
                //   `merge`，漏了它的话重启后帧数永远是 0 —— 轮播帧文件还在
                //   磁盘上，却再没人引用，表现为"重启后动画没了"。
                //   下面处理 `frames` 时会用真实写入结果覆盖它。
                frameCount = clampInt(o, "frameCount", base.frameCount, 0, BubbleFrames.MAX_FRAMES),
                frameIntervalMs = clampInt(o, "frameIntervalMs", base.frameIntervalMs, 20, 60_000),
                spriteRows = clampInt(o, "spriteRows", base.spriteRows, 0, 32),
                spriteCols = clampInt(o, "spriteCols", base.spriteCols, 0, 32),
                frameLoop = clampInt(o, "frameLoop", base.frameLoop, -1, 100_000),
                // ⚠️ `action` 空串是**合法取值**（= 自动跟随状态），不能像其它文本
                //   字段那样"空串即保持原值" —— 那会让 AI 没法从钦定动作切回自动。
                //   只在字段缺席时才保留原值。
                action = if (o.has("action")) actionOf(o, "action") else base.action,
                actionMap = if (o.has("actionMap")) actionMapOf(o, "actionMap") else base.actionMap,
                spin = clampInt(o, "spin", base.spin, -720, 720),
                pulse = if (o.has("pulse")) o.optBoolean("pulse", base.pulse) else base.pulse,
                panelWidth = clampInt(o, "panelWidth", base.panelWidth, 180, 2000),
                panelHeight = clampInt(o, "panelHeight", base.panelHeight, 0, 4000),
                panelBg = colorOf(o, "panelBg", base.panelBg),
                panelText = colorOf(o, "panelText", base.panelText),
                panelAccent = colorOf(o, "panelAccent", base.panelAccent),
                statusAutoHideMs = clampInt(o, "statusAutoHideMs", base.statusAutoHideMs, 0, 60_000),
                edgeSnap = if (o.has("edgeSnap")) o.optBoolean("edgeSnap", base.edgeSnap) else base.edgeSnap,
            )

            // ── 轮播帧 / 动作帧集 ──
            //
            // ⚠️ 帧字节**不落进配置**（几十张图进 SharedPreferences 会把它撑爆），
            //   这里只处理"要不要换帧集"以及随之而来的元信息。
            //   没有 context 时（纯解析场景）只认帧数，字节由调用方负责 —— 见 save()。
            //
            // ⚠️ **这段是"默认动作"的路径**：`action` 为空（或恰好是 default）时，
            //   写的就是 `bubble-frames/` 本身 —— 也就是 0.2.6 及以前那套布局。
            //   AI 只改 `frames` 的老用法因此一字不变。
            val targetAction = BubbleFrames.normalizeAction(merged.action)
            if (targetAction == null) {
                Log.w(TAG, "悬浮球动作名非法，保持原动作")
                merged = merged.copy(action = base.action)
            }
            val writeAction = targetAction ?: BubbleFrames.DEFAULT_ACTION

            if (o.has("frames") && context != null) {
                val arr = o.optJSONArray("frames")
                if (arr == null || arr.length() == 0) {
                    // 显式传空数组 = 清空这个动作的轮播，回到单张头像。
                    BubbleFrames.clear(context, writeAction)
                    if (writeAction == BubbleFrames.DEFAULT_ACTION) {
                        merged = merged.copy(frameCount = 0)
                    }
                } else {
                    val frames = ArrayList<String>(arr.length())
                    for (i in 0 until arr.length()) {
                        val s = arr.optString(i, "")
                        if (s.isNotEmpty()) frames += s
                    }
                    val durations = intArrayOf(o, "frameDurations")
                    val sprite = spriteOf(merged)
                    val loop = clampInt(o, "frameLoop", merged.frameLoop, -1, 100_000)
                    val n = BubbleFrames.write(
                        context, frames, durations, sprite, merged.frameIntervalMs,
                        writeAction, loop,
                    )
                    if (n != null) {
                        if (writeAction == BubbleFrames.DEFAULT_ACTION) {
                            merged = merged.copy(frameCount = n)
                        }
                        // ⚠️ 名字是这个动作唯一的记录 —— 磁盘上的目录建出来了，
                        //   但配置里的 `action` 若还指着别的动作，刚写的这套帧
                        //   就没人在播。所以写谁就切到谁。
                        merged = merged.copy(action = writeAction)
                    } else {
                        Log.w(TAG, "轮播帧写入失败，保持原帧集")
                    }
                }
            } else if (context != null && o.has("spriteRows") && o.has("spriteCols")) {
                // 只改精灵图网格、没重传 frames：旧帧集是按旧网格切的，必须重切。
                // 做不到就退回"每帧一张完整图"，并明确告知 —— 静默用错网格会让
                // 球显示成网格里的碎块，比不显示更难查。
                val sprite = spriteOf(merged)
                if (sprite != null) {
                    val n = BubbleFrames.rewriteSprite(context, sprite, writeAction)
                    if (n == null && writeAction == BubbleFrames.DEFAULT_ACTION && merged.frameCount > 0) {
                        Log.w(TAG, "精灵图网格重切失败（帧字节已丢），退回整图模式")
                        merged = merged.copy(spriteRows = 0, spriteCols = 0)
                    }
                }
            }

            // ── 一次配一套动作（桌面宠物）──
            //
            // ⚠️ 传的是**对象**而不是数组：`{"idle":{"frames":[…]},"thinking":{…}}`。
            //   用数组的话动作名要跟帧并列塞进同一层，解析时得分辨"这是帧还是名字"，
            //   而对象让每个动作各自带着自己那套 `frames`/`spriteRows`/`frameLoop`。
            //
            // ⚠️ 每个动作**独立合并**，一个失败不影响其余：宠物有 4 个动作，
            //   其中一个图坏了，另外 3 个照常换上，比整批回退好。
            if (context != null && o.has("actions")) {
                val acts = o.optJSONObject("actions")
                if (acts != null) {
                    val names = acts.keys().asSequence()
                        .map { BubbleFrames.normalizeAction(it) }
                        .filterNotNull()
                        .filter { it != BubbleFrames.DEFAULT_ACTION }
                        .take(BubbleFrames.MAX_ACTIONS)
                        .toList()
                    for (nm in names) {
                        val spec = acts.optJSONObject(nm) ?: continue
                        applyActionSpec(context, nm, spec)
                    }
                    if (names.isNotEmpty() && merged.action.isEmpty()) {
                        // 只配动作集、没点名播哪个 —— 让状态映射（或默认动作）去决定。
                        Log.i(TAG, "已写入悬浮球动作集: ${names.joinToString()}")
                    }
                }
            }

            // ⚠️ **帧数以磁盘为准**：配置里那个 `frameCount` 只是"上一次写成功了几帧"，
            //   而动作集批量写入、清空、精灵图重切都会改变真实帧数。
            //   不信磁盘就会显示空白（找不到 index.bin）或漏播最后几帧。
            // ⚠️ 帧数与网格都以磁盘为准：写入、清空、精灵图重切都会改变真实帧数，
            //   而配置里那份只是"上一次写成功了几帧"。不信磁盘就会显示空白
            //   （找不到 index.bin）或按错网格切片（显示成碎块）。
            if (context != null) {
                val realCount = BubbleFrames.frameCount(context, writeAction)
                val realSprite = BubbleFrames.sprite(context, writeAction)
                merged = merged.copy(
                    frameCount = realCount,
                    spriteRows = realSprite?.first ?: 0,
                    spriteCols = realSprite?.second ?: 0,
                )
            }

            // 帧集在磁盘上真的存在才算数：用户清过数据、或上一轮写失败时，
            // 配置里留着 frameCount>0 会让球显示空白（找不到帧文件）。
            if (context != null && merged.frameCount > 0 && !BubbleFrames.available(context, writeAction)) {
                Log.w(TAG, "配置里有轮播帧但磁盘上找不到，退回单张头像")
                merged = merged.copy(frameCount = 0)
            }

            // 精灵图网格只在有帧时才有意义。
            if (merged.frameCount <= 0 && (merged.spriteRows > 0 || merged.spriteCols > 0)) {
                merged = merged.copy(spriteRows = 0, spriteCols = 0)
            }

            merged
        } catch (t: Throwable) {
            Log.w(TAG, "悬浮球外观配置解析失败（保持原配置）: ${t.message}")
            base
        }

        /** 精灵图网格（两者都 > 0 才有效）。 */
        fun spriteOf(a: BubbleAppearance): Pair<Int, Int>? =
            if (a.spriteRows > 0 && a.spriteCols > 0) a.spriteRows to a.spriteCols else null

        /**
         * 动作名字段。
         *
         * ⚠️ 空串是**合法取值**（= 跟随状态自动切换），所以不能复用 [textOf]
         *   （它把空串当"没传"并返回 fallback）。非法名（含路径分隔符等）一律
         *   退成空串：宁可回到自动，也不要让一个坏名字写到不该写的地方。
         */
        private fun actionOf(o: JSONObject, key: String): String {
            val raw = o.optString(key, "").trim()
            if (raw.isEmpty()) return ""
            val name = BubbleFrames.normalizeAction(raw)
            if (name == null) {
                Log.w(TAG, "悬浮球动作名非法: $raw（退回自动跟随状态）")
                return ""
            }
            // ⚠️ `default` 要**保留**而不是归一成空串：空串的语义是"跟随状态自动切换"，
            //   而 `action:"default"` 的语义是"就播默认动作、别跟状态变"。两者不同，
            //   归一了就没法强制回到默认动作。
            return name
        }

        /**
         * 状态 → 动作名 的映射。
         *
         * ⚠️ 两处都校验：key 必须是已知运行状态，value 必须是合法动作名。
         *   任何一项不合规就**丢掉这一条而不是整份映射** —— 宠物配了 4 个状态，
         *   其中一个打错字不该让另外 3 个也失效。
         *
         * ⚠️ 传空对象 = 清空映射（回到"永远播默认动作"），与"没传"不同。
         */
        private fun actionMapOf(o: JSONObject, key: String): Map<String, String> {
            val src = o.optJSONObject(key) ?: return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (state in src.keys()) {
                if (state !in RUN_STATES) {
                    Log.w(TAG, "动作映射的运行状态未知，已忽略: $state")
                    continue
                }
                val raw = src.optString(state, "").trim()
                if (raw.isEmpty()) continue
                val name = BubbleFrames.normalizeAction(raw)
                if (name == null) {
                    Log.w(TAG, "动作映射里的动作名非法，已忽略: $state → $raw")
                    continue
                }
                out[state] = if (name == BubbleFrames.DEFAULT_ACTION) "" else name
            }
            return out
        }

        /**
         * 按 [RUN_STATES] 的顺序给出动作名，没配的用 [fallback]。
         *
         * 这是「哪个动作该播」的唯一判定入口 —— 原生自动切换与插件主动点名
         * 都从这里取，免得两处各写一份 if 链。
         */
        fun actionFor(a: BubbleAppearance, runState: String, fallback: String): String {
            val mapped = a.actionMap[runState]
            if (!mapped.isNullOrEmpty()) return mapped
            return fallback
        }

        /**
         * 写入一个动作的帧集（`actions` 对象里的一项）。
         *
         * ⚠️ 每个动作**各自解析自己的网格与轮数**，不共用全局那份：宠物不同动作
         *   常常来自不同的素材包（待机是 4 帧精灵图、思考是 6 帧独立图），
         *   强制共用网格会让其中一套显示成碎块。
         *
         * ⚠️ 失败**不抛**：批量配动作时一个坏图不该让另外几个换不上。
         */
        private fun applyActionSpec(context: Context, name: String, spec: JSONObject) {
            val arr = spec.optJSONArray("frames")
            if (arr == null || arr.length() == 0) {
                // 显式空数组 = 删掉这个动作。
                BubbleFrames.clear(context, name)
                Log.i(TAG, "已删除悬浮球动作: $name")
                return
            }
            val frames = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "")
                if (s.isNotEmpty()) frames += s
            }
            val rows = spec.optInt("spriteRows", 0)
            val cols = spec.optInt("spriteCols", 0)
            val sprite = if (rows > 0 && cols > 0) rows to cols else null
            val intervalMs = spec.optInt("frameIntervalMs", 400).coerceIn(20, 60_000)
            val loop = spec.optInt("frameLoop", BubbleFrames.LOOP_FOREVER).coerceIn(-1, 100_000)
            val n = BubbleFrames.write(
                context, frames, intArrayOf(spec, "frameDurations"), sprite, intervalMs, name, loop,
            )
            if (n == null) {
                Log.w(TAG, "悬浮球动作 $name 写入失败，保持原帧集")
            } else {
                Log.i(TAG, "悬浮球动作 $name 已就位: $n 帧")
            }
        }

        /** 读一个 int 数组字段；不存在或非法时返回空数组。 */
        private fun intArrayOf(o: JSONObject, key: String): List<Int> {
            if (!o.has(key)) return emptyList()
            val arr = o.optJSONArray(key) ?: return emptyList()
            val out = ArrayList<Int>(arr.length())
            for (i in 0 until arr.length()) {
                val v = arr.optInt(i, -1)
                if (v > 0) out += v
            }
            return out
        }

        /**
         * 文本字段（如 SVG path）：去空白、限长。
         *
         * ⚠️ 限长是必须的：一条几万字符的 path 会让每次解析都卡住主线程，
         *   而它带来的收益为零 —— 100dp 的球上画不出那么多细节。
         */
        private fun textOf(o: JSONObject, key: String, fallback: String, maxChars: Int): String {
            if (!o.has(key)) return fallback
            val v = o.optString(key, "").trim()
            if (v.isEmpty()) return ""
            if (v.length > maxChars) {
                Log.w(TAG, "悬浮球外观配置的 $key 过长（${v.length} > $maxChars），已忽略")
                return fallback
            }
            return v
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

        /**
         * 落盘。**每次 apply 都写**：插件改完就该在重启后仍然生效。
         *
         * ⚠️ **这里漏一个字段 = 那个字段只在本次运行有效，重启即回默认。**
         *   表现是"AI 改的时候看得见，重开 App 就没了" —— 加字段时务必同步这里，
         *   [load] 走 [merge]，`merge` 认的键这里都得写出来。
         */
        fun save(context: Context, appearance: BubbleAppearance) {
            val json = JSONObject().apply {
                put("state", appearance.state)
                put("shape", appearance.shape)
                put("shapePath", appearance.shapePath)
                put("shapeViewBox", appearance.shapeViewBox)
                put("size", appearance.size)
                put("cornerRadius", appearance.cornerRadius)
                put("opacity", appearance.opacity.toDouble())
                put("bgOpacity", appearance.bgOpacity.toDouble())
                put("avatarOpacity", appearance.avatarOpacity.toDouble())
                put("avatarScale", appearance.avatarScale.toDouble())
                put("bg", appearance.bg)
                put("bg2", appearance.bg2)
                put("border", appearance.border)
                put("borderWidth", appearance.borderWidth)
                // ⚠️ 头像**不落进配置 JSON**：base64 可能几百 KB，写进
                //   SharedPreferences 会让每次读写都带上它。图片单独落盘（见
                //   writeAvatar），配置里只留一个标记表示"用自定义头像"。
                put("avatar", if (appearance.avatar.startsWith("data:image/")) AVATAR_FILE else appearance.avatar)
                // ⚠️ 轮播帧同理，甚至更要紧：帧字节在 `filesDir/bubble-frames/`
                //   （见 [BubbleFrames]），这里只存"有几帧、怎么播"。
                //   `frameCount` 必须落盘，否则重启后 `load()` 以为没有帧，
                //   磁盘上那堆文件再没人引用。
                put("frameCount", appearance.frameCount)
                put("frameIntervalMs", appearance.frameIntervalMs)
                put("frameLoop", appearance.frameLoop)
                put("spriteRows", appearance.spriteRows)
                put("spriteCols", appearance.spriteCols)
                // ⚠️ 动作名与状态映射必须落盘，否则重启后宠物就只会播默认动作 ——
                //   而磁盘上那几套动作帧还在，表现为"重开 App 宠物就不动了"。
                //   动作名空串也要写：它是"自动跟随"这个状态本身。
                put("action", appearance.action)
                put(
                    "actionMap",
                    JSONObject().apply {
                        for ((k, v) in appearance.actionMap) put(k, v)
                    },
                )
                put("spin", appearance.spin)
                put("pulse", appearance.pulse)
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
