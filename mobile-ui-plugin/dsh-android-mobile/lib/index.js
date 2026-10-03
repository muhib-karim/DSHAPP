/**
 * dsh-android-mobile —— 宿主半（host half）。
 *
 * ## 这个文件为什么存在
 *
 * 移动端 UI 适配的**全部实现**在客户端半（`lib/client.js`），它由
 * `@deepseek-ai/dsh-client-modules` 按本包 `package.json` 的 `dsh.client`
 * 声明自动编入浏览器侧，不需要宿主半做任何事。
 *
 * 但包必须有一个 Node 侧入口：`main` 指向的模块会被 Cordis 真正 import，
 * 而 `lib/client.js` 是浏览器代码（顶层引用 `window.__ModuleLoader__`），
 * 在 Node 里 import 会当场抛 `window is not defined`。
 *
 * 所以这里是一个**刻意空转**的宿主半：只提供插件身份，`apply` 不做任何事。
 *
 * ## 为什么不把上游的宿主半原样搬过来
 *
 * 上游（`@dsh-mobile/mobile-polish`）的宿主半承担三件事：
 *   ① index.html 注入启动画面（splash）+ 真实字节进度条
 *   ② `sessionPersistence.readGenerationHeader` 头缓存
 *   ③ `/assets` 强缓存路由
 * ②③ 是**服务端性能**优化，与移动端 UI 无关；① 的进度条靠观察浏览器
 * 对 `/plugins/??` 组合请求的 Performance 记录来估算，也属于网络层。
 * 三者都已由宿主环境（桌面端 nginx / 本 App 的原生启动遮罩）覆盖，故不搬。
 *
 * @module dsh-android-mobile
 */

export const name = 'dsh-android-mobile';

/**
 * 无需任何服务注入：客户端半自己处理挂载时机。
 * @type {string[]}
 */
export const inject = [];

/**
 * 空转：移动端 UI 的挂载完全发生在浏览器侧。
 * @param {object} ctx - Cordis 上下文（此处仅用于日志）。
 */
export function apply(ctx) {
	ctx?.logger?.info?.('[dsh-android-mobile] host half applied (UI 适配在客户端半)');
}
