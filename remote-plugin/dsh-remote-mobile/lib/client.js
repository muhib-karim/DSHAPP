/**
 * dsh-remote-mobile —— 手机端（client half，跑在 WebView 的页面世界里）。
 *
 * ## 这个文件做两件事
 *
 * 1. **「电脑文件夹」入口** —— 注册进 `directoryFlow` slot，让选文件夹的对话框里
 *    多出一个「电脑」页签：浏览电脑目录、在电脑上直接开一个会话。
 * 2. **电脑实时消息流** —— 订阅宿主桥的全局 SSE，把电脑的流式帧转成
 *    `assistant/live-chunk` transient 注入本地会话，于是手机上有逐字动画。
 *
 * ## ⚠️ 为什么是「取代」官方对话框，而不是「并列」
 *
 * `directoryFlow` 是 `kind: "single"` 的 slot，渲染时取 `entriesOfSlot(name)[0]`，
 * 而 entries 按 priority **升序**排（见 dsh-client-ui-renderer L1148-1151）。
 * 也就是说这个 slot 同时只能渲染**一个** occupant，无法「并排加一项」。
 *
 * 所以本插件用 **priority -1** 占据首位，自己实现一个双页签对话框：
 * 「手机」页签复刻官方浏览行为（走 `uiWorkspace.listDirectory`，与官方 browse
 * 后端是同一个 API），「电脑」页签走宿主桥。对用户的观感就是
 * 「选文件夹的时候多了一个电脑入口」。
 *
 * ## ⚠️ 语法约束（旧内核）
 *
 * 本 App 有按 chrome73 降级编译的前端 bundle（`webview-compat.zip`），
 * 所以这里**不用**可选链 `?.`、空值合并 `??`、`Object.fromEntries`、`Array.flat`
 * 这些 chrome80+ 的语法。
 *
 * @module dsh-jiuling-remote-mobile/client
 */

window.__ModuleLoader__.load({
	id: 'dsh-jiuling-remote-mobile',
	factory: (require) => {
		var module = { exports: {} };
		var exports = module.exports;
		Object.defineProperty(exports, Symbol.toStringTag, { value: 'Module' });

		var react = require('react');
		var h = react.createElement;

		/**
		 * 官方 UI 原子组件（对话框、按钮、分段页签、图标）。
		 *
		 * ⚠️ **这是「长得像官方」的唯一正解**：官方目录选择器
		 * （`dsh-client-ui-directory-picker-browse`）自己也是这么 `require` 的，
		 * 该模块已在页面模块注册表里，手机与桌面两端都有。
		 *
		 * 自己手搓 `div` + `style` 的代价是实打实的：先前那版写死了
		 * `background:var(--dsw-alias-bg-primary,#1b1b1f)` —— 而
		 * **`--dsw-alias-bg-primary` 这个令牌根本不存在**（官方真实名字是
		 * `--dsw-alias-bg-layer-1` / `bg-l1` / `bg-base`），于是整块卡片永远走
		 * 那个深色 fallback；`--dsw-alias-border-secondary` 同样不存在。
		 * 表现就是手机上「两大坨黑色、跟官方 UI 完全脱钩」。
		 */
		var primitives = require('@deepseek-ai/dsh-client-ui-primitives');
		var Modal = primitives.Modal;
		var Button = primitives.Button;
		var Input = primitives.Input;
		var SegmentedTabs = primitives.SegmentedTabs;
		var IconFolderCloseRegular = primitives.IconFolderCloseRegular;
		var IconChevronRightOutlineRegular = primitives.IconChevronRightOutlineRegular;
		var IconPlusOutlineRegular = primitives.IconPlusOutlineRegular;

		/** 宿主桥的路由（同源，直接相对路径）。 */
		var ROUTE_RPC = '/api/dsh-remote/rpc';
		var ROUTE_STREAM = '/api/dsh-remote/stream';

		/**
		 * 对话框卡片的 className（Modal 的 `className` 收字符串、不是 style 对象）。
		 *
		 * 尺寸与留白**逐条照抄**官方目录选择器对 Modal 的覆盖
		 * （`.../dsh-client-ui-directory-picker-browse/lib/client.js` 里
		 * `dialog{...gap:0;width:min(680px,100%);height:min(500px,100dvh - 32px);padding:0}`），
		 * 只把宽度收窄到手机合适的 560px。背景、圆角、阴影、遮罩一律由官方
		 * Modal 自己的 CSS 提供 —— 本插件**不再声明任何颜色**。
		 */
		var DIALOG_CLASS = 'jlm_dialog';

		/**
		 * 布局样式：取值全部照抄官方目录选择器的同义规则，令牌全部是
		 * **真实存在**的官方设计令牌（`--dsw-alias-border-l3`、`bg-layer-*`、
		 * `interactive-bg-hover`、`label-*` …），不写死任何颜色值。
		 */
		var CSS = {
			dialog: 'gap:0;width:min(560px,100%);height:min(560px,100dvh - 32px);padding:0;',
			// 官方用它把 header / content / footer 抬进 Modal 卡片的 flex 流里。
			editorScope: 'display:contents;',
			header:
				'display:flex;flex-direction:column;flex:none;gap:8px;' +
				'padding:16px 14px 8px 24px;border-bottom:.5px solid var(--dsw-alias-border-l3);',
			title:
				'display:flex;align-items:flex-end;min-height:28px;margin:0;' +
				'font-size:16px;font-weight:510;line-height:24px;color:var(--dsw-alias-label-primary);',
			crumbBar: 'display:flex;align-items:center;gap:8px;min-height:24px;',
			// 「显示隐藏文件夹」开关：与「上一级」同排，靠右。
			crumbToggle:
				'display:flex;flex:none;align-items:center;gap:4px;cursor:pointer;' +
				'color:var(--dsw-alias-label-tertiary);font-size:12px;line-height:18px;white-space:nowrap;',
			crumbToggleOn: 'color:var(--dsw-alias-label-secondary);',
			pathText:
				'flex:1 1 0;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;' +
				'color:var(--dsw-alias-label-tertiary);font-size:13px;line-height:20px;',
			content:
				'display:flex;flex-direction:column;flex:1 1 auto;min-height:0;' +
				'padding:16px 16px 16px 24px;position:relative;overflow:hidden;',
			column:
				'display:flex;flex-direction:column;flex:1 1 auto;gap:2px;min-height:0;' +
				'padding-right:8px;overflow-y:auto;',
			row:
				'display:flex;align-items:center;gap:4px;width:100%;height:28px;padding:4px;' +
				'border:none;border-radius:var(--dsw-radius-sm);background:0 0;' +
				'text-align:left;cursor:pointer;flex:0 0 auto;',
			rowIcon: 'flex:none;color:var(--dsw-alias-label-secondary);',
			rowName:
				'flex:1 1 0;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;' +
				'color:var(--dsw-alias-label-primary);font-size:13px;font-weight:500;line-height:20px;',
			rowChevron: 'flex:none;color:var(--dsw-alias-label-tertiary);',
			status:
				'padding:4px 120px 4px 4px;font-size:12px;line-height:18px;' +
				'color:var(--dsw-alias-label-secondary);',
			error:
				'padding:4px 120px 4px 4px;font-size:12px;line-height:18px;' +
				'color:var(--dsw-alias-state-error-primary);word-break:break-all;',
			note:
				'padding:8px 4px 0;font-size:12px;line-height:18px;' +
				'color:var(--dsw-alias-label-tertiary);',
			footerBar:
				'display:flex;flex-wrap:wrap;flex:none;align-items:center;gap:8px;' +
				'padding:16px 24px;border-top:.5px solid var(--dsw-alias-border-l3);',
			footerGap: 'flex:1 1 0;',
			footerAction: 'min-width:72px;',
			// —— 配对条（只在「电脑」页签下显示）——
			pairBar:
				'display:flex;flex-wrap:wrap;align-items:center;gap:8px;flex:none;' +
				'padding:0 0 8px;',
			// ⚠️ **不能 `white-space:nowrap`**：手机上 360px 宽、这行字有 30 多个汉字，
			// 加了 nowrap + ellipsis 就会被截成「…里生...」—— 用户看不到要去哪一步，
			// 而这一步正是他唯一需要知道的。允许折行。
			pairWho:
				'flex:1 1 100%;min-width:0;' +
				'color:var(--dsw-alias-label-tertiary);font-size:12px;line-height:18px;',
			pairInput: 'width:140px;text-transform:uppercase;letter-spacing:2px;',
			// 解绑二次确认那一行：提示文字占满整行，两个按钮跟在下面（手机上 360px
			// 放不下「文字 + 两个按钮」一行，所以让文字 `flex:1 1 100%` 自己换行）。
			pairConfirm:
				'display:flex;flex-wrap:wrap;align-items:center;gap:8px;' +
				'font-size:12px;line-height:18px;color:var(--dsw-alias-label-tertiary);',
			pairConfirmText: 'flex:1 1 100%;',
			pairMsg: 'padding:0 4px 8px;font-size:12px;line-height:18px;',
			pairOk: 'color:var(--dsw-alias-state-success-primary);',
			// 「刷新」那一行（模型 / 预设 / 目录树三样一起刷）的回执文字。
			refreshNote:
				'padding:0 4px 8px;font-size:12px;line-height:18px;' +
				'color:var(--dsw-alias-label-tertiary);',
			refreshOk: 'color:var(--dsw-alias-state-success-primary);',
		};

		/**
		 * 把 CSS 文本解析成 React 的 style **对象**。
		 *
		 * ⚠️ **这一步不能省**：React 的 `style` prop 只接受对象，给字符串会当场抛
		 * `Minified React error #62`（React 18 的
		 * `if (style != null && typeof style != "object") throw Error(62)`），
		 * 组件随即被 slot 系统 `abdicate` 退役、渲染回落到官方对话框 ——
		 * 表现就是「电脑」页签根本不出现，而控制台里只有一条难懂的 #62。
		 *
		 * 键名同时做 kebab → camelCase（`min-width` → `minWidth`），否则 React
		 * 会逐条告警「Unsupported style property」。
		 *
		 * @param text - 形如 `'a:1;b:2;'` 的 CSS 声明串。
		 * @returns React style 对象。
		 */
		function toStyle(text) {
			var out = {};
			var parts = String(text).split(';');
			for (var index = 0; index < parts.length; index += 1) {
				var part = parts[index];
				if (part === '') continue;
				var colon = part.indexOf(':');
				if (colon < 0) continue;
				var name = part.slice(0, colon).trim();
				var value = part.slice(colon + 1).trim();
				if (name === '' || value === '') continue;
				out[name.replace(/-([a-z])/g, function (match, letter) {
					return letter.toUpperCase();
				})] = value;
			}
			return out;
		}

		// ⚠️ **先把文本留一份副本再转对象**：下面那个 `<style>` 标签拼的是 CSS
		// **文本**，而第 162 行会把 `CSS` 就地改写成对象 —— 顺序反了就会拼出
		// `[object Object]`，卡片尺寸规则整条失效（列表被 flex 压成 0 高、只剩空白）。
		var DIALOG_CSS_TEXT = CSS.dialog;

		// 就地转成对象：下游一律写 `style: CSS.xxx`，不会再碰到字符串形式。
		for (var styleKey in CSS) CSS[styleKey] = toStyle(CSS[styleKey]);

		/**
		 * 注入官方风格的样式表。
		 *
		 * ⚠️ 卡片那层（背景/圆角/阴影/遮罩）**全部由官方 Modal 自己的 CSS 提供**，
		 * 这里只覆盖尺寸与留白 —— 与官方目录选择器覆盖
		 * `dialog` 类的方式完全一致。所以本插件**不声明任何颜色**，
		 * 亮色/暗色主题都随官方走，不会再出现「一大坨黑」。
		 *
		 * 用 `<style data-plugin-css>` 注入（官方各 client 包的标准做法），
		 * 而不是把样式塞进 React 的 `style` prop —— Modal 只接受 `className`。
		 */
		var STYLE_TAG_ID = 'dsh-jiuling-remote-mobile/DirectoryTabs.css';
		if (
			typeof document !== 'undefined' &&
			document.querySelector('style[data-plugin-css=' + JSON.stringify(STYLE_TAG_ID) + ']') === null
		) {
			var styleTag = document.createElement('style');
			styleTag.dataset.plugin = 'dsh-jiuling-remote-mobile';
			styleTag.dataset.pluginCss = STYLE_TAG_ID;
			styleTag.textContent =
				'.' +
				DIALOG_CLASS +
				'{' +
				DIALOG_CSS_TEXT +
				'}' +
				'.' +
				DIALOG_CLASS +
				' [role="tablist"]{flex:none;}' +
				'.' +
				DIALOG_CLASS +
				' button:hover{background:var(--dsw-alias-interactive-bg-hover);}';
			document.head.appendChild(styleTag);
		}

		/**
		 * 电脑目录的**访问缓存**：`电脑路径 → listing`（模块级，跨对话框开关存活）。
		 *
		 * ## 为什么必须有（用户报障原文）
		 *
		 * 「为什么我点击添加工作区的时候，每次点击电脑都要重新加载一遍目录，
		 * 这就算了，我加载好之后，随便点击一个文件夹，文件夹里面的内容还要重新加载
		 * 一遍」—— 同一个电脑目录，每次进「电脑」页签、每次点进同一个文件夹，
		 * 都要重走一趟「手机 → Cloudflare 隧道 → 电脑」，一个来回 1～3 秒。
		 * 用户要的是「加载一次，后续直接从本地读」。
		 *
		 * ## 为什么放模块级而不是组件内 ref
		 *
		 * 对话框每次打开都是**新挂载的组件**，组件内的 ref 一关就没了 —— 那样
		 * 「关掉再打开又转一遍圈」，正是用户抱怨的第一个现象。放模块级则一次加载
		 * 整条会话内都命中。
		 *
		 * ⚠️ 键是**电脑路径**原样（`C:\Users\...`），`''` 表示根目录 —— 与
		 * `bridge('listDesktop')` 的入参完全一致，不做任何改写。
		 *
		 * ⚠️ **失效只有两条路**：用户点「刷新」（见 `refreshAll`），
		 * 或者那一次加载失败（见下面的 catch）。不做 TTL 轮询 —— 用户明确要的是
		 * 「加载一次就直接从本地读」。
		 */
		var desktopDirCache = {};

		/** 清掉访问缓存（配对 / 解绑 / 切页签时调用，避免跨电脑串味）。 */
		function clearDesktopDirCache() {
			desktopDirCache = {};
		}

		/**
		 * 哪些会话是「跑在电脑上的」—— 宿主 `remoteSessions` 的本地镜像。
		 *
		 * ## 为什么需要（用户需求原文）
		 *
		 * 「用户如果当前选择的是 **app 本地文件夹**，那么**就不要出现电脑的模型**，
		 * 以这个文件夹为隔离条件，**互不干预**」。
		 *
		 * 宿主把电脑的模型目录与手机本地那份**并**在一起交给前端（原因见宿主
		 * `mergeCatalog` 的注释：官方 `modelCatalog` 是全局唯一一份、不带 sessionId）。
		 * 所以要按工作区隔离，只能在**前端按会话**再筛一次 —— 而筛之前必须先知道
		 * 「这个会话是不是电脑的」。
		 *
		 * ⚠️ **判据必须与宿主 `prompt` 拦截同源**（宿主读的也是 `remoteSessions`）。
		 * 用 cwd 猜镜像目录是另一套判据，一旦两者不一致，就会出现「菜单里显示的是
		 * 电脑模型、消息却发到手机本地」这种最坏情况。
		 *
		 * ⚠️ **不能每次渲染打一次桥**：模型菜单每帧都读目录快照，那是同步 API。
		 * 所以 `apply` 时拉一次全量，之后建会话 / 解绑时自己增量维护，
		 * `getSnapshot` 纯查内存（见宿主 `case 'remoteSessions'` 的注释）。
		 */
		var remoteSessionIds = {};

		/** 从宿主拉一次全量远程会话清单（失败就按「全是本地」处理）。 */
		function loadRemoteSessionIds() {
			return bridge('remoteSessions', {}).then(
				function (result) {
					var ids = result && result.ids;
					if (!Array.isArray(ids)) return;
					var next = {};
					for (var at = 0; at < ids.length; at += 1) next[ids[at]] = true;
					remoteSessionIds = next;
				},
				function () {
					/* 拉不到就保持原样：最坏是本地工作区多看到电脑模型，不会误发 */
				},
			);
		}

		/**
		 * 按「这个会话是不是电脑的」筛出该显示的组与失败项。
		 *
		 * ## 双向隔离
		 *
		 * - **本地工作区**：只留手机自己的组，并把被电脑同 id provider 覆盖掉的那组
		 *   **还原**回来（`localGroups`，宿主 `mergeCatalog` 额外带上的）。
		 * - **电脑工作区**：只留电脑的组。这不是「顺手加活」—— 电脑会话的 prompt 会
		 *   转发到电脑，选一个手机本地才有的模型（比如 `agnes-3.0-flash`）发过去，
		 *   电脑上根本不存在，必然报错。用户说的「互不干预」正是这个意思。
		 *
		 * ⚠️ 没配对时目录里根本没有 `desktop` 标记，两个分支都会原样返回。
		 *
		 * ⚠️ **`extraLocal` 是必需的**：官方的 `syncInputs` 往 `directory.store`
		 * 里只拷了 `groups` / `failures`（见
		 * `dsh-client-ui-model-selection/lib/client.js:325`），**`localGroups` 不在
		 * 那一层**。所以筛 `directory.store` 时得由调用方把 `localGroups`
		 * 从 catalog 原值里递进来，否则本地工作区永远还原不出被覆盖的那组。
		 *
		 * @param value - 目录值（`{groups, failures, localGroups?, localFailures?}`）。
		 * @param isRemote - 这个会话是不是跑在电脑上。
		 * @param extraLocal - 备用本地组（`value.localGroups` 缺失时用）。
		 * @returns `{groups, failures}`（`value` 不合法时原样返回空数组）。
		 */
		function pickWorkspaceGroups(value, isRemote, extraLocal) {
			if (!value || !Array.isArray(value.groups)) {
				return { groups: [], failures: [] };
			}
			const groups = value.groups;
			const failures = Array.isArray(value.failures) ? value.failures : [];
			if (isRemote === true) {
				return {
					groups: groups.filter(function (group) { return group.desktop === true; }),
					failures: failures.filter(function (failure) { return failure.desktop === true; }),
				};
			}
			const kept = groups.filter(function (group) { return group.desktop !== true; });
			// 还原被电脑同 id 覆盖掉的本地组（见宿主 `mergeCatalog`）。
			const local = Array.isArray(value.localGroups)
				? value.localGroups
				: (Array.isArray(extraLocal) ? extraLocal : []);
			if (local.length > 0) {
				const have = {};
				for (let at = 0; at < kept.length; at += 1) have[kept[at].id] = true;
				for (let lt = 0; lt < local.length; lt += 1) {
					if (have[local[lt].id] !== true) kept.push(local[lt]);
				}
			}
			return {
				groups: kept,
				failures: failures.filter(function (failure) { return failure.desktop !== true; }),
			};
		}

		/**
		 * 从共享的 catalog 原值里取「手机本地的组」（宿主 `mergeCatalog` 带的）。
		 *
		 * @param directory - 官方的 `ModelDirectory`（含 `.catalog`）。
		 * @returns 本地组数组；拿不到就空数组。
		 */
		function localGroupsOf(directory) {
			try {
				// ⚠️ 不用可选链：本文件受 chrome73 语法约束（见文件头）。
				if (!directory || !directory.catalog || !directory.catalog.store) return [];
				const store = directory.catalog.store;
				if (typeof store.getSnapshot !== 'function') return [];
				const value = store.getSnapshot().value;
				return value && Array.isArray(value.localGroups) ? value.localGroups : [];
			} catch (error) {
				return [];
			}
		}

		/** 某个 `provider/model` 在给定的组里存不存在。 */
		function isSelectable(groups, selection) {			if (!selection) return false;
			for (let at = 0; at < groups.length; at += 1) {
				if (groups[at].id !== selection.provider) continue;
				const models = Array.isArray(groups[at].models) ? groups[at].models : [];
				for (let mt = 0; mt < models.length; mt += 1) {
					if (models[mt].id === selection.model) return true;
				}
			}
			return false;
		}

		/**
		 * 筛**目录值**（`catalog.store` 里 `{value}` 那一份）。
		 *
		 * ⚠️ **`default` 必须跟着换**：官方的 `syncInputs` 用
		 * `projected.next ?? catalog.value.default` 定 `current`，如果 `default`
		 * 指向一个已被筛掉的 provider，界面就会显示一个不在列表里的模型名。
		 * 所以筛完若 `default` 不在剩下的组里，就退到第一个组的第一个模型。
		 *
		 * ⚠️ **一组都没剩时原样返回**（例如电脑那边所有 provider 都挂了）：
		 * 宁可多显示几个，也别把菜单变成空的 —— 空菜单用户连「刷新」都点不到。
		 *
		 * @param value - 目录值。
		 * @param isRemote - 这个会话是不是跑在电脑上。
		 * @returns 筛过的目录值。
		 */
		function filterCatalogValue(value, isRemote) {
			if (!value || !Array.isArray(value.groups)) return value;
			const picked = pickWorkspaceGroups(value, isRemote);
			if (picked.groups.length === 0) return value;
			const next = Object.assign({}, value, {
				groups: picked.groups,
				failures: picked.failures,
			});
			if (!isSelectable(picked.groups, value.default)) {
				const first = picked.groups[0];
				const firstModel = Array.isArray(first.models) ? first.models[0] : undefined;
				if (firstModel !== undefined) next.default = { provider: first.id, model: firstModel.id };
			}
			return next;
		}

		/**
		 * 给官方那个**每会话共享**的 `ModelDirectory` 套一层「按工作区筛」的读路径。
		 *
		 * ## 为什么包装 `directoryFor` 而不是改前端 bundle
		 *
		 * `conversation.input.model` 这个 slot 的 `inject(sessionId)` 会调
		 * `modelDirectories.directoryFor(sessionId)`（见
		 * `dsh-client-ui-model-selection/lib/client.js:1189`），菜单和 `/model`
		 * 命令都从它拿数据。`directoryFor` 是**服务实例上的普通方法**，包它
		 * 就等于包住了这两个入口 —— 不用碰前端内部类，升级也不会碎。
		 *
		 * ## 为什么两层的 store 都要包
		 *
		 * 官方有两级快照，形状**不一样**，两个都要筛：
		 *
		 * | 来源 | 形状 | 谁读它 |
		 * |---|---|---|
		 * | `catalog.store` | `{value:{groups,default,…}, status}` | `syncInputs()` 算 `current`/`routable` |
		 * | `directory.store` | `{groups, failures, current, status, …}` | `ModelSelect` 直接渲染 |
		 *
		 * 只筛 `directory.store` 的话，`syncInputs` 仍会用**没筛过**的 `default`
		 * 把 `current` 算成被筛掉的那个模型 —— 菜单里没有它、触发按钮却显示它，
		 * 而且 `routable` 会被算成 `false`，官方界面会提示「该 provider 的目录加载失败」。
		 *
		 * ## 为什么要「稳定」而不是每次新建
		 *
		 * `inject` 每渲染一帧都调一次 `directoryFor`，返回值里的 `store` 直接喂给
		 * `useSyncExternalStore` 的 `subscribe`。每次返回新对象会让 React 每帧
		 * 重订阅。所以包装缓存在原 `directory.__dshRemoteScoped` 上，生命周期跟着
		 * 官方的会话 scope 一起走 —— 官方在 scope dispose 时删掉原实例，
		 * 包装随之被 GC，不会累积。
		 *
		 * ⚠️ 用 `Object.create(原型) + Object.assign` 而不是 `{...directory}`：
		 * `ModelDirectory` 的方法（`select` / `reasoningFor` / `syncInputs`）
		 * 在**原型**上，展开运算符只复制自有属性、会把方法全丢掉 —— 症状是
		 * 「点模型没反应」。
		 *
		 * ⚠️ `select` 走原型链、以 `this = wrapper` 调用，于是它读到的
		 * `this.store.getSnapshot()` 是**筛过**的（`current` 正确），写回
		 * `this.store.update()` 仍转发给原 store。读写都落在正确的那一层。
		 *
		 * @param directory - 官方的 `ModelDirectory` 实例。
		 * @param sessionId - 它归属的会话。
		 * @returns 读路径被筛过的同构对象。
		 */
		function scopeDirectoryToWorkspace(directory, sessionId) {
			if (!directory || !directory.store || typeof directory.store.getSnapshot !== 'function') {
				return directory;
			}
			const isRemote = function () { return remoteSessionIds[sessionId] === true; };
			const wrapper = Object.create(Object.getPrototypeOf(directory));
			Object.assign(wrapper, directory);

			/**
			 * 包一层 store：读时筛，通知与写回照旧转发。
			 *
			 * ⚠️⚠️ **`getSnapshot()` 必须身份稳定**，否则 React 直接炸。这条是
			 * 1.0.19 第二次真机启动实测到的（第一次是上面的 `remote.session`）：
			 *
			 *     [web] slot entry crashed in 'conversation.input.model':
			 *           Error: Minified React error #185
			 *
			 * #185 就是「Maximum update depth exceeded」（无限重渲染）。原因：
			 * 官方把这个 store 的 `getSnapshot` 直接喂给 `useSyncExternalStore`，
			 * 而 React 在渲染期用 **`Object.is`** 比较前后两次快照，不同就再渲染一次。
			 * 而 `pickWorkspaceGroups` 每次都 `filter` 出新数组、外面又 `Object.assign`
			 * 出新对象 —— **同一个源快照也每次返回新对象** ⇒ 每一帧都"变了" ⇒ 死循环。
			 *
			 * 所以按「源快照身份 + 影响筛选结果的外部开关」缓存上一次的投影结果。
			 */
			const wrapStore = function (store, project, volatileToken) {
				let cachedSource;
				let cachedToken;
				let cachedProjected;
				return {
					getSnapshot: function () {
						const source = store.getSnapshot();
						const token = volatileToken === undefined ? '' : volatileToken();
						// 源快照换了、或开关变了才重算；否则复用同一个对象（身份稳定）。
						if (source === cachedSource && token === cachedToken) return cachedProjected;
						cachedSource = source;
						cachedToken = token;
						cachedProjected = project(source);
						return cachedProjected;
					},
					subscribe: function (fn) { return store.subscribe(fn); },
					update: function (mutator) { return store.update(mutator); },
					set: function (value) { return store.set(value); },
				};
			};

			const store = directory.store;
			wrapper.store = wrapStore(store, function (snapshot) {
				// ⚠️ `directory.store` 那一层没有 `localGroups`（官方 `syncInputs`
				// 只拷了 groups/failures），所以从 catalog 的原值里现取一份递进去。
				const picked = pickWorkspaceGroups(snapshot, isRemote(), localGroupsOf(directory));
				if (picked.groups.length === 0) return snapshot;
				const next = Object.assign({}, snapshot, picked);
				// `current` 指向被筛掉的模型时置空 —— 官方界面会显示「选择模型」，
				// 比显示一个列表里根本没有的名字好。
				if (!isSelectable(picked.groups, snapshot.current)) next.current = null;
				return next;
			// ⚠️ 缓存键必须带上「这个会话算不算远程」：同一个源快照在「切成远程会话」
			// 前后要投影出**不同**结果，只看源快照身份会永远返回旧的那一份。
			}, function () { return isRemote() === true ? 'remote:1' : 'remote:0'; });

			// `load()` 返回的就是快照，`/model` 命令直接拿它渲染，所以也要筛。
			wrapper.load = function () {
				return Promise.resolve(directory.load()).then(function () {
					return wrapper.store.getSnapshot();
				});
			};

			const catalog = directory.catalog;
			if (catalog && catalog.store && typeof catalog.store.getSnapshot === 'function') {
				const catalogStore = catalog.store;
				const catalogWrapper = Object.create(Object.getPrototypeOf(catalog));
				Object.assign(catalogWrapper, catalog);
				catalogWrapper.store = wrapStore(catalogStore, function (snapshot) {
					return Object.assign({}, snapshot, {
						value: filterCatalogValue(snapshot.value, isRemote()),
					});
				}, function () { return isRemote() === true ? 'remote:1' : 'remote:0'; });
				wrapper.catalog = catalogWrapper;
			}
			return wrapper;
		}

		/**
		 * 把「按工作区筛模型目录」接到官方服务上。
		 *
		 * `ctx.get` 拿不到服务就静默跳过（最坏是回到「两个工作区看到同一份列表」的
		 * 旧行为，不会报错）。返回一个撤销函数。
		 *
		 * @param ctx - client 根上下文。
		 * @returns 撤销函数。
		 */
		function patchModelDirectories(ctx) {
			var models = ctx.get === undefined ? undefined : ctx.get('modelDirectories');
			if (!models || typeof models.directoryFor !== 'function') return function () {};

			// ⚠️⚠️ **必须在「原始实例」上覆盖，不能在 `ctx.get()` 拿到的那个对象上覆盖。**
			//
			// `modelDirectories` 是 cordis 的 Service，`ctx.get()` 返回的是
			// `createTraceable()` 造出来的 **Proxy**（见 cordis `lib/index.js` 的
			// `getTraceable`/`createTraceable`）。那个 Proxy 的 `get` 会把方法包成
			// `createShadowMethod(ctx, fn, receiver, shadow)`，其 apply 里有一句：
			//
			//     if (thisArg === outer) thisArg = shadow;
			//
			// `outer` 就是取方法时的那个 receiver，而 `shadow` 是
			// `withProp(receiver, property, ctx.extend({[shadow]: origin}))` ——
			// 这个 `ctx` 是**谁调 `ctx.get()` 就用谁的上下文**。
			//
			// 于是 `models.directoryFor.bind(models)` 把 this 钉在了 Proxy 上，
			// 每次调用都命中 `thisArg === outer` ⇒ this 被换成**我的插件上下文**
			// 扩展出来的 shadow。而官方 `ModelDirectoryResolver.directoryFor` 内部要读
			// `this.ctx.remote.session`（`dsh-client-ui-model-selection/lib/client.js:388`），
			// 我的插件没有 inject 过 `remote.session` ⇒ 抛
			//
			//     cannot get property "remote.session" without inject
			//
			// 真机症状：`conversation.input.model` 这个 slot 整体 crash，
			// **输入框上的模型按钮直接消失**。1.0.19 首次真机启动实测到的。
			//
			// 正解是用 `Symbol.for('cordis.original')` 取出 Proxy 背后的原始实例
			// （`createTraceable` 的 get trap 里第一条就是 `if (prop === symbols.original) return target`），
			// 在**它**身上覆盖。这样 `this.ctx` 始终是 `ModelDirectoryResolver`
			// 构造时 `super(ctx, …)` 存下的那个 ctx，服务解析照旧。
			var ORIGINAL = Symbol.for('cordis.original');
			var raw = models[ORIGINAL] === undefined ? models : models[ORIGINAL];
			if (!raw || typeof raw.directoryFor !== 'function') return function () {};

			var hadOwn = Object.prototype.hasOwnProperty.call(raw, 'directoryFor');
			var previous = raw.directoryFor;
			var call = previous.bind(raw);
			raw.directoryFor = function (sessionId) {
				var directory = call(sessionId);
				if (directory.__dshRemoteScoped !== undefined) return directory.__dshRemoteScoped;
				var scoped = scopeDirectoryToWorkspace(directory, sessionId);
				try {
					directory.__dshRemoteScoped = scoped;
				} catch (error) {
					/* 冻结就退回「每次新建」，功能不受影响 */
				}
				return scoped;
			};
			return function () {
				if (hadOwn) raw.directoryFor = previous;
				else delete raw.directoryFor;
			};
		}

		/**
		 * 调宿主桥的一条 RPC（令牌只在引擎进程里，浏览器侧不持有）。
		 *
		 * @param method - 方法名。
		 * @param params - 参数。
		 * @returns 结果的 Promise。
		 */
		function bridge(method, params) {
			return fetch(ROUTE_RPC, {
				method: 'POST',
				headers: { 'content-type': 'application/json' },
				body: JSON.stringify({ method: method, params: params || {} }),
			}).then(
				(res) => res.json(),
				(error) => {
					throw new Error('无法连接本地桥: ' + String((error && error.message) || error));
				},
			).then((payload) => {
				if (!payload || payload.ok !== true) {
					throw new Error((payload && payload.error) || '本地桥调用失败');
				}
				return payload.result;
			});
		}

		/** 从任意形状的目录列表里取条目数组（宿主/官方后端的字段名略有差异）。 */
		function entriesOf(listing) {
			if (!listing || typeof listing !== 'object') return [];
			if (Array.isArray(listing.entries)) return listing.entries;
			if (Array.isArray(listing.items)) return listing.items;
			return [];
		}

		/** 取条目的显示名。 */
		function nameOf(entry) {
			return entry.name || entry.title || entry.displayName || String(entry.path || '');
		}

		/** 取条目的完整路径。 */
		function pathOf(entry) {
			return entry.path || entry.fullPath || entry.name || '';
		}

		/** 是否是可进入的文件夹。 */
		function isDir(entry) {
			if (entry.enterable === false) return false;
			if (entry.isDirectory === false) return false;
			return true;
		}

		/**
		 * 是否是隐藏条目（名字以 `.` 开头）。
		 *
		 * ⚠️ **只按名字判断，不要去看属性位**：电脑端返回的条目只有
		 * `{name, path, isDirectory, enterable}`，没有 Windows 的「隐藏」属性，
		 * 而用户说的「前面带 `.` 的文件夹比如 `.dsh`」正是这一种。
		 *
		 * @param entry - 目录条目。
		 * @returns 是否应归入「默认不显示」。
		 */
		function isHidden(entry) {
			var name = nameOf(entry);
			return name.length > 0 && name.charAt(0) === '.';
		}

		/** 取列表的当前路径与上级路径。 */
		function locationOf(listing) {
			var path = (listing && listing.path) || '';
			var parent = (listing && listing.parent) || '';
			// 后端明确给空 parent = 已经到顶了（Linux 的 `/`、Windows 的 `C:\`），
			// 这时**不能**再自己兜底推算：切 `C:\` 会得到 `C:`，而它在前端眼里
			// 也是绝对路径、按钮会被点亮，点下去后端直接报错。
			var atRoot = path === '' || path === '/' || /^[a-zA-Z]:[\\/]?$/.test(path);
			if (parent === '' && !atRoot) {
				var trimmed = path.replace(/[\\/]+$/, '');
				var slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
				if (slash > 0) parent = trimmed.slice(0, slash);
			}
			return { path: path, parent: parent, home: (listing && listing.home) || '' };
		}

		/**
		 * 「选择工作区目录」对话框：手机 / 电脑 双页签。
		 *
		 * props 是 slot 契约的 `{open, busy, onPicked, onCancel, onError}`，
		 * 外加 `inject` 送进来的浏览能力与宿主桥。
		 */
		function RemoteDirectoryFlow(props) {
			var open = props.open;
			var onPicked = props.onPicked;
			var onCancel = props.onCancel;
			var listDirectory = props.listDirectory;
			var createDirectory = props.createDirectory;
			var openSession = props.openSession;
			/** 把宿主刚建好的会话登记进本地列表（见 `settleLocal`）。 */
			var registerLocal = props.registerLocal;
			/** 兜底：拉一次全量会话列表（登记失败时用）。 */
			var refreshSessions = props.refreshSessions;
			/** 把会话标记成「电脑的」，让模型菜单按工作区隔离（见 `remoteSessionIds`）。 */
			var markRemote = props.markRemote;
			/** 解绑后把所有会话退回「本地」。 */
			var clearRemote = props.clearRemote;

			var statePair = react.useState({
				tab: 'local',
				path: null,
				listing: null,
				loading: false,
				error: null,
				creating: false,
				// 是否显示 `.` 开头的隐藏文件夹。默认关 —— 电脑的项目根目录里
				// `.dsh`、`.git`、`.vscode` 一堆，全列出来会把真正的项目目录淹掉。
				showHidden: false,
			});
			var state = statePair[0];
			var setState = statePair[1];

			/**
			 * 配对状态（与目录浏览无关，所以单独一个 state）。
			 *
			 * `endpoint === null` 表示还没查过；`paired === false` 表示
			 * 手机还没有任何凭据（令牌为空），需要用户去电脑上取一个配对码。
			 */
			var pairPair = react.useState({
				endpoint: null,
				paired: false,
				code: '',
				busy: false,
				// 「重新配对」的两步确认：`true` 时才显示「确定解绑 / 取消」。
				confirming: false,
				message: null,
				// 一键刷新（模型 / 预设 / 目录树）的状态与回执。
				refreshing: false,
				refreshNote: null,
			});
			var pairState = pairPair[0];
			var setPairState = pairPair[1];

			var patchPair = react.useCallback(function (next) {
				setPairState(function (prev) {
					return Object.assign({}, prev, next);
				});
			}, []);

			var patch = react.useCallback(function (next) {
				setState(function (prev) {
					return Object.assign({}, prev, next);
				});
			}, []);

			var aliveRef = react.useRef(true);
			react.useEffect(function () {
				aliveRef.current = true;
				return function () {
					aliveRef.current = false;
				};
			}, []);

			/**
			 * 让官方前端的模型目录**失效重载**。
			 *
			 * ## ⚠️ 这一步为什么不能省
			 *
			 * 官方 `ModelCatalogDirectory` 在页面加载时就 `load()` 一次并进入
			 * `status === 'ready'`，此后**点菜单一个请求都不发**
			 * （`dsh-client-ui-model-selection/lib/client.js:100`：ready 时直接
			 * 返回旧值）。所以「配对之后模型列表变成电脑的」这件事，只把宿主那边的
			 * 缓存换掉是**看不见的** —— 前端那一层永远捧着配对前拉到的手机列表。
			 *
			 * 这正是用户报的「预设列表正确地加载了电脑预设，但模型列表还是没同步」：
			 * 预设走 `remoteExportList`，**没有缓存层**、每次重读，所以它是对的。
			 *
			 * `modelDirectories` 是官方那个服务实例（`super(ctx, "modelDirectories")`），
			 * `.catalog.refresh()` 就是官方在 `llm/adapters-updated` 时走的同一条路 ——
			 * 走公开方法，不碰前端 bundle 内部类。
			 *
			 * `ctx.get` 拿不到服务时静默跳过（见 `injected()` 里 `modelDirectories`
			 * 的注释）：最坏是回到「要点一下刷新按钮」的旧行为，不会报错。
			 */
			var reloadModelCatalog = function () {
				try {
					var models = props.modelDirectories;
					if (models && models.catalog && typeof models.catalog.refresh === 'function') {
						models.catalog.refresh();
					}
				} catch (error) {
					/* 服务结构变了就只更新宿主缓存，不打扰用户 */
				}
			};

			// 每次打开都回到「手机」页签，避免上次停在电脑目录造成困惑。
			react.useEffect(
				function () {
					if (open) patch({ tab: 'local', path: null, error: null });
				},
				[open, patch],
			);

			// 打开时查一次配对状态（只查一次，失败也不打扰用户 —— 未配对
			// 本来就是这个流程的正常起点）。
			react.useEffect(
				function () {
					if (!open) return undefined;
					bridge('pairStatus', {}).then(
						function (result) {
							if (!aliveRef.current) return;
							patchPair({ endpoint: result.endpoint, paired: result.paired === true });
						},
						function () {
							/* 查不到就不显示那一条，用户仍然可以输码 */
						},
					);
					return undefined;
				},
				[open, patchPair],
			);

			// 加载当前页签、当前路径的目录列表。
			var tab = state.tab;
			var path = state.path;
			react.useEffect(
				function () {
					if (!open) return undefined;
					// ⚠️ **没配对就别去问电脑要目录**。未配对时手机没有任何凭据，
					// 打过去必然是 401/502 —— 用户看到的是转圈 10 秒后一句
					// 「远程请求失败」，完全不知道要先去配对。这里直接给出可操作的提示。
					if (tab === 'desktop' && pairState.paired !== true) {
						patch({ loading: false, listing: null, error: null });
						return undefined;
					}
					// ⚠️ **电脑目录命中缓存就直接铺回去，不打桥**（用户报障：来回点文件夹
					// 每次都重新加载）。缓存键用 `''` 表示根，与 `listDesktop` 的入参一致。
					if (tab === 'desktop') {
						var key = path === null ? '' : path;
						var hit = desktopDirCache[key];
						if (hit !== undefined) {
							patch({ loading: false, listing: hit, error: null });
							return undefined;
						}
					}
					patch({ loading: true, error: null });
					var load =
						tab === 'desktop'
							? bridge('listDesktop', path === null ? {} : { path: path })
							: Promise.resolve(listDirectory(path === null ? undefined : path));
					load.then(
						function (listing) {
							if (!aliveRef.current) return;
							if (tab === 'desktop') {
								desktopDirCache[path === null ? '' : path] = listing;
							}
							patch({ loading: false, listing: listing });
						},
						function (reason) {
							if (!aliveRef.current) return;
							// ⚠️ 失败**要清掉这一格缓存**：留着的话用户再点进来会看到
							// 上一次的旧内容，而以为自己看的是电脑现状。
							if (tab === 'desktop') delete desktopDirCache[path === null ? '' : path];
							patch({
								loading: false,
								listing: null,
								error: String((reason && reason.message) || reason),
							});
						},
					);
					return undefined;
				},
				[open, tab, path, listDirectory, patch, pairState.paired],
			);

			if (!open) return null;

			var loc = locationOf(state.listing);
			// `.` 开头的默认不显示（见 `isHidden`）；开关在面包屑那一行。
			var allDirs = entriesOf(state.listing).filter(isDir);
			var hiddenCount = allDirs.filter(isHidden).length;
			var entries = state.showHidden === true ? allDirs : allDirs.filter(function (e) { return !isHidden(e); });

			var switchTab = function (next) {
				if (next === state.tab) return;
				patch({ tab: next, path: null, listing: null, error: null });
			};

			/** 「手机」页签：采纳为本地工作区。 */
			var pickLocal = function () {
				if (loc.path === '') return;
				onPicked(loc.path);
			};

			/**
			 * 提交配对码。
			 *
			 * 成功后**必须重拉一次目录列表**：配对的当下 `listDesktop` 是失败的
			 * （那时还没配对），而上面那个 effect 的依赖里有 `paired`，
			 * 状态一变它自己就会重跑 —— 这里只需要把 `paired` 翻过来。
			 */
			var submitPair = function () {
				var code = pairState.code.trim();
				if (code === '' || pairState.busy) return;
				patchPair({ busy: true, message: null });
				bridge('pair', { code: code }).then(
					function (result) {
						if (!aliveRef.current) return;
						patchPair({
							busy: false,
							paired: true,
							endpoint: result.endpoint,
							code: '',
							message: { kind: 'ok', text: '配对成功，正在连接电脑…' },
						});
						// ⚠️ 换了一台电脑，旧缓存里的路径完全无效（甚至同名不同机）——
						// 必须作废，否则用户会看到上一台电脑的目录。
						clearDesktopDirCache();
						patch({ path: null, listing: null });
						// ⚠️ 前端那份模型目录是**永久缓存**的（见 `reloadModelCatalog`），
						// 不作废的话用户接下来看到的仍然是配对前那份手机模型列表 ——
						// 就是用户报的「模型列表还是没同步」。
						reloadModelCatalog();
					},
					function (reason) {
						if (!aliveRef.current) return;
						patchPair({
							busy: false,
							message: { kind: 'error', text: String((reason && reason.message) || reason) },
						});
					},
				);
			};

			/**
			 * 解除配对：清掉本地凭据，退回未配对状态。
			 *
			 * 成功后 `paired` 翻回 `false`，配对条自己就会换成输入框（同一个
			 * `pairState`），所以这里不用手工重挂组件。
			 */
			var submitUnpair = function () {
				if (pairState.busy) return;
				patchPair({ busy: true, message: null });
				bridge('unpair', {}).then(
					function (result) {
						if (!aliveRef.current) return;
						patchPair({
							busy: false,
							confirming: false,
							paired: false,
							endpoint: result.endpoint,
							code: '',
							message: { kind: 'ok', text: '已解绑，请到电脑上生成新的配对码' },
						});
						// 解绑后旧凭据作废，缓存里的目录不该再露出来。
						clearDesktopDirCache();
						// ⚠️ **同时清掉「电脑会话」标记**：解绑后电脑已经不在了，
						// 那些会话退回普通本地会话（宿主 `unpair` 也清了
						// `remoteSessions`）。留着标记的话，模型菜单会一直显示
						// 电脑的模型，而消息其实发给了手机本地。
						if (clearRemote !== undefined) clearRemote();
						patch({ path: null, listing: null });
					},
					function (reason) {
						if (!aliveRef.current) return;
						patchPair({
							busy: false,
							message: { kind: 'error', text: String((reason && reason.message) || reason) },
						});
					},
				);
			};

			/**
			 * 已配对时的「重新配对」入口。
			 *
			 * ⚠️ **这是必需的回退路径，不是便利功能。** 电脑端一旦「解绑」，它会换掉
			 * 令牌并停掉隧道；而手机这边 `mobile.json` 里还留着旧令牌，`pairStatus`
			 * 会一直报「已配对」，于是输入框永远不渲染 —— 用户卡在 502，界面上无路
			 * 可走，只能手工删 `mobile.json` 或清 App 数据。补上这个入口，回退才闭环。
			 *
			 * 两步确认：解绑会停隧道、清远程会话清单，误触代价太大。
			 */
			var unpairControls = function () {
				if (pairState.confirming !== true) {
					return h(
						Button,
						{
							// ⚠️ 用 `outline` 而不是 `ghost`：ghost 在浅色面板上没有边框，
							// 真机截图里「重新配对」看起来就是一行灰字，用户认不出可点 ——
							// 而这是电脑解绑后唯一的回退入口，必须一眼看出来。
							variant: 'outline',
							size: 'sm',
							onClick: function () {
								patchPair({ confirming: true, message: null });
							},
							disabled: pairState.busy,
						},
						'重新配对',
					);
				}
				return h(
					'span',
					{ style: CSS.pairConfirm },
					h('span', { style: CSS.pairConfirmText }, '解绑后这台手机要重新输入配对码。'),
					h(
						Button,
						{
							variant: 'outline',
							size: 'sm',
							onClick: submitUnpair,
							disabled: pairState.busy,
						},
						pairState.busy ? '解绑中…' : '确定解绑',
					),
					h(
						Button,
						{
							variant: 'ghost',
							size: 'sm',
							onClick: function () {
								patchPair({ confirming: false });
							},
							disabled: pairState.busy,
						},
						'取消',
					),
				);
			};

			/**
			 * 一键刷新：把「模型 / 预设 / 目录树」三样一起重新拉一遍。
			 *
			 * 用户需求原文：「你只需要添加一个手动刷新…这三个地方的数据都可以在
			 * 选择工作区的时候，那个刷新按钮直接一键刷新这三个位置。」
			 *
			 * ⚠️ 光调桥拉数据**不够**：官方前端的模型目录是**带缓存**的
			 * （`ModelCatalogDirectory.load()` 在 `status === 'ready'` 时直接返回旧值，
			 * 见 `dsh-client-ui-model-selection/lib/client.js:100`）。所以拉完还要
			 * 让它失效重载，否则用户看到的是「点了刷新，列表没变」。
			 *
			 * `modelDirectories` 是官方那个服务的名字（`super(ctx, "modelDirectories")`），
			 * 它的 `.catalog.refresh()` 正是官方在 `llm/adapters-updated` 时走的那条路 ——
			 * 走服务实例的公开方法，不碰前端 bundle 内部类。
			 *
			 * 预设名单没有等价的服务实例（官方预设前端不是 Service），它每次
			 * 打开会话/设置面板都会重读，所以这里只更新宿主缓存即可。
			 */
			var refreshAll = function () {
				if (pairState.refreshing) return;
				patchPair({ refreshing: true, refreshNote: null });
				var target = state.tab === 'desktop' ? loc.path : '';
				// ⚠️ **「刷新」必须清掉访问缓存**，否则新列表铺回 `listing` 之后，
				// 用户一离开再回来又命中旧的那一格，看起来就是「刷新没生效」。
				// 连同整棵已缓存的树一起作废：电脑上的目录可能已经变了。
				clearDesktopDirCache();
				bridge('refreshAll', target === '' ? {} : { path: target }).then(
					function (result) {
						if (!aliveRef.current) return;
						// 目录树：直接把新列表铺回去（正在看的这一层），并写回缓存。
						if (result && result.directory && result.directory.ok) {
							desktopDirCache[target === '' ? '' : target] = result.directory.value;
							patch({ listing: result.directory.value });
						}
						// 模型：让官方目录失效重载，下一次打开菜单就是电脑的。
						if (result && result.models && result.models.ok) {
							try {
								var models = props.modelDirectories;
								if (models && models.catalog && typeof models.catalog.refresh === 'function') {
									models.catalog.refresh();
								}
							} catch (error) {
								/* 服务结构变了就只更新宿主缓存，不打扰用户 */
							}
						}
						var failed = [];
						if (result && result.models && !result.models.ok) failed.push('模型');
						if (result && result.presets && !result.presets.ok) failed.push('预设');
						if (result && result.directory && !result.directory.ok) failed.push('目录');
						patchPair({
							refreshing: false,
							refreshNote: failed.length > 0
								? { kind: 'error', text: failed.join('、') + '刷新失败，其余已更新' }
								: { kind: 'ok', text: '模型、预设、目录已刷新' },
						});
					},
					function (reason) {
						if (!aliveRef.current) return;
						patchPair({
							refreshing: false,
							refreshNote: { kind: 'error', text: String((reason && reason.message) || reason) },
						});
					},
				);
			};

			/**
			 * 「电脑」页签顶部的配对条。
			 *
			 * 已配对时只显示「连的是哪台电脑」，不显示令牌（令牌只在引擎进程里）。
			 * 未配对时给一个输入框 —— **手输是主路径**：手机 App 的
			 * `onPermissionRequest` 只放行录音权限，摄像头一律拒绝，
			 * 页面里拿不到 `getUserMedia`，扫二维码这条路走不通。
			 */
			var pairBar = h(
				'div',
				{ style: CSS.pairBar },
				pairState.paired === true
					? h(
							'span',
							{ style: CSS.pairWho, title: pairState.endpoint || '' },
							'已配对：' + (pairState.endpoint || ''),
						)
					: h(
							'span',
							{ style: CSS.pairWho },
							'还没配对。请在电脑的「设置 → 手机远程」里生成 6 位配对码，填在这里。',
						),
				pairState.paired === true
					? unpairControls()
					: h(Input, {
							value: pairState.code,
							onChange: function (event) {
								patchPair({ code: event.target.value.toUpperCase() });
							},
							onKeyDown: function (event) {
								if (event.key === 'Enter') submitPair();
							},
							placeholder: '6 位配对码',
							'aria-label': '配对码',
							maxLength: 8,
							// ⚠️ 直接给对象：`CSS` 在模块加载时已就地转成对象了
							//（见上面那个 `for (var styleKey in CSS)`），
							// 再套一层 `toStyle()` 会得到 `[object Object]`。
							style: CSS.pairInput,
						}),
				pairState.paired === true
					? null
					: h(
							Button,
							{
								variant: 'outline',
								size: 'sm',
								onClick: submitPair,
								disabled: pairState.busy || pairState.code.trim() === '',
							},
							pairState.busy ? '配对中…' : '配对',
						),
			);

			var pairMsg = pairState.message === null
				? null
				: h(
						'div',
						{
							// 同上：`CSS` 已是对象，直接拼对象即可。
							style: pairState.message.kind === 'ok'
								? Object.assign({}, CSS.pairMsg, CSS.pairOk)
								: CSS.pairMsg,
							role: pairState.message.kind === 'ok' ? undefined : 'alert',
						},
						pairState.message.text,
					);

			/** 「电脑」页签：在电脑的这个目录上开一个新对话，并跳到它。 */
			var openDesktop = function () {
				if (loc.path === '' || state.creating) return;
				patch({ creating: true, error: null });
				bridge('createSession', { path: loc.path }).then(
					function (result) {
						if (!aliveRef.current) return;
						// ⚠️ **先登记、再打开**，顺序反了就会静默失败（见 `registerLocal`）。
						return settleLocal(result).then(function () {
							if (!aliveRef.current) return;
							patch({ creating: false });
							onCancel();
							if (openSession !== undefined && result && result.sessionId) {
								openSession(result.sessionId);
							}
						});
					},
					function (reason) {
						if (!aliveRef.current) return;
						patch({ creating: false, error: String((reason && reason.message) || reason) });
					},
				).then(null, function (reason) {
					if (!aliveRef.current) return;
					patch({ creating: false, error: String((reason && reason.message) || reason) });
				});
			};

			/**
			 * 把刚在电脑上建好的会话**登记进本地会话列表**。
			 *
			 * ⚠️ **这一步不能省，而且必须在 `openSession` 之前完成。**
			 *
			 * `uiWorkspace.openSession(id)` 最终走
			 * `SessionManager.resolveTarget(id)`，它有**三道**身份检查，全不过就抛
			 * `sessions.retain: unknown session <id>`：
			 *
			 * 1. `sessions.has(id)` —— 已物化的客户端实例；
			 * 2. `summaries.some(...)` —— 客户端列表快照；
			 * 3. `addresses` —— 子 agent 地址。
			 *
			 * 而会话是**宿主**建的，客户端只能靠 `api-session/added` 这条**异步推送**
			 * 知道它 —— RPC 的响应会先到。所以直接在 RPC 回执里 `openSession()`
			 * 是**必然的竞态**：抛出的异常还被 `openSession()` 同步吞掉，界面
			 * 只表现为「点完没反应、又回到默认工作区」，控制台一句错都没有。
			 *
			 * 正解是走官方 client 自己的 `sessions.create({sessionId, cwd})`：
			 * `SessionManager.create()` 在 RPC 返回后立刻
			 * `recordMutation({kind:'placeholder'})` 把这一行写进列表快照，
			 * 于是 `openSession()` 当场就能解析到它。
			 *
			 * 幂等性是安全的：宿主侧已经建过这个 id，`createOrAdopt` 看到
			 * `agents.get(sessionId)` 活着就直接返回它，不会重复建、也不会撞 cwd。
			 *
			 * `cwd` 由宿主回执带回（手机本地的工作区路径，不是电脑路径）——
			 * 两端各自的 cwd 互不相通，只在 `sessionId` 上对齐。
			 *
			 * @param result - 宿主 `createSession` 的回执 `{sessionId, cwd}`。
			 * @returns 登记完成的 Promise（失败也 resolve，交由调用方决定下一步）。
			 */
			var settleLocal = function (result) {
				if (!result || typeof result.sessionId !== 'string') return Promise.resolve();
				// ⚠️ **先标记「这是电脑的」再登记**：模型菜单的隔离判据就靠这个标记，
				// 而用户在会话刚建好、还没发第一条消息时就会去看模型列表。
				if (markRemote !== undefined) markRemote(result.sessionId);
				var local = registerLocal === undefined
					? Promise.resolve()
					: Promise.resolve(registerLocal(result.sessionId, result.cwd)).then(null, function () {
							// 登记失败（例如 `session/writer-held`）：退一步拉全量列表，
							// 宿主那一行仍然会进来，`openSession` 同样能解析。
							return refreshSessions === undefined ? null : Promise.resolve(refreshSessions()).then(null, function () { return null; });
						});
				return local;
			};

			var makeFolder = function () {
				if (loc.path === '' || createDirectory === undefined) return;
				var name = window.prompt('文件夹名称', '新建文件夹');
				if (name === null || name === '') return;
				Promise.resolve(createDirectory(loc.path, name)).then(
					function () {
						if (aliveRef.current) patch({ path: loc.path });
					},
					function (reason) {
						if (aliveRef.current) {
							patch({ error: String((reason && reason.message) || reason) });
						}
					},
				);
			};

			var rows = entries.map(function (entry) {
				var full = pathOf(entry);
				return h(
					'button',
					{
						key: full,
						type: 'button',
						style: CSS.row,
						onClick: function () {
							patch({ path: full });
						},
					},
					// ⚠️ 图标组件只解构 `size/className/strokeWidth`，**不透传 `style`**
					// —— 上色要靠外层 span（官方图标本来就是 currentColor 描边）。
					h('span', { style: CSS.rowIcon }, h(IconFolderCloseRegular, { size: 16 })),
					h('span', { style: CSS.rowName }, nameOf(entry)),
					h('span', { style: CSS.rowChevron }, h(IconChevronRightOutlineRegular, { size: 12 })),
				);
			});

			var body = h(
				'div',
				{ style: CSS.editorScope },
				// —— 头部：标题 + 页签 + 路径行 ——
				h(
					'div',
					{ style: CSS.header },
					h('h2', { style: CSS.title }, '选择工作区目录'),
					h(SegmentedTabs, {
						label: '目录来源',
						value: state.tab,
						onChange: switchTab,
						items: [
							{ value: 'local', label: '手机' },
							{ value: 'desktop', label: '电脑' },
						],
					}),
					h(
						'div',
						{ style: CSS.crumbBar },
						h(
							Button,
							{
								variant: 'outline',
								size: 'sm',
								disabled: loc.parent === '',
								onClick: function () {
									patch({ path: loc.parent === '' ? null : loc.parent });
								},
							},
							'上一级',
						),
						h('span', { style: CSS.pathText, title: loc.path }, loc.path || '（根）'),
						// 有隐藏文件夹时才显示开关 —— 一个目录里没有 `.` 开头的东西
						// 却摆个开关，只会让人以为漏了什么。
						hiddenCount > 0 || state.showHidden === true
							? h(
									'button',
									{
										type: 'button',
										style: state.showHidden === true
											? Object.assign({}, CSS.crumbToggle, CSS.crumbToggleOn)
											: CSS.crumbToggle,
										title: state.showHidden === true
											? '隐藏以 . 开头的文件夹'
											: '显示 ' + hiddenCount + ' 个以 . 开头的隐藏文件夹',
										onClick: function () {
											patch({ showHidden: state.showHidden !== true });
										},
									},
									state.showHidden === true ? '隐藏 . 文件夹' : '显示 . 文件夹 (' + hiddenCount + ')',
								)
							: null,
						// 一键刷新：模型 / 预设 / 目录树。只在「电脑」页签下有意义
						//（手机页签的数据本来就来自本机，没什么可刷的）。
						state.tab === 'desktop' && pairState.paired === true
							? h(
									'button',
									{
										type: 'button',
										style: CSS.crumbToggle,
										title: '重新拉取电脑的模型列表、预设列表与当前目录',
										disabled: pairState.refreshing === true,
										onClick: refreshAll,
									},
									pairState.refreshing === true ? '刷新中…' : '刷新',
								)
							: null,
					),
				),
				// —— 主体：目录列表 ——
				h(
					'div',
					{ style: CSS.content },
					// 配对条只在「电脑」页签下出现 —— 「手机」页签跟配对无关。
					state.tab === 'desktop' ? pairBar : null,
					state.tab === 'desktop' ? pairMsg : null,
					state.tab === 'desktop' && pairState.refreshNote !== null
						? h(
								'div',
								{
									style: pairState.refreshNote.kind === 'ok'
										? Object.assign({}, CSS.refreshNote, CSS.refreshOk)
										: Object.assign({}, CSS.refreshNote, CSS.error),
									role: pairState.refreshNote.kind === 'ok' ? undefined : 'alert',
								},
								pairState.refreshNote.text,
							)
						: null,
					state.loading
						? h('div', { style: CSS.status, role: 'status' }, '加载中…')
						: h(
								'div',
								{ style: CSS.column },
								rows.length > 0
									? rows
									: h(
											'div',
											{ style: CSS.status },
											state.tab === 'desktop' && pairState.paired !== true
												? '配对后这里会显示电脑上的文件夹'
												: hiddenCount > 0
													? '这里只有 ' + hiddenCount + ' 个以 . 开头的隐藏文件夹'
													: '这里没有子文件夹',
										),
							),
					state.error !== null ? h('div', { style: CSS.error, role: 'alert' }, state.error) : null,
					state.tab === 'desktop'
						? h(
								'div',
								{ style: CSS.note },
								'在电脑的这个目录上开一个新对话，手机上会同步显示它的全部过程。',
							)
						: null,
				),
				// —— 底部动作栏 ——
				h(
					'div',
					{ style: CSS.footerBar },
					state.tab === 'local' && createDirectory !== undefined
						? h(
								Button,
								{
									variant: 'outline',
									icon: h(IconPlusOutlineRegular, { size: 14 }),
									onClick: makeFolder,
								},
								'新建文件夹',
							)
						: null,
					h('span', { style: CSS.footerGap }),
					h(Button, { variant: 'outline', style: CSS.footerAction, onClick: onCancel }, '取消'),
					state.tab === 'local'
						? h(
								Button,
								{
									variant: 'primary',
									style: CSS.footerAction,
									disabled: loc.path === '' || props.busy,
									onClick: pickLocal,
								},
								props.busy ? '处理中…' : '选择此文件夹',
							)
						: h(
								Button,
								{
									variant: 'primary',
									style: CSS.footerAction,
									disabled: loc.path === '' || state.creating || pairState.paired !== true,
									onClick: openDesktop,
								},
								state.creating ? '正在电脑上新建…' : '在此新建对话',
							),
				),
			);

			// 卡片本身（背景/圆角/阴影/遮罩/关闭按钮）全部交给官方 Modal；
			// 我们只覆盖尺寸与留白（见 DIALOG_CLASS）。`headless` 才不会
			// 再套一层官方自带的标题栏 —— 标题由我们放进 header 里。
			return h(
				Modal,
				{
					open: open,
					onClose: onCancel,
					title: '选择工作区目录',
					closeLabel: '关闭',
					className: DIALOG_CLASS,
					headless: true,
				},
				body,
			);
		}

		//#region 电脑流式帧 → 本地 transient
		/**
		 * 每个会话的流式折叠状态。
		 *
		 * 与官方 `ClientAssistantStream` 同构：`start` 建立 attempt、`chunk` 逐帧递增
		 * `nextIndex`、`end` 收尾。**序号不连续时直接放弃这一轮流式动画**
		 * （不抛错）——durable 事件随后会把完整内容补上，用户看到的是「没有逐字动画」
		 * 而不是「内容缺失」。这与官方 `rebaseline` 的兜底方向一致。
		 */
		var attempts = new Map();

		/** 取某会话的绑定（`eventSource` 就是浏览器侧的会话事件源）。 */
		function bindingOf(ctx, sessionId) {
			try {
				return ctx.sessions.binding(sessionId);
			} catch (error) {
				return undefined;
			}
		}

		/**
		 * 退役某会话的逐字残片。
		 *
		 * ⚠️ **必须由我们来做**：官方的清理点是
		 * `MutableSessionEventSource.settleAssistant(attemptId)`，它只被
		 * `ClientAssistantStream.acceptEventChange` 调用 —— 而那条链服务于**远程
		 * 事件流**。手机本地的 durable 事件是宿主直接 `session.append()` 进会话的，
		 * 走不到它。不清理的话，逐字残片会永久留在窗口里，与随后到达的完整消息
		 * 叠成两份。
		 *
		 * 不传 `entry`：durable 的完整消息会由会话事件流自己送进窗口，这里只负责
		 * 把残片摘掉。
		 *
		 * @param ctx - client 根上下文。
		 * @param sessionId - 会话 id。
		 * @param attemptId - 要退役的 attempt。
		 */
		function retireTransient(ctx, sessionId, attemptId) {
			if (typeof attemptId !== 'string') return;
			var binding = bindingOf(ctx, sessionId);
			if (binding === undefined || binding.eventSource === undefined) return;
			try {
				binding.eventSource.settleAssistant(attemptId);
			} catch (error) {
				/* 窗口已被替换：无需清理 */
			}
		}

		/**
		 * 把一帧电脑流式数据折进手机本地会话的 transient 行。
		 *
		 * @param ctx - client 根上下文。
		 * @param sessionId - 两端同名的会话 id。
		 * @param frame - 电脑端流式帧。
		 */
		function injectFrame(ctx, sessionId, frame) {
			if (frame.type === 'start') {
				// 上一轮若还挂着（start 帧没等到 end 帧），先把它退役，免得起重。
				var stale = attempts.get(sessionId);
				if (stale !== undefined) {
					attempts.delete(sessionId);
					retireTransient(ctx, sessionId, stale.attemptId);
				}
				attempts.set(sessionId, {
					attemptId: frame.attemptId,
					turn: frame.turn,
					step: frame.step,
					nextIndex: 0,
					gap: 0,
				});
				return;
			}
			var attempt = attempts.get(sessionId);
			if (attempt === undefined || attempt.attemptId !== frame.attemptId) return;
			if (frame.type === 'end') {
				attempts.delete(sessionId);
				// `abandoned` 不会有 durable 结算，全靠这里把残片摘掉。
				retireTransient(ctx, sessionId, attempt.attemptId);
				return;
			}
			if (frame.type !== 'chunk') return;
			if (frame.index !== attempt.nextIndex) {
				// 序号跳了：这一轮流式不可信，交给 durable 通道兜底。
				attempts.delete(sessionId);
				retireTransient(ctx, sessionId, attempt.attemptId);
				return;
			}
			attempt.nextIndex += 1;
			attempt.gap += 1;

			var binding = bindingOf(ctx, sessionId);
			if (binding === undefined) return;

			// transient 的 seq 要插在已提交事件之间：官方用 durableCursor + 1 - 1/(n+1)。
			var cursor = -1;
			var snapshot = binding.eventSource.getSnapshot();
			var entries = (snapshot && snapshot.entries) || [];
			for (var index = 0; index < entries.length; index += 1) {
				var entry = entries[index];
				if (entry.type !== 'transient' && entry.event && typeof entry.event.seq === 'number') {
					if (entry.event.seq > cursor) cursor = entry.event.seq;
				}
			}
			try {
				binding.eventSource.append({
					type: 'transient',
					event: {
						type: 'assistant/live-chunk',
						seq: cursor + 1 - 1 / (attempt.gap + 1),
						time: typeof frame.time === 'number' ? frame.time : Date.now(),
						data: {
							attemptId: attempt.attemptId,
							turn: attempt.turn,
							step: attempt.step,
							chunk: frame.chunk,
						},
					},
				});
			} catch (error) {
				attempts.delete(sessionId);
			}
		}

		/**
		 * 订阅宿主桥的全局 SSE。
		 *
		 * 全局一条：手机 WebView 对并发连接有上限，且一条连接就能服务所有会话。
		 * EventSource 自带断线重连，所以这里不做退避。
		 *
		 * @param ctx - client 根上下文。
		 */
		function subscribe(ctx) {
			var source = new EventSource(ROUTE_STREAM);
			source.addEventListener('frame', function (message) {
				var payload;
				try {
					payload = JSON.parse(message.data);
				} catch (error) {
					return;
				}
				if (!payload || typeof payload.sessionId !== 'string') return;
				injectFrame(ctx, payload.sessionId, payload.frame);
			});
			// durable 结算兜底：万一 `end` 帧丢了，残片也要被摘掉。
			source.addEventListener('settled', function (message) {
				var payload;
				try {
					payload = JSON.parse(message.data);
				} catch (error) {
					return;
				}
				if (!payload || typeof payload.sessionId !== 'string') return;
				var attempt = attempts.get(payload.sessionId);
				if (attempt === undefined) return;
				attempts.delete(payload.sessionId);
				retireTransient(ctx, payload.sessionId, attempt.attemptId);
			});
			return function () {
				source.close();
			};
		}
		//#endregion

		/** 需要宿主提供的服务：slot 注册表、工作区浏览与导航、会话数据。 */
		const inject = ['slots', 'uiWorkspace', 'sessions'];

		/**
		 * Client 插件主体：注册「手机 / 电脑」双页签的目录选择对话框 + 订阅流式帧。
		 *
		 * @param ctx - client 根上下文。
		 */
		function apply(ctx) {
			const injected = () => ({
				listDirectory: (path, signal) => ctx.uiWorkspace.listDirectory(path, signal),
				createDirectory: (path, name) => ctx.uiWorkspace.createDirectory(path, name),
				openSession: (sessionId) => ctx.uiWorkspace.openSession(sessionId),
				/**
				 * 把宿主刚建好的会话登记进本地列表。
				 *
				 * 走官方 client 的 `sessions.create({sessionId, cwd})`：
				 * `SessionManager.create()` 在 RPC 回执后立刻
				 * `recordMutation({kind:'placeholder'})` 把这一行写进列表快照，
				 * 于是 `openSession()` 当场就能解析到这个 id。
				 * **不登记就直接 `openSession()` 是竞态**，会抛
				 * `sessions.retain: unknown session` 并被静默吞掉
				 * （详见组件里 `settleLocal` 的注释）。
				 *
				 * 传 `cwd`（宿主回执里的**手机本地**路径）而不是 `workspaceId`：
				 * 宿主侧已经把这个会话挂进工作区了，这里只需要让客户端列表
				 * 认识它；带上 cwd 才能让列表把它分进正确的工作区分组。
				 *
				 * 宿主侧已建过该 id ⇒ `createOrAdopt` 看到活着的 agent 直接返回，
				 * 不会重复建。
				 *
				 * @param sessionId - 会话 id。
				 * @param cwd - 宿主回执里的本地 cwd（两端只在 id 上对齐）。
				 */
				registerLocal: (sessionId, cwd) => {
					const options = { sessionId };
					if (cwd !== undefined && cwd !== '') options.cwd = cwd;
					return ctx.sessions.create(options);
				},
				/** 兜底：拉一次全量会话列表。 */
				refreshSessions: () => ctx.sessions.refresh(),
				/**
				 * 官方的模型目录服务（`super(ctx, "modelDirectories")`）。
				 *
				 * 「刷新」按钮要让它失效重载 —— 官方目录**带缓存**，光把宿主那边的
				 * 数据换掉，前端仍然显示旧值（见 `refreshAll` 的注释）。
				 * `ctx.get` 拿不到就返回 `undefined`，组件里会跳过这一步。
				 */
				modelDirectories: ctx.get === undefined ? undefined : ctx.get('modelDirectories'),
				/**
				 * 把一个会话标记成「跑在电脑上」。
				 *
				 * 建完远程会话**当场**登记，用户不必等下一次全量拉取就能看到
				 * 正确的模型列表（见 `remoteSessionIds` 的注释）。
				 *
				 * @param sessionId - 会话 id。
				 */
				markRemote: (sessionId) => {
					if (typeof sessionId === 'string' && sessionId !== '') remoteSessionIds[sessionId] = true;
				},
				/** 解绑后把所有会话退回「本地」（电脑已经不在了）。 */
				clearRemote: () => {
					remoteSessionIds = {};
				},
			});

			// 拉一次全量远程会话清单（重启 App 后已有会话也要能正确识别）。
			loadRemoteSessionIds();

			// 把「按工作区筛模型目录」接到官方服务上。
			ctx.effect(() => patchModelDirectories(ctx), 'dsh-remote-mobile: workspace-scoped model catalog');

			// 负 priority：single slot 取 entries[0]，entries 按 priority 升序 ⇒ 最先渲染。
			const register = (name) =>
				ctx.slots.register(
					{
						name: name,
						priority: -1,
						inject: injected,
					},
					RemoteDirectoryFlow,
				);

			ctx.slots.inject('conversation.hero.workspace.directoryFlow', () =>
				ctx.slots.inject('sidebar.workspaces.directoryFlow', function* () {
					yield register('conversation.hero.workspace.directoryFlow');
					yield register('sidebar.workspaces.directoryFlow');
				}),
			);

			ctx.effect(() => subscribe(ctx), 'dsh-remote-mobile: assistant stream bridge');
		}

		exports.apply = apply;
		exports.inject = inject;
		return module.exports;
	},
});
