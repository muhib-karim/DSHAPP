window.__ModuleLoader__.load({
	id: "dsh-android-mobile",
	factory: (require) => {
		"use strict";
		var module = { exports: {} };
		var exports = module.exports;
		Object.defineProperty(exports, Symbol.toStringTag, { value: "Module" });

				// ── CSS ──────────────────────────────────────────────────────────────
		// Scoped styles injected as one style tag (same pattern as ui-responsive).
		// Class names below are the stable ones observed in the 0.13.0-preview
		// snapshot bundles (wSkVaW_* = conversation root, uV2eYG_* = composer
		// StatsLine, Nqubda_* = cordis panel). Where possible we key off ARIA/data
		// attributes instead so the rules survive minor bundle changes.
		const CSS = `
/* 4. 去掉全局点击/聚焦蓝色闪烁（WebView tap highlight + focus ring） */
[data-mobile] * {
  -webkit-tap-highlight-color: transparent !important;
}

/* 4c. 长按手势选择控制：
     全局禁用 touch-callout + user-select（消灭长按弹出系统菜单和文字选中）。
     例外：消息区域 .wSkVaW_viewArea 和输入元素允许原生长按行为。
     非消息区域（header/sidebar/settings等）保持 disabled，体验接近原生 app。 */
[data-mobile] * {
  -webkit-touch-callout: none !important;
  user-select: none !important;
  -webkit-user-select: none !important;
}
[data-mobile] .wSkVaW_viewArea,
[data-mobile] .wSkVaW_viewArea * {
  -webkit-touch-callout: default !important;
  user-select: text !important;
  -webkit-user-select: text !important;
}
[data-mobile] input, [data-mobile] textarea, [data-mobile] [contenteditable] {
  -webkit-touch-callout: default !important;
  user-select: text !important;
  -webkit-user-select: text !important;
}

/* 4b. 隐藏所有滚动条（移动端太丑） */
[data-mobile] *::-webkit-scrollbar {
  display: none !important;
  width: 0 !important;
  height: 0 !important;
}
[data-mobile] * {
  scrollbar-width: none !important;
}

/* 抽屉让出状态栏（官方侧栏顶部 logoRow 需要安全区） */
[data-mobile] .pI_x6G_sidebarCol {
  padding-top: var(--dsh-android-system-top, 0px);
}
/* 内容列的顶部让位**不在 CSS 里做**（修复 58 的第一版就是死在这里，别再走回去）：
   ============================================================
   全面屏打开后内容列必须让出状态栏（窗口 content 由 [0,108] 变 [0,0]，
   状态栏直接压住汉堡与聊天页 header，实测汉堡 rect.top=3.75）。

   第一版在这里加了一条
     [data-mobile] .pI_x6G_centerCol { padding-top: var(--dsh-android-system-top, 0px) }
   结果**完全不生效**：同一个属性上已经有一条修复 66 加的
     [data-mobile] .pI_x6G_centerCol { padding-top: var(--mp-float-nav-h, 0px) !important }
   它带 !important，后加的规则压不过它（实测 getComputedStyle 仍是 0px）。

   也不能在 CSS 里写成 max(两条变量之和 / 取大者)：max() 要 Chrome 79，而本 App
   的目标内核是 **chrome74**（见 ⑯），老内核上整条声明会失效 ⇒ 全屏下顶部被啃掉，
   且只在老设备上复现。

   正解是**并进 --mp-float-nav-h 的语义**：让 mpSyncFloatNavGap() 在算它的时候
   把状态栏高度也纳入（JS 里 Math.max 不受内核版本限制）。那条 !important 规则
   于是不用动，两条让位需求由同一个变量统一表达。 */
[data-mobile] button:focus, [data-mobile] button:focus-visible, [data-mobile] a:focus,
[data-mobile] [tabindex]:focus, [data-mobile] input:focus,
[data-mobile] textarea:focus, [data-mobile] select:focus {
  outline: none !important;
  box-shadow: none !important;
}

/* 2. 消息操作栏：隐藏点赞/有问题反馈按钮（保留 复制/分支/时间/用时） */
[data-mobile] button[aria-label="好的回答"],
[data-mobile] button[aria-label="有问题的回答"] {
  display: none !important;
}

/* 1. 会话头部：标题长时右侧 actions/utilities 溢出重叠（undo 快照徽章 vs session log 按钮）→ 换行。
   容器是 wSkVaW_headerActions（模式徽章+快照徽章+utilities 并排超出容器宽，flex nowrap 导致重叠）。
   让 headerActions 在 titleCluster 内收缩、换行，避免溢出与 headerUtilities 重叠。 */
[data-mobile] .wSkVaW_titleRow {
  flex-wrap: wrap;
  row-gap: 2px;
  align-items: flex-start;
}
[data-mobile] .wSkVaW_titleCluster {
  flex: 1 1 0%;
  min-width: 0;
  overflow: hidden;
}
[data-mobile] .wSkVaW_headerActions {
  flex-wrap: wrap;
  row-gap: 4px;
  flex: 1 1 auto !important;
  min-width: 0 !important;
  width: 100% !important;
}
[data-mobile] .wSkVaW_headerUtilities {
  margin-left: 0 !important;
}
[data-mobile] .wSkVaW_titleRow {
  gap: 2px 4px;
}
[data-mobile] .wSkVaW_headerUtilities {
  margin-left: 0 !important;
}

/* 3. composer StatsLine：隐藏 首token/tok/s 与 输入/输出 tokens 段（JS 按文本处理，
    这里兜底隐藏其分隔符与目标段）。
    3b. 同时收窄左右内边距 32px → 12px，给「轮·步 / LLM·工具 / 缓存」三组缩写文案
    留出余量（可用 334px vs 实测 270.6px），**保持单行不换行**。
    只加 [data-mobile] 前缀 → 桌面端布局完全不受影响。 */

/* 7. 设置页两级化（移动端）：横向滑动导航 → 竖向列表；一级=列表，二级=内容。
   NOTE: 全部用 JS inline style，不用 CSS 规则 —— 实测本 WebView（Chromium 114 +
   ui-responsive 的 html zoom:3）下，任何匹配设置 dialog 子树的 CSS 规则都会把
   dialog 推到屏幕外（x=-301，关闭按钮变 0 尺寸，无法关闭）。 */

/* 5. 审批面板（cordis）：移动端固定到 composer 上方 —— 定位全在 JS（positionApprovalPanel），
   此处不再注入 CSS（同上，避免 media CSS 触发布局 bug）。 */

/* 审批有内容时自动打开的遮罩提示：面板上缘阴影强化层级 */
[data-mobile] .Nqubda_panel[data-mp-approval] {
  box-shadow: var(--dsw-shadow-lv3, 0 8px 30px rgba(0, 0, 0, .25)) !important;
}

/* ══ 8. 移动端外壳（0.1.5 融合版）══════════════════════════════════════════
   0.1.5 官方 ui-layout 的窄屏形态是「把侧栏压成 56px 图标条」，正文只剩 334px；
   官方自带的抽屉/手机顶栏来自已被停用的 @dsh-android/dsh-client-ui-responsive
   （它在新版的槽位校验下会抛异常 → 整页白屏，故弃用）。

   这里改用「保留官方骨架 + 补移动端外壳」的融合方案：
     · 正文占满全宽（frame 侧栏轨道归零）
     · 侧栏（官方渲染的完整 280px 形态）改为固定定位浮层，.mp-drawer-open 时滑入
     · 左上角自建汉堡按钮触发；遮罩点击关闭；点会话行/新建会话自动收起
     · 官方 header（标题/模式/⋯ 菜单/右侧栏按钮）与 对话/轨迹 tabs 原样保留
   开合状态仍由官方 .hHd-Xa_toggle 驱动（不碰 React state）。 */
[data-mobile] .pI_x6G_frame {
  grid-template-columns: 0px minmax(0, 1fr) 0px !important;
}
/* ⚠️ 侧栏一旦 position:fixed 就脱离网格流，其后的正文列会自动落进第 1 条
   （0px）轨道 → 正文宽度变成 0。必须给正文/右栏显式指定轨道列。 */
[data-mobile] .pI_x6G_centerCol {
  grid-column: 2 !important;
}
[data-mobile] .pI_x6G_rightbarCol {
  grid-column: 3 !important;
}
/* ⚠️ 抽屉用 left 做动画，绝不用 transform：本插件/官方的浮层大量使用
   position:fixed，而 fixed 元素若位于带 transform 的祖先内，会以该祖先为
   包含块 → 设置弹窗（fixed 100vw）会被裁进 300px 的抽屉里（实测踩到）。
   left 动画不产生包含块，浮层仍相对视口定位。 */
[data-mobile] .pI_x6G_sidebarCol {
  position: fixed !important;
  top: 0;
  bottom: 0;
  left: calc(-1 * min(300px, 85vw) - 40px);
  width: min(300px, 85vw) !important;
  z-index: 40;
  transition: left .22s var(--ds-ease-in-out, ease);
  box-shadow: 0 0 30px rgba(0, 0, 0, .18);
}
[data-mobile] .pI_x6G_sidebarCol.mp-drawer-open {
  left: 0;
}
/* ⚠️ 修复 26 起**不再**给抽屉加 .mp-drawer-closing —— 规则保留作回滚安全网。
   为什么弃用：visibility **会继承给子元素**，而设置弹窗的 DOM 就在抽屉内部
   （实测 col.contains(dialog) === true）→ 弹窗被连坐隐藏 200ms，露出底层对话界面。
   且它本就是多余的：修复 25 后抽屉是"先滑出屏幕、再点官方 toggle"，
   官方 56px 图标条的 morph 发生在 left=-340 的屏幕外，本来就看不见。 */
[data-mobile] .pI_x6G_sidebarCol.mp-drawer-closing {
  visibility: hidden !important;
  transition: none !important;
}
.mp-drawer-mask {
  position: fixed;
  inset: 0;
  z-index: 39;
  background: var(--dsw-alias-overlay-mask, rgba(0, 0, 0, .35));
  opacity: 0;
  pointer-events: none;
  transition: opacity .22s var(--ds-ease-in-out, ease);
}
/* ⚠️ 修复 39：打开态**必须**限定在 [data-mobile] 下。
   宽屏下 .pI_x6G_sidebarCol 没有浮层化（仍是 static），若遮罩可点可见，
   就会盖住侧栏与整页 → 变暗、点不动、误关抽屉（啾灵 2026-09-16 反馈）。
   基类已是 opacity:0 + pointer-events:none，所以这里加上作用域 = 兜底：
   即便 .mp-drawer-open 被以任何方式误加，宽屏下也不会变暗、不会吃点击。 */
[data-mobile] .mp-drawer-mask.mp-drawer-open {
  opacity: 1;
  pointer-events: auto;
}
/* 修复 25b：关闭时遮罩比抽屉（.22s）淡得更快，让对话区先亮起来，
   抵消"抽屉滑走露出未淡完遮罩"造成的短暂下沉（实测 -16 → 目标 <8）。 */
.mp-drawer-mask.mp-drawer-closing {
  transition: opacity .13s var(--ds-ease-in-out, ease) !important;
}
/* 汉堡按钮：固定左上，浮在正文之上（自建元素，不注入 React 子树） */
.mp-nav-btn {
  /* ⚠️ 默认 display:none，仅 [data-mobile] 下显示（桌面端不受影响）。
     注意不要在同一条规则里再写 display，后写会覆盖前写。 */
  display: none;
  position: fixed;
  left: 6px;
  top: calc(var(--dsh-android-system-top, 0px) + 6px);
  z-index: 38;
  width: 40px;
  height: 40px;
  padding: 0;
  border: none;
  border-radius: 10px;
  background: transparent;
  color: var(--dsw-alias-label-primary, #222);
  align-items: center;
  justify-content: center;
  cursor: pointer;
}
[data-mobile] .mp-nav-btn {
  display: inline-flex;
}
.mp-nav-btn.mp-press {
  background: var(--dsw-alias-interactive-bg-hover, rgba(127, 127, 127, .15));
}
/* 官方 header 给汉堡让出左侧空间（标题不再被压住） */
[data-mobile] .wSkVaW_header {
  padding-left: 52px !important;
}

/* ══ 修复 30：头部单行化（2026-09-16 啾灵拍板方案）══════════════════════
   官方 0.1.5 头部两行（标题行 + 对话/轨迹 tabs）在手机上占 ~85px。改为：
   ① tabs 行隐藏，「轨迹」入口移入 ⋯ 菜单（mpInjectTraceItem）
   ② 标题居中，模式徽章（创造模式等）显示在标题下方
   ③ 汉堡 / ⋯ / 右侧栏按钮绝对定位于 header 两角
   实测 header 高度 85px → 55px。⚠️ 官方 min-height:76px 写死，必须覆盖。 */
[data-mobile] .wSkVaW_header {
  position: relative !important;
  /* ⭐ 修复 30b：header 必须 self-elevate。修复 30 的 position:relative 让 header
     变成层叠上下文，而消息流容器在 DOM 里排在 header 之后（同层级后画者胜）→
     ⋯ 菜单的 z-index:100 被锁死在 header 上下文内，整个 header 被消息流压住：
     菜单透明（背景被消息文字盖住）且点不动（啾灵真机 2026-09-16 实测）。
     给 header 一个显式 z-index 让它整体高于消息流，菜单即恢复最上层。 */
  z-index: 30 !important;
  /* 修复 57：左右内边距给两端控件让出真实空间（内容盒，故用 border-box）。
     修复 59：左内边距**不再写死**，改由 mpSyncHeaderCenter() 按右侧控件
     的运行时真实占位反推（左留白 = 右占位 ⇒ 标题簇中心恒落在屏幕中线）。
     这里的 50px 只是 JS 没跑起来时的兜底值。 */
  box-sizing: border-box !important;
  padding-left: var(--mp-hdr-padl, 50px) !important;
  padding-right: 12px !important;
  padding-top: 4px !important;
  padding-bottom: 4px !important;
  min-height: 0 !important;
  height: auto !important;
}

/* ══ 修复 61：顶部标题栏高度 +25% ═══════════════════════════════════════
   需求（用户 2026-10-03）：聊天界面顶部标题栏调高 25%。

   ⚠️ 这里**不要写死像素值**。第一版写死 81.25px（= 修复 57 实测的
      65px × 1.25），但「65px」只是 iPhone 13 一种状态下的实测值：
      官方 header 是内容驱动高度（修复 30 把官方 min-height:76px 覆盖成 0），
      自然高度随「模式徽章有无 / 标题是否换行 / 横竖屏」在 55~65px 浮动，
      写死就会在某些状态下远超 25%（55px 时变成 +48%）——真机反馈「有点高过头」
      就是这么来的。

   所以改为**运行时自校准**：由 mpSyncHeaderHeight() 先量出「未加高」时的
   自然高度，再写 --mp-hdr-h = 自然高度 × MP_HDR_SCALE。任何设备、任何
   方向、任何状态下都精确 +25%。
   下面的 0px 只是 JS 没跑起来时的兜底 —— 等于不加高，行为同官方。

   为什么这样写：
     · 官方 header 是 display:grid，隐式行 auto、align-content 默认
       stretch → 容器给了 min-height 后行会撑满，而 titleRow 自身是
       align-items:center 的 flex，内容自动垂直居中：只加高度，
       不需要动布局，也不影响左右两端（汉堡 / ⋯ / utilities / corner）。
     · 用 min-height 而非 height：标题换行、大字号或横屏时仍能自然变高，
       不会把内容裁掉（配合上面的 height:auto）。
     · 本条必须排在修复 30 的 min-height:0 之后：同为
       [data-mobile] .wSkVaW_header + !important，靠书写顺序取胜。
     · 想调比例只动 JS 里的 MP_HDR_SCALE（1.25 = +25%），不要回来写死像素。 */
[data-mobile] .wSkVaW_header {
  min-height: var(--mp-hdr-h, 0px) !important;
}

/* ══ 修复 62：恢复标题下方的「当前模式 / Agent 预设」徽章（2026-10-03 反馈）═
   现象：手机端聊天页标题下方不再显示当前对话用的是哪个模式（预设名 +
         图标），桌面端却有 —— 用户反应「好像少了点啥」。

   根因（**官方行为，不是本插件引入的**）：
     · 徽章由 @deepseek-ai/dsh-client-ui-agent-preset 注册进
       conversation.session.header.actions 槽位（AgentPresetLabel，order -10），
       渲染为 <span class="SVAs4q_label" title="…">图标 + 预设名</span>；
     · 它自带的样式里有一条容器查询：
         @container (width<=540px){ .SVAs4q_label{ display:none } }
       而官方 .wSkVaW_titleRow 设了 container-type:inline-size ——
       手机竖屏 titleRow 内联尺寸 ≈ 390 − 50(padL) − 12(padR) ≈ 328px，
       必然命中 ≤540px → 官方按「窄屏省略」把它隐藏。桌面端 titleRow
       宽 >540px，所以一直可见，这也解释了为什么只有手机缺。

   为什么放回来是安全的：本插件修复 30 的单行化头部本来就是
   「标题 + 徽章」两层结构，headerActions 就在 titleCluster 这个列容器里、
   被 align-items:center 居中 —— 位置早就留好了，只需要把 display 还原，
   不需要动任何布局。

   ⚠️ SVAs4q_label 是构建期哈希类名，官方升级可能变；所以同时给一条
      结构性兜底（headerActions 里带 title 的 span）。同槽位的其他条目
      （subagent-catalog / job-list）都是按钮，不会被这条误伤。 */
[data-mobile] .wSkVaW_headerActions .SVAs4q_label,
[data-mobile] .wSkVaW_headerActions span[title] {
  display: inline-flex !important;
}
[data-mobile] .wSkVaW_tabs {
  display: none !important;
}

/* ══ 修复 34：轨迹页详情面板底部被输入框挡住（2026-09-16 啾灵反馈）══════════
   现象：轨迹页点开某条数据后，右侧详情面板一直延伸到屏幕最底部，
        最后几行（吞吐量等）被悬浮的输入框压住、看不到也点不到。
   根因：官方详情面板用 absolute + top:0/bottom:0 撑满 .Y0dWHa_split；
        它只在**内层滚动容器**上用了 padding-bottom = --dsh-trajectory-bottom-clearance
        （= composer 高度 + 16px），面板本体没有这个留白。
        ⚠️ 实测该变量在移动端是**正确**的（--dsh-composer-height 为 128px），
        所以不是变量丢失，而是「面板本体不吃这个 padding」。
   修法：在窄屏下给详情面板本体补同样的底部留白，让它停在输入框上方。
        用官方的同一个变量、同一套语义（+16px 呼吸位），不写死像素。 */
[data-mobile] .Y0dWHa_details {
  bottom: var(--dsh-trajectory-bottom-clearance, calc(var(--dsh-composer-height, 152px) + 16px)) !important;
}
/* 概览/详情内部滚动区也补一份，避免内容贴着面板底缘 */
[data-mobile] .Y0dWHa_overviewPreview,
[data-mobile] .Y0dWHa_detailBody {
  padding-bottom: 12px !important;
}
/* ══ 修复 57：长标题压住右上角控件（2026-09-28 啾灵真机反馈）══════════════
   现象（iPhone/窄屏）：会话标题较长时，标题右端压到 header 右侧的
     「用文件资源管理器打开 / 更多打开方式 / ⋯ / 右侧栏」按钮上。
   实测（390px，标题「DroidMind 安装教程与部署」）：
     标题簇 99..291，而 headerUtilities 从 267 开始 → 重叠 24px；
     标题越长越严重（详解v2 → 49px，超长 → 56px）。

   根因（本插件自己引入的，非官方问题）：
     ① 修复 30 把 headerUtilities / headerCorner 改成 position:absolute
        脱离文档流 → 它们不再参与 flex 挤压，标题也就不再被它们「顶住」；
     ② titleCluster 被设成 max-width:72vw(=280px) 且整屏居中，
        而右侧控件实际起点是 267px → 可用区只有 221px，却允许排到 280px。
     官方原本是 flex 流内挤压 + 省略号，根本不会重叠。

   修法（去掉「绝对定位 + 写死像素」这套补丁，回到官方流内语义）：
     ① headerUtilities / headerCorner 恢复 position:static，回到 titleRow
        的 flex 流内 —— 由浏览器自动挤压，右侧宽度不再写死；
     ② header 用 border-box + 左右内边距给两端让位（左给汉堡、右给呼吸位）；
     ③ titleCluster 改为 flex:1 1 0 + min-width:0 + overflow:hidden，
        crumb 链 min-width:0 且 text-overflow:ellipsis →
        标题只在中间可用区里省略，永不越界。

   实测结果（iPhone 13 / 390px，五种标题长度）：
     短「你好」→ 不截断；长标题 → 省略号；重叠 0px；header 高度仍 65px；
     320px 极窄屏同样 0 重叠、无横向溢出。
     桌面端（1440/1024）逐项几何与改动前**完全一致**（data-mobile 未命中）。 */
[data-mobile] .wSkVaW_titleRow {
  display: flex !important;
  justify-content: center !important;
  width: 100% !important;
  margin: 0 !important;
  flex-wrap: nowrap !important;
  box-sizing: border-box !important;
  align-items: center !important;
  gap: 0 4px !important;
  padding: 0 !important;
}
[data-mobile] .wSkVaW_titleCluster {
  display: flex !important;
  flex-direction: column !important;
  align-items: center !important;
  justify-content: center !important;
  gap: 0 !important;
  /* 修复 57：由 max-width:72vw 改为「吃掉剩余空间并可收缩」，
     让两侧控件的真实宽度决定标题的可用区。 */
  flex: 1 1 0% !important;
  min-width: 0 !important;
  max-width: none !important;
  overflow: hidden !important;
}
[data-mobile] .wSkVaW_crumbs {
  min-width: 0 !important;
  max-width: 100% !important;
  overflow: hidden !important;
  justify-content: center !important;
}
[data-mobile] .wSkVaW_crumbSeg {
  min-width: 0 !important;
  overflow: hidden !important;
}
[data-mobile] .wSkVaW_crumb {
  /* 修复 59：标题最大宽度再收一档（用户反馈「标题宽度可以再限定一下」）。
     上限 = min(可用宽, 62vw) → 390px 屏约 242px；长标题更早出现省略号，
     视觉上更「居中成块」，也避免标题贴着右侧按钮。 */
  max-width: min(100%, 62vw) !important;
  min-width: 0 !important;
  overflow: hidden !important;
  text-overflow: ellipsis !important;
  white-space: nowrap !important;
}
[data-mobile] .wSkVaW_headerActions {
  flex-wrap: nowrap !important;
  width: auto !important;
  flex: 0 0 auto !important;
}
/* 汉堡按钮：由 fixed 改为锚定 header 左上（mpBuildShell 会把它挪进 header）。
   仍用 absolute 是无害的：header 的左内边距已为它留出 50px，
   它不参与挤压，也就不影响标题的省略计算。

   ⚠️ 修复 62：top 用 max(50%, 20px)，不是裸 50%。
   未选工作区的 Hero 页，官方 header 是空壳（wSkVaW_headerBlank
   wSkVaW_headerSessionless），实测高度只有 8px（CDP：rect.h=8、padTop=4px）。
   裸 50% 在那里算成 4px，再被 translateY(-50%) 上移 20px → 按钮 y = -16px，
   整个汉堡跑到 header 上方、顶进状态栏（用户反馈「顶栏一整行上移了，
   选完工作区又恢复」—— 选中后 header 恢复自然高度 ~47px，50% 变正值就"回位"）。
   max() 的下界取汉堡自身高度的一半（40 / 2 = 20px），保证 translateY 之后
   顶边不低于 0；header 高度正常时 50% 大于 20px 取 50%，行为与修复前逐像素一致。
   这个下界只依赖汉堡自己的尺寸，不依赖官方类名语义或 header 自然高度。 */
[data-mobile] .mp-nav-btn.mp-in-header {
  position: absolute !important;
  left: 6px !important;
  top: max(50%, 20px) !important;
  transform: translateY(-50%) !important;
}
/* 修复 57：回到 flex 流内（原先 absolute + right:44px 是重叠的根因）。 */
[data-mobile] .wSkVaW_headerUtilities {
  position: static !important;
  right: auto !important;
  left: auto !important;
  top: auto !important;
  transform: none !important;
  margin-left: 0 !important;
  flex: 0 0 auto !important;
}
[data-mobile] .wSkVaW_headerCorner {
  position: static !important;
  right: auto !important;
  left: auto !important;
  top: auto !important;
  transform: none !important;
  /* 修复 30c：官方自带 margin-right:-16px（配合旧布局把按钮拉出容器缘），
     会让按钮探出屏幕 16px（啾灵真机：右侧栏按钮一半看不见）。归零。 */
  margin-right: 0 !important;
  flex: 0 0 auto !important;
}

/* ══ 修复 58：移动端隐藏「打开方式」分体按钮（2026-09-28 啾灵拍板）════════
   该按钮来自官方插件 @deepseek-ai/dsh-client-ui-open-in-app，注册在
   conversation.session.header.utilities 槽位，作用是**在运行 DSH 的这台电脑上**
   用文件资源管理器打开会话工作目录（并记住选过的程序）。

   为什么移动端隐藏：
     ① 手机上点它调起的仍是电脑上的程序，对手机用户没有意义；
     ② 它占据 header 右侧 43px（实测 259..302），是长标题可用区被压缩的主因。
        隐藏后标题可用区从 209px 增至约 252px，长标题能多显示 3~4 个字；
     ③ 用户明确反馈「标题不居中也太丑了」—— 隐藏后右侧只剩「⋯」+「右侧栏」
        两枚按钮，左右更对称。

   ⚠️ 只隐藏这一个分体按钮，**同容器内的「更多操作 ⋯」菜单必须保留**
      （它是移动端进入「轨迹」等入口的唯一通道，见 mpInjectTraceItem）。

   识别方式：用官方稳定属性 [data-open-target]（值是 directory/file），
     不依赖会随发布变化的 CSSModule hash，也不用 :has()（旧内核可能不支持）。
   桌面端：无 data-mobile → 规则不命中，按钮与布局完全不变。 */
[data-mobile] .wSkVaW_headerUtilities [data-open-target] {
  display: none !important;
}

/* 8b. StatsPills（0.1.5 新版：两个可点药丸）在手机宽度下的收窄：
   官方 .bOPqQW_root 左右内边距 = calc(--dsh-composer-side-clearance + 16px) = 32px，
   390dp 下药丸实测 129 + 143 + gap 12 = 284px > 可用 236px → 尾部恒被截断。
   收窄内边距到 8px、gap 到 8px 后可用 284px，两枚药丸完整显示（保持单行）。

   ⭐ 修复 60（2026-09-28 啾灵反馈「底部长了会有省略号，比较难看」）：
   实测底部宽度预算（iPhone 13 / 390px）：
     root(BuPN2G_root) 内容区 356px
       └ dock 356px 上限，其中 遥测栏 + gap12 + 环形61
         └ 遥测栏最多 283px（= 356 − 12 − 61）
            └ 减 padding 12 + 药丸间 gap 6 + 两药丸固定开销 26*2=52
              ⇒ 文本可用 ~213px
   而原文案「24 轮 1085 步·172 t/s」+「92.9M·缓存 93%」实测 197px——
   **余量仅 ~16px**：步数从 4 位变 5 位（1085→10857）就要 +8px，再叠加
   轮数或 token 量增长必然溢出 → 尾部省略号；继续压缩时 label 的
   min-width:0 会把它压到 0 宽，视觉上就像整组「消失」。
   下面把各处 padding/gap 再收紧一档，文本可用宽提到 ~227px（+14px）。
   剩余差额由 JS 分级降档兜底（见 processStatsLine / mpFitStats）。 */
[data-mobile] .bOPqQW_root {
  padding-left: 2px !important;
  padding-right: 2px !important;
  gap: 4px !important;
}
[data-mobile] .bOPqQW_pill {
  padding-left: 3px !important;
  padding-right: 3px !important;
  gap: 3px !important;
}
/* 药丸文字必须能收缩：官方 .bOPqQW_label 是 overflow:hidden + ellipsis，
   但缺 min-width:0 → flex 项不肯收缩，超宽时文字直接被裁（尾部变省略号）。
   ⭐ 修复 60：给 min-width 一个下限（而非 0）。原来的 min-width:0 允许它被
   一路压到 0 宽 → 药丸只剩图标，视觉上就是用户反馈的「底部信息整组消失」。
   现在最多压到 3em，配合 JS 分级降档，永远不会出现 0 宽或省略号。 */
[data-mobile] .bOPqQW_label {
  min-width: 3em;
  overflow: hidden;
  text-overflow: ellipsis;
}
[data-mobile] .bOPqQW_anchor {
  min-width: 0;
}
/* ⚠️ 修复 60 注记：root **必须保持可收缩**（官方 flex 简写 0/1/auto）。
   不要改成 flex:0 0 auto —— 那样 root 会撑开 dock 横向溢出，且
   scrollWidth === clientWidth 恒成立，processStatsLine 里
   「内容宽 > 可视宽」的溢出检测将永远失效，分级降档也就永不触发。
   真正的防线是：① label 的 min-width:3em（防 0 宽「消失」）
   ② JS 分级降档（防省略号）。 */

/* ══ 修复 63（方案 A）：给「三组并排」腾出余量 ══════════════════════════
   文案侧已把 tok/s 与 token 总量移进弹层（见 mpStatsVariants 的注释），
   这里再把三组之间的横向开销各收一档，给 5~6 位步数留余量：
     · dock（遥测栏 ↔ 上下文环）gap 12 → 8
     · 上下文环触发器 padding 8→6、图标↔文字 gap 6→4
   ⚠️ .JObwrW_* 与 .uV2eYG_* 是官方构建期哈希类名（分别来自
      ui-conversation 的 ContextMeter / InputBar），官方升级可能变；
      失效的后果只是「回到官方间距」，不影响功能。 */
[data-mobile] .uV2eYG_dock {
  gap: 8px !important;
}
[data-mobile] .JObwrW_trigger {
  padding-left: 6px !important;
  padding-right: 6px !important;
  gap: 4px !important;
}


/* 5. 审批面板浮到输入框上方（面板保留在 Cordis layer 内不动，仅重定位）。
   抽屉打开时 transform 为 identity，fixed 相对视口生效；bottom 用 CSS 变量
   承载：React 重算 anchor 覆盖内联 bottom 也压不过 !important。 */
[data-mobile] .Nqubda_panel[data-mp-approval] {
  position: fixed !important;
  left: 12px !important;
  right: 12px !important;
  top: auto !important;
  width: auto !important;
  max-width: calc(100vw - 24px) !important;
  bottom: var(--mp-approval-bottom, 96px) !important;
  z-index: 9999 !important;
}

/* 9. 移动端全局关闭 hover 文字提示（tooltip）：
   桌面端 onMouseEnter/onFocus 显示的气泡在触摸设备上会因焦点残留而
   卡在屏幕上（如侧边栏"收起侧边栏"提示）。触摸设备不需要这类提示，
   直接全部隐藏；桌面 hover 行为不受影响。 */
@media (pointer: coarse) {
  [role="tooltip"] {
    display: none !important;
  }
}

/* 9b. 移动端侧边栏会话/工作区行的 ⋮ 菜单按钮常显：
   官方样式仅 hover 或 menuOpen 时显示 rowActions，桌面靠鼠标悬停，
   移动端没有 hover 导致要先点一下会话行才出现。触摸设备上改为
   始终显示，并像官方 hover 那样隐藏时间戳腾出空间。 */
[data-mobile] .YDXeBa_sessionRow .YDXeBa_rowActions,
[data-mobile] .YDXeBa_projectRow .YDXeBa_rowActions {
  display: inline-flex !important;
}
[data-mobile] .YDXeBa_sessionRow .YDXeBa_time,
[data-mobile] .YDXeBa_projectRow .YDXeBa_time {
  display: none !important;
}

/* 9c. 移动端消灭"粘住的 hover"（触摸端没有鼠标移开，
   手指点一下之后 :hover 会一直保留到点别处，看起来像鼠标悬停没走开）。
   官方把下面这些视觉反馈挂在 :hover 上，触摸端会留下残留：
     - .YDXeBa_projectRow / .YDXeBa_sessionRow   行底色
     - 行首图标：hover 时"文件夹"变"▸ 箭头"
     - 抽屉里各种图标按钮的圆形底色
   处理原则：把 :hover 效果归零到各自的基准值，按压反馈改由 JS 打
   .mp-press 类实现（触摸端 :active 不可靠，实测按住时并不匹配）。
   注意保留两个真实状态：会话行的"当前选中" .YDXeBa_selected
   与 ⋮ 菜单打开态 .YDXeBa_menuOpen（都不是 hover）。
   设置 dialog 子树（VOzbGW_*）一律不碰，避免把 dialog 推出屏幕。 */
[data-mobile] .YDXeBa_projectRow:hover,
[data-mobile] .YDXeBa_sessionRow:hover:not(.YDXeBa_selected),
[data-mobile] .YDXeBa_searchResultRow:hover:not(.YDXeBa_selected),
[data-mobile] [data-mobile] .bhn1Oq_iconButton:hover,
[data-mobile] .bhn1Oq_searchButton:hover,
[data-mobile] .hHd-Xa_iconButton:hover {
  background: transparent !important;
}
[data-mobile] .YDXeBa_projectRow:hover .YDXeBa_chevron {
  display: none !important;
}
[data-mobile] .YDXeBa_projectRow:hover .YDXeBa_folder {
  display: inline-flex !important;
}
[data-mobile] .YDXeBa_iconButton:hover {
  color: var(--dsw-alias-label-tertiary, #888) !important;
}
[data-mobile] .hHd-Xa_newSession:hover {
  background: var(--dsw-alias-button-elevated-fill, #fff) !important;
}

/* 9c-2. 变量级兜底。上面 9c 是逐个类名写死的，只盖住了侧栏那一小撮；
   真机实测主界面还有一批按钮把 hover 底色挂在设计系统变量上 ——
   11 个可点元素里有 9 个是同一个 --dsw-alias-interactive-bg-hover
   (= #2631480f = rgba(38,49,72,0.06)，就是用户看到的"黑印")。
   官方类名是构建期 hash（换版本就变），所以这一层改成从变量拦截，
   一次覆盖全部元素，不依赖任何类名。

   ⚠️ 必须排除 [aria-expanded="true"]。官方把"菜单已展开"的持久高亮和 :hover
   写在同一组规则里、共用同一个变量：
     .pXSMma_workspace:not(:disabled):hover,
     .pXSMma_workspace[aria-expanded="true"] { background: var(--dsw-alias-interactive-bg-hover) }
   连坐清掉会把"当前展开的菜单"高亮一起弄没（真机上唯一带这个属性的是
   home 工作区按钮）。排除后真机实测：收起+hover → 透明；
   展开 → 仍是 rgba(38,49,72,0.06)（持久高亮完好）。

   ⚠️ 只包在 (hover: none) 里：接鼠标的设备保留 hover 反馈。
   测试机 Huawei CDY-TN90 命中该媒体查询（hover:none / pointer:coarse）。 */
@media (hover: none) {
  [data-mobile] button:hover:not([aria-expanded="true"]),
  [data-mobile] [role="button"]:hover:not([aria-expanded="true"]),
  [data-mobile] [role="tab"]:hover:not([aria-expanded="true"]),
  [data-mobile] [role="menuitem"]:hover:not([aria-expanded="true"]),
  [data-mobile] a[href]:hover:not([aria-expanded="true"]),
  [data-mobile] summary:hover:not([aria-expanded="true"]),
  [data-mobile] [tabindex]:hover:not([aria-expanded="true"]) {
    --dsw-alias-interactive-bg-hover: transparent;
  }
  /* composer 的 "+" 按钮走的是 -solid 变量（#f1f3f5），把它也归零会变成全透明，
     比原来的"浅灰→略深灰"更难看，所以这里直接还原成它的静态底色。 */
  [data-mobile] .uV2eYG_add:hover:not(:disabled) {
    background: #f5f6f7 !important;
  }
  /* 侧栏"新会话"按钮：9c 把它 hover 设成白卡（elevated-fill），
     而它的静态底色是透明，点完同样留一块白印，这里一并归零。 */
  [data-mobile] .hHd-Xa_newSession:hover {
    background: transparent !important;
  }
}

/* 9d. 按压反馈（替代触摸端不可用的 hover）：
   JS 在 touchstart/pointerdown 时给按下的行加 .mp-press，抬手即移除。 */
[data-mobile] .YDXeBa_projectRow.mp-press,
[data-mobile] .YDXeBa_sessionRow.mp-press,
[data-mobile] .YDXeBa_searchResultRow.mp-press,
[data-mobile] .bhn1Oq_iconButton.mp-press,
[data-mobile] .bhn1Oq_searchButton.mp-press,
[data-mobile] .hHd-Xa_iconButton.mp-press {
  background: var(--dsw-alias-interactive-bg-hover, rgba(127, 127, 127, 0.15)) !important;
}
[data-mobile] .YDXeBa_iconButton.mp-press {
  color: var(--dsw-alias-label-primary, #222) !important;
}

/* 10. 顶栏标题重命名弹窗（移动端自建，兼容手机 WebView） */
.mp-rename-mask {
  position: fixed;
  inset: 0;
  z-index: 99999;
  background: rgba(0, 0, 0, .4);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  box-sizing: border-box;
}
.mp-rename-box {
  width: 100%;
  max-width: 340px;
  background: var(--dsw-alias-bg-base, #fff);
  border-radius: 16px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, .25);
  padding: 20px 18px;
  box-sizing: border-box;
}
.mp-rename-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--dsw-alias-label-primary, #222);
  margin: 0 0 14px;
  text-align: center;
}
.mp-rename-input {
  width: 100%;
  box-sizing: border-box;
  border: 1px solid var(--dsw-alias-border-l2, #e0e0e0);
  background: var(--dsw-alias-bg-layer-1, #fff);
  color: var(--dsw-alias-label-primary, #222);
  border-radius: 10px;
  padding: 12px 14px;
  font-size: 15px;
  outline: none;
}
.mp-rename-input:focus {
  border-color: var(--dsw-alias-state-business-primary, #4a7dff);
}
.mp-rename-row {
  display: flex;
  gap: 10px;
  margin-top: 16px;
}
.mp-rename-btn {
  flex: 1;
  height: 44px;
  border: none;
  border-radius: 10px;
  font-size: 15px;
  cursor: pointer;
}
.mp-rename-cancel {
  background: var(--dsw-alias-interactive-bg-hover, #f0f0f0);
  color: var(--dsw-alias-label-secondary, #666);
}
.mp-rename-ok {
  background: var(--dsw-alias-state-business-primary, #4a7dff);
  color: #fff;
  font-weight: 600;
}
.mp-rename-error {
  color: var(--dsw-alias-state-error-primary, #e5484d);
  font-size: 12px;
  margin: 10px 2px 0;
  min-height: 16px;
}

/* 12. 插件列表分组（官方 / 用户插件），默认折叠、点标题展开 */
.mp-plugin-group {
  grid-column: 1 / -1;
  list-style: none;
  margin: 6px 0 2px;
}
.mp-plugin-group-btn {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 11px 12px;
  border: 1px solid var(--dsw-alias-border-l2, rgba(0, 0, 0, 0.08));
  border-radius: 10px;
  background: var(--dsw-specific-menu, #fff);
  color: var(--dsw-alias-label-primary, #222);
  font: inherit;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  text-align: left;
  box-sizing: border-box;
}
.mp-plugin-group-count {
  color: var(--dsw-alias-label-tertiary, #888);
  font-size: 12px;
  font-weight: 400;
  background: var(--dsw-alias-interactive-bg-hover, rgba(127, 127, 127, 0.15));
  border-radius: 99px;
  padding: 1px 8px;
  white-space: nowrap;
}
.mp-plugin-group-chevron {
  margin-left: auto;
  opacity: 0.6;
  transition: transform 0.15s ease;
}
.mp-plugin-group-open .mp-plugin-group-chevron {
  transform: rotate(180deg);
}

/* ══ 修复 66：全局面板页（插件 / 定时任务）顶部文字被汉堡压住 ════════════
   现象（2026-10-04 啾灵真机反馈）：
     侧栏点「插件」后主区切到插件页，页面最上方的「插件」大标题与它下面
     的说明文字，和左上角那枚汉堡按钮**叠在一起**（截图实测汉堡 y 145..215、
     标题 y 150..215，重叠约 65px）。

   根因（官方布局 + 本插件的挂载策略共同导致，不是单方面的锅）：
     ① 「.wSkVaW_header」（含 ⋯ 那一行顶栏）属于 **conversation 插件**，
        切到 main panel 时 conversation 整体卸载 → 顶栏连同一整行都没了；
     ② 本插件的汉堡由 mpAnchorBurger() 挂载：有顶栏时进 header（absolute），
        **没有顶栏时回落到 document.body（fixed 左上角浮层）** —— 这一步本身
        是对的（否则插件页就没有入口回不去侧栏了）；
     ③ 而插件页自己的「.X_2TxG_pageHead」只有 padding-top:28px，那是官方给
        **桌面端** --dsh-frame-top-clearance 预留的量（该变量只在
        darwin / windows-titlebar 下定义，Android 上恒为 0）→ 页面从视口顶部
        开始排，正好撞进汉堡的浮层范围。

   修法：**不给插件页写死像素，也不去硬编码它的 hash 类名**。
     汉堡是浮层时，把它真实的 bottom（+ 呼吸位）写成 CSS 变量
     --mp-float-nav-h，由「.pI_x6G_centerCol」（官方 layout 的稳定类名）
     吃成 padding-top；汉堡回到顶栏里时该变量清零，conversation 页逐像素不变。
     这样任何面板页、任何屏幕、任何系统栏高度都自动对齐，且官方换 hash 类名
     也不会失效（我们不依赖 X_2TxG_page 这个构建期哈希）。

   ⚠️ 只动 centerCol 的**上内边距**：它是 flex 列容器、内部只有 main slot
      一个子项，加内边距 = 子项整体下移，不改变子项自身的布局语义。 */
[data-mobile] .pI_x6G_centerCol {
  padding-top: var(--mp-float-nav-h, 0px) !important;
}

/* ⚠️ 修复 68 的教训（第一版就是死在这里，别再走回去）：
 *
 * 第一版摘掉 type="password" 之后，又用下面这条 CSS 把圆点补了回来：
 *
 *   input[data-mp-key="1"] { -webkit-text-security: disc !important; }
 *
 * 这条规则让整个修复**完全失效**，且失效得很隐蔽 —— 属性层面看起来全对：
 * DOM 里确实已是 type="text"、autocomplete 也确实被摘掉了，但输入法
 * **仍然**切到安全键盘、**仍然**屏蔽剪贴板。
 *
 * 真机实测（Huawei CDY-TN90 / Android 10，焦点停在 API key 输入框上）：
 *
 *   JS 层（探针）：INPUT type=text  ac=(空)  data-mp-key=1  pwdSel=0
 *   IME 层（dumpsys input_method）：
 *     mCurrentTextBoxAttribute: inputType=0xe1
 *
 * 0xe1 = TYPE_CLASS_TEXT(0x1) | TYPE_TEXT_VARIATION_WEB_PASSWORD(0xe0)
 * —— 页面里一个 type=password 都不剩了，键盘拿到的却还是「网页密码框」。
 *
 * 根因：blink 判定「这是个密码字段」用的是
 * Element::ShouldObscureValue()，而它检查的是
 * **computedStyle.textSecurity != none** —— 也就是说，
 * -webkit-text-security: disc **自己就是**那个触发条件；
 * type=password 只是通过 UA 样式表拿到同一条声明而已。
 * 摘掉属性、再手写同一条声明 ＝ 原样把密码语义装了回去。
 *
 * 结论：**「普通键盘」与「圆点遮蔽」在 Android WebView 上不可兼得。**
 * 要剪贴板就必须放弃遮蔽 —— 这正是用户明确要的效果（「跟普通输入框一样」）。
 * 所以本插件不再为凭据框做任何遮蔽补偿；API key 在设置页里明文可见。
 * 取舍依据：API key 是用户自己粘贴进去的、本就掌握在自己手里的一串字符，
 * 而安全键盘是**让这个功能彻底不可用**。可接受。
 *
 * ⚠️ 本注释位于 CSS 模板字符串内部：**禁止出现反引号**，那会提前终止字符串。
 *    上面提到的属性名一律不加 markdown 反引号，就是这个原因。 */
`;

		function injectStyles() {
			if (document.querySelector("style[data-plugin-css='dsh-android-mobile/main.css']")) return;
			const tag = document.createElement("style");
			tag.dataset.plugin = "dsh-android-mobile";
			tag.dataset.pluginCss = "dsh-android-mobile/main.css";
			tag.textContent = CSS;
			document.head.appendChild(tag);
		}

		// ── 3. 消息 meta 精简：保留 "22:41 · 用时 3秒"，删 " · 首 token … / … tok/s" ──
		function trimMeta(el) {
			if (!mpIsMobile()) return; // 修复 40：仅移动端；桌面端完全走官方
			const m = /^(.+?·\s*用时\s*[^·]*?)(\s*·.*)?$/.exec((el.textContent || "").trim());
			if (!m || !m[2]) return;
			el.textContent = m[1];
		}

		// ── 3. composer StatsPills 精简 ──
		// 官方 .bOPqQW_root 左右内边距 = calc(--dsh-composer-side-clearance + 16px) = 32px，
		// 390dp 下可用宽仅 270px，而两枚药丸实测 129 + 143 + gap 12 = 284px → 尾部恒被截断
		// （用户看到的是「3 轮 22 …」）。
		//
		// ⭐ 修复 60（2026-09-28 啾灵反馈「底部长了会有省略号，比较难看」+「有时整组消失」）：
		// 实测底部宽度预算（iPhone 13 / 390px）：
		//     dock 上限 356px = 遥测栏 + gap12 + 环形61  ⇒ 遥测栏最多 283px
		//     减 root padding 4 + 药丸间 gap 4 + 两药丸固定开销 (icon14+gap3+pad6)*2=46
		//     ⇒ 文本预算 229px，而原文案「24 轮 1085 步·172 t/s」+「92.9M·缓存 93%」实测 197px
		//     ⇒ **余量仅 ~16px**。步数 4 位→5 位要 +7px，轮数/token 再长就溢出 → 尾部省略号。
		//
		// 为什么会出现「整组消失」：.bOPqQW_label 带 min-width:0（修复早期为解决
		//   flex 不肯收缩而加），溢出时它会被一路压到 0 宽 → 药丸只剩图标，
		//   视觉上就是「轮/步、缓存两组信息消失了」。
		//
		// 修法：不做"压缩到溢出"，改为**分级降档**——按信息重要性逐级舍弃，
		//   每级都实测真实宽度，选第一个放得下的，因此**永不产生省略号**。
		//   信息优先级（啾灵认可的顺序）：
		//     ① 轮/步计数  —— 进度核心，任何档位都保留
		//     ② 缓存命中率 % —— 第二重要，最后档位也保留
		//     ③ token 总量（92.9M）—— 中优先级，挤不下先舍
		//     ④ 速度 t/s   —— 实时性弱、变化最频繁，**最先**舍弃
		// 幂等：降档结果写回 dataset.mpApplied，下一轮若文本未变则不重复测量；
		//   React 写入新数值时（now !== mpApplied）才重新计算并更新原文缓存。
		const MP_STATS_RAW = "mpRaw", MP_STATS_APPLIED = "mpApplied";

		/* ══ 修复 63：底栏常驻文案精简（方案 A + 用户追加「用量」）══════════════

		   常驻可见 = 轮/步（进度）· token 总量·缓存命中%（用量/成本效率）·
		   上下文环%（会不会爆，环由 conversation 插件的 ContextMeter 渲染）。
		   常驻里**去掉**的只有 tok/s。

		   为什么（预算账）：官方「详细」档这一行是三组并排
		     ⏱「24 轮 1085 步 · 172 tok/s」 🗄「92.9M tok · 缓存命中 93%」 ◔「63%」
		   需求宽 ~285px，而 390px 屏的 dock 预算 ~283px —— **天生差一点**，
		   所以永远在省略号边缘蹭，数字一进位就变省略号。精简后实测：
		     ⏱「24轮 1085步」+ 🗄「92.9M·缓存93%」
		     = 药丸 93 + 4(药丸间 gap) + 109 ≈ 216px（含图标/padding/内 gap），
		     对 240px 的保守预算余量 ~24px（真实 dock 预算更宽松，约 298px）。
		   于是按用户要求把 token 总量放回来仍然放得下；仍是 tok/s 留在弹层
		   （瞬时值、每秒都在跳，信息半衰期最短，且它是最长的一段）。
		   ⭐ 关键前提：三组**都能点开**，弹层里一个数据都没少
		     （时间药丸 → LLM/工具时长、首 token、速度；用量药丸 → 缓存命中率、
		      输入/缓存读/缓存写/输出 tok；上下文环 → ~已用/窗口 + 分段明细）。

		   下面第 0 档（基准档）就是固定精简文案，其余档位是极端数字
		   （1085→108570 步、总量涨到 B 级之类）时的兜底降档。 */
		function mpStatsVariants(raw) {
			const abbr = (s) => s.replace(/缓存命中/g, "缓存").replace(/(\S)\s*tok\/s/g, "$1 t/s");
			const src = abbr(raw);
			const v = [];
			if (/轮|步|turns|steps/.test(src)) {
				// 进度药丸：基准档直接就是**紧排**的「24轮 1085步」
				// （官方原文「24 轮 1085 步」在数字与单位间有空格，白占约 8px，
				//   而这次改动的目的就是省宽度 → 不把它当基准档）。
				// 其后依次：去掉中间空格 → 只留步数 → 只留数字。
				const tight = src.replace(/\s*·\s*[^·]*$/, "").trim()
					.replace(/(\d)\s+(轮|步|turns|steps)/g, "$1$2");
				v.push(tight);
				v.push(tight.replace(/\s+/g, ""));
				const stepsOnly = tight.replace(/^.*?(\d+)\s*(步|steps)\s*$/, "$1$2");
				v.push(stepsOnly);
				if (stepsOnly !== tight) v.push(stepsOnly.replace(/\s*(步|steps)\s*$/, ""));
			} else {
				// 用量药丸：基准档 = 官方同款语义「总量·缓存N%」（用户 2026-10-03
				//   看过实测余量后要求把「用量」放回来）。
				//   总量与缓存命中%本来就是官方的**同一枚药丸**（UsagePill），
				//   合在一枚里比拆成两枚省一个图标+间距+间隙（约 31px），
				//   所以放回总量不必新增药丸。
				//   降档顺序：舍总量 → 舍「缓存」二字只留百分比。
				const m = /·\s*([^·]+)$/.exec(src);
				if (m) {
					// "92.9M tok" → "92.9M"（图标已表达"token"，单位白占宽）
					const total = src.slice(0, m.index).trim().replace(/\s*tok\b/g, "").trim();
					const cache = m[1].trim().replace(/\s+/g, "");
					if (total) v.push(total + "·" + cache);
					v.push(cache);
					const pct = (cache.match(/\d+(?:\.\d+)?%/) || [""])[0];
					if (pct) v.push(pct);
				} else {
					v.push(src.replace(/\s*tok\b/g, "").trim() || src);
				}
			}
			// 去掉空串与重复，保持顺序（单调递减，降档必然终止）
			return v.filter((x, i) => x && v.indexOf(x) === i);
		}

		function mpStatsFits(root, labels) {
			// 内容宽 > 可视宽 ⇒ 溢出（会出省略号）
			if (root.scrollWidth > root.clientWidth + 1) return false;
			return labels.every((el) => el.scrollWidth <= el.clientWidth + 1);
		}

		/* ⚠️⚠️ 修复 60b（2026-09-28）——上一版这里是**死循环**，把整个页面卡死了，
		   表现是「什么按钮都点不了、输入框也点不了」，远程访问时尤其明显。

		   错在哪（务必不要再写回来）：
		     processStatsLine 被 MutationObserver 的 characterData 分支调用
		     （见文件末尾 mo.observe(document.body, {childList,subtree,characterData})）。
		     上一版每次都【从第 0 档（最长文案）重新开始试】：
		         写第 0 档 → 溢出 → 写第 1 档 → 溢出 → 写第 2 档 → 放得下
		     净结果虽然回到第 2 档，但**每次调用都写了 3 次 textContent** ⇒
		     每次写入又触发一次 observer ⇒ queueMicrotask 再调回来 ⇒
		     微任务级无限循环，主线程被 100% 占满，页面彻底无响应。

		   正确做法（现在这版）必须满足两条不变量：
		     ① **不溢出就一个 DOM 都不写，直接 return** —— 无写入 ⇒ 不触发 observer
		        ⇒ 环被切断。这是最关键的一条。
		     ② **只能单调降档，绝不回写更长的档** —— 候选档位有限（≤4 档），
		        每次最多降一级，降到最后一档就 next === cur 不再写入 ⇒ 必然终止。

		   信息优先级（修复 63 / 方案 A 之后的最终形态）：
		     常驻 = 轮/步 · 缓存命中% · 上下文环%；tok/s 与 token 总量**从基准档
		     就已移入弹层**（点药丸即得），不再参与常驻竞争。
		     这里的档位只剩「极端数字兜底」：轮→步→纯数字、缓存标签→纯百分比。 */
		function processStatsLine() {
			if (!mpIsMobile()) return; // 修复 40：仅移动端；桌面端完全走官方
			const root = document.querySelector("[data-composer-stats] .bOPqQW_root") || document.querySelector(".bOPqQW_root");
			if (!root) return;
			const labels = [...root.querySelectorAll(".bOPqQW_label")];
			if (!labels.length) return;

			// ① 原文快照：只有「当前文本不是我们上次写的那一份」时才认为 React 更新了原文。
			//    必须带 MP_STATS_APPLIED 判断，否则我们写下的缩短文案会被误当成新原文，
			//    原文越缩越短、再也回不去。
			for (const el of labels) {
				const now = el.textContent || "";
				if (now !== el.dataset[MP_STATS_APPLIED]) el.dataset[MP_STATS_RAW] = now;
			}

			// ② 档位表（按 mpStatsRaw 生成，一药丸一组，单调递减）
			const variants = labels.map((el) => mpStatsVariants(el.dataset[MP_STATS_RAW] || el.textContent || ""));
			// 放得下？（root 内容溢出 + 每个 label 的内部截断都要看：
			// label 带 ellipsis 时 root 未必溢出，但 label.scrollWidth 会暴露截断）
			const fits = mpStatsFits(root, labels);

			// ③ 双不变量（修复 60b 的死循环教训必须继续成立）：
			//    ① **不写 DOM 的路径必须存在且最先命中** —— 当前文案已在候选表里
			//       且放得下 ⇒ 一个字节都不写 ⇒ 不触发 observer ⇒ 环被切断；
			//    ② **只能单调降档**，每次最多降一级 ⇒ 必然终止。
			//
			//    ⭐ 修复 63 的实质改动：官方原文（idx < 0）**不再看放不放得下**，
			//       一律先归一到基准档 v[0]（= 精简常驻文案：轮/步 · 缓存N%）。
			//       因为方案 A 是「常驻就精简」，不是「挤不下才精简」——
			//       否则数字短的时候 tok/s 和 token 总量又会回到屏幕上。
			//       归一后下一轮 idx ≥ 0 且放得下 ⇒ 走不变量①，不再写入。
			//       已在候选表里（idx ≥ 0）才允许、且只在溢出时降一级。
			labels.forEach((el, i) => {
				const v = variants[i];
				const cur = el.textContent || "";
				const idx = v.indexOf(cur);
				let next;
				if (idx < 0) next = v[0];               // 官方原文 → 归一为精简基准档
				else if (fits) return;                  // 已是候选档且放得下 → 不写（断环）
				else next = v[Math.min(idx + 1, v.length - 1)]; // 溢出 → 降一档
				if (next === cur) return;               // 无变化 ⇒ 不写 ⇒ 不触发 observer
				el.textContent = next;
				el.dataset[MP_STATS_APPLIED] = next;
			});
		}

		// ── 6. 侧栏点「新会话」或「会话条」后自动收回抽屉 ──
		function closeDrawerAfterClick() {
			setTimeout(() => { mpCloseDrawer(); }, 150);
		}
		function onNewSessionClick(e) {
			if (!mpIsMobile()) return; // 修复 40：收抽屉仅移动端有意义
			// 覆盖两种新建会话按钮（中英文界面）：
			//   顶部按钮 aria-label="新建会话" / "New session"，
			//   项目行右侧 ➕ aria-label="在“{name}”中新建会话" / "New session in {name}"。
			const t = e.target.closest('button[aria-label="新建会话"], button[aria-label*="中新建会话"], button[aria-label="New session"], button[aria-label*="New session in"]');
			if (!t) return;
			closeDrawerAfterClick();
		}
		function onSessionRowClick(e) {
			// 仅移动端抽屉场景
			if (!mpIsMobile()) return; // 修复 40：与 CSS 作用域同源
			// 点击会话条主体（非 ⋮ 操作按钮）时选中并收抽屉
			const row = e.target.closest('.YDXeBa_sessionRow');
			if (!row) return;
			if (e.target.closest('.YDXeBa_rowActions')) return;
			closeDrawerAfterClick();
		}
		// ── 6b. 侧栏点「全局面板」条目（插件 / 定时任务）后同样自动收回抽屉 ──
		//
		// 修复 66（2026-10-04 啾灵真机反馈）：
		//   现象：侧栏点「插件」后主区**已经**切到插件页，但侧栏抽屉还开在原地，
		//         要用户再点一次遮罩才收起来。
		//   根因：上面两个监听器只覆盖「新会话」与「会话条」，而全局面板条目是
		//         sidebar.panellist 槽位注册的**另一类**按钮（PanelRow），
		//         它的 onClick 只调 selectPanel(id)，从不经过 closeDrawerAfterClick()
		//         → 主区切了、抽屉没人收。
		//
		//   ⚠️ 判据用官方 PanelRow 的构建期哈希类名 `hHd-Xa_panelRow`，
		//      与本项目既有做法一致（.hHd-Xa_toggle / .hHd-Xa_root 同理）；
		//      再补一条结构性兜底 `hHd-Xa_panelList button`（容器内的按钮），
		//      这样即便官方升级把 panelRow 这个类名换掉，只要槽位容器还在就仍能命中。
		//      两条都不会误伤会话条 / 新建按钮 —— 它们在别的容器里。
		//
		//   ⚠️ 用 closest 而不是 e.target.matches：点击通常落在条目内的图标
		//      span / 文字 span 上，只有向上找到那个 button 才是可点实体。
		//      PanelRow 渲染出来的就是 <button class="hHd-Xa_panelRow">，
		//      所以两条选择器最终命中的是同一个元素。
		function onPanelRowClick(e) {
			if (!mpIsMobile()) return; // 修复 40：收抽屉仅移动端有意义
			const t = e.target.closest('.hHd-Xa_panelRow, .hHd-Xa_panelList button');
			if (!t) return;
			closeDrawerAfterClick();
		}

		// ── 4d. 按压反馈（替代触摸端不可用的 :hover，见 CSS 9c/9d）──
		// 触摸端 :active 实测按住时并不匹配；而官方把行底色的视觉反馈挂在
		// :hover 上，手指点完 :hover 会一直粘着（要再点别处才消失），
		// 看起来就像鼠标悬停没走开。这里改成：按下加 .mp-press，抬手/滑走即移除，
		// 反馈只在按住的瞬间存在——更接近原生列表的手感。
		const MP_PRESS_SELECTOR = [
			'.YDXeBa_projectRow', '.YDXeBa_sessionRow', '.YDXeBa_searchResultRow',
			'.YDXeBa_iconButton', '.bhn1Oq_iconButton', '.bhn1Oq_searchButton',
			'.hHd-Xa_iconButton', '.mp-nav-btn'
		].join(',');
		function setupPressFeedback() {
			let pressed = null;
			let safety = 0;
			const clear = () => {
				if (safety) { window.clearTimeout(safety); safety = 0; }
				if (pressed) { pressed.classList.remove('mp-press'); pressed = null; }
			};
			const onDown = (e) => {
				if (!document.querySelector('[data-mobile]')) return;
				const t = e.target instanceof Element ? e.target.closest(MP_PRESS_SELECTOR) : null;
				if (!t) return;
				clear();
				pressed = t;
				t.classList.add('mp-press');
				// 兜底：万一没收到 up/cancel（例如手指划出屏幕），600ms 后自动清掉
				safety = window.setTimeout(clear, 600);
			};
			const onUp = () => clear();
			document.addEventListener('touchstart', onDown, { capture: true, passive: true });
			document.addEventListener('pointerdown', onDown, { capture: true, passive: true });
			document.addEventListener('touchend', onUp, true);
			document.addEventListener('touchcancel', onUp, true);
			document.addEventListener('pointerup', onUp, true);
			document.addEventListener('pointercancel', onUp, true);
			// 手指按住后滑动（滚动列表）不算点击 → 收起反馈
			document.addEventListener('touchmove', onUp, { capture: true, passive: true });
			return () => {
				clear();
				document.removeEventListener('touchstart', onDown, true);
				document.removeEventListener('pointerdown', onDown, true);
				document.removeEventListener('touchend', onUp, true);
				document.removeEventListener('touchcancel', onUp, true);
				document.removeEventListener('pointerup', onUp, true);
				document.removeEventListener('pointercancel', onUp, true);
				document.removeEventListener('touchmove', onUp, true);
			};
		}

		// ── 5. 审批自动上浮到输入框上方 ──
		let lastApprovalCount = -1;
		function watchApproval() {
			if (!mpIsMobile()) return; // 修复 40：仅移动端；桌面端完全走官方
			const badge = document.querySelector("[data-cordis-approval-badge]");
			if (!badge) return;
			const count = parseInt(badge.getAttribute("data-cordis-approval-badge") || "0", 10);
			if (count === lastApprovalCount) return;
			const prev = lastApprovalCount;
			lastApprovalCount = count;
			if (count > 0) {
				// 有新审批：展开面板 + 打开抽屉（面板保留在 layer 内，useDismiss/React 均正常）
				if (badge.getAttribute("aria-expanded") !== "true") badge.click();
				openDrawer();
				positionApprovalPanel();
			} else if (prev > 0) {
				// 审批已处理：收起面板 + 收起抽屉
				if (badge.getAttribute("aria-expanded") === "true") badge.click();
				closeDrawer();
			}
		}

		function openDrawer() { mpOpenDrawer(); }

		function closeDrawer() { mpCloseDrawer(); }

		function positionApprovalPanel() {
			if (!mpIsMobile()) return; // 修复 40：仅移动端；桌面端完全走官方
			// 面板渲染是异步的（React setState → re-render）：轮询等面板出现再定位
			let tries = 0;
			const tick = () => {
				const panel = document.querySelector(".Nqubda_panel");
				const seat = document.querySelector("[data-composer-seat]");
				if (panel && seat) {
					const r = seat.getBoundingClientRect();
					panel.dataset.mpApproval = "1";
					panel.style.setProperty("--mp-approval-bottom", Math.max(8, window.innerHeight - r.top + 8) + "px");
				} else if (tries++ < 10) {
					setTimeout(tick, 100);
				}
			};
			tick();
		}

						// ── 7b. 插件列表分组（官方 / 用户插件），默认折叠、点击展开 ──
		// 分类规则（用户选定，包名规则）：
		//   官方 = cordis:include、dshmarket（市场本体）、@deepseek-ai/*
		//   用户 = 其余（@xmanrui/*、@dsh-mobile/*、@dsh-android/*、dsh-* 等用户安装/创建的）
		// 官方列表 UI 是 React 渲染的（remote.pluginInventory.list），本函数只做
		// DOM 层"归一化"：React 每次重渲染会打平列表，MutationObserver 侦测后重新
		// 分组（幂等，结构一致时不再改动，避免循环）。
		// ── 7c. 插件卡片描述 ──
		// 官方接口（@deepseek-ai/dsh-host-plugin-inventory）每条只返回 entryId /
		// moduleName / enabled / fiberPhase，**没有 description 字段**；`/plugins/<pkg>/package.json`
		// 也不对外提供（实测 404）。所以这里内置一份人工整理的中文描述表，按 moduleName
		// （取自 .qSYn7G_cardTitle 的 title 属性）匹配，注入到展开后的 .qSYn7G_cardDetails。
		// 生成方式：读取两个模块根下各包 package.json 的 description 后逐条译写，见
		// /root/ui-design/plugin-descs-zh.json（156 条，覆盖全部 182 个插件条目）。
		const MP_PLUGIN_DESCS = {
			"./plugins/argfix3.js":"cordis 工具参数扁平化补丁（会话预设 cordis-argfix 自带）", "@deepseek-ai/cordis-plugin-hmr":"cordis 的热模块替换（HMR）插件", "@deepseek-ai/cordis-plugin-timer":"为 cordis 提供定时器服务",
			"@deepseek-ai/dsh-agent":"Agent 接口、注册表、发起者作用域与事件词汇", "@deepseek-ai/dsh-agent-default-model":"Agent 各入口共用的默认模型选择", "@deepseek-ai/dsh-agent-instructions":"加载工作区 AGENTS.md/CLAUDE.md 指令文件",
			"@deepseek-ai/dsh-agent-loop":"智能体主循环的具体实现插件", "@deepseek-ai/dsh-agent-presets":"按预设 cordis.yml 文件为每个会话组装智能体", "@deepseek-ai/dsh-api-gateway":"Typert 远程宿主分发器与客户端 API 端点",
			"@deepseek-ai/dsh-api-remotes":"远程 BFF 装配与 Host 端 Agent／Session 查找策略", "@deepseek-ai/dsh-attachment-local":"DSH_HOME 内按内容寻址的私有附件存储", "@deepseek-ai/dsh-bash-sandbox":"消费沙箱的 bash 执行器实现，逐条命令受限并报告结果",
			"@deepseek-ai/dsh-client-connection":"连接消费层：上行 HTTP、下行 WebSocket 与可重连的双流", "@deepseek-ai/dsh-client-hmr":"仅开发用的客户端热重载驱动：SSE 帧重建触发 fiber 交换", "@deepseek-ai/dsh-client-locale":"语言插件：Host 侧中英偏好、浏览器回退与命名空间词典",
			"@deepseek-ai/dsh-client-modules":"客户端模块系统：node 半构建入口图，浏览器半为惰性 CJS 表", "@deepseek-ai/dsh-client-runtime":"客户端核心服务：SlotRegistry 与 SessionRuntime", "@deepseek-ai/dsh-client-ui-agent-preset":"智能体预设界面：后续会话默认值、本会话席位与编排编辑器",
			"@deepseek-ai/dsh-client-ui-attachment":"对话输入与消息图片插槽的动态附件展示插件", "@deepseek-ai/dsh-client-ui-brand-official":"Web 端侧边栏与对话 Hero 插槽的官方品牌内容", "@deepseek-ai/dsh-client-ui-commands":"客户端命令界面：全局目录缓存、/ 来源与 popupSelect 注册表",
			"@deepseek-ai/dsh-client-ui-conversation":"会话域：骨架、有序对话流、带 Host 端忙碌回车偏好的输入框与详情宿主", "@deepseek-ai/dsh-client-ui-cordis":"Cordis 动态插件定义卡片：cordis_define 工具行与运行开关", "@deepseek-ai/dsh-client-ui-deliverables":"Web 端产出文件回合尾部与最终回复的可点击文件引用",
			"@deepseek-ai/dsh-client-ui-directory-picker-browse":"应用内目录浏览界面：渲染宿主列举与创建原语的工作区目录流", "@deepseek-ai/dsh-client-ui-goal":"会话目标界面：停靠输入框上方的 GoalBar，读取目标会话投影", "@deepseek-ai/dsh-client-ui-input-trigger":"输入触发管线：检测 / 与 @、候选菜单、把选择路由到已注册来源",
			"@deepseek-ai/dsh-client-ui-jobs":"会话头部后台任务列表：镜像 session/jobs 帧的实时注册表状态", "@deepseek-ai/dsh-client-ui-layout":"外壳插件：三栏 AppFrame 与 ctx.layout 视图状态", "@deepseek-ai/dsh-client-ui-message-feedback":"助手消息操作栏的逐条反馈控件，由 messageFeedback 支撑",
			"@deepseek-ai/dsh-client-ui-model-selection":"模型选择：session.selectModel 上的 /model 弹窗", "@deepseek-ai/dsh-client-ui-permission-presets":"权限界面：新会话默认值设置与当前会话的 /permission 弹窗", "@deepseek-ai/dsh-client-ui-plan":"计划模式输入控件：plan 席位与 /plan 命令通道",
			"@deepseek-ai/dsh-client-ui-reference":"Web 端统一的 @file 与 @session 引用来源", "@deepseek-ai/dsh-client-ui-renderer":"浏览器 UI 渲染器：ctx.uiRenderer 插槽绑定与应用根装配", "@deepseek-ai/dsh-client-ui-settings":"设置域基础插件：settings 命名空间作用域服务与标准设置插槽契约",
			"@deepseek-ai/dsh-client-ui-settings-general":"通用设置分节、外壳头部内容、设置词典与版本化欢迎提示", "@deepseek-ai/dsh-client-ui-settings-models":"模型设置与共享的产品引导弹窗，复用既有设置与凭据关联", "@deepseek-ai/dsh-client-ui-settings-plugin-inventory":"Web 插件设置中的只读 Cordis Loader 清单标签页",
			"@deepseek-ai/dsh-client-ui-settings-plugins":"插件设置分节：各功能自有的标签页与可配置的宿主平面插件卡片", "@deepseek-ai/dsh-client-ui-sidebar":"侧边栏插件：会话多级树、搜索、分组与状态圆点", "@deepseek-ai/dsh-client-ui-skill":"Web 端 skill 引用与专用的 skill 工具行",
			"@deepseek-ai/dsh-client-ui-subagent":"子智能体会话目录、续接路由 UI 与 @ 引用来源", "@deepseek-ai/dsh-client-ui-theme":"主题插件：亮暗与系统主题状态、--dsw-* 令牌样式", "@deepseek-ai/dsh-client-ui-tool":"客户端工具调用树渲染器与按工具键入的展示插槽",
			"@deepseek-ai/dsh-client-ui-trajectory":"轨迹事件账本与交互式耗时总览：注册进会话 ViewMap 的纯消费插件", "@deepseek-ai/dsh-client-ui-user-questions":"ask_user_question 功能：宿主工具挂载与提问界面", "@deepseek-ai/dsh-client-ui-workflow-run":"Web 端持久化工作流运行的会话节点与嵌套成员展开",
			"@deepseek-ai/dsh-client-ui-workspace":"工作区选择插件：向侧边栏与空状态插槽注册 WorkspacePicker", "@deepseek-ai/dsh-code-runtime-worker-thread":"代码执行抽象层的 worker-thread 实现", "@deepseek-ai/dsh-command-compact":"显式压缩会话的斜杠命令",
			"@deepseek-ai/dsh-command-feedback":"仅记录日志的会话反馈与斜杠命令", "@deepseek-ai/dsh-command-goal":"持久化会话目标的斜杠命令", "@deepseek-ai/dsh-commands":"面向 UI 的命令注册表，由插件提供",
			"@deepseek-ai/dsh-compaction-basic":"基于 token 计量的压缩策略与 LLM 摘要后端", "@deepseek-ai/dsh-compaction-tool-result-pruner":"可重放的免模型工具结果头中尾裁剪", "@deepseek-ai/dsh-cordis-client-runner":"动态双半插件的浏览器半：事件订阅、闭包求值与守卫门面",
			"@deepseek-ai/dsh-cordis-host-runner":"动态包定义注册表、host 半沙箱生命周期与调用处理表", "@deepseek-ai/dsh-credentials-local":"文件存储的凭据提供方（$DSH_HOME/.env）", "@deepseek-ai/dsh-file-reference-local":"本地文件系统 ctx.fileReferences 提供方，带限界模糊索引",
			"@deepseek-ai/dsh-fs-observation-policy":"文件上下文策略：经 fs/* 事件闸门实现观察态、先读后写与版本校验", "@deepseek-ai/dsh-fs-sandbox":"文件系统抽象层的沙箱实现：按调用级沙箱模式拦截写/编辑，读取放行", "@deepseek-ai/dsh-goal":"事件溯源的会话目标状态与生命周期服务",
			"@deepseek-ai/dsh-goal-round-driver":"带竞态防护的会话目标轮次驱动", "@deepseek-ai/dsh-host-apiproxy":"API 网关：ApiProxy 契约与 ctx.apiProxy 提供方", "@deepseek-ai/dsh-host-directory-picker-auto":"目录选择抽象层的自适应选择器：按启动环境挂载原生或浏览后端",
			"@deepseek-ai/dsh-host-directory-picker-browse":"目录选择抽象层的应用内浏览后端：宿主文件系统的列举与创建原语", "@deepseek-ai/dsh-host-plugin-inventory":"当前 Cordis Loader 插件状态的只读远程投影", "@deepseek-ai/dsh-host-webserver":"Web 路由注册插件：HTTP 路由、index 改写与静态资源回退",
			"@deepseek-ai/dsh-jobs-local":"后台任务注册表（ctx.jobs）的进程内实现", "@deepseek-ai/dsh-llm":"提供方中立的 LLM 服务接口", "@deepseek-ai/dsh-llm-deepseek":"为 LLM 抽象层实现 DeepSeek chat-completions 适配",
			"@deepseek-ai/dsh-llm-pi-ai":"基于 pi-ai 的 DeepSeek 适配器，dsh-llm-deepseek 的设计验证孪生", "@deepseek-ai/dsh-llm-retry":"按提供方路由的 LLM 请求重试策略", "@deepseek-ai/dsh-message-feedback":"与生命周期绑定的逐条消息评分与备注",
			"@deepseek-ai/dsh-permission-presets":"面向用户的权限预设：一个 Permissions 下拉，统一沙箱模式与审批策略", "@deepseek-ai/dsh-persona":"由编排定义的部署级人格提示分节", "@deepseek-ai/dsh-plan-mode":"按智能体记录的计划模式，含退出确认",
			"@deepseek-ai/dsh-pwsh-sandbox":"消费沙箱的 PowerShell 执行器实现，逐条命令受限并报告结果", "@deepseek-ai/dsh-repeat-tool-reminder":"重复工具调用防护：智能体循环调用时给出提醒", "@deepseek-ai/dsh-sandbox-local":"沙箱抽象层的本地后端（bwrap、landlock、Seatbelt 等）",
			"@deepseek-ai/dsh-sandbox-policy":"逐次调用的沙箱策略解析器与当前模型上下文", "@deepseek-ai/dsh-session":"基于事件溯源的会话存储", "@deepseek-ai/dsh-session-checkpoint-policy":"模型请求与工具副作用前的会话持久化检查点",
			"@deepseek-ai/dsh-session-log-export":"Web 会话日志导出命令与共用的下载弹窗", "@deepseek-ai/dsh-session-persistence-jsonl":"JSONL 持久化会话存储后端", "@deepseek-ai/dsh-session-projection":"会话投影抽象层：投影类型表、提供方契约与当前值注册表",
			"@deepseek-ai/dsh-session-projection-cache":"持久化投影缓存：逐会话检查点、节流写回与冷读回放", "@deepseek-ai/dsh-session-query-sqlite":"带 SQLite FTS5 全文检索的 ctx.sessionQuery 后端", "@deepseek-ai/dsh-session-reference":"跨会话快照引用与持久化的非可信模型上下文",
			"@deepseek-ai/dsh-session-stats":"全日志会话计数与耗时投影 sessionStats", "@deepseek-ai/dsh-session-telemetry-otel":"遥测抽象层的 OpenTelemetry 后端，会话记录交给 OTel 日志管道", "@deepseek-ai/dsh-session-title":"基于日志的会话标题服务与提供方注册表",
			"@deepseek-ai/dsh-session-title-first-prompt-llm":"用首条消息生成会话标题的 LLM 提供方", "@deepseek-ai/dsh-settings-file":"文件存储的设置提供方（settings.yaml）", "@deepseek-ai/dsh-shell-env":"与工具无关的托管 DSH_* shell 环境注册表",
			"@deepseek-ai/dsh-skill":"智能体 Skill 提供方注册表", "@deepseek-ai/dsh-skill-badge":"内置 dsh badge 徽章技能的提供方", "@deepseek-ai/dsh-skill-filesystem":"本地文件系统的 Skill 提供方",
			"@deepseek-ai/dsh-spill-local":"溢出存储抽象层的本地文件系统实现", "@deepseek-ai/dsh-spill-policy":"工具结果溢出策略：超长结果替换为预览与溢出文件路径", "@deepseek-ai/dsh-storage":"存储中枢 ctx.storage：具名后端注册表与数据形态设施",
			"@deepseek-ai/dsh-storage-domain":"领域数据形态：经 schema 校验、可发事件的 KV 领域", "@deepseek-ai/dsh-storage-json":"存储中枢的 JSON 文件 KV 后端", "@deepseek-ai/dsh-subagent":"子智能体抽象层（ctx.subagents）与提供方注册表",
			"@deepseek-ai/dsh-subagent-fork-in-process":"进程内 fork 子智能体后端：用父日志前缀启动子智能体", "@deepseek-ai/dsh-subagent-spawn-in-process":"进程内 spawn 子智能体后端（ctx.agents）", "@deepseek-ai/dsh-subprocess-local":"子进程抽象层的本地实现",
			"@deepseek-ai/dsh-system-prompt":"系统提示词组装注册表", "@deepseek-ai/dsh-token-meter":"可重放的 token 计量服务（ctx.tokenMeter）", "@deepseek-ai/dsh-tool-ask-user":"基于 ctx.userQuestions 抽象层的提问工具",
			"@deepseek-ai/dsh-tool-bash":"面向模型的 bash 工具，支持后台任务与沙箱提权", "@deepseek-ai/dsh-tool-call-timeout-policy":"工具调用超时策略：按工具设置截止时间并返回超时错误", "@deepseek-ai/dsh-tool-cordis":"自省式 Cordis 工具集：查看运行时状态、挂载与卸载模型编写的插件",
			"@deepseek-ai/dsh-tool-fs":"面向模型的文件读写与编辑工具（ctx.fs）", "@deepseek-ai/dsh-tool-fs-search":"面向模型的文件检索工具（glob/grep，ripgrep 支持）", "@deepseek-ai/dsh-tool-goal":"面向模型的同会话目标工具，带执行期权限校验",
			"@deepseek-ai/dsh-tool-jobs":"面向模型的后台任务工具（job_output、job_list、job_kill）", "@deepseek-ai/dsh-tool-present":"把文件登记为本次任务的交付物", "@deepseek-ai/dsh-tool-pwsh":"基于 bash 执行器抽象层的 pwsh 工具",
			"@deepseek-ai/dsh-tool-ralph":"面向模型的 Ralph 循环：全新智能体多轮迭代", "@deepseek-ai/dsh-tool-skill":"面向模型的 skill 加载工具", "@deepseek-ai/dsh-tool-str-replace-editor":"面向模型的文件查看、创建、替换与插入工具",
			"@deepseek-ai/dsh-tool-subagent":"面向模型的子智能体委派工具（ctx.subagents）", "@deepseek-ai/dsh-tool-subagent-control":"全局命名工具：send_message 等子智能体续接操作", "@deepseek-ai/dsh-tool-subagent-control/list-agents":"全局命名工具：send_message 等子智能体续接操作",
			"@deepseek-ai/dsh-tool-subagent-report":"子智能体作用域的 report 上报工具", "@deepseek-ai/dsh-tool-todo":"面向模型的 todo_write 待办工具", "@deepseek-ai/dsh-tool-web":"面向模型的网络工具：web_search 与 web_fetch",
			"@deepseek-ai/dsh-tool-workflow":"面向模型的 workflow 工具：执行 JS 编排脚本", "@deepseek-ai/dsh-tools":"工具注册表与工具执行流水线", "@deepseek-ai/dsh-typert-loader":"生成的 Typert 包贡献的加载器集成",
			"@deepseek-ai/dsh-typert-registry":"生成包反射与 Zod schema 的运行时注册表", "@deepseek-ai/dsh-user-approval":"用户审批抽象层（ctx.approval）：一次性授权经应答方瀑布派发，默认拒绝", "@deepseek-ai/dsh-user-questions":"agent 运行期间向人提问的抽象层（ctx.userQuestions）",
			"@deepseek-ai/dsh-web":"网络访问抽象层 ctx.web：搜索/抓取提供方注册表与 WebError 分类", "@deepseek-ai/dsh-web-app":"浏览器界面 bundle：dsh-base 的 web 补丁层与运行时粘合插件", "@deepseek-ai/dsh-web-app/startup":"浏览器界面 bundle：dsh-base 的 web 补丁层与运行时粘合插件",
			"@deepseek-ai/dsh-web-search-deepseek":"DeepSeek 搜索提供方：经兼容 API 调用原生 web_search", "@deepseek-ai/dsh-workflow-worker-thread":"worker-thread 工作流引擎：宿主事件循环外执行编排脚本", "@deepseek-ai/dsh-workspace":"工作区实体注册表：持久化记录与经校验的会话挂载",
			"@dsh-android/dsh-client-ui-responsive":"移动端核心响应式布局：窄屏抽屉侧栏、底部弹层、手机顶栏与安全区", "@dsh-external/dsh-thinking-loop-guard":"检测并打断思维链死循环：推理反复重复时自动中止本轮并注入纠正提示", "dsh-android-mobile":"移动端界面打磨：顶栏、消息操作、插件分组、设置页两级化等 24 项修复",
			"@xmanrui/dsh-im":"接入九种 IM 机器人与公网 AI Office，把消息转发给本机 Harness", "cordis:include":"Cordis 组合的 include 指令，把外部插件文件并入当前组合", "dsh-agconfig":"在设置里填写 Agnes AI 密钥，供图片与视频生成两个工具共用",
			"dsh-agimage":"Agnes AI 图片生成工具（generate_image），结果图在会话内直接显示", "dsh-agvideo":"Agnes AI 视频生成工具（generate_video），支持文生、首尾帧、参考图三模式", "dsh-away-nudge":"离开提醒：AI 回复后你久未查看时，自动唤醒会话并发微信提醒",
			"dsh-basic-auth":"给 DSH 网页入口加一道账号密码门卫：凭据由浏览器保管，换设备也不用重新登录", "dsh-client-ui-file-upload":"在「+」菜单加「文件上传」，文件落到会话 uploads/ 目录供 AI 读取", "dsh-client-ui-photo-upload":"在「+」菜单加「照片上传」，手机可直接从相册或相机发图",
			"dsh-file-show":"让 AI 把本地文件展示进对话：图片成图卡，其它类型成可点文件卡", "dsh-im-proactive":"让 AI 主动发消息给你的工具（send_im_message）", "dsh-imgswitch":"设置页「图片输入」开关：按渠道或模型开关图片输入，免手改配置",
			"dsh-memory":"跨会话记忆与回复风格：核心记忆常驻注入，另有 7 个记忆工具与设置页", "dsh-restart-local":"独立自重启控制：设置页按钮与 restart_harness 工具", "dsh-skill-list":"在设置 → 插件里加「Skill 列表」页，列出当前会话可用的 Skill",
			"dshmarket":"DSH 可视化插件市场：浏览、搜索并一键安装社区插件",
		};
		// 注入描述。幂等判据：已注入且 dataset.mpDescFor 与目标文案一致则不动 DOM
		// ——否则自己写 DOM 又会触发 observer，重复重建会拖死页面（见修复 24）。
		// 注意：官方只为**已展开**的卡片渲染 .qSYn7G_cardDetails，所以每次最多处理一两张。
		const applyPluginDescriptions = (section) => {
			if (!section) return;
			section.querySelectorAll("li.qSYn7G_card").forEach((card) => {
				const details = card.querySelector(":scope > .qSYn7G_cardDetails");
				if (!details) return;
				const title = card.querySelector(".qSYn7G_cardTitle");
				const mod = (title && title.getAttribute("title")) || "";
				const desc = MP_PLUGIN_DESCS[mod] || "";
				const marked = details.querySelector(":scope > .mp-plugin-desc");
				if (!desc) {
					if (marked) marked.remove();
					return;
				}
				if (marked && marked.dataset.mpDescFor === desc) return;
				const el = marked || document.createElement("p");
				if (!marked) {
					el.className = "mp-plugin-desc";
					// 设置 dialog 子树内一律用 inline style（注入 CSS 会把 dialog 推出屏幕，见 §4.2）
					el.style.cssText = "margin:0 0 8px;color:var(--dsw-alias-label-secondary,#555);"
						+ "font-size:12px;line-height:17px;overflow-wrap:anywhere;";
				}
				el.dataset.mpDescFor = desc;
				el.textContent = desc;
				if (!marked) {
					const dl = details.querySelector(":scope > .qSYn7G_details");
					if (dl) details.insertBefore(el, dl); else details.appendChild(el);
				}
			});
		};

		function setupPluginGrouping(content) {
			if (!mpIsMobile()) return; // 修复 40：仅移动端；桌面端完全走官方
			if (!content || content.dataset.mpPluginGroup) return;
			content.dataset.mpPluginGroup = "1";
			// ── 0.1.5 结构（2026-09-15 重新适配）──
			// 官方自己已经有**两级**分组：
			//   SECTION.qSYn7G_group[data-plugin-scope="preset"] 「会话插件」  → 28 个（当前 Agent 预设）
			//   SECTION.qSYn7G_group[data-plugin-scope="global"] 「全局插件」  → 171 个（系统与所有会话共用）
			// 我们原来那套「用户插件 / 官方插件」是给旧版 182 个扁平列表用的，
			// 套在「会话插件」（28 个全官方）上只会得到一个空的「用户插件 0」——纯噪音。
			// 新规则：**只在「全局插件」这一级内部**再做「用户 / 官方」二分
			// （171 个里混着 14 个自装 + 155 官方 + 2 内置，混排才是问题）；
			// 「会话插件」保持官方原样，不再插手。
			const isOfficial = (moduleName) => {
				if (!moduleName) return true;
				if (moduleName === "cordis:include" || moduleName === "dshmarket") return true;
				return moduleName.startsWith("@deepseek-ai/");
			};
			const isNarrow = () => mpIsMobile();
			// 全局插件里：自装 14 个默认**展开**（这是用户最关心的），官方 155 个默认**折叠**
			// （否则一屏 171 张卡片，滚半天）。空分组直接隐藏。
			const expanded = { user: true, official: false };
			let ul = null;
			const groupOf = (card) => {
				if (card.dataset.mpGroup) return card.dataset.mpGroup;
				const title = card.querySelector(".qSYn7G_cardTitle");
				const moduleName = (title && (title.getAttribute("title") || title.textContent)) || "";
				const g = isOfficial(moduleName) ? "official" : "user";
				card.dataset.mpGroup = g;
				return g;
			};
			const applyVisibility = () => {
				if (!ul) return;
				const counts = { user: 0, official: 0 };
				ul.querySelectorAll(":scope > .qSYn7G_card").forEach((card) => {
					const g = groupOf(card);
					counts[g] = (counts[g] || 0) + 1;
					card.style.display = expanded[g] ? "" : "none";
				});
				ul.querySelectorAll(":scope > .mp-plugin-group").forEach((h) => {
					const g = h.dataset.group;
					// 空分组隐藏：0.1.5 常出现「用户插件 0」（当前预设未用到自装插件），
					// 留一个点不开的空标题只是噪音。
					const empty = (counts[g] || 0) === 0;
					h.style.display = empty ? "none" : "";
					h.classList.toggle("mp-plugin-group-open", !!expanded[g]);
					const btn = h.querySelector(".mp-plugin-group-btn");
					if (btn) btn.setAttribute("aria-expanded", String(!!expanded[g]));
				});
			};
			const toggle = (g) => {
				expanded[g] = !expanded[g];
				applyVisibility();
			};
			const mkHeader = (g, label, count) => {
				const li = document.createElement("li");
				li.className = "mp-plugin-group";
				li.dataset.group = g;
				const btn = document.createElement("button");
				btn.type = "button";
				btn.className = "mp-plugin-group-btn";
				const lab = document.createElement("span");
				lab.className = "mp-plugin-group-label";
				lab.textContent = label;
				const cnt = document.createElement("span");
				cnt.className = "mp-plugin-group-count";
				cnt.textContent = String(count);
				const chev = document.createElement("span");
				chev.className = "mp-plugin-group-chevron";
				chev.textContent = "▾";
				btn.append(lab, cnt, chev);
				btn.addEventListener("click", () => toggle(g));
				li.appendChild(btn);
				return li;
			};
			// ⚠️ 修复 24（UI 卡死/渲染进程崩溃）：
			// 判据必须用 classList.contains，**绝不能用 className 全等**。
			// 展开分组时 applyVisibility() 会 classList.toggle("mp-plugin-group-open")，
			// 于是 header 的 className 变成 "mp-plugin-group mp-plugin-group-open"，
			// 与全等字面量不再匹配 → groupedSignature 永远返回 false → 每次 mutation
			// 都走 replaceChildren 重建 184 张卡片 → 重建自身又产生 mutation →
			// 无限微任务循环，主线程锁死、WebView renderer 崩溃（用户表现为点卡片后 UI 卡死、
			// 必须重新进入 Web）。折叠态恰好全等所以不复现，只有展开分组后点卡片才触发。
			const isGroupHeader = (el, group) =>
				!!el && el.classList && el.classList.contains("mp-plugin-group") && el.dataset.group === group;
			const groupedSignature = (userCards, officialCards) => {
				const kids = Array.from(ul.children);
				if (kids.length !== userCards.length + officialCards.length + 2) return false;
				if (!isGroupHeader(kids[0], "user")) return false;
				if (!isGroupHeader(kids[1 + userCards.length], "official")) return false;
				for (let i = 0; i < userCards.length; i++) if (kids[1 + i] !== userCards[i]) return false;
				for (let i = 0; i < officialCards.length; i++) if (kids[2 + userCards.length + i] !== officialCards[i]) return false;
				return true;
			};
			// ⚠️ 修复 24 加固：自愈型熔断。
			// 上面"幂等"的成立完全依赖 groupedSignature 判据正确；判据一旦失效
			// （修复 24 正是 className 全等被 -open 类打破），replaceChildren 自身
			// 产生的 mutation 会把 normalize 无限重入下去 → 主线程锁死、renderer 崩溃。
			// 这里做**基于时间窗**的熔断：1 秒内重建超过 10 次即认定不收敛，
			// 暂停分组 2 秒（期间只做 applyVisibility，不再重建结构），然后自动复位重试。
			// 效果：即使判据再次失效，最坏也只是"分组短暂不生效"，绝不会拖死页面。
			let rebuildTimes = [];
			const rebuildAllowed = () => {
				const now = Date.now();
				rebuildTimes = rebuildTimes.filter((t) => now - t < 1000);
				if (rebuildTimes.length >= 10) return false;
				rebuildTimes.push(now);
				return true;
			};
			const normalize = () => {
				if (!isNarrow()) return;
				// 描述注入对两个分区都要做（会话插件也有卡片）
				applyPluginDescriptions(content);
				const section = content.querySelector(".qSYn7G_section");
				if (!section) return;
				// ⚠️ 只接管「全局插件」分区（data-plugin-scope="global"）。
				//     「会话插件」交给官方原样渲染（它只有 28 个官方插件，二分无意义）。
				const globalGroup = section.querySelector('.qSYn7G_group[data-plugin-scope="global"]');
				if (!globalGroup) return;
				const u = globalGroup.querySelector("ul.qSYn7G_cards");
				if (!u) return;            // 折叠态官方不渲染 ul，等展开后再说
				if (u !== ul) ul = u;
				const cards = Array.from(u.querySelectorAll(":scope > .qSYn7G_card"));
				if (cards.length === 0) return;
				const search = section.querySelector("input[type=search]");
				const searching = search && search.value.trim().length > 0;
				if (searching) {
					// 搜索时保持官方扁平过滤列表（React 自己渲染）
					if (u.querySelector(".mp-plugin-group")) {
						u.querySelectorAll(":scope > .mp-plugin-group").forEach((h) => h.remove());
						u.querySelectorAll(":scope > .qSYn7G_card").forEach((c) => { c.style.display = ""; });
					}
					return;
				}
				const userCards = [];
				const officialCards = [];
				cards.forEach((card) => {
					(groupOf(card) === "user" ? userCards : officialCards).push(card);
				});
				if (!groupedSignature(userCards, officialCards) && rebuildAllowed()) {
					u.replaceChildren(
						mkHeader("user", "自装插件", userCards.length),
						...userCards,
						mkHeader("official", "官方插件", officialCards.length),
						...officialCards,
					);
				}
				applyVisibility();
			};
			const observer = new MutationObserver(() => normalize());
			// 同步 normalize：MutationObserver 回调在微任务阶段运行，先于浏览器绘制，
			// 因此 React 渲染完扁平列表后、首帧绘制前就完成分组 → 不闪现旧样式。
			// normalize 幂等：第二次进入时结构已一致，不再改动，不会死循环。
			observer.observe(content, { childList: true, subtree: true });
			normalize();
		}


		// ── 6.5 设置页导航专属图标 ──
		// 官方 navIcon() 只给 models / agent-presets / plugins 三个 id 发专属图标，
		// 其余 id 一律落到 IconSettingsOutline16（齿轮）→ 手机上是一排重复齿轮。
		// 这里按导航项文本匹配，替换 .VOzbGW_navIcon 里的 SVG。
		// 规格对齐官方 Icon*Outline16：viewBox 0 0 16 16、fill:none、stroke:currentColor，
		// 因此暗色模式自动跟随文字色，无需额外适配。
		// ⚠️ 用 stroke 而非 fill，9 个并排才不糊；marker 属性 data-mp-icon 做幂等判据。
		const MP_ICONS = {
			// 通用设置：直接复用官方 IconSettingsOutline16 的原始 SVG（实心 fill）。
			// 自绘齿轮在 16px 下无法做到几何对称（实测自绘版中心偏上 1.8px），
			// 复用官方原始路径既精准又与官方视觉一致。
			general: '<path fill="currentColor" d="M14.0861 5.51366C13.8717 5.0575 13.588 4.58542 13.2889 4.18108C13.208 4.07172 13.1596 4.04373 13.0243 4.03054C12.4277 3.97255 11.8245 4.05527 11.2269 3.9972C10.7224 3.94816 10.3133 3.71661 10.0115 3.30919C9.66986 2.84777 9.43973 2.31343 9.09824 1.85234C9.01771 1.74365 8.96805 1.71589 8.83354 1.70282C8.29432 1.65044 7.70402 1.65061 7.16656 1.70282C7.03205 1.71589 6.98239 1.74365 6.90186 1.85234C6.56067 2.31303 6.33025 2.84774 5.98855 3.30919C5.68681 3.71661 5.27774 3.94816 4.77317 3.9972C4.17564 4.05527 3.57239 3.97255 2.97585 4.03054C2.84046 4.04373 2.79208 4.07172 2.71115 4.18108C2.41212 4.58542 2.12835 5.0575 1.91403 5.51366C1.85299 5.64359 1.85286 5.7018 1.91403 5.8319C2.14865 6.33077 2.49748 6.76892 2.73237 7.26854C2.9594 7.7515 2.96041 8.24717 2.73338 8.73044C2.49837 9.23061 2.14891 9.66837 1.91403 10.1681C1.85291 10.2982 1.85299 10.3564 1.91403 10.4863C2.12856 10.9429 2.41185 11.4142 2.71115 11.8189C2.79208 11.9283 2.84046 11.9563 2.97585 11.9694C3.57239 12.0274 4.17564 11.9447 4.77317 12.0028C5.27774 12.0518 5.68681 12.2834 5.98855 12.6908C6.33024 13.1522 6.56037 13.6866 6.90186 14.1476C6.98239 14.2563 7.03205 14.2841 7.16656 14.2972C7.70402 14.3494 8.29432 14.3495 8.83354 14.2972C8.96805 14.2841 9.01771 14.2563 9.09824 14.1476C9.43944 13.687 9.66985 13.1522 10.0115 12.6908C10.3133 12.2834 10.7224 12.0518 11.2269 12.0028C11.8244 11.9447 12.4271 12.0275 13.0243 11.9694C13.1596 11.9563 13.208 11.9283 13.2889 11.8189C13.5891 11.4131 13.872 10.942 14.0861 10.4863C14.1471 10.3564 14.1472 10.2982 14.0861 10.1681C13.8513 9.66861 13.5017 9.23061 13.2667 8.73044C13.0397 8.24717 13.0407 7.7515 13.2677 7.26854C13.5026 6.7689 13.8513 6.33106 14.0861 5.8319C14.1472 5.7018 14.1471 5.64359 14.0861 5.51366ZM15.3035 6.40373C15.0685 6.90359 14.7188 7.34119 14.4841 7.84037C14.4231 7.97025 14.423 8.02855 14.4841 8.15861C14.7189 8.65833 15.0685 9.09611 15.3035 9.59626C15.5308 10.0801 15.5308 10.5744 15.3035 11.0582C15.052 11.5933 14.7225 12.1426 14.37 12.6191C14.0685 13.0265 13.6581 13.259 13.1536 13.3081C12.5566 13.366 11.9541 13.2835 11.3573 13.3414C11.2228 13.3545 11.1731 13.3823 11.0926 13.491C10.7511 13.9521 10.521 14.4864 10.1793 14.9478C9.87828 15.3542 9.46719 15.5869 8.96387 15.6358C8.34008 15.6964 7.66194 15.6966 7.03623 15.6358C6.53291 15.5869 6.12182 15.3542 5.82084 14.9478C5.47911 14.4863 5.24878 13.9517 4.90753 13.491C4.82701 13.3823 4.77734 13.3545 4.64284 13.3414C4.04647 13.2835 3.44373 13.366 2.84653 13.3081C2.34201 13.259 1.93164 13.0265 1.63013 12.6191C1.27867 12.144 0.948453 11.5941 0.696621 11.0582C0.469315 10.5744 0.469279 10.0801 0.696621 9.59626C0.931628 9.09613 1.2813 8.65807 1.51597 8.15861C1.57708 8.02855 1.57702 7.97025 1.51597 7.84037C1.28117 7.34095 0.931635 6.9036 0.696621 6.40373C0.469213 5.91992 0.469367 5.42562 0.696621 4.94183C0.948441 4.40587 1.27868 3.85598 1.63013 3.38092C1.93164 2.97349 2.34201 2.74095 2.84653 2.6919C3.44353 2.63397 4.04599 2.71649 4.64284 2.65856C4.77734 2.64549 4.82701 2.61774 4.90753 2.50904C5.24905 2.04792 5.47913 1.51362 5.82084 1.05219C6.12182 0.645806 6.53291 0.413119 7.03623 0.364178C7.66002 0.303556 8.33816 0.303369 8.96387 0.364178C9.46719 0.413119 9.87828 0.645806 10.1793 1.05219C10.521 1.51365 10.7513 2.04828 11.0926 2.50904C11.1731 2.61774 11.2228 2.64549 11.3573 2.65856C11.9541 2.71649 12.5566 2.63397 13.1536 2.6919C13.6581 2.74095 14.0685 2.97349 14.37 3.38092C14.7214 3.85598 15.0517 4.40587 15.3035 4.94183C15.5307 5.42562 15.5309 5.91992 15.3035 6.40373Z"/><path fill="currentColor" d="M9.13764 7.99999C9.13764 7.3715 8.62855 6.8624 8.00005 6.8624C7.37155 6.8624 6.86246 7.3715 6.86246 7.99999C6.86246 8.62849 7.37155 9.13759 8.00005 9.13759C8.62855 9.13759 9.13764 8.62849 9.13764 7.99999ZM10.4834 7.99999C10.4834 9.37126 9.37132 10.4833 8.00005 10.4833C6.62878 10.4833 5.51674 9.37126 5.51674 7.99999C5.51674 6.62873 6.62878 5.51669 8.00005 5.51669C9.37132 5.51669 10.4834 6.62873 10.4834 7.99999Z"/>',
			model: '<path d="M2 4.2h4.2M9.4 4.2H14"/><circle cx="7.8" cy="4.2" r="1.6"/><path d="M2 11.8h2.6M7.8 11.8H14"/><circle cx="6.2" cy="11.8" r="1.6"/>',
			image: '<rect x="1.8" y="3" width="12.4" height="10" rx="1.6"/><circle cx="5.6" cy="6.4" r="1.1"/><path d="M2.2 11.2l3-2.6 2.4 2 2.3-2.4 3.7 3.4"/>',
			plugin: '<path d="M6.1 1.6c.9 0 1.6.7 1.6 1.6v1h2.6c.5 0 .9.4.9.9v2.4h1c.9 0 1.6.7 1.6 1.6s-.7 1.6-1.6 1.6h-1v3.1c0 .5-.4.9-.9.9H7.2v-1.1c0-.9-.7-1.6-1.6-1.6s-1.6.7-1.6 1.6v1.1H2.9c-.5 0-.9-.4-.9-.9V9.7h1.1c.9 0 1.6-.7 1.6-1.6S4 6.5 3.1 6.5H2V4.3c0-.5.4-.9.9-.9h3.2z"/>',
			bell: '<path d="M4.3 7.2a3.7 3.7 0 0 1 7.4 0v2.3l1.1 1.9H3.2l1.1-1.9z"/><path d="M6.6 13.2a1.6 1.6 0 0 0 2.8 0"/><path d="M13.4 2.4a6.2 6.2 0 0 1 1.5 2.6M2.6 2.4A6.2 6.2 0 0 0 1.1 5"/>',
			// AI 回复风格：复用官方 IconSparkle16（一大两小凹弧星芒，实心）。
			// AI 的通用视觉标识，比自绘的「气泡+三点」更贴题，且与品牌一致。
			style: '<path fill="currentColor" d="M6.1 3.1Q6.6 7.8 11.3 8.3Q6.6 8.8 6.1 13.5Q5.6 8.8 0.9 8.3Q5.6 7.8 6.1 3.1Z"/><path fill="currentColor" d="M11.9 1Q12.2 3.7 14.9 4Q12.2 4.3 11.9 7Q11.6 4.3 8.9 4Q11.6 3.7 11.9 1Z"/><path fill="currentColor" d="M12.5 9.4Q12.7 11.4 14.7 11.6Q12.7 11.8 12.5 13.8Q12.3 11.8 10.3 11.6Q12.3 11.4 12.5 9.4Z"/>',
			agent: '<rect x="3" y="5.4" width="10" height="8" rx="2"/><path d="M8 5.4V3.2"/><circle cx="8" cy="2.2" r="1"/><circle cx="6.1" cy="9.1" r="1"/><circle cx="9.9" cy="9.1" r="1"/><path d="M6.3 11.6h3.4"/>',
			im: '<rect x="2" y="2.4" width="12" height="8.6" rx="2.2"/><path d="M5.4 14.2l1.6-3.2"/><circle cx="6" cy="6.7" r="0.95"/><circle cx="8" cy="6.7" r="0.95"/><circle cx="10" cy="6.7" r="0.95"/>',
			market: '<path d="M2 6.6V4.8l.9-2.2c.2-.4.5-.6 1-.6h8.2c.5 0 .8.2 1 .6l.9 2.2v1.8"/><path d="M2 6.6h12v1.6c0 1.2-1 2.2-2.2 2.2S9.6 9.4 9.6 8.2c0 1.2-1 2.2-2.2 2.2S5.2 9.4 5.2 8.2C5.2 9.4 4.2 10.4 3 10.4S2 9.4 2 8.2z"/><path d="M3.4 10.6v3.2c0 .5.4.9.9.9h7.4c.5 0 .9-.4.9-.9v-3.2"/><path d="M6.6 14.7v-3h2.8v3"/>',
			// 特权权限（uid 2000）：自绘「盾牌 + 钥匙孔」。
			// 语义是「被管住的提权」—— 盾牌代表边界还在（该身份反而没有沙箱），
			// 钥匙孔代表拿得到特权。用描边风格（同 plugin/model 等），
			// 与「通用设置/AI 回复风格」那两个实心官方图标区分开。
			privileged: '<path d="M8 1.8l5.6 2.1v4.5c0 3.2-2.4 5.6-5.6 6.5-3.2-.9-5.6-3.3-5.6-6.5V3.9z"/><circle cx="8" cy="6.9" r="1.35"/><path d="M8 8.25V10.7"/>',
			// 悬浮气泡：圆（气泡本体）+ 一条底线（屏幕/别的应用）。
			// 语义是「浮在其它应用之上」—— 这也是气泡与「IM机器人」那个
			// 「气泡+三点」的区别所在：这里表达的是**层级**，不是对话。
			bubble: '<circle cx="8" cy="6.5" r="4.3"/><path d="M2.2 13.7h11.6"/>',
			// 交流群：双人。前面一个是完整的头+肩，后面那个只画半侧轮廓，
			// 靠「被挡住」表现多人 —— 16px 下画两个完整人会糊成一团。
			community: '<circle cx="5.9" cy="5.9" r="2.5"/><path d="M1.5 13.4c0-2.4 2-4.4 4.4-4.4s4.4 2 4.4 4.4"/><path d="M10.7 3.8a2.5 2.5 0 0 1 0 4.6"/><path d="M12 9.4c1.4.6 2.3 2 2.3 3.8"/>',
		};
		// 文本 → 图标键。先长后短匹配，避免「插件」抢走「插件市场」。
		const MP_NAV_ICONS = [
			["通用设置", "general"],
			["图片输入", "image"],
			["AI 回复风格", "style"],
			["AI回复风格", "style"],
			["Agent 预设", "agent"],
			["Agent预设", "agent"],
			["IM机器人", "im"],
			["IM 机器人", "im"],
			["插件市场", "market"],
			["模型", "model"],
			["插件", "plugin"],
			["离开提醒", "bell"],
			// 本插件自己注册的分节。⚠️ 必须排在「插件」之后：mpIconKeyOf 是
			// 子串匹配、先到先得，「特权权限」不含「插件」所以不冲突，但顺序仍
			// 保持「长/具体优先」的既有约定。
			["特权权限", "privileged"],
			["Privileged access", "privileged"],
			// 本插件自己注册的另两个分节（悬浮气泡 26 / 交流群 27）。
			// ⚠️ 同样必须排在「插件」之后才不会被它的子串匹配抢先；
			//   这两个词本身不含「插件」，所以只是沿用既有顺序约定。
			["悬浮气泡", "bubble"],
			["Floating bubble", "bubble"],
			["交流群", "community"],
			["Community group", "community"],
		];
		const mpIconKeyOf = (label) => {
			const t = (label || "").trim();
			if (!t) return null;
			for (const [needle, key] of MP_NAV_ICONS) if (t.includes(needle)) return key;
			return null;
		};
		// 实心图标（复用官方路径）需 fill，自绘的 8 个走 stroke。按 key 区分。
		const MP_FILL_ICONS = { general: true, style: true };
		const mpMakeIcon = (key) => {
			const body = MP_ICONS[key];
			if (!body) return null;
			const NS = "http://www.w3.org/2000/svg";
			const svg = document.createElementNS(NS, "svg");
			svg.setAttribute("width", "16");
			svg.setAttribute("height", "16");
			svg.setAttribute("viewBox", "0 0 16 16");
			// 根节点始终 fill="none"：实心图标靠 path 自带的 fill="currentColor"，
			// 描边图标靠这里的 stroke 属性，互不干扰。
			svg.setAttribute("fill", "none");
			if (!MP_FILL_ICONS[key]) {
				svg.setAttribute("stroke", "currentColor");
				svg.setAttribute("stroke-width", "1.4");
				svg.setAttribute("stroke-linecap", "round");
				svg.setAttribute("stroke-linejoin", "round");
			}
			svg.setAttribute("aria-hidden", "true");
			svg.style.flex = "none";
			svg.innerHTML = body;
			return svg;
		};
		function setupNavIcons(nav) {
			if (!mpIsMobile()) return; // 修复 40：仅移动端；桌面端完全走官方
			const navList = nav.querySelector(".VOzbGW_navList");
			if (!navList || navList.dataset.mpIcons === "1") return;
			navList.dataset.mpIcons = "1";
			// 幂等归一化：结构已一致就完全不动，避免 observer 自触发死循环。
			const normalize = () => {
				navList.querySelectorAll("button").forEach((btn) => {
					const label = btn.querySelector(".VOzbGW_navLabel");
					const key = mpIconKeyOf(label ? label.textContent : btn.innerText);
					if (!key) return;
					// 官方图标 slot：className 含 navIcon
					const slot = btn.querySelector('[class*="navIcon"]');
					if (!slot) return;
					if (slot.dataset.mpIcon === key && slot.firstElementChild) return; // 已是目标态
					const svg = mpMakeIcon(key);
					if (!svg) return;
					slot.replaceChildren(svg);
					slot.dataset.mpIcon = key;
				});
			};
			// 观察子树：官方 React 可能只重渲染某个 button 的图标 slot（不重建 button），
			// 只观察直接 childList 会漏掉这种情况 → 图标永久丢失。
			// 防自我触发：normalize 的判据是「slot 已带正确 marker 且含 svg 就完全不动」，
			// 因此本函数自己造成的 mutation 在下一轮会立即早退，不会无限循环。
			// 另加 reentry 闸：回调内部同步执行，防御性避免极端情况下的重入。
			let running = false;
			const observer = new MutationObserver(() => {
				if (running) return;
				running = true;
				try { normalize(); } finally { running = false; }
			});
			observer.observe(navList, { childList: true, subtree: true });
			normalize();
		}

		// ── 7. 设置页两级化（全 inline style，不用 CSS 规则——见 CSS 区注释） ──
		function setupSettingsDialog(dialog) {
			if (!mpIsMobile()) return; // 修复 40：仅移动端；桌面端完全走官方
			if (dialog.dataset.mpReady) return;
			dialog.dataset.mpReady = "1";
			const nav = Array.from(dialog.children).find((c) => c.tagName === "NAV");
			if (!nav) return;
			dialog.dataset.mpSettings = "1";
			// 隐藏安卓壳遗留的"开发者选项"：其功能依赖 window.androidBridge /
			// /api/android/*，在 Web 进程中不存在，保留会进入一个空壳页面。
			nav.querySelectorAll("button").forEach((b) => {
				if ((b.innerText || "").trim() === "开发者选项") b.style.display = "none";
			});
			const content = nav.nextElementSibling;
			setupPluginGrouping(content);
			setupNavIcons(nav);
			const isNarrow = () => mpIsMobile();
			// — 全屏 + 纵向布局（避免旧样式闪烁：立即应用，不等动画结束） —
			Object.assign(dialog.style, {
				width: "100vw", maxWidth: "100vw", height: "100vh", maxHeight: "100vh",
				borderRadius: "0", boxShadow: "none", position: "fixed", top: "0", left: "0",
				flexDirection: "column", overflow: "hidden",
			});
			const overlay = dialog.parentElement;
			if (overlay) {
				overlay.style.left = "0";
				overlay.style.width = "100vw";
				overlay.style.height = "100vh";
				overlay.style.alignItems = "stretch";
			}
			const navTitle = nav.querySelector(".VOzbGW_navTitle");
			if (navTitle) navTitle.style.display = "none";
			// 顶部返回条：只有圆形返回按钮 + 标题，不再放 ✕
			const backBar = document.createElement("div");
			backBar.className = "mp-back-bar";
			backBar.innerHTML = '<button type="button" class="mp-back-circle" aria-label="返回">‹</button>'
				+ '<span class="mp-title">设置</span>';
			Object.assign(backBar.style, {
				display: "flex", flex: "0 0 auto", alignItems: "center", gap: "4px",
				minHeight: "56px", padding: "8px 12px", boxSizing: "border-box",
				borderBottom: "none", background: "transparent", position: "relative",
			});
			// 回退 CSS 规则：用 !important 压内联 padding，让出状态栏
			const sysTop = getComputedStyle(document.documentElement).getPropertyValue("--dsh-android-system-top").trim() || "0px";
			backBar.style.setProperty("padding-top", sysTop, "important");
			const backBtn = backBar.querySelector(".mp-back-circle");
			Object.assign(backBtn.style, {
				width: "36px", height: "36px", borderRadius: "50%", background: "var(--dsw-alias-interactive-bg-hover, #f0f0f0)",
				border: "none", cursor: "pointer", display: "inline-flex", alignItems: "center",
				justifyContent: "center", fontSize: "20px", color: "#222", flex: "0 0 auto",
			});
			const titleSpan = backBar.querySelector(".mp-title");
			Object.assign(titleSpan.style, {
				position: "absolute", left: "50%", top: "50%", transform: "translate(-50%, -50%)",
				maxWidth: "70%", fontSize: "17px", fontWeight: "600",
				color: "var(--dsw-alias-label-primary, #222)", whiteSpace: "nowrap",
				overflow: "hidden", textOverflow: "ellipsis", padding: "0 4px",
			});
			dialog.insertBefore(backBar, nav);
			const closeDialog = () => {
				const mask = dialog.parentElement?.querySelector(".VOzbGW_mask");
				if (mask) mask.click();
			};
			// 返回按钮：一级页关闭设置，二级页回一级
			const showList = () => {
				titleSpan.textContent = "设置";
				// 用 !important 压过 dsh-market 的
				//   @media (max-width:560px){ [role=dialog]:has([data-dsh-market-root])>nav{display:none} }
				// 市场组件（data-dsh-market-root）挂载后一直留在 dialog 里，:has() 只看 DOM
				// 存在性不看可见性，返回列表页时普通内联 display 会被该规则覆盖 → 列表页空白。
				nav.style.setProperty("display", "flex", "important");
				if (content) content.style.display = "none";
				applyNavStyle();
			};
			const showContent = (titleText) => {
				const sectionTitle = content?.querySelector(".zGbnIq_title, h2");
				titleSpan.textContent = titleText || (sectionTitle ? sectionTitle.textContent : "设置");
				// 与 showList 对称：用 !important，确保能覆盖 showList 留下的 flex !important
				nav.style.setProperty("display", "none", "important");
				if (content) {
					content.style.display = "flex";
					content.style.flexDirection = "column";
					content.style.flex = "1 1 0%";
					content.style.minHeight = "0";
					content.style.overflowY = "auto";
				}
				const options = content?.querySelector(".VOzbGW_options");
				if (options) {
					options.style.flex = "1 1 0%";
					options.style.minHeight = "0";
					options.style.overflowY = "auto";
				}
			};
			// 设置页的「退一层」语义（二级 → 一级 → 关闭），返回按钮与**系统返回键**共用。
			// 系统返回键由原生经 `window.__DSH_MOBILE_BACK__` 调进来，见 §12。
			const backOneLevel = () => {
				const atList = !content || content.style.display === "none";
				if (atList) closeDialog();
				else showList();
			};
			backBtn.addEventListener("click", backOneLevel);
			// ⚠️ 用 WeakMap 而不是 DOM 属性存回调：DOM 属性只能存字符串，
			//   而这里要存的是带闭包的函数（`content`/`closeDialog` 都在闭包里）。
			mpDialogBack.set(dialog, backOneLevel);
			// nav 竖向化 + 分类小标题 + 可滚动
			const applyNavStyle = () => {
				if (!isNarrow()) return;
				nav.style.flexDirection = "column";
				nav.style.alignItems = "stretch";
				nav.style.overflowY = "auto";
				nav.style.overflowX = "hidden";
				nav.style.width = "100%";
				nav.style.gap = "2px";
				nav.style.padding = "10px";
				nav.style.flex = "1 1 0%";
				nav.style.minHeight = "0";
				const navList = nav.querySelector(".VOzbGW_navList");
				if (navList) {
					navList.style.flex = "1 1 0%";
					navList.style.minHeight = "0";
					navList.style.overflowY = "auto";
				}
				if (navList && !navList.querySelector("[data-mp-group]")) {
					const groups = [
						{ label: "通用", items: ["通用设置"] },
						{ label: "模型", items: ["模型"] },
						{ label: "插件", items: ["插件"] },
						{ label: "高级", items: ["Agent 预设", "快照", "附件缓存", "特权权限", "悬浮气泡"] },
					];
					for (const g of groups) {
						const first = [...navList.querySelectorAll("button")].find((b) => g.items.includes((b.innerText || "").trim()));
						if (!first) continue;
						const label = document.createElement("div");
						label.className = "mp-nav-group";
						label.dataset.mpGroup = "1";
						label.textContent = g.label;
						label.style.cssText = "padding:14px 16px 4px;font-size:12px;font-weight:600;color:var(--dsw-alias-label-secondary,#888);";
						navList.insertBefore(label, first);
					}
				}
				Array.from(nav.children).forEach((c) => {
					if (c.querySelector && c.querySelector("button")) {
						c.style.flexDirection = "column";
						c.style.overflowX = "hidden";
						c.style.gap = "2px";
						c.style.flex = "1 1 0%";
						c.style.minWidth = "0";
					}
				});
				nav.querySelectorAll("button").forEach((b) => {
					b.style.width = "100%";
					b.style.minHeight = "50px";
					b.style.justifyContent = "flex-start";
					b.style.padding = "0 16px";
					b.style.borderRadius = "12px";
					b.style.fontSize = "15px";
					b.style.flex = "none";
					b.style.textAlign = "left";
					// 移动端一级列表不显示"当前项"高亮（官方 .VOzbGW_navCell.VOzbGW_active
					// 的背景色块）：二级页是全屏的，nav 根本看不见，回到列表时这行残留的
					// 色块只会让人误以为"刚才那下没退掉"（用户 2026-09-14 反馈）。
					// ⚠️ 必须用内联样式：匹配设置 dialog 的 CSS 规则会把 dialog 推出屏幕
					// （x=-301、关不掉），此处禁用 CSS 规则。内联样式优先级高于该类规则，
					// 且官方 React 不管理 background 属性，重渲染不会把它冲掉。
					b.style.background = "transparent";
				});
			};
			const contentHeader = content?.querySelector(".VOzbGW_header");
			if (contentHeader) contentHeader.style.display = "none";
			nav.addEventListener("click", (e) => {
				const btn = e.target.closest("button");
				if (!btn) return;
				const titleText = (btn.innerText || "").trim();
				setTimeout(() => showContent(titleText || "设置"), 0);
			});
			// 立即应用，去掉 800ms 延迟（旧样式闪烁根因）
			applyNavStyle();
			showList();
		}

			// ── 8. 移动端外壳（0.1.5 融合版）──
			// 官方 0.1.5 的窄屏形态只是把侧栏压成 56px 图标条（正文仅 334px）；
			// 旧版自带的抽屉/手机顶栏来自已停用的 @dsh-android/dsh-client-ui-responsive
			// （它在新版槽位校验下渲染即抛异常 → 整页白屏，故弃用）。
			// 融合方案：保留官方骨架 —— 正文占满全宽、侧栏改为浮层抽屉，
			// 官方 header（会话标题/模式/⋯ 菜单含「下载 Session 日志」/右侧栏按钮）
			// 与 对话/轨迹 tabs 原样保留，不再自建顶栏与 ⋮ 菜单。
			const MP_NARROW = 1024;   // 与官方 SIDEBAR_AUTO_COLLAPSE 一致
			let mpShellBuilt = false;
			// ⭐ 修复 47（2026-09-17 啾灵反馈）：汉堡节点的**自持引用**。
			//   官方 React 提交阶段会把 header 的子节点整个 removeChild 掉（实测栈：React j4/xt），
			//   锚在 header 里的汉堡被连带摘走 → detached；而重挂路径用 querySelector 查它，
			//   查询**只匹配已连接节点** → 查不到 → 早退 → 汉堡永远回不来。
			//   自持引用后，detached 也能无损重挂（监听器都还在节点上）。
			var mpNavBtnEl = null;
			// 关闭动画进行中的闸（修复 25）：期间 mpSyncDrawer 不得把抽屉又拉回来；
			// 关闭中再点汉堡 = 取消关闭、直接重开。
			let mpClosing = false;
			let mpCloseTimer = null;
			// 修复 27：设置弹窗打开期间的「视觉收起」标记 —— 此时只把抽屉滑出屏幕，
			// 绝不点官方 toggle（官方 railIn 动画会给 .hHd-Xa_footArea 加 opacity:0，
			// 而设置弹窗的 DOM 就住在 footArea 里 → 整页隐身 150ms → 闪过对话界面）。
			// 官方收起推迟到弹窗关闭后由 mpSyncDrawer 补做（morph 在屏幕外，看不见）。
			let mpModalClose = false;
			// 修复 56：等待官方侧栏收起期间用的观察器与兜底定时器。
			let mpCollapseObs = null;
			let mpCollapseFallback = null;
			// §12：设置弹窗 → 它的「退一层」回调。用 WeakMap 而非 DOM 属性，
			//   因为存的是带闭包的函数（`content`/`closeDialog` 都在 setupSettingsDialog 的闭包里）。
			const mpDialogBack = new WeakMap();

			function mpIsNarrow() { return window.innerWidth < MP_NARROW; }

			// ══ 修复 40：移动端模式总开关 ══
			// 啾灵要求：「手机访问就用我们现在适配的UI，然后电脑那边完全不影响」。
			// ⚠️ 只判宽度不够：桌面浏览器窗口完全可以 <1024（未最大化时），
			//    那样"缩小窗口的电脑"也会被套上手机 UI。
			// 判据 = 窄屏 **且** 不是「悬停可用 + 精确指针」设备（= 确有鼠标/触控板）。
			//   · 这个判据**只做减法**：手机绝不会同时报告 hover:hover + pointer:fine，
			//     所以对真机零风险；
			//   · 任何鼠标设备无论窗口多窄，都保持官方 UI（正是她要的"完全不影响"）；
			//   · matchMedia 缺失/抛错 → 失败**开放**（退回纯宽度判据），
			//     绝不因为判据故障让手机失去适配。
			// ⚠️ 用 var（不是 const）：下面的闭包可能在模块常量初始化前被调用，const 会撞 TDZ。
			var mpDesktopMq;

			function mpIsPointerDesktop() {
				try {
					if (mpDesktopMq === undefined) {
						mpDesktopMq = window.matchMedia
							? window.matchMedia("(hover: hover) and (pointer: fine)") : null;
					}
					if (mpDesktopMq) return !!mpDesktopMq.matches;
					return !!(window.matchMedia
						&& window.matchMedia("(pointer: fine)").matches
						&& window.matchMedia("(hover: hover)").matches);
				} catch (e) { return false; }
			}
			function mpIsMobile() {
				if (!mpIsNarrow()) return false;
				if (mpIsPointerDesktop()) return false;
				return true;
			}
			

			// data-mobile：旧版由 ui-responsive 设置，新版无人设置，而本插件所有 CSS
			// 规则都挂在 [data-mobile] 下（桌面端因此完全不受影响）。
			// ══════════════════════════════════════════════════════════════════
		// 修复 55：失效 CSSModule 类名的「别名桥接」
		//
		// 官方 CSS Module 的类名是 `{hash}_{local}`（如 ZTP-Xa_frame），hash 每次
		// 发版都会变，而本插件的 CSS 与 JS 选择器全部按旧 hash 写死 → 换版本后
		// 样式与交互同时失效（实测 [class*="pI_x6G"] 命中 0 个元素，布局退化成
		// 「56px 图标条 + 334px 正文」，抽屉压窄正文、按钮换行错乱）。
		//
		// 做法：不重写那 37 条 CSS 规则、也不改 ~40 处 JS 选择器，而是**给现有
		// 元素补上旧类名**。局部名（frame/sessionRow/toggle…）是稳定的，所以按
		// 「局部名投票」就能把旧 hash 推导到当前 hash；一旦推导出来，原有规则与
		// 选择器零改动全部复活。官方下次再换 hash 也会自动重新推导。
		//
		// MP_LEGACY_HASHES 由构建脚本从本文件源码自动提取（含只出现在 JS 里的
		// 局部名，如 hHd-Xa_root / hHd-Xa_collapsed —— 手写极易漏，v1 就漏了它
		// 导致 mpOfficialDrawerOpen() 恒 false、抽屉打不开）。
		// ══════════════════════════════════════════════════════════════════

		const MP_LEGACY_HASHES = {
						"Nqubda": [
									"panel"
						],
						"VOzbGW": [
									"active",
									"header",
									"mask",
									"navCell",
									"navIcon",
									"navLabel",
									"navList",
									"navTitle",
									"options"
						],
						"Y0dWHa": [
									"detailBody",
									"details",
									"detailsLocation",
									"overviewPreview",
									"split"
						],
						"YDXeBa": [
									"chevron",
									"folder",
									"iconButton",
									"menuOpen",
									"projectRow",
									"rowActions",
									"searchResultRow",
									"selected",
									"sessionRow",
									"time"
						],
						"bOPqQW": [
									"anchor",
									"label",
									"pill",
									"root"
						],
						"bhn1Oq": [
									"iconButton",
									"searchButton"
						],
						"hHd-Xa": [
									"collapsed",
									"footArea",
									"iconButton",
									"newSession",
									"root",
									"toggle"
						],
						"nL4_yW": [
									"moreButton"
						],
						"pI_x6G": [
									"centerCol",
									"frame",
									"rightbarCol",
									"sidebarCol"
						],
						"qSYn7G": [
									"card",
									"cardDetails",
									"cardTitle",
									"cards",
									"details",
									"group",
									"section"
						],
						"uV2eYG": [
									"grow",
									"input",
									"root"
						],
						"wSkVaW": [
									"crumb",
									"crumbCurrent",
									"crumbs",
									"header",
									"headerActions",
									"headerCorner",
									"headerUtilities",
									"tab",
									"tabs",
									"titleCluster",
									"titleRow",
									"viewArea"
						],
						"xzv4MW": [
									"timeEnd"
						],
						"zGbnIq": [
									"title"
						]
			};

		/** 旧类名 → 当前类名 的映射（运行时推导，官方换 hash 后自动更新）。 */
		let mpPairIndex = null;
		let mpAliasDirty = true;
		let mpAliasSig = '';   // 上次生效的映射指纹，用于去重日志与重扫

		/** 桥接自己补上去的旧类名。用来防止「自我强化」，见 [mpCollectCurrentClasses]。 */
		const mpAliasAdded = new Set();

		/**
		 * 官方「仍在用」的 hash 累积集合（**只增不减**）。
		 *
		 * ⚠️ 为什么必须累积、不能只看当下快照（2026-10-04 第二次踩这个坑）：
		 *   官方 CSS Module 是**按需注入**的 —— 组件没渲染时它的 `<style>` 压根不在
		 *   `document.styleSheets` 里。若判据只看当下快照，同一个 hash 会在「出现过」
		 *   与「没出现」之间来回抖动，于是**同一个页面上有的分组头中招、有的正常**
		 *   （真机实测：22 个 `.O_Ebla_root` 里有 2 个被误加 `bOPqQW_root`，其余正常）。
		 *   只要某个 hash **曾经**出现在官方样式表里，就说明官方在用它，永久不再认领。
		 *   真正过期的 hash 永远不会出现在官方样式表里，因此不受影响。
		 */
		const mpOfficialSeen = new Set();

		/**
		 * 预热轮次计数：前 [MP_ALIAS_WARMUP] 轮**只累积证据、不做任何认领**。
		 *
		 * ⚠️ 为什么需要（2026-10-04）：官方 CSS Module 是懒注入的，页面刚加载时
		 *   `document.styleSheets` 里只有少数几张表，`mpOfficialSeen` 因此接近空集。
		 *   若此时就允许认领，**没过期的** hash（如 `YDXeBa`）会因为「当下没证据」
		 *   而被判为过期，转去认领别的 hash —— 真机日志实测：
		 *     `CSSModule 别名桥接: YDXeBa→pXSMma (2 个类名)`
		 *     `CSSModule 别名桥接: YDXeBa→oz9t_a (2 个类名)`
		 *   这与 `bOPqQW→O_Ebla` 是同一个病，只是发生在启动早期。
		 *   预热期的长度取自 `mpInstallAliasBridge` 的 600ms 轮询节奏，约 9 秒。
		 */
		const MP_ALIAS_WARMUP = 15;
		let mpAliasRound = 0;

		/**
		 * 采集当前存在的全部 `{hash}_{local}` 类名，返回 `byHash`（样式表 + DOM 的并集，用于投票）。
		 *
		 * ⚠️ 两个来源必须区别对待（2026-10-04 踩的坑）：桥接会把旧类名**补到 DOM 元素**上，
		 *   若把这些补上去的类名也算作证据，下一轮推导就会「自己证明自己」，越套越偏。
		 *   样式表是官方产物、桥接从不写入，所以它是唯一可信的
		 *   「官方仍在用这个 hash」判据 —— 且必须**累积**记录到 [mpOfficialSeen]。
		 */
		function mpCollectCurrentClasses() {
			const byHash = {};
			const add = (cls, fromSheet) => {
				// ⚠️ hash 字符集必须含 "-" 与 "_"，且可能以下划线开头
				//   （官方实测形如 ZTP-Xa、n_2Q3W、pI_x6G、_0gBrGq）。
				const mm = /^([A-Za-z0-9_][A-Za-z0-9_-]{2,13})_([A-Za-z][A-Za-z0-9]*)$/.exec(cls);
				if (!mm) return;
				/* 官方样式表里出现过 ⇒ 官方在用这个 hash。**累积**记录，见 [mpOfficialSeen]。 */
				if (fromSheet) mpOfficialSeen.add(mm[1]);
				// 跳过桥接自己补的类名：它们不是官方证据
				if (!fromSheet && mpAliasAdded.has(cls)) return;
				(byHash[mm[1]] || (byHash[mm[1]] = {}))[mm[2]] = true;
			};
			// ① 全部样式表（含尚未挂载组件的规则 —— 拿到完整类名域的关键）
			for (const sheet of document.styleSheets) {
				let rules;
				try { rules = sheet.cssRules; } catch (e) { continue; }
				if (!rules) continue;
				/* ⚠️ 必须跳过**插件自己**的样式表：本插件的 CSS 大量使用旧类名
				 *   （如 `[data-mobile] .bOPqQW_root`），若把它当作「官方仍在用」的
				 *   证据，所有旧 hash 都会被判为未过期，桥接彻底失效。
				 *   ⚠️ 判据必须是**全等**比较：官方 DSH 自己也给每个 CSS Module 的
				 *   `<style>` 打 `dataset.pluginCss`（值是模块路径），
				 *   只判 `dataset.pluginCss` 是否存在会误伤 140 个官方样式表。 */
				const owner = sheet.ownerNode;
				if (owner && owner.dataset &&
					owner.dataset.pluginCss === "dsh-android-mobile/main.css") continue;
				const walk = (rs) => {
					for (const r of rs) {
						if (r.cssRules) { walk(r.cssRules); continue; }
						const sel = r.selectorText;
						if (!sel) continue;
						for (const mm of sel.matchAll(/\.(-?[A-Za-z0-9][A-Za-z0-9_-]*)/g)) add(mm[1], true);
					}
				};
				try { walk(rules); } catch (e) { /* 单条异常不影响其余 */ }
			}
			// ② DOM（fromSheet=false：其中的旧类名可能是桥接自己补的，不能当证据）
			for (const el of document.querySelectorAll('[class]')) for (const cls of el.classList) add(cls, false);
			return { byHash };
		}

		/**
		 * 推导「旧 hash → 当前 hash」。
		 * 判据：统计每个旧 hash 的局部名在各新 hash 上的命中数，取最高者；
		 *   · 并列 → 放弃（套错比不套更糟，例如 bOPqQW 与 hHd-Xa 都可能命中同一个）
		 *   · **命中必须 ≥2** → 才采纳
		 *   · 同一新 hash 只允许被一个旧 hash 认领
		 *
		 * ⚠️ 2026-10-03 修复：原判据有一条 `|| locals.length === 1` 的豁免
		 *   （「该旧 hash 只有 1 个局部名时，1 票即可」）。这条豁免在
		 *   `Nqubda: ["panel"]` 上炸了 —— `panel` 是极通用的局部名，官方
		 *   右侧栏的 dockkit 面板（`P3OORG_panel`）也用它，于是桥接把
		 *   `Nqubda_panel` 补到了**那个面板**上；而 §5 的审批浮层规则
		 *   `[data-mobile] .Nqubda_panel` 随即把它变成 fixed 浮层，
		 *   盖住整个上半屏（实测 `[24,0,336,458]`），连「添加一个 API Key」
		 *   对话框都被压在下面 → 用户看到半屏白块。
		 *   同类隐患还有 `zGbnIq: ["title"]`（title 同样通用）。
		 *
		 *   豁免取消后，单名条目一律不参与推导；需要它们的场合改为在 JS 里
		 *   用「当前 hash」直接定位（见 mpCurrentHash 相关辅助）。
		 */
		function mpDeriveAliasMap() {
			const { byHash: cur } = mpCollectCurrentClasses();
			/* 预热期内只累积 `mpOfficialSeen`（上面的采集已完成），不认领任何映射。
			 * 此时官方样式表还没注入齐，认领必然是误伤。见 [MP_ALIAS_WARMUP]。 */
			if (mpAliasRound <= MP_ALIAS_WARMUP) return { map: {}, pairs: [] };
			const owners = {};
			for (const h of Object.keys(cur)) for (const l of Object.keys(cur[h])) (owners[l] || (owners[l] = [])).push(h);
			const map = {};
			const used = {};
			for (const [oldHash, locals] of Object.entries(MP_LEGACY_HASHES)) {
				/* ⚠️ 官方样式表里**曾经**出现过这个 hash ⇒ 它没有过期，不能拿它去认领别人。
				 *
				 * 2026-10-04 踩的坑：`bOPqQW`（composer 遥测药丸）既登记在旧表里，
				 * 又是官方当前仍在用的 hash。原逻辑无条件把它从候选池剔除，于是它
				 * 丢掉了自己的 4 票证据，转而去认领了恰好共享 `label` + `root` 两个
				 * 局部名的**无关** hash `O_Ebla`（2 票，刚好够门槛）——
				 * 而 `O_Ebla_root` 是「执行了命令」分组头，`bOPqQW_root` 带
				 * `justify-content: center`，于是那行标题被居中、不再左对齐。
				 *
				 * ⚠️ 判据必须用**累积**集合 `mpOfficialSeen` 而非当下快照：
				 *   官方 CSS Module 按需注入，未渲染组件的规则此刻不在表里。
				 *   用快照会让同一 hash 在「出现过 / 没出现」之间抖动，表现为
				 *   **同一个页面上有的分组头中招、有的正常**（真机实测 22 个
				 *   `.O_Ebla_root` 里只有 2 个被误加）。累积后结论稳定。
				 *
				 * ⚠️ 判据必须是「样式表 **或** DOM」两者取或，且 DOM 侧必须排除桥接自己补的类名：
				 *   官方类名可能**先出现在 DOM、样式表随后才懒注入**。只看样式表会让
				 *   没过期的 hash 在注入空窗期被判成过期 —— 真机日志实测：
				 *     `CSSModule 别名桥接: YDXeBa→oz9t_a (2 个类名)`
				 *   `YDXeBa` 是 workspace 的当前 hash（官方样式表里 36 个局部名齐全），
				 *   却因为那一刻样式表还没到而被误判。这与 `bOPqQW→O_Ebla` 同病。 */
				if (mpOfficialSeen.has(oldHash) || cur[oldHash]) continue;
				const votes = {};
				for (const l of locals) for (const h of (owners[l] || [])) votes[h] = (votes[h] || 0) + 1;
				const ranked = Object.entries(votes).sort((a, b) => b[1] - a[1]);
				if (ranked.length === 0) continue;
				const [bestHash, hit] = ranked[0];
				if (ranked.filter(([, v]) => v === hit).length > 1) continue;
				/* 只认证据充分的映射：至少 2 个局部名同时命中同一个新 hash */
				if (hit < 2) continue;
				/* ⚠️ 光有 2 票远远不够 —— 必须要求**局部名集合高度重合**。
				 *
				 * 2026-10-04 真机日志实测的误伤：`YDXeBa→oz9t_a (2 个类名)`。
				 * `YDXeBa` 是 workspace 的**当前** hash（官方样式表里 36 个局部名齐全），
				 * 只是它的组件（侧栏）那一刻还没渲染、样式表也还没懒注入，于是
				 * 「当下无证据」被判成了「已过期」。它转去认领 `oz9t_a`，只因两者
				 * 偶然共享 `time` + `chevron` 两个**通用**局部名，把 `YDXeBa_time`
				 * 补到了折叠轮次的时间戳上。
				 *
				 * 真正的「过期」是**同一个组件换了 hash**，局部名集合基本不变
				 * （`bOPqQW` 换成新 hash 时 anchor/label/pill/root 仍全在）。
				 * 所以用**重合率**作判据：命中数要占双方较小集合的 60% 以上。
				 *   · `YDXeBa`(10) vs `oz9t_a`(10)，命中 2 → 20% ⇒ 拒绝
				 *   · 真换 hash 时局部名几乎全在 ⇒ 接近 100% ⇒ 采纳
				 */
				const otherSize = Object.keys(cur[bestHash] || {}).length;
				const denom = Math.min(locals.length, otherSize);
				if (denom === 0 || hit / denom < 0.6) continue;
				/* 该新 hash 若自身也在旧表里登记过（即它也是官方当前 hash），
				 * 说明它属于另一个组件，不该被认领。 */
				if (MP_LEGACY_HASHES[bestHash]) continue;
				if (used[bestHash]) continue;
				used[bestHash] = true;
				map[oldHash] = bestHash;
			}
			const pairs = [];
			for (const [oldHash, newHash] of Object.entries(map)) {
				for (const l of MP_LEGACY_HASHES[oldHash]) {
					if (cur[newHash] && cur[newHash][l]) pairs.push([`${newHash}_${l}`, `${oldHash}_${l}`]);
				}
			}
			return { map, pairs };
		}

		function mpRefreshAliasMap(force) {
			if (!force && !mpAliasDirty) return;
			const { map, pairs } = mpDeriveAliasMap();
			mpAliasDirty = false;
			const sig = pairs.map((p) => p.join('=')).sort().join(',');
			if (sig === mpAliasSig) return;   // 映射未变 → 不重复打日志
			mpAliasSig = sig;
			mpPairIndex = new Map(pairs);
			mpPruneStaleAliases();            // 撤掉上一轮误加、现已不再成立的类名
			const desc = Object.entries(map).map(([o, n]) => `${o}→${n}`).join(' ');
			if (desc) console.log('[mobile-polish] CSSModule 别名桥接:', desc, `(${pairs.length} 个类名)`);
		}

		/**
		 * 撤掉「已补上去、但当前映射里不再成立」的旧类名。
		 *
		 * ⚠️ 为什么必须有（2026-10-04）：推导判据修正后，之前误加的类名**不会自己消失** ——
		 *   它们留在 DOM 上，而 `.bOPqQW_root` 那条官方规则（`justify-content: center`）
		 *   照旧生效，用户看到的错位不会随代码修复而恢复，除非重启页面。
		 *   真机实测：修好判据后，已被误加的 2 个 `.O_Ebla_root` 仍然是居中状态。
		 */
		function mpPruneStaleAliases() {
			if (!mpAliasAdded.size) return;
			const live = new Set(mpPairIndex ? mpPairIndex.values() : []);
			const stale = [...mpAliasAdded].filter((c) => !live.has(c));
			if (!stale.length) return;
			for (const cls of stale) mpAliasAdded.delete(cls);
			for (const el of document.querySelectorAll('[class]')) {
				for (const cls of stale) if (el.classList.contains(cls)) el.classList.remove(cls);
			}
		}

		/** 给单个元素补齐旧类名（幂等）。 */
		function mpAliasElement(el) {
			if (!mpPairIndex || !mpPairIndex.size) return;
			if (el.nodeType !== 1 || !el.classList) return;
			for (const cls of el.classList) {
				const alias = mpPairIndex.get(cls);
				if (alias !== undefined && !el.classList.contains(alias)) {
					el.classList.add(alias);
					// 记下来：这是桥接自己补的，下一轮推导不能拿它当官方证据
					mpAliasAdded.add(alias);
				}
			}
		}

		function mpAliasTree(root) {
			if (!mpPairIndex || !mpPairIndex.size) return;
			mpAliasElement(root);
			if (root.querySelectorAll) for (const el of root.querySelectorAll('[class]')) mpAliasElement(el);
		}

		let mpAliasObserver = null;
		function mpInstallAliasBridge() {
			mpRefreshAliasMap(true);
			// 官方样式表随组件懒加载，早期推导可能不全 → 持续重推；
			// mpRefreshAliasMap 内部按指纹去重，映射没变时不会重复打日志。
			let rounds = 0;
			const retry = () => {
				rounds++;
				mpAliasRound++;                 // 预热期计数，见 [MP_ALIAS_WARMUP]
				const before = mpAliasSig;
				mpAliasDirty = true;
				mpRefreshAliasMap(false);
				if (mpAliasSig !== before) mpAliasTree(document.body);   // 映射变了才全树重扫
				// 前 40 轮（约 24s）密集；之后放缓到 2s，长期兜底
				if (rounds < 40) setTimeout(retry, 600);
				else if (rounds < 200) setTimeout(retry, 2000);
			};
			setTimeout(retry, 300);
			mpAliasTree(document.body);
			if (mpAliasObserver) return;
			mpAliasObserver = new MutationObserver((records) => {
				mpRefreshAliasMap(false);
				for (const r of records) {
					if (r.type === 'childList') {
						for (const n of r.addedNodes) if (n.nodeType === 1) mpAliasTree(n);
					} else if (r.type === 'attributes') {
						mpAliasElement(r.target);
					}
				}
			});
			mpAliasObserver.observe(document.documentElement, {
				childList: true, subtree: true, attributes: true, attributeFilter: ['class'],
			});
		}

		function mpTeardownAliasBridge() {
			if (mpAliasObserver) { mpAliasObserver.disconnect(); mpAliasObserver = null; }
			// ⚠️ 不能按 split('_')[0] 取 hash：'pI_x6G' 本身就含下划线。
			// 用「旧类名全集」精确判断，只删我们加上的那些。
			const ours = new Set();
			for (const [oldHash, locals] of Object.entries(MP_LEGACY_HASHES)) for (const l of locals) ours.add(`${oldHash}_${l}`);
			for (const el of document.querySelectorAll('[class]')) {
				for (const cls of [...el.classList]) if (ours.has(cls)) el.classList.remove(cls);
			}
			mpPairIndex = null;
			mpAliasDirty = true;
			mpAliasSig = '';   // 重置指纹：否则重装时指纹相同会跳过重新赋值 mpPairIndex
		}

		/* ══ 启动动画收尾信号 ═══════════════════════════════════════════
		 * 原生侧盖了一层不透明的启动动画（头像 + 光环 + 进度环），它需要知道
		 * 「界面什么时候真的能用了」才能收起来。
		 *
		 * 四个候选信号，只有最后一个是对的：
		 *   · `apply()` 返回   → React 还没挂载，界面是白的
		 *   · `document.readyState === "complete"` → 实测在插件激活**之前**就完成
		 *   · data-mobile 已打上 且 外壳已建 → 只说明**本插件**挂好了
		 *   · 上面两条 **且官方前端的启动页已消失** → 界面真的能用了 ✅
		 *
		 * ⚠️ 第三条单独用是不够的 —— 这是本轮修掉的真 bug，实测数据：
		 *
		 *     t=9339ms  interactive  正文 = "HARNESS Loading plugins…"   官方启动页
		 *     t=10231ms interactive  data-mobile=true, .mp-nav-btn 已存在  ← 旧判据在此满足
		 *     t=10475ms complete     正文 = "探索未至之境 选择工作区…"      启动页才消失
		 *
		 *   也就是说旧判据比「界面真的可用」早了 **244ms 以上**，这段时间原生层已经
		 *   收起，用户就看到官方那个 HARNESS / Loading plugins… 白底页闪了几帧。
		 *
		 * 所以判据必须是「官方前端的启动页没了」。那个页面**没有稳定的 class 或 id**
		 * （它由官方前端在运行时构建，随版本变化），但它的**文案**是稳定的英文
		 * `Loading plugins`。这里按文案判定，并做成宽松匹配（大小写不敏感、
		 * 允许中间有省略号或空格差异），避免官方改一个字符就失效。
		 *
		 * ⚠️ 四条判据的实际分工（真机实测，别想当然）：
		 *   `apply()` 被调用时，页面通常**已经跑过启动页了** —— 本插件的客户端半有
		 *   5.5MB，等它解析完就已经接近 `load`，所以 `officialBootVisible()` 在正常
		 *   路径上测到的是 false（诊断里的 `polls=1 bootSeen=false` 就是这么来的）。
		 *   真正把收起时机推后的是 `pageLoaded()`。
		 *   但文案判据**不能删** —— 它是更慢的设备、更冷的缓存下唯一能挡住启动页的
		 *   那一条，只是在本机复现不出来而已。
		 *
		 * ⚠️ 为什么不用「#root 有内容」：`#root` 在启动页阶段就已经有 1 个子节点、
		 *   24 个字符了（就是那个启动页本身），所以「有内容」判不出来。
		 *
		 * ⚠️ 必须轮询而不是监听一次：`mpSyncMobileAttr()` 由 observer 节流驱动
		 *   （300ms），首次打上 data-mobile 的时刻不确定，单次检查会漏。
		 *   上限 [UI_READY_MAX_WAIT_MS] 后无条件上报 —— 宁可早收也不能不收，
		 *   否则启动页会永久盖住界面（那是最糟的失败方式）。
		 *
		 * ⚠️ 只在移动端生效。桌面端没有原生启动层，上报是空操作（桥不存在）。
		 */
		function mpSignalUiReady() {
			const bridge = window.DSHAndroid;
			if (!bridge || typeof bridge.onUiReady !== "function") return;   // 桌面端 / 桥不可用

			let reported = false;
			let timer = 0;
			const report = (reason) => {
				if (reported) return;
				reported = true;
				if (timer) window.clearInterval(timer);
				try {
					bridge.onUiReady();
					console.log("[dsh-android-mobile] onUiReady: " + reason);   // 便于真机核对是哪条判据收的
				} catch (e) { /* 原生侧异常不影响页面 */ }
			};

			/**
			 * 官方前端的启动页是否还在。
			 *
			 * ⚠️ 判据用 **`body.innerText`** 而不是 `#root.innerText`：
			 *   实测启动页阶段 `#root` 的 innerText 只有 24 字符，而 `body` 的匹配
			 *   才是可靠的（两者并非总是同一个文本视图）。用 body 更稳。
			 *
			 * ⚠️ 只在启动阶段查，不存在"用户聊天内容里恰好出现这个词"的误判风险 ——
			 *   这段时间页面里根本还没有会话内容。
			 */
			const officialBootVisible = () => {
				const body = document.body;
				if (!body) return false;
				// "Loading plugins…" / "Loading plugins..." / 大小写差异都算
				return /loading\s*plugins/i.test(body.innerText || "");
			};

			/**
			 * 页面是否已经加载完。
			 *
			 * ⚠️ 这一条是**文案判据的保险**，不是重复。若官方前端把启动页文案本地化
			 *   （中文界面下就不是 "Loading plugins"），`officialBootVisible()` 会永远
			 *   返回 false → 判据提前满足。加上 readyState 之后，那种情况下至少还能
			 *   等页面真正加载完再收。
			 *
			 * 实测冷启动：`readyState` 从 `interactive`（正文 = "HARNESS Loading plugins…"）
			 * 变为 `complete` 的时刻，正是真实 UI 出现的时刻（t≈11606ms）。
			 */
			const pageLoaded = () => document.readyState === "complete";

			const ready = () =>
				document.documentElement.hasAttribute("data-mobile") &&
				document.querySelector(".mp-nav-btn") !== null &&
				pageLoaded() &&
				!officialBootVisible();

			// 先立刻查一次：apply() 之前可能已经由上一轮 observer 打好属性
			if (ready()) { report("立即"); return; }

			const t0 = Date.now();
			let bootSeen = false;      // 是否观测到过官方启动页
			let polls = 0;
			let firstText = "";        // 第一次轮询时看到的正文（诊断用）
			timer = window.setInterval(() => {
				polls++;
				const bodyTxt = (document.body && document.body.innerText) || "";
				if (polls === 1) firstText = bodyTxt.replace(/\s+/g, " ").slice(0, 40);
				if (officialBootVisible()) bootSeen = true;
				if (ready()) {
					// ⚠️ 诊断信息要带**实际观测到的文本**：只报"见到/没见到"无法区分
					//   "判据正确"与"判据根本没生效"，这一轮就因此多绕了一次。
					report(
						(bootSeen ? "官方启动页已消失" : "未见到官方启动页") +
						" [polls=" + polls + " bootSeen=" + bootSeen +
						" 首帧正文=" + JSON.stringify(firstText) + "]"
					);
				} else if (Date.now() - t0 > UI_READY_MAX_WAIT_MS) {
					report("超时兜底 [polls=" + polls + " bootSeen=" + bootSeen + "]");
				}
			}, 50);
		}

		function mpSyncMobileAttr() {				// ⚠️ 修复 40：改由 mpIsMobile()（窄屏 且 非鼠标设备）决定 data-mobile，
				// 全部 CSS 作用域都挂在这个属性上 → CSS 与 JS 永远同一个判据。
				// 离开移动模式时顺手拆掉我们留下的痕迹（平板旋转 / 跨断点缩放）。
				const on = mpIsMobile();
				const has = document.documentElement.hasAttribute("data-mobile");
				if (on && !has) {
					document.documentElement.setAttribute("data-mobile", "");
					mpInstallAliasBridge();
					// 修复 59：进入移动模式后立即算一次居中留白（首屏不等 mutation）。
					mpScheduleHeaderCenter();
					// ⚠️ 修复 40：桌面启动时 mpBuildShellForMobile 没建外壳（正确），
					// 用户把窗口缩窄后才进入移动模式 → 必须在这里补建，否则没有汉堡按钮。
					mpBuildShell(); // 修复 40：内部按 mpIsMobile() 决定是否建外壳（监听器无条件装）
					mpBuildShellForMobile();
				} else if (!on && has) {
					document.documentElement.removeAttribute("data-mobile");
					// 修复 59：属性已摘 → 立即清掉居中用的 CSS 变量（回到官方布局）
					try { mpSyncHeaderCenter(); } catch (e) { /* 静默 */ }
					mpSetDrawerOpen(false);
					mpTeardownAliasBridge();
					mpTeardownMobile();
				}
			}

			function mpSidebarCol() { return document.querySelector(".pI_x6G_sidebarCol"); }
			function mpOfficialToggle() { return document.querySelector(".hHd-Xa_toggle"); }
			// 官方开合状态的唯一事实源：.hHd-Xa_root 是否带 hHd-Xa_collapsed
			function mpOfficialDrawerOpen() {
				const col = mpSidebarCol();
				const root = col ? col.querySelector(".hHd-Xa_root") : null;
				return !!root && !root.classList.contains("hHd-Xa_collapsed");
			}
			function mpMask() {
				let mask = document.querySelector(".mp-drawer-mask");
				if (!mask) {
					mask = document.createElement("div");
					mask.className = "mp-drawer-mask";
					mask.addEventListener("click", mpCloseDrawer);
					document.body.appendChild(mask);
				}
				return mask;
			}
			function mpSetDrawerOpen(open) {
				// ⚠️ 修复 39（2026-09-16 啾灵反馈）：抽屉/遮罩机制**只在窄屏下成立**。
				// 宽屏下官方侧栏常驻展开，mpOfficialDrawerOpen() 恒为 true → 这里会给遮罩
				// 加上 .mp-drawer-open（opacity:1 + pointer-events:auto + inset:0 z=39），
				// 而抽屉的浮层 CSS 只作用于 [data-mobile] → 遮罩压在正常布局的侧栏与整页之上：
				// 整页变暗、点「设置」点到的是遮罩、点遮罩又触发 mpCloseDrawer 把侧栏收起。
				// 这里做**唯一收口**：非窄屏一律强制关闭（与窄屏判定相与）。
				const on = mpIsMobile() && !!open;
				const col = mpSidebarCol();
				if (col) col.classList.toggle("mp-drawer-open", on);
				// 遮罩是懒建的：宽屏下若它还不存在就不必创建（少一个节点少一份风险）；
				// 若已存在（从窄屏切过来），下面这行负责把它的打开类摘掉。
				if (on || document.querySelector(".mp-drawer-mask")) {
					mpMask().classList.toggle("mp-drawer-open", on);
				}
			}
			function mpSyncDrawer() {
				// 修复 25：关闭动画进行中，绝不能被 observer/resize 把抽屉又拉回来
				//（官方此刻仍是 expanded，mpSetDrawerOpen 会重新加上 mp-drawer-open）。
				if (mpClosing) return;
				// ⚠️ 修复 39：宽屏下抽屉机制整体不适用（浮层 CSS 挂在 [data-mobile] 下），
				// 直接收工并确保遮罩是关闭态——绝不能让遮罩参与宽屏布局。
				if (!mpIsMobile()) { mpSetDrawerOpen(false); return; }
				// 模态弹窗打开时：只做**视觉收起**（抽屉滑出屏幕），不点官方 toggle。
				// （修复 26 教训：官方收起会触发 railIn → footArea opacity:0 → 连坐弹窗。）
				// 弹窗关闭后这里会自然走到下一行，把推迟的官方收起补做掉。
				const modal = document.querySelector('[role="dialog"][aria-modal="true"]');
				if (modal) {
					const col = mpSidebarCol();
					// 只看「视觉开」类：官方 expanded 与否在弹窗期间无所谓，
					// 否则 visualOnly 收起完成后会被这里反复重入（重启定时器）。
					if (col && col.classList.contains("mp-drawer-open")) {
						mpCloseDrawer({ visualOnly: true });
					}
					return;
				}
				// 走到这里说明没有弹窗了：若之前因弹窗做过视觉收起、官方仍 expanded，
				// 现在把它补收起（此刻无弹窗连坐风险，且抽屉在屏幕外，morph 看不见）。
				if (mpModalClose && mpOfficialDrawerOpen()) {
					mpModalClose = false;
					const btn = mpOfficialToggle();
					if (btn) { btn.click(); mpSetDrawerOpen(false); return; }
				}
				mpModalClose = false;
				mpSetDrawerOpen(mpOfficialDrawerOpen());
			}
			function mpToggleDrawer() {
				if (!mpIsMobile()) return; // 修复 40：仅移动端；桌面端完全走官方
				// 关闭中再点汉堡 = 取消关闭、直接重开（而不是又去点官方 toggle）
				if (mpClosing) { mpOpenDrawer(); return; }
				// ⭐ 修复 56b：**关闭方向复用 mpCloseDrawer**。
				//   原实现只做 setTimeout(mpSyncDrawer, 60)：官方 React 要 ~800ms 才
				//   写出 collapsed，60ms 时读到过期的 expanded → 又保持打开；而主
				//   observer 监听的是 {childList,subtree,characterData}，**不含 class**
				//   → 官方随后真正收起时无人复查，抽屉以浮层形式永久占屏。
				//   mpCloseDrawer 已含「滑出动画 + 等官方状态翻转 + 超时兜底」，直接复用。
				//   打开方向保持原样（实测 3 轮开合与遮罩关闭均正常）。
				if (mpOfficialDrawerOpen()) { mpCloseDrawer(); return; }
				const btn = mpOfficialToggle();
				if (btn) btn.click();
				setTimeout(mpSyncDrawer, 60);
			}
			// 修复 25：抽屉关闭。
			// ⚠️ 旧实现（修复 ③）用 .mp-drawer-closing 把抽屉**瞬间**隐藏，但遮罩仍走
			//   opacity .22s 淡出 → 这 220ms 屏幕上只剩一层 35% 的黑，实测对话区亮度
			//   212.7 → 164.8（-23%），啾灵反馈为「整个画面全部变暗，又瞬间回到正常」。
			// 新实现：抽屉自己**滑出**（left .22s）、遮罩**同步淡出**（opacity .22s），
			//   两者同时开始；等抽屉滑出屏幕（240ms）**之后**才点官方 toggle，
			//   官方切 56px 图标条 + rail-in 动画就发生在屏幕外，看不见（保住修复 ③ 的效果）。
			function mpCloseDrawer(opts) {
				if (!mpIsMobile()) return; // 修复 40：仅移动端；桌面端完全走官方
				const visualOnly = !!(opts && opts.visualOnly);
				if (mpClosing) return;
				if (!mpOfficialDrawerOpen()) { mpSetDrawerOpen(false); return; }
				mpClosing = true;
				// 修复 27：弹窗打开期间只做「视觉收起」—— 抽屉滑出屏幕即可，
				// 绝不点官方 toggle（否则官方 railIn 动画 → footArea opacity:0 → 连坐弹窗）。
				// 官方收起推迟到弹窗关闭后，由 mpSyncDrawer 补做。
				if (visualOnly) {
					mpModalClose = true;
					const col0 = mpSidebarCol();
					if (col0) col0.classList.remove("mp-drawer-open");
					const mask0 = document.querySelector(".mp-drawer-mask");
					if (mask0) { mask0.classList.remove("mp-drawer-open"); mask0.classList.add("mp-drawer-closing"); }
					setTimeout(() => {
						const m2 = document.querySelector(".mp-drawer-mask");
						if (m2) m2.classList.remove("mp-drawer-closing");
						mpClosing = false;
					}, 220);
					return;
				}
				// ① 抽屉滑出 + 遮罩淡出：同时开始，一起走完 .22s
				const col = mpSidebarCol();
				if (col) col.classList.remove("mp-drawer-open");
				const mask = document.querySelector(".mp-drawer-mask");
				if (mask) { mask.classList.remove("mp-drawer-open"); mask.classList.add("mp-drawer-closing"); }
				// ② 等抽屉完全滑出屏幕外，再让官方收起（图标条 morph 在屏幕外发生）
				mpCloseTimer = setTimeout(() => {
					mpCloseTimer = null;
					// ⚠️ 修复 26：这里**不能**给抽屉加 .mp-drawer-closing（visibility:hidden）。
					//   抽屉此刻已在屏幕外（left=-340、width:301 !important），官方 56px 图标条
					//   的 morph 本来看不见，加隐藏是多余的"保险"；而 visibility **会继承给子元素**
					//   —— 设置弹窗的 DOM 就在抽屉内部（实测 col.contains(dialog) === true），
					//   于是弹窗被连坐隐藏 200ms → 啾灵看到"进入设置→闪过对话界面→回到设置"。
					const btn = mpOfficialToggle();
					if (btn) btn.click();
					// ⭐ 修复 56：**等官方状态真正翻转**再收尾，不能定时读一次就下结论。
					//   实测时间线：点 toggle 后约 800ms 官方 React 才写入 hHd-Xa_collapsed，
					//   而旧代码只等 200ms 就去读 → 读到过期的 expanded → mpSetDrawerOpen(true)
					//   把抽屉又拉回来（t=3500ms left 从 -340 滑回 0），等官方真收起时已无人复查
					//   → 56px 图标条以浮层形式占屏 + 遮罩盖全屏 = 卡死。
					mpAwaitOfficialCollapsed();
				}, 240);
			}
			// ⭐ 修复 56：等官方侧栏真的收起（hHd-Xa_collapsed 出现）再复位状态。
			//
			// 为什么必须等：官方 toggle 只是改 React state，DOM 上的 collapsed 类
			// 要等一次渲染（实测 240ms~800ms 不等，视负载而定）。旧实现固定等 200ms
			// 就读 mpOfficialDrawerOpen()，读到过期值反而把抽屉重新拉出来。
			//
			// 等待期间**保持 mpClosing=true**：任何 resize / observer 回调都会在
			// mpSyncDrawer 开头因它而早退，不会把抽屉拉回来。
			// 3s 兜底：官方若因故无响应，至少保证遮罩摘掉，绝不留下卡死状态。
			function mpAwaitOfficialCollapsed() {
				let done = false;
				const finish = (why) => {
					if (done) return;
					done = true;
					if (mpCollapseObs) { mpCollapseObs.disconnect(); mpCollapseObs = null; }
					if (mpCollapseFallback !== null) { clearTimeout(mpCollapseFallback); mpCollapseFallback = null; }
					const m2 = document.querySelector(".mp-drawer-mask");
					if (m2) m2.classList.remove("mp-drawer-closing", "mp-drawer-open");
					const c2 = mpSidebarCol();
					if (c2) c2.classList.remove("mp-drawer-closing");
					mpClosing = false;
					// 以官方**当下真实状态**为准做最后一次同步（此时它必然已更新完）
					mpSetDrawerOpen(mpOfficialDrawerOpen());
					if (why === "timeout") console.warn("[mobile-polish] 官方侧栏收起超时，已强制复位抽屉状态");
				};
				// 已经收起（同步路径）→ 直接收尾
				if (!mpOfficialDrawerOpen()) { finish("sync"); return; }
				const col = mpSidebarCol();
				const root = col ? col.querySelector(".hHd-Xa_root") : null;
				if (!root) { finish("noroot"); return; }
				// ① 首选：观察 root 的 class，等到 collapsed 出现
				if (mpCollapseObs) { mpCollapseObs.disconnect(); mpCollapseObs = null; }
				mpCollapseObs = new MutationObserver(() => {
					if (!mpOfficialDrawerOpen()) finish("observed");
				});
				mpCollapseObs.observe(root, { attributes: true, attributeFilter: ["class"] });
				// ② 兜底：官方若没动静（或 root 被 React 换掉）也不留卡死
				if (mpCollapseFallback !== null) clearTimeout(mpCollapseFallback);
				mpCollapseFallback = setTimeout(() => finish("timeout"), 3000);
			}

			function mpOpenDrawer() {
				if (!mpIsMobile()) return; // 修复 40：仅移动端；桌面端完全走官方
				// 关闭动画进行中：取消关闭、直接重开（官方仍是 expanded，加上类即可滑回）
				if (mpClosing) {
					if (mpCloseTimer !== null) { clearTimeout(mpCloseTimer); mpCloseTimer = null; }
					mpClosing = false;
					const c = mpSidebarCol();
					if (c) c.classList.remove("mp-drawer-closing");
					const m = document.querySelector(".mp-drawer-mask");
					if (m) m.classList.remove("mp-drawer-closing");
					mpSetDrawerOpen(true);
					return;
				}
				if (mpOfficialDrawerOpen()) return;
				const btn = mpOfficialToggle();
				if (btn) btn.click();
				setTimeout(mpSyncDrawer, 60);
			}

			// 汉堡按钮：固定左上、浮于正文之上（自建元素，不注入 React 子树）
			// ⚠️ 修复 40：mpAnchorBurger 提到模块作用域。
			//   原先它是 mpBuildShell 的**内部函数声明**（外部不可见），
			//   而修复 40 要把 MutationObserver 装到 mpBuildShell 里（宽窄屏都要装），
			//   那里引用内部声明会直接 ReferenceError。
			//   顺带把按钮查找从闭包变量改成按类查询（拆解/重建后依然正确）。
			// ══ 修复 59：标题相对**屏幕**绝对居中（2026-09-28 啾灵反馈）══════════
			// 现象：修完长标题重叠后，左边只有汉堡（50px）而右边有「⋯」+「右侧栏」
			//   （约 88px），标题簇只能靠「左右留白相等」来居中 → 实测簇中心 174px，
			//   而屏幕中线 195px，**偏左 21px**。用户要的是「相对屏幕绝对居中」。
			//
			// 为什么不能用 CSS 写死：
			//   右侧控件宽度会随会话变化（只有「⋯」时有 2 个按钮，有「打开方式」
			//   时分体按钮还在占位 —— 虽然移动端已隐藏，但 headerActions 里可能
			//   还有其他官方槽位内容），写死像素必然在某些会话下再次偏。
			//
			// 做法：让左侧留白 = 右侧真实占位，簇中心即恒等于屏幕中线。
			//   设 header 内容区为 [padL, W - padR]，titleRow 内依次是
			//   [titleCluster(flex:1), headerUtilities, headerCorner]，
			//   右侧总占位 R = titleRow 宽 − 簇宽（**与 padL 无关的常量**）。
			//   簇中心 = padL + (W − padL − padR − R)/2，令其 = W/2 解得：
			//     padL = padR + R
			//   ⇒ 一次测量即可精确解出，无需迭代收敛。
			//
			// 安全上限：簇宽至少留 96px，否则极窄屏会把标题挤没（宁可偏左也要看得见）。
			function mpSyncHeaderCenter() {
				const root = document.documentElement;
				// 桌面端：立刻清掉变量 → CSS 回落到兜底值，官方布局零影响。
				if (!root.hasAttribute("data-mobile")) {
					if (root.style.getPropertyValue("--mp-hdr-padl")) root.style.removeProperty("--mp-hdr-padl");
					return;
				}
				const header = document.querySelector(".wSkVaW_header");
				const row = header && header.querySelector(".wSkVaW_titleRow");
				const cluster = row && row.querySelector(".wSkVaW_titleCluster");
				if (!header || !row || !cluster) return;
				// 官方 header 是空壳（Hero 页 / 尚未渲染）时不动
				const rowBox = row.getBoundingClientRect();
				const clBox = cluster.getBoundingClientRect();
				if (rowBox.width < 1 || clBox.width < 1) return;
				const padR = parseFloat(getComputedStyle(header).paddingRight) || 0;
				const rightTotal = Math.max(0, rowBox.width - clBox.width);
				const maxPadL = Math.max(0, header.getBoundingClientRect().width - padR - rightTotal - 96);
				const padL = Math.max(0, Math.min(Math.round(padR + rightTotal), Math.round(maxPadL)));
				const now = root.style.getPropertyValue("--mp-hdr-padl");
				const next = padL + "px";
				// 只在变化时写（避免无谓的样式失效重算）
				if (now !== next) root.style.setProperty("--mp-hdr-padl", next);
			}

			/* ══ 修复 61：标题栏高度 ×1.25（运行时自校准，配合上面 CSS 的
			   min-height: var(--mp-hdr-h)）═════════════════════════════════

			   为什么不能写死：官方 header 是内容驱动高度（修复 30 把官方
			   min-height:76px 覆盖成 0），自然高度实测在 55~65px 之间浮动：
			     · 有模式徽章 / 标题换行 → 约 65px
			     · 无徽章、单行标题      → 约 55px
			   写死 81.25px（=65×1.25）在 55px 状态下就是 +48% —— 真机反馈
			   「有点高过头」正是如此。先量再乘，才是任何状态都精确 +25%。

			   怎么量（关键）：把 inline min-height 临时压成 0px → 读高度 →
			   立刻还原。整段在同一个任务内完成，浏览器不会绘制中间态，
			   所以没有闪烁、不触发可见跳动。

			   成本控制（修复 59 的教训：别在每帧里反复强制同步布局）：
			   用 mpHdrKey =「header 宽 : 内容高」当指纹，只有这两者之一
			   变化时才真的去量。内容高取 titleRow 所有子项的**最大高度**：
			   这些子项在 titleRow 里都是 align-items:center 的 flex 项、
			   **不会**被拉伸，所以高度恒等于内容高度；取最大值可覆盖
			   「模式徽章挂在 headerActions、快照徽章挂在 utilities」等
			   徽章出现在簇外的情况。其余调用只花几次廉价读 + 比较。 */
			const MP_HDR_SCALE = 1.25;
			var mpHdrKey = "";
			function mpSyncHeaderHeight() {
				const root = document.documentElement;
				// 桌面端：清掉变量 → CSS 回落 0px，官方布局零影响
				if (!root.hasAttribute("data-mobile")) {
					if (root.style.getPropertyValue("--mp-hdr-h")) root.style.removeProperty("--mp-hdr-h");
					mpHdrKey = "";
					return;
				}
				const header = document.querySelector(".wSkVaW_header");
				const row = header && header.querySelector(".wSkVaW_titleRow");
				// 官方 header 是空壳（Hero 页 / 尚未渲染）时不动，免得把 0 当基线
				if (!header || !row) return;
				const cluster = row.querySelector(".wSkVaW_titleCluster") || row;
				const width = Math.round(header.getBoundingClientRect().width);
				// 内容高 = titleRow 各子项（含标题簇 / headerActions / utilities /
				// corner）里的最大高度；它们不被拉伸，故等于内容高度。
				let contentH = cluster.getBoundingClientRect().height;
				for (const child of row.children) {
					const h = child.getBoundingClientRect().height;
					if (h > contentH) contentH = h;
				}
				contentH = Math.round(contentH * 100) / 100;
				if (width < 1 || contentH < 1) return;
				const key = width + ":" + contentH;
				if (key === mpHdrKey) return;
				// 量自然高度：inline + important 能压过样式表里的 !important
				const savedPrio = header.style.getPropertyPriority("min-height");
				const saved = header.style.getPropertyValue("min-height");
				header.style.setProperty("min-height", "0px", "important");
				const natural = header.getBoundingClientRect().height;
				if (saved) header.style.setProperty("min-height", saved, savedPrio);
				else header.style.removeProperty("min-height");
				if (!(natural > 0)) return; // 量不到就保持原样，下一帧再试
				mpHdrKey = key;
				root.style.setProperty("--mp-hdr-h", Math.round(natural * MP_HDR_SCALE * 100) / 100 + "px");
			}

			// 修复 59：把 mpSyncHeaderCenter 合并到下一帧执行。
			// MutationObserver 在 body 上以 subtree:true 观察，触发极频繁
			// （每次消息流更新都会来一发）→ 同一帧内最多测一次，避免手机上
			// 反复强制同步布局（getBoundingClientRect）拖慢滚动。
			// ⚠️ 必须用 var（不能用 let/const）：mpSyncMobileAttr 可能在模块求值
			//   走到本行**之前**就调用 mpScheduleHeaderCenter（首屏 mpBuildShell），
			//   而 let 存在 TDZ → 会抛 "Cannot access before initialization"。
			//   var 会提升并初始化为 undefined，`if (mpCenterRaf) return` 对
			//   undefined 判假 → 行为正确。
			var mpCenterRaf = 0;
			function mpScheduleHeaderCenter() {
				if (mpCenterRaf) return;
				mpCenterRaf = requestAnimationFrame(() => {
					mpCenterRaf = 0;
					// 修复 61：高度自校准与居中留白共用这一帧（共用节流，不额外增加重排次数）
					try { mpSyncHeaderHeight(); } catch (e) { /* 静默：纯外观，绝不影响功能 */ }
					try { mpSyncHeaderCenter(); } catch (e) { /* 静默：纯外观，绝不影响功能 */ }
					// 修复 66：与上面两件事共用同一帧（零额外重排）
					try { mpSyncFloatNavGap(); } catch (e) { /* 静默：纯外观，绝不影响功能 */ }
				});
			}

			/* ══ 修复 66：汉堡浮层时给主区让出顶部空间（配合 CSS 的
			   [data-mobile] .pI_x6G_centerCol { padding-top: var(--mp-float-nav-h) }）══

			   背景见 CSS 那条注释：切到全局面板（插件 / 定时任务）后官方顶栏随
			   conversation 一起卸载，汉堡由 mpAnchorBurger() 回落到 body 的
			   fixed 浮层，而面板页自己的顶部留白是给桌面端预留的（Android 恒为 0）
			   → 面板标题撞进汉堡。

			   ⚠️ **量汉堡的真实 rect，不写死像素**：
			     汉堡 top = calc(var(--dsh-android-system-top, 0px) + 6px)，而该变量
			     在本 App 里从未被任何代码写入（恒 0）；真正把汉堡推下去的是 WebView
			     自身对系统栏的避让。写死 46px 在别的机型/系统栏高度上就会偏，
			     所以直接读 getBoundingClientRect().bottom —— 它是 fixed 元素，
			     页面滚动不影响，读数恒定，也不需要知道系统栏多高。

			   ⚠️ 判据用 `mp-in-header` 类而不是"header 在不在"：这个类正是
			     mpAnchorBurger() 挂载时打的标记（进 header 加、回落 body 摘），
			     与汉堡的实际归属永远一致，不会出现"类与位置打架"的中间态。

			   非移动端 / 汉堡不在浮层 → 变量归 0px，conversation 页逐像素不变。 */
			const MP_FLOAT_NAV_GAP = 8; // 呼吸位：标题不要贴着汉堡下沿
			var mpFloatNavH = "";
			function mpSyncFloatNavGap() {
				const root = document.documentElement;
				if (!root.hasAttribute("data-mobile")) {
					if (root.style.getPropertyValue("--mp-float-nav-h")) root.style.removeProperty("--mp-float-nav-h");
					mpFloatNavH = "";
					return;
				}
				const btn = mpNavBtnEl;
				const floating = !!(btn && btn.isConnected && !btn.classList.contains("mp-in-header"));
				let needed = 0;
				if (floating) {
					const r = btn.getBoundingClientRect();
					if (r.height > 0) needed = r.bottom + MP_FLOAT_NAV_GAP;
				}
				/* 修复 58：全面屏打开时，内容列还要再让出状态栏。
				   ============================================================
				   全面屏把窗口 content 从 [0,108] 变成 [0,0]，WebView 不再自己
				   避让系统栏，状态栏于是直接压在内容上（实测汉堡 rect.top=3.75、
				   正好被时间/电量那一行盖住）。原生侧会把状态栏高度写进
				   --dsh-android-system-top，这里取它。

				   ⚠️ 两种让位（浮层汉堡、状态栏）必须在**同一个变量**里合并：
				     吃这个变量的是 CSS 里那条带 !important 的
				     .pI_x6G_centerCol { padding-top: var(--mp-float-nav-h) }，
				     同属性上再加一条不带 !important 的规则压不过它。
				   ⚠️ 合并用 JS 的 Math.max 而不是 CSS 的 max()：后者要 Chrome 79，
				     本 App 目标内核是 chrome74，老内核上整条声明失效。
				   ⚠️ 汉堡浮层时不必再取 max —— 它本身是 fixed 且 top 已含系统栏高度
				     （calc(--dsh-android-system-top + 6px)），bottom 必然大于状态栏高度；
				     取 max 只是为了在两种形态下都成立，多算一次不影响结果。 */
				const sysTop = parseFloat(
					getComputedStyle(root).getPropertyValue("--dsh-android-system-top"),
				) || 0;
				if (sysTop > needed) needed = sysTop;
				const next = Math.round(needed) + "px";
				if (next === mpFloatNavH) return; // 无变化 ⇒ 不写 DOM ⇒ 不触发 observer
				mpFloatNavH = next;
				root.style.setProperty("--mp-float-nav-h", next);
			}

			function mpAnchorBurger() {
				if (!mpIsMobile()) return; // 修复 40：非移动端绝不挂载汉堡
				// 修复 59：header 内容变化时顺手重算居中留白（已合并到下一帧）。
				mpScheduleHeaderCenter();
				// ⭐ 修复 47：**先查已连接的，查不到就回退到自持引用**（可能是 detached 的）。
				//   ⚠️ 不能只写 document.querySelector(".mp-nav-btn")：它只匹配已连接节点，
				//   汉堡被 React 摘掉后它返回 null → 早退 → 永远不再挂回去（本次 bug 的根因）。
				const btn = document.querySelector(".mp-nav-btn") || mpNavBtnEl;
				if (!btn) return;
				mpNavBtnEl = btn;
				const header = document.querySelector(".wSkVaW_header");
				const usable = header && header.querySelector(".wSkVaW_titleRow");
				if (usable) {
					if (btn.isConnected && btn.parentElement === header) return;
					header.appendChild(btn);
					btn.classList.add("mp-in-header");
				} else {
					if (btn.isConnected && btn.parentElement === document.body) return;
					btn.classList.remove("mp-in-header");
					document.body.appendChild(btn);
				}
			}

			// ══════════════════════════════════════════════════════════════════
			// §12 系统返回键：把 Android 的返回手势/按键接到 Web UI 上
			// ══════════════════════════════════════════════════════════════════
			//
			// 原生侧（MainActivity）拦下返回键后调 `window.__DSH_MOBILE_BACK__()`，
			// 返回值 = 「这一层被消费了吗」。**只有返回 false，原生才退出 App**，
			// 所以下面这个顺序就是用户感知的"返回栈"。
			//
			// 由内到外（与屏幕上的层次一致）：
			//   ① 自建的重命名弹窗
			//   ② 设置弹窗：二级 → 一级 → 关闭（复用返回按钮那一套语义）
			//   ③ 官方其它模态弹窗（兜底：点遮罩 / 派 Escape）
			//   ④ composer 的触发菜单（「/」指令、@ 引用等）
			//   ⑤ 侧栏抽屉
			//   ⑥ 官方右侧栏（「工作区文件 / 新建终端」那一栏）
			//   ⑦ 官方主面板（插件管理器 / 日程 / 快照…）→ 回对话
			//   都没有 ⇒ false，交原生退出
			//
			// ⚠️ ⑥ 与 ⑦ 的先后：右栏在**对话界面之内**（它是会话的停靠面），而主面板
			//   是替换掉整个对话区。所以先退右栏、再退主面板，与视觉层次一致。
			//
			// ⚠️ 判据一律用**可见性**，不能用 DOM 存在性：官方弹窗关掉后节点往往
			//   仍留在 DOM 里（只改 display），按存在性判会永远认为"有东西可关"，
			//   返回键就再也退不出 App 了。

			/** 元素是否真的显示在屏幕上（`display:none` / 已卸载 ⇒ false）。 */
			function mpVisible(el) {
				if (!el || !el.isConnected) return false;
				try { return el.getClientRects().length > 0; } catch (e) { return false; }
			}

			/** 当前可见的官方模态弹窗；没有则 null。 */
			function mpVisibleModal() {
				const all = document.querySelectorAll('[role="dialog"][aria-modal="true"]');
				for (const d of all) if (mpVisible(d)) return d;
				return null;
			}

			/**
			 * 派发一次 Escape —— 官方大量组件用它做"退一层"
			 * （原生返回键在桌面端的对应物）。
			 *
			 * ⚠️ 两处都发：官方有的监听在 `document` 上，有的挂在获得焦点的元素上。
			 */
			function mpDispatchEscape() {
				try {
					const ev = new KeyboardEvent("keydown", {
						key: "Escape", code: "Escape",
						bubbles: true, cancelable: true, composed: true,
					});
					const target = document.activeElement || document.body;
					if (target) target.dispatchEvent(ev);
					document.dispatchEvent(ev);
				} catch (e) { /* 静默：派发失败也不该让返回键卡住 */ }
			}

			/** 官方 `ctx.layout` 服务（拿不到就返回 null，绝不抛）。 */
			function mpLayout() {
				if (!mpCtx) return null;
				try { return mpCtx.get("layout") ?? null; } catch (e) { return null; }
			}

			/**
			 * 官方 `ctx.sidebarRight` 服务 —— 右上角那一栏（工作区文件 / 新建终端）。
			 *
			 * ⚠️ **不要用 `layout.closeRightbar()` 关它**，那是个陷阱：该方法在
			 *   `ILayout` 上的语义是「**报告**右栏为隐藏」（`dsh-client-ui-layout` 的
			 *   注释原文 "Report the right panel as hidden"），它是给占用方
			 *   （`dsh-client-ui-sidebar-right`）上报呈现状态用的。而右栏自己的
			 *   `expanded` 状态住在 sidebar-right 的 store 里，由它反过来调
			 *   `openRightbar`/`closeRightbar` 同步给 layout（见其 client.js 的
			 *   `syncPresentation`）。直接调 layout 只会改 grid 轨道，**右栏的
			 *   `expanded` 仍是 true**，于是面板被滑到屏幕外却还认为自己开着，
			 *   用户再点右上角按钮时行为就错乱了。
			 *
			 * 正确的接缝是 `ctx.sidebarRight`（`provide("sidebarRight", controller)`）：
			 *   · `isExpanded()` —— 「这一层开着吗」，判据与官方自己一致；
			 *   · `toggleExpanded()` —— 收起/展开，内部走 store action，会正常
			 *     同步 layout 轨道与面板滑出动画。
			 *
			 * @returns {object|null} 服务实例；拿不到（未装该插件）返回 null。
			 */
			function mpSidebarRight() {
				if (!mpCtx) return null;
				try { return mpCtx.get("sidebarRight") ?? null; } catch (e) { return null; }
			}

			/**
			 * 处理一次系统返回键。
			 * @returns {boolean} true = 已消费（原生不退出）；false = 无层级可退。
			 */
			function mpHandleSystemBack() {
				// ① 自建的重命名弹窗
				const rename = document.querySelector(".mp-rename-mask");
				if (mpVisible(rename)) { closeRename(rename); return true; }

				// ② / ③ 模态弹窗
				const dialog = mpVisibleModal();
				if (dialog) {
					const step = mpDialogBack.get(dialog);
					if (typeof step === "function") {
						try { step(); return true; } catch (e) { /* 落到下面的兜底 */ }
					}
					// 没注册回调（`setupSettingsDialog` 因结构不符早退过）或回调抛错：
					// 按官方自己的关闭方式兜底 —— 先点遮罩，没有遮罩再派 Escape。
					const mask = dialog.parentElement;
					if (mask && mask.classList && mask.classList.contains("VOzbGW_mask")) {
						mask.click();
						return true;
					}
					mpDispatchEscape();
					return true;
				}

				// ④ composer 的触发菜单：能读到 store 就按 store 关，否则派 Escape
				const ctrl = mpTriggerController();
				if (ctrl && ctrl.menu && typeof ctrl.menu.getSnapshot === "function") {
					try {
						if (ctrl.menu.getSnapshot().open) { ctrl.dismiss(); return true; }
					} catch (e) { /* 静默 */ }
				}
				if (document.querySelector("[data-trigger-menu]")) { mpDispatchEscape(); return true; }

				// ⑤ 侧栏抽屉。判据用**视觉开**（与 mpCloseDrawer 内部同源）：
				//    设置弹窗打开期间抽屉会被"视觉收起"（官方仍是 expanded），
				//    那种状态下按返回键不该去动抽屉。
				const col = mpSidebarCol();
				if (col && col.classList.contains("mp-drawer-open")) { mpCloseDrawer(); return true; }

				// ⑥ 官方右侧栏（右上角那个按钮开出来的「工作区文件 / 新建终端」栏）。
				//    判据用服务自己的 `isExpanded()`，不猜 DOM —— 右栏收起时面板只是被
				//    平移到屏幕外（`transform`，节点仍在 DOM 里且尺寸非零），用
				//    `mpVisible()` 那套可见性判据会**永远为真**，返回键就再也退不出去了。
				const sidebarRight = mpSidebarRight();
				if (sidebarRight && typeof sidebarRight.isExpanded === "function") {
					try {
						if (sidebarRight.isExpanded()) { sidebarRight.toggleExpanded(); return true; }
					} catch (e) { /* 静默：服务异常不该让返回键卡住 */ }
				}

				// ⑦ 官方主面板 → 回对话
				const layout = mpLayout();
				if (layout) {
					try {
						const info = layout.panelInfo && layout.panelInfo.getSnapshot();
						if (info && info.activePanelId != null) { layout.selectPanel(null); return true; }
					} catch (e) { /* 静默：拿不到就当没有主面板 */ }
				}

				return false;   // 真的没层级可退 ⇒ 原生退出 App
			}

			// ⚠️ 修复 40：这里只装**常驻监听器**（宽窄屏都要装）。
			// 若把监听器留在 mpBuildShellForMobile 里，桌面端启动时直接 return
			// → 用户把窗口缩窄就没人发现（宽→窄切换彻底失效）。
			// ⚠️ mpShellBuilt 一旦置 true 就**永不复位**（拆解时也不复位）——
			//   否则重新进入移动模式会重复注册监听器。
			function mpBuildShell() {
				mpSyncMobileAttr();
				if (mpShellBuilt) return;
				mpShellBuilt = true;
				// 修复 40：监听器移到这里（无条件装），回调内部各自判移动端。
				new MutationObserver(mpAnchorBurger).observe(document.body, { childList: true, subtree: true });
				document.addEventListener("click", (e) => {
					const t = e.target;
					if (!t || !t.closest) return;
					if (!t.closest(".hHd-Xa_toggle")) return;
					const col = mpSidebarCol();
					if (!col || !col.classList.contains("mp-drawer-open")) return; // 抽屉没开：交官方
					if (mpClosing) return;
					e.preventDefault();
					e.stopPropagation();
					e.stopImmediatePropagation();
					mpCloseDrawer();
				}, { capture: true });
				window.addEventListener("resize", () => { mpSyncMobileAttr(); mpSyncDrawer(); mpScheduleHeaderCenter(); }, { passive: true });
				// §12：把返回键处理器交给原生。原生拦下系统返回键后调它，
				//   返回值 = 「这一层被消费了吗」；只有 false 才退出 App。
				//   ⚠️ 挂在 mpBuildShell 里（与上面三个监听器同处，只执行一次）；
				//   拆解时**不摘**，理由与 mpShellBuilt 不复位相同。
				window.__DSH_MOBILE_BACK__ = mpHandleSystemBack;
				mpBuildShellForMobile();
			}

			// 移动端外壳元素（汉堡按钮 + 遮罩）：非移动端一个节点都不建。
			// ══ 修复 40：离开移动模式时的拆解 ══
			// 只在「曾经是移动模式、现在不是」时调用（平板旋转、跨断点缩放）。
			// 纯鼠标桌面**永不进入**移动模式 → 对该场景是恒等操作（零风险）。
			// CSS 部分已由 data-mobile 属性移除自动失效，这里只收拾 JS 留下的痕迹。
			function mpTeardownMobile() {
				if (mpCollapseObs) { mpCollapseObs.disconnect(); mpCollapseObs = null; }
				if (mpCollapseFallback !== null) { clearTimeout(mpCollapseFallback); mpCollapseFallback = null; }
				mpClosing = false;
				try {
					// ① 自建节点：汉堡按钮 / 遮罩 / 设置页返回条 / 分组小标题
					document.querySelectorAll(".mp-nav-btn, .mp-drawer-mask, .mp-back-bar, .mp-nav-group")
						.forEach((n) => { try { n.remove(); } catch (e) { /* 静默 */ } });
					mpNavBtnEl = null; // ⭐ 修复 47：节点已移除，引用一并清空
					// ⚠️ **不复位 mpShellBuilt**：监听器只该注册一次。
					//   复位它会让重进窄屏时 mpBuildShell 再走一遍 → 重复注册
					//   MutationObserver / click / resize（三个都泄漏）。
					//   重进窄屏的外壳重建由 mpBuildShellForMobile()（幂等）负责。
					// ② 内联覆盖：设置弹窗被我们改成 100vw/100vh 全屏 —— 恢复官方
					document.querySelectorAll('[role="dialog"][aria-modal="true"]').forEach((d) => {
						["width", "maxWidth", "height", "maxHeight", "borderRadius", "boxShadow",
							"position", "top", "left", "flexDirection", "overflow"].forEach((k) => {
							try { d.style[k] = ""; } catch (e) { /* 静默 */ }
						});
						const ov = d.parentElement;
						if (ov && ov.classList && ov.classList.contains("VOzbGW_mask")) {
							["left", "width", "height", "alignItems"].forEach((k) => {
								try { ov.style[k] = ""; } catch (e) { /* 静默 */ }
							});
						}
					});
					// ③ 插件分组：把被我们藏起来的官方卡片恢复（否则桌面端插件列表是空的）
					document.querySelectorAll(".qSYn7G_card").forEach((c) => {
						try { c.style.display = ""; } catch (e) { /* 静默 */ }
					});
					// ④ 标记位：清掉以便再次进入窄屏时能重新处理
					document.querySelectorAll("[data-mp-ready]").forEach((n) => { try { delete n.dataset.mpReady; } catch (e) {} });
					document.querySelectorAll("[data-mp-plugin-group]").forEach((n) => { try { delete n.dataset.mpPluginGroup; } catch (e) {} });
					document.querySelectorAll("[data-mp-group]").forEach((n) => { try { delete n.dataset.mpGroup; } catch (e) {} });
					document.querySelectorAll("[data-mp-tap]").forEach((n) => { try { delete n.dataset.mpTap; } catch (e) {} });
					document.querySelectorAll("[data-mp-more-bound]").forEach((n) => { try { delete n.dataset.mpMoreBound; } catch (e) {} });
				} catch (e) { /* 修复 40：拆解失败也绝不抛错、绝不阻断页面 */ }
			}


			function mpBuildShellForMobile() {
				if (!mpIsMobile()) return; // 修复 40
				// 幂等：拆解后重进窄屏会再次调用，别建出第二个汉堡。
				if (document.querySelector(".mp-nav-btn")) return;
				const btn = document.createElement("button");
				btn.type = "button";
				btn.className = "mp-nav-btn";
				btn.setAttribute("aria-label", "打开导航");
				btn.innerHTML = '<svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M3 5.5h14M3 10h14M3 14.5h14" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>';
				btn.addEventListener("click", (e) => { e.stopPropagation(); mpToggleDrawer(); });
				document.body.appendChild(btn);
				mpNavBtnEl = btn; // ⭐ 修复 47：自持引用，detached 后也能重挂
				// 修复 30：汉堡锚定进 header（CSS .mp-nav-btn.mp-in-header 相对 header 定位）。
				// ⚠️ React 重渲染 header 会把外来子节点整个清掉（btn 变 detached），
				//   所以这里不能只看 class 判断"已锚定"——必须检查 btn 是否还在文档里，
				//   不在就重新 appendChild（实测 detached 节点可无损重挂）。
				// ⚠️ 修复 31：**空会话页（Hero）官方 header 是空壳**（高度 0、无 titleRow），
				//   锚进去 = 锚进一个 0 高度容器 → 汉堡被裁掉、新建对话后打不开侧边栏。
				//   判据：header 有可见内容（titleRow 存在）才锚定；否则退回 body + fixed 定位。
				mpMask();
				mpAnchorBurger(); // 修复 40：首屏立即锚定（observer 只在 mutation 时触发）
				// 修复 32：用户在抽屉内点**官方收起按钮**（.hHd-Xa_toggle）时，
				//   官方会**立刻**把侧栏切到 56px 图标条形态（collapsed + railIn），
				//   而我们的抽屉还在 left:0 没滑走 → 中间约 160ms 露出"空白侧边栏"，
				//   然后才滑出屏幕（啾灵截图反馈）。
				//   做法：捕获阶段拦下这次点击，阻止官方立即 morph，改走我们的
				//   mpCloseDrawer（先滑出屏幕、再点官方 toggle，morph 发生在屏幕外）。
			}

			// ── 8a. 官方 ⋯ 菜单补回「刷新」──
			// 0.1.5 的 ⋯ 菜单由官方提供，只有「下载 Session 日志」一项；旧版我们
			// 自建顶栏时有「下载日志 / 刷新 / 查看轨迹」。现在保留官方菜单，
			// 只把「刷新」按官方同样的 DOM/类名插进去（观感、间距、hover 全一致）。
			// ⚠️ 幂等：官方每次打开菜单会重建 DOM，故按 data-mp-item 去重；
			//    同时用 observer 兜住「菜单已开时 React 又重渲染」的情况。
			function mpEnsureRefreshItem() {
				if (!mpIsMobile()) return; // 修复 40：仅移动端；桌面端完全走官方
				// ⚠️ 修复 28：只认顶栏 ⋯ 菜单。官方这套 Menu 是全站通用组件——
				//   顶栏 ⋯ 菜单是唯一 dense:true 的（列表带 _denseList_ 类）；
				//   会话行 ⋮、权限切换卡片等都是普通菜单（没有该类）。
				//   旧版带了个 `div[class*="_list_"]` 兜底 → 任何官方下拉菜单都会被
				//   误注入「刷新」（啾灵实测：点会话行 3 个点、权限切换卡片都弹出刷新）。
				const list = document.querySelector('[class*="_list_"][class*="_denseList_"]');
				if (!list) return;
				// 只在菜单确实可见时注入
				if (!list.offsetParent && list.getBoundingClientRect().height === 0) return;
				// 修复 30 / 33：对话⇄轨迹切换入口并入 ⋯ 菜单（官方 tabs 行已在移动端隐藏）。
				//   ⚠️ 必须放在「刷新项已存在就 return」之前：本函数会被 observer 反复调用，
				//   一旦早退就会连带跳过视图切换项的标签刷新（切了视图菜单仍显示旧项）。
				mpInjectTraceItem(list);
				if (list.querySelector('[data-mp-item="refresh"]')) return;
				// 修复 30c：刷新图标（30 重构时漏传 SVG，只剩空壳 → 菜单里看不见图标）
				mpInjectMenuItem(list, "refresh", "刷新",
					'<svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">' +
					'<path d="M13.5 8a5.5 5.5 0 1 1-1.62-3.9M13.5 2.2v3.2h-3.2" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>' +
					"</svg>",
					() => { location.reload(); });
			}

			// 按「官方同款类名克隆」的方式往 ⋯ 菜单里插一项（观感/间距/hover 与官方一致）
			function mpInjectMenuItem(list, key, label, iconSvg, onClick) {
				if (list.querySelector('[data-mp-item="' + key + '"]')) return;
				const sample = list.querySelector('div[class*="_itemWrap_"]');
				if (!sample) return;
				const wrap = document.createElement("div");
				wrap.className = sample.className;
				wrap.dataset.mpItem = key;
				const btn = document.createElement("button");
				btn.type = "button";
				btn.setAttribute("role", "menuitem");
				btn.className = sample.querySelector("button") ? sample.querySelector("button").className : "";
				const iconCls = sample.querySelector('span[class*="_itemIcon_"]')?.className || "";
				const labelCls = sample.querySelector('span[class*="_itemLabel_"]')?.className || "";
				btn.innerHTML =
					'<span class="' + iconCls + '">' +
					(iconSvg || '<svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true"></svg>') +
					"</span>" +
					'<span class="' + labelCls + '">' + label + "</span>";
				btn.addEventListener("click", onClick);
				wrap.appendChild(btn);
				list.appendChild(wrap);
			}

			// 修复 30 / 33：往 ⋯ 菜单里加「对话⇄轨迹」切换项。
			//   官方 tabs（对话/轨迹）在移动端被隐藏，两个视图的入口都收敛到这里。
			//   ⚠️ 修复 33：原来只注入「轨迹」一项 —— 一旦切到轨迹视图，
			//     页面上就**再没有任何可见的「对话」入口**（实测：可见含「对话」字样的
			//     元素 = 0，⋯ 菜单里也只有「轨迹」）→ 啾灵反馈「点开轨迹之后就无法返回对话」。
			//   现在按**当前激活的 tab 反向**注入：在对话视图显示「轨迹」，在轨迹视图显示「对话」。
			//   幂等键用 data-mp-item="view-switch"，标签随状态重写（同一项切换，不堆两项）。
			function mpInjectTraceItem(list) {
				if (!mpIsMobile()) return; // 修复 40：仅移动端；桌面端完全走官方
				const tabs = [...document.querySelectorAll(".wSkVaW_tab")];
				if (!tabs.length) return;
				const isActive = (t) => (t.className || "").toString().includes("tabActive") || t.getAttribute("aria-selected") === "true";
				const active = tabs.find(isActive);
				// 判据：当前在轨迹 → 目标是「对话」；否则（含状态未知）→ 目标是「轨迹」
				const inTrace = !!active && (active.textContent || "").trim() === "轨迹";
				const targetLabel = inTrace ? "对话" : "轨迹";
				const targetTab = tabs.find((t) => (t.textContent || "").trim() === targetLabel);
				if (!targetTab) return;
				// 图标：对话 = 气泡；轨迹 = 折线
				const iconSvg = inTrace
					? '<svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">' +
						'<path d="M2.5 3.5h11v7h-6l-3.2 2.6V10.5h-1.8z" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/></svg>'
					: '<svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">' +
						'<path d="M2 11.5c2.5 0 2.5-7 5-7s2.5 7 5 7" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>' +
						'<circle cx="13.8" cy="11.5" r="1.2" fill="currentColor"/></svg>';
				mpInjectMenuItem(list, "view-switch", targetLabel, iconSvg, () => {
					// 借用官方 tab 的 DOM click() 触发 React 状态切换
					const t = [...document.querySelectorAll(".wSkVaW_tab")]
						.find((x) => (x.textContent || "").trim() === targetLabel);
					t?.click();
				});
				// 标签/图标随视图状态刷新（同一菜单项复用，避免重复项堆积）
				const item = list.querySelector('[data-mp-item="view-switch"]');
				if (item) {
					const labelEl = item.querySelector('span[class*="_itemLabel_"]');
					if (labelEl && labelEl.textContent !== targetLabel) labelEl.textContent = targetLabel;
					const iconEl = item.querySelector('span[class*="_itemIcon_"]');
					if (iconEl && iconEl.dataset.mpIconFor !== targetLabel) {
						iconEl.dataset.mpIconFor = targetLabel;
						iconEl.innerHTML = iconSvg;
						item.dataset.mpLabel = targetLabel;
					}
				}
			}

			// ── 8b. 点击会话标题 → 重命名（沿用官方 sessions.rename）──
			// ⚠️ 0.1.5 的标题是 <button disabled>（`.wSkVaW_crumbCurrent`）。
			//   **disabled 表单控件不派发任何鼠标事件**（连捕获阶段也收不到，实测点击
			//   命中的就是该按钮，header 上的监听器一次都没被调用）。
			//   故：① 去掉 disabled 让点击可穿透；② 用 role/aria 标注它是可点的；
			//   ③ 再在 header 上做捕获监听（防御 React 重渲染把 disabled 写回来）。
			function mpEnableTitleTap() {
				if (!mpIsMobile()) return; // 修复 40：仅移动端；桌面端完全走官方
				const crumb = document.querySelector(".wSkVaW_crumbCurrent");
				if (!crumb) return;
				if (crumb.disabled) crumb.disabled = false;
				if (crumb.dataset.mpTap !== "1") {
					crumb.dataset.mpTap = "1";
					crumb.style.cursor = "pointer";
					// 去掉 disabled 后按钮会进入 tab 序，移动端不需要
					crumb.setAttribute("tabindex", "-1");
				}
			}
			// ⋯ 按钮点击后立刻注入（不等 observer 的 300ms 节流，避免菜单先显示一项再补）
			function mpBindMoreButton() {
				const more = document.querySelector(".nL4_yW_moreButton");
				if (!more || more.dataset.mpMoreBound) return;
				more.dataset.mpMoreBound = "1";
				more.addEventListener("click", () => { setTimeout(mpEnsureRefreshItem, 0); setTimeout(mpEnsureRefreshItem, 60); });
			}

			function bindTitleRename() {
				if (!mpIsMobile()) return;
				mpBindMoreButton();
				mpEnableTitleTap();
				const header = document.querySelector(".wSkVaW_header");
				if (!header || header.dataset.mpRenameBound) return;
				header.dataset.mpRenameBound = "1";
				header.addEventListener("click", (e) => {
					const sessions = mpSessionsOf();
					if (!sessions) return;
					const cluster = e.target.closest && e.target.closest(".wSkVaW_titleCluster");
					if (!cluster) return;
					// 只认标题本体，避免抢「模式」下拉与右侧工具按钮的点击
					if (!e.target.closest(".wSkVaW_crumb, .wSkVaW_crumbCurrent, .wSkVaW_crumbs")) return;
					const list = sessions.list.getSnapshot();
					const id = list.current;
					if (id === void 0) return;
					const binding = sessions.binding(id);
					if (!binding) return;
					const crumb = cluster.querySelector(".wSkVaW_crumbCurrent");
					showRenameDialog((crumb && crumb.innerText || "").trim(), binding.session);
				}, true);
				const cluster = header.querySelector(".wSkVaW_titleCluster");
				if (cluster) cluster.style.cursor = "pointer";
			}

		// ── 全局 MutationObserver：懒处理动态挂载的元素 ──
		// 节流 300ms + 去重：防止历史/流式渲染时的高频 mutation 造成
		// 全量 DOM 扫描风暴（华为 WebView 114 renderer 会 mmap OOM 崩溃）。
		let moTimer = 0;
		// ── 修复 23：StatsLine 闪烁（同步处理，不等节流）──
		// 现象：底部「多少轮·多少步」数值变化时（如 130 步 → 131 步）会瞬闪一下
		//       未缩写的原样（`2 轮 · 131 步`，尾部被截成省略号），随后才恢复缩写态。
		// 根因：官方 StatsLine 的 span 用 key={group} 渲染，group 文本一变 React 就
		//       重建该 span 并写回**未缩写原文**；而我们的缩写/隐藏跑在下面 300ms 的
		//       节流回调里 → 这段窗口内页面显示的是「未修复」形态，即用户看到的闪烁。
		// 修法：在 observer 回调里**同步**跑一次 processStatsLine()。MutationObserver
		//       回调处于微任务阶段、先于浏览器绘制，因此 React 写入的那一帧就直接是
		//       缩写态，中间不产生可见帧。processStatsLine 幂等（已缩写的文本再跑
		//       结果不变、已隐藏的 span 再跑仍是 none），故不会自触发死循环；
		//       它只做「seat 内 div 的 span 文本/display」这一小范围操作，
		//       不构成全量 DOM 扫描，不存在原来的 OOM 风险。
		// ⚠️ 只同步这一个函数；其余重活（trimMeta / 抽屉同步 / 设置 dialog
		//    等）仍留在 300ms 节流里，避免流式渲染时的高频全量扫描。
		let statsSyncQueued = false;
		const scheduleStatsSync = () => {
			if (statsSyncQueued) return;
			statsSyncQueued = true;
			queueMicrotask(() => {
				statsSyncQueued = false;
				processStatsLine();
			});
		};
		// ── 修复 35b：轨迹详情面板「不可用」回填闪烁（同步处理，不等节流）──
		// 现象（啾灵反馈）：点开轨迹表格某行时，面板先渲染出一帧「不可用」，300ms 后才被
		//   mpPatchTrajectoryTiming 回填 → 每次点行都闪一下，体验不如官方原生。
		// 根因：与修复 23 完全同源 —— 回填挂在 300ms 节流里，React 写入的那一帧
		//   屏幕上还是官方原始的「不可用」文案。
		// 修法：observer 回调里检测「details 面板新增/内部文本变更」，用 queueMicrotask
		//   同步跑一次 mpPatchTrajectoryTiming（微任务先于绘制，用户看不到中间帧）。
		// 幂等/收敛：回填自身写 dd 文本会再触发一次 mutation → 下一轮回填时
		//   面板里已无「不可用」→ 在 mpCollectTimings 之前早退（零写 DOM），自然收敛。
		//   事件窗口未就绪时 mpCollectTimings 返回 null 也不写 DOM，留待 300ms 兜底。
		let traceTimingQueued = false;
		const scheduleTraceTiming = () => {
			if (traceTimingQueued) return;
			traceTimingQueued = true;
			queueMicrotask(() => {
				traceTimingQueued = false;
				mpPatchTrajectoryTiming();
			});
		};
		const mo = new MutationObserver((muts) => {
			// 设置 dialog 挂载时立即处理，避免 300ms 节流导致旧样式闪烁
			for (const m of muts) {
				for (const n of m.addedNodes) {
					if (!n || n.nodeType !== 1) continue;
					if (n.matches && n.matches('[role="dialog"][aria-modal="true"]')) {
						setupSettingsDialog(n);
					} else if (n.querySelectorAll) {
						n.querySelectorAll('[role="dialog"][aria-modal="true"]').forEach(setupSettingsDialog);
					}
					// 修复 35b：详情面板挂载 → 微任务回填（抢在绘制前）
					if (n.matches && n.matches(".Y0dWHa_details")) {
						scheduleTraceTiming();
					} else if (n.querySelector && n.querySelector(".Y0dWHa_details")) {
						scheduleTraceTiming();
					}
				}
			}
			// StatsLine 相关改动 → 立即（微任务）归一化，消除闪烁
			// ⚠️ characterData 变更的 target 是**文本节点**（nodeType 3），
			//    必须取 parentElement 再判断，否则漏掉 React 只改文本的情况。
			for (const m of muts) {
				let t = m.target;
				if (!t) continue;
				if (t.nodeType === 3) t = t.parentElement;
				else if (t.nodeType !== 1) continue;
				if (t && t.closest && t.closest(".Y0dWHa_details")) {
					// 修复 35b：切行/重渲染时面板文本原地更新 → 同步回填（幂等，早退收敛）
					scheduleTraceTiming();
				}
				if (t && t.closest && (t.closest("[data-composer-seat]") || t.closest("[data-composer-stats]"))) {
					scheduleStatsSync();
					break;
				}
			}
			if (moTimer) return;
			moTimer = setTimeout(() => {
				moTimer = 0;
				document.querySelectorAll(".xzv4MW_timeEnd").forEach(trimMeta);
				processStatsLine();
				watchApproval();
				mpSyncDrawer();
				mpAnchorBurger(); // ⭐ 修复 47：兜底重挂（React 摘掉 header 子节点时专用 observer 若漏掉）
				bindTitleRename();
				mpEnsureRefreshItem();
				mpPatchTrajectoryTiming();
				mpScheduleStatus();   // 通知栏状态：跟着 DOM 变化走（内部 1s 节流）
				mpScheduleTranscript();   // 悬浮窗转录：同一批 DOM 变化，内部 1.5s 节流
				document.querySelectorAll('[role="dialog"][aria-modal="true"]').forEach(setupSettingsDialog);
			}, 300);
		});

		// ── 4e. 非消息区域长按拦截：不触发系统文字选择器 ──
		// 策略：touchstart 非 passive 允许我们在需要时 preventDefault；
		//       只在长按时（≥400ms 且无明显移动）才阻止，短按/滚动正常走原生。
		var mpLongPressStart = null;
		var mpLongPressMoved = false;
		var MP_LONGPRESS_THRESHOLD = 400;
		var MP_LONGPRESS_MOVE_THRESHOLD = 12;

		function mpIsMessageArea(el) {
			if (!el) return false;
			if (el.closest(".wSkVaW_viewArea")) return true;
			if (el.closest("input") || el.closest("textarea") || el.closest("[contenteditable]")) return true;
			return false;
		}

		document.addEventListener("touchstart", function(e) {
			if (!mpIsMobile()) return; // 修复 40
			if (e.touches.length !== 1) return;
			var t = e.touches[0];
			mpLongPressStart = { x: t.clientX, y: t.clientY, time: Date.now() };
			mpLongPressMoved = false;
		}, false);

		document.addEventListener("touchmove", function(e) {
			if (!mpIsMobile()) return; // 修复 40
			if (!mpLongPressStart) return;
			var t = e.touches[0];
			var dx = Math.abs(t.clientX - mpLongPressStart.x);
			var dy = Math.abs(t.clientY - mpLongPressStart.y);
			if (dx > MP_LONGPRESS_MOVE_THRESHOLD || dy > MP_LONGPRESS_MOVE_THRESHOLD) {
				mpLongPressStart = null;
			}
		}, false);

		document.addEventListener("touchend", function(e) {
			if (!mpIsMobile()) return; // 修复 40
			if (!mpLongPressStart) return;
			var elapsed = Date.now() - mpLongPressStart.time;
			if (elapsed < MP_LONGPRESS_THRESHOLD) {
				mpLongPressStart = null;
				return;
			}
			// 长按超时，检查是否在消息区域
			var t = e.changedTouches[0];
			var target = document.elementFromPoint(t.clientX, t.clientY);
			if (mpIsMessageArea(target)) {
				mpLongPressStart = null;
				return; // 消息区域：让原生长按行为正常执行
			}
			// 非消息区域长按：阻止系统文本选择器
			e.preventDefault();
			mpLongPressStart = null;
		}, false);

		// contextmenu 兜底（调试工具偶尔触发）
		document.addEventListener("contextmenu", function(e) {
			// ⚠️ 修复 40：此前**无条件** preventDefault → 桌面端右键菜单被我们吃掉，
			// 这是「电脑那边也被改了」里最影响日常使用的之一。
			if (!mpIsMobile()) return; // 修复 40
			if (!mpIsMessageArea(e.target)) {
				e.preventDefault();
			}
		}, false);

		// ── 10. 顶栏标题重命名：点击标题弹自定义输入框，走官方 sessions.rename ──
		// ⚠️ 不能只在 apply() 里抓一次 ctx.get("sessions")：本插件 `inject: []`（无声明依赖），
		//   激活可能早于 sessions 服务就绪 → 抓到 null 后永久为 null（实测 0.1.5 下
		//   `mpSessions === null`，导致点标题毫无反应）。改为**每次用时懒取**。
		let mpCtx = null;
		let mpSessionsCache = null;
		// ══ 修复 51：刷新后先闪 Hero 主界面（照搬 ECS 的 dsh-custom 补丁 A + C）══
		// 现象（啾灵原话）：「在对话界面中，点右上角刷新，刷新成功后会先出现这个主界面
		//   （探索未至之境），然后才回到对话界面」。
		// 根因（官方状态机的必然结果，0.1.5 / 0.1.6 都一样）：
		//   Hero 的渲染判据是 `sessionId === void 0`，而 sessionId 来自
		//   sessions.list.current，由 SessionManager.buildListSnapshot() 算出：
		//     const current = selected !== void 0 &&
		//       (items.some(i => i.sessionId === selected) || this.addresses.has(selected))
		//         ? selected : void 0
		//   启动瞬间 summaries 必为空 → items 空 → current 恒为 void 0
		//   → **首帧必然渲染 Hero**；等 sessions.list RPC 返回、current 落定，
		//   才挂载对话 → 这就是那一闪。更糟的是 projectList() 里
		//     if (current === void 0) { if (persisted !== void 0) this.selection.set({}) }
		//   会**擦掉官方持久化的选择**。
		// ECS 的解法（/root/dsh-custom，2026-09-10，记录见 ecs维护/HISTORY.md §693）
		// 是给官方文件打两个补丁：
		//   A `runtime-select-trust-pending`：current 判定加 `this.listPhase === "pending" ||`
		//   C `runtime-construct-projectlist`：构造后立刻同步一次 projectList()
		// 本插件不改官方文件（升级不丢），改为**运行时包装同一个方法**：
		//   · 包装 manager.buildListSnapshot，pending 期把 current 补成持久化选择
		//     （= 补丁 A，语义逐字一致）；
		//   · 包装后立刻强制重算缓存 + sessions.projectList()，让**第一帧**就有 current
		//     （= 补丁 C）。
		// ⚠️ 只做「pending 期信任持久化选择」这一件事：清单 ready 后若该会话确已
		//   删除，current 自然回落 void 0 → 照常显示 Hero（与官方语义一致）。
		// ⚠️ selected 由官方构造时从 localStorage `dsh.sessions.current` 同步恢复
		//   （ClientSessions 构造 → new SessionManager(remote, restored.sessionId)），
		//   构造完成即有值，无需我们自己去读 localStorage。
		function mpInstallPendingTrust() {
			const sessions = mpSessionsOf();
			if (!sessions) return false;
			const mgr = sessions.manager;
			if (!mgr || typeof mgr.buildListSnapshot !== "function") return false;
			if (mgr.__mpPendingTrust === true) return true;   // 幂等
			const orig = mgr.buildListSnapshot;
			mgr.buildListSnapshot = function () {
				const snap = orig.call(this);
				/* 补丁 A：pending 期信任持久化选择。
				   官方原判据要求该会话已出现在 items（RPC 结果）里，启动窗口期
				   items 必空 → current 恒 void 0 → 首帧渲染 Hero。 */
				if (snap.current === void 0 && this.listPhase === "pending" && this.selected !== void 0) {
					return {
						...snap,
						current: this.selected,
						currentAddress: this.addresses.get(this.selected)
					};
				}
				return snap;
			};
			mgr.__mpPendingTrust = true;
			/* 补丁 C：立刻同步一次首投影，让**第一帧**就有 current。
			   ⚠️ 不能只调 projectList() —— 它读 getListSnapshot()，而后者走
			   notifier.ensureFresh()，缓存不脏时**直接返回构造期算出的旧值**
			   （那时还没包装，current 已是 void 0）。必须先强制重算缓存。 */
			try {
				mgr.listSnapshotCache = mgr.buildListSnapshot();
				sessions.projectList();
			} catch (e) { /* 失败不影响正确性：后续 manager 通知仍走包装后的方法 */ }
			return true;
		}
		/* 用 ctx.inject 等待 sessions 服务（比定时器可靠）：
		   · 服务已就绪 → 回调**同步**执行，赶在 React 首帧之前；
		   · 服务晚到 → 就绪那一刻触发；
		   · 服务被重建（官方插件重挂）→ 依赖变化会**重新触发**，
		     而定时器只在启动 3s 内有效、之后新实例就漏掉了。 */
		function mpWatchSessions(ctx) {
			try {
				ctx.inject(["sessions"], () => { mpInstallPendingTrust(); });
			} catch (e) { /* 静默：定时器兜底 */ }
		}

		function mpSessionsOf() {
			if (mpSessionsCache) return mpSessionsCache;
			if (!mpCtx) return null;
			try { mpSessionsCache = mpCtx.get("sessions") ?? null; } catch (e) { mpSessionsCache = null; }
			return mpSessionsCache;
		}

		// ══ 状态上报：把「AI 当前在干什么」实时推给原生通知栏 ══
		// 原生侧在 WebView 上挂了 `window.DSHAndroid.onStatus(json)`，收到后用**同一个
		// 通知 ID** 更新前台服务的通知（只 notify，不重启服务）。
		//
		// 为什么读 DOM 而不是官方 store：本插件 `inject: []`（无声明依赖），
		//   直接读 DOM 反而最稳 —— 官方重命名 store 字段时 DOM 契约还在。
		//
		// 三个信号的来源（0.2.0-rc.2 实测，均在官方 dsh-client-ui-chat / -conversation 里）：
		//   · **运行中** = 主按钮变成「停止生成」。官方 `primaryStops` 为真时把
		//     `uV2eYG_primary` 的 aria-label 换成 `t("input.stop")`
		//     （dsh-client-ui-conversation/lib/client.js:17407 / 17571）。
		//   · **在干什么** = 最后一个分组头 `.O_Ebla_title` 的文本。官方
		//     `ProcessGroupHeader` 在组未关闭时用 `message.stepProcess.<activity>`
		//     （「正在运行命令」），关闭后用 `processTitle()`（「执行了命令」）；
		//     两者都可能接 `message.turnProcess.separator`（" · "）+ `liveToolDetail`
		//     从工具参数里提出来的详情（如 `sleep 10`）。
		//   · **详细内容** = 最后一条助手正文（`[data-slot="conversation.chat.node"]`
		//     下的 `.hWmORq_root`），运行中它同时含正在流式的思考内容。
		//
		// ⚠️ 通知更新节流 1s：太频繁会让通知栏闪烁并白白耗电；内容没变则一个字节都不发。
		let mpStatusTimer = 0;
		let mpStatusPending = false;
		let mpLastStatusJson = "";
		// 转录通道（见 mpCollectTranscript）：节流比 status 宽，指纹不变则一个字节都不发。
		let mpTranscriptTimer = 0;
		let mpTranscriptPending = false;
		let mpLastTranscriptJson = "";

		function mpIsEngineRunning() {
			// 文案判据（引擎版本与界面语言在本 App 里都固定）。
			// 兜底：没有主按钮时当作空闲，宁可显示"空闲"也不要谎报在跑。
			const btn = document.querySelector(".uV2eYG_primary");
			if (!btn) return false;
			const label = btn.getAttribute("aria-label") || "";
			return label.indexOf("停止") >= 0 || /stop/i.test(label);
		}

		function mpCurrentActivity() {
			// 取**最后一个**分组头：它就是当前正在跑（或刚跑完）的那一组。
			const titles = document.querySelectorAll(".O_Ebla_title");
			if (!titles.length) return { activity: "", text: "" };
			const el = titles[titles.length - 1];
			const text = (el.textContent || "").replace(/\s+/g, " ").trim();
			const kind = el.getAttribute("data-process-activity") || "";
			return { activity: kind, text: text };
		}

		function mpTailAssistantText(limit) {
			// ⚠️ 必须限定 `data-chat-flow-kind="assistant-step"`。
			//   `.hWmORq_root` 是 AssistantMarkdown 的类，但**不能**只靠它选：
			//   同名的消息容器会把用户自己发的话也捞进来（真机实测用户消息是
			//   `data-chat-flow-kind="user"`，内含 Sixlwa_* 气泡）。
			//   限定 flow-kind 后取到的必然是助手输出（含 reasoning 与 response 两种
			//   group-part），运行中就是正在流式输出的那段。
			const nodes = document.querySelectorAll(
				'[data-chat-flow-kind="assistant-step"] .hWmORq_root'
			);
			for (let i = nodes.length - 1; i >= 0; i--) {
				const t = (nodes[i].textContent || "").replace(/\s+/g, " ").trim();
				if (t) return t.length > limit ? "…" + t.slice(t.length - limit) : t;
			}
			return "";
		}

		// 页面实际背景色（`#RRGGBB`），用来让原生侧把 WebView / 容器 / 窗口的底色对齐。
		//
		// 为什么必须上报：`adjustResize` 让窗口变矮是**逐帧动画**的，WebView 跟着变矮，
		// 但页面重排要等下一帧 —— 这个空档里新露出的那条区域还没有内容覆盖，
		// 显示的就是 WebView 自己的背景色。底色若与页面不一致，就会看到一条异色块
		// （深色底 + 浅色页面 = 用户实测的"点输入框时下半屏先黑一下"）。
		//
		// ⚠️ 不能写死颜色：官方主题可切换，写死只是把问题换个方向暴露。
		// ⚠️ 输出 `#RRGGBB` 而不是 computed style 的 `rgb(...)`：Android 的
		//   `Color.parseColor` 原生就吃这个格式，省掉在 Kotlin 里再写一套 CSS 颜色解析。
		//
		// ⚠️ **读主题 token 而不是读 `backgroundColor`**（这是"默认跟随主题色"这一条的
		//   实现）：`backgroundColor` 只是 token 的**最终投影**，在纯色主题下两者恰好
		//   相等，一旦主题改用背景图/渐变，投影与主题语义就分叉 —— 那时要把系统栏
		//   对齐到"主题的基底色"就只能靠 token。深色主题下 token 是 `#151517`、
		//   浅色是 `#fff`，与旧行为一致，所以这次替换对当前外观零改变。
		//   token 定义在 `body` 上（`html` 上取到空串），所以两个元素都要试。

		/** 主题基底色 token，按优先级排列。 */
		const MP_THEME_BG_TOKENS = ["--dsw-alias-bg-base"];

		// 把 CSS 颜色值规范化成 `#RRGGBB`；无法解析或完全透明时返回空串。
		//
		// 支持 token 实际会用到的几种写法：`#fff`（缩写）、`#ffffff`、`#ffffffff`
		// （带 alpha，取前 6 位）与 `rgb()/rgba()`。其余写法（百分比、颜色函数）
		// 一律返回空串，让调用方退回下一条路 —— 猜错比不猜更糟。
		function mpCssColorToHex(value) {
			const raw = String(value || "").trim();
			if (!raw) return "";
			let r;
			let g;
			let b;
			if (raw.startsWith("#")) {
				const h = raw.slice(1);
				const expand = (s) => (s.length === 1 ? s + s : s);
				if (h.length === 3 || h.length === 4) {
					r = parseInt(expand(h[0]), 16);
					g = parseInt(expand(h[1]), 16);
					b = parseInt(expand(h[2]), 16);
				} else if (h.length === 6 || h.length === 8) {
					r = parseInt(h.slice(0, 2), 16);
					g = parseInt(h.slice(2, 4), 16);
					b = parseInt(h.slice(4, 6), 16);
				} else {
					return "";
				}
			} else {
				const m = /^rgba?\(([^)]+)\)$/.exec(raw);
				if (!m) return "";
				const parts = m[1].split(",").map((s) => parseFloat(s));
				if (parts.length < 3 || parts.some((n) => !isFinite(n))) return "";
				// 完全透明 ⇒ 视为"没设背景"（rgba(0,0,0,0) 是常见表示）
				if (parts.length === 4 && parts[3] === 0) return "";
				[r, g, b] = parts;
			}
			if (![r, g, b].every((n) => isFinite(n))) return "";
			const hex = (n) => Math.max(0, Math.min(255, Math.round(n)))
				.toString(16).padStart(2, "0").toUpperCase();
			return "#" + hex(r) + hex(g) + hex(b);
		}

		function mpPageBackground() {
			// ① 主题 token 优先（见上方注释里为什么要换掉 backgroundColor）
			for (const el of [document.documentElement, document.body]) {
				if (!el) continue;
				const cs = getComputedStyle(el);
				for (const token of MP_THEME_BG_TOKENS) {
					const hex = mpCssColorToHex(cs.getPropertyValue(token));
					if (hex) return hex;
				}
			}
			// ② token 取不到（老内核/主题改结构）⇒ 退回实际算出来的背景色。
			// body 透明时往上找 html；都透明才退回白。
			for (const el of [document.body, document.documentElement]) {
				if (!el) continue;
				const hex = mpCssColorToHex(getComputedStyle(el).backgroundColor);
				if (hex) return hex;
			}
			return "#FFFFFF";
		}

		function mpCollectStatus() {
			const running = mpIsEngineRunning();
			const { activity, text } = mpCurrentActivity();
			let title = (document.title || "").replace(/\s*—\s*DeepSeek Harness\s*$/, "").trim();
			return {
				running: running,
				activity: activity,
				text: text,
				body: mpTailAssistantText(400),
				title: title,
				bg: mpPageBackground(),
			};
		}

		function mpReportStatus() {
			const bridge = window.DSHAndroid;
			if (!bridge || typeof bridge.onStatus !== "function") return;
			let json;
			try { json = JSON.stringify(mpCollectStatus()); } catch (e) { return; }
			if (json === mpLastStatusJson) return;   // 无变化 ⇒ 不打扰通知栏
			mpLastStatusJson = json;
			try { bridge.onStatus(json); } catch (e) { /* 原生侧异常不影响页面 */ }
		}

		// 节流：流式输出时 DOM 变化极频繁，合并成最多 1 次/秒。
		function mpScheduleStatus() {
			if (mpStatusPending) return;
			mpStatusPending = true;
			mpStatusTimer = window.setTimeout(() => {
				mpStatusPending = false;
				mpReportStatus();
			}, 1000);
		}

		// ══ 悬浮窗展开态的转录通道 ══════════════════════════════════════════
		// 用途：原生侧悬浮球展开后要显示**完整**的对话 —— 用户消息、助手完整回复、
		//   每一次工具调用。通知栏那条 status 通道的 body 被刻意压到 400 字，
		//   体量差一个数量级，所以单独开一条（原生侧对应 ChatTranscript.kt）。
		//
		// ⚠️ 选择器一律用**属性**而不是类名：`data-chat-flow-kind`（官方 chat.js 的
		//   chatNode() 生成）与 `data-tool` / `data-state`（官方 tool.js 的 ToolRow
		//   写死）是稳定契约，而 `hWmORq_root` / `Sixlwa_bubble` 这类 CSS Module
		//   哈希类名每次官方发版都可能变。类名只用于"取文本时收窄"，取不到就退回
		//   整个节点 —— 退化的结果是"多带一点噪音"，而不是"内容全丢"。
		const MP_TRANSCRIPT_MAX_ITEMS = 80;
		const MP_TRANSCRIPT_MAX_CHARS = 2000;
		// ⚠️ 工具行的判据是 `[data-chat-flow-kind="tool-call"]`，**不是** `[data-tool]`。
		//   真机实测（CDP 读真实会话 DOM）：一次 bash 工具调用渲染出的是
		//   `<div data-chat-flow-kind="tool-call" data-turn-process-member="true"
		//     data-turn-process-hidden="true" hidden="until-found">`，
		//   而全文档 `[data-tool]` 命中数**恒为 0** —— `data-tool` 是
		//   `dsh-client-ui-tool` 的标记，本页面走的不是那套渲染器。
		//   只认 `data-tool` 的后果是「工具调用一条都采不到」，正是本通道上线后
		//   一直没见过的现象。
		// ⚠️ 工具行即使被折叠（`hidden="until-found"`）也照样采：`textContent`
		//   对 hidden 元素仍可读，而用户要的正是「展开面板能看到调了哪些工具」，
		//   恰恰是折叠态下最需要补的信息。
		const MP_TRANSCRIPT_SELECTOR = [
			'[data-chat-flow-kind="user"]',
			'[data-chat-flow-kind="steering"]',
			'[data-chat-flow-kind="assistant-step"]',
			'[data-chat-flow-kind="tool-call"]',
			'[data-chat-flow-kind="turn-error"]',
			"[data-tool]",
		].join(",");

		/** 压成单行并按 code point 截断（按 UTF-16 切会把 emoji 切成半个）。 */
		function mpTranscriptText(raw, limit) {
			const flat = String(raw === null || raw === undefined ? "" : raw)
				.replace(/\s+/g, " ").trim();
			const cps = Array.from(flat);
			return cps.length > limit ? cps.slice(0, limit - 1).join("") + "…" : flat;
		}

		/**
		 * 取节点的文本，**剔除元数据与按钮子树**。
		 *
		 * ⚠️ 为什么必须剔除（真机实测）：官方把时间戳与操作按钮放在同一条消息的尾部
		 *   （`.xzv4MW_actions` 里一个 `.xzv4MW_timeStart` 加一个含 svg 的复制按钮），
		 *   直接取 `textContent` 会得到「只回复两个字：通了01:14」—— 时间戳粘在用户
		 *   原话后面，看起来像用户自己打的字。
		 *
		 * ⚠️ 判据用 **`[data-clock]` + `button`/`svg` 标签**，不用那个哈希类名：
		 *   `data-clock` 是官方写死的元数据标记（`start` = 时间戳、`end` = 时间戳 +
		 *   用量），实测页面里两处都有；而"消息尾部的操作按钮"永远是 button。
		 *   类名随官方发版会变，属性不会。取不到就退回原文（多带点噪音，不丢内容）。
		 */
		function mpTranscriptVisibleText(el) {
			try {
				const clone = el.cloneNode(true);
				const junk = clone.querySelectorAll("[data-clock], button, svg");
				for (let i = 0; i < junk.length; i++) junk[i].remove();
				return clone.textContent || "";
			} catch (e) {
				return el.textContent || "";
			}
		}

		/** 会话标题（与状态通道同源，两处共用免得正则漂移）。 */
		function mpConversationTitle() {
			return (document.title || "").replace(/\s*—\s*DeepSeek Harness\s*$/, "").trim();
		}

		function mpCollectTranscript() {
			const items = [];
			// 限定在消息视图内：轨迹页/设置页里也可能有 `[data-tool]` 之类的节点，
			// 不加限定会把别的界面的内容混进对话里。取不到容器就退回整个文档。
			const root = document.querySelector(".wSkVaW_viewArea") || document;
			let nodes = [];
			try { nodes = root.querySelectorAll(MP_TRANSCRIPT_SELECTOR); }
			catch (e) { nodes = []; }

			// 引擎是否在跑（与状态通道同源），以及最后一个助手节点 —— 只有它
			// 可能是"正在输出"的那一条。
			const engineRunning = mpIsEngineRunning();
			let lastAssistantStep = null;
			for (const el of nodes) {
				if (el.getAttribute("data-chat-flow-kind") === "assistant-step") lastAssistantStep = el;
			}

			for (const el of nodes) {
				const tool = el.getAttribute("data-tool");
				if (tool) {
					const state = el.getAttribute("data-state") || "";
					items.push({
						role: "tool",
						// 工具行的可见文本已经含标题（如「运行 bash」）与状态文案，
						// 直接用；为空才退回工具名，保证面板上至少看得见调了哪个工具。
						text: mpTranscriptText(mpTranscriptVisibleText(el), MP_TRANSCRIPT_MAX_CHARS) || tool,
						running: state === "running",
					});
					continue;
				}
				const kind = el.getAttribute("data-chat-flow-kind");

				// 工具调用（真机实测形状，见选择器处的注释）：
				//   `data-sample` = 工具名（`bash`），`data-state` = `ok`/`running`，
				//   可见文本 = 标题 + 摘要（「运行命令 Echo TOOL_OK to test output」）。
				// ⚠️ 两个属性都长在**内层** `[data-sample]` 节点上，不在
				//   `[data-chat-flow-kind]` 那个外层 div 上 —— 直接读 el 会全空。
				if (kind === "tool-call") {
					const inner = el.querySelector("[data-sample]") || el;
					const sample = inner.getAttribute("data-sample") || "";
					const state = inner.getAttribute("data-state") || "";
					const body = mpTranscriptText(mpTranscriptVisibleText(inner), MP_TRANSCRIPT_MAX_CHARS);
					items.push({
						role: "tool",
						text: body || sample || "工具调用",
						running: state === "running",
					});
					continue;
				}

				// assistant-step 的**思考**与**正文**是两个并列节点，靠
				// `data-chat-group-part` 区分（真机实测：`reasoning` / `response`）。
				// ⚠️ 不区分时两者都发成 `assistant`，面板上标签同为「AI」、样式也
				//   一模一样，用户看到的就是"思考与正文糊在一起、同时冒出来"。
				const part = kind === "assistant-step"
					? (el.getAttribute("data-chat-group-part") || "")
					: "";

				let body = el;
				if (kind === "assistant-step") {
					// 收窄到 AssistantMarkdown 的正文容器。官方 AssistantMarkdown 对
					// tool-call 块是直接 `break` 跳过的（不渲染进 body），所以这里取到
					// 的是纯正文，不会把工具标题混进助手回复里。
					body = el.querySelector(".hWmORq_root") || el;
				}
				const text = mpTranscriptText(mpTranscriptVisibleText(body), MP_TRANSCRIPT_MAX_CHARS);
				if (!text) continue;
				// 只有"引擎在跑"且"这是最后一条助手节点"才算正在输出 —— 后面的
				// 节点一出现，前一个自然就不再是运行中。
				const live = engineRunning && kind === "assistant-step" && el === lastAssistantStep;
				if (kind === "user") items.push({ role: "user", text: text, running: false });
				// steering = 用户在回合进行中追加的指令，按用户消息显示。
				else if (kind === "steering") items.push({ role: "user", text: text, running: false });
				else if (kind === "assistant-step") {
					items.push({
						role: part === "reasoning" ? "reasoning" : "assistant",
						text: text,
						running: live,
					});
				}
				else if (kind === "turn-error") items.push({ role: "error", text: text, running: false });
			}

			return {
				title: mpConversationTitle(),
				running: mpIsEngineRunning(),
				// 只留最后 MAX 条：面板要看的是当前进展，早期历史可以丢。
				items: items.length > MP_TRANSCRIPT_MAX_ITEMS
					? items.slice(items.length - MP_TRANSCRIPT_MAX_ITEMS)
					: items,
			};
		}

		function mpReportTranscript() {
			const bridge = window.DSHAndroid;
			if (!bridge || typeof bridge.onTranscript !== "function") return;
			let json;
			try { json = JSON.stringify(mpCollectTranscript()); } catch (e) { return; }
			// 指纹不变就一条都不发：这条通道体量比 status 大两个数量级，
			// 无脑重推会白白占用原生侧的 JSON 解析与面板重绘。
			if (json === mpLastTranscriptJson) return;
			mpLastTranscriptJson = json;
			try { bridge.onTranscript(json); } catch (e) { /* 原生侧异常不影响页面 */ }
		}

		// 节流 250ms：用户要求面板里"看得见流式输出"，所以这里必须跟着 DOM 的
		// 逐字变化走。原来的 1.5s 会让整段回复攒成两三坨跳出来 —— 观感就是
		// "没有流式、一次全出"。
		//
		// ⚠️ 成本可控的依据：`mpReportTranscript()` 带指纹去重，**内容没变一个
		//   字节都不发**，所以节流只决定"最多多快发一次"，不决定实际发送量。
		//   DOM 的 characterData 变化本身就已经是逐 token 的节奏。
		function mpScheduleTranscript() {
			if (mpTranscriptPending) return;
			mpTranscriptPending = true;
			mpTranscriptTimer = window.setTimeout(() => {
				mpTranscriptPending = false;
				mpReportTranscript();
			}, 250);
		}

		function mpInstallTranscriptBridge() {
			mpReportTranscript();
			const t = window.setInterval(mpReportTranscript, 5000);   // 兜底：DOM 静默变化时也能纠正
			return () => window.clearInterval(t);
		}

		function mpInstallStatusBridge() {
			mpReportStatus();
			const t = window.setInterval(mpReportStatus, 5000);   // 兜底：DOM 静默变化时也能纠正
			return () => window.clearInterval(t);
		}

		// ══ 修复 65：键盘升起时输入框被盖住（原生预判）════════════════════
		// 现象：点输入框后，键盘从屏幕底部升起来，但输入框要等约 200ms 才跳上去，
		//      中间这几帧输入框被键盘压住。
		//
		// 真机实测（Huawei CDY-TN90 / Android 10，各 2~3 轮一致）：
		//   · focus → 窗口变矮 = 180~220ms
		//   · 期间窗口高度 764 → 475，**只有 1 个 resize 事件、无中间帧**
		//   · visualViewport 与 innerHeight **同一时刻**变化，没有更早的信号
		//   · navigator.virtualKeyboard.boundingRect 恒为 0（该内核不提供几何信息）
		//   · 主线程最长阻塞 76~85ms，不足以解释这 200ms
		// → 这 200ms 里页面**拿不到任何"键盘现在多高"的信息**，只能等窗口变矮。
		//   这是平台行为（逐帧 insets 回调要 API 30 的 WindowInsetsAnimation），
		//   不是插件或主线程的问题。
		//
		// 修法：**提前告诉原生"键盘要来了"**。`focusin` 发生在点击那一刻，
		//   必然早于窗口变矮。原生收到后按**上一次观测到的键盘高度**先把
		//   WebView 变矮，等真 insets 到达再交回给系统。
		//
		// ⚠️ 这里只负责"通知"，不猜高度 —— 高度由原生实测缓存（每台机器、
		//   每种输入法都不同，写死必错）。
		// ⚠️ 只认输入框本体。用 `focusin` 的捕获阶段，保证在任何 stopPropagation 之前。
		function setupImePrejudge() {
			if (!mpIsMobile()) return () => {};   // 仅移动端（桌面无软键盘）
			const onFocusIn = (e) => {
				try {
					const t = e.target;
					if (!t || typeof t.closest !== "function") return;
					// 只认 composer 的编辑区；点「+」等按钮抢焦点不该触发
					if (!t.closest("[data-composer-input]")) return;
					const bridge = window.DSHAndroid;
					if (!bridge || typeof bridge.onImeWillShow !== "function") return;
					bridge.onImeWillShow();
				} catch (err) { /* 静默：绝不打断输入 */ }
			};
			document.addEventListener("focusin", onFocusIn, true);
			return () => document.removeEventListener("focusin", onFocusIn, true);
		}
		// ══ 修复 35：轨迹详情面板「首 token 时间不可用」回填（2026-09-16 啾灵反馈）══
		// 现象：轨迹页点开任意一条历史数据，「首 token 延迟 / 生成 / 吞吐量」三行全显示
		//      「首 token 时间不可用」（实时流式时是有的，落盘后再回看就没了）。
		// 根因（官方缺陷，非本插件造成）：
		//   官方轨迹回放（dsh-client-ui-trajectory 的 fallbackState$1）**只认
		//   `assistant/live-chunk`** 这一种事件来喂 `state.firstTokenTime`；
		//   但持久化到磁盘的分片事件名不是它 —— 老会话是 `assistant/chunk`，
		//   新会话（v3）把分片打包进 `assistant/message.data.stream`。
		//   → 分片被整个跳过 → firstTokenTime 恒为 undefined → ?? null → 显示不可用。
		//   而官方**服务端**（dsh-session-stats）和**客户端 connection 包**都用
		//   `event.data.stream` + `assistantStreamFirstTokenTime()` 算得出这个值，
		//   说明**数据其实在**，只有轨迹页把它忽略了。
		// 修法（纯插件、不改官方文件）：从 sessions 服务读当前会话的事件窗口，
		//   用官方同一套算法（isTokenDelta 判据 + packed-run 的 time0/dt 累加）
		//   算出每个 step 的 firstTokenTime，再在轨迹详情面板里把「不可用」
		//   就地替换成正确的时长/吞吐量文案。
		// ⚠️ 只在窄屏且轨迹面板存在时工作；算不出的一律保持原样（不猜、不编）。
		/* 启动动画收尾的兜底上限（ms）。实测插件激活约 1.4~1.7s，取 6s 留足余量：
		 * 只有判据真的永远不满足时才会走到这里，目的是避免原生启动层永久盖住界面。 */
		/**
		 * 等待「界面真的可用」的上限。
		 *
		 * ⚠️ 从 6000 提到 9000：判据里新增了「官方前端的启动页已消失」，
		 *   而那一项实测要等到 `readyState === complete` 之后才满足
		 *   （旧判据 10231ms 满足，官方启动页 10475ms 才消失），
		 *   留足余量避免在正常启动路径上误触发超时兜底。
		 */
		const UI_READY_MAX_WAIT_MS = 9000;

		const MP_TIMING_UNAVAILABLE = "首 token 时间不可用";		const MP_TIMING_NOT_RECORDED = "未记录";
		const MP_TIMING_STEP_START_UNAVAILABLE = "步骤开始时间不可用";
		const MP_TIMING_DURATION_TOO_SHORT = "时长过短";
		const MP_TIMING_PENDING = "等待中";
		const MP_TIMING_USAGE_UNAVAILABLE = "用量不可用";
		const MP_TIMING_OUTPUT_UNAVAILABLE = "输出 token 数不可用";

		// 与官方 dsh-llm `isTokenDelta` 完全一致的判据：
		// 非空 text/reasoning delta，或带非空 argumentsDelta / 有 name 的 tool-call delta。
		function mpIsTokenDelta(chunk) {
			if (!chunk) return false;
			switch (chunk.type) {
				case "text-delta":
				case "reasoning-delta": return chunk.text !== "" && chunk.text !== void 0;
				case "tool-call-delta": return (chunk.argumentsDelta !== "" && chunk.argumentsDelta !== void 0) || chunk.name !== void 0;
				default: return false;
			}
		}
		// 复现官方 `firstRunMemberTime`：packed run 的成员时间 = time0 起按 dt 累加。
		function mpRunMemberTime(run, predicate) {
			const frags = run.type === "tool-call-chunks" ? run.args : run.texts;
			if (!Array.isArray(frags)) return void 0;
			let time = run.time0;
			for (let i = 0; i < frags.length; i += 1) {
				if (i > 0) {
					const dt = Array.isArray(run.dt) ? run.dt[i - 1] : void 0;
					if (typeof dt !== "number") return void 0;
					time += dt;
				}
				if (predicate(frags[i])) return time;
			}
			return void 0;
		}
		// 复现官方 `assistantStreamFirstTokenTime`：扫一条 stream，返回首个 token 的时间。
		function mpFirstTokenTime(stream) {
			if (!Array.isArray(stream)) return void 0;
			for (const rec of stream) {
				if (!rec) continue;
				if (rec.type === "chunk") {
					if (mpIsTokenDelta(rec.chunk) && typeof rec.time === "number") return rec.time;
					continue;
				}
				if (rec.type === "tool-call-chunks") {
					if (rec.name !== void 0 && typeof rec.time0 === "number") return rec.time0;
					continue;
				}
				if (rec.type === "text-chunks" || rec.type === "reasoning-chunks") {
					const t = mpRunMemberTime(rec, (f) => f !== "");
					if (typeof t === "number") return t;
				}
			}
			return void 0;
		}
		// 扫描当前会话事件窗口，建立 "turn:step" → { startTime, firstTokenTime, completedTime, outputTokens }
		// ⚠️ 只读叶子标量，不整对象序列化（事件是 live 数据）。
		function mpCollectTimings() {
			const sessions = mpSessionsOf();
			if (!sessions) return null;
			let id;
			try {
				const list = sessions.list.getSnapshot();
				id = list && list.current;
			} catch (e) { return null; }
			if (id === void 0 || id === null) return null;
			let binding;
			try { binding = sessions.binding(id); } catch (e) { return null; }
			if (!binding) return null;
			const src = binding.eventSource;
			if (!src || typeof src.getSnapshot !== "function") return null;
			let win;
			try { win = src.getSnapshot(); } catch (e) { return null; }
			const entries = win && Array.isArray(win.entries) ? win.entries : null;
			if (!entries) return null;
			const map = Object.create(null);
			const keyOf = (d) => (d && d.turn !== void 0 && d.step !== void 0) ? (d.turn + ":" + d.step) : null;
			for (const entry of entries) {
				const ev = entry && entry.event;
				if (!ev || !ev.data) continue;
				const k = keyOf(ev.data);
				if (k === null) continue;
				let rec = map[k];
				if (rec === void 0) { rec = map[k] = { startTime: null, firstTokenTime: null, completedTime: null, outputTokens: null }; }
				if (ev.type === "step/start") {
					if (rec.startTime === null && typeof ev.time === "number") rec.startTime = ev.time;
					continue;
				}
				if (ev.type !== "assistant/message" && ev.type !== "assistant/attempt") continue;
				const stream = ev.data.stream;
				if (rec.firstTokenTime === null) {
					const ft = mpFirstTokenTime(stream);
					if (typeof ft === "number") rec.firstTokenTime = ft;
				}
				if (ev.type === "assistant/message") {
					if (typeof ev.time === "number") rec.completedTime = ev.time;
					const usage = ev.data.usage;
					if (usage && Number.isFinite(usage.outputTokens)) rec.outputTokens = usage.outputTokens;
				}
			}
			return map;
		}
		function mpFormatDuration(ms) {
			if (ms < 1e3) return Math.round(ms) + " 毫秒";
			return (ms / 1e3).toFixed(ms < 1e4 ? 2 : 1) + " 秒";
		}
		// 官方三行文案的等价复现（判据、顺序、格式全对齐官方实现）
		function mpTimingText(kind, m) {
			if (!m) return null;
			if (kind === "ttft") {
				// ⚠️ 采集侧字段名是 startTime（对应官方的 stepStartTime）
				if (m.startTime === null) return MP_TIMING_STEP_START_UNAVAILABLE;
				if (m.firstTokenTime === null) return MP_TIMING_UNAVAILABLE;
				// ⚠️ 防御：任一非有限数都不算，绝不产出 "NaN 秒" 这种脏文案
				if (!Number.isFinite(m.startTime) || !Number.isFinite(m.firstTokenTime)) return null;
				return mpFormatDuration(Math.max(0, m.firstTokenTime - m.startTime));
			}
			if (kind === "generation") {
				if (m.firstTokenTime === null) return MP_TIMING_UNAVAILABLE;
				if (m.completedTime === null) return MP_TIMING_PENDING;
				if (!Number.isFinite(m.firstTokenTime) || !Number.isFinite(m.completedTime)) return null;
				return mpFormatDuration(Math.max(0, m.completedTime - m.firstTokenTime));
			}
			if (kind === "throughput") {
				if (m.outputTokens === null) return MP_TIMING_OUTPUT_UNAVAILABLE;
				if (m.firstTokenTime === null) return MP_TIMING_UNAVAILABLE;
				if (m.completedTime === null) return MP_TIMING_PENDING;
				if (!Number.isFinite(m.firstTokenTime) || !Number.isFinite(m.completedTime) || !Number.isFinite(m.outputTokens)) return null;
				const sec = (m.completedTime - m.firstTokenTime) / 1e3;
				if (sec <= 0) return MP_TIMING_DURATION_TOO_SHORT;
				return (m.outputTokens / sec).toFixed(1) + " tok/s";
			}
			return null;
		}
		// 在轨迹详情面板里就地回填。幂等：已回填过的用 dataset 标记跳过。
		function mpPatchTrajectoryTiming() {
			if (!mpIsMobile()) return;
			const details = document.querySelector(".Y0dWHa_details");
			if (!details) return;
			// 面板标题带「第 N 轮 · 步骤 M」→ 直接解析出 key，无需依赖表格行
			const loc = details.querySelector(".Y0dWHa_detailsLocation");
			if (!loc) return;
			const text = (loc.textContent || "").trim();
			const mm = text.match(/第\s*(\d+)\s*轮\s*·\s*步骤\s*(\d+)/);
			if (!mm) return;
			const key = mm[1] + ":" + mm[2];
			// 只在有「不可用」时才干活，避免无谓开销
			const dds = details.querySelectorAll("dt");
			let hasUnavailable = false;
			for (const dt of dds) {
				const dd = dt.nextElementSibling;
				if (dd && (dd.textContent || "").indexOf(MP_TIMING_UNAVAILABLE) >= 0) { hasUnavailable = true; break; }
			}
			if (!hasUnavailable) return;
			const map = mpCollectTimings();
			if (!map) return;
			const m = map[key];
			if (!m) return;
			for (const dt of dds) {
				const label = (dt.textContent || "").trim();
				const dd = dt.nextElementSibling;
				if (!dd) continue;
				const cur = (dd.textContent || "").trim();
				if (cur.indexOf(MP_TIMING_UNAVAILABLE) < 0) continue;
				let kind = null;
				if (label.indexOf("首 token") >= 0) kind = "ttft";
				else if (label === "生成") kind = "generation";
				else if (label === "吞吐量") kind = "throughput";
				if (kind === null) continue;
				const out = mpTimingText(kind, m);
				if (out === null || out === MP_TIMING_UNAVAILABLE) continue;
				dd.textContent = out;
				dd.dataset.mpTimingPatched = kind;
			}
		}
		function showRenameDialog(currentTitle, session) {
			if (!session) return;
			// 复用现有弹窗（若已打开则聚焦输入框）
			let mask = document.querySelector(".mp-rename-mask");
			if (!mask) {
				mask = document.createElement("div");
				mask.className = "mp-rename-mask";
				mask.innerHTML =
					'<div class="mp-rename-box">' +
					'<p class="mp-rename-title">重命名会话</p>' +
					'<input class="mp-rename-input" type="text" maxlength="120" autocomplete="off" />' +
					'<div class="mp-rename-error"></div>' +
					'<div class="mp-rename-row">' +
					'<button type="button" class="mp-rename-btn mp-rename-cancel">取消</button>' +
					'<button type="button" class="mp-rename-btn mp-rename-ok">确定</button>' +
					'</div></div>';
				document.body.appendChild(mask);
				const input = mask.querySelector(".mp-rename-input");
				const error = mask.querySelector(".mp-rename-error");
				const cancel = mask.querySelector(".mp-rename-cancel");
				const ok = mask.querySelector(".mp-rename-ok");
				let busy = false;
				mask.addEventListener("click", (e) => {
					if (e.target === mask) closeRename(mask);
				});
				cancel.addEventListener("click", () => closeRename(mask));
				ok.addEventListener("click", async () => {
					if (busy) return;
					const title = input.value.trim();
					if (!title) {
						error.textContent = "标题不能为空";
						return;
					}
					busy = true;
					ok.disabled = true;
					error.textContent = "";
					try {
						const result = await session.rename(title);
						if (result.ok) closeRename(mask);
						else error.textContent = result.error?.message || "重命名失败";
					} catch (err) {
						error.textContent = err instanceof Error ? err.message : String(err);
					} finally {
						busy = false;
						ok.disabled = false;
					}
				});
				input.addEventListener("keydown", (e) => {
					if (e.key === "Enter") ok.click();
					else if (e.key === "Escape") closeRename(mask);
				});
			}
			const input = mask.querySelector(".mp-rename-input");
			const error = mask.querySelector(".mp-rename-error");
			input.value = currentTitle;
			error.textContent = "";
			mask.style.display = "flex";
			// 等一帧再聚焦（避免弹窗未显示时键盘抢焦点）
			setTimeout(() => {
				input.focus();
				input.select();
			}, 80);
		}
		function closeRename(mask) {
			if (mask) mask.style.display = "none";
		}
		// bindTitleRename 见 §8b（新版没有自建顶栏，改绑官方 header 的标题区）

		// ── 11. 移动端只允许"用户直接点输入框"唤起键盘 ──
		// 官方 composer 会在多处自动 focus 输入框：加号按钮的 keepFocus
		// (onMouseDown)、切换会话时 useEffect([sessionId]) 的自动聚焦。桌面端
		// 合理（方便直接打字），移动端会弹出输入法键盘挡住菜单/遮住界面。
		// 守卫：记录最近一次 pointerdown 的目标，若 textarea 获得的焦点并非
		// 由用户直接点击输入框引起，则立即 blur（同一事件循环内，键盘不弹出）。
		/* ══ 修复 64：移走编辑区焦点的**时机**闸门（两个守卫共用）══
		   真机实测的时序（键盘收起状态点「+」）：
		     ① 官方 onToggleCommandMenu 是「先把焦点还给编辑区，再 toggle 菜单」
		        （dsh-client-ui-conversation/lib/client.js:17390 `focusDraftEditor` → `toggleCommandMenu`），
		        而指令菜单**只在编辑区持焦点时才渲染**；
		     ② 官方的 keepDraftFocus 还会在 mousedown 里再 focus 一次（preventDefault + focus）。
		   于是「谁先把焦点从编辑区拿走」就直接决定菜单能不能出现：

		     | 拿焦点的时机        | 菜单 | 键盘 |
		     |---------------------|------|------|
		     | 菜单渲染前（旧行为）| ❌ 不开 | 不弹 |
		     | 一直不拿            | ✅ 开   | ❌ 弹回 |
		     | **菜单出现之后再拿** | ✅ 开   | ✅ 不弹 |

		   所以把「拿焦点」挂到 `[data-trigger-menu]` 出现的这一刻上，而不是手势里。
		   闸门对**两条**抢焦点的路径同时生效（它们都会被官方那次 focus 触发）：
		     · setupComposerKeyboardGuard 的 onFocusIn 守卫（修复 29）
		     · setupKeepFocusGuard 的 arm 分支（修复 38b）
		   ⚠️ 兜底定时器必需：万一菜单始终不出现（官方改了渲染方式），也必须把焦点
		     还回去，否则键盘会一直挂在那里。
		   ⚠️ 只对会弹指令菜单的按钮延迟（`button[aria-haspopup="listbox"]`）；发送 /
		     停止 / 模型选择等不弹这个菜单，必须维持**立即 blur** —— 对它们延迟等于
		     白等一个兜底周期，那段时间足够键盘被挂回来。
		   ⚠️ 不要退回「立即 blur」，也不要改成「在 keepFocus 里 blur」—— 后者同样抢在
		     React 渲染菜单之前。 */
		const MP_MENU_SEL = "[data-trigger-menu]";
		let mpBlurGateUntilMenu = false;
		let mpPendingBlurEl = null, mpPendingBlurObs = null, mpPendingBlurTid = 0;
		const mpFlushPendingBlur = () => {
			mpBlurGateUntilMenu = false;
			try { if (mpPendingBlurObs) { mpPendingBlurObs.disconnect(); mpPendingBlurObs = null; } } catch (e) { /* 静默 */ }
			try { if (mpPendingBlurTid) { window.clearTimeout(mpPendingBlurTid); mpPendingBlurTid = 0; } } catch (e) { /* 静默 */ }
			const el = mpPendingBlurEl;
			mpPendingBlurEl = null;
			if (el) { try { el.blur(); } catch (e) { /* 静默 */ } }
		};
		const mpDeferBlur = (el) => {
			// arm 与 onFocusIn 是同一次手势的两段，盯的是同一个编辑区 → 别互相冲掉
			if (mpBlurGateUntilMenu && mpPendingBlurEl === el) return;
			mpFlushPendingBlur();
			mpBlurGateUntilMenu = true;
			mpPendingBlurEl = el;
			try {
				const obs = new MutationObserver(() => {
					if (document.querySelector(MP_MENU_SEL)) mpFlushPendingBlur();
				});
				obs.observe(document.body, { childList: true, subtree: true });
				mpPendingBlurObs = obs;
			} catch (e) { /* 静默 */ }
			mpPendingBlurTid = window.setTimeout(mpFlushPendingBlur, 700);
		};

		function setupComposerKeyboardGuard() {
			if (!mpIsMobile()) return () => {}; // 修复 40：仅移动端（必须返回空清理函数）
			let lastDownTarget = null;
			const onDown = (e) => {
				lastDownTarget = e.target;
			};
			// ⚠️ 0.1.5 的输入框由 <textarea> 换成 **contenteditable div**
			//   （`.uV2eYG_input[data-composer-input][role=textbox]`，实测页面里
			//   textarea 数量 = 0）。旧判据只认 TEXTAREA → 守卫整体失效，
			//   官方 keepFocus/auto-focus 会把键盘顶出来。这里两者都认。
			const isComposerInput = (t) => {
				if (!t || typeof t.closest !== "function") return false;
				if (t.tagName === "TEXTAREA") return !!t.closest('[data-composer-seat]');
				if (t.isContentEditable) return !!t.closest('[data-composer-seat], .uV2eYG_root');
				return false;
			};
			const onFocusIn = (e) => {
				const t = e.target;
				if (!isComposerInput(t)) return;
				// 修复 29：必须是「用户直接点了输入框本身」才放行。
				// ⚠️ 旧判据 lastDownTarget.closest('[data-composer-seat]') 会把
				//   composer 内的**所有按钮**（指令「+」、添加附件、模型选择、访问模式、
				//   上下文等，全在 uV2eYG_tools 里、都在 seat 内）误判成"点了输入框"，
				//   → 官方 keepFocus 把焦点塞给 contenteditable → 键盘被顶出来。
				//   （实测事件序列：touchstart(按钮) → +8ms INPUT.focus → focusin，
				//     lastDownTarget=按钮 → 旧判据放行 → 键盘唤起 ❌）
				//   正解：按下的目标必须**就是输入框自己**（contenteditable 本体）。
				const userTapped = lastDownTarget === t ||
					(!!lastDownTarget && typeof lastDownTarget.closest === "function" &&
						lastDownTarget.closest("[data-composer-input]") === t);
				if (!userTapped) {
					// ⭐ 修复 64：官方 onToggleCommandMenu 会先 focus 编辑区再 toggle 菜单，
					//   而菜单只在编辑区持焦点时渲染 —— 这里若当场 blur，菜单永远不出现
					//   （真机实测：只有 focusout，菜单从未进过 DOM）。
					//   故当这次 focus 是「点 + 弹菜单」引起的时候，让路给菜单。
					//   判据有两条：闸门已开（setupKeepFocusGuard 的 arm 先判定了是「+」），
					//   或按下的目标本身就是「+」按钮。
					const menuBtn = mpBlurGateUntilMenu ||
						(!!lastDownTarget && typeof lastDownTarget.closest === "function" &&
							!!lastDownTarget.closest('button[aria-haspopup="listbox"]'));
					if (menuBtn) {
						e.stopImmediatePropagation?.();
						mpDeferBlur(t);
						return;
					}
					e.stopImmediatePropagation?.();
					t.blur();
				}
			};
			document.addEventListener("touchstart", onDown, true);
			document.addEventListener("mousedown", onDown, true);
			document.addEventListener("focusin", onFocusIn, true);
			return () => {
				document.removeEventListener("touchstart", onDown, true);
				document.removeEventListener("mousedown", onDown, true);
				document.removeEventListener("focusin", onFocusIn, true);
			};
		}

		// ══ 修复 37：点输入框后「指令卡片」赖着不走（2026-09-16 啾灵反馈）══
		// 现象（啾灵原话）：「我点击输入框左下角的加号，它弹出一个卡片；后来我又不想点
		//   里面的内容了，就继续点输入框去输入文字 —— 但那个卡片一直不消失，
		//   还要我再点一下加号才消失。我想要的是：点输入框，卡片就自己取消掉。」
		// 根因（官方「点外面关闭」的判据太宽）：
		//   官方 MenuView 的外部 pointerdown 处理器（dsh-client-ui-input-trigger）里有：
		//     if (listRef.current?.closest("[data-composer-card]"))?.contains(ev.target) return;
		//   这一句本意是"别把卡片内的点击当外部"，但卡片 = **整张 composer**（含输入框本体），
		//   而菜单 `[data-trigger-menu]` 自己就挂在卡片的 overlayAnchor 里 →
		//   **点输入框永远被判成"卡片内部"**，onDismiss 根本不触发。
		//   又：点输入框不改变草稿，也不会走 controller.track() 的"无触发符即关闭"路径
		//   → 两条关闭路径同时失效，卡片只能靠再点一次「+」按 toggle 关掉。
		//   （实测证据：点「+」后菜单 rect [16,17,326,328]；改点输入框后菜单依旧 open=true、
		//     menuInCard=true；pointerdown 目标 = DIV.uV2eYG_input 且 inCard=true。）
		// 修法（走官方 API，**不模拟点击**）：捕获阶段监听 pointerdown/touchstart，
		//   若指令菜单正开着、且按下的目标落在**输入框编辑区**（[data-composer-input]
		//   或 .uV2eYG_grow）内 → 直接调官方 controller.dismiss()。
		// ⚠️ 必须排除两种情况，否则会帮倒忙：
		//   ① 「+」开关按钮本身（button[aria-haspopup="listbox"]）——官方 toggle 的语义是
		//      "开着就关"，我们先关掉它就变成"关→再开"，菜单只会闪一下；
		//   ② 菜单内部本身（[data-trigger-menu]）——点选项要照常走官方 pick。
		// ⚠️ 不 preventDefault、不碰焦点：dismiss 只关菜单，随后浏览器照常把焦点给输入框，
		//   不会与修复 29 的键盘守卫打架。
		function mpTriggerController() {
			try {
				const sessions = mpSessionsOf();
				if (!sessions) return null;
				const list = sessions.list.getSnapshot();
				const id = list && list.current;
				if (id === void 0 || id === null) return null;
				const actx = sessions.scope(id);
				if (!actx) return null;
				if (!mpCtx) return null;
				const triggers = mpCtx.get("inputTriggers");
				if (!triggers || typeof triggers.sessionOf !== "function") return null;
				return triggers.sessionOf(actx) || null;
			} catch (e) { return null; }
		}

		function setupTriggerMenuAutoDismiss() {
			if (!mpIsMobile()) return () => {}; // 修复 40：仅移动端（必须返回空清理函数）
			const MENU_SEL = "[data-trigger-menu]";
			const onDown = (e) => {
				// 没开卡片就完全不管（零开销早退）
				if (!document.querySelector(MENU_SEL)) return;
				const t = e.target;
				if (!t || typeof t.closest !== "function") return;
				if (t.closest(MENU_SEL)) return;                                  // ① 菜单内部
				if (t.closest('button[aria-haspopup="listbox"]')) return;          // ② 「+」开关
				const editing = t.closest("[data-composer-input]") || t.closest(".uV2eYG_grow");
				if (!editing) return;                                             // 只认输入框编辑区
				const ctrl = mpTriggerController();
				if (!ctrl || !ctrl.menu || typeof ctrl.menu.getSnapshot !== "function") return;
				try {
					// 以 store 快照为准（touch 与 pointer 会各来一次，第二次已 closed → 早退）
					if (ctrl.menu.getSnapshot().open) ctrl.dismiss();
				} catch (err) { /* 静默：收卡片失败也绝不打断输入 */ }
			};
			document.addEventListener("pointerdown", onDown, true);
			document.addEventListener("touchstart", onDown, true);
			return () => {
				document.removeEventListener("pointerdown", onDown, true);
				document.removeEventListener("touchstart", onDown, true);
			};
		}

		// ══ 修复 38 / 38b：焦点已在输入框时，点「+」会直接唤起键盘（2026-09-16 啾灵反馈）══
		// 现象（啾灵原话）：「如果我的焦点原来已经在输入框上，此时我点加号的话会直接唤起键盘」。
		//   补充（第二次反馈）：「如果我原本焦点在输入框上，手动把输入法收起来之后，再点那个
		//   加号，输入法就会再次弹起；如果本来焦点不在输入框里，点加号就不会弹出输入法」。
		//
		// 官方机制：dsh-client-ui-conversation 里 composer 的 4 个按钮（指令 / 添加附件 /
		//   停止 / 发送）都绑了
		//     onMouseDown: (e) => { e.preventDefault(); editor.getRootElement().focus(...) }
		//   即 `keepFocus` —— **无条件**重聚焦输入框。
		//
		// 【修复 38 的认知（部分正确）】起初判断是"那次对已聚焦元素的重聚焦"重启了输入法：
		//   修复 29 的键盘守卫挂在 `focusin` 上，而对**已聚焦**元素再调 focus() 是空操作、
		//   不派发 focusin → 守卫没机会执行。于是在捕获阶段给输入框实例挂临时 noop `focus`
		//   吃掉这一次调用（不改原型、不 preventDefault、不阻断传播）。
		//
		// 【修复 38b：真机信标推翻上述判断】真机数据显示：点「+」前 v0=752、点完后 vmin=445
		//   （键盘确实弹起），同一时刻 sw=1（那次 focus() 已被中和）、pf=空（无任何 focus()
		//   逃逸）→ **弹键盘与那次 focus() 无关**。
		//   真因：`e.preventDefault()` 让编辑区**始终保持 DOM 焦点**（真机整个手势
		//   activeElement 恒为编辑区）。IME 已被手动收起时，Android 只要看到「仍有可编辑
		//   元素持有焦点」，就会在这次 composer 触摸上重新挂回输入法；焦点不在输入框时
		//   没有可恢复的输入连接 → 不弹（与啾灵两次补充观察完全吻合）。
		//   → 故键盘**不可见**时必须真的 `blur()` 移走焦点；只吞 focus() 不够。
		//   → 键盘**可见**时保持原样（用户在打字，绝不能抢焦点）。
		//
		// ⚠️ 为什么不直接 preventDefault + stopImmediatePropagation（更省事）：
		//   会连「访问模式 / 选择模型」两个弹出按钮的 mousedown 一起吃掉，使它们
		//   **不再移走焦点**（实测：这两个按钮的「焦点在输入框」由 false 变成 true）。
		// ⚠️ 不改原型（曾试 `HTMLElement.prototype.focus` 包装 → 波及所有元素且抛
		//   `triggerRef.current?.focus is not a function`）。
		// ⚠️ 教训：修复 38 的「18/18 全过」量的是**代理指标**（focus() 调用次数）。
		//   无头 Chromium **没有输入法**，"键盘是否弹出"在无头里根本不可观测。
		//   键盘类修复只有真机 visualViewport 信标或用户确认才算证据。
		function setupKeepFocusGuard() {
			if (!mpIsMobile()) return () => {}; // 修复 40：仅移动端（必须返回空清理函数）
			const INPUT_SEL = "[data-composer-input]";
			const CARD_SEL = "[data-composer-card]";
			let stubbed = null, prevOwn = false, prevVal = null;
			let tid = 0;
			let restVh = 0;

			const vh = () => {
				try { return window.visualViewport ? window.visualViewport.height : window.innerHeight; } catch (e) { return 0; }
			};
			// 静止视口高度 = 历史最大值（键盘只会让可视区变矮，不会变高）
			const sampleRest = () => {
				try { const h = vh(); if (h > restVh) restVh = h; } catch (e) { /* 静默 */ }
			};
			// 键盘是否**正在占据视口**。
			// ① 官方 VirtualKeyboard API（Android Chrome 94+）最直接；
			// ② 回退「当前可视高比静止高度矮 80px 以上」——真机实测：静止 752、键盘弹起 445~504。
			// 两条都判不出 → 保守当作**可见**：宁可保留官方行为，也绝不误关用户正在用的键盘。
			const imeVisible = () => {
				let byApi = null;
				try {
					const vk = navigator.virtualKeyboard;
					if (vk && vk.boundingRect) byApi = vk.boundingRect.height > 0;
				} catch (e) { /* 静默 */ }
				if (byApi === true) return true;
				const byVh = restVh > 0 ? (restVh - vh()) > 80 : null;
				if (byVh === true) return true;
				if (byApi === false || byVh === false) return false;
				return true;
			};
			const clearTimer = () => {
				try { if (tid) { window.clearTimeout(tid); tid = 0; } } catch (e) { /* 静默 */ }
			};
			// 只有触摸设备才有"软键盘被手动收起后又被重新挂回"这件事。
			// 桌面端鼠标点「+」时抢走焦点 = 打断正在打字的人 → 必须排除。
			const isTouchUI = () => {
				try {
					return (navigator.maxTouchPoints || 0) > 0 &&
						!!window.matchMedia && window.matchMedia("(pointer: coarse)").matches;
				} catch (e) { return false; }
			};
			// 修复 38b 的注记：官方 keepFocus 里的 preventDefault() 让编辑区**始终
			// 保持 DOM 焦点**（真机信标实测：整个手势 activeElement 恒为编辑区）。
			// 键盘被用户手动收起后，Android 只要看到「仍有可编辑元素持有焦点」，就会在
			// 这次 composer 触摸上重新挂回输入法 —— 与官方那次 focus() 无关。
			// → 键盘不可见时必须真的把焦点移走；但**移走的时机**由修复 64 的共享闸门
			//   决定（见 setupComposerKeyboardGuard 上方的长注释），不能就地 blur。
			const restore = () => {
				clearTimer();
				if (!stubbed) return;
				try { if (prevOwn) stubbed.focus = prevVal; else delete stubbed.focus; } catch (e) { /* 静默 */ }
				stubbed = null;
			};
			const arm = (e) => {
				try {
					// 同一次手势内（touchstart → 合成 mousedown）已接管 → 桩继续留着
					if (stubbed) return;
					const t = e.target;
					if (!t || typeof t.closest !== "function") return;
					if (!t.closest(CARD_SEL)) return;          // 只在 composer 内
					if (t.closest(INPUT_SEL)) return;          // 用户点输入框本体 → 官方语义照旧
					if (!t.closest("button")) return;          // 只针对按钮
					const inp = document.activeElement;
					// 仅当编辑区**此刻已聚焦**才接管。未聚焦时官方 keepFocus 会派发 focusin，
					// 由修复 29 的守卫拦下（那条路径本来就是对的）。
					if (!inp || !inp.matches || !inp.matches(INPUT_SEL)) return;
					sampleRest();
					// ⭐ 修复 38b（2026-09-16 真机实测根因）：
					//   官方 keepFocus 里的 e.preventDefault() 让编辑区**始终保持 DOM 焦点**
					//   （真机信标实测：整个手势 activeElement 恒为编辑区）。
					//   键盘被用户手动收起后，Android 只要看到「仍有可编辑元素持有焦点」，
					//   就会在这次 composer 触摸上重新挂回输入法 —— **与官方那次 focus() 无关**：
					//   实测已中和 focus()（sw=1）、无任何泄漏（pf=空），键盘照样弹起（752→445）。
					//   反之焦点不在输入框时没有可恢复的输入连接，所以不弹（与啾灵观察一致）。
					//   → 键盘不可见时，必须**真的把焦点移走**；只吞 focus() 不够。
					//
					// ⭐ 修复 64：移走焦点的**时机**由共享闸门决定（见上方长注释）。
					//   「+」（`button[aria-haspopup="listbox"]`，真机实测 aria-haspopup=listbox）
					//   要等指令菜单渲染出来再 blur，否则菜单根本不会出现；其余按钮立即 blur。
					const btn = t.closest("button");
					if (!imeVisible()) {
						// 仅触摸端执行：桌面端没有软键盘，抢焦点只会打断打字。
						if (isTouchUI()) {
							try {
								if (btn && btn.matches('button[aria-haspopup="listbox"]')) mpDeferBlur(inp);
								else inp.blur();
							} catch (e2) { /* 静默 */ }
						}
					}
					prevOwn = Object.prototype.hasOwnProperty.call(inp, "focus");
					prevVal = prevOwn ? inp.focus : null;
					// 这一步在 38b 里**仍然必要**：我们刚 blur 走焦点，官方 keepFocus 的
					// mousedown 处理器紧接着就会 `focus()` 把它抢回来，必须吃掉。
					// 桩必须留到本次手势走完（blur 之后合成的 mousedown 仍会调它，
					// 早拆会让编辑区被重新聚焦 → 键盘又回来）。
					// 桩本身是**真正静默**的 noop（不写全局、不产生副作用）。
					inp.focus = function () { /* 修复 38/38b：吃掉官方 keepFocus 的这一次重聚焦 */ };
					stubbed = inp;
					clearTimer();
					// 兜底还原：任何异常路径都保证桩不残留（正常路径由 mouseup/click 拆）
					tid = window.setTimeout(restore, 700);
				} catch (err) { /* 静默：绝不打断输入 */ }
			};
			// ⚠️ 只在 mouseup/click 拆桩，**绝不在 touchend 拆**：
			//   触摸端合成鼠标事件的顺序是 touchstart → touchend → **mousedown** → mouseup → click，
			//   在 touchend 拆会让随后的合成 mousedown 重新武装失败（此时编辑区已被 blur、
			//   不再是 activeElement）→ 官方 keepFocus 的 focus() 逃逸 → 键盘回来。
			const onEnd = () => restore();

			sampleRest();
			try { if (window.visualViewport) window.visualViewport.addEventListener("resize", sampleRest); } catch (e) { /* 静默 */ }
			window.addEventListener("resize", sampleRest);
			document.addEventListener("touchstart", arm, true);
			document.addEventListener("mousedown", arm, true);
			window.addEventListener("mouseup", onEnd, false);
			window.addEventListener("click", onEnd, false);
			return () => {
				restore();
				mpFlushPendingBlur(); // 修复 64：拆掉待执行的 observer/定时器，别泄漏
				try { if (window.visualViewport) window.visualViewport.removeEventListener("resize", sampleRest); } catch (e) { /* 静默 */ }
				window.removeEventListener("resize", sampleRest);
				document.removeEventListener("touchstart", arm, true);
				document.removeEventListener("mousedown", arm, true);
				window.removeEventListener("mouseup", onEnd, false);
				window.removeEventListener("click", onEnd, false);
			};
		}

		// ══════════════════════════════════════════════════════════════════
		// 特权权限（uid 2000）设置分节
		//
		// 需求：设置页加一项，专门引导用户打开 uid 2000 权限。
		// 方案（用户选定）：静态引导 + 实时状态检测；入口 = 设置页左侧一级导航。
		//
		// 接缝 = 官方 `settings.section` 槽位（`dsh-client-ui-settings-plugins` 用的
		// 是同一个）。注册进去就自动出现在一级导航里，并被**现有的两级导航**接管 ——
		// `setupSettingsDialog()` 会把 nav 竖向化、点进去全屏显示二级页，
		// 所以这里**不需要**碰导航 DOM，也不必再写一套页面切换。
		//
		// 状态从哪来：浏览器进程读不到 `process.env` / 文件系统，所以状态由宿主半
		// （`lib/index.js`）的 `GET /api/dsh-android/privileged` 提供。那里返回的是
		// **端到端跑一次 `id -u`** 的结果，不是「文件在不在」—— 密钥文件存在但未被
		// adbd 授权时，通道照样拿不到 uid 2000，只查文件会得到**假阳性**。
		// ══════════════════════════════════════════════════════════════════
		const MP_PRIV_API = "/api/dsh-android/privileged";
		const MP_PRIV_NS = "dsh-android-mobile.privileged";
		// 官方静态模块表（`dsh-web-frontend` 的 `staticModules`）里有 react，
		// 所以客户端半可以直接 require 它，不需要自己打包一份 React。
		//
		// ⚠️ **必须延迟到 apply() 再 require，不能在模块顶层**：本文件的 factory
		//    会在 `__ModuleLoader__` 的 **queue 模式**下就被调用（模块系统尚未 boot、
		//    静态模块表还没建立），那时 require 会抛。apply() 一定是 boot 之后跑的。
		// ⚠️ 用 try/catch 拿：拿不到就**只损失这一个分节**，绝不能让整个插件
		//    （含移动端 UI 适配）挂掉。
		let mpPrivReact = null;

		const MP_PRIV_ZH = {
			nav: "特权权限",
			title: "特权权限（uid 2000 / root）",
			intro: "开启后，AI 可以在需要时以更高身份执行命令 —— 截图、模拟点击、卸载应用、改系统设置这些原本做不到的事。默认仍走 App 身份，要不要提权由 AI 自己判断。",
			statusTitle: "当前状态",
			statusOn: "已开启",
			statusOff: "未开启",
			statusUnknown: "检测中",
			recheck: "重新检测",
			checking: "检测中…",
			failed: "检测失败",
			rowRunner: "执行通道",
			rowKey: "ADB 密钥",
			rowIdentity: "实际身份",
			ready: "已就位",
			missing: "缺失",
			imported: "已导入（电脑密钥）",
			generated: "已生成（待配对）",
			paired: "已配对",
			keyOriginUnknown: "来源未知",
			notImported: "未导入",
			justImported: "刚刚导入",
			uidOk: "uid 2000",
			uidApp: "App 身份（未提权）",
			// 「固定」= 走 legacy 5555，它不依赖无线调试开关，也不会随重启换端口。
			chStable: "固定端口（关无线调试也不断）",
			chWireless: "无线调试（关掉开关会断）",
			bytes: "字节",
			guideTitle: "开启步骤",
			step1: "用数据线把手机连到电脑。首次连接时手机会弹「允许 USB 调试吗」，勾选「一律允许」再确定。",
			step2: "在电脑上打开终端，执行下面这条 —— 它让手机的 adbd 在 5555 端口监听。Android 10 每次重启手机后都要重做一次。",
			step3: "再执行下面这条，把电脑上已授权的密钥推到手机的共享目录（macOS / Linux 把 %USERPROFILE%\\.android\\adbkey 换成 ~/.android/adbkey）。",
			step4: "回到本页，点上面的「重新检测」。App 会把密钥搬进自己的私有目录，并立刻删掉共享目录里的中转文件。",
			cmd2: "adb tcpip 5555",
			cmd3: "adb push %USERPROFILE%\\.android\\adbkey /sdcard/dsh_adbkey",
			copy: "复制",
			copied: "已复制",
			copyFailed: "复制失败",
			notesTitle: "几点说明",
			note1: "密钥等同于这台设备的控制权。App 只在私有目录保存它，导入后立刻删除 /sdcard 上的中转文件。",
			note2: "卸载重装会清掉密钥，需要重新做一遍上面的步骤。",
			note3: "以特权身份执行的命令不经过沙箱 —— 该身份读不到 App 私有目录，写沙箱和 link 转译对它都不生效。",
			// ── 无线调试（Android 11+）：免电脑那条路 ──
			wlTitle: "无线调试（Android 11+，不用电脑）",
			wlIntro: "Android 11 起系统自带「无线调试」，可以让本应用自己拿到特权身份，全程不用数据线、不用电脑，重启手机也依然有效。首次需要配对一次。",
			wlStep1: "在本页点下面的「配对无线调试」按钮 —— 通知栏会出现一条常驻提示，告诉你接下来去哪。",
			wlStep2: "在系统「设置 → 开发者选项」里打开「无线调试」开关，再点进「使用配对码配对设备」。",
			wlStep3: "那个界面一打开，通知栏的提示就会自动变成带输入框的（本应用会一直等着，不用回本页重按）。下拉通知栏，在里面直接回复那 6 位配对码。",
			wlStep4: "配对成功后回到本页点「重新检测」，身份应变成 uid 2000。之后就一直有效，不必再配对。",
			wlPair: "配对无线调试",
			wlPairing: "正在等待…",
			// ⚠️ 这句话是本次体验修复的核心：告诉用户「不用回来重按」。
			//    原实现发现 8 秒没端口就报失败，用户刚在系统里找到那个按钮，
			//    通知栏已经说没找到了，只能回本页重按 —— 而重按还是同样结果。
			wlPairingHint: "已开始等待。请去系统「设置 → 开发者选项 → 无线调试」点开「使用配对码配对设备」，让那个界面保持显示 —— 本应用会一直等着（不会超时），端口一出现通知栏就会变成输入框。想中止请点上面的「取消配对」。",
			wlCancel: "取消配对",
			wlPairHint: "配对码由系统显示、只能人工输入 —— 这是 SPAKE2 协议的要求，任何应用（包括 Shizuku）都无法代填。",
			wlPort: "无线端口",
			wlPortNone: "未发现（无线调试可能没开）",
			wlNote: "与上面的电脑引导二选一即可。无线调试那条路更省事，但只在 Android 11 及以上可用；Android 10 只能用电脑引导。",
			wlTcp: "切到固定端口",
			wlTcpHint: "切到固定端口后就不依赖无线调试开关了 —— 关无线调试、关 Wi-Fi 都不断。重启手机后会自动重切，通常不用管。",
			wlNoBridge: "当前环境没有原生的配对入口（DSHAndroid 桥不可用），无法发起配对。",
			wlNoNotify: "配对全程靠通知栏交互（提示 → 输入配对码 → 取消），但本应用的通知被系统关掉了，配对无法进行。请到「系统设置 → 应用 → DSH → 通知」里打开通知后重试。",
			wlPortStale: "已失效（无线调试可能已关）",
			recommended: "推荐",
			// Android 10 上「无线调试」根本不存在，说清楚比让用户去系统设置里白找一遍好。
			wlUnsupported: "本机是 Android 10，系统里没有「无线调试」这个功能（它从 Android 11 才有）。请用下面的「电脑引导」，那是在这台设备上唯一可行的方式。",
			pcTitlePrimary: "开启引导（Android 10 用这条）",
			pcTitle: "改用电脑引导（Android 10 或没有无线调试时）",
			pcIntro: "如果系统里找不到「无线调试」（Android 10 及以下），或者无线调试一直配不上，可以插上数据线用电脑引导一次。",
			setupTitle: "开启引导（当前已就绪，一般不用看）",
			showDetails: "查看详情",
			hideDetails: "收起详情",
			nextAllGood: "已就绪，可以直接用了。",
			nextPairHint: "还差一步：点右边按钮完成无线调试配对。",
			nextSwitchHint: "建议切到固定端口，这样关掉无线调试开关也不会断。",
			nextChoose: "还没开启。按下面「无线调试」那几步走一遍即可，不用电脑。",
			nextChoosePc: "还没开启。按下面的「电脑引导」走一遍 —— 本机是 Android 10，只能用这种方式。",
			// 探测还没返回、但界面已过软截止（8 秒）时的提示。
			// ⚠️ 必须同时给出「不用等」和「能做什么」，不能只说「检测中」——
			//    真机反馈：首次安装时这里一直显示「检测中」且没有任何按钮可点。
			softPending: "还在探测（设备正在重启 adbd 时可能要几十秒）。你可以直接开始配对，不必等它。",
			softPendingPc: "还在探测（设备正在重启 adbd 时可能要几十秒）。你可以直接按下面的「电脑引导」开始，不必等它。",
			// ── 按失败原因给出的「下一步」──────────────────────────
			//
			// ⚠️ 判据是 runner 那条 `诊断: kind=…` 行（见宿主半的 `DIAG_KIND_RE`），
			//    **不是**去匹配中文文案 —— 文案改一个字分支就会静默失效。
			//    这里只覆盖「下一步动作明确不同」的几类；其余 kind（含 unknown、
			//    老版本 runner 没有这个字段）继续走上面的通用引导，行为与改动前一致。
			//
			// 这几类的共同点：用户光看「App 身份（未提权）」完全不知道该点哪里，
			// 而实际上每一类的正解都不一样。
			nextRevoked: "密钥的授权已被设备撤销（多半是点过「撤销 USB 调试授权」）。需要重新配对一次。",
			nextUnauthorized: "密钥还没被设备授权。点右边按钮走一次配对即可。",
			nextNoKey: "还没有 ADB 密钥。点右边按钮走一次配对即可，不用电脑。",
			nextNoKeyPc: "还没有 ADB 密钥。按下面的「电脑引导」走一遍 —— 本机是 Android 10，没有无线调试。",
			nextWirelessStale: "无线调试端口已失效（多半是关过无线调试或重启过）。重开一次无线调试，或直接点右边按钮切到固定端口。",
			nextNoChannel: "固定端口和无线调试都不可用。打开系统「无线调试」，或点右边按钮试一次。",
			nextAdbMissing: "内置的 adb 工具链缺失，无线调试这条路走不通。装一次 App 更新即可修好。",
			nextKeyBlocked: "密钥没被设备信任，为避免反复弹授权框已跳过无线调试。请重新配对。",
			nextTimeout: "探测超时了 —— 多半是 adbd 正在重启。等十几秒再点一次「重新检测」。",
			nextPair: "重新配对",

			// ── root（uid 0）────────────────────────────────────────
			// ⚠️ 这一块**必须存在**：`root` 工具只在检测到 root 时才注册，所以没有
			//   root 的设备上用户看不到它的任何痕迹，分不清「本机没有 root」与
			//   「有 root 但没检测到」。状态与原因都得摆出来。
			rootTitle: "root 权限（uid 0）",
			rootOn: "已开启",
			rootDenied: "未授权",
			rootAbsent: "无 root",
			rootUnknown: "未检测",
			rootRowState: "状态",
			rootRowDetail: "探测结论",
			rootRowAt: "检测时间",
			rootRecheck: "重新检测",
			rootChecking: "检测中…",
			// 「下一步」按三态各写一句 —— 三种情况下用户该做的事完全不同。
			// ⚠️ 还有第四种：结论是 GRANTED，但**本次启动没挂上**（用户是刚点完
			//   「重新检测」才授权成功的）—— 那句单独写，见 rootNextOnPendingReload。
			rootNextOn: "root 工具已注册，AI 可以直接以 uid 0 执行命令（读写 /data、改挂载、iptables 等）。",
			rootNextOnPendingReload: "已检测到 uid 0，但 root 工具要下次启动才会挂上（它是启动时挂载的，当前这个会话里还看不到）。请重启 App 后再用。",
			rootNextDenied: "本机有 su，但本应用没拿到 uid 0。请在 Magisk / SuperSU 之类的授权管理里给「DSH」授权，然后点「重新检测」。",
			rootNextAbsent: "本机没有 root（也没找到 su）。需要 root 的功能用不了，其余功能不受影响 —— 上方的 uid 2000 通道与之无关，照常可用。",
			rootNextUnknown: "还没检测过。点「重新检测」跑一次（首次可能会弹 root 授权框）。",
			// ⚠️ 这里是**纯文本**渲染（React 直接塞进 DOM），不能写 Markdown 标记 ——
			//   写 `**加粗**` 或反引号会原样显示出来（第一版就这么错了）。
			rootNote: "root 工具只在检测到 uid 0 时才注册。检测方式是真的跑一次 su -c id -u、看输出是不是 0，而不是查 su 文件在不在 —— 占位 su、装了 Magisk 但没授权、root 升级后失效这三种情况文件都在却提不了权。结果会缓存 24 小时。",
			rootDetailNever: "（无）",
		};

		// ══════════════════════════════════════════════════════════════════
		// 悬浮气泡设置分节
		//
		// 需求：App 退到后台时浮一个圆形气泡，用户能看着 AI 干活、也能继续说话。
		// 场景是**设备自动化** —— AI 去操作别的 App 时用户必须离开本应用，
		// 否则完全看不见进展、也没法中途补一句话。
		//
		// 分节要解决三件事，缺一不可：
		//   ① 让用户知道有这么个东西（默认关，不主动开就没有）
		//   ② 把用户送到系统的「显示在其他应用上层」授权页 —— 这是**特殊权限**，
		//      `requestPermissions` 对它无效，只能手动开
		//   ③ 开关本身
		//
		// 状态由原生提供（`DSHAndroid.bubbleState()`）—— 开关在 SharedPreferences、
		// 授权在 Settings，两者都在原生侧，浏览器进程读不到。
		// ══════════════════════════════════════════════════════════════════
		const MP_BUBBLE_NS = "dsh-android-mobile.bubble";
		const MP_BUBBLE_ZH = {
			nav: "悬浮气泡",
			title: "悬浮气泡",
			intro: "退到后台后，屏幕上浮一个圆形气泡，实时显示 AI 在干什么，也能直接打字发消息。做设备自动化时用它盯着进度，不用来回切应用。",
			statusTitle: "当前状态",
			statusOn: "已开启",
			statusOff: "未开启",
			statusUnknown: "检测中",
			rowSwitch: "开关",
			rowPerm: "悬浮窗权限",
			on: "已开启",
			off: "已关闭",
			granted: "已授权",
			notGranted: "未授权",
			enable: "开启气泡",
			disable: "关闭气泡",
			grant: "去授权",
			recheck: "重新检测",
			nextNeedPerm: "还差一步：系统还没允许本应用显示在其他应用上层。点右边按钮去开。",
			nextNeedEnable: "权限已就绪，打开开关即可用。",
			nextAllGood: "已就绪。退到后台就能看到气泡。",
			howTitle: "怎么用",
			how1: "退到后台（按 Home 或切到别的应用）时气泡才出现 —— 在本应用里它不会碍事。",
			how2: "拖动可以挪位置，松手会自动吸附到最近的屏幕边缘，位置会记住。",
			how3: "点一下展开面板：上面是当前动作和最近输出，下面是输入框，打字点「发送」即可。",
			how4: "面板上「打开」回到本应用，「收起」退回圆形。",
			notesTitle: "几点说明",
			note1: "「显示在其他应用上层」是特殊权限，系统不允许应用自己弹窗申请，必须手动去系统设置里打开。",
			note2: "气泡靠前台服务推送的状态刷新，约每秒一次；不额外耗电（引擎本来就在跑）。",
			note3: "气泡里发消息走的是同一个官方输入框，会进入当前会话，和在本应用里打字完全一样。",
			noBridge: "当前环境没有原生桥（DSHAndroid 不可用），无法控制气泡。",
		};
		const MP_BUBBLE_EN = {
			nav: "Floating bubble",
			title: "Floating bubble",
			intro: "When the app goes to the background, a round bubble floats on screen showing what the AI is doing, with a text box to send messages. Useful for device automation — watch progress without switching apps.",
			statusTitle: "Current status",
			statusOn: "Enabled",
			statusOff: "Not enabled",
			statusUnknown: "Checking",
			rowSwitch: "Switch",
			rowPerm: "Overlay permission",
			on: "On",
			off: "Off",
			granted: "Granted",
			notGranted: "Not granted",
			enable: "Turn on",
			disable: "Turn off",
			grant: "Grant",
			recheck: "Re-check",
			nextNeedPerm: "One step left: the system hasn't allowed this app to draw over other apps. Tap the button to open the setting.",
			nextNeedEnable: "Permission is ready — just turn the switch on.",
			nextAllGood: "All set. Send the app to the background to see the bubble.",
			howTitle: "How it works",
			how1: "The bubble appears only when the app is in the background — it never gets in your way inside the app.",
			how2: "Drag to move it; on release it snaps to the nearest screen edge and remembers the position.",
			how3: "Tap to expand: current action and recent output on top, a text box and Send below.",
			how4: "\"Open\" returns to the app; \"Collapse\" goes back to the round bubble.",
			notesTitle: "Notes",
			note1: "\"Display over other apps\" is a special permission — Android does not allow an app to request it at runtime; you must enable it in system settings.",
			note2: "The bubble is fed by the foreground service's status, about once per second. No extra battery cost — the engine is already running.",
			note3: "Messages sent from the bubble go through the same official composer and land in the current session, exactly as if you typed them in the app.",
			noBridge: "No native bridge (DSHAndroid unavailable) in this environment — cannot control the bubble.",
		};

		const MP_PRIV_EN = {
			nav: "Privileged access",
			title: "Privileged access (uid 2000 / root)",
			intro: "When enabled, the AI can run commands as a higher identity when it needs to — screenshots, synthetic taps, uninstalling apps, changing system settings. Everything still defaults to the app's own identity; the AI decides when to escalate.",
			statusTitle: "Current status",
			statusOn: "Enabled",
			statusOff: "Not enabled",
			statusUnknown: "Checking",
			recheck: "Re-check",
			checking: "Checking…",
			failed: "Check failed",
			rowRunner: "Execution channel",
			rowKey: "ADB key",
			rowIdentity: "Effective identity",
			ready: "In place",
			missing: "Missing",
			imported: "Imported (PC key)",
			generated: "Generated (pairing needed)",
			paired: "Paired",
			keyOriginUnknown: "Origin unknown",
			notImported: "Not imported",
			justImported: "just imported",
			uidOk: "uid 2000",
			uidApp: "App identity (not escalated)",
			chStable: "fixed port (survives wireless debugging off)",
			chWireless: "wireless debugging (breaks when toggled off)",
			bytes: "bytes",
			guideTitle: "How to enable it",
			step1: "Connect the phone to a computer with a USB cable. On first connect the phone asks \"Allow USB debugging?\" — tick \"Always allow\" and confirm.",
			step2: "Open a terminal on the computer and run the command below — it makes the phone's adbd listen on port 5555. On Android 10 you must redo this after every reboot.",
			step3: "Then run the command below to push the computer's already-authorized key to the phone's shared storage (on macOS / Linux replace %USERPROFILE%\\.android\\adbkey with ~/.android/adbkey).",
			step4: "Come back to this page and tap \"Re-check\" above. The app moves the key into its private directory and immediately deletes the hand-off file from shared storage.",
			cmd2: "adb tcpip 5555",
			cmd3: "adb push %USERPROFILE%\\.android\\adbkey /sdcard/dsh_adbkey",
			copy: "Copy",
			copied: "Copied",
			copyFailed: "Copy failed",
			notesTitle: "Things to know",
			note1: "The key is equivalent to control of this device. The app keeps it only in its private directory and deletes the /sdcard hand-off file right after importing.",
			note2: "Uninstalling the app wipes the key; you will need to repeat the steps above.",
			note3: "Commands run as the privileged identity bypass the sandbox — that identity cannot read the app's private directory, so the write sandbox and the link translation layer do not apply to it.",
			// ── Wireless debugging (Android 11+): the no-computer path ──
			wlTitle: "Wireless debugging (Android 11+, no computer)",
			wlIntro: "Android 11 and later ship \"Wireless debugging\", which lets this app obtain the privileged identity on its own — no cable, no computer, and it survives reboots. A one-time pairing is required.",
			wlStep1: "In system Settings → Developer options, turn on the \"Wireless debugging\" switch.",
			wlStep2: "Tap \"Pair device with pairing code\" — you will see a 6-digit code and a port. Leave that screen open, then come back here and tap the button below.",
			wlStep3: "A pairing notification appears in the notification shade. Pull it down and reply with the 6-digit code right in the notification (no need to switch back to this app).",
			wlStep4: "Once pairing succeeds, come back here and tap \"Re-check\" — the identity should become uid 2000. It stays valid from then on; no re-pairing needed.",
			wlPair: "Pair wireless debugging",
			wlCancel: "Cancel pairing",
			wlPairHint: "The pairing code is shown by the system and can only be typed by a human — that is a requirement of the SPAKE2 protocol, and no app (Shizuku included) can fill it in for you.",
			wlPort: "Wireless port",
			wlPortNone: "Not found (wireless debugging may be off)",
			wlNote: "Use either this or the computer-based guide above. Wireless debugging is easier but only exists on Android 11+; on Android 10 only the computer route works.",
			wlTcp: "Switch to fixed port",
			wlTcpHint: "After switching to a fixed port the channel no longer depends on the wireless debugging toggle — turning wireless debugging or Wi-Fi off will not break it. It re-switches automatically after a reboot.",
			wlNoBridge: "The native pairing entry point is unavailable in this environment (the DSHAndroid bridge is missing), so pairing cannot be started.",
			wlNoNotify: "Pairing is driven entirely from the notification shade (prompt → code entry → cancel), but this app's notifications are turned off, so pairing cannot proceed. Enable notifications under Settings → Apps → DSH → Notifications, then retry.",
			wlPortStale: "Stale (wireless debugging may be off)",
			recommended: "Recommended",
			wlUnsupported: "This device runs Android 10, which has no \"Wireless debugging\" — that feature starts at Android 11. Use the computer-based guide below; it is the only workable path on this device.",
			pcTitlePrimary: "Setup guide (use this on Android 10)",
			pcTitle: "Use the computer-based guide instead (Android 10, or no wireless debugging)",
			pcIntro: "If there is no \"Wireless debugging\" entry in system settings (Android 10 and below), or pairing keeps failing, connect a USB cable and run the computer-based guide once.",
			setupTitle: "Setup guide (already enabled — normally not needed)",
			showDetails: "Show details",
			hideDetails: "Hide details",
			nextAllGood: "Ready to use.",
			nextPairHint: "One step left: tap the button to finish wireless pairing.",
			nextSwitchHint: "Switching to a fixed port is recommended so turning wireless debugging off will not break it.",
			nextChoose: "Not enabled yet. Follow the \"Wireless debugging\" steps below — no computer needed.",
			nextChoosePc: "Not enabled yet. Follow the computer-based guide below — this device runs Android 10, so that is the only way.",
			// Probe hasn't returned but the UI passed its soft deadline (8s). Say both
			// "you don't have to wait" and "what you can do" — never just "Checking…".
			softPending: "Still probing (this can take a while when adbd is restarting). You can start pairing right away instead of waiting.",
			softPendingPc: "Still probing (this can take a while when adbd is restarting). You can follow the computer-based guide below instead of waiting.",
			// ── Cause-specific next steps (see the Chinese dictionary for the rationale) ──
			nextRevoked: "This key's authorisation was revoked by the device (usually via \"Revoke USB debugging authorisations\"). Pair once more.",
			nextUnauthorized: "The key has not been authorised by the device yet. Tap the button to pair once.",
			nextNoKey: "No ADB key yet. Tap the button to pair once — no computer needed.",
			nextNoKeyPc: "No ADB key yet. Follow the computer-based guide below — this device runs Android 10 and has no wireless debugging.",
			nextWirelessStale: "The wireless debugging port went stale (wireless debugging was turned off or restarted). Turn it back on, or tap the button to switch to the fixed port.",
			nextNoChannel: "Neither the fixed port nor wireless debugging is available. Turn on system \"Wireless debugging\", or tap the button to try a switch.",
			nextAdbMissing: "The bundled adb toolchain is missing, so the wireless route cannot work. Installing an app update fixes it.",
			nextKeyBlocked: "The key is not trusted by the device, so the wireless route was skipped to avoid repeated authorisation prompts. Pair again.",
			nextTimeout: "The probe timed out — adbd is most likely still restarting. Wait a few seconds and tap \"Re-check\" again.",
			nextPair: "Pair again",

			// ── root (uid 0) ──────────────────────────────────────
			// ⚠️ This block must exist: the `root` tool is only registered when root is
			//   detected, so on a non-rooted device the user sees no trace of it at all
			//   and cannot tell "this device has no root" from "root exists but was not
			//   detected". Both the state and the reason have to be visible.
			rootTitle: "root access (uid 0)",
			rootOn: "Enabled",
			rootDenied: "Not authorized",
			rootAbsent: "No root",
			rootUnknown: "Not checked",
			rootRowState: "Status",
			rootRowDetail: "Probe result",
			rootRowAt: "Checked at",
			rootRecheck: "Re-check",
			rootChecking: "Checking…",
			rootNextOn: "The root tool is registered — the AI can run commands as uid 0 directly (read/write /data, remount, iptables, and so on).",
			rootNextOnPendingReload: "uid 0 was detected, but the root tool is only mounted at startup — it is not available in this session yet. Restart the app to use it.",
			rootNextDenied: "This device has su, but the app did not obtain uid 0. Grant \"DSH\" root access in Magisk / SuperSU or similar, then tap \"Re-check\".",
			rootNextAbsent: "This device has no root (no su found either). Root-only features are unavailable; everything else is unaffected — the uid 2000 channel above is unrelated and still works.",
			rootNextUnknown: "Not checked yet. Tap \"Re-check\" to run a probe (the first one may show a root authorization dialog).",
			// ⚠️ Plain-text rendering (React puts this straight into the DOM) — no
			//   Markdown markers, they would show up literally.
			rootNote: "The root tool is registered only when uid 0 is detected. Detection actually runs su -c id -u and checks whether the output is 0, rather than looking for a su file — a placeholder su, Magisk installed but not authorized, and a root that broke after an upgrade all leave the file present while escalation fails. The result is cached for 24 hours.",
			rootDetailNever: "(none)",
		};

		// 全内联样式：设置 dialog 里的既有教训是「匹配 dialog 的 CSS 规则会把弹窗
		// 推出屏幕」，而 section 内容住在那个 dialog 里，所以这里不新增 CSS 规则。
		const MP_PRIV_S = {
			section: { maxWidth: "760px", display: "flex", flexDirection: "column", gap: "12px", color: "var(--dsw-alias-label-primary, #222)" },
			heading: { margin: 0, fontSize: "18px", fontWeight: 600 },
			intro: { margin: 0, fontSize: "13px", lineHeight: "20px", color: "var(--dsw-alias-label-tertiary, #888)" },
			card: { border: "0.5px solid var(--dsw-alias-border-l2, #e2e2e2)", borderRadius: "10px", padding: "12px 14px", display: "flex", flexDirection: "column", gap: "10px" },
			cardHead: { display: "flex", alignItems: "center", gap: "8px" },
			cardTitle: { margin: 0, fontSize: "15px", fontWeight: 600 },
			badge: { marginLeft: "auto", fontSize: "12px", fontWeight: 600, padding: "3px 9px", borderRadius: "999px", whiteSpace: "nowrap" },
			btn: { border: "0.5px solid var(--dsw-alias-border-l2, #dcdcdc)", background: "transparent", color: "inherit", font: "inherit", fontSize: "13px", padding: "6px 14px", borderRadius: "8px", cursor: "pointer" },
			row: { display: "flex", alignItems: "baseline", gap: "8px", fontSize: "13px", lineHeight: "19px" },
			dot: { flex: "0 0 auto", width: "7px", height: "7px", borderRadius: "50%", transform: "translateY(-1px)" },
			rowLabel: { flex: "0 0 auto", color: "var(--dsw-alias-label-tertiary, #888)" },
			rowValue: { flex: "1 1 auto", minWidth: 0, wordBreak: "break-word" },
			steps: { margin: 0, padding: "0 0 0 20px", display: "flex", flexDirection: "column", gap: "9px" },
			step: { fontSize: "13px", lineHeight: "20px" },
			// 命令块：独立成行 + 可横向滚动。窄屏上命令很长，
			// 换行会把路径断在中间导致抄错，所以宁可横向滚。
			// ⚠️ 这里**刻意不**设 user-select：本插件的全局 CSS 是
			//   `[data-mobile] * { user-select: none !important }`，内联会被压掉，
			//   写了也是死代码。**正因如此才必须有复制按钮** —— 命令长按选不中，
			//   而这台设备上用户是在电脑终端里敲，只能照着手机屏幕抄。
			cmdRow: { display: "flex", alignItems: "stretch", gap: "6px", marginTop: "6px" },
			cmd: { flex: "1 1 auto", minWidth: 0, display: "block", padding: "7px 9px", borderRadius: "6px", background: "var(--dsw-alias-interactive-bg-hover, #f2f2f2)", fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace", fontSize: "12px", lineHeight: "18px", overflowX: "auto", whiteSpace: "pre" },
			// 固定宽度：文案在「复制 / 已复制 / 复制失败」之间切换，
			// 不固定的话按钮会随字数变宽，把左边的命令块挤得来回跳。
			cmdBtn: { flex: "0 0 auto", display: "flex", alignItems: "center", justifyContent: "center", minWidth: "62px", border: "0.5px solid var(--dsw-alias-border-l2, #dcdcdc)", background: "transparent", color: "inherit", font: "inherit", fontSize: "12px", padding: "0 8px", borderRadius: "6px", cursor: "pointer", whiteSpace: "nowrap" },
			notes: { margin: 0, padding: "0 0 0 18px", display: "flex", flexDirection: "column", gap: "6px" },
			note: { fontSize: "12px", lineHeight: "18px", color: "var(--dsw-alias-label-tertiary, #888)" },
			error: { margin: 0, fontSize: "12px", lineHeight: "18px", color: "#c92a2a" },

			// ── 重设计后新增 ────────────────────────────────────────
			// 首屏「下一步」块：把「点哪里」摆在结论正下方，用户不必自己从
			// 三行技术明细里翻译出该做什么。
			stack: { display: "flex", flexDirection: "column", gap: "12px" },
			summary: { margin: 0, fontSize: "13px", lineHeight: "19px" },
			next: { display: "flex", alignItems: "center", gap: "10px", flexWrap: "wrap", padding: "9px 10px", borderRadius: "8px", background: "var(--dsw-alias-interactive-bg-hover, #f2f2f2)" },
			nextText: { flex: "1 1 160px", minWidth: 0, fontSize: "13px", lineHeight: "19px" },
			// root 卡片专用的「下一步」文案样式：与上面那条的区别只有换行基线。
			//
			// ⚠️ **不复用 `nextText`**：它的 `flex: "1 1 160px"` 在 360 CSS px 宽的
			//   卡片里会把长文案挤成每行十来个字的一窄列（root 那三条文案都挺长）。
			//   用 100% 作基线则文案先占满整行、按钮自然掉到下一行，读起来才是一段话。
			//   之所以单独开一条而不是改上面那条：上面那条是 uid 2000 卡片在用的，
			//   改它会连带改掉那张已经定型的卡片 —— 只修自己新加的那张。
			nextTextWide: { flex: "1 1 100%", minWidth: 0, fontSize: "13px", lineHeight: "19px" },
			actions: { display: "flex", alignItems: "center", gap: "8px", flexWrap: "wrap" },
			// 主按钮：整页只有「配对」和「下一步」用实心，避免到处都在喊。
			btnPrimary: { border: "0.5px solid transparent", background: "var(--dsw-alias-interactive-bg-primary, #2b6cb0)", color: "#fff", font: "inherit", fontSize: "13px", fontWeight: 600, padding: "6px 14px", borderRadius: "8px", cursor: "pointer" },
			// 次要动作（取消配对 / 查看详情）用弱化样式，和主按钮拉开层级。
			btnQuiet: { border: "0.5px solid transparent", background: "transparent", color: "var(--dsw-alias-label-tertiary, #888)", font: "inherit", fontSize: "13px", padding: "6px 10px", borderRadius: "8px", cursor: "pointer" },
			details: { display: "flex", flexDirection: "column", gap: "8px", paddingTop: "4px", borderTop: "0.5px solid var(--dsw-alias-border-l2, #eee)" },
			// 折叠块：引导与注意事项默认收起，让首屏只剩结论。
			fold: { border: "0.5px solid var(--dsw-alias-border-l2, #e2e2e2)", borderRadius: "10px", padding: "10px 14px" },
			foldHead: { fontSize: "14px", fontWeight: 600, cursor: "pointer" },
			foldBody: { display: "flex", flexDirection: "column", gap: "12px", paddingTop: "10px" },
			// ⚠️ 这里**不能**引用 `MP_PRIV_TONE` —— 它定义在本对象**之后**，
			// 在模块求值期引用会抛 `Cannot access 'MP_PRIV_TONE' before
			// initialization`（整个插件 import 失败、界面直接白屏，踩过一次）。
			// 所以颜色写死，与 MP_PRIV_TONE.on 保持一致。
			tagRecommended: { marginLeft: "auto", fontSize: "11px", fontWeight: 600, padding: "2px 8px", borderRadius: "999px", color: "#2f9e44", background: "rgba(47,158,68,0.12)", whiteSpace: "nowrap" },

			// ── 「交流群」分节 ──────────────────────────────────────
			// 二维码容器：居中 + 一点上下留白，避免图片紧贴卡片边框。
			qrWrap: { display: "flex", justifyContent: "center", padding: "4px 0 2px" },
			// 显示尺寸取 240px 且不超过卡片宽度。
			// ⚠️ 内联源图是 640px 的 WebP，缩到 240 CSS px 显示。实测（dpr=3 渲染
			//   截图后交给 zxing 解码）400px 的源图就已够扫，640px 是留了余量：
			//   用户可能会用另一台设备对着屏幕拍，拍摄角度/对焦都会损失有效分辨率。
			// ⚠️ 不设 `imageRendering: "pixelated"`：那是为放大低分辨率图准备的，
			//   这里是从 640 缩到 240（缩小），平滑重采样反而更利于扫描识别。
			qrImg: { width: "240px", maxWidth: "100%", height: "auto", display: "block", borderRadius: "8px", background: "#fff" },
			// 群号用等宽字体且**放大一号**：这是要照着念/照着手输的数字，
			// 与周围的说明文字同等字号会显得不够醒目。
			mpGroupNo: { flex: "1 1 auto", minWidth: 0, fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace", fontSize: "15px", fontWeight: 600, letterSpacing: "0.5px" },
		};

		const MP_PRIV_TONE = {
			on: { color: "#2f9e44", bg: "rgba(47,158,68,0.12)" },
			off: { color: "#e8590c", bg: "rgba(232,89,12,0.12)" },
			// 「还不能用，但没坏」—— 目前只有一种情况：密钥是 App 现场生成的，
			// 设备尚未信任，需要走一次配对。用橙色会和「缺失」混淆，所以单列一色。
			warn: { color: "#f08c00", bg: "rgba(240,140,0,0.12)" },
			unknown: { color: "#868e96", bg: "rgba(134,142,150,0.12)" },
		};

		/**
		 * 把一段文本写进系统剪贴板。
		 *
		 * ⚠️ **必须两条路都留**：官方前端跑在 `http://127.0.0.1:3080` 上，
		 * 而 `navigator.clipboard` 只在**安全上下文**（https / localhost 域名 /
		 * file:）里存在 —— `127.0.0.1` 虽是回环地址、Chromium 也把它算作
		 * potentially trustworthy，但 WebView 上这个判定随内核版本而变，
		 * 不能赌。所以先用 `navigator.clipboard`，失败或不存在时退回
		 * `document.execCommand("copy")` + 临时 textarea 的老办法。
		 *
		 * @param {string} text - 要复制的文本。
		 * @returns {Promise<boolean>} 是否成功。
		 */
		function mpCopyText(text) {
			const fallback = () => {
				try {
					const area = document.createElement("textarea");
					area.value = text;
					// 必须在文档里且可聚焦，execCommand 才会作用于它。
					// 用 fixed + 透明把它藏起来，避免点按钮时页面跳动。
					area.setAttribute("readonly", "");
					area.style.position = "fixed";
					area.style.top = "0";
					area.style.left = "0";
					area.style.opacity = "0";
					area.style.pointerEvents = "none";
					document.body.appendChild(area);
					area.select();
					area.setSelectionRange(0, text.length);
					const ok = document.execCommand("copy");
					document.body.removeChild(area);
					return ok;
				} catch (error) {
					console.warn("[dsh-android-mobile] 复制到剪贴板失败:", error && error.message);
					return false;
				}
			};
			try {
				if (navigator.clipboard && navigator.clipboard.writeText) {
					return navigator.clipboard.writeText(text).then(() => true, fallback);
				}
			} catch (error) {
				/* 落到 fallback */
			}
			return Promise.resolve(fallback());
		}

		/**
		 * 「特权权限」分节组件。
		 *
		 * 官方向 `settings.section` 的组件注入 `t`（由注册项的 `locale` 命名空间绑定）
		 * 与 `close`；本组件只用 `t`。
		 *
		 * @param {object} props - 槽位注入面。
		 * @returns {object} React 元素。
		 */
		function mpPrivSection(props) {
			const React = mpPrivReact;
			const S = MP_PRIV_S;
			const t = typeof props.t === "function" ? props.t : (key) => MP_PRIV_ZH[key] ?? key;
			const [state, setState] = React.useState({ phase: "loading" });
			// ⚠️ **软截止**：探测真正返回前，界面不能一直锁在「检测中」。
			//
			//   真机反馈的现象：首次安装打开本页，「当前状态」一直显示「检测中」，
			//   既没有结论也没有任何按钮（那个「重新检测」按钮在 loading 期间是
			//   `disabled` 的），只能硬等——而宿主半的探测超时是 **45 秒**，
			//   用户描述为「等几分钟」。
			//
			//   为什么 45 秒砍不掉：那个预算是 runner 最坏路径（三条通道级联 +
			//   adbd 重启）的上限，砍了会把「慢但成功」误判成失败。
			//   但**界面不必陪着一起等** —— 超过这个软截止就先按「还没结论」渲染，
			//   把按钮还给用户；真结果回来时照常覆盖（`phase` 变 ready/error）。
			const SOFT_DEADLINE_MS = 8000;
			const [softExpired, setSoftExpired] = React.useState(false);
			const refresh = React.useCallback(() => {
				let alive = true;
				setState({ phase: "loading" });
				setSoftExpired(false);
				// 到点就把界面从「检测中」解锁。真正的 fetch 不取消：它回来时
				// 下面的 then/catch 会用真实结论覆盖掉这个临时视图。
				const softTimer = setTimeout(() => { if (alive) setSoftExpired(true); }, SOFT_DEADLINE_MS);
				window.fetch(MP_PRIV_API, { cache: "no-store" })
					.then((res) => {
						if (!res.ok) throw new Error("HTTP " + res.status);
						return res.json();
					})
					.then((data) => { if (alive) { clearTimeout(softTimer); setState({ phase: "ready", data }); } })
					.catch((error) => {
						if (alive) {
							clearTimeout(softTimer);
							setState({ phase: "error", message: String((error && error.message) || error) });
						}
					});
				return () => { alive = false; clearTimeout(softTimer); };
			}, []);
			React.useEffect(refresh, [refresh]);

			// ══ root（uid 0）状态 ══════════════════════════════════════════
			//
			// 与上面的 uid 2000 状态**来源不同**：那条走宿主半的 HTTP 路由（探测要
			// spawn runner），而 root 的结论在**原生**侧（探测要起 `su` 进程、可能弹
			// 授权框），所以走 JS 桥 `window.DSHAndroid.rootState()`。
			//
			// ⚠️ 桥上的 `rootState()` **只读缓存、不触发探测** —— 它跑在 WebView 的
			//   JS 线程上，同步探测会把页面卡住。重新探测走 `rootRecheck()`，那是
			//   后台线程，界面靠下面的轮询拿结果。
			const [root, setRoot] = React.useState({ known: false, state: "", granted: false, detail: "", at: 0, mounted: false });
			const [rootBusy, setRootBusy] = React.useState(false);
			// 在跑的轮询计时器。用 ref 而不是普通变量：`recheckRoot` 是 useCallback，
			// 闭包捕获的必须是一个**跨渲染稳定**的容器，否则每次渲染新建一个 Set，
			// 卸载时的清理就清不到真正的计时器。
			const rootTimers = React.useRef(null);
			if (rootTimers.current === null) rootTimers.current = new Set();

			/** 读一次缓存里的 root 结论（不触发探测）。 */
			const readRoot = React.useCallback(() => {
				const bridge = window.DSHAndroid;
				if (!bridge || typeof bridge.rootState !== "function") {
					setRoot({ known: false, state: "", granted: false, detail: "", at: 0, mounted: false });
					return;
				}
				try {
					// 桥返回 JSON 字符串（@JavascriptInterface 只支持基本类型与 String）。
					const raw = bridge.rootState();
					const o = typeof raw === "string" ? JSON.parse(raw) : (raw || {});
					setRoot({
						known: !!o.known,
						state: String(o.state || ""),
						granted: !!o.granted,
						detail: String(o.detail || ""),
						at: Number(o.at) || 0,
						// ⚠️ 这是「本会话里 root 工具真的挂上了没有」，与 granted 不是一回事：
						//   overlay 只在引擎启动时读一次，重新检测把结论刷成 GRANTED 之后
						//   工具并不会凭空出现。见下面的 rootNextOnPending。
						mounted: !!o.mounted,
					});
				} catch (e) {
					console.warn("[dsh-android-mobile] 读取 root 状态失败:", e && e.message);
					setRoot({ known: false, state: "", granted: false, detail: "", at: 0, mounted: false });
				}
			}, []);

			React.useEffect(() => { readRoot(); }, [readRoot]);

			/**
			 * 请求重新检测，然后**轮询**直到结论变化。
			 *
			 * ⚠️ 必须轮询，不能等一个返回值：原生侧是「立即返回、后台线程里探测」，
			 *   因为探测最多要等 5 秒（首次还会弹授权框），同步等会把页面卡死。
			 * ⚠️ 轮询上限（10 秒）要比原生的探测超时（5 秒）宽，否则会在原生还没
			 *   出结论时就把按钮恢复，用户以为检测完了、看到的却是旧结论。
			 * ⚠️ 判据是**时间戳变化**（`at`），不是结论变化：连续两次都测出
			 *   「无 root」时结论字符串一样，只看结论会一直轮询到超时。
			 */
			const recheckRoot = React.useCallback(() => {
				const bridge = window.DSHAndroid;
				if (!bridge || typeof bridge.rootRecheck !== "function") return;
				let before = 0;
				try {
					const raw = bridge.rootState();
					before = Number((typeof raw === "string" ? JSON.parse(raw) : (raw || {})).at) || 0;
				} catch (e) { /* 拿不到旧时间戳就退化成「轮询到超时」，仍然正确 */ }
				try {
					bridge.rootRecheck();
				} catch (e) {
					console.warn("[dsh-android-mobile] 请求重新检测 root 失败:", e && e.message);
					return;
				}
				setRootBusy(true);
				const startedAt = Date.now();
				const timer = window.setInterval(() => {
					const b = window.DSHAndroid;
					let at = 0;
					try {
						const raw = b && b.rootState ? b.rootState() : "{}";
						at = Number((typeof raw === "string" ? JSON.parse(raw) : (raw || {})).at) || 0;
					} catch (e) { /* 读失败当作还没出结果，继续等 */ }
					if (at > before || Date.now() - startedAt > 10000) {
						window.clearInterval(timer);
						rootTimers.current.delete(timer);
						readRoot();
						setRootBusy(false);
					}
				}, 700);
				// 记账以便卸载时清理（本函数没有 cleanup 出口，所以交给下面的 effect）。
				rootTimers.current.add(timer);
			}, [readRoot]);

			// 组件卸载时清掉所有在跑的轮询 —— 否则用户切走分节后计时器仍在打桥。
			React.useEffect(() => {
				const timers = rootTimers.current;
				return () => {
					timers.forEach((id) => window.clearInterval(id));
					timers.clear();
				};
			}, []);

			// 从授权界面回来后刷新：给 root 授权是在 Magisk 之类的**别的应用**里完成的，
			// 本页拿不到任何回调，只能靠 visibilitychange / focus（与气泡分节同一手法）。
			React.useEffect(() => {
				const onVisible = () => { if (!document.hidden) readRoot(); };
				document.addEventListener("visibilitychange", onVisible);
				window.addEventListener("focus", onVisible);
				return () => {
					document.removeEventListener("visibilitychange", onVisible);
					window.removeEventListener("focus", onVisible);
				};
			}, [readRoot]);

			const data = state.phase === "ready" ? state.data : null;
			// ⚠️ 软截止到期后**不再算 loading**：下一步提示与按钮可用性都按正常态渲染，
			//   用户立刻拿回控制权（见 SOFT_DEADLINE_MS）。
			//   真结果回来时 `state.phase` 会变成 ready/error，一切照常覆盖。
			const loading = state.phase === "loading" && !softExpired;
			// ⚠️ 但**徽章文案**不能因为软截止就改成「未开启」—— 那时根本没有结论，
			//   报「未开启」是假话（真结果可能是「已开启」）。只有拿到真结论
			//   （ready/error）才允许改徽章，否则一律维持「检测中」。
			const settled = state.phase !== "loading";
			const identityOk = !!(data && data.identity && data.identity.ok);
			const tone = !settled ? "unknown"
				: state.phase === "error" ? "off"
					: identityOk ? "on" : "off";
			const badgeText = !settled ? t("statusUnknown")
				: state.phase === "error" ? t("failed")
					: identityOk ? t("statusOn") : t("statusOff");

			// 当前通道 —— 只有固定端口那条**不依赖无线调试开关**，所以要显式区分，
			// 否则用户看到「已开启」也不知道关了开关会不会断。
			const channel = data && data.identity ? data.identity.channel : "none";
			const channelText = channel === "legacy" ? t("chStable")
				: channel === "wireless" ? t("chWireless") : "";

			// ── 系统版本：决定走哪条引导路径 ──────────────────────
			//
			// ⚠️ **Android 10 及以下没有「无线调试」** —— 那是 Android 11（API 30）
			//   才有的功能。在 Android 10 上还把它摆在最上面、还挂「推荐」、还给出
			//   「配对无线调试」按钮，用户照着去系统设置里找只会找不到（真机反馈过）。
			//
			// 判据用 UA 里的 `Android <版本>`：浏览器进程拿不到 `Build.VERSION.SDK_INT`，
			//   而 UA 是现成的、不需要为它新增一条原生桥。
			const androidMajor = (() => {
				const m = /Android\s+(\d+)/i.exec(navigator.userAgent || "");
				return m ? Number.parseInt(m[1], 10) : 0;
			})();
			// 版本判不出来时**当作不支持**（走电脑引导）：电脑引导在所有版本上都成立，
			// 而无线调试在 Android 10 上根本不存在。判错方向宁可保守。
			const wirelessSupported = androidMajor >= 11;

			// 「下一步该做什么」—— 由当前状态推出来，摆在首屏。
			// 用户不该为了知道点哪里而读完全部卡片。
			const keyReady = !!(data && data.key
				&& (data.key.origin === "imported" || data.key.origin === "paired"));
			let next = null;
			if (data) {
				// 失败分类（runner 那条 `诊断: kind=…` 行）。没拿到时是 "unknown"，
				// 下面的分支会自动退回通用引导 —— 与改动前的行为一致。
				const kind = data.identity && data.identity.kind ? data.identity.kind : "unknown";
				// 四类「密钥侧」的问题，正解都是**再配对一次**，只是原因不同。
				const keyKind = kind === "no-key" || kind === "key-revoked"
					|| kind === "key-unauthorized" || kind === "key-blocked";
				if (identityOk) {
					next = channel === "wireless"
						? { text: t("nextSwitchHint"), action: "switchTcp", label: t("wlTcp") }
						: { text: t("nextAllGood"), action: null };
				} else if (keyKind) {
					// ⚠️ 只在系统真支持无线调试时才给配对按钮 —— 否则那个按钮在
					//    Android 10 上永远走不通，点了只是白等（与下面同一条约束）。
					const text = kind === "no-key"
						? t(wirelessSupported ? "nextNoKey" : "nextNoKeyPc")
						: kind === "key-revoked" ? t("nextRevoked")
							: kind === "key-unauthorized" ? t("nextUnauthorized")
								: t("nextKeyBlocked");
					next = wirelessSupported
						? { text, action: "pair", label: t(kind === "no-key" ? "wlPair" : "nextPair") }
						: { text, action: null };
				} else if (kind === "wireless-stale" || kind === "no-channel") {
					// 这两类的正解是**切到固定端口**：固定端口一旦就绪就不再依赖
					// 无线调试开关，恰好绕开「无线调试关着 ⇒ 无线通道必然不可用」。
					next = {
						text: t(kind === "wireless-stale" ? "nextWirelessStale" : "nextNoChannel"),
						action: "switchTcp",
						label: t("wlTcp"),
					};
				} else if (kind === "adb-missing" || kind === "timeout") {
					// 这两类没有能替用户按的按钮：前者要重装 App，后者只是 adbd
					// 还在重启（等一会儿点「重新检测」）。给一句可执行的说明就够，
					// **不要**摆一个点了没用的按钮。
					next = { text: t(kind === "adb-missing" ? "nextAdbMissing" : "nextTimeout"), action: null };
				} else if (data.key && data.key.origin === "generated" && wirelessSupported) {
					// 只有系统真支持无线调试时，才引导去配对 —— 否则那个按钮
					// 在 Android 10 上永远走不通，点了只是白等。
					next = { text: t("nextPairHint"), action: "pair", label: t("wlPair") };
				} else if (keyReady) {
					next = { text: t("nextSwitchHint"), action: "switchTcp", label: t("wlTcp") };
				} else {
					next = {
						text: wirelessSupported ? t("nextChoose") : t("nextChoosePc"),
						action: null,
					};
				}
			}

			// 两个原生动作都要走 JS 桥 —— 配对码得经通知栏输入，而通知是 Android
			// 的能力，Node 侧（宿主半的 HTTP 路由）够不到。
			const doAction = (action) => {
				const b = window.DSHAndroid;
				const fn = action === "pair" ? "startWirelessPairing"
					: action === "switchTcp" ? "switchToTcpMode" : null;
				if (fn === null) return;
				if (!b || typeof b[fn] !== "function") { window.alert(t("wlNoBridge")); return; }
				// ⚠️ 配对的一切交互（提示、配对码输入框、取消按钮）**都挂在那条常驻
				//   通知上**，通知被系统关掉时原生侧会直接拒绝开始（见
				//   `AdbPairingNotifier.start`）。不在这里先说清楚的话，用户看到的是
				//   「点了配对，什么都没发生」—— 然后反复点，永远不知道为什么。
				if (action === "pair") {
					try {
						if (typeof b.pairingNotificationsEnabled === "function"
							&& !b.pairingNotificationsEnabled()) {
							window.alert(t("wlNoNotify"));
							return;
						}
					} catch (e) { /* 查不了就照常走，原生侧还有一道拦截 */ }
				}
				b[fn]();
				// 只有配对需要回写「进行中」—— 它是一条要用户离开本页去系统设置的
				// 长流程；「切到固定端口」是几秒钟的原生调用，不需要。
				if (action === "pair") setPairing(true);
			};

			/** 取消配对：停掉原生侧那条常驻监听，并把本页状态复位。 */
			const cancelPairing = () => {
				const b = window.DSHAndroid;
				if (b && typeof b.cancelWirelessPairing === "function") b.cancelWirelessPairing();
				setPairing(false);
			};

			// 无线端口三态：真的在听 / 文件里有值但端口已死 / 没发现。
			// ⚠️ 中间那态以前会显示成绿灯（只查文件在不在），真机撞到过 ——
			// 无线调试关着、35589 早已没人监听，界面却报「无线端口 35589」。
			const wlPort = data && data.wireless ? data.wireless.connect : null;
			const wlStale = data && data.wireless ? data.wireless.stale : null;
			const [showDetails, setShowDetails] = React.useState(false);

			const rows = [];
			if (data) {
				rows.push({
					key: "runner", label: t("rowRunner"), ok: data.runner.ok,
					value: data.runner.ok ? `${t("ready")} · ${data.runner.size} ${t("bytes")}` : t("missing"),
				});
				// ⚠️ 徽章不能只看「密钥文件在不在」：**App 现场生成的密钥设备并不信任**，
				// 显示绿点会让人以为可以用了。
				//
				// 三态（判据见 assets/dsh-android-shell.mjs 的 `keyOrigin()`）：
				//   imported / paired → 绿点，设备已信任，直接能用
				//   generated         → 黄点，必须先配对
				//   null              → 灰点，**来源未知**（标记文件是后加的，
				//                       老安装的密钥没有它，无法回溯推断）
				// 未知时**故意不猜**成「已导入」—— 那正是这次要修的误报。
				rows.push({
					key: "key", label: t("rowKey"), ok: keyReady,
					warn: data.key.ok && data.key.origin === "generated",
					unknown: data.key.ok && !keyReady && data.key.origin !== "generated",
					value: data.key.ok
						? `${data.key.origin === "generated" ? t("generated")
							: data.key.origin === "paired" ? t("paired")
								: data.key.origin === "imported" ? t("imported")
									: t("keyOriginUnknown")} · ${data.key.size} ${t("bytes")}${data.key.importedNow ? " · " + t("justImported") : ""}`
						: t("notImported"),
				});
				rows.push({
					key: "identity", label: t("rowIdentity"), ok: identityOk,
					value: identityOk
						? `${t("uidOk")}${data.identity && data.identity.channel === "legacy" ? " · " + t("chStable")
							: data.identity && data.identity.channel === "wireless" ? " · " + t("chWireless") : ""}`
						: `${t("uidApp")}${data.identity && data.identity.reason ? " · " + data.identity.reason : ""}`,
				});
			}

			// 复制结果按**对象**存（不是布尔、也不是纯字符串）：两个块共用一个
			// 「最近复制的是哪条」，点第二条时第一条会自己复位成「复制」，
			// 不会两块同时显示「已复制」让人分不清到底复制了哪条。
			// 存对象而非字符串还有个作用：连点同一个按钮时 `at` 每次都不同，
			// 保证 state 一定变化 → effect 重跑 → 复位计时器被重新计时。
			const [copied, setCopied] = React.useState(null);
			React.useEffect(() => {
				if (copied === null) return undefined;
				const timer = window.setTimeout(() => setCopied(null), 2000);
				return () => window.clearTimeout(timer);
			}, [copied]);

			// 是否已经发起过配对（点了「配对无线调试」但还没取消）。
			//
			// ⚠️ 这个状态**必须让用户看见**，否则点了按钮后界面毫无变化，用户会以为
			// 没点上而反复点 —— 而真实情况是「正在等你去系统里打开那个界面」。
			// 只存本地 state：真正的监听状态在原生侧，这里只是给个即时反馈，
			// 不需要（也不该）为此多开一条查询接口。
			const [pairing, setPairing] = React.useState(false);
			const e = React.createElement;
			// 命令块 = 等宽代码 + 复制按钮。**按钮不是锦上添花**：本插件的全局
			// CSS 把 `user-select` 压成 `none`（见文件头 4c），命令长按选不中，
			// 而用户是在电脑终端里敲这些命令，只能照着手机屏幕抄 —— 没有按钮就得
			// 逐字符手抄 `%USERPROFILE%\.android\adbkey`。
			const cmdBlock = (text) => e("div", { key: text, style: S.cmdRow },
				e("code", { style: S.cmd }, text),
				e("button", {
					type: "button",
					style: S.cmdBtn,
					onClick: () => {
						mpCopyText(text).then((ok) => setCopied({ text, ok, at: Date.now() }));
					},
				}, copied && copied.text === text
					? (copied.ok ? t("copied") : t("copyFailed"))
					: t("copy")),
			);
			const guideSteps = [
				e("li", { key: 0, style: S.step }, t("step1")),
				e("li", { key: 1, style: S.step }, t("step2"), cmdBlock(t("cmd2"))),
				e("li", { key: 2, style: S.step }, t("step3"), cmdBlock(t("cmd3"))),
				e("li", { key: 3, style: S.step }, t("step4")),
			];

			// ── 首屏：结论 + 下一步 ────────────────────────────────
			// 原来的首屏是一张「执行通道 / ADB 密钥 / 实际身份」的技术表格，
			// 用户得自己把三行翻译成「我现在能不能用、不能的话点哪里」。
			// 现在把结论摆在最上面，明细折进「查看详情」。
			const head = e("div", { style: S.card },
				e("div", { style: S.cardHead },
					e("h3", { style: S.cardTitle }, t("statusTitle")),
					e("span", { style: { ...S.badge, color: MP_PRIV_TONE[tone].color, background: MP_PRIV_TONE[tone].bg } }, badgeText),
				),
				// 已开启时把「走哪条通道」直接写在结论下面 —— 这是用户唯一需要
				// 知道的运行期事实（关无线调试/关 Wi-Fi 会不会断）。
				identityOk && channelText
					? e("p", { style: S.summary }, channelText)
					: null,
				next ? e("div", { style: S.next },
					e("span", { style: S.nextText }, next.text),
					next.action ? e("button", {
						type: "button",
						style: S.btnPrimary,
						onClick: () => doAction(next.action),
					}, next.label) : null,
				) : null,
				// ⚠️ 软截止到期、真结果还没回来时，明确告诉用户「还在探测、你可以不等」，
				//   并直接给出「重新匹配」入口 —— 而不是留一个没有结论也没有按钮的死界面。
				//   真机反馈原话：「你一检测没有就是没有了呀，没有就让用户重新走匹配流程啊，
				//   我要这里等几分钟？」这条分支就是那个诉求的落点。
				softExpired && state.phase === "loading" ? e("div", { style: S.next },
					e("span", { style: S.nextText }, t(wirelessSupported ? "softPending" : "softPendingPc")),
					// ⚠️ 只有系统真支持无线调试时才给配对按钮 —— Android 10 上配对
					//   根本不存在，摆一个点了必然白等的按钮比不给更糟（与上面
					//   `keyKind` 分支同一条约束）。
					wirelessSupported ? e("button", {
						type: "button",
						style: S.btnPrimary,
						onClick: () => doAction("pair"),
					}, t("wlPair")) : null,
				) : null,
				state.phase === "error" ? e("p", { style: S.error }, state.message) : null,
				e("div", { style: S.actions },
					e("button", {
						type: "button",
						style: S.btn,
						// ⚠️ 只在**软截止之前**禁用。到点即解锁，用户不必陪着探测一起等 ——
						//   真机反馈的「一直显示检测中、又没有按钮、又没有停止」正是
						//   这里一直 `disabled` 造成的。
						disabled: loading,
						onClick: refresh,
					}, loading ? t("checking") : t("recheck")),
					data ? e("button", {
						type: "button",
						style: S.btnQuiet,
						onClick: () => setShowDetails((v) => !v),
					}, showDetails ? t("hideDetails") : t("showDetails")) : null,
				),
				// 明细：定位问题时才需要，默认收起。
				showDetails && data ? e("div", { style: S.details },
					rows.map((row) => e("div", { key: row.key, style: S.row },
						e("span", {
							style: {
								...S.dot,
								background: (row.ok ? MP_PRIV_TONE.on
									: row.warn ? MP_PRIV_TONE.warn
										: row.unknown ? MP_PRIV_TONE.unknown
											: MP_PRIV_TONE.off).color,
							},
						}),
						e("span", { style: S.rowLabel }, row.label),
						e("span", { style: S.rowValue }, row.value),
					)),
				) : null,
			);

			// ── 两条引导路径：按系统版本决定哪条在前、哪条带「推荐」──
			// （版本判定在函数开头，见 androidMajor / wirelessSupported）
			const wlCard = e("div", { style: S.card },
				e("div", { style: S.cardHead },
					e("h3", { style: S.cardTitle }, t("wlTitle")),
					wirelessSupported ? e("span", { style: S.tagRecommended }, t("recommended")) : null,
				),
				// Android 10 上明确说明为什么这条路不通，而不是让用户白找一遍。
				!wirelessSupported ? e("p", { style: S.note }, t("wlUnsupported")) : null,
				e("p", { style: S.intro }, t("wlIntro")),
				e("ol", { style: S.steps }, [
					e("li", { key: 0, style: S.step }, t("wlStep1")),
					e("li", { key: 1, style: S.step }, t("wlStep2")),
					e("li", { key: 2, style: S.step }, t("wlStep3")),
					e("li", { key: 3, style: S.step }, t("wlStep4")),
				]),
				// 无线端口是「有没有发现到」的最直接证据 —— 没有它，
				// 用户只能靠「配对按钮点了没反应」来猜，无从下手。
				// 三态：真的在听（绿）/ 文件里有值但端口已死（灰，说明无线调试关了）/
				// 没发现（灰）。中间那态以前会误报成绿灯。
				data ? e("div", { style: S.row },
					e("span", {
						style: {
							...S.dot,
							background: wlPort ? MP_PRIV_TONE.on.color
								: wlStale ? MP_PRIV_TONE.unknown.color : MP_PRIV_TONE.off.color,
						},
					}),
					e("span", { style: S.rowLabel }, t("wlPort")),
					e("span", { style: S.rowValue }, wlPort ? String(wlPort)
						: wlStale ? t("wlPortStale") : t("wlPortNone")),
				) : null,
				e("div", { style: S.cmdRow },
					e("button", {
						type: "button",
						style: S.btnPrimary,
						disabled: pairing,
						onClick: () => doAction("pair"),
					}, pairing ? t("wlPairing") : t("wlPair")),
					e("button", {
						type: "button",
						style: S.btn,
						onClick: () => doAction("switchTcp"),
					}, t("wlTcp")),
					e("button", {
						type: "button",
						style: S.btnQuiet,
						onClick: cancelPairing,
					}, t("wlCancel")),
				),
				// 等待期间把「接下来该做什么」直接铺在按钮下面 —— 用户点了配对之后
				// 眼神还在这块区域，跑到别处去提示等于没提示。
				pairing ? e("p", { style: S.note }, t("wlPairingHint")) : null,
				e("p", { style: S.note }, t("wlTcpHint")),
				e("p", { style: S.note }, t("wlPairHint")),
			);

			// ── 电脑引导 ──────────────────────────────────────────
			// Android 10 上它是**唯一**可行的路径，所以默认展开、摆前面；
			// Android 11+ 上它只是备用，折起来放后面。
			const pcCard = e("details", { style: S.fold, open: !wirelessSupported },
				e("summary", { style: S.foldHead },
					wirelessSupported ? t("pcTitle") : t("pcTitlePrimary")),
				e("p", { style: S.intro }, t("pcIntro")),
				e("ol", { style: S.steps }, guideSteps),
			);

			// ── 注意事项（同样折起）────────────────────────────────
			const notesCard = e("details", { style: S.fold },
				e("summary", { style: S.foldHead }, t("notesTitle")),
				e("ul", { style: S.notes },
					[t("note1"), t("note2"), t("note3")].map((text, index) => e("li", { key: index, style: S.note }, text)),
				),
			);

			// ── root（uid 0）卡片 ──────────────────────────────────
			//
			// 为什么单独一张卡：uid 2000 与 root 是**两条独立的通道**，可用性互不
			// 影响（没 root 的设备照样能用 uid 2000）。所以不能把 root 塞进上面那张
			// 状态卡的徽章里 —— 那个徽章只回答「uid 2000 能不能用」。
			//
			// ⚠️ 三态各自对应**完全不同的下一步**（去授权 / 什么都不用做 / 去检测），
			//   所以文案必须分开，不能合成一句「root 不可用」。
			const rootTone = !root.known ? MP_PRIV_TONE.unknown
				: root.granted ? MP_PRIV_TONE.on
					: root.state === "DENIED" ? MP_PRIV_TONE.warn : MP_PRIV_TONE.off;
			const rootBadge = !root.known ? t("rootUnknown")
				: root.granted ? t("rootOn")
					: root.state === "DENIED" ? t("rootDenied") : t("rootAbsent");
			// ⚠️ `granted` 与 `mounted` 必须分开判：前者是「探测结论」，后者是
			//   「本次启动真的挂上了 root 工具」。工具走 `--patch`，启动时定死 ——
			//   用户在设置页点「重新检测」把结论刷成 GRANTED 的那一刻，引擎已经在跑，
			//   这个会话里**没有** root 工具。此时若还说「已注册」，就是在说假话。
			const rootNext = !root.known ? t("rootNextUnknown")
				: root.granted
					? (root.mounted ? t("rootNextOn") : t("rootNextOnPendingReload"))
					: root.state === "DENIED" ? t("rootNextDenied") : t("rootNextAbsent");
			// 检测时间：结论是 24 小时缓存的，用户需要知道这是刚测的还是昨天的。
			const rootAtText = root.at > 0 ? new Date(root.at).toLocaleString() : t("rootDetailNever");

			const rootCard = e("div", { style: S.card },
				e("div", { style: S.cardHead },
					e("h3", { style: S.cardTitle }, t("rootTitle")),
					e("span", { style: { ...S.badge, color: rootTone.color, background: rootTone.bg } }, rootBadge),
				),
				e("div", { style: S.next },
					e("span", { style: S.nextTextWide }, rootNext),
					e("button", {
						type: "button",
						style: S.btnPrimary,
						disabled: rootBusy,
						onClick: recheckRoot,
					}, rootBusy ? t("rootChecking") : t("rootRecheck")),
				),
				e("div", { style: S.row },
					e("span", { style: { ...S.dot, background: rootTone.color } }),
					e("span", { style: S.rowLabel }, t("rootRowDetail")),
					// 结论原文由原生给出（`su=/system/bin/su, id -u → 0` 这类），
					// 直接透出比翻译成一句话更有助于排查。
					e("span", { style: S.rowValue }, root.known && root.detail ? root.detail : t("rootDetailNever")),
				),
				e("div", { style: S.row },
					e("span", { style: { ...S.dot, background: MP_PRIV_TONE.unknown.color } }),
					e("span", { style: S.rowLabel }, t("rootRowAt")),
					e("span", { style: S.rowValue }, rootAtText),
				),
				e("p", { style: S.note }, t("rootNote")),
			);

			return e("div", { style: S.section },
				e("h2", { style: S.heading }, t("title")),
				e("p", { style: S.intro }, t("intro")),
				head,
				// root 卡片**始终显示**（不像引导那样按状态折叠）—— 它的全部意义就是
				// 让「本机有没有 root」这件事可见，折起来等于没做。
				rootCard,
				// 已就绪时把引导整体折起来 —— 能用的人不该被一堆步骤挡着。
				// 未就绪时：Android 10 把电脑引导摆前面（那是唯一可行的路），
				// Android 11+ 反过来 —— 无线调试更省事。
				identityOk
					? e("details", { style: S.fold },
						e("summary", { style: S.foldHead }, t("setupTitle")),
						e("div", { style: S.foldBody }, wlCard, pcCard),
					)
					: e("div", { style: S.stack },
						...(wirelessSupported ? [wlCard, pcCard] : [pcCard, wlCard])),
				notesCard,
			);
		}

		/**
		 * 把「特权权限」分节注册进官方设置页的一级导航。
		 *
		 * 用 `ctx.inject` 等 `slots` / `locale` 就绪：本插件声明的 `inject` 是空数组
		 * （客户端半刻意不声明依赖，避免因某个服务缺失而整个不激活），所以这里按需
		 * 开一个子插件来等这两个服务。
		 *
		 * @param {object} ctx - 浏览器插件上下文。
		 */
		function mpRegisterPrivilegedSection(ctx) {
			if (mpPrivReact === null) {
				try { mpPrivReact = require("react"); } catch (e) {
					console.warn("[dsh-android-mobile] require('react') 失败，跳过特权权限分节:", e && e.message);
					mpPrivReact = null;
				}
			}
			if (!mpPrivReact) return;   // 拿不到 React 就静默跳过，不影响移动端 UI 适配
			try {
				ctx.inject(["slots", "locale"], (scope) => {
					// ⚠️ **必须用 `scope` 上的服务，不能用外层 `ctx`。**
					//   Cordis 的 `ctx.locale` 要求**当前 fiber 声明过** `locale` 依赖，
					//   否则抛 `cannot get property "locale" without inject`。
					//   本插件的 `inject` 是空数组（客户端半刻意不声明依赖），所以外层
					//   `ctx.locale` 一定不可用；`scope` 是这个 `ctx.inject` 子 fiber，
					//   它的依赖表里有 `slots`/`locale`，从它取才对。
					//   （官方 ui-approval 写 `ctx.locale` 是因为它的 inject 里声明了 locale，
					//    照抄那行在本插件里必炸 —— 这里踩过一次。）
					const t = scope.locale.bind(MP_PRIV_NS);
					scope.effect(
						() => scope.locale.register(MP_PRIV_NS, { zh: MP_PRIV_ZH, en: MP_PRIV_EN }),
						"dsh-android-mobile: 特权权限字典",
					);
					scope.slots.inject("settings.section", () => scope.slots.register({
						name: "settings.section",
						// 刻意不叫 "privileged"：官方若将来加同名分节，我们的会把它顶掉。
						id: "dsh-android-privileged",
						// 官方 order：account=-10、general=0、models=10、plugins=15、
						// agent-presets=20。排在所有官方分节之后，不打乱用户熟悉的顺序。
						order: 25,
						label: () => t("nav"),
						locale: MP_PRIV_NS,
					}, mpPrivSection));
				});
			} catch (e) {
				/* 注册失败只损失这一个分节，绝不能连累移动端 UI 适配。
				   但**要打日志**：静默吞异常会让「分节没出现」变成无从下手的哑谜。 */
				console.warn("[dsh-android-mobile] 注册特权权限分节失败:", e && (e.stack || e.message || e));
			}
		}

		/**
		 * 「悬浮气泡」分节组件。
		 *
		 * 布局沿用「特权权限」那套**结论优先**的结构：head 卡给状态徽章 + 一条
		 * 「下一步」+ 直达按钮，明细与说明折进 `<details>`。
		 *
		 * @param {object} props - 槽位注入面（只用 `t`）。
		 * @returns {object} React 元素。
		 */
		function mpBubbleSection(props) {
			const React = mpPrivReact;
			const S = MP_PRIV_S;
			const t = typeof props.t === "function" ? props.t : (key) => MP_BUBBLE_ZH[key] ?? key;
			const [state, setState] = React.useState({ phase: "loading" });
			const [showDetails, setShowDetails] = React.useState(false);

			/**
			 * 从原生读一次状态。
			 *
			 * ⚠️ 桥**可能不存在**（非 App 环境、或桥被清理），所以先查再调 ——
			 *   直接调会抛 `TypeError: Cannot read properties of undefined`。
			 */
			const refresh = React.useCallback(() => {
				const bridge = window.DSHAndroid;
				if (!bridge || typeof bridge.bubbleState !== "function") {
					setState({ phase: "ready", enabled: false, granted: false, noBridge: true });
					return;
				}
				try {
					// 桥返回的是 JSON 字符串（@JavascriptInterface 只支持基本类型与 String）。
					const raw = bridge.bubbleState();
					const o = typeof raw === "string" ? JSON.parse(raw) : (raw || {});
					setState({ phase: "ready", enabled: !!o.enabled, granted: !!o.granted, noBridge: false });
				} catch (e) {
					console.warn("[dsh-android-mobile] 读取气泡状态失败:", e && e.message);
					setState({ phase: "ready", enabled: false, granted: false, noBridge: true });
				}
			}, []);

			React.useEffect(() => { refresh(); }, [refresh]);

			/**
			 * 从系统设置页回来后刷新。
			 *
			 * ⚠️ 授权是**在别的界面**完成的，本页拿不到任何回调。只能监听
			 *   `visibilitychange`（用户切回来时触发）。不加这个的话，用户授完权
			 *   回来看到的还是「未授权」，会以为没生效。
			 */
			React.useEffect(() => {
				const onVisible = () => { if (!document.hidden) refresh(); };
				document.addEventListener("visibilitychange", onVisible);
				window.addEventListener("focus", onVisible);
				return () => {
					document.removeEventListener("visibilitychange", onVisible);
					window.removeEventListener("focus", onVisible);
				};
			}, [refresh]);

			const toggle = () => {
				const bridge = window.DSHAndroid;
				if (!bridge || typeof bridge.bubbleSetEnabled !== "function") return;
				try {
					bridge.bubbleSetEnabled(!state.enabled);
					// 乐观更新：桥是同步的，但立刻重读一次更可靠（也顺带验证写入生效）。
					refresh();
				} catch (e) {
					console.warn("[dsh-android-mobile] 切换气泡失败:", e && e.message);
				}
			};

			const grant = () => {
				const bridge = window.DSHAndroid;
				if (bridge && typeof bridge.bubbleRequestOverlay === "function") {
					try { bridge.bubbleRequestOverlay(); } catch (e) { /* 下面统一提示 */ }
				}
			};

			if (state.phase === "loading") {
				return React.createElement("div", { style: S.section },
					React.createElement("h2", { style: S.heading }, t("title")),
					React.createElement("p", { style: S.intro }, t("statusUnknown")),
				);
			}

			const noBridge = state.noBridge;
			const ready = state.enabled && state.granted;
			const tone = ready ? MP_PRIV_TONE.on : MP_PRIV_TONE.off;
			const badgeText = ready ? t("statusOn") : t("statusOff");

			// 「下一步」按状态级联挑一条 —— 与特权权限分节同一套思路：
			// 不让用户自己从几行技术明细里翻译出该做什么。
			let nextText = t("nextAllGood");
			let nextBtn = null;
			if (noBridge) {
				nextText = t("noBridge");
			} else if (!state.granted) {
				nextText = t("nextNeedPerm");
				nextBtn = React.createElement("button", { style: S.btnPrimary, onClick: grant }, t("grant"));
			} else if (!state.enabled) {
				nextText = t("nextNeedEnable");
				nextBtn = React.createElement("button", { style: S.btnPrimary, onClick: toggle }, t("enable"));
			} else {
				nextBtn = React.createElement("button", { style: S.btnQuiet, onClick: toggle }, t("disable"));
			}

			const dot = (ok) => React.createElement("span", {
				style: { ...S.dot, background: ok ? MP_PRIV_TONE.on.color : MP_PRIV_TONE.off.color },
			});
			const row = (label, ok, value) => React.createElement("div", { style: S.row, key: label },
				dot(ok),
				React.createElement("span", { style: S.rowLabel }, label),
				React.createElement("span", { style: S.rowValue }, value),
			);

			return React.createElement("div", { style: S.section },
				React.createElement("h2", { style: S.heading }, t("title")),
				React.createElement("p", { style: S.intro }, t("intro")),

				React.createElement("div", { style: S.card },
					React.createElement("div", { style: S.cardHead },
						React.createElement("h3", { style: S.cardTitle }, t("statusTitle")),
						React.createElement("span", {
							style: { ...S.badge, color: tone.color, background: tone.bg },
						}, badgeText),
					),
					React.createElement("div", { style: S.next },
						React.createElement("span", { style: S.nextText }, nextText),
						nextBtn ? React.createElement("div", { style: S.actions }, nextBtn) : null,
					),
					React.createElement("div", { style: S.actions },
						React.createElement("button", { style: S.btn, onClick: refresh }, t("recheck")),
						React.createElement("button", {
							style: S.btnQuiet,
							onClick: () => setShowDetails((v) => !v),
						}, showDetails ? t("hideDetails") : t("showDetails")),
					),
					showDetails ? React.createElement("div", { style: S.details },
						row(t("rowSwitch"), state.enabled, state.enabled ? t("on") : t("off")),
						row(t("rowPerm"), state.granted, state.granted ? t("granted") : t("notGranted")),
					) : null,
				),

				React.createElement("details", { style: S.fold },
					React.createElement("summary", { style: S.foldHead }, t("howTitle")),
					React.createElement("div", { style: S.foldBody },
						React.createElement("ul", { style: S.notes },
							[t("how1"), t("how2"), t("how3"), t("how4")].map((line, i) =>
								React.createElement("li", { style: S.note, key: i }, line)),
						),
					),
				),

				React.createElement("details", { style: S.fold },
					React.createElement("summary", { style: S.foldHead }, t("notesTitle")),
					React.createElement("div", { style: S.foldBody },
						React.createElement("ul", { style: S.notes },
							[t("note1"), t("note2"), t("note3")].map((line, i) =>
								React.createElement("li", { style: S.note, key: i }, line)),
						),
					),
				),
			);
		}

		/**
		 * 把「悬浮气泡」注册进设置页一级导航。
		 *
		 * 与特权权限分节同一套接缝（`settings.section`），所以不需要碰导航 DOM。
		 *
		 * @param {object} ctx - 浏览器插件上下文。
		 */
		function mpRegisterBubbleSection(ctx) {
			if (mpPrivReact === null) {
				try { mpPrivReact = require("react"); } catch (e) {
					console.warn("[dsh-android-mobile] require('react') 失败，跳过悬浮气泡分节:", e && e.message);
					mpPrivReact = null;
				}
			}
			if (!mpPrivReact) return;
			try {
				ctx.inject(["slots", "locale"], (scope) => {
					// ⚠️ 必须用 `scope` 上的服务（原因见 mpRegisterPrivilegedSection 的注释）。
					const t = scope.locale.bind(MP_BUBBLE_NS);
					scope.effect(
						() => scope.locale.register(MP_BUBBLE_NS, { zh: MP_BUBBLE_ZH, en: MP_BUBBLE_EN }),
						"dsh-android-mobile: 悬浮气泡字典",
					);
					scope.slots.inject("settings.section", () => scope.slots.register({
						name: "settings.section",
						id: "dsh-android-bubble",
						// 紧跟在「特权权限」（25）之后。
						order: 26,
						label: () => t("nav"),
						locale: MP_BUBBLE_NS,
					}, mpBubbleSection));
				});
			} catch (e) {
				console.warn("[dsh-android-mobile] 注册悬浮气泡分节失败:", e && (e.stack || e.message || e));
			}
		}

		/* ══ 「交流群」分节：二维码 + 群号 ══

		   ⚠️ **二维码必须内联成 data URI，不能放成独立图片文件。**
		     官方 `dsh-client-modules` 的 `/plugins/<id>/<file>` 路由**只服务
		     `client.js` 与它的 sourcemap**（`index.js` 的 `PLUGIN_ROUTE` 与
		     `chunkUrl()` 都写死了这两个后缀），**没有**通用静态资源目录。
		     把二维码写成 `./qr.webp` 会得到一个 404 的碎图 —— 而且失败方式是
		     静默的（`<img>` 加载失败不抛异常，只是不显示）。
		     插件自身也没有别的落地目录可以断言「引擎一定会把它对客户端暴露」。

		   格式选 WebP 而非 PNG：同一张图 PNG 要 93~129 KB，WebP(q92) 只要
		   31~70 KB，体积约 1/3。而 WebP 从 Chrome 32 就有解码支持，远低于本
		     项目的最低内核门槛（`MIN_WEBVIEW_MAJOR_COMPAT = 74`），不构成新约束。

		   ⚠️ 源图是**彩色圆点风格**的二维码，`cv2.QRCodeDetector` 解不出来
		     （实测原图/裁剪/灰度/二值化/放大全 FAIL），换 `zxing-cpp` 立刻成功。
		     验证这类图的可扫性**必须用 zxing**，别因为 OpenCV 报 FAIL 就判定图片
		     坏了 —— 那是解码器对非标准模块形状的识别能力差异，不是图的问题。

		   可扫性验证方式（比「肉眼看没问题」可靠）：把 data URI 按真实布局
		     （视口 390 CSS px + `--force-device-scale-factor=3`）用 Chrome 渲染成
		     截图，再交给 zxing 解码。400/512/640/852 四档源图**全部解码成功**，
		     说明「内联 → WebP 解码 → CSS 缩到 240px → dpr=3 渲染」整条链路的
		     余量都够。 */
		const MP_CONTACT_NS = "dsh-android-mobile.contact";
		/** 群号：从二维码图里读出的群名「BooAI交流群」下方的数字。 */
		const MP_CONTACT_GROUP_NO = "1127652833";
		/** 二维码内联 data URI（WebP）。生成方式与可扫性验证见上方注释。 */
		const MP_CONTACT_QR = "data:image/webp;base64,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";
		const MP_CONTACT_ZH = {
			nav: "交流群",
			title: "交流群",
			intro: "遇到问题、想反馈 bug，或者想聊聊怎么用，都欢迎进群。群里也会同步新版本和已知问题。",
			groupLabel: "群号",
			copy: "复制群号",
			copied: "已复制",
			copyFailed: "复制失败",
			qrAlt: "交流群二维码",
			howTitle: "怎么加群",
			how1: "用另一台设备上的手机 QQ「扫一扫」扫上面的二维码。",
			how2: "或者打开 QQ → 右上角「+」→ 加好友/群 → 搜群号，粘贴上面的群号。",
			how3: "群号是纯数字，长按选不中（本插件禁用了全局选中），所以用右边的按钮复制。",
			notesTitle: "反馈问题的小建议",
			note1: "说清楚「哪一步、期望什么、实际什么」，比只说「用不了」好定位得多。",
			note2: "设置页里「运行日志」能导出会话记录，贴上来最有用。",
			note3: "顺带说下机型和系统版本（例如「小米 13 / MIUI 14」），很多问题只出在特定机型上。",
		};
		const MP_CONTACT_EN = {
			nav: "Community group",
			title: "Community group",
			intro: "Found a bug, or want to talk about how to use it? Join the group. New releases and known issues are posted there too.",
			groupLabel: "Group ID",
			copy: "Copy ID",
			copied: "Copied",
			copyFailed: "Copy failed",
			qrAlt: "Community group QR code",
			howTitle: "How to join",
			how1: "Scan the QR code above with QQ's scanner on another device.",
			how2: "Or open QQ → \"+\" at the top right → Add friend/group → search the group ID, then paste the ID above.",
			how3: "The ID is digits only and cannot be selected (this plugin disables global text selection), so use the copy button.",
			notesTitle: "Tips for reporting issues",
			note1: "Describe \"which step, what you expected, what actually happened\" — far easier to locate than \"it doesn't work\".",
			note2: "The \"Session log\" section in Settings can export the conversation; attaching it helps most.",
			note3: "Mention your device and OS version (e.g. \"Xiaomi 13 / MIUI 14\") — many issues are model-specific.",
		};

		/**
		 * 「交流群」分节组件。
		 *
		 * 纯静态信息（无 bridge、无原生状态），唯一的有状态部分是复制按钮的
		 * 短暂反馈。布局沿用「特权权限 / 悬浮气泡」那套卡片风格。
		 *
		 * @param {object} props - 槽位注入面（只用 `t`）。
		 * @returns {object} React 元素。
		 */
		function mpContactSection(props) {
			const React = mpPrivReact;
			const S = MP_PRIV_S;
			const t = typeof props.t === "function" ? props.t : (key) => MP_CONTACT_ZH[key] ?? key;
			const [copyState, setCopyState] = React.useState("idle");
			const timerRef = React.useRef(0);

			React.useEffect(() => () => { if (timerRef.current) window.clearTimeout(timerRef.current); }, []);

			const doCopy = () => {
				mpCopyText(MP_CONTACT_GROUP_NO).then((ok) => {
					setCopyState(ok ? "done" : "failed");
					if (timerRef.current) window.clearTimeout(timerRef.current);
					// 2 秒后回到常态：文案在「复制群号 / 已复制」间切换，按钮宽度固定，
					// 不会让整行来回跳（与权限分节的复制按钮同一处理）。
					timerRef.current = window.setTimeout(() => setCopyState("idle"), 2000);
				});
			};

			const copyLabel = copyState === "done" ? t("copied")
				: copyState === "failed" ? t("copyFailed")
				: t("copy");

			return React.createElement("div", { style: S.section },
				React.createElement("h2", { style: S.heading }, t("title")),
				React.createElement("p", { style: S.intro }, t("intro")),

				React.createElement("div", { style: S.card },
					React.createElement("div", { style: S.qrWrap },
						React.createElement("img", {
							style: S.qrImg,
							src: MP_CONTACT_QR,
							alt: t("qrAlt"),
							// 二维码是装饰性内容，不让长按弹出「保存图片」菜单：
							// 保存下来也没用（用户要的是加群，不是存图）。
							draggable: false,
						}),
					),
					React.createElement("div", { style: S.row },
						React.createElement("span", { style: S.rowLabel }, t("groupLabel")),
						React.createElement("span", { style: S.mpGroupNo }, MP_CONTACT_GROUP_NO),
					),
					React.createElement("div", { style: S.cmdRow },
						React.createElement("button", {
							type: "button",
							style: S.cmdBtn,
							onClick: doCopy,
						}, copyLabel),
					),
				),

				React.createElement("details", { style: S.fold },
					React.createElement("summary", { style: S.foldHead }, t("howTitle")),
					React.createElement("div", { style: S.foldBody },
						React.createElement("ol", { style: S.steps },
							[t("how1"), t("how2"), t("how3")].map((line, i) =>
								React.createElement("li", { style: S.step, key: i }, line)),
						),
					),
				),

				React.createElement("details", { style: S.fold },
					React.createElement("summary", { style: S.foldHead }, t("notesTitle")),
					React.createElement("div", { style: S.foldBody },
						React.createElement("ul", { style: S.notes },
							[t("note1"), t("note2"), t("note3")].map((line, i) =>
								React.createElement("li", { style: S.note, key: i }, line)),
						),
					),
				),
			);
		}

		/**
		 * 把「交流群」注册进设置页一级导航。
		 *
		 * 与前两个分节同一套接缝（`settings.section`），所以不需要碰导航 DOM。
		 *
		 * @param {object} ctx - 浏览器插件上下文。
		 */
		function mpRegisterContactSection(ctx) {
			if (mpPrivReact === null) {
				try { mpPrivReact = require("react"); } catch (e) {
					console.warn("[dsh-android-mobile] require('react') 失败，跳过交流群分节:", e && e.message);
					mpPrivReact = null;
				}
			}
			if (!mpPrivReact) return;
			try {
				ctx.inject(["slots", "locale"], (scope) => {
					// ⚠️ 必须用 `scope` 上的服务（原因见 mpRegisterPrivilegedSection 的注释）。
					const t = scope.locale.bind(MP_CONTACT_NS);
					scope.effect(
						() => scope.locale.register(MP_CONTACT_NS, { zh: MP_CONTACT_ZH, en: MP_CONTACT_EN }),
						"dsh-android-mobile: 交流群字典",
					);
					scope.slots.inject("settings.section", () => scope.slots.register({
						name: "settings.section",
						id: "dsh-android-contact",
						// 紧跟在「悬浮气泡」（26）之后 —— 反馈入口排在设置页最后，
						// 与「关于 / 帮助」的惯例一致。
						order: 27,
						label: () => t("nav"),
						locale: MP_CONTACT_NS,
					}, mpContactSection));
				});
			} catch (e) {
				console.warn("[dsh-android-mobile] 注册交流群分节失败:", e && (e.stack || e.message || e));
			}
		}

		/* ══ 修复 67：回车 = 换行（移动端）══

		   背景：官方 composer 的键盘映射（`dsh-client-ui-conversation/lib/client.js` 的
		   `registerComposerKeymap`）是**桌面语义**：

		     | 按键            | 官方行为 | 出处 |
		     |-----------------|----------|------|
		     | `Enter`         | **发送** | L16704-16716 `handlers.submit(...)` |
		     | `Shift+Enter`   | 换行     | L16706 `if (event?.shiftKey === true) return false;`（放行给编辑器默认行为） |
		     | `Ctrl/Cmd+Enter`| 发送     | L16715 `submit(event.ctrlKey === true \|\| event.metaKey === true)` |

		   桌面上有 Shift 键，所以「Enter 发送、Shift+Enter 换行」是合理默认。但**手机
		   软键盘没有 Shift**（中文输入法更不会给），于是用户想在多行里换行时**做不到**：
		   一按回车就发出去了，长 prompt 只能挤成一行。这是纯移动端才有的一类缺口。

		   做法：在**捕获阶段**拦下编辑器上的 Enter，把它改写成 `Shift+Enter` 派发回去。
		   这样官方那条 `shiftKey === true → return false` 的分支就命中了，编辑器执行
		   默认行为（插入换行），而**不需要**去改引擎源码、也不依赖任何私有 API。

		   ⚠️ **必须拦在 composer 编辑区上，不能拦 document**：官方把键盘映射注册在
		     编辑器实例上（`editor.registerCommand`），拦到别处既无效、又会误伤设置页
		     等其它输入框（那些地方回车该是「确认」）。
		   ⚠️ **必须检查 `isComposing` / `keyCode === 229`**：中文输入法的候选框确认也
		     发 Enter。不排除的话，**选词会被当成换行**，用户根本没法打中文。官方
		     `isComposingEvent()` 用的就是这两个判据（外加 composition 结束 10ms 内的宽限）。
		   ⚠️ **要用真实焦点上的元素派发**，且 `cancelable: true`、`composed: true`：
		     官方这条命令是挂在该编辑器实例的 root 上的。
		   ⚠️ **不要试图 preventDefault 后手工插 `\n`**：编辑器是 Lexical（`editor.update()`
		     那套），直接改 DOM 会被它的模型覆盖掉。走「改写成 Shift+Enter」这条路，
		     等于让官方自己执行它本来就有的换行分支。 */
		const MP_ENTER_SEL = "[data-composer-input]";

		function setupEnterAsNewline() {
			if (!mpIsMobile()) return () => {}; // 修复 40：仅移动端（必须返回空清理函数）

			/** 中文/日文输入法正在组字时，回车属于「上屏」而不是「换行」。 */
			function mpIsComposing(e) {
				if (!e) return false;
				if (e.isComposing === true || e.keyCode === 229) return true;
				// 输入法上屏后紧跟的那次回车，浏览器可能已经把 isComposing 置回 false。
				// 用「最近一次 compositionend 的时间」兜一下（官方宽限 10ms，这里给 80ms：
				// 手机上输入法的时序比桌面慢，实测偶有超出 10ms 的情况）。
				return mpLastCompositionEnd > 0 && (Date.now() - mpLastCompositionEnd) < 80;
			}

			const onKeyDown = (e) => {
				try {
					if (e.key !== "Enter") return;
					// 已经是「换行」或「发送」的变体（用户外接键盘按了 Shift/Ctrl/Cmd）：
					// 一律放行给官方，保持外接键盘上的桌面习惯不变。
					if (e.shiftKey || e.ctrlKey || e.metaKey || e.altKey) return;
					if (mpIsComposing(e)) return;
					const ed = e.target && e.target.closest ? e.target.closest(MP_ENTER_SEL) : null;
					if (!ed) return;

					// 吃掉这一次 Enter，再补一次带 shiftKey 的 —— 官方把它当换行。
					e.preventDefault();
					e.stopPropagation();
					// ⚠️ 派发目标优先用**真实焦点元素**：官方那条命令注册在编辑器实例的
					//   root 上，而 `[data-composer-input]` 未必就是那个 root（可能只是
					//   它的祖先）。`dispatchEvent` 只向上冒泡、**不进入子元素**，若从祖先
					//   派发而监听在子元素上就永远收不到。焦点元素必然在编辑区内部，
					//   且 `bubbles: true` 会一路冒泡经过 root ⇒ 两种情形都覆盖到。
					let target = ed;
					try {
						const a = document.activeElement;
						if (a && a !== document.body && ed.contains(a)) target = a;
					} catch (err2) { /* 静默：拿不到焦点就用编辑区本体 */ }
					const synth = new KeyboardEvent("keydown", {
						key: "Enter", code: "Enter", keyCode: 13, which: 13,
						shiftKey: true, bubbles: true, cancelable: true, composed: true,
					});
					target.dispatchEvent(synth);
				} catch (err) { /* 静默：绝不打断输入 */ }
			};

			// 组字状态由编辑器自己维护，这里只需要知道「刚刚结束过一次组字」。
			const onCompositionEnd = () => { mpLastCompositionEnd = Date.now(); };

			document.addEventListener("keydown", onKeyDown, true);
			document.addEventListener("compositionend", onCompositionEnd, true);
			return () => {
				document.removeEventListener("keydown", onKeyDown, true);
				document.removeEventListener("compositionend", onCompositionEnd, true);
			};
		}

		/* ══ 修复 68：凭据输入框改用普通键盘（移动端）══

		   背景：官方把「API key / 凭据」输入框渲染成 `<input type="password">`：

		     · `dsh-client-ui-settings-models/lib/client.js` L1380 与 L1692
		       （模型配置页的两个变体：内置 provider 与自定义 provider）
		     · `dsh-web-frontend/dist` 里的通用凭据字段组件（`type:"password"`）

		   这是**桌面语义**：桌面上 `type=password` 只意味着「遮挡字符」，键盘还是
		   物理键盘，`Ctrl+V` 照常可用。但在 Android 上它有一个额外且不明显的后果 ——
		   **输入法会据此切换到「安全键盘」**（华为/小米/OPPO 的密码输入法），而
		   安全键盘**按设计屏蔽剪贴板**：既没有粘贴入口，长按也不出粘贴菜单。

		   而 API key 是一串 51 字符的 `sk-...`，**手抄不现实**，用户唯一的现实
		   做法就是从别处复制粘贴。于是「安全键盘」在这里不是安全增强，而是**把
		   这个功能变不可用**。这是纯移动端才有的一类缺口（同修复 67）。

		   做法：把 `type="password"` 改写成 `type="text"`，并移除 `autocomplete`。
		   这样输入法看到的是普通文本框 ⇒ 普通键盘 ⇒ 剪贴板可用。

		   ⚠️⚠️ **绝对不要为了保留圆点外观而补 CSS 遮蔽 —— 那会让整个修复失效。**
		     第一版正是这么干的（`input[data-mp-key="1"] { -webkit-text-security:
		     disc !important; }`），结果属性层面全对、行为层面全错。真机实测：
		       焦点元素的 JS 属性： INPUT type=text  ac=(空)  pwdSel=0   ← 全对
		       输入法实际收到的：   inputType=0xe1                        ← 仍是密码型
		     `0xe1` = TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_WEB_PASSWORD。
		     根因是 blink 的 `Element::ShouldObscureValue()` 只看
		     `computedStyle.textSecurity != none` —— **CSS 遮蔽本身就是触发条件**，
		     `type=password` 只是经 UA 样式表拿到同一条声明。
		     也就是说：**「普通键盘」与「圆点遮蔽」在 Android WebView 上不可兼得。**
		     用户要的是前者（「跟普通输入框一样」「能粘贴」），所以本插件让 API key
		     明文可见，不做任何遮蔽补偿。详见「修复 68 的教训」那段 CSS 注释。

		   ⚠️ **必须全局扫描，不能只认某个选择器**：这些输入框**没有稳定的
		     class / data 属性**可锚（class 是 CSS Modules 的哈希名，随构建变），
		     且分布在**两个独立编译的包**里。唯一稳定的判据就是 `type="password"`
		     这个属性本身 —— 它也正是输入法所用的判据。
		   ⚠️ **用 MutationObserver 兜住懒挂载与重渲染**：设置页是按需打开的
		     对话框，`apply()` 执行时它还不存在。同时观察 `type` 属性变化，
		     万一 React 把它设回去也能立刻改回来。
		   ⚠️ **只改 type，不动 value / onChange**：官方那两个框是**受控组件**
		     （`value={keyDraft}`），字符本身由 React 状态持有。改 type 不碰状态，
		     React 下次渲染时该属性与虚拟 DOM 一致（React 侧一直是 "password"、
		     未变化 ⇒ 不会重设），因此不会打架。
		   ⚠️ **不改 autoComplete**：`new-password` 只影响浏览器的密码管理器提示，
		     与输入法（安全键盘）无关，动了反而可能引入自动填充弹窗。
		   ⚠️ 只在移动端生效（修复 40 的惯例）：桌面端 `type=password` 完全正常，
		     没有「安全键盘屏蔽剪贴板」这个问题，不该被改。
		   ⚠️ `data-mp-key` 标记保留：它不再驱动任何 CSS 遮蔽，只作为「本插件
		     处理过这个框」的标记，便于排查时一眼认出（探针就靠它定位焦点元素）。 */
		const MP_KEY_ATTR = "data-mp-key";

		/**
		 * ══ 修复 68 的真正生效点：在**原型层**拦掉 `type=password` ══
		 *
		 * 为什么必须有这一层 —— 这是本修复第二版才搞明白的关键机制：
		 *
		 * 官方那两个凭据输入框除了 `type="password"` 之外，还带 **`autoFocus: true`**
		 * （`dsh-client-ui-settings-models/lib/client.js` L1699 与 L2552）。React 把
		 * 元素插入 DOM 的**同一帧**就让它获得焦点，WebView 在那时按「密码框」建立了
		 * 输入连接并把 `inputType` 定成 `TYPE_TEXT_VARIATION_WEB_PASSWORD`(0xe1)。
		 *
		 * 而 **WebView 不会为已经建立的连接重算 `inputType`**。所以「先让 password
		 * 存在、再由 MutationObserver 改成 text」这条路**必然失败**：改属性发生在
		 * 聚焦之后，输入法早就拿到密码型类型了。
		 *
		 * 真机实测（Huawei CDY-TN90 / Android 10，`dumpsys input_method` 读实时值）：
		 *
		 *   只做事后改属性（第一版行为）：  DOM type=text、ts=none  →  inputType=0xe1 ✗
		 *   本层拦截，password 从未出现：   DOM type=text、ts=none  →  inputType=0x80a1 ✓
		 *
		 * 顺带排除掉的几个嫌疑（都实测过，**不是**触发条件，别再去改它们）：
		 *   · `data-mp-key` 标记本身 —— 带它的纯净 input 仍是 0x0
		 *   · CSS `-webkit-text-security` —— 已确认可读，焦点框实测 `ts=none`
		 *   · blur + focus 重建连接 —— 实测刷不动 `inputType`
		 *
		 * 做法：重定义 `HTMLInputElement.prototype` 的 `type` 访问器与 `setAttribute`，
		 * 把写入的 `"password"` 就地换成 `"text"`。React 完全无感 —— 它写 password、
		 * 读回来的也是它自己以为的 password（React 的受控逻辑只认自己的 props，
		 * 不读 DOM 属性），而真实 DOM 里**从头到尾没有出现过 password**。
		 *
		 * ⚠️ 只拦 `type`，**不碰 value / onChange / required**：官方那两个框是受控组件，
		 *    字符由 React 状态持有，本层不介入数据流。
		 * ⚠️ `autocomplete` 仍交给下面的 MutationObserver 摘（它不影响输入连接，
		 *    早一步晚一步都行）。
		 * ⚠️ 只在移动端生效（修复 40 的惯例）：桌面端没有安全键盘问题，不该被改。
		 */
		function installPasswordTypeShim() {
			if (!mpIsMobile()) return () => {};
			try {
				const proto = HTMLInputElement.prototype;
				const desc = Object.getOwnPropertyDescriptor(proto, "type");
				if (!desc || typeof desc.set !== "function") return () => {};
				const origSet = desc.set;
				const origGet = desc.get;
				const origSetAttribute = proto.setAttribute;

				Object.defineProperty(proto, "type", {
					configurable: true,
					enumerable: desc.enumerable,
					get() { return origGet.call(this); },
					set(value) {
						// 只改写入值，不改语义：调用方以为写的是 password，DOM 拿到 text。
						origSet.call(this, String(value).toLowerCase() === "password" ? "text" : value);
					},
				});
				proto.setAttribute = function (name, value) {
					if (String(name).toLowerCase() === "type" && String(value).toLowerCase() === "password") {
						return origSetAttribute.call(this, "type", "text");
					}
					return origSetAttribute.call(this, name, value);
				};
				// 只还原这两个，不做通用 hook 复原：本函数只改这两处。
				return () => {
					try {
						Object.defineProperty(proto, "type", desc);
						proto.setAttribute = origSetAttribute;
					} catch (err) { /* 静默 */ }
				};
			} catch (err) {
				return () => {}; // 拦截失败就退化成下面 MutationObserver 的事后补救
			}
		}

		function setupPlainKeyboardForCredentials() {
			if (!mpIsMobile()) return () => {}; // 修复 40：仅移动端（桌面无安全键盘）

			// ⚠️ **顺序要紧**：拦截器必须在任何 React 渲染之前装上，否则第一帧的
			//    `type=password` + autoFocus 又会把输入连接定成密码型（见上面长注释）。
			const disposeShim = installPasswordTypeShim();

			/** 把一处密码输入框降级成普通文本框 + 打标记。返回是否真的改了。 */
			function soften(el) {
				if (!el || el.nodeType !== 1 || el.tagName !== "INPUT") return false;
				// ⚠️ **先看现在是不是密码框，不是就一个字节都不动。**
				//   ① 安全：只处理真正的密码框，页面里普通的 `input[type=email]`
				//      （登录页之类）不被误标。
				//   ② 幂等：改成 text 之后再调用会走到这里直接返回，不产生任何写入 ⇒
				//      MutationObserver 不会自激。
				//   装了拦截器之后，`type=password` 几乎不会出现（写进去就变 text），
				//   所以这里通常不命中 —— 它现在主要是**兜底**：万一 React 用
				//   `innerHTML`/`cloneNode` 等绕过 setter 的路径造出密码框，
				//   或拦截器因环境差异没装上，仍然能补救。
				if ((el.getAttribute("type") || "").toLowerCase() !== "password") return false;
				try {
					el.setAttribute(MP_KEY_ATTR, "1");
					// 主触发点：`type=password` ⇒ WebView 的 inputType 变成
					// TYPE_TEXT_VARIATION_PASSWORD ⇒ 输入法切安全键盘。
					el.setAttribute("type", "text");
					// 次要触发点：`autocomplete="new-password"` 是 Chromium 的 autofill
					// 提示（AutofillManager 会把它作为 hint 交给输入法），部分国产输入法
					// 据此也判成密码字段。**整个移除属性**而不是改成 "off" —— 后者会让
					// Chromium 置上 `IME_FLAG_NO_PERSONALIZED_LEARNING`，又是一条「敏感
					// 字段」信号，等于换一个可能触发安全键盘的理由。
					if (el.hasAttribute("autocomplete")) el.removeAttribute("autocomplete");
					return true;
				} catch (err) { return false; } // 静默：绝不打断设置页
			}

			/**
			 * 兜底扫描 + 摘 `autocomplete`。
			 *
			 * ⚠️ 装了拦截器之后，这里**不再**是主路径（password 压根不会出现），
			 *    它的职责变成两件：
			 *      ① 万一拦截器没生效（环境差异）时的补救；
			 *      ② 摘掉 `autocomplete="new-password"` —— 拦截器不管这个属性。
			 *    所以判断条件放宽成「带 data-mp-key 或 autocomplete 的输入框」，
			 *    而不是只认 `type=password`。
			 */
			function tidy(el) {
				if (!el || el.nodeType !== 1 || el.tagName !== "INPUT") return false;
				let changed = false;
				try {
					if ((el.getAttribute("type") || "").toLowerCase() === "password") {
						changed = soften(el) || changed;
					}
					// autocomplete 只有"像密码框"的输入框才需要摘 —— 判据是官方那两处
					// 都会带 `new-password`，普通输入框的 autocomplete（如 "email"）
					// 是页面正常语义，不该动。
					if (el.getAttribute("autocomplete") === "new-password") {
						el.removeAttribute("autocomplete");
						changed = true;
					}
				} catch (err) { /* 静默 */ }
				return changed;
			}

			function scan(root) {
				if (!root || typeof root.querySelectorAll !== "function") return;
				try {
					tidy(root); // root 自身可能就是那个输入框（addedNodes 分支）
					root.querySelectorAll("input").forEach(tidy);
				} catch (err) { /* 静默 */ }
			}

			scan(document);

			const obs = new MutationObserver((records) => {
				for (const r of records) {
					// attributes 分支：React 把 type / autocomplete 设回去时立刻改回来。
					// tidy 自身幂等（无需改动时不产生写入）⇒ 不会自激循环。
					if (r.type === "attributes") { tidy(r.target); continue; }
					for (const n of r.addedNodes) scan(n);
				}
			});
			// subtree + childList 抓懒挂载（设置页是按需打开的对话框）；
			// attributes 抓 React 回写。
			obs.observe(document.documentElement || document.body, {
				childList: true, subtree: true,
				attributes: true, attributeFilter: ["type", "autocomplete"],
			});

			return () => {
				try { obs.disconnect(); } catch (err) { /* 静默 */ }
				disposeShim();
			};
		}

		/* ══ 「运行日志与诊断」分节 ══

		   需求（用户原话）：做一个"极其强壮、极其详细"的日志系统，两个入口 ——
		   一个在设置界面，一个在崩溃界面（加载界面）；崩溃与加载界面"不管任何
		   时候都要有这个日志接口"。

		   本分节就是**设置界面那个入口**。它自己不显示日志内容，只做三件事：

		     ① 让用户知道日志系统存在（默认没人会去翻文件系统）
		     ② 给出摘要（多少条、几条错误、几份崩溃现场、几份待上传）——
		        用户看到"3 条错误、2 份崩溃现场"才知道值得点进去
		     ③ 一键拉起**原生**日志界面

		   ⚠️ **为什么不把日志界面做成页面插件**：因为它要能在「页面根本打不开」
		     的时候用。插件没激活、前端 bundle 挂了、引擎没起来 —— 这三种情况
		     恰好都是最需要看日志的时候，而那时页面插件自己也活不了。
		     所以真正的界面在原生侧（`LogViewerActivity`），本分节只是个入口。

		   ⚠️ 另一个入口在**启动/崩溃浮层**上（`activity_main.xml` 的
		     `@+id/overlayLogs`），它常驻显示、不依赖任何 Web 层。两条路合起来
		     覆盖「界面能开」与「界面开不了」两种情况。 */

		const MP_LOGS_NS = "dsh-android-mobile.logs";
		const MP_LOGS_ZH = {
			nav: "运行日志与诊断",
			title: "运行日志与诊断",
			intro: "应用、引擎、网页三方面的日志都会记下来。出问题时点进去看，或一键打包发给开发者 —— 包里带着设备信息、崩溃现场与自动结论。",
			statusTitle: "当前状态",
			statusHasError: "有错误",
			statusHasCrash: "有崩溃现场",
			statusOk: "正常",
			statusUnknown: "读取中",
			rowTotal: "日志条数",
			rowWarn: "警告",
			rowError: "错误",
			rowCrash: "崩溃现场",
			rowPending: "待上传",
			rowLastExit: "上次退出",
			open: "打开日志界面",
			refresh: "刷新",
			showDetails: "查看详情",
			hideDetails: "收起详情",
			nextHasCrash: "上次留下了崩溃现场，建议点「打开日志界面」导出诊断包发给开发者。",
			nextHasError: "日志里有错误记录，可以点进去看看。",
			nextOk: "一切正常。遇到问题随时回来这里。",
			noBridge: "当前环境没有原生桥（DSHAndroid 不可用），无法打开日志界面。",
			uploadOn: "已配置日志服务器（可在日志界面一键上传）",
			uploadOff: "尚未配置日志服务器（只能导出到本地）",
			howTitle: "怎么用",
			how1: "点「打开日志界面」—— 那是原生界面，**引擎没起来时也能用**。",
			how2: "界面里可以按级别（警告/错误）和来源（应用/引擎/网页/崩溃）筛选，也能搜关键字。",
			how3: "「导出诊断包」生成一个 zip：含设备信息、三份日志、崩溃现场和一份自动结论（diagnosis.json）。",
			how4: "「上传给开发者」直接发出去；失败会留在本地，下次启动自动重试。",
			notesTitle: "几点说明",
			note1: "日志会脱敏：token、api key、密钥一类的内容写盘前就被替换掉了。",
			note2: "日志不记录会话正文，只记元信息（哪个会话、什么时间、什么动作）。",
			note3: "启动/崩溃界面上也有一个「查看日志」按钮 —— 应用打不开时从那里进。",
			note4: "「清空」会删掉已落盘的日志与崩溃现场，不可恢复。",
		};
		const MP_LOGS_EN = {
			nav: "Logs & diagnostics",
			title: "Logs & diagnostics",
			intro: "Logs from the app, the engine and the web UI are all recorded. When something breaks, open the log viewer or bundle everything into one file for the developer — it includes device info, crash scenes and an automatic diagnosis.",
			statusTitle: "Current status",
			statusHasError: "Errors present",
			statusHasCrash: "Crash scene present",
			statusOk: "OK",
			statusUnknown: "Reading",
			rowTotal: "Log entries",
			rowWarn: "Warnings",
			rowError: "Errors",
			rowCrash: "Crash scenes",
			rowPending: "Pending upload",
			rowLastExit: "Last exit",
			open: "Open log viewer",
			refresh: "Refresh",
			showDetails: "Show details",
			hideDetails: "Hide details",
			nextHasCrash: "A crash scene was recorded. Open the log viewer and export a diagnostic bundle to send to the developer.",
			nextHasError: "There are errors in the log — open the viewer to inspect them.",
			nextOk: "All clear. Come back here whenever something misbehaves.",
			noBridge: "No native bridge (DSHAndroid unavailable) in this environment — cannot open the log viewer.",
			uploadOn: "Log server configured (one-tap upload in the viewer)",
			uploadOff: "No log server configured yet (export to a local file instead)",
			howTitle: "How to use it",
			how1: "Tap \"Open log viewer\" — that is a native screen and works even when the engine never started.",
			how2: "Filter by level (warnings/errors) and by source (app/engine/web/crash); search by keyword.",
			how3: "\"Export bundle\" produces a zip with device info, three logs, crash scenes and an automatic diagnosis (diagnosis.json).",
			how4: "\"Upload\" sends it straight away; if it fails the bundle stays locally and is retried on next launch.",
			notesTitle: "Notes",
			note1: "Logs are redacted: tokens, API keys and secrets are replaced before anything is written to disk.",
			note2: "Message bodies are not logged — only metadata (which session, when, what action).",
			note3: "The launch/crash overlay also has a \"View logs\" button, for when the app itself won't open.",
			note4: "\"Clear\" deletes on-disk logs and crash scenes permanently.",
		};

		/**
		 * 「运行日志与诊断」分节组件。
		 *
		 * 布局沿用「特权权限」「悬浮气泡」那套**结论优先**的结构：
		 * head 卡给状态徽章 + 一条「下一步」+ 直达按钮，明细折进 `<details>`。
		 *
		 * ⚠️ 摘要数据来自 `DSHAndroid.logSummary()`（同步返回 JSON 字符串）。
		 *   它刻意只读内存缓冲 + 一个小 JSON，**不读磁盘日志** —— 设置页每次
		 *   打开都会调它，读几 MB 的文件算行数会明显卡顿。
		 *
		 * @param {object} props - 槽位注入面（只用 `t`）。
		 * @returns {object} React 元素。
		 */
		function mpLogsSection(props) {
			const React = mpPrivReact;
			const S = MP_PRIV_S;
			const t = typeof props.t === "function" ? props.t : (key) => MP_LOGS_ZH[key] ?? key;
			const [state, setState] = React.useState({ phase: "loading" });
			const [showDetails, setShowDetails] = React.useState(false);

			/**
			 * 从原生读一次摘要。
			 *
			 * ⚠️ 桥**可能不存在**（非 App 环境、或桥被清理），先查再调 ——
			 *   直接调会抛 `TypeError: Cannot read properties of undefined`。
			 *
			 * ⚠️ **必须补读一次**（`again=true` 的那趟）：gecko 版的 `logSummary()`
			 *   读的是**快照缓存**（native messaging 只能异步，见 content.js），
			 *   第一次拿到的可能是旧值；那边在读取时顺带请求原生重推快照，
			 *   所以隔一拍再读就是新值。system 版是同步真值，补读无副作用。
			 */
			const refreshRef = React.useRef(null);
			const refresh = React.useCallback((again) => {
				const bridge = window.DSHAndroid;
				if (!bridge || typeof bridge.logSummary !== "function") {
					setState({ phase: "ready", noBridge: true });
					return;
				}
				try {
					const raw = bridge.logSummary();
					const o = typeof raw === "string" ? JSON.parse(raw) : (raw || {});
					setState({
						phase: "ready",
						noBridge: false,
						total: o.total | 0,
						warn: o.warn | 0,
						error: o.error | 0,
						crash: o.crash | 0,
						pending: o.pending | 0,
						lastExit: typeof o.lastExit === "string" ? o.lastExit : "",
						uploadConfigured: !!o.uploadConfigured,
					});
				} catch (e) {
					console.warn("[dsh-android-mobile] 读取日志摘要失败:", e && e.message);
					setState({ phase: "ready", noBridge: true });
				}
				// ⚠️ 用 `!== true` 而不是 `!again`：本函数也挂在事件监听上，
				//   那时第一个参数是 event 对象（truthy），会把补读吃掉。
				if (again !== true) {
					setTimeout(() => { if (refreshRef.current) refreshRef.current(true); }, 300);
				}
			}, []);
			refreshRef.current = refresh;

			React.useEffect(() => { refresh(); }, [refresh]);

			/**
			 * 从日志界面回来后刷新。
			 *
			 * ⚠️ 用户点「清空」或「上传」是在**另一个 Activity** 里做的，本页拿不到
			 *   任何回调。只能监听 `visibilitychange` —— 不加的话用户清空完回来
			 *   看到的还是旧数字，会以为没生效（悬浮气泡分节踩过同一个坑）。
			 */
			React.useEffect(() => {
				const onVisible = () => { if (!document.hidden) refresh(); };
				document.addEventListener("visibilitychange", onVisible);
				window.addEventListener("focus", onVisible);
				return () => {
					document.removeEventListener("visibilitychange", onVisible);
					window.removeEventListener("focus", onVisible);
				};
			}, [refresh]);

			const open = () => {
				const bridge = window.DSHAndroid;
				if (bridge && typeof bridge.openLogs === "function") {
					try { bridge.openLogs(); } catch (e) {
						console.warn("[dsh-android-mobile] 打开日志界面失败:", e && e.message);
					}
				}
			};

			if (state.phase === "loading") {
				return React.createElement("div", { style: S.section },
					React.createElement("h2", { style: S.heading }, t("title")),
					React.createElement("p", { style: S.intro }, t("statusUnknown")),
				);
			}

			const noBridge = state.noBridge;
			const crash = state.crash || 0;
			const error = state.error || 0;
			// 严重度按「有没有崩溃现场」> 「有没有错误」排 —— 崩溃现场是硬证据，
			// 错误可能只是第三方路由的一次 429。
			const tone = crash > 0 ? MP_PRIV_TONE.off : (error > 0 ? MP_PRIV_TONE.warn : MP_PRIV_TONE.on);
			const badgeText = crash > 0 ? t("statusHasCrash") : (error > 0 ? t("statusHasError") : t("statusOk"));

			let nextText = t("nextOk");
			if (noBridge) nextText = t("noBridge");
			else if (crash > 0) nextText = t("nextHasCrash");
			else if (error > 0) nextText = t("nextHasError");

			const dot = (ok) => React.createElement("span", {
				style: { ...S.dot, background: ok ? MP_PRIV_TONE.on.color : MP_PRIV_TONE.off.color },
			});
			const row = (label, ok, value) => React.createElement("div", { style: S.row, key: label },
				dot(ok),
				React.createElement("span", { style: S.rowLabel }, label),
				React.createElement("span", { style: S.rowValue }, value),
			);

			return React.createElement("div", { style: S.section },
				React.createElement("h2", { style: S.heading }, t("title")),
				React.createElement("p", { style: S.intro }, t("intro")),

				React.createElement("div", { style: S.card },
					React.createElement("div", { style: S.cardHead },
						React.createElement("h3", { style: S.cardTitle }, t("statusTitle")),
						React.createElement("span", {
							style: { ...S.badge, color: tone.color, background: tone.bg },
						}, badgeText),
					),
					React.createElement("div", { style: S.next },
						React.createElement("span", { style: S.nextText }, nextText),
						!noBridge ? React.createElement("div", { style: S.actions },
							React.createElement("button", { style: S.btnPrimary, onClick: open }, t("open")),
						) : null,
					),
					React.createElement("div", { style: S.actions },
						React.createElement("button", { style: S.btn, onClick: refresh }, t("refresh")),
						React.createElement("button", {
							style: S.btnQuiet,
							onClick: () => setShowDetails((v) => !v),
						}, showDetails ? t("hideDetails") : t("showDetails")),
					),
					showDetails ? React.createElement("div", { style: S.details },
						row(t("rowTotal"), (state.total || 0) > 0, String(state.total || 0)),
						row(t("rowWarn"), (state.warn || 0) === 0, String(state.warn || 0)),
						row(t("rowError"), (state.error || 0) === 0, String(state.error || 0)),
						row(t("rowCrash"), crash === 0, String(crash)),
						row(t("rowPending"), (state.pending || 0) === 0, String(state.pending || 0)),
						row(t("rowLastExit"), true, state.lastExit || "—"),
						row(t("rowTotal"), state.uploadConfigured, state.uploadConfigured ? t("uploadOn") : t("uploadOff")),
					) : null,
				),

				React.createElement("details", { style: S.fold },
					React.createElement("summary", { style: S.foldHead }, t("howTitle")),
					React.createElement("div", { style: S.foldBody },
						React.createElement("ul", { style: S.notes },
							[t("how1"), t("how2"), t("how3"), t("how4")].map((line, i) =>
								React.createElement("li", { style: S.note, key: i }, line)),
						),
					),
				),

				React.createElement("details", { style: S.fold },
					React.createElement("summary", { style: S.foldHead }, t("notesTitle")),
					React.createElement("div", { style: S.foldBody },
						React.createElement("ul", { style: S.notes },
							[t("note1"), t("note2"), t("note3"), t("note4")].map((line, i) =>
								React.createElement("li", { style: S.note, key: i }, line)),
						),
					),
				),
			);
		}

		/**
		 * 把「运行日志与诊断」注册进设置页一级导航。
		 *
		 * 与特权权限/悬浮气泡同一套接缝（`settings.section`），不需要碰导航 DOM。
		 *
		 * ⚠️ `order` 取 28：排在「悬浮气泡」（26）与「交流群」（27）之后，
		 *   也就是设置列表最末。日志是**排障入口**，不该占据日常设置的显眼位置，
		 *   但必须存在。
		 *
		 * @param {object} ctx - 浏览器插件上下文。
		 */
		function mpRegisterLogsSection(ctx) {
			if (mpPrivReact === null) {
				try { mpPrivReact = require("react"); } catch (e) {
					console.warn("[dsh-android-mobile] require('react') 失败，跳过日志分节:", e && e.message);
					mpPrivReact = null;
				}
			}
			if (!mpPrivReact) return;
			try {
				ctx.inject(["slots", "locale"], (scope) => {
					// ⚠️ 必须用 `scope` 上的服务（原因见 mpRegisterPrivilegedSection 的注释）。
					const t = scope.locale.bind(MP_LOGS_NS);
					scope.effect(
						() => scope.locale.register(MP_LOGS_NS, { zh: MP_LOGS_ZH, en: MP_LOGS_EN }),
						"dsh-android-mobile: 日志字典",
					);
					scope.slots.inject("settings.section", () => scope.slots.register({
						name: "settings.section",
						id: "dsh-android-logs",
						order: 28,
						label: () => t("nav"),
						locale: MP_LOGS_NS,
					}, mpLogsSection));
				});
			} catch (e) {
				console.warn("[dsh-android-mobile] 注册日志分节失败:", e && (e.stack || e.message || e));
			}
		}

		/** 最近一次组字结束的时间戳（0 = 从未）。见 setupEnterAsNewline。 */
		let mpLastCompositionEnd = 0;

		function apply(ctx) {
			mpCtx = ctx;
			mpSessionsCache = ctx.get("sessions") ?? null;
			mpRegisterPrivilegedSection(ctx);
			mpRegisterBubbleSection(ctx);
			mpRegisterLogsSection(ctx);
			mpRegisterContactSection(ctx);
			mpInstallPendingTrust();   /* 修复 51：越早越好 */
			mpWatchSessions(ctx);       /* 修复 51：等服务就绪 */
			injectStyles();
			const disposeKeyboardGuard = setupComposerKeyboardGuard();
			const disposeTriggerMenuDismiss = setupTriggerMenuAutoDismiss();
			const disposeKeepFocusGuard = setupKeepFocusGuard();
			const disposeEnterAsNewline = setupEnterAsNewline();   /* 修复 67 */
			const disposePlainKey = setupPlainKeyboardForCredentials();   /* 修复 68 */
			// 初始一轮
			document.querySelectorAll(".xzv4MW_timeEnd").forEach(trimMeta);
			processStatsLine();
			watchApproval();
			mpBuildShell();
			mpSyncDrawer();
			bindTitleRename();
			document.querySelectorAll('[role="dialog"][aria-modal="true"]').forEach(setupSettingsDialog);
			document.addEventListener("click", onNewSessionClick, true);
			document.addEventListener("click", onSessionRowClick, true);
			document.addEventListener("click", onPanelRowClick, true);   /* 修复 66 */
			const disposePressFeedback = setupPressFeedback();
			const disposeStatusBridge = mpInstallStatusBridge();
			const disposeTranscriptBridge = mpInstallTranscriptBridge();
			const disposeImePrejudge = setupImePrejudge();   /* 修复 65 */
			mo.observe(document.body, { childList: true, subtree: true, characterData: true });
			// 启动爆发：apply() 执行时 React 尚未挂载，而 observer 有 300ms 节流，
			// 导致顶栏会话标题出现偏慢。前 ~1.2s 内主动补 10 轮（120ms 间隔），
			// 之后完全交给 observer 常态驱动，不增加稳态开销。
			let mpBootBursts = 0;
			const mpBootBurstTimer = window.setInterval(() => {
				mpSyncMobileAttr();
				mpSyncDrawer();
				bindTitleRename();
				mpInstallPendingTrust();   /* 修复 51 兜底 */
				if (++mpBootBursts >= 10) window.clearInterval(mpBootBurstTimer);
			}, 120);
			// 审批徽章可能在侧栏抽屉渲染完成前不存在：轮询兜底（低频）
			const t = window.setInterval(watchApproval, 5000);
			/* 启动动画收尾信号：告诉原生「界面真的能用了」。
			 *
			 * ⚠️ 不能在这里直接报 —— apply() 返回时 React 还没挂载，`data-mobile`
			 *   属性与外壳按钮都还不存在，此刻报等于骗原生提前收起启动页，
			 *   用户会看到一块空白的 WebView。
			 *   真机实测：apply() 之后约 1.4~1.7s 界面才真正可用。
			 *
			 * 判据用「data-mobile 已打上 且 外壳已建」，与 CSS 生效条件同源：
			 * 这两件事成立就说明本插件的移动端适配已经挂好，界面可以交付了。 */
			mpSignalUiReady();

			return () => {
				mo.disconnect();
				document.removeEventListener("click", onNewSessionClick, true);
				document.removeEventListener("click", onSessionRowClick, true);
				disposePressFeedback();
				disposeStatusBridge();
				disposeTranscriptBridge();
				disposeImePrejudge();
				window.clearInterval(t);
				window.clearInterval(mpBootBurstTimer);
				disposeKeyboardGuard();
				disposeTriggerMenuDismiss();
				disposeKeepFocusGuard();
				disposeEnterAsNewline();   /* 修复 67 */
				disposePlainKey();   /* 修复 68 */
				document.removeEventListener("click", onPanelRowClick, true);   /* 修复 66 */
			};
		}

		exports.apply = apply;
		exports.inject = [];
		return module.exports;
	}
});