/**
 * **中继必须拒绝那个已泄露的旧令牌** —— 安全回归测试。
 *
 * ## 为什么单独验这一条
 *
 * `no-shared-credentials.mjs` 验的是「包里不再有凭据、未配对时令牌随机」。
 * 但还有一层必须独立确认：**新代码起的中继，拿旧公开令牌打过来必须 401**。
 *
 * 因为那个常量已经发布到 npm、无法收回，它是**已泄露**的。任何还留着它的
 * 老机器（`token.txt` 没升级）或任何读过旧版包的人，都可能拿它来打新版本。
 * 令牌既然已经轮换成随机的，旧值就必须被拒 —— 这是修复生效的直接证据。
 *
 * ## 怎么做到「在空闲端口起真 relay」
 *
 * `RELAY_PORT` 是模块级常量（17890），而那台机器上**真的有另一个 DSH 占着它**，
 * 所以不能直接 `apply()` —— 会走降级分支，中继根本没监听，测不了鉴权。
 *
 * 这里用「源码文本替换 + 落到临时文件」的办法：把端口换成空闲端口、把
 * `./pair.js` 的相对导入换成绝对 `file://` URL，然后 import 那份**真源码**。
 * 换的是端口号，**鉴权逻辑一字未改**，所以断言仍然打在真代码上。
 *
 * 用法：node relay-auth.mjs
 */
import { mkdtempSync, rmSync, writeFileSync, readFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createServer } from 'node:http';
import { randomBytes } from 'node:crypto';

const LEGACY = 'f6721b0cf82414709df172056193c78a6c374e9a920e51145dd7883923cf16e7';
const SRC = join(import.meta.dirname, '..', 'dsh-remote-desktop', 'lib', 'index.js');
const PAIR_SRC = join(import.meta.dirname, '..', 'dsh-remote-desktop', 'lib', 'pair.js');

let failed = 0;
const check = (ok, label, detail = '') => {
	console.log(`${ok ? '✅' : '❌'} ${label}${detail ? '  ' + detail : ''}`);
	if (!ok) failed += 1;
};

/** 找一个空闲端口。 */
async function freePort() {
	return new Promise((resolve, reject) => {
		const probe = createServer();
		probe.on('error', reject);
		probe.listen(0, '127.0.0.1', () => {
			const { port } = probe.address();
			probe.close(() => resolve(port));
		});
	});
}

const PORT = await freePort();
const stateHome = mkdtempSync(join(tmpdir(), 'dsh-relayauth-'));
const workDir = mkdtempSync(join(tmpdir(), 'dsh-relayauth-src-'));

// 造一个「已配对」的机器：accessToken 是随机的（模拟服务器下发的）
//
// ⚠️ `pair.json` 必须落在 `STATE_DIR` = `$DSH_HOME/dsh-remote/` 下（这是
// `createPairing` 的约定），不是随便一个临时目录 —— 放错了 `pairing.read()`
// 读不到，中继就会用随机生成的令牌，于是「正确令牌」那条断言 401，
// 连带「旧令牌 401」也变成**假绿**（它拒绝一切，不是因为认出了旧令牌）。
const FRESH = randomBytes(32).toString('hex');
const stateDir = join(stateHome, 'dsh-remote');
mkdirSync(stateDir, { recursive: true });
writeFileSync(
	join(stateDir, 'pair.json'),
	JSON.stringify({
		activationCode: 'TESTTEST',
		subdomain: 'dsh-testtest',
		hostname: 'dsh-testtest.example',
		tunnelToken: 'y'.repeat(240),
		accessToken: FRESH,
		pairedAt: Date.now(),
		reused: false,
	}),
	{ mode: 0o600 },
);

// 源码替换：只换端口与相对导入，鉴权逻辑原样
let src = readFileSync(SRC, 'utf8');
const before = src;
src = src.replace(/const RELAY_PORT = \d+;/, `const RELAY_PORT = ${PORT};`);
src = src.replace(/from '\.\/pair\.js'/, `from ${JSON.stringify(pathToFileURL(PAIR_SRC).href)}`);
check(src !== before && src.includes(`RELAY_PORT = ${PORT}`), '源码替换成功（端口换到空闲端口）', `PORT=${PORT}`);
check(!src.includes("from './pair.js'"), '相对导入已换成绝对 file:// URL');

const tmpModule = join(workDir, 'index-tmp.js');
writeFileSync(tmpModule, src);

process.env.DSH_HOME = stateHome;
process.env.DSH_REMOTE_CLOUDFLARED = join(workDir, 'nonexistent.exe');

const mod = await import(pathToFileURL(tmpModule).href);

const logs = [];
/** `apply()` 里 `ctx.effect(fn)` 的 `fn()` 返回 dispose —— **必须收好并在末尾调用**，
 *  否则进程退出时中继的 server 还开着，libuv 会以
 *  `!(handle->flags & UV_HANDLE_CLOSING)` 断言崩掉（退出码 -1073740791），
 *  真实失败信号就被这个崩溃盖住了。 */
const disposers = [];
const ctx = {
	effect: (fn) => {
		try {
			const d = fn();
			if (typeof d === 'function') disposers.push(d);
		} catch (e) {
			logs.push(['error', String(e?.message ?? e)]);
		}
		return () => {};
	},
	on: () => {},
	logger: {
		info: (m) => logs.push(['info', String(m)]),
		warn: (m) => logs.push(['warn', String(m)]),
		error: (m) => logs.push(['error', String(m)]),
	},
	webServer: { register: () => ({ dispose() {} }) },
	sessions: { get: () => undefined },
};
mod.apply(ctx, { autoTunnel: false });

// 等中继真的监听上
let up = false;
for (let i = 0; i < 40; i++) {
	await new Promise((r) => setTimeout(r, 100));
	try {
		await fetch(`http://127.0.0.1:${PORT}/api/dsh-remote/rpc`, {
			method: 'POST',
			headers: { 'content-type': 'application/json' },
			body: '{}',
			signal: AbortSignal.timeout(1000),
		});
		up = true;
		break;
	} catch {
		/* 还没起来 */
	}
}
check(up, `中继已在 127.0.0.1:${PORT} 监听`);
check(!logs.some(([, m]) => /中继无法监听/.test(m)), '没有端口冲突（用的是空闲端口）');

/** 用给定令牌打一条 RPC。 */
async function call(token) {
	const headers = { 'content-type': 'application/json' };
	if (token !== undefined) headers['x-dsh-remote-token'] = token;
	const res = await fetch(`http://127.0.0.1:${PORT}/api/dsh-remote/rpc`, {
		method: 'POST',
		headers,
		body: JSON.stringify({ id: 't', method: 'ping', params: {} }),
		signal: AbortSignal.timeout(10000),
	});
	let payload;
	try {
		payload = await res.json();
	} catch {
		payload = undefined;
	}
	return { status: res.status, payload };
}

console.log('\n=== 鉴权断言 ===');
const withFresh = await call(FRESH);
check(withFresh.status === 200, '服务器下发的**新令牌** → 200（能正常用）', `HTTP ${withFresh.status}`);

const withLegacy = await call(LEGACY);
check(withLegacy.status === 401, '已泄露的**旧公开令牌** → 401（被拒绝）← 这是修复生效的直接证据', `HTTP ${withLegacy.status}`);
check(withLegacy.payload?.error === 'unauthorized', '返回 unauthorized', JSON.stringify(withLegacy.payload));

const noToken = await call(undefined);
check(noToken.status === 401, '不带令牌 → 401', `HTTP ${noToken.status}`);

const wrong = await call('a'.repeat(64));
check(wrong.status === 401, '随便一个错令牌 → 401', `HTTP ${wrong.status}`);

// 令牌来源也要确认：用的是 pair.json 的 accessToken，不是别的
check(logs.some(([, m]) => new RegExp(`访问令牌: ${FRESH}`).test(m)), '中继用的令牌来自 pair.json 的 accessToken');
check(!logs.some(([, m]) => /检测到旧版内置的公开令牌/.test(m)), '没有误报「旧令牌」（文件里本来就是新值）');

// 收尾：先关中继（否则进程退出时 libuv 断言崩），再删临时目录
for (const d of disposers) {
	try {
		d();
	} catch {
		/* 已关闭 */
	}
}
await new Promise((r) => setTimeout(r, 200));
rmSync(stateHome, { recursive: true, force: true });
rmSync(workDir, { recursive: true, force: true });
console.log(`\n${failed === 0 ? '✅ 全部通过' : `❌ ${failed} 项失败`}`);
process.exit(failed === 0 ? 0 : 1);
