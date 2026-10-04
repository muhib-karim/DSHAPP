/**
 * dsh-android-mobile —— 宿主半（host half）。
 *
 * ## 这个文件承担两件事
 *
 * ① 移动端 UI 适配的**全部实现**在客户端半（`lib/client.js`），它由
 *    `@deepseek-ai/dsh-client-modules` 按本包 `package.json` 的 `dsh.client`
 *    声明自动编入浏览器侧，不需要宿主半做任何事。
 *    但包必须有一个 Node 侧入口：`main` 指向的模块会被 Cordis 真正 import，
 *    而 `lib/client.js` 是浏览器代码（顶层引用 `window.__ModuleLoader__`），
 *    在 Node 里 import 会当场抛 `window is not defined`。所以本文件同时是那个入口。
 *
 * ② **uid 2000 特权能力的状态探测接口**（`GET /api/dsh-android/privileged`）。
 *    设置页的「特权权限」分节（客户端半注册的 `settings.section`）要靠它显示
 *    实时状态。浏览器进程拿不到 `process.env` / 文件系统，所以这一半必须在宿主侧。
 *
 * ## 为什么这个接口返回的是「端到端跑一次」而不是「文件在不在」
 *
 * 特权通道有两级降级（见 AGENTS.md ⑪）：
 *   · 第一级 —— 通道资产缺失 → `shell` 工具根本不注册；
 *   · 第二级 —— 密钥没授权/5555 没监听 → runner **静默回落**成 app 身份执行，
 *     命令照样跑、退出码照样 0。
 *
 * 第二级是静默的：只查「`adbkey` 文件在不在」会得到**假阳性** —— 密钥文件可能
 * 存在但没被 adbd 授权（设备 `adb_keys` 里没有对应公钥），那时文件在、通道却
 * 拿不到 uid 2000。所以真正的判据只有一条：**实跑 `id -u`，看它回什么**。
 * 文件存在性只作为定位问题用的分解信息。
 *
 * 探测会顺带触发 runner 的密钥导入（首次运行时从 `/sdcard/dsh_adbkey` 搬进
 * `$DSH_HOME/adbkey` 并删除中转文件），所以设置页点一次「重新检测」就等于
 * 完成了引导的最后一步。
 *
 * @module dsh-android-mobile
 */

import { spawn } from 'node:child_process';
import { readFile, stat } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

export const name = 'dsh-android-mobile';

/**
 * 状态接口挂在 web 组合的 HTTP 载体上，所以依赖 `webServer`。
 *
 * ⚠️ 这只影响**宿主半**：客户端半由 `dsh-client-modules` 独立扫描 `dsh.client`
 * 声明编入，宿主半激活与否都不影响「特权权限」分节照常注册。
 * 极端情况下（无 webServer 的部署）接口 404，设置页显示「无法获取状态」。
 *
 * @type {string[]}
 */
export const inject = ['webServer'];

/** 状态接口路径。exact 路由优先于 `/api` 前缀，所以不会被 RPC 通道截走。 */
const ROUTE = '/api/dsh-android/privileged';

/** 探测超时。runner 连 adbd 自身有 4s 连接 + 8s 握手上限，这里留足余量。 */
const PROBE_TIMEOUT_MS = 15000;

/** runner 打在 stderr 上的前缀，与 `assets/dsh-android-shell.mjs` 的 `TAG` 一致。 */
const TAG = '[dsh-adb]';

/** runner 回落成 app 身份时的 stderr 文案（原因在中文括号里）。 */
const FALLBACK_RE = /uid 2000 不可用（(.+?)）/;

/**
 * 引擎的 `$DSH_HOME`。
 * @returns {string} 绝对路径。
 */
function homeDir() {
	const home = process.env.DSH_HOME;
	if (typeof home === 'string' && home !== '') return home;
	return path.join(os.homedir(), '.dsh');
}

/**
 * uid 2000 执行通道的落盘路径。
 *
 * 优先用启动时注入的 `DSH_PRIV_RUNNER`；缺失时按 `EngineManager` 的布局回推
 * （`$DSH_HOME` = `<filesDir>/home/.dsh`，runner 在 `<filesDir>/dsh-android-shell.mjs`，
 * 所以从 `$DSH_HOME` 上溯两级）。
 *
 * @param {string} home - 引擎的 `$DSH_HOME`。
 * @returns {string} runner 的绝对路径。
 */
function runnerPath(home) {
	const explicit = process.env.DSH_PRIV_RUNNER;
	if (typeof explicit === 'string' && explicit !== '') return explicit;
	return path.resolve(home, '..', '..', 'dsh-android-shell.mjs');
}

/**
 * `stat` 一个普通文件，失败（不存在/目录/权限）一律给 `null`。
 * @param {string} target - 待查路径。
 * @returns {Promise<{size: number, mtimeMs: number}|null>} 文件信息或 `null`。
 */
async function statFile(target) {
	try {
		const info = await stat(target);
		return info.isFile() ? { size: info.size, mtimeMs: info.mtimeMs } : null;
	} catch {
		return null;
	}
}

/**
 * 跑一次 runner 并收齐输出。**不做回落判断**，只如实返回。
 * @param {string} runner - runner 的绝对路径。
 * @param {string} command - 交给 runner 的命令。
 * @param {string} home - 引擎的 `$DSH_HOME`。
 * @returns {Promise<{stdout: string, stderr: string, code: number|null, timedOut: boolean}>} 执行结果。
 */
function runRunner(runner, command, home) {
	return new Promise((resolve) => {
		let settled = false;
		const done = (value) => {
			if (settled) return;
			settled = true;
			clearTimeout(timer);
			resolve(value);
		};
		let child;
		try {
			child = spawn(process.execPath, [runner, command], {
				// ⚠️ 必须显式给 DSH_HOME：runner 靠它定位 adbkey，缺了就静默回落。
				env: { ...process.env, DSH_HOME: home },
				stdio: ['ignore', 'pipe', 'pipe'],
			});
		} catch (error) {
			resolve({ stdout: '', stderr: String(error?.message ?? error), code: null, timedOut: false });
			return;
		}
		let stdout = '';
		let stderr = '';
		let timedOut = false;
		const timer = setTimeout(() => {
			timedOut = true;
			child.kill('SIGKILL');
		}, PROBE_TIMEOUT_MS);
		child.stdout.setEncoding('utf8');
		child.stderr.setEncoding('utf8');
		child.stdout.on('data', (chunk) => {
			stdout += chunk;
		});
		child.stderr.on('data', (chunk) => {
			stderr += chunk;
		});
		child.on('error', (error) => {
			done({ stdout, stderr: `${stderr}${error?.message ?? error}`, code: null, timedOut });
		});
		child.on('close', (code) => {
			done({ stdout, stderr, code, timedOut });
		});
	});
}

/**
 * 端到端探测一次特权能力。
 * @returns {Promise<object>} 可直接 JSON 序列化的状态快照。
 */
async function probe() {
	const home = homeDir();
	const runner = runnerPath(home);
	const keyPath = path.join(home, 'adbkey');

	const runnerStat = await statFile(runner);
	const keyBefore = await statFile(keyPath);

	/** @type {any} */
	const snapshot = {
		home,
		runner: { path: runner, ok: runnerStat !== null, size: runnerStat?.size ?? 0 },
		key: {
			path: keyPath,
			ok: keyBefore !== null,
			size: keyBefore?.size ?? 0,
			mtimeMs: keyBefore?.mtimeMs ?? 0,
			importedNow: false,
			origin: null,
		},
		identity: { ok: false, uid: null, reason: '', log: [] },
	};

	if (runnerStat === null) {
		snapshot.identity.reason = '执行通道文件不存在，无法探测';
		return snapshot;
	}

	const result = await runRunner(runner, 'id -u', home);
	const uid = result.stdout.trim();

	// runner 的 `[dsh-adb] …` 行是给用户看的原因（密钥导入、回落原因），原样带上。
	snapshot.identity.log = result.stderr
		.split('\n')
		.map((line) => line.trim())
		.filter((line) => line.startsWith(TAG));

	if (uid === '2000') {
		snapshot.identity.ok = true;
		snapshot.identity.uid = '2000';
	} else {
		const fallback = FALLBACK_RE.exec(result.stderr);
		snapshot.identity.uid = uid === '' ? null : uid;
		snapshot.identity.reason = fallback !== null
			? fallback[1]
			: result.timedOut
				? `探测超时（${PROBE_TIMEOUT_MS / 1000} 秒）`
				: uid === ''
					? '执行通道没有返回身份'
					: `实际身份是 uid ${uid}，没有拿到 uid 2000`;
	}

	// 密钥可能是**这次探测**才导入的（runner 首次运行会搬运中转文件），复查一次。
	const keyAfter = await statFile(keyPath);
	if (keyAfter !== null) {
		snapshot.key.ok = true;
		snapshot.key.size = keyAfter.size;
		snapshot.key.mtimeMs = keyAfter.mtimeMs;
		snapshot.key.importedNow = keyBefore === null;
	}

	// 密钥**来源**：`imported`（从 PC 中转文件搬进来，设备已信任，直接能用）
	// 还是 `generated`（App 现场生成，**设备尚未信任，必须先配对**）。
	//
	// ⚠️ 光看密钥文件在不在会误报 —— 两种情况都"有密钥"，但只有前者能直接拿到
	// uid 2000。来源由 runner 在写入密钥那一刻落盘到 `adbkey.origin`
	// （见 assets/dsh-android-shell.mjs 的 `markKeyOrigin()`），上面那次 `id -u`
	// 已经跑过 `ensureKey()`，所以此刻这个文件一定是最新的。
	snapshot.key.origin = null;
	try {
		const raw = (await readFile(path.join(home, 'adbkey.origin'), 'utf8')).trim();
		if (raw === 'imported' || raw === 'generated' || raw === 'paired') snapshot.key.origin = raw;
	} catch {
		// 老版本装出来的密钥没有这个标记，或者密钥还没生成 —— 交给 UI 兜底
	}

	// 无线调试（Android 11+）的端口：由 Kotlin 侧的 `NsdManager` 发现后写入
	// `$DSH_HOME/adb-wireless-port`。Node 侧读不到也发不出 mDNS（SELinux 挡组播），
	// 所以这里只读文件，不做任何探测 —— 文件不在就说明无线调试没开。
	//
	// 格式 `<connect端口> [<配对端口>]`，见 assets/dsh-android-shell.mjs。
	snapshot.wireless = { connect: null, pairing: null };
	try {
		const raw = (await readFile(path.join(home, 'adb-wireless-port'), 'utf8')).trim();
		const [c, p] = raw.split(/\s+/);
		const connect = Number.parseInt(c, 10);
		const pairing = Number.parseInt(p, 10);
		if (Number.isInteger(connect) && connect > 0) snapshot.wireless.connect = connect;
		if (Number.isInteger(pairing) && pairing > 0) snapshot.wireless.pairing = pairing;
	} catch {
		// 文件不存在 = 无线调试没开或还没发现，属于正常情况
	}

	return snapshot;
}

/**
 * 挂上「特权权限」状态接口。
 * @param {object} ctx - Cordis 上下文（已注入 `webServer`）。
 */
export function apply(ctx) {
	ctx.effect(() => ctx.webServer.register({
		kind: 'exact',
		path: ROUTE,
		handler: (req, res) => {
			if (req.method !== 'GET' && req.method !== 'HEAD') {
				res.writeHead(405, { allow: 'GET, HEAD' });
				res.end();
				return;
			}
			probe().then((body) => {
				res.writeHead(200, {
					'content-type': 'application/json; charset=utf-8',
					// 状态含密钥存在性与时间戳，不给任何一层缓存。
					'cache-control': 'no-store',
				});
				res.end(req.method === 'HEAD' ? undefined : JSON.stringify(body));
			}).catch((error) => {
				ctx.logger?.warn?.(`[dsh-android-mobile] 特权状态探测失败: ${error?.message ?? error}`);
				if (res.headersSent) {
					res.destroy();
					return;
				}
				res.writeHead(500, { 'content-type': 'application/json; charset=utf-8' });
				res.end(JSON.stringify({ error: String(error?.message ?? error) }));
			});
		},
	}), 'dsh-android-mobile: privileged status route');
}
