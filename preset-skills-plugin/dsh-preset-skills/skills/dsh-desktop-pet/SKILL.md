---
name: dsh-desktop-pet
description: 制作 DSH 移动端悬浮球外观 / 桌面宠物 / 精灵图动画时使用。内含悬浮球的落盘布局（bubble-frames 目录契约）、精灵图切片的精确规则、bubble 工具的全部字段与上限、运行状态到动作的映射、以及 AI 做宠物时最常犯的错。直接照此调用即可，不必再翻项目源码。
---

# 悬浮球外观与桌面宠物

## 0. 一句话结论

**AI 通过 `bubble` 工具改悬浮球，精灵图只传一张图 + 一组网格数字。**

```
bubble(action:"set", frameFiles:["/abs/sheet.png"], spriteRows:2, spriteCols:3, frameIntervalMs:120)
```

就这一条。**不要**自己把大图切成 N 张再传 N 个文件 —— 那是"轮播模式"，
能用但浪费：精灵图模式磁盘上只存 1 个文件，且改网格时不用重传图片。

## 1. 精灵图契约（照这个做，不会错）

### 1.1 网格怎么切

- 图片被当作 `rows × cols` 的**等分网格**，每格是一帧。
- 单格宽 = `图片宽 / cols`，单格高 = `图片高 / rows`（**整数除法，余数丢弃**）。
  所以**图片尺寸最好是 cols / rows 的整数倍**，否则右边缘或下边缘会被裁掉几个像素。
- 帧顺序是**行优先**：先从左到右走完第一行，再第二行。
  即 2×3 的网格里，第 0 帧是左上角，第 2 帧是第一行最右，第 3 帧是第二行最左。

### 1.2 网格数字的硬限制

| 项 | 限制 |
|---|---|
| `spriteRows` / `spriteCols` | 每个 0–32，且**两个都要 > 0** 才进精灵图模式 |
| 总帧数 `rows × cols` | ≤ **64** |
| 单张精灵图 base64 | ≤ 6 MB（约 4.5 MB 原图） |

⚠️ 只写 `spriteRows` 不写 `spriteCols`（或写 0）= **不进精灵图模式**，
   那张大图会被当成"只有一帧"，用户看到的就是一整张网格图缩在球里。
   两个数字必须成对出现且都 > 0。

### 1.3 图片从哪来

- 参数是**绝对路径**，由插件所在进程读盘：`/sdcard/...` 下的图可以直接用。
- 允许扩展名：`.png` `.jpg` `.jpeg` `.webp` `.gif`。
- 用户发来的附件先在工作区里找到它的落盘路径，再把路径给 `frameFiles`。

### 1.4 只改网格、不重传图

想让同一个精灵图换一种切法（比如 2×3 改成 3×2），**只传网格数字**：

```
bubble(action:"set", spriteRows:3, spriteCols:2)
```

原生侧会发现"没有新帧"，于是复用已有的整图字节、只重写 meta。
**别重新传一遍图**，那是白白多传几 MB、而且容易触发体积上限。

## 2. 落盘布局（排查问题时按这个看）

`filesDir` 下：

```
bubble-frames/                    ← 默认动作（老数据，零迁移）
  ├── 0.bin                       ← 普通模式：一帧一个文件
  ├── 1.bin
  ├── …
  └── meta.json
bubble-frames/                    ← 精灵图模式：整张图只占一个文件
  ├── 0.bin                       ← 就是那张网格大图
  └── meta.json
bubble-frames/sets/<动作名>/       ← 命名动作（桌面宠物用这个）
  ├── 0.bin
  └── meta.json
```

`meta.json` 的形状：

```json
{"count":6,"durations":[120,120,120,120,120,120],"sprite":{"rows":2,"cols":3},"loop":-1}
```

⚠️ **默认动作就是 `bubble-frames/` 本身**，不是 `sets/default/`。
⚠️ **命名动作必须落在 `sets/` 下**：根目录的 `0.bin` 已被默认动作占用，
   平铺会撞名，表现为"配了新动作，默认动作的帧被顶掉"。

写入是**整目录替换**（先写临时目录再改名），所以任何一步失败都不会留下半个帧集。

## 3. 桌面宠物：一个状态一套动作

### 3.1 五个运行状态（写死，多一个少一个都静默不生效）

`idle`（没在跑）· `thinking`（模型生成中）· `working`（工具/命令执行中）·
`waiting`（等你确认/输入）· `error`（上一轮失败）

### 3.2 两步配出宠物

```
bubble(action:"set",
  actions:{
    "idle":  {frameFiles:["/abs/idle.png"],  spriteRows:2, spriteCols:4, frameIntervalMs:300},
    "think": {frameFiles:["/abs/think.png"], spriteRows:2, spriteCols:4, frameIntervalMs:150},
    "work":  {frameFiles:["/abs/work.png"],  spriteRows:2, spriteCols:4, frameIntervalMs:100},
    "sad":   {frameFiles:["/abs/sad.png"],   spriteRows:2, spriteCols:4, frameIntervalMs:400}
  },
  actionMap:{"thinking":"think","working":"work","error":"sad"})
```

配完之后宠物**自己**随状态切换，不再需要每次驱动。
`actions` 里每个动作的规格与 §1 的字段完全一致，外加 `frameLoop`。

- 动作名：只允许 `a–z 0–9 - _`，≤ 24 字符，**不能中文**（会变成目录名）。
- 未映射的状态回落到默认动作 —— 所以通常不用给 `idle` 建映射。
- 传空 `frames: []` 删除那个动作；`action:"get"` 返回磁盘上**真实存在**的动作清单。

### 3.3 主动驱动（不落盘）

跑长任务时想让宠物即时有反应：

```
bubble(action:"state", runState:"working")
bubble(action:"state", runState:"idle")
```

`action:"state"` **不保存任何设置**，只影响"现在演什么"。
`pinAction` 则会落盘（钦定一个动作，忽略 actionMap）；`pinAction:""` 回到自动。
**能用 `state` 就别用 `pinAction`** —— 后者会给用户留下一个改不回来的状态。

## 4. 上限（超了会明确报错，不会静默）

| 项 | 上限 | 超了会怎样 |
|---|---|---|
| 单个动作帧数 | 64 | 截断（工具会说明） |
| 单个动作 base64 总量 | 6 MB | 明确报错，要求压图或减帧 |
| **一次调用的全部动作** base64 总量 | 12 MB | 明确报错 |
| 动作数量 | 24 | 明确报错 |
| 动作名长度 | 24 字符 | 明确报错 |

⚠️ 6 MB / 12 MB 这两条**是防内存爆的，不是防磁盘的**：帧以 base64 进 JSON，
   原生侧要一次性 `readText()` 整个文件。超了会让 App 被系统杀掉，
   **症状是"悬浮球突然消失、什么都没发生"**，极难归因。
   所以：一次只配 2–3 个动作，图片先压到 256×256 上下。

## 5. AI 做宠物最常犯的四个错

1. **把精灵图切好再逐帧传** —— 浪费传输量，且很容易撞 6 MB 上限。
   用 `spriteRows`/`spriteCols` 传一张图。
2. **只给一个网格数字** —— 不进精灵图模式，球里显示整张网格图。
3. **配完不检查可见性**：默认 `state:"auto"` 时，**悬浮球只在 App 退到后台才可见**。
   用户正盯着 App 看，却说"我什么都没看到" —— 这是**预期行为不是失败**。
   要当场展示效果，先 `bubble(action:"set", state:"collapsed")` 把它钉在前台可见。
4. **用中文动作名** —— 被白名单拦掉。用 pinyin/英文。

## 6. 验收

1. `bubble(action:"get")` 读回当前外观与**磁盘上真实存在的 actions 列表**，确认写进去了。
2. `adb logcat -d -s DSH:I` 里有
   `悬浮球动作帧集已更新: <动作名> = N 帧（精灵图 R×C）`。
3. **人眼看**：`state:"collapsed"` 钉到前台，看它有没有在动、切得对不对。
   动画是否流畅、切帧是否错位是视觉判断，日志替代不了。

## 7. 边界（知道就好，别越界）

- 悬浮球是**独立进程里的一个窗口**，工具通过文件信箱与它通信（写 `$DSH_HOME/bubble-cmd.json`，
  原生 250 ms 轮询）。所以单次调用有 4 秒等待上限；超时会明确返回"可能已生效也可能没有，
  请用 `action:"get"` 复查"，**这时不要当成功**。
- 非法字段值会**回落成当前值**而不是报错，未知字段被忽略 —— 所以打错字不会让球消失，
  但也不会生效。改完务必 `get` 复查。
