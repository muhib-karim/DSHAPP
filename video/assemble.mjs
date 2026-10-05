// clips/<ratio>/<id>.mp4 + audio/vo-NN.mp3 -> out/<name>-<ratio>.mp4
//
// Two independent timelines:
//   video: normalize each clip to its exact duration, then xfade-chain
//   audio: adelay each voice line to its scene start, then amix
// They are joined only at the final mux.
//
//   node assemble.mjs                      # all ratios
//   node assemble.mjs --ratio=16x9
//   node assemble.mjs --ratio=16x9 --dry   # print the filtergraph, render nothing
import { existsSync, mkdirSync, readFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { ffmpeg, duration, probe } from './ffmpeg.mjs'

const projectRoot = process.cwd()
const cfg = (await import(new URL('file:///' + join(projectRoot, 'video.config.mjs').replace(/\\/g, '/')).href)).default
const FPS = cfg.fps ?? 30
const TRANSITION = cfg.transition ?? 0.45      // xfade duration
const LEAD = cfg.audioLead ?? 0.18             // push speech off the cut by a hair

const argRatio = process.argv.find((a) => a.startsWith('--ratio='))?.split('=')[1]
const DRY = process.argv.includes('--dry')
const ratios = argRatio ? cfg.ratios.filter((r) => r.id === argRatio) : cfg.ratios

const timing = JSON.parse(readFileSync(join(projectRoot, 'audio', 'timing.json'), 'utf8'))
mkdirSync(join(projectRoot, 'out'), { recursive: true })

for (const ratio of ratios) {
  const clipDir = join(projectRoot, 'clips', ratio.id)
  console.log(`\n[${ratio.id}]`)

  // ---- durations: derived from audio, never hardcoded ----
  const clips = []
  for (const shot of cfg.shots) {
    const file = join(clipDir, `${shot.id}.mp4`)
    if (!existsSync(file)) { console.error(`  missing clip ${file} — run render.mjs first`); process.exit(1) }
    const line = timing.lines.find((l) => l.index === shot.line)
    clips.push({ ...shot, file, target: line.seconds + shot.pad, line })
  }

  // ---- video: xfade chain ----
  // offset_i = sum(durations before i) - i * TRANSITION
  const inputs = clips.flatMap((c) => ['-i', c.file])
  const parts = []
  let acc = 0
  for (let i = 0; i < clips.length; i++) {
    const c = clips[i]
    // Normalize every clip to its exact target length first, otherwise the xfade
    // offsets drift and audio/picture desync accumulates down the chain.
    parts.push(`[${i}:v]trim=0:${c.target.toFixed(3)},setpts=PTS-STARTPTS,` +
      `scale=${ratio.w}:${ratio.h}:force_original_aspect_ratio=decrease,` +
      `pad=${ratio.w}:${ratio.h}:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=${FPS}[v${i}]`)
  }
  // Chain from the NORMALIZED labels [v0], not raw [0:v] — using the raw input here
  // would consume input 0 twice and leave [v0] dangling ("output 0 unconnected").
  let prev = 'v0'
  for (let i = 1; i < clips.length; i++) {
    acc += clips[i - 1].target
    const offset = acc - i * TRANSITION
    const out = i === clips.length - 1 ? 'vout' : `x${i}`
    parts.push(`[${prev}][v${i}]xfade=transition=fade:duration=${TRANSITION}:offset=${offset.toFixed(3)}[${out}]`)
    prev = out
  }
  const vlabel = clips.length === 1 ? 'v0' : 'vout'

  // ---- audio: place each line at its scene start ----
  // input order is: [0..n-1] clips, [n..2n-1] voice lines, [2n] bgm (if any)
  const audioInputs = []
  const alabels = []
  let t = 0
  for (let i = 0; i < clips.length; i++) {
    const c = clips[i]
    const vo = join(projectRoot, c.line.file)
    const ms = Math.round((t + LEAD) * 1000)
    audioInputs.push('-i', vo)
    alabels.push(`[${clips.length + i}:a]adelay=${ms}|${ms}[a${i}]`)
    t += c.target - (i < clips.length - 1 ? TRANSITION : 0)
  }
  const amix = `${clips.map((_, i) => `[a${i}]`).join('')}amix=inputs=${clips.length}:normalize=0[aout]`
  // normalize=0 is REQUIRED — the default attenuates each input by 1/n and the mix comes out quiet.

  const bgm = cfg.bgm && existsSync(join(projectRoot, cfg.bgm))
    ? ['-i', join(projectRoot, cfg.bgm)]
    : []

  const graph = [...parts, ...alabels, amix].join(';')
  const out = join(projectRoot, 'out', `${cfg.name}-${ratio.id}.mp4`)

  if (DRY) {
    console.log('  video inputs :', clips.length)
    console.log('  filtergraph  :\n' + graph.split(';').map((s) => '    ' + s).join('\n'))
    console.log('  -> would write', out)
    continue
  }

  const args = []
  args.push(...inputs, ...audioInputs)
  let audioMap = '[aout]'
  let finalGraph = graph
  if (bgm.length) {
    args.push(...bgm)
    // input indices: [0..n-1] video clips, [n..2n-1] voice lines, [2n] bgm
    const bgmIdx = clips.length * 2
    // duck the music way down; narration must stay intelligible
    finalGraph = `${graph};[${bgmIdx}:a]volume=0.22[bg];[aout][bg]amix=inputs=2:normalize=0[a2]`
    audioMap = '[a2]'
  }
  args.push('-filter_complex', finalGraph)
  args.push(
    '-map', `[${vlabel}]`, '-map', audioMap,
    '-c:v', 'libx264', '-preset', 'medium', '-crf', '18', '-pix_fmt', 'yuv420p', '-r', String(FPS),
    '-c:a', 'aac', '-b:a', '192k',
    '-shortest', '-movflags', '+faststart',
    out,
  )

  console.log('  muxing...')
  ffmpeg(args)

  const info = probe(out)
  const vs = info.streams.find((s) => s.codec_type === 'video')
  const as = info.streams.find((s) => s.codec_type === 'audio')
  console.log(`  -> ${out}`)
  console.log(`     ${vs.width}x${vs.height} @${vs.r_frame_rate}  audio=${as ? as.codec_name : 'NONE'}  ${Number(info.duration).toFixed(2)}s`)
  if (!as) console.log('     \x1b[31m! no audio stream\x1b[0m')
}
console.log('')
