/**
 * ui-probe.js 的行为验收 —— 在**真实 Chrome 的真实 DOM** 上跑，不是 mock。
 *
 * 为什么不 mock：探针的全部价值就是「在真浏览器里点得中、React 感知得到」。
 * 用 jsdom 之类的假 DOM 验它，等于验了自己写的那份假 DOM。
 *
 * 关键一项是受控输入的模拟（scene 里那个 `installReactTracker`）：
 * React 会在元素**实例**上定义 value 的 get/set 拦截器，把「上一轮的值」
 * 存在闭包里，靠 `currentValue !== node.value` 判断要不要触发 onChange。
 * 所以直接 `el.value = x` 会同时更新拦截器里的记录 ⇒ React 认为没变化 ⇒
 * onChange 不触发 ⇒ 界面上的输入框看着有字、但组件状态是空的。
 * 正解是用**原型上的原始 setter** 写、再派发 input 事件 —— 本脚本验证的正是这条。
 *
 * 用法：node tools/ui-probe-verify.mjs [chrome 路径]
 * 退出码 0 = 全部通过。
 */

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';

const PROBE = path.resolve('app/src/main/assets/ui-probe.js');
const CHROME = process.argv[2]
	|| 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const PORT = 9333;

// ────────────────────────────────────────────────────────────────────
// 测试场景：一个与 DSH GUI 同构的 DOM
// ────────────────────────────────────────────────────────────────────
const SCENE = `<!doctype html>
<html><head><meta charset="utf-8"><title>ui-probe verify</title>
<style>
  body { margin: 0; font: 14px sans-serif; }
  #scroll { height: 100px; overflow-y: auto; border: 1px solid #ccc; }
  #scrollInner { height: 600px; }
  button, [role="button"] { padding: 8px; }
  #hiddenBtn { display: none; }
</style></head>
<body>
<div id="app">
  <header>
    <button id="btnNew" data-testid="btn-new" aria-label="新建会话">＋</button>
    <div role="button" tabindex="0" id="btnMenu">菜单</div>
  </header>
  <div id="scroll"><div id="scrollInner">长内容</div></div>
  <div id="composer">
    <input id="msg" placeholder="输入消息">
    <div role="button" id="btnSend">发送</div>
  </div>
  <button id="hiddenBtn">隐藏的按钮</button>
  <button id="disabledBtn" disabled>禁用的按钮</button>
</div>
<script>
  // 事件记录器：tap/key 的断言靠它，不靠"看起来动了"
  window.__events = [];
  ['pointerdown','mousedown','pointerup','mouseup','click','input','change','keydown','keypress','keyup']
    .forEach(function (t) {
      document.addEventListener(t, function (e) {
        window.__events.push({ type: t, id: (e.target && e.target.id) || '', key: e.key || null });
      }, true);
    });

  /**
   * 精确复刻 React 的 value 追踪机制。
   * 见文件头注释 —— 这是本次验证里最重要的一项。
   */
  window.__reactState = { value: '', onChangeCalls: 0 };
  window.installReactTracker = function (node) {
    var proto = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
    var lastValue = null;                      // React 存在闭包里的"上一轮值"
    Object.defineProperty(node, 'value', {
      configurable: true,
      get: function () { return proto.get.call(this); },
      set: function (v) { lastValue = '' + v; proto.set.call(this, v); }
    });
    // React 的 inputValueTracking：getValue() 给"上一轮值"，node.value 走上面的 getter
    node.__getTracked = function () { return lastValue; };
    node.__markTracked = function () { lastValue = proto.get.call(node); };
    node.addEventListener('input', function () {
      // React 的判据：tracked !== node.value ⇒ 认为用户改了 ⇒ 触发 onChange
      if (node.__getTracked() !== node.value) {
        window.__reactState.onChangeCalls++;
        window.__reactState.value = node.value;
        node.__markTracked();
      }
    });
  };
  window.installReactTracker(document.getElementById('msg'));

  // 遮挡用：一个覆盖全屏的遮罩，默认不显示
  window.showMask = function () {
    var m = document.createElement('div');
    m.id = 'mask';
    m.style.cssText = 'position:fixed;inset:0;background:rgba(0,0,0,.3);z-index:9999';
    document.body.appendChild(m);
  };
  window.hideMask = function () {
    var m = document.getElementById('mask');
    if (m) m.parentNode.removeChild(m);
  };
</script>
</body></html>`;

// ────────────────────────────────────────────────────────────────────
// CDP 极简客户端
// ────────────────────────────────────────────────────────────────────
class Cdp {
	constructor(ws) { this.ws = ws; this.id = 0; this.pending = new Map(); this.events = []; }

	static async connect(port) {
		let list = null;
		for (let i = 0; i < 60; i++) {
			try {
				const r = await fetch(`http://127.0.0.1:${port}/json/list`);
				list = await r.json();
				if (Array.isArray(list) && list.some((t) => t.type === 'page')) break;
			} catch { /* 还没起来 */ }
			await new Promise((r) => setTimeout(r, 250));
		}
		const target = list?.find((t) => t.type === 'page');
		if (!target) throw new Error('找不到 Chrome 的 page target');
		const ws = new WebSocket(target.webSocketDebuggerUrl);
		const cdp = new Cdp(ws);
		await new Promise((res, rej) => {
			ws.addEventListener('open', res, { once: true });
			ws.addEventListener('error', rej, { once: true });
		});
		ws.addEventListener('message', (ev) => {
			const msg = JSON.parse(ev.data);
			if (msg.id !== undefined) {
				const p = cdp.pending.get(msg.id);
				if (p) {
					cdp.pending.delete(msg.id);
					if (msg.error) p.reject(new Error(JSON.stringify(msg.error)));
					else p.resolve(msg.result);
				}
			} else {
				cdp.events.push(msg);
			}
		});
		return cdp;
	}

	send(method, params = {}) {
		const id = ++this.id;
		return new Promise((resolve, reject) => {
			this.pending.set(id, { resolve, reject });
			this.ws.send(JSON.stringify({ id, method, params }));
		});
	}

	/** 求值一个表达式，返回它按值序列化后的结果。 */
	async eval(expression) {
		const r = await this.send('Runtime.evaluate', {
			expression, returnByValue: true, awaitPromise: true,
		});
		if (r.exceptionDetails) {
			throw new Error('页面抛异常: '
				+ (r.exceptionDetails.exception?.description ?? r.exceptionDetails.text));
		}
		return r.result.value;
	}

	async navigate(url) {
		const before = this.events.length;
		await this.send('Page.navigate', { url });
		for (let i = 0; i < 120; i++) {
			if (this.events.slice(before).some((e) => e.method === 'Page.loadEventFired')) return;
			await new Promise((r) => setTimeout(r, 100));
		}
		throw new Error('页面加载超时');
	}
}

// ────────────────────────────────────────────────────────────────────
// 断言
// ────────────────────────────────────────────────────────────────────
let pass = 0;
const failures = [];
function check(name, cond, detail) {
	if (cond) { pass++; console.log(`  ✓ ${name}`); }
	else {
		failures.push(name);
		console.log(`  ✗ ${name}${detail === undefined ? '' : ` — ${JSON.stringify(detail)}`}`);
	}
}

async function main() {
	const probeSrc = fs.readFileSync(PROBE, 'utf8');
	const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'uiprobe-'));
	const scenePath = path.join(dir, 'scene.html');
	fs.writeFileSync(scenePath, SCENE, 'utf8');
	const userDir = path.join(dir, 'profile');
	const fileUrl = 'file:///' + scenePath.replace(/\\/g, '/');

	const chrome = spawn(CHROME, [
		'--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
		`--remote-debugging-port=${PORT}`, `--user-data-dir=${userDir}`,
		'--window-size=420,900', '--allow-file-access-from-files', 'about:blank',
	], { stdio: 'ignore' });

	let cdp;
	try {
		cdp = await Cdp.connect(PORT);
		await cdp.send('Page.enable');
		await cdp.send('Runtime.enable');
		await cdp.navigate(fileUrl);

		// 探针调用器：与原生侧完全同构 —— (`(源码)(cmd JSON)`)
		const call = (cmd) => cdp.eval(`(${probeSrc})(${JSON.stringify(cmd)})`);

		console.log('\n── ping ──────────────────────────────────────');
		const ping = await call({ action: 'ping' });
		check('ping 返回 ok', ping.ok === true, ping);
		check('ping 报告了可交互元素数量', typeof ping.interactive === 'number' && ping.interactive > 0, ping.interactive);
		check('ping 报告了视口', ping.viewport && ping.viewport.w > 0, ping.viewport);
		check('探针已挂到 window 上（幂等装填）', await cdp.eval('typeof window.__DSH_UI_PROBE__') === 'object');

		console.log('\n── dump ──────────────────────────────────────');
		const dump = await call({ action: 'dump' });
		check('dump 返回 ok', dump.ok === true, dump.error);
		const els = dump.elements ?? [];
		check('dump 找到了元素', els.length > 0, els.length);
		const byId = (id) => els.find((e) => e.sel === '#' + id || (e.sel ?? '').includes('#' + id));
		const btnSend = els.find((e) => e.text === '发送');
		check('找到了文本为「发送」的 role=button（React 风格按钮）', !!btnSend, els.map((e) => e.text));
		check('role 字段是 button', btnSend?.role === 'button', btnSend?.role);
		check('元素带 CSS 中心坐标', typeof btnSend?.center?.x === 'number' && typeof btnSend?.center?.y === 'number', btnSend?.center);
		check('元素带尺寸', btnSend?.rect?.w > 0 && btnSend?.rect?.h > 0, btnSend?.rect);
		check('元素带 CSS 选择器', typeof btnSend?.sel === 'string' && btnSend.sel.length > 0, btnSend?.sel);
		check('隐藏按钮默认不出现在 dump 里', !els.some((e) => e.sel === '#hiddenBtn'));
		const dumpAll = await call({ action: 'dump', includeHidden: true });
		check('includeHidden:true 时隐藏按钮出现', (dumpAll.elements ?? []).some((e) => (e.sel ?? '').includes('hiddenBtn')));
		const dumpFiltered = await call({ action: 'dump', filter: '发送' });
		check('filter 能收窄结果', (dumpFiltered.elements ?? []).length === 1 && dumpFiltered.elements[0].text === '发送',
			(dumpFiltered.elements ?? []).map((e) => e.text));
		const dumpLimited = await call({ action: 'dump', limit: 2 });
		check('limit 限制返回条数', (dumpLimited.elements ?? []).length <= 2, (dumpLimited.elements ?? []).length);

		console.log('\n── tap ───────────────────────────────────────');
		await cdp.eval('window.__events = []');
		const tap = await call({ action: 'tap', text: '发送' });
		check('tap 返回 ok', tap.ok === true, tap.error);
		const evTypes = (tap.dispatched ?? []).join(',');
		for (const t of ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click']) {
			check(`派发了 ${t}`, evTypes.includes(t), evTypes);
		}
		check('页面真的收到了 click', await cdp.eval("window.__events.filter(e=>e.type==='click'&&e.id==='btnSend').length === 1"));
		check('tap 命中目标本体（onTarget 不是 false）', tap.onTarget !== false, tap.onTarget);
		check('tap 返回被点元素', tap.element?.text === '发送', tap.element);

		const tapMissing = await call({ action: 'tap', text: '这个按钮不存在' });
		check('点不存在的元素 → ok:false', tapMissing.ok === false, tapMissing);

		const tapHidden = await call({ action: 'tap', sel: '#hiddenBtn' });
		check('点隐藏元素 → ok:false', tapHidden.ok === false, tapHidden.error);
		check('隐藏元素的失败原因说得清', /不可见|隐藏/.test(tapHidden.error ?? ''), tapHidden.error);

		console.log('\n── tap 的遮挡检测 ────────────────────────────');
		await cdp.eval('window.showMask()');
		const tapMasked = await call({ action: 'tap', text: '发送' });
		check('有遮罩时仍返回 ok（点击动作本身发生了）', tapMasked.ok === true, tapMasked.error);
		check('遮挡被识别：onTarget:false', tapMasked.onTarget === false, tapMasked.onTarget);
		check('告知了实际接收点击的元素', typeof tapMasked.hitTopmost?.sel === 'string' && tapMasked.hitTopmost.sel.includes('mask'),
			tapMasked.hitTopmost);

		// ── 遮挡识别（本轮新增的核心能力）─────────────────────────────
		//
		// ⚠️ 这一组断言锁的是一个**真实踩过的坑**：官方前端首启会弹「预览版说明」
		//    对话框（全屏遮罩 + z-index:1000），底下 16 个按钮在 DOM 里看着完全
		//    正常（有尺寸、opacity 为 1），第一版探针因此把它们全报成可用 ——
		//    模型挨个点、界面纹丝不动且拿不到任何提示。
		check('tap 被遮挡时给出 hint 指路', typeof tapMasked.hint === 'string' && tapMasked.hint.length > 0,
			tapMasked.hint);
		check('tap 被遮挡时回报 overlay 层', !!tapMasked.overlay && tapMasked.overlay.sel.includes('mask'),
			tapMasked.overlay);

		const dumpMasked = await call({ action: 'dump', limit: 50 });
		check('dump 顶层报出遮挡层（overlay）', !!dumpMasked.overlay && dumpMasked.overlay.sel.includes('mask'),
			dumpMasked.overlay);
		check('dump 给出可读的遮挡警告', typeof dumpMasked.warning === 'string' && dumpMasked.warning.length > 0,
			dumpMasked.warning);
		check('dump 统计了被遮挡的元素数', typeof dumpMasked.blocked === 'number' && dumpMasked.blocked > 0,
			dumpMasked.blocked);
		const sendEl = dumpMasked.elements.find((e) => (e.text || '') === '发送');
		check('每个元素带 covered 标记', sendEl?.covered === true, sendEl?.covered);
		check('每个被遮挡的元素指明 blockedBy', !!sendEl?.blockedBy && String(sendEl.blockedBy.sel).includes('mask'),
			sendEl?.blockedBy);

		const dumpOnlyClickable = await call({ action: 'dump', limit: 50, clickableOnly: true });
		check('clickableOnly 滤掉了被遮挡的元素',
			!dumpOnlyClickable.elements.some((e) => (e.text || '') === '发送'),
			dumpOnlyClickable.elements.map((e) => e.text));

		const ovAction = await call({ action: 'overlay' });
		check('overlay 动作报出 present:true', ovAction.present === true, ovAction.present);
		check('overlay 动作报出遮挡层是谁', ovAction.layer?.sel?.includes('mask') === true, ovAction.layer);
		check('overlay 动作列出此刻真正能点的元素',
			Array.isArray(ovAction.clickable) && ovAction.clickable.every((e) => e.covered !== true),
			ovAction.clickable?.map((e) => e.text));

		const waitCovered = await call({ action: 'wait', text: '发送', clickable: true });
		check('wait clickable:true 不把被遮挡的元素算作等到了', waitCovered.satisfied === false,
			waitCovered.satisfied);
		const waitVisible = await call({ action: 'wait', text: '发送' });
		check('wait 默认仍只看可见性（不传 clickable 时行为不变）', waitVisible.satisfied === true,
			waitVisible.satisfied);

		await cdp.eval('window.hideMask()');
		const ovGone = await call({ action: 'overlay' });
		check('遮罩移除后 overlay 报 present:false', ovGone.present === false, ovGone.present);
		const dumpClean = await call({ action: 'dump', limit: 50 });
		check('遮罩移除后 dump 不再报遮挡', !dumpClean.overlay && dumpClean.blocked === 0,
			{ overlay: dumpClean.overlay, blocked: dumpClean.blocked });

		console.log('\n── type（React 受控输入）─────────────────────');
		await cdp.eval('window.__reactState = { value: "", onChangeCalls: 0 }; window.__events = []');
		const typed = await call({ action: 'type', sel: '#msg', value: '你好世界' });
		check('type 返回 ok', typed.ok === true, typed.error);
		check('输入框真实值已写入', await cdp.eval("document.getElementById('msg').value") === '你好世界');
		check('★ React 感知到了变化（onChange 触发）',
			await cdp.eval('window.__reactState.onChangeCalls') > 0,
			await cdp.eval('window.__reactState.onChangeCalls'));
		check('★ React 拿到的值正确', await cdp.eval('window.__reactState.value') === '你好世界',
			await cdp.eval('window.__reactState.value'));
		check('派发了 input 事件', (typed.dispatched ?? []).join(',').includes('input')
			|| await cdp.eval("window.__events.some(e=>e.type==='input')"));
		check('回执里的 value 是写入后的值', typed.value === '你好世界', typed.value);

		const typedClear = await call({ action: 'type', sel: '#msg', value: '', clear: true });
		check('clear:true 能清空', typedClear.ok === true && await cdp.eval("document.getElementById('msg').value") === '');

		await cdp.eval('window.__reactState = { value: "", onChangeCalls: 0 }; window.__events = []');
		const typedEnter = await call({ action: 'type', sel: '#msg', value: 'hi', enter: true });
		check('enter:true 时派发了 keydown/keyup',
			await cdp.eval("window.__events.some(e=>e.type==='keydown'&&e.key==='Enter') && window.__events.some(e=>e.type==='keyup')"),
			await cdp.eval('window.__events'));

		console.log('\n── key ───────────────────────────────────────');
		await cdp.eval('window.__events = []');
		const keyed = await call({ action: 'key', key: 'Escape' });
		check('key 返回 ok', keyed.ok === true, keyed.error);
		check('Escape 送达页面', await cdp.eval("window.__events.some(e=>e.type==='keydown'&&e.key==='Escape')"));

		console.log('\n── scroll ────────────────────────────────────');
		const scrolled = await call({ action: 'scroll', sel: '#scroll', dy: 200 });
		check('scroll 返回 ok', scrolled.ok === true, scrolled.error);
		check('容器真的滚动了', await cdp.eval("document.getElementById('scroll').scrollTop") > 0,
			await cdp.eval("document.getElementById('scroll').scrollTop"));
		check('报告了位移前后', typeof scrolled.before === 'number' && typeof scrolled.after === 'number', scrolled);
		const scrollBottom = await call({ action: 'scroll', sel: '#scroll', to: 'bottom' });
		check('to:bottom 跳到最大位移',
			await cdp.eval("Math.abs(document.getElementById('scroll').scrollTop - (document.getElementById('scroll').scrollHeight - document.getElementById('scroll').clientHeight)) < 2"),
			await cdp.eval("document.getElementById('scroll').scrollTop"));

		console.log('\n── text ──────────────────────────────────────');
		const txt = await call({ action: 'text', sel: '#composer' });
		check('text 返回 ok', txt.ok === true, txt.error);
		check('读到了可见文本', (txt.lines ?? []).some((l) => l.includes('发送')), txt.lines);

		console.log('\n── info / find / html ────────────────────────');
		const info = await call({ action: 'info' });
		check('info 返回 ok', info.ok === true, info.error);
		check('info 带 path', typeof info.path === 'string', info.path);
		check('info 带视口', info.viewport?.w > 0, info.viewport);
		const found = await call({ action: 'find', testid: 'btn-new' });
		check('find 按 data-testid 命中', found.ok === true && found.element?.testid === 'btn-new', found);
		const foundAria = await call({ action: 'find', aria: '新建会话' });
		check('find 按 aria-label 命中（图标按钮的唯一办法）', foundAria.ok === true, foundAria.error);
		const html = await call({ action: 'html', sel: '#btnSend' });
		check('html 返回源码', html.ok === true && (html.html ?? '').includes('发送'), html);

		console.log('\n── 按标签定位（text / exact）─────────────────');
		const partial = await call({ action: 'find', text: '发' });
		check('text 默认子串匹配', partial.ok === true, partial.error);
		const exactMiss = await call({ action: 'find', text: '发', exact: true });
		check('exact:true 时不接受部分匹配', exactMiss.ok === false, exactMiss);

		console.log('\n── wait（探针侧是单次检查）───────────────────');
		const waitNo = await call({ action: 'wait', text: '不存在的按钮' });
		check('条件不成立时 satisfied:false', waitNo.ok === true && waitNo.satisfied === false, waitNo);
		const waitYes = await call({ action: 'wait', text: '发送' });
		check('条件成立时 satisfied:true', waitYes.ok === true && waitYes.satisfied === true, waitYes);
		const waitGone = await call({ action: 'wait', sel: '#hiddenBtn', visible: false });
		check('visible:false 等「不可见」也能成立', waitGone.satisfied === true, waitGone);

		console.log('\n── 健壮性 ────────────────────────────────────');
		const unknown = await call({ action: '没这个动作' });
		check('未知 action 报明确失败而不是崩', unknown.ok === false && /未知/.test(unknown.error ?? ''), unknown);
		const empty = await call({ action: 'dump', sel: '#根本不存在的选择器' });
		check('不存在的选择器不抛异常', empty.ok === true || empty.ok === false);
		check('返回值可 JSON 序列化', typeof JSON.stringify(dump) === 'string' && JSON.stringify(dump).length > 10);
		check('探针没有污染页面（未插入任何样式/元素）',
			await cdp.eval("document.querySelectorAll('style,link[rel=stylesheet]').length") === 1,
			await cdp.eval("document.querySelectorAll('style,link[rel=stylesheet]').length"));
		check('探针未改变元素属性（body 的 style 仍为空）',
			(await cdp.eval("document.body.getAttribute('style')")) === null,
			await cdp.eval("document.body.getAttribute('style')"));

		console.log('\n── 幂等装填 ──────────────────────────────────');
		// ⚠️ 版本号从**源码**读，不要在这里硬编码 —— 探针改语义就要升版，
		//    写死的话每次升版都会让这条本来正确的用例变成失败（踩过一次）。
		const expectV = Number(/var VERSION = (\d+)/.exec(fs.readFileSync(PROBE, 'utf8'))?.[1]);
		const v1 = await cdp.eval('window.__DSH_UI_PROBE__.__v');
		await call({ action: 'ping' });
		const v2 = await cdp.eval('window.__DSH_UI_PROBE__.__v');
		check('重复调用不会重复装填（版本号稳定）', v1 === v2 && v1 === expectV, { v1, v2, expectV });
	} finally {
		try { cdp?.ws.close(); } catch { /* 忽略 */ }
		chrome.kill();
		try { fs.rmSync(dir, { recursive: true, force: true }); } catch { /* 忽略 */ }
	}

	console.log(`\n${'═'.repeat(52)}`);
	if (failures.length === 0) {
		console.log(`全部通过：${pass} 项断言`);
		process.exit(0);
	}
	console.log(`通过 ${pass} 项，失败 ${failures.length} 项：`);
	for (const f of failures) console.log(`  ✗ ${f}`);
	process.exit(1);
}

main().catch((e) => { console.error('验证器自身出错:', e); process.exit(2); });
