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
		 * ## 为什么挂载就自动打开
		 *
		 * 用户从侧栏点「AI 浏览器」，期望的是**浏览器出来**，而不是先看到一块面板、
		 * 还得再点一次按钮。原生浏览器是全屏覆盖层，一打开就把右侧栏盖住，
		 * 所以这块面板实际只在「关掉浏览器又切回侧栏」时才被看见 —— 那时它是
		 * 一张说明卡 + 一个复开入口，正是有用的东西。
		 *
		 * ⚠️ `autoOpened` 这个 ref 守卫不能省：React 的 StrictMode 会把 effect
		 *    跑两遍，没有它就是连开两次（第一次的 WebView 还没建完又建一个）。
		 *
		 * @returns {object} React 元素。
		 */
		function BrowserPane() {
			const React = require("react");
			const e = React.createElement;
			// 三态而不是布尔：初始那一帧是 "idle"，**不能**先画成失败 ——
			// 打开成功时用户会看到橙色警告闪一下再消失，像出了错。
			const [phase, setPhase] = React.useState("idle");
			const autoOpened = React.useRef(false);

			React.useEffect(() => {
				if (autoOpened.current) return;
				autoOpened.current = true;
				setPhase(openNativeAiBrowser() ? "opened" : "failed");
			}, []);

			const card = {
				padding: "14px",
				borderRadius: "12px",
				background: "rgba(127,127,147,.10)",
				border: "1px solid rgba(127,127,147,.22)",
				display: "flex",
				flexDirection: "column",
				gap: "10px",
			};
			const head = { fontSize: "13.5px", fontWeight: 600, lineHeight: 1.4 };
			const note = { fontSize: "12px", lineHeight: 1.65, opacity: 0.72 };
			const button = {
				alignSelf: "flex-start",
				padding: "8px 16px",
				borderRadius: "999px",
				border: "1px solid rgba(127,127,147,.34)",
				background: "rgba(127,127,147,.16)",
				color: "inherit",
				font: "inherit",
				fontSize: "12.5px",
				fontWeight: 600,
				cursor: "pointer",
			};

			return e("div", { style: { padding: "10px" } },
				e("div", { style: card },
					e("div", { style: head }, "AI 浏览器"),
					e("div", { style: note },
						"这台浏览器跑在你自己的手机上，登录状态会保留。AI 可以直接用它上网、登录、填表单；你也可以随时从顶部状态按钮接管。"),
					e("button", {
						type: "button",
						style: button,
						onClick: () => setPhase(openNativeAiBrowser() ? "opened" : "failed"),
					}, phase === "opened" ? "再打开一次" : "打开浏览器"),
					phase === "failed"
						? e("div", { style: Object.assign({}, note, { color: "#E0A33A" }) },
							"没能打开：这个版本的原生桥还没接上 openAiBrowser。")
						: null,
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
