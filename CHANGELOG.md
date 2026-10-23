# 更新日志

本文件记录本项目所有值得注意的变更。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

> **关于版本号**：`versionName` 表达「对外是不是同一版」，`versionCode` 才保证
> 唯一性。历史上存在多个内容不同但 `versionName` 相同的构建（如 `0.2.9-rescue`
> 对应 vc 21/22/23），那期间靠 `versionCode` 区分。自 **0.3.0** 起引入新产品能力
> 时改前两位版本号。

---

## [Unreleased]

### 计划中

- 补齐开源仓库配置（README / LICENSE / CONTRIBUTING / SECURITY / CI）

---

## [1.3.2-en] — 2026-10-23

Fork release (muhib-karim/DSHAPP), `com.mkdsh.droid` / `com.mkdsh.gecko`, versionCode 37. Built on upstream **0.3.0**
(tag `v0.3.0`); the loading screen, chat rendering, markdown, status line and engine bundle are those of 0.3.0.
Installs as an update over the fork's 1.3.1-en (same package and signing key); it cannot update an upstream-signed 0.3.0.

### Added
- **English UI**: string resources default to English (the Chinese is kept in `values-zh`), and the text the app and
  its bundled plugins show on screen is English. Logs, diagnostics and text sent to the model are unchanged.
- **DeepSeek web login** (ported from the fork's 1.3.0): first screen with "Continue with Google" / "Use API key",
  a local OpenAI-compatible bridge to chat.deepseek.com, Settings > Account (sign in again, switch, sign out) and a
  long-press launcher shortcut "DeepSeek account".
- **In-app bookmark guide** on the web-login screen: "Copy bookmarklet code" plus step-by-step instructions for
  Android Chrome and desktop browsers, what "Open in DSH" and "Copy session instead" do, and a security note.

### Fixed
- Tool calls the web model writes in DeepSeek's DSML markup (`<｜DSML｜invoke …>`, fullwidth or ASCII bars, spaces,
  `string="true|false"` parameters, several invokes) become real tool calls instead of raw text in the reply.
- An "Open in DSH" link that did not follow a sign-in started in the app is no longer rejected silently: DSH shows the
  account and asks "Use this DeepSeek session?" first.
- First launch over 1.3.1-en data: the bundled engine is unpacked once more, 1.3.1's update slots and browser overlays
  are removed, and preferences of an unexpected type are reset (sessions, credentials, workspace and the web sign-in
  are kept).

### Notes
- 0.3.0 has no settings/chat export or import (only the diagnostic bundle in the log viewer and per-session export).

---

## [0.3.0] — 2026-10-17

### 新增

- **Shizuku 权限通道** —— uid 2000 执行不再只依赖 adb。不需要 5555 端口、
  不需要 adbkey 被信任、重启后依然有效。此前对「Android 10 且只有有线 adb」的
  用户是结构性死锁。
- **插件市场**（`dsh-android-market`）+ 远程目录双插件。
- **配对服务端加固** + 管理面板；新增五组门禁测试。
- **界面改三标签页**。

### 修复

- **白屏真根因（HTTP 431）** —— auth cookie 累积导致请求头超限；同时 GeckoView
  不跟随 303 重定向。新增救援判据。
- **救援判据漏报** —— `parseGatewayFailure` 无法识别 typert gateway 整体下线的场景。
- **ELF 路径改写必须用运行时真实包名** —— 编译期常量在被改包名的安装上会指向
  不存在的目录。
- **补传诊断包跨进程互斥** —— 单靠 `AtomicBoolean` 挡不住重叠的主进程实例。
- **L1 隔离作用在过期快照上** + quarantine 有一条完全静默的 `false` 分支。
- **救援阶梯日志从不落盘** + `rescue/` 状态文件从不进诊断包。

---

## [0.2.9] — 2026-10-14

### 新增

- **内建 uid 2000 执行通道**（`shell` 工具）。
- **悬浮球** —— 可自定义外观、帧动画、视频图层。
- **`bubble` / `pet` / `ui` / `logs` 工具插件** —— 让模型自己操作悬浮窗、看屏幕、
  查日志。
- **四级救援阶梯 + 安全模式** —— 坏插件不再让 App 打不开。

### 修复

- **「界面反复出错」误报** —— 渲染重载计数没有时间窗，长期运行的内核回收会
  累计成「反复崩溃」。
- **界面重载永远判失败** —— 四层缺陷叠加，内核被回收后无法自动恢复。
- **「已停止自动恢复」后仍在重载** —— 在飞的重载链没有失效机制。
- **主线程卡死现场无栈** —— 补主线程栈快照，此前 4 份真实用户现场全部无法定位。
- **文件上传任何格式都失败** —— 上传 Worker 改到主线程。
- **号池「API 密钥无效、重启又恢复」** —— 固定槽位表耗尽。

---

## [0.2.7] — 2026-10-13

### 新增

- **内置 Web 内核版**（gecko flavor，GeckoView 140）—— 不依赖系统 WebView，
  与 system 版 `applicationId` 不同，可同装一台机器。
- **内置号池** —— TLS 层注入，密钥不落盘、不进 JS。

### 修复

- **旧 WebView 内核白屏** —— 改为把前端 bundle 按 chrome73 降级编译，而不是
  只打 polyfill（polyfill 补不了语法）。

---

## [0.2.6] 及更早

引入 `system` / `gecko` 双 flavor 之前的历史版本。核心适配工作在此阶段完成：

- Termux 环境自举（bootstrap / node / pnpm）
- **`link` 转译层** —— SELinux 不给 `link()` 权限
- **自带沙箱 runner** —— Android 没有 bwrap / Landlock
- **ripgrep / sharp / node-pty 的 android 变体**
- WebView 文件选择器与下载器
- 目录选择器接上手机存储

细节见 [`docs/agents/`](docs/agents/README.md)。

---

## 链接

- [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)
- [语义化版本](https://semver.org/lang/zh-CN/)
