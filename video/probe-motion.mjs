// probe-motion.mjs — 探针：测「每屏在入场结束之后是否还在动」。
//
// 为什么需要它：上一版每屏的入场动画 1.2s 就结束，而每屏长 9–10s —— 约 8.5s
// 完全静止，全片近 110 秒是定格画面，用户的原话是「动态效果比较少，没有展现出
// 比较好的交互效果」。这个病用肉眼看单帧是看不出来的（每帧都很好看），必须量化。
//
// 指标：在 t >= 1.5s（入场必然已结束）之后采样若干时间点，逐帧存 PNG 并算 sha256。
//   - 像素完全相同 ⇒ PNG 字节完全相同（PNG 无损，Chromium 同状态渲染确定性）
//   - 唯一哈希数 == 采样数 ⇒ 该屏一直在动
//   - 唯一哈希数 == 1        ⇒ 该屏是静止画面（PPT）
//
// 用法：
//   $env:NODE_PATH = "C:\Users\XIAOPAN\node_modules"
//   node probe-motion.mjs [--only=s2,s3]
//
// 退出码 0 = 全部通过，1 = 有屏静止。

import { chromium } from 'playwright'
import { pathToFileURL } from 'node:url'
import { createHash } from 'node:crypto'
import { mkdirSync, readFileSync, rmSync } from 'node:fs'
import { join } from 'node:path'
import cfg from './video.config.mjs'

const PROJECT = process.cwd()
// ⚠️ 采样点必须与常见动画周期「不共振」。第一版用了 1.5/3/4.5/6/7.5/9/10.5 ——
// 全是 1.5 的倍数，而 flow-y 周期正好 1.5s、flow-x 1.2s（也是 1.5 的倍数），
// 于是每个采样点都落在同一相位，流动点回到原点、像素完全相同，
// 探针把「一直在动」的场景误判成静止。改用无理数步长错开相位。
const TIMES = [1.6, 2.37, 3.19, 4.61, 5.43, 6.71, 7.88, 9.13, 10.31]
// 至少要这么多个「互不相同的帧」才算这一屏在动。采样点会被场景时长裁掉，
// 所以用比例而不是绝对值。
const MIN_RATIO = 0.8

const onlyArg = process.argv.find((a) => a.startsWith('--only='))
const only = onlyArg ? new Set(onlyArg.slice(7).split(',').map((s) => s.trim())) : null

const timing = JSON.parse(readFileSync(join(PROJECT, 'audio', 'timing.json'), 'utf8'))
const durOf = (shot) => {
  const line = timing.lines.find((l) => l.index === shot.line)
  return (line?.seconds ?? 0) + shot.pad
}

const outDir = join(PROJECT, 'tmp', 'motion')
rmSync(outDir, { recursive: true, force: true })
mkdirSync(outDir, { recursive: true })

const browser = await chromium.launch()
const page = await browser.newPage({ viewport: { width: cfg.ratios[0].w, height: cfg.ratios[0].h } })
await page.goto(pathToFileURL(join(PROJECT, cfg.scenes)).href, { waitUntil: 'load' })
// 与 render.mjs 逐字一致：key 是 shot.id，不是 `s${index}`
const LINES = {}
for (const shot of cfg.shots) {
  const t = timing.lines.find((l) => l.index === shot.line)
  if (t) LINES[shot.id] = t.text
}
await page.evaluate((l) => { window.__LINES = l }, LINES)
await page.waitForTimeout(500)

const shots = cfg.shots.filter((s) => !only || only.has(s.id))
const rows = []
let failed = 0

for (const shot of shots) {
  const dur = durOf(shot)
  const times = TIMES.filter((t) => t < dur - 0.2)

  await page.evaluate((id) => window.__show(id), shot.id)
  await page.waitForTimeout(140)

  const hashes = []
  for (let i = 0; i < times.length; i++) {
    const t = times[i]
    await page.evaluate(({ t }) => {
      for (const a of document.getAnimations()) { a.pause(); a.currentTime = t * 1000 }
      const el = document.querySelector('.scene.on')
      if (el) window.__seek(el.id, t)
    }, { t })
    const buf = await page.screenshot({ type: 'png' })
    hashes.push(createHash('sha256').update(buf).digest('hex').slice(0, 8))
    // 全部采样帧都落盘：幅度分析（相邻帧差）交给 analyze-motion.py，
    // 它比「哈希是否相同」更能分辨「真动效」与「只是背景漂移」。
    await page.screenshot({ path: join(outDir, `${shot.id}-f${i}-t${t}.png`) })
  }

  const uniq = new Set(hashes).size
  const ratio = times.length ? uniq / times.length : 0
  const ok = ratio >= MIN_RATIO
  if (!ok) failed++
  rows.push({ id: shot.id, dur, n: times.length, uniq, ratio, ok })
}

await browser.close()

console.log('\n  屏    时长   采样  不同帧  比例   判定')
console.log('  ' + '─'.repeat(46))
for (const r of rows) {
  console.log(
    `  ${r.id.padEnd(4)} ${r.dur.toFixed(2).padStart(6)}s ${String(r.n).padStart(4)} ` +
    `${String(r.uniq).padStart(6)} ${(r.ratio * 100).toFixed(0).padStart(5)}%   ${r.ok ? '在动' : '★ 静止'}`,
  )
}
console.log(`\n  ${rows.length - failed}/${rows.length} 屏通过（门槛：入场后不同帧比例 ≥ ${MIN_RATIO * 100}%）`)
console.log(`  样张：tmp/motion/`)

process.exit(failed ? 1 : 0)
