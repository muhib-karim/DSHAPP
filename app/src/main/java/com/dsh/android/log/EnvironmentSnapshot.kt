package com.dsh.android.log

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.DisplayMetrics
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 环境快照 —— 诊断包里回答「这是台什么机器、什么版本、什么状态」的那一份。
 *
 * ## 为什么必须有
 *
 * 本项目大量故障是**设备相关**的：Android 版本决定硬约束②（`targetSdk ≤ 28`）
 * 能否满足、`filesDir` 能否 `execve`；WebView 内核版本决定前端 bundle 能不能跑；
 * 剩余空间决定解包会不会失败；内存决定会不会被 LMK 杀。
 *
 * 而用户报问题时**永远不会提供这些**（他们只会说「打不开」）。所以必须在诊断包里
 * 自动带上 —— 这是把「来回问三轮」变成「一次到位」的关键。
 *
 * ## 全部字段都允许失败
 *
 * 这个类在**启动极早期**、甚至**崩溃路径**上都可能被调用。任何一项取不到都
 * 记成 `null` 或 `"unknown"`，绝不抛异常 —— 少一个字段的诊断包有用，
 * 生成失败的诊断包没用。
 */
object EnvironmentSnapshot {

    /**
     * 采集快照。
     *
     * @param context 应用上下文；为 null 时只采集不依赖 Context 的部分
     *   （崩溃路径上 `Application` 可能还没 attach 完）。
     */
    fun collect(context: Context?): JSONObject {
        val o = JSONObject()

        // ── App ──
        runCatching {
            o.put(
                "app",
                JSONObject().apply {
                    put("versionName", VersionInfo.versionName)
                    put("versionCode", VersionInfo.versionCode)
                    put("flavor", VersionInfo.flavor)
                    put("applicationId", VersionInfo.applicationId)
                    put("debuggable", isDebuggable())
                },
            )
        }

        // ── 设备 / 系统 ──
        runCatching {
            o.put(
                "device",
                JSONObject().apply {
                    put("manufacturer", Build.MANUFACTURER)
                    put("brand", Build.BRAND)
                    put("model", Build.MODEL)
                    put("device", Build.DEVICE)
                    put("hardware", Build.HARDWARE)
                    put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
                    put("release", Build.VERSION.RELEASE)
                    put("sdkInt", Build.VERSION.SDK_INT)
                    put("previewSdk", Build.VERSION.PREVIEW_SDK_INT)
                    put("securityPatch", if (Build.VERSION.SDK_INT >= 23) Build.VERSION.SECURITY_PATCH else "")
                    // ⚠️ 内核版本是硬约束②的判据之一（execve 能否用取决于内核+SELinux）
                    put("kernel", kernelVersion())
                    put("locale", java.util.Locale.getDefault().toString())
                    put("timeZone", java.util.TimeZone.getDefault().id)
                },
            )
        }

        // ── 屏幕（本项目大量 UI 修复与尺寸/DPI 有关）──
        runCatching {
            val wm = context?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm?.defaultDisplay?.getMetrics(dm)
            o.put(
                "display",
                JSONObject().apply {
                    put("widthPx", dm.widthPixels)
                    put("heightPx", dm.heightPixels)
                    put("densityDpi", dm.densityDpi)
                    put("density", dm.density.toDouble())
                    put("scaledDensity", dm.scaledDensity.toDouble())
                },
            )
        }

        // ── 内存（LMK 杀进程的判据）──
        runCatching {
            val am = context?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            o.put(
                "memory",
                JSONObject().apply {
                    put("totalBytes", mi.totalMem)
                    put("availBytes", mi.availMem)
                    put("thresholdBytes", mi.threshold)
                    put("lowMemory", mi.lowMemory)
                    put("appMaxHeapBytes", am?.memoryClass?.toLong()?.times(1024L * 1024L) ?: 0L)
                },
            )
        }

        // ── 磁盘（解包失败的常见原因）──
        runCatching {
            val files = context?.filesDir
            o.put(
                "storage",
                JSONObject().apply {
                    if (files != null) {
                        val fs = StatFs(files.absolutePath)
                        put("dataFreeBytes", fs.availableBytes)
                        put("dataTotalBytes", fs.totalBytes)
                    }
                    runCatching {
                        val ext = Environment.getExternalStorageDirectory()
                        val fs = StatFs(ext.absolutePath)
                        put("externalFreeBytes", fs.availableBytes)
                        put("externalTotalBytes", fs.totalBytes)
                    }
                },
            )
        }

        // ── 权限（决定哪些功能会「静默失效」）──
        runCatching {
            if (context != null) {
                o.put(
                    "permissions",
                    JSONObject().apply {
                        put("notifications", hasPermission(context, "android.permission.POST_NOTIFICATIONS"))
                        put("storage", hasPermission(context, "android.permission.WRITE_EXTERNAL_STORAGE"))
                        put("microphone", hasPermission(context, "android.permission.RECORD_AUDIO"))
                        // 悬浮窗是特殊权限，必须查系统设置
                        put("overlay", hasOverlay(context))
                        put("batteryExempt", isBatteryExempt(context))
                    },
                )
            }
        }

        // ── 运行环境（引擎那一侧）──
        runCatching {
            o.put(
                "runtime",
                JSONObject().apply {
                    put("bootId", BootState.bootId)
                    put("uptimeMs", BootState.uptimeMs())
                    put("logFileLogging", LogStore.fileLoggingEnabled)
                    put("logThrottled", LogStore.throttledTotal)
                    put("logDroppedAsync", LogStore.droppedAsync)
                    put("javaVersion", System.getProperty("java.vm.version") ?: "")
                    put("availableProcessors", Runtime.getRuntime().availableProcessors())
                    put("maxMemoryBytes", Runtime.getRuntime().maxMemory())
                },
            )
        }

        return o
    }

    /**
     * 采集「关键文件状态」—— 回答「资产到底解包了没有」。
     *
     * ## 为什么这一项特别重要
     *
     * 本项目最常见的故障是**资产没解包/解包不全**（版本戳不一致 → 整体跳过、
     * 磁盘满 → 解包到一半、被用户清数据 → 全没了）。这些在日志里表现为
     * 「某功能静默不工作」，而看日志的人很难判断「到底哪个文件该在而没在」。
     *
     * 所以这里主动把**该存在的文件**列出来，逐个报「存在/大小/时间」——
     * 一眼就能看出缺哪个。文件清单由调用方传入（`EngineManager` 知道该有哪些），
     * 本类不猜。
     */
    fun collectFileStates(files: List<File>): JSONObject {
        val arr = JSONArray()
        for (f in files) {
            runCatching {
                arr.put(
                    JSONObject().apply {
                        // ⚠️ 路径里含用户目录名，但都是 app 私有目录下的相对结构，
                        //    不含用户名/账号信息，可以进包（见 Redactor 的说明）。
                        put("path", f.absolutePath)
                        put("exists", f.exists())
                        put("isDirectory", f.isDirectory)
                        put("sizeBytes", if (f.exists()) f.length() else 0L)
                        put("lastModified", if (f.exists()) f.lastModified() else 0L)
                    },
                )
            }
        }
        return JSONObject().apply { put("files", arr) }
    }

    // ── 私有工具 ──────────────────────────────────────────────────────────────

    private fun isDebuggable(): Boolean = runCatching {
        (LogContext.get()?.applicationInfo?.flags?.and(android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) ?: 0) != 0
    }.getOrDefault(false)

    /**
     * 内核版本。读 `/proc/version`（所有 Android 都读得到，不需要 root）。
     *
     * ⚠️ 用 `readText` 而不是 `uname` 系统调用：不需要额外权限，且这正是
     * 硬约束②排查时要看的那一行。
     */
    private fun kernelVersion(): String = runCatching {
        File("/proc/version").readText().trim().take(200)
    }.getOrDefault("")

    private fun hasPermission(context: Context, perm: String): Boolean = runCatching {
        context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun hasOverlay(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) android.provider.Settings.canDrawOverlays(context) else true
    }.getOrDefault(false)

    private fun isBatteryExempt(context: Context): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)
}
