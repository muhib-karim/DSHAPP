/**
 * 图谱「判据唯一性」审计 —— `expect` 必须**只在一屏成立**。
 *
 * ## 为什么需要这个脚本
 *
 * 图谱里每屏有个 `expect`（判据），回答「我到底进没进这一屏」。它有两个用途：
 *   ① 生成图谱时校验那一屏真的进去了（`verified`）；
 *   ② **内建成插件的「一键到屏」**（`tools/build-uiagent-screens.mjs`）——
 *      真机上的模型靠它知道现在在哪一屏。
 *
 * 判据一旦在**多屏**成立，两个用途**同时失效**，而且**症状都是静默的**：
 *   - 图谱那一屏会「假通过」（从没进去也记成 ✅）；
 *   - `goto` 会「假到达」（停在别的屏也报成功），模型于是对着错误的界面点按钮。
 *
 * 本轮实测抓到 **4 处**，全都是同一个成因 —— **看着独有、其实撞车**：
 *
 * | 屏 | 原判据 | 为什么撞车 |
 * |---|---|---|
 * | `hero` | `打开导航` | 它是 mobile-ui 的常驻悬浮按钮，**17 屏全有** |
 * | `model-picker` | `推理等级` | 子串命中了每屏都在的长文案「选择模型，当前 …，推理等级 High」 |
 * | `settings-priv` | `重新检测` | `settings › 悬浮气泡` 那屏也有一个「重新检测」 |
 * | `preset-picker` | `极简模式` | 子串命中了 `settings › Agent 预设` 的「设为新任务默认: 极简模式」 |
 *
 * ⚠️ **光靠肉眼审不出来**（我这轮就先看漏了两处）：必须机械地对**全部元素**跑
 *    一遍探针的匹配语义。
 *
 * ## 与探针口径的一致性 —— 这是本脚本的要害
 *
 * 判据最终是交给探针的 `wait` / `tap` 去执行的，所以这里必须**逐条复刻**它的语义，
 * 差一点结论就反：
 *
 * 1. **候选集合是全部可交互元素**，不过滤 `covered` / `offscreen`。
 *    遮挡与屏幕外影响的是「能不能点」，**不影响「在不在」**。
 * 2. **`exact` 走两轮**：先扫全部找**精确相等**，找不到才退回**子串包含**，
 *    且子串取**第一个**命中的（不是最长的、也不是最靠前的可见项）。
 * 3. **`clickable:true`** 才附加「没被遮挡、中心点在视口内」。
 * 4. `aria` 与 `text` 是两套入口，`locate()` 里还回落 `title`（这里只审 `text`/`aria`）。
 *
 * ## 用法
 *
 * ```powershell
 * node tools/ui-map-expect-audit.mjs
 * ```
 *
 * 退出码非零 = 有判据不合格。**改完判据要重跑图谱**（`ui-map-real-frontend.mjs`），
 * 让 JSON 里的 `expect` 跟着更新，否则内建表还是旧的。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const MAP = path.join(ROOT, 'tools', 'ui-map-real-frontend.json');
const GEN = path.join(ROOT, 'tools', 'ui-map-real-frontend.mjs');

const map = JSON.parse(fs.readFileSync(MAP, 'utf8'));
const gen = fs.readFileSync(GEN, 'utf8');

/**
 * 从**生成器**而不是 JSON 里读判据。
 *
 * ⚠️ 这一步是有意的：改判据的地方是生成器，JSON 要重跑才会跟上。
 *    从生成器读，就能在**重跑之前**先告诉你判据行不行 —— 否则重跑一次要几分钟。
 */
function expectsFromGenerator() {
  const out = {};
  // 匹配 `id: 'xxx', ... expect: { text: 'yyy', exact: true, clickable: true },`
  const re = /id: '([a-z-]+)',[\s\S]*?expect: \{ (text|aria|sel): '([^']+)'((?:, (?:exact|clickable): true)*) \}/g;
  for (const mm of gen.matchAll(re)) {
    const flags = mm[4] ?? '';
    out[mm[1]] = {
      kind: mm[2],
      want: mm[3],
      exact: flags.includes('exact'),
      clickable: flags.includes('clickable'),
    };
  }
  return out;
}

/** 复刻探针 `byText`：先精确、再子串。返回命中的元素或 null。 */
function byTextLike(screen, want, exact) {
  const w = want.trim().toLowerCase();
  for (const e of screen.elements) {
    if ((e.text || '').trim().toLowerCase() === w) return e;
  }
  if (exact) return null;
  for (const e of screen.elements) {
    if ((e.text || '').trim().toLowerCase().includes(w)) return e;
  }
  return null;
}

/** 某个判据在全部 17 屏里，哪些屏成立。 */
function hitsOf(spec) {
  const outs = [];
  for (const s of map.screens) {
    let el = null;
    if (spec.kind === 'text') el = byTextLike(s, spec.want, spec.exact);
    else if (spec.kind === 'aria') el = s.elements.find((e) => e.text === spec.want) ?? null;
    else if (spec.kind === 'sel') {
      // `sel` 判据在 dump 里没有等价字段（dump 不回落选择器匹配），
      // 所以这里只能查「图谱里记的 sel 一模一样」，属于弱校验，单独标出来。
      el = s.elements.find((e) => e.sel === spec.want) ?? null;
    }
    if (!el) continue;
    if (spec.clickable === true && (el.covered || el.offscreen)) continue;
    outs.push(s.id);
  }
  return outs;
}

const expects = expectsFromGenerator();
const problems = [];

console.log(`图谱判据唯一性审计 —— ${map.screens.length} 屏 / ${map.screens.length} 个判据\n`);

for (const s of map.screens) {
  const spec = expects[s.id];
  if (!spec) {
    problems.push(`${s.id}: 生成器里没有解析到 expect`);
    console.log(`  ⚠️ ${s.id.padEnd(18)} （没解析到 expect）`);
    continue;
  }
  const hits = hitsOf(spec);
  const ok = hits.length === 1 && hits[0] === s.id;
  if (!ok) problems.push(`${s.id}: 判据「${spec.want}」在 ${hits.length} 屏成立（${hits.join(', ') || '一屏都不成立'}）`);

  const flag = `[${spec.kind}${spec.exact ? '+exact' : ''}${spec.clickable ? '+clickable' : ''}]`;
  console.log(`  ${ok ? '✅' : '⚠️'} ${s.id.padEnd(18)} ${flag.padEnd(18)} `
    + `${JSON.stringify(spec.want).slice(0, 40).padEnd(42)} 命中 ${hits.length}: ${hits.join(' ') || '无'}`);
}

console.log('');
if (problems.length > 0) {
  console.error(`✗ ${problems.length} 处判据不合格 —— 这些屏的「已进入」是假的，goto 会假到达：`);
  for (const p of problems) console.error(`   ${p}`);
  console.error('\n修法：换一个**只在这一屏成立**的判据（见文件头那 4 个反例），');
  console.error('然后重跑 `node tools/ui-map-real-frontend.mjs "<url>"` 让 JSON 跟上。');
  process.exit(1);
}
console.log(`✓ ${map.screens.length} 屏判据全部唯一（每屏只在自己那一屏成立）`);
