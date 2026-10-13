package com.dsh.android.log

import android.content.Context

/**
 * 日志系统自己的 Application 上下文持有者。
 *
 * ## 为什么单独持有一份，而不是用 `DshApplication.instance`
 *
 * 日志系统有一条**极早期的调用路径**：`attachBaseContext()` 里就要装落盘、装崩溃
 * 处理器，而那时 `Application.onCreate()` 还没跑、`DshApplication.instance` 还是
 * `lateinit` 未初始化的状态 —— 去读它会抛 `UninitializedPropertyAccessException`。
 *
 * 而崩溃路径上更麻烦：崩溃可能发生在 `onCreate` 之前或之中。
 *
 * 所以这里持有一份**最早就能拿到**的引用：`attachBaseContext(base)` 的参数本身
 * 就是一个可用的 `Context`（`ContextWrapper` 包装后的 Application），比
 * `onCreate` 早一整个阶段。
 *
 * ## 允许为 null
 *
 * [get] 返回可空 —— 所有调用方都必须容忍拿不到上下文（退回「只采集不依赖
 * Context 的部分」），绝不能因为拿不到上下文而让诊断包生成失败。
 * 见 [EnvironmentSnapshot] 的类注释。
 */
object LogContext {

    @Volatile
    private var ctx: Context? = null

    /** 装上上下文。幂等；重复安装只是覆盖（后续调用给的更完整，直接覆盖即可）。 */
    fun install(context: Context) {
        ctx = context.applicationContext ?: context
    }

    /** 当前上下文；未安装时为 null。 */
    fun get(): Context? = ctx

    /**
     * 应用私有目录。**所有日志落盘都以它为根**。
     *
     * 未安装上下文时返回 null —— 调用方据此退回纯内存模式。
     */
    fun filesDirOrNull(): java.io.File? = ctx?.filesDir
}
