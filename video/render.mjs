// Deterministic frame-by-frame renderer -> clips/<ratio>/<id>.mp4
//
// ===== WHY THIS EXISTS =====
// Real-time screen recording (Playwright recordVideo, OBS, ...) ties output smoothness
// to the page's LIVE frame rate. If the page runs at 4-15 FPS, you record 4-15 FPS of
// content into a 30 FPS container -> visible stutter that no amount of CSS tuning fixes.
//
// Deterministic capture removes the dependency entirely: pause every animation, set
// currentTime = t, let the page render AT that exact timestamp, screenshot. The page can
// be as slow as it likes; the output is still perfect 30 FPS. The only cost is wall time.
//
// ===== USAGE =====
//   node render.mjs                       # all ratios
//   node render.mjs --ratio=16x9
//   node render.mjs --only=s1,a1          # debug one scene
//   node render.mjs --probe               # render only the 1st scene, 3s -> tmp/
//
// Config comes from video.config.mjs in the project root.
import { chromium } from 'playwright'
import { existsSync, mkdirSync, readFileSync, rmSync } from 'node:fs'
import { join, dirname, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { ffmpeg, duration } from './ffmpeg.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const projectRoot = process.cwd()

const cfgPath = join(projectRoot, 'video.config.mjs')
if (!existsSync(cfgPath)) {
  console.error('missing video.config.mjs in the current directory — copy references/VIDEO-CONFIG.example.mjs')
  process.exit(1)
}
const cfg = (await import(pathToFileURL(cfgPath).href)).default
const FPS = cfg.fps ?? 30

const timingPath = join(projectRoot, 'audio', 'timing.json')
if (!existsSync(timingPath)) {
  console.error('missing audio/timing.json — run the TTS step first')
  process.exit(1)
}
const timing = JSON.parse(readFileSync(timingPath, 'utf8'))

const argOnly = process.argv.find((a) => a.startsWith('--only='))?.split('=')[1]
const only = argOnly ? new Set(argOnly.split(',')) : null
const argRatio = process.argv.find((a) => a.startsWith('--ratio='))?.split('=')[1]
const PROBE = process.argv.includes('--probe')

let shots = cfg.shots
let ratios = argRatio ? cfg.ratios.filter((r) => r.id === argRatio) : cfg.ratios
if (PROBE) {
  shots = shots.slice(0, 1)
  ratios = ratios.slice(0, 1)
  console.log(`\n[probe] rendering 1 scene x ${ratios[0].id}, first 3s only`)
}

if (!shots.length) { console.error('no shots matched'); process.exit(1) }

// Subtitle text injected into the page.
const LINES = {}
for (const shot of shots) {
  const t = timing.lines.find((l) => l.index === shot.line)
  if (t) LINES[shot.id] = t.text
}

const pageUrl = pathToFileURL(join(projectRoot, cfg.scenes)).href
const browser = await chromium.launch()
let grand = 0

for (const ratio of ratios) {
  const clipDir = join(projectRoot, PROBE ? 'tmp/probe' : join('clips', ratio.id))
  mkdirSync(clipDir, { recursive: true })

  const page = await browser.newPage({
    viewport: { width: ratio.w, height: ratio.h },
    deviceScaleFactor: 1,
  })
  await page.goto(pageUrl, { waitUntil: 'load' })
  await page.evaluate((l) => { window.__LINES = l }, LINES)
  await page.waitForTimeout(500)          // fonts + animation objects
  const animCountTotal = await page.evaluate(() => document.getAnimations().length)

  console.log(`\n[${ratio.id}] ${ratio.w}x${ratio.h}  (${animCountTotal} animations)`)
  const t0 = Date.now()
  const todo = shots.filter((s) => !only || only.has(s.id))

  for (let i = 0; i < todo.length; i++) {
    const shot = todo[i]
    const line = timing.lines.find((l) => l.index === shot.line)
    const dur = PROBE ? Math.min(3, line.seconds + shot.pad) : line.seconds + shot.pad
    const totalFrames = Math.round(dur * FPS)

    const frameDir = join(projectRoot, 'tmp', 'frames', ratio.id, shot.id)
    rmSync(frameDir, { recursive: true, force: true })
    mkdirSync(frameDir, { recursive: true })

    await page.evaluate((id) => window.__show(id), shot.id)   // switch -> restarts CSS animations
    await page.waitForTimeout(140)                            // let the scene settle one frame
    const animCount = await page.evaluate(() => document.getAnimations().length)

    for (let f = 0; f < totalFrames; f++) {
      const t = f / FPS
      await page.evaluate(({ t }) => {
        for (const a of document.getAnimations()) { a.pause(); a.currentTime = t * 1000 }
        const el = document.querySelector('.scene.on')
        if (el) window.__seek(el.id, t)
      }, { t })

      // NEVER pass animations:'disabled' — Playwright fast-forwards finite animations to
      // their end and resets infinite ones, silently overriding every __seek above.
      // Symptom: every frame identical (all hashes equal).
      //
      // JPEG q92 measures ~1.5x faster than PNG with no visible difference.
      await page.screenshot({
        path: join(frameDir, `f${String(f).padStart(4, '0')}.jpg`),
        type: 'jpeg', quality: 92,
      })
    }

    const clip = join(clipDir, `${shot.id}.mp4`)
    ffmpeg([
      '-framerate', String(FPS),
      '-i', join(frameDir, 'f%04d.jpg'),
      '-frames:v', String(totalFrames),      // pin the exact frame count
      '-c:v', 'libx264', '-preset', 'medium', '-crf', '18',
      '-pix_fmt', 'yuv420p', '-r', String(FPS), '-an',
      clip,
    ])

    const elapsed = (Date.now() - t0) / 1000
    const fps = grand / elapsed || 0
    process.stdout.write(
      `\r  ${shot.id.padEnd(4)} ${String(totalFrames).padStart(4)}f ${dur.toFixed(2)}s ` +
      `(${animCount} anim) -> ${duration(clip).toFixed(2)}s  ${i + 1}/${todo.length}  ` +
      `${elapsed.toFixed(0)}s  ${fps.toFixed(1)} f/s   `,
    )
    grand += totalFrames
  }
  console.log('')
  await page.close()
}

await browser.close()
rmSync(join(projectRoot, 'tmp', 'frames'), { recursive: true, force: true })
console.log(`\n${grand} frames -> ${join(projectRoot, PROBE ? 'tmp/probe' : 'clips')}\n`)
if (PROBE) console.log('Probe done. WATCH IT before committing to a full render.\n')
