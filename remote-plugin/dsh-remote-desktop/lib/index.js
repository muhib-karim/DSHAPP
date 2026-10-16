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
import { randomBytes, randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { isAbsolute, dirname, join, resolve } from 'node:path';
import { createPairing, PAIR_ENDPOINT } from './pair.js';

export const name = 'dsh-jiuling-remote-desktop';

/**
 * `sessionController` 提供官方的建会话/发消息/中断命令层；`sessions` 用于
 * SSE 开场补发历史；中继 HTTP 服务由本插件自建（不用 `webServer`，因为
 * `webServer` 挂在 DSH 自己的 Web 端口上，而隧道的 ingress 指向独立端口 17890）。
 *
 * `webServer` 是给**配对界面**用的：客户端半边要能填激活码、看二维码，
 * 那条路走的是 DSH 自己的 Web 端口（与中继端口无关）。
 */
export const inject = ['sessionController', 'sessions', 'webServer'];

/** 中继监听端口。与 Cloudflare 隧道的 ingress 必须一致。 */
const RELAY_PORT = 17890;

/** 配对界面的路由前缀（挂在 DSH 自己的 Web 端口上）。 */
const PAIR_ROUTE = '/api/dsh-remote/pair';

/** 配置与状态落在 $DSH_HOME 下的这个目录。 */
const STATE_DIR = join(process.env.DSH_HOME ?? join(homedir(), '.dsh'), 'dsh-remote');

/** 访问令牌文件。手机端要用同一个令牌才能连上。 */
const TOKEN_PATH = join(STATE_DIR, 'token.txt');

/** 隧道凭据文件（可选：覆盖内置的隧道令牌）。 */
const TUNNEL_PATH = join(STATE_DIR, 'tunnel.txt');

/**
 * 令牌落盘时的随机源 —— **不要再往包里写死任何凭据**。
 *
 * ## 为什么这里必须随机生成（这是一次真实漏洞的修复，别改回去）
 *
 * 早期版本把访问令牌和 Cloudflare 隧道令牌**硬编码在包内**，让「装完即用」。
 * 但那等于把凭据**公开发布到 npm**：任何人都能 `npm pack` 出这两个值。
 * 实测后果（用包内那个公开令牌打公网隧道）：
 *
 * ```
 * POST https://dsh-remote.jiulingssh.xyz/api/dsh-remote/rpc
 * x-dsh-remote-token: <包内公开常量>
 * → HTTP 200，cf-ray a4b1631e9a978bcf-SIN，读到本机 177 个目录条目
 * ```
 *
 * 面向大众分发时这个缺陷是**灾难级**的：每个客户机都拿同一个隧道凭据去连
 * **同一条**隧道（`dsh-remote.jiulingssh.xyz`），而访问令牌又是公开常量 ——
 * 于是**任何一个客户都能读其他客户整台电脑的目录、并能以他们的身份发消息**。
 * 客户机还会被挂到别人的域名上，本人毫不知情。
 *
 * 所以凭据一律**本机生成、本机落盘**（`token.txt`），配对成功后由服务器下发的
 * `accessToken` 取代（见 `ensureToken` 的优先级）。包里**不含任何可用凭据**。
 */
function generateToken() {
	return randomBytes(32).toString('hex');
}

/**
 * **已作废**的旧公开令牌 —— 只用于识别，**不是凭据，也永远不要再用它鉴权**。
 *
 * 1.0.11 及更早的版本把它当默认令牌写进 `token.txt`。老用户升级后文件里还留着
 * 这个值，若不识别就会**继续用这个全网公开的令牌**跑中继 —— 等于漏洞没修。
 * 所以 `ensureToken` 见到它就当成「没有令牌」，重新随机生成。
 *
 * 它已经发布到 npm，无法收回，因此**它必须被视为已泄露**：只做识别用。
 */
const LEGACY_PUBLIC_TOKEN = 'f6721b0cf82414709df172056193c78a6c374e9a920e51145dd7883923cf16e7';

/**
 * 找 cloudflared 可执行文件。
 *
 * @returns 可执行文件路径，或 `undefined`（未安装）。
 */
function findCloudflared() {
	// ⚠️ `<STATE_DIR>/bin/` 这一条不能少：配对流程会自动把 cloudflared 下到这里
	//（见 `pair.js` 的 `ensureCloudflared`），而客户机上通常别处都没有它。
	// 漏了这一条的症状很隐蔽 —— 配对「成功」、隧道却起不来，
	// 日志里只有一句「未找到 cloudflared」。
	const binDir = join(STATE_DIR, 'bin');
	const candidates = [
		process.env.DSH_REMOTE_CLOUDFLARED,
		join(binDir, process.platform === 'win32' ? 'cloudflared.exe' : 'cloudflared'),
		process.env.LOCALAPPDATA ? join(process.env.LOCALAPPDATA, 'cloudflared', 'cloudflared.exe') : undefined,
		'/usr/local/bin/cloudflared',
		'/usr/bin/cloudflared',
		'/opt/homebrew/bin/cloudflared',
	].filter(Boolean);
	for (const candidate of candidates) if (existsSync(candidate)) return candidate;
	return undefined;
}

/**
 * 决定中继的访问令牌。
 *
 * ## 优先级（这是配对能自动生效的关键）
 *
 * 1. **配对凭据里的 `accessToken`** —— 手机扫码后从服务器 claim 到的就是它，
 *    两端因此**不需要任何手工同步**。这是面向大众分发的主路径。
 * 2. `token.txt` 文件 —— 老用户 / 想自己换令牌的人走这条。
 * 3. **本机随机生成**并落盘 —— 既没配对也没文件时的兜底。
 *
 * ⚠️ 第 3 条早期是「退回包内硬编码的常量」，那是个真实漏洞：常量随 npm 包公开，
 * 于是任何未配对的客户机都用**同一个公开令牌**跑中继，谁都能连上去读他的电脑。
 * 现在改成随机生成，包里不再含任何可用凭据（详见 `generateToken` 的注释）。
 *
 * ⚠️ 顺序不能反。若把文件放第一位，客户配对成功后令牌仍是文件里的旧值，
 * 而手机端拿到的是服务器新发的 —— 两端不一致，症状是「配对显示成功、
 * 一发消息就 401」，且完全看不出问题在哪。
 *
 * @param ctx - Cordis 上下文（只用 logger）。
 * @param paired - 配对记录，或 `undefined`。
 * @returns 令牌字符串。
 */
function ensureToken(ctx, paired) {
	if (typeof paired?.accessToken === 'string' && paired.accessToken.length >= 16) {
		return paired.accessToken;
	}
	try {
		const existing = readFileSync(TOKEN_PATH, 'utf8').trim();
		// ⚠️ 老版本把公开常量写进了这个文件。**必须当成没有令牌**，
		// 否则升级后仍在用那个已泄露的令牌 —— 漏洞等于没修。
		if (existing.length >= 16 && existing !== LEGACY_PUBLIC_TOKEN) return existing;
		if (existing === LEGACY_PUBLIC_TOKEN) {
			ctx.logger?.warn?.('[dsh-remote] 检测到旧版内置的公开令牌（已泄露），正在换成本机随机令牌');
		}
	} catch {
		/* 文件不存在：下面生成一份并落盘 */
	}
	const token = generateToken();
	try {
		mkdirSync(STATE_DIR, { recursive: true });
		writeFileSync(TOKEN_PATH, token, { mode: 0o600 });
	} catch (error) {
		// 写不了盘也照用（本机内存里这份仍是随机的）；只是下次启动会换一个，
		// 而手机端要等配对才能知道新值 —— 配对是面向大众的主路径，不受影响。
		ctx.logger?.warn?.(`[dsh-remote] 写令牌文件失败（本次仍用随机令牌）: ${String(error?.message ?? error)}`);
	}
	return token;
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
 * 把手机传来的「工作区相对路径」变成电脑上能直接喂给官方 `workspaceFiles` 的路径。
 *
 * ⚠️ **两端各算各的**：手机只知道它那边的镜像目录（一个以电脑路径为名的空壳），
 * 它传进来的是**相对于工作区**的一段路径（根目录是空串，posix 分隔符）。
 * 真正的绝对路径由本机拼 —— 本机用什么盘符、什么分隔符只有本机知道，
 * 让手机去拼 `C:\…` 必然出错（见文件头「cwd 绝不跨端传」）。
 *
 * 官方 `workspaceFiles` 内部是拿 `cwd: workspaceRoot` 去 resolve 的，所以这里
 * **直接把相对路径透传**就是正解；根目录换成 `workspaceRoot` 只为少一次 resolve。
 */
function resolveWorkspacePath(scope, path) {
	if (typeof path !== 'string' || path === '' || path === '.') return scope.workspaceRoot;
	return path;
}

/**
 * 不中断的取消信号。
 *
 * ⚠️ 官方 `workspaceFiles` 的每个方法都要求一个 `signal`（内部会交给 fs 后端）。
 * 本插件的 RPC 没有取消通道（中继那边一问一答），所以给一个**永不 abort** 的
 * 信号 —— 传 `undefined` 在部分后端上会直接抛。
 */
function idleSignal() {
	return new AbortController().signal;
}

/** 取官方的 `workspaceFiles` 服务；没就绪时给一句能看懂的错。 */
function workspaceFilesOf(ctx) {
	const files = ctx.get?.('workspaceFiles');
	if (files === undefined || typeof files.list !== 'function') {
		throw new Error('workspaceFiles 不可用（引擎还没就绪？）');
	}
	return files;
}

/**
 * 按 sessionId 拼出官方要的 `workspaceFileScope`（`{sessionId, workspaceRoot}`）。
 *
 * ⚠️ **照抄官方 `dsh-api-workspace-files` 里那个 lookup 的取法**：活会话读
 * `sessions.get(id).header`，已归档的会话退回 `sessionPersistence.stat(id).header`。
 * 只读活会话的话，手机去列一个电脑上「已落盘但当前没跑」的会话就报「工作区不存在」。
 */
async function workspaceScopeOf(ctx, sessionId) {
	if (typeof sessionId !== 'string' || sessionId === '') throw new Error('sessionId required');
	const live = ctx.sessions?.get?.(sessionId)?.header;
	let stored;
	if (live === undefined) {
		const persistence = ctx.get?.('sessionPersistence');
		if (typeof persistence?.stat === 'function') {
			stored = await persistence.stat(sessionId).catch(() => undefined);
		}
	}
	const cwd = (live ?? stored?.header)?.cwd;
	if (typeof cwd !== 'string' || cwd === '') {
		throw new Error(`电脑上没有会话 ${sessionId} 的工作目录`);
	}
	return { sessionId, workspaceRoot: cwd };
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

		/**
		 * 发消息：驱动电脑上的 agent。
		 *
		 * ## 附件为什么必须原样透传（用户需求原文：「照片就会被吞了 AI 只能看到我的文字」）
		 *
		 * 手机端 composer 里的附件分两种形态（见 `dsh-client-ui-conversation/lib/client.js`
		 * 的 `serializeAttachments`）：
		 *
		 * | 形态 | 结构 | 谁落盘 |
		 * |---|---|---|
		 * | 图片 | `{type:'image', mediaType, data(base64), name?}` | **电脑**（内联字节，没有 receipt） |
		 * | 文件 | `{type:'file', receiptId}` | **手机**（receipt 是本机 `fileUploads` 发的） |
		 *
		 * 所以图片是**自带字节**的，直接转给电脑的 `controller.prompt` 即可，电脑会
		 * 用 `attachments.admitPromptContent` 落成它自己的 content-addressed 对象。
		 *
		 * ⚠️ `{type:'file', receiptId}` **不能**转发：receipt 是**手机本地**
		 * `fileUploads` 服务发的，电脑的 `stagedFiles` 里没有它，转过去必然
		 * `session/attachment-invalid FILE_NOT_STAGED`。所以那种 part 降级成一句
		 * 文本说明 —— 至少让模型知道用户附了个文件，而不是**静默吞掉**。
		 *
		 * @returns `{sessionId, requestId, accepted, forwarded, skipped}`。
		 */
		case 'prompt': {
			const sessionId = params.sessionId;
			const text = typeof params.text === 'string' ? params.text : '';
			// 附件形态的 content（手机端传过来的原始数组）。没有就退回纯文本。
			const incoming = Array.isArray(params.content) ? params.content : null;
			if (typeof sessionId !== 'string' || sessionId === '') throw new Error('sessionId required');
			// ⚠️ 判据必须包含附件：用户只发一张图、不打字，是完全正常的用法。
			// 早先只校验 `text.trim() === ''` 会把这种消息直接拒掉。
			const content = [];
			let skipped = 0;
			if (incoming === null) {
				if (text.trim() === '') throw new Error('sessionId and non-empty text required');
				content.push({ type: 'text', text });
			} else {
				for (const part of incoming) {
					if (part?.type === 'text') {
						if (typeof part.text === 'string' && part.text !== '') content.push({ type: 'text', text: part.text });
						continue;
					}
					if (part?.type === 'image') {
						// 只认官方 `imageMediaType()` 放行的四种；其余一律降级成文本，
						// 免得电脑端抛 `UnsupportedImageMediaTypeError` 把整条消息废掉。
						const mediaType = part.mediaType;
						if (
							typeof part.data === 'string' &&
							part.data !== '' &&
							(mediaType === 'image/png' ||
								mediaType === 'image/jpeg' ||
								mediaType === 'image/webp' ||
								mediaType === 'image/gif')
						) {
							content.push({
								type: 'image',
								mediaType,
								data: part.data,
								...(typeof part.name === 'string' && part.name !== '' ? { name: part.name } : {}),
							});
						} else {
							skipped += 1;
						}
						continue;
					}
					// `file` 与任何未知形态：receipt 属于手机，电脑解不开（见上表）。
					skipped += 1;
				}
				if (content.length === 0) {
					if (skipped === 0) throw new Error('prompt content must include text or an image');
					// 只有解不开的附件：给模型一句说明，别让它以为用户什么都没发。
					content.push({
						type: 'text',
						text: `（用户附了 ${skipped} 个文件，但文件传输通道尚未接通，只收到了这条说明。）`,
					});
				}
			}
			// ⚠️ requestId 必须唯一：官方用它做幂等去重，两边都是 undefined 会误判重复。
			const requestId = typeof params.requestId === 'string' && params.requestId !== ''
				? params.requestId
				: randomUUID();
			// ⚠️ **必须传第二个参数 `signal`**，`undefined` 会直接炸。
			//
			// 官方 `dsh-api-session-controller/lib/index.js:3096`：
			//   prompt(request, signal) { signal.throwIfAborted(); ... }
			// 它**无条件**对 `signal` 调 `throwIfAborted()`，所以漏传就是
			//   `Cannot read properties of undefined (reading 'throwIfAborted')`
			// 报错文案完全看不出是参数漏传，症状是「手机发了消息、电脑毫无反应」。
			//
			// 传一个永不过期的 signal：远端调用的生命周期不由本机的 UI 决定，
			// 真正的中断走 `cancel` 那条 RPC。
			const accepted = await controller.prompt(
				{
					sessionId,
					requestId,
					content,
					...(params.mode === 'steer' ? { mode: 'steer' } : {}),
				},
				new AbortController().signal,
			);
			return { sessionId, requestId, accepted: accepted?.accepted === true, forwarded: content.length, skipped };
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

		/**
		 * 列电脑上某个会话工作区里的一层目录（**含文件**）。
		 *
		 * ⚠️ **不要拿 `listDirectory` 顶替**：那个是给「选工作区」对话框用的，
		 * 只列子目录（文件被 `continue` 跳过）。而官方侧边栏的文件树要
		 * `{name, type, size?}` 三样齐全，拿不到 `type:'file'` 的条目就等于
		 * 侧边栏里**一个文件都看不见** —— 这正是本方法存在的理由。
		 *
		 * 直接把官方的 `workspaceFiles.list()` 结果原样带回去，客户端就不需要
		 * 任何形状转换（官方 `sidebar-files` 的 `createList` 只挑
		 * `entries` 与 `truncated` 两个字段）。
		 */
		case 'fileList': {
			const files = workspaceFilesOf(ctx);
			const scope = await workspaceScopeOf(ctx, params.sessionId);
			return await files.list(scope, resolveWorkspacePath(scope, params.path), idleSignal());
		}

		/**
		 * 读电脑上一个会话工作区里的文本文件（分页）。
		 *
		 * @returns 官方 `workspaceFiles.read` 的原样结果
		 *   （`{offset, text, lines, eof, absolutePath, version, bytes?}`）。
		 */
		case 'fileRead': {
			const files = workspaceFilesOf(ctx);
			const scope = await workspaceScopeOf(ctx, params.sessionId);
			const range = params.offset === undefined ? {} : { offset: params.offset };
			return await files.read(scope, resolveWorkspacePath(scope, params.path), range, idleSignal());
		}

		/**
		 * 取电脑上一个会话工作区里某个路径的元信息（`{absolutePath, version, bytes?}`）。
		 *
		 * 官方预览面板靠 `version` 判断文件有没有被外部改动过，缺了它就不会刷新。
		 */
		case 'fileStat': {
			const files = workspaceFilesOf(ctx);
			const scope = await workspaceScopeOf(ctx, params.sessionId);
			return await files.stat(scope, resolveWorkspacePath(scope, params.path), idleSignal());
		}

		/**
		 * 读电脑上一个会话工作区里的**字节**（图片等二进制预览用）。
		 *
		 * ⚠️ 两端的 RPC 都是 JSON，`Uint8Array` 过不去 —— 这里过一道 base64。
		 * 手机端收到后还原成 `Uint8Array`，官方预览面板才认。
		 */
		case 'fileReadBytes': {
			const files = workspaceFilesOf(ctx);
			const scope = await workspaceScopeOf(ctx, params.sessionId);
			const options = params.range === undefined ? {} : { range: params.range };
			const result = await files.readBytes(scope, resolveWorkspacePath(scope, params.path), options, idleSignal());
			const { data, ...meta } = result ?? {};
			return { ...meta, data: Buffer.from(data ?? []).toString('base64') };
		}

		/**
		 * 电脑上的模型目录（供手机把「模型选择」列表换成电脑的）。
		 *
		 * ⚠️ **必须走 `controller.modelCatalog()`，不能自己去读配置**：它是官方的
		 * 模型目录构建器（`buildModelCatalog`），已经把「哪些 provider 可用、
		 * 每个模型支持哪些 reasoning effort」都算好了，而且失败会按 provider
		 * 隔离成 `failures` 而不是整体抛错。自己读配置只会得到一份对不上官方
		 * 前端的结构。
		 *
		 * @returns `{ default, routableProviders, groups, failures }`。
		 */
		case 'modelCatalog': {
			if (typeof controller.modelCatalog !== 'function') throw new Error('modelCatalog 不可用');
			return await controller.modelCatalog();
		}

		/**
		 * 在电脑上选定模型（手机的模型选择器选中的是电脑的模型时转发过来）。
		 *
		 * ⚠️ 不转发的话，手机本地 `selectModel` 会拿**手机**的 llm 注册表去校验，
		 * 电脑上独有的模型一律报「模型不可用」—— 而用户明明在菜单里看到了它。
		 */
		case 'selectModel': {
			const sessionId = params.sessionId;
			if (typeof sessionId !== 'string' || sessionId === '') throw new Error('sessionId required');
			if (typeof controller.selectModel !== 'function') throw new Error('selectModel 不可用');
			const selected = await controller.selectModel({
				sessionId,
				provider: params.provider,
				model: params.model,
				...(typeof params.reasoningEffort === 'string' && params.reasoningEffort !== ''
					? { reasoningEffort: params.reasoningEffort }
					: {}),
			});
			return { sessionId, selected: selected?.selected ?? null };
		}

		/**
		 * 电脑上的预设列表（供手机把「预设」列表换成电脑的）。
		 *
		 * `agentPresets.remoteExportList()` 是官方给前端用的那份结构
		 * （`{presets: [{id, name, description, order, isDefault}]}`），
		 * 比 `list()` 多一个 `isDefault` —— 手机要照它显示「默认」标记。
		 */
		case 'presetList': {
			const presets = ctx.get?.('agentPresets');
			if (presets === undefined || typeof presets.remoteExportList !== 'function') {
				throw new Error('agentPresets 不可用');
			}
			return await presets.remoteExportList();
		}

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

	/**
	 * 当前有效的访问令牌。
	 *
	 * ⚠️ **必须是可变的**：配对是**运行中**发生的，配完要立刻生效。
	 * 若把 `token` 直接闭包进 `apply()` 的常量，客户填完激活码还得手动重启
	 * DSH —— 而那正是「面向大众」最不该有的一步。
	 */
	const auth = { token };

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
		if (provided !== auth.token) {
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

	/**
	 * ⚠️ `error` 处理器**不能省**，否则端口被占用时整个引擎进程会崩。
	 *
	 * `listen()` 的失败是**异步**通过 `'error'` 事件抛的，`apply()` 里那个
	 * try/catch 完全兜不住 —— 实测（本机已有一个 DSH 在跑时再起一个）：
	 *
	 *   Error: listen EADDRINUSE: address already in use 127.0.0.1:17890
	 *   Emitted 'error' event on Server instance at: ...
	 *   Node.js v24.16.0        ← 进程直接退出
	 *
	 * 后果远不止「远程功能不可用」：客户机上有残留的引擎进程是**常见现象**
	 *（本项目 AGENTS.md 就记着「3080 已被占用（多半是上一次残留的引擎进程）」），
	 * 而崩掉的是**整个 DSH**，客户完全看不出这是本插件造成的。
	 *
	 * 所以这里降级：日志说清原因，其余功能照常。
	 */
	server.on('error', (error) => {
		ctx.logger?.warn?.(
			`[dsh-remote] 中继无法监听 127.0.0.1:${RELAY_PORT}（${String(error?.message ?? error)}）` +
				'；多半是另一个 DSH 实例已在运行。远程功能不可用，但本进程其余功能不受影响。',
		);
	});

	server.listen(RELAY_PORT, '127.0.0.1');
	return {
		/**
		 * 换令牌（配对后热更新，无需重启 DSH）。
		 *
		 * @param next - 新令牌。
		 */
		setToken: (next) => {
			auth.token = next;
		},
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
 * 换一条隧道：杀掉旧的、起新的。
 *
 * ⚠️ 换隧道**必须换进程**：`cloudflared` 的凭据是启动参数，运行中改不了。
 * 旧的还挂着上一次（或内置）的凭据，不杀的话两条隧道同时连着 Cloudflare，
 * 域名解析到哪一条是随机的 —— 表现是「一会儿通一会儿 502」。
 *
 * @param ctx - Cordis 上下文。
 * @param current - 当前隧道子进程，或 `undefined`。
 * @param token - 新隧道 token；为空则只杀不起。
 * @returns 新的子进程，或 `undefined`。
 */
function restartTunnel(ctx, current, token) {
	try {
		current?.kill();
	} catch {
		/* 已退出 */
	}
	if (typeof token !== 'string' || token.length < 16) {
		ctx.logger?.warn?.('[dsh-remote] 配对记录里没有隧道 token，跳过重启隧道');
		return undefined;
	}
	return startTunnel(ctx, token);
}

/**
 * 读隧道凭据：**配对记录优先**，其次文件；都没有就返回 `undefined`（不起隧道）。
 *
 * ⚠️ **这里绝不能有「包内硬编码的兜底隧道令牌」**（早期版本有，是真实漏洞）。
 *
 * 硬编码的后果不是「方便」，而是把**同一条隧道**发给所有客户：每台未配对的机器
 * 都拿同一个凭据去连 `dsh-remote.jiulingssh.xyz`，于是
 * ① 客户机被挂到**别人的域名**上，本人毫不知情；
 * ② 谁能拿到那个公开常量（`npm pack` 即可），谁就能连上**所有**这些机器。
 *
 * 隧道凭据只能来自「本机配对」——配对服务按激活码签发**每机一条**独立隧道。
 * 没有配对就不起隧道，本机中继仍在 `127.0.0.1:17890` 上跑（局域网/自建反代可用）。
 *
 * @param paired - 配对记录，或 `undefined`。
 * @returns 隧道 token 字符串，或 `undefined`。
 */
function readTunnelToken(paired) {
	if (typeof paired?.tunnelToken === 'string' && paired.tunnelToken.length >= 16) {
		return paired.tunnelToken;
	}
	try {
		const value = readFileSync(TUNNEL_PATH, 'utf8').trim();
		return value === '' ? undefined : value;
	} catch {
		return undefined;
	}
}

/**
 * 处理一条配对相关的 HTTP 请求。
 *
 * 路由挂在 DSH 自己的 Web 端口上（不是中继端口 17890）—— 配对界面是
 * **本机**的，不该经公网隧道绕一圈回来。
 *
 * @param ctx - Cordis 上下文。
 * @param pairing - `createPairing()` 的返回值。
 * @param state - `apply()` 的运行态 `{ relay, tunnel }`（配对要热切这两样）。
 * @param req - 请求。
 * @param res - 响应。
 */
function handlePairRoute(ctx, pairing, state, req, res) {
	const reply = (code, payload) => {
		res.writeHead(code, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' });
		res.end(JSON.stringify(payload));
	};
	const chunks = [];
	req.on('data', (c) => chunks.push(c));
	req.on('end', async () => {
		const url = new URL(req.url ?? '/', 'http://127.0.0.1');
		const action = url.pathname.slice(PAIR_ROUTE.length) || '/status';
		let body = {};
		if (chunks.length > 0) {
			try {
				body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
			} catch (error) {
				reply(400, { ok: false, message: `请求体不是合法 JSON: ${String(error?.message ?? error)}` });
				return;
			}
		}
		try {
			switch (action) {
				case '/status':
					reply(200, { ok: true, ...pairing.status() });
					return;

				/** 填激活码 → 向服务器注册 → 起隧道。 */
				case '/register': {
					const result = await pairing.register(body.code);
					if (!result.ok) {
						reply(200, { ok: false, message: result.message });
						return;
					}
					// ⚠️ 换隧道必须**热切**，不能让客户去重启 DSH。
					//
					// 配对是运行中发生的：旧的隧道子进程还挂着内置凭据，
					// 中继也还在用旧令牌校验。两处都要就地换掉 ——
					// 面向大众的产品里「配完请重启」这一步等于功能不可用。
					ctx.logger?.info?.(`[dsh-remote][pair] 隧道切换到 ${result.record.hostname}`);
					// 客户机上大概率没有 cloudflared，这里顺手备好。
					// ⚠️ 会**阻塞这次响应**（55 MB，实测 47 秒）。这是刻意的取舍：
					// 让客户点一次就完事，好过让他再去找一个「下载」按钮 ——
					// 面向大众的流程里每多一步都会掉人。
					const binary = await pairing.ensureCloudflared();
					if (!binary.ok) {
						ctx.logger?.warn?.(`[dsh-remote][pair] cloudflared 未就绪: ${binary.message}`);
					}
					if (state.relay !== undefined && typeof result.record.accessToken === 'string') {
						state.relay.setToken(result.record.accessToken);
					}
					state.tunnel = restartTunnel(ctx, state.tunnel, result.record.tunnelToken);
					reply(200, {
						ok: true,
						message: binary.ok
							? result.message
							: `${result.message}，但 cloudflared 未就绪（${binary.message}），隧道没起来`,
						...pairing.status(),
					});
					return;
				}

				/** 生成 6 位配对码 + 二维码。 */
				case '/handshake': {
					const result = await pairing.handshake();
					reply(200, result);
					return;
				}

				/** 确保 cloudflared 在位（需要时自动下载）。 */
				case '/cloudflared': {
					const result = await pairing.ensureCloudflared();
					reply(200, result);
					return;
				}

				/** 解绑：清掉配对凭据，换一个本机随机令牌，并停掉隧道。 */
				case '/unpair': {
					pairing.clear();
					// 解绑后必须**换掉**令牌：旧令牌已随配对公开过（手机端存了一份），
					// 继续用等于解绑无效。
					const fresh = generateToken();
					try {
						writeFileSync(TOKEN_PATH, fresh, { mode: 0o600 });
					} catch (error) {
						ctx.logger?.warn?.(`[dsh-remote] 解绑时写令牌失败: ${String(error?.message ?? error)}`);
					}
					if (state.relay !== undefined) state.relay.setToken(fresh);
					// 隧道凭据属于**那一台**已配对的机器，解绑即失效；没有新凭据就不再起隧道。
					state.tunnel = restartTunnel(ctx, state.tunnel, undefined);
					reply(200, { ok: true, message: '已解绑，已停止隧道（如需重连请重新配对）' });
					return;
				}

				default:
					reply(404, { ok: false, message: `未知操作 ${action}` });
			}
		} catch (error) {
			ctx.logger?.warn?.(`[dsh-remote][pair] ${action} 失败: ${String(error?.message ?? error)}`);
			reply(200, { ok: false, message: String(error?.message ?? error) });
		}
	});
}

/**
 * 插件入口。
 *
 * @param ctx - Cordis 上下文。
 * @param config - 插件配置：`{ tunnelToken?, autoTunnel? }`。
 */
export function apply(ctx, config = {}) {
	const pairing = createPairing({ stateDir: STATE_DIR, logger: ctx.logger });
	const paired = pairing.read();
	const token = ensureToken(ctx, paired);
	const state = { relay: undefined, tunnel: undefined };

	ctx.effect(() => {
		try {
			state.relay = startRelay(ctx, token);
			ctx.logger?.info?.(`[dsh-remote] 中继已监听 http://127.0.0.1:${RELAY_PORT}`);
			ctx.logger?.info?.(`[dsh-remote] 访问令牌: ${token}`);
			ctx.logger?.info?.(`[dsh-remote] 令牌文件: ${TOKEN_PATH}`);
			if (paired !== undefined) {
				ctx.logger?.info?.(`[dsh-remote] 已配对: ${paired.hostname}（激活码 ${paired.activationCode}）`);
			} else {
				ctx.logger?.info?.(`[dsh-remote] 尚未配对，请在设置里填激活码（配对服务 ${PAIR_ENDPOINT}）`);
			}
		} catch (error) {
			ctx.logger?.error?.(`[dsh-remote] 中继启动失败: ${String(error?.message ?? error)}`);
		}

		// ---- 配对路由（挂在本机 Web 端口上） ----
		//
		// ⚠️ 只注册 `prefix` 一条：`register()` 对重复的 `(kind, path)` 会**抛错**
		//（`webserver: duplicate ...`），而 exact 与 prefix 是两张表 —— 同时注册
		// 同一个 path 虽然不冲突，但 exact 永远先命中，等于白写一条。
		// 前缀匹配已经覆盖 `/api/dsh-remote/pair` 与它的全部子路径。
		if (typeof ctx.webServer?.register === 'function') {
			ctx.effect(
				() =>
					ctx.webServer.register({
						kind: 'prefix',
						path: PAIR_ROUTE,
						handler: (req, res) => handlePairRoute(ctx, pairing, state, req, res),
					}),
				'dsh-remote-desktop: pairing route',
			);
		} else {
			ctx.logger?.warn?.('[dsh-remote] webServer 不可用，配对界面无法工作（中继不受影响）');
		}

		const tunnelToken = config.tunnelToken ?? process.env.DSH_REMOTE_TUNNEL_TOKEN ?? readTunnelToken(paired);
		if (config.autoTunnel === false) {
			ctx.logger?.info?.('[dsh-remote] autoTunnel=false，只跑本地中继（不起隧道）');
		} else if (typeof tunnelToken !== 'string' || tunnelToken.length < 16) {
			// 未配对（且没给 tunnelToken / tunnel.txt）⇒ **不起隧道**。
			// 这是修掉「包内硬编码共享隧道凭据」之后的正常状态，不是错误。
			ctx.logger?.info?.(
				'[dsh-remote] 尚未配对，未启动隧道（中继仍在 127.0.0.1:' +
					`${RELAY_PORT} 上监听）；配对成功后会自动起隧道。`,
			);
		} else {
			state.tunnel = startTunnel(ctx, tunnelToken);
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
	}, 'dsh-remote-desktop: relay + tunnel + pairing');
}

export { RELAY_PORT, TOKEN_PATH, TUNNEL_PATH, PAIR_ROUTE, listDirectory, handleRpc };
