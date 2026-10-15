/**
 * dsh-remote-mobile —— 手机端（host half，跑在手机的 DSH 引擎里）。
 *
 * ## 职责
 *
 * 手机是这个方案的「瘦客户端 + 搬运工」：
 * - 手机本地跑**完整官方前端**（零远程 UI 加载，这是本方案相对「映射桌面」的核心优势）；
 * - 手机不跑 LLM。用户在「电脑文件夹」里发的消息，转成 RPC 打到电脑；
 * - 电脑回的 durable 事件写进**手机本地**同名 sessionId 的会话日志。
 *
 * 于是会话列表、历史、工具卡片、流式渲染全部是官方现成组件在工作，
 * 手机端不需要写任何聊天 UI（只写那个「电脑文件夹」入口对话框）。
 *
 * ## ⚠️ cwd 绝不跨端传（实测硬约束）
 *
 * Android 是 posix 语义，`path.posix.isAbsolute('C:/Users/...') === false`，
 * 而 `validateSessionHeader` 要求 cwd 是绝对路径；更危险的是
 * `SessionCommandController.createOrAdopt()` 里有 `await mkdir(cwd, {recursive:true})`。
 * 所以**手机端会话用自己的本地 cwd**，电脑路径只作展示文字，两端靠 sessionId 映射。
 *
 * ## 为什么手机端不装 LLM 也能「发消息」
 *
 * 关键：手机端会话**不要有 live agent**。一旦 `ctx.agents.get(sessionId)` 有值，
 * 官方 `sessionController.prompt` 就会把消息交给那个 agent（真跑手机本地模型）。
 * 本插件**拦截 `sessionController.prompt`**：远程会话的消息转发电脑，
 * 同时用 `Session.append()` 在手机本地落一条 user/message，前端立刻显示用户气泡。
 *
 * 注意 `follow` / `page` 这类历史 RPC 的第三个参数是 `false`（不激活 Agent），
 * 所以手机端打开一个远程会话**不会**触发本地模型，这是本方案能成立的前提。
 *
 * ## 事件搬运（durable 通道）
 *
 * 电脑的 durable 事件按顺序写进手机本地会话：
 * - 直接 `session.append(type, data, opts)`，与电脑端同构，前端零改动；
 * - **跳过 `session/end-seed`** —— 手机端 `Session` 构造时已自动插过一条，
 *   重放时再写会让 seq 错位一位（实测踩过这个坑）。
 *
 * ## 流式通道（best-effort，全局一条）
 *
 * 电脑的流式帧经**一条全局 SSE** 转给浏览器，浏览器侧注入 `assistant/live-chunk`
 * transient（不落盘）。用全局而不是按会话开多条，是因为手机 WebView 对
 * 并发连接有上限，且重开 App 后一条连接就能恢复所有会话的动画。
 *
 * 丢帧是安全的：电脑端帧来自进程内事件永不丢，重连后 durable settlement
 * 会把完整内容补上（实测结论），最坏只是少了逐字动画。
 *
 * @module dsh-remote-mobile
 */

import { randomUUID } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';

export const name = 'dsh-jiuling-remote-mobile';

/** `sessions` 建会话/追加事件，`webServer` 给浏览器挂桥，`sessionController` 拦截 prompt。 */
export const inject = ['sessions', 'sessionController', 'webServer'];

/** 浏览器访问桥的路由。 */
const ROUTE_RPC = '/api/dsh-remote/rpc';
const ROUTE_STREAM = '/api/dsh-remote/stream';

/** 状态目录（手机端）。 */
const STATE_DIR = join(process.env.DSH_HOME ?? join(homedir(), '.dsh'), 'dsh-remote');

/** 连接配置：电脑隧道地址 + 访问令牌。 */
const CONFIG_PATH = join(STATE_DIR, 'mobile.json');

/** 远程会话清单（持久化，重开 App 后能接着搬）。 */
const SESSIONS_PATH = join(STATE_DIR, 'sessions.json');

/** 默认隧道地址。 */
const DEFAULT_ENDPOINT = 'https://dsh-remote.jiulingssh.xyz';

/**
 * 内置访问令牌 —— **必须与 `dsh-jiuling-remote-desktop` 里的内置值逐字相同**。
 *
 * 两端同值 ⇒ 装完即可连通，用户不用手工同步任何东西。想隔离就改这个文件里的
 * `token` 字段，并把电脑端 `<DSH_HOME>/dsh-remote/token.txt` 改成一样的值。
 */
const DEFAULT_TOKEN = 'f6721b0cf82414709df172056193c78a6c374e9a920e51145dd7883923cf16e7';

/**
 * 读配置。首次运行写一份模板，用户直接改文件即可。
 *
 * `token` 为空时退回内置值（与电脑端同值），所以**装完就能连**。
 *
 * @returns `{ endpoint, token }`。
 */
function loadConfig() {
	try {
		const parsed = JSON.parse(readFileSync(CONFIG_PATH, 'utf8'));
		const token = typeof parsed.token === 'string' && parsed.token !== '' ? parsed.token : DEFAULT_TOKEN;
		return {
			endpoint:
				typeof parsed.endpoint === 'string' && parsed.endpoint !== ''
					? parsed.endpoint.replace(/\/+$/, '')
					: DEFAULT_ENDPOINT,
			token,
		};
	} catch {
		try {
			mkdirSync(STATE_DIR, { recursive: true });
			writeFileSync(
				CONFIG_PATH,
				JSON.stringify({ endpoint: DEFAULT_ENDPOINT, token: DEFAULT_TOKEN }, null, 2),
				{ mode: 0o600 },
			);
		} catch {
			/* 只读环境：用默认值继续 */
		}
		return { endpoint: DEFAULT_ENDPOINT, token: DEFAULT_TOKEN };
	}
}

/** 读远程会话清单。 */
function loadRemoteSessions() {
	try {
		const parsed = JSON.parse(readFileSync(SESSIONS_PATH, 'utf8'));
		return new Set(Array.isArray(parsed.sessions) ? parsed.sessions.filter((x) => typeof x === 'string') : []);
	} catch {
		return new Set();
	}
}

/** 写远程会话清单（失败不致命，只影响重开后的自动恢复）。 */
function saveRemoteSessions(set, logger) {
	try {
		mkdirSync(STATE_DIR, { recursive: true });
		writeFileSync(SESSIONS_PATH, JSON.stringify({ sessions: [...set] }, null, 2), { mode: 0o600 });
	} catch (error) {
		logger?.warn?.(`[dsh-remote-mobile] 保存远程会话清单失败: ${String(error?.message ?? error)}`);
	}
}

/**
 * 调电脑端的一条 RPC。
 *
 * @param config - `{ endpoint, token }`。
 * @param method - 方法名。
 * @param params - 参数。
 * @returns 电脑端的 `result`。
 */
async function callRemote(config, method, params = {}) {
	if (config.token === '') {
		throw new Error(`未配置访问令牌：请在 ${CONFIG_PATH} 里填 token（电脑端启动日志会打印它）`);
	}
	const res = await fetch(`${config.endpoint}/api/dsh-remote/rpc`, {
		method: 'POST',
		headers: {
			'content-type': 'application/json',
			'x-dsh-remote-token': config.token,
		},
		body: JSON.stringify({ id: randomUUID(), method, params }),
	});
	if (!res.ok) throw new Error(`远程请求失败: HTTP ${res.status}`);
	const payload = await res.json();
	if (payload.ok !== true) throw new Error(payload.error ?? '远程请求失败');
	return payload.result;
}

/**
 * 把一个电脑事件写进手机本地会话。
 *
 * ## seq 对齐规则（这是搬运正确性的核心）
 *
 * ⚠️ 两端的 seq **起点不一样**，这是实测确认的：
 *
 * - 电脑端走 `sessionController.create` → agent factory → `agents.create({seed: ...})`，
 *   `seed !== undefined` ⇒ `Session` 构造时**自动插一条 `session/end-seed`**（seq=0），
 *   真实事件从 seq=1 开始（`dsh-session` L1351-1352）。
 * - 手机端走 `sessions.create(id, {meta})`，**不带 seed** ⇒ 那条自动插入不触发
 *   （L1351-1352 的条件是 `seed !== void 0`），真实事件从 seq=0 开始。
 *
 * 所以映射关系是 `手机 seq = 电脑 seq - 已跳过的 end-seed 条数`。
 * `end-seed` 只会出现在日志最前面，跳过它之后两端就逐条对齐了。
 *
 * 据此判断：
 * - 折算后的 seq < 本地 seq ⇒ 已写过（SSE 重连时电脑会补发历史），**跳过**；
 * - 折算后 === 本地 seq ⇒ 正是下一条，写入；
 * - 折算后 > 本地 seq ⇒ 中间缺事件。此时**不能硬写** —— 官方 `Session` 的 seq
 *   必须连续，硬写会造成空洞。抛错交给调用方记日志，等重连补齐。
 *
 * @param session - 手机本地会话。
 * @param event - 电脑端事件（`{type, seq, time, data, surfaceOp?, sourceEventSeqs?}`）。
 * @param state - 该会话的搬运状态 `{ skippedSeeds }`。
 * @returns 是否写入了。
 */
function mirrorEvent(session, event, state) {
	// ⚠️ `session/end-seed` 必须跳过：手机端没有对应的一条，写进去会让后续全部错位。
	// 它只会出现在电脑日志的最开头，所以偏移量只认一次 —— 否则 SSE 重连补发
	// 历史时会被反复累加，后续事件的折算 seq 越算越小。
	if (event.type === 'session/end-seed') {
		if (state.skippedSeeds === 0) state.skippedSeeds = 1;
		return false;
	}
	if (typeof event.seq === 'number') {
		const expected = event.seq - state.skippedSeeds;
		if (expected < session.seq) return false; // 重连补发的历史，已写过
		if (expected > session.seq) {
			throw new Error(
				`事件流有缺口：电脑 seq=${event.seq}（折算 ${expected}），手机本地 seq=${session.seq}（等重连补齐）`,
			);
		}
	}
	const opts = {};
	if (event.surfaceOp !== undefined) opts.surfaceOp = event.surfaceOp;
	if (event.sourceEventSeqs !== undefined) opts.sourceEventSeqs = event.sourceEventSeqs;
	session.append(event.type, event.data, opts);
	return true;
}

/**
 * 插件入口。
 *
 * @param ctx - Cordis 上下文。
 */
export function apply(ctx) {
	const config = loadConfig();
	ctx.logger?.info?.(`[dsh-remote-mobile] 远端: ${config.endpoint}`);
	if (config.token === '') {
		ctx.logger?.warn?.(`[dsh-remote-mobile] 未配置令牌，请在 ${CONFIG_PATH} 里填 token`);
	}

	/** 远程会话 id 集合：这些会话的 prompt 转发电脑，不在手机本地跑模型。 */
	const remoteSessions = loadRemoteSessions();

	/** 每个远程会话正在跟随的电脑事件流。 */
	const follows = new Map();

	/** 每个远程会话的搬运状态（已跳过几条 `session/end-seed`）。 */
	const mirrorStates = new Map();

	/** 取（或建）某会话的搬运状态。 */
	const mirrorStateOf = (sessionId) => {
		let state = mirrorStates.get(sessionId);
		if (state === undefined) {
			state = { skippedSeeds: 0 };
			mirrorStates.set(sessionId, state);
		}
		return state;
	};

	/** 浏览器侧全局 SSE 订阅者。 */
	const watchers = new Set();

	/** 往所有浏览器订阅者推一条 SSE。 */
	const broadcast = (eventName, payload) => {
		if (watchers.size === 0) return;
		const chunk = `event: ${eventName}\ndata: ${JSON.stringify(payload)}\n\n`;
		for (const res of watchers) {
			try {
				res.write(chunk);
			} catch {
				watchers.delete(res);
			}
		}
	};

	/**
	 * 跟随电脑的一个会话：durable 事件写手机本地，流式帧广播给浏览器。
	 *
	 * @param sessionId - 两端同名的会话 id。
	 */
	const follow = (sessionId) => {
		if (follows.has(sessionId)) return;
		const controller = new AbortController();
		follows.set(sessionId, controller);

		/**
		 * 连一次电脑的 SSE 并消费到断开。
		 *
		 * 断线是**常态**（Cloudflare 隧道会掐空闲连接、手机休眠会断网），所以
		 * 外面套一层退避重连：重连后电脑会补发全部历史，`mirrorEvent` 靠 seq
		 * 折算把已写过的跳过，不会重复。
		 */
		const once = async () => {
			const url =
				`${config.endpoint}/api/dsh-remote/stream` +
				`?session=${encodeURIComponent(sessionId)}&token=${encodeURIComponent(config.token)}`;
			const res = await fetch(url, {
				headers: { accept: 'text/event-stream' },
				signal: controller.signal,
			});
			if (!res.ok || res.body === null) throw new Error(`HTTP ${res.status}`);

			const decoder = new TextDecoder();
			let buffer = '';
			for await (const chunk of res.body) {
				buffer += decoder.decode(chunk, { stream: true });
				// SSE 以空行分隔消息；末段可能不完整，留在 buffer 里。
				let split;
				while ((split = buffer.indexOf('\n\n')) >= 0) {
					const raw = buffer.slice(0, split);
					buffer = buffer.slice(split + 2);
					let eventName = 'message';
					let dataLine = '';
					for (const line of raw.split('\n')) {
						if (line.startsWith('event:')) eventName = line.slice(6).trim();
						else if (line.startsWith('data:')) dataLine += line.slice(5).trim();
					}
					if (dataLine === '') continue; // 心跳/注释行
					let payload;
					try {
						payload = JSON.parse(dataLine);
					} catch {
						continue;
					}
					if (eventName === 'event') {
						const session = ctx.sessions.get(sessionId);
						if (session === undefined) continue;
						let wrote = false;
						try {
							wrote = mirrorEvent(session, payload, mirrorStateOf(sessionId));
						} catch (error) {
							ctx.logger?.warn?.(
								`[dsh-remote-mobile] 写入事件失败 (${payload?.type}): ${String(error?.message ?? error)}`,
							);
						}
						// ⚠️ 必须告诉浏览器「这一轮的 durable 结算到了」。
						//
						// 浏览器侧的逐字动画是我们自己注入的 transient 行，而官方的
						// 清理点（`MutableSessionEventSource.settleAssistant`）只挂在
						// `ClientAssistantStream.acceptEventChange` 上 —— 那条链是**远程
						// 事件流**专用的，手机本地的 durable 是同步 append 进会话的，
						// 走不到它。没人清的话，逐字残片会永久留在窗口里，和随后到达的
						// 完整消息叠成两份。
						if (wrote && (payload?.type === 'assistant/message' || payload?.type === 'assistant/attempt')) {
							broadcast('settled', {
								sessionId,
								turn: payload?.data?.turn,
								step: payload?.data?.step,
							});
						}
					} else if (eventName === 'frame') {
						// 带上 sessionId：浏览器侧一条连接服务所有会话。
						broadcast('frame', { sessionId, frame: payload });
					}
				}
			}
		};

		const run = async () => {
			let wait = 1000;
			while (!controller.signal.aborted) {
				try {
					await once();
				} catch (error) {
					if (controller.signal.aborted) return;
					ctx.logger?.warn?.(
						`[dsh-remote-mobile] 事件流中断(${wait}ms 后重连): ${String(error?.message ?? error)}`,
					);
				}
				if (controller.signal.aborted) return;
				await new Promise((resolve) => setTimeout(resolve, wait));
				wait = Math.min(wait * 2, 15000); // 退避到 15s 为止，一直重试
			}
		};

		run().finally(() => {
			if (follows.get(sessionId) === controller) follows.delete(sessionId);
		});
	};

	// ---- 拦截官方 prompt：远程会话转发电脑，本地会话走原逻辑 ----
	ctx.effect(() => {
		const controller = ctx.sessionController;
		if (controller === undefined) return;
		const originalPrompt = controller.prompt.bind(controller);
		controller.prompt = async (request) => {
			if (!remoteSessions.has(request.sessionId)) return originalPrompt(request);
			const text = (request.content ?? [])
				.filter((part) => part.type === 'text')
				.map((part) => part.text)
				.join('\n');
			if (ctx.sessions.get(request.sessionId) === undefined) {
				throw new Error(`远程会话 "${request.sessionId}" 不在手机本地`);
			}
			// ⚠️ 手机端**不**自己写 user/message。
			//
			// 手机本地日志是电脑日志的镜像，seq 必须逐条对齐；本地多写一条会让
			// 后续所有事件的 seq 错位，事件搬运随即失效。而电脑端 prompt 后
			// 本来就会 append 一条 user/message 并流回来 —— 那才是唯一真源。
			//
			// 把前端的 requestId 原样带过去：电脑端会把它写进
			// `source.rpcId`，前端凭这个结算「提交中」状态并显示用户气泡。
			await callRemote(config, 'prompt', {
				sessionId: request.sessionId,
				text,
				...(request.requestId === undefined ? {} : { requestId: request.requestId }),
			});
			// 确保事件流在线（断线时重连，补齐这段时间的事件）。
			follow(request.sessionId);
			return { accepted: true };
		};

		// 「停止生成」同样要转发到电脑。
		// ⚠️ 不拦的话，官方 `cancel` 会 `resolveAgent()` → 在手机上 resume 一个 agent，
		// 那会真去建目录、真跑手机本地模型。
		const originalCancel = controller.cancel.bind(controller);
		controller.cancel = async (request) => {
			if (!remoteSessions.has(request.sessionId)) return originalCancel(request);
			await callRemote(config, 'cancel', { sessionId: request.sessionId });
			return { accepted: true };
		};

		return () => {
			controller.prompt = originalPrompt;
			controller.cancel = originalCancel;
		};
	}, 'dsh-remote-mobile: prompt/cancel 拦截');

	// ---- 浏览器桥：目录浏览 / 建会话 ----
	ctx.effect(
		() =>
			ctx.webServer.register({
				kind: 'exact',
				path: ROUTE_RPC,
				handler: (req, res) => {
					const reply = (code, payload) => {
						res.writeHead(code, { 'content-type': 'application/json; charset=utf-8' });
						res.end(JSON.stringify(payload));
					};
					const chunks = [];
					req.on('data', (c) => chunks.push(c));
					req.on('end', async () => {
						let body;
						try {
							body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
						} catch (error) {
							reply(400, { error: `bad json: ${String(error?.message ?? error)}` });
							return;
						}
						try {
							const result = await handleBridge(ctx, config, {
								remoteSessions,
								follow,
								persist: () => saveRemoteSessions(remoteSessions, ctx.logger),
								body,
							});
							reply(200, { ok: true, result });
						} catch (error) {
							reply(200, { ok: false, error: String(error?.message ?? error) });
						}
					});
				},
			}),
		'dsh-remote-mobile: browser rpc route',
	);

	// ---- 浏览器流式订阅（全局一条） ----
	ctx.effect(
		() =>
			ctx.webServer.register({
				kind: 'exact',
				path: ROUTE_STREAM,
				handler: (req, res) => {
					res.writeHead(200, {
						'content-type': 'text/event-stream; charset=utf-8',
						'cache-control': 'no-store, no-transform',
						connection: 'keep-alive',
						'x-accel-buffering': 'no',
					});
					res.write(': connected\n\n');
					watchers.add(res);
					const heartbeat = setInterval(() => {
						try {
							res.write(': ping\n\n');
						} catch {
							clearInterval(heartbeat);
						}
					}, 15000);
					const cleanup = () => {
						clearInterval(heartbeat);
						watchers.delete(res);
					};
					req.on('close', cleanup);
					req.on('error', cleanup);
				},
			}),
		'dsh-remote-mobile: browser stream route',
	);

	// ---- 启动时恢复：把持久化的远程会话重新接上电脑 ----
	ctx.effect(() => {
		for (const sessionId of remoteSessions) follow(sessionId);
		if (remoteSessions.size > 0) {
			ctx.logger?.info?.(`[dsh-remote-mobile] 已恢复 ${remoteSessions.size} 个远程会话的事件流`);
		}
		return () => {
			for (const controller of follows.values()) controller.abort();
			follows.clear();
			for (const res of watchers) {
				try {
					res.end();
				} catch {
					/* 已断开 */
				}
			}
			watchers.clear();
		};
	}, 'dsh-remote-mobile: restore + teardown');
}

/**
 * 处理浏览器桥来的一条 RPC。
 *
 * @param ctx - Cordis 上下文。
 * @param config - 远程配置。
 * @param io - `{ remoteSessions, follow, persist, body }`。
 */
async function handleBridge(ctx, config, io) {
	const params = io.body?.params ?? {};
	switch (io.body?.method) {
		/** 连接自检：手机 → 电脑。 */
		case 'ping':
			return callRemote(config, 'ping', {});

		/** 列电脑上的目录。 */
		case 'listDesktop':
			return callRemote(config, 'listDirectory', { path: params.path });

		/**
		 * 在电脑的某个目录下开一个新会话。
		 *
		 * 两端用**同一个 sessionId**：手机本地建会话（用手机 cwd，见文件头），
		 * 电脑端在它自己的 cwd 下建同名会话。之后事件就能直接互搬。
		 */
		case 'createSession': {
			if (typeof params.path !== 'string' || params.path === '') {
				throw new Error('请先在电脑页签里选一个目录');
			}
			if (config.token === '') {
				throw new Error(`未配置访问令牌：请在 ${CONFIG_PATH} 里填 token`);
			}
			const sessionId = `session-${randomUUID()}`;
			// ⚠️ 手机端 cwd 必须是**手机本地**的绝对路径，绝不把电脑路径传进来
			// （Android 是 posix 语义，`path.posix.isAbsolute('C:/...') === false`，
			// 且官方 createOrAdopt 会 `mkdir(cwd, {recursive:true})` 真去建目录）。
			// `process.cwd()` 是引擎的工作目录，在手机上就是那个绝对路径。
			const cwd = process.cwd();
			ctx.sessions.create(sessionId, { meta: { cwd } });
			try {
				await callRemote(config, 'createSession', { sessionId, cwd: params.path });
			} catch (error) {
				// 电脑没建成功就别留在手机本地，免得出现一个永远发不出消息的会话。
				io.remoteSessions.delete(sessionId);
				throw error;
			}
			io.remoteSessions.add(sessionId);
			io.persist();
			io.follow(sessionId);
			return { sessionId, cwd, desktopPath: params.path };
		}

		/** 手机本地会话（供前端判断某个会话是不是远程会话）。 */
		case 'isRemote':
			return { remote: io.remoteSessions.has(params.sessionId) };

		default:
			throw new Error(`unknown method "${String(io.body?.method)}"`);
	}
}

export { callRemote, mirrorEvent, loadConfig, DEFAULT_ENDPOINT, CONFIG_PATH };
