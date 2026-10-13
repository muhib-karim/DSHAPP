package com.dsh.android.log

/**
 * 密钥脱敏 —— 日志**离开设备之前**的最后一道网。
 *
 * ## 为什么这不是可选项
 *
 * 诊断包会被上传到服务器、会被用户导出分享给别人。调用方只要有一次把 `apiKey`
 * 拼进日志消息（`"用 key=$apiKey 请求"` 这种写法太自然了），用户的密钥就会跟着
 * 诊断包一起离开设备。而这类泄漏**不可撤销** —— 密钥已经出去了。
 *
 * 所以这里做**兜底**，而不是依赖调用方自觉。⚠️ 但这**不是许可证**：调用方仍然
 * 不许主动把密钥拼进日志。
 *
 * ## 为什么放在独立文件
 *
 * 原先这套正则长在 `AppLogger` 里，而日志系统现在有多个写入方（`LogStore`、
 * `EventLog`、`CrashRecorder`、`EnvironmentSnapshot`）。任何一个漏了脱敏就是一个
 * 泄漏口，所以收敛到一处、由所有写入方共用。
 *
 * ## 设计取舍：宁可多抹，不可漏抹
 *
 * 误抹的代价是「日志里某段看着像密钥的东西变成 `***REDACTED***`」—— 分析时
 * 少一点信息，但还能看懂上下文。漏抹的代价是密钥泄漏。两者不对称，所以模式
 * 刻意写得宽。
 */
object Redactor {

    /**
     * 键名 + 分隔符 + 值：`api_key = "sk-abc..."` / `token: xyz` / `Authorization: Bearer ...`
     *
     * 值长度下限 8：太短的多半是 `token: 无` 这类正常文本，抹了反而看不懂。
     */
    private val secretKeyPattern = Regex(
        "(?i)\\b(api[_-]?key|apikey|secret|token|password|passwd|authorization|bearer)" +
            "(\\s*[=:]\\s*)([\"']?)([A-Za-z0-9_\\-./+]{8,})\\3",
    )

    /**
     * 常见厂商的 token 前缀形态：`sk-…` / `sk-ant-…` / `xai-…` / `gsk_…` / `AIza…`
     *
     * ⚠️ 与上面那条**并存**而不是替代：这条抓「值本身长得像密钥」，上面那条抓
     * 「键名说明这是密钥」。两条都留着才能覆盖 `Authorization: Bearer sk-xxx`
     * 与裸的 `sk-xxx` 两种形态。
     */
    private val skTokenPattern = Regex("\\b(sk|sk-or|sk-ant|xai|gsk|AIza)[-_][A-Za-z0-9_\\-]{12,}")

    /**
     * URL query 里的密钥参数：`?token=abc...` / `&api_key=xyz`
     *
     * ⚠️ 这一条是本项目特有的：引擎启动时会打印
     * `dsh web: http://127.0.0.1:3080/?token=...`，那个 token 就是 Web GUI 的
     * 访问凭据。整行日志会被打进诊断包，不抹掉等于把会话入口一起送出去。
     */
    private val urlSecretPattern = Regex(
        "(?i)([?&](?:token|api[_-]?key|key|secret|access_token)=)([A-Za-z0-9_\\-./+%]{8,})",
    )

    /**
     * 抹掉 [s] 里形似密钥的串。空串原样返回。
     *
     * ⚠️ **幂等**：已经含 `***REDACTED***` 的文本再过一遍结果不变。这一点是必需的
     * —— 日志会被多处处理（写入时一次、打包时可能再一次），不幂等就会把
     * 标记本身也啃掉。
     */
    fun redact(s: String): String {
        if (s.isEmpty()) return s
        var out = s

        // 顺序：先抹「值长得像密钥」的，再抹「键名说明是密钥」的。
        // 反过来的话，第一条会把 sk-xxx 换成 ***REDACTED***，第二条的
        // `[A-Za-z0-9_\-./+]{8,}` 又可能匹配上标记里的字母 —— 虽然标记本身
        // 不会泄漏，但会产出 `***REDACTED***REDACTED***` 这种脏输出。
        if (out.contains("sk-") || out.contains("sk_") || skTokenPattern.containsMatchIn(out)) {
            out = skTokenPattern.replace(out) { m -> m.value.take(6) + "***REDACTED***" }
        }
        if (secretKeyPattern.containsMatchIn(out)) {
            out = secretKeyPattern.replace(out) { m ->
                "${m.groupValues[1]}${m.groupValues[2]}${m.groupValues[3]}***REDACTED***${m.groupValues[3]}"
            }
        }
        if (out.contains("token=") || out.contains("api_key=") || out.contains("key=")) {
            out = urlSecretPattern.replace(out) { m -> "${m.groupValues[1]}***REDACTED***" }
        }
        return out
    }

    /**
     * 判断一段文本里是否**还有**形似密钥的串（自检用）。
     *
     * 用于诊断包的生成后校验：万一某个新写入方忘了调 [redact]，这一步能兜住
     * 并在包里标注出来，而不是静默泄漏。
     */
    fun looksUnredacted(s: String): Boolean =
        skTokenPattern.containsMatchIn(s) ||
            secretKeyPattern.containsMatchIn(s) ||
            urlSecretPattern.containsMatchIn(s)
}
