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
 ├─ assets/pnpm-android-arm64.zip  pnpm 11.28.3 纯 JS 版，解到 $PREFIX/lib/pnpm
 ├─ assets/mobile-ui.zip           移动端 UI 适配插件（dsh-android-mobile）
 └─ assets/webview-polyfill.js     旧 WebView 内核兼容垫片

运行期（filesDir 下）
 ├─ usr/            $PREFIX —— Termux 环境（含 bin/pnpm）
 ├─ engine/         引擎解包目录（含 .stamp 版本戳）
 ├─ home/.dsh/      DSH_HOME：配置、凭据、会话、profiles/web/
 │   └─ mobile-ui/dsh-android-mobile/  移动端 UI 插件（**刻意放在 pnpm 管辖之外**）
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
| `mobile-ui.zip` 内容 | `EngineManager.MOBILE_UI_VERSION`（`1.0.0-2`） |
| `node-runtime.bin` 内容 | `TermuxBootstrap.NODE_VERSION`（`26.4.0-2`） |
| `engine.bin` 内容 | `MainActivity.assetStamp`（`0.2.0-rc.2-1`） |
| `pnpm-android-arm64.zip` 内容 | `EngineManager.PNPM_VERSION`（`11.28.3`） |
| `node-addon-android-arm64.zip` | 无需改（只查文件存在且非空） |
| `node-addon-system-android-arm64.zip` | 无需改（只查文件存在且非空） |
| `ripgrep-android-arm64.zip` | 无需改（只查文件存在且非空） |
| `linkcompat.bin` | 无需改（每次启动覆盖写） |
| `boosandbox.bin` / `boo-sandbox-run.bin` | 无需改（每次启动覆盖写） |
| `webview-polyfill.js` | 无需改（每次启动覆盖写） |

**漏改的后果是静默的**：老标记仍在 → 解包被跳过 → 新资产永不生效。
这三个都是踩过的坑。

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

- **终端/PTY 执行**：不在 v1 范围。
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

验收方式：把 `.so` 和一个 14 项断言的测试程序（独占/保源/内容一致/linkat/ENOENT/目录
EPERM/无残留/大文件完整性）`adb push` 到设备，用 `run-as` 直接跑。一次性脚本，不入库。
沙箱与 runner 同理（`sbtest2.sh` 8/8、runner 10/10）。

## 与 DSH 桌面端的关系

引擎版本以**桌面端实际安装的版本为准**（当前 `0.2.0-rc.2`），来源是
`Y:\DSH\resources\app.asar` → `dsh/package.json`。
`C:\Users\XIAOPAN\.dsh\profiles\node_modules\@deepseek-ai\` 是旧版（`0.1.6-alpha.1`），
**不是权威来源**，别拿它当版本依据。
