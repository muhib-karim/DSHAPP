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

import { createHash, randomUUID } from 'node:crypto';
import { mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { request as httpRequest } from 'node:http';
import { request as httpsRequest } from 'node:https';
import { homedir } from 'node:os';
import { join } from 'node:path';

export const name = 'dsh-jiuling-remote-mobile';

/** `sessions` 建会话/追加事件，`webServer` 给浏览器挂桥，`sessionController` 拦截 prompt。 */
export const inject = ['sessions', 'sessionController', 'webServer', 'workspaceRegistry'];

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
 * 配对服务地址。
 *
 * 与电脑端 `dsh-jiuling-remote-desktop/lib/pair.js` 的 `PAIR_ENDPOINT`
 * **必须逐字相同** —— 两端各写一份是因为这两个包要能独立安装，
 * 不能互相 import（一个是 host 插件、一个是另一台机器上的 host 插件）。
 */
const PAIR_ENDPOINT = 'https://pair.jiulingssh.xyz';

/**
 * 保存连接配置（配对成功后写）。
 *
 * ⚠️ 走「临时文件 + rename」：这个文件是手机端**唯一一份**凭据，
 * 写一半断电就得重新配对一次。
 *
 * @param endpoint - 电脑的隧道地址。
 * @param token - 访问令牌。
 * @returns 落盘后的 `{ endpoint, token }`。
 */
function saveConfig(endpoint, token) {
	const record = {
		endpoint: String(endpoint).replace(/\/+$/, ''),
		token: String(token),
	};
	mkdirSync(STATE_DIR, { recursive: true });
	const tmp = `${CONFIG_PATH}.${process.pid}.tmp`;
	writeFileSync(tmp, JSON.stringify(record, null, 2), { mode: 0o600 });
	renameSync(tmp, CONFIG_PATH);
	return record;
}

/**
 * 令牌为空 —— 表示**尚未配对**。
 *
 * ⚠️ 早期这里硬编码了一个「内置访问令牌」，让两端装完即连。那是真实漏洞：
 * 常量随 npm 包公开，任何未配对的电脑都跑在**同一个公开令牌**上，谁都能连上去
 * 读他的电脑（实测用包内公开令牌打公网隧道 → HTTP 200，读到 177 个目录条目）。
 * 现在**包里不含任何凭据**：令牌只能由配对流程从服务器 claim 得到（见
 * `claimPairing`），未配对时就是空字符串，`callRemote` / `follow` 会直接报
 * 「尚未配对」，不会静默用一个假令牌去连。
 */
const EMPTY_TOKEN = '';

/**
 * **已作废**的旧公开令牌 —— 只用于识别，**不是凭据**。
 *
 * 1.0.9 及更早的版本把它当默认令牌写进 `mobile.json`。老用户升级后文件里还留着
 * 这个值，若不识别就会被 `pairStatus` 判成「已配对」，而实际上电脑端早已改用随机
 * 令牌 —— 症状是「显示已配对，一发消息就 401」。所以见到它就当成未配对。
 */
const LEGACY_PUBLIC_TOKEN = 'f6721b0cf82414709df172056193c78a6c374e9a920e51145dd7883923cf16e7';

/**
 * 读配置。首次运行写一份模板，用户直接改文件即可。
 *
 * ⚠️ 这里**没有**任何内置令牌兜底（早期有，是漏洞）。`token` 为空即「未配对」，
 * 调用方据此直接拒绝，而不是拿一个包里公开的常量去连。
 *
 * @returns `{ endpoint, token }`。
 */
function loadConfig() {
	try {
		const parsed = JSON.parse(readFileSync(CONFIG_PATH, 'utf8'));
		// ⚠️ 旧版写进文件的公开令牌要当成**未配对**，否则界面显示已配对、
		// 实际一调用就 401（电脑端已换随机令牌）。
		const raw = typeof parsed.token === 'string' ? parsed.token : EMPTY_TOKEN;
		const token = raw === LEGACY_PUBLIC_TOKEN ? EMPTY_TOKEN : raw;
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
				JSON.stringify({ endpoint: DEFAULT_ENDPOINT, token: EMPTY_TOKEN }, null, 2),
				{ mode: 0o600 },
			);
		} catch {
			/* 只读环境：用默认值继续 */
		}
		return { endpoint: DEFAULT_ENDPOINT, token: EMPTY_TOKEN };
	}
}

/**
 * 镜像工作区登记表：`<slug> → <电脑路径>`。
 *
 * ## ⚠️ 为什么必须有这张表（否则「备选区选电脑目录」会静默退化成本地会话）
 *
 * 电脑目录在手机上的落点是**镜像目录** `STATE_DIR/workspaces/<slug>`，而 slug 是
 * 电脑路径的**单向哈希**（`desktopSlug`，末 8 位是 sha256 摘要）——**推不回原路径**。
 *
 * 而用户第二次开对话时走的是**另一条路**：他不再进「电脑文件夹」对话框，而是直接在
 * 新建对话首页的**备选区**里点上次那个工作区。那条路是官方前端自己
 * `sessionController.create({workspaceId})`，**根本不经过本插件的 `createSession` 桥**
 * ⇒ `remoteSessions` 里没有这个 sessionId ⇒ prompt 拦截不命中 ⇒ 消息走手机本地模型。
 *
 * 真机症状（用户原话）：「我问他你的工作目录是什么，他回答的就是我安卓的目录，
 * 模型也没加载到电脑的」。第一次手动建时一切正常，**第二次起就变本地**。
 *
 * 所以要能从「镜像目录」反查出「电脑路径」，才能在那个路口把会话重新绑成远程。
 */
const WORKSPACES_PATH = join(STATE_DIR, 'workspaces.json');

/** 读镜像工作区登记表。 */
function loadWorkspaceMap() {
	try {
		const parsed = JSON.parse(readFileSync(WORKSPACES_PATH, 'utf8'));
		const map = parsed?.workspaces;
		if (map === null || typeof map !== 'object') return {};
		/** @type {Record<string, string>} */
		const out = {};
		for (const key of Object.keys(map)) {
			if (typeof map[key] === 'string' && map[key] !== '') out[key] = map[key];
		}
		return out;
	} catch {
		return {};
	}
}

/** 写镜像工作区登记表（失败不致命，只影响重开后的自动绑定）。 */
function saveWorkspaceMap(map, logger) {
	try {
		mkdirSync(STATE_DIR, { recursive: true });
		writeFileSync(WORKSPACES_PATH, JSON.stringify({ workspaces: map }, null, 2), { mode: 0o600 });
	} catch (error) {
		logger?.warn?.(`[dsh-remote-mobile] 保存镜像工作区登记表失败: ${String(error?.message ?? error)}`);
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
 * 跨端请求专用的 undici 连接池。
 *
 * ## ⚠️ 为什么不能用全局 `fetch`（真机实测的硬坑）
 *
 * 在 Android 上，引擎进程里**全局 undici dispatcher 连不通公网**：同一个进程、
 * 同一个 URL、同一时刻的对照实验（`__diag` 探针）——
 *
 * | 通道 | 结果 |
 * |---|---|
 * | 全局 `fetch()`（默认 dispatcher） | **12018 ms 超时** ❌ |
 * | `new Agent({ autoSelectFamily: false })` | **2658 ms 200** ✅ |
 * | `new Agent({ connect: { family: 4 } })` | 5283 ms 200 ✅ |
 * | `node:https.request()` | 2125 ms 200 ✅ |
 *
 * DNS 正常（10 ms 解析出 2 个 IPv6 + 2 个 IPv4），所以不是解析问题；是全局
 * dispatcher 的 **Happy Eyeballs / autoSelectFamily 在 Android 上卡住**
 * （IPv6 地址拿到却连不通，又不及时回退到 IPv4）。
 *
 * 症状具有极强的误导性：**纯本地的桥方法（如 `isRemote`）几十毫秒就返回**，
 * 而任何真正出网的桥方法（`ping` / `listDesktop`）永久挂起 —— 看起来像
 * 「插件没装上」或「隧道不通」，实际是本机引擎的连接策略问题。
 *
 * 所以本插件**不复用全局 dispatcher**，自己建一个关掉 `autoSelectFamily` 的池。
 * 只影响本插件发出的请求，不碰进程全局状态。
 *
 * @param url - 完整 URL。
 * @param init - `fetch` 的第二参数。
 * @returns 响应。
 */
async function remoteFetch(url, init) {
	const pool = await remotePool();
	// ① 首选：undici 专用池（关掉 autoSelectFamily，Android 上最稳）。
	// ② 备用：`node:https`（强制 IPv4，不依赖 undici 解析）。
	//
	// ⚠️ **两条通道必须互为备份，不能只做「解析失败才兜底」**。线上实测过：
	// 同一个版本、同一个账号，配对服务器日志里既有 `POST /pair/claim 400`
	// （请求到了）又有界面上的 `fetch failed`（请求没到）—— 说明是**间歇性**
	// 建连失败。只修「undici 没解析到」那一种情形，另一种照样挂。
	//
	// 重试是安全的：`fetch()` 抛错意味着**建连或响应头阶段**就失败了，
	// 此时响应体一个字节都还没消费，重发不会产生重复副作用。
	// （流中途断开不会走到这里 —— 那是 `for await` 里的事，不在本函数内。）
	const attempts = [];
	if (pool !== null) {
		try {
			return await pool.undici.fetch(url, { ...init, dispatcher: pool.agent });
		} catch (error) {
			if (isAbort(error)) throw error;
			attempts.push(describeFetchError(error, '走的是专用 undici 池'));
		}
	} else if (!remoteFallbackWarned) {
		remoteFallbackWarned = true;
		console.warn('[dsh-remote-mobile] 拿不到 undici，改用 node:https 通道（强制 IPv4）。');
	}
	try {
		return await nodeHttpsFetch(url, init);
	} catch (error) {
		if (isAbort(error)) throw error;
		attempts.push(describeFetchError(error, '走的是 node:https 通道（强制 IPv4）'));
		throw new Error(`两条通道都失败：\n  ① ${attempts[0]}\n  ② ${attempts[1]}`);
	}
}

/**
 * 是否是**主动中止**（不是网络故障）。
 *
 * ⚠️ SSE 断开重连靠 `AbortController`，把它当成网络故障包装掉，重连逻辑就失效了。
 *
 * @param error - 抛出的错误。
 * @returns 是否应当原样上抛。
 */
function isAbort(error) {
	return error?.name === 'AbortError' || error?.name === 'TimeoutError';
}

/**
 * 不依赖 undici 的 HTTPS 兜底通道（只用 Node 内置的 `node:https`）。
 *
 * ## 为什么需要它
 *
 * `undici` 是**引擎**的依赖（`<engine>/node_modules/undici`），不在插件的解析链上。
 * 一旦解析不到，退回全局 `fetch` 在 Android 上会因 **Happy Eyeballs** 卡死
 * （真机实测 12018 ms 超时，而关掉 `autoSelectFamily` 的池 2658 ms 就通）。
 * 症状是配对时一句 `fetch failed`，配对服务器日志里**根本没有这次请求**。
 *
 * `node:https` 是 Node 内置模块，**解析零风险**，且它天然按 DNS 返回顺序逐个尝试
 * 地址（不做 Happy Eyeballs 竞速），实测在真机上 2125 ms 就通。
 *
 * 这里刻意**只强制 IPv4**：Android 上运营商给 AAAA 记录、但 IPv6 实际不通是常态，
 * 而 `node:https` 遇到连不通的地址要等超时才换下一个 —— 直接锁 IPv4 最稳。
 *
 * ## 能力取舍（调用方按需使用）
 *
 * 只实现本插件真正用到的部分：POST/GET + 自定义头 + 文本/流式响应体。
 * 返回对象形状对齐 `fetch`：`{ ok, status, headers: {get}, text(), json(), body }`，
 * 其中 `body` 是**异步可迭代**的（SSE 消费方 `for await (const chunk of res.body)` 照常工作）。
 *
 * @param url - 完整 URL（https 走 TLS，http 走明文，便于本机调试）。
 * @param init - `{ method, headers, body, signal }`。
 * @returns 类 `fetch` 响应对象。
 */
function nodeHttpsFetch(url, init = {}) {
	const target = new URL(url);
	const isTls = target.protocol === 'https:';
	const send = isTls ? httpsRequest : httpRequest;
	const method = init.method ?? 'GET';
	const headers = { ...(init.headers ?? {}) };
	// 强制 IPv4：见上面的注释（IPv6 不通时能省掉一整个超时窗口）。
	const options = {
		protocol: target.protocol,
		hostname: target.hostname,
		port: target.port === '' ? (isTls ? 443 : 80) : target.port,
		path: `${target.pathname}${target.search}`,
		method,
		headers,
		family: 4,
	};

	return new Promise((resolve, reject) => {
		const req = send(options, (res) => {
			const body = res[Symbol.asyncIterator]
				? res
				: (async function* empty() {})();
			resolve({
				ok: res.statusCode >= 200 && res.statusCode < 300,
				status: res.statusCode,
				headers: {
					get: (name) => res.headers[String(name).toLowerCase()] ?? null,
				},
				body,
				text: () =>
					new Promise((done, fail) => {
						const chunks = [];
						res.on('data', (chunk) => chunks.push(chunk));
						res.on('end', () => done(Buffer.concat(chunks).toString('utf8')));
						res.on('error', fail);
					}),
				json() {
					return this.text().then((text) => JSON.parse(text));
				},
			});
		});
		req.on('error', reject);
		if (init.signal) {
			if (init.signal.aborted) {
				req.destroy();
				const abort = new Error('The operation was aborted');
				abort.name = 'AbortError';
				reject(abort);
				return;
			}
			init.signal.addEventListener('abort', () => {
				req.destroy();
				const abort = new Error('The operation was aborted');
				abort.name = 'AbortError';
				reject(abort);
			});
		}
		if (init.body !== undefined && init.body !== null) req.write(init.body);
		req.end();
	});
}

/**
 * 把 fetch 的失败原因展开成可读文字。
 *
 * ⚠️ **`fetch failed` 本身不含任何可诊断信息** —— 真正的 errno 藏在 `error.cause`
 * （有时还要再深一层 `cause.cause`）里。不展开的话，界面上只能看到「fetch failed」，
 * 而下面这些完全不同的故障会给出**一模一样**的文案，导致无法判断该改代码还是换通道：
 *
 * | 真因（在 cause 里） | 含义 | 对策 |
 * |---|---|---|
 * | `ENOTFOUND` | DNS 解析不了（运营商 DNS 污染/无网络） | 换 DNS 或换通道 |
 * | `ETIMEDOUT` / `UND_ERR_CONNECT_TIMEOUT` | 连得上 DNS、连不上 IP（被阻断） | 换通道 |
 * | `ECONNRESET` / `UND_ERR_SOCKET` | 建连后被重置（中间设备干扰 TLS） | 换通道 |
 * | `CERT_*` | 证书校验失败（手机时间不对也会这样） | 校时 |
 *
 * @param error - `fetch` 抛出的错误。
 * @param channel - 这条错误来自哪条通道（`'undici'` / `'https'`）。
 * @returns 形如 `fetch failed ← ENOTFOUND getaddrinfo ENOTFOUND pair… ← 走的是专用 undici 池`。
 */
function describeFetchError(error, channel) {
	const parts = [];
	if (error?.message) parts.push(String(error.message));
	let cause = error?.cause;
	for (let depth = 0; cause && depth < 3; depth += 1) {
		const code = cause.code ? String(cause.code) : '';
		const message = cause.message ? String(cause.message) : '';
		const text = code !== '' && message !== '' ? `${code} ${message}` : code || message;
		if (text !== '') parts.push(text);
		cause = cause.cause;
	}
	// 同时连多个地址时抛的是 AggregateError，每个子错误的 errno 都要看到。
	if (Array.isArray(error?.errors) && error.errors.length > 0) {
		parts.push(
			error.errors
				.map((item) => `${item?.code ?? ''} ${item?.message ?? ''}`.trim())
				.join(' | '),
		);
	}
	parts.push(channel);
	return parts.join(' ← ');
}

/** 解析 undici 失败时的告警只打一次（否则每条 RPC 刷一行）。 */
let remoteFallbackWarned = false;

/** 专用连接池的初始化 Promise（并发首调只初始化一次）。 */
let remotePoolPromise;

/**
 * 建（或取）专用连接池。
 *
 * @returns `{ undici, agent }`；拿不到 undici 时 `null`。
 */
function remotePool() {
	if (remotePoolPromise === undefined) {
		remotePoolPromise = (async () => {
			const undici = await loadUndici();
			if (undici === null) return null;
			return { undici, agent: new undici.Agent({ connect: { autoSelectFamily: false } }) };
		})();
	}
	return remotePoolPromise;
}

/**
 * 解析 undici 模块。
 *
 * ## ⚠️ 为什么不能只写 `await import('undici')`（配对报 `fetch failed` 的根因）
 *
 * 本插件装在 `<DSH_HOME>/profiles/<profile>/node_modules/`，而 undici 是
 * **引擎**的依赖，装在 `<engine>/node_modules/undici` —— 它在插件的解析链
 * **之外**，于是 `import('undici')` 抛 `ERR_MODULE_NOT_FOUND`，`remoteFetch`
 * 静默退回全局 `fetch`（即上面那张表里 12018 ms 超时的那个）。
 *
 * 真机实测（CDY_TN90 / Android 10 / 插件 1.0.12，2026-10-16）：
 *
 * | 写法 | 结果 |
 * |---|---|
 * | `await import('undici')`（插件目录内） | **ERR_MODULE_NOT_FOUND** ❌ |
 * | `createRequire(process.argv[1]).resolve('undici')` | ✅ → `<engine>/node_modules/undici/index.js` |
 *
 * 这个故障是**间歇性**的：网络恰好在 Happy Eyeballs 回退窗口内时全局 fetch
 * 也能通，所以会看到「同一个版本、一台手机能配对、另一台报 fetch failed」——
 * 而**配对服务器日志里根本没有那次请求**（请求没出网），这是最快的判据。
 *
 * @returns undici 模块；都失败时 `null`。
 */
async function loadUndici() {
	// ① 首选：以**引擎入口**为锚点解析。插件由引擎加载，`process.argv[1]` 就是
	//    引擎的 bin.js（实测 …/engine/node_modules/@deepseek-ai/dsh/lib/bin.js），
	//    它的 node_modules 里就有 undici。
	const entry = process.argv[1];
	if (typeof entry === 'string' && entry !== '') {
		try {
			const { createRequire } = await import('node:module');
			const { pathToFileURL } = await import('node:url');
			const resolved = createRequire(pathToFileURL(entry).href).resolve('undici');
			return await import(pathToFileURL(resolved).href);
		} catch {
			/* 换下一个锚点 */
		}
	}
	// ② 兜底：本插件自己的解析链（将来若把 undici 声明成依赖，这里会命中）。
	try {
		return await import('undici');
	} catch {
		return null;
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
		// 未配对 = 没有令牌。**不要**提示用户去手改文件：面向大众的主路径是配对。
		throw new Error('还没配对：请在电脑的「设置 → 手机远程」里生成配对码，再到「添加工作区 → 电脑」填进去');
	}
	const res = await remoteFetch(`${config.endpoint}/api/dsh-remote/rpc`, {
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
 * 调配对服务器的一条接口。
 *
 * ⚠️ 同样走 `remoteFetch`（专用 undici 池）：配对服务器也在公网上，
 * 用全局 `fetch` 会撞上 Android 上那个 12 秒超时的连接策略问题（见 `remoteFetch`）。
 *
 * @param path - 例如 `/pair/claim`。
 * @param body - 请求体；不传就发 GET。
 * @returns 解析后的 JSON（失败抛错，错误文案直接可读）。
 */
async function pairFetch(path, body) {
	const init = body === undefined
		? { method: 'GET' }
		: {
				method: 'POST',
				headers: { 'content-type': 'application/json' },
				body: JSON.stringify(body),
			};
	const res = await remoteFetch(`${PAIR_ENDPOINT}${path}`, init);
	let payload;
	try {
		payload = await res.json();
	} catch {
		throw new Error(`配对服务器返回了非 JSON（HTTP ${res.status}）`);
	}
	if (!res.ok) {
		throw new Error(payload.message ?? payload.error ?? `配对失败: HTTP ${res.status}`);
	}
	// ⚠️ **不能要求 `payload.ok === true`**：配对服务器 `claim_handshake()` 的
	// 成功响应体只有 `{hostname, accessToken, subdomain}`，**没有 `ok` 字段**
	//（失败时才是 400 + `{error, message}`）。按 `ok` 判会让每一次正常配对
	// 都报「配对失败: HTTP 200」，而 HTTP 状态码明明是对的。
	if (payload.ok === false) {
		throw new Error(payload.message ?? payload.error ?? '配对失败');
	}
	return payload;
}

/**
 * 用 6 位配对码领取这台电脑的连接信息。
 *
 * ⚠️ **配对码是一次性的**：服务器在 `claim` 成功后立刻删掉它。所以这里的顺序
 * 必须是「先领、后写盘」—— 反过来（先写盘再领）一旦写盘失败，码已经作废，
 * 用户得回电脑上重新生成一个。
 *
 * @param code - 用户输入的 6 位码（大小写、空格、连字符都由服务器容错）。
 * @returns 落盘后的 `{ endpoint, token }`。
 */
async function claimPairing(code) {
	const payload = await pairFetch('/pair/claim', { pairCode: code });
	const endpoint = `https://${payload.hostname}`;
	const token = payload.accessToken;
	if (typeof endpoint !== 'string' || typeof token !== 'string' || token === '') {
		throw new Error('配对服务器没有返回完整的连接信息');
	}
	return saveConfig(endpoint, token);
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
		ctx.logger?.info?.('[dsh-remote-mobile] 尚未配对：请到「添加工作区 → 电脑」填配对码（配对后自动连接）');
	}

	/** 远程会话 id 集合：这些会话的 prompt 转发电脑，不在手机本地跑模型。 */
	const remoteSessions = loadRemoteSessions();

	/** 镜像工作区登记表：`<slug> → <电脑路径>`（见 `WORKSPACES_PATH` 的注释）。 */
	const workspaceMap = loadWorkspaceMap();

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

	/**
	 * 电脑侧的静默缓存：`{ catalog, presets, directory, at }`。
	 *
	 * ## 为什么要缓存（用户需求原文）
	 *
	 * 「用户配对成功之后 就可以静默的拉取一些数据了，比如模型列表和预设列表，
	 * 以及目录树这样体验会好很多……因为一般项目目录是不会变的，可以提前加载」。
	 *
	 * 配对后立刻拉一次，之后前端一打开模型菜单就有内容，不用等一轮往返。
	 *
	 * ⚠️ **`directory` 缓存「根」那一层**（`listDirectory` 不给 path 时返回的
	 * home），因为「点开电脑页签」是每次都逃不掉的第一步，而它现在必然要过一轮
	 * 隧道往返 —— 用户看到的就是那个转圈（原话：「还是有一个加载过程，
	 * 不能预加载吗」）。
	 *
	 * ⚠️ **`dirs` 是「用户正在看的那一层」的下一层**（`电脑路径 → listing`）。
	 * 只缓存根层还不够：用户报障原文「你现在好像只缓存了顶级目录，打开某个
	 * 文件夹还是要加载」—— 点进任何一个子目录仍要过一次隧道往返（实测
	 * 700～1200ms）。而用户看到列表到点击之间的那一两秒，完全够把这一层的
	 * 子目录拉回来（见 `prefetchDirs`）。
	 *
	 * **整棵目录树不进缓存**：电脑上几万个目录，全拉一遍既慢又没意义，而且
	 * 会大面积显示过期内容。所以 `dirs` 只装「用户实际看过的那几层的下一层」，
	 * 上限见 `DESKTOP_DIRS_MAX`。过期窗口由「刷新」按钮关闭。
	 */
	const desktopCache = { catalog: null, presets: null, directory: null, dirs: {}, at: 0 };

	/** `desktopCache.dirs` 的容量上限（按插入顺序淘汰最旧的那些）。 */
	const DESKTOP_DIRS_MAX = 200;

	/**
	 * 把用户**正在看的这一层**的子目录拉回手机本地。
	 *
	 * ## 为什么要有这个（用户报障原文）
	 *
	 * 「你现在好像只缓存了顶级目录，打开某个文件夹还是要加载」—— 根层命中缓存后
	 * 不再打隧道了，可点进任何一层子目录仍然要过一趟 Cloudflare，实测
	 * 700～1200ms，用户看到的就是那个转圈。
	 *
	 * 而**用户看列表、再决定点哪个文件夹，中间至少有一两秒**。这段时间完全够
	 * 把这一层的子目录并发拉回来 —— 用户点下去时就已经在本地了。
	 *
	 * ## 只预取「一层」而不是整棵树
	 *
	 * 电脑上一个项目根目录动辄几十上百个子目录，整棵树递归下去是几千次往返，
	 * 既慢又会大面积显示过期内容。**只拉下一层**：用户真正会点的就是这一层里
	 * 的那几个，而下下一层等他进了这一层再预取（`listDesktop` 每次都会触发）。
	 *
	 * ## 并发上限
	 *
	 * 隧道是单个 Cloudflare 连接，一次打几十个并发没有意义还会互相挤。所以
	 * 分批（每批 `DESKTOP_PREFETCH_BATCH` 个）顺序推，**不 await**：这是后台
	 * 动作，调用方（`listDesktop`）要立刻把当前层回给前端。
	 *
	 * @param listing - 用户当前看到的那一层（`listDirectory` 的产物）。
	 */
	const prefetchDirs = (listing) => {
		if (config.token === EMPTY_TOKEN) return;
		const dirs = listing?.entries?.filter?.((entry) => entry?.isDirectory === true) ?? [];
		if (dirs.length === 0) return;
		// ⚠️ **已经缓存过的不再拉**：来回翻页时不能把同一批目录重复请求一遍。
		const todo = dirs
			.map((entry) => entry.path)
			.filter((path) => typeof path === 'string' && path !== '' && desktopCache.dirs[path] === undefined);
		if (todo.length === 0) return;
		void (async () => {
			for (let at = 0; at < todo.length; at += DESKTOP_PREFETCH_BATCH) {
				const batch = todo.slice(at, at + DESKTOP_PREFETCH_BATCH);
				await Promise.all(
					batch.map(async (path) => {
						// 期间用户可能已经点「刷新」清过缓存，那就别再写回去了。
						if (desktopCache.dirs[path] !== undefined) return;
						try {
							const child = await callRemote(config, 'listDirectory', { path });
							rememberDir(path, child);
						} catch {
							/* 单个目录拉不到（被删 / 无权限）不影响其它目录，静默跳过 */
						}
					}),
				);
			}
		})();
	};

	/** 每批并发数（见 `prefetchDirs` 的注释）。 */
	const DESKTOP_PREFETCH_BATCH = 6;

	/**
	 * 写一格目录缓存，超出上限就按插入顺序淘汰最旧的。
	 *
	 * ⚠️ 淘汰必须用 `Object.keys` 的**插入顺序**：JS 对象的字符串键就是这个顺序，
	 * 而 `desktopCache.dirs` 正好是按用户浏览顺序写进去的 —— 最旧的键就是最早
	 * 看过的那些层，淘汰它们对用户体感最没影响。
	 *
	 * @param path - 电脑上的绝对路径。
	 * @param listing - 该层的列表结果。
	 */
	function rememberDir(path, listing) {
		desktopCache.dirs[path] = listing;
		const keys = Object.keys(desktopCache.dirs);
		for (let at = 0; at + DESKTOP_DIRS_MAX < keys.length; at += 1) {
			delete desktopCache.dirs[keys[at]];
		}
	}

	/**
	 * 静默预拉电脑的模型目录与预设列表。
	 *
	 * ⚠️ **失败必须完全静默**：这是配对后的后台动作，用户没点任何东西。
	 * 弹错误只会让人以为配对坏了 —— 而实际上配对是成功的，只是这台电脑的
	 * 某个 provider 没配好（官方 `modelCatalog` 会把这种失败放进 `failures`，
	 * 根本不会抛）。
	 *
	 * @param why - 日志里标明是谁触发的（配对 / 手动刷新）。
	 */
	const prefetchDesktop = async (why) => {
		if (config.token === EMPTY_TOKEN) return;
		const [catalog, presets, directory] = await Promise.all([
			callRemote(config, 'modelCatalog', {}).catch((error) => {
				ctx.logger?.warn?.(`[dsh-remote-mobile] 预拉模型列表失败（${why}）: ${String(error?.message ?? error)}`);
				return null;
			}),
			callRemote(config, 'presetList', {}).catch((error) => {
				ctx.logger?.warn?.(`[dsh-remote-mobile] 预拉预设列表失败（${why}）: ${String(error?.message ?? error)}`);
				return null;
			}),
			// 根目录那一层：不带 path ⇒ 电脑返回它的 home，正是用户点开
			// 「电脑」页签第一眼看到的东西。见 `desktopCache.directory` 的注释。
			callRemote(config, 'listDirectory', {}).catch((error) => {
				ctx.logger?.warn?.(`[dsh-remote-mobile] 预拉目录树失败（${why}）: ${String(error?.message ?? error)}`);
				return null;
			}),
		]);
		if (catalog !== null) desktopCache.catalog = catalog;
		if (presets !== null) desktopCache.presets = presets;
		if (directory !== null) desktopCache.directory = directory;
		desktopCache.at = Date.now();
		const models = catalog?.groups?.reduce((n, g) => n + (g.models?.length ?? 0), 0) ?? 0;
		const dirs = directory?.entries?.length ?? directory?.items?.length ?? 0;
		ctx.logger?.info?.(
			`[dsh-remote-mobile] 已预拉电脑数据（${why}）：${models} 个模型、${presets?.presets?.length ?? 0} 个预设、根目录 ${dirs} 项`,
		);
	};

	/**
	 * 取电脑的模型目录，缓存为空就**当场拉一次**。
	 *
	 * ⚠️ **不能只依赖配对时的预拉**。那是异步的后台动作，而前端页面一加载
	 * 就会调一次 `modelCatalog`（`ModelCatalogDirectory` 构造后立刻 `load()`，
	 * 见 `dsh-client-ui-model-selection/lib/client.js:354`）—— 谁先到谁决定
	 * 用户看到什么。拿不到电脑那份就返回手机本地列表，症状正是用户报的
	 * 「预设列表正确地加载了电脑预设，但模型列表还是没同步」：预设走
	 * `remoteExportList`，**没有缓存层**、每次重读，所以它是对的。
	 *
	 * @returns 电脑的模型目录；拉不到时 `null`。
	 */
	const desktopCatalog = async () => {
		if (desktopCache.catalog !== null) return desktopCache.catalog;
		try {
			desktopCache.catalog = await callRemote(config, 'modelCatalog', {});
			return desktopCache.catalog;
		} catch (error) {
			ctx.logger?.warn?.(
				`[dsh-remote-mobile] 取电脑模型列表失败: ${String(error?.message ?? error)}`,
			);
			return null;
		}
	};

	/**
	 * 把电脑的模型目录并进手机本地的目录。
	 *
	 * ## ⚠️ 为什么是「并」而不是「换」
	 *
	 * 官方前端的模型目录是**全局唯一一份**（`ModelCatalogDirectory` 由
	 * `ModelDirectoryResolver` 持有，所有会话共享，见
	 * `dsh-client-ui-model-selection/lib/client.js:340`），请求本身**不带 sessionId**。
	 * 所以「远程会话看电脑的、本地会话看手机的」这种按会话切换，在**这一层**
	 * 表达不出来。
	 *
	 * 折中：这里把两份**并起来**（电脑的排前面，同 id 的 provider 以电脑为准），
	 * 由前端按「当前会话的工作区是不是电脑镜像目录」再筛一次
	 * （见 client.js 的 `filterCatalogForWorkspace`）。合并时必须给电脑那几组
	 * 打上 `desktop: true`，否则前端分不出哪些是电脑的 —— 用户需求原文：
	 * 「用户如果当前选择的是 app 本地文件夹，那么就不要出现电脑的模型，
	 * 以这个文件夹为隔离条件，互不干预」。
	 *
	 * ⚠️ **`localGroups` / `localFailures` 必须一起带上**。因为同 id 的 provider
	 * 以电脑为准 ⇒ 本地那一组被**丢掉**了；而本地会话的 `current` 很可能正指向
	 * 它（手机本地的 `deepseek-official` 与电脑的同名）。前端要「本地工作区只显示
	 * 本地模型」，就得能把被覆盖的那组**还原**回来 —— 只靠 `groups` 做不到，
	 * 那组已经不在里面了。这两个字段是**本插件加的**，官方 `syncInputs` 只读
	 * `groups` / `failures` / `default`，多带两个不会影响渲染。
	 *
	 * ⚠️ `desktop` 同理是**我们加的非官方字段**。前端渲染只读 `id` / `name` /
	 * `models`（见 `optionsOf`），多一个字段无害。
	 *
	 * @param local - 手机本地的模型目录。
	 * @param desktop - 电脑的模型目录（`buildModelCatalog` 的产物）。
	 * @returns 合并后的目录（额外带 `localGroups` / `localFailures`）。
	 */
	function mergeCatalog(local, desktop) {
		const groups = (desktop?.groups ?? []).map((group) => ({ ...group, desktop: true }));
		const seen = new Set(groups.map((group) => group.id));
		for (const group of local?.groups ?? []) {
			if (!seen.has(group.id)) groups.push(group);
		}
		return {
			...local,
			groups,
			// failures 同样打标记：本地工作区里不该看到电脑 provider 的加载失败。
			failures: [
				...(desktop?.failures ?? []).map((failure) => ({ ...failure, desktop: true })),
				...(local?.failures ?? []),
			],
			// ⚠️ 前端「本地工作区只显示本地模型」的还原依据（见上面注释）。
			localGroups: local?.groups ?? [],
			localFailures: local?.failures ?? [],
			routableProviders: [
				...new Set([...(local?.routableProviders ?? []), ...(desktop?.routableProviders ?? [])]),
			],
		};
	}

	/**
	 * 把电脑的预设并进手机本地的预设名单（同 id 以电脑为准）。
	 *
	 * @param local - 手机本地的 `{presets}`。
	 * @param desktop - 电脑的 `{presets}`。
	 * @returns 合并后的名单。
	 */
	function mergePresets(local, desktop) {
		const presets = [...(desktop?.presets ?? [])];
		const seen = new Set(presets.map((preset) => preset.id));
		for (const preset of local?.presets ?? []) {
			if (!seen.has(preset.id)) presets.push(preset);
		}
		return { ...local, presets };
	}

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
			// ⚠️ 必须走 `remoteFetch`（自带 dispatcher）：全局 fetch 的 dispatcher
			// 在 Android 上连不通公网，见 `remoteFetch` 的注释。
			const res = await remoteFetch(url, {
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

	// ---- 拦截官方 create：备选区选中「电脑目录」时自动绑成远程会话 ----
	//
	// ⚠️ **这是「第二次起就变本地」的根因修复，不是锦上添花。**
	//
	// 用户第一次是在「电脑文件夹」对话框里选的目录，走 `createSession` 桥 ——
	// 那条路会把 sessionId 记进 `remoteSessions`，prompt 因此被转发电脑。
	//
	// 但第二次他是**在新建对话首页的备选区里**点上次那个工作区。官方前端此时
	// 直接 `sessionController.create({workspaceId})`，**完全不经过本插件**，
	// 于是 `remoteSessions` 里没有这个 sessionId ⇒ prompt 拦截不命中 ⇒
	// 消息交给手机本地 agent（真跑手机模型、cwd 是手机路径）。
	// 用户看到的就是「问他工作目录，答的是安卓目录」。
	//
	// 这里在同一个路口把它认出来并补上绑定：镜像目录 → 电脑路径 → 让电脑建同名会话。
	ctx.effect(() => {
		const controller = ctx.sessionController;
		if (controller === undefined || typeof controller.create !== 'function') return;
		const originalCreate = controller.create.bind(controller);
		controller.create = async (request) => {
			const result = await originalCreate(request);
			const sessionId = result?.sessionId;
			if (typeof sessionId !== 'string' || sessionId === '') return result;
			// 已经绑过的（走对话框那条路）不用再绑。
			if (remoteSessions.has(sessionId)) return result;
			// 解析这个会话的 cwd：优先 `workspaceId`（备选区走的就是这条），
			// 退回请求里的 `cwd`（直接给路径的情形）。
			let dir = request?.cwd;
			if (request?.workspaceId !== undefined) {
				const workspace = ctx.get?.('workspaceRegistry')?.get?.(request.workspaceId);
				if (workspace?.path !== undefined) dir = workspace.path;
			}
			const desktopPath = desktopPathOfMirror(workspaceMap, dir);
			if (desktopPath === undefined) return result;
			// 没配对时**不能**绑：绑了之后 prompt 会去打一个不存在的电脑，
			// 用户看到的是「发消息就报错」，比退回本地会话更难理解。
			if (config.token === EMPTY_TOKEN) {
				ctx.logger?.warn?.(
					`[dsh-remote-mobile] 会话 ${sessionId} 选的是电脑目录「${desktopPath}」，但还没配对，先当本地会话`,
				);
				return result;
			}
			try {
				await callRemote(config, 'createSession', { sessionId, cwd: desktopPath });
			} catch (error) {
				// 电脑没建成功就别绑 —— 否则这个会话的每条消息都会失败。
				ctx.logger?.warn?.(
					`[dsh-remote-mobile] 电脑建会话失败，${sessionId} 退回本地会话: ${String(error?.message ?? error)}`,
				);
				return result;
			}
			remoteSessions.add(sessionId);
			saveRemoteSessions(remoteSessions, ctx.logger);
			follow(sessionId);
			ctx.logger?.info?.(
				`[dsh-remote-mobile] 备选区选中电脑目录「${desktopPath}」，会话 ${sessionId} 已绑为远程会话`,
			);
			return result;
		};
		return () => {
			controller.create = originalCreate;
		};
	}, 'dsh-remote-mobile: create 拦截（备选区选电脑目录）');

	// ---- 拦截官方 prompt：远程会话转发电脑，本地会话走原逻辑 ----
	ctx.effect(() => {
		const controller = ctx.sessionController;
		if (controller === undefined) return;
		const originalPrompt = controller.prompt.bind(controller);
		// ⚠️ **签名必须带上第二个参数 `signal`**（`(request, signal)`）。
		//
		// 官方 `dsh-api-session-controller/lib/index.js:3096` 是
		// `prompt(request, signal) { signal.throwIfAborted(); ... }` —— 它**无条件**
		// 对 `signal` 调 `throwIfAborted()`。我们的包装若只声明 `(request)`，
		// 转发给 `originalPrompt(request)` 时 `signal` 就是 `undefined`，
		// 于是发消息必报：
		//   `Cannot read properties of undefined (reading 'throwIfAborted')`
		// 症状是**消息根本发不出去**，而报错文案完全看不出是参数漏传。
		controller.prompt = async (request, signal) => {
			if (!remoteSessions.has(request.sessionId)) return originalPrompt(request, signal);
			// ⚠️ **必须把附件一起转过去**，不能只挑 `text` part。
			//
			// 用户报的就是这个：「照片就会被吞了 AI 只能看到我的文字」。前端的
			// `serializeAttachments()`（`dsh-client-ui-conversation/lib/client.js:3299`）
			// 给图片发的是 `{type:'image', mediaType, data(base64)}` —— **字节是内联的**，
			// 直接透传，电脑会用自己的 `attachments.admitPromptContent` 落盘。
			//
			// `{type:'file', receiptId}` 不转（receipt 属于手机的 `fileUploads`，
			// 电脑解不开），由电脑端降级成一句说明（见其 `prompt` 分支的注释）。
			const content = Array.isArray(request.content) ? request.content : [];
			const text = content
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
				// 附件形态一并带上（没有附件时给空数组，电脑端会退回纯文本）。
				content,
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

	// ---- 屏蔽远程会话在手机本地的标题生成 ----
	//
	// ⚠️ 官方 `@deepseek-ai/dsh-session-title/lib/index.js` 的
	// `ctx.on("session/event")` 对**任何** `source.kind === 'user'` 的
	// `user/message` 都会在**本地**写一条 `session/title`
	//（`onUserMessage()` → `ensureFallback()`，源码 379-403 行），并在首轮再置
	// `state.pending`，让标题 LLM 补一条 `session/title-llm-request`
	//（`onRequestHeader()` 消费，源码 386 行那个条件）。
	//
	// 手机本地日志是电脑日志的**逐条镜像**，`mirrorEvent` 靠 seq 折算对齐
	//（见其注释）。镜像进来的 `user/message` 同样满足那个条件，于是手机端
	// **本地多写 2 条**事件，把电脑端同一 seq 上的 2 条真实事件挤掉 —— 而
	// `mirrorEvent` 对「折算 seq < 本地 seq」的事件是按「重连补发的历史」
	// **静默丢弃**的，被挤掉的那 2 条在手机上永久缺失。
	//
	// 真机实测（探针会话 `session-dc52fa34`，两轮对话，`_test/compare-logs.mjs`）：
	// 电脑端 41 条、手机端 41 条，seq 0-40 只有 **seq 9 与 seq 16** 不一致，
	// 丢的是电脑端的 workspace 说明（`system-reminder` 那条 `user/message`）
	// 与电脑端自己的 `session/title`，而手机端那两格是**本地生成**的
	// （phone seq=9 `time=1792088906625` `source:{kind:"fallback"}`，与电脑端
	// 同一条的 `time` 不同 ⇒ 确认不是镜像来的）。本轮丢的是说明文字，
	// 时序一变被挤掉的就会是 `assistant/message` —— 也就是**用户看不到 AI 回复**。
	//
	// 标题的真源在电脑端：电脑端生成后会把 `session/title` 流过来，所以远程
	// 会话在手机本地**一律不生成**标题。这样本地 seq 不再被抢占，两端日志
	// 恢复逐条一致，也省掉一次白烧用户额度的本地标题 LLM 调用。
	ctx.effect(() => {
		/** 已完成的还原函数（插件卸载时逐个执行）。 */
		const undo = [];
		let done = false;

		/**
		 * 给标题服务挂上「远程会话直接短路」的包装。
		 *
		 * @param titleService - `ctx.sessionTitle` 实例；未就绪时为 undefined。
		 * @returns 是否已经完成（含此前已完成）。
		 */
		const patchTitleService = (titleService) => {
			if (done) return true;
			if (titleService === undefined || typeof titleService.onUserMessage !== 'function') {
				return false;
			}
			const original = titleService.onUserMessage;
			// ⚠️ 必须用 `function` 而不是箭头函数：官方是 `this.onUserMessage(...)`
			// 这样调的，箭头函数会让 `this` 丢失、`original.call(this, ...)` 里的
			// `this` 落成 undefined，标题服务内部读 `this.registration` 即抛错。
			titleService.onUserMessage = function (session, event) {
				if (remoteSessions.has(session.id)) return undefined;
				return original.call(this, session, event);
			};
			undo.push(() => {
				titleService.onUserMessage = original;
			});
			done = true;
			return true;
		};

		// ⚠️ 两条路径都各自包 try/catch：屏蔽失败只是退化成「远端事件可能错位」，
		// 但**绝不能拖垮整个插件** —— 那会让远程功能完全不可用，比错位严重得多。
		//（`ctx.get(name)` 在服务未注册时返回 `undefined`，不抛错；这里防的是
		//  `ctx.get` / `ctx.inject` 本身在引擎版本变化后不再可用。）
		//
		// ⚠️ **两条路要分开 try**：若合在一个 try 里，`ctx.get` 一旦抛错，
		// `ctx.inject` 这条「服务迟一点才注册」的兜底路径会被一并跳过 ——
		// 守卫就这么**静默失效**了，而且日志里只有一条 warn，很难看出少兜了一次。
		const warn = (error) =>
			ctx.logger?.warn?.(
				`[dsh-remote-mobile] 未能屏蔽本地标题生成，远端事件镜像可能错位: ${String(
					error?.message ?? error,
				)}`,
			);

		// 路径一：服务通常在本插件加载前就已注册，先同步打一次。
		let patched = false;
		try {
			patched = patchTitleService(ctx.get('sessionTitle'));
		} catch (error) {
			warn(error);
		}

		// 路径二：还没就绪（或路径一失败）则交给依赖注入，等服务注册后再打。
		if (!patched) {
			try {
				ctx.inject(['sessionTitle'], (scoped) => {
					patchTitleService(scoped.sessionTitle);
				});
			} catch (error) {
				warn(error);
			}
		}

		return () => {
			for (const fn of undo) fn();
		};
	}, 'dsh-remote-mobile: 屏蔽远程会话的本地标题生成');

	// ---- 把电脑的模型目录与预设名单接进官方前端 ----
	//
	// ## 为什么拦在宿主侧，而不是改前端
	//
	// 官方前端的模型目录来自 `ctx.remote.session.modelCatalog()`（宿主 RPC），
	// 预设来自 `ctx.remote.agentPresets.list()`（宿主 RPC）。两个 RPC 的**宿主实现**
	// 就是 `sessionController.modelCatalog` 与 `agentPresets.remoteExportList`
	// （装饰器 `Remote("modelCatalog")` / `Remote("list")`，见
	// `dsh-api-session-controller/lib/index.js:2586`、
	// `dsh-agent-preset-registry/lib/index.js:426`）。
	//
	// 这两处都是 `Remote(...)` 装饰的方法，**经实例动态派发**（与 `create`/`prompt`
	// 同一机制），所以替换实例上的方法就能同时拦到直接调用与 RPC 调用 —— 不必碰
	// 前端 bundle，升级也不会碎。
	//
	// 用户要的是「选了电脑目录之后，模型列表刷新为电脑的」。前端拿到的目录里
	// **电脑的排前面**，手机自己那几组留在后面（原因见 `mergeCatalog` 的注释）。
	ctx.effect(() => {
		const undo = [];

		/** 已配对才值得把电脑的东西并进来；没配对时保持手机本地原样。 */
		const paired = () => config.token !== EMPTY_TOKEN;

		// ① 模型目录
		const controller = ctx.sessionController;
		if (controller !== undefined && typeof controller.modelCatalog === 'function') {
			const originalCatalog = controller.modelCatalog.bind(controller);
			controller.modelCatalog = async (...args) => {
				const local = await originalCatalog(...args);
				if (!paired()) return local;
				const desktop = await desktopCatalog();
				if (desktop === null) return local;
				return mergeCatalog(local, desktop);
			};
			undo.push(() => {
				controller.modelCatalog = originalCatalog;
			});

			// ② 选模型：远程会话的选择必须落到**电脑**上。
			//
			// ⚠️ 不转发的话，用户在前端选的模型只写进手机本地会话的投影，
			// 电脑那边仍用它自己的默认模型 —— 表现是「选了也不生效」。
			if (typeof controller.selectModel === 'function') {
				const originalSelect = controller.selectModel.bind(controller);
				controller.selectModel = async (request) => {
					if (!remoteSessions.has(request?.sessionId)) return originalSelect(request);
					const selected = await callRemote(config, 'selectModel', {
						sessionId: request.sessionId,
						provider: request.provider,
						model: request.model,
						...(request.reasoningEffort === undefined
							? {}
							: { reasoningEffort: request.reasoningEffort }),
					});
					return selected;
				};
				undo.push(() => {
					controller.selectModel = originalSelect;
				});
			}
		}

		// ③ 预设名单
		const patchPresets = (presets) => {
			if (presets === undefined || typeof presets.remoteExportList !== 'function') return false;
			const originalList = presets.remoteExportList.bind(presets);
			presets.remoteExportList = async (...args) => {
				const local = await originalList(...args);
				if (!paired() || desktopCache.presets === null) return local;
				return mergePresets(local, desktopCache.presets);
			};
			undo.push(() => {
				presets.remoteExportList = originalList;
			});
			return true;
		};

		// 与标题守卫同构：先同步试一次，没就绪再交给依赖注入兜底。
		// 两条路分开 try，否则 `ctx.get` 一抛错就把 `ctx.inject` 那条兜底也跳过了。
		const warn = (error) =>
			ctx.logger?.warn?.(
				`[dsh-remote-mobile] 未能接上电脑的模型/预设列表: ${String(error?.message ?? error)}`,
			);
		let patched = false;
		try {
			patched = patchPresets(ctx.get('agentPresets'));
		} catch (error) {
			warn(error);
		}
		if (!patched) {
			try {
				ctx.inject(['agentPresets'], (scoped) => {
					patchPresets(scoped.agentPresets);
				});
			} catch (error) {
				warn(error);
			}
		}

		return () => {
			for (const fn of undo) fn();
		};
	}, 'dsh-remote-mobile: 电脑模型目录 / 预设名单接入');

	// ---- 把远程会话的「文件树」接到电脑上（侧边栏 + 文件预览） ----
	//
	// 官方侧边栏的文件面板（`dsh-client-ui-sidebar-files`）以及内容预览
	// （`dsh-client-ui-document-preview`）都只认 host 的 `workspaceFiles` 服务：
	//
	//   list(scope, path)              → 一层目录（含文件）
	//   read(scope, path, {offset})    → 文本分页
	//   stat(scope, path)              → 版本/大小
	//   readBytes(scope, path, opts)   → 二进制分页
	//
	//   scope = {sessionId, workspaceRoot}，由官方按 sessionId 从会话 header 现算。
	//
	// ⚠️ **远程会话不能走本地的 `workspaceFiles`**：它的 `workspaceRoot` 是本插件
	// 在手机上的**镜像空壳目录**（只为了让「备选区」里有个能点的项，见
	// `ensureDesktopWorkspace`）。照本地那条路走，侧边栏里永远是**空的** ——
	// 而用户要的是看到电脑上的目录树。所以这里把这几条调用转给电脑。
	//
	// ⚠️ **`changes`（自动刷新）刻意不转发**：官方那份是流式 RPC，本插件的中继
	// 是一问一答，跨端桥一条长连的代价远大于收益。本地的 watcher 落在镜像空壳上，
	// 会正常回 `ready`、只是永远不报变化 —— 侧边栏不会因此报错，用户用手动
	// 「刷新」按钮看最新内容（产品上也是这么定的）。
	ctx.effect(() => {
		const undo = [];

		/**
		 * 判定会话是不是远程会话，并把路径换算成「相对于工作区」的一段。
		 *
		 * @returns 相对路径（根目录是空串）；不是远程会话时 `undefined`。
		 */
		const mapPath = (scope, path) => {
			const desktopRoot = desktopPathOfMirror(workspaceMap, scope?.workspaceRoot);
			if (desktopRoot === undefined) return undefined;
			if (typeof path !== 'string' || path === '') return undefined;
			// 侧边栏给的路径以会话 cwd 开头（官方 `childPath` 逐层拼出来），而
			// `scope.workspaceRoot` 与它同源（都是会话 header 的 cwd）⇒ 前缀直接剥掉。
			//
			// ⚠️ **只传相对路径，绝不在这里拼电脑的绝对路径**：两端盘符/分隔符不同，
			// 拼接是电脑那一端的事（见文件头「cwd 绝不跨端传」）。
			const a = path.replace(/\\/g, '/');
			const b = String(scope.workspaceRoot).replace(/\\/g, '/').replace(/\/+$/, '');
			if (a === b) return { path: '' };
			if (a.startsWith(`${b}/`)) return { path: a.slice(b.length + 1) };
			// 不在工作区内的路径：原样交给电脑去解析（它自己会判越界）。
			return { path: a };
		};

		/**
		 * 只处理远程会话；本地会话原样交给官方。
		 *
		 * @param name - `workspaceFiles` 上的方法名。
		 * @param original - 该方法**本来的**实现（可能没有）。
		 */
		const forward = (name, original) => async (scope, path, ...rest) => {
			const mapped = remoteSessions.has(scope?.sessionId) ? mapPath(scope, path) : undefined;
			if (mapped === undefined) {
				if (original === undefined) throw new Error(`workspaceFiles.${name} 不可用`);
				return await original(scope, path, ...rest);
			}
			if (name === 'list') {
				return await callRemote(config, 'fileList', { sessionId: scope.sessionId, path: mapped.path });
			}
			if (name === 'read') {
				const range = rest[0] ?? {};
				return await callRemote(config, 'fileRead', {
					sessionId: scope.sessionId,
					path: mapped.path,
					...(range.offset === undefined ? {} : { offset: range.offset }),
				});
			}
			if (name === 'stat') {
				return await callRemote(config, 'fileStat', { sessionId: scope.sessionId, path: mapped.path });
			}
			// readBytes：字节没法直接进 JSON，两端用 base64 过一道。
			//
			// ⚠️ **丢掉 `baseFile`**：官方那份是「相对 baseFile 所在目录」的语义，
			// 而这里的路径已经换算成电脑上的绝对路径了，再带上一个手机路径的
			// baseFile 只会让电脑去解析一个不存在的相对路径。
			const result = await callRemote(config, 'fileReadBytes', {
				sessionId: scope.sessionId,
				path: mapped.path,
				...(rest[0]?.range === undefined ? {} : { range: rest[0].range }),
			});
			const { data, ...meta } = result ?? {};
			return { ...meta, data: new Uint8Array(Buffer.from(String(data ?? ''), 'base64')) };
		};

		const patch = (files) => {
			if (files === undefined || typeof files.list !== 'function') return false;
			let count = 0;
			for (const name of ['list', 'read', 'stat', 'readBytes']) {
				const fn = files[name];
				if (typeof fn !== 'function') continue;
				files[name] = forward(name, fn.bind(files));
				undo.push(() => {
					files[name] = fn;
				});
				count += 1;
			}
			return count > 0;
		};

		const warn = (error) =>
			ctx.logger?.warn?.(
				`[dsh-remote-mobile] 未能接上电脑的文件树: ${String(error?.message ?? error)}`,
			);
		let patched = false;
		try {
			patched = patch(ctx.get('workspaceFiles'));
		} catch (error) {
			warn(error);
		}
		if (!patched) {
			try {
				ctx.inject(['workspaceFiles'], (scoped) => {
					patch(scoped.workspaceFiles);
				});
			} catch (error) {
				warn(error);
			}
		}

		return () => {
			for (const fn of undo) fn();
		};
	}, 'dsh-remote-mobile: 电脑文件树接入（侧边栏 / 预览）');

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
								// 解绑要能停掉全部旧连接、清掉全部搬运状态（见 `unpair`）。
								follows,
								mirrorStates,
								// ⚠️ **必须显式传进来**：`handleBridge` 是模块级函数，作用域里
								// 没有 `apply()` 的闭包变量；漏传就是 `workspaceMap is not defined`，
								// 用户点「在此新建对话」时必然失败（1.0.16 的真实报障）。
								workspaceMap,
								persist: () => saveRemoteSessions(remoteSessions, ctx.logger),
								// 模型/预设的静默缓存：配对后预拉、刷新按钮回写（见 desktopCache）。
								desktopCache,
								prefetch: prefetchDesktop,
								// ⚠️ 同 `workspaceMap`：`handleBridge` 是模块级函数，闭包里的
								// `prefetchDirs` / `rememberDir` 它看不见，必须显式传。
								prefetchDirs,
								rememberDir,
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
		// 已配对的话，启动时也把电脑的模型/预设静默拉一次 —— 否则重启 App 后
		// 用户直接打开模型菜单会看到空列表（缓存是内存态，重启即丢）。
		// ⚠️ 不 await：这是后台动作，别拖慢引擎启动。`.catch` 兜住最后一道，
		// 免得一个后台失败变成 unhandled rejection 打脏引擎日志。
		prefetchDesktop('启动').catch(() => {});
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
 * 把电脑路径压成一个**文件名安全**的稳定 slug。
 *
 * 只用于镜像目录的命名（用户看不到），所以要求是「同一个电脑路径永远得到
 * 同一个名字，不同路径几乎不可能撞」。`createHash` 那 8 位十六进制就是防撞的：
 * 纯字符替换会把 `C:\a-b` 与 `C:\a\b` 压成同一个名字。
 *
 * @param desktopPath - 电脑上的绝对路径（win32 语义）。
 * @returns 形如 `c-users-xiaopan-1a2b3c4d` 的 slug。
 */
function desktopSlug(desktopPath) {
	const flat = desktopPath.replace(/[^a-zA-Z0-9]+/g, '-').replace(/^-+|-+$/g, '').toLowerCase();
	const digest = createHash('sha256').update(desktopPath).digest('hex').slice(0, 8);
	return `${flat === '' ? 'root' : flat}-${digest}`;
}

/**
 * 取电脑路径的末段当工作区标题。
 *
 * ⚠️ **不能直接用 `node:path` 的 `basename`**：手机是 posix 语义，
 * `basename('C:\\Users\\XIAOPAN')` 会把整串原样返回（反斜杠不是分隔符），
 * 侧边栏就会显示一长条 `C:\Users\XIAOPAN`。这里两种分隔符都切。
 *
 * @param desktopPath - 电脑上的绝对路径。
 * @returns 末段；没有末段（如 `C:\`）时退回去掉尾部斜杠的原串。
 */
function desktopTitle(desktopPath) {
	const trimmed = desktopPath.replace(/[/\\]+$/, '');
	const cut = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
	const name = cut < 0 ? trimmed : trimmed.slice(cut + 1);
	return name === '' ? desktopPath : name;
}

/**
 * 为电脑上的一个目录，在手机本地建（或复用）一个**同名镜像工作区**。
 *
 * ## 为什么要建工作区，而不是塞进「默认工作区」
 *
 * 前端把会话归到哪个工作区，是**按 cwd 路径精确匹配**的
 * （`dsh-workspace/lib/index.js:102` 的 `sessionIds` getter）。
 * 早先的实现把远程会话的 cwd 设成手机默认工作区的路径，于是**所有**远程会话
 * 都堆在「默认工作区」底下 —— 用户在电脑页签里选了 `C:\Users\XIAOPAN`，
 * 侧边栏却显示「默认工作区」，看起来就像「建完又回到默认工作去了」。
 *
 * 正解是给每个电脑目录配一个**独立的手机本地镜像目录**，用它注册一个独立
 * 工作区，标题取电脑目录的末段（`XIAOPAN`）。镜像目录是空的、永远不会被写入
 * —— 真正干活的是电脑那台机器，这里只是给前端一个可以挂载的 cwd 锚点。
 *
 * ⚠️ cwd 依然**不能**是电脑路径：Android 是 posix 语义，
 * `path.posix.isAbsolute('C:/Users/...') === false`，而
 * `createOrAdopt()` 里有 `await mkdir(cwd, {recursive:true})` —— 传电脑路径
 * 会真在手机上建出一个叫 `C:\Users\...` 的怪目录。
 *
 * @param ctx - 宿主 Cordis 上下文。
 * @param desktopPath - 电脑上的绝对路径（仅用于取名字，不会成为 cwd）。
 * @returns 工作区实体（含 `.id` / `.path` / `.title`）。
 */
async function ensureDesktopWorkspace(ctx, desktopPath, workspaceMap) {
	const registry = ctx.get?.('workspaceRegistry');
	if (registry === undefined || typeof registry.create !== 'function') {
		throw new Error('workspaceRegistry 不可用，无法为电脑目录建工作区');
	}
	const slug = desktopSlug(desktopPath);
	const dir = join(STATE_DIR, 'workspaces', slug);
	mkdirSync(dir, { recursive: true });
	// 登记 `slug → 电脑路径`，供「备选区直接选镜像工作区」那条路反查（见 WORKSPACES_PATH）。
	if (workspaceMap !== undefined && workspaceMap[slug] !== desktopPath) {
		workspaceMap[slug] = desktopPath;
		saveWorkspaceMap(workspaceMap, ctx.logger);
	}
	return await registry.create(dir, desktopTitle(desktopPath));
}

/**
 * 从镜像目录反查电脑路径。
 *
 * @param workspaceMap - `slug → 电脑路径`。
 * @param dir - 会话的 cwd（应当落在 `STATE_DIR/workspaces/<slug>` 下）。
 * @returns 电脑路径；不是镜像目录时 `undefined`。
 */
function desktopPathOfMirror(workspaceMap, dir) {
	if (typeof dir !== 'string' || dir === '') return undefined;
	// ⚠️ 用 `/workspaces/` 定位而不是拿 `join(STATE_DIR,'workspaces')` 做前缀比对：
	// `workspace.path` 是 **realpath**（官方 `createOrAdopt` 会 realpath 解析），
	// 在真机上可能多出/少掉一层符号链接（`/data/user/0` vs `/data/data`），
	// 前缀一比就永远不命中 —— 症状是「修了但没生效」，极难查。
	//
	// 分隔符两种都认：真机是 posix（`join` 给 `/`），但把本插件装到 Windows 上
	// 调试时 `join` 给的是 `\`，只认一种就会在那边静默失效。
	const at = dir.search(/[\\/]workspaces[\\/]/);
	if (at < 0) return undefined;
	const slug = dir.slice(at).split(/[\\/]/)[2];
	if (slug === undefined || slug === '') return undefined;
	const path = workspaceMap[slug];
	return typeof path === 'string' && path !== '' ? path : undefined;
}

/**
 * 处理浏览器桥来的一条 RPC。
 *
 * @param ctx - Cordis 上下文。
 * @param config - 远程配置。
 * @param io - `{ remoteSessions, follow, follows, mirrorStates, persist, workspaceMap, body }`。
 */
async function handleBridge(ctx, config, io) {
	const params = io.body?.params ?? {};
	switch (io.body?.method) {
		/** 连接自检：手机 → 电脑。 */
		case 'ping':
			return callRemote(config, 'ping', {});

		/**
		 * 列电脑上的目录。
		 *
		 * ⚠️ **命中缓存就直接回，不打隧道**。两级缓存：
		 *
		 * ① 根层（`desktopCache.directory`）—— 用户点开「电脑」页签时看的正是
		 *    这一层，每一轮往返都要穿过 Cloudflare 隧道，那就是用户抱怨的
		 *    「还是有一个加载过程」（原话：「目录树都提前存档手机本地了，
		 *    为什么还是还加载呢，不能预加载吗」）。
		 * ② 子层（`desktopCache.dirs`）—— 用户报障原文「你现在好像只缓存了
		 *    顶级目录，打开某个文件夹还是要加载」。
		 *
		 * 两级都只在**拉成功之后**写回，并且**每次返回前都顺手预取「下一层」**
		 * （见 `prefetchDirs`）：用户看列表、决定点哪个，这一两秒足够把下一层
		 * 拉回来，点下去时就已经在本地了。
		 */
		case 'listDesktop': {
			const wantRoot = params.path === undefined || params.path === null || params.path === '';
			const path = wantRoot ? '' : params.path;
			const cached = wantRoot ? io.desktopCache?.directory : io.desktopCache?.dirs?.[path];
			if (cached != null) {
				// 命中也要预取下一层：用户还没离开这一屏。
				io.prefetchDirs?.(cached);
				return cached;
			}
			const listing = await callRemote(config, 'listDirectory', { path: params.path });
			if (io.desktopCache !== undefined) {
				if (wantRoot) io.desktopCache.directory = listing;
				else if (typeof path === 'string' && path !== '') io.rememberDir?.(path, listing);
			}
			io.prefetchDirs?.(listing);
			return listing;
		}

		/**
		 * 电脑上的模型目录。
		 *
		 * 用户要的是「选了电脑目录之后，输入框右下方的模型列表就刷新为电脑的」。
		 * 手机本地那份模型列表来自手机自己的 LLM 配置，跟电脑上能跑什么没关系，
		 * 拿它去发消息会选到一个电脑上不存在的模型。
		 */
		case 'modelCatalog':
			return callRemote(config, 'modelCatalog', {});

		/** 电脑上的预设列表。 */
		case 'presetList':
			return callRemote(config, 'presetList', {});

		/**
		 * 一键刷新：把「模型 / 预设 / 目录树」三样一起重新拉一遍。
		 *
		 * ⚠️ 三样**必须并发**且**互不牵连**：目录树拉不到（比如电脑上那个目录被删了）
		 * 不该让模型列表也刷不出来。所以逐个 catch，谁失败谁自己带 `error`。
		 *
		 * @returns `{ models, presets, directory }`，每项是 `{ok, value|error}`。
		 */
		case 'refreshAll': {
			const wantDirectory = typeof params.path === 'string' && params.path !== '';
			const jobs = [
				['models', () => callRemote(config, 'modelCatalog', {})],
				['presets', () => callRemote(config, 'presetList', {})],
				...(wantDirectory ? [['directory', () => callRemote(config, 'listDirectory', { path: params.path })]] : []),
			];
			const settled = await Promise.all(
				jobs.map(async ([key, run]) => {
					try {
						return [key, { ok: true, value: await run() }];
					} catch (error) {
						return [key, { ok: false, error: String(error?.message ?? error) }];
					}
				}),
			);
			const result = Object.fromEntries(settled);
			// ⚠️ 刷新到的模型/预设要**写回缓存**，否则「刷新按钮」只更新了它自己
			// 那份响应，前端模型菜单读的是宿主拦截器里的缓存 —— 用户会看到
			// 「点了刷新，模型列表没变」。
			//
			// 根目录那一层同理：刷完不回写的话，用户接下来点开「电脑」页签
			// 命中的还是**刷新前**那份预拉缓存，看起来就是「刷新没生效」。
			//
			// ⚠️ **判据是「拉回来的这一层就是缓存的那一层」**（同 path），
			// 不是「有没有带 path」：前端在根层点刷新时传的是当前显示的绝对路径
			// （`loc.path`，也就是 home），照样是带 path 的一次调用 —— 只看
			// `params.path` 空不空会把根层刷新漏掉。
			if (io.desktopCache !== undefined) {
				if (result.models?.ok === true) io.desktopCache.catalog = result.models.value;
				if (result.presets?.ok === true) io.desktopCache.presets = result.presets.value;
				// ⚠️ **刷新要把子层缓存整棵作废**：用户点刷新就是要看电脑现状，
				// 留着旧的子层，点进去看到的还是刷新前的内容（根层作废了、
				// 子层没作废，比两处都不作废更难解释）。
				io.desktopCache.dirs = {};
				if (
					result.directory?.ok === true &&
					io.desktopCache.directory?.path === result.directory.value?.path
				) {
					io.desktopCache.directory = result.directory.value;
				}
				io.desktopCache.at = Date.now();
				// 刷新完顺手把这一层的下一层预取回来，用户点进去就不用等了。
				if (result.directory?.ok === true) io.prefetchDirs?.(result.directory.value);
			}
			return result;
		}

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
				throw new Error('还没配对：请先在「电脑」页签里填配对码');
			}
			const sessionId = `session-${randomUUID()}`;
			// ⚠️ 手机端 cwd 必须是**手机本地**的绝对路径，绝不把电脑路径传进来
			// （Android 是 posix 语义，`path.posix.isAbsolute('C:/...') === false`，
			// 且官方 createOrAdopt 会 `mkdir(cwd, {recursive:true})` 真去建目录）。
			//
			// 这里给它一个**电脑目录专属的镜像工作区**（标题 = 电脑目录末段），
			// 而不是手机的「默认工作区」—— 否则所有远程会话都堆在「默认工作区」
			// 底下，看起来就像「建完又回到默认工作去了」（见 `ensureDesktopWorkspace`）。
			const workspace = await ensureDesktopWorkspace(ctx, params.path, io.workspaceMap);
			const cwd = workspace.path;
			// ⚠️ **必须走 `sessionController.create`，不能走 `ctx.sessions.create`。**
			//
			// `ctx.sessions.create()` 只把会话塞进内存 store：持久化层既没
			// `registerCreated()` 也没 `adopt()` 一个写句柄，于是日志**永远不落盘**，
			// 而前端会话列表读的是 `sessionQuery.listSessions()`（磁盘 + pending），
			// 因此列表里看不到这个会话、`openSession()` 也就跳不过去 —— 表现是
			// 「电脑端建了会话，手机界面却停在『选择一个工作区开始』」。
			//
			// 官方链路 `sessionController.create` → `agents.ensureSession` →
			// `agents.create` → `sessionPersistence.create` 才会真正建写句柄并落盘。
			//
			// ⚠️ 这**不会**让手机跑本地模型：远程会话的 `prompt` 被我们拦下来转发电脑
			// （见上面的 prompt/cancel 拦截），这里的 agent 只是个空壳。
			if (typeof ctx.sessionController?.create !== 'function') {
				throw new Error('sessionController.create 不可用，无法在手机本地建会话');
			}
			await ctx.sessionController.create({ sessionId, cwd });
			// ⚠️ 建完必须**挂到工作区**上，否则前端看不到这个会话。
			//
			// 归属判据是 `dsh-workspace` 的 `sessionIds` getter：
			// `record.sessionIds.filter(id => host.sessionPath(id) === record.path)`。
			// 而 `sessionPaths` 索引只在注册表启动时建一次（`indexHeader`），
			// 新建的会话不在索引里 ⇒ `sessionPath(id)` 是 `undefined` ≠ `record.path`
			// ⇒ 被 getter 过滤掉：侧边栏没有它、composer 判成「没选工作区」而禁用。
			//
			// `attachSession()` 会 `rememberSessionPath()` 把这条补进索引，并写进
			// `record.sessionIds` —— 正是官方「添加工作区」那条路的落点。
			// 它要求 `header.cwd` 的 realpath **等于**工作区 path，所以上面的 cwd
			// 必须取自同一个工作区实体，不能是 `process.cwd()`。
			//
			// ⚠️ 失败不致命（最坏是回到「会话在盘上、界面看不见」的旧症状），
			// 所以只记日志，不让整个创建失败。
			if (workspace !== undefined && typeof workspace.attachSession === 'function') {
				try {
					await workspace.attachSession(sessionId);
				} catch (error) {
					ctx.logger?.warn?.(
						`[dsh-remote-mobile] 会话 ${sessionId} 挂到工作区失败（界面可能看不到它）: ${String(error?.message ?? error)}`,
					);
				}
			}
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

		/**
		 * 全量远程会话清单（一次性给前端，前端拿它在**本地**做同步判定）。
		 *
		 * ⚠️ **为什么需要一个「全量」的桥**：前端要把模型菜单按「当前会话是不是
		 * 电脑镜像工作区」隔离（用户需求原文：「用户如果当前选择的是 app 本地
		 * 文件夹，那么就不要出现电脑的模型，以这个文件夹为隔离条件，互不干预」）。
		 * 而模型菜单每渲染一帧都会读一次目录快照，**不可能每帧打一次桥** ——
		 * 那是同步 API。所以前端在 `apply` 时拉一次全量，之后建会话/解绑时
		 * 自己增量维护，`getSnapshot` 就纯查内存。
		 *
		 * 重启 App 后已有会话也能正确识别，正是靠这一条：宿主从
		 * `sessions.json` 恢复出 `remoteSessions`，前端一次性拿走。
		 */
		case 'remoteSessions':
			return { ids: [...io.remoteSessions] };

		/**
		 * 配对：用电脑上生成的 6 位码换回这台电脑的隧道地址与令牌。
		 *
		 * ⚠️ **必须就地改写 `config`**（`Object.assign` 而不是重新赋值）：
		 * `config` 是 `apply()` 里 `loadConfig()` 的产物，被闭包里的 `prompt`
		 * 拦截、`follow`、以及本函数共享。如果这里只写盘不更新对象，
		 * 配对的**当下**仍然拿着旧的默认地址与令牌 —— 表现是「配对提示成功、
		 * 一发消息就 401」，而重启 App 后又好了（重启才会重新 `loadConfig`）。
		 */
		case 'pair': {
			if (typeof params.code !== 'string' || params.code.trim() === '') {
				throw new Error('请输入电脑上显示的 6 位配对码');
			}
			const saved = await claimPairing(params.code.trim());
			Object.assign(config, saved);
			// 配对成功后**静默**预拉电脑的模型与预设（用户需求原文：
			// 「用户配对成功之后 就可以静默的拉取一些数据了，比如模型列表和预设列表」）。
			// ⚠️ 不 await：这是后台动作，配对请求要立刻回给前端，别让用户等一轮往返。
			io.prefetch?.('配对成功')?.catch?.(() => {});
			return { endpoint: saved.endpoint, tokenSet: saved.token !== '' };
		}

		/** 当前连的是哪台电脑（前端用来显示状态）。 */
		case 'pairStatus':
			// 「已配对」的判据是**有令牌**。早期拿「令牌不等于内置常量」当判据，
			// 那个常量已删（它是公开凭据，见 EMPTY_TOKEN 的注释）。
			return { endpoint: config.endpoint, paired: config.token !== EMPTY_TOKEN };

		/**
		 * 解除配对：清掉本地凭据，退回未配对状态，让用户能重新填配对码。
		 *
		 * ⚠️ **这个方法是必需的回退路径，不是便利功能**。电脑端一旦「解绑」，
		 * 它会换掉令牌并停掉隧道；手机这边 `mobile.json` 里还留着旧令牌，
		 * `pairStatus` 就一直报「已配对」⇒ 前端那条「已配对」横幅永远挂着、
		 * **输入框根本不渲染**（见 client.js 里 `paired === true` 的分支）。
		 * 结果就是：电脑解绑后手机永久卡在 502，界面上没有任何重新配对的入口，
		 * 只能手工删 `mobile.json` 或清 App 数据。
		 *
		 * 清理分三部分，缺一不可：
		 * ① 凭据 —— `config` 就地清空 + 写盘，否则重启后旧令牌又回来了；
		 * ② 连接 —— 停掉所有跟随旧电脑的事件流，否则它们会拿着旧令牌
		 *    无限重连（退避到 15s 一次）一直打一个已经不属于自己的域名；
		 * ③ 会话归属 —— `remoteSessions` 里的会话原本靠「转发电脑」工作，
		 *    解绑后电脑那边可能已经不存在了，继续留着会让这些会话的 prompt
		 *    走 `callRemote` 报错。一并清掉，它们就退回普通本地会话。
		 *
		 * @returns 清空后的 `{ endpoint, paired: false }`。
		 */
		case 'unpair': {
			Object.assign(config, saveConfig(DEFAULT_ENDPOINT, EMPTY_TOKEN));
			for (const controller of io.follows.values()) {
				try {
					controller.abort();
				} catch {
					/* 已经结束了 */
				}
			}
			io.follows.clear();
			io.mirrorStates.clear();
			io.remoteSessions.clear();
			// ⚠️ **子层目录缓存必须一起清**：`catalog` / `presets` / 根层目录在下次
			// 配对成功时会被 `prefetchDesktop` **覆盖**，不会串味；而 `dirs` 是
			// **累加**的（用户每浏览一层写一格），不清的话换一台电脑配对后，
			// 旧电脑那些子目录还会命中缓存 —— 用户点进去看到的是上一台电脑的内容。
			if (io.desktopCache !== undefined) {
				io.desktopCache.dirs = {};
				io.desktopCache.directory = null;
			}
			io.persist();
			ctx.logger?.info?.('[dsh-remote-mobile] 已解除配对，本地凭据与远程会话清单已清空');
			return { endpoint: config.endpoint, paired: false };
		}

		default:
			throw new Error(`unknown method "${String(io.body?.method)}"`);
	}
}

export { callRemote, mirrorEvent, loadConfig, DEFAULT_ENDPOINT, CONFIG_PATH, PAIR_ENDPOINT, claimPairing, handleBridge };
