// 在**真实官方前端**上跑 ui-probe.js，产出「按钮位置地图」。
//
// 为什么需要它：真机装不上本仓库的包（设备上是别处签名的 APK，
// `install -r` 撞 INSTALL_FAILED_UPDATE_INCOMPATIBLE，而卸载会清掉
// filesDir 下的整个 Termux 环境与 $DSH_HOME），所以 `ui` 工具链在
// 真机上跑不起来。但探针本身是**纯页面 JS**，只要有一份真实的官方
// 前端 DOM，就能验证它在真前端上到底看见了什么。
//
// ⚠️ 真机上另有一条路也走不通：`uiautomator dump` 在 WebView 上**只给一个
//    `android.webkit.WebView` 节点**（实测华为 CDY-TN90：3137 字节的树里
//    只有 5 个 FrameLayout + 1 个 WebView，页面内的按钮一个都不在无障碍树里）。
//    所以按钮坐标的唯一可靠来源就是本脚本 —— 在真实前端里直接量 CSS 坐标。
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

// ── 屏幕清单 ──────────────────────────────────────────────────────
//
// 「屏幕」= 一组**进入步骤**唯一确定的界面状态。进入步骤用的定位参数与 `ui`
// 工具的 `tap` 完全同构（`text` / `aria` / `sel`），所以图谱里记下的每一步
// 都能**原样**喂给 `ui tap` —— 这是这份图谱的价值所在：它既是坐标表，
// 也是「怎么走到那一屏」的路线。
//
// ⚠️ 每屏都从**基态**（Hero，页面刚加载完）重新进入，而不是从上一屏接着走。
//    这样屏与屏之间没有状态污染，某一屏的步骤写错了也不会连带毁掉后面的屏。
//
// ⚠️⚠️ **但「重载」并不足以回到基态 —— 这个前端会持久化好几样东西**，本轮
//    在这上面连栽三次，症状全都是「找不到要点击的元素」（看着像定位写错了）：
//
//    | 被持久化的 | 后果 | 对策 |
//    |---|---|---|
//    | 右侧边栏的**开关状态** | 重载后它自己又开着，入口按钮的 aria 从「打开右侧边栏」变成「收起右侧边栏」 | 每屏重置后先 `collapsePanels()` 收掉 |
//    | 右侧边栏的**内容页** | 点过「新建终端」后，再打开直接是终端页，启动页的「新建终端」不再出现 | 启动页那一屏排在终端屏**之前** |
//    | **当前会话** | 种过会话就回不到空会话态，「选择新任务使用的 Agent 预设」再也不渲染 | 只在空态存在的屏排在 `drawer` 之前 |
//
//    共同点是**单向**：一旦进入就回不去。所以屏的顺序本身是有语义的，
//    不是随手排的 —— 改顺序前先想清楚哪一屏会把状态推过去。
//
// `expect` 是「这一屏真的进去了吗」的判据 —— 只看 `tap` 的 `ok` 不够：
// 点了没生效时 `ok` 照样是 true（那正是遮挡/屏幕外两类问题的表现）。
const SCREENS = [
  {
    id: 'hero',
    title: '空会话（Hero）',
    enter: [],
    // ⚠️ 判据用「打开导航」这个**基态特有**的悬浮按钮。它是 mobile-ui 在窄屏
    //    下加的（`body > button`），只在这一屏出现，不会与别的屏撞车。
    expect: { text: '打开导航' },
  },
  {
    id: 'settings',
    title: '设置面板',
    enter: [{ text: '设置' }],
    expect: { text: '通用设置' },
  },
  {
    id: 'settings-general',
    title: '设置 › 通用设置',
    enter: [{ text: '设置' }, { text: '通用设置' }],
    // ⚠️ 判据挑**这一屏独有**的：字号那对按钮只有通用设置里有
    //    （「返回」「关闭」「中文」在别的设置页也有，不能用）
    expect: { text: '增大字号' },
  },
  {
    id: 'settings-group',
    title: '设置 › 交流群',
    enter: [{ text: '设置' }, { text: '交流群' }],
    expect: { text: '复制群号' },
  },
  {
    id: 'settings-plugins',
    title: '设置 › 内置插件',
    enter: [{ text: '设置' }, { text: '内置插件' }],
    expect: { text: '会话插件' },
  },
  {
    id: 'settings-model',
    title: '设置 › 模型',
    enter: [{ text: '设置' }, { text: '模型' }],
    // ⚠️ 判据要挑**这一屏独有**的元素：「返回」「关闭」这类在好几屏都有，
    //    拿它当判据等于没判（任何面板屏都能满足）。「添加模型」只在这一屏出现。
    expect: { text: '添加模型' },
  },
  {
    id: 'settings-preset',
    title: '设置 › Agent 预设',
    enter: [{ text: '设置' }, { text: 'Agent 预设' }],
    expect: { text: '新任务默认: 标准模式' },
  },
  {
    id: 'settings-priv',
    title: '设置 › 特权权限',
    enter: [{ text: '设置' }, { text: '特权权限' }],
    expect: { text: '重新检测' },
  },
  {
    id: 'settings-bubble',
    title: '设置 › 悬浮气泡',
    enter: [{ text: '设置' }, { text: '悬浮气泡' }],
    expect: { text: '怎么用' },
  },
  {
    id: 'plugins-panel',
    title: '插件面板',
    enter: [{ text: '插件' }],
    expect: { text: '添加插件' },
  },
  {
    id: 'model-picker',
    title: '模型选择器',
    // ⚠️ 用 `text` 而不是 `aria`：这个按钮**没有** `aria-label`（实测
    //    `{aria:'选择模型，当前…'}` 报「找不到要点击的元素」）。同排的
    //    「访问模式」「Agent 预设」两个倒是有 aria-label —— 同一行里三个按钮
    //    的取值口径都不一样，所以**别照着邻居抄**，先 `dump` 看它报的是哪个字段。
    enter: [{ text: '选择模型' }],
    expect: { text: '推理等级' },
  },
  {
    id: 'access-mode',
    title: '访问模式选择器',
    enter: [{ aria: '访问模式，当前：工作区内修改' }],
    // 「完全权限」是三个选项里最不可能出现在别处的那个
    expect: { text: '完全权限' },
  },
  {
    id: 'preset-picker',
    title: 'Agent 预设选择器',
    enter: [{ aria: '选择新任务使用的 Agent 预设' }],
    // 这一屏的选项文本很长（带整段说明），所以判据用**子串**：
    // `byText` 先找完全相等、找不到才退回子串包含，所以给短名即可命中。
    expect: { text: '极简模式' },
  },
  // ⚠️⚠️ **顺序是有语义的：`drawer` 必须排在最后几个。**
  //
  //    「种会话」是**单向**的 —— 前端会把当前会话持久化，重载也回不到空会话态。
  //    实测：seed 之后「选择新任务使用的 Agent 预设」这个按钮**再也不出现**
  //    （它在 Hero 空态才渲染），于是 `preset-picker` 屏永远进不去，
  //    而且症状是「找不到要点击的元素」—— 看着像探针定位错了，其实是页面已经不是那一屏。
  //
  //    所以所有**只在空会话态存在**的屏都必须排在 `drawer` 之前。
  //    这条与「每屏都从基态重来」是配套的：重载能回到基态，但**回不到空会话态**。
  {
    id: 'drawer',
    title: '会话抽屉（左侧栏展开）',
    // ⚠️ **必须先进入「有会话」的页面**，否则抽屉根本开不出来：空会话（Hero）页
    //    上官方侧栏还没渲染（`.pI_x6G_sidebarCol` 不存在），mobile-ui 的
    //    `mpToggleDrawer()` 里 `mpOfficialToggle()` 返回 null、`if (btn) btn.click()`
    //    不执行 —— 点「打开导航」本来就没反应（那是 mobile-ui 在 Hero 页的缺陷，
    //    探针如实报 `onTarget:true` 并没有说谎）。
    //    实测：空会话下点「打开导航」后标签数仍是 15、没有「收起侧边栏」；
    //    先发一条消息（页面进入会话态）再点，就能开出 36 个标签的抽屉。
    seed: true,
    enter: [{ aria: '打开导航' }],
    // ⚠️ 这里必须用 `text` 不能用 `aria`：抽屉里的「收起侧边栏」**没有**
    //    `aria-label`，文案在文本节点里。而 `locate()` 的 `aria` 分支只查
    //    `[aria-label="…"]`（现已回落到 `title`，但抽屉这个连 `title` 也没有），
    //    **不回落**到文本 —— 用 `aria` 会「找不到要点击的元素」，
    //    而那个按钮明明就在屏幕上（实测踩过：抽屉明明开了，校验却说没进去）。
    //    判据是 dump 里的字段：`aria` 有值才用 `aria`，否则用 `text`。
    expect: { text: '收起侧边栏' },
    // 抽屉要点两次（见下面的重试注释），所以给它多一次机会
    enterAttempts: 3,
  },
  {
    id: 'right-sidebar',
    title: '右侧边栏',
    enter: [{ aria: '打开右侧边栏' }],
    expect: { text: '新建终端' },
  },
  // ⚠️ 「工作区文件」与「终端」都是右侧边栏里的**内容页**，而内容页是**持久化**的
  //    （见上面「持久化状态」那段）：点过其中一个，再打开侧边栏就直接落在它上面，
  //    启动页（有「新建终端」/「工作区文件」两个入口的那一屏）不再出现。
  //    所以这两屏都只能从启动页进，**排在一起、各跑各的**：每屏都会先
  //    `collapsePanels()` 把侧边栏收掉，下一次打开才重新回到启动页。
  {
    id: 'workfiles',
    title: '右侧边栏 › 工作区文件',
    enter: [{ aria: '打开右侧边栏' }, { text: '工作区文件' }],
    // 「重新读取」是这一屏特有的（终端屏没有），用它当判据
    expect: { text: '重新读取' },
  },
  {
    id: 'terminal',
    title: '右侧边栏 › 终端',
    enter: [{ aria: '打开右侧边栏' }, { text: '新建终端' }],
    // ⚠️ 判据**不能**用「新标签页」—— 工作区文件屏也有它，于是「其实没进去」
    //    也会被判成 ✅（实测踩过：`tap「新建终端」ok=false` 却报已进入）。
    //    终端屏独有的标签是当前 Shell 的名字（默认 `cmd.exe`，见 ⑦.7 的 Shell 选择）。
    //    ⚠️ 换了默认 Shell 这个名字会变，届时同步改这里。
    expect: { text: 'cmd.exe' },
  },
];

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

  const source = readFileSync(PROBE, 'utf8');
  const probeVersion = Number(/var VERSION = (\d+)/.exec(source)?.[1] ?? 0);

  // 打开前端 + 装探针 + 关掉首启向导，收敛到**基态**（Hero）。
  //
  // ⚠️ 每屏都从这里重来，所以这段被抽成函数而不是内联一次。
  async function resetToBase() {
    await cdp.send('Page.navigate', { url });
    let ready = false;
    for (let i = 0; i < 80; i++) {
      await sleep(500);
      try {
        const info = await cdp.eval(`(() => ({
          title: document.title,
          nodes: document.querySelectorAll('*').length,
          text: (document.body && document.body.innerText || '').slice(0, 120)
        }))()`);
        if (info.nodes > 50) { ready = true; return info; }
      } catch { /* 导航中 */ }
    }
    if (!ready) console.warn('  ⚠️ 前端似乎没挂载起来，继续尝试');
    return null;
  }

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
  const dismissOverlays = async (tag) => {
    for (let round = 1; round <= 5; round++) {
      const ov = await cdp.eval(`(${source})({action:"overlay"})`);
      // ⚠️ **必须按 `kind` 判，不能按 `present` 判。** `present` 的语义是
      //    「有没有覆盖全屏的层」（面板也算），只有 `kind === 'mask'` 才是
      //    「要关掉的那个空遮罩」。照 `present` 判会把设置面板 / 右侧边栏
      //    也当遮挡层去「关」，实测把右侧边栏点开又点关，绕两圈才回到原位。
      if (!ov?.present || ov.kind !== 'mask') {
        console.log(`  ${tag}遮挡层: 没有了 ✅（此刻能点 ${ov?.count ?? 0} 个）` +
          (ov?.kind === 'panel' ? `（当前屏幕是面板 ${ov.panel?.cls || ov.panel?.tag}，不是遮挡）` : ''));
        return true;
      }
      console.log(`  ${tag}遮挡层 ${round}: ${ov.layer.cls || ov.layer.tag}（${ov.layer.rect.w}×${ov.layer.rect.h}，` +
        `${ov.layer.hits}/${ov.layer.samples} 个采样点被它挡住）`);
      console.log(`    此刻真正能点的有 ${ov.clickable.length} 个: ` +
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
      if (!closer) { console.log('    ⚠️ 没有可点的东西，可能是纯加载蒙层，退出'); return false; }
      const closed = await cdp.eval(`(${source})(${JSON.stringify({ action: 'tap', sel: closer.sel })})`);
      console.log(`    点「${closer.text || closer.sel}」: ok=${closed?.ok} onTarget=${closed?.onTarget}`);
      if (closed?.onTarget === false) console.log(`      hint: ${closed.hint}`);
      await sleep(800);
    }
    return false;
  };

  // ── 把面板收起来（重载**不等于**回到基态）─────────────────────────
  //
  // ⚠️ 这是本轮踩到的第 5 个坑，也是最阴的一个：**前端会把右侧边栏的打开状态
  //    持久化**（大概是 localStorage / 服务端 profile）。所以 `Page.navigate`
  //    重载之后，右侧边栏**自己又开着了**，那个按钮的 aria 也从
  //    「打开右侧边栏」变成了「收起右侧边栏」—— 于是照 `{aria:'打开右侧边栏'}`
  //    去点会报「找不到要点击的元素」，而屏幕上明明有个侧边栏。
  //
  //    症状有迷惑性：图谱里「右侧边栏」那一屏单独跑是 ✅，排在「终端」屏**之后**
  //    跑就 ❌ —— 因为终端屏把侧边栏打开了，下一屏重载后它还在。
  //    **屏与屏之间有状态残留**，这正是「每屏都从基态重来」这条设计要防的事，
  //    但重载并不足以回到基态。
  //
  //    所以基态 = 重载 **且** 把面板收掉。收的时候用面板自己的关闭按钮
  //    （「收起右侧边栏」/「关闭」），**不是**去关掉面板本身（它是屏幕不是遮挡）。
  //
  // ⚠️⚠️ 但「收起右侧边栏」**不重置内容页**！右侧边栏的内容页也是持久化的，
  //    实测：进了「工作区文件」之后收起再打开，**还是「工作区文件」**，启动页
  //    那两个入口（新建终端 / 工作区文件）不再出现 —— 于是下一屏 `tap「新建终端」`
  //    报 `ok:false 找不到要点击的元素`。
  //    **内容页自己的「关闭」才重置内容页**（点了它侧边栏整个收起，再打开就回到
  //    启动页）。所以关闭按钮的**首选**是「关闭」，「收起右侧边栏」只作兜底 ——
  //    顺序反了就会带着上一屏的内容页跑下一屏。
  //    ⚠️ 启动页的侧边栏**没有**「关闭」（只有分栏 / 退出全屏 / 收起右侧边栏），
  //    所以「关闭」找不到时自然会落到兜底上，两条路都覆盖到了。
  const collapsePanels = async (tag) => {
    for (let i = 0; i < 3; i++) {
      const ov = await cdp.eval(`(${source})({action:"overlay"})`);
      if (ov?.kind !== 'panel') return;
      const pool = ov.clickable ?? [];
      // ⚠️ 关闭按钮要**按优先级挑**，两个坑叠在一起：
      //    ① 「退出全屏」排在前面，拿第一个正则命中的就用会点到它 —— 面板照旧
      //       开着（它只切换全屏态），下一轮又拿到同一个面板，**绕圈**（实测踩过）；
      //    ② 「收起右侧边栏」虽然能收起面板，但**不重置内容页**（见上）。
      //    所以顺序是：内容页的「关闭」→ 启动页的「收起右侧边栏」→ 泛化「关闭/返回」。
      const closer =
        pool.find((b) => /^关闭$/.test((b.text || '').trim())) ??
        pool.find((b) => /收起.*边栏|收起侧边栏|收起面板/.test(b.text || '')) ??
        pool.find((b) => /^返回$/.test((b.text || '').trim())) ??
        pool.find((b) => /关闭|返回/.test(b.text || ''));
      if (!closer) {
        console.log(`  ${tag}⚠️ 面板 ${ov.panel?.cls || ov.panel?.tag} 上没有关闭按钮，收不掉`);
        return;
      }
      console.log(`  ${tag}收起面板「${closer.text}」`);
      await cdp.eval(`(${source})(${JSON.stringify({ action: 'tap', sel: closer.sel })})`);
      // ⚠️ 收起是**带动画的**（侧栏滑出）。动画没落定就点「打开右侧边栏」，
      //    按钮还处在过渡态（aria 仍是「收起右侧边栏」），于是报
      //    「找不到要点击的元素」—— 实测「右侧边栏」屏就是这么挂的。
      await sleep(1600);
    }
  };

  console.log(`打开真实官方前端: ${url.slice(0, 60)}…`);
  const boot = await resetToBase();
  if (boot) console.log(`  已挂载：${boot.nodes} 个节点，title=${boot.title}`);
  await sleep(2500); // 给懒加载 chunk 一点时间

  // 装探针（与原生侧完全相同的调用方式：函数表达式直接调用）
  const installed = await cdp.eval(`(${source})({action:"ping"})`);
  console.log(`探针 ping: ${JSON.stringify(installed)}  (VERSION=${probeVersion})`);

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

  console.log('\n── 第 0 步：清掉遮挡层 ──');
  await dismissOverlays('');

  const viewport = await cdp.eval(`({w: innerWidth, h: innerHeight, dpr: devicePixelRatio})`);

  // ⚠️ 编辑器的定位**不能靠 placeholder 文案**：空会话（Hero）页是「描述你想要构建的
  //    内容…」，一旦有了会话就变成「发消息或创建任务…」—— 照文案找会「找不到
  //    composer」，而 composer 明明就在那儿（实测踩过两次）。
  //    直接查 `[contenteditable="true"]`，与 `ui` 工具链在真机上用的是同一个抓手。
  const EDITOR_SEL = '[contenteditable="true"]';
  const findEditor = async () => {
    const r = await cdp.eval(`(${source})(${JSON.stringify({ action: 'find', sel: EDITOR_SEL })})`);
    return r?.ok === true ? r.element : null;
  };

  // ── 「种」一个会话：某些屏幕只在有会话时才存在 ────────────────────
  //
  // ⚠️ 会话抽屉就是这种：Hero 页上官方侧栏还没渲染，点「打开导航」没反应。
  //    发一条消息让页面进入会话态，抽屉才开得出来。见 SCREENS 里 drawer 的注释。
  const seedSession = async () => {
    const editor = await findEditor();
    if (!editor) { console.log('  ⚠️ 找不到 composer，无法种会话'); return false; }
    const seedText = '图谱种会话';
    await cdp.eval(`(${source})(${JSON.stringify({ action: 'type', sel: editor.sel, value: seedText })})`);
    await sleep(900); // execCommand 异步生效，见 setValue 的注释
    await cdp.eval(`(${source})({action:"tap", aria:"发送消息"})`);
    await sleep(3500); // 等消息落地、侧栏渲染出来
    console.log(`  种会话完成（composer 已提交）`);
    return true;
  };

  // ── 逐屏采集 ──────────────────────────────────────────────────────
  const screens = [];
  for (const spec of SCREENS) {
    console.log(`\n══ 屏幕「${spec.title}」(${spec.id}) ══`);

    // 非首屏才需要重置：首屏已经在基态上了，重载一次纯属浪费。
    if (spec.enter.length > 0) {
      await resetToBase();
      await sleep(2500);
      await cdp.eval(`(${source})({action:"ping"})`);
      await dismissOverlays('  ');
      // ⚠️ **重载不等于回到基态**：前端会持久化右侧边栏的打开状态，重载后它
      //    自己又开着，入口按钮的 aria 也随之从「打开右侧边栏」变成「收起右侧边栏」。
      //    不收掉的话，这一屏的 `enter` 第一步就会报「找不到要点击的元素」。
      //    见 `collapsePanels` 的注释。
      await collapsePanels('  ');
    }
    // `seed:true` 的屏要先进入会话态（见 seedSession 的注释）
    if (spec.seed) await seedSession();
    // ⚠️ 种会话之后页面刚重渲染过，等它稳定再点 —— 紧接着就点「打开导航」会点到
    //    还没挂载的按钮上（`tap` 返回 `ok:false` 找不到元素，或点了个空壳）。
    if (spec.seed) await sleep(2000);

    // ── 进入 + 校验：没等到特征元素就**重点一次** ──────────────────
    //
    // ⚠️ **不是所有屏幕都能一次点进去。** 实测会话抽屉要点**两次**「打开导航」：
    //    第一次点完侧栏从 `x:-137` 滑到 `x:-340`（更外面，仍是关着的），第二次才
    //    真正展开（`class` 加上 `mp-drawer-open`、`x:0`、「收起侧边栏」出现）。
    //    根因是 mobile-ui 的时序竞态 —— 会话刚建立时官方侧栏组件还没渲染完，
    //    `mpToggleDrawer()` 那一下切换落空了。
    //
    //    这正是 `ui` 工具链在真机上**必须**走的路径：点 → `wait` 等特征元素 →
    //    没等到就**再点一次**（不是重载重来）。所以这里照做。
    //
    //    ⚠️ 重试必须是**原地重点**：重置回基态再点一次会撞上同一个竞态，永远
    //    进不去（试过，三次全失败）。
    const steps = [];
    let entered = true;
    for (const step of spec.enter) {
      const r = await cdp.eval(`(${source})(${JSON.stringify({ action: 'tap', ...step })})`);
      const label = step.text || step.aria || step.sel;
      const stepOk = r?.ok === true && r?.onTarget !== false && r?.offscreen !== true;
      console.log(`  tap「${label}」 ok=${r?.ok} onTarget=${r?.onTarget} offscreen=${r?.offscreen ?? false}` +
        (stepOk ? '' : `  ⚠️ ${r?.error ?? r?.hint ?? ''}`));
      steps.push({ target: label, spec: step, ok: stepOk, error: r?.error ?? null, hint: r?.hint ?? null });
      if (!stepOk) entered = false;
      await sleep(900);
    }

    let verified = null;
    if (spec.expect && entered) {
      const key = spec.expect.text ?? spec.expect.aria ?? spec.expect.sel;
      const tries = spec.enterAttempts ?? 2;
      for (let attempt = 1; attempt <= tries; attempt++) {
        const w = await cdp.eval(`(${source})(${JSON.stringify({
          action: 'wait', ...spec.expect, timeout: 2500,
        })})`);
        verified = w?.satisfied === true;
        console.log(`  等「${key}」: ${verified ? '✅ 等到了' : '✗ 没等到'}` +
          (verified ? '' : `（第 ${attempt}/${tries} 次）`));
        if (verified) break;
        if (attempt === tries) break;
        // 原地重点最后一步 —— 与真机上「没等到就再点一次」完全一致
        const last = spec.enter[spec.enter.length - 1];
        const again = await cdp.eval(`(${source})(${JSON.stringify({ action: 'tap', ...last })})`);
        console.log(`  ↻ 重点「${last.text || last.aria}」 ok=${again?.ok} onTarget=${again?.onTarget}`);
        steps.push({ target: (last.text || last.aria) + '（重试）', spec: last, ok: again?.ok === true, retry: true });
        await sleep(1200);
      }
    }

    const dump = await cdp.eval(`(${source})({action:"dump", limit:400})`);
    if (!dump || dump.ok !== true) throw new Error(`dump 失败: ${JSON.stringify(dump)}`);
    const elements = dump.elements ?? [];
    const labelled = elements.filter((e) => e.text || e.testid || e.aria);

    // 「真的进去了吗」——最终以 **dump 的实际状态**为准。
    // 只看 tap 的 ok 不够：点了没生效时 ok 照样是 true（遮挡/屏幕外就是这样）。
    // 循环里那次 `wait` 决定「要不要再试一次」，这里决定「记进图谱的是什么」。
    if (spec.expect) {
      const found = labelled.find((e) =>
        (spec.expect.text && (e.text || '').includes(spec.expect.text)) ||
        (spec.expect.aria && e.aria === spec.expect.aria));
      const byDump = !!found;
      if (byDump !== verified) {
        console.log(`  ⚠️ dump 复核与 wait 不一致（wait=${verified} dump=${byDump}），以 dump 为准`);
      }
      verified = byDump;
      console.log(`  校验: ${verified ? '✅ 已进入' : '❌ 没进去'}（找「${spec.expect.text || spec.expect.aria}」）`);
    }
    if (dump.overlay) console.log(`  ⚠️ 该屏仍有遮挡层: ${dump.warning}`);

    console.log(`  共 ${elements.length} 个可交互元素（有标签 ${labelled.length}，` +
      `被遮挡 ${dump.blocked ?? 0}，屏幕外 ${dump.offscreen ?? 0}）`);
    for (const e of labelled.slice(0, 60)) {
      const c = e.center ? `${Math.round(e.center.x)},${Math.round(e.center.y)}` : '-';
      const rr = e.rect ? `${Math.round(e.rect.w)}×${Math.round(e.rect.h)}` : '-';
      console.log(`    ${String(e.text || e.aria || e.testid).slice(0, 30).padEnd(32)} ${e.tag.padEnd(9)} ` +
        `CSS(${c.padEnd(9)}) ${rr.padEnd(9)}` +
        (e.covered ? ' ⛔被遮挡' : '') + (e.offscreen ? ' 📴屏幕外' : '') + `  ${e.sel}`);
    }

    screens.push({
      id: spec.id,
      title: spec.title,
      enter: steps,
      entered,
      verified,
      total: elements.length,
      labelled: labelled.length,
      blocked: dump.blocked ?? 0,
      offscreen: dump.offscreen ?? 0,
      elements: labelled,
    });
  }

  // ── 回到基态，做交互实测 ──────────────────────────────────────────
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
  //
  // ⚠️ **重载一次是必须的，不能只靠「关掉面板」**：上一屏（右侧边栏）留下的
  //    面板会盖住 composer —— 实测就是它导致「找不到 composer 编辑器」。
  //    重载把整个页面状态清干净，比逐个去关面板可靠得多。
  console.log('\n══ 交互实测（探针真的驱动了应用）══');
  await resetToBase();
  await sleep(2500);
  await cdp.eval(`(${source})({action:"ping"})`);
  await dismissOverlays('  ');
  await collapsePanels('  ');

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

  const editor = await findEditor();
  if (!editor) throw new Error('找不到 composer 编辑器（' + EDITOR_SEL + '）');

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
    // ⚠️ `onTarget:false` 时**必须**把命中者打出来：只报「没点到」而不报「点到
    //    了谁」，就无从判断是探针误报还是真被挡住了 —— 而这两种情况的处置
    //    完全相反（前者要修判据，后者要关遮挡层）。
    if (sent?.onTarget === false) {
      console.log(`  ⚠️ 点击没落到目标上，实际命中的是: ` +
        (sent.hitTopmost ? `${sent.hitTopmost.tag}.${sent.hitTopmost.cls || ''} ` +
          `CSS(${Math.round(sent.hitTopmost.center?.x ?? 0)},${Math.round(sent.hitTopmost.center?.y ?? 0)}) ` +
          `文本=「${sent.hitTopmost.text || ''}」` : '(取不到)'));
      console.log(`  目标元素: ${sent.element?.tag} CSS(${Math.round(sent.element?.center?.x ?? 0)},` +
        `${Math.round(sent.element?.center?.y ?? 0)}) 文本=「${sent.element?.text || ''}」`);
    }
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

  const hero = screens.find((s) => s.id === 'hero') ?? screens[0];
  const report = {
    url: url.replace(/token=[^&]+/, 'token=***'),
    viewport,
    dpr: viewport.dpr,
    probeVersion,
    screens,
    // ⚠️ 顶层这几个字段指向 **hero 屏**，是首版单屏脚本的语义，保留下来是为了
    //    不打断已经依赖它们的用法。要看别的屏请走 `screens[]`。
    total: hero.total,
    blocked: hero.blocked,
    offscreen: hero.offscreen,
    labelled: hero.labelled,
    elements: hero.elements,
    interaction: {
      typed: typed.ok === true,
      reactAccepted: mid.sendDisabled === false,
      sent: sentOk,
    },
  };
  const out = join(__dirname, 'ui-map-real-frontend.json');
  writeFileSync(out, JSON.stringify(report, null, 2), 'utf8');
  console.log(`\n已写出 ${out}`);
  console.log(`视口: ${JSON.stringify(report.viewport)}`);
  console.log(`\n══ 图谱总览（${screens.length} 屏）══`);
  for (const s of screens) {
    console.log(`  ${s.verified === false ? '❌' : '✅'} ${s.title.padEnd(24)} ` +
      `标签 ${String(s.labelled).padStart(3)} 个  被遮挡 ${String(s.blocked).padStart(2)}  屏幕外 ${String(s.offscreen).padStart(2)}` +
      (s.enter.length ? `  路径: ${s.enter.map((x) => x.target).join(' → ')}` : '  路径: （基态）'));
  }
} finally {
  try { child.kill(); } catch { /* ignore */ }
  try { rmSync(profile, { recursive: true, force: true }); } catch { /* ignore */ }
}
