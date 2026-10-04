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
 ├─ assets/boosandbox.bin          LD_PRELOAD 写拦截器（沙箱的强制层）
 ├─ assets/boo-sandbox-run.bin     沙箱 runner：把 bwrap 方言翻译成拦截器配置
 ├─ assets/ripgrep-android-arm64.zip   官方 linux-arm64 静态 rg，补 @vscode/ripgrep 缺的 android 包
 ├─ assets/sharp-wasm32.zip        sharp 的 wasm32 变体（官方无 android 预编译二进制）
 ├─ assets/node-pty-android-arm64.zip  自编译的 node-pty 绑定（官方 prebuilds 无 android）
 ├─ assets/pnpm-android-arm64.zip  pnpm 11.28.3 纯 JS 版，解到 $PREFIX/lib/pnpm
 ├─ assets/mobile-ui.zip           移动端 UI 适配插件（dsh-android-mobile）
 ├─ assets/dsh-android-shell.mjs   uid 2000 执行通道（直连 adbd）
 ├─ assets/shell-tool.zip          uid 2000 特权工具插件（注册 `shell` 工具）
 └─ assets/webview-polyfill.js     旧 WebView 内核兼容垫片

运行期（filesDir 下）
 ├─ usr/            $PREFIX —— Termux 环境（含 bin/pnpm）
 ├─ engine/         引擎解包目录（含 .stamp 版本戳）
 ├─ home/.dsh/      DSH_HOME：配置、凭据、会话、profiles/web/
 │   ├─ mobile-ui/dsh-android-mobile/  移动端 UI 插件（**刻意放在 pnpm 管辖之外**）
 │   └─ shell-tool/dsh-android-shell/  uid 2000 特权工具插件（同上，刻意在 pnpm 之外）
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

```powershell
# 构建（workdir = 本目录）
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
.\gradlew.bat :app:assembleDebug --console=plain
# 产物：app\build\outputs\apk\debug\app-debug.apk（约 117 MB）
```

环境：AGP 8.8.2 / Kotlin 2.0.21 / Gradle 8.13 / JDK 17 / compileSdk 35 / minSdk 26。

- **必须显式设 `JAVA_HOME`**，否则取到系统默认 JDK。
- 工程路径含中文（`1-项目代码\安卓开发`），靠 `gradle.properties` 里的
  `android.overridePathCheck=true` 放行，别删。
- 构建需经代理（`gradle.properties` 里的 `127.0.0.1:7897`）。

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

**真机 syscall 实测（app 私有目录）**：

| 调用 | 结果 |
|---|---|
| `link()` / `linkat()` | **EACCES** |
| `rename()` | OK |
| `renameat2(RENAME_NOREPLACE)` | **OK**（目标不存在则成功，已存在则 EEXIST 且不覆盖） |
| `symlink()` / `copyFile()` / `unlink()` / `mkdir()` / `fsync()` | OK |

**为什么不能靠 JS 侧打补丁**：引擎用 `import { link } from "node:fs/promises"`（ESM 具名
导入）。实测 `--import` 预加载里改 `fsPromises.link` 得到
`named_import_is_patched = false`（default 属性改了，具名绑定没改）→ JS 侧此路不通。

**做法**：`app/src/main/jni/linkcompat.c` 编译成 `assets/linkcompat.bin`，
`EngineManager.ensureLinkCompat()` 解到 `filesDir/liblinkcompat.so`，启动 node 时
`LD_PRELOAD` 它。只 hook `link`/`linkat` 两个符号，**先试原生调用，只有被 SELinux 拒
（EACCES/EPERM）才转译**，其余 errno 原样返回。

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
设备内核 4.14 支持；代码里保留了 `ENOSYS` 时退化为「`O_EXCL` 占位 + rename」的分支。

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
| `mobile-ui.zip` 内容 | `EngineManager.MOBILE_UI_VERSION`（`1.0.0-13`） |
| `node-runtime.bin` 内容 | `TermuxBootstrap.NODE_VERSION`（`26.4.0-2`） |
| `engine.bin` 内容 | `MainActivity.assetStamp`（`0.2.0-rc.2-1`） |
| `pnpm-android-arm64.zip` 内容 | `EngineManager.PNPM_VERSION`（`11.28.3`） |
| `node-addon-android-arm64.zip` | 无需改（只查文件存在且非空） |
| `node-addon-system-android-arm64.zip` | 无需改（只查文件存在且非空） |
| `ripgrep-android-arm64.zip` | 无需改（只查文件存在且非空） |
| `node-pty-android-arm64.zip` | 无需改（只查文件存在且非空） |
| `linkcompat.bin` | 无需改（每次启动覆盖写） |
| `boosandbox.bin` / `boo-sandbox-run.bin` | 无需改（每次启动覆盖写） |
| `dsh-android-shell.mjs` | 无需改（每次启动覆盖写） |
| `shell-tool.zip` | 无需改（每次启动解包覆盖写） |
| `webview-polyfill.js` | 无需改（每次启动覆盖写） |

**漏改的后果是静默的**：老标记仍在 → 解包被跳过 → 新资产永不生效。
这三个都是踩过的坑。

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

#### 密钥从哪来：必须由用户用 PC 引导一次

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
- Android 11+ 才能真正免 PC（走无线调试配对），本方案**尚未覆盖**。
- **卸载重装会清掉 `$DSH_HOME`**（Termux 环境、全部会话日志、`adbkey`），
  重装后要重新 bootstrap 并用 PC 再导入一次 adbkey。

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

## 与 DSH 桌面端的关系

引擎版本以**桌面端实际安装的版本为准**（当前 `0.2.0-rc.2`），来源是
`Y:\DSH\resources\app.asar` → `dsh/package.json`。
`C:\Users\XIAOPAN\.dsh\profiles\node_modules\@deepseek-ai\` 是旧版（`0.1.6-alpha.1`），
**不是权威来源**，别拿它当版本依据。
