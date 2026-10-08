// ffmpeg / ffprobe location + thin wrappers. Shared by every script.
//
// Cross-platform, and deliberately NOT hardcoded to a version: winget upgrades
// ffmpeg-8.1.1 -> 9.0.2 and any hardcoded path silently breaks mid-project.
import { execFileSync } from 'node:child_process'
import { existsSync, readdirSync } from 'node:fs'
import { join } from 'node:path'

const EXE = process.platform === 'win32' ? '.exe' : ''

function findBin(name) {
  const env = process.env[name === 'ffmpeg' ? 'FFMPEG' : 'FFPROBE']
  if (env && existsSync(env)) return env

  // Unix: just ask PATH (execFileSync resolves it for us) — return the bare name.
  if (process.platform !== 'win32') {
    for (const dir of ['/opt/homebrew/bin', '/usr/local/bin', '/usr/bin']) {
      const c = join(dir, name)
      if (existsSync(c)) return c
    }
    return name
  }

  const roots = [
    join(process.env.LOCALAPPDATA ?? '', 'Microsoft', 'WinGet', 'Packages'),
    join(process.env.LOCALAPPDATA ?? '', 'ms-playwright'),
    'C:\\ffmpeg\\bin',
    'C:\\Program Files\\ffmpeg\\bin',
  ]
  for (const root of roots) {
    if (!existsSync(root)) continue
    for (const dir of readdirSync(root)) {
      if (!dir.toLowerCase().includes('ffmpeg')) continue
      const sub = join(root, dir)
      let inner = []
      try { inner = readdirSync(sub) } catch { inner = ['.'] }
      for (const i of inner) {
        // winget: <pkg>/<ver>/bin/ffmpeg.exe   playwright: <dir>/ffmpeg-win64.exe
        const cands = [
          join(sub, i, 'bin', `${name}${EXE}`),
          join(sub, i, `${name}-win64${EXE}`),
          join(sub, `${name}${EXE}`),
        ]
        for (const c of cands) if (existsSync(c)) return c
      }
    }
  }
  return `${name}${EXE}` // last resort: hope it is on PATH
}

export const FFMPEG = findBin('ffmpeg')
export const FFPROBE = findBin('ffprobe')

/** Run ffmpeg. On failure surface the tail of stderr — the default error output is often empty. */
export function ffmpeg(args, { quiet = true } = {}) {
  try {
    return execFileSync(FFMPEG, ['-y', '-hide_banner', ...(quiet ? ['-loglevel', 'error'] : []), ...args], {
      encoding: 'buffer', maxBuffer: 1 << 28,
    })
  } catch (error) {
    const err = String(error.stderr ?? '')
    const tail = err.split('\n').filter(Boolean).slice(-12).join('\n')
    throw new Error(`ffmpeg failed:\n${tail || error.message}`)
  }
}

/** Media duration in seconds. */
export function duration(file) {
  const out = execFileSync(FFPROBE, [
    '-v', 'error', '-show_entries', 'format=duration',
    '-of', 'default=noprint_wrappers=1:nokey=1', file,
  ], { encoding: 'utf8' })
  return Number.parseFloat(out.trim())
}

/** Full stream summary — used by the verify step.
 *
 * Uses JSON output on purpose: the flat `key=value` form groups entries by section,
 * so `format=duration` appears AFTER the stream blocks and a naive parser assigns it
 * to the last stream. That silently produces `duration NaN`. */
export function probe(file) {
  const out = execFileSync(FFPROBE, [
    '-v', 'error',
    '-show_entries', 'format=duration,size:stream=index,codec_type,codec_name,width,height,r_frame_rate',
    '-of', 'json', file,
  ], { encoding: 'utf8' })
  const j = JSON.parse(out)
  return {
    duration: Number.parseFloat(j.format?.duration ?? 'NaN'),
    size: Number(j.format?.size ?? 0),
    streams: (j.streams ?? []).map((s) => ({
      ...s,
      index: s.index,
      codec_type: s.codec_type,
      codec_name: s.codec_name,
      width: s.width,
      height: s.height,
      r_frame_rate: s.r_frame_rate,
    })),
  }
}
