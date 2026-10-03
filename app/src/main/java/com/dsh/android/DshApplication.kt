package com.dsh.android

import android.app.Application
import android.util.Log
import com.dsh.android.termux.TermuxBootstrap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口：在后台把 Termux 环境准备好。
 *
 * 为什么放在 Application 而不是 Activity：bootstrap 首次要解压 23 MB（约 1–2 秒），
 * 放在 Activity 里会随转屏重建反复触发。这里用 applicationScope 起一次，进程内共享。
 *
 * ⚠️ 三条都必须调用，且 [TermuxBootstrap.ensurePathsRewritten] /
 * [TermuxBootstrap.ensureMirrorsConfigured] **每次启动都要调**：
 * `install()` 有 `.bootstrap_done` 标记，升级安装的机器不会再进，新增的修复对老装机
 * 永远不生效。这是 kit 的 README §3.4 特意记下的坑。
 */
class DshApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "DSH application created")
        bootstrapTermux()
    }

    private fun bootstrapTermux() {
        applicationScope.launch {
            val t0 = System.currentTimeMillis()
            try {
                val bootstrap = TermuxBootstrap(this@DshApplication)
                if (!bootstrap.isReady) {
                    bootstrap.install()
                }
                bootstrap.ensurePathsRewritten()
                bootstrap.ensureMirrorsConfigured()
                // Node 运行时单独一层：它在 bootstrap 之后铺入，标记也独立
                val ok = bootstrap.installNodeRuntime()
                bootstrap.ensureNodeRuntimePatched()
                Log.i(
                    TAG,
                    "Termux 引导完成（${System.currentTimeMillis() - t0} ms），" +
                        "node=${if (ok) bootstrap.nodeBinary.absolutePath else "不可用"}",
                )
            } catch (t: Throwable) {
                // 引导失败不在这里崩溃：EngineManager 启动时会再检查一次并给出
                // 带具体路径的报错，UI 上能看到，比进程直接挂掉好定位。
                Log.e(TAG, "Termux 引导失败", t)
            }
        }
    }

    companion object {
        const val TAG = "DSH"

        lateinit var instance: DshApplication
            private set

        /**
         * 进程级协程作用域：用于不随任何界面生命周期结束的后台任务。
         * 用 SupervisorJob 让一个子任务失败不影响其它任务。
         */
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
