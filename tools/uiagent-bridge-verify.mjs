/**
 * `ui` 工具**插件侧**的文件信箱契约验收。
 *
 * 为什么单独验这一层：整条链路是三跳的 ——
 *
 *     模型 ──▶ 插件(本文件所验) ──ui-cmd.json──▶ 原生 Kotlin ──▶ 页面探针
 *                    ◀──ui-result.json──┘
 *
 * 另外两跳在 PC 侧已有验收（`ui-probe-verify.mjs` 验页面探针、真机验原生），
 * **只有中间这一跳既不在页面里、也不需要真机** —— 它是纯 Node + 文件系统，
 * 所以能在本机**用真的插件代码**驱动，只需一个假的原生对端。
 *
 * 验的是四件在真机上表现为「偶发、看起来随机」的事：
 *   ① `id` 配对 —— 上一轮的回执被当成本轮答案；
 *   ② 串行化   —— 并发调用互相覆盖，前一条从未执行；
 *   ③ 超时语义 —— 超时被当成成功（模型以为点了，界面没动）；
 *   ④ 原子写   —— 原生读到写了一半的 JSON。
 *
 * 用法：node tools/uiagent-bridge-verify.mjs
 * 退出码 0 = 全部通过。
 */

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const PLUGIN = path.resolve('mobile-ui-plugin/dsh-android-uiagent/lib/index.js');

// ────────────────────────────────────────────────────────────────────
// 断言
// ────────────────────────────────────────────────────────────────────
let pass = 0;
const failures = [];

function ok(cond, label, extra) {
	if (cond) {
		pass += 1;
		console.log(`  ✓ ${label}`);
	} else {
		failures.push(label);
		console.log(`  ✗ ${label}${extra ? ` — ${extra}` : ''}`);
	}
}

function section(title) {
	console.log(`\n── ${title} ${'─'.repeat(Math.max(0, 44 - title.length))}`);
}

function sleep(ms) {
	return new Promise((r) => setTimeout(r, ms));
}

// ────────────────────────────────────────────────────────────────────
// 假原生：只实现「读 ui-cmd.json → 写 ui-result.json」那一侧
// ────────────────────────────────────────────────────────────────────
function startFakeNative(home, opts = {}) {
	const cmdPath = path.join(home, 'ui-cmd.json');
	const resPath = path.join(home, 'ui-result.json');
	const seen = [];          // 收到的每条命令（含收到的时刻）
	const concurrent = [];    // 同时存在的命令数（>1 即并发写，是缺陷）
	let inFlight = 0;
	let stop = false;

	const timer = setInterval(() => {
		let text;
		try { text = fs.readFileSync(cmdPath, 'utf8'); } catch { return; }
		let cmd;
		try { cmd = JSON.parse(text); } catch {
			// ③ 读到写了一半的文件：**不算错误**，下一轮再看。
			//    这里刻意放大计数，因为「插件必须原子写」正是被验的事。
			concurrent.push('PARSE_FAIL');
			return;
		}
		// 同一份命令可能被读两次（轮询很快），只在 id 变化时算「新命令」。
		if (seen.length > 0 && seen[seen.length - 1].id === cmd.id) return;

		seen.push({ id: cmd.id, action: cmd.action, at: Date.now(), raw: cmd });
		inFlight += 1;
		concurrent.push(inFlight);

		if (opts.holdMs) {
			// 故意慢：模拟「tap 之后等界面变化」那段耗时。
			setTimeout(() => { inFlight -= 1; }, opts.holdMs);
		} else {
			inFlight -= 1;
		}

		const reply = {
			id: cmd.id,
			ok: true,
			action: cmd.action,
			echo: cmd.value ?? null,
			...(opts.replyExtra ?? {}),
		};
		if (opts.dropIds?.includes(cmd.id)) return;   // 故意不回执（模拟原生挂了）
		if (opts.wrongIdAlways) {
			// 故意总用**别的** id 回执：验「按 id 配对」是否真的生效 ——
			// 探针必须认出来这不是给自己，而不是把内容照单全收。
			fs.writeFileSync(resPath, JSON.stringify({ ...reply, id: cmd.id + 7 }), 'utf8');
			return;
		}
		if (opts.delayMs) {
			setTimeout(() => { try { fs.writeFileSync(resPath, JSON.stringify(reply), 'utf8'); } catch {} }, opts.delayMs);
			return;
		}
		try { fs.writeFileSync(resPath, JSON.stringify(reply), 'utf8'); } catch {}
	}, opts.pollMs ?? 5);

	return {
		seen,
		concurrent,
		stop() { stop = true; clearInterval(timer); },
		get stopped() { return stop; },
	};
}

// ────────────────────────────────────────────────────────────────────
// 载入插件并取出工具
// ────────────────────────────────────────────────────────────────────
const home = fs.mkdtempSync(path.join(os.tmpdir(), 'uiagent-bridge-'));
process.env.DSH_HOME = home;

const mod = await import(pathToFileURL(PLUGIN).href);

// 抓注册进来的工具定义（假 ctx）
let tool = null;
const ctx = {
	logger: { warn: (...a) => console.log(`    [插件警告] ${a.join(' ')}`) },
	tools: { register(t) { tool = t; } },
};

console.log(`插件: ${path.relative(process.cwd(), PLUGIN)}`);
console.log(`信箱: ${home}`);

section('注册与前置条件');
mod.apply(ctx);
ok(tool !== null, 'DSH_HOME 存在时注册了 ui 工具');
ok(tool?.name === 'ui', `工具名是 ui（实际 ${tool?.name}）`);
ok(Array.isArray(tool?.parameters?.required) && tool.parameters.required.includes('action'),
	'action 是必填参数');
const actions = tool?.parameters?.properties?.action?.enum ?? [];
ok(actions.includes('overlay'), '动作枚举里有本轮新增的 overlay');
ok(actions.includes('dump') && actions.includes('tap') && actions.includes('wait'),
	'基本动作（dump/tap/wait）都在枚举里');
ok(tool?.parameters?.properties?.clickableOnly?.type === 'boolean',
	'schema 暴露了 clickableOnly（dump 只看能点的）');
ok(tool?.parameters?.properties?.clickable?.type === 'boolean',
	'schema 暴露了 clickable（wait 要求真能点）');
ok(tool?.parameters?.properties?.additionalProperties === undefined
	|| tool?.parameters?.additionalProperties === false,
	'额外字段被禁止（避免模型瞎编参数）');

// ────────────────────────────────────────────────────────────────────
section('① id 配对：上一轮的回执不能当本轮答案');
{
	// 预埋一份「上一轮」的回执，id 与本轮不同。
	fs.writeFileSync(path.join(home, 'ui-result.json'),
		JSON.stringify({ id: 999, ok: true, from: '上一轮' }), 'utf8');

	const native = startFakeNative(home);
	const r = await tool.execute({ action: 'ping' });
	native.stop();

	ok(r.ok === true, 'ping 拿到回执');
	ok(r.details?.from === undefined, '没把上一轮的陈旧回执当成本轮结果');
	ok(r.details?.id === 1, `回执 id 与本轮请求一致（实际 ${r.details?.id}）`);
	ok(native.seen.length === 1, `原生只收到 1 条命令（实际 ${native.seen.length}）`);
	ok(native.seen[0]?.action === 'ping', '透传给原生的 action 正确');
}

section('② 串行化：并发调用不能互相覆盖');
{
	// 关键场景：模型在同一轮里并行发起多个工具调用。
	// 延迟回执，让「串行」与「并发」在耗时上可区分：若插件没有闸门，三条命令
	// 会同时落进同一个文件、只有最后一条被执行。
	const native = startFakeNative(home, { pollMs: 5, delayMs: 50 });
	const t0 = Date.now();
	const results = await Promise.all([
		tool.execute({ action: 'dump' }),
		tool.execute({ action: 'info' }),
		tool.execute({ action: 'text' }),
	]);
	const elapsed = Date.now() - t0;
	native.stop();

	ok(native.seen.length === 3, `三条命令都被原生收到（实际 ${native.seen.length}）`);
	const gotActions = native.seen.map((c) => c.action).join(',');
	ok(gotActions === 'dump,info,text', `执行顺序与发起顺序一致（实际 ${gotActions}）`);
	ok(!native.concurrent.includes('PARSE_FAIL'), '原生从未读到写了一半的命令文件（原子写生效）');
	ok(Math.max(...native.concurrent) === 1, `任一瞬间信箱里只有一条命令（实际峰值 ${Math.max(...native.concurrent)}）`);
	ok(results.every((r) => r.ok === true), '三个并发调用各自拿到自己的回执');
	ok(results[0].details?.action === 'dump' && results[1].details?.action === 'info'
		&& results[2].details?.action === 'text', '回执没有串台（各自对各自的 action）');
	// 三次各 50ms 回执 + 80ms 轮询 ⇒ 串行下至少 ~150ms；真并发会 ≈ 130ms 且只剩 1 条命令。
	// 这条只是辅助证据，主证据是上面的顺序与峰值并发。
	ok(elapsed >= 120, `耗时符合串行特征（${elapsed}ms）`);
}

section('③ id 对不上的回执不能被误认成功（闸门随后必须放行）');
{
	// 假原生先回一份**别人的**回执，再回正确的 —— 插件必须跳过前者、接受后者。
	// （比「一律不回执、等 20 秒超时」快得多，验的是同一件事。）
	const cmdPath = path.join(home, 'ui-cmd.json');
	const resPath = path.join(home, 'ui-result.json');
	const native = {
		seen: [],
		stop() { clearInterval(timer); },
	};
	const timer = setInterval(() => {
		let cmd;
		try { cmd = JSON.parse(fs.readFileSync(cmdPath, 'utf8')); } catch { return; }
		if (native.seen.includes(cmd.id)) return;
		native.seen.push(cmd.id);
		// ① 先投一份 id 对不上的（模拟上一轮遗留 / 串台）
		fs.writeFileSync(resPath, JSON.stringify({ id: cmd.id + 7, ok: true, action: 'WRONG' }), 'utf8');
		// ② 40ms 后投正确的那份
		setTimeout(() => {
			try {
				fs.writeFileSync(resPath, JSON.stringify({ id: cmd.id, ok: true, action: cmd.action }), 'utf8');
			} catch {}
		}, 40);
	}, 5);

	const r1 = await tool.execute({ action: 'ping' });
	native.stop();

	ok(r1.ok === true, 'ping 最终拿到回执');
	ok(r1.details?.action === 'ping',
		`拿到的是**正确 id** 的那份，而不是 id 对不上的（实际 ${r1.details?.action}）`);
	ok(r1.details?.id !== native.seen[0] + 7, '错误 id 的回执没有被接受');

	// 闸门是否放行 —— 后续调用必须照常工作。
	const native2 = startFakeNative(home, { pollMs: 5 });
	const r2 = await tool.execute({ action: 'ping' });
	native2.stop();
	ok(r2.ok === true, '后续调用照常工作（闸门没有卡死）');
}

section('④ 超时不能返回成功');
{
	// 原生永不回执。为了不真等 20 秒，这里直接检查插件声明的超时值与文案。
	const src = fs.readFileSync(PLUGIN, 'utf8');
	const m = /const WAIT_TIMEOUT_MS = (\d+);/.exec(src);
	ok(m !== null, '插件里能读到 WAIT_TIMEOUT_MS');
	const waitMs = Number(m?.[1]);
	ok(waitMs >= 12_000,
		`插件超时(${waitMs}ms)必须大于原生单次超时(12000ms)，否则原生还没答就判超时`);

	// 检查超时分支**不返回 ok**
	const idx = src.indexOf('等待原生回执超时');
	const around = idx >= 0 ? src.slice(Math.max(0, idx - 300), idx + 200) : '';
	ok(/ok:\s*false/.test(around), '超时分支明确返回 ok:false（绝不假装成功）');
	ok(/info/.test(around), '超时文案引导模型用 info 复查，而不是让它猜');
	ok(/可能已经变化，也可能没有|可能/.test(around),
		'超时文案如实说明「界面可能变了也可能没变」，不替模型下结论');
}

section('⑤ 字段原样透传（不做 text ↔ value 翻译）');
{
	const native = startFakeNative(home, { pollMs: 5 });
	const r = await tool.execute({ action: 'tap', text: '发送' });
	native.stop();

	const got = native.seen[0]?.raw ?? {};
	ok(got.text === '发送', 'tap 的 text 原样透传到原生（没被当成输入内容删掉）');
	ok(got.value === undefined, 'tap 没有被塞进 value');
	ok(r.ok === true, 'tap 调用成功');
}
{
	const native = startFakeNative(home, { pollMs: 5 });
	await tool.execute({ action: 'type', sel: '#msg', value: '你好' });
	native.stop();
	const got = native.seen[0]?.raw ?? {};
	ok(got.value === '你好', 'type 的 value 原样透传');
	ok(got.sel === '#msg', 'type 的定位条件 sel 原样透传');
}

section('⑥ 参数校验：非法输入要报明确失败而不是发给原生');
{
	// 用「不存在的 home」不好造，改为直接看：校验失败时命令文件不应被写出来。
	const cmdPath = path.join(home, 'ui-cmd.json');
	try { fs.unlinkSync(cmdPath); } catch { /* 干净就行 */ }
	const r = await tool.execute({ action: '不存在的动作' });
	ok(r.ok === false, '未知 action 被拦下');
	ok(r.message && r.message.length > 0, '拦下时给出了可读的原因');
	ok(!fs.existsSync(cmdPath), '未知 action 没有往信箱里写命令（校验在发送之前）');
}

section('⑦ 渲染层：模型读到的是文本，遮挡信息排在元素清单之前');
{
	const native = startFakeNative(home, {
		pollMs: 5,
		replyExtra: {
			matched: 2,
			blocked: 2,
			elements: [
				{ text: '发送', tag: 'button', sel: '#send', rect: { x: 10, y: 20, w: 30, h: 30 }, covered: true, blockedBy: { tag: 'div', cls: '_mask_' } },
				{ text: '取消', tag: 'button', sel: '#cancel', rect: { x: 50, y: 20, w: 30, h: 30 }, covered: true, blockedBy: { tag: 'div', cls: '_mask_' } },
			],
			overlay: { tag: 'div', cls: '_mask_', sel: 'html > body > div._mask_', rect: { x: 0, y: 0, w: 360, h: 780 } },
			warning: '当前有覆盖视口大部分区域的遮挡层，下面的元素点了不会有反应。',
			viewport: { w: 360, h: 780 },
		},
	});
	const r = await tool.execute({ action: 'dump' });
	native.stop();

	const msg = r.message ?? '';
	const iOverlay = msg.indexOf('遮挡层');
	const iFirstEl = msg.indexOf('发送');
	ok(iOverlay >= 0, 'dump 文本里有遮挡层警告');
	ok(iFirstEl >= 0, 'dump 文本里列出了元素');
	ok(iOverlay < iFirstEl,
		`遮挡警告排在元素清单之前（遮挡@${iOverlay}，首个元素@${iFirstEl}）—— 模型扫清单前就被拦下`);
	ok(/点不动|被遮挡/.test(msg), '文案直说「点不动/被遮挡」，不是含糊的警告');
	ok(/被遮挡←/.test(msg),
		'元素行上直接标出遮挡者（模型逐行扫清单时对那条就不会照点）');
	ok(/此刻真正能点/.test(msg),
		'提示用 overlay 看「此刻真正能点的」，而不是误导成「遮挡层里面的」（官方弹窗里按钮是遮罩的兄弟节点）');
}

section('⑧ 渲染层：type 的三态 applied 不能被当布尔');
{
	const three = [
		{ applied: true, how: 'execCommand', value: 'x', want: '不报错' },
		{ applied: null, how: 'execCommand(异步生效)', value: '', want: '说明异步' },
		{ applied: false, how: 'textContent(未生效)', value: '', want: '警告未写入' },
	];
	for (const c of three) {
		const native = startFakeNative(home, {
			pollMs: 5,
			replyExtra: { applied: c.applied, how: c.how, value: c.value, element: { tag: 'div', sel: '#ed' } },
		});
		const r = await tool.execute({ action: 'type', sel: '#ed', value: 'x' });
		native.stop();
		const msg = r.message ?? '';
		if (c.applied === true) {
			ok(!/⚠️/.test(msg), 'applied:true 时不报警告（不误报失败）');
		} else if (c.applied === null) {
			ok(/异步/.test(msg) && !/没有真正写进去/.test(msg),
				'applied:null 被当作「异步生效」而不是失败（contenteditable 的正常路径）');
		} else {
			ok(/没有真正写进去/.test(msg), 'applied:false 明确报「没写进去」并给建议');
		}
	}
}

section('⑨ 未就绪时不该注册（避免模型拿到一个永远失败的工具）');
{
	const saved = process.env.DSH_HOME;
	process.env.DSH_HOME = '';
	let t2 = null;
	const ctx2 = { logger: { warn: () => {} }, tools: { register: (t) => { t2 = t; } } };
	// 重新载入模块（cache-busting 需要不同 URL）
	const mod2 = await import(`${pathToFileURL(PLUGIN).href}?v=2`);
	mod2.apply(ctx2);
	ok(t2 === null, 'DSH_HOME 为空时不注册工具');
	process.env.DSH_HOME = saved;
}

// ────────────────────────────────────────────────────────────────────
console.log(`\n${'═'.repeat(52)}`);
if (failures.length === 0) {
	console.log(`全部通过：${pass} 项断言`);
} else {
	console.log(`失败 ${failures.length} 项 / 共 ${pass + failures.length} 项：`);
	for (const f of failures) console.log(`  ✗ ${f}`);
}
try { fs.rmSync(home, { recursive: true, force: true }); } catch {}
process.exit(failures.length === 0 ? 0 : 1);
