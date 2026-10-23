# AI 浏览器

> 本文是 [AGENTS.md](../../AGENTS.md) 的分册之一，索引见 [docs/agents/README.md](README.md)。

给模型一台**属于它自己的浏览器**：一个独立于官方界面的原生 `WebView`，
跑在用户手机上。模型可以开网页、读页面、点按钮、填表单、登录、截图；
用户可以旁观，也可以随时接管。

## 为什么不用引擎自带的侧栏浏览器

引擎内置 `@deepseek-ai/dsh-client-ui-sidebar-browser`，但它是 **iframe**。
GitHub 回 `X-Frame-Options: deny`、Google 用 CSP 禁止嵌入 —— 那些站点
在 iframe 里**连登录页都加载不出来**，而「让 AI 登录网站」恰恰是需求核心。

原生 `WebView`（`AiBrowserView`）没有这个限制，而且：

| | iframe 方案 | 原生 WebView |
|---|---|---|
| GitHub / Google | ✗ 拒绝嵌入 | ✓ 正常加载 |
| 渲染位置 | 页面里 | 本地，零延迟 |
| Cookie 持久 | 受 iframe 限制 | ✓ 原生 CookieManager |
| 服务器开销 | 无 | **无**（跑在用户手机上） |

⚠️ **不要**改成「服务端跑 Chromium + 截图流式推送」：那让每个用户都占一份
服务器 CPU/内存，而用户自己的手机明明就能渲染。

## 三块代码

```
模型 browser 工具  ──写 browser-cmd.json──▶  BrowserBridge
   (Node 进程)        $DSH_HOME 里的文件信箱      (App 进程, 后台线程)
                                                      │ post 到主线程
                                                      ▼
                                                 AiBrowserView
                                                 (第二个 WebView)
   (Node 进程)  ◀──读 browser-result.json──────  写回执 ◀── 页面 JS 结果
```

| 文件 | 是什么 |
|---|---|
| `app/src/main/java/com/dsh/android/browser/AiBrowserView.kt` | 浏览器本体：工具栏、标签页、WebView 配置、截图、下载 |
| `app/src/main/java/com/dsh/android/browser/BrowserBridge.kt` | 文件信箱 + 主线程闸门 + 页面侧 JS（读页面/点/输入/滚动/等待） |
| `mobile-ui-plugin/dsh-android-browser/` | 插件源码，注册 `browser` 工具 |

链路与 `ui` 工具（`UiProbeBridge`）**同构**，但信箱**独立**：命令可能并发发起，
共用文件会让两个调用互相覆盖请求 —— 那种失败是偶发的、最难查。

## ⚠️ 重新打包 `browser-tool.zip`

`app/src/main/assets/browser-tool.zip` 是**手打**的，没有构建脚本
（与 `uiagent-tool.zip` / `logs-tool.zip` 一样）。改了 `mobile-ui-plugin/dsh-android-browser/`
下的任何文件都必须重打，否则设备上跑的还是旧插件 —— **这个失败是静默的**。

```bash
cd mobile-ui-plugin/dsh-android-browser
rm -f ../../app/src/main/assets/browser-tool.zip
zip -X -q -r ../../app/src/main/assets/browser-tool.zip package.json lib
unzip -l ../../app/src/main/assets/browser-tool.zip     # 必须是 package.json / lib/index.js
```

⚠️ **条目必须是相对路径**（`lib/index.js`，不是 `dsh-android-browser/lib/index.js`）：
`EngineManager.ensureBrowserTool()` 按 `entry.name` 直接解到目标目录，带上顶层目录
会解成嵌套两层、插件找不到，然后**静默不挂载**。

与其它工具插件一样**不做版本戳**：包很小，每次启动覆盖写。

## 控制权仲裁（必须保留）

浏览器是**共享的**：AI 和用户操作同一个 `View`。同时操作会互相干扰
（AI 正在填表单，用户随手一点就失焦）。

- `AiBrowserView.Control { AI, USER }`，一次只有一方。
- AI 侧每次动作前 `claimAiControl()` → `webView.setOnTouchListener { _, _ -> true }`
  **吃掉所有触摸**。
- 顶部 chip 显示「AI 操作中」（橙色）/「你」（绿色），点一下切换。
- ⚠️ 别把「吃掉触摸」改成「让事件继续下发」：那会让 AI 输入到一半被用户的
  一次误触打断，而且症状是「字打进去了但提交的是空表单」，极难查。

## 页面侧 JS 的三条硬规矩

1. **读页面回的是「可交互元素清单」**（带稳定编号），不是 HTML。
   让模型猜 CSS 选择器在真实站点上十次有八次落空。
   枚举顺序由 `querySelectorAll` 的文档序保证 —— **不要排序**，
   否则「点第 3 个」会点到别的东西上。
2. **输入框必须走原生 setter + 派发 `input`/`change` 事件**，
   不能 `el.value = x`：React 受控组件把 value 劫持在内部 state 上，
   直接赋值会 DOM 与 state 不一致 —— 表现是「看得见字，提交时是空的」。
   这是 GitHub / Google / Twitter 上最典型的坑。
3. **JS 里不能出现 Kotlin 关键字**。`BrowserBridge.kt` 的 JS 是写在
   Kotlin raw string 里的，`var` 打成 `val` 编译照样过，运行时整段
   `SyntaxError`。**改完一定要逐段验证**：

```bash
# 把 BrowserBridge.kt 里五段 raw string 抽出来，展开模板后 node --check
# （模板变量按 ${jsStr(...)} → 'X'、$index → 0 之类替换）
node --check 展开后的.js
```

这个坑踩过一次：`type` 动作的 `var v` 写成了 `val v`，导致**所有输入操作**
静默失败，而编译与日志都没有任何异常。

## 已知边界

- **截图只截可见视口**，不是整页长图。`WebView.draw()` 只能拿到当前画面；
  整页拼接出来的图模型也读不清。要「看页面」用 `read`（结构化）优先。
- **下载走系统 `DownloadManager`，落 App 专属外部目录**
  （`Android/data/<包名>/files/Download/`）。写公共 Download 目录在
  targetSdk 28 下要 `WRITE_EXTERNAL_STORAGE`，为了存一张图申请存储权限不值得。
- **`blob:` / `data:` 下载不支持**（`DownloadManager` 不认这两个 scheme）。
- **最多 6 个标签**：每个标签一个独立 `WebView`，各自几十 MB，硬顶住。
- **不隔离 Cookie 数据目录**：与官方界面的 WebView 共享同一个 WebView
  数据目录（Android 默认行为），所以 Cookie 是共享的。AI 登录某网站后
  官方界面也会带上那份 Cookie。目前判断可接受；若以后发现互相污染，
  用 `WebView.setDataDirectorySuffix()` 隔离。
- **浏览器实例在 `onDestroy` 单独销毁**：它不归 `WebHost` 管，
  漏掉会一直挂着两个渲染进程。

## 验收（真机）

```bash
adb logcat -c ; adb shell am start -n com.dsh.android/.MainActivity
adb logcat -d -s DSH:I | grep -i "浏览器\|browser"
```

期望看到：

```
已安装浏览器工具插件 dsh-android-browser (NNNNN 字节)
已写入浏览器工具 overlay: browser-tool.patch.yml
[engine] 已注册 browser 工具: /data/user/0/com.dsh.android/files/home/.dsh/browser-cmd.json
AI 浏览器实例已创建
AI 浏览器已打开: https://github.com/
```

任何一行缺了，就按「入口不存在 → 挂载被跳过」顺着往上查。
