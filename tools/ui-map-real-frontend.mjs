// 在**真实官方前端**上跑 ui-probe.js，产出「按钮位置地图」。
//
// 为什么需要它：真机装不上本仓库的包（设备上是别处签名的 APK，
// `install -r` 撞 INSTALL_FAILED_UPDATE_INCOMPATIBLE，而卸载会清掉
// filesDir 下的整个 Termux 环境与 $DSH_HOME），所以 `ui` 工具链在
// 真机上跑不起来。但探针本身是**纯页面 JS**，只要有一份真实的官方
// 前端 DOM，就能验证它在真前端上到底看见了什么。
//
// 用法：
//   1) 起一个独立实例（独立 DSH_HOME / 端口，别碰用户正在用的那个）：
//        $env:DSH_HOME = "<隔离目录>"
//        dsh web --no-open --port 31999
//   2) node tools/ui-map-real-frontend.mjs "http://127.0.0.1:31999/?token=..."
//
// 输出：控制台报告 + tools/ui-map-real-frontend.json
//
// 依赖：仅 headless Chrome（--headless=new）+ CDP，Node 24 自带 WebSocket。

import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const PROBE = join(__dirname, '..', 'app', 'src', 'main', 'assets', 'ui-probe.js');

const CHROME_CANDIDATES = [
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
  join(process.env.LOCALAPPDATA ?? '', 'Google', 'Chrome', 'Application', 'chrome.exe'),
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
];

const url = process.argv[2];
if (!url) {
  console.error('用法: node tools/ui-map-real-frontend.mjs "<带 token 的 URL>"');
  process.exit(2);
}

const chrome = CHROME_CANDIDATES.find((p) => {
  try { return readFileSync(p) !== null; } catch { return false; }
});
if (!chrome) {
  console.error('找不到 Chrome/Edge');
  process.exit(2);
}

// ── CDP 最小客户端 ────────────────────────────────────────────────
class Cdp {
  constructor(ws) {
    this.ws = ws;
    this.id = 0;
    this.pending = new Map();
    ws.addEventListener('message', (ev) => {
      const msg = JSON.parse(ev.data);
      const p = this.pending.get(msg.id);
      if (p) { this.pending.delete(msg.id); msg.error ? p.reject(new Error(JSON.stringify(msg.error))) : p.resolve(msg.result); }
    });
  }
  send(method, params = {}) {
    const id = ++this.id;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.ws.send(JSON.stringify({ id, method, params }));
      setTimeout(() => { if (this.pending.delete(id)) reject(new Error(`CDP 超时: ${method}`)); }, 60000);
    });
  }
  /** 在页面里 eval 一个表达式，返回其值（awaitPromise）。 */
  async eval(expression) {
    const r = await this.send('Runtime.evaluate', {
      expression, returnByValue: true, awaitPromise: true,
    });
    if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description ?? '页面异常');
    return r.result.value;
  }
}

async function connect(wsUrl) {
  const ws = new WebSocket(wsUrl);
  await new Promise((res, rej) => {
    ws.addEventListener('open', res, { once: true });
    ws.addEventListener('error', rej, { once: true });
  });
  return new Cdp(ws);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── 主流程 ───────────────────────────────────────────────────────
const profile = mkdtempSync(join(tmpdir(), 'dsh-uimap-'));
const port = 9500 + Math.floor(Math.random() * 400);

const child = spawn(chrome, [
  '--headless=new',
  `--remote-debugging-port=${port}`,
  `--user-data-dir=${profile}`,
  '--no-first-run', '--no-default-browser-check',
  '--disable-gpu', '--hide-scrollbars',
  // 手机视口：与 DSH Android 上 WebView 的实际尺寸同量级（1080×2340 物理 / 3.0 dpr）
  '--window-size=393,851',
  '--force-device-scale-factor=3',
  'about:blank',
], { stdio: 'ignore' });

let cdp;
try {
  // 等 CDP 端口起来
  let targets = null;
  for (let i = 0; i < 60 && !targets; i++) {
    await sleep(250);
    try {
      const r = await fetch(`http://127.0.0.1:${port}/json/list`);
      if (r.ok) targets = await r.json();
    } catch { /* 还没起来 */ }
  }
  if (!targets) throw new Error('CDP 没起来');

  // ⚠️ `/json/list` 里混着**扩展的 background 页**（type=background_page /
  //    service_worker），它的 innerWidth 是 0、枚举出 0 个元素 —— 第一版就是
  //    连到了它，表现为「探针 ping 正常但 dump 出 0 个元素」，很容易误判成
  //    探针坏了。必须挑真正的页面目标。
  const page = targets.find((t) => t.type === 'page' && /^https?:/.test(t.url ?? ''))
    ?? targets.find((t) => t.type === 'page');
  if (!page) throw new Error(`没有可用的 page 目标: ${targets.map((t) => t.type).join(',')}`);

  cdp = await connect(page.webSocketDebuggerUrl);
  await cdp.send('Runtime.enable');
  await cdp.send('Page.enable');

  // ⚠️ **必须用 Emulation 精确设视口，不能靠 `--window-size`。**
  //    实测 `--window-size=393,851` 在 headless 下拿到的是 486×753（窗口装饰与
  //    缩放的差异），而 mobile-ui 的窄屏适配是按宽度触发的 —— 视口错一点，
  //    拿到的就是**桌面布局的坐标**，整张地图作废。
  //    手机上 WebView 的实际尺寸：1080 物理宽 / dpr 3.0 = 360 CSS 宽。
  const VW = Number(process.env.UIMAP_W ?? 360);
  const VH = Number(process.env.UIMAP_H ?? 780);
  const DPR = Number(process.env.UIMAP_DPR ?? 3);
  await cdp.send('Emulation.setDeviceMetricsOverride', {
    width: VW, height: VH, deviceScaleFactor: DPR, mobile: true,
  });
  await cdp.send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 });

  console.log(`打开真实官方前端: ${url.slice(0, 60)}…`);
  await cdp.send('Page.navigate', { url });

  // 等前端挂载：轮询直到出现 composer 或超时
  let ready = false;
  for (let i = 0; i < 80; i++) {
    await sleep(500);
    try {
      const info = await cdp.eval(`(() => ({
        title: document.title,
        href: location.href,
        nodes: document.querySelectorAll('*').length,
        text: (document.body && document.body.innerText || '').slice(0, 120)
      }))()`);
      if (info.nodes > 50) { ready = true; console.log(`  已挂载：${info.nodes} 个节点，title=${info.title}`); break; }
    } catch { /* 导航中 */ }
  }
  if (!ready) console.warn('  ⚠️ 前端似乎没挂载起来，继续尝试');

  await sleep(2500); // 给懒加载 chunk 一点时间

  // 装探针（与原生侧完全相同的调用方式：函数表达式直接调用）
  const source = readFileSync(PROBE, 'utf8');
  const installed = await cdp.eval(`(${source})({action:"ping"})`);
  console.log(`探针 ping: ${JSON.stringify(installed)}`);

  // ⚠️ 「界面加载出来了」不等于「mobile-ui 适配生效了」。
  //    mobile-ui 的客户端半靠 `dsh-client-modules` 扫 `dsh.client` 声明编入，
  //    挂载失败时**不报错**：宿主半照常 apply，页面照常渲染，只是那些
  //    `[data-mobile]` 规则没了、界面静默退回桌面布局。所以这里显式量一下
  //    适配是否生效 —— 否则会拿着一张桌面布局的坐标当地图用。
  const adapt = await cdp.eval(`(() => {
    const all = document.querySelectorAll('*');
    let marked = 0;
    for (const el of all) if (el.hasAttribute('data-mobile')) marked++;
    const styles = [...document.querySelectorAll('style')].map(s => s.textContent || '').join('');
    return {
      dataMobileNodes: marked,
      hasMobileCss: styles.includes('[data-mobile]'),
      mobileCssRules: (styles.match(/\\[data-mobile\\]/g) || []).length,
      viewport: innerWidth + 'x' + innerHeight,
    };
  })()`);
  console.log(`mobile-ui 适配: data-mobile 节点=${adapt.dataMobileNodes}, ` +
    `CSS 规则=${adapt.mobileCssRules} 条, 视口=${adapt.viewport}` +
    (adapt.mobileCssRules === 0 ? '  ⚠️ 适配未生效（拿到的是桌面布局坐标）' : '  ✅'));

  // ── 先处理遮挡层（这是「操作方法」的第 0 步）───────────────────────
  //
  // ⚠️ 全新 profile 首次打开会弹「预览版说明」对话框（`z-index:1000` + 覆盖全屏
  //    的 `_mask_`）。它会盖住底下**全部**按钮 —— 而底下的按钮在 DOM 里看着
  //    完全正常（有尺寸、`opacity` 为 1），第一版探针因此把它们全报成可用，
  //    模型挨个点、界面纹丝不动且拿不到任何提示。
  //
  //    现在探针会自己发现它：`overlay` 动作报出遮挡层与它**里面**的按钮，
  //    `dump` 也会带上 `overlay` + `warning`。下面这段就是照着那个输出走的，
  //    与真机上模型要走的路径**完全一致** —— 不是测试专用的特判。
  // ⚠️ 要**循环**：全新 profile 的首启向导是**多步**的 —— 关掉「预览版说明」
  //    之后立刻又弹「API 密钥」（稍后配置 / 保存并继续）。只关一次会停在
  //    第二个弹窗上，拿到的坐标照样点不动。这正是模型要学的那套操作法：
  //    `overlay` → 点此刻唯一能点的东西 → 再看 `overlay`，直到 present:false。
  for (let round = 1; round <= 5; round++) {
    const ov = await cdp.eval(`(${source})({action:"overlay"})`);
    if (!ov?.present) {
      console.log(`\n第 ${round} 轮: 没有遮挡层了 ✅（此刻能点 ${ov?.count ?? 0} 个）`);
      break;
    }
    console.log(`\n第 ${round} 轮遮挡层: ${ov.layer.cls || ov.layer.tag}（${ov.layer.rect.w}×${ov.layer.rect.h}，` +
      `${ov.layer.hits}/${ov.layer.samples} 个采样点被它挡住）`);
    console.log(`  此刻真正能点的有 ${ov.clickable.length} 个: ` +
      ov.clickable.map((b) => `「${b.text || b.sel}」`).join(', '));
    // ⚠️ 探针**如实**报出此刻能点的一切（可能是输入框 + 若干按钮），但**不替
    //    模型猜语义** —— 关弹窗该点哪个是业务判断。这里照模型的思路来：
    //    先挑「跳过/稍后/继续/知道了」这类**不要求填内容**的按钮，其次挑普通
    //    button，最后才退到任意元素。盲选第一个会选中输入框，点了弹窗照旧
    //    （踩过一次：连点 5 轮「API 密钥」输入框，弹窗纹丝不动）。
    const SKIP = /稍后|跳过|以后|知道了|我知道了|继续|确定|关闭|取消|好$/;
    const closer =
      ov.clickable.find((b) => b.tag === 'button' && SKIP.test(b.text || '')) ??
      ov.clickable.filter((b) => b.tag === 'button').pop() ??
      ov.clickable[0];
    if (!closer) { console.log('  ⚠️ 没有可点的东西，可能是纯加载蒙层，退出'); break; }
    const closed = await cdp.eval(`(${source})(${JSON.stringify({ action: 'tap', sel: closer.sel })})`);
    console.log(`  点「${closer.text || closer.sel}」: ok=${closed?.ok} onTarget=${closed?.onTarget}`);
    if (closed?.onTarget === false) console.log(`    hint: ${closed.hint}`);
    await sleep(800);
  }

  // ── 枚举按钮 ──────────────────────────────────────────────────────
  const dump = await cdp.eval(`(${source})({action:"dump", limit:300})`);
  if (!dump || dump.ok !== true) throw new Error(`dump 失败: ${JSON.stringify(dump)}`);
  if (dump.overlay) console.log(`⚠️ dump 仍报遮挡: ${dump.warning}`);

  const elements = dump.elements ?? [];
  console.log(`\n真实前端上共枚举到 ${elements.length} 个可交互元素` +
    (dump.blocked ? `（其中 ${dump.blocked} 个被遮挡）` : ''));

  // 只打印「有标签的」——那就是用户说的「按钮」
  const labelled = elements.filter((e) => e.text || e.testid || e.aria);
  console.log(`\n其中有可见标签/aria 的 ${labelled.length} 个：\n`);
  for (const e of labelled.slice(0, 80)) {
    const label = e.text || e.aria || e.testid;
    const c = e.center ? `${Math.round(e.center.x)},${Math.round(e.center.y)}` : '-';
    const r = e.rect ? `${Math.round(e.rect.w)}×${Math.round(e.rect.h)}` : '-';
    console.log(`  ${String(label).slice(0, 34).padEnd(36)} ${e.tag.padEnd(8)} CSS(${c}) ${r}` +
      (e.covered ? '  ⛔被遮挡' : '') + `  ${e.sel}`);
  }

  const report = {
    url: url.replace(/token=[^&]+/, 'token=***'),
    viewport: await cdp.eval(`({w: innerWidth, h: innerHeight, dpr: devicePixelRatio})`),
    total: elements.length,
    blocked: dump.blocked ?? 0,
    labelled: labelled.length,
    elements: labelled,
  };
  const out = join(__dirname, 'ui-map-real-frontend.json');
  writeFileSync(out, JSON.stringify(report, null, 2), 'utf8');
  console.log(`\n已写出 ${out}`);
  console.log(`视口: ${JSON.stringify(report.viewport)}`);

  // ── 交互实测：探针能不能**真的**操作真实前端 ──────────────────────
  //
  // ⚠️ 判据必须是「**业务状态真的变了**」，节点数不是判据 —— 两个反例都踩过：
  //    ① 抽屉靠 CSS transform/opacity 显隐，DOM 节点数一个都不增；
  //    ② `.mp-drawer-mask` 是**懒建且常驻**的（`mpMask()` 建完就不删），
  //       所以「它存在」也不代表抽屉开着。
  //
  //    而「点汉堡展开抽屉」本身在空会话页（Hero）**根本不是有效判据**：
  //    此时官方侧栏尚未渲染（`.pI_x6G_sidebarCol` 不存在），
  //    `mpToggleDrawer()` 里 `mpOfficialToggle()` 返回 null、
  //    `if (btn) btn.click()` 不执行 → 点汉堡本来就没反应。那是 mobile-ui
  //    插件在 Hero 页的缺陷，探针如实报 `onTarget:true` 并没有说谎。
  //
  //    所以用 composer 走一遍**真正会改变界面**的链路：
  //    type 写入 → 发送按钮从禁用变可用（证明 React 认账）→ tap 发送 →
  //    消息出现在界面上。这三步任何一步失败都说明探针没真的驱动到应用。
  console.log('\n══ 交互实测（探针真的驱动了应用）══');

  const COMPOSER = `(() => {
    const ce = document.querySelector('[contenteditable="true"]');
    const ta = document.querySelector('textarea');
    const input = ce || ta;
    const sendBtn = [...document.querySelectorAll('button')].find(b =>
      /发送|send/i.test((b.getAttribute('aria-label') || '') + (b.textContent || '')));
    return {
      editorKind: ce ? 'contenteditable' : (ta ? 'textarea' : null),
      value: input ? (input.value !== undefined ? input.value : input.innerText) : null,
      sendDisabled: sendBtn ? (sendBtn.disabled === true || sendBtn.getAttribute('aria-disabled') === 'true') : null,
    };
  })()`;

  const before = await cdp.eval(COMPOSER);
  console.log(`输入前: 编辑器=${before.editorKind} 值=${JSON.stringify(before.value)} 发送键禁用=${before.sendDisabled}`);

  const ed = await cdp.eval(`(${source})({action:"dump", limit:20, filter:"描述你想要构建"})`);
  const editor = (ed.elements ?? []).find((e) => e.tag === 'div' || e.tag === 'textarea');
  if (!editor) throw new Error('找不到 composer 编辑器');

  const TEXT = '探针自检：这条消息由 ui-probe 写入并发送';
  const typed = await cdp.eval(`(${source})(${JSON.stringify({ action: 'type', sel: editor.sel, value: TEXT })})`);
  // ⚠️ 必须**等一下再读**：`execCommand` 对富文本编辑器（官方 composer 是
  //    Lexical）是**异步生效**的，同步读必然为空。立即断言会把成功误判成失败。
  await sleep(900);
  const mid = await cdp.eval(COMPOSER);
  console.log(`type 写入: ok=${typed.ok} 方式=${typed.how} applied=${typed.applied}`);
  console.log(`  输入框已填入: ${String(mid.value).length > 0 ? '✅' : '❌'}`);
  console.log(`  发送键 禁用→可用: ${mid.sendDisabled === false ? '✅（React 真的认账了）' : `❌（sendDisabled=${mid.sendDisabled}）`}`);

  let sentOk = false;
  if (mid.sendDisabled === false) {
    const nodesBefore = await cdp.eval(`document.querySelectorAll('*').length`);
    const sent = await cdp.eval(`(${source})({action:"tap", aria:"发送消息"})`);
    console.log(`tap 发送: ok=${sent?.ok} onTarget=${sent?.onTarget}`);
    await sleep(2500);
    const post = await cdp.eval(`(() => {
      const ce = document.querySelector('[contenteditable="true"]');
      return {
        nodes: document.querySelectorAll('*').length,
        editor: ce ? (ce.innerText || '') : null,
        hasMsg: document.body.innerText.includes(${JSON.stringify(TEXT)}),
      };
    })()`);
    console.log(`  输入框已清空: ${post.editor === '' ? '✅' : `❌（${JSON.stringify(String(post.editor).slice(0, 30))}）`}`);
    console.log(`  消息出现在界面上: ${post.hasMsg ? '✅ 探针真的把消息发出去了' : '❌'}`);
    console.log(`  节点数 ${nodesBefore} → ${post.nodes}（仅作参考，不是判据）`);
    sentOk = post.hasMsg === true;
  }

  report.interaction = {
    typed: typed.ok === true,
    reactAccepted: mid.sendDisabled === false,
    sent: sentOk,
  };
  writeFileSync(out, JSON.stringify(report, null, 2), 'utf8');
  console.log(`\n已更新 ${out}`);
} finally {
  try { child.kill(); } catch { /* ignore */ }
  try { rmSync(profile, { recursive: true, force: true }); } catch { /* ignore */ }
}
