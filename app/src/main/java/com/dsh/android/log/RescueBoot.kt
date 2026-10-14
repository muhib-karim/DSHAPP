package com.dsh.android.log

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 启动层级（救援启动的降级阶梯）与「上次为什么起不来」的记录。
 *
 * ## 这个文件解决的是什么
 *
 * 真机事实（2026-10-14，Xiaomi 24122RKC7C / Android 16 / v18）：用户装了 40 个第三方
 * 插件，其中一个重复注册了 `mcpManager/list` 这个 endpoint，把冲突灌进**核心插件**
 * `typert-loader`，于是引擎 `startup failed: 1 required plugin did not activate`
 * 直接退出 —— **App 完全打不开，同一个 bootId 内连续 8 次**。
 *
 * 而 App 里已有的两层防护都没拦住：
 *
 * 1. **启动前体检（`EngineManager.preflight()`）放行了**。它的判据建立在
 *    `--dump-config` 的静态输出上，而那是**配置解析**、不执行插件的 `apply()`；
 *    本次失败的 `dump-config` 退出码是 **0**、4 个 required 条目**全在**
 *    （`typert-loader` 是第 5 个、不在 `REQUIRED_ENTRY_IDS` 里）。
 *    ⇒ 「静态通过」被当成了「能启动」。这是本项目一直在记的缺陷族
 *    「结论与它描述的对象不是同一个」的第 27 例。
 * 2. **安全模式（`EngineManager.enterSafeMode()`）用户从没点过** ——
 *    真机证据：两个包中「安全模式」出现 **0 次**。它已经会做正确的事
 *    （摘掉全部第三方 bundle），但入口是失败浮层上的一个按钮，
 *    而用户面对的是「打不开」，根本不知道该点它。
 *
 * ⇒ 本对象负责 P0（判据补全）：把「真实启动失败」这件事**第一次变成可读的状态**，
 * 让后续的自动降级有据可依，也让诊断包能如实报出「App 现在是残的」。
 *
 * ## 它**不**做什么
 *
 * ⚠️ 本对象**不做任何降级动作**（不摘插件、不切 profile、不重解包）。
 * 它只负责**记录**与**上报**。降级动作在 `EngineManager` 里、由调用方决定 ——
 * 这样「判据」与「动作」分开，判据正确性可以独立验证（见 P0 验收标准）。
 *
 * ⚠️ 也**不做精确归因**。日志点名的是受害者（`typert-loader`）而不是凶手
 * （那个重复注册的第三方插件），所以 [Failure.failedPlugins] 只如实记录
 * 引擎自己列出的名字，**不试图猜谁是真凶**。
 *
 * ## 与 `BootState` 的分工
 *
 * | | 管什么 | 判据来源 |
 * |---|---|---|
 * | [BootState] | 上次是否**正常退出**（崩溃/被系统回收/断电） | `ApplicationExitInfo` 不可用时的自记账 |
 * | [RescueBoot] | 上次是否**能起来**（引擎有没有真的就绪） | 引擎真实启动的输出 |
 *
 * 两者必须分开：`BootState` 的 `clean=false` 在「引擎起不来」时**也是真的**
 * （进程确实没正常退出），但它无法区分「起不来」与「用了一阵才被回收」——
 * 这正是本对象存在的理由。
 */
object RescueBoot {

    private const val TAG = "DSH"

    /**
     * 记录目录名（`$DSH_HOME/rescue/`）。
     *
     * ⚠️ **公开**：`RescueCommandBridge` 要把自己的进度文件
     * （`rescue/probe-count.json`）放在同一个目录里。让它自己拼字面量
     * `"rescue"` 就等于把这个名字复制了一份 —— 将来改名必漏一处，
     * 而后果是「信箱进度永远读不到、每次重启都重放旧请求」这类静默行为。
     */
    const val DIR_NAME = "rescue"

    /** 上次真实启动失败的记录文件。 */
    private const val FILE_LAST_FAILURE = "last-boot-failure.json"

    /** 当前启动层级。 */
    private const val FILE_STATE = "state.json"

    /**
     * 记录格式版本。
     *
     * ⚠️ 必须有：诊断包 `manifest.json` 已用这个范式。不加的话将来加字段会让
     * 老版本 App 读到不认识的结构而崩 —— 而这份文件的读者恰恰可能是「降级后的
     * 救援会话」，那时 App 状态已经不正常了，不能再引入新的崩溃点。
     */
    const val SCHEMA_VERSION = 1

    /**
     * 启动层级。
     *
     * 顺序 = 降级顺序，**越往后第三方插件参与得越少**。
     */
    enum class Tier(val id: String, val label: String) {
        /** L0 正常启动。 */
        NORMAL("normal", "正常"),

        /** L1 自动隔离：摘掉了可疑的第三方插件，其余照常。 */
        ISOLATED("isolated", "已隔离可疑插件"),

        /** L2 救援启动：不含任何第三方插件的干净 profile。 */
        RESCUE("rescue", "救援模式"),

        /** L3 安全模式：推开一切 + 重解包。 */
        SAFE_MODE("safemode", "安全模式"),
        ;

        companion object {
            /** 从持久化的 id 还原；不认识的值退回 [NORMAL]（**不抛异常**）。 */
            fun of(id: String?): Tier = entries.firstOrNull { it.id == id } ?: NORMAL
        }
    }

    /** 一次真实启动失败的关键事实。 */
    data class Failure(
        /** 这次的 bootId（`BootState.bootId` 的副本，用于与崩溃现场对齐）。 */
        val bootId: String,
        /** 引擎进程退出码；拿不到时为 null。 */
        val exitCode: Int?,
        /** 引擎自己报的「N 个 required 插件没起来」；没有这一行时为 null。 */
        val requiredDidNotActivate: Int?,
        /** 引擎列出的失败插件名（`Failed plugins (N):` 段落里的名字）。 */
        val failedPlugins: List<String>,
        /**
         * 引擎在 `Failed plugins (N)` 头里**声称**的条目数。
         *
         * ⚠️ 这个字段存在的唯一理由是：**声称的数与实际列出的常常不一致**。
         * 真机实测（`20261014-082615-a5d15471.zip`）：头写 `Failed plugins (40):`，
         * 而段落里只有 **38** 个「2 空格缩进 + 紧跟 `Package:`」的条目。
         *
         * 若只记 [failedPlugins]，读的人看到 38 个会以为「引擎一共就列了 38 个」——
         * 而引擎自己说的是 40。这就是本项目已确认 28 例的缺陷族
         * 「结论与它描述的对象不是同一个」：**计数与清单不是同一个总体**。
         * 如实记下来，差异就浮在表面（`failedPlugins.size != claimedFailedPlugins`）。
         *
         * ⚠️ 不要试图"补齐"到 40 —— 我们不知道缺的那 2 个是谁，编不出来。
         */
        val claimedFailedPlugins: Int?,
        /**
         * **跨启动**的连续启动失败次数（每次 [recordFailure] +1，成功启动清零）。
         *
         * ## 为什么需要它，而不是「同一 bootId 内失败几次」
         *
         * 方案原稿写的是「同一个 bootId 内连续失败 ≥2 次」才触发 L1 自动隔离，
         * 但**那个条件永远不成立**（2026-10-14 实测证伪）：`BootState.begin()`
         * 在每次进程启动时都生成新的 `bootId`，所以「同一个 bootId 内失败两次」
         * 物理上不可能发生。
         *
         * 真机证据（那台 Xiaomi 的 30 个诊断包，共 8 次 `startup failed`）：
         * 按 `manifest.json` 的 `bootId` 分组，8 次失败分布在 **8 个不同的
         * bootId** 里。`engine.log` 是**累积追加**的，所以日志里的失败计数看着
         * 递增到 8，但每个 bootId 只对应一次启动。
         *
         * 按原判据实现，L1 在这台机器上一次都不会触发 —— 而它恰恰是唯一需要
         * L1 的设备。所以判据换成这个跨启动计数。
         *
         * ## ⚠️ 为什么不用 `BootState.consecutiveUncleanCount`
         *
         * 那个已有的跨启动计数的语义是「上次**未正常退出**」（异常死亡、被系统
         * 回收、被 OOM 都算），与「**启动就起不来**」不是同一个总体。混用正是
         * 本项目缺陷族的典型形态：**结论与它描述的对象不是同一个**。
         * 用户正常用完 App 之后从最近任务里划掉，`consecutiveUncleanCount` 也会
         * 涨，但那跟插件坏不坏毫无关系。
         */
        val consecutiveFailures: Int,
        /** 引擎输出的尾部（截断后），供人/AI 直接看。 */
        val tail: List<String>,
        /** 失败判定时刻。 */
        val at: Long,
    ) {
        /** 是不是「插件问题导致起不来」。
         *
         * 判据 = 引擎**自己**说了 `required plugin did not activate`。
         *
         * ⚠️ **不要用「`Failed plugins` 段落存在」当判据**：那一段里绝大多数是正常的
         * `skipping profile bundle`（版本不兼容，引擎自愿跳过、**不致命**）。
         * 真机反例：那台设备 `Failed plugins (40)` 但引擎**有时仍能起来**
         * （07:14 成功启动过两次）。用「有 Failed plugins 就是坏」会大面积误报。
         */
        val looksLikePluginProblem: Boolean
            get() = requiredDidNotActivate != null && requiredDidNotActivate > 0

        /**
         * 引擎声称的条目数 vs 实际列出的条目数；两者都有且不等时返回
         * `"40 vs 38"`，其余情况返回 null。
         *
         * 供界面/诊断包**如实暴露**这个不一致（真机上确实发生过，见
         * [claimedFailedPlugins] 的说明）。返回 null = 没有可报的不一致，
         * **不代表两者相等**（可能其中一个没解析出来）。
         */
        val failedCountMismatch: String?
            get() {
                val c = claimedFailedPlugins ?: return null
                if (c == failedPlugins.size) return null
                return "$c vs ${failedPlugins.size}"
            }
    }

    /** 当前启动层级的状态。 */
    data class State(
        val tier: Tier,
        /** 进入该层级的时刻。 */
        val since: Long,
        /** 进入该层级的原因（给人看的一句话）。 */
        val reason: String,
        /** 该层级下被摘掉的第三方 bundle（L1 用）。 */
        val removedBundles: List<String>,
        /** 可恢复点：被隔离的原件路径（L1/L3 用）。 */
        val restorePoint: String?,
    )

    @Volatile
    private var dir: File? = null

    @Volatile
    private var cachedState: State? = null

    /**
     * 安装记录目录。幂等；重复安装只是覆盖。
     *
     * ⚠️ 允许传 null / 目录创建失败：所有写入都**静默降级**为「不记录」，
     * 绝不让「记不下来」变成新的失败点。这是本项目对诊断设施的既有要求。
     */
    fun install(rescueDir: File?) {
        dir = rescueDir
        cachedState = null
    }

    /** 安装到 `$DSH_HOME/rescue/` 的标准位置。 */
    fun installUnder(dshHome: File) {
        install(File(dshHome, DIR_NAME))
    }

    /**
     * 记录一次真实启动失败（覆盖写，只保留最近一次）。
     *
     * ⚠️ **必须传真实启动的输出**，不能传 `--dump-config` 的 —— 后者看不见
     * `apply()` 期的失败（见类注释）。传错了这个文件就是**又一次「结论与对象不符」**。
     *
     * @param tailLines 引擎 stdout/stderr 的尾部；内部会截断到
     *   [TAIL_MAX_LINES] 行，避免把文件撑大。
     * @return 落盘成功与否。**false 不是异常**：调用方不需要处理。
     */
    fun recordFailure(
        bootId: String,
        exitCode: Int?,
        tailLines: List<String>,
        at: Long = System.currentTimeMillis(),
    ): Boolean {
        val f = fileOf(FILE_LAST_FAILURE) ?: return false
        // ⚠️ **跨启动累加**：先读上一次的计数再 +1。见 [Failure.consecutiveFailures]
        //   里的说明 —— 这是 L1 自动隔离的触发依据，必须跨启动累积，
        //   因为每次启动都有新的 bootId（否则判据永远不成立）。
        //
        // ⚠️ 读出来的旧计数**只在同一次"失败链"里才有意义**。真实启动成功时
        //   [clearFailure] 会把整个文件删掉，计数随之归零 —— 所以这里直接
        //   读文件即可，不需要额外的时间窗判断。
        val streak = (lastFailure()?.consecutiveFailures ?: 0) + 1
        val parsed = parseFailure(bootId, exitCode, tailLines, at, streak)
        return try {
            f.writeText(toJson(parsed).toString(2))
            Log.w(TAG, "救援：已记录启动失败（第 $streak 次连续失败，" +
                "required 未激活=${parsed.requiredDidNotActivate ?: "未提及"}，" +
                "失败插件 ${parsed.failedPlugins.size} 个" +
                // ⚠️ 引擎声称的数与实际列出的常常不等（真机 40 vs 38）。
                //   不在这里如实说出来的话，日志读起来就是"列了 38 个"，
                //   而引擎明明说 40 —— 又一处同族失真。
                (parsed.failedCountMismatch?.let { "，引擎声称 $it" } ?: "") + "）")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "救援：启动失败记录写入失败: ${t.message}")
            false
        }
    }

    /**
     * 从引擎的真实输出里解析失败事实。
     *
     * 纯函数（可被测试直接调用）。
     *
     * ## 解析的两处判据都是「引擎自己说的话」
     *
     * - `dsh: startup failed: N required plugin did not activate` ⇒ [Failure.requiredDidNotActivate]
     * - `Failed plugins (N):` 之后每行 `  <name>` ⇒ [Failure.failedPlugins]
     *
     * ⚠️ 后者要**按缩进切段**：那一段的格式是真机看到的
     * （`Failed plugins (40):` 下面是 `  typert-loader`、`  Package: ...`、
     * 多行堆栈、再下一个 `  furongjun1999-dsh-memory` …）。
     * 只把「缩进后是一个合法包名、且下一行是 `Package:`」的当成插件名 ——
     * 否则会把堆栈行也当成插件名（真机上那一段有几百行）。
     *
     * ## ⚠️ 两种输入都要吃得下（这不是防御性编程，是真实存在的两种来源）
     *
     * `tailLines` 在实际调用里有**两个真实来源**，前缀形态不同：
     *
     * | 来源 | 形态 |
     * |---|---|
     * | `EngineManager` 的 stdout 实时循环 | 引擎**原始**行：`  typert-loader` |
     * | 回读 `logs/engine.log` | 带落盘前缀：`2026-10-14 07:28:52.941 [I] [engine] #414 dsh-engine-output   typert-loader` |
     *
     * 实测确认（`20261014-082616-a5d15471.zip` 的 `engine.log` 完整原始行）：
     *
     * ```
     * RAW['2026-10-14 07:28:52.941 [I] [engine] #414 dsh-engine-output   typert-loader']
     * ```
     *
     * 即**行首不是缩进、而是时间戳**。若解析器直接 `startsWith("  ")`，
     * 对第二种来源会**一行都匹配不上** —— 于是 [Failure.failedPlugins] 静默为空，
     * 而记录本身看起来「成功了」。那正是本项目反复记录的缺陷族
     * 「结论与它描述的对象不是同一个」（第 28 例）：**报了一个空的失败插件列表，
     * 读的人会以为引擎没列插件名**。
     *
     * 所以先剥掉可能的落盘前缀（[stripLogPrefix]），再按缩进判断。
     */
    fun parseFailure(
        bootId: String,
        exitCode: Int?,
        tailLines: List<String>,
        at: Long,
        consecutiveFailures: Int = 0,
    ): Failure {
        var required: Int? = null
        for (l in tailLines) {
            val m = REQUIRED_RE.find(stripLogPrefix(l)) ?: continue
            required = m.groupValues[1].toIntOrNull()
            if (required != null) break
        }

        val names = ArrayList<String>()
        var claimed: Int? = null
        // ⚠️ 先统一剥掉落盘前缀，后面才能用「行首缩进」判断（见 KDoc 的两种来源）。
        val cleaned = tailLines.map { stripLogPrefix(it) }
        val it = cleaned.iterator()
        while (it.hasNext()) {
            val l = it.next()
            val hm = FAILED_HEADER_RE.find(l)
            if (hm == null) continue
            // 头里的数字：`Failed plugins (40):` ⇒ 40
            claimed = hm.groupValues.getOrNull(1)?.toIntOrNull()
            // 段内：只认「**恰好 2 空格**缩进 + 合法包名 + 紧跟着 `Package:`」
            //
            // ⚠️ 缩进宽度是对着真机原始输出定的，不是猜的。引擎那一段的真实形态
            //   （`20261014-082616-a5d15471.zip` 的 `engine.log`）：
            //
            //     Failed plugins (40):
            //       typert-loader                      ← 2 空格 = 插件名
            //         Package: @deepseek-ai/...        ← 4 空格
            //         AggregateError: ...
            //           at Object.apply ...            ← 8 空格 = 堆栈
            //       furongjun1999-dsh-memory           ← 下一个插件名，同 2 空格
            //
            //   所以「2 空格」是唯一的插件名缩进，4/8 空格是它的附属内容。
            //   用 `startsWith("  ")` 会把 4/8 空格的行也吞进来 —— 那些行里
            //   有 `at Object.apply [as callback] (...)` 之类，虽然过不了
            //   PACKAGE_RE，但把判据依赖在「恰好不是合法包名」上是脆的
            //   （堆栈里完全可能出现一个恰好长成包名的片段）。
            //   捆上「下一行是 Package:」是第二重保险，与 KDoc 的说法一致。
            var pendingName: String? = null
            var pendingWasTwoSpace = false
            while (it.hasNext()) {
                val cur = it.next()
                // 段结束：出现下一个顶层报告行
                if (SECTION_END_RE.containsMatchIn(cur)) break
                val trimmed = cur.trim()
                if (trimmed.startsWith("Package:")) {
                    if (pendingWasTwoSpace) pendingName?.let { n -> if (n !in names) names += n }
                    pendingName = null
                    pendingWasTwoSpace = false
                    continue
                }
                pendingWasTwoSpace = cur.startsWith("  ") && !cur.startsWith("   ")
                pendingName = if (pendingWasTwoSpace && PACKAGE_RE.matches(trimmed)) trimmed else null
            }
            break // 只取第一段
        }

        return Failure(
            bootId = bootId,
            exitCode = exitCode,
            requiredDidNotActivate = required,
            failedPlugins = names,
            claimedFailedPlugins = claimed,
            consecutiveFailures = consecutiveFailures,
            tail = tailLines.takeLast(TAIL_MAX_LINES),
            at = at,
        )
    }

    /** 读回最近一次失败记录；没有或读不出时返回 null。 */
    fun lastFailure(): Failure? {
        val f = fileOf(FILE_LAST_FAILURE) ?: return null
        if (!f.isFile) return null
        return try {
            val o = JSONObject(f.readText())
            Failure(
                bootId = o.optString("bootId", ""),
                exitCode = if (o.has("exitCode") && !o.isNull("exitCode")) o.optInt("exitCode") else null,
                requiredDidNotActivate = if (o.has("requiredDidNotActivate") && !o.isNull("requiredDidNotActivate")) {
                    o.optInt("requiredDidNotActivate")
                } else {
                    null
                },
                failedPlugins = o.optJSONArray("failedPlugins")?.let { a ->
                    (0 until a.length()).map { a.optString(it, "") }.filter { it.isNotEmpty() }
                } ?: emptyList(),
                claimedFailedPlugins = if (o.has("claimedFailedPlugins") && !o.isNull("claimedFailedPlugins")) {
                    o.optInt("claimedFailedPlugins")
                } else {
                    null
                },
                // 老版本写的文件没有这个字段 ⇒ 默认 1（"这是第一次失败"）。
                // ⚠️ 用 1 而不是 0：读到旧记录说明**确实失败过一次**，
                //   给 0 会让 L1 的触发判据（>=2）少算这一次。
                consecutiveFailures = o.optInt("consecutiveFailures", 1),
                tail = o.optJSONArray("tail")?.let { a ->
                    (0 until a.length()).map { a.optString(it, "") }
                } ?: emptyList(),
                at = o.optLong("at", 0L),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "救援：启动失败记录读取失败: ${t.message}")
            null
        }
    }

    /**
     * 清除失败记录。
     *
     * ⚠️ 只在**真实启动成功之后**调用。调早了会让「上次为什么起不来」这个证据消失，
     * 而它正是救援会话里 AI 唯一的线索。
     */
    fun clearFailure(): Boolean {
        val f = fileOf(FILE_LAST_FAILURE) ?: return false
        return try {
            if (!f.exists()) return true
            f.delete()
        } catch (t: Throwable) {
            Log.w(TAG, "救援：启动失败记录清除失败: ${t.message}")
            false
        }
    }

    /**
     * 记录当前启动层级。
     *
     * ⚠️ 与 [clearFailure] 一样，这是**给人看的状态**，必须与真实情况一致 ——
     * 本项目已确认 27 例「结论与它描述的对象不符」的缺陷，这里是第 28 例的
     * 高危位置：**如果 tier 报 normal 而实际是救援启动，用户会以为一切正常。**
     */
    fun setTier(tier: Tier, reason: String, removedBundles: List<String> = emptyList(), restorePoint: String? = null): Boolean {
        val s = State(tier, System.currentTimeMillis(), reason, removedBundles, restorePoint)
        cachedState = s
        val f = fileOf(FILE_STATE) ?: return false
        return try {
            f.writeText(toJson(s).toString(2))
            Log.w(TAG, "救援：启动层级 = ${tier.id}（${tier.label}）原因=$reason")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "救援：启动层级写入失败: ${t.message}")
            false
        }
    }

    /** 当前启动层级；没记录过时视为 [Tier.NORMAL]。 */
    fun tier(): State {
        cachedState?.let { return it }
        val f = fileOf(FILE_STATE)
        val s = if (f != null && f.isFile) {
            try {
                val o = JSONObject(f.readText())
                // ⚠️ 用 `optString(name)` + `ifEmpty` 而**不是** `optString(name, null)`：
                //   后者在 Kotlin 里会把泛型推成 `Nothing?` 并报
                //   "inferred type is 'kotlin.Nothing?', but 'kotlin.String' was expected"
                //   （JSONObject 是 Java 类，可空性靠平台类型推，传 null 字面量推不出来）。
                State(
                    tier = Tier.of(o.optString("tier").ifEmpty { null }),
                    since = o.optLong("since", 0L),
                    reason = o.optString("reason", ""),
                    removedBundles = o.optJSONArray("removedBundles")?.let { a ->
                        (0 until a.length()).map { a.optString(it, "") }.filter { it.isNotEmpty() }
                    } ?: emptyList(),
                    restorePoint = o.optString("restorePoint")
                        .ifEmpty { null }
                        ?.takeIf { it != "null" },
                )
            } catch (t: Throwable) {
                Log.w(TAG, "救援：启动层级读取失败: ${t.message}")
                State(Tier.NORMAL, 0L, "", emptyList(), null)
            }
        } else {
            State(Tier.NORMAL, 0L, "", emptyList(), null)
        }
        cachedState = s
        return s
    }

    /** 是不是「非正常」层级（诊断包据此判 fail/warn）。 */
    fun isDegraded(): Boolean = tier().tier != Tier.NORMAL

    /**
     * 当前层级**从什么时候开始**的人类可读时间（`"2026-10-14 08:31:02.117"`）。
     *
     * ⚠️ 存在的理由：诊断包里说「以救援模式启动」而**不说何时起**，
     * 读的人会默认它是刚刚发生的 —— 而它可能是三天前降级后就再没恢复过。
     * 「何时」是判断「这是不是一个还在持续的问题」的唯一依据。
     *
     * 取不到（没装、文件没写成功）时返回 null，调用方应**省略时间而不是编一个**。
     */
    fun stateSinceText(): String? {
        val s = tier()
        if (s.since <= 0L) return null
        return runCatching {
            SafeDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date(s.since))
        }.getOrNull()
    }

    /**
     * 诊断包用的一句话结论。
     *
     * ⚠️ 措辞必须与 [tier] 一致，且**不能**用「未发现异常」这类词
     * —— L2/L3 下 App 是残的，说「无异常」就是第 26 例的翻版。
     */
    fun describe(): String {
        val s = tier()
        return when (s.tier) {
            Tier.NORMAL -> "正常启动"
            Tier.ISOLATED -> "已隔离 ${s.removedBundles.size} 个可疑插件后启动（其余插件照常）"
            Tier.RESCUE -> "救援模式启动（未加载任何第三方插件）—— 上次启动因插件问题失败"
            Tier.SAFE_MODE -> "安全模式启动（已推开配置并重解包引擎）"
        }
    }

    // ── 内部 ─────────────────────────────────────────────────────────────

    /**
     * 剥掉 `LogStore` 落盘时加的固定前缀，露出引擎的原始行。
     *
     * 真机 `engine.log` 的一行（完整原始内容，未做任何处理）：
     *
     * ```
     * 2026-10-14 07:28:52.941 [I] [engine] #414 dsh-engine-output   typert-loader
     * └────────── 时间戳 ──────────┘ └级别┘ └ tag ┘ └序号┘ └── 来源 ──┘ └─ 引擎原文 ─┘
     * ```
     *
     * ## 为什么不能只用正则「去掉前 N 个空格」
     *
     * 因为**引擎原文本身就以缩进开头**，而缩进正是本解析器唯一的判据
     * （2 空格 = 插件名，4/8 空格 = 附属内容）。多去或少去一个空格都会
     * 把判据搞错，且**错误是静默的**（要么漏掉全部插件名，要么把堆栈行
     * 当成插件名）。所以这里精确匹配前缀的**结构**，而不是「大概的位置」。
     *
     * ## 对两种输入都成立
     *
     * - 带前缀（回读 `engine.log`）⇒ 剥掉，返回 `  typert-loader`
     * - 不带前缀（`EngineManager` 的 stdout 实时循环）⇒ **原样返回**
     *
     * 第二种靠 [LOG_PREFIX_RE] 匹配不上来保证 —— 引擎原始行永远不可能长得像
     * `2026-10-14 07:28:52.941 [I] [engine] #414 dsh-engine-output `。
     *
     * ⚠️ `#<序号>` 那一段：真机上是**偶数递增**（`#412` → `#414` → `#416`），
     * 但那是 `LogStore` 的计数器行为，与本正则无关 —— 本正则只要求它是数字，
     * 不假设步长。假设步长会让「计数器实现变了」直接导致解析全废。
     */
    private fun stripLogPrefix(line: String): String {
        val m = LOG_PREFIX_RE.find(line) ?: return line
        return line.substring(m.range.last + 1)
    }

    /**
     * `LogStore` 给引擎 stdout 每行加的前缀。
     *
     * 形态来源：真机 `engine.log` 逐字节核对（见 [stripLogPrefix] 的 KDoc）。
     * 级别位可能是 `[I]` / `[W]` / `[E]`，所以用 `\[\w\]`。
     */
    private val LOG_PREFIX_RE = Regex(
        """^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} \[\w\] \[\w+\] #\d+ \S+ """,
    )

    private fun fileOf(name: String): File? {
        val d = dir ?: return null
        return try {
            if (!d.isDirectory && !d.mkdirs()) return null
            File(d, name)
        } catch (t: Throwable) {
            null
        }
    }

    private fun toJson(f: Failure): JSONObject = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("bootId", f.bootId)
        put("exitCode", f.exitCode ?: JSONObject.NULL)
        put("requiredDidNotActivate", f.requiredDidNotActivate ?: JSONObject.NULL)
        put("failedPlugins", JSONArray(f.failedPlugins))
        put("claimedFailedPlugins", f.claimedFailedPlugins ?: JSONObject.NULL)
        // ⚠️ 把不一致**显式写成一句话**，而不是只留两个数字让人自己除。
        //   诊断包/救援会话的读者可能是 AI，明说比让它推更可靠。
        put("failedCountMismatch", f.failedCountMismatch ?: JSONObject.NULL)
        put("consecutiveFailures", f.consecutiveFailures)
        put("tail", JSONArray(f.tail))
        put("at", f.at)
        put("atText", SafeDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date(f.at)))
    }

    private fun toJson(s: State): JSONObject = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("tier", s.tier.id)
        put("since", s.since)
        put("sinceText", SafeDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date(s.since)))
        put("reason", s.reason)
        put("removedBundles", JSONArray(s.removedBundles))
        put("restorePoint", s.restorePoint ?: JSONObject.NULL)
    }

    /** 尾部最多留多少行（够看清失败原因，又不至于把文件撑大）。 */
    const val TAIL_MAX_LINES = 120

    /** `dsh: startup failed: 1 required plugin did not activate` */
    private val REQUIRED_RE = Regex("""startup failed:\s*(\d+)\s+required plugin""")

    /** `Failed plugins (40):` —— 组 1 = 引擎声称的条目数。 */
    private val FAILED_HEADER_RE = Regex("""Failed plugins\s*\((\d+)\)""")

    /** 段结束：下一个顶层报告行。 */
    private val SECTION_END_RE = Regex("""^(Plugins waiting|Failed plugins|dsh:|Error:)""")

    /** 是否是合法包名（`foo`、`@scope/name`）。 */
    private val PACKAGE_RE = Regex("""^(@[a-z0-9][\w.-]*/)?[a-z0-9][\w.-]*$""")
}
