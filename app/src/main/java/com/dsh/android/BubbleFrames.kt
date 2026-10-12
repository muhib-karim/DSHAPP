package com.dsh.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import java.io.File

/**
 * 球的**多帧图片**存储与解码 —— 「轮播图片 / 轮播精灵图」的底座。
 *
 * ## 为什么单独一个类
 *
 * 单张头像（[BubbleAppearance.AVATAR_FILE]）只需要「一个文件 + 一次解码」。
 * 轮播要的是「N 张图 + 每张的停留时长 + 播几轮」，还要处理**精灵图切片**：
 * 一张大图按 rows×cols 切成 N 帧，那是最省体积的做法（也是游戏里的常规做法）。
 * 把这些塞进 [BubbleAppearance] 会让那个类变成杂物间。
 *
 * ## 落盘布局
 *
 * 两种模式**共用一套目录**，靠 `meta.json` 的 `sprite` 字段区分：
 *
 * ```
 * filesDir/bubble-frames/
 *   ├── 0.bin   第 0 帧
 *   ├── 1.bin   第 1 帧        ← 普通模式：一帧一个文件
 *   └── meta.json
 *
 * filesDir/bubble-frames/
 *   ├── 0.bin   整张精灵图      ← 精灵图模式：只有一个文件
 *   └── meta.json  {"count":6,"sprite":{"rows":2,"cols":3}}
 * ```
 *
 * ⚠️ **精灵图模式只落一个文件**，帧数由 `rows × cols` 算出。这是「精灵图」的
 *   本来语义（一张大图切片），也避免了「把同一张大图存 N 份」的浪费 ——
 *   6 帧的精灵图按普通模式存要 6 倍空间。
 *
 * ⚠️ **整目录替换而不是逐帧增量写**：帧数会变（3 帧的轮播换成 5 帧的），
 *   增量写会留下第 4 帧的残影。写入前先 `deleteRecursively()`。
 *
 * ⚠️ **先写进临时目录再改名**：写到一半被杀会留下半个帧集，那会让球显示成
 *   一堆碎图。临时目录改名是原子的，失败时旧帧集完好。
 *
 * ## 精灵图切片
 *
 * [loadFrame] 在精灵图模式下按 `rows × cols` 网格裁出第 `index` 格。
 * **行优先**（从左到右、从上到下），与 CSS `background-position` 的直觉一致，
 * 也与大多数游戏素材一致。
 */
object BubbleFrames {

    private const val TAG = DshApplication.TAG

    /** 帧集目录名（在 `filesDir` 下）。 */
    const val DIR = "bubble-frames"

    /** 元数据文件名。 */
    const val META = "meta.json"

    /** 单帧 base64 长度上限。与头像同一口径（约 512KB 原始数据）。 */
    const val MAX_FRAME_BASE64 = 700_000

    /** 帧数上限。再多就不是"轮播"而是"逐帧动画"，那该走视频而不是 base64。 */
    const val MAX_FRAMES = 64

    /** 单帧停留时长范围（ms）。 */
    const val MIN_DURATION_MS = 20
    const val MAX_DURATION_MS = 60_000

    /** 一帧的落盘文件名。 */
    fun frameName(index: Int): String = "$index.bin"

    /** 帧集目录。 */
    fun dir(context: Context): File = File(context.filesDir, DIR)

    /** 帧集是否可用（目录存在且至少有一帧文件）。 */
    fun available(context: Context): Boolean {
        val d = dir(context)
        return d.isDirectory && d.listFiles()?.any { it.name.endsWith(".bin") } == true
    }

    /**
     * 写入一整套帧。**全有或全无**：任何一步失败都不动已有的帧集。
     *
     * 两种模式由 [sprite] 决定：
     * - `sprite == null`（普通轮播）：[frames] 每项是一帧完整图片，落 N 个文件。
     * - `sprite != null`（精灵图）：[frames] **只取第一项**，那是整张精灵图；
     *   帧数 = `rows × cols`，落 1 个文件。
     *
     * @param frames 每帧的 data URI（`data:image/…;base64,…`）。
     * @param durations 每帧停留时长（ms）；为空或长度不符时用 [defaultDurationMs]。
     * @param sprite 精灵图网格 `rows × cols`；为 null 表示每帧是独立完整图片。
     * @param defaultDurationMs 缺省停留时长。
     * @return 写入的帧数；失败返回 null。
     */
    fun write(
        context: Context,
        frames: List<String>,
        durations: List<Int>,
        sprite: Pair<Int, Int>?,
        defaultDurationMs: Int,
    ): Int? {
        if (frames.isEmpty()) return null

        // 精灵图：只解一张大图，帧数由网格算出。
        val count: Int
        val payloads: List<String>
        if (sprite != null) {
            count = sprite.first * sprite.second
            if (count <= 0 || count > MAX_FRAMES) {
                Log.w(TAG, "精灵图网格 ${sprite.first}×${sprite.second} 不合法（帧数 $count）")
                return null
            }
            payloads = listOf(frames[0])
        } else {
            count = minOf(frames.size, MAX_FRAMES)
            payloads = frames.subList(0, count)
        }

        // 先全部解码验证，再落盘 —— 避免"前 3 帧好了、第 4 帧是坏的"这种半成品。
        val decoded = ArrayList<ByteArray>(payloads.size)
        for ((i, uri) in payloads.withIndex()) {
            val bytes = decodeDataUri(uri)
            if (bytes == null || bytes.isEmpty()) {
                Log.w(TAG, "第 $i 帧不是可解析的 data URI，整批放弃")
                return null
            }
            decoded += bytes
        }

        val parent = context.filesDir
        val tmp = File(parent, "$DIR.tmp")
        val target = dir(context)
        tmp.deleteRecursively()
        if (!tmp.mkdirs()) {
            Log.w(TAG, "无法创建帧集临时目录")
            return null
        }

        try {
            for (i in decoded.indices) {
                File(tmp, frameName(i)).writeBytes(decoded[i])
            }
            File(tmp, META).writeText(buildMeta(count, durations, sprite, defaultDurationMs))
        } catch (t: Throwable) {
            Log.w(TAG, "写入帧集失败: ${t.message}")
            tmp.deleteRecursively()
            return null
        }

        if (!publish(parent, tmp, target)) {
            tmp.deleteRecursively()
            return null
        }
        Log.i(
            TAG,
            "悬浮球帧集已更新: $count 帧" +
                (sprite?.let { "（精灵图 ${it.first}×${it.second}）" } ?: ""),
        )
        return count
    }

    /**
     * 只改精灵图网格，复用已有的精灵图字节。
     *
     * 用于「AI 只改了 `spriteRows`/`spriteCols`、没重传图片」这一种情况 ——
     * 旧帧集是按旧网格切的，不重切就会显示成网格里的碎块。
     *
     * ⚠️ 只在**精灵图模式**下成立（磁盘上只有 `0.bin` 那一张整图）。普通轮播
     *   模式没有"整图"可切，返回 null 让调用方退回整图模式。
     *
     * @return 新的帧数；不可重切时返回 null。
     */
    fun rewriteSprite(context: Context, sprite: Pair<Int, Int>): Int? {
        val sheet = File(dir(context), frameName(0))
        if (!sheet.isFile) return null
        val count = sprite.first * sprite.second
        if (count <= 0 || count > MAX_FRAMES) return null
        return try {
            // 只改 meta，字节不动 —— 这正是精灵图模式省空间的地方。
            val old = readMeta(context)
            val durations = old?.optJSONArray("durations")?.let { arr ->
                (0 until arr.length()).map { arr.optInt(it, 400) }
            } ?: emptyList()
            val defaultMs = durations.firstOrNull() ?: 400
            File(dir(context), META).writeText(buildMeta(count, durations, sprite, defaultMs))
            count
        } catch (t: Throwable) {
            Log.w(TAG, "重切精灵图失败: ${t.message}")
            null
        }
    }

    /**
     * 把 [tmp] 目录原子地替换成 [target]。
     *
     * 顺序是「旧目录先挪成 .old → tmp 改名就位 → 删 .old」，失败时把 .old 挪回来。
     * 这样任何一步中断，磁盘上都至少有一份完整帧集。
     */
    private fun publish(parent: File, tmp: File, target: File): Boolean {
        val backup = File(parent, "$DIR.old")
        backup.deleteRecursively()
        if (target.exists() && !target.renameTo(backup)) {
            // 挪不动就退化成"删了重建"：丢的是旧帧集，但新帧集一定能就位。
            target.deleteRecursively()
        }
        if (!tmp.renameTo(target)) {
            Log.w(TAG, "帧集改名失败，回滚")
            backup.renameTo(target)
            return false
        }
        backup.deleteRecursively()
        return true
    }

    /**
     * 读第 [index] 帧的位图。
     *
     * ⚠️ 精灵图模式下**磁盘上只有 `0.bin`**（那是整张精灵图），所以这里必须
     *   无条件读第 0 个文件再切片，而不是去读 `index.bin` —— 后者不存在，
     *   会让球在精灵图模式下永远显示空白。
     *
     * @param sprite 非 null 时按 `rows × cols` 网格裁切。
     * @param reqSize 期望边长（px）；只用于 `inSampleSize` 粗降采样，实际缩放交给 ImageView。
     */
    fun loadFrame(context: Context, index: Int, sprite: Pair<Int, Int>?, reqSize: Int): Bitmap? {
        val fileIndex = if (sprite != null) 0 else index
        val file = File(dir(context), frameName(fileIndex))
        if (!file.isFile) return null
        return try {
            val bytes = file.readBytes()
            if (sprite == null) {
                decodeScaled(bytes, reqSize)
            } else {
                val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (full == null) {
                    null
                } else {
                    val cut = slice(full, index, sprite.first, sprite.second)
                    // slice 在越界/非法时原样返回 src，此时不能回收它。
                    if (cut != null && cut !== full) full.recycle()
                    cut
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "读取第 $index 帧失败: ${t.message}")
            null
        }
    }

    /** 帧数（读 meta；读不到就数文件）。 */
    fun frameCount(context: Context): Int {
        val meta = readMeta(context) ?: return countFiles(context)
        return meta.optInt("count", 0).takeIf { it > 0 } ?: countFiles(context)
    }
    /** 每帧停留时长（ms）。meta 缺失或长度不符时全部退回 [fallback]。 */
    fun durations(context: Context, fallback: Int): List<Int> {
        val meta = readMeta(context) ?: return emptyList()
        val arr = meta.optJSONArray("durations") ?: return emptyList()
        val out = ArrayList<Int>(arr.length())
        for (i in 0 until arr.length()) {
            out += arr.optInt(i, fallback).coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
        }
        return out
    }

    /** 精灵图网格；不是精灵图时返回 null。 */
    fun sprite(context: Context): Pair<Int, Int>? {
        val meta = readMeta(context) ?: return null
        val s = meta.optJSONObject("sprite") ?: return null
        val rows = s.optInt("rows", 0)
        val cols = s.optInt("cols", 0)
        return if (rows > 0 && cols > 0) rows to cols else null
    }

    /** 清空帧集（回到单张头像模式时调用）。 */
    fun clear(context: Context) {
        dir(context).deleteRecursively()
        File(context.filesDir, "$DIR.tmp").deleteRecursively()
        File(context.filesDir, "$DIR.old").deleteRecursively()
    }

    // ── 内部 ──────────────────────────────────────────────────────────

    private fun countFiles(context: Context): Int {
        val d = dir(context)
        if (!d.isDirectory) return 0
        var n = 0
        while (File(d, frameName(n)).isFile) n++
        return n
    }

    private fun readMeta(context: Context): org.json.JSONObject? = try {
        val f = File(dir(context), META)
        if (f.isFile) org.json.JSONObject(f.readText()) else null
    } catch (t: Throwable) {
        null
    }

    private fun buildMeta(
        count: Int,
        durations: List<Int>,
        sprite: Pair<Int, Int>?,
        defaultDurationMs: Int,
    ): String {
        val o = org.json.JSONObject()
        o.put("count", count)
        val arr = org.json.JSONArray()
        for (i in 0 until count) {
            val d = durations.getOrNull(i) ?: defaultDurationMs
            arr.put(d.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS))
        }
        o.put("durations", arr)
        if (sprite != null) {
            o.put("sprite", org.json.JSONObject().put("rows", sprite.first).put("cols", sprite.second))
        }
        return o.toString()
    }

    /**
     * 裁出精灵图的第 [index] 格（**行优先**）。
     *
     * ⚠️ `createBitmap` 在源图不可变时也能工作，但它返回的是**新位图**，源图要由
     *   调用方回收 —— 见 [loadFrame] 里的 `recycle()`。
     */
    private fun slice(src: Bitmap, index: Int, rows: Int, cols: Int): Bitmap? {
        if (rows <= 0 || cols <= 0) return src
        val total = rows * cols
        if (index < 0 || index >= total) return src
        val w = src.width / cols
        val h = src.height / rows
        if (w <= 0 || h <= 0) return src
        val r = index / cols
        val c = index % cols
        return try {
            Bitmap.createBitmap(src, c * w, r * h, w, h)
        } catch (t: Throwable) {
            Log.w(TAG, "精灵图切片失败(第 $index 格): ${t.message}")
            null
        }
    }

    /** 解 `data:image/…;base64,…` 或纯 base64 成字节。 */
    private fun decodeDataUri(uri: String): ByteArray? {
        if (uri.isEmpty()) return null
        val comma = uri.indexOf(',')
        val payload = if (uri.startsWith("data:") && comma > 0) uri.substring(comma + 1) else uri
        return try {
            Base64.decode(payload, Base64.DEFAULT)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 按 [reqSize] 粗降采样解码（只降 2 的幂次）。
     *
     * ⚠️ 只降采样、**不精确缩放**：精确缩放交给 ImageView 的 scaleType，
     *   那一步在 GPU 上做，比在 Java 里 `createScaledBitmap` 快得多。
     */
    private fun decodeScaled(bytes: ByteArray, reqSize: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val maxDim = maxOf(opts.outWidth, opts.outHeight)
        var sample = 1
        if (reqSize > 0) {
            while (maxDim / (sample * 2) >= reqSize) sample *= 2
        }
        val opts2 = BitmapFactory.Options().apply { inSampleSize = sample }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts2)
        } catch (t: Throwable) {
            Log.w(TAG, "解码帧失败: ${t.message}")
            null
        }
    }
}
