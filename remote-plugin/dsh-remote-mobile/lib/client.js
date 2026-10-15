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

		/** 宿主桥的路由（同源，直接相对路径）。 */
		var ROUTE_RPC = '/api/dsh-remote/rpc';
		var ROUTE_STREAM = '/api/dsh-remote/stream';

		/** 配色取自官方设计令牌，拿不到时回退到中性值，避免与官方 UI 撞色。 */
		var CSS = {
			overlay:
				'position:fixed;inset:0;z-index:60;display:flex;align-items:center;justify-content:center;' +
				'background:rgba(0,0,0,.42);padding:16px;',
			panel:
				'width:100%;max-width:520px;max-height:82vh;display:flex;flex-direction:column;overflow:hidden;' +
				'border-radius:14px;background:var(--dsw-alias-bg-primary,#1b1b1f);' +
				'color:var(--dsw-alias-label-primary,#e8e8ea);' +
				'box-shadow:0 18px 48px rgba(0,0,0,.38);' +
				'border:1px solid var(--dsw-alias-border-secondary,rgba(255,255,255,.1));',
			head: 'display:flex;align-items:center;gap:10px;padding:14px 16px 10px;flex:none;',
			title: 'font-size:15px;font-weight:600;letter-spacing:.01em;flex:1;min-width:0;',
			tabs: 'display:flex;gap:6px;padding:0 16px 10px;flex:none;',
			tab:
				'flex:1;height:32px;border-radius:8px;border:1px solid transparent;cursor:pointer;' +
				'font-size:13px;background:transparent;color:var(--dsw-alias-label-secondary,#9a9aa2);',
			tabOn:
				'flex:1;height:32px;border-radius:8px;cursor:pointer;font-size:13px;font-weight:600;' +
				'border:1px solid var(--dsw-alias-border-secondary,rgba(255,255,255,.14));' +
				'background:var(--dsw-alias-interactive-bg-hover,rgba(255,255,255,.07));' +
				'color:var(--dsw-alias-label-primary,#e8e8ea);',
			path:
				'display:flex;align-items:center;gap:8px;padding:8px 16px;flex:none;font-size:12px;' +
				'color:var(--dsw-alias-label-tertiary,#7d7d85);' +
				'border-top:1px solid var(--dsw-alias-border-secondary,rgba(255,255,255,.08));' +
				'border-bottom:1px solid var(--dsw-alias-border-secondary,rgba(255,255,255,.08));',
			pathText:
				'flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;',
			list: 'flex:1;min-height:120px;overflow:auto;padding:6px 8px;',
			row:
				'display:flex;align-items:center;gap:10px;width:100%;text-align:left;padding:9px 10px;' +
				'border-radius:8px;border:none;background:transparent;cursor:pointer;font-size:14px;' +
				'color:var(--dsw-alias-label-primary,#e8e8ea);',
			foot: 'display:flex;gap:8px;padding:12px 16px;flex:none;justify-content:flex-end;',
			btn:
				'height:34px;padding:0 16px;border-radius:8px;cursor:pointer;font-size:13px;' +
				'border:1px solid var(--dsw-alias-border-secondary,rgba(255,255,255,.16));' +
				'background:transparent;color:var(--dsw-alias-label-primary,#e8e8ea);',
			btnPrimary:
				'height:34px;padding:0 16px;border-radius:8px;cursor:pointer;font-size:13px;font-weight:600;' +
				'border:none;background:var(--dsw-alias-state-business-primary,#4d6bfe);color:#fff;',
			note: 'padding:10px 16px;font-size:12px;color:var(--dsw-alias-label-tertiary,#7d7d85);',
			err: 'padding:10px 16px;font-size:12px;color:var(--dsw-alias-state-error-primary,#ff6b6b);word-break:break-all;',
		};

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

			var statePair = react.useState({
				tab: 'local',
				path: null,
				listing: null,
				loading: false,
				error: null,
				creating: false,
			});
			var state = statePair[0];
			var setState = statePair[1];

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

			// 每次打开都回到「手机」页签，避免上次停在电脑目录造成困惑。
			react.useEffect(
				function () {
					if (open) patch({ tab: 'local', path: null, error: null });
				},
				[open, patch],
			);

			// 加载当前页签、当前路径的目录列表。
			var tab = state.tab;
			var path = state.path;
			react.useEffect(
				function () {
					if (!open) return undefined;
					patch({ loading: true, error: null });
					var load =
						tab === 'desktop'
							? bridge('listDesktop', path === null ? {} : { path: path })
							: Promise.resolve(listDirectory(path === null ? undefined : path));
					load.then(
						function (listing) {
							if (!aliveRef.current) return;
							patch({ loading: false, listing: listing });
						},
						function (reason) {
							if (!aliveRef.current) return;
							patch({
								loading: false,
								listing: null,
								error: String((reason && reason.message) || reason),
							});
						},
					);
					return undefined;
				},
				[open, tab, path, listDirectory, patch],
			);

			if (!open) return null;

			var loc = locationOf(state.listing);
			var entries = entriesOf(state.listing).filter(isDir);

			var switchTab = function (next) {
				if (next === state.tab) return;
				patch({ tab: next, path: null, listing: null, error: null });
			};

			/** 「手机」页签：采纳为本地工作区。 */
			var pickLocal = function () {
				if (loc.path === '') return;
				onPicked(loc.path);
			};

			/** 「电脑」页签：在电脑的这个目录上开一个新会话，并跳到它。 */
			var openDesktop = function () {
				if (loc.path === '' || state.creating) return;
				patch({ creating: true, error: null });
				bridge('createSession', { path: loc.path }).then(
					function (result) {
						if (!aliveRef.current) return;
						patch({ creating: false });
						onCancel();
						if (openSession !== undefined && result && result.sessionId) {
							openSession(result.sessionId);
						}
					},
					function (reason) {
						if (!aliveRef.current) return;
						patch({ creating: false, error: String((reason && reason.message) || reason) });
					},
				);
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
						onMouseEnter: function (event) {
							event.currentTarget.style.background =
								'var(--dsw-alias-interactive-bg-hover,rgba(255,255,255,.07))';
						},
						onMouseLeave: function (event) {
							event.currentTarget.style.background = 'transparent';
						},
						onClick: function () {
							patch({ path: full });
						},
					},
					h('span', { style: { opacity: 0.55, flex: 'none' } }, '\u25B8'),
					h(
						'span',
						{
							style: {
								flex: '1',
								minWidth: 0,
								overflow: 'hidden',
								textOverflow: 'ellipsis',
								whiteSpace: 'nowrap',
							},
						},
						nameOf(entry),
					),
				);
			});

			return h(
				'div',
				{
					style: CSS.overlay,
					onClick: function (event) {
						if (event.target === event.currentTarget) onCancel();
					},
				},
				h(
					'div',
					{ style: CSS.panel, role: 'dialog', 'aria-label': '选择工作区目录' },
					h(
						'div',
						{ style: CSS.head },
						h('div', { style: CSS.title }, '选择工作区目录'),
						h(
							'button',
							{ type: 'button', 'aria-label': '关闭', style: CSS.btn, onClick: onCancel },
							'关闭',
						),
					),
					h(
						'div',
						{ style: CSS.tabs },
						h(
							'button',
							{
								type: 'button',
								style: state.tab === 'local' ? CSS.tabOn : CSS.tab,
								onClick: function () {
									switchTab('local');
								},
							},
							'手机',
						),
						h(
							'button',
							{
								type: 'button',
								style: state.tab === 'desktop' ? CSS.tabOn : CSS.tab,
								onClick: function () {
									switchTab('desktop');
								},
							},
							'电脑',
						),
					),
					h(
						'div',
						{ style: CSS.path },
						h(
							'button',
							{
								type: 'button',
								style: CSS.btn,
								disabled: loc.parent === '',
								onClick: function () {
									patch({ path: loc.parent === '' ? null : loc.parent });
								},
							},
							'上一级',
						),
						h('div', { style: CSS.pathText, title: loc.path }, loc.path || '（根）'),
					),
					state.error !== null ? h('div', { style: CSS.err, role: 'alert' }, state.error) : null,
					state.loading
						? h('div', { style: CSS.note }, '加载中…')
						: h(
								'div',
								{ style: CSS.list },
								rows.length > 0 ? rows : h('div', { style: CSS.note }, '这里没有子文件夹'),
							),
					state.tab === 'desktop'
						? h(
								'div',
								{ style: CSS.note },
								'在电脑的这个目录上开一个新对话，手机上会同步显示它的全部过程。',
							)
						: null,
					h(
						'div',
						{ style: CSS.foot },
						state.tab === 'local' && createDirectory !== undefined
							? h('button', { type: 'button', style: CSS.btn, onClick: makeFolder }, '新建文件夹')
							: null,
						state.tab === 'local'
							? h(
									'button',
									{
										type: 'button',
										style: CSS.btnPrimary,
										disabled: loc.path === '' || props.busy,
										onClick: pickLocal,
									},
									props.busy ? '处理中…' : '选择此文件夹',
								)
							: h(
									'button',
									{
										type: 'button',
										style: CSS.btnPrimary,
										disabled: loc.path === '' || state.creating,
										onClick: openDesktop,
									},
									state.creating ? '正在电脑上新建…' : '在此新建对话',
								),
					),
				),
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
			});

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
