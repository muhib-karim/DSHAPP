/*
 * DSH UI 探针 —— 跑在**页面世界**里的一次性装填器，让原生/模型能精确地看和点界面。
 *
 * ## 为什么需要它
 *
 * 这个 App 的界面不是自绘的：它是官方 DSH Web GUI 跑在 WebView 里，按钮全是
 * React 渲染出来的 DOM。模型（跑在 Node 进程里）既看不到 DOM，也没有任何
 * 途径「点一下那个按钮」—— 于是「AI 自己测自己的 App」这件事在能力上是空的。
 *
 * 本文件补的就是这一段：把「枚举可交互元素 + 精确点击 + 输入 + 滚动 + 读文本」
 * 这套能力装进页面世界，挂成 `window.__DSH_UI_PROBE__`，由原生经
 * `WebHost.evaluateJavascript` 调用。
 *
 * ## 为什么是「每次调用都自带装填逻辑」而不是注入到 index.html
 *
 * ① **不碰用户的页面文件**。注入 `dist/index.html` 意味着改引擎的解包产物，
 *    而那份产物是按版本戳决定要不要重解包的 —— 加一段自己的脚本进去会让
 *    「引擎升级」与「探针升级」两个生命周期纠缠在一起。
 * ② **SPA 内部导航不重建 window**，所以探针装一次就一直在；只有整页导航才丢。
 *    每次调用都检查一遍版本（`__v`），丢了就自动补装，代价是稳态下多几十字节。
 * ③ 本文件是**函数表达式源码**，原生侧拼成 `(<本文件>)(<cmd JSON>)` 执行。
 *    这样 `cmd` 经 `JSON.stringify` 进来，没有任何字符串注入风险。
 *
 * ## ⚠️ 三条必须遵守的约束
 *
 * ① **不能用 ES6+ 的新语法**（可选链 `?.`、`??`、class 字段等）。本 App 要兼容
 *    降级到 chrome74 的老 WebView（见 AGENTS.md ⑯），而本文件**不经过**任何
 *    转译 —— 它是运行时直接 eval 的源码。用 `var` + `function` 最稳。
 * ② **必须返回一个可 JSON 序列化的值**。gecko 侧是
 *    `JSON.stringify(pageWindow.eval(script))`，system 侧是 WebView 自己序列化；
 *    返回 DOM 节点、函数、循环引用都会让整次调用废掉。
 * ③ **绝不能改变页面的可见外观**。不插样式表、不设元素属性、不写 document。
 *    所有改动都只在 `window.__DSH_UI_PROBE__` 这个我们自己造的对象上。
 *
 * ## 坐标系（这是「精确」二字的落点）
 *
 * `getBoundingClientRect()` 给的是 **CSS 像素的视口坐标**。要落到屏幕物理像素
 * 上还需两步换算，全部由原生侧完成（页面里拿不到 WebView 在屏幕上的位置）：
 *
 * ```
 * scale    = webView物理宽度 / 页面视口CSS宽度
 * 屏幕物理X = webView屏幕X + cssX * scale
 * ```
 *
 * 所以探针只负责返回 **CSS 坐标 + 视口尺寸**，换算交给原生 —— 那里才有
 * `getLocationOnScreen()`。这样探针不需要知道自己在什么容器里，两个 flavor 通吃。
 *
 * @param {object} CMD 原生命令，形如 `{action: 'dump', ...}`。由原生 JSON 编码。
 * @returns {object} 结果，必须可 JSON 序列化。
 */
function (CMD) {
  'use strict';

  /** 探针版本。**改了本文件的语义就要 +1** —— 页面上的旧探针靠它判断要不要换。 */
  var VERSION = 2;

  /** 结果里单个元素的文本/类名截断长度（防超大 JSON 撑爆 IPC）。 */
  var MAX_TEXT = 120;
  var MAX_CLASS = 80;
  var MAX_ELEMENTS = 300;

  /** `dump` 的默认条数上限。 */
  var DEFAULT_LIMIT = 60;

  // ────────────────────────────────────────────────────────────────────
  // 工具函数
  // ────────────────────────────────────────────────────────────────────

  /** 切成真数组（NodeList 没有 map/filter）。 */
  function toArray(list) {
    var out = [];
    for (var i = 0; i < list.length; i++) out.push(list[i]);
    return out;
  }

  function trunc(s, n) {
    s = String(s == null ? '' : s).replace(/\s+/g, ' ').trim();
    return s.length > n ? s.slice(0, n) + '…' : s;
  }

  /** `CSS.escape` 的兜底（老 WebView 上可能没有）。 */
  function esc(s) {
    if (window.CSS && typeof CSS.escape === 'function') return CSS.escape(s);
    return String(s).replace(/[^a-zA-Z0-9_-]/g, function (c) {
      return '\\' + c.charCodeAt(0).toString(16) + ' ';
    });
  }

  /**
   * 元素**自身**是否可见（有尺寸、没被隐藏、能接指针事件）。
   *
   * ⚠️ 只看 `getBoundingClientRect()` 的尺寸是不够的：React 里大量用了
   *   `visibility:hidden` / `opacity:0` 做过渡，这类元素有尺寸但看不见，
   *   而**看不见的元素点了不会有任何反应** —— 那正是「AI 说点了但没生效」的
   *   头号原因。所以尺寸、visibility、display、opacity 四样都查。
   *
   * ⚠️ **本函数只查元素自身，不查「有没有别的东西盖在它上面」** —— 后者由
   *   `blocker()` 负责。两者必须分开：一个元素完全可能「自己看着很正常，
   *   但被全屏弹窗盖住」，那种情况下它 `isVisible` 为 true 而真机上点不动。
   */
  function isVisible(el) {
    if (!el || !el.getBoundingClientRect) return false;
    var r = el.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0) return false;
    var st = window.getComputedStyle(el);
    if (!st) return false;
    if (st.display === 'none' || st.visibility === 'hidden' || st.visibility === 'collapse') return false;
    if (parseFloat(st.opacity) === 0) return false;
    if (st.pointerEvents === 'none') return false;
    return true;
  }

  /**
   * 取某个视口坐标上的**最上层**元素。
   *
   * ⚠️ `elementFromPoint` 对**视口外**的坐标返回 `null`，而那不是「被遮挡」，
   *   是「没显示在屏幕上」—— 两者必须分开，否则列表里滚出屏幕的元素会被
   *   全部误报成遮挡，把真正的问题淹掉。
   *
   * ⚠️ `pointer-events:none` 的元素会被内核自动跳过，这正是我们要的：
   *   它们不接事件，也就挡不住点击。
   */
  function topAt(x, y) {
    try {
      if (x < 0 || y < 0) return null;
      if (x > (window.innerWidth || 0) || y > (window.innerHeight || 0)) return null;
      return document.elementFromPoint(x, y);
    } catch (e) {
      return null;
    }
  }

  /** 两个元素是否有祖先/后代关系（这种「压住」是正常的，不算遮挡）。 */
  function related(a, b) {
    if (!a || !b) return false;
    if (a === b) return true;
    return !!((a.contains && a.contains(b)) || (b.contains && b.contains(a)));
  }

  /**
   * 元素是否**被别的东西盖住**（真机手指点不到它）。返回遮挡者，没被挡则 null。
   *
   * ⚠️ 这是本探针最重要的一条判据，也是第一版最大的缺口：只查元素自身样式
   *   会把「被全屏弹窗盖住的按钮」报成可用 —— 它有尺寸、`opacity` 是 1、
   *   `visibility` 是 visible，**看上去完全正常**，但真机上点它只会点到底下
   *   那层遮罩。模型于是挨个点、界面纹丝不动、还拿不到任何提示，
   *   只能猜「是不是选择器写错了」。
   *
   *   实测的典型案例：官方前端首启会弹「预览版说明」对话框（`z-index:1000`
   *   加一层覆盖全屏的 `_mask_`），此时探针会枚举出 16 个「可见」按钮，
   *   而它们**一个都点不动**；那个弹窗的按钮文案是「继续」，不认识它就关不掉。
   *
   * 判据与 `clickEl` 里的 `onTarget` 必须同源 —— 否则「dump 说能用」与
   * 「tap 说没点到」会互相矛盾，模型无从判断该信哪个。
   */
  function blocker(el) {
    if (!el || !el.getBoundingClientRect) return null;
    var r = el.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0) return null;
    var top = topAt(r.left + r.width / 2, r.top + r.height / 2);
    if (!top || related(top, el)) return null;
    return top;
  }

  /** 元素是否在视口内（`dump` 里标记用，不参与过滤）。 */
  function inViewport(r) {
    return r.bottom > 0 && r.right > 0 &&
      r.top < (window.innerHeight || 0) && r.left < (window.innerWidth || 0);
  }

  function rectOf(el) {
    var r = el.getBoundingClientRect();
    return {
      x: Math.round(r.left * 100) / 100,
      y: Math.round(r.top * 100) / 100,
      w: Math.round(r.width * 100) / 100,
      h: Math.round(r.height * 100) / 100
    };
  }

  /** 视口信息 —— 原生据此把 CSS 坐标换算成屏幕物理坐标。 */
  function viewportInfo() {
    var de = document.documentElement;
    return {
      w: window.innerWidth || (de && de.clientWidth) || 0,
      h: window.innerHeight || (de && de.clientHeight) || 0,
      dpr: window.devicePixelRatio || 1,
      scrollX: Math.round(window.pageXOffset || 0),
      scrollY: Math.round(window.pageYOffset || 0),
      docW: de ? de.scrollWidth : 0,
      docH: de ? de.scrollHeight : 0
    };
  }

  /**
   * 给元素生成一个**稳定的 CSS 选择器**（可回传给 `tap` 用）。
   *
   * 优先 `#id`（最稳），退化成 `tag:nth-of-type(...)` 逐级路径。
   */
  function cssPath(el) {
    if (!el || el.nodeType !== 1) return '';
    // 有 id 且全局唯一 —— 最优解
    if (el.id) {
      try {
        if (document.querySelectorAll('#' + esc(el.id)).length === 1) return '#' + esc(el.id);
      } catch (e) { /* 非法 id，忽略 */ }
    }
    var parts = [];
    var cur = el;
    var depth = 0;
    while (cur && cur.nodeType === 1 && cur !== document.documentElement && depth < 12) {
      var tag = cur.tagName.toLowerCase();
      var parent = cur.parentElement;
      if (parent) {
        var same = [];
        for (var i = 0; i < parent.children.length; i++) {
          if (parent.children[i].tagName === cur.tagName) same.push(parent.children[i]);
        }
        if (same.length > 1) tag += ':nth-of-type(' + (same.indexOf(cur) + 1) + ')';
      }
      if (cur.id) {
        parts.unshift('#' + esc(cur.id));
        break;
      }
      parts.unshift(tag);
      cur = parent;
      depth++;
    }
    return parts.join(' > ');
  }

  /**
   * 元素的**可读标签** —— 模型靠它认出「这是那个按钮」。
   *
   * 优先级：`aria-label` > `title` > 输入控件的 placeholder/value > 文本内容。
   * 这个顺序是有理由的：图标按钮（DSH 界面上有很多）没有文本，只有 aria-label；
   * 反过来，一个容器按钮的 innerText 可能包含整段子内容，用它会认错。
   */
  function labelOf(el) {
    var a = el.getAttribute && (el.getAttribute('aria-label') || el.getAttribute('title'));
    if (a && String(a).trim()) return trunc(a, MAX_TEXT);
    var tag = el.tagName;
    if (tag === 'INPUT') {
      var t = el.type;
      if (t === 'checkbox' || t === 'radio' || t === 'range') return '';
      return trunc(el.getAttribute('placeholder') || el.value || '', MAX_TEXT);
    }
    if (tag === 'TEXTAREA') return trunc(el.getAttribute('placeholder') || el.value || '', MAX_TEXT);
    if (el.isContentEditable) return trunc(el.getAttribute('placeholder') || el.innerText || '', MAX_TEXT);
    return trunc(el.innerText || el.textContent || '', MAX_TEXT);
  }

  /** 元素的 `role`（显式写的优先，否则从标签推断）。 */
  function roleOf(el) {
    var r = el.getAttribute && el.getAttribute('role');
    if (r) return r;
    var tag = el.tagName;
    if (tag === 'BUTTON') return 'button';
    if (tag === 'A') return 'link';
    if (tag === 'INPUT') {
      var t = (el.type || 'text').toLowerCase();
      if (t === 'checkbox') return 'checkbox';
      if (t === 'radio') return 'radio';
      if (t === 'submit' || t === 'button') return 'button';
      return 'textbox';
    }
    if (tag === 'TEXTAREA') return 'textbox';
    if (tag === 'SELECT') return 'combobox';
    return '';
  }

  function testIdOf(el) {
    if (!el.getAttribute) return '';
    return el.getAttribute('data-testid') || el.getAttribute('data-test-id') ||
      el.getAttribute('data-test') || '';
  }

  /**
   * 元素的可点击性权重 —— `dump` 排序用。
   *
   * 权重高的排前面，模型就不用在一堆装饰性 div 里翻找真正的按钮。
   */
  function score(el) {
    var tag = el.tagName;
    var s = 0;
    if (tag === 'BUTTON') s += 100;
    if (tag === 'A' && el.getAttribute('href')) s += 90;
    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') s += 85;
    if (el.isContentEditable) s += 85;
    var role = el.getAttribute && el.getAttribute('role');
    if (role === 'button' || role === 'menuitem' || role === 'tab' || role === 'option') s += 70;
    if (testIdOf(el)) s += 40;
    if (el.getAttribute && el.getAttribute('aria-label')) s += 25;
    if (labelOf(el)) s += 12;
    return s;
  }

  /**
   * 「可交互」的候选选择器。
   *
   * ⚠️ **不能只查 `button`/`a`**：DSH 的界面是 React，大量按钮是带
   *   `role="button"` 或 `tabindex` 的 div。漏掉它们等于漏掉半个界面。
   */
  var INTERACTIVE = [
    'button',
    'a[href]',
    'input:not([type="hidden"])',
    'textarea',
    'select',
    '[role="button"]',
    '[role="link"]',
    '[role="menuitem"]',
    '[role="menuitemcheckbox"]',
    '[role="tab"]',
    '[role="option"]',
    '[role="checkbox"]',
    '[role="radio"]',
    '[role="switch"]',
    '[contenteditable="true"]',
    '[tabindex]:not([tabindex="-1"])',
    'summary',
    'label[for]'
  ].join(',');

  /**
   * 把一个元素描述成可回传的普通对象。
   *
   * ⚠️ 这里**只放原始类型** —— 返回 DOM 节点会让 JSON 序列化整个废掉。
   *
   * ⚠️ `covered` / `blockedBy` 是**必须**带上的：没有它们，调用方拿到的是一份
   *   「看着都能点」的清单，而真机上可能一个都点不动（见 `blocker()`）。
   */
  function describe(el, index) {
    var r = el.getBoundingClientRect();
    var b = blocker(el);
    return {
      ref: 'e' + index,
      sel: cssPath(el),
      tag: el.tagName.toLowerCase(),
      role: roleOf(el),
      text: labelOf(el),
      testid: testIdOf(el),
      cls: trunc(el.getAttribute && el.getAttribute('class') || '', MAX_CLASS),
      rect: rectOf(el),
      center: {
        x: Math.round((r.left + r.width / 2) * 100) / 100,
        y: Math.round((r.top + r.height / 2) * 100) / 100
      },
      inViewport: inViewport(r),
      // 「盖住了」与「盖它的是谁」成对出现：只报 true 而不报是谁，模型还得再查一轮。
      covered: !!b,
      blockedBy: b ? {
        sel: cssPath(b),
        tag: b.tagName.toLowerCase(),
        cls: trunc(b.getAttribute && b.getAttribute('class') || '', MAX_CLASS),
        text: labelOf(b)
      } : null,
      enabled: !(el.disabled === true || (el.getAttribute && el.getAttribute('aria-disabled') === 'true')),
      checked: typeof el.checked === 'boolean' ? el.checked : null,
      value: typeof el.value === 'string' ? trunc(el.value, MAX_TEXT) : null
    };
  }

  /** `dump` 用的候选收集（去重 + 可见性过滤 + 遮挡标注 + 排序 + 截断）。 */
  function collect(opts) {
    opts = opts || {};
    var limit = typeof opts.limit === 'number' && opts.limit > 0 ? Math.min(opts.limit, MAX_ELEMENTS) : DEFAULT_LIMIT;
    var needle = (opts.filter == null ? '' : String(opts.filter)).toLowerCase();
    var includeHidden = opts.includeHidden === true;
    // 「只要现在真能点的」—— 模型在弹窗挡着时最需要的开关
    var clickableOnly = opts.clickableOnly === true;

    var found = toArray(document.querySelectorAll(INTERACTIVE));
    var seen = [];
    var out = [];
    var blocked = 0;

    for (var i = 0; i < found.length; i++) {
      var el = found[i];
      if (seen.indexOf(el) >= 0) continue;
      seen.push(el);
      if (!includeHidden && !isVisible(el)) continue;

      var d = describe(el, out.length);
      if (d.covered) blocked++;
      if (clickableOnly && d.covered) continue;
      if (needle) {
        var hay = (d.text + ' ' + d.testid + ' ' + d.cls + ' ' + d.sel + ' ' + d.role).toLowerCase();
        if (hay.indexOf(needle) < 0) continue;
      }
      d._score = score(el) + (d.covered ? 0 : 1000); // 真能点的排前面
      out.push(d);
      if (out.length >= MAX_ELEMENTS) break;
    }

    // 可点击性高的排前面（截图/枚举时更符合直觉）
    out.sort(function (a, b) { return b._score - a._score; });
    var trimmed = out.slice(0, limit);
    for (var j = 0; j < trimmed.length; j++) delete trimmed[j]._score;
    return { matched: out.length, elements: trimmed, blocked: blocked, scanned: seen.length };
  }

  /**
   * 从 `el` 向上找「覆盖视口大部分区域」的那层壳。
   *
   * ⚠️ 遮挡层几乎总是「一个覆盖全屏的容器 + 里面的实际内容」。采样点命中的
   *   往往是**内容**（弹窗里的一段文字、一个按钮），它自己的 rect 很小 ——
   *   直接拿命中元素判面积会得出「没有遮挡」，而实际上全屏都被盖住了。
   *   往上找到那层壳，才能正确回答「现在谁盖着屏幕」。
   */
  function fullScreenAncestor(el) {
    var vw = window.innerWidth || 0;
    var vh = window.innerHeight || 0;
    if (vw <= 0 || vh <= 0) return null;
    var cur = el;
    while (cur && cur !== document.body && cur !== document.documentElement) {
      var r = cur.getBoundingClientRect();
      if (r.width * r.height >= vw * vh * 0.5) {
        var st = window.getComputedStyle(cur);
        if (st && (st.position === 'fixed' || st.position === 'absolute')) return cur;
      }
      cur = cur.parentElement;
    }
    return null;
  }

  /**
   * 当前有没有**全屏级别的遮挡层**？有的话它是谁？
   *
   * ⚠️ 这是「现在这个界面能不能操作」的总开关。有全屏弹窗时，底下所有按钮都
   *   点不动，而它们各自的 `covered` 会指向同一个遮罩 —— 把遮罩单独报出来，
   *   模型一眼就知道「先关掉它」，而不是对着十几条 `covered:true` 发愣。
   *
   * ⚠️ **必须多点采样**。第一版只取视口中心一个点，结果命中弹窗里的文字，
   *   `fullScreenAncestor` 还没加时更是直接判成「没有遮挡」—— 于是出现了
   *   `overlay` 说「没有遮挡层」而同一时刻 `dump` 说「15 个元素被遮挡」的
   *   自相矛盾。两个字段对同一件事给出相反答案，比缺字段更糟。
   */
  function overlay() {
    var vw = window.innerWidth || 0;
    var vh = window.innerHeight || 0;
    if (vw <= 0 || vh <= 0) return null;

    // 中心 + 四角内缩 + 四边中点，共 9 点：既能覆盖全屏层，又能避开单点落在
    // 弹窗内容（或页面上某个恰好很大的普通元素）上的偶然。
    var pts = [
      [0.5, 0.5], [0.15, 0.15], [0.85, 0.15], [0.15, 0.85], [0.85, 0.85],
      [0.5, 0.06], [0.5, 0.94], [0.06, 0.5], [0.94, 0.5]
    ];

    var tally = [];
    for (var i = 0; i < pts.length; i++) {
      var hit = topAt(Math.round(vw * pts[i][0]), Math.round(vh * pts[i][1]));
      if (!hit || hit === document.body || hit === document.documentElement) continue;
      var layer = fullScreenAncestor(hit);
      if (!layer) continue;
      var at = -1;
      for (var j = 0; j < tally.length; j++) if (tally[j].el === layer) { at = j; break; }
      if (at >= 0) tally[at].n++;
      else tally.push({ el: layer, n: 1 });
    }
    if (tally.length === 0) return null;

    tally.sort(function (a, b) { return b.n - a.n; });
    // 只在**多数**采样点都被它挡住时才认定是遮挡层。少数点命中说明那只是
    // 页面上某个恰好很大的普通元素，不是「盖住了整个界面」。
    if (tally[0].n < 5) return null;

    var el = tally[0].el;
    return {
      sel: cssPath(el),
      tag: el.tagName.toLowerCase(),
      cls: trunc(el.getAttribute && el.getAttribute('class') || '', MAX_CLASS),
      text: labelOf(el),
      rect: rectOf(el),
      hits: tally[0].n,
      samples: pts.length
    };
  }

  // ────────────────────────────────────────────────────────────────────
  // 定位：把「请求」翻译成一个 DOM 元素
  // ────────────────────────────────────────────────────────────────────

  /** 按 CSS 选择器找（容错：非法选择器不抛，返回 null）。 */
  function bySel(sel) {
    if (!sel) return null;
    try {
      return document.querySelector(String(sel));
    } catch (e) {
      return null;
    }
  }

  /**
   * 按**文本**找可交互元素。
   *
   * ⚠️ 匹配的是 [labelOf] 的结果，且**优先精确相等**：DSH 界面上「发送」这种
   *   文本可能同时出现在按钮和它的提示里，纯子串匹配会认错。所以分两轮：
   *   先找完全相等的，找不到再退回子串包含。
   */
  function byText(text, exact) {
    if (!text) return null;
    var want = String(text).trim().toLowerCase();
    var found = toArray(document.querySelectorAll(INTERACTIVE));
    var partial = null;
    for (var i = 0; i < found.length; i++) {
      var el = found[i];
      if (!isVisible(el)) continue;
      var label = labelOf(el).toLowerCase();
      if (!label) continue;
      if (label === want) return el;
      if (!exact && partial === null && label.indexOf(want) >= 0) partial = el;
    }
    return exact ? null : partial;
  }

  /**
   * 统一的元素定位。**顺序即优先级**：
   *
   * ① `ref`/`sel` —— 精确（`ref` 是上一次 `dump` 给的，见下面的解析）
   * ② `testid` —— 开发留的锚点，最稳
   * ③ `text` —— 人话，最常用
   * ④ `aria` —— 图标按钮的唯一抓手
   *
   * ⚠️ `ref` 不能直接当选择器用：它是「上一次 dump 里的第 N 个」，两次调用之间
   *   页面可能已经重渲染。所以这里**不用 ref 定位**，只在返回里给出 `sel` 让
   *   调用方回传。硬用 ref 会产生「点到了完全不相干的元素」这种最难查的 bug。
   */
  function locate(spec) {
    spec = spec || {};
    var el = null;
    if (spec.sel) el = bySel(spec.sel);
    if (!el && spec.testid) {
      el = bySel('[data-testid="' + spec.testid + '"]') || bySel('[data-test-id="' + spec.testid + '"]');
    }
    if (!el && spec.aria) {
      el = bySel('[aria-label="' + String(spec.aria).replace(/"/g, '\\"') + '"]');
    }
    if (!el && spec.text) el = byText(spec.text, spec.exact === true);
    return el;
  }

  // ────────────────────────────────────────────────────────────────────
  // 动作
  // ────────────────────────────────────────────────────────────────────

  /**
   * 滚到元素可见。
   *
   * ⚠️ 用 `block:'center'` 而不是默认的 `'start'`：贴顶的元素会被界面自己的
   *   固定顶栏盖住，点击落到顶栏上 —— 症状是「点了没反应」。
   */
  function reveal(el) {
    try {
      el.scrollIntoView({ block: 'center', inline: 'center' });
    } catch (e) {
      try { el.scrollIntoView(); } catch (e2) { /* 忽略 */ }
    }
  }

  /**
   * **像人一样**点一个元素。
   *
   * 为什么不是简单的 `el.click()`：很多 UI（拖拽、长按、需要 pointer 事件的
   * 自绘控件）监听的是 `pointerdown`/`mousedown` 而不是 `click`。而 React 18
   * 用根级事件委托，缺了前置事件时某些组件的内部状态机不会推进 —— 表现为
   * 「click 事件发了，但界面没变」。
   *
   * ⚠️ `elementFromPoint` 这一步不可省：目标可能被一个透明遮罩（弹窗背景、
   *   加载蒙层）盖住，此时真实接收事件的是遮罩。我们能做的是把事件发给**最上层
   *   的那个元素**，并把它报回去 —— 让调用方自己判断「是不是被挡了」。
   */
  function clickEl(el) {
    reveal(el);
    var r = el.getBoundingClientRect();
    var x = r.left + r.width / 2;
    var y = r.top + r.height / 2;

    var top = null;
    try { top = document.elementFromPoint(x, y); } catch (e) { /* 坐标越界 */ }
    var target = el;
    if (top && (top === el || el.contains(top) || (top.contains && top.contains(el)))) target = top;

    var base = {
      bubbles: true,
      cancelable: true,
      composed: true,
      clientX: x,
      clientY: y,
      screenX: x,
      screenY: y,
      button: 0,
      buttons: 1,
      view: window
    };

    try { if (typeof target.focus === 'function') target.focus(); } catch (e) { /* 不可聚焦 */ }

    var fired = [];
    function fire(type, ctor, extra) {
      try {
        var opts = base;
        if (extra) for (var k in extra) if (extra.hasOwnProperty(k)) opts[k] = extra[k];
        var ev;
        if (ctor && window[ctor]) ev = new window[ctor](type, opts);
        else ev = new MouseEvent(type, opts);
        target.dispatchEvent(ev);
        fired.push(type);
      } catch (e) {
        // 单个事件失败不影响其余（老 WebView 没有 PointerEvent）
      }
    }

    fire('pointerdown', 'PointerEvent');
    fire('mousedown', 'MouseEvent');
    fire('pointerup', 'PointerEvent', { buttons: 0 });
    fire('mouseup', 'MouseEvent', { buttons: 0 });
    fire('click', 'MouseEvent', { buttons: 0 });

    // ⚠️ `onTarget` 的判据是「真实手指会不会点到目标」，而**不是**「我们把事件
    //   发给了谁」。这两件事必须分开报：
    //   - 事件**总是发给目标元素**（下面 target 的选取保证了这一点）：我们要执行
    //     的是「点这个元素」这个意图，即使有装饰层浮在上面也不该丢掉意图。
    //   - 但 `elementFromPoint` 的结果要如实报告，否则调用方无从知道「这一次点击
    //     在真机上会被谁吃掉」。
    //
    // ⚠️ 第一版的判据写成了 `target === el`，而 `target` 只在「命中元素与目标
    //   有祖先/后代关系」时才被改掉 —— 于是无关元素（遮罩、蒙层）压在上面时
    //   `target` 仍是 `el`，`onTarget` 恒为 true。**这个字段因此在所有情况下
    //   都报 true，等于没有**；而工具文档明确写着「onTarget:false 说明被遮挡」，
    //   那句话永远触发不了。判据必须直接看命中元素本身。
    var covered = false;
    if (top && top !== el) {
      var related = (el.contains && el.contains(top)) || (top.contains && top.contains(el));
      covered = !related;
    }

    return {
      dispatched: fired,
      hit: top ? describe(top, -1) : null,
      // 命中元素取不到时（坐标越界、元素已脱离文档）不妄断遮挡：报 true，
      // 让调用方按「没发现问题」理解，而不是凭空得到一次假警报。
      onTarget: !covered
    };
  }

  /**
   * 往输入框里写字，**并让 React 认账**。
   *
   * ⚠️ 这是整个探针里最容易被忽略、也最容易失败的一步。React 受控组件内部
   *   追踪 `value` 的「上一次的值」，直接 `el.value = 'x'` 会被它判定成
   *   「值没变」而**不触发 onChange** —— 那正是「AI 说填好了，但发送按钮
   *   仍然是灰的」的原因。正解是用原型上的原生 setter 绕开追踪，再补一个
   *   冒泡的 `input` 事件。
   *
   * 这条做法是 React 官方测试工具（react-dom/test-utils 的
   * `setNativeValue`）用的同一招，不是偏方。
   */
  /**
   * 写进去的内容是否**真的在**元素里了。
   *
   * ⚠️ 判据是「**包含**」而不是「完全相等」：`contenteditable` 的富文本编辑器
   *   （官方 composer 就是 Lexical）会把文本包进 `<p><span data-lexical-text>`，
   *   严格相等会把一次**成功**的写入误报成失败。
   */
  function applied(el, want) {
    var got = readText(el);
    if (want === '') return got === '';
    return got.indexOf(want) >= 0;
  }

  function setValue(el, text) {
    var s = String(text == null ? '' : text);
    if (el.isContentEditable) {
      try { el.focus(); } catch (e) { /* 不可聚焦 */ }

      // ⚠️ **`execCommand('insertText')` 的生效是异步的**，这是本函数最容易搞错的
      //   一点，实测过两轮才量清楚：
      //
      //     t=0ms   调用 execCommand → 返回 true
      //     t=0ms   同步读 textContent / innerText → **都还是空的**
      //     t=700ms 再读 → 内容已落地（`<p><span data-lexical-text>…</span></p>`）
      //
      //   官方 composer 是 Lexical，它把插入排进自己的 update cycle，所以
      //   **同步读必然为空**。第一版拿「同步读 == 期望值」当成功判据，于是
      //   每一次都判定失败、再走 `el.textContent = s` 兜底 —— 而那个兜底会把
      //   Lexical 精心维护的 `<p><span>` 结构拍成裸文本，破坏编辑器内部模型。
      //   表面上「内容进去了」，实际是两条路都在写同一个元素。
      //
      //   所以：**返回 true 就相信它**，不做同步覆盖；只有它返回 false 或抛异常
      //   时才用兜底。落地的复核交给调用方在下一轮 `find`/`text` 里做
      //   （见 `type` 结果里的 `applied` 字段说明）。
      var ok = false;
      try { ok = document.execCommand('insertText', false, s); } catch (e) { ok = false; }
      if (ok) return 'execCommand(异步生效)';

      // 兜底：`execCommand` 不可用（老内核/非聚焦）时才走这条。
      el.textContent = s;
      el.dispatchEvent(new Event('input', { bubbles: true }));
      return applied(el, s) ? 'textContent' : 'textContent(未生效)';
    }

    var proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
    var desc = Object.getOwnPropertyDescriptor(proto, 'value');
    if (desc && desc.set) desc.set.call(el, s);
    else el.value = s;

    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    return 'native-setter';
  }

  /**
   * 读一个可编辑元素的当前文本。
   *
   * ⚠️ `contenteditable` 用 `textContent` 而不是 `innerText`：赋值走的就是
   *   `textContent`，而 `innerText` 是**布局相关**的（元素不可见/未布局时返回
   *   空串）。用 `innerText` 做校验会出现「刚写进去、校验说不相等」的假失败
   *   —— `how` 报 `textContent(未生效)` 而发送键明明已经变可用（踩过）。
   *   校验与被校验的必须是同一个量。
   */
  function readText(el) {
    if (!el) return '';
    if (el.isContentEditable) return String(el.textContent || '');
    return typeof el.value === 'string' ? el.value : '';
  }

  /** 按键。React 的 Enter 提交走 `keydown`，所以三个都发。 */
  function pressKey(el, key, modifiers) {
    modifiers = modifiers || {};
    var base = {
      key: key,
      code: key,
      bubbles: true,
      cancelable: true,
      composed: true,
      ctrlKey: modifiers.ctrl === true,
      shiftKey: modifiers.shift === true,
      altKey: modifiers.alt === true,
      metaKey: modifiers.meta === true
    };
    var target = el || document.activeElement || document.body;
    var fired = [];
    ['keydown', 'keypress', 'keyup'].forEach(function (type) {
      try {
        target.dispatchEvent(new KeyboardEvent(type, base));
        fired.push(type);
      } catch (e) { /* 忽略 */ }
    });
    return fired;
  }

  /**
   * 滚动。
   *
   * ⚠️ 找「可滚动的祖先」是有必要的：DSH 的聊天区不是 `document` 在滚，而
   *   是里面某个 overflow 容器。只滚 `window` 会「成功但没有效果」。
   */
  function findScrollable(from) {
    var el = from;
    while (el && el !== document.body && el !== document.documentElement) {
      var st = window.getComputedStyle(el);
      if (st && /(auto|scroll|overlay)/.test(st.overflowY) && el.scrollHeight > el.clientHeight + 4) return el;
      el = el.parentElement;
    }
    var best = null;
    var bestGap = 0;
    var cands = toArray(document.querySelectorAll('div,main,section,ul,ol'));
    for (var i = 0; i < cands.length; i++) {
      var c = cands[i];
      var gap = c.scrollHeight - c.clientHeight;
      if (gap > bestGap && c.clientHeight > 80) {
        var s2 = window.getComputedStyle(c);
        if (s2 && /(auto|scroll|overlay)/.test(s2.overflowY)) { best = c; bestGap = gap; }
      }
    }
    return best;
  }

  // ────────────────────────────────────────────────────────────────────
  // action 分派
  // ────────────────────────────────────────────────────────────────────

  function ok(extra) {
    var out = { ok: true, v: VERSION, url: location.href, title: document.title };
    if (extra) for (var k in extra) if (extra.hasOwnProperty(k)) out[k] = extra[k];
    return out;
  }

  function fail(why, extra) {
    var out = { ok: false, v: VERSION, error: why, url: location.href };
    if (extra) for (var k in extra) if (extra.hasOwnProperty(k)) out[k] = extra[k];
    return out;
  }

  function run(cmd) {
    cmd = cmd || {};
    var action = String(cmd.action || 'ping');

    // ── ping：探针活着吗、跑在哪个页面 ────────────────────────────────
    if (action === 'ping') {
      return ok({
        viewport: viewportInfo(),
        readyState: document.readyState,
        interactive: document.querySelectorAll(INTERACTIVE).length
      });
    }

    // ── dump：把界面上的按钮全列出来（带精确位置）────────────────────
    if (action === 'dump') {
      var got = collect(cmd);
      var ov = overlay();
      var out2 = {
        viewport: viewportInfo(),
        matched: got.matched,
        count: got.elements.length,
        scanned: got.scanned,
        blocked: got.blocked,
        elements: got.elements
      };
      // ⚠️ 有全屏遮挡层时，**必须**在顶层把这件事说出来。否则模型拿到的是一份
      //   看起来正常的清单（元素有尺寸、opacity 为 1），挨个点却全部无效 ——
      //   这正是「AI 说点过了但界面没动」的现场。
      if (ov) {
        out2.overlay = ov;
        out2.warning = '当前有覆盖视口大部分区域的遮挡层，它下面的按钮都点不动。' +
          '先关掉它（通常是弹窗里的某个按钮）再操作。';
      }
      return ok(out2);
    }

    // ── overlay：现在有没有遮挡层、它是什么、此刻真正能点什么 ────────
    if (action === 'overlay') {
      var layer = overlay();
      // ⚠️ 不要试图回答「遮挡层**里面**有哪些按钮」—— 实测官方前端的弹窗结构是
      //   `_root_ > [_mask_（全屏空遮罩）, _dialog_（真内容）]`，也就是**按钮是
      //   遮罩的兄弟节点，不是后代**。按后代找会得到「0 个可点元素」，而屏幕
      //   上明明有个「继续」可以点。
      //
      //   模型真正要问的是「**现在我能点什么**」，所以直接回答那个：列出当前
      //   **没有被遮挡**的可交互元素。这在任何 DOM 结构下都成立。
      var live = collect({ limit: 30, clickableOnly: true });
      if (!layer) {
        return ok({ present: false, clickable: live.elements, count: live.elements.length });
      }
      return ok({
        present: true,
        layer: layer,
        clickable: live.elements,
        count: live.elements.length,
        blocked: live.blocked
      });
    }

    // ── find：查单个元素的详情 ──────────────────────────────────────
    if (action === 'find') {
      var f = locate(cmd);
      if (!f) return fail('找不到元素', { spec: cmd });
      var d = describe(f, 0);
      d.html = trunc(f.outerHTML || '', 400);
      return ok({ element: d, viewport: viewportInfo() });
    }

    // ── tap：点一下 ────────────────────────────────────────────────
    if (action === 'tap') {
      var t = locate(cmd);
      if (!t) return fail('找不到要点击的元素', { spec: cmd });
      if (!isVisible(t)) {
        // 找到了但看不见 —— 明确报出来，别让它退化成「点了没反应」
        return fail('元素存在但不可见（被隐藏或尺寸为 0）', { element: describe(t, 0) });
      }
      var info = describe(t, 0);
      var res = clickEl(t);
      var out = {
        element: info,
        dispatched: res.dispatched,
        hitTopmost: res.hit,
        onTarget: res.onTarget,
        covered: !!res.hit && !res.onTarget,
        blockedBy: res.onTarget ? null : res.hit
      };
      // ⚠️ 被遮挡时**把话说明白**。这是本探针最该省下的一轮来回：模型点不动时
      //   最想知道的是「谁挡着、我该先点谁」，而不是自己去猜选择器写错了没有。
      if (!res.onTarget) {
        out.hint = '点击被上层元素吃掉了，目标没收到事件。先处理挡在前面的元素' +
          (res.hit && res.hit.text ? '（它的文案是「' + res.hit.text + '」）' : '') +
          '，或用 overlay 动作查看当前遮挡层。';
        out.overlay = overlay();
      }
      return ok(out);
    }

    // ── type：往输入框写字 ─────────────────────────────────────────
    //
    // ⚠️ **内容取自 `value`，不是 `text`** —— 这两个字段在这里是**两种意思**：
    //   `text` 是「按可见标签找元素」（与 tap/find 共用同一套定位），
    //   `value` 才是「要写进去的内容」。第一版把两者都用 `text`，结果是
    //   「填输入框时定位条件被内容覆盖」—— 找一个标签等于所填内容的元素，
    //   必然找不到，而报错是「找不到输入元素」，完全指不到真因。
    if (action === 'type') {
      var inp = locate(cmd);
      if (!inp) return fail('找不到输入元素', { spec: cmd });
      if (cmd.clear === true) setValue(inp, '');
      var how = setValue(inp, cmd.value == null ? '' : cmd.value);
      var submitted = null;
      if (cmd.enter === true) submitted = pressKey(inp, 'Enter', cmd);
      // ⚠️ 用 `readText` 而不是 `inp.value`：`contenteditable` 元素的 `value` 是
      //   `undefined`，于是这里**恒报 null**，而输入框里明明已经有字了 ——
      //   调用方拿到 `ok:true, value:null` 完全无法判断到底写进去没有（踩过）。
      //
      // ⚠️ 但 `value` 对 `contenteditable` **不能**当成「写成功了」的证据：
      //   `execCommand` 是异步生效的（见 `setValue`），**同步读必然为空**。
      //   所以这里只报「当时读到的值」，是否真的落地由 `applied` 表达 ——
      //   它的语义是「同步可确认已写入」，为 null 表示「异步写入已发出，
      //   请在下一步用 find/text 复核」，**不是失败**。
      var actual = readText(inp);
      var wanted = cmd.value == null ? '' : String(cmd.value);
      var confirmed = applied(inp, wanted);
      var async = how.indexOf('execCommand') === 0;
      return ok({
        element: describe(inp, 0),
        how: how,
        value: trunc(actual, MAX_TEXT),
        applied: confirmed ? true : (async ? null : false),
        submitted: submitted
      });
    }

    // ── key：按键 ──────────────────────────────────────────────────
    if (action === 'key') {
      var kel = cmd.sel || cmd.text || cmd.aria ? locate(cmd) : null;
      var fired = pressKey(kel, String(cmd.key || 'Enter'), cmd);
      return ok({ dispatched: fired, target: kel ? describe(kel, 0) : null });
    }

    // ── scroll：滚动 ───────────────────────────────────────────────
    if (action === 'scroll') {
      var anchor = cmd.sel || cmd.text ? locate(cmd) : null;
      var box = cmd.container ? bySel(cmd.container) : findScrollable(anchor);
      var dy = typeof cmd.dy === 'number' ? cmd.dy : 0;
      var dx = typeof cmd.dx === 'number' ? cmd.dx : 0;

      if (cmd.to === 'bottom' || cmd.to === 'top') {
        var target = box || document.scrollingElement || document.documentElement;
        var before = target.scrollTop;
        target.scrollTop = cmd.to === 'bottom' ? target.scrollHeight : 0;
        return ok({
          container: box ? cssPath(box) : 'document',
          before: before,
          after: target.scrollTop
        });
      }

      var beforeY = box ? box.scrollTop : (window.pageYOffset || 0);
      if (box) { box.scrollTop = beforeY + dy; box.scrollLeft = box.scrollLeft + dx; }
      else window.scrollBy(dx, dy);
      var afterY = box ? box.scrollTop : (window.pageYOffset || 0);
      return ok({
        container: box ? cssPath(box) : 'window',
        before: beforeY,
        after: afterY,
        moved: afterY !== beforeY
      });
    }

    // ── text：读可见文本（模型看不懂界面时用这个兜底）────────────────
    if (action === 'text') {
      var root = cmd.sel ? bySel(cmd.sel) : document.body;
      if (!root) return fail('找不到容器', { sel: cmd.sel });
      var raw = root.innerText || root.textContent || '';
      var lines = raw.split('\n').map(function (s) { return s.replace(/\s+/g, ' ').trim(); })
        .filter(function (s) { return s.length > 0; });
      var maxLines = typeof cmd.limit === 'number' && cmd.limit > 0 ? cmd.limit : 200;
      return ok({
        lines: lines.slice(0, maxLines),
        total: lines.length,
        truncated: lines.length > maxLines
      });
    }

    // ── html：读某元素的 HTML（排查用）─────────────────────────────
    if (action === 'html') {
      var node = cmd.sel ? bySel(cmd.sel) : document.body;
      if (!node) return fail('找不到元素', { sel: cmd.sel });
      var html = node.outerHTML || '';
      var max = typeof cmd.limit === 'number' && cmd.limit > 0 ? cmd.limit : 2000;
      return ok({ html: html.slice(0, max), length: html.length });
    }

    // ── wait：等条件成立（**单次检查**，重试由原生侧做）─────────────
    if (action === 'wait') {
      var hit = cmd.sel || cmd.text || cmd.testid || cmd.aria ? locate(cmd) : null;
      var needVisible = cmd.visible !== false;
      var satisfied = !!(hit && (!needVisible || isVisible(hit)));
      // ⚠️ 被遮挡的元素**不算「等到了」**：调用方等它出现是为了点它，而它现在
      //   点不动。报 satisfied 会让「等 → 点」这条路在弹窗场景下静默失败。
      if (satisfied && cmd.clickable === true && blocker(hit)) satisfied = false;
      return ok({
        satisfied: satisfied,
        element: hit ? describe(hit, 0) : null
      });
    }

    // ── info：一次性把「现在是什么界面」讲清楚 ──────────────────────
    if (action === 'info') {
      var path = location.pathname + location.search;
      var got2 = collect({ limit: 12 });
      return ok({
        viewport: viewportInfo(),
        path: path,
        hash: location.hash,
        readyState: document.readyState,
        focused: document.activeElement ? describe(document.activeElement, 0) : null,
        overlay: overlay(),
        blocked: got2.blocked,
        top: got2.elements,
        scrollable: (function () {
          var s = findScrollable(null);
          return s ? { sel: cssPath(s), top: s.scrollTop, height: s.scrollHeight, view: s.clientHeight } : null;
        })()
      });
    }

    return fail('未知 action: ' + action, {
      known: ['ping', 'dump', 'find', 'tap', 'type', 'key', 'scroll', 'text', 'html',
        'wait', 'info', 'overlay']
    });
  }

  // ────────────────────────────────────────────────────────────────────
  // 装填（幂等）
  // ────────────────────────────────────────────────────────────────────

  if (!window.__DSH_UI_PROBE__ || window.__DSH_UI_PROBE__.__v !== VERSION) {
    window.__DSH_UI_PROBE__ = {
      __v: VERSION,
      run: run,
      // 下面这些是给「页面内的调试脚本」用的入口，不影响任何既有行为。
      dump: function (o) { return collect(o); },
      locate: locate,
      describe: describe,
      cssPath: cssPath,
      click: clickEl,
      setValue: setValue,
      highlight: function (sel) {
        // ⚠️ 只在显式调用时画框，且用 fixed 定位、pointer-events:none，
        //   所以它**不会**改变点击行为，也不会留在页面上（用完自己删）。
        var el = bySel(sel);
        if (!el) return false;
        var r = el.getBoundingClientRect();
        var box = document.createElement('div');
        box.setAttribute('data-dsh-probe-mark', '1');
        box.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483647;' +
          'border:2px solid #ff3b30;border-radius:4px;box-sizing:border-box;' +
          'left:' + r.left + 'px;top:' + r.top + 'px;' +
          'width:' + r.width + 'px;height:' + r.height + 'px;';
        document.body.appendChild(box);
        setTimeout(function () { if (box.parentNode) box.parentNode.removeChild(box); }, 1200);
        return true;
      }
    };
  }

  return window.__DSH_UI_PROBE__.run(CMD);
}
