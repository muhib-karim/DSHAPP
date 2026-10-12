/**
 * dsh-android-bubble —— 把「改悬浮窗」暴露成一个**模型可调用的工具**。
 *
 * ## 为什么需要这一层
 *
 * 悬浮球的原生侧早已有完整的外观接口（`FloatingBubble.applyAppearance()` /
 * `currentAppearanceJson()`），页面侧插件也能经 `window.DSHAndroid.bubbleApply()`
 * 调它。但那条路对**模型**是关着的：
 *
 * | 谁 | 能不能改悬浮窗 | 为什么 |
 * |---|---|---|
 * | 页面侧插件（`lib/client.js`） | ✅ | 浏览器里直接摸到 `window.DSHAndroid` |
 * | 模型（AI） | ❌ | 它的工具跑在 **Node 进程**里，那里没有 `window` |
 *
 * 于是「让 AI 改悬浮窗，像改自己的 web 界面一样简单」这句话在**可发现性**上是假的：
 * 模型既不知道有这个能力，也没有任何途径触达它。本插件补的就是这一段 ——
 * 注册一个 `bubble` 工具，让它出现在工具列表里、且真的能改到原生那个球。
 *
 * ## 跨进程怎么打通
 *
 * 引擎 node 是 App 进程的**子进程**，两者共享 app 私有目录（SELinux 同域），
 * 所以走**文件信箱**：本插件把命令写进 `$DSH_HOME/bubble-cmd.json`，原生侧
 * `BubbleCommandBridge` 轮询读走、执行、把结果写回 `$DSH_HOME/bubble-result.json`。
 *
 * 这与项目里 `adb-wireless-port` 那条通道同构（Kotlin 写、Node 读），只是方向反过来。
 * **不选 socket**：Android 的 app 域不让进程开命名管道（见 AGENTS.md 的沙箱说明），
 * 而 AF_UNIX 抽象命名空间是可选实现；文件信箱在两个方向上都已经在本项目里验过。
 *
 * ⚠️ 请求带自增 `id`，结果按 id 配对 —— 否则「两个工具调用并发、后一个的结果被
 *   前一个读走」会让模型拿到张冠李戴的回执。
 *
 * ## 环境变量
 *
 * - `DSH_HOME`（必需）：文件信箱的落点。缺失或目录不可写时**不注册工具** ——
 *   注册一个必然失败的工具比不注册更糟，模型会反复重试。
 *
 * @module dsh-android-bubble
 */

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

export const name = 'dsh-android-bubble';

/**
 * 只要 `tools`：本插件不碰 shell，不需要执行体。
 * @type {string[]}
 */
export const inject = ['tools'];

/** 工具名。与内置工具不冲突（实测 30 个内置工具名里没有它）。 */
const TOOL_NAME = 'bubble';

/** 文件信箱里三个文件名，原生侧 `BubbleCommandBridge` 必须与这里一致。 */
const CMD_FILE = 'bubble-cmd.json';
const RESULT_FILE = 'bubble-result.json';

/** 等待原生侧回执的上限。原生轮询间隔 250ms，留足余量即可。 */
const WAIT_TIMEOUT_MS = 4000;

/** 轮询回执的间隔。 */
const POLL_INTERVAL_MS = 60;

/**
 * 轮播帧数上限。与原生侧 `BubbleFrames.MAX_FRAMES` 必须一致。
 *
 * 超了不是"少几帧"而是静默丢尾巴，用户会觉得轮播"播到一半就断了"，
 * 所以这里先截断并在结果里说明。
 */
const MAX_FRAMES = 64;

/**
 * 一批轮播帧的 base64 总字节上限。
 *
 * ⚠️ **这条限制是防内存爆的，不是防磁盘的。** 帧以 data URI 形式进 JSON，
 *   原生侧 `BubbleCommandBridge` 要 `readText()` 整个文件再 `JSONObject(...)`；
 *   64 帧 × 每帧 700 KB ≈ 45 MB 的字符串在 Android 上足以让 App 被 LMK 杀掉，
 *   而症状是"悬浮球突然消失、什么都没发生"—— 极难归因。
 *
 * 6 MB 能装下 64 张 ~90 KB 的 PNG（对 100dp 的球绰绰有余）。
 * 超了就在插件侧明确报错，让模型自己缩小图片或减少帧数。
 */
const MAX_FRAMES_BASE64 = 6 * 1024 * 1024;

/**
 * 模型可见的工具描述 —— **这是本插件最要紧的一段文本**。
 *
 * 三件事必须写死，理由与 `dsh-android-shell` 同：
 * ① 什么场景该用（否则模型不知道有这个能力）；
 * ② 字段有哪些、取值范围是多少（否则模型会猜字段名，静默无效）；
 * ③ 改了之后**用户能不能立刻看到**（前台/后台的差别是反直觉的，不写清楚
 *    模型会以为「改了没生效」而反复重试）。
 */
const DESCRIPTION = `Change the Android floating bubble (悬浮球) of this app — its state, size, shape, colours, panel, avatar and animation. This is the only way for you to affect that floating window; a page-side plugin can call \`window.DSHAndroid.bubbleApply()\`, but you run in the Node process and cannot.

Use it when the user asks to change / restyle / resize / hide the floating ball, or asks you to "change your floating window". Also use \`action:"get"\` first when the user wants a modification relative to the current look, so you do not silently reset their settings.

## The bubble is layered — think in layers, not in presets

    shape layer   : the background outline + its fill + border
    content layer : one image on top (avatar / carousel frame / sprite cell)
    motion layer  : rotation / breathing

Each layer has its own opacity and they multiply, so almost any visual request maps onto
a combination. \`opacity\` is the global multiplier on top of both.

## Actions

- \`action:"set"\` — apply the fields below (default).
- \`action:"get"\` — read the current appearance back as JSON.
- \`action:"notify"\` — push a message into the status strip above the ball (\`text\`, \`durationMs\`, \`sticky\`). This does NOT change any persisted setting; it is you *saying something* to the user. Use it to report progress, or to answer when the user only wants a short notice.

## Fields (all optional; merged onto the current appearance — pass only what you change)

Shape layer:
- \`state\`: "expanded" | "collapsed" | "hidden" | "auto". "auto" (default) shows the ball only while the app is in the background; "expanded"/"collapsed" pin it visible in the foreground too. "hidden" hides it entirely.
- \`shape\`: "circle" | "square" | "rounded" | "path"
- \`shapePath\`: an SVG path \`d\` string — used when \`shape\` is "path", and this is how you give the ball ANY outline (star, heart, hexagon, speech bubble, logo). Coordinates are in \`shapeViewBox\` units, not dp, so the same path scales to any \`size\`. Example five-pointed star, viewBox "0 0 100 100": \`M50 3 L61 38 L98 38 L68 60 L79 95 L50 73 L21 95 L32 60 L2 38 L39 38 Z\`. Max 4000 characters. Invalid paths fall back to a circle.
- \`shapeViewBox\`: "minX minY width height", default "0 0 100 100".
- \`size\`: ball diameter in dp, 24–160
- \`cornerRadius\`: dp, 0–80 (only meaningful for "rounded")
- \`bg\` / \`bg2\`: "#RRGGBB". Together they make a vertical gradient; \`bg\` alone is a solid colour. Also accepts "#AARRGGBB" if you want per-colour alpha.
- \`border\` ("#RRGGBB") and \`borderWidth\` (dp, 0–8)
- \`bgOpacity\`: 0–1, applies to the shape layer ONLY. \`0\` = fully transparent background, i.e. only the image is visible — this is the "transparent background, opaque image" request.
- \`opacity\`: 0.05–1, global multiplier over everything.

Content layer:
- \`avatar\`: "default" (built-in portrait) | "none" (no image at all — background shape only) | a \`data:image/...;base64,...\` URI
- \`avatarFile\`: an absolute path to an image readable from this process; the file is read and sent as the avatar. Takes precedence over \`avatar\`. Prefer this over hand-building a data URI.
- \`avatarOpacity\`: 0–1, applies to the content layer ONLY. Combine with \`bgOpacity\` to fade the picture while keeping the background solid, or the reverse.
- \`avatarScale\`: 0.2–2.0. Values above 1 overflow the ball and get clipped by the shape — that is how you crop/zoom a picture.
- \`frameFiles\`: an array of absolute image paths — **an animated carousel**. Each file is one frame, played in order and looping. Up to 64 frames, each up to ~500 KB. Pass \`[]\` to clear the carousel and go back to a single image.
- \`frameDurations\`: optional array of per-frame durations in ms (same order as \`frameFiles\`), 20–60000. Omit to use \`frameIntervalMs\` for every frame.
- \`frameIntervalMs\`: uniform frame duration in ms, 20–60000 (default 400).
- \`frameLoop\`: how many full cycles to play; -1 (default) = forever, 0 = forever too, N>0 = stop on the last frame after N cycles.
- \`spriteRows\` / \`spriteCols\`: **sprite sheet mode**. Pass ONE file in \`frameFiles\` that is a grid of \`rows × cols\` equally-sized cells; the plugin treats each cell as a frame, in row-major order. This is how you animate a character with a single image. Setting these with no new \`frameFiles\` re-slices the sheet that is already stored.

Motion layer:
- \`spin\`: degrees per second, -720–720 (negative = counter-clockwise), 0 = no rotation. Applied to the content layer, so the outline stays put.
- \`pulse\`: boolean, a breathing scale animation on the content layer.

Panel and status:
- \`panelWidth\` (dp) and \`panelHeight\` (dp, 0 = fit content)
- \`panelBg\` / \`panelText\` / \`panelAccent\`: "#RRGGBB" for the expanded panel
- \`statusAutoHideMs\`: how long the status strip stays after its content stops changing; 0 disables auto-hide
- \`edgeSnap\`: boolean — whether dragging the ball off-screen collapses it into an edge sliver

## Recipes for common asks

- "transparent background, keep the picture" → \`{bgOpacity: 0}\`
- "remove the picture" → \`{avatar: "none"}\`
- "make it a star / heart / hexagon" → \`{shape: "path", shapePath: "..."}\`
- "make it spin" → \`{spin: 90}\`; "make it breathe" → \`{pulse: true}\`
- "animate these images" → \`{frameFiles: ["/abs/a.png", "/abs/b.png"], frameIntervalMs: 200}\`
- "animate this sprite sheet" → \`{frameFiles: ["/abs/sheet.png"], spriteRows: 4, spriteCols: 4, frameIntervalMs: 120}\`
- "make it bigger and see-through" → \`{size: 96, opacity: 0.6}\`
- "tell me when you're done" → \`{action: "notify", text: "搞定", durationMs: 4000}\`

Invalid values fall back to the current value instead of failing, and unknown fields are ignored, so a typo does not make the ball disappear. The result tells you the appearance as it is *after* the change; check it if you need to be sure.

⚠️ With the default \`state:"auto"\` the ball is only visible while this app is in the background — if the user is looking at the app right now and reports "I see nothing", that is expected, not a failure. Setting \`state:"collapsed"\` or \`"expanded"\` pins it visible in the foreground, which is what you usually want when the user asked to see a change.

⚠️ Image paths are read by THIS process, so they must be readable from your working directory. The user's photos are usually under \`/sdcard/...\`. If you only have a picture the user attached, find its path in the workspace first.`;

/** 把一条参数对象压成要下发的字段集合（丢 undefined / null / 空串）。 */
function pickFields(args) {
	const fields = {};
	const keys = [
		'state', 'shape', 'shapePath', 'shapeViewBox', 'size', 'cornerRadius', 'opacity',
		'bgOpacity', 'avatarOpacity', 'avatarScale',
		'bg', 'bg2', 'border', 'borderWidth', 'avatar',
		'frameIntervalMs', 'frameDurations', 'frameLoop', 'spriteRows', 'spriteCols',
		'spin', 'pulse',
		'panelWidth', 'panelHeight', 'panelBg', 'panelText', 'panelAccent',
		'statusAutoHideMs', 'edgeSnap',
	];
	for (const key of keys) {
		const value = args[key];
		if (value === undefined || value === null) continue;
		if (typeof value === 'string' && value === '') continue;
		fields[key] = value;
	}
	return fields;
}

/**
 * 把本地图片读成 data URI。
 *
 * ⚠️ **在插件这一侧读文件**，而不是把路径交给原生：原生拿不到工作区路径
 *   （它按自己的沙箱跑），而本插件与 `read` 工具在同一个进程、同一套权限里。
 *
 * @param {string} file - 绝对路径。
 * @returns {{ok: true, uri: string} | {ok: false, error: string}} 结果。
 */
function readImageFile(file) {
	try {
		const info = fs.statSync(file);
		if (!info.isFile()) return { ok: false, error: `不是普通文件: ${file}` };
		const ext = path.extname(file).toLowerCase();
		const mime = ext === '.png' ? 'image/png'
			: (ext === '.jpg' || ext === '.jpeg') ? 'image/jpeg'
				: ext === '.webp' ? 'image/webp'
					: ext === '.gif' ? 'image/gif'
						: '';
		if (mime === '') return { ok: false, error: `不支持的图片扩展名: ${ext || '(无)'}（支持 png/jpg/webp/gif）` };
		const bytes = fs.readFileSync(file);
		return { ok: true, uri: `data:${mime};base64,${bytes.toString('base64')}` };
	} catch (error) {
		return { ok: false, error: `读取失败: ${error?.message ?? error}` };
	}
}

/**
 * 把 `frameFiles` 读成 data URI 数组。
 *
 * ⚠️ **全有或全无**：任何一帧读失败就整批放弃并明确报错。只送一部分会让
 *   用户看到"轮播缺了几帧"，而他根本不知道为什么。
 *
 * ⚠️ 上限与原生侧 `BubbleFrames.MAX_FRAMES` 对齐（64）。超了就在这里截断并
 *   在 message 里说明，而不是送过去让原生静默丢掉尾巴。
 *
 * @param {string[]} files - 绝对路径数组。
 * @returns {{ok: true, frames: string[], note: string} | {ok: false, error: string}} 结果。
 */
function readFrameFiles(files) {
	if (!Array.isArray(files)) return { ok: false, error: 'frameFiles 必须是数组' };
	if (files.length === 0) return { ok: true, frames: [], note: '' };

	const capped = files.slice(0, MAX_FRAMES);
	const frames = [];
	let total = 0;
	for (const file of capped) {
		const read = readImageFile(file);
		if (!read.ok) return { ok: false, error: `第 ${frames.length + 1} 帧不可用（${read.error}）` };
		total += read.uri.length;
		if (total > MAX_FRAMES_BASE64) {
			return {
				ok: false,
				error: `轮播帧总量过大（第 ${frames.length + 1} 帧后已超 ${Math.round(MAX_FRAMES_BASE64 / 1024 / 1024)} MB）。`
					+ '请先把图片缩小（例如压到 256×256 的 PNG），或减少帧数 —— '
					+ '整批一次送过去会把原生侧的内存撑爆，悬浮球会直接消失。',
			};
		}
		frames.push(read.uri);
	}
	const note = files.length > capped.length
		? `（只用了前 ${capped.length} 帧，上限 ${MAX_FRAMES}）`
		: '';
	return { ok: true, frames, note };
}

/** 写文件（先写临时文件再 rename —— 原生侧可能正读到一半）。 */
function writeJsonAtomic(target, value) {
	const tmp = `${target}.tmp`;
	fs.writeFileSync(tmp, JSON.stringify(value), 'utf8');
	fs.renameSync(tmp, target);
}

/** 读 JSON；文件不存在/内容不完整时给 null（原生正在写的那一瞬间会读到半个）。 */
function readJson(target) {
	try {
		return JSON.parse(fs.readFileSync(target, 'utf8'));
	} catch {
		return null;
	}
}

const sleep = (ms) => new Promise((resolve) => { setTimeout(resolve, ms); });

/**
 * 把一条命令交给原生侧并等回执。
 * @param {string} home - `$DSH_HOME`。
 * @param {number} id - 请求编号。
 * @param {object} payload - `{action, fields}`。
 * @returns {Promise<{ok: boolean, message: string, appearance?: string}>} 结果。
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
		return { ok: false, message: `写入文件信箱失败: ${error?.message ?? error}` };
	}

	const deadline = Date.now() + WAIT_TIMEOUT_MS;
	while (Date.now() < deadline) {
		await sleep(POLL_INTERVAL_MS);
		const reply = readJson(resPath);
		if (reply === null || reply.id !== id) continue;
		return {
			ok: reply.ok === true,
			message: typeof reply.message === 'string' ? reply.message : '',
			appearance: typeof reply.appearance === 'string' ? reply.appearance : undefined,
		};
	}
	// 超时不代表失败：原生侧可能只是慢（引擎刚启动、或主线程正忙）。
	// 但绝不能返回 ok —— 那会让模型以为改成功了。
	return {
		ok: false,
		message: `等待原生回执超时（${WAIT_TIMEOUT_MS} ms）。悬浮窗可能已经改好，也可能没有；`
			+ '请用 action:"get" 复查，确认后再向用户汇报。',
	};
}

/**
 * 工具定义。
 *
 * ⚠️ **手工构造而不是 `defineTool({...})`**：本包解在 `$DSH_HOME/bubble-tool/` 下，
 * 那里没有 `node_modules`，ESM 解析够不到引擎的 `@deepseek-ai/dsh-tools`。
 * 理由与做法与 `dsh-android-shell` 完全一致（那里有更详细的说明）。
 *
 * @param {string} home - `$DSH_HOME`。
 * @returns {object} 工具定义。
 */
function bubbleTool(home) {
	/** 请求编号。进程内自增即可 —— 只有本插件会写这个信箱。 */
	let seq = 0;

	return {
		name: TOOL_NAME,
		description: DESCRIPTION,
		parameters: {
			type: 'object',
			additionalProperties: false,
			properties: {
				action: {
					type: 'string',
					enum: ['set', 'get', 'notify'],
					description: '"set" applies the fields below; "get" only reads the current appearance back; "notify" pushes text into the status strip above the ball without changing any setting.',
				},
				state: { type: 'string', enum: ['expanded', 'collapsed', 'hidden', 'auto'] },
				shape: { type: 'string', enum: ['circle', 'square', 'rounded', 'path'] },
				shapePath: { type: 'string', description: 'SVG path "d" for shape:"path" — any outline you like, in shapeViewBox units. Max 4000 chars.' },
				shapeViewBox: { type: 'string', description: '"minX minY width height" for shapePath, default "0 0 100 100".' },
				size: { type: 'number', description: 'Ball diameter in dp, 24–160.' },
				cornerRadius: { type: 'number', description: 'dp, 0–80.' },
				opacity: { type: 'number', description: 'Global opacity multiplier, 0.05–1.' },
				bgOpacity: { type: 'number', description: '0–1, affects the background shape ONLY. 0 = fully transparent background.' },
				avatarOpacity: { type: 'number', description: '0–1, affects the image ONLY.' },
				avatarScale: { type: 'number', description: '0.2–2.0; above 1 overflows and gets clipped by the shape.' },
				bg: { type: 'string', description: 'Solid colour "#RRGGBB" (or "#AARRGGBB"), or the top colour of a vertical gradient with bg2.' },
				bg2: { type: 'string', description: 'Bottom colour "#RRGGBB" of the vertical gradient.' },
				border: { type: 'string', description: 'Border colour "#RRGGBB".' },
				borderWidth: { type: 'number', description: 'dp, 0–8.' },
				avatar: { type: 'string', description: '"default", "none" (no image at all), or a data:image/...;base64,... URI.' },
				avatarFile: { type: 'string', description: 'Absolute path to an image file to use as the avatar. Takes precedence over avatar.' },
				frameFiles: {
					type: 'array',
					items: { type: 'string' },
					description: 'Absolute paths to image files forming an animated carousel (or ONE sprite sheet when spriteRows/spriteCols are set). Pass [] to clear the carousel. Max 64 frames.',
				},
				frameDurations: {
					type: 'array',
					items: { type: 'number' },
					description: 'Optional per-frame durations in ms, same order as frameFiles, each 20–60000.',
				},
				frameIntervalMs: { type: 'number', description: 'Uniform frame duration in ms, 20–60000 (default 400).' },
				frameLoop: { type: 'number', description: '-1 (default) plays forever; N>0 stops on the last frame after N cycles.' },
				spriteRows: { type: 'number', description: 'Sprite-sheet rows, 0–32. Use with spriteCols and a single frameFiles entry.' },
				spriteCols: { type: 'number', description: 'Sprite-sheet columns, 0–32.' },
				spin: { type: 'number', description: 'Degrees per second of rotation, -720–720; negative is counter-clockwise, 0 stops it.' },
				pulse: { type: 'boolean', description: 'Breathing scale animation on the image.' },
				panelWidth: { type: 'number', description: 'Expanded panel width in dp.' },
				panelHeight: { type: 'number', description: 'Expanded panel height in dp; 0 fits the content.' },
				panelBg: { type: 'string', description: 'Panel background "#RRGGBB".' },
				panelText: { type: 'string', description: 'Panel body text colour "#RRGGBB".' },
				panelAccent: { type: 'string', description: 'Panel accent colour "#RRGGBB" (send button, user bubble).' },
				statusAutoHideMs: { type: 'number', description: 'Milliseconds before the status strip hides once its content stops changing; 0 disables it.' },
				edgeSnap: { type: 'boolean', description: 'Whether dragging the ball off-screen collapses it into an edge sliver.' },
				text: { type: 'string', description: 'For action:"notify" — the message to show in the status strip above the ball.' },
				durationMs: { type: 'number', description: 'For action:"notify" — how long the message stays, in ms. Omit to use statusAutoHideMs.' },
				sticky: { type: 'boolean', description: 'For action:"notify" — true keeps the message up until the next notify.' },
			},
			required: ['action'],
		},
		output: {
			schema: {
				type: 'object',
				additionalProperties: false,
				properties: {
					ok: { type: 'boolean' },
					appearance: { type: 'string' },
					message: { type: 'string' },
				},
				required: ['ok', 'appearance', 'message'],
			},
			render: (_args, value) => [{ type: 'text', text: renderText(value) }],
		},
		presentCall: (args) => ({
			card: 'generic',
			title: args.action === 'get'
				? '读取悬浮窗外观'
				: args.action === 'notify' ? '推送状态消息' : '修改悬浮窗',
			description: describeCall(args),
		}),
		async execute(args) {
			const action = args.action;

			// `notify` 走独立分支：它不改外观，也就不该被外观字段的校验牵连。
			if (action === 'notify') {
				const text = typeof args.text === 'string' ? args.text.trim() : '';
				if (text === '') {
					return { ok: false, appearance: '', message: 'notify 需要非空的 text' };
				}
				const reply = await sendCommand(home, ++seq, {
					action: 'notify',
					text,
					durationMs: typeof args.durationMs === 'number' ? args.durationMs : 0,
					sticky: args.sticky === true,
				});
				return {
					ok: reply.ok,
					appearance: reply.appearance ?? '',
					message: reply.ok ? `已推送状态消息: ${text}` : reply.message,
				};
			}

			const fields = pickFields(args ?? {});
			const notes = [];

			// `avatarFile` → data URI。先做，因为它可能失败，失败时要**明确报错**
			// 而不是静默退回内置头像（那样用户会看到球突然变成默认头像却没有任何提示）。
			if (typeof args?.avatarFile === 'string' && args.avatarFile !== '') {
				const read = readImageFile(args.avatarFile);
				if (!read.ok) {
					return { ok: false, appearance: '', message: `头像文件不可用（${read.error}）` };
				}
				fields.avatar = read.uri;
			}

			// `frameFiles` → data URI 数组。同上：读失败就整批放弃。
			if (args?.frameFiles !== undefined) {
				const read = readFrameFiles(args.frameFiles);
				if (!read.ok) {
					return { ok: false, appearance: '', message: `轮播帧不可用（${read.error}）` };
				}
				fields.frames = read.frames;
				if (read.note !== '') notes.push(read.note);
			}

			if (action === 'get') {
				const reply = await sendCommand(home, ++seq, { action: 'get' });
				return {
					ok: reply.ok,
					appearance: reply.appearance ?? '',
					message: reply.ok ? '已读取当前悬浮窗外观' : reply.message,
				};
			}

			// 空 fields 的 set 没有意义（原生会原样返回当前配置），但也不算错 ——
			// 模型可能只是想确认当前状态。这里只提示一句，不报失败。
			if (Object.keys(fields).length === 0) {
				notes.push('没有传入任何字段，外观未改变（只想读状态请用 action:"get"）');
			}

			const reply = await sendCommand(home, ++seq, { action: 'set', fields });
			return {
				ok: reply.ok,
				appearance: reply.appearance ?? '',
				message: reply.ok
					? `已应用（只列出了你改动的字段时，未列出的字段保持原值）：${summarizeFields(fields)}${notes.join('')}`
					: reply.message,
			};
		},
	};
}

/**
 * 把下发的字段压成一行摘要给模型看。
 *
 * ⚠️ **不能直接 `JSON.stringify(fields)`**：`avatar` 与 `frames` 里装的是
 *   base64 data URI，一帧就几十万字符、64 帧能到几十 MB —— 那会把整条对话
 *   的上下文当场撑爆（而且这些内容模型本来就刚给过，回显毫无价值）。
 *   所以图片类字段只报"有几帧 / 多少字节"。
 */
function summarizeFields(fields) {
	const parts = [];
	for (const [key, value] of Object.entries(fields)) {
		if (key === 'frames') {
			const n = Array.isArray(value) ? value.length : 0;
			parts.push(`frames:${n} 帧`);
			continue;
		}
		if (key === 'avatar' && typeof value === 'string' && value.startsWith('data:')) {
			parts.push(`avatar:自定义图片(${Math.round(value.length / 1024)} KB)`);
			continue;
		}
		parts.push(`${key}:${JSON.stringify(value)}`);
	}
	return parts.join(', ');
}

/** 把一次调用压成一行给用户看的说明。 */
function describeCall(args) {
	if (args?.action === 'get') return '读取当前状态、大小、形状与配色';
	if (args?.action === 'notify') {
		const text = typeof args?.text === 'string' ? args.text : '';
		return text === '' ? '推送状态消息' : `推送状态消息：${text}`;
	}
	const bits = [];
	if (args?.state !== undefined) bits.push(String(args.state));
	if (args?.size !== undefined) bits.push(`${args.size}dp`);
	if (args?.shape !== undefined) bits.push(String(args.shape));
	if (args?.shapePath !== undefined) bits.push('自定义形状');
	if (args?.bg !== undefined) bits.push(String(args.bg));
	if (args?.bgOpacity !== undefined) bits.push(`背景不透明度 ${args.bgOpacity}`);
	if (args?.avatarOpacity !== undefined) bits.push(`图片不透明度 ${args.avatarOpacity}`);
	if (args?.spin !== undefined && args.spin !== 0) bits.push(`旋转 ${args.spin}°/s`);
	if (args?.pulse === true) bits.push('呼吸动画');
	if (Array.isArray(args?.frameFiles)) {
		const n = args.frameFiles.length;
		bits.push(args?.spriteRows && args?.spriteCols
			? `精灵图 ${args.spriteRows}×${args.spriteCols}`
			: `轮播 ${n} 帧`);
	}
	if (args?.avatarFile !== undefined) bits.push(`头像 ${path.basename(String(args.avatarFile))}`);
	else if (args?.avatar === 'none') bits.push('去掉图片');
	else if (args?.avatar !== undefined) bits.push('自定义头像');
	return bits.length > 0 ? bits.join(' · ') : '应用外观改动';
}

/** 把结果渲染成模型可见文本。 */
function renderText(value) {
	const lines = [];
	lines.push(value.ok ? '结果: 成功' : '结果: 失败');
	if (value.appearance !== '') lines.push(`当前外观: ${value.appearance}`);
	if (value.message !== '') lines.push(value.message);
	return lines.join('\n');
}

/**
 * 注册 `bubble` 工具；前置条件不满足时**不注册**。
 * @param {object} ctx - Cordis 上下文（需 `tools` 服务）。
 */
export function apply(ctx) {
	const home = process.env.DSH_HOME;
	if (typeof home !== 'string' || home === '') {
		ctx?.logger?.warn?.('[dsh-android-bubble] 未配置 DSH_HOME，bubble 工具未注册');
		return;
	}
	if (!fs.existsSync(home)) {
		ctx?.logger?.warn?.(`[dsh-android-bubble] DSH_HOME 不存在（${home}），bubble 工具未注册`);
		return;
	}

	ctx.tools.register(bubbleTool(home));
	ctx?.logger?.info?.(`[dsh-android-bubble] 已注册悬浮球控制工具 ${TOOL_NAME}: ${path.join(home, CMD_FILE)}`);
}
