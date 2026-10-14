package com.dsh.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.dsh.android.log.DshLog as Log
import java.io.File

/**
 * 悬浮球内容层的**视频渲染后端** —— 让球能播带 alpha 通道的 webm。
 *
 * ## 为什么是 WebView（而不是 MediaPlayer / VideoView）
 *
 * 原生 Android **没有任何 View 能播透明视频**：`MediaPlayer`/`VideoView` 走
 * `SurfaceView`/`TextureView`，两者都不参与 alpha 合成；MediaCodec 的 VP9 解码器
 * 也不输出 alpha side data。真机实测（CDY-TN90 / Android 10 / Chromium 114）
 * 唯一保得住 alpha 的路是 **WebView 的 `video` → `canvas`**：
 * 透明背景占 82.9%、四角像素 `a≈0`。
 *
 * ⚠️ **WebView 只是"内容层的一个渲染后端"，不是悬浮球的实现。** 拖拽、抛掷、
 *   物理、状态映射、面板、贴边折叠全部仍在原生 Kotlin 里 —— 这正是它与
 *   「把整个球做成一个网页」的区别：后者的触摸、焦点、输入法、层级全都要重来一遍，
 *   而本 App 的悬浮窗契约（[FloatingBubble] 里那几条）已经踩平了那些坑。
 *
 * ## 用 canvas 而不是直接 `<video>`
 *
 * 直接显示 `<video>` 有两个问题：① 各内核对"透明视频"的合成路径不一致，canvas
 * 是逐帧重绘、alpha 由我们自己保证；② 挤压/拉伸（squash & stretch）要靠
 * `ctx.drawImage` 的目标矩形做，CSS transform 在缩放时会把边缘插值成半透明。
 *
 * ⚠️ **全程不调用 `getImageData`/`toDataURL`**：那两个会把 canvas 标记成"被污染"，
 *   在 `file://` 下直接抛 SecurityError。绘制本身不需要读回像素。
 *
 * ## 动作切换
 *
 * 一次只播一个动作（单 `<video>` 元素，切源即换动作）。播放循环由原生决定：
 * - `loop = true`（待机类）：JS 自己无限循环，**不回调**，省掉每轮的进程往返；
 * - `loop = false`（一次性动作）：播完回调 [onEnded]，由原生挑下一个动作。
 *
 * ⚠️ **`loop=false` 时必须显式 `video.loop=false`**：上一轮若是待机动作，`loop`
 *   还停在 `true`，不重置的话一次性动作会无声地循环下去，表现为"做完这个动作
 *   就卡住不换"。
 */
class BubbleVideoLayer(
    private val context: Context,
    /** 一次性动作播完时的回调（参数是刚播完的动作名）。在主线程调用。 */
    private val onEnded: (String) -> Unit,
) {

    private companion object {
        const val TAG = DshApplication.TAG

        /** 播放器 HTML 的落点目录（在 `filesDir` 下）。 */
        const val PLAYER_DIR = "bubble-player"
        const val PLAYER_FILE = "player.html"
    }

    /** 承载 WebView 的容器（`bubbleCircle` 里的那一层）。 */
    private var host: android.widget.FrameLayout? = null

    private var web: WebView? = null

    /** 页面是否已加载完成（未完成时 evaluateJavascript 会丢）。 */
    private var pageReady = false

    /** 当前正在播的动作名。 */
    private var current = ""

    /** 等待页面就绪后要补播的动作（`play` 早于 `onPageFinished` 时用）。 */
    private var pending: Triple<String, String, Boolean>? = null

    /** 页面加载失败后是否已经报过（避免刷日志）。 */
    private var warned = false

    /** 是否已经创建过 WebView（创建很贵，只在真有视频素材时做）。 */
    val created: Boolean get() = web != null

    /** 当前动作名。 */
    val action: String get() = current

    /**
     * 惰性创建 WebView 并挂到 [container] 上。
     *
     * ⚠️ **必须惰性**：一个 WebView 常驻要几十 MB 内存，而绝大多数用户根本不会
     *   放视频素材（球就是一张头像）。没有视频时连 WebView 都不该存在。
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun attach(container: android.widget.FrameLayout) {
        host = container
        if (web != null) return

        val html = ensurePlayerFile() ?: run {
            Log.w(TAG, "视频播放器页面写入失败，视频层不可用")
            return
        }

        val w = WebView(context)
        // 透明背景：窗口本身已是 TRANSLUCENT，这里再压掉 WebView 自己的白底。
        w.setBackgroundColor(Color.TRANSPARENT)
        w.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        w.isVerticalScrollBarEnabled = false
        w.isHorizontalScrollBarEnabled = false
        w.overScrollMode = android.view.View.OVER_SCROLL_NEVER
        // ⚠️ WebView 默认会消费触摸 —— 悬浮球的拖动/点击靠父容器的
        //   onInterceptTouchEvent 抢回来（见 BubbleTouchFrame）。
        w.isClickable = false
        w.isFocusable = false
        w.isLongClickable = false

        w.settings.apply {
            javaScriptEnabled = true
            // 素材与页面都在 filesDir 里，都是 file:// —— 必须放行同源文件访问。
            allowFileAccess = true
            allowFileAccessFromFileURLs = true
            allowContentAccess = false
            // ⚠️ 关掉缩放：球本身就是最终尺寸，让内核再缩一次会糊。
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = false
            loadWithOverviewMode = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            // 视频要自动播放：muted + 允许无手势播放（WebView 侧由 muted 兜住）。
            mediaPlaybackRequiresUserGesture = false
            domStorageEnabled = false
        }

        w.addJavascriptInterface(Bridge(), "BubbleNative")
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true
                // 补播：页面加载期间来的 play() 会丢，这里补上。
                pending?.let { (a, src, loop) ->
                    pending = null
                    playInternal(a, src, loop)
                }
            }
        }

        container.addView(
            w,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        web = w
        w.loadUrl("file://" + html.absolutePath)
        Log.i(TAG, "悬浮球视频层已创建: ${html.absolutePath}")
    }

    /**
     * 切到某个动作并开始播放。
     *
     * @param action 动作名（用于回调与去重）。
     * @param url 素材的 `file://` URL。
     * @param loop 是否无限循环（待机类动作 true）。
     */
    fun play(action: String, url: String, loop: Boolean) {
        val w = web ?: return
        if (!pageReady) {
            pending = Triple(action, url, loop)
            return
        }
        if (action == current) return
        playInternal(action, url, loop)
    }

    /** 停掉播放但保留 WebView（球被移除 / App 回前台时省电）。 */
    fun pause() {
        if (web == null) return
        web?.evaluateJavascript("window.__pet && window.__pet.pause && window.__pet.pause()", null)
    }

    /** 恢复播放。 */
    fun resume() {
        if (web == null) return
        web?.evaluateJavascript("window.__pet && window.__pet.resume && window.__pet.resume()", null)
    }

    /** 彻底释放（球被隐藏时）。 */
    fun release() {
        val w = web ?: return
        web = null
        pageReady = false
        pending = null
        current = ""
        runCatching {
            (w.parent as? android.view.ViewGroup)?.removeView(w)
            w.destroy()
        }
    }

    // ── 内部 ──────────────────────────────────────────────────────────

    private fun playInternal(action: String, url: String, loop: Boolean) {
        current = action
        val js = "window.__pet && window.__pet.play(" +
            "${quote(action)}, ${quote(url)}, ${if (loop) "true" else "false"})"
        web?.evaluateJavascript(js, null)
    }

    /** 拼一个 JS 字符串字面量。用 JSON 转义，避免路径里的反斜杠/引号破坏脚本。 */
    private fun quote(s: String): String = org.json.JSONObject.quote(s)

    /**
     * 把播放器页面写到磁盘（内容不变就跳过）。
     *
     * ⚠️ **写成文件而不是 `loadDataWithBaseURL`**：后者的 origin 是 opaque，
     *   在 `file://` 下加载视频会被判跨源而失败，且各内核行为不一致。落地成文件
     *   后页面与素材同属 `file://`，同源判定稳定。
     */
    private fun ensurePlayerFile(): File? {
        return try {
            val dir = File(context.filesDir, PLAYER_DIR)
            if (!dir.isDirectory && !dir.mkdirs()) return null
            val f = File(dir, PLAYER_FILE)
            if (!f.isFile || f.readText() != PLAYER_HTML) f.writeText(PLAYER_HTML)
            f
        } catch (t: Throwable) {
            Log.w(TAG, "写入视频播放器页面失败: ${t.message}")
            null
        }
    }

    /**
     * 页面 → 原生的回调。
     *
     * ⚠️ `@JavascriptInterface` 的方法**必须 public 且返回基本类型或 String**，
     *   返回自定义对象会被静默丢弃。
     */
    inner class Bridge {
        /** 一次性动作播完。 */
        @JavascriptInterface
        fun onEnded(action: String) {
            // ⚠️ **必须写 `this@BubbleVideoLayer`**：`Bridge` 自己也有个 `onEnded`
            //   成员函数，直接写 `onEnded(action)` 会解析成**递归调用自己** ——
            //   构造参数被同名成员遮蔽了。症状极具迷惑性：桥那侧日志一切正常
            //   （每帧都打印"onEnded action=walk"），而原生永远收不到事件，
            //   表现为「一次性动作播完不换下一个、转向动作播完不翻面」。
            this@BubbleVideoLayer.onEnded(action)
        }

        /** JS 侧出错（解码失败、文件缺失…）。 */
        @JavascriptInterface
        fun onError(action: String, message: String) {
            if (warned) return
            warned = true
            Log.w(TAG, "视频播放失败（动作 $action）: $message")
        }
    }

    /**
     * 播放器页面。
     *
     * 每帧 `clearRect` + `drawImage`：**先清空再绘制**，否则上一帧的像素会留在
     * canvas 上，表现为"动作拖影"。alpha 由 canvas 自身保证，不需要额外处理。
     *
     * ⚠️ 页面里**不能出现反引号**（Kotlin 原始字符串会被提前终止），也不能出现
     *   `$`（会被当模板插值）。本页已按此约束写，改动时留意。
     */
    private val PLAYER_HTML: String
        get() = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
<style>
  html,body{margin:0;padding:0;background:transparent;overflow:hidden;width:100%;height:100%}
  #c{display:block;width:100%;height:100%}
</style>
</head>
<body>
<canvas id="c"></canvas>
<script>
(function(){
  var cv = document.getElementById('c');
  var ctx = cv.getContext('2d');
  var video = document.createElement('video');
  video.muted = true;
  video.playsInline = true;
  video.preload = 'auto';
  video.setAttribute('webkit-playsinline', '');
  var curAction = '';
  var raf = 0;

  function syncSize(){
    var r = cv.getBoundingClientRect();
    var dpr = window.devicePixelRatio || 1;
    var w = Math.max(1, Math.round(r.width * dpr));
    var h = Math.max(1, Math.round(r.height * dpr));
    if (cv.width !== w || cv.height !== h) { cv.width = w; cv.height = h; }
  }

  function draw(){
    syncSize();
    var W = cv.width, H = cv.height;
    ctx.clearRect(0, 0, W, H);
    if (video.readyState >= 2 && video.videoWidth > 0) {
      var vw = video.videoWidth, vh = video.videoHeight;
      var s = Math.min(W / vw, H / vh);
      var dw = vw * s, dh = vh * s;
      ctx.drawImage(video, (W - dw) / 2, (H - dh) / 2, dw, dh);
    }
    raf = requestAnimationFrame(draw);
  }

  video.addEventListener('ended', function(){
    if (video.loop) return;
    try { BubbleNative.onEnded(curAction); } catch (e) {}
  });
  video.addEventListener('error', function(){
    var m = video.error ? ('code ' + video.error.code) : 'unknown';
    try { BubbleNative.onError(curAction, m); } catch (e) {}
  });

  window.__pet = {
    play: function(action, src, loop){
      curAction = action;
      video.loop = !!loop;
      video.src = src;
      video.load();
      var p = video.play();
      if (p && p.catch) { p.catch(function(e){
        try { BubbleNative.onError(action, String(e)); } catch (x) {}
      }); }
      return 'ok';
    },
    pause: function(){ try { video.pause(); } catch (e) {} return 'ok'; },
    resume: function(){ var p = video.play(); if (p && p.catch) { p.catch(function(){}); } return 'ok'; },
    current: function(){ return curAction; },
    ready: function(){ return video.readyState; }
  };

  raf = requestAnimationFrame(draw);
})();
</script>
</body>
</html>
""".trimIndent()
}
