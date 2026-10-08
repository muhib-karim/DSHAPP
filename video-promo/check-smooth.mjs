// The smoothness metric. Run on EVERY output before showing anyone.
//
// You cannot watch the video; the user can. This converts "does it look choppy"
// into a number. A high duplicate-frame ratio is the exact signature of real-time
// screen recording, where the page ran far below the target FPS during capture.
//
//   node check-smooth.mjs out/video.mp4 [atSeconds] [windowSeconds]
//
//   < 5%   smooth
//   5-20%  mild stutter
//   > 20%  choppy — capture was not deterministic, or the page was far too slow

import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { join, dirname, basename, resolve } from 'node:path'
import { FFMPEG, probe } from './ffmpeg.mjs'

const file = resolve(process.argv[2] ?? '')
if (!file || !existsSync(file)) {
  console.error('usage: node check-smooth.mjs <video.mp4> [atSeconds=8] [windowSeconds=3]')
  process.exit(1)
}
const at = Number(process.argv[3] ?? 8)
const win = Number(process.argv[4] ?? 3)

console.log(`\n${basename(file)}`)
try {
  const info = probe(file)
  for (const s of info.streams) {
    if (s.codec_type === 'video') {
      console.log(`  video  ${s.codec_name} ${s.width}x${s.height} @ ${s.r_frame_rate}`)
      const [n, d] = String(s.r_frame_rate).split('/').map(Number)
      if (d && n / d < 29) console.log(`  \x1b[33m!\x1b[0m frame rate ${s.r_frame_rate} is below 30 — check the render step`)
    }
    if (s.codec_type === 'audio') console.log(`  audio  ${s.codec_name}`)
  }
  console.log(`  duration ${Number(info.duration).toFixed(2)}s`)
} catch (e) {
  console.log(`  (ffprobe failed: ${e.message.split('\n')[0]})`)
}

const tmp = join(dirname(file), '..', 'tmp', 'smooth')
rmSync(tmp, { recursive: true, force: true })
mkdirSync(tmp, { recursive: true })

execFileSync(FFMPEG, [
  '-v', 'error', '-ss', String(at), '-t', String(win), '-i', file,
  '-vf', 'fps=30', '-q:v', '2', join(tmp, 'f%04d.jpg'),
], { stdio: 'inherit' })

const frames = readdirSync(tmp).filter((f) => f.endsWith('.jpg')).sort()
if (frames.length < 2) {
  console.log('  \x1b[31m✗ could not extract frames\x1b[0m\n')
  process.exit(1)
}
const hashes = frames.map((f) => createHash('sha1').update(readFileSync(join(tmp, f))).digest('hex'))
let dup = 0
for (let i = 1; i < hashes.length; i++) if (hashes[i] === hashes[i - 1]) dup++
const pct = (dup / (hashes.length - 1)) * 100

console.log(`  sampled ${at}–${(at + win).toFixed(0)}s: ${frames.length} frames, ${dup} identical to previous (${pct.toFixed(1)}%)`)
if (pct < 5) console.log('  \x1b[32m✓ motion continuous\x1b[0m')
else if (pct < 20) console.log('  \x1b[33m△ mild stutter\x1b[0m')
else console.log('  \x1b[31m✗ CHOPPY — capture was not deterministic, or the page was far too slow\x1b[0m')
console.log('')

rmSync(tmp, { recursive: true, force: true })
process.exit(pct < 20 ? 0 : 1)
