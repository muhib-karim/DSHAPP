// Narration script -> audio/vo-NN.mp3 + audio/timing.json
//
//   node tts.mjs
//
// Scene durations are DERIVED from these audio lengths (see video.config.mjs), so
// picture and voice stay locked. Never hardcode durations.
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { FFPROBE } from './ffmpeg.mjs'

const projectRoot = process.cwd()
const cfg = (await import(new URL('file:///' + join(projectRoot, 'video.config.mjs').replace(/\\/g, '/')).href)).default
const VOICE = cfg.voice ?? 'zh-CN-XiaoxiaoNeural'
const RATE = cfg.rate ?? '+10%'
const SCRIPT = cfg.script

const audioDir = join(projectRoot, 'audio')
mkdirSync(audioDir, { recursive: true })

// edge-tts shells out to python; a proxy set in your shell is NOT inherited reliably.
// Export it explicitly or the child gets ClientConnectorError / WinError 64.
const env = { ...process.env }
for (const k of ['HTTPS_PROXY', 'HTTP_PROXY', 'ALL_PROXY']) {
  if (!env[k] && env.HTTP_PROXY) env[k] = env.HTTP_PROXY
}

function mediaSeconds(file) {
  const out = execFileSync(FFPROBE, [
    '-v', 'error', '-show_entries', 'format=duration',
    '-of', 'default=noprint_wrappers=1:nokey=1', file,
  ], { encoding: 'utf8' })
  return Number.parseFloat(out.trim())
}

/**
 * edge-tts resets connections intermittently (WinError 64 / ConnectionResetError).
 * Retry with escalating backoff, and delete the file before each attempt — a 0-byte
 * MP3 left behind by a failed attempt poisons ffprobe with
 * "Failed to find two consecutive MPEG audio frames", and that failure then surfaces
 * much later, far from its cause.
 */
async function synthesize(text, out) {
  const MAX = 8
  for (let i = 0; i < MAX; i++) {
    rmSync(out, { force: true })
    try {
      execFileSync('edge-tts', [
        '--voice', VOICE, '--rate', RATE, '--text', text, '--write-media', out,
      ], { stdio: ['ignore', 'ignore', 'pipe'], env, timeout: 90_000 })

      // validate: non-trivial size AND actually decodable
      if (!existsSync(out) || statSync(out).size < 1024) throw new Error('file too small')
      const d = mediaSeconds(out)
      if (!Number.isFinite(d) || d <= 0.2) throw new Error(`bad duration ${d}`)
      return d
    } catch (error) {
      if (i === MAX - 1) throw new Error(`TTS failed after ${MAX} attempts: ${String(error.message).split('\n')[0]}`)
      const wait = Math.min(8000, 1000 + i * 1500)
      process.stdout.write(`\r    retry ${i + 1}/${MAX} in ${(wait / 1000).toFixed(1)}s...   `)
      await new Promise((r) => setTimeout(r, wait))
    }
  }
}

const lines = []
let total = 0
console.log(`\nvoice=${VOICE} rate=${RATE}  ${SCRIPT.length} lines\n`)

for (let i = 0; i < SCRIPT.length; i++) {
  const index = i + 1
  const rel = `audio/vo-${String(index).padStart(2, '0')}.mp3`
  const out = join(projectRoot, rel)
  const text = SCRIPT[i]

  const seconds = await synthesize(text, out)
  total += seconds
  lines.push({ index, text, seconds, file: rel })
  console.log(`  ${String(index).padStart(2)}  ${seconds.toFixed(2)}s  ${text.slice(0, 34)}`)
}

const timing = { voice: VOICE, rate: RATE, total: Number(total.toFixed(2)), lines }
writeFileSync(join(audioDir, 'timing.json'), JSON.stringify(timing, null, 2))

console.log(`\ntotal ${total.toFixed(2)}s -> audio/timing.json`)
console.log(`Plus transition overlap and per-scene pads, expect the final video around ` +
  `${(total + cfg.shots.reduce((a, s) => a + (s.pad ?? 0), 0) - (cfg.shots.length - 1) * (cfg.transition ?? 0.45)).toFixed(1)}s\n`)
