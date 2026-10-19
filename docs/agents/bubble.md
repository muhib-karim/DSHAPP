# ⑫ / ⑫.1 悬浮气泡

> 本文是 [AGENTS.md](../../AGENTS.md) 的分册之一，
索引见 [docs/agents/README.md](README.md)。

### ⑫ 悬浮气泡 —— App 退到后台后唯一的交互入口

**为什么需要**：让 AI 去操作微信/设置/别的 App 时，用户**必须离开本 App**，
于是官方界面看不见了 —— 既不知道 AI 跑到哪一步，也没法中途喊停或补一句话。
通知栏能显示状态，但点一下就跳走，**没法交互**。

实现：`FloatingBubble.kt`（原生 View，约 1960 行）+ `bubble_window.xml` /
`bubble_status.xml` / `bubble_msg.xml`。**由 `MainActivity.onStart/onStop` 控制只在后台显示** ——
前台时官方界面本来就在，气泡是多余的遮挡。所以**要看到气泡必须按 HOME**。

⚠️ 例外：插件把 `state` 显式设成 `expanded` / `collapsed`（非 `auto`）时，
前台也保持显示 —— 见 ⑫.2「悬浮窗接进 DSH 插件生态」。

三种形态共用一个 window（靠 `visibility` 切换，**不重建 View**）：

| 形态 | 内容 |
|---|---|
| 收起 | 56dp 圆形头像按钮 + 14dp 状态圆点（实心绿=在跑，空心灰=空闲） |
| 贴边 | 6dp 薄片贴在屏幕边缘（把球拖出屏幕时自动进入，点一下弹回并展开） |
| 展开 | 面板：当前动作 / 会话标题 / **完整会话转录**（可滚动）/ 输入框 + 发送/停止 / 底部署缩手柄 |

#### ⚠️ 状态圆点会掉出圆外 —— 父容器是**矩形裁剪**

圆点是 `bottom|end` + 5dp 边距。⚠️ **不能贴到 1dp**：`bubble_circle_bg.xml`
是个椭圆 drawable，但**父容器的裁剪区域是矩形**，圆点放太靠角就会露在圆的
弧线之外。5dp 是实测能贴住弧线的值。

⚠️ 圆点带 **2dp 白描边**（`LayerDrawable` + `setLayerInset`）：
头像是深蓝色，纯色圆点压在上面辨识度不够。小圆点（状态气泡里的那个）**不描边**，
它本来就小，描边会把它糊掉。

#### ⚠️⚠️ `FLAG_ALT_FOCUSABLE_IM` 的语义是**反的**，别照着直觉写

**原实现的注释写着「平时不抢焦点、点输入框才聚焦」，那个理解是错的，
正是键盘弹不出来的原因。**

该标志的真实语义是「**这个窗口不需要输入法交互**」—— 它把
`FLAG_NOT_FOCUSABLE` 对输入法的豁免**又撤掉了**，所以它是**阻止**键盘的，
不是允许键盘的。

正确写法（`applyFocusFlags`）：

| 形态 | flags |
|---|---|
| 收起 | 加 `FLAG_NOT_FOCUSABLE`（纯显示，不该抢下层应用的按键焦点） |
| 展开 | **去掉** `FLAG_NOT_FOCUSABLE`，且**永不**设 `FLAG_ALT_FOCUSABLE_IM` |

⚠️ **两态都要保留 `FLAG_NOT_TOUCH_MODAL`**：它让窗口**之外**的触摸照常传给
下层应用 —— 没有它，气泡一出现整个屏幕就点不动了。

⚠️ 拖动时要**按下即 `setFlags`** 切一次（`applyTouchFlags`）：不切的话手势会被
输入法层截走。

#### ⚠️ 状态气泡是**独立窗口**，不是圆的子 View

球上方的状态标签（「正在运行命令 · sleep 10」）是**另一个 window**
（`bubble_status.xml` + `statusParams()`），不是把 56dp 的圆撑大。

⚠️ **不能撑大圆**：圆的**左上角锚点**是所有拖动/吸附数学的基准，改变尺寸会让
`curX/curY` 的语义整体错位，贴边吸附、位置持久化、夹取范围全要重算。
独立窗口只是"跟着球走"（`moveTo()` 里调 `positionStatusBubble()`），
球的位置数学一行都不用动。

- 只在**收起态 + 真有活动**时显示（`text.isEmpty() || expanded` 就隐藏）。
  展开态面板里已有同一份信息，再挂一个标签是重复的。
- ⚠️ **故意让它可点**（点一下 = 点球 = 展开面板），所以**不能加
  `FLAG_NOT_TOUCHABLE`**。代价是这一小块区域会挡住下层应用，但标签只在有进展时出现。
- ⚠️ **必须防重入**：`positionStatusBubble()` 会改 `layoutParams`，可能再触发一次
  layout 回调 —— 不加 `positioning` 守卫就是无限递归（ANR）。
- ⚠️ **首次 `addView` 后先 `alpha = 0`**，量出真实宽度后再摆位并淡入。
  否则会先按"宽度 0"居中一次、量完再跳一次，用户看到标签从球心"弹"到左边。
- ⚠️ **内容没变就不 `setText`**：状态每 5 秒刷新一次，无脑 setText 会一直触发重排，
  表现为标签文字轻微抖动。
- 竖直方向默认在球**正上方**，`curY` 太小时翻到下方。

#### ⚠️ 收起时位置会漂 —— 展开前必须记住原位

展开时为了把 300dp 宽的面板夹进屏幕，`curX` 会被改掉（贴右边时从 912 变成 180）。
收起时若只做一次 `clamp`，球会停在**面板的左上角**而不是展开前的位置 ——
用户看到的是「点一下收起，球跑别处去了」。

所以 `expand()` 里先存 `preExpandX/preExpandY`，`collapse()` 用
`clampInto(preExpandX, preExpandY, side, side)` 回去。

⚠️ `clampInto` 有**带尺寸的重载**，不是多余的：切换形态那一瞬间 `root` 的尺寸
还是旧的那个（收起瞬间布局尚未重测，`root.width` 仍是展开态的 900），
拿它算夹取范围会把球留在面板左上角。

#### 拖动用绝对坐标，不能用 `getRawX()` 的差值当坐标

`WindowManager.updateViewLayout` 的 x/y 是相对**屏幕**的左上角，而 `getRawX()`
是相对屏幕的触点。必须把位移量**累加到按下时的 window 坐标**上
（`downWinX + dx`）。直接拿 `rawX` 当 x 会让气泡瞬移到手指位置（左下角对齐触点）。

⚠️ 吸附动画**用 Handler 逐帧**，不用 `ViewPropertyAnimator` ——
后者动画的是 View 的 `translationX/Y`，而悬浮窗位置由 `layoutParams` 决定，
两者会打架（松手瞬间闪回原位）。

#### 发消息 / 停止：靠 `evaluateJavascript` 注入官方 DOM 契约

气泡自己**不发请求**，它把操作翻译成对官方页面的操作：

```
气泡(原生) ──读──> EngineService.currentStatus()/onStatus()  <──推── 页面 mpReportStatus() (5s)
     │
     └──发消息/停止──> WebView.evaluateJavascript ──注入──> Lexical 编辑器
```

- **发消息**：清空走 `__lexicalEditor.update(() => root.clear())`（Lexical 自己的
  API），插入走在 `contenteditable` 上派发 **`beforeinput`** 事件。
  ⚠️ 实测过若干方案，**只有 `beforeinput` 单行/多行/引号/反斜杠/emoji/超长文本全部正确**
  （直接改 `textContent`、`execCommand`、`InputEvent('input')` 都有各自的坏法）。
- **停止**：先验 `aria-label` 确实是「停止生成」，再点 `.uV2eYG_primary`。
  不验就点等于盲点，页面状态不同时会误触。
- ⚠️⚠️ **`BUBBLE_SEND_DELAY_MS = 500` 不能省**：`beforeinput` 是**同步**派发的，
  但 React 要等**下一次渲染**才会清掉发送按钮的 `disabled`。立刻点 = 点到禁用按钮，
  表现为「消息进了输入框但没发出去」。

#### 头像

圆形按钮里是 App 图标（`bubble_avatar.png`，五档密度 56/84/112/168/224 px），
由 `drawable-xxxhdpi/ic_launcher_foreground.png` 预裁成圆生成。

⚠️ **裁切比例取 1.20**，这是量出来的：非透明 bbox 是 `(65,65,366,367)`，
边长 ×1.00 会把头顶那撮呆毛切掉，×1.45 又缩得太小 ⇒ 1.20。
生成时用 4× 超采样再降采样（直接按目标尺寸画圆会看到锯齿）。

⚠️ `bubble_circle_bg.xml` 是白底椭圆 + 1dp `#14000000` 描边：
没有描边的话，浅色背景上圆形按钮的边界会消失。

#### 调试真机：覆盖窗口看不见，坐标要加状态栏高度

⚠️ **`uiautomator dump` 看不到覆盖窗口**（它不是 Activity 的 View 树）。要用：

```powershell
adb shell dumpsys window windows | Select-String -Context 0,2 APPLICATION_OVERLAY
```

⚠️ **`dumpsys window` 给的覆盖窗口坐标是相对窗口的，要加上状态栏/挖孔高度才是屏幕坐标。**
Huawei CDY-TN90 实测**偏移 115 px**：`屏幕 y = 窗口 y + 115`。
WebView 视口是 360×764 CSS px、dpr 3，所以真实点击坐标是
`physX = cssX * 3`、`physY = 108 + cssY * 3`。拿窗口坐标直接去 `input tap`
会点在偏上 115px 的地方 —— 本次调试中真的被它误导过。

### ⑫.1 悬浮球的四项交互契约（拖出屏幕贴边 / 状态条 2 秒自隐 / 面板拖动缩放 / 完整转录）

四项都是**原生侧**的事，页面侧一行都不用改（只有「完整转录」要页面侧**采集并上报**，
见 ⑫.2）。四条各自的判据都写在这里，改之前先读。

#### ① 状态横条「内容没变 2 秒就消失」

`STATUS_AUTO_HIDE_MS = 2000`。关键在**判据是内容的指纹，不是"收到推送"这个事件**：

```kotlin
val key = "$text\u0000$running"     // scheduleStatusAutoHide 的入参
if (key == statusKey) return         // 指纹没变 ⇒ 一条都不重排定时器
```

页面每 5 秒推一次**完全相同**的状态（`mpReportStatus()`），如果按"收到就重置计时"
实现，横条会**永远不消失**。按指纹判定后，同样的内容推一百次也只在**第一次**计时
2 秒后消失 —— 这正是「没有变化时 2 秒后自动消失」的字面语义。

- `statusAutoHideMs <= 0`（插件把 `statusAutoHideMs` 设成 0 或负数）= **永不自动隐藏**。
- 内容**变了**（换了动作/从跑变空闲）= 指纹变 = 重新计时 2 秒，横条重新出现。
- 展开态不显示横条（面板顶栏已有同一份信息），收起时 `statusKey` 清零让它重新计时。

#### ② 拖出屏幕 → 贴边折叠；点一下弹回

判定必须用**未夹取的 `rawX`**：

```kotlin
val overshoot = maxOf(-rawX, rawX + w - dm.widthPixels)
if (allowEdgeCollapse && appearance.edgeSnap && overshoot >= dp(EDGE_OVERSHOOT_DP)) enterEdge(...)
```

⚠️ **不能用 `curX`** —— 拖动过程中每一帧都经过 `clampInto()`，越界量早就被抹平了，
拿夹取后的值判定，`overshoot` **恒为 0**，贴边永远不触发。这是本轮最容易写错的一处。

- `EDGE_OVERSHOOT_DP = 28`：手指再多拖 28dp 才算「想把它扔出屏幕」，避免误触。
- 贴边态是**独立薄片** `bubbleEdge`（`EDGE_WIDTH_DP = 6`），不是把球缩窄 ——
  球的 56dp 是安装面积的语义，缩窄会让它变得点不到。
- 薄片触摸只认点击（拖动它没意义，拖了就当作"想拿回来"直接 `exitEdge()`）。
- `appearance.edgeSnap = false` 可以**关掉**这个行为（松手只会吸附到最近的左右边缘）。

#### ③ 展开面板：完整转录 + 可拖动 + 可缩放

- **拖动**：把手是 `panelHeader`（顶栏）。⚠️ 消息列表自己会滚、输入框要收文字，
  只有顶栏这一块不会跟手势打架。
- **缩放**：把手是面板**最底部独立一行** `panelResize`（28dp 高，只有右端画 grip）。
  ⚠️ 刻意不做成右下角悬浮小方块 —— 那个位置正好压在「发送」按钮上，实测点发送的
  右半边会被判成 resize。做成独立一行后这条上没有任何其它可交互元素。
- 竖直只改**转录区高度**（`panelScroll`），水平改面板宽度：面板是 `wrap_content`，
  改内容高度它自己会长高。**反过来直接给面板定高会让输入框与顶栏被挤掉** ——
  用户拖一下就把发送按钮拖没了。
- 松手落盘（`KEY_PANEL_W` / `KEY_TRANSCRIPT_H`），下次展开仍是这个尺寸；
  插件的 `panelHeight` 是**面板总高**，由 `applyConfiguredPanelHeight()` 扣掉实测的
  固定 chrome 反推转录区高度，**必须 post 到布局之后**执行（早跑会读到 0）。

#### ⚠️⚠️ `attach()` 不能省：插件在前台下发配置会被静默吞掉

`appContext` 原先**只在 `onAppBackground()` 里赋值**。后果是插件在 App **前台**调
`bubbleApply()` 时，`applyAppearance()` 里那句 `val ctx = appContext ?: return false`
直接返回 —— **改了没反应，切到后台又好了**（因为切后台那一下才把 `appContext` 填上）。
真机上表现为「插件下发失败」，但日志里能看到 `插件下发悬浮球配置: ...` 已经打印。

修法：`MainActivity.onCreate` 里调一次 `FloatingBubble.attach(this)`，它存
`applicationContext` 并 `post { reconcileVisibility(...) }`。后者顺带把「App 被杀后
重启，插件设的形态丢失」也一并修好（用持久化的 appearance 重新摆位）。

⇒ **任何新的插件侧入口都要先确认 `appContext` 已经就位**，别假设它一定是非空的。

