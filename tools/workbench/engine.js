/**
 * 素材处理引擎 —— 把用户导入的图变成可用的透明 webm。
 *
 * 全部在浏览器本地完成，不上传任何数据。
 *
 * ## 为什么能输出透明 webm
 *
 * Chrome 的 `MediaRecorder` 在录制 canvas 时会保留 alpha：
 * canvas 的透明像素 → 编码后 `TAG:alpha_mode=1`。
 * （Firefox / Safari 不支持，所以工作台会检测并提示用 Chrome。）
 */
const PetEngine = (() => {

  // ── 状态 ────────────────────────────────────────────────────────────
  let frames = [];          // [{ bitmap: ImageBitmap, w, h, delay }]
  let sourceName = '';
  let bbox = null;          // 所有帧的并集包围盒 { x0, y0, x1, y1 }
  let warnings = [];

  // ── 工具 ────────────────────────────────────────────────────────────

  /** 读文件为 ImageBitmap。失败返回 null。 */
  async function loadBitmap(file) {
    try {
      return await createImageBitmap(file);
    } catch {
      return null;
    }
  }

  /** 解码 GIF 的所有帧（用 ImageDecoder，Chrome 支持）。 */
  async function decodeGif(file) {
    if (typeof ImageDecoder === 'undefined') return null;
    try {
      const data = await file.arrayBuffer();
      const dec = new ImageDecoder({ data, type: file.type || 'image/gif' });
      await dec.tracks.ready;
      const track = dec.tracks.selectedTrack;
      const count = track.frameCount;
      const out = [];
      for (let i = 0; i < count; i++) {
        const { image } = await dec.decode({ frameIndex: i });
        const delay = (image.duration || 40000) / 1000;   // μs → ms
        out.push({ bitmap: image, w: image.displayWidth, h: image.displayHeight, delay });
      }
      return out;
    } catch {
      return null;
    }
  }

  /** 按文件名里的数字排序（frame_2 要排在 frame_10 前面）。 */
  function naturalSort(a, b) {
    const num = (s) => {
      const m = s.match(/(\d+)(?!.*\d)/);
      return m ? parseInt(m[1], 10) : 0;
    };
    return num(a.name) - num(b.name) || a.name.localeCompare(b.name);
  }

  // ── 导入 ────────────────────────────────────────────────────────────

  /**
   * 导入文件列表。
   *
   * @returns {{ok: boolean, message: string, count: number}}
   */
  async function importFiles(fileList) {
    const files = Array.from(fileList).filter(f =>
      /\.(png|jpe?g|webp|gif|bmp)$/i.test(f.name) || /^image\//.test(f.type)
    );
    if (files.length === 0) {
      return { ok: false, message: '没找到图片文件（支持 PNG / JPG / WebP / GIF）', count: 0 };
    }

    frames = [];
    warnings = [];
    bbox = null;

    // 单个 GIF → 展开成多帧
    if (files.length === 1 && /\.gif$/i.test(files[0].name)) {
      const gifFrames = await decodeGif(files[0]);
      if (gifFrames && gifFrames.length > 0) {
        frames = gifFrames;
        sourceName = files[0].name;
        return {
          ok: true,
          message: `已从 GIF 解出 ${frames.length} 帧`,
          count: frames.length,
        };
      }
      // ImageDecoder 不可用 → 退回单帧（只有第一帧）
      warnings.push('这个浏览器解不了 GIF 动画，只取到了第一帧。建议用 Chrome。');
    }

    // 多张图 → 按文件名排序
    files.sort(naturalSort);
    for (const f of files) {
      const bmp = await loadBitmap(f);
      if (bmp) {
        frames.push({ bitmap: bmp, w: bmp.width, h: bmp.height, delay: null });
      }
    }

    if (frames.length === 0) {
      return { ok: false, message: '图片解码失败', count: 0 };
    }
    sourceName = files.length === 1 ? files[0].name : `${files.length} 张图`;
    return {
      ok: true,
      message: `已导入 ${frames.length} 帧`,
      count: frames.length,
    };
  }

  // ── 自动裁剪 ────────────────────────────────────────────────────────

  /**
   * 计算所有帧的**并集**包围盒。
   *
   * ⚠️ 必须用并集而不是每帧各自裁：各帧尺寸不一致的话，
   *    切换动作时角色会跳。这与 dsh-app-pet 素材优化的做法一致。
   *
   * @param {number} alphaThreshold 低于此 alpha 的像素算作透明（默认 8）
   */
  function computeBBox(alphaThreshold = 8) {
    if (frames.length === 0) return null;

    const W = frames[0].w, H = frames[0].h;
    const cv = new OffscreenCanvas(W, H);
    const ctx = cv.getContext('2d', { willReadFrequently: true });

    let x0 = W, y0 = H, x1 = -1, y1 = -1;

    for (const fr of frames) {
      ctx.clearRect(0, 0, W, H);
      ctx.drawImage(fr.bitmap, 0, 0, W, H);
      const data = ctx.getImageData(0, 0, W, H).data;

      // 逐行扫，遇到不透明像素就更新边界（比逐像素快一些）
      for (let y = 0; y < H; y++) {
        const row = y * W * 4;
        for (let x = 0; x < W; x++) {
          if (data[row + x * 4 + 3] > alphaThreshold) {
            if (x < x0) x0 = x;
            if (x > x1) x1 = x;
            if (y < y0) y0 = y;
            if (y > y1) y1 = y;
          }
        }
      }
    }

    if (x1 < 0) {
      warnings.push('所有帧都是全透明的 —— 检查一下图片是不是没抠干净？');
      bbox = { x0: 0, y0: 0, x1: W - 1, y1: H - 1, W, H };
      return bbox;
    }

    bbox = { x0, y0, x1, y1, W, H };
    return bbox;
  }

  /** 当前帧数 / 尺寸 / 包围盒摘要。 */
  function info() {
    return {
      frameCount: frames.length,
      width: frames[0]?.w ?? 0,
      height: frames[0]?.h ?? 0,
      sourceName,
      bbox,
      warnings: [...warnings],
    };
  }

  // ── 渲染与导出 ──────────────────────────────────────────────────────

  /**
   * 把一帧画到目标 canvas 上（应用裁剪 + 缩放）。
   *
   * @param {CanvasRenderingContext2D} ctx
   * @param {number} i 帧序号
   * @param {{crop: boolean, outW: number, outH: number}} opt
   */
  function drawFrame(ctx, i, opt) {
    const fr = frames[i];
    if (!fr) return;

    const { crop, outW, outH } = opt;
    const box = crop && bbox
      ? { x: bbox.x0, y: bbox.y0, w: bbox.x1 - bbox.x0 + 1, h: bbox.y1 - bbox.y0 + 1 }
      : { x: 0, y: 0, w: fr.w, h: fr.h };

    ctx.clearRect(0, 0, outW, outH);
    ctx.drawImage(fr.bitmap, box.x, box.y, box.w, box.h, 0, 0, outW, outH);
  }

  /**
   * 把全部帧录成透明 webm。
   *
   * ## 原理
   *
   * canvas → `captureStream(fps)` → `MediaRecorder('video/webm;codecs=vp9')`。
   * Chrome 会保留 canvas 的 alpha 通道，编码后 webm 带 `alpha_mode=1`。
   *
   * ⚠️ **必须实时录制**：MediaRecorder 按 wall clock 采样，所以 24 帧 @24fps
   *    就是 1 秒真实时间。不能"瞬间"生成。这里用 `requestAnimationFrame`
   *    配合 setTimeout 精确控制每帧的驻留时间。
   *
   * @param {object} opt
   * @param {boolean} opt.crop    是否裁掉透明空白
   * @param {number}  opt.fps     帧率
   * @param {number}  opt.outW    输出宽
   * @param {number}  opt.outH    输出高
   * @param {number}  opt.quality 0.1–1，映射到码率
   * @param {function} opt.onProgress (0–1) => void
   * @returns {Promise<{blob: Blob, size: number, durationMs: number}>}
   */
  async function exportWebm(opt) {
    const { crop, fps, outW, outH, quality, onProgress } = opt;

    if (frames.length === 0) throw new Error('没有素材');

    if (typeof MediaRecorder === 'undefined') {
      throw new Error('这个浏览器不支持 MediaRecorder，请用 Chrome / Edge');
    }
    const mime = 'video/webm;codecs=vp9';
    if (!MediaRecorder.isTypeSupported(mime)) {
      throw new Error('这个浏览器不支持 VP9 编码，请用 Chrome / Edge');
    }

    const cv = document.createElement('canvas');
    cv.width = outW;
    cv.height = outH;
    // ⚠️ alpha:true 是默认值，但显式写出来更清楚 —— 少了它可能被合成成黑底
    const ctx = cv.getContext('2d', { alpha: true });

    // 每帧时长：优先用 GIF 自带 delay，否则用 1/fps
    const frameMs = frames.map(fr => (fr.delay && fr.delay > 10 ? fr.delay : 1000 / fps));
    const totalMs = frameMs.reduce((a, b) => a + b, 0);

    const stream = cv.captureStream(fps);
    // 码率：质量 0.1–1 → 0.3–4 Mbps
    const bitrate = Math.round((0.3 + quality * 3.7) * 1_000_000);
    const rec = new MediaRecorder(stream, { mimeType: mime, videoBitsPerSecond: bitrate });

    const chunks = [];
    rec.ondataavailable = e => { if (e.data && e.data.size > 0) chunks.push(e.data); };

    const stopped = new Promise(res => { rec.onstop = res; });

    // 先画第一帧再开始录，避免开头录到空白
    drawFrame(ctx, 0, { crop, outW, outH });
    rec.start();

    const t0 = performance.now();
    let elapsed = 0;

    for (let i = 0; i < frames.length; i++) {
      drawFrame(ctx, i, { crop, outW, outH });
      if (onProgress) onProgress(Math.min(1, elapsed / totalMs));

      const wait = frameMs[i];
      await new Promise(r => setTimeout(r, wait));
      elapsed += wait;
    }

    // 最后多停一帧的时间，确保末帧被编码进去
    await new Promise(r => setTimeout(r, 1000 / fps));
    rec.stop();
    await stopped;
    if (onProgress) onProgress(1);

    const blob = new Blob(chunks, { type: 'video/webm' });
    return {
      blob,
      size: blob.size,
      durationMs: performance.now() - t0,
    };
  }

  // ── 导出 ────────────────────────────────────────────────────────────

  return {
    importFiles,
    computeBBox,
    info,
    drawFrame,
    exportWebm,
    get frames() { return frames; },
    get bbox() { return bbox; },
  };
})();
