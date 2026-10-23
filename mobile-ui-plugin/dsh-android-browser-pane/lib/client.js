/**
 * dsh-android-browser-pane —— 客户端半：在**官方右侧边栏**里加一项「AI 浏览器」。
 *
 * ## 为什么必须走官方槽位
 *
 * 需求是「从应用右侧边栏进入」。贴一个悬浮按钮不是那个位置：它挡内容、位置固定、
 * 与官方的面板切换逻辑无关。官方右侧栏的接缝是两个，**缺一不可**：
 *
 * | 接缝 | 作用 | 缺了会怎样 |
 * |---|---|---|
 * | `sidebarRightTabs.register({ id, kind, title, guide })` | 定义一种 tab —— 右侧栏的「+」新建列表与空态引导页会列出 `guide` 里的胶囊 | 列表里根本没有这一项 |
 * | `slots.register({ name: "sidebar.right.pane.tab", key })` | 提供这个 tab 的**正文** | tab 能开出来，但一片空白 |
 *
 * `key` 必须等于上面那个 `id` —— 座位就是按类型 id 找正文的。
 *
 * ## 两个必须知道的前提
 *
 * ① 本包 `package.json` 的 `dsh.client.inject` 是空数组（客户端半刻意不声明依赖，
 *    免得某个服务缺失就让整个插件不激活）。所以**外层 `ctx` 取不到**
 *    `slots` / `sidebarRightTabs`（会抛 `cannot get property ... without inject`），
 *    必须用 `ctx.inject([...], (scope) => ...)` 开一个子 fiber，从 `scope` 上取。
 *
 * ② 客户端模块不是 ESM，而是 `window.__ModuleLoader__.load({ id, factory })` ——
 *    由引擎提供的 `require` 才能拿到 `react` 与官方服务。
 */

window.__ModuleLoader__.load({
	id: "dsh-android-browser-pane",
	factory: (require) => {
		"use strict";
		var module = { exports: {} };
		var exports = module.exports;
		Object.defineProperty(exports, Symbol.toStringTag, { value: "Module" });

		/** 侧栏项的类型 id。**必须与 `slots.register` 的 `key` 一致。** */
		const TAB_ID = "dsh-android-ai-browser";

		/** 类型判别符。用自己独有的 kind，不去抢官方的（`browser` 是官方的 iframe 浏览器）。 */
		const TAB_KIND = "android-ai-browser";

		/**
		 * 调原生打开 AI 浏览器。
		 *
		 * @returns {boolean} 桥不存在时 false（旧 APK / 白名单漏了）。
		 */
		function openNativeAiBrowser() {
			try {
				const bridge = window.DSHAndroid;
				if (!bridge || typeof bridge.openAiBrowser !== "function") {
					console.warn("[dsh-android-browser-pane] 原生桥没有 openAiBrowser（版本过旧？）");
					return false;
				}
				bridge.openAiBrowser();
				return true;
			} catch (error) {
				console.warn("[dsh-android-browser-pane] 打开 AI 浏览器失败:", error && error.message);
				return false;
			}
		}

		/**
		 * 原生 → 前端的状态事件名。**必须与 `MainActivity.notifyBrowserPane`
		 * 里 `dispatchEvent` 的事件名逐字一致。**
		 */
		const STATE_EVENT = "dsh:browser-state";

		/**
		 * 右侧栏「AI 浏览器」面板的正文。
		 *
		 * ## 它其实是个「占位 + 覆盖」
		 *
		 * 原生 WebView **不能**放进网页 DOM 里（iframe 会被 `X-Frame-Options`
		 * / CSP 拒绝 —— 那正是这套方案存在的原因）。所以这里渲染一块铺满面板的
		 * 空 div，把它 `getBoundingClientRect()` 量出来上报给原生，原生再把
		 * WebView 摆到屏幕上同一块矩形上。
		 *
		 * ## 两个方向都必须有通知（只做单向会留下两个 bug）
		 *
		 * ```
		 * 前端 → 原生：aiBrowserLayout(rect)   面板在哪、多大
		 * 原生 → 前端：dsh:browser-state      浏览器还在不在
		 * ```
		 *
		 * 少了第二条，用户点 ✕ 之后面板会僵在「一片空白 + 不再响应点击」：
		 * 浏览器已经被原生隐藏，面板却以为还在显示，于是既不重开也不提示。
		 *
		 * ## 为什么要 ResizeObserver 而不是量一次
		 *
		 * 面板宽度会变（侧栏拖宽、旋转屏幕）、高度会变（键盘弹出、窗口调整）、
		 * 位置也会变（抽屉动画）。只量一次就会错位。
		 * 上报按 `requestAnimationFrame` 节流 —— ResizeObserver 在动画期间可能
		 * 一帧来好几次，直接转发会把原生的 layout 打爆。
		 *
		 * @returns {object} React 元素。
		 */
		function BrowserPane() {
			const React = require("react");
			const e = React.createElement;
			const slotRef = React.useRef(null);
			// idle = 还没量到有效矩形；live = 浏览器正在这块区域里显示
			const [phase, setPhase] = React.useState("idle");
			// ⚠️ 这两个必须用 ref 而不是闭包变量 / state：
			//   闭包变量在 effect 外（点击回调里）读不到，state 在 measure 那个
			//   rAF 回调里读到的是旧值。ref 两边都能读写当前值。
			const liveRef = React.useRef(false);
			const scheduleRef = React.useRef(null);
			const rectPayloadRef = React.useRef(null);

			React.useEffect(() => {
				const bridge = window.DSHAndroid;
				if (!bridge || typeof bridge.aiBrowserLayout !== "function") {
					setPhase("noBridge");
					return undefined;
				}

				let raf = 0;
				let lastPayload = "";

				/**
				 * 量出占位区域的当前矩形（CSS 视口坐标）。
				 *
				 * @returns {object|null} 尺寸无效（面板收起中）时 null。
				 */
				const rectPayload = () => {
					const node = slotRef.current;
					if (!node) return null;
					let rect = node.getBoundingClientRect();
					// 占位 div 在某些面板里高度会塌成 0（父级是 auto 高度）。
					// 那就退一步量父元素 —— 面板本身的矩形才是我们真正要的区域。
					if (rect.height < 8 && node.parentElement) {
						rect = node.parentElement.getBoundingClientRect();
					}
					if (rect.width < 8 || rect.height < 8) return null;
					return JSON.stringify({
						x: Math.round(rect.left),
						y: Math.round(rect.top),
						w: Math.round(rect.width),
						h: Math.round(rect.height),
						vw: Math.round(window.innerWidth),
						vh: Math.round(window.innerHeight),
					});
				};
				rectPayloadRef.current = rectPayload;

				const measure = () => {
					raf = 0;
					const payload = rectPayload();
					// ⚠️ 量不出有效矩形 = 面板被收起了（点右上角收起侧栏、窗口太窄）。
					//   这时要主动 detach，否则原生那块 WebView 还留在屏幕上，
					//   看起来就是「侧栏收起来了但浏览器赖着不走」。
					if (!payload) {
						if (liveRef.current) {
							liveRef.current = false;
							lastPayload = "";
							setPhase("idle");
							try {
								if (typeof bridge.aiBrowserDetach === "function") bridge.aiBrowserDetach();
							} catch (error) {
								/* 面板正在收起，什么都不做 */
							}
						}
						return;
					}
					// 只有「正在显示」时才上报矩形：浏览器没开的时候上报会让原生
					// 把它显示出来（applyEmbedRect 末尾会 setVisibility(VISIBLE)），
					// 于是用户一打开侧栏 tab，浏览器就自己冒出来了。
					if (!liveRef.current) return;
					if (payload === lastPayload) return;
					lastPayload = payload;
					try {
						bridge.aiBrowserLayout(payload);
					} catch (error) {
						console.warn("[dsh-android-browser-pane] 上报面板矩形失败:", error && error.message);
					}
				};

				const schedule = () => {
					if (raf) return;
					raf = window.requestAnimationFrame(measure);
				};
				scheduleRef.current = schedule;

				/** 原生通知浏览器开了 / 关了。 */
				const onState = (event) => {
					const visible = !!(event && event.detail && event.detail.visible);
					liveRef.current = visible;
					lastPayload = "";
					setPhase(visible ? "live" : "idle");
					// 重新变可见时要立刻把矩形补上：面板自己的尺寸没变，
					// ResizeObserver 不会再触发。
					if (visible) schedule();
				};

				window.addEventListener(STATE_EVENT, onState);

				let observer = null;
				try {
					observer = new ResizeObserver(schedule);
					if (slotRef.current) observer.observe(slotRef.current);
				} catch (error) {
					// 老内核没有 ResizeObserver：退回定时测量（下面那几拍）也够用
					console.warn("[dsh-android-browser-pane] 无 ResizeObserver，退回定时测量");
				}

				// ⚠️ 收起侧栏时占位元素的**尺寸常常不变**（只是被移出可视区 / 换了
				//   父容器），ResizeObserver 不会触发，浏览器就会赖在屏幕上不走。
				//   IntersectionObserver 专门抓「离开可视区」这一种情况。
				let viewObserver = null;
				try {
					viewObserver = new IntersectionObserver((entries) => {
						for (const entry of entries) {
							// 完全不可见 + 浏览器正开着 ⇒ 立即 detach
							if (!entry.isIntersecting && entry.intersectionRatio === 0 && liveRef.current) {
								liveRef.current = false;
								setPhase("idle");
								try {
									if (typeof bridge.aiBrowserDetach === "function") bridge.aiBrowserDetach();
								} catch (error) {
									/* 面板正在卸载，忽略 */
								}
								return;
							}
						}
						schedule();
					}, { threshold: [0, 1] });
					if (slotRef.current) viewObserver.observe(slotRef.current);
				} catch (error) {
					console.warn("[dsh-android-browser-pane] 无 IntersectionObserver，收起侧栏可能不会自动隐藏浏览器");
				}
				window.addEventListener("resize", schedule);
				window.addEventListener("orientationchange", schedule);

				// 挂载时先对齐一次原生状态：AI 可能已经用工具打开了网页，
				// 用户这时才点开 tab —— 面板得直接进入 live，而不是显示一个按钮。
				try {
					if (typeof bridge.aiBrowserState === "function" && bridge.aiBrowserState() === "visible") {
						liveRef.current = true;
						setPhase("live");
					}
				} catch (error) {
					/* 老 APK 没有这个方法：按「没开」处理即可 */
				}

				// 面板打开的动画 / 抽屉滑动不触发 resize，补几拍。
				schedule();
				const timers = [80, 200, 420, 900].map((ms) => window.setTimeout(schedule, ms));

				return () => {
					scheduleRef.current = null;
					rectPayloadRef.current = null;
					for (const t of timers) window.clearTimeout(t);
					if (raf) window.cancelAnimationFrame(raf);
					if (observer) observer.disconnect();
					if (viewObserver) viewObserver.disconnect();
					window.removeEventListener(STATE_EVENT, onState);
					window.removeEventListener("resize", schedule);
					window.removeEventListener("orientationchange", schedule);
					// tab 切走 / 面板卸载：把浏览器收起来（只隐藏，状态保留）。
					try {
						if (typeof bridge.aiBrowserDetach === "function") bridge.aiBrowserDetach();
					} catch (error) {
						/* 面板已经在拆，什么都做不了；不刷日志 */
					}
				};
			}, []);

			/**
			 * 点「打开」时的动作。
			 *
			 * 先切到 live 再上报矩形：`measure` 只在 live 时才肯上报（见上面的
			 * 注释），所以顺序不能反 —— 反了就是「点了没反应」。
			 */
			const openBrowser = () => {
				const bridge = window.DSHAndroid;
				if (!bridge || typeof bridge.openAiBrowser !== "function") return;
				// ⚠️ 顺序不能反：先把矩形推过去让原生把 WebView 摆到面板上并显示，
				//   再调 openAiBrowser 加载首页。反过来的话 WebView 会先以
				//   MATCH_PARENT（全屏）露出来一帧，用户看到一次「闪全屏」。
				const payload = rectPayloadRef.current ? rectPayloadRef.current() : null;
				if (payload && typeof bridge.aiBrowserLayout === "function") {
					try {
						bridge.aiBrowserLayout(payload);
					} catch (error) {
						console.warn("[dsh-android-browser-pane] 摆位失败:", error && error.message);
					}
				}
				openNativeAiBrowser();
				liveRef.current = true;
				setPhase("live");
				// 面板尺寸没变，ResizeObserver 不会再触发 —— 补一拍。
				if (scheduleRef.current) scheduleRef.current();
			};

			const note = { fontSize: "12px", lineHeight: 1.65, opacity: 0.72 };
			const card = {
				padding: "14px",
				borderRadius: "12px",
				background: "rgba(127,127,147,.10)",
				border: "1px solid rgba(127,127,147,.22)",
			};
			const button = {
				marginTop: "10px",
				padding: "7px 16px",
				fontSize: "13px",
				fontWeight: 600,
				borderRadius: "999px",
				border: "1px solid rgba(127,127,147,.34)",
				background: "transparent",
				color: "inherit",
				cursor: "pointer",
			};

			return e("div", {
				style: {
					position: "relative",
					width: "100%",
					height: "100%",
					// 面板高度是 auto 时给个下限，保证第一次量得到一块可用区域
					minHeight: "320px",
					boxSizing: "border-box",
				},
			},
				// 这块就是浏览器要盖住的区域。背景留空 —— 原生 WebView 会盖上来。
				e("div", {
					ref: slotRef,
					style: { position: "absolute", left: 0, top: 0, right: 0, bottom: 0 },
				}),
				phase === "live"
					? null
					: e("div", { style: Object.assign({}, card, { position: "absolute", left: 0, top: 0, right: 0 }) },
						e("div", { style: { fontSize: "13.5px", fontWeight: 600, marginBottom: "6px" } }, "AI 浏览器"),
						phase === "noBridge"
							? e("div", { style: Object.assign({}, note, { color: "#E0A33A" }) },
								"这个 APK 的原生桥还没有 aiBrowserLayout —— 面板没法把浏览器嵌进来。")
							: e("div", { style: note },
								"这台浏览器跑在你自己的手机上，登录状态会保留。AI 可以直接用它上网、登录、填表单；你也可以随时从顶部状态按钮接管。"),
						phase === "noBridge"
							? null
							: e("button", {
								type: "button",
								style: button,
								onClick: openBrowser,
							}, "打开浏览器"),
					),
			);
		}

		/**
		 * 注册侧栏项。
		 *
		 * @param {object} ctx - 客户端插件上下文。
		 */
		function install(ctx) {
			try {
				ctx.inject(["slots", "sidebarRightTabs"], (scope) => {
					scope.effect(() => scope.sidebarRightTabs.register({
						id: TAB_ID,
						kind: TAB_KIND,
						// `extension` = 来自产品外部的类型（band 最高）。它只在**自己独有的
						// kind** 上生效，不会顶掉官方的 `browser`（那是 iframe 方案）。
						priority: "extension",
						title: () => "AI 浏览器",
						guide: [{
							id: "open",
							// 排在最前：这是本 App 自己加的唯一一项，用户最容易找的位置。
							order: 1,
							title: () => "AI 浏览器",
							description: () => "用手机上的真浏览器上网，AI 可操作",
						}],
					}), "dsh-android-browser-pane: tab 类型");

					scope.effect(() => scope.slots.inject("sidebar.right.pane.tab", () => scope.slots.register({
						name: "sidebar.right.pane.tab",
						// ⚠️ key 必须等于 TAB_ID —— 座位按类型 id 找正文。
						key: TAB_ID,
					}, BrowserPane)), "dsh-android-browser-pane: tab 正文");
				});
			} catch (error) {
				// 注册失败只损失这一个侧栏项，不能连累别的插件。
				// 但**要打日志**：静默吞异常会让「侧栏里没有这一项」变成无从下手的哑谜。
				console.warn(
					"[dsh-android-browser-pane] 注册侧栏项失败:",
					error && (error.stack || error.message || error),
				);
			}
		}

		exports.apply = install;
		exports.inject = [];
		return module.exports;
	},
});
