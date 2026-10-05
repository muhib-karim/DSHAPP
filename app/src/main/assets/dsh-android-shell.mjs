#!/usr/bin/env node
/**
 * dsh-android-shell.mjs —— uid 2000（shell）执行通道。
 *
 * ## 为什么需要它
 *
 * DSH 的 bash 工具跑在 App 自己的 uid（如 10323）下，被 SELinux 的
 * `untrusted_app` 域限制，很多操作**做不到**，而且失败方式很阴险：
 *
 * | 命令 | app 域的真实表现 | shell 域（uid 2000） |
 * |---|---|---|
 * | `screencap -p x.png` | rc=1，**stderr 完全为空**，留下 0 字节文件 | rc=0，真图 |
 * | `input keyevent 3` | **rc=0 但屏幕纹丝不动** | rc=0，真的生效 |
 * | `pm uninstall x` | `SecurityException: INTERACT_ACROSS_USERS_FULL` | 走到包管理器 |
 * | `settings put/get` | `SecurityException: INTERACT_ACROSS_USERS` | 正常 |
 * | `am force-stop` | `SecurityException: FORCE_STOP_PACKAGES` | 正常 |
 * | `getevent` | `/dev/input Permission denied` | 能枚举输入设备 |
 * | `dumpsys battery` | rc=**0**，输出 `Can't find service: battery` | 完整状态 |
 * | `wm size` | rc=**20**，`Can't find service: window` | 正常 |
 * | `ps -A` | rc=0，**只有 6 行**（自己 uid 的进程） | 672 行 |
 *
 * 注意 `input` 和 `dumpsys` 两行：**退出码是 0 或空输出**，静默失败。
 * 所以「失败」不能只看退出码，这也是本通道存在的理由 —— 与其事后猜，
 * 不如让模型在需要时**显式选择**特权身份（见 dsh-bash-sandbox 的 patch）。
 *
 * ## 怎么拿到 uid 2000
 *
 * 设备上的 adbd 以 shell 身份（uid 2000）运行，监听 5555。本脚本直连它，
 * 走标准 ADB 协议认证后执行命令。**不需要 adb 二进制，也不需要 ADB server。**
 *
 * ⚠️ **ADB 的签名算法不是标准的 `crypto.sign('sha1', token)`。**
 * AOSP 是手工拼一个 PKCS#1 v1.5 块，把 **token 原文**放进 DigestInfo 的
 * 摘要槽（而不是 token 的 SHA1）：
 *
 * ```
 * 00 01 FF×219 00 ‖ 30 21 30 09 06 05 2b 0e 03 02 1a 05 00 04 14 ‖ token原文
 * ```
 *
 * 用 `crypto.sign('sha1', token, priv)` 会被 adbd 拒绝（表现为反复重发 AUTH
 * token，或转而要公钥）。这一点踩过坑，别改回去。
 *
 * ## 密钥从哪来
 *
 * Android 10 **没有**无线调试配对机制（那是 API 30+），而且 adbd 在
 * **TCP 连接**下**不会**弹「允许 USB 调试吗」对话框 —— 实测发公钥后
 * adbd 直接静默挂起，永远等不到授权。所以本脚本**必须使用一把设备已授权的密钥**。
 *
 * 因此引导流程是：用户用 PC 的 adb（已授权）把私钥推到设备，
 * 本脚本从 `/sdcard/dsh_adbkey` 导入到 `$DSH_HOME/adbkey`。
 *
 * 公钥**从私钥现场派生**，不读 `adbkey.pub`：PC 上那个文件的格式并不标准
 * （实测缺 `ssh-rsa ` 前缀，且与私钥不匹配），派生才是唯一可靠来源。
 *
 * ## 不可用时怎么办
 *
 * 一律**回落为本地执行**（app 域）—— 即「没授权就走普通的」。
 * 回落是静默的、逐条命令的，不会让 bash 工具整体失效。
 *
 * 用法：`node dsh-android-shell.mjs <command>`
 * 退出码与输出原样透传；stderr 混入的通道诊断行统一带 `[dsh-adb] ` 前缀。
 */

import net from 'node:net';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';

// ── ADB 协议常量 ─────────────────────────────────────────────
const CMD = {
  CNXN: 0x4e584e43,
  AUTH: 0x48545541,
  OPEN: 0x4e45504f,
  OKAY: 0x59414b4f,
  WRTE: 0x45545257,
  CLSE: 0x45534c43,
};
const CMD_NAME = (c) => Object.keys(CMD).find((k) => CMD[k] === c) ?? `0x${c.toString(16)}`;

const AUTH_TOKEN = 1;
const AUTH_SIGNATURE = 2;
/**
 * ⚠️ **故意保留但不再发送**：这是 ADB 协议里「把公钥递给设备求授权」的
 * `arg0`，而递公钥是 adbd 弹「允许 USB 调试吗」的**唯一**触发条件。
 * 本 App 已全面停用这条路径（原因见 `handshake()` 的注释），保留常量只为
 * 让协议定义完整、便于排查抓包。**不要「修好」它。**
 */
const AUTH_RSA_PUBLIC = 3;

const MAX_PAYLOAD = 256 * 1024;

/** shell v2 的流 id。退出码靠 EXIT 包透传，v1 协议没有退出码，所以必须用 v2。 */
const SHELL_STDOUT = 1;
const SHELL_STDERR = 2;
const SHELL_EXIT = 3;
const SHELL_CLOSE_STDIN = 4;

const CONNECT_TIMEOUT_MS = 4000;
const HANDSHAKE_TIMEOUT_MS = 8000;
const HOST = '127.0.0.1';
const PORT = 5555;

const TAG = '[dsh-adb]';

// ── 无线调试通道（Android 11+）──────────────────────────────
/**
 * Android 11 起有「无线调试」（Wireless debugging），它和 legacy `adb tcpip`
 * 是**两个不同的东西**：
 *
 * | | legacy `adb tcpip 5555` | 无线调试 |
 * |---|---|---|
 * | 端口 | 固定 5555 | **随机高位端口** |
 * | 协议 | 明文 ADB，`A_AUTH` 认证 | **TLS**，连接后先 `A_STLS` |
 * | 怎么开 | PC 执行 `adb tcpip 5555` | 开发选项里勾选 |
 * | 重启后 | **失效**（Android 10 实测 `persist.adb.tcp.port` 为空） | 仍然有效 |
 *
 * 后者才是「免 PC」的关键，但它带来两个新问题，下面分别解决。
 *
 * ## 为什么必须外挂一个 adb 二进制
 *
 * 配对（pairing）走 **SPAKE2 + TLS-PSK**，而 Node 的 `tls` 模块**不支持
 * external PSK**（`tls.createSecureContext` 没有 pskCallback 之外的口子，
 * TLS 1.3 的 external PSK 更是完全没暴露）。自己实现 SPAKE2 等于手写密码学，
 * 不可接受。
 *
 * 所以直接内置 Termux 官方的 `android-tools` 里的 `adb` 二进制 ——
 * 复用 Google 自己的实现。它同时支持 legacy 与无线调试两条路。
 *
 * ## 端口从哪来：Kotlin 侧用 `NsdManager` 发现，写进文件
 *
 * 无线调试的端口是**随机高位端口**，官方发现手段是 mDNS
 * （`_adb-tls-connect._tcp` / `_adb-tls-pairing._tcp`）。
 *
 * 但**本进程自己发不出 UDP 组播**：
 *
 * ```
 * sock.send(q, 0, q.length, 5353, '224.0.0.251')
 *   → EPERM  send EPERM 224.0.0.251:5353
 * ```
 *
 * 绑定 5353、`addMembership` 都成功，只有 `send` 到组播地址被拒（换任意
 * 组播地址、任意绑定端口都一样，是 SELinux 对 untrusted_app 的限制）。
 * 而 Android 的 `mdnsd` 又只答组播查询（单播查询实测无响应）。
 *
 * ⚠️ **不要退回「扫端口」。** 那是最初的实现（扫 32768–60999，实测 20 秒），
 * 已删除。无线调试端口每次重启/重开都会变，等于每条特权命令前都可能干等 20 秒，
 * 而且每次重扫都会耗电。Shizuku 走的是另一条路 ——
 *
 * **`NsdManager` 是系统服务**：请求经 Binder 交给 `mdnsd`（跑在系统域）代发，
 * 完全不受上面那条限制。真机实测（OPPO PHY110 / Android 15）：**81 毫秒**就
 * 拿到端口。所以端口发现放在 Kotlin 侧（`AdbMdns.kt`），结果落到
 * `$DSH_HOME/adb-wireless-port`，本脚本只负责读。
 *
 * 文件格式（纯文本，一行）：
 *
 * ```
 * <connect端口> [<配对端口>]
 * ```
 *
 * 配对端口只在用户打开「使用配对码配对设备」时才有，可能为空。
 *
 * ⚠️ **拿不到文件就说明无线调试没开或没发现** —— 直接回落，不猜、不扫。
 */
const ADB_HOME = process.env.DSH_ADB_HOME ?? '';
const ADB_BIN = ADB_HOME === '' ? '' : path.join(ADB_HOME, 'bin', 'adb');

/** Kotlin 侧（`AdbMdns`）把发现到的端口写在这里，供本脚本读取。 */
const PORT_CACHE_FILE = () => path.join(homeDir(), 'adb-wireless-port');

// ── ADB 报文封装 ─────────────────────────────────────────────
let CRC_TABLE = null;
function crc32(buf) {
  if (CRC_TABLE === null) {
    CRC_TABLE = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      CRC_TABLE[n] = c;
    }
  }
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function pack(cmd, arg0, arg1, data) {
  const payload = data ?? Buffer.alloc(0);
  const header = Buffer.alloc(24);
  header.writeUInt32LE(cmd, 0);
  header.writeUInt32LE(arg0, 4);
  header.writeUInt32LE(arg1, 8);
  header.writeUInt32LE(payload.length, 12);
  header.writeUInt32LE(crc32(payload), 16);
  header.writeUInt32LE((cmd ^ 0xffffffff) >>> 0, 20);
  return Buffer.concat([header, payload]);
}

const be32 = (n) => {
  const b = Buffer.alloc(4);
  b.writeUInt32BE(n);
  return b;
};
const sshString = (s) => {
  const b = Buffer.from(s, 'utf8');
  return Buffer.concat([be32(b.length), b]);
};
/** SSH mpint：去前导零，最高位为 1 时补 0x00 防止被当成负数。 */
const mpint = (b) => {
  let i = 0;
  while (i < b.length - 1 && b[i] === 0) i++;
  let x = b.subarray(i);
  if ((x[0] & 0x80) !== 0) x = Buffer.concat([Buffer.from([0]), x]);
  return Buffer.concat([be32(x.length), x]);
};

/** SHA1 的 DigestInfo 前缀（ASN.1 DER），ADB 签名固定用 SHA1。 */
const SHA1_DIGEST_INFO = Buffer.from('3021300906052b0e03021a05000414', 'hex');

// ── 密钥 ─────────────────────────────────────────────────────
function homeDir() {
  return process.env.DSH_HOME ?? path.join(os.homedir(), '.dsh');
}
function keyPath() {
  return path.join(homeDir(), 'adbkey');
}

/**
 * 密钥**来源**的标记文件，内容是 `imported` / `generated` / `paired`。
 *
 * ## 为什么需要它
 *
 * 设置页原来只显示「已导入 / 未导入」，判据是**密钥文件在不在** —— 这是错的：
 * 密钥有两个来源，而其中一个（App 自己生成）在设备看来**尚未被信任**。
 * 两者都显示「已导入」会让用户以为可以用了，实际却拿不到 uid 2000。
 *
 * | 来源 | 设备是否信任 | 用户该做什么 |
 * |---|---|---|
 * | `imported`（从 `/sdcard/dsh_adbkey` 搬进来） | **是**（那是 PC 已授权的密钥） | 直接就能用 |
 * | `generated`（App 现场生成） | **否** | 必须先配对 |
 * | `paired`（生成后又配对成功） | **是** | 直接就能用 |
 *
 * ⚠️ **不能靠 stderr 日志判断**：`importKeyIfNeeded()` 是幂等的，导入日志只在
 * 第一次运行出现，之后再也看不到 —— 用它判断会在第二次探测时误报成「已生成」。
 * 所以来源必须在写入密钥的**那一刻**落盘。
 */
const KEY_ORIGIN_FILE = () => path.join(homeDir(), 'adbkey.origin');

/**
 * 密钥**信任状态**的标记文件：内容是这把密钥公钥指纹的 md5。
 *
 * ## 为什么必须有它（这条是弹窗问题的根治点）
 *
 * ADB 协议里，adbd 只有一条路径会弹「允许 USB 调试吗」：客户端发了
 * `A_AUTH / AUTH_RSA_PUBLIC`（把公钥递给设备求授权）。而原实现是在
 * `handshake()` 里「签名没过就无条件发公钥」—— 于是只要这把密钥没被设备
 * **持久**信任，**每一条命令、每一次连接**都会重弹一次对话框。
 *
 * 用户勾了「一律允许」也没用：TCP 连接下的授权在部分 ROM（实测 OPPO/Android 15）
 * 上不写进持久 keystore，于是下一条命令又是一次全新连接，又弹一次。
 * 用户体感就是「你每执行一次命令我手机就弹一次」。
 *
 * ## 状态机
 *
 * | 状态 | 判据 | 发公钥？ |
 * |---|---|---|
 * | 从未成功过 | 无标记文件 / 指纹不匹配 | **仅当**调用方显式允许（用户主动点「重新检测」或走配对） |
 * | 曾经成功过 | 标记文件里的指纹 == 当前密钥指纹 | **永不**（签名失败即报错回落，不骚扰用户） |
 *
 * ⚠️ 标记里存**指纹**而不是一个裸布尔值：密钥换了（导入 PC 那把、或重新生成）
 * 之后旧标记必须失效，否则新密钥永远拿不到「首次授权」的机会。
 *
 * ⚠️ 指纹算的是**公钥 blob**（与设备 keystore 里存的是同一串），不是私钥文件字节 ——
 * 私钥重新导出（换行符/封装差异）会让字节指纹漂移，公钥不会。
 */
const KEY_TRUST_FILE = () => path.join(homeDir(), 'adbkey.trusted');

/** 公钥 blob 的 md5，用作信任标记的内容。 */
function keyFingerprint(key) {
  return crypto.createHash('md5').update(Buffer.from(key.publicKey, 'base64')).digest('hex');
}

/** 这把密钥是否被设备接受过（曾经握手成功）。 */
function isKeyTrusted(key) {
  try {
    return fs.readFileSync(KEY_TRUST_FILE(), 'utf8').trim() === keyFingerprint(key);
  } catch {
    return false;
  }
}

/** 记下「这把密钥已被设备接受」。写不进去只是下次可能多弹一次，不影响功能。 */
function markKeyTrusted(key) {
  try {
    fs.writeFileSync(KEY_TRUST_FILE(), keyFingerprint(key), { mode: 0o600 });
  } catch {
    // 忽略
  }
}

/** 密钥被替换时必须清掉旧信任标记，否则新密钥拿不到首次授权机会。 */
function clearKeyTrust() {
  try {
    fs.unlinkSync(KEY_TRUST_FILE());
  } catch {
    // 本来就没有
  }
}

/**
 * 记下密钥来源。写不进去不影响功能，只是设置页少一条提示。
 */
function markKeyOrigin(origin) {
  try {
    fs.writeFileSync(KEY_ORIGIN_FILE(), origin, { mode: 0o600 });
  } catch {
    // 忽略
  }
}

/**
 * 读密钥来源；密钥不存在或来源未知时给 `null`。
 * @returns {'imported'|'generated'|'paired'|null}
 */
function keyOrigin() {
  if (!fs.existsSync(keyPath())) return null;
  try {
    const raw = fs.readFileSync(KEY_ORIGIN_FILE(), 'utf8').trim();
    return raw === 'imported' || raw === 'generated' || raw === 'paired' ? raw : null;
  } catch {
    return null;
  }
}

/**
 * 把 `/sdcard/dsh_adbkey` 里的密钥搬进 `$DSH_HOME`。
 *
 * 用户的引导命令是
 * `adb push %USERPROFILE%\.android\adbkey /sdcard/dsh_adbkey`。
 *
 * ## 判据不是「有没有密钥」，而是「在的那把设备信不信」
 *
 * ⚠️ 这里**不能**写成「目标已存在就跳过」。App 自己生成的密钥
 * （`origin = generated`）**设备并不信任** —— 拿它连 adbd 会弹
 * 「是否允许 USB 调试？」，而且用户点「始终允许」**也没用**：那把公钥本来就不在
 * 设备的 `adb_keys` 里，系统每次都当新密钥问一遍。
 *
 * 后果是一个死循环：用户按引导推了 PC 密钥上去，但导入逻辑看到「已有密钥」就
 * 直接返回，App 于是永远拿着自己那把不受信任的密钥去连、永远弹窗、永远点不掉。
 * （真机撞到过，用户反馈「点了很多次始终允许都没效果」。）
 *
 * 所以规则是：**只有 `generated` 允许被顶掉**。
 * `imported` / `paired` 是设备已经信任的密钥，照旧不动。
 *
 * ⚠️ **导入后立刻删掉中转文件。** `/sdcard` 是所有应用都能读的共享目录
 * （本机实测该文件是 `-rw-rw---- root sdcard_rw`），ADB 私钥留在那里
 * 等于把设备控制权敞开放着。实测 app 域**有权**删除它，所以这步是可行的。
 */
function importKeyIfNeeded() {
  const dest = keyPath();
  if (fs.existsSync(dest) && keyOrigin() !== 'generated') return;
  const staged = '/sdcard/dsh_adbkey';
  try {
    if (!fs.existsSync(staged)) return;
    const pem = fs.readFileSync(staged);
    // 先验证能解析再落盘，避免把半个文件写进去后永久卡住导入
    crypto.createPrivateKey(pem);
    if (fs.existsSync(dest)) {
      // 内容一模一样说明目标本来就是 PC 那把（只可能是上次导入后 origin 没写上），
      // 修正来源即可，不必重复写盘。
      let same = false;
      try {
        same = fs.readFileSync(dest).equals(pem);
      } catch {
        // 读不出来就照常覆盖
      }
      if (same) {
        markKeyOrigin('imported');
        return;
      }
      process.stderr.write(`${TAG} 已存在的密钥不受设备信任（generated），改用 PC 推来的那把\n`);
    }
    fs.mkdirSync(path.dirname(dest), { recursive: true });
    fs.writeFileSync(dest, pem, { mode: 0o600 });
    markKeyOrigin('imported');
    // 密钥换了，旧的信任标记随之作废：新密钥需要一次「首次授权」的机会。
    clearKeyTrust();
    process.stderr.write(`${TAG} 已导入 ADB 密钥: ${staged} → ${dest}\n`);
    try {
      fs.unlinkSync(staged);
      process.stderr.write(`${TAG} 已删除中转密钥 ${staged}（共享目录不留私钥）\n`);
    } catch (e) {
      process.stderr.write(`${TAG} ⚠️ 无法删除中转密钥 ${staged}: ${e.message}，请手动删除\n`);
    }
  } catch (e) {
    process.stderr.write(`${TAG} 导入密钥失败: ${e.message}\n`);
  }
}

/**
 * 由私钥导出 ADB 公钥 blob（`ssh-rsa` + e + n 的 SSH 线格式）。
 *
 * ⚠️ **必须现场派生，不要读 `adbkey.pub`**：实测本机那个文件格式不标准
 * （716 字节、缺 `ssh-rsa ` 前缀、内容与私钥不匹配），而设备 keystore 里存的
 * 是公钥 blob。派生才是唯一可靠来源。
 *
 * @param {unknown} priv `crypto.createPrivateKey()` 的产物。
 * @returns {string} base64 的公钥 blob（不带 `ssh-rsa ` 前缀）。
 */
function publicKeyBlob(priv) {
  const jwk = crypto.createPublicKey(priv).export({ format: 'jwk' });
  return Buffer.concat([
    sshString('ssh-rsa'),
    mpint(Buffer.from(jwk.e, 'base64url')),
    mpint(Buffer.from(jwk.n, 'base64url')),
  ]).toString('base64');
}

function loadKey() {
  importKeyIfNeeded();
  const file = keyPath();
  if (!fs.existsSync(file)) return null;
  try {
    const priv = crypto.createPrivateKey(fs.readFileSync(file));
    return { priv, publicKey: publicKeyBlob(priv) };
  } catch (e) {
    process.stderr.write(`${TAG} 解析密钥失败: ${e.message}\n`);
    return null;
  }
}

/**
 * 当前这把密钥**是否有可能**已被设备授权（用于「能不能进内置 adb 那条通道」的守卫）。
 *
 * 与 `isKeyTrusted()` 的区别：这个**不导入、不生成**，纯读盘判断，无副作用，
 * 所以可以在通道选择之前当守卫用。
 *
 * ## 为什么需要这个守卫
 *
 * 内置 adb（通道 2 与 1.5）是**外部进程**，它遇到未授权的密钥时**自己会弹**
 * 「允许 USB 调试吗」，我们拦不住它的 AUTH 行为。所以只能在**进入那条通道之前**
 * 就挡住 —— 宁可回落成 app 身份，也不去弹用户的窗。
 *
 * ## 判据为什么不是「有信任标记」
 *
 * 只认标记会误伤：一把刚从 PC 导入（`imported`）、或刚配对成功（`paired`）的密钥，
 * 在**第一次真正连上之前**是没有标记的 —— 而它恰恰是设备**已经知道**的密钥，
 * 用它连接根本不会弹窗。若因此把内置 adb 挡掉，用户会在「5555 没在听、只有
 * 无线调试可用」时永远拿不到特权通道，那是个死锁。
 *
 * 所以判据是「设备**已经知道**这把密钥」，三条任一条成立即可：
 *
 * | 条件 | 依据 |
 * |---|---|
 * | 信任标记匹配 | 我们亲手握手成功过 |
 * | `origin = imported` | 它来自 PC 的 adbkey，设备 keystore 里本来就有 |
 * | `origin = paired` | 配对时设备把我们的公钥写进了 keystore |
 *
 * 唯一被挡住的是 `origin = generated` 且从未配对成功的密钥 —— 那正是会反复弹窗的那把。
 *
 * @returns {boolean}
 */
function keyMayBeAuthorized() {
  const file = keyPath();
  if (!fs.existsSync(file)) return false;
  const origin = keyOrigin();
  if (origin === 'imported' || origin === 'paired') return true;
  try {
    const priv = crypto.createPrivateKey(fs.readFileSync(file));
    return isKeyTrusted({ publicKey: publicKeyBlob(priv) });
  } catch {
    return false;
  }
}

/**
 * 没有密钥就**自己生成一把**。
 *
 * ## 为什么必须自己生成
 *
 * 「从 PC 导入密钥」那套是 Android 10 的无奈之举（没有无线调试配对，只能复用
 * 一把设备已授权的密钥）。而 Android 11+ 的**配对机制本身就是用来授权新密钥的** ——
 * 用户看到 6 位配对码，输进来，设备就把**我们这把**公钥写进 keystore。
 *
 * 所以正确的免 PC 流程是：**自己生成密钥 → 用配对码授权它**，而不是继续找 PC 要。
 * 不生成的话，配对会因为「没有密钥」直接失败，无线调试又退化成需要 PC 引导。
 *
 * ## 与 PC 导入的关系
 *
 * 顺序是「先导入、后生成」：`/sdcard/dsh_adbkey` 存在时仍优先用它（那是设备
 * **已经信任**的密钥，导入后 legacy 5555 通道立刻可用，不用配对）。只有两处都
 * 没有时才生成 —— 也就是全新的、只开了无线调试的设备。
 *
 * ⚠️ 生成的密钥**在配对之前是不被设备信任的**，所以：
 * - legacy 5555 通道会认证失败（设备 keystore 里没有它）→ 正常回落，不是 bug；
 * - 必须先走一次 `--pair`，之后无线通道才能用。
 *
 * ⚠️ 用 PKCS#8（`-----BEGIN PRIVATE KEY-----`）而不是 PKCS#1：实测设备上那把
 * PC 密钥也是 PKCS#8，且 Node 的 `createPrivateKey` 两者都认。
 *
 * @returns {boolean} 现在是否有一把可用的密钥。
 */
function ensureKey() {
  const file = keyPath();
  // 先把 PC 中转的那把收进来 —— 那是设备**已经信任**的密钥，能直接用，
  // 不该被新生成的顶掉。importKeyIfNeeded 自身幂等（已有就不动）。
  importKeyIfNeeded();
  if (fs.existsSync(file)) return true;
  try {
    const { privateKey } = crypto.generateKeyPairSync('rsa', {
      modulusLength: 2048,
      publicKeyEncoding: { type: 'spki', format: 'pem' },
      privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
    });
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, privateKey, { mode: 0o600 });
    markKeyOrigin('generated');
    process.stderr.write(`${TAG} 已生成新的 ADB 密钥（等待用配对码授权）: ${file}\n`);
    return true;
  } catch (e) {
    process.stderr.write(`${TAG} 生成 ADB 密钥失败: ${e.message}\n`);
    return false;
  }
}

/**
 * ADB 签名：`RSA_NO_PADDING` 下手工构造 PKCS#1 v1.5 块。
 * ⚠️ 摘要槽里放的是 **token 原文**，不是 `sha1(token)`。见文件头注释。
 */
function signToken(token, priv) {
  const size = (crypto.createPublicKey(priv).asymmetricKeyDetails?.modulusLength ?? 2048) / 8;
  const padLen = size - 3 - SHA1_DIGEST_INFO.length - token.length;
  if (padLen < 8) throw new Error(`token 过长，无法装进 ${size} 字节的 RSA 块`);
  const block = Buffer.concat([
    Buffer.from([0x00, 0x01]),
    Buffer.alloc(padLen, 0xff),
    Buffer.from([0x00]),
    SHA1_DIGEST_INFO,
    token,
  ]);
  return crypto.privateEncrypt({ key: priv, padding: crypto.constants.RSA_NO_PADDING }, block);
}

// ── 连接 ─────────────────────────────────────────────────────
/**
 * 建立一条已认证的 ADB 连接。失败抛异常（调用方负责回落）。
 *
 * ⚠️ 这条路径**永不**向设备递公钥，所以**永不弹**「允许 USB 调试吗」。
 * 密钥的授权只能来自「无线调试配对」或「PC 推入已授权密钥」，见 `handshake()`。
 *
 * @returns {Promise<AdbConn>}
 */
async function connect() {
  const key = loadKey();
  if (key === null) throw new Error('未找到 ADB 密钥（先用 PC 执行 adb push 导入）');

  const sock = await new Promise((resolve, reject) => {
    const s = net.connect({ host: HOST, port: PORT });
    const timer = setTimeout(() => {
      s.destroy();
      reject(new Error(`连接 ${HOST}:${PORT} 超时`));
    }, CONNECT_TIMEOUT_MS);
    s.once('connect', () => {
      clearTimeout(timer);
      resolve(s);
    });
    s.once('error', (e) => {
      clearTimeout(timer);
      reject(e);
    });
  });

  const conn = new AdbConn(sock);
  await conn.handshake(key);
  return conn;
}

class AdbConn {
  #sock;
  #buf = Buffer.alloc(0);
  #queue = [];
  #waiter = null;
  #closed = false;
  /** 每个本地 stream id 一个消息队列，ADB 允许同一连接上并发多个 stream。 */
  #streams = new Map();

  constructor(sock) {
    this.#sock = sock;
    sock.on('data', (d) => this.#feed(d));
    sock.on('error', () => this.#die());
    sock.on('close', () => this.#die());
  }

  #die() {
    this.#closed = true;
    for (const q of this.#streams.values()) q.fail(new Error('ADB 连接已关闭'));
    this.#streams.clear();
    if (this.#waiter !== null) {
      const w = this.#waiter;
      this.#waiter = null;
      w.fail(new Error('ADB 连接已关闭'));
    }
  }

  #feed(chunk) {
    this.#buf = Buffer.concat([this.#buf, chunk]);
    while (this.#buf.length >= 24) {
      const cmd = this.#buf.readUInt32LE(0);
      const arg0 = this.#buf.readUInt32LE(4);
      const arg1 = this.#buf.readUInt32LE(8);
      const len = this.#buf.readUInt32LE(12);
      if (this.#buf.length < 24 + len) break;
      const data = this.#buf.subarray(24, 24 + len);
      this.#buf = this.#buf.subarray(24 + len);

      const stream = this.#streams.get(arg1);
      if (stream !== undefined && (cmd === CMD.WRTE || cmd === CMD.CLSE || cmd === CMD.OKAY)) {
        stream.push({ cmd, arg0, arg1, data });
      } else if (this.#waiter !== null) {
        const w = this.#waiter;
        this.#waiter = null;
        w.push({ cmd, arg0, arg1, data });
      }
    }
  }

  /** 读一条非 stream 报文（握手/OPEN 应答）。 */
  recv(timeoutMs) {
    return new Promise((resolve, reject) => {
      if (this.#closed) return reject(new Error('ADB 连接已关闭'));
      if (this.#queue.length > 0) return resolve(this.#queue.shift());
      const timer = setTimeout(() => {
        if (this.#waiter === entry) this.#waiter = null;
        reject(new Error(`等待 ADB 报文超时（${timeoutMs}ms）`));
      }, timeoutMs);
      const entry = {
        push: (m) => {
          clearTimeout(timer);
          resolve(m);
        },
        fail: (e) => {
          clearTimeout(timer);
          reject(e);
        },
      };
      this.#waiter = entry;
    });
  }

  send(cmd, arg0, arg1, data) {
    if (this.#closed) throw new Error('ADB 连接已关闭');
    this.#sock.write(pack(cmd, arg0, arg1, data));
  }

  /**
   * 握手 + 认证。
   *
   * ## ⚠️ 这里曾经是「每执行一条命令手机就弹一次授权窗」的根因
   *
   * 原实现：签名验证失败 → 发 `AUTH_RSA_PUBLIC`（把公钥递给设备求授权）。
   * 而 adbd 只有收到公钥才会弹「允许 USB 调试吗」，于是密钥只要不被设备
   * **持久**信任，每一次连接（= 每一条命令、每次探测）都会弹一次。
   *
   * ## 为什么是**删掉**而不是「降低触发频率」
   *
   * 递公钥在**本 App 的全部场景下都是无效动作**，两类设备都实测过：
   *
   * | 设备 | 递公钥之后 |
   * |---|---|
   * | Android 10（TCP 连接） | adbd **不弹对话框**，静默挂起，永远等不到授权（20s 超时无任何响应） |
   * | Android 15（TCP 连接） | 弹窗，但点「一律允许」**不写进持久 keystore**，下一条命令又弹 |
   *
   * 也就是说它要么什么都不做，要么只制造骚扰。所以**直接不发**：签名失败就报错，
   * 让调用方回落成 app 身份。
   *
   * ## 密钥怎么获得信任（两条路都不弹窗）
   *
   * 1. **Android 11+**：设置页走「无线调试配对」，输 6 位配对码 —— 设备把我们的
   *    公钥写进 keystore，这正是配对机制的本来用途。见 `--pair` 分支。
   * 2. **Android 10**：PC 上 `adb push %USERPROFILE%\.android\adbkey /sdcard/dsh_adbkey`，
   *    那是**设备已经信任**的密钥，导入即可用。见 `importKeyIfNeeded()`。
   *
   * @param {{priv: unknown, publicKey: string}} key
   */
  async handshake(key) {
    this.send(CMD.CNXN, 0x01000001, MAX_PAYLOAD, Buffer.from('host::features=shell_v2,cmd,stat_v2\0'));
    let msg = await this.recv(HANDSHAKE_TIMEOUT_MS);

    if (msg.cmd === CMD.AUTH && msg.arg0 === AUTH_TOKEN) {
      this.send(CMD.AUTH, AUTH_SIGNATURE, 0, signToken(msg.data, key.priv));
      msg = await this.recv(HANDSHAKE_TIMEOUT_MS);
      if (msg.cmd === CMD.AUTH) {
        // ⚠️ 这里**故意不发公钥**。原因见上面的表 —— 它在本 App 的场景下要么
        // 无效、要么只制造弹窗，是纯粹的负收益。要授权请走配对或 PC 导入。
        throw new Error(
          isKeyTrusted(key)
            ? 'ADB 密钥被设备拒绝（授权可能已被撤销），请重新配对'
            : 'ADB 密钥尚未被设备授权，请用无线调试配对授权它'
              + '（Android 10 需先用 PC 执行 adb push 推入已授权的密钥）',
        );
      }
    }

    if (msg.cmd !== CMD.CNXN) {
      throw new Error(`ADB 认证失败（收到 ${CMD_NAME(msg.cmd)} arg0=${msg.arg0}）`);
    }
    // 走到这里说明设备认了这把密钥。落盘标记，此后永不因它弹窗。
    markKeyTrusted(key);
  }

  /**
   * 用 shell v2 协议执行一条命令，拿回分开的 stdout / stderr / 退出码。
   * @returns {Promise<{stdout: Buffer, stderr: Buffer, exitCode: number}>}
   */
  async shell(command, { timeoutMs } = {}) {
    const localId = this.#nextId();
    const q = new StreamQueue();
    this.#streams.set(localId, q);

    try {
      // raw: 表示不做 PTY 分配，命令原文直传；v2 才带 EXIT 包
      this.send(CMD.OPEN, localId, 0, Buffer.from(`shell,v2,raw:${command}\0`));
      const opened = await q.next(timeoutMs ?? 15000);
      if (opened.cmd !== CMD.OKAY) {
        throw new Error(`adbd 拒绝打开 shell 流（${CMD_NAME(opened.cmd)}）`);
      }
      const remoteId = opened.arg0;

      const stdout = [];
      const stderr = [];
      let exitCode = 0;
      let pending = Buffer.alloc(0);

      // 一个 WRTE 里可能装多个 v2 包，也可能只装半个 —— 按流式解析，跨报文续上
      const consume = (chunk) => {
        pending = Buffer.concat([pending, chunk]);
        for (;;) {
          if (pending.length < 5) break;
          const id = pending[0];
          const len = pending.readUInt32LE(1);
          if (pending.length < 5 + len) break;
          const payload = pending.subarray(5, 5 + len);
          pending = pending.subarray(5 + len);
          if (id === SHELL_STDOUT) stdout.push(payload);
          else if (id === SHELL_STDERR) stderr.push(payload);
          else if (id === SHELL_EXIT) exitCode = payload.length > 0 ? payload[0] : 0;
          else if (id === SHELL_CLOSE_STDIN) { /* 无需回写 stdin */ }
        }
      };

      let sawClose = false;
      for (;;) {
        if (sawClose && q.isEmpty) break;
        const m = sawClose ? q.tryNext() : await q.next(timeoutMs ?? 0);        if (m === null) break;
        if (m.cmd === CMD.CLSE) {
          sawClose = true;
          continue;
        }
        if (m.cmd !== CMD.WRTE) continue;

        consume(m.data);
        // ⚠️ 必须回 OKAY：ADB 是窗口式流控，读完一个 WRTE 不回执，adbd 会**停在那里
        // 不再发下一个包**（表现为命令挂住、永远等不到 CLSE）。Dadb 的
        // AdbStreamImpl.read() 也是读完即 `writeOkay(localId, remoteId)`。
        this.send(CMD.OKAY, localId, remoteId, Buffer.alloc(0));
      }

      // 回执 CLSE，让 adbd 释放这条流
      try {
        this.send(CMD.CLSE, localId, remoteId, Buffer.alloc(0));
      } catch { /* 连接可能已经断了，忽略 */ }

      return {
        stdout: Buffer.concat(stdout),
        stderr: Buffer.concat(stderr),
        exitCode,
      };
    } finally {
      this.#streams.delete(localId);
    }
  }

  #nextIdCounter = 0;
  /**
   * 与 AOSP 一样顺序递增。**不要改成随机 id**：随机 id 可能与在用的 id 撞车，
   * 撞车后先关闭的那条流会把另一条活流的队列一起拆掉（AOSP 的注释专门记了这条）。
   */
  #nextId() {
    return ++this.#nextIdCounter;
  }

  close() {
    try {
      this.#sock.destroy();
    } catch { /* ignore */ }
  }
}

/** 单条 stream 的报文队列。 */
class StreamQueue {
  #items = [];
  #waiter = null;
  #error = null;

  get isEmpty() {
    return this.#items.length === 0;
  }

  push(item) {
    if (this.#waiter !== null) {
      const w = this.#waiter;
      this.#waiter = null;
      w.resolve(item);
    } else {
      this.#items.push(item);
    }
  }

  fail(err) {
    this.#error = err;
    if (this.#waiter !== null) {
      const w = this.#waiter;
      this.#waiter = null;
      w.reject(err);
    }
  }

  /** 只取已有报文，没有就返回 null（用于 CLSE 之后的收尾排空）。 */
  tryNext() {
    return this.#items.length > 0 ? this.#items.shift() : null;
  }

  /** timeoutMs 为 0 表示不设超时（等命令自然结束）。 */
  next(timeoutMs) {
    if (this.#items.length > 0) return Promise.resolve(this.#items.shift());
    if (this.#error !== null) return Promise.reject(this.#error);
    return new Promise((resolve, reject) => {
      let timer = null;
      if (timeoutMs > 0) {
        timer = setTimeout(() => {
          if (this.#waiter === entry) this.#waiter = null;
          reject(new Error(`等待命令输出超时（${timeoutMs}ms）`));
        }, timeoutMs);
      }
      const entry = {
        resolve: (v) => {
          if (timer !== null) clearTimeout(timer);
          resolve(v);
        },
        reject: (e) => {
          if (timer !== null) clearTimeout(timer);
          reject(e);
        },
      };
      this.#waiter = entry;
    });
  }
}

/**
 * 把命令包成「先切到 cwd，再执行」。
 *
 * ⚠️ **cwd 必须显式传递，而且允许失败。** adbd 执行 shell 命令时的 cwd 恒为 `/`，
 * 而引擎会把工作目录设成 workspace（`/data/user/0/com.dsh.android/files/workspace`），
 * 那个目录是 `drwx------`，**uid 2000 进不去**（实测 Permission denied）。
 *
 * 所以这里用 `cd … 2>/dev/null; <command>` 而不是 `cd … && <command>`：
 * 切不过去就留在 `/` 继续跑，而不是让整条命令失败。
 *
 * 这个降级是**有意**的 —— 特权命令本就不该依赖工作区里的相对路径
 * （uid 2000 读不到工作区，相对路径对它没有意义）。而如果用 `&&`，
 * 那么「在工作区里跑 `screencap`」这种完全合理的用法会直接失败。
 */
function withCwd(command, cwd) {
  if (typeof cwd !== 'string' || cwd === '' || cwd === '/') return command;
  // 单引号包裹 + 内部单引号转义，避免路径里的空格/特殊字符破坏命令
  const quoted = `'${cwd.replaceAll("'", `'\\''`)}'`;
  return `cd ${quoted} 2>/dev/null; ${command}`;
}

// ── 无线调试通道（经内置 adb 二进制）────────────────────────
/**
 * 读 Kotlin 侧（`AdbMdns`）写下的端口文件。
 *
 * 格式 `<connect端口> [<配对端口>]`，任一项可能缺失。
 *
 * ⚠️ 这里**不做任何端口探测**（不 connect、不 bind、不扫）。有效性由写入方
 * 负责 —— `AdbMdns` 已经用「bind 127.0.0.1:port 失败即 adbd 在听」筛过一遍，
 * 并且校验了解析出的地址必须是本机地址。重复校验只是白花时间。
 *
 * @returns {{connect: number|null, pairing: number|null}} 解析结果。
 */
function readWirelessPorts() {
  try {
    const raw = fs.readFileSync(PORT_CACHE_FILE(), 'utf8').trim();
    if (raw === '') return { connect: null, pairing: null };
    const [c, p] = raw.split(/\s+/);
    const connect = Number.parseInt(c, 10);
    const pairing = Number.parseInt(p, 10);
    return {
      connect: Number.isInteger(connect) && connect > 0 ? connect : null,
      pairing: Number.isInteger(pairing) && pairing > 0 ? pairing : null,
    };
  } catch {
    // 文件不存在 = 无线调试没开或还没发现，属于正常情况
    return { connect: null, pairing: null };
  }
}

/**
 * 用内置 adb 二进制跑一条命令。
 *
 * 命令**不经 shell 二次转义**：`adb shell <cmd>` 的参数是逐个传给 adb 的，
 * adb 会把它们拼成一条命令交给设备端 shell，所以这里直接把原命令作为一个
 * argv 传进去，与用户敲 `adb shell '...'` 等价（实测退出码 42/0/5 透传正常，
 * 单引号、中文、`&&`、管道全部正确）。
 *
 * @param {string} adbBin - adb 可执行文件路径。
 * @param {string} target - `127.0.0.1:<port>`。
 * @param {string} command - 要执行的命令。
 * @returns {Promise<{stdout: string, stderr: string, code: number}>} 执行结果。
 */
/**
 * 内置 adb 的运行环境。
 *
 * ⚠️ 三个变量缺一不可，都是踩过的坑：
 * - `HOME`：adb 在 `$HOME/.android/adbkey` 找密钥。指向别处会**静默自生成**一把新密钥。
 * - `LD_LIBRARY_PATH`：adb 是 Termux 的 ELF，要它的 `.so`。**只对 adb 需要** ——
 *   引擎进程绝不能设它（会毒化系统二进制，见 AGENTS.md）。
 * - `TMPDIR`：adb 硬编码往 `/tmp/adb.<pid>.log` 写启动日志，app 域无权写 `/tmp`
 *   （实测 `cannot open /tmp/adb.10535.log: Permission denied` → server 起不来）。
 */
function adbEnv() {
  return {
    ...process.env,
    HOME: path.join(ADB_HOME, 'home'),
    LD_LIBRARY_PATH: path.join(ADB_HOME, 'lib'),
    TMPDIR: path.join(ADB_HOME, 'tmp'),
  };
}

/** 跑一次内置 adb，收集 stdout/stderr/退出码。 */
function adbExec(args) {
  return new Promise((resolve) => {
    const env = adbEnv();
    try {
      fs.mkdirSync(env.TMPDIR, { recursive: true });
    } catch {
      // 已存在
    }
    const child = spawn(ADB_BIN, ['-L', `localabstract:dshadb-${process.pid}`, ...args], {
      stdio: ['ignore', 'pipe', 'pipe'],
      env,
    });
    let out = '';
    let err = '';
    child.stdout.setEncoding('utf8');
    child.stderr.setEncoding('utf8');
    child.stdout.on('data', (c) => { out += c; });
    child.stderr.on('data', (c) => { err += c; });
    child.on('error', (e) => resolve({ code: 127, out, err: `${err}${e.message}` }));
    child.on('close', (code) => resolve({ code: code ?? 1, out, err }));
  });
}

/**
 * 用内置 adb 二进制跑一条命令。
 *
 * 命令**不经 shell 二次转义**：`adb shell <cmd>` 的参数是逐个传给 adb 的，
 * adb 会把它们拼成一条命令交给设备端 shell，所以这里直接把原命令作为一个
 * argv 传进去，与用户敲 `adb shell '...'` 等价（实测退出码 42/0/5 透传正常，
 * 单引号、中文、`&&`、管道全部正确）。
 *
 * @param {string} adbBin - adb 可执行文件路径（保留形参以兼容调用点）。
 * @param {string} target - `127.0.0.1:<port>`。
 * @param {string} command - 要执行的命令。
 * @returns {Promise<{stdout: string, stderr: string, code: number}>} 执行结果。
 */
function runWireless(adbBin, target, command) {
  return (async () => {
    try {
      await adbExec(['start-server']);
      const conn = await adbExec(['connect', target]);
      if (!conn.out.includes('connected')) {
        return { stdout: '', stderr: `${TAG} 无线调试连接失败: ${(conn.out + conn.err).trim()}\n`, code: 1 };
      }
      const r = await adbExec(['-s', target, 'shell', command]);
      // adb 把远端 stderr 并进自己的 stderr，stdout/stderr 天然分开
      return { stdout: r.out, stderr: r.err, code: r.code };
    } catch (e) {
      return { stdout: '', stderr: `${TAG} 无线调试执行异常: ${e.message}\n`, code: 1 };
    }
  })();
}

// ── 回落：本地执行（app 域）─────────────────────────────────
function runLocal(command) {
  const shell = process.env.PREFIX ? path.join(process.env.PREFIX, 'bin', 'bash') : 'bash';
  return new Promise((resolve) => {
    const child = spawn(shell, ['-c', command], {
      stdio: ['ignore', 'inherit', 'inherit'],
      env: process.env,
    });
    child.on('error', (e) => {
      process.stderr.write(`${TAG} 本地执行失败: ${e.message}\n`);
      resolve(127);
    });
    child.on('exit', (code, signal) => resolve(code ?? (signal !== null ? 128 : 1)));
  });
}

// ── 入口 ─────────────────────────────────────────────────────
/**
 * 写出全部数据并等它真正 flush 完，再设定退出码。
 *
 * ⚠️ **不要用 `process.exit(code)` 收尾。** 输出走管道时 `process.exit()` 会
 * 在 libc 缓冲区写回之前就终止进程，**静默截断**大输出 —— `screencap`、
 * `dumpsys`、`ps -A` 这类恰好都是大输出。用 `process.exitCode` 让事件循环
 * 自然退出，Node 会把流排干。
 */
function finish(stdout, stderr, code) {
  const write = (stream, buf) =>
    new Promise((resolve) => {
      if (buf.length === 0) return resolve();
      stream.write(buf, () => resolve());
    });
  Promise.all([write(process.stdout, stdout), write(process.stderr, stderr)]).then(() => {
    process.exitCode = code;
  });
}

/**
 * 让内置 adb 用同一把密钥：把 `$DSH_HOME/adbkey` 放到它期望的
 * `$HOME/.android/adbkey`。
 *
 * ⚠️ 两边必须是**同一把**。设备 keystore 里记的是 PC 那把公钥，换一把就认证不过。
 * 同时删掉 `.pub`：让 adb 从私钥现场派生，避免用上不匹配的公钥（PC 上那个
 * `adbkey.pub` 就与私钥不一致，是踩过的坑）。
 * @returns {boolean} 密钥是否就位。
 */
function syncKeyForAdb() {
  const src = keyPath();
  if (!fs.existsSync(src)) return false;
  const dir = path.join(ADB_HOME, 'home', '.android');
  const dest = path.join(dir, 'adbkey');
  try {
    fs.mkdirSync(dir, { recursive: true });
    const want = fs.readFileSync(src);
    // 内容不同才写，省掉每条命令一次无谓的磁盘写
    let same = false;
    try {
      same = fs.readFileSync(dest).equals(want);
    } catch {
      same = false;
    }
    if (!same) {
      fs.writeFileSync(dest, want, { mode: 0o600 });
      fs.rmSync(`${dest}.pub`, { force: true });
    }
    return true;
  } catch (e) {
    process.stderr.write(`${TAG} 同步 adb 密钥失败: ${e.message}\n`);
    return false;
  }
}

/**
 * 无线调试的**配对**（Android 11+）。
 *
 * 配对走 SPAKE2 + TLS-PSK，Node 的 `tls` 实现不了，所以借内置 adb 二进制的
 * `adb pair`。配对成功后设备 keystore 就记下了我们的公钥，之后 `connect` 免配对。
 *
 * @param {string} port - 配对端口（设备「使用配对码配对设备」界面上显示的端口）。
 * @param {string} code - 6 位配对码。
 * @returns {Promise<number>} 退出码。
 */
function pairWireless(port, code) {
  // 配对输出要实时给用户看（Kotlin 侧读出来打日志），所以这里不用 adbExec 的
  // 一次性收集，而是 inherit 出去。
  return new Promise((resolve) => {
    const env = adbEnv();
    try {
      fs.mkdirSync(env.TMPDIR, { recursive: true });
    } catch {
      // 已存在
    }
    const child = spawn(ADB_BIN, ['-L', `localabstract:dshadb-${process.pid}`, 'pair', `${HOST}:${port}`, code], {
      stdio: ['ignore', 'inherit', 'inherit'],
      env,
    });
    child.on('error', (e) => {
      process.stderr.write(`${TAG} 启动 adb 失败: ${e.message}\n`);
      resolve(127);
    });
    child.on('exit', (c) => resolve(c ?? 1));
  });
}

/**
 * 把 adbd 从「无线调试」切到固定的 legacy TCP 端口（默认 5555）。
 *
 * ## 为什么需要这一步（实测结论）
 *
 * 无线调试的 TLS 端口**只在无线调试开关开着时才存在**，而且**关掉开关会立刻
 * 掐断已建立的连接**（实测：`settings put global adb_wifi_enabled 0` 之后，
 * 已连上的 `adb-…._adb-tls-connect._tcp` 立刻变 `device offline`）。
 * 也就是说「连上后一直用无线调试」这条路是死的 —— 用户一关开关，通道就断。
 *
 * 而 `adb tcpip 5555` 会把 adbd **重启到 TCP 模式**，此后：
 *
 * | | 无线调试 | tcpip 5555 |
 * |---|---|---|
 * | 关掉无线调试开关 | **立刻断连** | **不受影响**（实测 `wifi=0` 时仍 `LISTEN *:5555`） |
 * | 关掉 Wi-Fi | 断 | **不受影响**（走 127.0.0.1 回环） |
 * | 端口 | 每次重启随机 | 固定 5555 |
 * | 加密 | TLS | 明文（但只在回环上，不出设备） |
 * | 重启手机后 | 需重开开关 | **需重新切一次**（`persist.adb.tcp.port` 实测为空） |
 *
 * ⚠️ **两者可以并存**：Android 15 实测 `adb tcpip 5555` **不会**关闭无线调试
 * （`adb_wifi_enabled` 仍是 1）。所以这是一个纯增益动作，不会破坏用户的设置。
 *
 * ⚠️ 切之前**必须先有一条活着的连接**（要么 legacy 已在听，要么无线调试连着），
 * 因为 `tcpip` 命令本身要通过 adb 发出去。
 *
 * @returns {Promise<boolean>} 是否切换成功。
 */
async function switchToTcp() {
  if (ADB_BIN === '' || !fs.existsSync(ADB_BIN)) return false;
  // 先用**已经可用**的通道连一次，把 tcpip 命令发出去。
  // 优先 legacy（已经在听说明已经是 TCP 模式，无需再切）；否则走无线调试。
  const already = await legacyAlive();
  if (already) return true;

  const { connect: port } = readWirelessPorts();
  if (port === null) {
    process.stderr.write(`${TAG} 无法切 TCP 模式：没有可用的无线调试端口\n`);
    return false;
  }
  const rc = await adbExec(['-s', `${HOST}:${port}`, 'tcpip', String(PORT)]);
  if (rc.code !== 0) {
    process.stderr.write(`${TAG} 切换 TCP 模式失败: ${(rc.out + rc.err).trim()}\n`);
    return false;
  }
  // adbd 重启需要一点时间才重新监听
  for (let i = 0; i < 20; i += 1) {
    await new Promise((r) => setTimeout(r, 300));
    if (await legacyAlive()) {
      process.stderr.write(`${TAG} 已切到 TCP 模式 127.0.0.1:${PORT}（不再依赖无线调试开关）\n`);
      return true;
    }
  }
  process.stderr.write(`${TAG} 切换 TCP 模式后 ${PORT} 端口未就绪\n`);
  return false;
}

/** legacy 端口是否有人监听（能连上即视为就绪）。 */
function legacyAlive() {
  return new Promise((resolve) => {
    const sock = net.connect({ host: HOST, port: PORT });
    const done = (ok) => {
      sock.removeAllListeners();
      sock.destroy();
      resolve(ok);
    };
    sock.setTimeout(1000);
    sock.on('connect', () => done(true));
    sock.on('error', () => done(false));
    sock.on('timeout', () => done(false));
  });
}

async function main() {
  const argv = process.argv.slice(2);

  // ── 子命令：配对 ──────────────────────────────────────────
  // 端口由 Kotlin 侧（AdbMdns 发现 `_adb-tls-pairing._tcp`）通过参数传入。
  if (argv[0] === '--pair') {
    const port = argv[1];
    const code = argv[2];
    if (port === undefined || code === undefined) {
      process.stderr.write(`${TAG} 用法: node dsh-android-shell.mjs --pair <port> <code>\n`);
      process.exitCode = 2;
      return;
    }
    if (ADB_BIN === '' || !fs.existsSync(ADB_BIN)) {
      process.stderr.write(`${TAG} 内置 adb 不可用，无法配对\n`);
      process.exitCode = 1;
      return;
    }
    // 没有密钥就自己生成一把 —— 配对的目的正是**授权一把新密钥**，
    // 而不再需要 PC。详见 ensureKey()。
    if (!ensureKey() || !syncKeyForAdb()) {
      process.stderr.write(`${TAG} ADB 密钥不可用，无法配对\n`);
      process.exitCode = 1;
      return;
    }
    const rc = await pairWireless(port, code);
    if (rc === 0) {
      // 配对成功 = 设备 keystore 里已经存了我们的公钥，这把密钥从此**被信任**。
      // 把来源升级掉，否则设置页会一直显示「待配对」的黄点（判据见 keyOrigin）。
      markKeyOrigin('paired');
      // 同步把「信任」标记落盘。配对成功意味着设备已经收下了这把公钥，此后
      // legacy 通道的签名认证必然通过 —— 若不记，第一次 legacy 连接万一因别的原因
      // 签名失败，就会去发公钥、白弹一次窗（那正是要根治的行为）。
      const paired = loadKey();
      if (paired !== null) markKeyTrusted(paired);
      // 立刻切到固定的 TCP 端口 —— 配对只给了我们「能连无线调试」的权利，而
      // 无线调试的端口**一关开关就没了**（实测断连）。切成 tcpip 之后通道就
      // 不再依赖那个开关，也不会随重启换端口。详见 switchToTcp()。
      await switchToTcp();
    }
    process.exitCode = rc;
    return;
  }

  // ── 子命令：切到固定 TCP 端口（供设置页按钮 / 配对后自动调用）──
  if (argv[0] === '--switch-tcp') {
    if (!ensureKey() || !syncKeyForAdb()) {
      process.stderr.write(`${TAG} ADB 密钥不可用，无法切换\n`);
      process.exitCode = 1;
      return;
    }
    // ⚠️ 必须先起 server 并 connect 一次 —— `switchToTcp()` 发的是
    // `adb -s 127.0.0.1:<port> tcpip 5555`，没有这条连接时 adb 会直接报
    // 「device not found」，切换必然失败。通道 1.5 里也是这个顺序。
    const { connect: wlPort } = readWirelessPorts();
    if (wlPort !== null) {
      await adbExec(['start-server']);
      await adbExec(['connect', `${HOST}:${wlPort}`]);
    }
    process.exitCode = (await switchToTcp()) ? 0 : 1;
    return;
  }

  // ── 子命令：只报告无线调试状态（供设置页显示）──────────────
  if (argv[0] === '--discover') {
    const adbOk = ADB_BIN !== '' && fs.existsSync(ADB_BIN);
    // ⚠️ 这里**只查密钥文件在不在**，不生成：设置页每刷新一次就凭空造一把密钥
    // 是意外副作用。生成留给真正的执行路径与 `--pair`。
    const keyOk = fs.existsSync(keyPath());
    // 端口由 Kotlin 侧发现后写入，这里只读文件，不做任何探测
    const ports = readWirelessPorts();
    process.stdout.write(`${JSON.stringify({
      adb: adbOk,
      key: keyOk,
      keyOrigin: keyOrigin(),
      connect: ports.connect,
      pairing: ports.pairing,
    })}\n`);
    process.exitCode = 0;
    return;
  }

  const command = argv[0];
  if (command === undefined || command === '') {
    process.stderr.write(`${TAG} 用法: node dsh-android-shell.mjs <command> [cwd]\n`);
    process.exitCode = 2;
    return;
  }
  const cwd = argv[1];
  const wrapped = withCwd(command, cwd);

  // 先保证有密钥：可能来自 PC 中转（设备已信任），也可能现场生成（等待配对授权）。
  // 放在通道尝试**之前**，这样两条通道用的是同一把，诊断信息也准确。
  const hasKey = ensureKey();

  // ── 通道 1：legacy 5555（自实现协议，最快，Android 10 唯一可用）──
  // 先试它是因为成本极低：端口没监听时是立刻 ECONNREFUSED，不拖慢。
  let legacyReason = hasKey ? '' : '没有 ADB 密钥';
  let conn = null;
  try {
    conn = await connect();
  } catch (e) {
    legacyReason = e.message;
  }
  if (conn !== null) {
    try {
      const { stdout, stderr, exitCode } = await conn.shell(wrapped);
      conn.close();
      process.stderr.write(`${TAG} 通道: legacy 127.0.0.1:${PORT}\n`);
      finish(stdout, stderr, exitCode);
      return;
    } catch (e) {
      conn.close();
      legacyReason = `执行失败: ${e.message}`;
    }
  }

  // ── 通道 1.5：legacy 不通但无线调试可用 → 切到 TCP 模式再走 legacy ──
  //
  // 这一步让「重启后 5555 消失」自动恢复：只要无线调试还开着，就顺手切回
  // 固定的 5555，之后连无线调试开关都不用管了。稳态下（5555 已在听）
  // 通道 1 已经返回，走不到这里，所以没有额外开销。
  //
  // ⚠️ 同样要 `keyMayBeAuthorized()` 守卫：这里用内置 adb 去 `connect` 无线调试
  // 端口，密钥未授权时**内置 adb 自己会弹授权窗**，拦不住，只能提前不进这条分支。
  if (conn === null && hasKey && ADB_BIN !== '' && fs.existsSync(ADB_BIN) && keyMayBeAuthorized()) {
    const { connect: wlPort } = readWirelessPorts();
    if (wlPort !== null) {
      await adbExec(['start-server']);
      await adbExec(['connect', `${HOST}:${wlPort}`]);
      if (await switchToTcp()) {
        try {
          conn = await connect();
        } catch (e) {
          legacyReason = `切 TCP 后仍连不上: ${e.message}`;
        }
        if (conn !== null) {
          try {
            const { stdout, stderr, exitCode } = await conn.shell(wrapped);
            conn.close();
            process.stderr.write(`${TAG} 通道: legacy 127.0.0.1:${PORT}（自动切自无线调试）\n`);
            finish(stdout, stderr, exitCode);
            return;
          } catch (e) {
            conn.close();
            legacyReason = `执行失败: ${e.message}`;
          }
        }
      }
    }
  }

  // ── 通道 2：无线调试（内置 adb + NsdManager 发现的 TLS 端口）──
  // ⚠️ 守卫：内置 adb 遇到未授权的密钥**自己也会弹「允许 USB 调试吗」**，而它
  // 是外部进程，我们拦不住它的 AUTH 行为。所以只能在进这条通道**之前**就挡住 ——
  // 密钥没被接受过就干脆不试，宁可回落成 app 身份，也不去弹用户的窗。
  if (hasKey && ADB_BIN !== '' && fs.existsSync(ADB_BIN) && keyMayBeAuthorized() && syncKeyForAdb()) {
    const { connect: port } = readWirelessPorts();
    if (port !== null) {
      const target = `${HOST}:${port}`;
      const r = await runWireless(ADB_BIN, target, wrapped);
      // 连不上就当作这条通道不可用，继续往下回落
      if (r.code !== 1 || !r.stderr.includes('无线调试连接失败')) {
        process.stderr.write(`${TAG} 通道: 无线调试 ${target}\n`);
        finish(r.stdout, r.stderr, r.code);
        return;
      }
      process.stderr.write(`${TAG} 无线调试端口 ${port} 连不上，继续回落\n`);
    }
  }

  // ── 通道 3：本地执行（app 身份）────────────────────────────
  const why = legacyReason === '' ? '没有可用的 uid 2000 通道' : legacyReason;
  process.stderr.write(`${TAG} uid 2000 不可用（${why}）→ 回落为本地执行\n`);
  process.exitCode = await runLocal(command);
}

main().catch((e) => {
  process.stderr.write(`${TAG} 未捕获异常: ${e?.stack ?? e}\n`);
  process.exitCode = 1;
});
