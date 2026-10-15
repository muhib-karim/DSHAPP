/**
 * dsh-remote-desktop —— 电脑端（host half，装在客户电脑的 DSH 里）。
 *
 * ## 这个插件做什么
 *
 * 把本机 DSH 的会话事件流经 Cloudflare 隧道搬到手机，让手机能「进入电脑目录、
 * 在电脑上发起对话」。公网**只传文本**（用户几串字 + AI 回复 + 事件 JSON），
 * 不映射桌面、不映射 Web UI。
 *
 * ## 架构（路 B：电脑跑 agent loop，手机搬事件流）
 *
 * ```
 *   手机 WebView ──HTTPS──> dsh-remote.jiulingssh.xyz ──CF隧道──> 本机 :17890
 *                                                                    │
 *                                                            本插件的中继服务
 *                                                                    │
 *                                                    ctx.sessionController（官方命令层）
 * ```
 *
 * 为什么不是「手机跑 loop、电脑当文件后端」：那样每个文件内容都要在公网来回滚，
 * 带宽与延迟都不可接受。路 B 下电脑是唯一真源，手机只搬事件。
 *
 * ## ⚠️ 关键设计：复用官方 `sessionController`，不自己造 agent
 *
 * 本插件**不**手工拼 `ctx.agents.create(...)`。官方 `SessionCommandController.create()`
 * 内部会 `agents.ensureSession(sessionId, cwd, ...)` —— 那会走 agent factory 建出
 * 一个**真 agent**（带 preset、带模型选择、带工具），这是手搓很难对齐的。
 *
 * 所以：
 * - 建会话 → `ctx.sessionController.create({ sessionId, cwd })`
 * - 发消息 → `ctx.sessionController.prompt({ sessionId, content, requestId })`
 * - 中断   → `ctx.sessionController.cancel({ sessionId })`
 *
 * ⚠️ **`prompt` 必须带唯一的 `requestId`**：官方用 `hasPromptRequest(agent, requestId)`
 * 做幂等去重，而它判的是 `source.rpcId === requestId`。若两边都是 `undefined`，
 * 第二次发消息会被**误判为重复**而静默返回 `{accepted:true}` —— 消息根本没进 agent。
 *
 * ## ⚠️ cwd 绝不跨端传（实测硬约束）
 *
 * Android 是 posix 语义，`path.posix.isAbsolute('C:/Users/...') === false`，
 * 而 `validateSessionHeader` 要求 `cwd` 是绝对路径。更危险的是官方
 * `createOrAdopt()` 里有 `await mkdir(cwd, { recursive: true })` ——
 * 手机端拿电脑路径去建会话会**真去建目录**。
 * 所以两端会话靠 **sessionId** 映射，电脑路径只作纯展示文字。
 * 本插件收到 `cwd` 参数时**只认电脑自己的绝对路径**（手机端不会传电脑路径过来）。
 *
 * ## 中继协议
 *
 * - `POST /api/dsh-remote/rpc`：`{ id, method, params }` → `{ id, ok, result }`
 * - `GET  /api/dsh-remote/stream?session=<id>`：SSE，`event:` 是 durable 事件、
 *   `frame:` 是流式帧
 *
 * 选 SSE 而不是 WebSocket：手机 WebView 里 EventSource 是原生 API，断线自动重连
 * 是浏览器内建的；WS 要自己写心跳与退避。Cloudflare 隧道对 SSE 的支持与普通
 * HTTP 一致。
 *
 * ## ⚠️ 丢帧是安全的（实测结论）
 *
 * 手机端 `ClientAssistantStream.acceptFrame()` 遇到不连续的帧返回
 * `{ type: 'rebaseline' }`，消费点是 `events.restart()` —— 即重连重拉基线。
 * 而 **host 侧的帧永不丢**（进程内事件），重连后从 baseline 能补齐全部已生成内容。
 * 所以这条通道可以做成 best-effort，不必自己设计序号补洞。
 *
 * @module dsh-remote-desktop
 */

import { createServer } from 'node:http';
import { randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { isAbsolute, dirname, join, resolve } from 'node:path';

export const name = 'dsh-jiuling-remote-desktop';

/**
 * `sessionController` 提供官方的建会话/发消息/中断命令层；`sessions` 用于
 * SSE 开场补发历史；中继 HTTP 服务由本插件自建（不用 `webServer`，因为
 * `webServer` 挂在 DSH 自己的 Web 端口上，而隧道的 ingress 指向独立端口 17890）。
 */
export const inject = ['sessionController', 'sessions'];

/** 中继监听端口。与 Cloudflare 隧道的 ingress 必须一致。 */
const RELAY_PORT = 17890;

/** 配置与状态落在 $DSH_HOME 下的这个目录。 */
const STATE_DIR = join(process.env.DSH_HOME ?? join(homedir(), '.dsh'), 'dsh-remote');

/** 访问令牌文件。手机端要用同一个令牌才能连上。 */
const TOKEN_PATH = join(STATE_DIR, 'token.txt');

/** 隧道凭据文件（可选：覆盖内置的隧道令牌）。 */
const TUNNEL_PATH = join(STATE_DIR, 'tunnel.txt');

/**
 * 内置访问令牌 —— **必须与 `dsh-jiuling-remote-mobile` 里的内置值逐字相同**。
 *
 * 两端同值 ⇒ 装完即可连通，用户不用手工同步任何东西。想隔离就改这个文件：
 * 电脑端 `<DSH_HOME>/dsh-remote/token.txt` 与手机端
 * `<DSH_HOME>/dsh-remote/mobile.json` 的 `token` 字段，两边改成一样的即可。
 */
const DEFAULT_TOKEN = 'f6721b0cf82414709df172056193c78a6c374e9a920e51145dd7883923cf16e7';

/**
 * 内置 Cloudflare 隧道令牌（账号 `cf45cf053dfe24156bfcad084a02b884` 下的
 * `dsh-remote` 隧道，ingress = `dsh-remote.jiulingssh.xyz` → `localhost:17890`）。
 *
 * 装完即用，不必再去 Cloudflare 后台复制。要换成自己的隧道就改
 * `<DSH_HOME>/dsh-remote/tunnel.txt`，或在插件 config 里给 `tunnelToken`。
 */
const DEFAULT_TUNNEL_TOKEN =
	'eyJhIjoiY2Y0NWNmMDUzZGZlMjQxNTZiZmNhZDA4NGEwMmI4ODQiLCJ0IjoiYTk4MjA2ZmEtNmEzOC00N2NiLWFhMGQtNTQxZDRjNTg2YjQyIiwicyI6IlJFdng4NU8zNlhtOTc0M0hLSk9nb3M1WUJsUGFsVkZKZWl4UitRQUwxTHpOWG10ZURTY0dCWHZydEU1MmFMNHc4ajBsTlBTVjdvZHl1a0M5b1g3ayt3PT0ifQ==';

/**
 * 找 cloudflared 可执行文件。
 *
 * @returns 可执行文件路径，或 `undefined`（未安装）。
 */
function findCloudflared() {
	const candidates = [
		process.env.DSH_REMOTE_CLOUDFLARED,
		process.env.LOCALAPPDATA ? join(process.env.LOCALAPPDATA, 'cloudflared', 'cloudflared.exe') : undefined,
		'/usr/local/bin/cloudflared',
		'/usr/bin/cloudflared',
	].filter(Boolean);
	for (const candidate of candidates) if (existsSync(candidate)) return candidate;
	return undefined;
}

/**
 * 读或建访问令牌。
 *
 * 首次运行把内置令牌落盘，用户可以直接改这个文件来换令牌（两端改成一样即可）。
 * **不随机生成** —— 随机的话手机端装完就连不上，必须让用户手工同步一次，
 * 那就违背了「装完即可用」。
 *
 * @param ctx - Cordis 上下文（只用 logger）。
 * @returns 令牌字符串。
 */
function ensureToken(ctx) {
	try {
		const existing = readFileSync(TOKEN_PATH, 'utf8').trim();
		if (existing.length >= 16) return existing;
	} catch {
		/* 文件不存在：下面落盘一份内置值 */
	}
	try {
		mkdirSync(STATE_DIR, { recursive: true });
		writeFileSync(TOKEN_PATH, DEFAULT_TOKEN, { mode: 0o600 });
	} catch (error) {
		ctx.logger?.warn?.(`[dsh-remote] 写令牌文件失败（仍用内置令牌）: ${String(error?.message ?? error)}`);
	}
	return DEFAULT_TOKEN;
}

/** 组一条 SSE 消息。 */
function sse(eventName, payload) {
	return `event: ${eventName}\ndata: ${JSON.stringify(payload)}\n\n`;
}

/**
 * 列出本机存在的盘符（仅 Windows）。
 *
 * 没有这一步，用户从主目录一路退到 `C:\` 就**再也出不去**了 —— 驱动器根没有
 * 上级目录，`parent` 为空，「上一级」按钮随即失灵，D 盘永远不会出现在列表里。
 *
 * @returns `['C:\\', 'D:\\', ...]`。
 */
function listDrives() {
	if (process.platform !== 'win32') return [];
	const drives = [];
	for (let code = 65; code <= 90; code += 1) {
		const root = `${String.fromCharCode(code)}:\\`;
		try {
			if (existsSync(root)) drives.push(root);
		} catch {
			/* 无权限探测：忽略该盘符 */
		}
	}
	return drives;
}

/**
 * 列一个目录下的子目录（跟随符号链接，与官方 browse 后端一致）。
 *
 * @param input - 请求的路径；缺省用主目录。
 * @returns `{ path, home, parent, entries }`。
 */
function listDirectory(input) {
	const home = homedir();
	const target = input === undefined || input === '' ? home : String(input);
	if (!isAbsolute(target)) throw new Error('path must be absolute');
	const abs = resolve(target);
	const entries = [];
	for (const entry of readdirSync(abs, { withFileTypes: true })) {
		let isDir = entry.isDirectory();
		if (entry.isSymbolicLink()) {
			try {
				isDir = statSync(join(abs, entry.name)).isDirectory();
			} catch {
				continue; // 断链：跳过
			}
		}
		if (!isDir) continue;
		entries.push({ name: entry.name, path: join(abs, entry.name), isDirectory: true, enterable: true });
	}
	entries.sort((a, b) => a.name.localeCompare(b.name));
	// ⚠️ 用 dirname 而不是切字符串：手切 `C:\Users` 会得到 `C:`，
	// 而 win32 下 `isAbsolute('C:') === false` ⇒ 点「上一级」直接报错。
	// dirname('C:\Users') = 'C:\'，dirname('C:\') = 'C:\'（等于自身 ⇒ 到顶了）。
	const up = dirname(abs);
	const parent = up === abs ? '' : up;
	// 站在驱动器根上（parent 为空）时，把其它盘符一并列出来当出口。
	if (parent === '') {
		const current = abs.replace(/[\\/]+$/, '').toLowerCase();
		const drives = listDrives()
			.filter((drive) => drive.replace(/[\\/]+$/, '').toLowerCase() !== current)
			.map((drive) => ({ name: drive, path: drive, isDirectory: true, enterable: true }));
		entries.unshift(...drives);
	}
	return {
		path: abs,
		home,
		parent,
		entries,
	};
}

/**
 * 处理一条 RPC。
 *
 * @param ctx - Cordis 上下文。
 * @param method - 方法名。
 * @param params - 参数。
 */
async function handleRpc(ctx, method, params) {
	const controller = ctx.sessionController;
	if (controller === undefined) throw new Error('sessionController 不可用');

	switch (method) {
		/** 探测：手机端「测试连接」用。 */
		case 'ping':
			return { ok: true, platform: process.platform, home: homedir(), pid: process.pid };

		/** 列电脑上的会话（只给元数据）。 */
		case 'listSessions': {
			const result = await controller.list({});
			return { sessions: result?.items ?? [] };
		}

		/**
		 * 建会话：手机发起，电脑在**电脑自己的** cwd 下建，并建出真 agent。
		 *
		 * `sessionId` 由手机端生成并传入，两端保持一致，事件才能直接互搬。
		 */
		case 'createSession': {
			const sessionId = params.sessionId;
			if (typeof sessionId !== 'string' || sessionId === '') throw new Error('sessionId required');
			const cwd = params.cwd;
			if (typeof cwd !== 'string' || cwd === '' || !isAbsolute(cwd)) {
				throw new Error('cwd must be an absolute path on this machine');
			}
			// 官方命令层：内部走 agents.ensureSession()，会建出真 agent。
			const created = await controller.create({
				sessionId,
				cwd,
				...(typeof params.presetId === 'string' && params.presetId !== ''
					? { agentPreset: params.presetId }
					: {}),
			});
			return { sessionId: created?.sessionId ?? sessionId, cwd, agentPreset: created?.agentPreset ?? null };
		}

		/** 发消息：驱动电脑上的 agent。 */
		case 'prompt': {
			const sessionId = params.sessionId;
			const text = params.text;
			if (typeof sessionId !== 'string' || typeof text !== 'string' || text.trim() === '') {
				throw new Error('sessionId and non-empty text required');
			}
			// ⚠️ requestId 必须唯一：官方用它做幂等去重，两边都是 undefined 会误判重复。
			const requestId = typeof params.requestId === 'string' && params.requestId !== ''
				? params.requestId
				: randomUUID();
			const accepted = await controller.prompt({
				sessionId,
				requestId,
				content: [{ type: 'text', text }],
				...(params.mode === 'steer' ? { mode: 'steer' } : {}),
			});
			return { sessionId, requestId, accepted: accepted?.accepted === true };
		}

		/** 中断当前生成。 */
		case 'cancel': {
			const sessionId = params.sessionId;
			if (typeof sessionId !== 'string' || sessionId === '') throw new Error('sessionId required');
			const accepted = await controller.cancel({ sessionId });
			return { sessionId, accepted: accepted?.accepted === true };
		}

		/** 列电脑上的目录（手机的「电脑」页签用）。 */
		case 'listDirectory':
			return listDirectory(params.path);

		default:
			throw new Error(`unknown method "${method}"`);
	}
}

/**
 * 启动中继 HTTP 服务。
 *
 * @param ctx - Cordis 上下文。
 * @param token - 访问令牌。
 * @returns `{ close }`。
 */
function startRelay(ctx, token) {
	/** sessionId → Set<res>：正在跟随该会话的 SSE 连接。 */
	const followers = new Map();

	/** 往某会话的所有跟随者推一条 SSE。 */
	const push = (sessionId, eventName, payload) => {
		const set = followers.get(sessionId);
		if (set === undefined || set.size === 0) return;
		const chunk = sse(eventName, payload);
		for (const res of set) {
			try {
				res.write(chunk);
			} catch (error) {
				ctx.logger?.warn?.(`[dsh-remote] 推送失败: ${String(error?.message ?? error)}`);
				set.delete(res);
			}
		}
	};

	// ---- durable 通道：落盘的事件 ----
	ctx.on(
		'session/event',
		(session, event) => {
			push(session.id, 'event', event);
		},
		{ global: true },
	);

	// ---- 流式通道：进程内事件，不落盘，只给逐字动画 ----
	ctx.on(
		'agent/assistant-stream',
		({ agent, frame }) => {
			push(agent.session.id, 'frame', frame);
		},
		{ global: true },
	);

	const server = createServer((req, res) => {
		const url = new URL(req.url ?? '/', `http://127.0.0.1:${RELAY_PORT}`);

		// 鉴权：令牌走 header 或 query（EventSource 不能设 header，所以 query 也要收）。
		const provided = req.headers['x-dsh-remote-token'] ?? url.searchParams.get('token') ?? '';
		if (provided !== token) {
			res.writeHead(401, { 'content-type': 'application/json; charset=utf-8' });
			res.end(JSON.stringify({ error: 'unauthorized' }));
			return;
		}

		// ---- SSE：跟随一个会话的事件流 ----
		if (url.pathname === '/api/dsh-remote/stream' && req.method === 'GET') {
			const sessionId = url.searchParams.get('session');
			if (sessionId === null) {
				res.writeHead(400, { 'content-type': 'application/json; charset=utf-8' });
				res.end(JSON.stringify({ error: 'session query required' }));
				return;
			}
			res.writeHead(200, {
				'content-type': 'text/event-stream; charset=utf-8',
				'cache-control': 'no-store, no-transform',
				connection: 'keep-alive',
				// 关掉代理缓冲，否则事件会攒着一起发，逐字动画变成一次性出现。
				'x-accel-buffering': 'no',
			});
			// 注释行当心跳：既让浏览器知道连接活着，也让中间代理不掐空闲连接。
			res.write(': connected\n\n');

			let set = followers.get(sessionId);
			if (set === undefined) {
				set = new Set();
				followers.set(sessionId, set);
			}
			set.add(res);

			// 开场把已有事件补齐（断线重连后能接上）。
			try {
				const session = ctx.sessions?.get?.(sessionId);
				if (session !== undefined) {
					for (const event of session.snapshotEvents()) res.write(sse('event', event));
				}
			} catch (error) {
				ctx.logger?.warn?.(`[dsh-remote] 补发历史失败: ${String(error?.message ?? error)}`);
			}

			const heartbeat = setInterval(() => {
				try {
					res.write(': ping\n\n');
				} catch {
					clearInterval(heartbeat);
				}
			}, 15000);

			const cleanup = () => {
				clearInterval(heartbeat);
				set.delete(res);
				if (set.size === 0) followers.delete(sessionId);
			};
			req.on('close', cleanup);
			req.on('error', cleanup);
			return;
		}

		// ---- RPC：手机发起的动作 ----
		if (url.pathname === '/api/dsh-remote/rpc' && req.method === 'POST') {
			const chunks = [];
			req.on('data', (c) => chunks.push(c));
			req.on('end', async () => {
				const reply = (payload) => {
					res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
					res.end(JSON.stringify(payload));
				};
				let body;
				try {
					body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
				} catch (error) {
					res.writeHead(400, { 'content-type': 'application/json; charset=utf-8' });
					res.end(JSON.stringify({ error: `bad json: ${String(error?.message ?? error)}` }));
					return;
				}
				try {
					reply({ id: body.id, ok: true, result: await handleRpc(ctx, body.method, body.params ?? {}) });
				} catch (error) {
					reply({ id: body.id, ok: false, error: String(error?.message ?? error) });
				}
			});
			return;
		}

		res.writeHead(404, { 'content-type': 'application/json; charset=utf-8' });
		res.end(JSON.stringify({ error: 'not found' }));
	});

	server.listen(RELAY_PORT, '127.0.0.1');
	return {
		close: () => {
			for (const set of followers.values()) {
				for (const res of set) {
					try {
						res.end();
					} catch {
						/* 连接已断 */
					}
				}
			}
			followers.clear();
			server.close();
		},
	};
}

/**
 * 启动 cloudflared 隧道（可选）。
 *
 * 没配置 token 就只跑中继 —— 用户仍可用局域网或自己的反代，本插件不强制隧道。
 *
 * @param ctx - Cordis 上下文。
 * @param token - 隧道 token。
 * @returns 子进程，或 `undefined`。
 */
function startTunnel(ctx, token) {
	const bin = findCloudflared();
	if (bin === undefined) {
		ctx.logger?.warn?.('[dsh-remote] 未找到 cloudflared，跳过隧道（中继仍在本地监听）');
		return undefined;
	}
	const child = spawn(bin, ['tunnel', '--no-autoupdate', 'run', '--token', token], {
		stdio: ['ignore', 'pipe', 'pipe'],
		windowsHide: true,
	});
	const relay = (buffer) => {
		const line = String(buffer).trim();
		if (line) ctx.logger?.info?.(`[dsh-remote][tunnel] ${line}`);
	};
	child.stdout?.on('data', relay);
	child.stderr?.on('data', relay);
	child.on('error', (error) => {
		ctx.logger?.warn?.(`[dsh-remote] 隧道启动失败: ${String(error?.message ?? error)}`);
	});
	child.on('exit', (code) => {
		ctx.logger?.warn?.(`[dsh-remote] 隧道退出，code=${code}`);
	});
	return child;
}

/**
 * 读隧道凭据：文件优先，没有就用内置的。
 *
 * @returns 隧道 token 字符串。
 */
function readTunnelToken() {
	try {
		const value = readFileSync(TUNNEL_PATH, 'utf8').trim();
		return value === '' ? DEFAULT_TUNNEL_TOKEN : value;
	} catch {
		return DEFAULT_TUNNEL_TOKEN;
	}
}

/**
 * 插件入口。
 *
 * @param ctx - Cordis 上下文。
 * @param config - 插件配置：`{ tunnelToken?, autoTunnel? }`。
 */
export function apply(ctx, config = {}) {
	const token = ensureToken(ctx);
	const state = { relay: undefined, tunnel: undefined };

	ctx.effect(() => {
		try {
			state.relay = startRelay(ctx, token);
			ctx.logger?.info?.(`[dsh-remote] 中继已监听 http://127.0.0.1:${RELAY_PORT}`);
			ctx.logger?.info?.(`[dsh-remote] 访问令牌: ${token}`);
			ctx.logger?.info?.(`[dsh-remote] 令牌文件: ${TOKEN_PATH}`);
		} catch (error) {
			ctx.logger?.error?.(`[dsh-remote] 中继启动失败: ${String(error?.message ?? error)}`);
		}

		const tunnelToken =
			config.tunnelToken ?? process.env.DSH_REMOTE_TUNNEL_TOKEN ?? readTunnelToken();
		if (config.autoTunnel !== false) {
			state.tunnel = startTunnel(ctx, tunnelToken);
		} else {
			ctx.logger?.info?.('[dsh-remote] autoTunnel=false，只跑本地中继（不起隧道）');
		}

		return () => {
			try {
				state.tunnel?.kill();
			} catch {
				/* 已退出 */
			}
			try {
				state.relay?.close();
			} catch {
				/* 已关闭 */
			}
		};
	}, 'dsh-remote-desktop: relay + tunnel');
}

export { RELAY_PORT, TOKEN_PATH, TUNNEL_PATH, listDirectory, handleRpc };
