/**
 * 把 `tools/ui-map-real-frontend.json`（17 屏图谱）转成插件内建的屏幕表。
 *
 * ## 为什么要有这一步
 *
 * 图谱是**在 PC 上用无头 Chrome 对真实前端实测出来的**，而模型跑在**手机上的
 * Node 进程**里 —— 它读不到这个仓库，也跑不了那个生成器。所以「每个按钮在哪、
 * 怎么走到那一屏」这套知识必须在**打包时**烘进插件，否则真机上的模型每次都要
 * 从零试错点击，而试错的代价是真实界面被搅乱。
 *
 * 产物 `mobile-ui-plugin/dsh-android-uiagent/lib/screens.js` 由
 * `uiagent-tool.zip` 一起打进 APK，每次启动解包到 `$DSH_HOME/uiagent-tool/`。
 *
 * ## 取舍：丢掉 `sel`，只留「文本 + 类名 + 坐标」
 *
 * 图谱里每个元素都带一个 `sel`，但它是 `nth-of-type` 链
 * （`div > div > button:nth-of-type(1) > …`），**React 重渲染后会漂** ——
 * 图谱生成器自己的注释就警告过「元素顺序会变」。它占了 23 KB / 31 KB 的九成，
 * 换来的却是一个不可靠的定位条件。
 *
 * 留下的是三样各有分工的东西：
 *
 * | 字段 | 用途 | 稳定性 |
 * |---|---|---|
 * | `text` | **首选定位条件** —— 直接喂给 `ui{action:"tap", text}` | 与构建无关，最稳 |
 * | `cls`  | CSS Module 哈希类名，用来区分**同名**按钮（如两处「关闭」） | 随前端构建变，仅辅助 |
 * | `x,y` | CSS 中心坐标，给「确认识别到了同一个按钮」用 | 随视口变，仅参考 |
 *
 * ⚠️ **坐标是参考值，不是定位条件。** 它是在 360×780 / dpr 3 的视口下量的；
 *    换了设备（或字号设置变了）就不一样。运行时**必须优先用 `text` 定位**，
 *    坐标只用来核对「图谱里的按钮和屏幕上的按钮是不是同一个」。
 *
 * ## 校验：坏数据必须在打包时炸掉，不能带进 APK
 *
 * 内建表的每一屏都必须 `verified`（图谱实测确认真的进去了）且每一步 `ok`。
 * 少一条就退出非零 —— 一份「有一屏是假的」的图谱比没有图谱更糟：模型会照它开车。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SRC = path.join(ROOT, 'tools', 'ui-map-real-frontend.json');
const OUT = path.join(ROOT, 'mobile-ui-plugin', 'dsh-android-uiagent', 'lib', 'screens.js');

const map = JSON.parse(fs.readFileSync(SRC, 'utf8'));

if (!Array.isArray(map.screens) || map.screens.length === 0) {
  console.error(`✗ 图谱里没有 screens（${SRC}）`);
  process.exit(1);
}

const problems = [];
const screens = [];

for (const s of map.screens) {
  if (s.verified !== true) problems.push(`${s.id}: verified=${s.verified}（这一屏没被实测确认进入）`);
  if (s.entered !== true) problems.push(`${s.id}: entered=${s.entered}`);
  for (const step of s.enter ?? []) {
    if (step.ok !== true) problems.push(`${s.id}: 进入步骤「${step.target}」ok=${step.ok}（${step.error}）`);
  }
  if (!s.expect) problems.push(`${s.id}: 缺少 expect（没有「真的进去了吗」的判据，goto 就成了盲开车）`);

  const els = (s.elements ?? [])
    // ⚠️ 被遮挡 / 屏幕外的元素**不进内建表**：它们在真机上点不动，
    //    出现在按钮表里只会诱使模型去点。真机上确实需要时，用 dump 现查。
    .filter((e) => (e.text || e.testid) && !e.covered && !e.offscreen)
    // 形状：`[定位文本, 类名首段, CSS x, CSS y, role]`
    .map((e) => [
      e.text || `#${e.testid}`,
      (e.cls || '').split(' ')[0],
      Math.round(e.center.x),
      Math.round(e.center.y),
      e.role || e.tag,
    ]);

  screens.push({
    id: s.id,
    title: s.title,
    // 进入路线：`[{text|aria|sel}, …]`，与 `ui{action:"tap"}` 的定位参数同构，
    // 可以原样喂回去 —— 这正是内建表既当坐标表又当路线图的原因。
    enter: (s.enter ?? []).map((step) => step.spec),
    expect: s.expect,
    els,
  });
}

if (problems.length > 0) {
  console.error(`✗ 图谱有 ${problems.length} 处问题，拒绝内建：`);
  for (const p of problems) console.error(`   ${p}`);
  process.exit(1);
}

const total = screens.reduce((a, s) => a + s.els.length, 0);

const body = `/**
 * 内建的屏幕表 —— **自动生成，请勿手改**。
 *
 * 来源: \`tools/ui-map-real-frontend.json\`（由 \`tools/ui-map-real-frontend.mjs\`
 * 对**真实前端**实测生成，${screens.length} 屏 / ${total} 个可点按钮）。
 * 重新生成: \`node tools/build-uiagent-screens.mjs\`。
 *
 * 生成时的视口: ${map.viewport?.w}×${map.viewport?.h} CSS / dpr ${map.viewport?.dpr}
 * 探针版本: ${map.probeVersion}
 *
 * 每屏的形状：
 * \`\`\`js
 * { id, title, enter: [{text|aria}], expect: {text|aria}, els: [[定位文本, 类名, cssX, cssY, role]] }
 * \`\`\`
 *
 * ⚠️ \`els\` 里的坐标是**参考值**，随设备/视口/字号变化 ——
 *    定位永远优先用 \`[0]\` 那个文本，坐标只用来核对是不是同一个按钮。
 * ⚠️ \`enter\` 的每一步都能**原样**喂给 \`ui{action:"tap", ...}\`。
 * ⚠️ 屏的顺序**有语义**：这个前端会持久化「当前会话」「右侧边栏的内容页」，
 *    两者都是**单向**的（进去了就回不去）。\`goto\` 靠「重载回基态」规避，
 *    但**改这个数组的顺序前**要先读 \`ui-map-real-frontend.mjs\` 顶部那段说明。
 */
export const ATLAS = ${JSON.stringify({ viewport: map.viewport, probeVersion: map.probeVersion, screens })};

/** 按 id 或标题查一屏（宽松匹配：忽略大小写、允许标题子串）。 */
export function findScreen(ref) {
  if (ref == null || ref === '') return null;
  const want = String(ref).trim().toLowerCase();
  for (const s of ATLAS.screens) if (s.id === want) return s;
  for (const s of ATLAS.screens) if (s.title.toLowerCase() === want) return s;
  for (const s of ATLAS.screens) if (s.title.toLowerCase().includes(want)) return s;
  return null;
}
`;

fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, body, 'utf8');

console.log(`✓ 已写出 ${path.relative(ROOT, OUT)}`);
console.log(`  ${screens.length} 屏 · ${total} 个按钮 · ${Buffer.byteLength(body)} 字节`);
for (const s of screens) console.log(`    ${s.id.padEnd(18)} ${String(s.els.length).padStart(2)} 个按钮 · 路线 ${s.enter.length} 步`);
