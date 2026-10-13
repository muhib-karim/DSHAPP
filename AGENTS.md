# AGENTS.md — DSH Android 客户端

原生 Android App：内置 Termux 环境跑一个 Node，在 Node 上跑 DSH 引擎，然后用 WebView
加载 DSH 自带的 Web GUI。**不自绘聊天界面**——UI 完全复用官方前端，只在窄屏下由内置插件
做移动端适配。

```
APK
 ├─ assets/bootstrap-aarch64.zip   Termux bootstrap（bash/dpkg/apt + 64 个 .so）
 ├─ assets/node-runtime.bin        Termux 官方 arm64 node 26.4.0 + 6 个补缺 .so
 ├─ assets/engine.bin              DSH 引擎 @deepseek-ai/dsh@0.2.0-rc.2（node_modules）
 ├─ assets/node-addon-android-arm64.zip   自编译的原生插件（官方无 android 变体）
 ├─ assets/node-addon-system-android-arm64.zip  自编译的 flock 绑定（同上）
 ├─ assets/linkcompat.bin          link()/linkat() 语义转译层（LD_PRELOAD）
 ├─ assets/keyinject.bin            内置号池注入层（LD_PRELOAD，密钥在 TLS 层换上，见 ⑮）
 ├─ assets/boosandbox.bin          LD_PRELOAD 写拦截器（沙箱的强制层）
 ├─ assets/boo-sandbox-run.bin     沙箱 runner：把 bwrap 方言翻译成拦截器配置
 ├─ assets/ripgrep-android-arm64.zip   官方 linux-arm64 静态 rg，补 @vscode/ripgrep 缺的 android 包
 ├─ assets/sharp-wasm32.zip        sharp 的 wasm32 变体（官方无 android 预编译二进制）
 ├─ assets/node-pty-android-arm64.zip  自编译的 node-pty 绑定（官方 prebuilds 无 android）
 ├─ assets/pnpm-android-arm64.zip  pnpm 11.28.3 纯 JS 版，解到 $PREFIX/lib/pnpm
 ├─ assets/mobile-ui.zip           移动端 UI 适配插件（dsh-android-mobile）
 ├─ assets/adb-wireless-arm64.zip  Termux 官方 adb（无线调试配对要 TLS-PSK，Node 的 tls 不支持）
 ├─ assets/dsh-android-shell.mjs   uid 2000 执行通道（直连 adbd）
 ├─ assets/shell-tool.zip          uid 2000 特权工具插件（注册 `shell` 工具）
 ├─ assets/bubble-tool.zip         悬浮球控制工具插件（注册 `bubble` 工具，见 ⑫.3）
 ├─ assets/pet-bridge.zip          桌面宠物状态桥插件（订阅 `session/event`，见 ⑫.4）
 ├─ assets/uiagent-tool.zip        UI 调试工具插件（注册 `ui` 工具，见 ⑫.5）
 ├─ assets/logs-tool.zip           日志工具插件（注册 `logs` 工具，见 ⑫.6）
 ├─ assets/ui-probe.js             UI 探针（页面世界，**不走转译**，见 ⑫.5）
 ├─ assets/webview-polyfill.js     旧 WebView 内核兼容垫片（补**运行时 API**）
 └─ assets/webview-compat.zip      按 chrome74 降级编译的前端 bundle（补**语法**，见 ⑯）

运行期（filesDir 下）
 ├─ usr/            $PREFIX —— Termux 环境（含 bin/pnpm）
 ├─ engine/         引擎解包目录（含 .stamp 版本戳）
 ├─ home/.dsh/      DSH_HOME：配置、凭据、会话、profiles/web/
 │   ├─ mobile-ui/dsh-android-mobile/  移动端 UI 插件（**刻意放在 pnpm 管辖之外**）
 │   ├─ shell-tool/dsh-android-shell/  uid 2000 特权工具插件（同上，刻意在 pnpm 之外）
 │   ├─ bubble-tool/dsh-android-bubble/  悬浮球控制工具插件（同上，见 ⑫.3）
 │   ├─ pet-bridge/dsh-android-pet/      桌面宠物状态桥插件（同上，见 ⑫.4）
 │   ├─ bubble-cmd.json               悬浮球命令信箱：插件写、原生 250ms 轮询读（见 ⑫.3）
 │   ├─ bubble-result.json            同上，回执方向（原生写、插件读）
 │   ├─ bubble-state.json             运行状态信箱：插件单向写、原生读完即删（见 ⑫.4）
 │   ├─ uiagent-tool/dsh-android-uiagent/  UI 调试工具插件（同上，刻意在 pnpm 之外，见 ⑫.5）
 │   ├─ ui-cmd.json                   UI 命令信箱：插件写、原生 120ms 轮询读（见 ⑫.5）
 │   ├─ ui-result.json                同上，回执方向（原生写、插件读）
 │   ├─ logs-tool/dsh-android-logs/   日志工具插件（同上，刻意在 pnpm 之外，见 ⑫.6）
 │   ├─ logs-cmd.json                 日志命令信箱：插件写、原生 200ms 轮询读（见 ⑫.6）
 │   └─ logs-result.json              同上，回执方向（原生写、插件读）
 ├─ logs/                             日志与诊断（见 ⑫.6）
 │   ├─ app.log / app.log.1           应用日志（同步 + 每行 flush，1MB 轮转，留一代）
 │   ├─ engine.log / web.log          引擎 stdout / 网页 console（异步缓冲）
 │   ├─ events.jsonl                  事件时间线（启动→引擎就绪→渲染→崩溃）
 │   ├─ boot-state.json               上次是否正常退出（ApplicationExitInfo 要 API 30，用不了）
 │   ├─ crash/<时间>-<类别>.txt        崩溃现场（独立配额 8MB / 20 份，不轮转）
 │   ├─ device-id                     安装期随机 UUID（匿名指纹，清日志时**保留**）
 │   ├─ export/                       手动导出的诊断包
 │   ├─ pending/                      待上传的诊断包（下次启动自动重试）
 │   └─ failed/                       上传失败（3 次后落这里，不再重试）
 ├─ bubble-frames/                    悬浮球默认动作的帧集（见 ⑫.4）
 │   └─ sets/<动作名>/                各命名动作的帧集（见 ⑫.4）
 ├─ dsh-android-shell.mjs  uid 2000 执行通道（每次启动覆盖写）
 ├─ liblinkcompat.so  link 转译层（每次启动从资产覆盖写）
 ├─ libboosandbox.so  沙箱写拦截器（每次启动从资产覆盖写）
 ├─ boo-sandbox-run   沙箱 runner（每次启动从资产覆盖写）
 └─ workspace/      引擎工作目录（agent 文件操作的根）
```

## 两条硬约束（改动前必读，改错不报错但真机必炸）

### ① 包名必须恰好 15 字符

`applicationId` / `namespace` = `com.dsh.android`（15 字符）。

Termux 官方二进制把 `/data/data/com.termux/files`（27 字节）烤进 ELF 的 `.dynstr`，
`TermuxBootstrap.patchElfInPlace()` 只能做**等长原地覆盖**，替换串
`/data/data/<包名>/` = 12 + N 字节，等于 27 ⇒ **N = 15**。

长度不等时该函数直接 `return 0` **静默跳过**，不抛异常；症状是运行时
`CANNOT LINK EXECUTABLE` 或 `dpkg: Permission denied`。改包名必须同步改
`TermuxBootstrap.ELF_ROOT_REPLACEMENT`，两处字符数必须一致。

> `app/build.gradle.kts` 注释里提到的 `termux-kit/tools/verify-kit.ps1` **不在本仓库**，
> 不必去找。校验方式就是数字符。

### ② targetSdk 必须 ≤ 28

**不能升到 29+。** Android 10（API 29）起，targetSdk ≥ 29 的应用不允许 `execve()`
自身私有目录里的文件（W^X / SELinux `untrusted_app` 域禁止 `app_data_file`
`execute_no_trans`），而 node / bash / dpkg 全都装在 `filesDir` 下的 `$PREFIX` 里，
一升级就全线 Permission denied。Termux 官方 App 同样停在 28。

**代价**：只能侧载，无法上架 Google Play。

## 构建与验证

⚠️ **本工程有两个 Web 内核 flavor（`system` / `gecko`），构建任务名带 flavor**，
产物也落在带 flavor 的子目录里。细节见 ⑦.7。

```powershell
# 构建（workdir = 本目录）
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"

.\gradlew.bat :app:assembleSystemDebug --console=plain
# 产物：app\build\outputs\apk\system\debug\app-system-debug.apk（约 138 MB）

.\gradlew.bat :app:assembleGeckoDebug --console=plain
# 产物：app\build\outputs\apk\gecko\debug\app-gecko-debug.apk（约 224 MB）

.\gradlew.bat :app:assembleDebug --console=plain   # 两个都出
```

环境：AGP 8.8.2 / Kotlin 2.0.21 / Gradle 8.13 / JDK 17 / compileSdk 35 / minSdk 26。

- **必须显式设 `JAVA_HOME`**，否则取到系统默认 JDK。
- 工程路径含中文（`1-项目代码\安卓开发`），靠 `gradle.properties` 里的
  `android.overridePathCheck=true` 放行，别删。
- 构建需经代理（`gradle.properties` 里的 `127.0.0.1:7897`）。
- ⚠️ **gecko 包约 224 MB**（GeckoView 的 arm64 native 库就占 ~90 MB），比 system 版
  大 86 MB。这是「内核随包分发」的必然代价，不是配置错误。

### 发行版（release）

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"

.\gradlew.bat :app:assembleSystemRelease --console=plain
# 产物：app\build\outputs\apk\system\release\app-system-release.apk（已签名）

.\gradlew.bat :app:assembleGeckoRelease --console=plain
# 产物：app\build\outputs\apk\gecko\release\app-gecko-release.apk（已签名）

.\gradlew.bat :app:assembleRelease --console=plain   # 两个都出
```

⚠️ **两个 release 包的 `applicationId` 不同**（`com.dsh.android` / `com.dsh.webview`），
所以**可以同装一台机器**、各自独立的数据目录。但**同一个 flavor 下 debug 与 release
会互斥**（共用 applicationId、签名不同）—— 换装要先卸载 ⇒ 数据全丢，自用机保持 debug 不动。

**debug 是自用包、release 是发给别人的包，两把钥匙互相独立。**

- 签名凭据是仓库根的 `keystore.properties`（指向 `dsh-release.jks`），
  **两者都不入库**（`.gitignore` 已覆盖），`app/build.gradle.kts` 读它配
  `signingConfigs.release`。**文件不存在时自动跳过签名**，产物退化成
  `app-release-unsigned.apk` —— 别人 clone 后就是这个状态，`assembleDebug`
  完全不受影响。
- ⚠️ **这把钥匙是发行版的终身身份，发出去就不能换。** 换签名 ⇒ 老用户撞
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE` ⇒ 只能卸载重装，而卸载会清掉 `filesDir`
  下的整个 Termux 环境与 `$DSH_HOME`（会话、凭据、工作区全没）。
  **`dsh-release.jks` 与 `keystore.properties` 必须一起离线备份**，丢了同样发不出更新。
- 侧载**不需要买 CA 证书**，`keytool` 自签即可（Android 只校验前后一致，不校验链）。
  当前证书 `CN=DSH Android, O=DSH, C=CN`，RSA 4096，有效期 10000 天；
  `apksigner verify` 显示走 **v2 方案**（`minSdk 26` 用不上 v1）。
- ⚠️ **`lintVitalRelease` 的 `ExpiredTargetSdkVersion` 会因 `targetSdk = 28` 直接判
  release 构建失败**（报 "Google Play requires that apps target API level 33 or higher"）。
  已在 `app/build.gradle.kts` 的 `lint {}` 里**只关掉这一条**——本 App 只能侧载、
  不上架 Play，该检查在这里无意义——其余 lint 检查保持生效，别改回去。
  根因是硬约束②，**不要试图靠升 `targetSdk` 来"修"它**。
- ⚠️ debug 与 release **共用 `applicationId`**，同一台设备上只能存在一个；从 debug
  换装 release（或反之）会因签名不同而要求先卸载 ⇒ 数据全丢。自用机保持 debug 不动。

#### 发布到夸克网盘

发行包的对外分发走**夸克网盘**，目标是这个目录：

```
夸克网盘 / jiulingShare / DSH APP
```

里面已有的包用 `DSH<versionName>.apk` 命名（如 `DSH0.1.0.apk`）。**同名会冲突，
所以每次发布都在版本号后面加个后缀**，例如 `DSH0.1.0-pool.apk`（内置号池版）、
`DSH0.1.0-fixport.apk`。后缀随意，能区分就行。

##### 两个内核版本一起发

从 0.2.6 起有 **system / gecko 两个 flavor**，所以一次发布要传**两个包**，
后缀必须能区分是哪一个：

| flavor | 上传时改成的名字 | 说明 |
|---|---|---|
| system | `DSH<versionName>.apk` | 老包沿用原命名，绝大多数人装这个 |
| gecko | `DSH<versionName>-webview.apk` | 内置 Web 内核版，体积大一倍 |

⚠️ **两个包互不冲突、可以同时装**（applicationId 不同），所以**不能**把其中一个
挪进「历史版本」—— 它们不是新旧关系，是**并列的两个版本**。根目录里同时留住两个。

**必须走 Git 自带的 bash**（Windows 自带的 `bash.exe` 是 WSL，环境不对）：

```powershell
$bash = "C:\Program Files\Git\bin\bash.exe"
$skill = "/c/Users/XIAOPAN/.claude/skills/quarkclouddrive"

# 1) 先查目标目录的 FID（⚠️ 每次都要现查，见下）
& $bash -lc "cd $skill && node scripts/quark-drive.cjs search --keyword 'DSH APP' --search-type dir 2>&1"

# 2) 把包拷成要发布的名字（upload 用的是本地文件名，所以先改名再传）
Copy-Item app\build\outputs\apk\release\app-release.apk "$env:TEMP\DSH0.1.0-pool.apk" -Force

# 3) 上传（--parent-fid 填上一步查到的 FID）
& $bash -lc "cd $skill && node scripts/quark-drive.cjs upload '/c/Users/XIAOPAN/AppData/Local/Temp/DSH0.1.0-pool.apk' --parent-fid '<FID>' 2>&1" |
  Select-Object -Last 4
```

成功的收尾行长这样（`fullPath` 就是落地位置，**以此为准**）：

```json
{"code":0,"msg":"成功","data":{"fileNames":["DSH0.1.0-pool.apk"],"successCount":1,
 "instantUpload":true,"fullPath":"夸克网盘/jiulingShare/DSH APP"},"action":"upload","type":"result"}
```

⚠️ **`--parent-fid` 不能省略，也不能硬编码。** 省略会直接失败：

```
{"code":14001,"msg":"参数错误: [upload dir blank]","action":"upload","type":"result"}
```

而 FID **每次查询都在变**（实测同一个「DSH APP」目录，三次拿到三种前缀，只有
竖线后面的后缀 `|JkamYKaMIaE` 稳定）。所以**每次上传前重新 `search` 一次拿当前
FID**，不要从上一次的输出里复制。

⚠️ **`--parent-fid 0` 是根目录**（实测可用）。只有明确要传到根目录时才用它 ——
发行包**不该**传根目录，那是放错位置。

⚠️ **这个 CLI 没有删除命令**（`--help` 里 25 个命令逐个看过，没有 delete/rm/trash）。
**传错位置只能去夸克 App 或网页端手动删**。所以上传前先确认 `--parent-fid` 是对的 ——
这条踩过一次（包落到了根目录）。

其他实测要点：

- **进度是 NDJSON 刷屏**：一次上传打几百行 `{"msg":"进行中",...}`，必须
  `| Select-Object -Last 4` 收尾，别把全量倒出来（132 MB 那次刷了 185 KB 日志）。
- **秒传**：同内容文件已存在时 `instantUpload: true`，几秒就完（第二次传同一个包就是
  这样），所以重复上传不浪费流量。
- **账号**：昵称 `小强`，容量 20 TB 级。`get-user-info` 可验授权；它失败才说明授权
  失效（需 `login --token <授权码>` 重新授权），单条命令失败不算。
- ⚠️ **只能在 Windows 本机跑**（依赖 node）。Termux / Android 端没有 node，用不了。

#### ⚠️ 发布完必须整理目录：旧版挪进「历史版本」

`DSH APP` 根目录**只留最新一个包**，被它取代的旧包一律移进子目录
`DSH APP/历史版本/`。目录里那个 `保存此文件夹持续更新中.txt` 是分享说明，
不是包，别动。

**这一步是发布的一部分，不是可选收尾。** 漏做的后果是根目录堆着一串版本、
分享链接的访客分不清该下哪个。

```powershell
# 1) 列出根目录，拿到「旧包」与「历史版本」两个 FID
#    ⚠️ 每次都要现查：FID 前缀每次都变，只有竖线后的后缀稳定
& $bash -lc "cd $skill && node scripts/quark-drive.cjs browse --parent-fid '<DSH APP 的 FID>' --all 2>&1"

# 2) 移动（fids 是位置参数，目标目录用 --target-fid）
& $bash -lc "cd $skill && node scripts/quark-drive.cjs move '<旧包的 FID>' --target-fid '<历史版本的 FID>' 2>&1"
```

成功时 `move_path` 会回显落地位置，**以此为准**：

```json
{"code":0,"msg":"成功","data":{"fids":["~1E2xIos…|Z7KnYr6cf84"],
 "targetFid":"~1gZvlvk…|H_y2_NbMY0Q",
 "move_path":"夸克网盘/jiulingShare/DSH APP/历史版本"},"action":"move","type":"result"}
```

**什么算「旧包」**：同一次发布里如果因为返工传了第二个包（如 `DSH0.2.3.apk`
→ `DSH0.2.3-icons.apk`），根目录留**最后上传的那个**，先前那个进历史版本 ——
即使两者 `versionName` 相同。

⚠️ `move` 是**移动**不是复制，根目录里那一条会消失；移动完回查两个目录确认
（根目录少一个、历史版本多一个）。

⚠️ 实测 `历史版本` 目录的 FID 后缀是 `|H_y2_NbMY0Q`、`DSH APP` 是
`|JkamYKaMIaE`，但**前缀每次查询都变，所以仍然必须现查**，不能拿后缀凑 FID。

```powershell
# 安装 + 启动（adb 在 %LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe）
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb logcat -c ; adb shell am start -n com.dsh.android/.MainActivity
```

**「构建成功」不等于「改动生效」。** 用日志确认（TAG 恒为 `DSH`）：

```powershell
adb logcat -d -s DSH:I
```

关键行：

| 日志 | 含义 |
|---|---|
| `已安装移动端 UI 插件 dsh-android-mobile <版本> (<N> 字节)` | 插件已解包 |
| `已注入 WebView 兼容补丁: webview-polyfill.js` | polyfill 已注入 |
| `已安装原生插件 node-addon-require-builtin-android-arm64` | 原生插件已就位 |
| `已安装 system 插件 @deepseek-ai/node-addon-system-android-arm64` | flock 绑定已就位 |
| `已放行 flock 的 android 平台: flock.js` | 平台白名单已补 android |
| `已安装 ripgrep @vscode/ripgrep-android-arm64 (<N> 字节)` | grep/glob 的搜索二进制已就位 |
| `已安装 sharp wasm 变体 (@img/sharp-wasm32, <N> 字节)` | 图片附件的图像处理已就位 |
| `已放行附件模块的祖先目录 fsync: dsh-attachment-local` | 附件发布的目录 fsync 已容错 |
| `已安装 node-pty android-arm64 绑定 (<N> 字节)` | 终端面板的 PTY 绑定已就位 |
| `已放行 subprocess-local 的 android 进程检查器: runner-launch-<hash>.js` | 终端后端的进程检查器已放行 android |
| `已安装 pnpm 11.28.3 (<N> 个文件, <M> 字节启动脚本)` | 插件管理器可用了 |
| `已解出沙箱 runner boo-sandbox-run (<N> 字节) + libboosandbox.so (<N> 字节)` | 沙箱组件已落盘 |
| `已写入沙箱 overlay: sandbox.patch.yml` | 沙箱 runner 已挂上 |
| `已解出 link 兼容层 liblinkcompat.so (<N> 字节)` | link 转译层已落盘（并会被 LD_PRELOAD） |
| `已还原目录选择器的起始目录（改由主目录下的入口链接提供）` | 老安装里「起点改 /sdcard」的补丁已撤回（见 ③.5） |
| `已建工作区入口: 手机存储 → /sdcard` | HOME 下的入口链接已就位（只首次启动打印，见 ③.5） |
| `Web 端口 3080 空闲，沿用引擎默认值` | 正常路径，端口与改动前一致（见 ⑭） |
| `Web 端口 3080 已被占用（多半是上一次残留的引擎进程），改由内核分配空闲端口` | 撞上残留进程，已改传 `--port 0`（见 ⑭） |
| `[engine] dsh web: http://127.0.0.1:3080/?token=...` | 引擎打印了带 token 的 URL |
| `引擎就绪: http://...` | App 解析到 URL |
| `WebView 加载完成: http://127.0.0.1:3080/` | 前端已加载 |

WebView 内的 `console.log` 也会转发到同一 TAG，前缀 `[web]`。

## android 平台适配（这是本项目反复出问题的地方）

**根本原因**：Node 在 Android 上 `process.platform === 'android'`（不是 `'linux'`），
而 DSH 官方的原生插件和平台判断都是按 `linux`/`darwin`/`win32` 三选一写的。
凡是遇到「官方只认 linux，但代码本身与 libc 无关」的地方，都要为 android 补一份。

前两处是**自编译 android-arm64 原生绑定 + 打平台白名单补丁**；第三处性质不同，见下。

| 插件 | 官方判断 | 症状 |
|---|---|---|
| `node-addon-require-builtin` | 无 android 平台包 | 启动即 `No usable native binding found` |
| `node-addon-system`（flock） | `flock.js` 白名单排除 android | 每轮回复「本轮运行失败 flock is not supported on android-arm64」 |

第二个的具体链路：`dsh-session-persistence-jsonl` 的 `SessionWriteLease.acquire()`
要拿一把会话写入锁（内核 flock），失败即抛 `SessionAlreadyOwnedError` → 回复失败。
但 bionic 的 `sys/file.h` 里就有 `flock(int,int)`，Android 支持 flock(2)，
所以**正解是编译绑定，不是把锁 stub 掉**。

补丁锚点写死在 `EngineManager.FLOCK_PLATFORM_GUARD`。引擎升级后若该文本不再匹配，
`patchFlockPlatform()` 会跳过并打日志（不会让启动失败），届时按新文本更新锚点即可。

⚠️ 别把 flock 改成 no-op。官方在浏览器 worker 里确实 stub 成直接成功，但那是**单进程**
场景（进程内写声明已经足够互斥）；本 App 是真多进程（引擎可能再 spawn 子进程），
stub 掉会失去跨进程排他，可能撕裂会话日志。

### ③ SELinux 不给 `link` 权限 —— 用 LD_PRELOAD 转译（不是改源码）

**这是第三处、也是唯一一处不能靠「补平台白名单」解决的适配**，因为它不是
「官方漏了 android」，而是 Android 的 SELinux 策略**主动**不给：

```
avc: denied { link } for comm="libuv-worker"
  name="session.v4.jsonl.zstd.<hex>.tmp"
  scontext=u:r:untrusted_app_27:s0  tcontext=u:object_r:app_data_file:s0
  tclass=file permissive=0
```

`untrusted_app(_27)` 对 `app_data_file` 没有 `link`，AOSP 为防沙箱数据外泄硬移除。
而 DSH 上游用硬链接做**独占原子发布**，并靠 `EEXIST` 判定「已被别人发布」：

```js
try { await link(staged, currentPath); }
catch (e) { if (e.code === 'EEXIST') return false; throw e; }  // ← EACCES 走这里
```

EACCES 不是 EEXIST，于是冒泡成「本轮运行失败 EACCES: permission denied, link ...」。

**真机 syscall 实测 —— 两个文件系统的 errno 不一样，这是本节的要害**：

| 调用 | app 私有目录（`app_data_file`） | `/sdcard`（FUSE / sdcardfs） |
|---|---|---|
| `link()` / `linkat()` | **EACCES** | **EINVAL**（node 进程内）／**EPERM**（裸 C 探针） |
| `rename()` | OK | OK |
| `renameat2(RENAME_NOREPLACE)` | **OK**（目标不存在则成功，已存在则 EEXIST 且不覆盖） | **EINVAL**（FUSE 不认这个 flag） |
| `symlink()` | OK | **EPERM** |
| `copyFile()` / `unlink()` / `mkdir()` / `fsync()` / `O_EXCL` 建文件 | OK | OK |

⚠️ **`/sdcard` 上的 `link()` 不是 EACCES**，这一点最初判断错了 —— 转译层因此**根本没被
触发**（触发条件只写了 EACCES/EPERM），用户把工作区设在 `/sdcard` 时 `write` 工具直接报
`EINVAL: invalid argument, link ...`。而 `bash`、`edit` 正常（`edit` 走 `dsh-atomic-write`
的纯 `rename`，不碰 link），所以症状只在 `write` 上。

⚠️ 同一个调用在 node 与裸 C 探针下 errno 不同（EINVAL vs EPERM），所以**两个都得接受**，
不能只认一个。

⚠️ 即使在 `/sdcard` 上触发了转译，`renameat2(RENAME_NOREPLACE)` 那一层**也会 EINVAL** ——
降级分支原来只认 `ENOSYS`，于是 EINVAL 原样冒泡，症状与「没触发」一模一样。两处都得改。

**为什么不能靠 JS 侧打补丁**：引擎用 `import { link } from "node:fs/promises"`（ESM 具名
导入）。实测 `--import` 预加载里改 `fsPromises.link` 得到
`named_import_is_patched = false`（default 属性改了，具名绑定没改）→ JS 侧此路不通。

**做法**：`app/src/main/jni/linkcompat.c` 编译成 `assets/linkcompat.bin`，
`EngineManager.ensureLinkCompat()` 解到 `filesDir/liblinkcompat.so`，启动 node 时
`LD_PRELOAD` 它。只 hook `link`/`linkat` 两个符号，**先试原生调用，只有被系统拒
（EACCES / EPERM / EINVAL）才转译**，其余 errno 原样返回。

⚠️ **EINVAL 必须在触发条件里**：`/sdcard` 上 `link()` 返回的是 EINVAL，漏了它转译
根本不会启动（这正是 `write` 工具在 `/sdcard` 工作区失效的根因）。三个 errno 都收，
是因为同一个调用在不同文件系统、不同调用方（node 的 libuv 线程 vs 裸 C）下报的不一样。

转译语义 = `link(from,to)` ≡ 「把 from 的内容复制到 to，且 to 必须原本不存在」，
三个性质都要保住：

- **独占**：`renameat2(RENAME_NOREPLACE)` 发布 → 目标存在时内核保证失败且不覆盖。
- **保源**：真 link 不消耗 `from`。**不能图省事换成 `rename()`** —— rename 会移走源，
  而 `dsh-attachment-local` 的 `publishImmutableAlias` 其 source 是 content-addressed
  持久对象，会被反复 link 到不同 ref（同文件 `lib/index.js` 第 690 与 708 行指向同一个
  object），第一次 rename 后第二次就 ENOENT。这是与「`O_EXCL` 占位 + `rename`」朴素写法
  的关键差别，那种写法会破坏数据。
- **原子**：先复制到**目标同目录**临时文件（保证同文件系统），再 rename 发布。

与真 link 的差异：inode 不同（复制而非共享）、空间翻倍。已逐处核对全部调用方，只依赖
EEXIST 语义与内容摘要校验，**没有任何一处依赖同 inode**。

验收（`lctest.c`，14 项断言全过）：独占、保源、内容一致、linkat 路径、ENOENT、目录 EPERM、
无残留临时文件、800KB 跨缓冲区完整性。真机 node 进程内 `fs.linkSync` 由 `EACCES` → 成功。

⚠️ `LD_PRELOAD` 会继承给 node spawn 的子进程（如 shell 工具）。本库只 hook
`link`/`linkat` 且先试原生，对系统二进制无害；但**将来要往 `LD_PRELOAD` 里追加别的库时，
务必确认不会破坏系统二进制**。

⚠️ `renameat2` 是 glibc 扩展，bionic **没有导出**，必须走 `syscall(__NR_renameat2, ...)`。
降级分支认 `ENOSYS | EINVAL | ENOTSUP`：前者是老内核（< 3.15）没有这个系统调用，
后两者是 **FUSE/sdcardfs 拒绝 `RENAME_NOREPLACE`** —— 也就是说在 `/sdcard` 上
**永远**走降级路径，那是正常路径而不是异常路径。

降级 = 「`O_EXCL` 占位 + `rename`」：独占性仍由 `O_EXCL` 保证，但两步之间被杀会留下
空占位文件，导致该文件名此后永久 `EEXIST`。这个窗口在 `/sdcard` 上是**真实存在**的
（`app_data_file` 上走不到这里，内核 4.14 支持 renameat2）。取舍依据：占位与 rename
之间只隔一次系统调用，且被杀的后果是「该文件名写不进去」，不是数据损坏 —— 相比
「`/sdcard` 工作区里 `write` 工具完全不可用」，代价明显更小。

⚠️ 降级路径的全部原语已在真机 `/sdcard` 上逐条验过（`O_EXCL` 建占位、`rename` 覆盖、
`fsync`/`fdatasync`/`futimens`、`chmod`、清理），不是推测。

⚠️ 真机验收（`/sdcard`、app 域、`LD_PRELOAD` 新库、node 进程内 `fs.linkSync`）：
独占新建 ✅、内容一致 ✅、**源文件保留** ✅、二次 link 正确报 `EEXIST` ✅、
目标目录不存在正确报 `ENOENT` ✅。

### ③.5 目录选择器只在**一棵**树里走 —— 用 HOME 下的入口链接接上手机存储

**症状**：点「选择工作区」，对话框停在 `filesDir/home`（app 私有目录），
既看不到手机上的文件夹，也**走不上去**（面包屑起点就是那里），
「选一个手机上的文件夹当工作区」在 App 里根本做不到。

**根因有两层**：

1. `dsh-host-directory-picker-auto` 在 Android 上判成 **`browse`** 后端（判定表：
   非 darwin/win32、非带 `DISPLAY` 的 linux ⇒ `browse`），其 `list()` 在没给路径时
   用 `homedir()` 当起点，而本 App 把引擎 `HOME` 设成 `filesDir/home`。
2. **更要紧的是**：Android 上手机存储与 app 私有目录**互不可达** —— app 域连
   `readdir("/")`、`readdir("/data")` 都是 EACCES（见 ⑨ 的实测表）。所以起点放在
   **哪一边，另一边就永远进不去**。

⚠️ **第一版修法是错的，别再走回去**：当时把起点直接改成 `/sdcard`，手机文件夹能选了，
但用户**再也进不了 app 私有目录**（默认工作区就在那儿）。真机反馈后推翻。

**做法**：起点**留在 HOME**，在 HOME 下建两个入口符号链接：

```
filesDir/home/手机存储  → /sdcard      选手机上的任意文件夹
filesDir/home/应用目录  → filesDir     选/建 app 私有目录里的文件夹
```

用户在对话框里看到「主目录」下的两个入口，点进去各走各的树，点面包屑上的
「主目录」就能回来 —— **有入口、也有向上导航**。

⚠️ **链接只能建在 app 私有目录这一侧**：`/sdcard` 上 `symlink()` 返回 **EPERM**
（FUSE/sdcardfs 不支持）。所以不能反过来把私有目录的入口放进手机存储。

⚠️ **browse 后端会跟随符号链接**：`directoryRow()` 对 `isSymbolicLink()` 的条目补一次
`stat()`，是目录就照常列为可进入的文件夹。真机实测两个链接都 `enterable=true`，
且经链接列目录（145 项）、建文件夹、写文件全部成功。

⚠️ 用户拒绝存储权限时「手机存储」那行 `stat()` 失败 ⇒ `directoryRow()` 返回 null ⇒
**该行不显示**（官方逻辑，不会崩），只剩「应用目录」可用。

⚠️ **`home` 字段从头到尾没被碰过**：客户端 `displayCrumbs()` 拿 `listing.home` 折叠
面包屑。把 `home` 改掉会让面包屑塌成一项，用户失去向上导航 —— 入口链接方案能成立，
正是因为起点回到了 `home`。

⚠️ **改这一处必须同时「撤回」老安装里已打进去的补丁**：引擎解包目录只在版本戳变化时
才重解包（`ensureExtracted` + `.stamp`），光改代码不还原的话，老设备会永远停在
`/sdcard` 起点上。`EngineManager.restoreDirectoryPickerStart()` 就是干这个的，
幂等判据是「打过补丁的文本在不在」。

⚠️ 涉及的两个常量（`PICKER_START_GUARD` = 官方原文，`PICKER_START_ANCHOR` = 旧补丁
文本）**两个都必须逐字保留**：少一个，老安装就撤不回来。

代码：`restoreDirectoryPickerStart()` + `ensurePickerRoots()`，调用点在 `start()` 里
`patchAttachmentDurability()` 之后。日志验收：

```
已还原目录选择器的起始目录（改由主目录下的入口链接提供）
已建工作区入口: 手机存储 → /sdcard
已建工作区入口: 应用目录 → /data/user/0/com.dsh.android/files
```

（第二、三行只在第一次启动时出现；之后链接已就位，幂等跳过。）

### ④ Android 没有内核级沙箱后端 —— 用自带 runner 保住写边界

**症状**：`bash` 工具完全不可用，报

```
sandbox mode "workspace-write" is requested but no sandbox backend is usable on
this host; refusing to run the command unconfined.
Install bubblewrap or run a Landlock-enforcing kernel...
```

`grep`/`glob` 也全废（那是另一处，见 ⑤）。AI 实测只能靠 `read` 硬啃整个文件。

**根因**：`dsh-sandbox-local` 按平台挑后端

```js
const PLATFORM_CHAINS = {
  linux:  ["bwrap", "landlock"],
  darwin: ["seatbelt"],
  win32:  ["windows-acl"],
};
```

Android 落不进任何一条，`confine()` 又是 **fail-closed** 的。设备实测：内核
**4.14.116**（Landlock 需 5.13+）、无 `bwrap`、无 `/sys/kernel/security/lsm`、
无 `/proc/sys/user/max_user_namespaces`，`unshare -U` 因缺 CAP_SYS_ADMIN 而 EPERM。

**做法**：该 provider 留了操作员口子 `Config.runnerCommand`。非空时**跳过平台探测**、
直接 `enforcement: "full"`，并按 bwrap 方言拼参数：

```
[ ...runnerCommand, ...bwrapProfileArgs(policy), "--", ...argv ]
```

`bwrapProfileArgs` 的实际内容（android 走 `workspace-write` 时）：

```
--ro-bind / /  --dev /dev  --unshare-pid  --proc /proc  --die-with-parent
--tmpfs /tmp   --bind <workspaceRoot> <workspaceRoot>
```

所以**不必改引擎源码**，只要提供一个听得懂这套参数的 runner。两个组件：

| 文件 | 作用 |
|---|---|
| `app/src/main/jni/boo-sandbox-run.c` | 解析上述方言 → 翻译成拦截器配置 → `execvp` 真实命令 |
| `app/src/main/jni/boosandbox.c` | `LD_PRELOAD` 写拦截器，在 libc 边界挡 allowlist 外的写 |

runner 的翻译规则：`--bind SRC DST` → 写白名单加一条（`--ro-bind`/`--dev-bind`
不授权写）；`--tmpfs` → 额外放行 `$TMPDIR` 与 `/tmp`；`--die-with-parent` →
`prctl(PR_SET_PDEATHSIG, SIGKILL)`；**未知 flag → exit 125（fail closed）**。
`nroots > 0` 即 `workspace` 模式，否则 `readonly`。它把 `BOO_SANDBOX_LIB`
**追加**进已有的 `LD_PRELOAD`，所以 link 转译层同时生效。

⚠️ **与真 bwrap 的差异**：`--unshare-pid` 无法实现（同样缺 CAP_SYS_ADMIN），
**PID 命名空间没有隔离**。文件写边界是完整的，那是本方案承诺的部分。
`--ro-bind / /` 也不做只读挂载，靠拦截器拦写。

⚠️ **拦截器的 deny 文案是接口**：引擎靠
`DENIAL_SIGNATURES.runnerCommand = ["read-only file system", "permission denied"]`
把失败归类成沙箱拒绝。改 `deny()` 的输出前先看 `boosandbox.c` 里那条注释。

overlay 由 `EngineManager.writeSandboxPatch()` 生成
（`$DSH_HOME/sandbox.patch.yml`），`runnerCommand` 指向 `filesDir/boo-sandbox-run`。
patch 是**整行替换 config**，不是合并 —— 要动这行时记得把需要的 config 一并重述。

⚠️ **`runnerFailureSignatures` 不能留空，留空会让整个沙箱服务注册失败。**
provider 构造函数有一对硬校验：

```js
if (runner.length > 0 && runnerFailureSignatures.length === 0)
  throw new Error("sandbox-local: runnerCommand requires at least one runnerFailureSignatures entry");
```

它映射到 `ConfinedArgv.runnerFailureRules[].fatalSignatures`，即「runner 自己挂了、
命令根本没跑起来」的 stderr 子串（与 bwrap 的 `"bwrap: "`、landlock 的
`"landlock-launcher: "` 同构）。填错的后果**不是降级而是整条服务链断裂**：

```
[engine] sandbox (@deepseek-ai/dsh-sandbox-local): Error: sandbox-local:
           runnerCommand requires at least one runnerFailureSignatures entry
[engine] dsh: warning: 4 entries did not activate
[engine] bash-sandbox: pending (waiting for service: sandbox)
[engine] permission:   pending (waiting for service: shell)
[engine] ptc-runtime:  pending (waiting for service: sandbox)
```

界面表现是**新建会话直接失败**：

```
新建会话失败：agent-preset/invalid: tool-bash (@deepseek-ai/dsh-tool-bash):
waiting for shell workflow-ptc (@deepseek-ai/dsh-workflow-ptc):
waiting for ptcRuntime tool-workflow (@deepseek-ai/dsh-tool-workflow):
waiting for workflowEngine
```

注意这条报错链的**尾**是 `workflowEngine`，容易被误导去查 workflow 相关包；
真正的**根**是第一行的 `sandbox` 行注册失败。**排查这类 `waiting for` 要先看
`did not activate` 那几行。**

当前填的是 `'boo-sandbox-run: '` —— `boo-sandbox-run.c` 的每条 fail-closed 路径
都用这个前缀。**改 runner 的输出前缀必须同步改这里。**

「沙箱拒绝」与「runner 失败」是两回事：前者靠 provider 内置的
`DENIAL_SIGNATURES.runnerCommand = ["read-only file system", "permission denied"]`
识别（`libboosandbox.so` 的 deny 输出正好含 `permission denied`），后者才是上面这个字段。

验收（`sbtest2.sh` 8/8 + runner 10/10）：workspace 内写放行、app 私有目录写被拒、
`/sdcard` 写被拒、`..` 与 `/proc/self/../..` 逃逸被拒、`/dev/null` 放行、读不受限、
`readonly` 下连 workspace 也拒、`$TMPDIR` 放行、退出码透传、未知 flag → 125。
`boosandbox.c` 从 Boo 的 kit 逐字节照搬后**只改了 allowlist 从单根变多根**
（`MAX_ALLOW_ROOTS 8` + `strtok_r` 切 `:`）。

### ⑤ `@vscode/ripgrep` 没有 android 平台包 —— 补一个

`grep`/`glob` 都由 `dsh-tool-fs-search` 经 `@vscode/ripgrep` 解析二进制来跑：

```js
const platformPkg = `@vscode/ripgrep-${process.platform}-${arch}`;  // android-arm64
require.resolve(`${platformPkg}/bin/${binaryName}`);                // ← 抛错
```

官方的 `optionalDependencies` 只列了 darwin/linux/win32 的 11 个变体，**没有 android**，
于是工具层报 `Error: glob could not start its search command (ripgrep launch failed)` /
`SearchError SEARCH_FAILED`。

**做法**：取官方 `@vscode/ripgrep-linux-arm64` 1.18.0 的 `bin/rg`，打成
`assets/ripgrep-android-arm64.zip`，`EngineManager.ensureRipgrep()` 解到
`engine/node_modules/@vscode/ripgrep-android-arm64/`（条目是 `bin/rg` + `package.json`，
**没有顶层目录**）。它能在 Android 上跑是因为**完全静态** —— `readelf -d` 无任何
`NEEDED`，不依赖 glibc。设备实测 `ripgrep 15.0.0`，`+pcre2`，`simd(compile):+NEON`。

⚠️ 从 APK 资产解出来的文件**没有执行位**，必须 `setExecutable(true, false)`，
否则报 `EACCES` 而不是「找不到」。

### ⑥ Termux 的 `$PREFIX/bin` 里没有 pnpm —— 而且不能用 pnpm 12

`plugin_manager` 的 `install_bundle` 靠 spawn `pnpm add` 装插件，而 Termux bootstrap
只带 dpkg/apt 那一套，**没有 pnpm/npm/npx/corepack**。症状是 `spawn pnpm ENOENT`。

实测后果值得记一笔：AI 因此**绕过包管理器**，把插件包手工塞进
`profiles/web/node_modules/`，并**直接编辑了用户的 `profiles/web/cordis.patch.yml`**
（留下 `cordis.patch.yml.bak-light-green-ui`）。

**做法**：`EngineManager.ensurePnpm()` 装 **pnpm 11.28.3 的纯 JS 版**
（npm 包 `pnpm`，解到 `$PREFIX/lib/pnpm`），再在 `$PREFIX/bin/pnpm` 写一个
`exec <node> <pkg>/bin/pnpm.mjs "$@"` 的启动脚本。

#### 为什么不是 `@pnpm/exe.android-arm64`（12.x）

那是**原生 Android 可执行文件**（`INTERP=/system/bin/linker64`，只 NEEDED
libc/libm/libdl），装上去 `pnpm --version` 也正常（→ 12.8.1），
**但它做不了任何写操作**：

```
ERR_PNPM_STORE_DIR_ACQUIRE_OPERATION_LOCK
  ╰─▶ Failed to acquire the store operation lock at
      ".../pnpm-store-operation-locks-<pid>/all-stores.lock": lock_shared() not supported
```

pnpm 12 是 Rust 重写的（pacquet），store 操作锁走 `File::lock_shared()`，
而 **Rust std 在编译期就把 Android 判为不支持**。

⚠️ **这不是内核不给锁**，别往 SELinux / flock(2) 方向查。设备实测同一路径下：

| 调用 | 结果 |
|---|---|
| `flock(fd, LOCK_SH)` | **0（成功）** |
| `flock(fd, LOCK_SH \| LOCK_NB)` | 0 |
| `flock(fd, LOCK_EX)` / `LOCK_UN` | 0 |
| `fcntl(fd, F_SETLK, F_RDLCK)` | 0 |

决定性证据是那个二进制的**动态符号表里只有 `fcntl`、根本没有 `flock`**：

```powershell
llvm-readelf --dyn-syms app\src\main\assets\pnpm-android-arm64.bin | Select-String 'flock|fcntl'
# → 0000000000000000  0 FUNC GLOBAL DEFAULT UND fcntl@LIBC   （仅此一条）
```

所以是编译期判定，**没有任何配置项能绕过去**。

⚠️ **上面这一整段是 12.8.1 的实测结论，12.10.1 已经不成立**（2026-10-10 复测，
CDY-TN90 / Android 10）。上游在这两个版本之间把锁换成了 flock 可用的实现：

| 判据 | 12.8.1（原文实测） | 12.10.1（复测） |
|---|---|---|
| 动态符号表里的 `flock` | **没有**（只有 `fcntl`） | **有**（`grep -c -a flock` 命中 1） |
| `pnpm add` 写操作 | `ERR_PNPM_STORE_DIR_ACQUIRE_OPERATION_LOCK` | **成功**，RC=0 |

复测证据（`@pnpm/exe.android-arm64@12.10.1`，位于
`$HOME/.local/share/pnpm/store/v11/links/@/pnpm/12.10.1/<hash>/node_modules/pnpm/pnpm`）：

```
$P add is-number   → RC=0，590ms，node_modules/is-number 落盘
$P add is-even     → RC=0，820ms，+5 包
pnpm add is-odd    → RC=0，793ms，+2 包（经 pnpm 11 自动切换后）
```

三次都没出现 `ERR_PNPM_STORE_DIR_ACQUIRE_OPERATION_LOCK`。store 仍写成 v11 格式。

⚠️ **这不改变本 App 的做法**：插件管理仍固定走 pnpm 11.28.3，理由是**确定性**
（11 是纯 JS，行为已在本项目逐条验过），不是因为 12 不能用。
⚠️ **也别据此就把 12 升上来** —— 上面的对比只覆盖「能不能写」，没覆盖
`nodeLinker`、`node_modules/.ignored` 驱逐行为、本地 `link:` 包这些本项目真正依赖的
语义。要升就按 ⑥ 的老办法重新逐项实测。

⚠️ **pnpm 12 会在特定目录里被自动下载并接管**，这一点值得知道：`pnpm init`（12.x 写的
package.json）会带上

```json
"devEngines": { "packageManager": { "name": "pnpm", "version": "12.10.1", "onFail": "download" } },
"packageManager": "pnpm@12.10.1"
```

之后在该目录跑**任何** pnpm 命令都会自动下载 12.10.1 并切过去（实测就是这样从
11.28.3 切走的），产物落在 `$HOME/.local/share/pnpm/store/`。用户在自己工作目录里
`pnpm init` 后就会遇到，**这是预期行为不是 bug**。
而 `$DSH_HOME/profiles/web/package.json` **没有这两个字段**（只有
`name`/`private`/`dependencies`/`dsh`，已实测确认），所以**插件安装永远走 11.28.3**。

#### 为什么 pnpm 11 可以

11.x 是最后一条**纯 JS** 主线：`dist/pnpm.mjs` 是 15.7 MB 的单文件 bundle，
锁走 JS 实现（`dist/pnpm.mjs` 里搜不到 `lock_shared`）。
真机实测（含本地路径包 + 真实 profile 的 `nodeLinker: hoisted` 布局）：

```
pnpm add <本地包>  →  exit=0，node_modules/@local/xxx 就位，
                      package.json 写入 "link:/data/..."
```

副作用：**体积从 46 MB 单文件变成 5.9 MB 的 zip**（454 个文件），APK 反而小了。

⚠️ **不要把 `bin/pnpm.mjs` 直接软链到 `bin/pnpm`**：Android 的 SELinux
不给 app 私有目录建符号链接（与 ③ 的 `link` 同一类限制），shebang 也不可靠。
写启动脚本，且 node 用**绝对路径**（不依赖 PATH）。

⚠️ **pnpm 会把 profile 的 `node_modules` 里任何"多余依赖"挪到 `node_modules/.ignored/`。**
这正是移动端 UI 插件**曾经**被驱逐的原因，现在已把它挪出 `node_modules` 规避（见
「移动端 UI 插件」一节的 ⚠️）。**不要**再把 App 注入的插件放进 profile 的
`node_modules`，也不要为此把它写进 `dependencies`。

⚠️ 这一处**必须等 Termux 环境就位之后再装**（目标是 `$PREFIX/bin`），
所以调用点在 `bootstrap.installNodeRuntime()` 之后，和其它 `ensureXxx` 不同。

## 资产版本戳（改了资产必须同步改代码常量）

| 改了什么 | 必须改 |
|---|---|
| `mobile-ui.zip` 内容 | `EngineManager.MOBILE_UI_VERSION`（现 `1.0.0-61`）**且**重跑 `tools/build-webview-compat.ps1` 时 bump `-CompatVersion`（现 `17`，见 ⑯） |
| `dsh-bridge/content.js` 内容 | `app/src/gecko/assets/dsh-bridge/manifest.json` 的 `version`（现 `1.0.13`），否则 GeckoView 继续用旧扩展（见 ⑦.7） |
| `node-runtime.bin` 内容 | `TermuxBootstrap.NODE_VERSION`（`26.4.0-2`） |
| `engine.bin` 内容 | `MainActivity.assetStamp`（`0.2.0-rc.2-1`） |
| `pnpm-android-arm64.zip` 内容 | `EngineManager.PNPM_VERSION`（`11.28.3`） |
| `adb-wireless-arm64.zip` | 无需改（判据是 `bin/adb` 非空 + 完整性哨兵 `.extracted`，见 ⑪.1） |
| `node-addon-android-arm64.zip` | 无需改（只查文件存在且非空） |
| `node-addon-system-android-arm64.zip` | 无需改（只查文件存在且非空） |
| `ripgrep-android-arm64.zip` | 无需改（只查文件存在且非空） |
| `node-pty-android-arm64.zip` | 无需改（只查文件存在且非空） |
| `linkcompat.bin` | 无需改（每次启动覆盖写） |
| `keyinject.bin` | 无需改（每次启动覆盖写；换密钥池必须重新跑 `tools/build-keyinject.ps1`，见 ⑮） |
| `boosandbox.bin` / `boo-sandbox-run.bin` | 无需改（每次启动覆盖写） |
| `dsh-android-shell.mjs` | 无需改（每次启动覆盖写） |
| `shell-tool.zip` | 无需改（每次启动解包覆盖写） |
| `bubble-tool.zip` | 无需改（每次启动解包覆盖写，见 ⑫.3） |
| `pet-bridge.zip` | 无需改（每次启动解包覆盖写，见 ⑫.4） |
| `uiagent-tool.zip` | 无需改（每次启动解包覆盖写，见 ⑫.5） |
| `logs-tool.zip` | 无需改（每次启动解包覆盖写，见 ⑫.6） |
| `ui-probe.js` | 无需改（原生每次启动读一次进内存，见 ⑫.5）。⚠️ 但改了语义要同步升 `ui-probe.js` 内部的 `VERSION`（页面上的旧探针靠它判断要不要换） |
| `webview-polyfill.js` | 无需改（每次启动覆盖写） |
| `webview-compat.zip` | **必须重跑** `tools/build-webview-compat.ps1`（见 ⑯；文件名带内容 hash，换 `engine.bin` 后必然对不上） |
| `preset-skills.zip` | `EngineManager.PRESET_SKILLS_VERSION`（现 `1.0.0-1`）。判据是 `skills/.preset_skills_version` 里的字符串，**改了内容不改这个常量 ⇒ 老设备永远拿不到新 skill** |
| `preset-razor.zip` | 无需改（每次启动覆盖写） |

**漏改的后果是静默的**：老标记仍在 → 解包被跳过 → 新资产永不生效。
这三个都是踩过的坑。

### ⚠️ 改 `mobile-ui-plugin/**` 的源码**必须手工重打对应的 `.zip`**

`assets/*.zip` 全部被 `.gitignore` 忽略（L33），**没有任何打包脚本** ——
`logs-tool.zip` / `bubble-tool.zip` / `statusbar-tool.zip` / `shell-tool.zip` /
`uiagent-tool.zip` / `root-tool.zip` 都是**手工**打的。所以改完源码很容易
「commit 了、构建也过了、APK 里却是旧代码」。

**真踩过（`b0fbb4b`，10-14 02:44）**：那次给 6 个工具插件加了「注册失败不拖垮引擎」
的 try/catch，但只改了源码、一个 zip 都没重打。事后补打时又**漏了
`root-tool.zip`** —— 它停在 10-08 的版本，于是 `0.2.9-tooliso` 的 APK 里
**唯独 root 插件仍会在注册失败时把整个引擎带崩**（这正是那次修复要解决的问题）。

**判据（每次改完必须做）**：zip 内的 `lib/index.js` 必须与源目录**逐字节一致**。
一条命令验完 6 个：

```powershell
Add-Type -AssemblyName System.IO.Compression.FileSystem
$pairs=@{'logs-tool'='dsh-android-logs';'bubble-tool'='dsh-android-bubble';
 'statusbar-tool'='dsh-android-statusbar';'shell-tool'='dsh-android-shell';
 'uiagent-tool'='dsh-android-uiagent';'root-tool'='dsh-android-root'}
foreach($k in $pairs.Keys){
  $z=[IO.Compression.ZipFile]::OpenRead((Resolve-Path "app\src\main\assets\$k.zip").Path)
  foreach($e in $z.Entries){
    $r=New-Object IO.StreamReader($e.Open());$a=$r.ReadToEnd();$r.Dispose()
    $b=Get-Content "mobile-ui-plugin\$($pairs[$k])\$($e.FullName -replace '/','\')" -Raw
    if($a -ne $b){ "⚠️ $k :: $($e.FullName) 不一致" }
  }
  $z.Dispose()
}
```

**打包格式**（六个 zip 同构）：条目为 `package.json` + `lib/index.js`
（`uiagent-tool` 多一个 `lib/screens.js`），**相对路径、没有顶层目录** ——
`EngineManager` 直接往 `$DSH_HOME/<xxx>-tool/dsh-android-xxx/` 解，多一层目录就找不到入口。

### ⑦ WebView 默认没有文件选择器 —— 必须实现 `onShowFileChooser`

**症状**：composer 里点「+」→「文件」，**静默无反应**，没有报错、没有日志。

**根因**：`WebChromeClient` 只覆写了 `onConsoleMessage`，没覆写 `onShowFileChooser`。
WebView 的默认实现**立刻回调 `onReceiveValue(null)`**（即"用户没选任何文件"），
于是页面认为选择被取消，什么都不发生。页面里确实有
`<input type="file" multiple hidden>`（`.uV2eYG_tools` 内），走的就是标准 file chooser。

⚠️ 该页面**没有** File System Access API（`showOpenFilePicker` / `showSaveFilePicker` /
`showDirectoryPicker` 全是 `undefined`），所以不需要实现 `onShowFilePicker`（WebView 113+
的新接口）；`onShowFileChooser` 才是正确的接缝。

**做法**（`MainActivity.kt`）：用 `registerForActivityResult(StartActivityForResult)`
拉起 `fileChooserParams.createIntent()`，按 `mode == MODE_OPEN_MULTIPLE` 补
`EXTRA_ALLOW_MULTIPLE`，结果里优先取 `clipData`（多选）再退回 `data.data`（单选）。

⚠️ **取消时必须回调 `null`，不能回调空数组** —— 空数组会被页面解读成"选了 0 个文件"
而触发一次空提交。`ActivityNotFoundException`（无任何应用能处理）同样要 `null` 并返回 `false`。
⚠️ 回调是**一次性**的：`filePathCallbackRef` 要在调用前取出并置空，否则第二次点击不会响应。

### ⑦.5 WebView 默认没有下载器 —— 必须实现 `setDownloadListener`

**症状**：composer 里 `/export`（下载日志）或顶栏「下载 Session 日志」**声称成功**
（界面显示"已保存"），但 `/sdcard/Download/` 下**没有文件**，也没有任何报错。

**根因与 ⑦ 同构**：`WebView` 默认的 `DownloadListener` 是 **no-op** ——
页面用 `<a download>` + `anchor.click()` 触发下载时，WebView 什么都不做。
`dsh-session-log-export/lib/client.js` 的 `downloadUrl()` 走的正是这条路：

```js
const anchor = document.createElement("a");
anchor.href = url; anchor.download = filename; anchor.click();
```

⚠️ **`MainActivity` 从来没有 `setDownloadListener`**（`grep` + `git log -S` 全历史
确认过，不是回归而是从未实现）。所以这一处和 ⑦ 一样属于「默认实现静默失败」。

⚠️ **不要用系统 `DownloadManager`。** 试过，三个理由都不成立：
1. 它跑在**系统进程**里，**不共享 WebView 的 cookie jar**；
2. API 28+ 会**剥掉**应用自带的 `Cookie` 头；
3. 而 `/api/session.export` 是**纯 cookie 鉴权**（带 cookie → `200`，不带 → `401`）。

**做法**（`MainActivity.kt`）：`setDownloadListener { url, userAgent, contentDisposition,
mimeType, _ -> handleDownload(...) }`，`handleDownload()` 在**本进程内**用
`HttpURLConnection` 发 GET，手动带上三样东西：

| 头 | 值 |
|---|---|
| `Cookie` | `CookieManager.getInstance().getCookie(url)` |
| `User-Agent` | WebView 传进来的 `userAgent` |
| `Referer` | `http://127.0.0.1:3080/` |

`connectTimeout 15_000` / `readTimeout 60_000`，`responseCode != HTTP_OK` 直接
`error("HTTP $code")`，`finally { conn.disconnect() }`，落盘后
`withContext(Dispatchers.Main)` 弹 toast。

⚠️ **只处理 `http://127.0.0.1` 的 URL**，其余（`blob:`/`data:`/外链）交给系统浏览器
（`Intent.ACTION_VIEW`）—— 本 App 的下载全部来自内置引擎，外链走系统更合适。

⚠️ 文件名用 `URLUtil.guessFileName(url, contentDisposition, mimeType)`。

**落盘位置**（`downloadTargetDir()`）：有 `WRITE_EXTERNAL_STORAGE` 时用
`Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)`（即
`/sdcard/Download/`），否则退回 `getExternalFilesDir(DIRECTORY_DOWNLOADS) ?: filesDir`。

**真机验收**（`/export` 全链路）：

```
/sdcard/Download/dsh-session-session-dff05b44-baa3-47b2-8dd7-71d90c6c4ba9.zip  665 B
日志：Session 日志已保存: /storage/emulated/0/Download/...（665 字节）
```

把 zip 拉回 PC 用 `ZipFile` 打开 → 1 个条目 `session.v4.jsonl`（1457 B），
魔数 `PK\x03\x04` ✅。

### ⑦.7 内置 Web 内核版（productFlavor `gecko`）—— 不依赖系统 WebView

同一个工程出**两个可共存的 APK**：

| flavor | applicationId | Web 内核 | 说明 |
|---|---|---|---|
| `system` | `com.dsh.android` | 设备系统 WebView | 原有行为，**一字未改** |
| `gecko` | `com.dsh.webview` | 打进 APK 的 GeckoView | 新增，不依赖系统 WebView |

```powershell
.\gradlew.bat :app:assembleSystemDebug   # app\build\outputs\apk\system\debug\app-system-debug.apk
.\gradlew.bat :app:assembleGeckoDebug    # app\build\outputs\apk\gecko\debug\app-gecko-debug.apk
.\gradlew.bat :app:assembleDebug         # 两个都出
```

⚠️ **第二个包名必须也恰好 15 字符**（硬约束①）：`com.dsh.webview` = 15 ✅。
`TermuxBootstrap.ELF_ROOT_REPLACEMENT` 已改成**从 `BuildConfig.APPLICATION_ID` 推导**，
所以两个 flavor 各自把 ELF 里的 `/data/data/...` 改写成**自己的**私有目录 ——
写死的话另一个 flavor 会把二进制改成别的应用的路径，必炸。

⚠️ **两个包的数据目录互相独立**：会话、凭据、工作区**不共享**。这是共存的代价。

⚠️ **内核实现必须放在 flavor 源集里**（`app/src/system/java`、`app/src/gecko/java`），
**不能放 `main`** —— `GeckoWebHost` 引用 `org.mozilla.geckoview.*`，而该依赖只挂在
`geckoImplementation` 上，放 `main` 会让 system flavor 编译不过。

#### 为什么是 GeckoView，以及为什么是 140

- 唯一有**官方预编译 Maven 产物**的可嵌入内核。腾讯 X5/TBS 的**内核是运行时下载的**，
  违背「内置」这一条。
- **157 / 146 用不了**：它们的传递依赖要 AGP 9.1.0（`androidx.core:core:1.19.0`）
  与 compileSdk 36（`media3:1.11.0`），当前 AGP 8.8.2 / compileSdk 35 接不住。
  升 AGP 是另一件事，本轮没做。**140.0.20250707120347** 可用。
- 仓库要加 Mozilla 的 maven（`settings.gradle.kts`，限定 group 免得拖慢解析）：
  `https://maven.mozilla.org/maven2/`。

#### GeckoView 与 WebView 的 API 差异（写代码前必读）

| 能力 | WebView | GeckoView |
|---|---|---|
| 注入 JS 到页面 | `evaluateJavascript` | **没有**；必须 `pageWindow.eval(源码)` |
| JS ↔ 原生 | `addJavascriptInterface` | **没有**；走 WebExtension native messaging |
| console 转发 | `onConsoleMessage` | **没有**；在页面世界包 `console.*` |
| 下载 | `setDownloadListener` | **没有**；见下 |
| 读 cookie | `CookieManager.getCookie` | **没有 API**（只有 cookie 横幅那几个方法） |
| targetSdk 依赖 | 内核随系统 | **不依赖**，与系统 WebView 版本无关 |

所以 `main` 里的宿主只认 `WebHost` 接口（`app/src/main/java/com/dsh/android/web/WebHost.kt`），
两个 flavor 各给一份实现。

#### 桥（bridge）的实现要点

- 扩展在 `app/src/gecko/assets/dsh-bridge/`（MV2，`manifest.json` + `content.js`，
  **没有 background**）。
- ⚠️ **GeckoView 不实现 `browser.tabs`**，所以 content script 必须**自己**
  `browser.runtime.connectNative('browser')`；manifest 的 permissions 要有
  `["nativeMessaging","nativeMessagingFromContent","geckoViewAddons"]`。
- ⚠️ 原生侧必须用 **`session.webExtensionController.setMessageDelegate(...)`**，
  不是 `extension.setMessageDelegate(...)` —— **content script 的消息只认前者**。
- ⚠️ **content script 跑在隔离世界**，往页面世界塞东西只有一条可行的路：

  ```js
  pageWindow.eval(源码字符串)(exportFunction(fn, pageWindow), …)
  ```
  | 写法 | 结果 |
  |---|---|
  | `pageWindow.DSHAndroid = {...}` | 页面读到的是 Xray wrapper，**Permission denied** |
  | `cloneInto(obj, pageWindow)` | `Permission denied to pass a Function via structured clone`（结构化克隆**不能传函数**） |
  | `exportFunction(fn, pageWindow)` | ✅（**第二个参数不能省**，省了抛 `Function requires at least 2 arguments`） |

  对象必须在**页面世界**构造（`eval` 那段源码），要调的原生函数以参数形式喂进去。
- ⚠️ `post()` 发出的消息 `type` **必须是 `'invoke'`**：原生按 `type` 分派，漏了不报错、
  **静默丢弃**，表现为「所有动作（含 onUiReady）都到不了原生」。
- ⚠️ 扩展 manifest 的 `version` **改了 `content.js` 就必须加**，否则 GeckoView 打
  `Addon with ID … already installed, older version will be disabled` 并**继续用旧包**。

#### 文件选择（⑦ 的 gecko 版）

⚠️ **必须返回一个未完成的 `GeckoResult<PromptResponse>`**。返回 `null` 不是「稍后再说」，
而是「本 delegate 不处理」—— 内核立刻按默认行为取消，此后 `prompt.confirm(...)` 的
结果**再也送不回内核**。真机症状：选择器能弹出，选完文件**永远不落进 Web**。

⚠️ `FilePrompt.confirm(Context, Uri[])` **只接受 `file://`**，给 `content://` 会报
`NS_ERROR_FILE_UNRECOGNIZED_PATH`（Mozilla bug 1591640 / 1875732）。
所以 `GeckoWebHost.toLocalFile()` 先把 `content://` **拷进 `cacheDir`** 再 confirm
（用 `cacheDir` 而不是 `filesDir`：这是一次性上传暂存，让系统能回收）。

真机验收：

```
文件选择请求（type=2，多选=true）
所选文件已落盘: 1791728591132--31edd527-w5.png（33375 字节，源 content://…）
文件选择完成：1 个（已落盘到本地）
```

#### 下载（⑦.5 的 gecko 版）—— 踩了两条死路才找到正解

`/api/session.export` 是**纯 cookie 鉴权**，而 GeckoView **没有任何读 cookie 的 API**，
cookie 存在内核自己的 `files/mozilla/<profile>/cookies.sqlite` 里、**不写进** WebView
的 cookie jar。于是：

| 路 | 结果 |
|---|---|
| 页面报 URL → **宿主**用 `HttpURLConnection` 代发 | **必然 401**（宿主持不到 cookie） |
| 页面报 URL → 用 `GeckoSession.Loader` 让**内核**发导航 | **真机 403 forbidden**，且**错误页把整个 GUI 顶掉了** |
| ✅ 在**页面世界**用 `fetch` 拉，base64 分块经桥回传 | 通 |

第二条路的两个教训要记住：
1. 引擎的准入检查（`dsh-client-connection` 的 `isTrustedApiRequest`，看
   host / `sec-fetch-site` / `origin`）对**原生发起的导航**与**页面发起的 fetch**
   给的不是同一组取值，前者会被判 403；
2. **任何非 attachment 的响应都会被内核当主文档渲染** —— 一次失败就把界面顶掉，
   比功能不可用严重得多。

第三条路之所以对：**与 GUI 平时调 API 完全同源同头**，且响应是数据不是导航，
失败最多是一次 fetch 报错。

⚠️ 分块消息**每条都自带 url 与文件名**，不另发「开始」消息 —— 单独的开场消息被
内核 messaging 拒过一次（logcat 只有 `Invalid event data property args`，原生收不到，
而分块本身能通），结果是分块到了却没有进行中的下载、整批丢弃。

⚠️ 落盘由宿主做（`callbacks.onDownloadStream(DownloadStream(url, filename, body))`），
内核侧不碰文件系统；`MainActivity.handleDownloadStream()` 在 IO 线程读流写文件。

#### ⚠️ 三个「静默炸掉整个文件」的注释陷阱（都踩过，且症状极难查）

1. **Kotlin 块注释会嵌套**：KDoc 里写 `files/mozilla/*/cookies.sqlite`、`/api/*`
   会打开一个嵌套注释，**吞掉文件后半段**，报一连串莫名其妙的
   `Unresolved reference`（`WebHostCallbacks`、`DownloadStream`、`GECKO_MAJOR`…）
   外加 `Unclosed comment`。**提到路径一律用 `「」` 包裹**。
2. **JS 的 `//` 注释里出现 `/*`**（例如写 `/api/*`）同样提前闭合块注释，
   症状是 **content script 整体不执行**。
3. **模板字符串里出现反引号**（`PAGE_SHIM_SOURCE` / `BRIDGE_INSTALLER_SOURCE` 的内容）
   会**提前终止模板**，症状同样是 content script 完全不执行。

三者都**不会被 `node --check` 全部抓到**（第 3 种的典型表现是 `node --check` 通过、
运行时整个脚本站点级失效）。诊断依据永远是 logcat 里的
`W Web Content: [JavaScript Error: ...]` **和** `E GeckoConsole`（DSH tag 下看不到）。
建议的机械校验：见 `tools/scan-bridge.js`（扫注释里的 `/*` 与模板内的反引号）。

### ⑧ `sharp` 没有 android 预编译二进制 —— 装 wasm32 变体

**症状**：图片附件**上传成功**（缩略图正常显示），点发送报
`prompt rejected (session/agent-busy)`。

⚠️ `agent-busy` 是**兜底错误码**，真因藏在 `details.reason` 里，别照着字面查
"会话忙"。`dsh-api-session-controller/lib/index.js` 是
`throw new RemoteError("session/agent-busy", "prompt rejected", { reason: String(error) })`。
**排查手法**：在页面里 hook `window.fetch`，把 `details.reason` 打出来。

`dsh-attachment-local` 用 `sharp`（^0.35.3）做图片元数据 + 缩放重编码。sharp 的
预编译二进制只覆盖 darwin/linux/linuxmusl/win32/freebsd，**没有 android**。

`sharp/dist/sharp.cjs` 算出 `runtimePlatform = android-arm64`，switch 里没有这个分支，
于是**无条件落到 wasm 兜底**：

```js
sharp = require("@img/sharp-wasm32/sharp.node");
```

所以**只需要装 `@img/sharp-wasm32`，sharp 本身一行都不用改**。

**做法**：`assets/sharp-wasm32.zip`（含 `@img/sharp-wasm32` + `@emnapi/runtime`，
条目为相对路径、无顶层目录），`EngineManager.ensureSharp()` 解到
`engine/node_modules/`。幂等判据是
`@img/sharp-wasm32/lib/sharp-wasm32-0.35.5.node.wasm` 存在且非空。无需版本戳。

⚠️ `@emnapi/runtime` 的 `index.js` 按 `NODE_ENV` 二选一 require
（`emnapi.cjs.min.js` / `emnapi.cjs.js`），**两个都要打进去**。
⚠️ wasm 靠 `locateFile()` 相对 `scriptDirectory` 找同目录的
`sharp-wasm32-0.35.5.node.wasm`，所以 `.node.js` 与 `.node.wasm` 必须同目录。
⚠️ 设备实测通过（`sharp 0.35.5` / `vips 8.18.7`，1200×800 全链路
metadata → resize → png/jpeg/webp → thumbnail 全 OK）。
**别用手搓的 base64 PNG 测**，那会报 `vipspng: libpng read error`，误判成 wasm 的锅；
用 `sharp({ create: {...} })` 造图。

### ⑨ 附件模块会 fsync 到 `/` —— Android 的 SELinux 不给 open

**症状**：与 ⑧ 完全一样（`agent-busy`），但在 ⑧ 修好**之后**才浮出来。

`details.reason` 的真因：

```
Error: EACCES: permission denied, open '/data/user/0'
```

**根因**：`dsh-attachment-local` 发布附件时要证明"目录项已落盘"，
`ensureDurableDirectory()` 从 `$DSH_HOME` **逐级向上** fsync 每一层父目录
（`syncDirectory()` = `open(dir, O_RDONLY)` + `handle.sync()`），而
`ensureDurableHome()` 把 boundary 定成 `parse(home).root` —— 也就是 **`/`**，
于是这趟遍历**必然**走出应用目录。

而 Android 的 SELinux 不让应用 open 这些祖先目录。真机实测（root 的 `/` 也是 EACCES，
**不是权限位问题**，别去 `chmod`）：

| 目录 | 模式 | `open(O_RDONLY)` |
|---|---|---|
| `/` | 755 | **EACCES** |
| `/data` | 771 | **EACCES** |
| `/data/user` | 711 | **EACCES** |
| `/data/user/0` | 771 | **EACCES** |
| `/data/data` | 771 | **EACCES** |
| `/data/user/0/com.dsh.android` | 700 | OK |
| `/storage/emulated/0` | 771 | OK |

**做法**：`EngineManager.patchAttachmentDurability()` 把 `syncDirectory()` 里那次
`open` 包进 try/catch，**只把 EACCES/EPERM 改成跳过该层**，其余 errno 照旧抛出。
锚点见 `ATTACH_SYNC_GUARD` / `ATTACH_SYNC_ANCHOR`。

⚠️ 丢掉的只是"祖先目录项的崩溃持久性"这一项加固，**不是正确性**：附件字节本身的
fsync 照旧，应用自己目录（700、可 open）的 fsync 也照旧。应用目录之外的目录项在
Android 上**本来就不可达**，这不是取舍是平台事实。
⚠️ 桌面端不受影响（`/`、`/home` 在 Linux 上都是可 open 的 755），catch 分支不触发。
⚠️ 引擎升级后锚点若不匹配，补丁会跳过并打日志，不会让启动失败。

**同类站点已逐处核对**（`dsh-session-persistence-jsonl` 的 `syncDirectory`、
其 `lib/worker.cjs` 孪生、`dsh-storage-json` 的 `fsyncDirectory`）——
它们只 fsync **直接父目录**，都在 `$DSH_HOME` 以内，**安全**。
**只有 `dsh-attachment-local` 会一路走到 `/`。**

### ⑨.5 API 错误中断后会话**永久发不出去** —— assistant 的 `tool_use` 块顺序

**症状（严重，会话级永久损坏）**：某一轮因为 **API 错误**结束时（实测是第三方路由的
`429 RATE_LIMIT`），**该会话此后的每一条消息都失败**，连「只回复两个字：通了」
这种最简消息也一样：

```
本轮运行失败 messages.7.1: `tool_use` ids were found without `tool_result`
blocks immediately after: call_f0dd74e854fd4cb1a0a6ee0a. (INVALID_REQUEST)
```

**新建会话不受影响** —— 所以这是**历史消息**的问题，不是当前请求的问题。
UI 上表现为「处理失败」，`INVALID_REQUEST` 是错误码。

⚠️ **触发它的不是用户点「停止生成」**（实测那条路径完全正常，中断后会话照常可用），
而是 **API 侧的错误中断**：那一轮的 assistant 消息已经带上了 `tool_use` 块，
但永远等不到对应的 `tool_result`，历史就此定形。

**根因**：`dsh-llm-deepseek/lib/index.js` 的 `serialize()` 把 assistant 消息的
content 块**原样保序**映射成 `tool_use` / `text` / `thinking`，而持久化历史里
assistant 的实际形状是**交错的**（模型先说一句、再调工具、再说一句、再调工具）：

```
["tool_use:call_e6d1…", "text(3)", "tool_use:call_a47f…", "tool_use:call_2399…"]
```

而 DeepSeek Messages API 要求 assistant 的 `tool_use` 块**必须连续**，且紧后必须
是携带全部 `tool_result` 的 user 消息。夹在中间的 `text` 直接违约。

⚠️ **引擎自己的自检抓不到它**：`serialize()` 末尾那个 `pending` 循环**只查 id 配对、
不查块顺序**，所以这种历史在本地自检里是「合法」的，只有真发给 API 才炸。

⚠️ **引擎其实已经对 user 消息做了同样的重排**（`[...results, ...其余]`，就是修
「tool_result 必须在前」的那一行），**只是漏了 assistant 这一侧**。这个补丁补的就是
那处遗漏。

**做法**：`EngineManager.patchLlmDeepseekToolOrder()` 把 `assistant()` 的
`return message.content.map(...)` 拆成先赋给 `const mapped`，再补一次稳定重排 ——
**把 `tool_use` 收敛到末尾**，其余块保持原有相对顺序。锚点 `TOOL_ORDER_GUARD_HEAD` /
`TOOL_ORDER_GUARD_TAIL`（首尾两行，不是整段照抄，免得无关空白变化就失配）。

⚠️ **方向是「置尾」而不是「置首」**：assistant 的 content 首块常是 `reasoning`
（映射成 `thinking`），而 Messages API 要求 thinking 排在 assistant content 的**最前**。
把 `tool_use` 提到最前会让 thinking 落到它后面，可能触发签名校验失败。收敛到末尾
得到的 `[thinking, text, tool_use…]` 才是规范形状。

⚠️ **重排必须发生在 `map` 之后**：`readReplay()` 返回的签名数组是**按下标**与
`message.content` 对齐的，`assistant()` 在 `map` 里用 `replay[index]` 取签名。
若在 `map` 之前重排 `message.content`，签名会串位。补丁插在 `map` 的外层正是这个位置。

⚠️ **只改 wire 请求体，不改持久化历史**：`assistant()` 返回的是新数组，
`message.content` 本身不被改写，会话日志与 UI 显示都不受影响。真机实测修复后
日志里 assistant 仍是交错的 37 个 `tool-call`，证明没有回写。

**真机验收**（同一个已卡死的会话 `session-c470b9d5`，21 轮历史，其中两条
`INVALID_REQUEST`）：

| | 修复前 | 修复后 |
|---|---|---|
| 「只回复两个字：通了」 | `INVALID_REQUEST` | `已完成，用时 15秒` → 回复「通了」 |
| 多工具任务（3 步 bash） | — | `turn/end kind=completed`，产物 `e2e-multi/x.txt` = `MULTI_OK` |

日志：`已修正 DeepSeek Messages 的 tool_use 块顺序: dsh-llm-deepseek`。
幂等：第二次启动不再打印该行（`contains(TOOL_ORDER_ANCHOR_HEAD)` 命中即返回），
补丁文本仍只有 1 处。

⚠️ 离线复刻验证（把会话日志按 `serialize()` 的逻辑重建 wire messages）：
现状 **24 处违例**（`messages.1/7/13/21/…`，报错里的 `messages.7.1` 正是其中之一），
重排后 **0 处**。

### ⑨.6 第三方路由不给 tool_call id —— 整条会话**从列表里消失**

**症状（比 ⑨.5 更狠：会话直接没了）**：某一轮结束后

```
format v4 tool/result at seq 19 requires toolCallId matching its tool source
tool call id requires a nonempty string
```

随后**该会话从列表里消失**（重启 App 后彻底不见），`/export` 报 HTTP 500，
**新建会话也失败**：

```
新建会话失败: gateway/internal: failed to create session "session-7d4ab1ab-…":
  SessionQueryError: stored session … is corrupt:
  SessionFormatError: tool call id requires a nonempty string
  (raw log: /data/user/0/com.dsh.android/files/home/.dsh/sessions/…/session.v4.jsonl.zstd)
```

**根因**：`dsh-llm-pi-ai` 的 `toolcall_end` 分支拿 provider 给的 id 造 tool-call 块，
**没有任何校验**：

```js
id: brandString(event.toolCall.id),   // brandString 是纯类型断言（return value），零校验
```

对比 DeepSeek 官方适配器，那里有防线
（`if (!content.id || !content.name) return malformed("empty tool identity")`）——
**pi-ai 这条路径漏了**。第三方路由（实测设备上的 `agnes` / `q`，即「模型」页里
用户自加的 provider）在流式响应里不给 `toolCall.id` 时，`id` 就是 `""` 或 `undefined`。

⚠️ **`dsh-llm` 的收口兜不住它**，两个原因叠加：

```js
id: partial.toolCallId ?? brandString(`call-${index}`),   // ① `??` 只兜 null/undefined，空串放行
if (partial.block) return partial.block;                  // ② block-end 带来的块直接短路返回
```

② 意味着 pi-ai 这种走 `block-end` 的协议**根本执行不到** ① 那一行。
所以两处都得补（`patchLlmPiAiToolCallId` + `patchLlmToolCallIdFallback`）。

⚠️ **为什么一行坏数据能搞掉整个会话**：`tool/result` 落盘后，
读取侧 `dsh-session-format-v3-to-v4` 的 `assertV4ToolResultMessage()` 要求
`toolCallId` 是非空字符串**且**等于 `source.callId`；而
`dsh-session-persistence-jsonl` 的 `consumeEventLine()` 对格式错误
**只重抛、不恢复**（只有 JSON 解析失败才走 `issue` 恢复路径），
`finish()` 还会再跑一遍 `assertReleasedV4Relationships()`。
于是**一行坏数据 ⇒ 整个会话加载失败** ⇒ 列表里消失 ⇒ 导出 500 ⇒ 新建会话被拖累。

⚠️ 空 id 能一路落盘是因为 `snapshotJsonValue()` 只校验「可无损 JSON 序列化」，
空串是合法 JSON；`undefined` 则会被 JSON 往返整个丢掉字段。两种都逃得过写入侧。

**做法**：两处补丁，兜底值用 `call-<index>` 形状（与组装器原生兜底命名一致）：

| 补丁 | 文件 | 改动 |
|---|---|---|
| `patchLlmPiAiToolCallId()` | `dsh-llm-pi-ai/lib/index.js` | `toolcall_delta` 与 `toolcall_end` 两处 `\|\|` 兜底 |
| `patchLlmToolCallIdFallback()` | `dsh-llm/lib/index.js` | `assemble()` 的 `??` 改 `\|\|`（覆盖 pi-ai 之外的 provider） |

⚠️ **只改流式事件的 id 兜底，不改持久化历史**：provider 给了真实 id 时行为完全不变。

日志：`已修正 pi-ai 的空 tool-call id: dsh-llm-pi-ai` +
`已修正 dsh-llm 组装器的空 tool-call id 兜底: dsh-llm`。
幂等：第二次启动不再打印这两行。

⚠️ **离线复现验证**（复刻 pi-ai 的 start/delta/end → 组装器 → `assertV4ToolResultMessage`）：
provider 给真 id → 通过；**给空串 → `format v4 tool/result at seq 19 requires toolCallId
matching its tool source`（与线上报错逐字一致，连 `seq 19` 都对上）**；缺 id → 同样失败。
打完补丁后三种情况全部产出非空 id。
补丁文本与官方发布件逐字匹配（各命中 1 处），替换后 `node --check` 通过。

⚠️ **已损坏的会话不会自动恢复**：本补丁只堵住新坏行的产生。已经落盘了空 id 的
历史会话仍然读不出来 —— 那种情况要么删掉该会话目录，要么另行做读取侧容错。

### ⑩ 侧栏终端打不开 —— node-pty 缺 android 绑定 + 进程检查器只认三平台

**症状**：终端面板显示
`终端错误：subprocess-local: terminal inspection is unsupported on platform android`。

**这是两个独立的缺口叠在一起**，修一个会立刻撞上另一个，所以一起记：

**缺口一：`createProcessInspector()` 只认 linux/darwin/win32。**
它在 `dsh-subprocess-local/lib/runner-launch-<hash>.js` 里，是
`spawnTerminal()` 的第一步，落不进任何分支就 `throw`。

**做法**：让 android 复用 **Linux 实现**（`EngineManager.patchProcessInspector()`，
锚点 `INSPECTOR_GUARD` / `INSPECTOR_ANDROID_ANCHOR`）。依据：

- Android 就是 Linux 内核，`/proc/<pid>/stat` 格式一致（真机实测 `parseProcStat` 能解析）。
- `SYSCALLS` 表里**本来就有 arm64**，安卓 arm64 正好命中。
- app 域下 `/proc` **目录可读**，只是别的 uid 的条目被 SELinux 挡掉；而
  `LinuxProcessInspector` 对读不到的条目一律跳过（`readLinuxStat` 吞异常返回 `undefined`），
  **不会因此抛错**。真机实测 `readable=3`（只有自己 uid 的几个进程可读）。
  代价是只能看见自己 uid 的进程 —— 而终端 shell 及其子进程全是本 app 的 uid，
  正是需要观察的那部分。

⚠️ 实现体在**带 hash 的 chunk 名**里（`runner-launch-<hash>.js`），所以补丁按
**内容**找文件而不是按文件名找。⚠️ 幂等判断必须**先查「已打过补丁」再查「原文在不在」**：
打完之后原文就不存在了，顺序反了会把已完成的状态误报成「未找到」。

**缺口二：`node-pty` 没有 android 预编译二进制。**
它的 `prebuilds/` 只有 darwin/linux/win32，而 `lib/utils.js` 按
`prebuilds/${process.platform}-${process.arch}` 拼目录名 → 在 Android 上找
`prebuilds/android-arm64/pty.node` → `Failed to load native module: pty.node`。

**做法**：用 NDK 从 node-pty 自带的 `src/unix/pty.cc` 编一份，打成
`assets/node-pty-android-arm64.zip`，`EngineManager.ensureNodePty()` 解到
`engine/node_modules/node-pty/prebuilds/android-arm64/`。**上游源码一行都不用改。**

```powershell
# 先取 node 头文件（版本必须与设备上的 node 一致）
Invoke-WebRequest https://nodejs.org/dist/v26.4.0/node-v26.4.0-headers.tar.gz `
  -OutFile hdr.tgz -Proxy "http://127.0.0.1:7897"
tar -xzf hdr.tgz
$cxx = "C:\Users\XIAOPAN\AppData\Local\Android\Sdk\ndk\27.0.12077973\toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android26-clang++.cmd"
& $cxx -shared -fPIC -O2 -std=c++17 -static-libstdc++ `
  -I node-v26.4.0/include/node `
  -I <engine>\node_modules\node-addon-api `
  -o pty.node <engine>\node_modules\node-pty\src\unix\pty.cc
# 然后按「条目不带顶层目录」打成 zip：node-pty/prebuilds/android-arm64/pty.node
```

⚠️ **必须 `-static-libstdc++`**。默认动态链会引入 `libc++_shared.so`，
而 Termux 环境里**没有**这个库，加载即失败。静态链后 `readelf -d` 只剩
`libm/libdl/libc` 三个 bionic 系统库。

⚠️ **PTY 在 Android 上可用，别被「平台不支持」误导**：真机在 app 的 SELinux 域
（`untrusted_app`）里实测 `openpty` / `forkpty` / `open("/dev/ptmx")` 全部成功。
设备实测：绑定加载成功，`pty.spawn(bash)` 起得来，写入 `echo PTY_WORKS_$((6*7))`
能收到 `PTY_WORKS_42` 回显。

### ⑪ 应用身份权限太低 —— 内建 uid 2000（shell）执行通道

**症状**：截图、点击屏幕、卸载应用、改系统设置、读 `/dev/input` 这些操作
**全部做不到**。而且失败方式很隐蔽 —— 有些报权限错，有些**静默失败**。

**真机实测（Huawei CDY-TN90 / Android 10）两个身份的差异**：

| 命令 | app 域（u0_a323） | uid 2000（shell） |
|---|---|---|
| `screencap -p x.png` | rc=1，**stderr 完全为空**，留下 0 字节文件 | rc=0，708460 字节真图 |
| `input keyevent 3` | **rc=0 但前台窗口纹丝不动** | rc=0，真的切到桌面 |
| `pm uninstall x` | `SecurityException: INTERACT_ACROSS_USERS_FULL` | 走到包管理器 |
| `settings put/get` | `SecurityException: INTERACT_ACROSS_USERS` | 正常 |
| `am force-stop` | `SecurityException: FORCE_STOP_PACKAGES` | 正常 |
| `appops get` | `SecurityException: INTERACT_ACROSS_USERS` | 正常 |
| `getevent -pl` | `could not add watch for /dev/input, Permission denied` | 能枚举输入设备 |
| `dumpsys battery` | rc=**0**，输出 `Can't find service: battery` | 完整状态（308 B） |
| `wm size` | rc=**20**，`Can't find service: window` | `Physical size: 1080x2400` |
| `ps -A` | rc=0，**只有 6 行**（只看得见自己 uid） | **674 行** |
| `dumpsys activity services` | rc=0，`Permission Denial: … missing DUMP` | 3203 B 正常 |

⚠️ **这张表的关键是 `input` 和 `dumpsys`/`ps` 那几行**：退出码是 0，但结果
要么没生效、要么残缺。**「失败」不能只看退出码** —— 这也是为什么本方案把
「要不要用特权身份」交给**模型自己判断**，而不是让宿主去猜哪条命令该走哪个域。

#### 做法：注册一个**独立的 `shell` 工具**（不是给 bash 加参数）

**这一点是本次改动的地基，改动前务必先理解，尤其是「为什么不能用提权参数」。**

引擎确实原生就有提权机制：`@deepseek-ai/dsh-tool-bash` 在
`ctx.shell.sandboxMode !== undefined` 时会给 `bash` 工具的 schema **自动加上**
`sandbox_permissions`（enum `["workspace-write","danger-full-access"]`）与
`justification`（`lib/index.js:513`），模型声明后由 `ctx.approval` 批准。

**但这个机制在本 App 里是坏的，原因是一个耦合：** 它挂在**权限预设**上。
用户在 GUI 里选「无需审批 / 全部权限开放」时，`defaultPreset` 就是
`danger-full-access`，于是该模式成了会话默认。而
`dsh-sandbox/lib/index.js:101` 里 `approveEscalation()` 对
`mode === effectiveMode` **直接返回原值**（不许"升级"到当前档），
`approval/policy: "never"` 又让审批一律 `rejected` —— 结果是：
**每一条 bash 命令都直接以 uid 2000 跑，模型连选择的余地都没有。**

真机后果（会话日志 `session-77e5515b`，事件 `#1 permission/preset: danger-full-access`、
`#2 sandbox/mode: danger-full-access`、`#3 approval/policy: never`）：
`pwd` = `/`、`ls: No such file or directory`、`cd /data/data/com.dsh.android/files/engine`
→ `Permission denied`。**bash 工具整个废掉**，因为 uid 2000 读不到 app 私有目录。

所以正确做法是把「身份」和「预设」**彻底解耦**，注册一个**新工具**：

| 工具 | 身份 | 谁决定 |
|---|---|---|
| `bash` | **永远** app 身份（沙箱、link 转译、工作区全部正常） | 引擎，与预设无关 |
| `shell` | **永远** uid 2000 | 模型读工具描述后自己选 |

这样无论预设怎么设，`bash` 都是 app 身份；要特权就显式调 `shell` ——
恰好同时满足「默认走 app 权限」与「AI 自己决策要不要用 2000」。

⚠️ `shell` 这个工具名在引擎里**没被占用**（实测 30 个内置工具名），
可以安全注册。`run_code` 是保留名，不能碰。

#### 执行体：复用 `ctx.shell`，只是换个命令

插件在 `apply()` 里 `ctx.tools.register(defineTool({...}))`，`execute` 时把命令
换成 `node <runner> '<原命令>'`，并以 `sandboxPolicy: { mode: 'danger-full-access' }`
调用 `ctx.shell`。`dsh-bash-sandbox/lib/index.js:68` 里 `danger-full-access`
的语义**恰好**是「不做任何限制、直接跑」：

```js
if (mode === "danger-full-access") return SandboxBashExecutor.decorateResult(await super.execute(spec), …);
```

于是外层 `bash -c` 以 **app 身份、无沙箱** 启动（它读得到 app 私有目录里的
runner），runner 再连 adbd 以 uid 2000 跑真正的命令。**超时、取消、输出收集、
spill、grace 全部沿用引擎既有实现**，插件不必自己 spawn 一遍。

⚠️ **必须显式给子进程传 `DSH_HOME`**：`dsh-subprocess` 的 `scrubbedParentEnv()`
会剥掉**所有** `DSH_*` 变量（`lib/index.js:52`）。不传的话 runner 退回
`os.homedir()/.dsh`，与密钥所在的 `$DSH_HOME` 不一致 → 找不到密钥 →
**静默回落成 app 身份**。这是个很难查的坑。

⚠️ **插件手工构造 definition，不要 `import { defineTool }`**：包解在
`$DSH_HOME/shell-tool/` 下，那里没有 `node_modules`，ESM 解析**够不到**引擎的
`@deepseek-ai/dsh-tools`。`defineTool` 只做「spec schema → raw JSON Schema」+
「包一层参数校验」，手工写 raw JSON Schema 完全等价；参数校验由
`ctx.tools.register` 内部的 `validateJsonSchemaValue` 兜底。
支持的 schema 关键字子集是 `type/oneOf/properties/required/additionalProperties/items/enum/const`
+ 注解（`description/title/default/examples`），**`type` 与 `oneOf` 不能同时出现**。

实现分三块：

| 文件 | 作用 |
|---|---|
| `assets/dsh-android-shell.mjs` | 直连 `127.0.0.1:5555` 的 adbd，走 ADB 协议认证后以 uid 2000 执行 |
| `assets/shell-tool.zip` | host 插件 `dsh-android-shell`，注册 `shell` 工具 |
| `$DSH_HOME/shell-tool.patch.yml` | overlay，把上面的插件挂进 profile |

`EngineManager.ensurePrivilegedShell()` 每次启动把 runner 覆盖写到 `filesDir`；
`ensurePrivilegedPlugin()` 把 zip 解到 `$DSH_HOME/shell-tool/dsh-android-shell/`；
`writePrivilegedPatch()` 生成 overlay（`name` 用 `file://` 绝对 URL）。

⚠️ **为什么解到 `$DSH_HOME/shell-tool/` 而不是 profile 的 `node_modules`**：
与移动端 UI 插件同一个坑 —— 那里归 pnpm 管，而本包不在 profile 的
`dependencies` 里，用户每装一次插件就会被挪进 `node_modules/.ignored/`，
overlay 里的包名随即解析失败。详见「移动端 UI 插件」一节。

⚠️ **通道不可用时必须删掉 overlay**（`writePrivilegedPatch(false)` 会 `delete()`）：
插件本身在缺 `DSH_PRIV_RUNNER` 时不会注册工具，但留着一个指向不存在文件的
overlay 只会制造噪音。

⚠️ **不要改 `node_modules` 里的源码**：会在引擎升级时被覆盖，且改的是打包产物。

#### ⚠️ 历史教训：不要再用 `--import` 挂预加载

早先的实现是 `assets/dsh-android-privileged-loader.mjs` 经 `node --import`
替换 `SandboxBashExecutor.prototype.execute`。**这条路已经废弃并删除**，
原因就是上面那个耦合（它只能挂在 `danger-full-access` 上，而那恰好是用户的默认预设）。
若将来又想走 `--import`，先读这一节。

（技术备注：`--import` 是 **Node 的 flag**，必须排在脚本路径之前 ——
`node --import <loader> <bin.js> --profile web`；而 `--patch` 改的是配置，
够不到执行器行为。这些事实仍然成立，只是这个方案本身不成立。）

#### ADB 签名：不是标准的 `crypto.sign('sha1', token)`

**这是整件事最容易踩的坑，卡了很久。** AOSP 的做法是手工拼一个 PKCS#1 v1.5 块，
把 **token 原文**放进 DigestInfo 的摘要槽（**不是** `sha1(token)`）：

```
00 01 FF×219 00 ‖ 30 21 30 09 06 05 2b 0e 03 02 1a 05 00 04 14 ‖ token原文
                 └────────────── SHA1 DigestInfo 前缀 ──────────────┘
```

用 `crypto.sign('sha1', token, priv)` 会被 adbd 拒绝，症状是**反复重发 AUTH token**
（试满 2 次后转而要公钥），而不是明确的错误信息。Dadb 的
`AdbKeyPair.signPayload()` 用的是 `Cipher.getInstance("RSA/ECB/NoPadding")` +
手写 padding 表，同一回事。

参考实现：`dadb/src/main/kotlin/dadb/AdbKeyPair.kt`。

#### 另一个坑：读完每个 WRTE 必须回 OKAY

ADB 是**窗口式流控**：读完一个 `WRTE` 的 payload 不回 `OKAY`，adbd 会**停在那里
不再发下一个包** —— 表现为命令挂住、永远等不到 `CLSE`。Dadb 的
`AdbStreamImpl.read()` 也是读完即 `writeOkay(localId, remoteId)`。

同理，shell 要用 **v2 协议**（`OPEN` 的 destination 写 `shell,v2,raw:<cmd>`）：
v1 没有 `EXIT` 包，拿不到退出码。

⚠️ **收尾不能用 `process.exit(code)`**：输出走管道时它会在 libc 缓冲区写回之前
就终止进程，**静默截断大输出** —— 而 `screencap`/`dumpsys`/`ps -A` 恰好都是
大输出。要用 `process.exitCode` 让事件循环自然排干。

⚠️ stream id 要**顺序递增**，不能随机：随机 id 可能与在用的 id 撞车，撞车后
先关闭的那条流会把另一条活流的队列一起拆掉（AOSP 的注释专门记了这条）。

#### 密钥从哪来（Android 11+）：无线调试配对，**全程不碰电脑**

Android 11（API 30）起有了「无线调试」，它把「推密钥」这件事变成了**设备上
点几下 + 抄一个 6 位配对码**。配对成功后设备 keystore 里就有了我们的公钥，
此后走密钥认证，**不用再填码**。

分工（**端口发现必须在 Kotlin 侧**，原因见下）：

| 侧 | 文件 | 做什么 |
|---|---|---|
| Kotlin | `AdbMdns.kt` | `NsdManager` 发现 `_adb-tls-connect._tcp` / `_adb-tls-pairing._tcp` 的端口 |
| Kotlin | `WirelessDebugging.kt` | 编排：常驻监听写端口、配对、切固定端口 |
| Kotlin | `AdbPairingNotifier.kt` | 通知栏 `RemoteInput` 收配对码 |
| Node | `dsh-android-shell.mjs` | 读端口文件 → 用**内置 adb** 连上去执行 |

##### ⚠️ 端口发现只能走 `NsdManager`，不能自己实现 mDNS

**app 域发不出 UDP 组播。** 真机实测：

```
sock.send(q, 0, q.length, 5353, '224.0.0.251')
  → EPERM send EPERM 224.0.0.251:5353
```

`bind(5353)` 与 `addMembership` 都**成功**，单播也成功 —— 只有**组播发送**被
SELinux 层挡掉。而 Android 的 `mdnsd` **只应答组播查询**，所以自实现 mDNS 无解。

`NsdManager` 是**系统服务**：请求经 Binder 交给 **system 域**的 `mdnsd` 代发，
绕开了 `untrusted_app` 的限制。这正是 Shizuku 的做法（见其 `AdbMdns.kt`）。

⚠️ **因此不要退回「扫端口」。** 最初的实现扫 32768–60999 要 **20 秒**，而端口
**每次重启/重开都会变**，等于每条特权命令前都可能干等 20 秒。实测
`NsdManager` 解析耗时 **81 毫秒**。

端口有效性沿用 Shizuku 的判据：**能 `bind(127.0.0.1:port)` 成功 = 没人在听**，
失败才是 adbd。另外要求解析出的地址必须是本机地址 —— 实测重启后曾收到 PC 侧
缓存的陈旧 mDNS 条目，被这条规则正确拒绝：

```
[mdns] 忽略 adb-62291ab7-uzVPim → 192.168.1.85:33217（非本机或端口无人监听）
```

##### ⚠️ 配对端口只在那个界面开着时才广播 —— 所以必须**常驻等待**，不能超时就放弃

`_adb-tls-pairing._tcp` **只在用户打开「使用配对码配对设备」界面时才存在**，
界面一关就没了。所以 App 侧必须**一直**监听，直到端口出现。

⚠️ **原实现是「一次性发现 8 秒，没找到就报没找到」，这是个真实的体验缺陷**
（真机反馈修掉的）：用户点完配对后要去系统设置里翻找那个界面 —— 进「开发者选项」
再进「无线调试」再点「使用配对码配对设备」，远超 8 秒。于是：

```
用户刚找到那个按钮 → 通知栏已经显示「没找到端口」→ 只能回 App 重按一次配对
```

而重按一次**还是同样的结果**，因为端口本来就还没出现。这个来回毫无意义。

**现在的实现**（`AdbPairingNotifier.start()` → `runWatch()`）：

- 起一个**常驻**任务，用**一个** `AdbMdns` 实例持续监听，端口一出现就把通知换成
  带输入框的。**不加任何超时** —— 只有用户主动取消（设置页「取消配对」，或通知上的
  「取消」动作）与配对成功会让它停下。
- ⚠️ **必须用常驻实例，不能反复调 `AdbMdns.discover(timeout)`**：后者每次都要重新
  注册 discovery（`NsdManager` 同一 listener 不能重复注册，所以每次都新建对象），
  中间存在「上一次已停、下一次未注册」的**空窗期**，端口恰好在空窗期出现就会错过，
  要再等一轮。
- ⚠️ **常驻通知划不掉**，所以必须挂一个「取消」动作：万一进程被系统回收，监听已经
  没了而通知还挂着，用户就只能去强制停止应用。这个出口是必需的。
- ⚠️ 等待端口的那条通知也设为**常驻**：它是「正在等你操作」的指引，被划掉用户就
  不知道该去哪开无线调试了。
- ⚠️ 失败（配对码抄错）时**不停监听**：那个界面可能还开着、端口还在，用户改一位
  数字重发就行。
- 设置页要同步显示「正在等待…」（按钮禁用 + 把「接下来该做什么」铺在按钮下方）。
  点了按钮界面毫无变化的话，用户会以为没点上而反复点。
- ⚠️ 引导步骤的顺序是**先点配对、再去系统里开界面**（通知栏会指引）。原文案写的是
  反过来的「先开界面再回来点配对」，照旧文案走会白跑一趟。

##### ⚠️ `AdbMdns.isRunning` 的判据只能是 `running`（看门狗用）

常驻等待需要一个看门狗：`NsdManager` 在系统重启 mdnsd 等情况下会回调
`onStartDiscoveryFailed` / `onDiscoveryStopped` 把发现**静默掐掉**。一次性发现
不在乎（下次调用会重建），但常驻等待必须能发现并重建它 —— 否则用户按提示去开了
无线调试，这边却早已不再监听，永远不会出现输入框。

⚠️ **判据不能写成 `running && registered`**：`registered` 要等 `onDiscoveryStarted`
回调才为 true，而那是**异步**的。刚 `start()` 完那一瞬间 `running=true,
registered=false`，看门狗会把它误判成「已停」并**反复重建监听**，形成死循环
（写这一版时踩到过）。

⚠️ **`onDiscoveryStopped` / `onStopDiscoveryFailed` 必须同时清掉 `running`**：
系统**主动**停掉发现时只走这两个回调（我们主动 `stop()` 时 `running` 本来就是
false，再置一次无害）。不清的话 `isRunning` 永远报 true，看门狗永远不重建。

##### 配对码为什么要走通知栏 `RemoteInput`

用户必须**离开 App** 去系统设置页读那 6 位码，所以不能用 App 内输入框 ——
切走 App 后它可能被冻结/回收。通知栏可以在用户停留在设置页时接收输入。
（Shizuku 的 `AdbPairingService` 同理。）

⚠️ **`PendingIntent` 在 API 31+ 必须 `FLAG_MUTABLE`**，否则系统**写不回**
用户输入的内容，症状是「能打字但点确认没反应」。代码里按
`SDK >= S ? FLAG_MUTABLE : 0` 处理。

⚠️ **接收器必须静态声明**（`AndroidManifest.xml` 里 `exported="false"`）：
通知可以比 App 进程活得久，动态注册的接收器会收不到。

⚠️ **`goAsync()` 是必须的**：配对要十几秒（SPAKE2 握手 + adb 启动 + 实际执行，
且端口可能还没出现），远超广播接收器的 10 秒超时（不 `goAsync()` 直接 ANR）。
收尾必须 `pendingResult.finish()`，放在 `finally` 里。

##### 密钥来源必须落盘记录（否则设置页会误报）

设置页原来只按「密钥文件在不在」显示「已导入 / 未导入」—— **这是错的**：
密钥有两个来源，而其中一个设备**尚未信任**。

| 来源 | 设备是否信任 | 用户该做什么 |
|---|---|---|
| `imported`（从 `/sdcard/dsh_adbkey` 搬来） | **是**（那是 PC 已授权的密钥） | 直接能用 |
| `generated`（App 现场生成） | **否** | 必须先配对 |
| `paired`（生成后又配对成功） | **是** | 直接能用 |

来源写在 `$DSH_HOME/adbkey.origin`，由 `markKeyOrigin()` 在**写入密钥那一刻**落盘。

⚠️ **不能靠 stderr 日志判断**：`importKeyIfNeeded()` 是幂等的，导入日志只在
**第一次**运行出现，之后再也看不到 —— 用它判断会在第二次探测时把
`imported` 误报成 `generated`。

⚠️ 标记文件是后加的，**老安装没有它**。`keyOrigin()` 此时返回 `null`，
设置页显示灰点「来源未知」，**故意不猜成「已导入」** —— 那正是要修的误报。

##### ⚠️⚠️ 绝不要主动发公钥 —— 那会**每条命令**都弹「允许 USB 调试吗」

**这是本项目最严重的一处设计错误，也是唯一一处「删掉功能」才算修好的地方。**

**症状**（真机 OPPO / Android 15）：只要 App 碰一次 adbd，屏幕上就弹一次
「允许 USB 调试吗」；用户点了「一律允许」下次照样弹。`shell` 工具因此
**完全不可用** —— 用户不可能为每条命令点一次对话框。

**根因**在 `handshake()` 的失败分支：签名认证失败时它**无条件**发
`A_AUTH / AUTH_RSA_PUBLIC`（把公钥推给 adbd）。而 adbd 收到公钥后**唯一**的反应
就是弹那个对话框 —— 于是每一次连接（每条命令、每次探测）都弹一次。

⚠️ **「一律允许」在 TCP 连接下不落盘**：Android 15 实测勾选后
`/data/misc/adb/adb_keys` 并不会留下我们的公钥，下次连接照样弹。
也就是说**降低频率没有意义，只能彻底不做**。

**实测结论：这条能力在本 App 的所有场景里都是负收益**（所以不是「少用」而是「删掉」）：

| 场景 | 主动推公钥的结果 |
|---|---|
| Android 10 + TCP | adbd **不弹框、静默挂起**，20 s 超时无任何响应 |
| Android 15 + TCP | 弹框，但「一律允许」不持久化 ⇒ 下一条命令再弹 |

两条路都不通。**能拿到信任的只有两条路**，都不经过「主动发公钥」：

1. **Android 11+ 无线调试配对**：6 位配对码走 SPAKE2，设备把我们的公钥写进
   keystore，此后是**设备认得我们**，不需要我们推。
2. **Android 10 用 PC 推一把已授权的密钥**（`adb push %USERPROFILE%\.android\adbkey`）。

**改法**：`handshake(key)` **删掉 `allowAuthorize` 参数、永不发送公钥**，
签名失败直接抛错（`ADB 密钥被设备拒绝（授权可能已被撤销），请重新配对` /
`ADB 密钥尚未被设备授权，请用无线调试配对授权它（Android 10 需先用 PC 执行
adb push 推入已授权的密钥）`）；`connect()` 同步删掉 `allowAuthorize`，
`AUTH_AUTHORIZE_TIMEOUT_MS` 整个常量删除。

⚠️ **`AUTH_RSA_PUBLIC = 3` 这个常量要留着，并标「不要修好它」**：协议完整性
（将来抓包分析要用），但**没有任何代码路径会发它**。看到这个常量别手痒去接上。

##### ⚠️ 信任标记存的是**公钥指纹**，不是一个 bool

`$DSH_HOME/adbkey.trusted` 里存的是**公钥 blob 的 md5**，不是 `true`。

⚠️ **不能存布尔值**：密钥可以被替换（导入 PC 密钥、重新生成、重新配对），
换了一把之后旧的布尔值仍然是 `true`，于是「设备认得这把钥匙」这个判断变成
**假阳性** —— 而假阳性的后果正是上面那个弹框（我们以为设备认得，实际上不认得）。

- `markKeyTrusted(key)` —— `handshake()` 成功那一刻调用。
- `clearKeyTrust()` —— `importKeyIfNeeded()` 写入新密钥后调用（新钥匙要有一次
  被授权的机会），以及 `--pair` 成功后由 `markKeyTrusted(paired)` 覆盖。

##### ⚠️ `keyMayBeAuthorized()`：进「会弹框的通道」之前必须先过这道闸

**这是「删掉发公钥」之后必须补上的一半。** 打包进来的 adb 是**外部进程**，
它会**自己**弹那个对话框 —— 我们拦不住它。所以只能在**进入那些通道之前**挡掉：

```js
// 通道 1.5（切 TCP 再走 legacy）与通道 2（内置 adb + TLS）都要带这个条件
keyMayBeAuthorized()
```

判据是「**设备已经认得这把钥匙**」，三条**任一**成立即可：

| 条件 | 为什么算 |
|---|---|
| `isKeyTrusted(key)`（指纹匹配） | 我们亲手在这台设备上认证成功过 |
| `origin === 'imported'` | 那是 PC 已经授权过的密钥 |
| `origin === 'paired'` | 配对时设备把公钥写进了自己的 keystore |

⚠️ **不能只看信任标记。** 会死锁在这个场景：用户刚导入/刚配对完一把密钥，
**还没成功连过一次**（所以标记文件还没写），此时若 5555 没在监听、
只剩无线调试那条路 —— 只看标记就会拒绝进入通道 2，而通道 2 恰好是唯一
能让它连上并写下标记的路。**「刚配对完」正是最需要放行的那一刻。**

⚠️ 这个函数必须**只读**：不导入、不生成、不写盘。它是守卫，不是初始化。

##### ⚠️ 无线调试开关一关，通道就断 —— 所以必须切固定端口

**这是本轮最关键的实测结论。** 无线调试的 TLS 端口**只在开关开着时才存在**，
而且**关掉开关会立刻掐断已建立的连接**：

```
settings put global adb_wifi_enabled 0
  → 已连上的 adb-…._adb-tls-connect._tcp 立刻变 device offline
```

也就是说「连上后一直用无线调试」这条路是**死的**。而 `adb tcpip 5555` 把 adbd
重启到 TCP 模式，此后走 `127.0.0.1` 回环：

| 观测项 | 关掉无线调试后 |
|---|---|
| `adb_wifi_enabled` | 0 |
| `service.adb.tcp.port` | 5555 |
| `ss -tln` | `LISTEN *:5555` |
| 经 `127.0.0.1:5555` 跑 runner | **`uid=2000(shell)`** |

⚠️ **两者可以并存，不是互斥的**（我原先判断错了）：Android 15 实测
`adb tcpip 5555` **不会**关闭无线调试（`adb_wifi_enabled` 仍是 1）。所以这是
**纯增益动作**，不破坏用户设置。

⚠️ **`persist.adb.tcp.port` 实测为空** → **重启手机后 5555 消失**，需要重切。
所以 `dsh-android-shell.mjs` 的通道级联里加了「1.5 档」：legacy 不通但无线调试
可用时**自动重切一次**再走 legacy。这样重启后能自愈，用户通常不用手动点。

⚠️ 切换**必须先有一条活着的连接**（`tcpip` 命令本身要通过 adb 发出去）。

通道级联的完整顺序（`main()` 里）：

```
1   legacy 127.0.0.1:5555        自实现协议，最快；不依赖无线调试开关
1.5 legacy 不通 + 无线可用 → 切 TCP 再走 legacy    ← 重启后自愈
2   无线调试 <NsdManager 端口>    内置 adb + TLS
3   本地执行（app 身份）          回落，不是错误
```

##### 内置 adb 的二三事

配对走 **SPAKE2 + TLS-PSK**，Node 的 `tls` 模块**不支持外部 PSK**，所以打包了
Termux 官方的 `android-tools`（`assets/adb-wireless-arm64.zip`，`adb 1.0.41`，
5.26 MB / 105 个文件，含约 90 个 `libabsl_*.so`）。解到 `filesDir/adbwire/`，
判据是「`bin/adb` 存在且非空」，**无需版本戳**（与 ripgrep/node-pty 同类）。

⚠️ **adb 的运行环境三个变量缺一不可**（都踩过）：

| 变量 | 为什么 |
|---|---|
| `HOME` | adb 在 **`$HOME/.android/adbkey`** 找密钥。推到 `$HOME/adbkey` 会**静默自生成**一把新的 |
| `LD_LIBRARY_PATH` | adb 是 Termux 的 ELF，要它自己的 `.so`。**只对 adb 需要** —— 引擎进程绝不能设（见上一节） |
| `TMPDIR` | adb 硬编码往 **`/tmp/adb.<pid>.log`** 写启动日志，app 域无权写 `/tmp`（实测 `cannot open /tmp/adb.10535.log: Permission denied` → server 起不来） |

⚠️ **adb server 必须用 `-L localabstract:<name>`** 指定 socket 名（实测
`ss -xln` 里能看到 `@dshadb`），否则多进程会抢同一个默认 server。

⚠️ **没有配对需求的设备会「假成功」**：若 PC 的 adbkey 已在设备 keystore 里，
App 直接用它就能连上，配对流程根本不会被走到。判断依据是比对 md5
（本次实测 App 的 `$DSH_HOME/adbkey` = PC 的 `%USERPROFILE%\.android\adbkey`
= `d1f60cbb147060bed588e744a79e360e`）。**要在真机上验证配对，必须先把密钥
删掉重装**，否则测的是「复用 PC 密钥」那条路。

##### 设置页的无线调试卡片

界面是**结论优先**的：顶部一张 head 卡（状态徽章 + 当前通道摘要 + 一条「下一步该
做什么」+ `重新检测` / `查看详情`），下面才是可折叠的明细行。无线调试卡片带
**「推荐」标签**，位置**在电脑引导卡片之前**（Android 11+ 不用碰电脑，那是更短的
路径）；电脑引导卡片与注意事项都收进 `<details>`，通道已就绪时整个引导折叠成
「开启引导（当前已就绪，一般不用看）」。

⚠️ 「下一步」是按状态级联挑出来的（已就绪 → 提示可切固定端口；密钥是
`generated` → 提示配对；有密钥但通道没通 → 提示切固定端口；否则 → 提示选一条路径），
按钮直接调对应的动作，不再让用户自己从一堆按钮里挑。

⚠️ 「无线调试端口」行是**三态**，不是两态：`绿灯=端口活着` /
`灰灯=端口文件里那个值已经连不上（多半是无线调试被关了）` / `关=没有端口`。
判据是宿主半真的去 `connect` 一次（`portAlive()`），**不是「端口文件里有没有数字」** ——
只看文件会把关掉无线调试后残留的旧端口显示成绿灯（这个 bug 真实发生过）。

⚠️ 「实际身份」行会显示**当前走哪条通道**（`固定端口（关无线调试也不断）` /
`无线调试（关掉开关会断）`）—— 判据是从 runner 自己打的 `通道: …` 行里读的，
**不额外探测**。不显示这个的话，用户看到「已就绪」也不知道关掉开关会不会断。

#### 密钥从哪来（Android 10）：只能用 PC 引导一次

Android 10 **没有**无线调试配对机制（那是 API 30+）。而且实测：adbd 在
**TCP 连接**下**不会**弹「允许 USB 调试吗」对话框 —— 发完公钥后 adbd 直接静默
挂起，永远等不到授权（20s 超时无任何响应）。

所以只能复用一把**设备已授权**的密钥。引导命令（用户执行一次）：

```powershell
adb push %USERPROFILE%\.android\adbkey /sdcard/dsh_adbkey
```

`dsh-android-shell.mjs` 首次运行时会自动导入并**立刻删除中转文件**：

```
[dsh-adb] 已导入 ADB 密钥: /sdcard/dsh_adbkey → .../home/.dsh/adbkey
[dsh-adb] 已删除中转密钥 /sdcard/dsh_adbkey（共享目录不留私钥）
```

⚠️ **中转文件必须删。** `/sdcard` 是所有应用可读的共享目录（实测
`-rw-rw---- root sdcard_rw`），ADB 私钥留在那里等于把设备控制权敞开放着。
实测 app 域**有权**删除它（`run-as … rm -f /sdcard/dsh_adbkey` 成功）。

⚠️ **公钥必须从私钥现场派生**，不要读 `adbkey.pub`：本机那个文件的格式**不标准**
（716 字节，缺 `ssh-rsa ` 前缀，且内容与私钥不匹配 —— 实测
`ssh-keygen -y -f adbkey` 与它不一致）。PC 的 adb 之所以能连上，是因为设备
`adb_keys` 里存的是**更早的另一把**公钥。派生才是唯一可靠来源。

#### 没授权时怎么办：两级降级

**第一级：通道本身没就位 → 工具根本不注册。**
`ensurePrivilegedShell()` 失败（资产缺失/落盘失败）时不设 `DSH_PRIV_RUNNER`，
插件在 `apply()` 里打一条 warning 后 `return`，`shell` 工具**不进工具表**；
`writePrivilegedPatch(false)` 同时删掉 overlay。此时模型看不到 `shell`，
而 `$DSH_HOME/AGENTS.md` 里写的是另一段文本（明确说「`shell` 工具没被注册，
别反复重试、也别试图绕过 app 身份」）。**`bash` 不受任何影响。**

**第二级：密钥没授权 → 逐条命令回落 app 身份。**
runner 存在但连不上 adbd（无密钥/密钥未授权/5555 没监听）时，
runner 自己**回落为本地执行（app 域）**，并把原因写进输出：

```
[dsh-adb] uid 2000 不可用（未找到 ADB 密钥（先用 PC 执行 adb push 导入））→ 回落为本地执行
```

⚠️ 这一级是**静默降级**：命令照样跑、退出码照样正常，只是身份变回了 app。
所以插件在 `DESCRIPTION` 里明确告诉模型「输出里出现 `[dsh-adb] uid 2000 不可用`
就说明这条命令实际是 app 身份跑的」，避免模型基于假前提继续推理。

#### ⚠️ 固定端口 5555 的可靠性与 18 条缺陷（2026-10-12 全面修复）

**用户症状**（三条，指向同一根因）：① 开无线调试拿到权限后**关掉 WiFi 权限就掉**；
② 关 WiFi 后权限没掉、但**清后台重开就掉**；③ 用 Shizuku 取权限 + **打开固定端口**后，
关 WiFi、关无线调试都**不掉**。

③ 是决定性证据：**正解是真正切到 5555 并守住它**，而不是「无线调试开关开着」这种
脆弱状态 —— 无线调试端口只在开关开着时存在，关掉开关立刻掐断已建立的连接。

场景 ② 的完整链条：没切成 5555 → 关 WiFi ⇒ 没有网络接口 ⇒ mDNS 发现必然失败 →
清后台被杀 → 重开时**没有任何东西**能拉起 5555。

⚠️ **判定「固定端口是否可用」的唯一权威判据是 `connect()` + `conn.shell('id -u')`
返回 `2000`**，不是「端口有人听」（`isPortListening` / `legacyAlive` 只验有人听，
拿不到 `2000` 也算成功，是**假阳性**）。`isPortListening()` 已删除。

本轮修掉的 15 条（`app/src/main/assets/dsh-android-shell.mjs` +
`WirelessDebugging.kt` + `EngineManager.kt` + `mobile-ui-plugin/dsh-android-mobile/`）：

| # | 缺陷 | 修法 |
|---|---|---|
| 1 | `switchToTcp` 只等 6 秒判 `legacyAlive()` ⇒ 切成功却报失败 | 新 `fixedPortWorks()` + `TCPIP_VERIFY_BUDGET_MS = 20_000` 轮询 |
| 2 | `--switch-tcp` 只读端口文件，而该文件在无线调试关闭时被删 | Kotlin 侧把 `knownPort` 作为 `argv[1]` 传下去 |
| 3 | 通道 1.5 / 2 以「端口文件有值」为门卫 ⇒ 循环依赖 | 去掉 `if (wlPort !== null)` 前置条件 |
| 4 | `startWatcher` 判据 `watcher != null`（线程死了也算在跑）且无看门狗 | 改 `watcher?.isRunning == true` + `ensureWatchdog()`（30 秒） |
| 5 | App 进程重启后陈旧端口文件不被清理 | `purgeStalePort()`：先 `Socket.connect(127.0.0.1, port, 700)` 探，连不上才删 |
| 6 | 全工程**零**周期性守护，无任何东西监听 5555 | `startFixedPortGuard()`：5 分钟一轮走完整 `ensureTcpPort`，连续 3 次失败退避到 15 分钟 |
| 7 | `EngineManager.start()` 早返回分支跳过整个自愈块（用户「清后台重开」命中的就是它） | 抽出 `resumePrivilegedChannel()`，早返回前也调 |
| 8 | `ensureTcpPort` 15 秒预算不够 | 先问 runner 再 mDNS 发现（13 秒），发现到的端口传下去 |
| 9/13 | `isPortListening` / `legacyAlive` 只验「有人听」 | 删 `isPortListening`，统一用握手 + `id -u` |
| 10/14 | `localabstract:dshadb-${pid}` 每条命令泄漏一个 adb server | 改 `ADB_SOCKET = 'localabstract:dshadb'`（去掉 pid） |
| 11 | 设置页把 4 类失败混成一句不可执行的原因 | runner 输出 `诊断: kind=… key=… legacy=… wireless=…`，宿主半 `DIAG_KIND_RE` 解析成 `identity.kind`，客户端按 kind 分支给「下一步」 |
| 12 | `keyMayBeAuthorized()` 的 `imported`/`paired` 短路绕过「删掉发公钥」那道闸 | `isKeyRevoked(key)` 检查**排在所有放行判据之前**；收到第二次 `A_AUTH` 即 `markKeyRevoked()` |
| 15 | 端口文件 `pairing` 字段恒 null 且无消费方 | 两侧收敛为单值：`readWirelessPorts()` → `readWirelessPort()` |
| 16 | 守护/看门狗线程可重入（共享布尔量当判据）⇒ 两个守护并存，各自周期往 adbd 发 `tcpip` 互相打断 | 循环判据改成**代际比对** `guard === Thread.currentThread()`；`stopXxx()` 先置空字段再中断旧引用 |
| 17 | `markKeyRevoked()` 无条件落盘 ⇒ 全新设备上「必然被拒」的 `generated` 密钥被误记成「授权已被撤销」，把设置页结论引向错误方向，还多叠一道只有配对才能解的闸 | 只在 `origin = imported\|paired` 或 `isKeyTrusted()` 时记（与 `keyMayBeAuthorized()` 的放行条件逐条对齐） |
| 18 | `importKeyIfNeeded()` 以「密钥文件在不在」为早返回判据 ⇒ **已撤销**（或损坏）的密钥被当成可用，用户「重新推一把密钥」这条补救路径被吞掉；**Android 10 上是死锁**（没有无线调试，配对那条路走不通） | 判据改成「有没有一把**设备可能还认**的密钥」（`generated` / 已撤销 / 解析不出来都继续）；并新增 `readKeyFileNoImport()` + `isStagedNewerThanDest()` 支撑「重新推入即解除撤销」 |

⚠️ **缺陷 16 的机理值得记一笔（`interrupt()` 打不断阻塞读）**：守护线程会卡在
`ProcessBuilder` 的 `forEachLine` 上读外部进程输出（最长 30 秒），`Thread.interrupt()`
**对它无效**。所以 `stopFixedPortGuard()` 置 `guarding = false` 之后线程并不会马上退出；
若此时用户又触发一次 `start()`，字段被置回 `true`，旧线程醒来看到 `true` 就**继续跑**。
判据改成「我还是当前这一代吗」之后，旧线程必然自己退出。
`ensureWatchdog()` 是同型问题（`stopWatcher` → `startWatcher`），已一并改。
另外看门狗重建 `AdbMdns` 时，「写字段 → `start()`」两行之间被 `stopWatcher()` 穿过
会 stop 一个尚未 start 的对象（等于没停）⇒ 监听常驻泄漏，故启动后补一次代际自校验。

⚠️ **缺陷 17 的判据要与 `keyMayBeAuthorized()` 逐条对齐**，别简化成「握手失败就记」：
`generated` 且从未配对成功的密钥被设备拒绝是**正常**的（全新设备就长这样），
`keyMayBeAuthorized()` 本来就会挡它。把它记成 revoked 有两个坏后果：设置页会说
「多半是你点过『撤销 USB 调试授权』」（用户没做过），以及多叠一道**只有配对/重新导入
才能解除**的闸 —— 而原本只需一次成功握手就能自愈。

⚠️ **缺陷 18 是 17 的「下游」**：17 修好「什么时候记 revoked」，18 修「记了之后怎么解除」。
两处合起来才是完整闭环，只修一处会在 Android 10 上留下死锁。判定条件是**三种而不是一种**
（`generated` / 已撤销 / 解析不出来），三者都允许被 `/sdcard/dsh_adbkey` 顶掉。
「重新推入」的判据用 **mtime 而不是内容**：内容相同时既可能是用户刚重推（要清撤销标记，
这是 Android 10 的唯一自救路径），也可能是上次 `unlinkSync` 没成功留下的残渣
（**不能**清，否则每次启动都会放行一次可能弹授权框的通道）。容差 2 秒适配 `/sdcard`
的时间戳粒度，取不到 mtime 时一律当作「不是新推的」。

⚠️ **`importKeyIfNeeded()` 里不能调 `loadKey()`**：`loadKey()` 第一行就是
`importKeyIfNeeded()`，直接无限递归（栈溢出，症状是启动即崩、看不出与密钥有关）。
所以额外提供了不做导入/生成的 `readKeyFileNoImport()`。

⚠️ **本轮的教训：这类纯判据逻辑可以离线断言，别拿真机试。** 把 runner 的源码
（剥掉末尾 `main().catch(...)`）当模块 import，配合 `DSH_HOME` 指向临时目录、
`C:\sdcard` 建目录模拟 `/sdcard`，就能把 17/18 的全部判据跑成 38 项断言，几秒出结果。
**这类断言真的抓到了东西**：`classifyFallback()` 的 `key-revoked` 与 `wireless-stale`
两条文案里带了全角 `）`，违反上面那条接口约束（会让设置页的原因显示被截断成半句）——
`node --check` 与编译都不会报，只有把正则从宿主半源码里抠出来跑一遍才发现。

⚠️ **新增守护引入的设计风险（已自纠，别改回去）**：固定端口守护每 5 分钟调一次
`switchToTcp`，而它要用内置 adb 连无线端口 —— **未授权密钥会被放大成「每 5 分钟弹一次
『允许 USB 调试吗』」**。所以 `switchToTcp` 里必须有 `keyMayBeAuthorized()` 守卫，
且 `ensureKey()` 要排在守卫**之前**（否则「还没有密钥」会误报成「未授权」）。
另外 `fixedPortWorks()` 的幂等短路要排在**密钥/内置 adb 检查之前** —— 否则
「5555 已就绪但密钥未授权」会被误判成失败。

⚠️ **回落文案的格式是接口**：`诊断: kind=` 必须与宿主半 `DIAG_KIND_RE = /诊断: kind=([a-z-]+)/`
逐字对应；`classifyFallback()` 产出的 text 里**不可出现全角 `）`**，否则会截断宿主的
`FALLBACK_RE = /uid 2000 不可用（(.+?)）/`。kind 取值：
`no-key` / `key-revoked` / `key-unauthorized` / `adb-missing` / `key-blocked` /
`wireless-stale` / `no-channel` / `unknown`（宿主半另有 `no-runner` / `timeout`）。

⚠️ **宿主半的探测超时必须覆盖 runner 的最坏路径**：`PROBE_TIMEOUT_MS` 从 15_000
提到 **45_000**（runner 最坏 ≈30 秒）。改 runner 的预算就必须同步改它，否则设置页
会把「还在切」误报成失败。

⚠️ **`startFixedPortGuard` 的闸是 `switching: AtomicBoolean`**：`ensureTcpPort()` 现在是
薄包装（CAS 失败返回「已有一次固定端口切换正在进行中」），主体在
`ensureTcpPortLocked()`；守护撞上 `switching.get()` 时 `continue`（**不计失败**），
否则会把「用户正在手动切」累计成 3 次失败而退避。

⚠️ **改了 `mobile-ui-plugin/` 就绕不开两个资产戳**（本轮都动过，别只改一个）：
`EngineManager.MOBILE_UI_VERSION` → `1.0.0-56`，且重跑
`tools/build-webview-compat.ps1`（`-CompatVersion` → `12`，戳变 `chrome74-v12`）。
**这两个 zip 是预打包资产，源码改了必须重打**，否则「编译通过但改动不生效」——
本轮真实踩到过一次（首轮构建后解包 APK 发现 `DIAG_KIND_RE` 不在里面）。
验证手法就是解包 APK 断言：`assets/mobile-ui.zip` 内应含 `诊断: kind=` 与
`PROBE 45000`，`assets/webview-compat.zip` 的戳应是 `chrome74-v11`。

#### ⚠️ 走这条通道会绕过沙箱（这是固有代价，不是缺陷）

uid 2000 **读不到 app 私有目录**（实测 `/data/user/0/com.dsh.android` 是
`drwx------`），而 `libboosandbox.so`（写沙箱）与 `liblinkcompat.so`
（link 转译）都放在那里 —— 所以经 adbd 执行的命令**必然**拿不到 `LD_PRELOAD`，
**写沙箱与 link 转译对它都不生效**。

也就是说：**shell 身份换来的是特权，失去的是隔离。**

⚠️ 因此**不能**用「首词白名单」之类的方式去猜哪条命令该走哪个域并自动切换 ——
那会让 `touch /sdcard/x` 这类写在不知不觉中绕过沙箱。**选择权交给模型**
（它显式选择调 `shell` 而不是 `bash`），调没调这个工具在会话里看得见，边界清清楚楚。

⚠️ **cwd 会丢**：adbd 执行命令的 cwd 恒为 `/`，而工作区 uid 2000 进不去。
所以 runner 用 `cd '<cwd>' 2>/dev/null; <command>`（**不是** `&&`）——
切不过去就留在 `/` 继续跑，而不是让整条命令失败。特权命令本就不该依赖
工作区里的相对路径。（插件只把 `workdir` 透传给 runner，且工具描述里写明
它只对 `/sdcard` 这类共享路径有意义。）

#### 系统提示词怎么告知模型

**两条通道，分工不同**：

1. **工具描述**（插件里的 `DESCRIPTION` 常量）—— 这是**主通道**。模型是靠工具
   描述来决定调不调 `shell` 的，所以「什么时候用、什么时候别用、退出码会骗人」
   三件事都写在那里。
2. **`$DSH_HOME/AGENTS.md`** —— 补充说明（完整的 app 域 vs shell 域对照表）。
   `@deepseek-ai/dsh-agent-instructions` 会自动读它并注入系统提示词，
   **零代码**的通道，不需要改引擎、也不需要挂 overlay。

`EngineManager.ensurePrivilegedPrompt()` 只管理自己那一段
（`<!-- dsh-android:privileged BEGIN -->` / `END` 之间），**标记之外的用户内容
原样保留**。通道不可用时写的是另一段文本（明确告诉模型「`shell` 工具没被注册，
别反复重试」）。

**为什么必须让模型知道**：上面那张表里的 `input`（rc=0 但无效）和
`dumpsys`/`ps`（rc=0 但残缺）都是**静默失败** —— 模型若不知道自己在哪个身份下，
会基于假前提继续推理、越走越错。

#### 验收手法（数值证据）

```powershell
# 插件解出来了没、工具注册了没（两行都要有）
adb logcat -d -s DSH:I | Select-String "uid 2000|特权工具|特权身份说明|dsh-android-shell"
#   → 已解出 uid 2000 执行通道 dsh-android-shell.mjs (21322 字节)
#   → 已安装 uid 2000 特权工具插件 dsh-android-shell (<N> 字节)
#   → 已写入 uid 2000 特权工具 overlay: shell-tool.patch.yml
#   → 已写入特权身份说明: AGENTS.md（通道可用=true）
#   → [engine] [dsh-android-shell] 已注册 uid 2000 特权工具 shell: /data/.../dsh-android-shell.mjs

# 工具真的进了模型可见的工具表（`shell` 应与 bash 并列出现）
adb shell run-as com.dsh.android cat files/home/.dsh/shell-tool.patch.yml
adb shell run-as com.dsh.android ls -l files/home/.dsh/shell-tool/dsh-android-shell/lib/index.js

# 通道本身能不能拿到 uid 2000（应输出 2000）
adb shell run-as com.dsh.android sh -c "DSH_HOME=/data/data/com.dsh.android/files/home/.dsh \
  /data/data/com.dsh.android/files/usr/bin/node files/dsh-android-shell.mjs 'id -u'"
#   → 2000

# 截图（app 域是 0 字节，这里应是几百 KB）
adb shell run-as com.dsh.android sh -c "DSH_HOME=... node files/dsh-android-shell.mjs \
  'rm -f /sdcard/r.png; screencap -p /sdcard/r.png; ls -l /sdcard/r.png'"
```

⚠️ **`bash` 的行为必须与改动前一致**：无论 GUI 里的权限预设怎么设，
`bash` 都应以 app 身份执行（`id -u` 给的是 `u0_a…` 而不是 `2000`）。
这是本次改动的核心断言 —— 若 `bash` 仍走 uid 2000，说明旧的 `--import` 加载器
还没删干净（检查启动命令行里是否还有 `--import`）。

#### 设置页的「特权权限」引导分节

用户不该去读 AGENTS.md 才会开这个权限。设置页左侧一级导航里有一项
**「特权权限」**（`dsh-android-mobile` 的客户端半注册的），布局是
**结论优先**，不是「先摆一堆步骤让用户自己对照」：

- **head 卡（默认展开）**：状态徽章（已开启 / 未开启）+ 一句话通道摘要 +
  **一条「下一步」**（带一个直达按钮）+ `重新检测` / `查看详情`。
- **明细行（折叠在「查看详情」里）**：执行通道 / ADB 密钥 / 实际身份 / 无线调试端口。
- **两条路径的引导卡片**：无线调试（Android 11+，带「推荐」标签）在前，
  电脑引导（4 步 + 两条带复制按钮的等宽命令）在后，各自收进 `<details>`；
  通道已就绪时**整个引导**再折一层（「开启引导（当前已就绪，一般不用看）」）。
- 注意事项卡片同样折叠。

⚠️ 结论优先是**用户明确要求的重设计**（原版是「徽章 + 三行状态 + 一堆按钮平铺」，
用户要自己判断该点哪个）。改回去之前先问。

⚠️ 折叠用原生 `<details>`，不引入任何 UI 依赖 —— 本插件的定位是「窄屏适配 +
补充面板」，不重写官方组件。

⚠️ **复制按钮不是锦上添花，是必需品。** 本插件的全局 CSS 是
`[data-mobile] * { user-select: none !important }`（文件头 4c，为了消灭长按弹菜单），
命令块**长按选不中**，而用户是在电脑终端里敲这两条命令、只能照着手机屏幕抄 ——
没有按钮就得逐字符手抄 `%USERPROFILE%\.android\adbkey`。
（内联 `user-select: text` 压不过 `!important`，别想靠那个绕。）

⚠️ **写剪贴板必须留降级路径。** 真机实测（Huawei CDY-TN90 / Android 10 WebView）：

| 路径 | 结果 |
|---|---|
| `window.isSecureContext` | **true**（`127.0.0.1` 算 trustworthy origin） |
| `navigator.clipboard.writeText` | 存在，但抛 **`NotAllowedError: Write permission denied`** |
| `document.execCommand("copy")` + 临时 textarea | **返回 true，内容正确落进剪贴板** |

也就是说**光看 `isSecureContext` 或「API 在不在」都会误判成可用**。
必须 `navigator.clipboard` 失败后回落到 `execCommand`。已用「复制后粘贴到临时
textarea 再读回」逐字节验证（`execCommand:true` → 粘贴内容与命令完全一致）。

⚠️ **测这个按钮不能用 `element.click()`**：合成点击**不算用户手势**，`execCommand`
会返回 `false`，于是测出「复制失败」的**假象** —— 本次调试中真的被它误导过一轮。
要用 CDP 的 `Input.dispatchMouseEvent`（`mousePressed` + `mouseReleased`）发真实鼠标事件。

实现分两半，**跨进程**：

| 半 | 文件 | 做什么 |
|---|---|---|
| 客户端半 | `dsh-android-mobile/lib/client.js` | 注册 `settings.section`（`id: "dsh-android-privileged"`，`order: 25`），渲染分节 UI |
| 宿主半 | `dsh-android-mobile/lib/index.js` | `GET /api/dsh-android/privileged` 返回状态快照 |

浏览器进程读不到 `process.env` / 文件系统，所以状态必须由宿主半提供。
宿主半用 `ctx.webServer.register({kind:"exact", path, handler})` 挂路由
（`inject: ["webServer"]`）—— 这是引擎给插件留的自定义 HTTP 接缝。

⚠️ **状态判据必须是「端到端跑一次 `id -u`」，不能是「`adbkey` 文件在不在」。**
密钥文件存在但未被 adbd 授权（设备 `adb_keys` 里没有对应公钥）时，通道照样
拿不到 uid 2000 —— 只查文件会得到**假阳性**。所以宿主半实际 spawn 一次
runner 跑 `id -u`，看它回什么；文件存在性只作为定位用的分解信息。
探测会顺带触发 runner 的密钥导入，所以点「重新检测」就等于走完引导最后一步。

⚠️ **`slots.register` 要求槽位已被声明**，而 `settings.section` 由
`dsh-client-ui-settings-general` 的 `settings.root` factory 在 `children` 里声明。
用 `ctx.slots.inject("settings.section", …)` 等它 —— 声明已存在时同步执行，
否则在声明提交后执行。

⚠️ **`ctx.locale` 必须从 `ctx.inject` 的回调参数取，不能从外层 `ctx` 取。**
Cordis 的 `ctx.locale` 要求**当前 fiber 声明过** `locale` 依赖，否则抛

```
Error: cannot get property "locale" without inject
```

本插件的 `inject` 是空数组（客户端半刻意不声明依赖，避免某个服务缺失就整个不激活），
所以外层 `ctx.locale` **一定**不可用。正确写法是 `ctx.inject(["slots","locale"], (scope) => …)`
然后用 `scope.locale` / `scope.slots`。

> 官方 `ui-approval` 写的是 `ctx.locale.bind(NS)`，那是**因为它的 `inject` 数组里
> 声明了 `locale`**。照抄那一行在本插件里必炸 —— 这里踩过一次。

⚠️ **异常会变成全局未捕获异常，`try/catch` 捕不到。** `slots.inject` 内部对回调
异常的处理是 `queueMicrotask(() => { throw failure })`，所以「分节没出现、
日志里什么都没有」是典型症状。排查手法：在回调**内部**包 try/catch 并
`console.warn`（页面 `console` 会转发到 `DSH` tag，前缀 `[web]`）。

**验收手法**（数值证据）：

```powershell
# 插件版本要变（改了 mobile-ui-plugin 必须 bump MOBILE_UI_VERSION）
adb logcat -d -s DSH:I | Select-String "移动端 UI 插件"
#   → 已安装移动端 UI 插件 dsh-android-mobile 1.0.0-19 (<N> 字节)

# 状态接口（用 app 自己的 node 访问，设备上没有 curl）
adb shell "run-as com.dsh.android sh -c 'cp /data/local/tmp/probe.mjs files/p.mjs; \
  /data/data/com.dsh.android/files/usr/bin/node files/p.mjs; rm -f files/p.mjs'"
#   → {"runner":{"ok":true,"size":21322},"key":{"ok":true,"size":1732},
#      "identity":{"ok":true,"uid":"2000"}}

# 分节真的进了导航、二级页真的渲染（走 CDP）
adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>
# 在页面里查：navButtons 应含 "特权权限"，点进去 content.innerText 应含
# "adb tcpip 5555" / "adb push" / "uid 2000"
```

⚠️ **改了 `mobile-ui-plugin/` 必须同时做两件事**：重新打 `mobile-ui.zip` +
bump `EngineManager.MOBILE_UI_VERSION`。漏 bump 的后果是**静默的** ——
版本戳没变 → 解包被跳过 → 新代码永不生效（本次调试中真实踩到过一次）。

⚠️ **重打 zip 必须先删旧文件，不能用 `-Force`。** PowerShell 的
`Compress-Archive -Force` 对**已存在的归档不会替换里面的条目**（会保留旧内容），
于是打出来的 zip 里是**上一版代码**，而版本戳已经 bump 过 → 解包照常执行 →
装进去的是旧代码。症状是「日志说装了新版本，但新代码一点没生效」。

```powershell
Remove-Item app\src\main\assets\mobile-ui.zip -Force
Compress-Archive -Path mobile-ui-plugin\dsh-android-mobile\* `
  -DestinationPath app\src\main\assets\mobile-ui.zip
```

⚠️ **验证 zip 里真的是新代码**（别只看文件大小）：

```powershell
# 把 zip 解开 grep 新加的函数名/文案，计数应为非 0
```

⚠️ **别在 `MP_PRIV_S` 这类对象字面量里引用它后面才定义的常量。**
本插件用一堆 `MP_PRIV_S.xxx` 内联样式对象，而 `MP_PRIV_TONE` 定义在
`MP_PRIV_S` **之后** —— 在 `MP_PRIV_S` 里写 `color: MP_PRIV_TONE.on` 会在
**模块求值期**抛

```
Cannot access 'MP_PRIV_TONE' before initialization
```

整个插件 import 失败，界面是空白的「Failed to load plugins」页
（日志里是 `dsh-android-mobile: import failed: …` +
`web boot: 1 entry did not activate`）。**颜色值直接写字面量**，
`MP_PRIV_S` 里那几处 `MP_PRIV_TONE` 只允许出现在注释里。

#### ⚠️ 不要设 `LD_LIBRARY_PATH`（它只会破坏系统二进制）

启动引擎的 env 里**刻意没有** `LD_LIBRARY_PATH`。这一行曾经存在（当作「RUNPATH 没覆盖
到时的兜底」），但它是**净负面**的：

| | 带 `LD_LIBRARY_PATH=$PREFIX/lib` | 不带 |
|---|---|---|
| `$PREFIX/bin` 下 **305 个 ELF** 全量对比 | —— | **0 差异** |
| `screencap` / `input` / `app_process` | **rc=127 `CANNOT LINK EXECUTABLE`** | 正常（该失败还是失败） |

原因：Termux 二进制的 RUNPATH 已被 `patchElfInPlace()` 等长重写成
`/data/data/com.dsh.android//usr/lib`，**自己就找得到 .so**，根本不需要这行；而系统
二进制本来该去 `/system/lib64` 找依赖，被这行带去 Termux 的 lib 目录后，加载到 Termux
的 `libz.so` 而不是系统的，于是报

```
CANNOT LINK EXECUTABLE "screencap": cannot locate symbol "CrcGenerateTable"
  referenced by "/system/lib64/libunwindstack.so"
```

⚠️ **危害是误导而不是功能缺失**：`screencap` 在 app 域本来就因权限失败，现在失败得更早、
错误信息指向动态库，模型会被带偏去反复试 `env -u LD_PRELOAD` 之类的无用方向 ——
真机会话日志里实测发生过（连试三轮才放弃）。

去掉后实测正常：node 本体、4 个原生模块（`node-addon-require-builtin` / flock /
`node-pty` / `sharp-wasm32`）、`bash` / `dpkg` / `pnpm` / `rg` / `apt` / `tar` / `curl`
全部照常，`LD_PRELOAD=liblinkcompat.so` 也照常生效（link 转译实测仍 OK）。

#### 跨身份文件交接：`/sdcard` 要授权，否则绕道 app 自己的外部目录

`shell`（uid 2000）在 `/sdcard` 上建的文件属主是 **`root:sdcard_rw`、模式 `640`**，
`chmod 666` **改不动**（sdcardfs 的固定行为）。而 app 进程的附加组里没有 `sdcard_rw`
（实测引擎进程 `Groups: 3003 9997 20324 50324`），于是 `shell` 截的图 `bash` 读不了。

**机制**（真机 A/B，同一个 pid、同一次进程生命周期内切换）：

| 观测项 | 未授权 | 已授权 |
|---|---|---|
| `READ/WRITE_EXTERNAL_STORAGE` | `granted=false` | `granted=true` |
| 进程 `Groups` | `3003 9997 20324 50324` | **完全没变** |
| `/storage/emulated` 绑定视图 | `gid=1015(sdcard_rw), mask=6` | **`gid=9997(everybody), mask=7`** |

对照 launcher（无写权限）：`gid=9997, mask=23`。

⚠️ **机制不是「授权后进程多一个 GID」** —— `Groups` 一个字节都没变，
`platform.xml` 里这两条权限也确实没有 gid 属性。真正变的是 **sdcardfs 按权限把
`/storage/emulated` 绑到不同的 runtime 视图**：未授权落在 `default` 视图
（gid=1015，进程没这个组 → 读不了），授权后切到 `write` 视图（gid=9997，进程自带 →
组对上），再配合 `derive_gid` 把底层文件的 gid 1015 派生映射成视图的 gid 9997。
**授权是唯一钥匙，`chmod` 绕不过去。**

做法：manifest 声明 `READ_EXTERNAL_STORAGE` + `WRITE_EXTERNAL_STORAGE`，
`MainActivity.ensureStorageAccess()` 在启动时申请一次（已授予则静默返回）。
targetSdk = 28 走 legacy 模型，不需要 `MANAGE_EXTERNAL_STORAGE`。

⚠️ **用户拒绝也不阻塞**：退回原状而已 —— 跨身份交接绕道 app 自己的外部目录
（`/sdcard/Android/data/com.dsh.android/files/`，那里属主是 app 自己，
`shell` 写进去后 `bash` 读得到）。这条备用路径在真机会话里被模型自己摸索出来过
（连试 4 轮），值得写进工具描述。

⚠️ **调试时注意 `pm revoke` 要带 `--user 0`**，否则静默不生效；
而 `run-as` 的组集合含 `sdcard_rw`(1015)/`sdcard_r`(1028)、**不含** `9997`，
与真实引擎进程**正好相反** —— 用 `run-as` 验证跨身份读取会得到**假阳性**。

**已知代价**（如实记录）：
- **Android 10 每次重启后要重新引导**：`persist.adb.tcp.port` 实测为空，
  重启后 5555 不再监听，需要 PC/USB 再 `adb tcpip 5555` 一次。
  这是平台限制（Android 10 没有无线调试），改不了。
- **Android 11+ 可以完全免 PC** —— 走无线调试配对，见上一节
  「密钥从哪来（Android 11+）」。配对成功后切到固定 TCP 端口，此后**关无线调试、
  关 Wi-Fi 都不影响**（实测）。
- **卸载重装会清掉 `$DSH_HOME`**（Termux 环境、全部会话日志、`adbkey`），
  重装后要重新 bootstrap。Android 10 要再用 PC 导入一次 adbkey；
  Android 11+ 只需重新配对一次（也是手机上点几下，不用电脑）。

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

### ⑫.2 悬浮窗接进 DSH 插件生态 —— 页面里的插件就能改悬浮球

目标：**让 AI 改悬浮窗像改自己的 web 界面一样简单** —— 写个插件、
`window.DSHAndroid.bubbleApply(...)` 一行，悬浮球就变样，原生侧零改动。

```
插件(client.js) ──window.DSHAndroid.bubbleApply(json)──> 页面桥
                                                        ├─ system: @JavascriptInterface（同步）
                                                        └─ gecko : native messaging（异步）
                                                              ↓ PageBridge
                                                    MainActivity.StatusBridge
                                                              ↓
                                                    FloatingBubble.applyAppearance()
                                                              ↓
                                                    BubbleAppearance.merge + 落盘 + 重绘
```

#### 三个页面侧方法（两个 flavor 都暴露，方法名完全一致）

| 方法 | 语义 |
|---|---|
| `bubbleApply(json): Boolean` | 合并下发外观字段。返回 `true` = **已接受**，不代表视图已重绘 |
| `bubbleAppearance(): String` | 读回当前外观 JSON（同步），供插件做「读-改-写」 |
| `onTranscript(json)` | 上报会话转录（原生侧自己切主线程，页面不用管） |

页面侧另有 `bubbleState()` / `rootState()` 等既有方法，见 `PageBridge` 接口
（现共 **16 个**；`GeckoWebHost` 里那句「13 个方法」的注释是旧文案）。

#### 外观字段一览（`BubbleAppearance`）

⚠️ **是分层模型，不是枚举模型。** 早期版本只有一个 `shape` 枚举 + 一张图片，
于是 AI 无法满足任意诉求（"背景透明但图片不透明"、"把球做成星形"都表达不出来）。
现在分三层，**三层的透明度相乘**：

```
形状层   shape / shapePath / bg / bg2 / border / bgOpacity
内容层   avatar / frames / avatarOpacity / avatarScale
动效层   spin / pulse
```

| 字段 | 取值 | 说明 |
|---|---|---|
| `state` | `auto` / `expanded` / `collapsed` / `hidden` | `auto` = 老行为（只在后台显示）；其余三态**前台也保持** |
| `shape` | `circle` / `square` / `rounded` / **`path`** | 球的形状；`path` 用下面的 `shapePath` |
| `shapePath` | SVG path 的 `d` | **任意形状**（星形/心形/六边形/…）。坐标是 `shapeViewBox` 单位，不是 dp，所以同一串能适配任意 `size`。上限 `MAX_SHAPE_PATH_CHARS = 4000` |
| `shapeViewBox` | `minX minY width height` | 默认 `0 0 100 100` |
| `size` | dp | 球边长（默认 44） |
| `cornerRadius` | dp | `rounded` 的圆角 |
| `opacity` | 0.05–1 | **整体**不透明度（三层之上再乘一次） |
| `bgOpacity` | 0–1 | **只压形状层**。`0` = 背景全透明、只剩图片 |
| `avatarOpacity` | 0–1 | **只压内容层** |
| `avatarScale` | 0.2–2.0 | 内容层缩放；>1 溢出球、由形状裁剪（这就是"放大裁切"） |
| `bg` / `bg2` | `#RRGGBB` / `#AARRGGBB` | 底色 / 渐变第二色（留空 = 纯色） |
| `border` / `borderWidth` | 色 / dp | 描边 |
| `avatar` | `default` / `none` / data-URI | 头像（`none` = **完全不要图片**） |
| `frames` | data-URI 数组 | **轮播帧**（每项一帧）；`[]` = 清空轮播 |
| `frameDurations` | int 数组 | 每帧时长（ms），与 `frames` 同序；缺省用 `frameIntervalMs` |
| `frameIntervalMs` | ms（20–60000） | 统一帧时长，默认 400 |
| `frameLoop` | int | `-1` = 无限（默认）；`N>0` = 播 N 轮后停在最后一帧 |
| `spriteRows` / `spriteCols` | 0–32 | **精灵图网格**：`frames` 只给一张大图，按 `rows×cols` 行优先切片 |
| `spin` | -720–720 | 每秒旋转角度（负 = 逆时针），0 = 不转。作用在**内容层**，形状层不动 |
| `pulse` | bool | 呼吸缩放动画 |
| `panelWidth` / `panelHeight` | dp | 展开面板尺寸（`panelHeight` = **总高**，0 = 自适应内容） |
| `panelBg` / `panelText` / `panelAccent` | 色 | 面板配色 |
| `statusAutoHideMs` | ms | 状态横条自动隐藏（≤0 = 永不隐藏） |
| `edgeSnap` | bool | 是否启用「拖出屏幕自动贴边」 |

**常见诉求 → 字段组合**（插件描述里也给了模型同一张表）：

| 用户说 | 下发 |
|---|---|
| 背景透明、图片留着 | `{bgOpacity: 0}` |
| 去掉图片 | `{avatar: "none"}` |
| 做成星形/心形 | `{shape: "path", shapePath: "M50 3 L61 38 … Z"}` |
| 让它转起来 | `{spin: 90}` |
| 轮播几张图 | `{frameFiles: [...], frameIntervalMs: 200}` |
| 用精灵图做动画 | `{frameFiles: ["sheet.png"], spriteRows: 4, spriteCols: 4}` |

`merge()` 是**逐字段**合并：只写想改的字段，其余保持原值。落盘在
`SharedPreferences("dsh_bubble")` 的 `appearance` 键；头像字节落
`filesDir/bubble-avatar.bin`（`readAvatar`/`writeAvatar`，上限
`MAX_AVATAR_BASE64 = 700_000`）；轮播帧字节落 `filesDir/bubble-frames/`（见下）。

#### ⚠️⚠️ 加字段时 `save()` 与 `merge()` 必须同步（踩过的静默丢数据 bug）

`load()` 的实现是「读 SharedPreferences 的 JSON → 走 `merge()`」，所以：

- `merge()` 认的键，`save()` **必须写出来**，否则该字段只在本次运行有效、
  重启即回默认 —— 用户看到的是「AI 改的时候看得见，重开 App 就没了」。
- 反过来，`save()` 写了但 `merge()` 不认，重启后一样丢。

两个函数各有一处 `put(...)` / `xxxOf(o, ...)` 列表，**加字段要一起改**。
`frameCount` 尤其容易漏：漏了它重启后帧数读回 0，磁盘上那堆帧文件再没人引用，
表现为「重启后动画没了」。

#### ⚠️ 球的形状可以不是圆的 —— `BubbleShapeDrawable`

`shape:"path"` 时用 `androidx.core.graphics.PathParser` 解析成 `Path`，
`onBoundsChange` 里做等比缩放 + 居中（viewBox 空间 → 视图空间）。

- `getOutline()` 走 `Outline.setPath`（API 30+）；API 29 及以下只有
  `setConvexPath`，**非凸 path（星形、心形）会失败**，此时退回矩形 —— 那是
  **优雅降级**：阴影变方，形状本身照常正确。
  ⚠️ `setConvexPath` 返回 **void** 不是 boolean，判断"成没成"只能看
  `outline.isEmpty`。
- `setAlpha` **不能**把 alpha 乘进渐变分支：Skia 里 shader 的 alpha 与 paint 的
  alpha 本来就相乘，再乘一次会双重衰减（`#80` 的渐变会淡到看不见）。
- `getOpacity()` 恒返回 `TRANSLUCENT`：它是"最坏情况"声明，声明成不透明时
  背景透明会画出黑块。

`clipToShape()` 只在 `shape:"path"` 时给球设 `clipToOutline`，让图片跟着形状裁。
⚠️ 非凸 path 在 API 29 及以下无法作为 outline，此时**不裁剪**（宁可"图没被裁"
也不要"整个球不显示"）。

#### ⚠️ 轮播帧：两种存储模式，写入是全有或全无

`BubbleFrames`（`filesDir/bubble-frames/`，`META = meta.json`）：

| 模式 | 磁盘上 | 帧数 |
|---|---|---|
| 普通轮播（`sprite == null`） | `0.bin` … `N-1.bin`，每帧一张完整图 | = 文件数 |
| **精灵图**（`spriteRows/Cols > 0`） | **只有 `0.bin`** —— 那是整张大图 | = `rows × cols` |

⚠️ **精灵图模式下 `loadFrame(index)` 必须无条件读第 0 个文件再切片**，
不能去读 `index.bin`（它不存在）—— 漏了这条，球在精灵图模式下永远是空白。

⚠️ 只改 `spriteRows`/`spriteCols` 而不重传 `frames` 时，走
`BubbleFrames.rewriteSprite()` 只改 meta 不动字节；**普通轮播模式下没有"整图"
可重切**，返回 null，`merge()` 会退回整图模式并打日志（静默用错网格会让球显示
成网格里的碎块，比不显示更难查）。

⚠️ `write()` 先**全部解码**再落盘，然后写 `$DIR.tmp` → 原子改名（旧目录先挪成
`.old` 做备份）。任何一步失败都不动已有帧集 —— 半写好的帧集渲染出来是碎图。

⚠️ `MAX_FRAMES = 64`、`MIN_DURATION_MS = 20`、`MAX_DURATION_MS = 60_000`。
插件侧另设 `MAX_FRAMES_BASE64 = 6 MB` 总量闸：帧以 data URI 进 JSON，原生侧要
`readText()` 整个文件再 `JSONObject(...)`，64 帧 × 700 KB ≈ 45 MB 足以让 App 被
LMK 杀掉，而症状是「球突然消失、什么都没发生」。

#### ⚠️ 动效必须用两个独立的 `ObjectAnimator`

`spin` 与 `pulse` **不能**用 `view.animate()`（`ViewPropertyAnimator`）：它对同一个
View 是**单例**，后一次 `.start()` 会取消前一次 —— 两个动画同时打开时只有一个生效，
而且看不出是谁把谁取消了。且不能用 `withEndAction` 递归实现无限循环：递归入口有
"已在跑就不重启"的守卫，会把它自己挡住，转完一圈就停。

正确写法：`ObjectAnimator` + `repeatCount = INFINITE` + `repeatMode = RESTART`
（旋转）/ `REVERSE`（呼吸）。旋转终值必须是**正好一整圈**（±360），否则每轮之间
会跳一下。呼吸的 `SCALE_X` / `SCALE_Y` 各一个动画、时长与相位完全一致，否则会拉成椭圆。

#### ⚠️ 改尺寸后夹取不能读 `root.width`

`applyAppearance()` 改完 `layoutParams` 时布局**还没重测**，`root.width` 仍是
**旧**尺寸（可能是贴边态的 6dp，也可能是上一版的小球）。拿它算夹取范围，AI 一把球
改大，球就跑到屏幕右/下边缘外，用户看到的是「球消失了」。
收起态直接用 `dp(appearance.size)` 算；展开态尺寸由面板决定，`root.post{}` 里再夹一次。

#### AI 主动推送状态消息（`action:"notify"`）

与 `set` 是**两条独立通道**：`notify` 只影响这一次显示，**不改持久配置**
（否则下次启动气泡上会挂着上次推送的旧话）。推送期间引擎状态让位
（`refreshStatusBubble` 里 `pushedStatus` 优先），推送自带停留时长，`sticky` = 常驻。

⚠️ 横条自动消失时**必须一并清掉推送标记**：不清的话下一次引擎状态刷新（每几秒一次）
会把同一条推送重新显示出来，用户看到的是"这条消息消失又冒出来、消失又冒出来"。

⚠️ 气泡没显示时 `notify` 返回 `false` 而不是先把它显示出来：`state:"hidden"` 是插件
明确要求"别出现"，为一条推送打破它是越权。

#### ⚠️⚠️ 自定义头像必须能用**落盘标记**读回来（踩过的静默丢数据 bug）

`avatar` 字段在**两条路径上不是同一个值**，这是本处最容易漏的地方：

| 时点 | 值 |
|---|---|
| 插件下发 | `data:image/png;base64,…`（几百 KB） |
| `save()` 落盘 | **`bubble-avatar.bin`**（只留文件名标记，base64 单独落盘） |

`avatarOf()` 的合法值白名单原本只写了 `default` / `none` / `data:image/…`，
于是**回读时把 `bubble-avatar.bin` 判成非法值退回 `default`**。两个后果：

1. **自定义头像重启即丢** —— 图片文件还在 `filesDir`，但配置里再没人引用它；
2. `bubbleAppearance()` 的回读值与 `bubbleApply()` 的下发值**不一致**，
   插件做「读-改-写」时会把头像悄悄洗掉（读回 `default`，写回 `default`）。

修法：白名单补上 `AVATAR_FILE`，并**核对文件真的存在**（用户清数据后不能留悬空引用）。
`merge()` / `avatarOf()` 因此各多一个可空 `context` 参数（纯解析场景传 null = 当作文件在）。

同一处的第二个坑：**下发 data URI 那一帧不显示自定义头像**。视图判定走
`usesCustomAvatar`（即 `avatar == AVATAR_FILE`），而 `applyAppearance` 拿到的是 data URI。
所以 `writeAvatar()` 成功后必须**把内存里的 data URI 换成文件标记**，否则表现为
「AI 换了头像但没反应，重启才看见」。落盘失败（不是有效位图）时退回改动前的头像值，
不能留一个指向不存在文件的引用。

真机验收（system 包）：下发 1×1 PNG 的 data URI → 立刻回读 `avatar:"custom"`
（修复前是 `default`）→ `am force-stop` 重启后仍是 `custom` → 读-改-写只动 `size`
时 `avatar` 保持 `custom` → 显式传 `default` 能切回内置、再传标记能切回自定义。

#### ⚠️ 新增桥方法要**三处一起改**，漏一处是静默失败

`system` 与 `gecko` 的内核实现分别在 flavor 源集里，**没有共享基类**，所以每个新方法都要：

1. `app/src/main/java/com/dsh/android/web/PageBridge.kt` —— 接口声明；
2. `app/src/system/java/.../SystemWebHost.kt` —— `@JavascriptInterface fun xxx(...)`；
3. `app/src/gecko/java/.../GeckoWebHost.kt` —— `dispatchInvoke()` 里加分支
   **并调 `pushSnapshot()`**（页面侧是**同步读快照**，不推送的话插件读回的是旧值）；
4. `app/src/gecko/assets/dsh-bridge/content.js` —— 在页面世界暴露同名函数，
   **并 bump `manifest.json` 的 `version`**（否则 GeckoView 继续用旧扩展）。

⚠️ gecko 侧的 `BRIDGE_INSTALLER_SOURCE` 是**模板字符串**：里面出现反引号会提前
终止模板，症状是整段 content script 不执行（见 ⑦.7 的三个注释陷阱）。

#### 转录通道：为什么另开一条路，不加进通知栏那条

状态通道（`mpReportStatus`）的正文上限 400 字，只够放"当前在做什么"。完整转录
（用户消息 + 每条助手回复 + 每次工具调用）**大两个数量级**，混进同一条通道会把
通知栏那条挤爆。

所以另开 `onTranscript`：页面侧 `mpCollectTranscript()` 采集 → `mpReportTranscript()`
**指纹去重**（内容没变一条都不发）→ `mpScheduleTranscript()` 1500ms 节流 →
另有 5000ms 兜底轮询。上限 `MP_TRANSCRIPT_MAX_ITEMS = 80` /
`MP_TRANSCRIPT_MAX_CHARS = 2000`（超出保留**最后** 80 条 —— 面板要看的是当前进展）。

采集范围限定 `.wSkVaW_viewArea`（取不到退回 `document`），按 selector 逐节点取文本。

⚠️ **工具行的判据是 `[data-chat-flow-kind="tool-call"]`，不是 `[data-tool]`。**
这一点踩过：`data-tool` 是 `dsh-client-ui-tool` 的标记，而本页面渲染工具行的是另一套，
真机全文档 `[data-tool]` 命中数**恒为 0** —— 只认它的后果是「工具调用一条都采不到」，
而其它条目都正常，所以很容易误判成"没有工具调用"而不是"选择器错了"。
真实形状（CDP 读活会话）：

```html
<div data-chat-flow-kind="tool-call" data-turn-process-member="true"
     data-turn-process-hidden="true" hidden="until-found">
  <div class="ztWv_q_callRow">
    <div class="CY-8Ka_card">
      <div class="CY-8Ka_root" data-sample="bash" data-state="ok" data-expandable="true">
        …「运行命令」+ 摘要「Echo TOOL_OK to test output」…
```

⚠️ **`data-sample`（工具名）与 `data-state` 长在内层 `[data-sample]` 节点上**，
不在 `[data-chat-flow-kind]` 那个外层 div 上 —— 直接读外层会全空。
`data-state` 取值 `ok` / `running`（后者映射成面板上的"运行中"）。

⚠️ **工具行处在折叠态（`hidden="until-found"`）也要采**：`textContent` 对 hidden
元素仍可读，而用户要的正是「展开面板能看到调了哪些工具」，折叠时恰恰最需要补这条。

完整选择器（`MP_TRANSCRIPT_SELECTOR`）：

```
[data-chat-flow-kind="user"],
[data-chat-flow-kind="steering"],
[data-chat-flow-kind="assistant-step"],
[data-chat-flow-kind="tool-call"],
[data-chat-flow-kind="turn-error"],
[data-tool]
```

⚠️ **`steering` 是用户在回合进行中追加的指令**，按用户消息显示（官方 chat.js 定义了这个
kind，但默认视图里不出现，所以要显式列进选择器才采得到）。

⚠️ **`turn-process` 与 `turn-tail` 刻意不采**：前者是「已完成，用时 9秒」那个折叠按钮
（纯耗时摘要，不是对话内容），后者是「用量 13.3K tok / 01:15」的页脚。两者都不属于
"微博里能看到的回复内容"，采进来只会让面板多两行噪音。

原生侧 `ChatTranscript.parse()` **永不抛异常**（畸形 JSON 只是解析成
空列表），畸形输入不能让悬浮球崩掉。

⚠️ **取文本必须先剔除元数据子树**（`mpTranscriptVisibleText()`）：官方把时间戳与
复制按钮放在同一条消息尾部，直接取 `textContent` 会得到
`只回复两个字：通了01:14` —— 时间戳粘在用户原话后面，看起来像用户自己打的字。
判据用 **`[data-clock]` 属性 + `button`/`svg` 标签**，不用哈希类名
（`xzv4MW_actions` 这种会随官方发版变，属性不会）。取不到就退回原文
（多带点噪音，不丢内容）。

⚠️ 助手回复**收窄到 `.hWmORq_root`**：官方 `AssistantMarkdown` 对 tool-call 块是
直接 `break` 跳过的（不渲染进 body），所以这里取到的是纯正文，不会把工具标题混进
助手回复里。这个前提一旦变了（官方改成渲染进 body），助手条目就会掺进工具标题。

#### 验收（真机，system flavor / CDPY-TN90 / Android 10）

四条必改行为逐条实测通过：

| 行为 | 判据 |
|---|---|
| 状态条 2 秒自隐 | 推状态后 0.7s 截图有横条，3.7s 截图已消失 |
| 拖出屏幕贴边 | `adb shell input swipe` 拖出右边界 → 日志 `气泡已贴边折叠: side=1 x=1062 y=963`，窗口 `Requested w=18` |
| 点贴边条弹回 | 日志 `气泡已从贴边态弹出: side=1 x=888 y=963`，随后 `expand()` 到面板态 |
| 面板拖动 | 拖顶栏 → `mAttrs` 由 `(120,963)` 变 `(120,628)` |
| 面板缩放 | 拖底手柄 → `Requested w=960 h=1062` → `623 x 1436`，落盘 `panel_w=623` / `transcript_h=867`；收起再展开仍是 `722 x 1384`（持久化生效） |
| 插件改外观 | `bubbleApply({"shape":"rounded","size":64})` → 返回 `true`，日志 `插件下发悬浮球外观: ...` + `气泡已显示: (888, 963) shape=rounded size=64` |
| 完整转录 | 面板里同时有用户消息、助手回复（含思考）、**工具调用行**，颜色/对齐正确 |
| 工具调用行 | 真机发一条「用 bash 工具执行 echo TOOL_OK」→ 面板出现「工具调用 / 运行命令 Echo TOOL_OK to test output」。⚠️ 判据是 `[data-chat-flow-kind="tool-call"]`，`[data-tool]` 在本页面恒为 0（见上） |
| 时间戳剔除 | 面板用户条目为 `只回复两个字：通了`（无 `01:14`） |

gecko flavor 同样实测通过：`bubbleApply` 走 native messaging 生效（面板被插件改过的大小
与配色在 gecko 包上原样重现），转录经桥到达原生 —— **这条只能走 native messaging，
没有第二条路，所以它的通过本身就证明了 gecko 桥的三个新分支都是通的**。

⚠️ **`input tap` 的 y 坐标要按 `mFrame` 算，不是 `mAttrs` 的 y**：实测 `mAttrs` 给
`y=963`，而 `mFrame` 是 `[888,1071]` —— 差 108px（状态栏）。按 `mAttrs` 点会**点在球
上方 108px 处**，表现为「点了没反应」，很容易误判成触摸逻辑坏了。取坐标一律用：

```powershell
adb shell dumpsys window windows | Select-String 'APPLICATION_OVERLAY|mFrame='
```

⚠️ gecko 包**没开 WebView 远程调试**（`dsh-bridge` 走的是 native messaging，
`/proc/net/unix` 里只有 `gecko-crash-helper-pipe`），所以 gecko 侧不能靠 CDP 探针验证，
只能靠**预置 `shared_prefs/dsh_bubble.xml` + 重启**观察行为。

### ⑫.3 `bubble` 工具 —— 让**模型自己**改悬浮窗（补上可发现性那一段）

⑫.2 那条链对**页面侧插件**是通的，但对**模型**是关着的：

| 谁 | 能不能改悬浮窗 | 为什么 |
|---|---|---|
| 页面侧插件（`client.js`） | ✅ | 浏览器里直接摸到 `window.DSHAndroid` |
| **模型（AI）** | ❌ | 它的工具跑在 **Node 进程**里，那里没有 `window` |

所以「让 AI 改悬浮窗像改自己的 web 界面一样简单」在**可发现性**上是假的 ——
模型既不知道有这个能力，也没有任何途径触达它。⑫.3 补的就是这一段：
注册一个独立工具 `bubble`，并给它一条回原生的通道。

```
模型 ──调用 bubble 工具──> dsh-android-bubble（Node 插件，跑在引擎进程）
                                │ 写 $DSH_HOME/bubble-cmd.json
                                ↓
                    BubbleCommandBridge（原生，250ms 轮询）
                                ↓ FloatingBubble.applyAppearance()
                                │ 写 $DSH_HOME/bubble-result.json
                                ↑
模型 <──工具返回──────────────┘
```

| 件 | 位置 |
|---|---|
| 插件源码 | `mobile-ui-plugin/dsh-android-bubble/` |
| 打包产物 | `assets/bubble-tool.zip`（解到 `$DSH_HOME/bubble-tool/dsh-android-bubble/`） |
| overlay | `$DSH_HOME/bubble-tool.patch.yml`（`ensureBubbleTool()` + `writeBubbleToolPatch()`） |
| 原生通道 | `BubbleCommandBridge.kt`（`EngineManager.start()` 里 `BubbleCommandBridge.start(dshHome)`） |

#### 为什么是文件信箱，不是 socket / Binder

引擎 node 是 App 进程的**子进程**，同域、共享 app 私有目录，文件是最短的那条路。
本项目已有同构先例（`adb-wireless-port`：Kotlin 写、Node 读），这里只是把方向反过来。
不选 AF_UNIX 抽象命名空间：Android 的 app 域对命名管道的策略随版本变，
而文件在两个方向上都已经验过。

⚠️ **请求带自增 `id`，回执按 id 配对。** 没有它，「两个工具调用并发、后一个的回执
被前一个读走」会让模型拿到张冠李戴的结果。缺 `id` 的请求原生侧**直接丢弃并删文件**
（否则会卡住一个永远等不到答案的工具调用）。

⚠️ **原生侧先写回执、再删请求文件。** 反过来的话插件会超时 —— 它区分不了
「还没处理」与「回执丢了」。

⚠️ **回执文件由插件在发命令前主动删掉**（不是写空对象）：写空对象会让「解析失败」
与「还没处理」两种状态混在一起，只能靠 id 分辨，多一处出错的机会。

⚠️ **超时不返回成功。** 原生侧可能只是慢（引擎刚启动、主线程正忙），但返回 `ok`
会让模型以为改成功了。超时文案明确要求它用 `action:"get"` 复查。

⚠️ **`avatarFile` 在插件侧读**，不把路径交给原生：原生按自己的沙箱跑、拿不到工作区
路径，而插件与 `read` 工具同进程同权限。读取失败**明确报错**，不静默退回内置头像
（那样用户会看到球变成默认头像却没有任何提示）。

⚠️ **`DSH_HOME` 缺失/不存在时不注册工具。** 注册一个必然失败的工具比不注册更糟 ——
模型会反复重试。

#### 验收（真机，system flavor）

| 步骤 | 判据 |
|---|---|
| 插件挂载 | `已安装悬浮球控制工具插件 dsh-android-bubble (27981 字节)` + `已写入悬浮球控制工具 overlay: bubble-tool.patch.yml` + 启动命令行里出现 `--patch .../bubble-tool.patch.yml` |
| 信箱启动 | `悬浮球命令信箱已启动: /data/.../files/home/.dsh/bubble-cmd.json` |
| 通道往返（不经模型） | 手写 `{"id":1,"action":"get"}` 落进 `bubble-cmd.json` → 回执含完整外观 JSON，且请求文件被删 |
| 写入生效 | 手写 `{"id":2,"action":"set","fields":{"size":120,"shape":"square"}}` → 回执 `ok:true`，日志 `插件下发悬浮球外观: state=collapsed shape=square size=120`，`dumpsys` 的 `Requested w=360 h=360`（120dp × density 3.0） |
| **模型端到端** | 在官方 composer 里发「用 bubble 工具把悬浮球改成圆形、金黄色底 #FFC107、大小 90」→ 落盘 `shape=circle` / `size=90` / `bg=#FFC107`，日志同款一行，窗口 `Requested w=270 h=270` |

⚠️ 手写命令文件的坑：**PowerShell 里用 `adb shell "run-as ... sh -c 'printf ...'"` 转义
嵌套引号会被 PS 解析器吃掉**。稳妥做法是先在 PC 落一个 `.json`，`adb push` 到 `/sdcard`，
再 `run-as cp` 进私有目录。
⚠️ **写 JSON 必须用 UTF-8**（`[System.IO.File]::WriteAllText($p, $s, (New-Object System.Text.UTF8Encoding($false)))`）。
用 `-Encoding ascii` 会把中文替换成 `?`，而症状是**推送的横条显示成一串问号**，
看起来像"中文渲染坏了"，实际是测试脚本自己把字写坏了。

#### 分层外观的真机验收（2026-10-12，CDY-TN90 / Android 10）

| 能力 | 下发 | 判据 |
|---|---|---|
| 任意 SVG 形状 | `{shape:"path", shapePath:"M50 3 L61 38 …Z", size:120, bg:"#FFFFC107", avatar:"none"}` | 截图里球变成**黄色五角星** |
| 背景透明、图片留着 | `{bgOpacity:0, avatarOpacity:1}` | 截图里只剩头像、无背景板 |
| 轮播（4 张独立图） | `{frames:[4 个 data URI], frameIntervalMs:250}` | 日志 `悬浮球帧集已更新: 4 帧`；连续截图在球心采到 **4 种不同颜色** |
| 精灵图 2×2 | `{frames:[一张大图], spriteRows:2, spriteCols:2}` | 日志 `悬浮球帧集已更新: 4 帧（精灵图 2×2）`；同样采到 4 种颜色 |
| 旋转 + 呼吸同时 | `{spin:120, pulse:true}` | 蓝发重心与蓝像素数**均**周期变化（证明两个动画并行，没互相取消） |
| AI 主动推状态 | `{"action":"notify","text":"AI 主动推送测试 · 收到请举手","durationMs":9000}` | 截图里球上方横条显示该文案（中文正常） |
| 重启持久化 | 下发含全部新字段的配置 → `am force-stop` → 重启 → `action:"get"` | `shapePath` / `bgOpacity` / `avatarOpacity` / `avatarScale` / `frameCount` / `frameIntervalMs` / `frameLoop` / `spin` / `pulse` **全部完整读回** |
| 改大不跑出屏幕 | 球在 `x=1062`（贴右缘）时下发 `{size:140}` | `dumpsys` 里 `mAttrs=(660,578)` + `Requested w=420 h=420`，即被夹到 `1080-420=660` |

⚠️ **测试残留必须清回默认**（`state:"auto"` / `shape:"circle"` / `size:44` / 空 `shapePath`
/ `spin:0` / 空帧集），并删掉 `files/bubble-frames/` 与 `files/bubble-avatar.bin`。
⚠️ **必须先 `am force-stop` 再改 `shared_prefs/dsh_bubble.xml`** —— 不然内存里的旧配置
会在下次落盘时把文件覆盖回去，用户看到的是"我改了但没生效"。


### ⑫.4 桌面宠物 —— 把引擎的运行状态透传给悬浮球

**需求原话**：「如果我让 AI 做一个桌面宠物，宠物分不同状态，思考时用什么动作、等待时
用什么动作、待机用什么动作，可以做到吗？」

**做不到的不是渲染，是"状态"与"动作"这两件东西在模型里都不存在。** ⑫.2 的分层外观
只有**一个** `avatar`（外加一套 `frames`），而"宠物"要求的是**每个状态一套动作**；
并且没有任何通道把「AI 此刻在思考」这件事送到原生侧。

⑫.4 补的就是这两块：**多命名动作帧集** + **状态透传**。

#### 一、多命名动作帧集（`BubbleFrames`）

磁盘即注册表，**没有任何"注册动作名"的步骤**（少一步就能写错一步）：

```
files/bubble-frames/            动作 `default`（老布局，不搬）
files/bubble-frames/sets/idle/   动作 `idle`
files/bubble-frames/sets/work/   动作 `work`
```

每个动作目录里是一套完整的帧集（`0.bin`/`1.bin`… + `meta.json`），
与 `default` 的布局逐字相同 —— 所以**老安装的帧集原地就是 `default` 动作**，不用迁移。

动作名是**自由字符串**，`BubbleFrames.normalizeAction()` 白名单校验
（`a-z0-9-_`，≤24 字符），非法名返回 `null`（顺带挡掉 `../` 路径穿越）。
`actionNames()` 扫磁盘得到可用动作列表（`default` 有帧才排最前，其余按名排序）。

⚠️ **动作名非法时是「保持原样」而不是「报错退出」**：一个写错的动作名不该让整个
外观下发失败（其他字段还是好的）。

#### 二、状态 → 动作的映射（`actionMap`）

```jsonc
{
  "action": "",                        // 钦定动作；空串 = 跟随 actionMap 自动切换
  "actionMap": {                       // 运行状态 → 动作名
    "idle":     "idle",
    "thinking": "think",
    "working":  "work",
    "waiting":  "wait",
    "error":    "sad"
  }
}
```

运行状态固定五个：`idle` / `thinking` / `working` / `waiting` / `error`
（`BubbleAppearance.RUN_STATES`，与插件侧 `RUN_STATES` 必须一致）。

**该播哪个动作的优先级**（`FloatingBubble.wantedAction()`）：

| 序 | 来源 | 语义 |
|---|---|---|
| ① | `appearance.action` 非空 | AI 明确点名，**压过一切**（且落盘持久） |
| ② | `stateOverrideAction` | 脚本/插件临时钦定，不落盘 |
| ③ | `actionMap[runState]` | 按当前运行状态自动映射 |
| ④ | 都没有 | `default` |

⚠️ **`action` 的「空串」与「`default`」是两回事**：空串 = 跟随 `actionMap` 自动切换；
`"default"` = 就播默认动作、**不要**自动切。两者都合法，别把空串归一化成 `default`。

⚠️ **要的动作不在磁盘上就退回 `default`**，并把 `frameAction` 记成**实际播的那个**。
若不退回：`actionMap` 配了 `thinking` 但用户从没传过 thinking 的帧，球会**整个变空白**
（帧文件找不到），比"动作没换"严重得多。
⚠️ 退回日志**只对非 `default` 动作打** —— 否则每个没传过帧的用户每次启动都会看到一行
"改用默认动作"，把日志淹掉。

#### 三、状态从哪来：两条通道，一粗一细

| 通道 | 来源 | 粒度 | 毛病 |
|---|---|---|---|
| 粗 | 页面 DOM 的 `data-process-activity` → `EngineStatus.activity` | 每 ~1 秒 | 短于一秒的工具调用**整个漏掉** |
| 细 | `dsh-android-pet` 插件订阅引擎 `session/event` | 每事件，插件 120ms 合流 | 无 |

⚠️ **`EngineStatus.activity` 一度是「页面在发、原生没读」**：`parse()` 里根本没解析这个
字段，值到了就被丢掉。要断言这条通道，先确认 `parse()` 真的读了它。

**细通道的链路**：

```
引擎（session/event，@mode emit，监听器抛错只记日志）
   │ dsh-android-pet：EVENT_STATES 表 → 运行状态
   ↓ 原子写 $DSH_HOME/bubble-state.json（tmp + rename）
BubbleCommandBridge（原生，250ms 轮询）→ 读后即删
   ↓ FloatingBubble.applyRunStateToActive()
FloatingBubble：applyRunState() → applyActionLayer() → 换帧集
```

`EVENT_STATES` 只列**有明确归属**的事件（`turn/start`→thinking、`tool/call`→working、
`approval/asked`→waiting…）。**没列的事件一律忽略，不猜** —— 猜错会让宠物在"认真思考"
时突然切回待机，比不切更糟。事件名取自 `@deepseek-ai/dsh-session` 的
`KNOWN_SESSION_EVENT_TYPES`（已在真机逐条核对）。引擎升级后新增事件不会让本表失效，
只是那些新事件不驱动宠物。

⚠️ **`turn/end` 要特判**：它是唯一一个"这一轮结束了"的信号，且要区分成败
（`data.kind` 落在 `failed`/`error`/`aborted`… 或带 `error` 字段 ⇒ `error`，否则 `idle`）。
判据**刻意宽松、只认明确失败**：拿不准时按成功处理 —— 反过来会让宠物动不动就耷拉下来。

#### 四、⚠️ 三个必须走对的地方（都踩过）

**① 状态落了，动作没跟着换。** `syncRunStateFromPlugin()` 起初**只改 `runState`、
不刷动作层**，理由是"动作名会紧接着设"。但插件**可以只报状态不带动作名**（那是它的
常态），于是 `runState` 一路跟着变、`playingAction` 纹丝不动 —— `actionMap` 形同虚设。
两条通道现在都收敛到同一个 `applyRunState()`。

**② 一次状态推送别刷两遍轮播。** `applyRunStateToActive()` 里**先落钦定动作、再落运行
状态**。反过来的话，钦定还没生效时 `applyRunState()` 会先按 `actionMap` 刷一次，
紧接着钦定那步又刷一次。同理 `applyRunState()` 内部**动作没变就直接返回** ——
状态每秒推一次，无条件重刷会把正在播的那一轮打断，表现为"宠物动作一卡一卡"。

**③ `publish()` 的备份目录参数传成了临时目录。** 帧集发布是
「写 `…tmp` → rename 到目标，失败则从备份回滚」。我把第三个参数（备份目录）误传成
`tmpFor(...)`，于是**先把刚写好的临时目录删掉再 rename**，必然失败：
日志 `帧集改名失败，回滚` + `悬浮球动作 X 写入失败，保持原帧集`，
而回执**仍是 `ok:true`**（`actions` 那一路是逐动作容错的）。这是**回归**——
`default` 动作的写入走同一函数，同样受影响。备份目录必须是 `oldFor(...)`。

#### 五、为什么用**另一个文件**做状态信箱

`bubble-cmd.json` 是**按 `id` 配对**的请求/响应，`bubble-state.json` 是**单向、无回执、
读完即删**。不复用同一个文件，两条都是硬理由：

1. **id 空间会撞**：工具与插件各自从 1 开始自增，同一个 id 同时存在时，工具调用会收到
   状态播报的回执，表现为"AI 说改了悬浮球但没生效"；
2. **会互相覆盖**：工具写完命令要等最多 4 秒回执，这期间被状态播报覆盖就必然超时。

而状态播报本来就不需要回执（它是"我在干什么"，不是"请你做什么"）。

⚠️ **`action` 是命令名，动作名一律走 `name` 字段**（这两个名字撞过一次）。
⚠️ **「字段不存在」与「字段是空串」语义不同**（前者 = 别动钦定，后者 = 清掉钦定），
所以原生侧用 `json.has("name")` 判定，**不能用 `optString`**（它区分不了）。
⚠️ **解析失败不删文件**（只在成功处理后删）：否则一次读到写一半的内容，那条状态就永久
丢了。插件侧写盘用 `tmp + rename` 保证原生读不到半截。

#### 六、为什么插件**只报状态**，映射留在原生

插件是**传感器**，原生才是**策略**。把 `actionMap` 也放进插件就会出现**两份映射**
（原生一份给离线/无插件场景、插件一份给在线场景），它们必然漂移，而症状是
"有时按状态换动作、有时不换"。

#### 七、两个插件为什么不合并

| | `dsh-android-bubble` | `dsh-android-pet` |
|---|---|---|
| 何时跑 | **只在模型调用 `bubble` 工具时** | **每一轮**都在跑 |
| `inject` | `['tools']`（要注册工具） | `[]`（只订阅事件 + 写一个文件） |

⚠️ **`inject` 多声明一个服务就多一个"引擎没这个服务 ⇒ 整个插件不激活"的机会**，
而本插件失效的症状是**静默的**（宠物不动了，没有任何报错）。所以 pet 的 `inject` 是空数组。

⚠️ **插件里不要用 `ctx.logger`，用 `console.log`。** 本 App 只把引擎子进程的
stdout/stderr 转发进 logcat（`dsh-core-memory` 的"已激活"就是这么打的），
`ctx.logger` 的去向在移动端**不可见**。用 `ctx.logger` 的后果是**"插件到底有没有激活"
根本查不出来** —— 而本插件的失效症状本来就是静默的。这条踩过一次：
`dsh-android-pet` 早已正常激活，却因为日志通道不对而查不出，白排查了一轮。

#### 八、真机验收（2026-10-12，CDY-TN90 / Android 10，system flavor）

**通道往返（不经模型）** —— 手写 `bubble-state.json`：

| 推的状态 | `runState` | `playingAction` |
|---|---|---|
| （初始） | `idle` | `idle` |
| `working` | `working` | **`work`** |
| `thinking` | `thinking` | `idle`（`actionMap` 里 thinking→idle） |
| `idle` | `idle` | `idle` |
| `error` | `error` | `idle` |
| `waiting` | `waiting` | `default`（没配 waiting 的映射） |

**真实 `session/event` 全链路** —— 在 GUI 里发一条会跑工具的消息（`run sleep 8`），
原生侧每 700ms 读一次 `action:"get"`：

```
基线:            idle/idle
[1..5]  轮询:    thinking/idle      ← turn/start 等事件驱动
[6..14] 轮询:    working/work       ← tool/call 驱动，动作真的换了
```

**采样手法**：状态文件**读完即删**，PC 侧轮询（每 400ms 一次 adb）会漏掉大部分中间状态。
要看完整序列就在**设备侧**起一个高频采样循环（`sleep 0.05`，把每次内容追加到日志文件），
事后再去重保序 —— 实测一次真实 turn 采到 `{"state":"thinking","at":…}`。

**其它判据**：

| 项 | 判据 |
|---|---|
| 插件挂载 | `已安装桌面宠物状态桥插件 dsh-android-pet (<N> 字节)` + `已写入桌面宠物状态桥 overlay: pet-bridge.patch.yml` + 启动命令行里出现 `--patch .../pet-bridge.patch.yml` |
| 插件激活 | logcat 里 `[engine] dsh-android-pet: 状态桥已启动: /data/.../files/home/.dsh/bubble-state.json` |
| 动作落盘 | `find files/bubble-frames -type f` 列出 `sets/idle/0.bin`、`sets/idle/meta.json`、`sets/work/…` |
| 动作就位 | `悬浮球动作 idle 已就位: 1 帧` / `悬浮球动作 work 已就位: 1 帧` / `已写入悬浮球动作集: idle, work` |
| 回执含新字段 | `action:"get"` 的 `appearance` 里同时有 `action` / `actionMap` / `actions` / `runState` / `playingAction` |

⚠️ **回执里的 `appearance` 是「JSON 里的 JSON」**（一个转义过的字符串），
用正则从原始文本里抠 `"runState":"…"` **抠不到**（反斜杠转义）。
要先 `ConvertFrom-Json` 拿到外层对象，再对它 `.appearance` 二次解析。这一处卡过一轮。

⚠️ **`action:"get"` 的 `appearance` 来自磁盘配置，`runState`/`playingAction` 来自活着的
那个悬浮球实例** —— 所以球没显示时后两个字段读不到。读之前先 `KEYCODE_HOME` 让球出来。

**测试残留**：`am force-stop` → 配置重置为默认（含 `action:""` 与空 `actionMap`）→
删 `files/bubble-frames/`、`files/bubble-avatar.bin`、`files/home/.dsh/bubble-state.json`、
`files/sample.sh`、`files/sample.log`。


### ⑫.5 `ui` 工具 —— 让**模型自己**看和点这个 App 的界面

⑫.3 把「改悬浮窗」接到了模型的工具列表里，但它只能改**悬浮球**。
界面本身（官方 DSH Web GUI 那个聊天页）仍然是模型碰不到的：

| 谁 | 能不能操作界面 | 为什么 |
|---|---|---|
| 页面侧插件（`client.js`） | ✅ | 浏览器里直接摸到 DOM |
| **模型（AI）** | ❌ | 工具跑在 **Node 进程**里，没有 `window`、没有 `document` |

于是「让 AI 自己测这个 App」在能力上是空的 —— 它既看不见界面，也动不了界面。
⑫.5 补的就是这一段：注册一个独立工具 `ui`，把**枚举可交互元素（带屏幕精确坐标）
/ 点击 / 输入 / 滚动 / 读文本 / 等元素出现**整套能力交给模型。

```
模型 ──调用 ui 工具──> dsh-android-uiagent（Node 插件，跑在引擎进程）
                            │ 写 $DSH_HOME/ui-cmd.json
                            ↓
                UiProbeBridge（原生，120ms 轮询）
                            │ WebHost.evaluateJavascript( (<ui-probe.js 源码>)(cmd) )
                            ↓
                页面世界 window.__DSH_UI_PROBE__.run(cmd)  ← React 渲染的真实 DOM
                            │ JSON 结果
                            ↑
                原生把 CSS 坐标换算成屏幕物理坐标
                            │ 写 $DSH_HOME/ui-result.json
                            ↑
模型 <──工具返回────────────┘
```

| 件 | 位置 |
|---|---|
| 页面探针 | `app/src/main/assets/ui-probe.js`（**不走转译**，见下） |
| 插件源码 | `mobile-ui-plugin/dsh-android-uiagent/` |
| 打包产物 | `assets/uiagent-tool.zip`（解到 `$DSH_HOME/uiagent-tool/dsh-android-uiagent/`） |
| overlay | `$DSH_HOME/uiagent-tool.patch.yml`（`ensureUiTool()` + `writeUiToolPatch()`） |
| 原生通道 | `UiProbeBridge.kt`（`MainActivity.installUiProbeBridge()`） |

⚠️ **信箱文件与 `bubble` 工具完全分开**（`ui-cmd.json` / `ui-result.json` vs
`bubble-cmd.json` / `bubble-result.json`）。两者各自从 `id=1` 自增，共用文件会让
工具调用收到对方的回执。理由与 `bubble-state.json` 独立成文件完全一致。

#### ⚠️ 模型的 `action` 与原生内部 `control` 是**两个名字，必须都认**

原生最初只认 `control` 字段（内部旁路信号），而工具对外暴露的是统一的 `action` ——
模型不可能知道原生内部另有一个字段名。只认 `control` 的后果是**静默降级**：

| 调用 | 只认 `control` 时 | 修复后 |
|---|---|---|
| `action:"wait"` | 落进 else 分支，**只查一次**就报「没出现」 | 原生按 250ms 循环查，最多 10s |
| `action:"reload"` | 探针不认识这个 action → 报「未知 action: reload」 | 走原生 `reload()` 分支 |

两者都不报错到能看出根因。`UiProbeBridge.handle()` 现在写成
`control.ifBlank { action }`，**只有 `wait`/`reload` 会被拦下**，其余动作行为不变。

#### ⚠️ 探针**不经过任何转译**，必须守 ES5

`ui-probe.js` 是运行时被 `evaluateJavascript` 直接 eval 的源码，**不经过
`build-webview-compat.ps1` 那套 esbuild 降级**。用了 ES6+ 语法（`?.`、`??`、
`=>`、模板串）在老 WebView 上会**整段语法错误、探针静默全废** ——
而页面里看不到任何症状，只有原生侧每次调用都超时。

用 esbuild 验（包装成表达式再降级，因为探针本身是**匿名函数表达式**，
直接喂给 esbuild 会报 `Expected identifier but found "("`，那是语法形态不是兼容性问题）：

```powershell
$esbuild = "$env:TEMP\dsh-esbuild-tool\node_modules\.bin\esbuild.cmd"
$src = Get-Content app\src\main\assets\ui-probe.js -Raw
Set-Content "$env:TEMP\wrapped.js" -Value "var __probe = ($src);" -Encoding UTF8
& $esbuild "$env:TEMP\wrapped.js" --target=chrome74 --outfile="$env:TEMP\o74.js"
# 实测 chrome74 / chrome69 / chrome64 三档全部 exit=0、零警告
```

`node --check` **对它无效**（匿名函数表达式不是独立语句，必报
`Function statements require a function name`）—— 别拿它的失败当问题。

#### 坐标系（「精确」二字的落点）

探针只返回 **CSS 视口坐标**（`getBoundingClientRect`）+ 视口尺寸；换算全在原生侧，
因为页面里拿不到 WebView 在屏幕上的位置（`window.screenX` 在 WebView 内恒为 0）：

```
scale     = webView物理宽度 / 页面视口CSS宽度
屏幕物理X = webView屏幕X + cssX * scale
```

回执里因此有**两套坐标**，每个元素都带：

| 字段 | 含义 | 用在哪 |
|---|---|---|
| `center` / `rect` | CSS 视口坐标 | 页面内的判断 |
| `screenCenter` / `screenRect` | **屏幕物理坐标** | 要驱动真机触控（`input tap`）时**只能用这个** |

⚠️ `ScreenGeometry` 是通过**取值函数**（`provider` lambda）现取的，不是启动时存一份 View
引用 —— Activity 重建（旋转、从后台回来）会换掉整个 WebView，持旧引用会把 JS 打进
一个已销毁的内核，症状是「AI 说点过了，但界面没动」。

#### ⚠️ 受控输入：`el.value = x` 是**无效**的

React 在元素**实例**上定义 `value` 的 get/set 拦截器，把「上一轮的值」存在闭包里，
靠 `tracked !== node.value` 判断要不要触发 `onChange`。直接 `el.value = x` 会**同时**
更新拦截器里的记录 ⇒ React 认为没变化 ⇒ `onChange` 不触发 ⇒ 输入框看着有字、
但组件状态是空的（发送出去还是空消息）。

正解是走**原型上的原始 setter**，再派发冒泡的 `input` 事件：

```js
Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(el, text);
el.dispatchEvent(new Event('input', { bubbles: true }));
```

`clickEl()` 同理要**按顺序**发 `pointerdown → mousedown → pointerup → mouseup → click`：
React 18 用根级事件委托，缺前置事件时某些组件的状态机不推进，表现是「click 发了但界面没变」。

#### `onTarget` 的判据是「手指会不会点到目标」

`elementFromPoint` 的结果如实报告，与「我们把事件发给了谁」分开：

- 事件**总是发给目标元素**（意图是「点它」，有装饰层浮在上面也不该丢掉意图）；
- `onTarget:false` 表示**有无关元素压在上面**（遮罩、蒙层），此时 `hitTopmost` 会告诉
  你实际接收点击的是谁。

⚠️ 第一版把这个判据写成 `target === el`，而 `target` 只在「命中元素与目标有祖先/后代
关系」时才被改掉 —— 于是无关遮罩压上来时 `target` 仍是 `el`，**`onTarget` 恒为 true、
这个字段等于没有**，而工具文档还写着「onTarget:false 说明被遮挡」。判据必须直接看
命中元素本身。

#### ⚠️⚠️ 「看得见」不等于「点得动」—— 遮挡识别是这套工具最要紧的一环

**这是「AI 说点过了但界面没动」的头号成因**，实测在官方前端上撞到：

全新 profile 首次打开会弹**「预览版说明」对话框**（`z-index:1000` + 一层覆盖全屏的
`_mask_`），关掉它紧接着又是**「API 密钥」**向导。这两个弹窗盖着的时候，底下 16 个按钮
在 DOM 里**看着完全正常** —— 有尺寸、`opacity` 是 1、`visibility` 是 visible、
`pointer-events` 是 auto。第一版 `isVisible()` 只查元素**自身**样式，于是把它们全部
报成可用；模型挨个 `tap`、界面纹丝不动，而拿到的只有 `onTarget:false` 一句，**完全
不知道是谁挡着、下一步该干嘛**。

所以「精确」不只是坐标对，还必须包含**此刻它能不能被点到**。回执里三处都带上：

| 字段 | 在哪 | 含义 |
|---|---|---|
| `covered` / `blockedBy` | 每个元素 | 它现在被谁盖住（`blockedBy` 直接给出遮挡者的选择器/类名） |
| `overlay` / `blocked` / `warning` | `dump` 顶层 | 有没有全屏遮挡层、多少元素点不动、一句可读的警告 |
| `present` / `layer` / `clickable` | `overlay` 动作 | 遮挡层是谁 + **此刻真正能点的是哪几个** |

判据与 `clickEl()` 里的 `onTarget` **同源**（都走 `blocker()`）—— 否则「dump 说能用」
与「tap 说没点到」会互相矛盾，模型无从判断该信哪个。

⚠️ **遮挡层要往上找壳**。遮挡层几乎总是「一个覆盖全屏的容器 + 里面的实际内容」，
采样点命中的往往是**内容**（弹窗里的一段文字），它自己的 rect 很小 —— 直接拿命中元素
判面积会得出「没有遮挡」。`fullScreenAncestor()` 就是干这个的。

⚠️ **必须多点采样**。第一版只取视口中心一个点，结果出现
**`overlay` 报「没有遮挡层」而同一时刻 `dump` 报「15 个元素被遮挡」**的自相矛盾 ——
两个字段对同一件事给出相反答案，比缺字段更糟。现在取 9 个点（中心 + 四角内缩 + 四边
中点），**过半数**被同一层挡住才认定是遮挡层。

⚠️ **不要试图回答「遮挡层里面有哪些按钮」**。实测官方弹窗的结构是
`_root_ > [_mask_（全屏空遮罩）, _dialog_（真内容）]` —— **按钮是遮罩的兄弟节点，
不是后代**。按后代找会得到「0 个可点元素」，而屏幕上明明有个「继续」能点。
模型真正要问的是「**现在我能点什么**」，`overlay` 因此直接列**当前没有被遮挡**的
可交互元素，这在任何 DOM 结构下都成立。

#### ⚠️⚠️ 「屏幕外」是第二类「点不到」—— 与遮挡并列，处置办法**相反**

遮挡解决的是「谁把事件吃掉了」；屏幕外解决的是「**屏幕上根本没有可点的坐标**」。
两者的表现一模一样（界面纹丝不动、`ok` 还是 `true`），但**下一步该做什么正好相反**：

| | 判据 | 现象 | 该怎么办 |
|---|---|---|---|
| 被遮挡 | `covered: true` | 事件被上层元素接收，`onTarget:false` | **先关掉挡在前面的东西**（overlay → 点它列出的那个） |
| 屏幕外 | `offscreen: true` | 事件**确实发到了目标元素上**，`onTarget:true` | **先把界面切到它所在的状态**（例如先点开抽屉） |

**真机实测的现场**：抽屉收起后，侧栏那些按钮滑到 `left:-312`。它们仍然是可见的、
没有任何东西盖着，`tap` 也老老实实把事件发到了它们身上（元素的处理器真的被调用了一次）
—— 但手指在屏幕上**没有那个坐标**，所以界面不会有任何反应。此前探针报
`onTarget:true`，模型据此认为点成功了，然后继续往下走，错得毫无痕迹。

⚠️ **`position:fixed` 的元素救不回来，别指望 `scrollIntoView`**。`clickEl()` 里本来就调了
它，但 fixed 元素不参与滚动 —— 抽屉里的侧栏按钮正是这种。所以必须如实报出来。

⚠️ **判据是「中心点」在不在视口内，不是「有没有任意一部分露着」**。两个字段都得留：

| 字段 | 问的是什么 | 谁在用 |
|---|---|---|
| `inViewport` | 有没有任意一部分露在屏幕里 | 滚动列表里「这项滚出去了」的标记 |
| `offscreen` | **中心点**在屏幕外 ⇒ 真手指点不到 | 「能不能点」的判据 |

合并成一个字段会把「滚出屏幕的列表项」与「滑出视口的侧栏」混为一谈。

⚠️ **三处判据必须一起改，漏一处就自相矛盾**（踩过，与遮挡那次同一类）：

1. `collect({clickableOnly:true})` 要同时排除 `covered` 与 `offscreen` ——
   `overlay` 的 `clickable` 用的就是这个列表，漏排会让模型去点一个不在屏幕上的按钮；
2. `tap` 要报 `offscreen:true` + `hint`，而且**必须独立成支排在遮挡判断之前** ——
   它的 `onTarget` 是 `true`，走不进 `onTarget === false` 那个分支；
3. `wait {clickable:true}` 要一并判 `offscreen` —— 否则「等到了」紧接着的 `tap` 必然空点。

⚠️ **渲染层不能把 `offscreen` 套进遮挡那套话术**。模型看到「被上层元素接收」会去查
`overlay`，方向就错了。元素行上的标记也是两个：`被遮挡←<谁>` 与 `屏幕外·点不到`。

#### ⚠️⚠️ 全屏的「面板」不是遮挡层 —— 第三类误判，处置办法**相反**

这是**同一个 `overlay()` 判据**引出的第三种错。遮挡、屏幕外、面板三者的表现都是
「点了没反应」，但**该做什么完全相反**：

| | 判据 | 该怎么办 |
|---|---|---|
| 空遮罩 | `kind: "mask"` | **关掉它**（点它列出的那个按钮） |
| 屏幕外 | `offscreen: true` | **先切到它所在的状态** |
| 面板 | `kind: "panel"` | **别关它 —— 它就是你的屏幕**，点它**里面**的按钮 |

**根因**：设置面板、右侧边栏、会话抽屉都是 `position:fixed` 的**全屏容器**，
DOM 上与弹窗遮罩**完全同构**。探针早期只看「是否覆盖视口大部分区域」，
于是把这三屏一律报成「遮挡层」，`dump` 紧接着打出一句
「先关掉它（通常是弹窗里的某个按钮）再操作」—— 模型照着去点「返回」，
**那三屏就永远进不去了**。实测现场：图谱脚本点「工作区文件」→ 被引去点「关闭」，
来回绕了两圈才回到基态。

**判别器 = 层里有没有「自己人」**。遮罩是个**空壳**，它的按钮是它的**兄弟**节点：

| 全屏层 | `interactiveInside(layer)` |
|---|---|
| `_mask_o6lrb_18`（弹窗遮罩） | **0**（按钮在 `_root_ > [_mask_, _dialog_]` 里，是遮罩的兄弟） |
| `wCInkW_panel`（设置面板） | **8** |
| `OUqwTW_panel`（右侧边栏） | **7** |

于是 `overlay()` 的返回契约从「一个布尔」扩成**三个各管一件事的字段**：

| 字段 | 问的是什么 | 谁在用 |
|---|---|---|
| `present` | 有没有一个覆盖全屏的层（**面板也算**） | 想知道「屏幕上是不是多了东西」 |
| `kind` | `"mask"`（要关）还是 `"panel"`（别关） | **调用方必须按它分支** |
| `blocking` | 它是不是真的在挡你（只有空遮罩是 `true`） | 想知道「要不要先清场」 |

⚠️ **`present` 曾经被当成「有遮挡」，代价很大**：改成面板报 `present:false` 之后，
收尾逻辑里那个「循环到 `!present`」再也关不掉右侧边栏，下一步 `tap 发送` 报
`onTarget:false` —— 而消息**其实发出去了**。所以三个字段各司其职，**不许合并**，
调用方一律**先看 `kind`**，不要拿 `present` 当遮挡判据。

⚠️ **`tap` 的 `onTarget:false` 也分两种**：`reply.panel` 有值 ⇒ 元素在当前面板
**下面**（它不在你现在的屏幕上，提示语不能说「被上层元素接收」）；否则才是真的被挡。
两种提示引向的下一步相反，混了就会把模型引去关掉本该停留的那一屏。

⚠️ **`aria` 定位必须回落查 `title`**。`dump` 的名字来自 `labelOf()`，取值口径是
`aria-label || title`；而 `locate()` 的 `aria` 分支早期只查 `[aria-label="…"]`。
于是「只有 `title`」的按钮会被 dump 报出名字、模型照着回传却**定位不到**
（症状：`找不到要点击的元素`，而它在 dump 列表里明明有）。真机上 DSH 界面
大量图标按钮只有 `title`，所以这条回落是必需的 —— **dump 报得出的名字，就要点得到**。

⚠️ **不是所有屏幕都能一次点进去**。实测会话抽屉要点**两次**「打开导航」：第一次点完
侧栏从 `x:-137` 滑到 `x:-340`（更外面，仍是关着的），第二次才真正展开
（`class` 加上 `mp-drawer-open`、`x:0`、「收起侧边栏」出现）。根因是 mobile-ui 的
时序竞态 —— 会话刚建立时官方侧栏组件还没渲染完，`mpToggleDrawer()` 那一下切换落空了。
正确姿势就是真机上该做的那套：**点 → `wait` 等特征元素 → 没等到就原地再点一次**。
⚠️ **重试必须是原地重点**：重置回基态再点一次会撞上同一个竞态，永远进不去（试过，三次全失败）。

#### ⚠️⚠️ 前端**持久化**状态 —— 「重载」不等于「回到基态」

这一条是操作法里最容易栽的：连续几屏走下来，症状全是**「找不到要点击的元素」**
（看着像探针定位写错了），实际是**页面已经不是你以为的那一屏**。三处实测：

| 被持久化的 | 后果 | 对策 |
|---|---|---|
| 右侧边栏的**开关状态** | 重载后它自己又开着，入口按钮的 `aria` 从「打开右侧边栏」变成「收起右侧边栏」 | 每屏重置后先把它**收掉**（点面板自己的关闭按钮） |
| 右侧边栏的**内容页** | 点过「工作区文件」/「新建终端」之后，再打开直接落在那个内容页上，启动页那两个入口**不再出现** | 每屏重置时点**内容页自己的「关闭」**（见下），并让启动页那一屏排在内容页各屏**之前** |
| **当前会话** | 种过会话就回不到空会话态，「选择新任务使用的 Agent 预设」**再也不渲染** | 只在空态存在的屏排在 `drawer` 之前 |

⚠️ **三者都是单向的**：一旦进入就回不去。所以**屏的顺序本身是有语义的**，
不是随手排的 —— 改顺序前先想清楚哪一屏会把状态推过去。

⚠️⚠️ **「收起右侧边栏」不重置内容页 —— 这一点踩过，是本轮图谱里最隐蔽的坑。**
实测：进了「工作区文件」之后点「收起右侧边栏」再点「打开右侧边栏」，**还是「工作区文件」**，
启动页的「新建终端」/「工作区文件」两个入口都不在，于是下一屏
`tap「新建终端」` 直接报 `ok:false 找不到要点击的元素`（看着像定位写错，其实是屏幕不对）。
**内容页自己的「关闭」才重置内容页** —— 点它侧边栏整个收起，再打开就回到启动页。

⚠️ 所以关闭按钮的优先级是 **内容页的「关闭」→ 启动页的「收起右侧边栏」→ 泛化「关闭/返回」**，
**顺序反了就会带着上一屏的内容页跑下一屏**。两条路都覆盖到了：启动页的侧边栏
**没有**「关闭」（只有分栏 / 退出全屏 / 收起右侧边栏），所以「关闭」找不到时自然落到兜底。

⚠️ **收面板必须点面板自己的关闭按钮，不是去「关掉面板」**（面板是屏幕不是遮挡，
见上一节）。而且按钮要**按优先级挑**：右侧边栏里同时有「退出全屏」「收起右侧边栏」
「关闭」，拿第一个正则命中的就用会点到「退出全屏」—— 它只切换全屏态，面板照旧开着，
下一轮又拿到同一个面板，**绕圈**。

⚠️ **`expect` 必须是该屏独有的元素 —— 否则「其实没进去」会被判成通过。**
实测：「终端」屏原来拿「新标签页」当判据，可**工作区文件屏也有它**，于是
`tap「新建终端」` 明明报 `ok:false`（根本没点着），复核却因为「新标签页在」而显示 ✅。
终端屏独有的标签是当前 Shell 的名字（默认 `cmd.exe`）。
**通用判据**：返回 / 关闭 / 新标签页 这类多屏共有的词一律不能当 `expect`。

⚠️ **收起是带动画的**（侧栏滑出），动画没落定就点「打开右侧边栏」，按钮还在过渡态
（`aria` 仍是「收起右侧边栏」）→ 又是「找不到要点击的元素」。实测「右侧边栏」屏
就是这么挂的，等 1.6 s 才稳。

#### ⚠️⚠️ `execCommand` 写 contenteditable 是**异步生效**的

官方 composer 是 **Lexical**。实测时间线（这是量了两轮才搞清的）：

```
t=0ms    调用 execCommand('insertText') → 返回 true
t=0ms    同步读 textContent / innerText → **都还是空的**
t=700ms  再读 → 内容已落地（<p><span data-lexical-text>…</span></p>）
```

所以**同步读必然为空**，拿「同步读 == 期望值」当成功判据会**每一次都误判失败**，
然后走 `el.textContent = s` 兜底 —— 而那个兜底会把 Lexical 精心维护的 `<p><span>`
结构拍成裸文本，破坏编辑器内部模型。表面上「内容进去了」，实际是两条路在写同一个元素。

正确做法：**`execCommand` 返回 true 就相信它**，不做同步覆盖；`textContent` 只作
「`execCommand` 不可用/抛异常」时的兜底。

`type` 的 `applied` 字段因此是**三态**，别当布尔用：

| 值 | 含义 |
|---|---|
| `true` | 同步已确认写入 |
| `null` | 写入已发出、**异步生效中**（contenteditable 就是这样）—— **不是失败** |
| `false` | 真的没写进去 |

⚠️ 校验与赋值必须用**同一个量**：赋值走 `textContent`、校验却用 `innerText`
（布局相关，元素未布局时返回空串）会出现「刚写进去、校验说不相等」的假失败。
判据用「**包含**」而不是严格相等 —— 富文本会把文本包进 `<p><span>`。

#### ⚠️ 验收判据必须是「业务状态真的变了」

拿错判据会让人去修一个**根本没坏**的东西。两个反例都实测踩过：

| 错误判据 | 为什么无效 |
|---|---|
| `document.querySelectorAll('*').length` 变化 | 抽屉靠 CSS `transform`/`opacity` 显隐，**节点数一个都不增** |
| `.mp-drawer-mask` 存在/可见 | 它是 `mpMask()` **懒建且常驻**的，建完就不删；存在 ≠ 抽屉开着 |

而「点汉堡展开抽屉」在**空会话页（Hero）根本不是有效判据**：此时官方侧栏尚未渲染
（`.pI_x6G_sidebarCol` 不存在），`mpToggleDrawer()` 里 `mpOfficialToggle()` 返回
`null`、`if (btn) btn.click()` 不执行 → 点汉堡本来就没反应。那是 mobile-ui 插件在
Hero 页的缺陷（见 `client.js` 修复 31 的注释），探针如实报 `onTarget:true` **没有说谎**。

**有效判据**（`tools/ui-map-real-frontend.mjs` 用的就是这条）：走 composer 全链路 ——
`type` 写入 → **发送按钮从 `disabled:true` 变 `false`**（证明 React 真的认账了）→
`tap` 发送 → **消息文本出现在界面上**。三步任何一步失败都说明探针没真的驱动到应用。

#### 怎么用（模型的正确姿势）

**先查图谱，别现猜坐标。** `tools\ui-map-real-frontend.json` 里 17 屏的每个按钮都
记了 CSS 中心坐标与 `sel`，这是「按钮在哪」的权威答案；真机触控要的屏幕物理坐标
由探针按当前视口现算（见「坐标系」一节），别拿图谱里的 CSS 值直接去 `input tap`。

**第 0 步永远是「清场」**：`{"action":"overlay"}` 看有没有遮挡层。有就先点掉它，
再 `overlay` 一次确认没了 —— 官方首启向导是**多步**的，只关一次会停在第二个弹窗上。

```
overlay → kind:'mask' 吗？→ 是就点「此刻能点的」里那个按钮 → 再看 overlay → 直到没有 mask
```

⚠️ **循环的终止条件是 `kind !== 'mask'`，不是 `present:false`。** `present` 的语义是
「有没有全屏层」，**面板也算**（设置面板、右侧边栏、会话抽屉都是全屏层）。照
`present:false` 收尾会去关掉一个**本来就是你当前屏幕**的面板，那几屏就再也进不去了
（见上一节「全屏的『面板』不是遮挡层」）。

然后才是正常流程：

1. `{"action":"dump"}` 先看界面上有什么（返回每个元素的位置、文本、选择器、`covered`）；
2. 用 `sel`（首选，从刚才的 dump 里复制）或 `text` / `aria` / `testid` 定位；
   ⚠️ `aria` 会自动回落到 `title`，所以 dump 报得出的名字就点得到；
3. `{"action":"tap","text":"发送"}`；**`covered:true` 的元素别点**，先处理遮挡；
   `offscreen:true` 的也别点 —— 它没有屏幕坐标（处置办法与遮挡相反，见「屏幕外」一节）；
4. 界面变化是异步的 —— 接着 `{"action":"wait","text":"确认","clickable":true}` 等面板出现；
5. **永远先看 `ok` 字段**，`ok:false` 要如实报失败，不能假设点成功了。
   ⚠️ 但 `ok:true` **不等于**点到了东西 —— 点了个没反应的按钮同样返回 `ok:true`，
   所以每步之后要**用一个只属于目标屏的特征元素做判据**（`wait` 它出现），
   这就是图谱脚本里 `expect` 字段的作用。

其它动作：`type`（写输入框，`enter:true` 附带回车）、`key`（发按键）、
`scroll`（`to:"bottom"` 跳到底）、`text`（读可见文本）、`info`（一眼看当前界面）、
`find` / `html`（看单个元素）、`ping`（探针是否活着）、`reload`（界面白了就重载）。
`dump` 可加 `clickableOnly:true` 只看现在真能点的。

#### ⚡ 一键到屏：`map` / `where` / `goto`（模型的正确姿势）

**别再手点着导航 —— 用三个内建动作。** 图谱（`tools\ui-map-real-frontend.json`）已经
被**编成插件的一部分**（`lib/screens.js`，由 `tools\build-uiagent-screens.mjs` 从图谱
生成），所以模型不需要读那个 400 KB 的 JSON，也不需要猜自己在哪：

| 动作 | 干什么 |
|---|---|
| `{"action":"map"}` | 列出 17 屏的 id、标题、路线。加 `detail:true` 连每屏的按钮一起列 |
| `{"action":"where"}` | **回答「我现在在哪一屏」** —— 拿 17 屏的判据逐个比对实时界面，命中即为答案 |
| `{"action":"goto","screen":"特权权限"}` | 一条命令开到那一屏，返回过程与是否到达 |

`screen` 接受屏 id（`settings-priv`）、完整标题、或标题子串（`特权权限`）。

标准循环：

```
{action:"where"} → {action:"goto",screen:"..."} → {action:"dump"} → {action:"tap",...}
先弄清在哪          再开到目标屏              读实时位置          动手
```

⚠️ **`goto` 之所以必要，是因为这个前端「进去了就回不去」。** 它持久化三样**单向**的
状态：会话抽屉的开关、右侧边栏的开关**及其内容页**、以及当前会话。一旦点过
`新建终端` 或种过会话，纯 `tap` 序列**永远回不到**空会话屏（见「前端持久化状态」一节）。
`goto` 的第一件事是 `reload` 回基态，那是唯一可靠的回退手段。

`goto` 的内部顺序（照抄图谱生成器的策略，一字不差）：

```
reload → 等探针重新装填 → 关遮挡层（kind=='mask'）→ 收面板（kind=='panel'）→ 沿路线 tap → 用判据确认
```

两处**不能改**的细节：

- **清场按 `kind` 判，不是 `present`** —— `present` 把面板也算成覆盖层，照它循环会
  关掉**当前屏幕本身**（与「清场」那一节的 ⚠️ 同源）。
- **等不到判据时是原地重点，不是重载重来** —— 抽屉要点两次是 mobile-ui 的时序竞态，
  重载再点会撞上同一个竞态（图谱生成器实测三次全失败）。重试上限 3 次。

第三个动作 `where` 有一条性能上的硬约束：**它用 `find` 探测，不能用 `wait`。**
原生 `runWait()` 会一直轮询到 **10 秒**上限、且**忽略**调用方传的 `timeout`
（`UiProbeBridge.kt:390` 只用 `WAIT_MAX_MS`）。17 屏 × 10 秒 = 170 秒，而 `find` 是
单次检查、秒级完成。代价是 `wait` 代劳的 `clickable` 过滤要**在插件侧自己做**
（`describe()` 回传的 `covered`/`offscreen` 就是判据，照 `ui-probe.js:1159` 那行写）——
漏掉这一层，`hero` 的判据会在**任何**一屏「成立」，因为那个 composer 占位符 12 屏都有、
只是其中 11 屏被盖住了。

⚠️ **三处判据曾静默失效，已修并加了机械审计。** 判据（`expect`）有两个用途：图谱
生成时校验「真的进去了」，运行时 `where`/`goto` 判断在哪。撞车时**两个用途同时失效，
且症状都是静默的** —— 图谱那屏「假通过」，`goto`「假到达」（模型于是对着错误界面点
按钮）。本轮实测抓到 4 处：

| 屏 | 原判据 | 为什么撞车 |
|---|---|---|
| `hero` | `打开导航` | mobile-ui 的常驻悬浮按钮，**17 屏全有** |
| `model-picker` | `推理等级` | 子串命中了每屏都在的长文案「…，推理等级 High」 |
| `settings-priv` | `重新检测` | `设置 › 悬浮气泡` 那屏也有一个「重新检测」 |
| `preset-picker` | `极简模式` | 子串命中了 `设置 › Agent 预设` 的「设为新任务默认: 极简模式」 |

**光靠肉眼审不出来**（本轮就先看漏了两处）。所以固化成：

```powershell
node tools\ui-map-expect-audit.mjs    # 17 屏判据唯一性，退出码非零 = 有撞车
```

它**复刻探针的匹配语义**（先精确、再子串取第一个、候选集是**全部**元素不过滤遮挡、
`clickable:true` 才附加遮挡/屏幕外条件），并对**全部元素**跑一遍 —— 与探针口径不一致
的话结论会反。改完判据要**重跑图谱**让 JSON 跟上，再跑
`node tools\build-uiagent-screens.mjs` 让插件的内建表跟上。

##### ⚠️⚠️ 判据里**绝不能出现用户数据** —— 三类根因必须分开治

真机审计 17 屏时报出 29 处失败。逐条查下来，它们是**三种性质完全不同**的东西，
混在一起报会得出错误结论（第一版审计就把三类混成「85 个坐标偏移」）：

| 类 | 现象 | 是缺陷吗 | 治法 |
|---|---|---|---|
| ① **用户数据** | 表里记着 `推理等级 High`，真机上是 `Default` | **不是** —— 表**本来就不该记** | 换掉判据，只留稳定前缀 |
| ② **动态文案** | `已选 3 个`、`第 2 个`、`当前：完全权限` | **是** | 只用不含状态词的前缀 |
| ③ **布局随内容变** | 会话标题长短不同 ⇒ 行高、中心坐标不同 | **不是** —— 坐标只是参考 | 从坐标断言里排除 |

**核心结论：图谱是「结构地图」，不是「快照」。** 凡是「这台机器上恰好是什么值」
的东西（模型名、推理等级、权限、主题、字号、会话标题）一律不能进 `expect`，
也不能进 `els`。

本轮抓到并修掉的两处**真例**（都是「判据只在生成时那台机器上成立」）：

| 屏 | 原判据 | 为什么坏 | 改成 |
|---|---|---|---|
| `model-picker` | `{text:'推理等级 High', exact:true}` | 那是**生成机器上**的配置；真机是 `Default` | `{prefix:'模型 '}` |
| `terminal` | `{text:'cmd.exe'}` | 生成机器是 Windows，真机是 Android 的 `login` | `{sel:'.xterm-screen'}` |

`terminal` 那处顺带立了一条可复用的判据：**xterm.js 这类第三方库的类名
（`xterm-screen` / `xterm-viewport` / `xterm-rows`）跨构建、跨平台、跨用户都稳定**，
而 DSH 自己的 CSS Modules 类名（`_2H3hWW_newSession` vs `hHd-Xa_newSession`）
**每次构建都变**。所以非交互的结构锚点优先用第三方类名，别用自家 hash。

**机械判据（已固化进 `build-uiagent-screens.mjs`，违反即拒绝打包）**：

- `els` 里含**中文引号 `“”`** 的元素一律丢掉 —— 那是前端嵌用户数据的模板
  （`会话“<标题>”的操作`、`在“<工作区名>”中新建会话`）。已全图谱扫过**无反例**。
- `expect` / `enter` 里含中文引号 ⇒ **直接报错退出**（比 `els` 里含更严重：
  判据失效会让 `goto` 永远进不去那一屏）。
- 丢掉的条数会**打印出来** —— 静默丢弃等于看不见的损失。

##### ⚠️⚠️ 真机采集：两个会**改动用户状态**的坑（都已堵住）

在真机上跑采集/审计时，「清一个 localStorage 键」**不是无害操作** ——
前端有兜底行为，清了 A 会生出 B。两个坑都实际踩到过：

**坑一：清 `dsh.sessions.current` ⇒ 前端自己新建一个空会话。**

`dsh-client-ui-workspace/lib/client.js` 的 `reuseOrCreateBlank()`（L800）先找
「blank 且 cwd 匹配且未归档」的会话复用（L806），**一个都找不到就 L808
`this.sessions.create(...)` 新建一个**。所以「清掉 → 重载」得到的不一定是空态，
而是「前端挑一个 / 造一个」。实测后果：跑完全量审计后用户的 `current` 从他自己
的会话变成了一个新建的空会话（`blank:true` / `turns:0`），**界面停在新会话页上**。

三道防护（缺一不可，都在 `ui-map-real-frontend.mjs`）：
1. 每屏重置时**先把 `current` 写回原始值**再动别的键（让「造一个」的前提不成立）；
2. 空会话态那三屏（`hero`/`access-mode`/`preset-picker`）**跑完立刻**写回 `current`；
3. 收尾**逐键回读核对** + 拿「跑之前的会话 id 集合」做差集，把**本次新增的 blank
   会话**逐个 `workspace/archiveSession` 归档掉（归档是官方隐藏手段，不删数据）。

⚠️ 归档的 RPC 路径是 **`/api/workspace/archiveSession`**，**不是** `session/archive`
—— 归档属于 workspace 命名空间。路径猜错时引擎返回的是**纯文本 `not found`**
（路由兜底）而非 JSON 错误，症状是 `Unexpected token 'o', "not found" is not valid JSON`。
⚠️ `payload` 外面必须再套一层 `args`（`payload:{args:{request:{…}}}`）；少这层
`session/list` 会返回**空列表**（不报错），实测踩到过。

**坑二：`seedSession()` 会真发一条消息进用户的会话日志。**

`drawer` 屏需要「有会话」才开得出来，PC 上的做法是发一条「图谱种会话」。
但真机上用户的 `current` **本来就指向活跃会话**，`resetToBase()` 一还原就是会话态，
那条消息纯属多余 —— 而它会**永久留在用户的会话记录里**，用户翻会话就能看到。
真机改用 `ensureSessionState()`：**只断言在会话态，不写任何东西**。

**判据**：任何采集/审计脚本，跑完必须能回答「用户解锁手机后看到的是不是原样」。
回答不了就是没做完 —— 这不是可选的收尾。

##### ⚠️ `offscreen` 是**警告**，不是失败条件

图谱生成器曾把 `tap` 的 `offscreen === true` 也算进入失败，结果真机上**10 屏全卡**
（9 个设置屏的第一步「设置」+ `plugins-panel` 的「插件」）。但实测证据是反的：

```
tap「设置」 ok=true onTarget=true offscreen=true
校验: ✅ 已进入（找 「通用设置」）        ← find 判据成立
共 49 个可交互元素 … 返回/通用设置/模型/内置插件/…   ← dump 里面板元素全在
```

**面板真的开了。** 根因：**探针的 `tap` 是程序化派发**（`el.click()`），不是真手指。
官方侧栏收起时被 translate 到 `x:-312`，里面那个「设置」的中心点落在视口外
（`offscreen:true`），但它在 DOM 里、`onClick` 照常触发。

所以 `offscreen` 只说明「**真手指**点不到」，而本工具链从来不靠真手指。
**判定权交给 `verified`（探针 `find` 复核）** —— 离屏 + 判据成立 = 这条路走得通，照记；
离屏 + 判据不成立 = 交给重试逻辑。

⚠️ 但 `offscreen` 这个事实要**记进图谱**（`step.offscreen`）：读的人得知道这一步
不是「屏幕上看得见再点」，真机手动操作时得先把它滚进视口。

⚠️ 这条与「真机审计只点 `enter`、不点 `els`」是配套的：`els` 里那些离屏按钮
**模型不该去点**（点了就改用户状态），它们不进内建表；而 `enter` 是**路线**，
必须能走通，所以判据要放宽到「程序化点击是否生效」。

#### PC 侧自动化验收（不需要真机）

设备上装的是别处签名的包、无法 `install -r`（会 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`），
而卸载会清掉 `filesDir` 下的整个 Termux 环境与 `$DSH_HOME`。所以验收走
**真实 Chrome + 真实 DOM**（`--headless=new` + CDP，Node 24 自带 `WebSocket`，零依赖）：

```powershell
node tools\ui-probe-verify.mjs            # 探针行为，107 项断言（真实 DOM，含 React 受控输入模拟）
node tools\uiagent-bridge-verify.mjs      # 插件↔原生信箱契约，120 项断言（真插件代码 + 假原生对端）
node tools\ui-map-expect-audit.mjs        # 17 屏判据唯一性（判据撞车 = goto 假到达）
node tools\ui-map-real-frontend.mjs "<带 token 的 URL>"   # 真实官方前端端到端，17 屏图谱 + 交互实测
node tools\build-uiagent-screens.mjs      # 图谱 → 插件内建屏幕表 lib/screens.js
```

⚠️ **`uiagent-bridge-verify.mjs` 的 §⑪ 用引擎自己的断言函数验注册契约。**
`UIAGENT_ENGINE_DIR` 指到引擎的 `node_modules\@deepseek-ai\dsh-tools` 时，
它 import 引擎导出的 `assertSupportedJsonSchema` / `validateJsonSchemaValue`
来判 `output.schema`，而不是自己再写一份 —— 自己写的那份会把引擎的约束
写松，于是「本机绿、真机炸」。未设该变量时跳过并打印提示。

这条是**血换来的**：`ui` 工具曾经**从未注册成功过**，而当时 111 条断言全绿。

##### ⚠️⚠️ 工具注册的硬校验：`output` 声明（缺了整条工具是死的）

引擎的 `ctx.tools.register` 有一道**硬校验**（`dsh-tools/lib/index.js:2881`）：
`output` 必须是对象、`render` 必须是函数，随后还要过 `assertSupportedJsonSchema`。
**缺这一块不是降级而是抛错**：

```
[engine] dsh-android-uiagent (.../lib/index.js): TypeError:
         tool "ui" must declare output { schema, render, presentationMeta? }
[engine] dsh: warning: 1 entry did not activate
```

后果是**工具从未出现在模型的工具列表里** —— 模型看不到、调不到，而插件本身
「加载成功」的日志照常打印。`ui` 工具就是这样哑了整整一段时间，日志里从来没有
`uiagent` / `ui-probe` 的任何一行，原因就在这里。

⚠️ **为什么 111 条 PC 断言全绿却漏了它**：那些断言直接调 `tool.execute()`，
**绕过了 `register`**。凡是「引擎在注册期校验的东西」，直接调 `execute` 都验不到。
所以 §⑪ 改为 import **引擎自己的** `assertSupportedJsonSchema` /
`validateJsonSchemaValue` 来判，而不是自己再写一份（自己写的会把约束写松）。

⚠️ `output.schema` 里 **`details` 必须留空 schema**：它的形状随 action 变
（`dump` 是数组、`tap` 是元素对象、`wait` 带 `satisfied`…），收窄成固定形状会在
真机上以 `ToolOutputError` 的形式炸在渲染之前。引擎接受的子集是
`type/oneOf/properties/required/additionalProperties/items/enum/const` + 注解，
**空 schema `{}` 是合法的**，正是为这种「形状不定」准备的。

⚠️ **为什么本插件手工构造 `output` 而不用 `defineTool`**：`bubble-tool` /
`root-tool` / `shell-tool` / 本插件都住在 `$DSH_HOME/<tool>/` 下，那里**没有
`node_modules`**，`import { defineTool }` 解析不到。手工构造是这个布局的必然选择。

⚠️ **`uiagent-bridge-verify.mjs` 的假原生必须「会演」**：它维护一个「当前在哪一屏」，
`find`/`wait` **只在当前那一屏**上判判据。第一版写成了「逐屏比对」（拿每一屏自己的
判据比对传来的判据），于是**每一屏都命中** —— `where` 报「17 屏同时成立」、`goto` 的
「是否已在该屏」永远为真而直接从不到重载那步，8 条断言集体失败。真机上的探针只在
**当前 DOM** 上查，所以「当前屏」才是正确的模型形状。

图谱结果落在 `tools\ui-map-real-frontend.json`：每屏列出每个按钮的 **CSS 中心坐标 +
`sel`**，外加 `⛔被遮挡` / `📴屏幕外` 标记。**「按钮在哪」不用猜，查这个文件。**

⚠️ **图谱是快照，会随官方前端更新漂**。前端升级后重新跑一遍即可，不用手工维护。

**这一跳为什么单列一个脚本验**：整条链路是三跳的 ——

```
模型 ──▶ 插件 ──ui-cmd.json──▶ 原生 Kotlin ──▶ 页面探针
          ◀──ui-result.json──┘
```

页面探针有 `ui-probe-verify.mjs`、真机有 logcat，**只有中间这一跳既不在页面里、
也不需要真机** —— 纯 Node + 文件系统，所以能在本机**用真的插件代码**驱动，只需
一个假的原生对端（只实现「读 ui-cmd → 写 ui-result」那一侧）。

它验的是四件**在真机上只表现为「偶发、看起来随机」**的事，这类 bug 最难查：

| 验什么 | 为什么必须验 |
|---|---|
| `id` 配对 | 上一轮遗留的回执会被当成本轮答案（`sendCommand` 开头 unlink 旧回执就是防这个） |
| 串行化 | 原生只有一个轮询线程 + 一个命令文件；并发写会互相覆盖 ⇒ 前一条**从未执行**，界面没动而模型以为点了 |
| 超时不许返回 ok | 超时被当成成功同样让模型以为点了（这条与遮挡识别是同一类错误的不同来源） |
| 原子写 | 原生读到写了一半的 JSON（`writeJsonAtomic` 先临时文件再 rename） |

另外顺带钉住渲染层的两条**契约**（都是踩过的）：

- **遮挡警告必须排在元素清单之前** —— 模型是逐行扫清单、扫到就开始点的；
  警告跟在后面等于没有。断言直接比两个子串的下标先后。
- **`type` 的 `applied` 是三态**，断言必须分别验 `true`/`null`/`false` 三种渲染 ——
  按布尔断言会把 `null`（异步生效，**正常路径**）误报成失败。

⚠️ 没装 `DSH_HOME` 时**不该注册工具**（前置条件不满足还给模型一个永远失败的工具，
比没有这个工具更糟），这条也在断言里。

`ui-map-real-frontend.mjs` 需要一份**独立实例**（别碰用户正在用的那个 GUI）：

```powershell
# ⚠️ --patch 是**启动器**参数，必须在子命令**之前**；且必须配 --profile
dsh.cmd --profile web --patch "<隔离目录>\mobile-ui.patch.yml" --no-open --port 31999
node tools\ui-map-real-frontend.mjs "http://127.0.0.1:31999/?token=..."
```

| 覆盖 | 判据 |
|---|---|
| `dump` / `find` | 找到 React 的 `role=button`、CSS 中心坐标、`includeHidden`、`filter`、`limit` |
| `tap` | 五个事件按序派发 + **页面计数器真的 +1** + 遮挡识别 |
| **遮挡识别** | `covered`/`blockedBy`、`dump` 顶层 `overlay`+`warning`+`blocked`、`clickableOnly` 过滤、`overlay` 动作的 `present`/`layer`/`clickable`、`wait clickable:true` 不把被遮挡的算作等到了、遮罩移除后全部复位 |
| `type` | **React 的 onChange 真的被触发、拿到的值正确**（模拟实例级 value 拦截器） |
| `scroll` / `key` / `text` / `info` / `html` | 容器真的滚动、按键送达、文本可读 |
| 健壮性 | 未知 action 不崩、返回值可 JSON 序列化、**不污染页面**、幂等装填 |

⚠️ 这套 PC 侧验收的价值已经**三次**兑现：`onTarget` 判据恒为 true、`overlay` 单点采样
自相矛盾、`execCommand` 异步生效被误判成失败 —— 三个都是**真机上只会表现为
「AI 说点过了但界面没动」**的缺陷，在这里被断言抓出来。

#### ⚠️ 并发调用必须串行化

原生只有**一个**轮询线程 + **一个**命令文件。两条命令同时写进去，后一条**覆盖**前一条：

- 前一条命令从未被执行（模型以为点了，界面没动）；
- 它的 `id` 再也等不到回执，`sendCommand` 一路超时到 20 秒；
- 后一条的结果可能被前一条按 `id` 判为「不是给我的」而丢弃。

症状是「偶发、看起来随机」。插件侧因此有一个 `enqueue()` 串行闸门（模型确实会
同一轮并行发起多个工具调用）。⚠️ 闸门用 `.then(task, task)` 而不是 `.then(task)` ——
前一个任务抛异常时也必须放行，否则一次失败会**永久卡死**后续所有调用。

#### `text` 与 `value` 是两种意思（踩过）

| 字段 | 含义 |
|---|---|
| `text` | **按可见标签定位**元素（tap/find/type/wait 共用） |
| `value` | 往输入框里**写的内容**（仅 `type`） |

⚠️ 第一版在插件里做了 `text` ↔ `value` 的翻译，于是 `tap{text:"发送"}` 的定位条件被
当成「输入内容」处理、**转手就把 `text` 删了** —— 所有按文本定位的 tap/find/wait
全部报「找不到元素」，而报错文案完全指不到真因。现在两边字段名一致、**原样透传**，
中间没有任何翻译层，也就没有再搞错的机会。

#### ⚠️ `ok:true` 不表达「点着了」—— 渲染层必须主动报 `onTarget`

`tap` 有三种「执行成功但没点着」的情形，**三种都返回 `ok:true`**：

| 情形 | 探针字段 | 后果 |
|---|---|---|
| 被别的元素盖住 | `onTarget:false` + `hitTopmost` | 真机手指点到的是那层 |
| 中心点在视口外 | `onTarget:true` + `offscreen:true` | 事件发了，但屏幕上没有可点的坐标 |
| 在面板**下面** | `onTarget:false` + `panel` | 它根本不在当前屏幕上 |

所以 `ok:true` 与「界面会不会动」**没有关系**。渲染层如果只报一句「✓ 已点击」，
模型无从判断该不该信这次点击 —— 真机上就出现过点完直接往下走、而界面纹丝不动。

**三条渲染规则**（都在 `uiagent-bridge-verify.mjs` ⑦.6x 里有断言）：

1. `onTarget === true` **且非 `offscreen`** 时，必须给一句正向确认
   （「事件已送达目标元素本身（onTarget:true）」）。⚠️ 措辞刻意是**送达**而不是
   「点击生效」：事件送达 ≠ 界面响应，后者要靠下一步 `dump` 看，写成「生效」会让
   模型跳过复核。
2. `offscreen` **独立成支且排在最前** —— 它的 `onTarget` 是 `true`，走不进
   `onTarget === false` 的分支，而它恰恰是「点了没反应」的第二种成因。
3. `onTarget:false` 还要再分**两态**：有 `hitTopmost` 才是「被上层接收」；
   `hitTopmost` 为空时**根本没有上层元素**（事件落到了空白处，多半是元素在点击瞬间
   被重渲染掉），说成「被遮挡」会把模型引去查 `overlay` 白费一轮。

#### ⚠️ `clear:true` 对 `contenteditable` **不能**靠写空串实现

官方 composer 是 Lexical（`contenteditable`）。旧实现走 `setValue(inp, '')`，
而**「写入空字符串」这个操作根本不存在**。真机实测（CDY-TN90 / Android 10 /
系统 WebView 114）的候选表：

| 写法 | 结果 |
|---|---|
| `insertText('')` | **no-op**（返回 `true`，内容一字不变） |
| 全选 + `delete` | **no-op** |
| 全选 + `insertText('\n')` | **no-op** |
| 全选 + `insertText(非空)` | ✅ 整体替换 |
| 全选 + 派发 `Backspace` `keydown` | 只删**一个**字符（DOM selection 不被 Lexical 认） |
| ✅ 全选 + `insertText('X')` → 派发 `Backspace` | **清空成功** |

做法是 `clearEditable()`：`selectAllIn()`（每次都要**重建** Range，`execCommand`
会消耗掉它）→ `insertText('X')` → 派发 `Backspace`。失败则退回
`el.textContent = ''` + `input` 事件。回执里带 `cleared` 字段说明走的哪条路，
渲染层要报出来 —— 否则模型分不清「clear 生效了」与「clear 被忽略了」。

⚠️ 症状很有欺骗性：旧内容留下、新内容**追加在后面**，而回执里的 `value` 是一长串
拼接结果，模型完全看不出是自己没清掉。

#### ⚠️ 别用 `text` 定位 composer —— 它的 `text` 是 placeholder

composer 的 `text` 字段是 **placeholder**（「发消息或创建任务, / 调用指令, @ 文件或对话」），
**前端改文案就会失配**（实测已从「描述你想要构建的内容…」变过一次），
报错是「找不到输入元素」，完全指不到真因。

`role:"textbox"` 来自 `role="textbox"`，**稳定**。所以 `locate()` 里加了 `role`
分支，但它排在 `sel`/`testid`/`aria`/`text` **之后** —— 同 role 的元素可能并存，
`sel`/`aria` 必须优先。

#### ⚠️ 验证判据必须取自 **DOM**，不能取自模型的回复

真机写动作端到端（`tools/uiagent-device-write.mjs`）第一版让模型「原样贴出 tap 回执」，
它**只抄了 `fmtElement` 那一行**、把 `onTarget` 那行省了 —— 拿回复当证据会得出
「渲染层没生效」的错误结论，而真相是模型没抄。

工具结果在聊天界面里是**渲染成 DOM** 的，DOM 里的文本就是模型实际收到的 `message`。
所以判据是「在页面世界里找含关键字的最深元素、读它的 `innerText`」。

`uiagent-device-write.mjs` 的分工（三个脚本互补，缺一个都推不出「能操作 App」）：

| 脚本 | 验的是 |
|---|---|
| `ui-probe-device-check.mjs` | 探针在真机内核上能跑（**只读**） |
| `uiagent-device-e2e.mjs` | 插件↔原生信箱通、坐标换算对（**只读 dump**） |
| `uiagent-device-write.mjs` | **写动作**：模型点一下，界面真的变了 |

⚠️ 前两个只调 `dump`，而「让 AI 操作 App」的成败**全在写动作上**，只读全绿推不出
「点得动」。该脚本的**界面判据**是「侧栏「设置」按钮的 CSS x 从 -190 变成 140」
（收起时是负值，滑入后为正）—— 不信回执，直接量界面；且**全程可逆**，
结束时恢复原状并核对（测试不能把用户的界面改掉）。

⚠️ 探针版本号在 `ui-probe-device-check.mjs` 里**从源码正则解析**，不写死。
写死的后果是「改探针必须记得改这里」，而漏改的表现是「脚本报失败、真机一切正常」，
最容易被误判成资产没生效。

### ⑫.6 日志与诊断 —— 拿到日志就能定位问题

**这套系统解决的是一个此前一直存在的空洞**：全工程 476 处 `Log.*` 只写 logcat，
**进程一死现场就没了**。而本项目最需要排查的恰恰是「任务跑一半自己结束了」
「App 突然闪退」「装了某个插件后打不开」这类**伴随进程消失**的问题 ——
logcat 里只剩一片空白。

#### 为什么不是「多打点日志」

「极其强壮」在这里有具体含义，四个失败模式各自要堵：

| 失败模式 | 症状 | 对策 |
|---|---|---|
| 进程被杀，缓冲没落盘 | 崩溃前最后几行丢了 —— 而那正是要的 | 应用日志**同步写 + 每行 flush** |
| 高频日志把磁盘写爆 | 用户手机卡、存储满 | 1 MB 轮转 + 32 MB 总配额 + 限流折叠 |
| 崩溃处理器自己死锁 | 「闪退」变「永久卡死」 | 崩溃路径用 `tryLock`，**绝不阻塞** |
| 日志把密钥带出去 | 上报即泄漏 | 落盘前统一脱敏（[Redactor]） |

#### 三层存储

| 层 | 文件 | 写入方式 | 为什么 |
|---|---|---|---|
| 内存环形缓冲 | （不落盘） | 同步入队 | 4000 条，**最新**。崩溃时磁盘可能还差几行，这里补上 |
| 应用日志 | `app.log` | **同步 + 每行 flush** | 崩溃安全。代价是慢，所以只给 476 处 `DshLog` 用 |
| 引擎/网页日志 | `engine.log` / `web.log` | 异步队列（4096，满了丢） | 高频。**绝不能阻塞**引擎的 64 KB stdout 管道 |

⚠️ 三档写入策略是刻意的，不是不统一：`app.log` 慢但丢不了，另两个快但可能丢尾巴 ——
把它们对调会让引擎的 stdout 管道堵死，症状是「引擎卡住不动」而不是「日志少了」。

#### 476 处调用点一行不改 —— `DshLog` 门面

改造技巧：**换掉 `Log` 这个名字**，而不是改 476 个调用点。

```kotlin
-import android.util.Log
+import com.dsh.android.log.DshLog as Log
```

`DshLog` 的方法名/参数顺序/返回类型与 `android.util.Log` **逐一对应**（`v/d/i/w/e/wtf`
各两档、`println`、`isLoggable`、`getStackTraceString`），所以 `Log.i(TAG, "…")`
一个字符都不用动。

⚠️ **三参重载的 `tr` 必须是 `Throwable?`**：Java 侧 `android.util.Log` 的参数是平台类型，
Kotlin 调用点传可空值（实测 `GeckoWebHost.kt:516` 的 `onError` 回调就是 `Throwable?`）。
写成非空会让 **gecko flavor 编译失败而 system 通过** —— 这种只在单个 flavor 暴露的
签名问题最容易漏（本系统开发时就漏过一次）。

⚠️ **调用点里不能出现全限定写法**（`android.util.Log.i(...)`），那会绕过门面。
改造前已 grep 确认零处。

⚠️ `AppLogger.kt` 已删除。它此前与这套**并存**，而它的 `init()` 从来没被调用过
（只在 `TermuxBootstrap` 里当 tag 用）—— 两套日志系统并存会把日志劈成两半。

#### 四个崩溃入口（都在 `CrashRecorder`）

| 类别 | 现场文件名 | 判据 |
|---|---|---|
| 未捕获异常 | `<时间>-uncaught-exception.txt` | 装了默认 `UncaughtExceptionHandler` |
| 渲染进程崩溃 | `<时间>-renderer-crash.txt` | WebView/GeckoView 报 `didCrash` |
| 渲染进程被回收 | `<时间>-renderer-reclaimed.txt` | 同上但 `didCrash=false`（**不是崩溃**） |
| 引擎退出 | `<时间>-engine-exit.txt` | `Process.waitFor` 返回，带退出码与是否预期 |
| 主线程卡死 | `<时间>-main-thread-stall.txt` | 自建看门狗（500 ms ping，15 s 无响应） |

⚠️ **`ApplicationExitInfo` 用不了** —— 它需要 API 30，而本 App 因硬约束②停在
`targetSdk 28`。所以「上次怎么退出的」只能**自己记**：`BootState` 在启动时写
`boot-state.json` 标记「正在运行」，正常退出时改成「干净退出」。下次启动读到
「不干净」就说明上次没走正常路径。

#### ⚠️ 诚实分级是硬要求：**「非正常结束」不等于「崩溃」**

`BootState.Previous.describe()` 的措辞是「上次未正常结束（时间，**无堆栈**）」，不是
「崩溃了」。因为「不干净」有五种可能，而它们的排查方向完全不同：

| 真因 | 特征 | 下一步 |
|---|---|---|
| 未捕获异常 | 有 `uncaught-exception` 现场 | 看堆栈 |
| LMK 内存回收 | 无现场，内存快照里 heap 高 | 看内存 |
| ROM 冻结/后台清理 | 无现场，常在息屏后 | 看保活设置 |
| 用户强停 / 划掉 | 无现场 | 问用户 |
| 断电 / 重启 | 无现场，`uptimeMs` 短 | 问用户 |

⚠️ **看门狗记的是「主线程无响应（自建看门狗，非系统 ANR）」**。`targetSdk 28` 下拿不到
系统 ANR 堆栈，**绝不能伪造一个** —— 编一个看起来像 ANR 的堆栈比不写更糟，
它会把排查带向错误方向。

⚠️ **`CrashRecorder` 必须用 `tryLock`，永远不能用 `lock`**：崩溃可能发生在**已经持有
日志锁的那个线程**上，阻塞等待会把「闪退」变成「永久卡死」。拿不到锁也要照写现场文件
（那条路径不碰锁）。

#### 两个入口（缺一个就有一半场景看不到日志）

| 入口 | 在哪 | 覆盖场景 |
|---|---|---|
| ① 浮层按钮 | 加载/崩溃页上的「运行日志与诊断」 | **GUI 打不开**时 |
| ② 设置页分节 | 设置 →「运行日志与诊断」（`settings.section`，`order: 28`） | GUI 正常时 |

⚠️ **①在浮层上是「所有状态都显示」的**，与 `overlaySafeMode`（`isError` 门控）不同。
理由：这条入口的存在意义就是「界面不正常」，把它做成错误态才显示等于自相矛盾。

⚠️ **②必须是 `settings.section`，不能做成页面插件**。页面插件跑在 WebView 里，
而「引擎起不来」「插件把前端搞崩了」恰恰是页面插件一起死掉的场景 ——
用页面插件等于在最需要日志的时候把入口也弄没了。

⚠️ **`LogViewerActivity` 必须零依赖**：纯原生、不碰引擎、不碰 WebView、不初始化任何东西。
它是崩溃循环里唯一还能开的界面。所以列表用**框架 `ListView` + `BaseAdapter`**，
而不是 `RecyclerView` —— `material:1.12.0` 只传递来 `recyclerview:1.0.0`（compile scope），
用它就得加依赖，而「能开」是这条入口的唯一 KPI。

⚠️ 它读的是 `LogStore.entries()`（内存缓冲）而**不是**磁盘文件：磁盘上的 `engine.log`
是异步刷的，最后几行可能还没落盘，而用户要看的经常就是「刚刚那一下」。

#### 诊断包（`DiagnosticBundle`）—— 输出本身就要能定位问题

`diagnostic-<时间>-<bootId>.zip`，条目**按价值排序**写，超 8 MB 从低价值端丢：

```
environment.json   设备/系统/显示/内存/存储/权限/运行时
diagnosis.json     自动结论 + 7 项检查（含 summary.headline）
timeline.jsonl     事件时间线（启动→引擎就绪→渲染→崩溃）
crashes/           崩溃现场全文
files.json         关键文件的存在性/大小/修改时间
web.log / engine.log / app.log（含 .1 代）
manifest.json      最后写 —— 它记录前面实际写进去什么
```

⚠️ **`manifest.json` 必须最后写**，否则它记录的清单与包内容不符。
⚠️ **每个条目是全有或全无**：写一半失败就整条丢弃并在 `completeness` 里记 `truncated`。
半个 zip 条目比没有更糟（解压报错，分析者以为包坏了）。
⚠️ 超预算时**留尾巴不留头**（日志越新越有价值），并把 `truncated: true` 写进 `completeness`
—— 静默截断会让分析者把「没看到」当成「没发生」。

`diagnosis.json` 的检查项全部**给证据不给结论**，例如：
`last-exit` → `warn` / `"上次未正常结束（2026-10-13 19:20:11，无堆栈）"`。

#### 上传（`LogUploader`）

⚠️ **崩溃进程做不了网络 IO**。所以崩溃路径**只落包 + 标 pending**，
真正的上传由**下次启动**的 `uploadPendingBundles()` 做。
用户如果从此不再打开 App，包就传不出去 —— 这是平台限制，不是缺陷，文档里要说明。

- 端点/令牌在 `DshApplication.LOG_UPLOAD_ENDPOINT` / `LOG_UPLOAD_TOKEN`
  （**`val` 不是 `const val`** —— 可空类型不能是 const）。服务器就绪后填这里。
- ⚠️ **未配置时上传必须显式失败**（「尚未配置日志服务器…」），绝不能静默成功 ——
  那会让用户以为日志已经发出去了，而实际什么都没发生。
- ⚠️ **成功与失败都要给 Toast**，并把服务器返回的 `id` 显示出来供用户引用。
- 手写 `multipart/form-data`（`HttpURLConnection`）—— 本项目没有网络库，
  不为一个上传接口引入依赖。
- `device-id` = 安装期生成的随机 UUID。**刻意不用 IMEI/序列号**。

#### 脱敏（`Redactor`）—— 不是可选项

包要离开设备。落盘**之前**统一脱敏，三组模式：

| 模式 | 拦什么 |
|---|---|
| `skTokenPattern` | `sk-…` 形式的 API key |
| `secretKeyPattern` | `api_key=` / `token:` 等键值对 |
| `urlSecretPattern` | `?token=…` —— **引擎启动那行 URL 就带 token** |

⚠️ 第三条是实测逼出来的：`[engine] dsh web: http://127.0.0.1:3080/?token=…` 是每次启动
必打的一行，不拦就等于每次上报都泄漏一个可用凭据。

**包内容边界**：含设备/系统/App 版本、脱敏日志、插件/文件/设置状态；
**不含**会话正文（只含元数据）、凭据、工作区文件内容。

#### `logs` 工具 —— 让模型自己查（⑫.3 的同一套做法）

与 `bubble` / `statusbar` / `ui` 工具同构：`mobile-ui-plugin/dsh-android-logs/` 打包成
`assets/logs-tool.zip` → `$DSH_HOME/logs-tool/dsh-android-logs/`，
`EngineManager.ensureLogsTool()` 解包、`writeLogsToolPatch()` 生成 overlay，
命令走 `LogCommandBridge` 的文件信箱（`logs-cmd.json` / `logs-result.json`，200 ms 轮询）。

| action | 做什么 |
|---|---|
| `summary` | 一眼结论（**默认**，便宜，先调它） |
| `read` | 读最近 N 行，可按 `level`/`source`/`query` 筛 |
| `crashes` | 列崩溃现场，或按 `crashFile` 读全文 |
| `export` | 生成诊断包并返回路径 |

⚠️ **必须用独立的信箱文件**（不复用 bubble 那一对）：回执按自增 `id` 配对，而各插件
各自从 1 开始计数，同一个 `id=5` 会同时存在两条语义不同的请求。这类错配的表现是
**「AI 说日志里没有这条错误」而它看起来像真的查过了** —— 比报错危险得多。

⚠️ **超时上限 10 s**（bubble 是 4 s）：`export` 要打包几 MB 的 zip，4 s 会稳定超时，
而超时会被模型当成「导出失败」。

⚠️ **插件描述里必须写死三条边界**，否则模型会把「日志里没有」当成「没发生过」：
① 文件 1 MB 轮转 + 只留一代，很老的没了；② 重复行会被限流折叠；
③ 进程被系统直接杀掉可能**完全不留日志**。

⚠️ **`crashFile` 只接受纯文件名**（插件与原生两侧都校验）：这个值来自模型，
原生侧要拿它拼 `logs/crash/<name>`，放行 `../` 就等于给模型一个任意文件读取口。

⚠️ 插件侧对 `read` 的结果**再截断一次**（500 行 / 256 KB）—— 那是**上下文预算**限制，
不是能力限制。不截断会把这一轮对话直接撑爆，而模型不会自己意识到该少要点。

#### ⚠️ 四个踩过的坑（0.2.8 → 0.2.8-logsfix 修掉，都是「静默失效」类）

**① 两个 flavor 各有一份手工白名单，往 `PageBridge` 加方法必须三处同改。**

`SystemPageBridge`（`app/src/system/.../SystemWebHost.kt`，`addJavascriptInterface`
要每个方法带 `@android.webkit.JavascriptInterface`）与 gecko 的
`dispatchInvoke` 的 `when(call)` + `content.js` 的 `BRIDGE_INSTALLER_SOURCE`
**都是白名单**。`SystemPageBridge` 的类注释写着「恰好是页面侧用的那 16 个，不多不少」——
只改 `PageBridge` 接口不改白名单，`window.DSHAndroid.logSummary` 就是 `undefined`，
设置页那一块显示成**「当前环境没有原生桥」**，不报错、不崩溃。

⚠️ gecko 侧还有第三处：`content.js` 里 `window.DSHAndroid = {...}` 那段。
`logSummary` 走**快照缓存**（native messaging 只能异步，而页面侧是同步读），
所以它同时要出现在 `pushSnapshot()` 的 JSON 里、`snapshot` 对象里、以及
`getLogSummary` 那个 `exportFunction` 里 —— 四处。

⚠️ **改 `content.js` 必须 bump `manifest.json` 的 `version`**（否则 GeckoView 用旧扩展）。

**② 手动上传成功后必须删掉 `pending/` 里那一份。**

`LogUploader.upload()` 原来只上传不删，于是下次启动 `retryPending()` 把同一个包再传一遍。
服务器上留了铁证：两份包 `sha256` 完全相同、间隔 2 分钟，`reason` 分别是
`manual` 与 `startup-retry`。手动与自动补传必须共用「成功即出队」这一个语义。

**③ 崩溃包原来**从不**生成 —— `stashForCrash()` 定义了却无人调用。**

`CrashRecorder` 只写现场 `.txt`，`pending/` 永远是空的，`retryPending()` 什么都传不出去：
**「崩溃自动上传」这条链路从来没生效过**。服务器上收到的包 `reason` 只有
`manual` / `startup-retry`、一个 `crash` 都没有，就是判据。

正确形状是**分两段**：崩溃当场只写现场文件（快、可靠，崩溃进程只有几秒预算），
**下次启动**由 `LogUploader.collectCrashScenes()` 补打包 + 入队。幂等判据是
`crash/.queued` 里记的「已打包现场文件名集合」。
⚠️ 标记文件名**不能以 `.txt` 结尾** —— 现场文件就是按 `.txt` 认的，否则标记会把自己
算进集合，签名永远在变、每次启动重复打包。

**④ 「导出诊断包」原来落到 app 私有目录，用户根本拿不到。**

`filesDir/logs/export/` 是私有目录，文件管理器进不去 `/data/data/<包名>/`，
于是「导出」对用户等于**「提示保存成功，但那个文件我永远拿不到」**，
也就没法通过聊天软件发给开发者 —— 而这个功能存在的全部意义就是让用户把包发出来。

做法：打包仍写在 `logs/export/`（上传链路的输入，**不能移走**），
额外**复制**一份到 `LogContext.publicExportDirOrNull()`：有存储权限 → `/sdcard/Download/`；
无权限 → `getExternalFilesDir(DOWNLOADS)`（不需权限，文件管理器仍可达）；
都拿不到才退回私有目录。`logs` 工具的 `export` 同样返回 `sharedPath`。

#### 判定「崩溃自动上传」到底通没通

```powershell
# 服务器侧：看有没有 reason=crash 的包
ssh ... 'cat /workspace/dsh-logs/index.jsonl'
```

只有 `manual` / `startup-retry` ⇒ 崩溃那条链路没生效（见上面 ③）。
⚠️ 判断「重复上传」看 `sha256` 是否相同（见 ②），不要只看条数。

#### ⚠️ 两处「不是本 App 的错，但会被记成本 App 的错」的归因修正

诊断包的 `environment.json` / `crashes/*.txt` 是排查的**唯一入口**。下面两条会让
入口本身指向错误的方向 —— 不是功能坏了，是**归因错了**，代价是排查时间全花在
自己的代码上。

**① `device.kernel` 曾经大面积为空 —— 是 SELinux 不给读，不是设备老。**

现象：62 个诊断包里 **41 个** `device.kernel` 是空串。第一直觉是「老设备没有这个
文件」，但按 SDK 分组后**恰好相反**：SDK 35 的设备 20/20 全空，SDK 28/31 的反倒有值。

真机实测（CDY-TN90，HarmonyOS，Android 10 / SDK 29，`getenforce` = `Enforcing`）：

```
run-as com.dsh.android cat /proc/version     → cat: /proc/version: Permission denied
adb shell cat /proc/version                  → Linux version 4.14.116 (HarmonyOS@localhost) …
run-as com.dsh.android /system/bin/uname -a  → Linux localhost 4.14.116 #1 SMP PREEMPT … aarch64
```

Android 10+ 的 SELinux **拒绝 `untrusted_app` 读 `/proc/version`**（`/proc/sys/kernel/osrelease`、
`/proc/sys/kernel/version`、`/system/build.prop` 同样被拒），而 `/proc/cpuinfo`、
`/proc/self/status`、`/proc/self/maps` 可读。原实现是

```kotlin
runCatching { File("/proc/version").readText() }.getOrDefault("")
```

—— `runCatching` 把 EACCES **静默吞成空串**，于是「权限被拒」在包里长得跟
「设备没这个信息」一模一样。

做法（`EnvironmentSnapshot.kernelVersion()`）：先试 `/proc/version`，读不到回退
`ProcessBuilder("/system/bin/uname", "-a")`（该二进制在 app 域**可执行**），
两条都失败才返回 `""`；同时 `collect()` 在 kernel 为空时补写
`kernelUnavailable: "read-denied"`，让「没读到」在包里**可区分于「本来就没有」**。

⚠️ 回退路径必须 `waitFor(3, SECONDS)` + 超时 `destroyForcibly()`，否则子进程卡住会
把 `collect()` 一起拖住（它跑在诊断包构建路径上）。

**② `org.chromium.base.JniAndroid$UncaughtExceptionException` 不是本 App 的崩溃。**

3 份现场（2 个包：`20261014-013411-94efb107`、`20261014-013846-35b8918e`，
OPPO PKJ110 / Android 16 / API 36 / `0.2.8-logsfix (16) system`）被判成 App 的
**硬崩溃**，`crash-scenes` 直接报 fail。看栈才发现：

```
reason: org.chromium.base.JniAndroid$UncaughtExceptionException: Native stack trace:
#00 pc 0x… /my_product/app/TrichromeLibrary64CN/TrichromeLibrary64CN.apk (offset 0x8dc000)
…（14 帧全在这个 apk 里）
   ← org.chromium.base.JniAndroid.handleException
   ← org.chromium.content.browser.input.ImeAdapterImpl.r
   ← ImeAdapterImpl.updateState
```

那是**这台 ROM 的系统 WebView 内核在 IME 状态更新时自己 native 崩了**，
抛进我们的默认未捕获处理器。`grep` 全工程 `app/src/**` 确认**零处**处理过它。

做法（`CrashRecorder`）：新增 `isWebViewKernelCrash(err, stack)` —— 要求异常类名含
`JniAndroid` **且**栈里出现 `TrichromeLibrary` / `libmonochrome` /
`JniAndroid.handleException`（两个条件同时成立才认，避免误伤本 App 自己的异常），
命中时给 `reason` 加前缀 `【系统 Web 内核崩溃，非本 App】`。

⚠️ **只改归因文案，不改 level、不改 `isHardCrash`。** 界面确实死了（用户看到的是
App 崩），把它从硬崩溃里摘出去会让 `crash-scenes` 漏报真实后果。**归因准确 ≠ 降低严重度。**

### ⑬ 「添加插件」对话框少了「npm 官方源」—— 不是漏配，是被去重合并掉了

**症状**：手机端对话框的「安装源」下拉只有**一项**（中国大陆镜像源）+ 自定义地址，
桌面端有两项（npm 官方源 + 中国大陆镜像源）+ 自定义地址。功能没坏（选「自定义地址」
填 `https://registry.npmjs.org/` 等效），但看起来像手机端少了功能。

**根因：列表是官方前端按 URL 动态去重算出来的，三处来源撞成了同一个 URL。**

`@deepseek-ai/dsh-client-ui-plugin-manager/lib/client.js` 的 `offeredRegistries()`
遍历三处、按 `normalizeRegistry()` 归一化后去重：

```
[config.registry] + [config.fallbackRegistries] + [pnpm 自身配置(~/.npmrc) 指的源]
```

而 `@deepseek-ai/dsh-plugin-manager` 的 `fallbackRegistries` **默认值就是**
`NPMMIRROR_REGISTRY`。本机 `MirrorProbe` 测速后把
`registry=https://registry.npmmirror.com` 写进了 `~/.npmrc`，于是三项归一化后
**全是同一个 URL** ⇒ 去重后只剩一项。桌面版 `.npmrc` 指的是官方源，与 fallback
不同 ⇒ 两项都留下。**所以这是「被合并」而不是「少配了」。**

**做法**：`EngineManager.writePluginManagerPatch()` 生成
`$DSH_HOME/plugin-manager.patch.yml`，显式声明两项：

```yaml
- id: plugin-manager
  name: '@deepseek-ai/dsh-plugin-manager'
  config:
    registry: 'https://registry.npmmirror.com/'
    fallbackRegistries:
      - 'https://registry.npmmirror.com/'
      - 'https://registry.npmjs.org/'
```

⚠️ **`fallbackRegistries` 里必须也放镜像源**，不能只放官方源。`registryPlan()` 有：

```js
const ownIsPublic = own !== null && (own === "https://registry.npmjs.org/" || fallbacks.includes(own));
...
if ((first === null || firstKey === own) && !ownIsPublic) return [normalizedFirst];
```

`own` 是 pnpm 自己报的源（本机 = npmmirror）。fallback 里不放它 ⇒ `ownIsPublic`
为 false ⇒ 安装**只试一个源、没有回退**。放了 ⇒ 列表是 `[npmmirror, npmjs]`
（镜像在前，与桌面版顺序一致），且安装先走镜像、失败自动回退官方源。

⚠️ **URL 必须逐字与官方常量一致，含尾斜杠。** `normalizeRegistry()` 会给 pathname
补尾斜杠，所以 `.npmrc` 里无斜杠的写法归一化后也相同（去重键能对齐）；但我们自己
写进 overlay 的常量要带斜杠，免得读者以为两者不同。出处：
`dsh-plugin-manager/lib/index.js` 的 `NPMMIRROR_REGISTRY`。

⚠️ **`name` 必须与 base 层逐字一致**，否则 `applyEntryPatches()` 打
`patch: name mismatch ... skipping` **整条静默跳过**（只在日志里 warn）。
只写 `config:` **不会**动 base 层那行的 `disabled: !!js ...` —— patch 是
**逐字段覆盖**（`target[key] = value`），不是整行替换。

**为什么用 `--patch` 而不是用户层**：`plugin-manager` 的 `inject` 里**没有
`settings`**，这两项不是 GUI 设置项（`settings/describe` 里没有），用户无从覆盖 ——
不存在「锁死用户配置」的问题。与 `writeSandboxPatch()` / `writeWorkspacePatch()` 同类，
每次启动整体覆盖。

日志验收：`已写入插件源 overlay: plugin-manager.patch.yml`，且启动命令行里能看到
`--patch .../plugin-manager.patch.yml`，同时**不应**出现 `name mismatch`、
`entry not found`、`did not activate` / `pending`。

### ⑭ Web 端口 3080 被占用 —— 默认沿用，撞车才让内核分配

**症状**（粉丝反馈的真机截图）：启动页停在

```
引擎启动失败（引擎进程已退出）。最后输出:
dsh: startup failed: 2 required plugins did not activate

Failed plugins (1):
webserver (required)
Package: @deepseek-ai/dsh-host-webserver
Error: listen EADDRINUSE: address already in use 127.0.0.1:3080
...
Plugins waiting for services (10):
connection (required)  webRuntime
open-in-app            webServer, connection
...（10 行全是连锁反应）
```

**根因**：`3080` 是引擎自己的默认值（`dsh-web-app/cordis.patch.yml` 的
`port: !!js ctx.webStartup.port ?? 3080`），而 App 原来**不传 `--port`**，
于是每次启动都去抢 3080。而 `dsh-host-webserver` 的 `listen()` 失败是
**直接 reject 插件激活**（`server.once("error", reject)`），它是 required 插件
⇒ 整个引擎退出。**没有「端口被占就换一个」的退路。**

⚠️ **那 10 行 `waiting for services` 是连锁反应，不是独立故障。** 根只有
webserver 一个 —— 与 ④ 那条教训同构：**排查 `waiting for` 先看 `did not activate`。**

**占用者是谁**（截图判断不了，但分支有限，按概率排）：

1. **上一次残留的引擎进程**（最可能）。全工程**没有任何地方调用
   `EngineManager.stop()`** —— 只有定义、没有调用者。`ProcessBuilder` 起的 node
   是普通子进程，父进程被 LMK / 国产 ROM 清理时它**不会跟着死**（没设 PDEATHSIG），
   被 reparent 到 init 后继续占着 3080。特征是**稳定复现**，重启手机才解。
2. **应用分身 / 双开 / 工作资料**。`127.0.0.1` 是共享回环，两份实例抢同一个端口。
   特征是「先开的那份永远正常，后开的必失败」。
3. **引擎侧被手动跑过第二个 `dsh --profile web`**（本 App 带终端面板，见 ⑩）。
4. 别的 App 占了 3080（>1024 任何 App 都能 bind），概率最低。

**做法**（`EngineManager.resolveWebPort()` + `isLoopbackPortBusy()`）：
启动前先探一次 `127.0.0.1:3080`，**空闲就照旧不传 `--port`**（行为与改动前逐字一致），
**被占就传 `--port 0`** 让内核挑一个空闲端口。

```kotlin
// start() 的 buildList 里
resolveWebPort()?.let { port -> add("--port"); add(port.toString()) }
```

⚠️⚠️ **`--port` 必须排在所有 `--patch` 之后，紧邻 `--no-open`。位置是硬性的。**

`--port` 属于 **web app 的内层 flag**，而 `@deepseek-ai/dsh/lib/bin.js` 的 launcher
**只解析自己的 `--profile` / `--patch`**，规则是「**第一个不认识的 token 就是内层参数的
起点，其后原样转交**」（`bin.js` 顶部注释原文）。所以插到 `--patch` 前面时，
后面的 `--patch` 全被当成内层参数：

```
[engine] error: unknown option '--patch'
```

引擎**直接起不来**，且报错指向 `--patch` 而完全看不出是 `--port` 的位置问题。
真机实测踩过（第一次装上去就是这个症状）。改这一处时**先看 `bin.js` 的参数归属**
再决定插入点，别按「越早传越保险」的直觉排。

⚠️ **判据与 `AdbMdns.isPortInUse` 是反的**：那里「bind 失败 = adbd 在听」是**成功**，
这里「bind 失败」就是**真被占用**。两处语义不同，别互相抄。

⚠️ **不能一律传 `--port 0`**：那样每次启动端口都变，而移动端 UI 插件与用户的
收藏/书签都可能记着 `127.0.0.1:3080`。只在真被占时才让出端口。

⚠️ **`--port 0` 是官方支持的**（`dsh-web-app/lib/startup.js` 的
`"--port <port>"` 帮助文本明写 "pass 0 to let the OS pick a free one"），
`WebServer` 回填 `listenedPort = server.address().port`，打印的 URL 用的也是它 ——
所以 App 侧**不用改任何解析逻辑**（`start()` 里的正则本来就是 `\d+`）。

⚠️ **探测与真正 `listen` 之间天然有竞态**（探测完端口被抢走）。窗口只有毫秒级，
且失败后果与改动前完全相同（引擎报错退出），所以**不为此加重试** ——
真正的占用源是上一个残留进程，它不是并发抢端口。

⚠️ **browser-trust fence 不受影响**：`dsh-client-connection` 的
`isTrustedApiRequest()` 对 `isLoopbackHostname()` 的 Host **不比对端口**
（`127.0.0.1:任意端口` 都算 loopback），所以换端口不会让 `/api` 403。

日志验收：正常路径出现 `Web 端口 3080 空闲，沿用引擎默认值`；撞车时出现
`Web 端口 3080 已被占用（多半是上一次残留的引擎进程），改由内核分配空闲端口`，
随后 `[engine] dsh web: http://127.0.0.1:<非 3080 端口>/?token=...` 与
`引擎就绪: http://...` 照常。

### ⑮ 内置号池 —— 密钥在 TLS 层注入，粉丝挖不到

**需求**：给粉丝免费用内置的 Agnes 号池（当前 **132 个密钥，国内站 42 + 国际站 90**），
但**不让他们把密钥挖出来**。

> ⚠️ 数量随密钥池增删而变，**别把这里的数字当常量**。早期版本写的「14 个
> （7 + 7）」是首版规模，早就过时了；权威来源永远是
> `keyinject-keys.h` 里的 `KI_CN_COUNT` / `KI_INTL_COUNT`
> （由 `tools/build-keyinject.ps1` 按 `agnes-keys.properties` 的行数生成）。

**先说结论，别夸大**：本 App 给了 AI `bash` 与 `shell` 工具，粉丝**能对 AI 说话**。
所以「密钥藏在设备上」这条路本身是死的 —— 只要密钥在磁盘/环境变量/JS 里出现过
一次，问一句「打印所有环境变量」就出来了。**唯一彻底的解是把密钥放到服务器上做
中转**（密钥永不落设备），那需要一台服务器。

本方案是在「不引入服务器」前提下能做到的上限：**让密钥从来不进入 JS、文件、
环境变量**，只在 TLS 加密前的最后一刻于 C 库内存里出现。

#### 三个组件

| 文件 | 作用 |
|---|---|
| `app/src/main/jni/keyinject.c` | `LD_PRELOAD` 注入库，hook 4 个 OpenSSL 符号（**手写源码，入库**） |
| `tools/build-keyinject.ps1` | 读密钥清单 → 生成混淆头文件 → NDK 编译出 `assets/keyinject.bin`（**入库**） |
| `agnes-keys.properties` | 明文密钥清单（**不入库**，`.gitignore` 已覆盖） |

产物链路：`agnes-keys.properties` → `keyinject-keys.h` → `keyinject.bin`
（后两者都是生成物；头文件也**不入库**，因为混淆可逆、等价于明文）。

`EngineManager` 侧：`ensureKeyInject()` 解到 `filesDir/libkeyinject.so`；
`ensureKeyPoolProvider()` 把号池 provider **并入 profile 的用户配置层**
（`$DSH_HOME/profiles/web/cordis.patch.yml`，只补缺、不覆盖）；`start()` 里把该库
**追加**进 `LD_PRELOAD`（与 linkcompat 并存），并把 `AGNES_CREDENTIAL`
环境变量设成**占位符**。

⚠️ **不要把它写回 `$DSH_HOME/keyinject.patch.yml`** —— 那正是下一节要讲的
`overridden by a home patch` 事故。`KEYINJECT_PATCH` 常量现在**只用于删除**老残留。

#### 三个 hook 的分工

| hook | 做什么 |
|---|---|
| `SSL_set_alpn_protos` | 把 ALPN 列表**扣住**，等知道域名之后再设 |
| `SSL_ctrl`（SET_SNI） | 拿到域名 → 判断是不是目标域名，是才降级 `http/1.1` |
| `SSL_write` | **每个请求随机抽一把**钥匙；**国内站的钥匙顺带改写 Host 头**（两处都等长）→ 写完**立刻还原** |

#### combo：两个站点，一个供应商

**需求**：模型列表里**只显示一个「Agnes」**，但 14 把钥匙分属两个站点，而两站
钥匙**不通用**（实测互打 401，文档也写了不可混用）。

一开始的实现是**两个 provider 路由**（`agnes` + `agnes-intl`），各指自己的
`baseURL`。这样能用满 14 把钥匙，但代价是**模型选择器里出现两个 Agnes 分组**
（「Agnes 国内站」「Agnes 国际站」），每个都列 11 个模型 —— 列表又长又乱。

**现在的做法**：引擎侧只配**一个** provider，两站由 `keyinject.c` 在 TLS 层
当小路由 —— **选中哪把钥匙，就把 Host 头改成那把钥匙所属站点的域名**。

成立依据是三个实测事实：

| 事实 | 实测值 |
|---|---|
| 两个域名解析到**同一个 IP** | `api.agnes-ai.cn` 与 `apihub.agnes-ai.cn` 都是 `119.0.68.30` |
| 共用**同一张通配证书** | 都是 `CN=*.agnes-ai.cn`，SAN `*.agnes-ai.cn, agnes-ai.cn`，指纹一致 |
| 服务器按 **Host 头**路由，**不是** SNI | 见下表 |

路由依据的实测（同 IP 同证书，只换 SNI / Host，用「返回 11 还是 12 个模型」
区分站点 —— 国际站多一个 `agnes-image-2.0-flash`）：

| 请求 | 结果 | 实际站点 |
|---|---|---|
| INTL key + `Host: apihub.agnes-ai.cn` | 200 | 国际站 |
| INTL key + `SNI=apihub` + `Host: api.agnes-ai.cn` | 401 | — |
| **INTL key + `SNI=api` + `Host: apihub.agnes-ai.cn`** | **200** | **国际站** |
| **CN key + `SNI=apihub` + `Host: api.agnes-ai.cn`** | **200** | **国内站** |

⚠️ 后两行是**决定性证据**：SNI 与站点无关，**Host 头才是路由依据**。所以同一条
TLS 连接上换 Host 就能换站点（实测同一连接先 CN 后 INTL，两条都 200）。

⚠️ **Host 改写必须等长**：`apihub.agnes-ai.cn`（18 字符）比 `api.agnes-ai.cn`
（15 字符）长，所以后者用 **3 个尾部空格**补齐到 18。HTTP 头值的尾部空格由服务器
剥离（实测 `Host: api.agnes-ai.cn   ` 正确路由到国内站）。

⚠️ **因此 `baseURL` 必须用较长的国际站域名** —— 等长改写只能从长的往短的改，
反过来没有等长写法。这条决定了 `KEY_BASE_URL` 的取值，别随手改成国内站。

⚠️ **Host 改写只在头部区域**（首个 `\r\n\r\n` 之前）进行，避免误改 body 里恰好
出现的同一串域名（用户消息里粘了域名时，改 body 就是篡改用户内容）。

#### ⚠️ 14 把钥匙一个池子，两站交错，每个请求随机抽一把

池子按「国际站、国内站」**交错**排列：

```
intl.1, cn.1, intl.2, cn.2, … intl.7, cn.7
```

`keyref_at(slot, &site, &idx)` 就是这张映射表（偶数位国际、奇数位国内），
用 `% count` 索引，所以两站数量不等也能跑（少的那个被循环复用）。

**抽取策略是「每个请求独立随机抽」**（`arc4random_uniform(POOL_COUNT)`），
不是轮转、也不是失败退避。理由：

- 两站配额**同步消耗**，单把钥匙的速率摊薄到 1/14，429 概率显著下降；
- **没有「当前钥匙」这个状态**，也就没有需要推进的游标 —— 一个请求一次抽取，
  与上一个请求用了什么无关。

⚠️ **为什么用 `arc4random_uniform` 而不是 `rand()`**：`rand()` 需要一个可靠的
播种点，而本库是 `LD_PRELOAD` 进去的，没有合适的初始化时机（在构造函数里播种
还会干扰宿主自己的随机数使用）。bionic 的 `arc4random` 自行维护熵池且线程安全，
不需要播种。

⚠️ **解出来的明文钥匙必须放在栈上，不能用全局缓存**（踩过）：`SSL_write` 会被
node 的连接池**跨线程调用**，而随机化之后两个并发请求抽到的站点很可能不同。
用全局缓存就会出现「A 线程刚解出国际站钥匙、B 线程立刻覆盖成国内站的」——
于是 A 拿着国际站密钥去打国内站域名，**症状是稳定 401**，且只在并发时出现。
栈上 52 字节，代价可忽略。

⚠️ 早先还踩过更早的一版：按「表内下标」缓存，于是 `CN,idx=0` 与 `INTL,idx=0`
被当成同一把，症状同样是稳定 401。

**401 无法「就地重试」**：响应已经发完，字节改不了。引擎自己的 `retryPolicy`
会重发失败请求，重发时自然重新抽一把。

⚠️ 排查「抽到哪把」只能靠 `DSH_KEYINJECT_DEBUG=1`（打 `use CN idx=0` 到 stderr，
用 `adb logcat` 看）。**曾经**为此做过一套写文件的「路由日志」+ 设置页分节，
调试完之后**已经整套删掉** —— 不要再加回来。删掉的东西包括：本库里的
`route_log` / `DSH_KEYINJECT_LOG` / `SSL_read` hook、`EngineManager` 的
`readKeyInjectLog` / `clearKeyInjectLog`、`MainActivity` 的两个桥接口、
`client.js` 的 `dsh-android-route-log` 分节。

#### ⚠️ 必须降级 HTTP/1.1 —— 这是本方案最反直觉的一点

**真机实测**：引擎的 undici 默认协商 **HTTP/2**（`SSL_write` 里看到的是
`PRI * HTTP/2.0`），而 HTTP/2 的头部是 **HPACK 压缩**的 —— 看到的是一堆二进制，
**没有明文 `Authorization` 头**，等长替换无从谈起。

强制 ALPN 只留 `http/1.1` 之后，`SSL_write` 里就是标准明文 HTTP/1.1 报文，
`Authorization: Bearer sk-ANON…` 清晰可见，替换才成立。

⚠️ **降级只对目标域名生效，这是设计上最要紧的一条。** 全局降级会让引擎**所有**
HTTPS 请求失去 HTTP/2（多路复用、头部压缩），是实打实的性能损失。实测调用顺序是
`SSL_set_alpn_protos` **在前**、`SSL_ctrl(SET_SNI)` **在后**，所以本库先扣住
ALPN 列表、等 SNI 到达再决定是否降级。

⚠️ **`SSL_set_tlsext_host_name()` 是宏**，展开成 `SSL_ctrl()`，动态符号表里只有
后者 —— hook 前者**永远不会被调用**（踩过，白白多花一轮真机验证）。域名必须从
`SSL_ctrl(cmd=55 /* SSL_CTRL_SET_TLSEXT_HOSTNAME */, larg=0)` 的 `parg` 拿。

#### ⚠️ 等长替换：唯一不可妥协的前提

14 个密钥实测**全部恰好 51 字符**，占位符 `sk-ANON` + 44 个 `0` 也是 51 字符。
所以替换是**等长原地覆盖**：`Content-Length` 不变、缓冲区布局不变。

- 占位符长度写死在两处：C 侧的 `KI_PLACEHOLDER` 与 Kotlin 侧的 `KEY_PLACEHOLDER`，
  **必须逐字一致**。`build-keyinject.ps1` 会读 Kotlin 文件校验两者长度相等，
  不一致时**直接构建失败**（不静默放过）。
- `keyinject.c` 里有 `_Static_assert(sizeof KI_PLACEHOLDER - 1 == KI_KEY_LEN, ...)`
  兜底。C 侧的 `KI_KEY_LEN` 由生成的头文件给出，所以换密钥池时长度一变就编译不过。
- **换密钥池时长度必须保持一致**，否则请求会因长度错乱失败。

⚠️ **写完必须还原**：`SSL_write` 的缓冲区属于**调用方**（node 的 Buffer，可能被
复用），留下真密钥会污染它。所以替换前先存下原 51 字节，`real_SSL_write()` 返回后
立刻抹回占位符。

#### 密钥怎么存（⚠️ 是混淆，不是加密）

`build-keyinject.ps1` 对每个 key 逐字节 XOR 一个随机 mask，再叠一层位置相关的
滚动常量（`i * 7 + 0x5A`）。所以 `strings keyinject.bin` 搜不到 `sk-` 开头的明文。

⚠️ **这是混淆，不是加密。** mask 与滚动常量都在二进制里，有耐心的逆向者可以还原。
**别在任何地方说成「密钥被加密保护了」**。

⚠️ **门槛没有「会反汇编」那么高 —— 实测比这低得多，别把防线说强了。**
真正的弱点不在反汇编，而在**全表共用一把 mask**（见上面：`K[i]` 只与列号有关、
与行号无关）。攻击者**不读源码、不读头文件、不读 mask**，只要知道
「密钥是 51 字符、`sk-` 开头、以步长 51 的二维表存放」，就能逐列枚举
`K[i] ∈ [0,256)`、要求该列所有行解密后都落在 base62 内 —— 行数一多候选就收敛到 1。
实测（2026-10-13，`keyinject.bin` 16,688 B / 132 行）：

```
零知识定位：扫「步长 51 且前 3 列可解」的偏移 → 113 处候选，最强 base=2084 撑满 132 行
逐列枚举  ：51/51 列唯一确定，候选空间 = 1
终局比对  ：盲解 ∩ agnes-keys.properties = 132/132 条**逐字节完全相同**，独有条数 0
```

也就是说**全程没看 `.c`、没看 `-keys.h`、没看 `KI_MASK`**，一把全出。
这是本项目已知的、**刻意接受**的取舍（真正的解只有服务端中转，见下），
但**别把它的强度说高**：准确的表述是「挡得住 `strings` 级的顺手一搜，
挡不住愿意写二十行脚本的人」。`keyinject.c` 的注释与本节口径要一致。

⚠️ 每次构建都换一把新 mask，所以**不可复现构建** —— 这是有意的（从某版本还原出
明文也无法用同一套常量解开下一版），代价对本项目无影响（头文件与 `.bin` 都不入库）。

⚠️ 加密端（PowerShell 的 `Get-Scramble`）与解密端（C 的 `derive_key`）**必须逐字节
一致**，改一边必须同步改另一边。脚本里有往返自检，不一致会直接报错。

#### ⚠️⚠️ 内置号池**绝不能**写成 `--patch` overlay —— 那会让用户加不了第三方 API

**这是本方案踩过的最严重的一个坑**，症状是「更新完内置模型之后，用户一添加第三方
API 就报错」，报错原文：

```
Configuration for "llm-pi-ai" is overridden by a home patch or command-line overlay
```

**根因**（`@deepseek-ai/dsh-config-editor/lib/index.js:122`）：GUI 的「创建提供商」
走 `settings.mutate("llm-pi-ai", …)` → `ConfigEditor.edit()`，它在写盘前有一道
**保护性校验** —— 把刚写下的值经**完整层序（含 `--patch` overlay）**重新
`composeEntries()` 一遍，必须与写入值**深相等**，否则抛上面那句。

而 patch 的语义是**整块替换该行的 `config`**（不是深合并，见 `dsh-base/cordis.patch.yml`
头部注释），overlay 又排在**最顶层**。所以 `- id: llm-pi-ai` 的 overlay 一旦存在：

| 层 | `llm-pi-ai` 的 `providers` |
|---|---|
| bundle | （空） |
| **用户层**（GUI 写入 `agnes` + 用户新建的 `a`） | `agnes`, `a` |
| **`--patch` overlay**（内置号池） | **`agnes`** ← 只剩这个 |

compose 结果是 `{agnes}`，与写入的 `{agnes, a}` 不相等 ⇒ 校验失败 ⇒ **报错**。
用户的 provider 一个都存不进去。

**修法**：内置号池并入 **profile 的用户配置层**
（`$DSH_HOME/profiles/web/cordis.patch.yml`）—— 那正是 GUI 自己读写的那份文件，
格式与语义完全一致（同 [seedDefaultSettings] 的理由）。`EngineManager.ensureKeyPoolProvider()`
只做**补缺**：已有 `agnes:` 时一个字节都不动，所以既能保证号池始终在，又不会覆盖
用户自己加的 provider。

⚠️ **老安装残留的 `keyinject.patch.yml` 必须无条件删除**（与号池资产在不在无关）：
它本身就是那个 bug，留着它报错就还在。

⚠️ **也不能给号池另开一行**（比如 `- insert: [{id: agnes-pool, name: '@deepseek-ai/dsh-llm-pi-ai'}]`）：
`dsh-llm-pi-ai` 的 `apply()` 会**无条件**把 pi-ai 内置目录的 **41 个 provider**
全部 `registerConfigurableProviders()`。挂第二个实例时第二批声明撞上第一批已占用的
路由，抛 `DUPLICATE_DIRECTORY`（`configurable provider "anthropic" is already declared`）
⇒ 整个目录注册失败、**模型列表空掉**。实测 `getBuiltinProviders()` 返回 41 项。

⚠️ 改这一处时注意两个 YAML 细节（都踩过）：
- 空模板是 `[]`，往里追加 entry 前**必须先摘掉它**，否则 `[]` 后面跟块序列是**非法 YAML**；
- 行已存在但没写 `config:` 时，只补 `config` 子树，**不能再输出一遍 `name:`**（重复键非法）。

**验收**（离线，用引擎自己的 `composeEntries` + `dsh-config-editor` 的判据）：
四种起点（空模板 `[]` / 已有用户 provider / 已播种出厂默认项 / 行存在但无 config）
补完后，模拟 GUI 新建 provider 再 compose，**必须深相等**；幂等（跑两次逐字节相同）；
已有 `agnes:` 时**一个字节都不动**。

#### 验收

**离线实测（PC 上直接打两个站点，绕过引擎，验的是 combo 的分流原理）**：

| 项 | 结果 |
|---|---|
| 两域名 IP | 都是 `119.0.68.30` |
| 两域名证书 | 都是 `CN=*.agnes-ai.cn`，指纹一致 |
| 路由依据 | **Host 头**（`SNI=api` + `Host: apihub` 照样进国际站） |
| 同连接换 Host | 可行（先 CN 后 INTL，两条都 200） |
| 14 把钥匙各自站点 | **14/14 全 200** |
| `Host: api.agnes-ai.cn   `（3 空格补齐） | 200，且**路由到国内站**（用「有没有 `agnes-image-2.0-flash`」判定） |
| 真实 `chat/completions` | 走 Host 改写后 **200** |
| 二进制里搜明文密钥 | **0 命中**（`sk-` 只出现 1 次，就是占位符） |

**真机实测（安装 debug 包后看日志）**：

| 项 | 结果 |
|---|---|
| `已解出号池注入库 libkeyinject.so (12576 字节)` | 注入库已落盘 |
| `已把内置号池并入 profile 用户配置: .../profiles/web/cordis.patch.yml` | 已并入用户层（**不是** overlay） |
| `已删除旧的内置号池 overlay: keyinject.patch.yml` | 老安装的残留 overlay 已清掉（只出现一次） |
| 用户层里的内置号池内容 | **单** provider `agnes`、`displayName: 'Agnes'`、baseURL 是 apihub、**只有 `agnes-3.0-flash` 一个模型** |
| 引擎启动 | `引擎就绪` + `WebView 加载完成` |
| `已安装移动端 UI 插件 dsh-android-mobile <版本>` | 移动端插件已解包 |

⚠️ **下面这些要人工确认**（GUI 观感与真实对话，离线探针测不到）：

1. 主界面模型选择器里**只有一个「Agnes」分组**，下面**只有 `agnes-3.0-flash`**。
2. 选它发一条消息，能正常回复（证明 combo 在真机上把钥匙与 Host 配对了）。
3. 设置 → 高级 里**不应再有「路由日志」分节**（那套东西已随调试一起删掉）。

体积代价：`keyinject.bin` 12,576 字节 —— 对 132.8 MB 的发行包约 0.01%，可忽略。
真正的代价是维护面（一个原生组件、一条 `LD_PRELOAD`、一个启动顺序依赖，
以及 combo 引入的「Host 改写必须等长」这条隐性约束）。

#### ⚠️ 挡住了什么、挡不住什么（不要夸大）

**挡住**：
- AI 工具读文件 / 环境变量 / `/proc/<pid>/environ` —— 那些地方只有占位符（实测）
- `strings` APK / 十六进制搜索 —— 二进制里没有明文 key（实测）
- 装用户 CA 抓包 —— 抓到的是密文

**挡不住**：
- 有耐心的逆向者（反汇编本库、复现 XOR 与滚动算法）—— 是门槛，不是墙
- 拿到 root 后用 Frida / ptrace 读进程内存 —— 但 release 包
  `android:debuggable=false`，Frida 需要 root
- hook 了 `SSL_write` 的抓包工具 —— 与本库同一层，看得到
- ⚠️ **`android:usesCleartextTraffic="true"` 且没有 `networkSecurityConfig`**，
  所以用户装的 CA 能解密 HTTPS。本库不防这个。

⚠️ `/proc/<pid>/mem` 真机实测**被内核挡住**（`head: I/O error`），
`/proc/sys/kernel/yama/ptrace_scope` 在本设备上不存在 —— 但这两条**都不构成防护**，
ptrace/Frida 不受此限。

⚠️ **调试开关**：`DSH_KEYINJECT_OFF=1` 关掉注入（用来区分「是本库引起的」还是
「本来就这样」）；`DSH_KEYINJECT_DEBUG=1` 打开日志。两者都不参与保密 ——
关掉后请求会带占位符发出去并被判 401，是个可观测的失败。

#### ⚠️ 安全提醒（与代码无关，但比代码更要紧）

密钥文档挂在 **`http://39.98.163.161:57647/`**（**明文 HTTP**，无 TLS）。
若该主机对公网可达，那才是最大的暴露面 —— **比 APK 泄露严重得多**，
因为它是 14 个密钥的完整清单、一次请求全部拿走。发版前建议确认它只在内网，
或尽快改成 HTTPS / 加访问控制。

### ⑯ 旧 WebView 内核白屏 —— 前端 bundle 降级编译（**不是** polyfill）

> ⚠️ **降级范围是「三棵树」，不是只有 dist**（v2 起，`-CompatVersion 2`）。
> 只降 dist 是**不够的**，这是线上真实踩到的坑，见本节末尾的「v2：插件 bundle」。

**症状**（用户反馈的 45 秒录屏，vivo Y52s / V2057A / Android 10 / Funtouch OS 10.5）：
启动层走完「正在启动引擎…」→「正在准备界面…」，然后**一片纯白**，永远不出来。

**这一处的要害：白屏与「缺 API」是两回事。** 必须先分清，否则会一直在补 API：

| | 缺**运行时 API** | 缺**语法** |
|---|---|---|
| 例子 | `Object.hasOwn`、`.at()`、`Promise.withResolvers` | `static {}`、`??=`、`#m()` |
| 失败时机 | 执行到那一行才抛 TypeError | **解析整份 chunk 时就失败** |
| 后果 | 前面的代码已经跑过 | **整份 chunk 一行都不执行** |
| 能否用 polyfill 补 | **能** | **不能**（垫片自己也在那份 chunk 里/依赖它） |

官方前端 bundle 用的是新语法，所以命中右边一列 —— `webview-polyfill.js` 那种
运行时垫片**完全无能为力**。这就是为什么 0.1.4 只加日志、界面照样白。

**真机定位过程（可复现）**：

1. 逐帧量状态栏像素：全程停在主题色 `#0F1115`（RGB 15,16,20），内容区 `255,255,255`。
   状态栏颜色由页面的 `onStatus` 上报驱动 ⇒ **页面 JS 一行都没执行**。
   而 HTML/CSS 明显已加载（白底来自官方前端的 CSS）⇒ 排除网络与资源 404。
2. 读内核版本：`WebView.getCurrentWebViewPackage()` → **83**。
3. 扫 bundle 找 83 解析不了的语法，得到下面这张表。

**实测缺口（engine.bin 里的官方 bundle）**：

| 语法 | 最低内核 | index | vendor |
|---|---|---|---|
| `class static block`（`static {}`） | **94** | 2 | — |
| 逻辑赋值 `\|\|=` `&&=` `??=` | **85** | 25 | 7 |
| class 私有方法（`#m(){}`） | **84** | — | 1 |
| 可选链 `?.` | 80 | 231 | 96 |
| 空值合并 `??` | 80 | 156 | 29 |

内核 83 同时踩中 94 / 85 / 84 三道 ⇒ 与白屏完全吻合。

**做法**：`tools/build-webview-compat.ps1` 用 esbuild 把 dist 降到
`--target=chrome74`，只把**真有语法缺口**的文件打成 `assets/webview-compat.zip`
（实测只有 **3 个**：`index-5SrrfWpU.js`、`vendor-CCJJTK99.js`、`index-BPHePDI_.css`，
**438 KB**）。App 侧 `EngineManager.ensureWebViewCompat()` 解到 dist 覆盖。

⚠️ **判据不能是「输入 hash ≠ 输出 hash」**：esbuild 即使不降级也会重新格式化，
那样 61 个文件全被判为「变了」，资产从 0.44 MB 涨到 0.84 MB。
判据取「同一输入，`--target=esnext` 与 `--target=$Target` 的输出是否相同」——
输出相同 ⇔ 该文件没有缺口。

⚠️ **判据也不能是正则扫 `??=`**：正则字面量里就有这些字符。实测
`langs/make-CHLpvVh8.js` 里那处 `??=` 位于 `([+:?]??=)?` 之内，是正则文本不是运算符。
交给 esbuild 自己判，它认语法不认字符串。

**验收用的是独立解析器，不是 esbuild 自己**（用 esbuild 验 esbuild 是循环论证）：
`tools/check-frontend-syntax.mjs` 用 acorn 建 AST，**遍历节点**按「特性 → 最低 Chrome 版本」
表判定。

⚠️ **不能直接看 `ecmaVersion`**：`acorn.parse({ecmaVersion:2019})` 会拒绝 class 私有字段
（标准里属 ES2022），但 **Chrome 74 早就实现了它**。
**ECMAScript 版本号 ≠ Chrome 版本号** —— 实测降级产物在 ES2019 下报「需要 ES2022」，
真因只是私有字段，会把人误导回「降级失败」。
同理，`top-level await` 的标注必须**遇到函数边界就停**（函数体内的 await 是 async 的一部分，
从 ES2017 就合法），否则误报 44 处；`numeric separator` 只看数值字面量，
字符串的 `raw` 含引号内容，`"a1_2"` 会被误判。

**对照结果**（这才是证据）：

```
原始 index-5SrrfWpU.js  → FAIL: static block ×2(94)、||= ×25(85)、?? ×156、?. ×231
原始 vendor-CCJJTK99.js → FAIL: #m() ×1(84)、||= ×7(85)、?? ×29、?. ×96
降级后两个文件          → OK: 无任何 chrome74 以上语法
webview-polyfill.js 自身 → OK
检测器自检（--target 94）→ 原始 bundle 放行（证明不是无脑报错）
```

**门槛不再写死 94**：`MainActivity.effectiveMinWebViewMajor` 按
`EngineManager.webViewCompatApplied` 动态取值 —— 降级生效 → 74，没生效 → 退回 94。
⚠️ 判定放在 `observeEngine` 的 `Ready` 分支而不是 `detectWebViewVersion()`：
后者在 `onCreate` 跑，那时引擎还没启动、降级还没落盘，会把门槛误报成 94。

**polyfill 同步补齐降级后仍缺的 API**（esbuild 只降语法、不管 API）：
`.at()`（92，index ×14 + vendor ×12）、`Object.hasOwn`（93，×7）、
`replaceAll`（85，×5）、`reportError`（95，×2）、`AggregateError`（85，×1）、
`WeakRef`（84，×1）。

⚠️ **`WeakRef` 只能近似**：真弱引用语义在旧内核上无法用 JS 复刻，这里用强引用顶替
（`.deref()` 恒返回原对象），代价是该对象不被回收。比 `new WeakRef()` 直接抛
ReferenceError 让整个 chunk 挂掉好。
⚠️ **`replaceAll` 的字符串分支不能写成 `split/join`** —— 那样 `$&`、`$$` 就不按标准展开。
转义成字面量正则后交给原生 `replace`（core-js 同做法）。
⚠️ **polyfill 自己是普通 `<script>`，不经过 esbuild**，所以它自身的语法也必须能被 74 解析
（实测 OK：没用 `?.` / `??`，只用 `async` / `for await`）。

**CSS 只降掉一半，这是已知边界**：esbuild 与 lightningcss 都只把 `inset:` 从 18 处降下来，
`:is()` 88 / `:where()` 88 / `:has()` 105 / `color-mix()` 111 / `dvh` 108 / flex `gap` 84
**一处没动**。这些在 chrome74 上是**未知语法 ⇒ 整条规则被丢弃**（不是解析失败），
所以**不会白屏，但布局/配色会错乱**（实测 33 条规则受影响，其中 9 条涉及布局，
`_markdown_*` 排版占多数）。lightningcss 也解决不了，**不要再引入它**（已试过并删除）。
真机复测时若发现「能打开但界面乱」，根因在这里。

**验收日志**（真机实测，CDY-TN90 上跑 0.1.5 debug）：

```
WebView 内核: com.huawei.webview 114.0.5.302（官方 bundle 门槛 94，降级后 74）
已应用 WebView 降级前端: 3 个文件（chrome74-1）        ← 首次
WebView 降级前端已就位，跳过（chrome74-1）             ← 之后每次
```

⚠️ 幂等戳写在 **dist 里**（`.webview-compat-stamp`）而不是别处：引擎升级会重新解包 dist，
戳跟着一起没 ⇒ 自动重打，正是想要的。实测删掉戳后下次启动会重新应用。
⚠️ 文件名带内容 hash（`index-5SrrfWpU.js`），**换 `engine.bin` 后必然对不上，必须重跑脚本**。
⚠️ 降级资产**打不上时不能放行**：门槛会退回 94，宁可提示用户升级内核，也不要放进一片白屏。

#### v2：只降 `dist` 是不够的 —— 插件 bundle 也要降（**线上真实踩到**）

**症状**（粉丝反馈，小米 13 / MIUI 14，内核版本未知但 < 110）：
composer 里点模型选择器弹红字 `groups.toSorted is not a function`，点「重试」无效；
设置页的「模型」标签页**整页空白**。

**根因**：`dsh-client-ui-model-selection/lib/client.js:475` 的
`orderModelProviders(groups)` 直接调 `groups.toSorted(...)`（**Chrome 110+**），
`dsh-client-ui-settings-models/lib/client.js:929` 是 `rows.toSorted(...)`（同一个坑）。
`toSorted` 是**运行时 API** 而不是语法，所以 esbuild 降级**碰不到它**
（`--target` 只管语法）—— 必须由 polyfill 补。而 v1 的 polyfill 清单是
**只扫 `dsh-web-frontend/dist` 数出来的**，`toSorted` 那一族在 dist 里**一次都没出现**，
于是整族被漏掉。

**要害：前端是分两部分下发的，各自独立编译。** 只降 dist ≈ 覆盖率不到 5%：

| 部分 | 位置 | v1 覆盖 |
|---|---|---|
| 官方 shell | `node_modules/@deepseek-ai/dsh-web-frontend/dist/` | ✅ |
| **71 个插件**的客户端 bundle | `node_modules/@deepseek-ai/*/lib/client.js` | ❌ |
| App 自己注入的移动端 UI 插件 | `$DSH_HOME/mobile-ui/dsh-android-mobile/` | ❌ |

实测在 `--target=chrome74` 下扫描：**dist 61 个文件里 2 个**有缺口，
而**插件 85 个 `client*.js` 里 68 个**有缺口（`dsh-api-remotes` 一个包就有 ×340 逻辑赋值）。

⚠️ **插件缺语法时的症状比白屏更隐蔽**：dist 挂掉是整页白屏（一眼可见），
而插件挂掉是**「这一个插件静默不生效」或「这一块 UI 永远空白」** ——
`window.__ModuleLoader__.load()` 根本没执行，宿主与页面都收不到完整报错。
本次的 `toSorted` 只是其中一类（API 类），语法类症状与它同构。

**做法（v2）**：脚本的降级范围扩到三棵，zip 条目**带树前缀**，App 侧按前缀分派：

| zip 条目前缀 | 解到哪棵树 |
|---|---|
| `dist/` | `engine/node_modules/@deepseek-ai/dsh-web-frontend/dist/` |
| `engine/` | `engine/node_modules/@deepseek-ai/` |
| `mobileui/` | `$DSH_HOME/mobile-ui/dsh-android-mobile/` |

实测产物：**72 个文件**（dist 3 + engine 68 + mobileui 1），**9.95 MB**。

⚠️ **`-CompatVersion` 必须 bump 到 2**：v1 的戳（`chrome74-1`）已经落在老设备的
dist 里，不 bump 会命中「戳一致 → 跳过」，于是**新纳入的插件部分永远不生效** ——
静默，且症状与没修一模一样。现在戳是 `chrome74-v2`（`$Target-v$CompatVersion`）。

⚠️ **mobile-ui 那棵树必须晚于 `ensureMobileUi()`**：那一支在版本戳不一致时会
`target.deleteRecursively()` 整个重解包 mobile-ui 目录，**会把降级产物连同它的戳一起抹掉**。
所以 `start()` 里的顺序是 `ensureMobileUi()` → `ensureWebViewCompat()`
（dist / engine 两棵树与顺序无关）。

⚠️ **两棵树各判各的戳**：只按 dist 的戳判定「已就位」整体跳过的话，上面那次重解包
之后的 mobile-ui 就是**没降级的原版**，老内核上静默退回桌面布局。

⚠️ **写 `client*.js` 到磁盘对运行时真的生效**（已核实，不是推测）：
`dsh-client-modules` 的 `/plugins` 路由是 `readFileSync(record.meta.clientPath)`
（`lib/index.js` 的 613/815/946 行），**每次请求都读盘**；
浏览器缓存也不会成为障碍 —— 缓存键里的 `rev` 来自
`artifactRevision(baseline) = framedHash("plugin-artifact", [mtimeMs, ctimeMs, size])`，
覆盖文件后 `rev` 自动变化。另外 `initialBundleSnapshot()` 在**激活时**读，
所以必须在**引擎 spawn 之前**覆盖好 —— 当前调用顺序满足。

⚠️ **验收要独立解析器，不能用 esbuild 自己验自己**：脚本第 3.5 步对**每个降级 js 产物**
调 `tools/check-frontend-syntax.mjs` 复检。实测 71 个 js 全部通过。

⚠️ **复检的两个坑（都踩过）**：
- **不能把 `.css` 喂给 acorn** —— 它必然报 `Unexpected character '@'`，
  那是解析器不适用而不是 CSS 有问题。只复检 `*.js`。
- **必须区分退出码**：`1` = 真有语法缺口，`2` = 校验工具自己跑不起来
  （本仓库没有 `package.json`，`acorn` 常缺 —— 脚本会自己装一份到临时目录）。
  早先只判 `-ne 0`，于是 `Cannot find module 'acorn'` 被当成「72 个产物全部降级失败」，
  真因一个都没有。

**polyfill 同步补齐 v2 新暴露的缺口**（都在用户主路径上）：

| API | 最低内核 | 调用方 |
|---|---|---|
| `structuredClone` | **98** | `-ui-settings`（`mutate()`）、`-ui-settings-models`（读模型配置）、`client.pdf.js` ×4 |
| `Intl.Segmenter` | **87** | `-ui-chat`（**模块顶层**构造！）、`client.pdf.js` ×2 |

⚠️ **`Intl.Segmenter` 比 `structuredClone` 更急**：`-ui-chat` 在**模块顶层**
`const LIVE_TOOL_DETAIL_SEGMENTER = new Intl.Segmenter(...)`，缺了会在**加载插件那一刻**
就抛 ⇒ **整个 chat 插件的 client bundle 注册失败**。而 chat 是主界面。
pdf.js 用到 `segments.containing(index)`，必须实现这个方法。
⚠️ 它的兜底切分粒度不如原生（UAX #29 完整实现不现实），但调用场景是
「给 UI 详情做 160 字符截断」和 pdf 里的词边界判断，**截断点略偏不会造成错误结果**。
⚠️ 该段的正则**必须用 `new RegExp` 运行时构造、不能写成字面量**：
`\p{Extended_Pictographic}` 若不被某老内核认识，抛的是 **SyntaxError**，
那会让**整个 polyfill 文件**解析失败、连带上面所有补丁一起失效。
用 try/catch 包住并退化成「按码点切」。

## 后台保活（任务切到后台不能断）

引擎跑在本进程里，所以「App 切后台任务还能继续」= 让本进程既**不被回收**又**不被冻结**。
两层缺一不可：

| 层 | 手段 | 解决什么 |
|---|---|---|
| 系统回收 | `EngineService` 前台服务 + 常驻通知 | oom_adj 钉在前台档，内存压力下不被杀 |
| CPU 休眠 | `PARTIAL_WAKE_LOCK`（`dsh:engine`） | 锁屏后 CPU 不进 suspend，node 事件循环/定时器/网络回调继续跑 |
| ROM 冻结 | 电池优化白名单 | 华为「应用启动管理」/ OPPO「耗电保护」息屏几分钟就冻进程 |

### 通知栏实时状态（WebView → 原生）

常驻通知不只写「引擎正在运行」，而是**实时显示 AI 当前在干什么**：标题是当前动作
（「正在运行命令 · sleep 10」），正文是最后一条助手输出（运行中含正在流式的思考）。

链路：插件读 DOM → `window.DSHAndroid.onStatus(json)`（`addJavascriptInterface`）
→ `MainActivity.StatusBridge` → `EngineService.updateStatus()` → 用**同一个通知 ID**
`notify()` 更新（不重启服务）。

| 字段 | 来源（官方 DOM 契约，0.2.0-rc.2 实测） |
|---|---|
| `running` | `.uV2eYG_primary` 的 `aria-label` 变成「停止生成」。官方 `primaryStops` 为真时换 `t("input.stop")`（`dsh-client-ui-conversation/lib/client.js:17407`） |
| `text` | 最后一个 `.O_Ebla_title` 的文本。官方 `ProcessGroupHeader` 未关闭时用 `message.stepProcess.<activity>`（「正在运行命令」），关闭后用 `processTitle()`（「执行了命令」），都可能接 `" · "` + `liveToolDetail` 从工具参数提出来的详情 |
| `body` | 最后一个 `[data-chat-flow-kind="assistant-step"] .hWmORq_root` |
| `title` | `document.title` 去掉 `— DeepSeek Harness` 后缀，作通知副标题 |

⚠️ **正文选择器必须限定 `data-chat-flow-kind="assistant-step"`。** `.hWmORq_root`
是 `AssistantMarkdown` 的类，但同名容器**也会包住用户自己发的话**（真机实测用户消息是
`data-chat-flow-kind="user"`，内含 `Sixlwa_*` 气泡）。不限定就会把用户的话当成 AI 的进展。

⚠️ **通知卡片高度靠截断控制，不靠布局。** `BigTextStyle` 的展开态按正文**实际行数**
撑高，而助手输出动辄几百字，不截断会吃掉大半个通知栏（用户实测反馈）。三段各有字符上限
（`TITLE_MAX_CHARS` / `SUBTITLE_MAX_CHARS` / `BODY_MAX_CHARS`），全部按 **code point**
切（别按字节，会把 emoji 截成半个）。改这几个常量就是改卡片高度。

⚠️ **正文用 `truncateTail`（保留尾部），标题用 `truncateHead`。** 助手消息是**流式追加**
的，最新进展在末尾；保留头部会把通知栏钉死在几分钟前那句话上，反而看不出"现在在干什么"。

⚠️ **必须 `setOnlyAlertOnce(true)` + `setSilent(true)`。** 状态每秒都可能更新，
不加这两条每次刷新都会重新提醒一次（响铃/亮屏）。

⚠️ **`alive` 标志不能省。** 服务没起来时 `notify()` 会**凭空造出一条普通通知**
（不是前台服务的那条），用户会看到两条。`updateStatus()` 先查 `alive` 再发。

⚠️ 上报节流 1s 且**内容不变则一个字节都不发**（`mpLastStatusJson` 比对）——
流式输出时 DOM 变化极频繁，不节流会让通知栏闪烁并白耗电。

**验收手法**（数值证据，别靠截图猜）：

```powershell
# 通知当前内容（真机上最快的一招）
adb shell dumpsys notification --noredact | Select-String "android.title|android.text|android.subText"
#   → android.title=String (● 正在分析请求)
#     android.subText=String (会话标题)
#     android.text=String (…最后 100 字符，以省略号开头)

# 桥有没有挂上、页面侧判据对不对
adb shell pidof com.dsh.android     # 拿 pid，再用它建 CDP 转发
adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>
# 期望：hasBridge=true，primaryLabel 与通知里的 ●/○ 一致
```

#### 顺带解决：键盘升起时下半屏先闪一块深色

**症状**：点输入框，键盘还没完全升起时，中间有几帧屏幕下半部分是纯黑的占位区域，
随后才被键盘填充。**不是加载慢**，是底色不一致。

**根因**：`adjustResize` 让窗口变矮是**逐帧动画**的，WebView 跟着变矮，但页面重排要等
下一帧 —— 这个空档里新露出的那条区域还没有内容覆盖，显示的是 **WebView 自己的背景色**。
原先三处写死 `#0F1115`（`MainActivity.kt` 的 `setBackgroundColor`、`activity_main.xml`
的 FrameLayout、`themes.xml` 的 `windowBackground`），而官方页面是白底，于是深色块闪出来。

**做法**：插件把 `document.body`（透明则退到 `html`）的计算色转成 `#RRGGBB`
（`mpPageBackground()`）搭车同一个状态 JSON 上报，原生侧 `applyPageBackground()`
在**主线程**把 WebView + 根容器 + 窗口背景一起改成该色。

⚠️ **不能写死白色**：官方主题可切换，写死只是把问题换到深色模式下重新暴露。
⚠️ **三处都要改**：DecorView 与 WebView 是两层独立合成，窗口缩小时各自的 Surface
更新不同步，只改一层仍可能露出一帧旧色。
⚠️ **`bg` 不参与通知去重**（`updateStatus()` 里 `copy(bg = "")` 再比）——
主题切换时通知内容一个字没变，不该白发一次通知。
⚠️ 只在底色**真变了**时才 `runOnUiThread`，否则每次状态刷新都白 post 一个任务。

#### 修复 65：键盘升起时输入框被盖住（原生预判 + 高度自学习）

**症状**：点输入框后键盘从屏幕底部升起，但输入框要等约 200ms 才跳上去，
中间几帧被键盘压住（用户实测反馈）。

**真机实测**（Huawei CDY-TN90 / Android 10，各 2~3 轮一致）：

| 测量项 | 结果 |
|---|---|
| focus → 窗口变矮 | **180~220ms** |
| 期间窗口高度变化 | `764 → 475`，**只有 1 个 resize 事件、无中间帧** |
| `visualViewport` vs `innerHeight` | **同一时刻**变化，没有更早的信号 |
| `navigator.virtualKeyboard.boundingRect` | 恒为 `0`（该内核不提供几何信息） |
| 主线程最长阻塞 | 76~85ms（不足以解释这 200ms） |

**根因**：这 200ms 内页面**拿不到任何"键盘现在多高"的信息**，只能干等窗口变矮。
逐帧 insets 回调要 API 30 的 `WindowInsetsAnimation`，本机（API 29）没有。
**这是平台行为，不是插件或主线程的问题。**

**做法**：**提前告诉原生"键盘要来了"**。页面 `focusin`（点击那一刻）走已有的
`DSHAndroid` 桥通知原生，原生按**上次实测的键盘高度**先把 WebView 变矮
（`bottomMargin`），真 insets 到达后归零、交还给 `adjustResize`。

```
页面 focusin ──onImeWillShow()──► MainActivity.applyImePrejudge()
                                     └─ setWebViewBottomMargin(缓存的键盘高度)
真 insets 到 ──onLayoutChangeListener──► 归零 margin + 保存这次实测高度
700ms 仍无 insets ──► restoreFromPrejudge("超时未见 insets")
```

⚠️ **要"学"的不是延迟，是高度。** 延迟是系统动画的固有节奏，我们"点击瞬间立即执行"
就天然覆盖了它，不用学；真正因机器/输入法而异的是**键盘有多高**（本机实测 867 物理 px）。
⚠️ **高度必须持久化**（`SharedPreferences` 名 `dsh_ime`）。只放内存的话每次冷启动
都会丢掉，症状变成"每次开机第一次点输入框都要闪一下"。
⚠️ **横竖屏分开存**（`ime_height_port` / `ime_height_land`）：横屏键盘更矮，
共用一份会让转屏后预判缩错高度。
⚠️ **判据不能用 `getWindowVisibleDisplayFrame()`**：`adjustResize` 下窗口本身已被缩小，
可见区域恒等于窗口高度，算出来永远是 0。改用「静止高度 − 当前根视图高度」
（`restHeightPx` 取历史最大值；键盘只会让内容区变矮、不会变高），
与插件侧已验证的 `restVh` 是同一套判据。
⚠️ **宽度变化（转屏）必须重置 `restHeightPx`**：否则拿竖屏的"历史最大高度"去减横屏的
当前高度，会得到一个巨大的"隐藏高度"被**误判成键盘可见**。
⚠️ **不要在 `onLayoutChangeListener` 里写「布局变化但键盘不可见就撤销预判」**：
预判自己改 `layoutParams` 会让根视图重新布局并触发该回调，那时键盘必然还不可见，
一撤销就等于预判从未生效。真正的"点了输入框但键盘没弹"由 700ms 超时兜底。

**已知代价**（如实记录，不是缺陷）：
- 冷启动后**第一次**点输入框仍会闪一下（还没有缓存值），闪完就学到了。
- 换输入法（键盘高度变了）会有一次轻微错位跳动，随后自愈。

**验收手法**（数值证据）：

```powershell
# 第一次点输入框（无缓存）→ 只应有"已学习并保存"
# 第二次起 → 应看到"键盘预判：提前缩矮 <N>px" 和"键盘预判结束（真 insets 已到 …）"
adb logcat -d -s DSH:I | Select-String "键盘预判|键盘高度已学习"
# 缓存落盘了没
adb shell run-as com.dsh.android cat shared_prefs/dsh_ime.xml
```

⚠️ **只做前台服务是不够的**：前台服务只保证进程活着，锁屏后 CPU 仍会 suspend，
长任务照样断。两件事必须一起做。

⚠️ **WakeLock 刻意不带超时**（`@SuppressLint("WakelockTimeout")`）。带超时的话到期会
**静默释放**，任务跑到一半 CPU 就被允许休眠了。生命周期由服务自己管：
`onCreate` 拿、`onDestroy` 放，服务在则锁在；进程被杀时内核自动释放，不会漏。
`setReferenceCounted(false)` 保证重复 `acquire()` 不会留下需要等次数的计数。

⚠️ **电池优化白名单是唯一由系统认可的免冻结标记**，且**必须用户手动确认**，
App 只能拉起系统对话框。`MainActivity.ensureBatteryExempt()` 在**尚未豁免**时才弹，
已豁免则静默返回，不反复打扰。国产 ROM 上不做这一步，前两层做得再对也会被冻住。

⚠️ `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 在 Google Play 上受政策限制，**本项目侧载分发，
不受该限制**。不要因为「上架合规」把它删掉。

排查手法（数值证据，别靠猜）：

```powershell
# 前台服务在不在
adb shell dumpsys activity services com.dsh.android | Select-String EngineService
# WakeLock 持没持有
adb shell dumpsys power | Select-String "dsh:engine"
#   → mLock:... PARTIAL_WAKE_LOCK 'dsh:engine' ACQ=... (uid=... pid=...)
# 进程优先级（0 = 前台档）
adb shell "cat /proc/<pid>/oom_score_adj"
# 电池优化白名单
adb shell dumpsys deviceidle whitelist | Select-String dsh
```

## 移动端 UI 插件

源码在 `mobile-ui-plugin/dsh-android-mobile/`，打包成 `assets/mobile-ui.zip`。
`EngineManager.ensureMobileUi()` 解包到 `$DSH_HOME/mobile-ui/dsh-android-mobile/`，
`writeMobileUiPatch()` 生成 overlay（`name` 用 `file://` 绝对 URL）并作为 `--patch`
传给引擎。

```powershell
# 改完源码重新打包（注意：条目必须是 lib/client.js 这种相对路径）
Compress-Archive -Path mobile-ui-plugin\dsh-android-mobile\* `
                 -DestinationPath app\src\main\assets\mobile-ui.zip -Force
# 然后改 EngineManager.MOBILE_UI_VERSION
```

⚠️ `ensureMobileUi()` 按 `entry.name` 直接解到目标目录。若压缩时把顶层目录也带进去
（变成 `dsh-android-mobile/lib/client.js`），会解成嵌套两层，插件找不到 → 静默退回桌面布局。

### ⚠️ 为什么解到 `$DSH_HOME/mobile-ui/` 而不是 profile 的 `node_modules`（踩过的大坑）

**症状**：界面看起来"插件装了一半"——侧栏不再是抽屉，而是常驻 56px 图标条（正文只剩
304px），工具卡片/执行过程里的长文本**左右两端都被裁出屏幕**，但左上角汉堡按钮还在、
DOM 里也有 `.mp-nav-btn` / `.mp-drawer-mask`。**没有任何报错。**

**根因**：插件原先解到 `profiles/web/node_modules/dsh-android-mobile`，而该包**不在**
profile 的 `dependencies` 里（写进去会让 DSH 去 registry 拉这个本地包）。于是
**用户每用插件管理器装一次插件，pnpm 就把它当"多余依赖"挪进 `node_modules/.ignored/`**。
包一挪走，overlay 里按包名解析的 `name` 找不到模块 → 客户端半不编入浏览器 →
`injectStyles()` 注入的 `<style>` 随插件卸载被移除 → **全部 53 条 `[data-mobile]`
规则一起消失**，静默退回桌面布局。

**为什么难查**：`[data-mobile]` 属性还在（`mpSyncMobileAttr` 照常跑），残留的 DOM
元素也在，只有 CSS 没了——所有 `position` 都退回 `static`。用 CDP 数一下
`document.styleSheets` 里 `[data-mobile]` 规则的条数就能一眼看出（正常 53，坏掉 0）：

```js
let n=0; for (const sh of document.styleSheets){let rs;try{rs=sh.cssRules}catch(e){continue}
  const w=a=>{for(const x of a){if(x.cssRules&&!x.selectorText){w(x.cssRules);continue}
    if(x.selectorText&&/\[data-mobile\]/.test(x.selectorText))n++}};w(rs)} n  // 期望 53
```

**修法**：把包放到 pnpm 管不到的地方（`$DSH_HOME/mobile-ui/`），overlay 里用
`file://` 绝对 URL 挂载 —— `dsh-client-modules.locatePkgJson()` 显式支持 pathLike
说明符（`.` / `file:` / 绝对路径），会从模块位置向上找最近的 `package.json` 认作该包。
`ensureMobileUi()` 同时会清掉 `node_modules` 下的旧位置。

**为什么走 `--patch` 而不改 profile 的 `cordis.patch.yml`**：后者归 DSH 初始化并可能承载
用户配置，覆写会丢东西；`--patch` 层在 profile 层之后应用、优先级更高，且完全由本 App 拥有。

**为什么不写进 profile 的 `package.json` dependencies**：那会让 DSH 试图从 registry 拉这个
本地包。桌面端同样刻意不写。

**插件依赖的 CSS Module hash 与引擎版本强绑定**（如 `pI_x6G`=layout、`wSkVaW`=conversation）。
插件内置「别名桥接」在运行时从稳定局部名反推当前 hash，升级 DSH 后多数规则能自动复活，
但**不是所有**——见下。

### 别名桥接的判据（改过，别退回去）

`mpDeriveAliasMap()` 只认**命中 ≥2** 的映射（`if (hit < 2) continue`）。

原先有一条 `|| locals.length === 1` 豁免（单局部名的旧 hash 凭 1 票即可采纳），它在
`Nqubda: ["panel"]` 上炸了：`panel` 太通用，官方右侧栏的 dockkit 面板（`P3OORG_panel`）
也用它，于是桥接把 `Nqubda_panel` 补到了**那个面板**上，而审批浮层规则
`[data-mobile] .Nqubda_panel` 随即把它变成 `position:fixed` 浮层盖住上半屏——用户看到
半屏白块，连「添加一个 API Key」对话框都被压住。

同类隐患还有 `zGbnIq: ["title"]`。**加豁免前先确认该局部名是否唯一。**

### ⚠️ 别名桥接会误认领无关 hash（改过两次，别退回去）

**症状**：「执行了命令」这类**分组头被居中**（本该左对齐）。真机实测 53 个
`.O_Ebla_root` 里有 2 个中招、其余正常 —— **同一页面上有的错有的对**。

**根因**：`MP_LEGACY_HASHES` 是手写的「旧 hash → 局部名」表，但它**没有随引擎升级同步**。
`bOPqQW`（composer 遥测药丸）既登记在表里，**又是官方当前仍在用的 hash** ——
官方 `dsh-client-ui-chat/lib/client.js` 里 `.bOPqQW_root` 与 `.O_Ebla_root`
**同时存在**，属于两个不同组件。旧逻辑无条件把登记过的 hash 从候选池剔除，于是
`bOPqQW` 丢掉了自己的 4 票证据，转而去认领了恰好共享 `label` + `root` 两个局部名的
**无关** hash `O_Ebla`（2 票，刚好够门槛）。而 `bOPqQW_root` 官方定义带
`justify-content: center`：

```css
.bOPqQW_root{…;justify-content:center;gap:12px;display:flex}   /* 遥测药丸栏 */
.O_Ebla_root{min-width:0}                                        /* 分组头 */
.O_Ebla_title{…;text-align:left;…}                               /* 明确要求左对齐 */
```

**判据演进（三次都踩了坑，最终版才是对的）**：

| 版本 | 判据 | 为什么不够 |
|---|---|---|
| v1 | 无条件跳过登记过的 hash | `bOPqQW` 丢掉自己的证据 → 认领 `O_Ebla` |
| v2 | 当下样式表里有没有该 hash | CSS Module **懒注入**，同一 hash 时有时无 → 同页有的中招有的正常 |
| v3 | 累积证据 `mpOfficialSeen`（只增不减） | 早期样式表未注入时仍会误判 `YDXeBa→oz9t_a` |
| **v4（现行）** | 累积证据 **+ 重合率 ≥ 60%** | ✅ |

**最终判据**（`mpDeriveAliasMap`，三条缺一不可）：

1. **`mpOfficialSeen.has(oldHash) || cur[oldHash]` ⇒ 跳过**。前者是「官方样式表
   **曾经**出现过」，后者是「当下 DOM 里有（且已排除桥接自己补的）」。
   ⚠️ 必须**累积**：官方 CSS Module 按需注入，未渲染组件的规则此刻不在表里，
   用快照会让判据抖动。真机实测官方样式表里出现过 114 个 hash。
2. **重合率 `hit / min(旧局部名数, 新局部名数) ≥ 0.6`**。光有 2 票远不够 ——
   真机实测 `YDXeBa→oz9t_a` 只因两者偶然共享 `time` + `chevron` 两个**通用**局部名
   就达成了 2 票。真正的「过期」是**同一组件换 hash**，局部名集合基本不变（接近 100%）。
   `YDXeBa`(10) vs `oz9t_a`(10)，命中 2 → 20% ⇒ 拒绝。
3. **目标 hash 自身也登记在旧表里 ⇒ 不许被认领**（它属于另一个组件）。

另外两条必须同时满足：

- **排除插件自己的样式表**：插件 CSS 大量使用旧类名（如 `[data-mobile] .bOPqQW_root`），
  算进去的话所有旧 hash 都会被判为未过期，**桥接彻底失效**。
  ⚠️ 判据必须**全等**比较 `dataset.pluginCss === "dsh-android-mobile/main.css"` ——
  官方 DSH 自己也给每个 CSS Module 的 `<style>` 打 `dataset.pluginCss`（值是模块路径），
  只判该属性是否存在会误伤 140 个官方样式表（真机实测 143 张表里 140 张被误判）。
- **防自我强化**：桥接补上去的类名记进 `mpAliasAdded`，DOM 采集时跳过它们 ——
  否则下一轮推导会拿自己补的类名当官方证据，越套越偏。

⚠️ **误加的类名不会自己消失**：判据修好后，DOM 上残留的旧类名照旧触发官方规则
（`justify-content: center` 依然生效），用户看到的错位不会恢复。所以有
`mpPruneStaleAliases()` —— 映射变化时主动撤掉「已补上但当前映射里不再成立」的类名。

⚠️ **别再往 `MP_LEGACY_HASHES` 里加官方当前仍在用的 hash。** 加之前先在
`engine/node_modules/@deepseek-ai/dsh-client-ui-*/lib/client.js` 里
`Select-String -SimpleMatch "<hash>_"` 搜一遍：**搜得到就说明它没过期，不该进这张表**。
（实测：这张表里 14 个 hash 在当前引擎 `0.2.0-rc.2` 下**全部没过期**，
所以桥接在当前版本下本就无事可做，任何「认领」都是误伤。）

**验收手法**（数值证据，别靠截图猜）：

```js
// 期望：O_EblaWithBOPqQW = 0，justify = "normal"，dataMobileRules ≈ 57
({
  O_Ebla_root: document.querySelectorAll('.O_Ebla_root').length,
  O_EblaWithBOPqQW: document.querySelectorAll('.O_Ebla_root.bOPqQW_root').length,
  justify: getComputedStyle(document.querySelector('.O_Ebla_root')).justifyContent,
})
```

`dataMobileRules` = `document.styleSheets` 里 `[data-mobile]` 规则条数，
正常 **57**；**0 表示插件 CSS 整体丢失**（退回桌面布局，见上一节）。
另：logcat 里**不应出现** `[mobile-polish] CSSModule 别名桥接:` 那行 ——
它一旦打出就说明有 hash 被认领了，在当前引擎版本下即为误伤。

### 「+」菜单与键盘的时序（改过，别退回去）

官方「+」的指令菜单**只在编辑区仍持有 DOM 焦点时才会渲染**，而官方
`onToggleCommandMenu` 是「先把焦点还给编辑区、再 toggle 菜单」
（`dsh-client-ui-conversation/lib/client.js:17390`）。同时官方 `keepDraftFocus`
还会在 mousedown 里再 focus 一次。

而插件的两个守卫（修复 29 的 `onFocusIn`、修复 38b 的 `arm`）都会**主动把焦点从编辑区
拿走**（键盘被手动收起后，只要还有可编辑元素持焦点，Android 就会把输入法挂回来）。
于是「谁先拿走焦点」直接决定菜单能不能出现。真机三种时序：

| 拿走焦点的时机 | 菜单 | 键盘 |
|---|---|---|
| 菜单渲染前（旧行为） | ❌ 不开 | 不弹 |
| 一直不拿 | ✅ 开 | ❌ 弹回 |
| **菜单出现之后再拿** | ✅ 开 | ✅ 不弹 |

所以有一条**共享闸门** `mpDeferBlur()` / `mpFlushPendingBlur()`（在
`setupComposerKeyboardGuard` 上方）：把「拿焦点」挂到 `[data-trigger-menu]` 出现的
那一刻，并带 700ms 兜底（官方改了渲染方式时也必须把焦点还回去，否则键盘一直挂着）。

⚠️ 闸门**必须对两条守卫都生效** —— 只改 `arm` 无效，`onFocusIn` 会先一步把焦点抢走
（真机实测：只有 `focusout`，菜单从未进过 DOM）。
⚠️ **只对 `button[aria-haspopup="listbox"]`（即「+」）延迟**；发送 / 停止 / 模型选择等
不弹这个菜单，必须维持**立即 blur**，否则白等一个兜底周期，那段时间足够键盘被挂回来。
⚠️ 不要退回「立即 blur」，也不要改成「在 keepFocus 里 blur」—— 后者同样抢在 React
渲染菜单之前。

### API 密钥输入框弹安全键盘（修复 68，**改过两次才对**）

**症状**：设置 → 模型 → 添加模型提供商，点 API 密钥输入框弹出「华为安全键盘」，
**没有粘贴入口**。API key 是 51 字符的 `sk-...`，手抄不现实 ⇒ 功能实际不可用。

**根因（第一版没找对，第二版才定论）**：官方把凭据框渲染成 `<input type="password">`
（`dsh-client-ui-settings-models/lib/client.js` L1692 与 L2552），Android WebView 据此
把 `inputType` 定成 `TYPE_TEXT_VARIATION_WEB_PASSWORD`，输入法切安全键盘（按设计屏蔽剪贴板）。

**两次踩坑，都要记住：**

**坑一：用 CSS 补圆点 ⇒ 修复完全失效。**
第一版摘掉 `type=password` 后，又写了
`input[data-mp-key="1"] { -webkit-text-security: disc !important; }` 把圆点补回来。
而 blink 判定「密码字段」用的是 `Element::ShouldObscureValue()`，它检查的正是
**`computedStyle.textSecurity != none`** —— `type=password` 只是经 UA 样式表拿到同一条声明。
摘掉属性、再手写同一条声明 ＝ 原样把密码语义装了回去。
⚠️ **「普通键盘」与「圆点遮蔽」在 Android WebView 上不可兼得**，要剪贴板就必须放弃遮蔽。

**坑二（更隐蔽）：事后改属性永远追不上 —— WebView 不为已建立的输入连接重算 `inputType`。**
官方那两个框带 **`autoFocus: true`**（L1699、L2552）。React 把元素插入 DOM 的**同一帧**
就以 `type=password` 聚焦，WebView 在那时建立了密码型连接。MutationObserver 是**之后**
才改属性的，**太晚了**。实测 `blur() + focus()` 也刷不动 —— 确认是缓存的。

**决定性对照实验**（真机 CDY-TN90 / Android 10，读 `dumpsys input_method` 的实时值）：

| 聚焦的元素 | DOM | IME `inputType` |
|---|---|---|
| 手工造的纯净 `<input type=text>` | `type=text` | `0x0` |
| 同样纯净 + `data-mp-key="1"` | `type=text` | `0x0` |
| 官方凭据框（**只做事后改属性**） | `type=text`、`ts=none` | **`0xe1`** ✗ |
| 官方凭据框（**原型层拦截**） | `type=text`、`ts=none` | **`0x80a1`** ✓ |

`0xe1` = `TYPE_CLASS_TEXT(0x1)` \| `TYPE_TEXT_VARIATION_WEB_PASSWORD(0xe0)`
`0x80a1` = `TYPE_CLASS_TEXT(0x1)` \| `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD(0x10)` \| `NO_SUGGESTIONS`

**做法**：`installPasswordTypeShim()` 在 `HTMLInputElement.prototype` 层重定义 `type`
访问器与 `setAttribute`，把写入的 `"password"` 就地换成 `"text"` ——
**让 DOM 里从头到尾没出现过 password**，输入连接从建立的第一刻就是普通文本框。
React 完全无感（它只认自己的 props，不读 DOM 属性），受控逻辑不受影响。
MutationObserver 保留作兜底 + 摘 `autocomplete="new-password"`。

**顺带排除掉的嫌疑（都实测过，别再往这些方向查）**：
- `data-mp-key` 标记本身 —— 带它的纯净 input 仍是 `0x0`
- CSS `-webkit-text-security` —— 已确认 `getComputedStyle().webkitTextSecurity` 可读，
  焦点框实测 `ts=none`，证明遮蔽确实清掉了
- `blur() + focus()` 重建连接 —— 实测刷不动 `inputType`

**验收**：`0xe1` → `0x80a1`，键盘顶部「华为安全键盘」标识消失，键盘出现剪贴板入口。

⚠️ 改的是 `mobile-ui.zip` 内容 ⇒ **必须同时改两处版本戳**，否则老设备静默不更新：
`EngineManager.MOBILE_UI_VERSION`（→ `1.0.0-49`）与
`tools/build-webview-compat.ps1` 的 `-CompatVersion`（→ `9`，并重跑脚本）。
降级产物会**覆盖**源文件，这是「改了没生效」最常见的陷阱（见 ⑯）。

## 调试真机

App 私有目录别的 UID 读不到，`adb shell ls` 会报 `inaccessible or not found`，必须走 `run-as`：

```powershell
adb shell run-as com.dsh.android ls -la files/engine
adb shell run-as com.dsh.android cat files/home/.dsh/profiles/web/package.json
```

**定位 WebView 内布局问题的方法**（本轮就是这么做出来的）：插件 `client.js` 是明文，
可以直接往里追加探针代码再推回设备——

```powershell
adb push probe.js /sdcard/p.js
adb shell run-as com.dsh.android cp /sdcard/p.js `
  files/home/.dsh/mobile-ui/dsh-android-mobile/lib/client.js
adb shell am force-stop com.dsh.android   # 重启才会重新加载
```

`console.log` 会经 `[web]` 前缀出现在 logcat 里。用
`getBoundingClientRect()` / `getComputedStyle()` / `elementsFromPoint()` 拿数值证据，
比反复截图猜快得多。

⚠️ **探针用完必须删干净**：`run-as rm -rf` 掉整个插件目录（让它从 APK 重新解包），
并清 `/sdcard` 上的临时文件。否则下次构建的资产版本戳虽然会覆盖它，但中途容易被自己误导。

## 插件改坏导致 App 打不开 —— 启动前体检 + 安全模式

**症状**：用户让 App 里的 AI 改插件，AI 把 profile 或 `engine/` 改坏，之后 App
**永久打不开**，且用户手上没有任何出路 —— 只能卸载重装，而那会清掉全部会话与凭据。

**根因是两条叠加**：

1. profile 与 patch 层都是**模型可写**的，改坏会让引擎 `exit 1`（或 boot 挂死）。
2. `ensureExtracted()` 有 `.stamp` 短路 —— 改坏的是 `engine/` 下的文件时，
   **永远不会被还原**，因为版本戳没变。

### 判据：用引擎自己的 `--dump-config`，不要在 Kotlin 里重写 YAML 校验

引擎自带免 boot 的 `dsh --profile web --dump-config`，走的是**和启动完全同一套**
`loadProfile()` + patch 解析，但只解析、不求值 `!!js`、不起服务。所以判据来自引擎
自己的解析器，不会随引擎升级而漂。

真机实测耗时 **0.7~0.8 秒**（3 次：703 / 780 / 800 ms），相对引擎自身 7~10 秒的启动
时间可忽略。

**四种坏法的判据不一样，别合并成一条**（真机逐条实测）：

| 坏在哪 | `dump-config` | 判据 |
|---|---|---|
| `profiles/web/package.json` 非法 JSON | RC=1 | 退出码（但 stderr 里**没有文件路径**，只有 JSON 位置） |
| `profiles/web/cordis.patch.yml` 语法坏 | RC=1 | 退出码 + stderr 里的绝对路径 |
| 第三方 bundle 的 patch 坏 | **RC=0** | stderr 的 `skipping profile bundle` |
| 核心 bundle（`dsh-web-app`）的 patch 坏 | **RC=0** | **required 条目缺失** |

⚠️ **只看退出码会漏掉后两种**。第四种尤其要紧：真机实测该状态下 `dump-config`
返回 **RC=0**，但真正 boot 会**挂死**（45 秒超时被杀，`EXIT=124`）。所以必须再补一道
「required 条目在不在」的检查。

### 自动修：`EngineManager.preflight()`（A）

在 `start()` 的**最前段**跑（早于所有往 `engine/` 写东西的 `ensureXxx`），
发现坏文件就**改名隔离**（`<原名>.bak-<时间戳>`），再复检一次确认修好了。

⚠️ **顺序是硬性的**：体检在发现核心 bundle 坏时会触发**整棵 `engine/` 重解包**，
那会把已经打好的补丁与解出的原生插件全部抹掉。放在 `ensureWebCompat()` 之后会让
重解包抹掉刚打的补丁。

⚠️ **改名不删**：AI 改坏的东西可能还带着用户想要的内容，体检只负责让 App 能打开。

### 兜底：安全模式（B）

体检只用能精确指认的判据，拿不准就不碰 —— 免得误伤好配置。但覆盖不到的情况真实
存在（坏法超出那四类、体检超时、坏在体检看不见的地方）。所以失败浮层上给一个
**用户显式触发**的按钮（`overlaySafeMode`，只在 `isError` 时可见）。

`EngineManager.enterSafeMode()` 推开三样，都不碰用户数据：

| 推开什么 | 为什么 |
|---|---|
| profile 用户 patch 层 | 模型最爱改的就是它，改坏会让引擎 exit 1 |
| 第三方 bundle（保留 deepseek-ai 前缀那些） | 插件装坏 / 版本对不上是常见死因 |
| `engine/.stamp` | 强制重解包，把 `engine/` 里的改动全部还原 |

⚠️ **会话、凭据、工作区都在 `$DSH_HOME` 与 `workspace/`，不在这三样里** ——
真机验收时逐项确认过（`sessions/`、`storages/`、`workspace/` 全部完好）。

⚠️ 三样都是改名不删。`stripThirdPartyBundles()` 改的是 profile **清单**而不是插件包
（包还在 `node_modules` 里，想装回来重新勾一次即可），且**备份失败就不写** ——
否则会把唯一一份原始清单覆盖掉。

### 真机验收（2026-10-11，CDY-TN90 / Android 10）

| 场景 | 结果 |
|---|---|
| 正常态（配置完好） | 体检**静默通过**，无任何日志，启动 11 秒，行为零变化 |
| 坏法① `package.json` = `{ x` | 体检识别 + 隔离 + 复检通过，14 秒起好 |
| 坏法② `cordis.patch.yml` 引号未闭合 | 体检 1 秒识别 + 隔离 + 复检通过，14 秒起好 |
| 坏法④ 删核心 bundle 的 patch | 识别出缺 `webserver, modules, connection` → 重解包 18.5 秒 → 复检通过 → 起好 |
| 体检修不了的失败态（删 `bin.js`） | 失败浮层出现，按钮可见可点 |
| 点安全模式（清单含第三方 bundle） | 隔离 2 个文件 + 摘掉 1 个第三方 bundle + 重解包 → 起好，会话/凭据/工作区全在 |

### 写这套东西踩到的坑

⚠️ **Kotlin 的块注释支持嵌套**：KDoc 里写 `@deepseek-ai` 紧跟 `/*`（即包名通配那种
写法）会**开始一层嵌套注释**，把下面整段代码吞进注释里。报错是 `Missing '}'` +
`Unclosed comment`，**行号指向完全无关的地方**。文档里写这种通配要拆开写。

⚠️ **主题是 `Theme.Material3.*`，它会顶掉 `Button` 的 `android:background`** ——
设了描边背景却渲染成一大块实心紫。气泡里那几个按钮同样是 `TextView`，照抄既有写法
（`TextView` + `clickable="true"` + `@drawable/xxx_bg`）。

⚠️ **`org.json` 的 `JSONObject.toString()` 会把 `/` 转义成 `\/`**（防 `</script>`
的历史行为）。在 JSON 里完全合法、引擎也读得进去，但这份清单用户和模型都会看，
`@deepseek-ai\/dsh-base` 长得就像文件已经坏了 —— 而"配置看起来坏了"正是要消灭的
状态。回写时 `.replace("\\/", "/")` 还原。

⚠️ **`quarantine()` 是两条路径共用的**（体检自动修 / 安全模式），日志前缀必须区分
来源 —— 否则排查时会把用户主动进安全模式误读成"体检又修了一次"。

⚠️ **测试期隔离出来的 `.bak-<时间戳>` 会累积**：真机上跑一轮完整验证会留下五六个。
测完记得 `rm -f files/home/.dsh/profiles/web/*.bak-*`，并把 profile 恢复成测前的样子。

### ⚠️ 体检救不了的一种坏法：**我们自己注入的可选工具插件**注册失败

上面的体检只看**配置文件**（profile / patch / `engine/`）。但还有一条同类死路它完全
看不到 —— 坏在我们**自己的插件代码**里：

```
dsh: startup failed: 1 required plugin did not activate
Failed plugins (1):
  dsh-android-logs
    Package: file:///…/.dsh/logs-tool/dsh-android-logs/lib/index.js
    TypeError: tool "logs" must declare output { schema, render, presentationMeta? }
        at Proxy.register (…/@deepseek-ai/dsh-tools/lib/index.js:2881:188)
        at Object.apply (…/@deepseek-ai/cordis/lib/index.js:120:36)
```

真机实测（2026-10-13 21:46 ~ 23:29，20 个诊断包、**54 次启动**）：`logs` 工具的定义
漏了 `output` 声明，`ctx.tools.register()` 抛 `TypeError`，异常从 `apply()` 冒出去 →
cordis 判定该插件 failed → 引擎**直接退出**。用户看到的是
**「引擎启动失败（引擎进程已退出）」—— App 完全打不开**，
而表面上与「一个日志工具」毫无关联。**这正是体检覆盖不到的那一类**：配置文件全是好的。

⚠️ **根因链的最后一环是 `EngineManager.writeLogsToolPatch()` 的 `- insert:` 语义**：
它把 `dsh-android-logs` 作为**普通条目**插进 overlay，于是该插件与核心 bundle 的条目
**同等对待**，失败即「required」⇒ 整引擎不启动。所以从这个插件进来的任何异常，
代价都等于「App 打不开」。

**做法：6 个工具插件的 `ctx.tools.register()` 一律包 `try/catch`。**
覆盖 `bubble` / `logs` / `root` / `shell` / `statusbar` / `uiagent`
（`mobile-ui-plugin/dsh-android-*/lib/index.js`）。

降级方向：**注册失败 ⇒ 只有这一个工具不存在**（模型调用时会明确报「未知工具」），
而不是整个 App 不可用。⚠️ **但绝不能静默** —— catch 里用 `logger.warn` 把**原始错误
消息**（`error?.message`）原样打出来，否则又变成「工具不见了但没人知道为什么」。
日志里应看到：

```
[dsh-android-logs] 注册 logs 工具失败，本插件不激活（引擎继续启动）: tool "logs" must declare output …
```

⚠️ **`try/catch` 只能挡住「注册那一刻抛的异常」，挡不住「工具自身在调用时炸」** ——
后者由 `dsh-tools` 自己的调用边界处理，与启动无关。两者别混为一谈。

⚠️ **给新插件写 `apply()` 时照抄这个形状**；已有插件逐个核对过，6/6 都带保护、
无裸调用残留（`^\tctx\.tools\.register` 为 0 命中）。

## `$PREFIX/bin/node` 会坏，而且坏了之后只有卸载重装一条路 —— 自校验 + 自愈

**症状**（粉丝截图，无日志）：启动层停在

```
引擎启动失败（超时未打印URL）
# Fatal error in , line 0
# Check failed: magic_number_ == SerializedData::kMagicNumber
```

底下那个「进入安全模式（不改动会话与凭据）」按钮**点了也没用**。

### 判据：这条报错**只可能**来自 node 自己

`magic_number_ == SerializedData::kMagicNumber` 是 V8 `Deserializer` 构造函数里的
CHECK（`deps/v8/src/snapshot/deserializer.cc`），即在**反序列化内嵌启动快照**。
那发生在**任何一行 JS 执行之前**。所以它跟插件、`engine/`、profile、`$DSH_HOME`
**全都没关系** —— 坏的是 `$PREFIX/bin/node` 这个二进制本身。
`Fatal error in , line 0`（函数名为空、行号 0）也符合「CHECK 在构造函数里」。

⚠️ **真机排除实验记录**（别重复做）：

| 实验 | 结果 |
|---|---|
| 把 node 截断到 49,000,000 B（`head -c`） | `Segmentation fault` —— **不是**这条报错 |
| `--snapshot-blob` 喂 32 字节垃圾 | `node::SnapshotData::FromBlob … (magic) == (kMagic)` @ `node_snapshotable.cc:654` —— **不同字符串、不同站点** |
| 改文件偏移 `0xf914c4` 处的 `0xc0de05c2` → `0xdeadbeef` | node 照常打印 `v26.4.0` ⇒ **那个偏移不是启动快照** |

### 为什么原来的代码修不了它

`installNodeRuntime()` 的跳过判据原本只是**存在性**：

```kotlin
if (nodeMarker.exists() && nodeBinary.isFile) return true   // ← 内容坏掉照样命中
```

于是坏掉的 node 永远被当成「已装好」，引擎永远起不来。而「安全模式」只推开
profile patch 层 / 第三方 bundle / `engine/.stamp` —— **三样全在 `$DSH_HOME` 和
`engine/` 里，一个都不碰 `$PREFIX`**。所以用户唯一的出路是卸载重装，
而卸载会清掉整个 `$DSH_HOME`（会话、凭据、工作区）。

### 做法一：`nodeIsUsable()` —— 用「能不能起来」当判据

跑一次 `node --version`（约 50 ms），只有**退出码 0 且 stdout 恰好等于
`NODE_EXECUTABLE_VERSION`（`v26.4.0`）**才算可用。调它的地方三处：

| 位置 | 时机 |
|---|---|
| `installNodeRuntimeLocked()` 开头 | marker 命中时，坏了就删 marker + 删二进制，走重解包 |
| `installNodeRuntimeLocked()` 结尾 | 装完再验一次，**验不过就不写 marker**，下次启动重来 |
| `repairNodeRuntimeIfNeeded()` | 用户点「安全模式」时（见下） |

⚠️ **必须传 `LD_LIBRARY_PATH=$PREFIX/lib`**：不传的话 node 报
`CANNOT LINK EXECUTABLE … not found`，**一个健康的二进制会被误判成损坏**，
然后每次启动都白重解包一遍（实测 15 s）。
另外**两路流都要在读 `waitFor` 之前读完**，否则子进程可能卡在写满的管道上。

⚠️ **判据刻意不用 sha256 常量**：资产一换就得重算，漏改是**静默**的
（本项目已有多次「版本戳漏改 ⇒ 老装机不更新」的教训）；而且每次启动读 47 MB 太重。
`--version` 恰好覆盖「ELF 能加载 + 依赖库齐 + V8 启动快照能反序列化」这三点。

⚠️ **`NODE_EXECUTABLE_VERSION` 与 `NODE_VERSION` 必须同步改**：后者是安装标记
`.node_runtime_<版本>` 的版本，前者是资产里 node 自报的版本。改一个漏另一个，
症状相反但都是坏：漏前者 ⇒ 新 node 每次被误判成损坏、反复重解包；
漏后者 ⇒ marker 命中、新资产永不生效。

### 做法二：安全模式带上自愈

`enterSafeMode()` 第 ④ 步调 `bootstrap.repairNodeRuntimeIfNeeded()`：用**与
`installNodeRuntime` 完全相同**的判据，node 可用时零成本返回，不可用才重解包。
这样「点安全模式」才真的能救回一个坏 node。

### 做法三：`$PREFIX` 上的并发写必须互斥（这是坏 node 的来源）

`TermuxBootstrap` 有**两个实例**（`DshApplication` 一个、`EngineManager` 的
`by lazy` 一个），两条路径会**同时**进来：

- `DshApplication.bootstrapTermux()`（`applicationScope`，IO 线程）
- `EngineManager.start()`（`lifecycleScope`，调度到 IO）

而两条路径都会**原地重写同一个 47 MB 的 node 二进制**。**实例级锁挡不住它们**，
所以 `prefixLock` 挂在 **companion object**（进程级），三个入口共用：
`install()` / `installNodeRuntime()` / `ensureNodeRuntimePatched()` /
`ensurePathsRewritten()`。实现体抽成 `*Locked()` 形式，KDoc 里写明「调用方必须已持锁」。

同时把两处写盘改成**临时文件 + 改名**（全有或全无，没有半截文件窗口）：

| 函数 | 原写法 | 现写法 |
|---|---|---|
| `installNodeRuntimeLocked()` 解包循环 | 固定名 `<name>.part` + `renameTo` | `File.createTempFile(".<name>.", ".part", 同目录)` + `Files.move(REPLACE_EXISTING)` |
| `patchElfInPlace()` 写回 | `FileOutputStream(file).write(bytes)`（**原地截断重写**） | 唯一名临时文件 + `fd.sync()` + `Files.move(ATOMIC_MOVE)` |

⚠️ 固定临时名 `<name>.part` 本身就是个坑：两个写者会打开**同一个**临时文件。
⚠️ `patchElfInPlace()` 原来那个写法的窗口有几十毫秒，期间磁盘上是个**半截文件** ——
任何在这个窗口里 exec 它、或读到一半的进程，拿到的都是「大小对得上、内容已经坏掉」
的二进制，与粉丝报的症状一致。
⚠️ `fd.sync()` 不能省：`close()` 只把数据交给 OS，掉电会留下大小正确、内容全零的文件。

### 真机验收（2026-10-11，CDY-TN90 / Android 10，debug 包 `-r` 覆盖安装保留数据）

把 node 的 ELF magic 改成 `XXXX`（`printf "XXXX" | dd seek=0 conv=notrunc`），
**marker 保持不动**（这正是原来短路的条件），然后启动 App：

```
W Boo/TERMUX: node --version 校验失败: rc=1 out= err=…/bin/node[1]: XXXX: inaccessible or not found
W Boo/TERMUX: 内置 Node 运行时已损坏，重新解包: …/bin/node
I Boo/TERMUX: 安装内置 Node 运行时 26.4.0-2
I Boo/TERMUX: Node 运行时就绪: …/bin/node
I DSH     : Termux 引导完成（24945 ms）   ← 对照：健康路径 15265 ms，差值就是重解包
I DSH     : 引擎就绪: http://127.0.0.1:3080/?token=…
I DSH     : WebView 加载完成: http://127.0.0.1:3080/
```

治愈后逐项核对（**都是实测**）：

| 检查 | 结果 |
|---|---|
| ELF magic | `7f 45 4c 46 02 01 01 00` ✅ |
| 文件大小 | 49,715,528 B（与资产一致）✅ |
| `node --version` | `v26.4.0` ✅ |
| 内容正确性：`grep -c -a com.dsh.android node` | **22** ✅ |
| 内容正确性：`grep -c -a com.termux node` | **0** ✅ （证明 `patchElfInPlace` 的原子写回产出正确） |
| `bin/` 与整个 prefix 下的 `.part` / `.patched` 残留 | 0 个 ✅ |

⚠️ 健康路径**不打印** `node --version` 相关行（判据静默通过），所以
「日志里没有那两行 W/I」本身就是正常信号。

## 已知边界

- **终端/PTY 执行**：已接入（见 ⑩）。侧栏终端可用，但**沙箱不隔离 PID 命名空间**
  （见上），且 app 域下 `/proc` 只能看见自己 uid 的进程。
- **中断处理**：用户自行解决，别自作主张加。
- **`libboosandbox.so` 已构建并接入**（见「④ Android 没有内核级沙箱后端」）。
  它同时是 shell 写入沙箱的强制层。
- **沙箱不隔离 PID 命名空间**：`--unshare-pid` 需 CAP_SYS_ADMIN，普通应用拿不到。
  文件写边界是完整的。
- **`engine-overlay/disable-native.yml` 已废弃**，可删。
- **`x/` 目录是空的**，可删。
- **`engine.bin` 在 APK 内每轮构建都要重新压缩**（52 MB）。若嫌构建慢，可在首次启动时
  改用 `adb push`，能把构建压到 10 秒内。
- **APK 约 117 MB**：`engine.bin` 52 MB + `node-runtime.bin` 30 MB + bootstrap 23 MB
  + pnpm zip 5.9 MB + ripgrep 2 MB。若嫌大，pnpm 可改为首次启动联网下载，
  代价是离线不可用（现在它是离线的）。

## 重新生成资产

**构建脚本不在本仓库**，这些资产是一次性产物。当前存放位置：

- Termux debs / node 运行时打包：WSL `/opt/termux-debs/`、`/opt/node-runtime/`
- 引擎 staging：`%TEMP%\engine-build\`（`node_modules` 451 MB）
- 原生插件源码与构建：`%TEMP%\narb-src\`、`%TEMP%\narb-build\`

更新引擎 = 重跑 npm install + 重打包 `engine.bin`（约 2 分钟）。
更新 Node = 重下 debs + 重跑打包脚本 + **改 `NODE_VERSION`**。
更新原生插件 = 用 NDK 27 重新编译 + 换 zip（无需改版本戳）。

更新 link 转译层（改完 `jni/linkcompat.c` 后）：

```powershell
$cc = "C:\Users\XIAOPAN\AppData\Local\Android\Sdk\ndk\27.0.12077973\toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android26-clang.cmd"
& $cc -shared -fPIC -O2 -Wall -o app\src\main\assets\linkcompat.bin app\src\main\jni\linkcompat.c -ldl
```

无需改版本戳（每次启动覆盖写）。

更新沙箱两个组件（改完 `jni/boosandbox.c` 或 `jni/boo-sandbox-run.c` 后）：

```powershell
$cc = "C:\Users\XIAOPAN\AppData\Local\Android\Sdk\ndk\27.0.12077973\toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android26-clang.cmd"
& $cc -shared -fPIC -O2 -Wall -o app\src\main\assets\boosandbox.bin      app\src\main\jni\boosandbox.c -ldl
& $cc -O2 -Wall              -o app\src\main\assets\boo-sandbox-run.bin app\src\main\jni\boo-sandbox-run.c
```

无需改版本戳（每次启动覆盖写）。

更新 pnpm（换版本时**必须同步改** `EngineManager.PNPM_VERSION`）：

```powershell
# 取 npm 包 pnpm 的 tarball（**纯 JS 主线，11.x 是最后一条**；不要用 12.x，
# 见 ⑥ 的 lock_shared 说明）。解出 package/ 后按「条目不带顶层目录」打包：
Invoke-WebRequest https://registry.npmjs.org/pnpm/-/pnpm-11.28.3.tgz -OutFile pnpm.tgz
tar -xzf pnpm.tgz
Compress-Archive -Path package\* -DestinationPath app\src\main\assets\pnpm-android-arm64.zip -Force
# 打完后用 ZipFile 确认条目名是 bin/pnpm.mjs 而不是 package/bin/pnpm.mjs
```

更新 ripgrep（无需改版本戳）：

```powershell
# 从 https://registry.npmjs.org/@vscode/ripgrep-linux-arm64 取 bin/rg，
# 连同 package.json 打成 zip。条目必须是 bin/rg 这种相对路径（不能带顶层目录）。
```

更新 node-pty 绑定（无需改版本戳）：

```powershell
# 用 NDK 27 从 node-pty 自带的 src/unix/pty.cc 编，**必须 -static-libstdc++**。
# 完整命令与理由见 ⑩。
```

验收方式：把 `.so` 和一个 14 项断言的测试程序（独占/保源/内容一致/linkat/ENOENT/目录
EPERM/无残留/大文件完整性）`adb push` 到设备，用 `run-as` 直接跑。一次性脚本，不入库。
沙箱与 runner 同理（`sbtest2.sh` 8/8、runner 10/10）。

### ⚠️ 手写源码资产要单独加 `.gitignore` 白名单

`.gitignore` 是**整目录忽略**（`app/src/main/assets/*`），因为那些二进制合计 112.7 MB
且无法从本仓库重建。但 `assets/` 里有**两个手写源码**，必须入库：

```
app/src/main/assets/*
!app/src/main/assets/webview-polyfill.js
!app/src/main/assets/dsh-android-shell.mjs
```

⚠️ **新增手写源码资产时，记得同步加一条 `!` 例外**，否则它会被静默忽略 ——
`git status` 里看不见、`git add` 也不报错，改动就这么丢了（`dsh-android-shell.mjs`
本轮就是这么被发现漏了入库的）。

⚠️ `COMMIT_MSG.tmp` 也在忽略列表里：写提交信息用的临时文件，曾经误入库过一次
（`9919b27`），用 `git rm --cached` 清掉后补进 `.gitignore`。提交信息用
`git commit -F <file>` 传，文件放 `.git/` 下或记得删。

## 与 DSH 桌面端的关系

引擎版本以**桌面端实际安装的版本为准**（当前 `0.2.0-rc.2`），来源是
`Y:\DSH\resources\app.asar` → `dsh/package.json`。
`C:\Users\XIAOPAN\.dsh\profiles\node_modules\@deepseek-ai\` 是旧版（`0.1.6-alpha.1`），
**不是权威来源**，别拿它当版本依据。
