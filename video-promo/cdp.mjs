// 原生 CDP 客户端（Android WebView 不支持 Playwright 的 connectOverCDP）。一次性脚本。
const list = await (await fetch('http://127.0.0.1:9222/json/list')).json()
const target = list.find((t) => t.url.includes('127.0.0.1:3080'))
if (!target) { console.error('no webview target'); process.exit(1) }

const ws = new WebSocket(target.webSocketDebuggerUrl)
let id = 0
const pending = new Map()

ws.addEventListener('message', (ev) => {
  const msg = JSON.parse(ev.data)
  if (msg.id && pending.has(msg.id)) { pending.get(msg.id)(msg); pending.delete(msg.id) }
})

const send = (method, params = {}) =>
  new Promise((res) => { const i = ++id; pending.set(i, res); ws.send(JSON.stringify({ id: i, method, params })) })

await new Promise((r) => ws.addEventListener('open', r))
await send('Runtime.enable')

const evalJs = async (expr) => {
  const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true })
  if (r.result?.exceptionDetails) return { err: r.result.exceptionDetails.text }
  return r.result?.result?.value
}

const mode = process.argv[2] || 'probe'

if (mode === 'probe') {
  const items = await evalJs(`
    (() => {
      const out = []
      for (const el of document.querySelectorAll('button,[role="button"],a,[class*="nav"],[class*="tab"],[class*="icon"]')) {
        const r = el.getBoundingClientRect()
        if (r.width < 6 || r.height < 6 || r.width > 300) continue
        const t = (el.textContent || '').trim().slice(0, 20)
        const label = el.getAttribute('aria-label') || el.getAttribute('title') || ''
        const cls = (el.className || '').toString().slice(0, 44)
        if (!t && !label && !cls) continue
        out.push({ t, label, cls, x: Math.round(r.x + r.width/2), y: Math.round(r.y + r.height/2) })
      }
      return out
    })()
  `)
  console.log(JSON.stringify(items, null, 1))
} else if (mode === 'click') {
  const sel = process.argv[3]
  const r = await evalJs(`
    (() => {
      const els = [...document.querySelectorAll(${JSON.stringify(sel)})]
      if (!els.length) return 'not found'
      els[0].click()
      return 'clicked ' + els.length
    })()
  `)
  console.log(r)
} else if (mode === 'text') {
  const r = await evalJs(`document.body.innerText.slice(0, ${process.argv[3] || 2000})`)
  console.log(r)
}

ws.close()
