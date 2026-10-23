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
		 * 右侧栏「AI 浏览器」面板的正文。
		 *
		 * ## 它其实是个「占位 + 覆盖」
		 *
		 * 原生 WebView **不能**放进网页 DOM 里（iframe 会被 `X-Frame-Options`
		 * / CSP 拒绝 —— 那正是这套方案存在的原因）。所以这里渲染一块铺满面板的
		 * 空 div，把它 `getBoundingClientRect()` 量出来上报给原生，原生再把
		 * WebView 摆到屏幕上同一块矩形上。
		 *
		 * 视觉结果就是：浏览器的内容正好嵌在「AI 浏览器」这个 tab 下面，
		 * 上面是官方的 tab 头，下面是浏览器自己的工具栏和页面。
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
			// idle = 还没量到有效矩形；live = 已上报并显示；noBridge = 旧版 APK
			const [phase, setPhase] = React.useState("idle");

			React.useEffect(() => {
				const bridge = window.DSHAndroid;
				if (!bridge || typeof bridge.aiBrowserLayout !== "function") {
					setPhase("noBridge");
					return undefined;
				}

				let raf = 0;
				let lastPayload = "";
				let reported = false;

				const measure = () => {
					raf = 0;
					const node = slotRef.current;
					if (!node) return;
					let rect = node.getBoundingClientRect();
					// 占位 div 在某些面板里高度会塌成 0（父级是 auto 高度）。
					// 那就退一步量父元素 —— 面板本身的矩形才是我们真正要的区域。
					if (rect.height < 8 && node.parentElement) {
						rect = node.parentElement.getBoundingClientRect();
					}
					if (rect.width < 8 || rect.height < 8) return;
					const payload = JSON.stringify({
						x: Math.round(rect.left),
						y: Math.round(rect.top),
						w: Math.round(rect.width),
						h: Math.round(rect.height),
						vw: Math.round(window.innerWidth),
						vh: Math.round(window.innerHeight),
					});
					if (payload === lastPayload) return;
					lastPayload = payload;
					try {
						bridge.aiBrowserLayout(payload);
						if (!reported) {
							reported = true;
							setPhase("live");
						}
					} catch (error) {
						console.warn("[dsh-android-browser-pane] 上报面板矩形失败:", error && error.message);
					}
				};

				const schedule = () => {
					if (raf) return;
					raf = window.requestAnimationFrame(measure);
				};

				let observer = null;
				try {
					observer = new ResizeObserver(schedule);
					if (slotRef.current) observer.observe(slotRef.current);
				} catch (error) {
					// 老内核没有 ResizeObserver：退回轮询（下面那三个定时器）也够用
					console.warn("[dsh-android-browser-pane] 无 ResizeObserver，退回定时测量");
				}
				window.addEventListener("resize", schedule);
				window.addEventListener("orientationchange", schedule);

				// 面板打开的动画 / 抽屉滑动不触发 resize，补几拍。
				schedule();
				const timers = [80, 200, 420, 900].map((ms) => window.setTimeout(schedule, ms));

				return () => {
					for (const t of timers) window.clearTimeout(t);
					if (raf) window.cancelAnimationFrame(raf);
					if (observer) observer.disconnect();
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

			const note = { fontSize: "12px", lineHeight: 1.65, opacity: 0.72 };
			const card = {
				padding: "14px",
				borderRadius: "12px",
				background: "rgba(127,127,147,.10)",
				border: "1px solid rgba(127,127,147,.22)",
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
				phase === "live" ? null : e("div", { style: Object.assign({}, card, { position: "absolute", left: 0, top: 0, right: 0 }) },
					e("div", { style: { fontSize: "13.5px", fontWeight: 600, marginBottom: "6px" } }, "AI 浏览器"),
					phase === "noBridge"
						? e("div", { style: Object.assign({}, note, { color: "#E0A33A" }) },
							"这个 APK 的原生桥还没有 aiBrowserLayout —— 面板没法把浏览器嵌进来。")
						: e("div", { style: note }, "正在把浏览器放到这里…"),
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
