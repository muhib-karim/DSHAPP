/**
 * dsh-android-uiagent —— 把「操作并观察这个 App 的界面」暴露成一个**模型可调用的工具**。
 *
 * ## 为什么需要这一层
 *
 * 这个 App 的界面不是自绘的：它是官方 DSH Web GUI 跑在 WebView 里，按钮全是
 * React 渲染出来的 DOM。而**模型跑在 Node 进程里** —— 那里没有 `window`、
 * 没有 `document`，也没有任何办法「点一下那个按钮」。
 *
 * 于是「让 AI 自己测试/优化这个 App」在能力上是空的：它既看不见界面，
 * 也动不了界面。本插件补的就是这一段。
 *
 * | 谁 | 能不能操作界面 | 为什么 |
 * |---|---|---|
 * | 页面侧插件（`lib/client.js`） | ✅ | 浏览器里直接摸到 DOM |
 * | 模型（AI） | ❌ | 工具跑在 **Node 进程**里，没有 DOM |
 * | 本插件 | ✅ | 经**文件信箱**把命令送到原生，原生再打进页面 |
 *
 * ## 跨进程链路
 *
 * ```
 * 模型 ui 工具 ──写 ui-cmd.json──▶ UiProbeBridge(Kotlin)
 *                                    └─ WebHost.evaluateJavascript ─▶ __DSH_UI_PROBE__.run(cmd)
 *          ◀──读 ui-result.json──────┘        ◀─── JSON 结果 ───────────┘
 * ```
 *
 * 与 `dsh-android-bubble` 同构（同一个文件信箱模式），但**信箱是独立的**：
 * 命令可能并发发起，共用一个文件会互相覆盖请求 —— 那种失败是偶发的，最难查。
 *
 * ⚠️ 请求带自增 `id`，结果按 id 配对。没有它，「两个工具调用并发、后一个的结果
 *   被前一个读走」会让模型拿到张冠李戴的回执 —— 表现为「AI 说点过了，但界面没动」。
 *
 * ## 环境变量
 *
 * - `DSH_HOME`（必需）：文件信箱的落点。缺失或目录不可写时**不注册工具** ——
 *   注册一个必然失败的工具比不注册更糟，模型会反复重试。
 *
 * @module dsh-android-uiagent
 */

import fs from 'node:fs';
import path from 'node:path';

export const name = 'dsh-android-uiagent';

/**
 * 只要 `tools`：本插件不碰 shell，不需要执行体。
 * @type {string[]}
 */
export const inject = ['tools'];

/** 工具名。与内置工具不冲突（实测 30 个内置工具名里没有它）。 */
const TOOL_NAME = 'ui';

/** 文件信箱里两个文件名，原生侧 `UiProbeBridge` 必须与这里一致。 */
const CMD_FILE = 'ui-cmd.json';
const RESULT_FILE = 'ui-result.json';

/**
 * 等待原生回执的上限。
 *
 * ⚠️ 与 bubble 桥的 4 秒不同，这里给到 **20 秒**：`wait` 动作本身就可能等 10 秒
 *   （界面加载、动画结束），原生侧的总超时是 12 秒，这里必须比它长，
 *   否则会出现「原生还在干活，插件已经报超时」——模型会以为失败而重试，
 *   于是同一个点击做了两次。
 */
const WAIT_TIMEOUT_MS = 20000;

/** 轮询回执的间隔。 */
const POLL_INTERVAL_MS = 80;

/** 全部合法 action，与页面探针的分派表必须一致。 */
const ACTIONS = ['ping', 'info', 'dump', 'find', 'tap', 'type', 'key', 'scroll', 'text', 'html', 'wait', 'overlay', 'reload'];

/**
 * 模型可见的工具描述 —— **这是本插件最要紧的一段文本**。
 *
 * 三件事必须写死，理由与 `dsh-android-bubble` 同：
 * ① 什么场景该用（否则模型不知道有这个能力，会去猜、去瞎点）；
 * ② 字段有哪些、取值范围是多少（否则模型会猜字段名，静默无效）；
 * ③ **坐标系是什么**（这是最容易错的一点：CSS 坐标与屏幕物理坐标不是一回事，
 *    不写清楚模型会拿 CSS 坐标去 `input tap`，然后点到完全错误的位置）。
 */
const DESCRIPTION = `Inspect and operate THIS Android app's user interface — the DSH Web GUI rendered inside the app's WebView. You run in the Node process and have no DOM access, so this tool is your ONLY way to see or touch the app's buttons, inputs and panels.

## What you can do

- \`dump\`: list every interactive element (buttons, links, inputs, tabs…) with its exact on-screen position. **Start here** whenever you need to know what the UI currently looks like.
- \`tap\`: click an element. Takes a screenshot-free, precise DOM click (fires pointerdown/mousedown/pointerup/mouseup/click, like a real finger).
- \`type\`: write text into an input, done so React actually notices (native value setter + \`input\` event). Set \`enter:true\` to also press Enter.
- \`scroll\`: scroll the message list or any container; \`to:"bottom"\` jumps to the end.
- \`text\`: read the visible text of the page or a container — the fallback when you cannot recognise the UI.
- \`info\`: a one-shot summary (current path, focused element, scroll position, top buttons).
- \`wait\`: block until an element appears / disappears — use this after a tap that opens a panel.
- \`find\` / \`html\`: inspect one element in detail.
- \`reload\`: force the Web view to reload if the UI is broken/blank.

## ⚠️ Element targeting — how to name the thing you want

Give ONE of these (tried in this order):
1. \`sel\` — a CSS selector. Most precise; copy it from a previous \`dump\` result.
2. \`testid\` — the element's \`data-testid\`.
3. \`aria\` — the element's \`aria-label\` (best for icon-only buttons, which have no text).
4. \`text\` — the visible label, e.g. \`"发送"\`. Set \`exact:true\` to require a full match instead of substring.

Prefer \`sel\` from a \`dump\` you did **in the same turn** — React re-renders and element order changes, so a selector copied from long ago may hit a different element.

## ⚠️ Coordinates

Every element carries \`rect\`/\`center\` in **CSS viewport pixels** and, when the host can measure the view, \`screenRect\`/\`screenCenter\` in **physical screen pixels**. If you are going to drive a real touch (e.g. via a \`shell\`/\`adb\` tool with \`input tap\`), use \`screenCenter\`, never \`center\`. \`center\` is relative to the page viewport and will land on the wrong place.

## Practical notes

- The UI is a chat app: the composer input and its send button are the usual targets. \`dump\` with \`filter:"composer"\` finds them fast.
- After tapping something that opens a panel/menu, follow with \`wait\` (e.g. \`{action:"wait", text:"确认"}\`) before tapping inside it — the panel animates in and is not immediately clickable.
- \`dump\` only returns **visible** elements by default. Pass \`includeHidden:true\` to also see hidden ones (useful to diagnose "the button exists but I cannot click it").
- ⚠️ **A button can be visible and still unclickable.** When something covers it (a modal backdrop, a loading mask, an open drawer's scrim), a real touch lands on the cover, not on the button. Every element therefore carries \`covered\` and \`blockedBy\`; \`dump\` also reports \`overlay\` + \`blocked\` counts and a \`warning\` when a full-screen cover is present. **When \`covered\` is true, do not tap — deal with the cover first.** A common case: on a fresh profile the official frontend opens a "预览版说明" dialog whose button reads **「继续」**; until you tap it, all 16 buttons behind it are dead.
- \`{action:"overlay"}\` answers "what is covering the screen right now, and which buttons are actually live?" — it returns the cover plus the clickable elements **inside** it. Reach for it whenever \`covered\`/\`onTarget:false\` shows up.
- \`{action:"dump", clickableOnly:true}\` drops everything currently covered, so you get only what a finger could really hit.
- If \`tap\` reports \`onTarget:false\`, something is covering the element (a modal backdrop or loading mask) — read \`hitTopmost\`/\`blockedBy\` to see what actually received the click, and the returned \`hint\` says what to do next.
- Results include \`ok:false\` with an \`error\` when the element was not found or was not visible. **Always check \`ok\`** and report the failure instead of assuming the click worked.`;

/**
 * 把 `args` 里「对页面探针有意义的字段」挑出来。
 *
 * ⚠️ 白名单而不是黑名单：模型多传一个字段（比如把 `limit` 用在 `tap` 上）
 *   不该让整次调用失败，但也不该被原样送到页面里去（页面探针不认识它，
 *   只会增加 IPC 体积）。
 *
 * @param {object} args - 工具入参。
 * @returns {object} 页面探针能理解的命令体（不含 action/id）。
 */
function pickTarget(args) {
	const out = {};
	const passthrough = [
		'sel', 'testid', 'aria', 'text', 'exact',
		'filter', 'limit', 'includeHidden', 'clickableOnly',
		'dy', 'dx', 'to', 'container',
		'value', 'clear', 'enter', 'key',
		'ctrl', 'shift', 'alt', 'meta',
		'visible', 'clickable',
	];
	for (const key of passthrough) {
		if (args[key] !== undefined) out[key] = args[key];
	}
	return out;
}

/**
 * 校验一次调用，返回错误文本（合法时返回空串）。
 *
 * ⚠️ **校验必须发生在写信箱之前**：原生轮询线程是单线程的，一条注定失败的
 *   命令也会占住它一轮（`wait` 最长 12 秒）。
 *
 * @param {object} args - 工具入参。
 * @returns {string} 错误信息或空串。
 */
function validate(args) {
	const action = args.action;
	if (typeof action !== 'string' || !ACTIONS.includes(action)) {
		return `action 必须是以下之一: ${ACTIONS.join(' / ')}`;
	}
	const needsTarget = ['tap', 'find', 'type', 'wait'];
	if (needsTarget.includes(action)) {
		const has = ['sel', 'testid', 'aria', 'text'].some(
			(k) => typeof args[k] === 'string' && args[k].trim() !== '',
		);
		// `wait` 允许只带 visible:false 之外的东西；但至少要有一个定位条件。
		if (!has) {
			return `action:"${action}" 需要一个定位条件（sel / testid / aria / text 至少给一个）`;
		}
	}
	if (action === 'type' && args.value === undefined) {
		return 'action:"type" 需要 value（要给输入框写的内容；清空请传 clear:true 配合 value:""）';
	}
	if (action === 'key' && typeof args.key !== 'string') {
		return 'action:"key" 需要 key（如 "Enter"、"Escape"）';
	}
	if (action === 'scroll' && args.to === undefined && args.dy === undefined && args.dx === undefined) {
		return 'action:"scroll" 需要 to（"bottom"/"top"）或 dy/dx';
	}
	return '';
}

/**
 * 发一条命令给原生，等回执。
 *
 * ⚠️ **本函数必须串行执行**，调用方走 [enqueue] 排队 —— 理由见那里的注释。
 *
 * @param {string} home - `$DSH_HOME`。
 * @param {number} id - 请求编号。
 * @param {object} payload - `{action, ...}`。
 * @returns {Promise<object>} 原生回执（`{ok, error?, element?, ...}`）。
 */
async function sendCommand(home, id, payload) {
	const cmdPath = path.join(home, CMD_FILE);
	const resPath = path.join(home, RESULT_FILE);

	try {
		// 先把旧回执清掉：否则上一轮的 `ok` 会被当成本轮的答案。
		// 用 unlink 而不是写空对象 —— 写空对象会让「解析失败」与「还没处理」
		// 两种状态混在一起，只能靠 id 分辨，多一处出错的机会。
		try { fs.unlinkSync(resPath); } catch { /* 不存在就是已经干净了 */ }
		writeJsonAtomic(cmdPath, { id, ...payload });
	} catch (error) {
		return { ok: false, error: `写入文件信箱失败: ${error?.message ?? error}` };
	}

	const deadline = Date.now() + WAIT_TIMEOUT_MS;
	while (Date.now() < deadline) {
		await sleep(POLL_INTERVAL_MS);
		const reply = readJson(resPath);
		if (reply === null || reply.id !== id) continue;
		return reply;
	}
	// 超时不代表失败：原生侧可能只是慢（引擎刚启动、或主线程正忙）。
	// 但绝不能返回 ok —— 那会让模型以为操作成功了。
	return {
		ok: false,
		error: `等待原生回执超时（${WAIT_TIMEOUT_MS} ms）。界面可能已经变化，也可能没有；`
			+ '请用 action:"info" 复查当前状态，确认后再向用户汇报。',
	};
}

/** 原子写：先临时文件再 rename（原生侧可能正好读到这里）。 */
function writeJsonAtomic(file, value) {
	const tmp = `${file}.${process.pid}.tmp`;
	fs.writeFileSync(tmp, JSON.stringify(value), 'utf8');
	try {
		fs.renameSync(tmp, file);
	} catch (error) {
		try { fs.unlinkSync(tmp); } catch { /* 忽略 */ }
		throw error;
	}
}

/** 读一个 JSON 文件；不存在/坏了都返回 null（都不是错误）。 */
function readJson(file) {
	try {
		return JSON.parse(fs.readFileSync(file, 'utf8'));
	} catch {
		return null;
	}
}

function sleep(ms) {
	return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * 串行闸门：把并发调用排成一队。
 *
 * ⚠️ **这不是可选优化，是正确性要求。**
 *
 * 原生侧只有**一个**轮询线程，且只认**一个**命令文件（`ui-cmd.json`）+ **一个**
 * 结果文件。两条命令同时写进去，后一条会**覆盖**前一条 —— 于是：
 *
 * - 前一条命令**从未被执行**（模型以为点了，界面没动）；
 * - 而它的 `id` 再也等不到回执，`sendCommand` 会一路超时到 20 秒；
 * - 更糟的是后一条的结果可能被前一条按 `id` 判为「不是给我的」而丢弃。
 *
 * 症状是「偶发、看起来随机」的失败 —— 这类 bug 最难查，所以在这里用最直白的
 * 办法根除：**同一时刻只允许一条命令在信箱里**。
 *
 * 模型确实会并发调用工具（同一轮里并行发起多个调用），所以这个闸门是必需的，
 * 不是防御性的。
 */
let gate = Promise.resolve();

/**
 * 排队执行一次「发命令 + 等回执」。
 * @param {() => Promise<object>} task - 要串行执行的任务。
 * @returns {Promise<object>} 任务结果。
 */
function enqueue(task) {
	// 用 .then(task, task) 而不是 .then(task)：前一个任务失败（抛异常）时闸门
	// 也必须放行，否则一次失败会**永久卡死**后续所有调用。
	const next = gate.then(task, task);
	// 闸门本身永远不 reject —— 错误由 task 的返回值（而非异常）承载。
	gate = next.then(() => undefined, () => undefined);
	return next;
}

/**
 * 把回执渲染成模型读得懂的**文本**。
 *
 * ⚠️ 这里刻意不用「把 JSON 原样丢回去」的做法。工具结果是要**被模型读**的，
 *   而 `dump` 的原始 JSON 动辄几百行；渲染成紧凑的一行一个元素，模型才能在
 *   有限的上下文里真正看懂界面。同时原始数据仍以 `details` 形式附回。
 *
 * @param {object} args - 原始调用。
 * @param {object} reply - 原生回执。
 * @returns {string} 给模型看的文本。
 */
function render(args, reply) {
	const lines = [];
	const action = args.action;

	if (reply.ok !== true) {
		lines.push(`✗ 操作失败：${reply.error ?? '未知原因'}`);
		if (reply.element) lines.push(`  找到的元素: ${fmtElement(reply.element)}`);
		if (reply.spec) lines.push(`  定位条件: ${JSON.stringify(reply.spec)}`);
		return lines.join('\n');
	}

	// ── ping / info：一句话讲清"现在是什么界面" ──────────────────────
	if (action === 'ping') {
		lines.push(`✓ 探针就绪 · ${reply.url ?? ''}`);
		lines.push(`  可交互元素 ${reply.interactive ?? '?'} 个 · 视口 ${fmtViewport(reply.viewport)}`);
		return lines.join('\n');
	}

	if (action === 'info') {
		lines.push(`✓ 当前界面: ${reply.path ?? ''}${reply.hash ? ` ${reply.hash}` : ''}`);
		lines.push(`  视口 ${fmtViewport(reply.viewport)}`);
		if (reply.focused?.text) {
			lines.push(`  焦点元素: ${reply.focused.tag}「${reply.focused.text}」${fmtScreen(reply.focused)}`);
		}
		if (reply.scrollable) {
			lines.push(`  滚动容器: ${reply.scrollable.sel}（${reply.scrollable.top}/${reply.scrollable.height}，可视 ${reply.scrollable.view}）`);
		}
		if (Array.isArray(reply.top) && reply.top.length > 0) {
			lines.push(`  主要按钮:`);
			for (const el of reply.top) lines.push(`    ${fmtElement(el)}`);
		}
		return lines.join('\n');
	}

	// ── dump：界面元素清单 ──────────────────────────────────────────
	if (action === 'dump') {
		const list = reply.elements ?? [];
		lines.push(`✓ 找到 ${reply.matched ?? list.length} 个可交互元素，返回前 ${list.length} 个（视口 ${fmtViewport(reply.viewport)}）`);
		// ⚠️ 遮挡警告必须排在元素清单**之前**：模型最容易犯的错是扫一眼清单就
		//   开始挨个 tap。清单本身看着完全正常（元素都有尺寸），只有这一行能
		//   拦住它。
		if (reply.overlay) {
			lines.push(`  ⚠️ 有遮挡层盖住界面（${reply.blocked ?? 0} 个元素当前点不动）:`);
			lines.push(`    ${fmtElement(reply.overlay)}`);
			lines.push(`    ${reply.warning ?? ''}`);
			// ⚠️ 别写成「看遮挡层里面有哪些按钮」：官方前端的弹窗是遮罩与内容
			//   **平级**的（`_root_ > [_mask_, _dialog_]`），`overlay` 报的因此是
			//   「此刻真正能点的」而不是「里面的」—— 照那句理解会找不到按钮。
			lines.push(`    先关掉它（用 overlay 动作看此刻真正能点的是哪几个），再操作下面的元素。`);
		} else if ((reply.blocked ?? 0) > 0) {
			lines.push(`  ⚠️ 其中 ${reply.blocked} 个被上层元素盖住，现在点不动（见元素行上的「被遮挡←」。）。`);
		}
		if (list.length === 0) {
			lines.push('  （没有匹配的元素。界面可能还在加载，或 filter 太窄。）');
		}
		for (const el of list) lines.push(`  ${fmtElement(el)}`);
		lines.push('');
		lines.push('用返回的 sel 或 text 做 tap/type 的定位条件。');
		return lines.join('\n');
	}

	// ── overlay：现在谁盖着屏幕、此刻真正能点什么 ────────────────────
	if (action === 'overlay') {
		const live = reply.clickable ?? [];
		if (!reply.present) {
			lines.push(`✓ 当前没有遮挡层，界面可以正常操作。`);
			if (live.length > 0) {
				lines.push(`  现在能点的是这 ${live.length} 个:`);
				for (const el of live) lines.push(`    ${fmtElement(el)}`);
			}
			return lines.join('\n');
		}
		lines.push('✓ 当前有遮挡层:');
		lines.push(`  ${fmtElement(reply.layer)}`);
		// ⚠️ 报「此刻真正能点的」而不是「遮挡层里面的」：官方前端的弹窗是
		//   遮罩与内容**平级**（`_root_ > [_mask_, _dialog_]`），按后代找会得到
		//   0 个，而屏幕上明明有个「继续」能点。
		if (live.length === 0) {
			lines.push('  当前没有任何可点元素（可能是纯加载蒙层，等它自己消失）。');
		} else {
			lines.push(`  此刻只有这 ${live.length} 个元素是真能点的 —— 先处理它们:`);
			for (const el of live) lines.push(`    ${fmtElement(el)}`);
		}
		return lines.join('\n');
	}

	// ── 单个元素类动作 ──────────────────────────────────────────────
	if (action === 'find') {
		lines.push(`✓ 找到元素:`);
		lines.push(`  ${fmtElement(reply.element)}`);
		if (reply.element?.html) lines.push(`  HTML: ${reply.element.html}`);
		return lines.join('\n');
	}

	if (action === 'tap') {
		const el = reply.element;
		lines.push(`✓ 已点击 ${el ? `「${el.text || el.sel}」` : ''}`);
		if (el) lines.push(`  ${fmtElement(el)}`);
		if (reply.onTarget === false) {
			lines.push(`  ⚠️ 点击被上层元素接收（元素被遮挡，真机手指会点到那层而不是它）:`);
			if (reply.hitTopmost) lines.push(`    ${fmtElement(reply.hitTopmost)}`);
			if (reply.hint) lines.push(`    ${reply.hint}`);
		}
		lines.push(`  事件: ${(reply.dispatched ?? []).join(', ')}`);
		lines.push(`  提示: 界面变化是异步的 —— 若这一步应打开面板，接着用 wait 等它出现。`);
		return lines.join('\n');
	}

	if (action === 'type') {
		lines.push(`✓ 已写入输入框（方式: ${reply.how}）`);
		if (reply.element) lines.push(`  ${fmtElement(reply.element)}`);
		lines.push(`  当时读到的值: ${JSON.stringify(reply.value ?? '')}`);
		// ⚠️ `applied` 是三态，别把它当布尔用：
		//   true  = 同步已确认写入；
		//   null  = 写入已发出但**异步生效**（contenteditable/Lexical 就是这样，
		//           同步读必然为空），**不是失败** —— 下一步复核即可；
		//   false = 真的没写进去。
		if (reply.applied === false) {
			lines.push(`  ⚠️ 内容没有真正写进去（期望 ${JSON.stringify(args.value ?? '')}）。`);
			lines.push(`    若目标是 contenteditable，先 tap 点一下它再 type；或改用 key 动作逐字输入。`);
		} else if (reply.applied === null) {
			lines.push(`  ℹ️ 写入是异步生效的（富文本编辑器），此刻还读不到内容属正常。`);
			lines.push(`    下一步用 find/text 复核它，或直接检查「发送」按钮是否已从禁用变可用。`);
		}
		if (reply.submitted) lines.push(`  已按下 Enter（事件: ${reply.submitted.join(', ')}）`);
		return lines.join('\n');
	}

	if (action === 'key') {
		lines.push(`✓ 已按键 ${args.key ?? ''}（事件: ${(reply.dispatched ?? []).join(', ')}）`);
		if (reply.target) lines.push(`  目标: ${fmtElement(reply.target)}`);
		return lines.join('\n');
	}

	if (action === 'scroll') {
		if (reply.moved === false) {
			lines.push(`⚠️ 滚动没有产生位移（容器: ${reply.container}，位置仍是 ${reply.after}）`);
			lines.push(`  可能已经到头了，或这个容器不可滚动。`);
		} else {
			lines.push(`✓ 已滚动（容器: ${reply.container}）${reply.before} → ${reply.after}`);
		}
		return lines.join('\n');
	}

	if (action === 'text') {
		lines.push(`✓ 可见文本 ${reply.total ?? 0} 行${reply.truncated ? `（只返回前 ${reply.lines?.length ?? 0} 行）` : ''}:`);
		for (const line of reply.lines ?? []) lines.push(`  ${line}`);
		return lines.join('\n');
	}

	if (action === 'html') {
		lines.push(`✓ HTML（${reply.length} 字符${reply.length > (reply.html?.length ?? 0) ? '，已截断' : ''}）:`);
		lines.push(reply.html ?? '');
		return lines.join('\n');
	}

	if (action === 'wait') {
		if (reply.satisfied === true) {
			lines.push(`✓ 条件已成立（第 ${reply.attempts} 次检查）`);
			if (reply.element) lines.push(`  ${fmtElement(reply.element)}`);
		} else {
			lines.push(`✗ 等待超时，条件始终没有成立（检查了 ${reply.attempts} 次）`);
			lines.push(`  元素可能没出现、或一直不可见。用 action:"dump" 看当前界面到底有什么。`);
		}
		return lines.join('\n');
	}

	if (action === 'reload') {
		lines.push(`✓ ${reply.note ?? '已触发重载'}`);
		return lines.join('\n');
	}

	lines.push(`✓ ${action} 完成`);
	return lines.join('\n');
}

/** 视口一行摘要。 */
function fmtViewport(vp) {
	if (!vp) return '未知';
	return `${vp.w}×${vp.h} CSS（dpr ${vp.dpr}，滚动 ${vp.scrollX},${vp.scrollY}，文档 ${vp.docW}×${vp.docH}）`;
}

/** 屏幕坐标摘要（拿不到时给空串）。 */
function fmtScreen(el) {
	if (!el?.screenCenter) return '';
	return ` · 屏幕(${el.screenCenter.x},${el.screenCenter.y})`;
}

/**
 * 一个元素渲染成一行。
 *
 * 形状：`[role] "文本" · sel · CSS中心(x,y) · 屏幕中心(x,y) [状态]`
 *
 * ⚠️ 屏幕坐标**有就一定要显示**：模型要用 `input tap` 真机点击时，唯一能用的
 *   就是它，而 CSS 坐标看起来"也对"，用错了极难发现。
 */
function fmtElement(el) {
	if (!el) return '(无)';
	const parts = [];
	parts.push(`[${el.role || el.tag}]`);
	if (el.text) parts.push(`"${el.text}"`);
	else if (el.testid) parts.push(`testid=${el.testid}`);
	if (el.sel) parts.push(`sel=${el.sel}`);
	const c = el.center;
	if (c) parts.push(`CSS(${c.x},${c.y})`);
	if (el.screenCenter) parts.push(`屏幕(${el.screenCenter.x},${el.screenCenter.y})`);
	const r = el.rect;
	if (r) parts.push(`${r.w}×${r.h}`);
	const flags = [];
	if (el.enabled === false) flags.push('已禁用');
	if (el.checked !== null && el.checked !== undefined) flags.push(el.checked ? '已选中' : '未选中');
	if (el.inViewport === false) flags.push('不在视口内');
	// ⚠️ 「被盖住」必须直接写在元素这一行上。只在 dump 顶部报一次是不够的：
	//   模型是逐行扫这个清单的，遮挡信息跟元素分离时它会对被挡的那几条照点不误。
	if (el.covered) {
		const by = el.blockedBy;
		flags.push(by ? `被遮挡←${by.cls || by.tag || by.sel}` : '被遮挡');
	}
	if (flags.length > 0) parts.push(`(${flags.join('，')})`);
	return parts.join(' · ');
}

/**
 * 工具定义。
 *
 * ⚠️ **手工构造而不是 `defineTool({...})`**：本包解在 `$DSH_HOME/uiagent-tool/` 下，
 *   那里没有 `node_modules`，ESM 解析够不到引擎的 `@deepseek-ai/dsh-tools`。
 *   理由与做法与 `dsh-android-shell` / `dsh-android-bubble` 完全一致。
 *
 * @param {string} home - `$DSH_HOME`。
 * @returns {object} 工具定义。
 */
function uiTool(home) {
	/** 请求编号。进程内自增即可 —— 只有本插件会写这个信箱。 */
	let seq = 0;

	return {
		name: TOOL_NAME,
		description: DESCRIPTION,
		parameters: {
			type: 'object',
			additionalProperties: false,
			required: ['action'],
			properties: {
				action: {
					type: 'string',
					enum: ACTIONS,
					description: 'What to do. "dump" lists interactive elements with positions; "tap" clicks one; "type" fills an input; "scroll" scrolls; "text" reads visible text; "info" summarises the current screen; "wait" blocks until an element appears; "ping" checks the probe is alive; "find"/"html" inspect one element; "overlay" reports what is currently covering the screen and which buttons inside it are live; "reload" reloads a broken Web view.',
				},
				sel: { type: 'string', description: 'CSS selector for the target element. Most precise — copy it from a previous dump.' },
				testid: { type: 'string', description: 'Target by data-testid.' },
				aria: { type: 'string', description: 'Target by aria-label. Best for icon-only buttons that have no text.' },
				text: { type: 'string', description: 'Target by visible label, e.g. "发送". A LOCATOR only (used by tap/type/wait/find) — to supply text to write into an input use `value` instead.' },
				exact: { type: 'boolean', description: 'With text: require a full match rather than a substring match.' },
				filter: { type: 'string', description: 'For action:"dump" — only return elements whose text/testid/class/selector contains this substring. Great for narrowing to e.g. "composer".' },
				limit: { type: 'number', description: 'For dump/text — max results (dump default 60, max 300).' },
				includeHidden: { type: 'boolean', description: 'For dump — also list hidden elements. Useful to diagnose "the button exists but cannot be clicked".' },
				clickableOnly: { type: 'boolean', description: 'For dump — drop every element that is currently covered by something else, leaving only what a real finger could hit right now.' },
				value: { type: 'string', description: 'The text to write into the input, for action:"type". (`value`, not `text` — `text` is the element locator.)' },
				clear: { type: 'boolean', description: 'For action:"type" — empty the input before writing.' },
				enter: { type: 'boolean', description: 'For action:"type" — also press Enter after writing.' },
				key: { type: 'string', description: 'For action:"key" — the key to press, e.g. "Enter", "Escape", "Tab".' },
				ctrl: { type: 'boolean', description: 'Hold Ctrl for action:"key".' },
				shift: { type: 'boolean', description: 'Hold Shift for action:"key".' },
				alt: { type: 'boolean', description: 'Hold Alt for action:"key".' },
				meta: { type: 'boolean', description: 'Hold Meta/Cmd for action:"key".' },
				to: { type: 'string', enum: ['top', 'bottom'], description: 'For action:"scroll" — jump to the top or bottom.' },
				dy: { type: 'number', description: 'For action:"scroll" — vertical pixels to scroll (negative scrolls up).' },
				dx: { type: 'number', description: 'For action:"scroll" — horizontal pixels to scroll.' },
				container: { type: 'string', description: 'For action:"scroll" — CSS selector of the scroll container. Omit to auto-detect the main scrollable area.' },
				visible: { type: 'boolean', description: 'For action:"wait" — require the element to be visible (default true). Pass false to wait for it to disappear.' },
				clickable: { type: 'boolean', description: 'For action:"wait" — also require the element NOT to be covered by anything, i.e. wait until it is genuinely tappable.' },
			},
		},
		async execute(args) {
			const bad = validate(args ?? {});
			if (bad !== '') return { ok: false, message: bad };

			// ⚠️ **字段原样透传，一个字都不翻译。**
			//
			// 第一版在这里做了 `text` ↔ `value` 的翻译，结果制造了一个极隐蔽的
			// bug：`tap{text:"发送"}` 的定位条件被当成"输入内容"处理、
			// 转手就把 `text` 删了 —— 于是所有按文本定位的 tap/find/wait 全部
			// 报「找不到元素」，而报错文案完全指不到真因。
			//
			// 页面探针对两个字段的语义已经分得很清楚：`text` = 按可见标签**定位**，
			// `value` = 要写进输入框的**内容**。两边字段名一致，就不需要中间这层
			// 翻译，也就没有再搞错的机会。
			// ⚠️ 走串行闸门：原生只有一个轮询线程 + 一个命令文件，并发写会互相覆盖。
			//   详见 [enqueue] 的注释。
			const reply = await enqueue(() => sendCommand(home, ++seq, { ...args }));
			const text = render(args, reply);
			return {
				ok: reply.ok === true,
				message: text,
				// 原始回执附回去：模型需要精确值（比如 screenCenter）时可以读
				details: reply,
			};
		},
	};
}

/**
 * 注册 `ui` 工具；前置条件不满足时**不注册**。
 * @param {object} ctx - Cordis 上下文（需 `tools` 服务）。
 */
export function apply(ctx) {
	const home = process.env.DSH_HOME;
	if (typeof home !== 'string' || home === '') {
		ctx?.logger?.warn?.('[dsh-android-uiagent] 未配置 DSH_HOME，ui 工具未注册');
		return;
	}
	if (!fs.existsSync(home)) {
		ctx?.logger?.warn?.(`[dsh-android-uiagent] DSH_HOME 不存在（${home}），ui 工具未注册`);
		return;
	}

	ctx.tools.register(uiTool(home));
	ctx?.logger?.info?.(`[dsh-android-uiagent] 已注册 UI 调试工具 ${TOOL_NAME}: ${path.join(home, CMD_FILE)}`);
}
