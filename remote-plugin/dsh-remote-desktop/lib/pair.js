/**
 * 配对流程（电脑端）—— 激活码换隧道，配对码连手机。
 *
 * ## 一次配对的完整时序
 *
 * ```
 *   客户电脑                      jiulingssh.xyz 服务器              手机
 *      │                                  │                          │
 *      │ ① 填激活码 POST /pair/register    │                          │
 *      ├─────────────────────────────────>│                          │
 *      │   服务器建隧道 + 建 DNS + 发令牌   │                          │
 *      │<─────────────────────────────────┤                          │
 *      │ {hostname, tunnelToken, accessToken}                         │
 *      │                                  │                          │
 *      │ ② 起 cloudflared（用 tunnelToken） │                          │
 *      │ ③ POST /pair/handshake ──────────>│                          │
 *      │<── {pairCode: "A7K2M9", 600s} ────┤                          │
 *      │ ④ 屏幕上显示二维码 + 配对码        │                          │
 *      │                                  │  ⑤ 输码 POST /pair/claim │
 *      │                                  │<─────────────────────────┤
 *      │                                  │──> {hostname, accessToken}
 *      │                                  │                          │
 *      │<════════ 隧道建立，两端同令牌，开始搬事件 ═══════════════════>│
 * ```
 *
 * ## 为什么要有「配对码」这一跳
 *
 * 服务器下发给电脑的是 `{hostname, tunnelToken, accessToken}`，其中
 * `accessToken` 是 64 位十六进制 —— 塞进二维码会是一大坨，手输更不可能。
 * 而配对码只有 6 位，**二维码和手输两条路都成立**。
 *
 * 关键在于：**凭据本身不经过二维码**。二维码里只有那个 6 位码，
 * 真正的令牌由手机直接向服务器换取。所以哪怕二维码被人拍到，
 * 泄露的也只是一次性的、10 分钟就过期的短码。
 *
 * ## 为什么二维码里只有 6 位码，没有别的
 *
 * 试过编码成 `dsh-pair:A7K2M9` 这种带 scheme 的串，好处是将来能扩展，
 * 坏处是**用户拿系统相机扫出来的就是一串看不懂的东西**，还得自己从中
 * 抠出那 6 位。而现在扫出来就是 `A7K2M9`，照着输即可 —— 面向大众分发时
 * 「扫不出也能照抄」比「协议优雅」重要得多。
 *
 * @module dsh-remote-desktop/pair
 */

import { chmodSync, createWriteStream, existsSync, mkdirSync, readFileSync, renameSync, statSync, unlinkSync, writeFileSync } from 'node:fs';
import { homedir, hostname as osHostname } from 'node:os';
import { join } from 'node:path';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { qrSvg } from './qr.js';

/** 配对服务的公网地址（Cloudflare 隧道，走 HTTPS）。 */
export const PAIR_ENDPOINT = 'https://pair.jiulingssh.xyz';

/**
 * 平台 → cloudflared 文件名。
 *
 * 与服务器上 `/pair/cloudflared/` 的白名单**必须一致**，改一边就得改另一边。
 * 只列我们真的分发过的四个：Windows x64、Linux x64、macOS x64 / arm64。
 */
const PLATFORM_FILES = {
	'win32-x64': 'cloudflared-windows-amd64.exe',
	'linux-x64': 'cloudflared-linux-amd64',
	'darwin-x64': 'cloudflared-darwin-amd64',
	'darwin-arm64': 'cloudflared-darwin-arm64',
};

/**
 * 带超时的配对服务请求。
 *
 * ⚠️ 不用 `AbortSignal.timeout()`：那个 API 要 Node 17.3+，而这里宁可自己拿
 * `AbortController` + `setTimeout` 拼一次，省得为一个便利函数抬高版本要求。
 *
 * @param path - 相对 `/pair/` 的路径，如 `/pair/register`。
 * @param body - 请求体；`undefined` 表示发 GET。
 * @param timeoutMs - 超时毫秒数。
 * @returns `{ ok, status, payload }`。
 */
async function pairRequest(path, body, timeoutMs = 30000) {
	const controller = new AbortController();
	const timer = setTimeout(() => controller.abort(), timeoutMs);
	try {
		const response = await fetch(PAIR_ENDPOINT + path, {
			method: body === undefined ? 'GET' : 'POST',
			headers: body === undefined ? {} : { 'content-type': 'application/json' },
			body: body === undefined ? undefined : JSON.stringify(body),
			signal: controller.signal,
		});
		const text = await response.text();
		let payload;
		try {
			payload = JSON.parse(text);
		} catch {
			payload = { error: 'bad-json', message: text.slice(0, 200) };
		}
		return { ok: response.ok, status: response.status, payload };
	} catch (error) {
		const aborted = error?.name === 'AbortError';
		return {
			ok: false,
			status: 0,
			payload: {
				error: aborted ? 'timeout' : 'network',
				message: aborted
					? `连接配对服务器超时（${Math.round(timeoutMs / 1000)} 秒）`
					: `连不上配对服务器：${String(error?.message ?? error)}`,
			},
		};
	} finally {
		clearTimeout(timer);
	}
}

/** 规范化激活码：去空白、去横杠、转大写（用户从微信里复制粘贴常带这些）。 */
function normalizeCode(raw) {
	return String(raw ?? '')
		.trim()
		.toUpperCase()
		.replace(/[\s-]/g, '');
}

/**
 * 造一个配对流程实例。
 *
 * @param options - `{ stateDir, logger }`。
 * @returns 配对状态与动作的集合。
 */
export function createPairing({ stateDir, logger }) {
	const STATE_PATH = join(stateDir, 'pair.json');
	const BIN_DIR = join(stateDir, 'bin');

	/** cloudflared 自动下载的进度（给 UI 显示；没在下载时 `active` 为 false）。 */
	const download = { active: false, received: 0, total: 0, error: '' };

	/** 读配对状态；没配对过或文件坏了都返回 `undefined`。 */
	function read() {
		try {
			const parsed = JSON.parse(readFileSync(STATE_PATH, 'utf8'));
			if (typeof parsed?.hostname !== 'string' || typeof parsed?.tunnelToken !== 'string') {
				return undefined;
			}
			return parsed;
		} catch {
			return undefined;
		}
	}

	/**
	 * 落盘配对状态。
	 *
	 * ⚠️ 走「临时文件 + rename」而不是直接写：这个文件里是**唯一一份**隧道凭据，
	 * 写一半断电就永久丢了（得重新生成隧道才能恢复）。rename 在同一文件系统上是
	 * 原子的，要么旧内容要么新内容，不会出现半截 JSON。
	 *
	 * @param record - 要保存的记录。
	 */
	function save(record) {
		mkdirSync(stateDir, { recursive: true });
		const tmp = `${STATE_PATH}.${process.pid}.tmp`;
		writeFileSync(tmp, JSON.stringify(record, null, 2), { mode: 0o600 });
		renameSync(tmp, STATE_PATH);
	}

	/** 清掉配对状态（换激活码 / 解绑时用）。 */
	function clear() {
		try {
			unlinkSync(STATE_PATH);
		} catch {
			/* 本来就没有 */
		}
	}

	/**
	 * 用激活码向服务器注册，拿到这台电脑专属的隧道与令牌。
	 *
	 * 服务器对同一个激活码是**幂等**的：重复注册会原样返回上次的隧道，
	 * 不再消耗 Cloudflare 配额。所以重装插件、换台机器重填同一个码都安全。
	 *
	 * @param activationCode - 用户填的激活码。
	 * @returns `{ ok, record?, message }`。
	 */
	async function register(activationCode) {
		const code = normalizeCode(activationCode);
		if (code === '') return { ok: false, message: '请填写激活码' };

		logger?.info?.(`[dsh-remote][pair] 正在用激活码 ${code} 注册…`);
		const { ok, payload } = await pairRequest('/pair/register', {
			code,
			device: osHostname(),
		});
		if (!ok || typeof payload?.hostname !== 'string') {
			logger?.warn?.(`[dsh-remote][pair] 注册失败: ${JSON.stringify(payload)}`);
			return { ok: false, message: payload?.message ?? `注册失败（HTTP ${payload?.error ?? '?'}）` };
		}

		const record = {
			activationCode: code,
			subdomain: payload.subdomain,
			hostname: payload.hostname,
			tunnelToken: payload.tunnelToken,
			accessToken: payload.accessToken,
			pairedAt: Date.now(),
			reused: payload.reused === true,
		};
		save(record);
		logger?.info?.(`[dsh-remote][pair] 注册成功: ${record.hostname}${record.reused ? '（复用已有隧道）' : ''}`);
		return { ok: true, record, message: `已绑定 ${record.hostname}` };
	}

	/**
	 * 生成一个 6 位配对码（给手机用），并渲染成二维码。
	 *
	 * 每次调用都会换一个新码（旧码作废），所以「重新生成」按钮天然可用。
	 *
	 * @returns `{ ok, pairCode?, expiresIn?, svg?, message }`。
	 */
	async function handshake() {
		const record = read();
		if (record === undefined) return { ok: false, message: '还没有配对，请先填激活码' };

		const { ok, payload } = await pairRequest('/pair/handshake', { code: record.activationCode });
		if (!ok || typeof payload?.pairCode !== 'string') {
			return { ok: false, message: payload?.message ?? '生成配对码失败，请重试' };
		}
		return {
			ok: true,
			pairCode: payload.pairCode,
			expiresIn: payload.expiresIn,
			expiresAt: Date.now() + Number(payload.expiresIn ?? 600) * 1000,
			// 二维码里**只有那 6 位码**，见文件头「为什么二维码里只有 6 位码」。
			svg: qrSvg(payload.pairCode, { margin: 2, scale: 1 }),
			message: '把这个码输到手机上即可',
		};
	}

	/**
	 * 找到 cloudflared；找不到就**自动从配对服务器下载**。
	 *
	 * ## 为什么不直接去 GitHub 下
	 *
	 * 实测：**国内服务器直连 `github.com` 是不通的**（`curl` 返回 000），
	 * 客户电脑大概率同样。让每个客户各自去 GitHub 撞一次，结果就是
	 * 大量客户卡在「隧道起不来」，而用户完全看不出这是网络问题。
	 *
	 * 所以由配对服务器中转一次（它已经能出网，且文件已经放在上面）。
	 *
	 * @returns `{ ok, path?, message, cached? }`。
	 */
	async function ensureCloudflared() {
		const cached = findCloudflared();
		if (cached !== undefined) return { ok: true, path: cached, cached: true, message: '已找到 cloudflared' };

		const fileName = PLATFORM_FILES[`${process.platform}-${process.arch}`];
		if (fileName === undefined) {
			return {
				ok: false,
				message: `暂不支持这个平台（${process.platform}-${process.arch}），请手工安装 cloudflared 后重试`,
			};
		}

		const target = join(BIN_DIR, process.platform === 'win32' ? 'cloudflared.exe' : 'cloudflared');
		const tmp = `${target}.part`;
		mkdirSync(BIN_DIR, { recursive: true });
		logger?.info?.(`[dsh-remote][pair] 正在下载 cloudflared（${fileName}）…`);

		download.active = true;
		download.received = 0;
		download.total = 0;
		download.error = '';

		const controller = new AbortController();
		const timer = setTimeout(() => controller.abort(), 300000);
		try {
			const response = await fetch(`${PAIR_ENDPOINT}/pair/cloudflared/${fileName}`, {
				signal: controller.signal,
			});
			if (!response.ok || response.body === null) {
				throw new Error(`HTTP ${response.status}`);
			}
			download.total = Number(response.headers.get('content-length') ?? 0);

			// 逐块写盘并记账：55 MB 全读进内存没必要，且 UI 要显示进度。
			const source = Readable.fromWeb(response.body);
			source.on('data', (chunk) => {
				download.received += chunk.length;
			});
			await pipeline(source, createWriteStream(tmp));
			clearTimeout(timer);

			const size = statSync(tmp).size;
			if (size < 1024 * 1024) throw new Error(`文件太小（${size} 字节），可能不是可执行文件`);
			renameSync(tmp, target);
			if (process.platform !== 'win32') chmodSync(target, 0o755);

			logger?.info?.(`[dsh-remote][pair] cloudflared 已就绪: ${target}（${size} 字节）`);
			return { ok: true, path: target, cached: false, message: 'cloudflared 已下载完成' };
		} catch (error) {
			const aborted = error?.name === 'AbortError';
			download.error = aborted ? '下载超时' : String(error?.message ?? error);
			logger?.warn?.(`[dsh-remote][pair] 下载 cloudflared 失败: ${download.error}`);
			try {
				unlinkSync(tmp);
			} catch {
				/* 没落盘 */
			}
			return { ok: false, message: `下载 cloudflared 失败：${download.error}` };
		} finally {
			clearTimeout(timer);
			download.active = false;
		}
	}

	/** 找本机已有的 cloudflared（下载过的排在最前）。 */
	function findCloudflared() {
		const candidates = [
			process.env.DSH_REMOTE_CLOUDFLARED,
			join(BIN_DIR, process.platform === 'win32' ? 'cloudflared.exe' : 'cloudflared'),
			process.env.LOCALAPPDATA ? join(process.env.LOCALAPPDATA, 'cloudflared', 'cloudflared.exe') : undefined,
			'/usr/local/bin/cloudflared',
			'/usr/bin/cloudflared',
			'/opt/homebrew/bin/cloudflared',
		].filter(Boolean);
		for (const candidate of candidates) if (existsSync(candidate)) return candidate;
		return undefined;
	}

	/** 给 UI 的一份快照。 */
	function status() {
		const record = read();
		return {
			paired: record !== undefined,
			activationCode: record?.activationCode ?? '',
			hostname: record?.hostname ?? '',
			pairedAt: record?.pairedAt ?? 0,
			cloudflared: findCloudflared() ?? '',
			download: { ...download },
			home: homedir(),
			platform: `${process.platform}-${process.arch}`,
		};
	}

	return { read, save, clear, register, handshake, ensureCloudflared, findCloudflared, status, STATE_PATH };
}
