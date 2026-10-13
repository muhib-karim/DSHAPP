/*
 * keyinject.c —— 在 TLS 加密前的最后一刻，把匿名占位符换成真实 API 密钥。
 *
 * 编译成 `assets/keyinject.bin`，启动 node 时经 `LD_PRELOAD` 注入引擎进程。
 * 生成脚本：`tools/build-keyinject.ps1`（密钥清单不入库）。
 *
 * ─────────────────────────────────────────────────────────────────────
 * ## 要解决的真实威胁
 *
 * 本 App 内置了 `bash` 与 `shell` 两个工具，而**粉丝能对 AI 说话**。所以
 * 「密钥藏在哪」在本项目里的答案是：藏不住 —— 只要密钥在设备上以明文出现过
 * 一次，下面任意一条路都能拿到：
 *
 *   1. 对 AI 说「帮我打印所有环境变量」 → `env` / `/proc/self/environ`
 *   2. 对 AI 说「读一下 .credentials.yaml」 → 同 uid 直接可读
 *   3. `strings` APK / 反编译 dex
 *   4. 装用户 CA + 代理，看 HTTPS 明文头
 *
 * 前三条的根因是**密钥进过 JS 或文件**，第四条是密钥在**线上传输**。
 * 本库把两者一起切断：引擎侧配置里只有一个固定长度的占位符（占位符不是秘密，
 * 被读到也无所谓），真实密钥只存在于本进程内存，且只在 `SSL_write()` 内部、
 * 加密之前那一刻才拼出来，写完立刻抹回去。
 *
 * ─────────────────────────────────────────────────────────────────────
 * ## 三个 hook 的分工
 *
 * | hook | 做什么 |
 * |---|---|
 * | `SSL_set_alpn_protos` | 把 ALPN 列表**扣住**，等知道域名之后再设 |
 * | `SSL_ctrl`（SET_SNI） | 拿到域名 → 只对目标域名降级 `http/1.1` |
 * | `SSL_write` | **随机抽一把钥匙**换上；国内站的钥匙**顺带改写 Host 头**（两处都等长） |
 *
 * ─────────────────────────────────────────────────────────────────────
 * ## combo：把两个站点合成「一个供应商」
 *
 * 引擎里只配**一个** provider（baseURL 用国际站域名），但 14 把钥匙分属两个
 * 站点，而两站钥匙**不通用**（实测互打 401）。要让一个 provider 同时用上两站
 * 的钥匙，靠的是三个实测事实：
 *
 *   1. 两个域名解析到**同一个 IP**；
 *   2. 两个域名共用**同一张** `*.agnes-ai.cn` 通配证书；
 *   3. 服务器按 **Host 头**路由，**不是**按 SNI —— 同一条 TLS 连接上换 Host
 *      就能换站点（实测：`Host: apihub.agnes-ai.cn` → 国际站返回 12 个模型，
 *      `Host: api.agnes-ai.cn` → 国内站返回 11 个，两者可区分）。
 *
 * 所以本库在 `SSL_write` 里当一次「小路由」：**选中哪把钥匙，就把 Host 头改成
 * 那把钥匙所属站点的域名**。TCP 与 TLS 握手完全不受影响（同 IP、同证书），
 * 变的只是请求头里的域名。
 *
 * ⚠️ **Host 改写必须是等长的**：`apihub.agnes-ai.cn`（18 字符）比
 * `api.agnes-ai.cn`（15 字符）长 3，所以后者用 **3 个尾部空格**补齐到 18。
 * HTTP 头值的尾部空格由服务器剥离（实测 `Host: api.agnes-ai.cn   ` 正确路由到
 * 国内站），这条路因此成立。反过来不行 —— 从短的往长的改没有等长写法，
 * 这正是 baseURL **必须**用较长的国际站域名的原因。
 *
 * ⚠️ Host 改写只在**头部区域**（首个 `\r\n\r\n` 之前）进行，避免误改 body 里
 * 恰好出现的同一串域名。
 *
 * ─────────────────────────────────────────────────────────────────────
 * ## 为什么必须降级 HTTP/1.1（本方案最反直觉的一点）
 *
 * 真机实测：引擎的 undici 默认协商 **HTTP/2**（`SSL_write` 里看到的是
 * `PRI * HTTP/2.0`），而 HTTP/2 的头部是 **HPACK 压缩**的 —— 看到的是一堆
 * 二进制，**没有明文 `Authorization` 头**，等长替换无从谈起。
 *
 * 强制 ALPN 只留 `http/1.1` 之后，`SSL_write` 里就是标准明文 HTTP/1.1 报文：
 *
 *     GET /v1/models HTTP/1.1
 *     host: apihub.agnes-ai.cn
 *     authorization: Bearer sk-ANON000000000000000000000000000000000000000000
 *
 * ⚠️ **降级只对目标域名生效**，这是本库设计上最要紧的一条。全局降级会让引擎
 * **所有** HTTPS 请求失去 HTTP/2（多路复用、头部压缩），是实打实的性能损失。
 * 实测调用顺序是 `SSL_set_alpn_protos` 在前、`SSL_ctrl(SET_SNI)` 在后，所以
 * 本库先扣住 ALPN 列表、等 SNI 到达再决定是否降级。
 *
 * ⚠️ `SSL_set_tlsext_host_name()` 是**宏**，展开成 `SSL_ctrl()`，动态符号表里
 * 只有后者 —— hook 前者永远不会被调用（踩过，白白多花一轮真机验证）。
 *
 * ─────────────────────────────────────────────────────────────────────
 * ## 为什么是「等长替换」
 *
 * 14 个密钥实测**全部恰好 51 字符**，所以占位符也做成 51 字符：替换后
 * `Content-Length` 不变、缓冲区布局不变，直接原地 `memcpy` 即可。
 * 换密钥池时**长度必须一致** —— `build-keyinject.ps1` 会强校验并在不等长时
 * 直接构建失败，不允许静默放过。
 *
 * Host 改写同理（见上），两处都靠等长才敢就地改。
 *
 * ## 为什么要还原（不是改完就算）
 *
 * `SSL_write` 的缓冲区属于**调用方**（node/undici 的 Buffer，可能被复用）。
 * 往里写脏数据是有副作用的，所以替换前先存下原文（密钥 51 字节、Host 18 字节），
 * `SSL_write` 返回后立刻抹回。代价是栈上几十字节，换来「不污染调用方缓冲区」。
 *
 * ─────────────────────────────────────────────────────────────────────
 * ## 号池：14 把钥匙，每个请求随机抽一把
 *
 * 池子按「国际站、国内站」**交错**排列：
 *
 *     intl.1, cn.1, intl.2, cn.2, … intl.7, cn.7
 *
 * 每个带占位符的请求**独立随机**抽一个槽位（`arc4random_uniform`），所以：
 *
 *   · 两站的钥匙会被**均匀用上**，不会出现「一头配额耗尽、另一头闲着」；
 *   · 单个钥匙的速率被摊薄到 1/14，**撞 429 的概率显著下降**；
 *   · 不再需要「失败才换」那套退避逻辑 —— 下一个请求本来就会换一把。
 *
 * ⚠️ **抽到哪把是每个请求独立的**，不维护「当前是哪把」的概念。
 *
 * ⚠️ **随机源用 `arc4random_uniform` 而不是 `rand()`**：后者要 `srand()` 播种，
 * 而本库在 `LD_PRELOAD` 里没有可靠的初始化时机（构造函数里播种还会与调用方的
 * 随机数使用互相干扰）；`arc4random` 由 bionic 自己维护、无需播种、线程安全。
 *
 * ⚠️ **不重试当前请求**：密钥是在 `SSL_write` 里换的，请求字节已经发出去了，
 * 401 的响应回来时改不了这一次。引擎自己的 `retryPolicy` 会重发失败请求，
 * 重发时抽到的是新的一把 —— 那才是恢复路径。
 *
 * ─────────────────────────────────────────────────────────────────────
 * ## 密钥怎么存
 *
 * 由 `tools/build-keyinject.ps1` 从 `agnes-keys.properties`（**不入库**）
 * 生成 `keyinject-keys.h`：每个 key 逐字节 XOR 一个随机 mask，再叠一层位置
 * 相关的滚动常量，所以 `strings keyinject.bin` 搜不到 `sk-` 开头的明文。
 *
 * ⚠️ **这是混淆，不是加密。** mask 与滚动常量都在二进制里，有耐心的逆向者
 * 可以还原。别在任何地方把它说成「密钥被加密保护了」。
 *
 * ⚠️ **门槛比「会反汇编」低得多 —— 别把这条防线说强了。** 真正的弱点不在反汇编，
 * 而在**全表共用一把 mask**（`derive_key` 里 `K[i]` 只与列号有关、与行号无关）。
 * 攻击者不读本文件、不读头文件、不读 mask，只要知道「密钥是 51 字符、`sk-` 开头、
 * 按步长 51 的二维表存放」，就能逐列枚举 `K[i] ∈ [0,256)`、要求该列所有行解出来
 * 都落在 base62 内 —— 行数一多候选就收敛到 1。实测（2026-10-13，132 行）：
 * 51/51 列唯一确定、候选空间 1、盲解结果与明文清单 132/132 逐字节相同。
 * 所以准确的表述是：**挡得住 `strings` 级的顺手一搜，挡不住愿意写二十行脚本的人。**
 * 这是本项目已知并刻意接受的取舍（真正的解只有服务端中转，见下）。
 *
 * ─────────────────────────────────────────────────────────────────────
 * ## ⚠️ 挡住了什么、挡不住什么（不要夸大）
 *
 * **挡住**：
 *   · AI 工具读文件 / 环境变量 / `/proc/<pid>/environ` —— 那些地方只有占位符
 *   · `strings` / 十六进制搜索 —— 二进制里没有明文 key
 *   · 装用户 CA 抓包 —— 抓到的密文，密钥在 TLS 加密前才拼出来
 *
 * **挡不住**：
 *   · 有耐心的逆向者 —— **是门槛，不是墙**。而且门槛不高：见上面「全表共用一把
 *     mask」那段，写个二十行的列枚举脚本就能全出，连反汇编都不用
 *   · 拿到 root 后用 Frida / ptrace 读进程内存 —— 但 release 包
 *     `android:debuggable=false`，Frida 需要 root
 *   · hook 了 `SSL_write` 的抓包工具（如 Frida 脚本）—— 与本库同一层，看得到
 *
 * **真正的解只有一个：把密钥放到服务器上做中转。** 本库是在「不引入服务器」
 * 这个前提下能做到的上限。另外要记住：本 App 给了 AI `bash` 工具，所以
 * 「密钥会不会泄露」在极端情况下会退化成「AI 会不会被说服把密钥交出来」——
 * 而本库的设计恰好让 AI **无论如何都拿不到密钥**，这一点是它的核心价值。
 */

#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "keyinject-keys.h"

typedef struct ssl_st SSL;

/* 来自 openssl/tls1.h —— bionic 不带 OpenSSL 头，故按 ABI 常量硬写。
 * 这两个值在 OpenSSL 1.1/3.x 与 BoringSSL 里都是稳定的（改动会破坏 ABI）。 */
#define SSL_CTRL_SET_TLSEXT_HOSTNAME 55
#define TLSEXT_NAMETYPE_host_name 0

/**
 * 引擎配置里的匿名占位符。
 *
 * ⚠️ **必须与 `EngineManager.kt` 的 `KEY_PLACEHOLDER` 逐字一致**，长度也必须
 * 等于 `KI_KEY_LEN`（下面的 `_Static_assert` 会拦住不一致的构建）。
 *
 * 前缀取 `sk-ANON` 而不是 `sk-`：真 key 也以 `sk-` 开头，用短前缀会把**已经
 * 替换过**的请求再替换一遍。
 */
#define KI_PLACEHOLDER "sk-ANON00000000000000000000000000000000000000000000"

_Static_assert(sizeof KI_PLACEHOLDER - 1 == KI_KEY_LEN,
               "占位符长度必须等于密钥长度：等长替换是「不用重算 Content-Length」的前提");

/** 槽位上限。node 的 TLS 连接池远小于这个数；耗尽时退回「不干预」。 */
#define MAX_SSL_SLOTS 32

/* ── combo：一个域名，两个站点 ─────────────────────────────────────────── */

/** 目标域名判据：两个站点域名都含它。换 baseURL 域名时必须同步改这里。 */
#define KI_HOST_MARKER "agnes-ai.cn"

/** 国际站域名 —— 也是引擎 baseURL 用的那个（两者中较长的，18 字符）。 */
#define KI_HOST_INTL "apihub.agnes-ai.cn"
/** 国内站域名 —— 15 字符，比国际站短 3，用尾部空格补齐到 18。 */
#define KI_HOST_CN "api.agnes-ai.cn   "

#define KI_HOST_LEN (sizeof KI_HOST_INTL - 1)

_Static_assert(sizeof KI_HOST_CN - 1 == KI_HOST_LEN,
               "Host 改写必须等长：短的那个用尾部空格补齐到与国际站域名相同");

/** 站点编号。 */
#define SITE_INTL 0
#define SITE_CN 1

/** 池子大小 = 两站密钥之和。 */
#define POOL_COUNT (KI_INTL_COUNT + KI_CN_COUNT)

/**
 * 第 slot 把钥匙属于哪个站点、是对应表里的第几个。
 *
 * **交错**排列（偶数位国际站、奇数位国内站）。
 * 用 `% count` 而不是硬编码 0..6，所以密钥数量变化时这里不用改，也不会越界。
 */
static void keyref_at(int slot, int *site, int *idx) {
    if ((slot & 1) == 0) {
        *site = SITE_INTL;
        *idx = (slot >> 1) % KI_INTL_COUNT;
    } else {
        *site = SITE_CN;
        *idx = (slot >> 1) % KI_CN_COUNT;
    }
}

/** 每个 SSL 对象是否属于目标域名（0 = 不干预）。 */
static SSL *slot_ssl[MAX_SSL_SLOTS];
static int slot_target[MAX_SSL_SLOTS];
/** 被扣住的 ALPN 列表，等 SNI 到达再设。 */
static unsigned char slot_alpn[MAX_SSL_SLOTS][128];
static unsigned int slot_len[MAX_SSL_SLOTS];

static int (*real_SSL_write)(SSL *, const void *, int);
static int (*real_SSL_set_alpn_protos)(SSL *, const unsigned char *, unsigned int);
static int (*real_SSL_ctrl)(SSL *, int, long, void *);

static int inject_enabled = 1;
static int debug_on;
static int inited;

/* ── 小工具 ───────────────────────────────────────────────────────────── */

/** 在 [hay, hay+hlen) 里找 needle。bionic 有 memmem，自己写省一层判断。 */
static const char *find_sub(const char *hay, size_t hlen, const char *needle, size_t nlen) {
    if (nlen == 0 || hlen < nlen) return NULL;
    for (size_t i = 0; i + nlen <= hlen; i++) {
        if (hay[i] == needle[0] && memcmp(hay + i, needle, nlen) == 0) return hay + i;
    }
    return NULL;
}

/** 调试输出。默认**完全静默** —— 引擎 stdout 被 App 用来解析带 token 的 URL，
 *  多打无关的行只会增加误判风险。排查时设 `DSH_KEYINJECT_DEBUG=1` 打开。 */
static void dbg(const char *tag, const char *detail) {
    if (!debug_on) return;
    char buf[192];
    int n = snprintf(buf, sizeof buf, "[keyinject] %-14s %s\n", tag, detail ? detail : "");
    if (n > 0) {
        ssize_t ignored = write(2, buf, (size_t)n);
        (void)ignored;
    }
}

/**
 * 解出「站点 site 的第 idx 把」key 到 [out]（`KI_KEY_LEN` 字节 + NUL）。
 *
 * ⚠️ 算法与 `tools/build-keyinject.ps1` 的加密端必须**逐字节一致**，
 * 改一边必须同步改另一边，否则解出来是乱码、请求全部 401。
 */
static void derive_key(int site, int idx, char *out) {
    const unsigned char (*enc)[KI_KEY_LEN] = (site == SITE_INTL) ? KI_INTL_ENC : KI_CN_ENC;
    for (int i = 0; i < KI_KEY_LEN; i++) {
        out[i] = (char)(enc[idx][i] ^ KI_MASK[i] ^ (unsigned char)(i * 7 + 0x5A));
    }
    out[KI_KEY_LEN] = '\0';
}

static void init_once(void) {
    if (inited) return;
    inited = 1;
    real_SSL_write = (int (*)(SSL *, const void *, int))dlsym(RTLD_NEXT, "SSL_write");
    real_SSL_set_alpn_protos =
        (int (*)(SSL *, const unsigned char *, unsigned int))dlsym(RTLD_NEXT, "SSL_set_alpn_protos");
    real_SSL_ctrl = (int (*)(SSL *, int, long, void *))dlsym(RTLD_NEXT, "SSL_ctrl");
    /* 逃生开关：用来区分「是本库引起的」还是「本来就这样」。它不参与保密 ——
     * 关掉之后请求会带着占位符发出去并被判 401，是个可观测的失败。 */
    const char *off = getenv("DSH_KEYINJECT_OFF");
    if (off != NULL && off[0] == '1') inject_enabled = 0;
    const char *dbgv = getenv("DSH_KEYINJECT_DEBUG");
    if (dbgv != NULL && dbgv[0] == '1') debug_on = 1;
}

/** 找出 ssl 的槽位，没有就分配一个。返回 -1 表示槽位耗尽。 */
static int slot_of(SSL *ssl) {
    for (int i = 0; i < MAX_SSL_SLOTS; i++) {
        if (slot_ssl[i] == ssl) return i;
    }
    for (int i = 0; i < MAX_SSL_SLOTS; i++) {
        if (slot_ssl[i] == NULL) {
            slot_ssl[i] = ssl;
            slot_target[i] = 0;
            return i;
        }
    }
    return -1;
}

/** 域名是不是目标站点（两个 agnes 域名都算）。 */
static int is_target_host(const char *host) {
    return find_sub(host, strlen(host), KI_HOST_MARKER, sizeof KI_HOST_MARKER - 1) != NULL;
}

/* ── hook 1：扣住 ALPN，等 SNI 到达再决定 ─────────────────────────────── */

int SSL_set_alpn_protos(SSL *ssl, const unsigned char *protos, unsigned int len) {
    init_once();
    if (!inject_enabled || real_SSL_set_alpn_protos == NULL || len > sizeof slot_alpn[0]) {
        return real_SSL_set_alpn_protos(ssl, protos, len);
    }
    int i = slot_of(ssl);
    if (i < 0) return real_SSL_set_alpn_protos(ssl, protos, len);
    memcpy(slot_alpn[i], protos, len);
    slot_len[i] = len;
    /* 返回 0 = 成功（OpenSSL 约定），真实调用推迟到 SSL_ctrl 里。
     * 中间这段时间 ALPN 是「还没设」，而握手要等 SNI 之后才开始，安全。 */
    return 0;
}

/* ── hook 2：SNI 到达 → 判断是否目标域名 + 决定 ALPN ──────────────────── */

int SSL_ctrl(SSL *ssl, int cmd, long larg, void *parg) {
    init_once();
    if (real_SSL_ctrl == NULL) return -1;

    if (inject_enabled && cmd == SSL_CTRL_SET_TLSEXT_HOSTNAME &&
        larg == TLSEXT_NAMETYPE_host_name && parg != NULL) {
        const char *host = (const char *)parg;
        int i = slot_of(ssl);
        if (i >= 0 && slot_len[i] > 0) {
            int target = is_target_host(host);
            slot_target[i] = target;
            if (target) {
                /* 只留 http/1.1：让 Authorization 与 Host 头以明文出现在
                 * SSL_write 里。去掉 h2 是有意为之，见文件头。 */
                unsigned char only11[16];
                only11[0] = 8;
                memcpy(only11 + 1, "http/1.1", 8);
                real_SSL_set_alpn_protos(ssl, only11, 9);
                dbg("target", host);
            } else {
                /* 非目标域名：原样放行，HTTP/2 保持可用。 */
                real_SSL_set_alpn_protos(ssl, slot_alpn[i], slot_len[i]);
            }
            slot_len[i] = 0; /* 同一个 SSL 对象只处理一次 */
        }
    }
    return real_SSL_ctrl(ssl, cmd, larg, parg);
}

/* ── hook 3：加密前替换占位符（+ 改写 Host），返回后还原 ──────────────── */

int SSL_write(SSL *ssl, const void *buf, int num) {
    init_once();
    if (real_SSL_write == NULL) return -1;

    /* 还原用：原文。只有命中时才填。 */
    char original[KI_KEY_LEN];
    char *hit = NULL;
    char host_original[KI_HOST_LEN];
    char *host_hit = NULL;
    /* 本请求选中的密钥明文。声明在函数级是为了能在收尾处抹掉 —— 它只在命中时
     * 才被填写，所以「是否要抹」由 hit 决定。 */
    char key[KI_KEY_LEN + 1];
    memset(key, 0, sizeof key);

    if (inject_enabled && num > 0 && buf != NULL) {
        char *base = (char *)buf;
        /* 只在头部区域里找 —— 两处改写都限定在这里，免得动到 body 里的同名字符串
         * （用户消息里恰好粘了域名或占位符时，改 body 就是篡改用户内容）。 */
        size_t hdr_end = (size_t)num;
        const char *sep = find_sub(base, (size_t)num, "\r\n\r\n", 4);
        if (sep != NULL) hdr_end = (size_t)(sep - base);

        const char *found = find_sub(base, hdr_end, KI_PLACEHOLDER, KI_KEY_LEN);
        /* 必须放得下整个 key 才替换 —— 放不下就原样发出。那会被判 401，
         * 是个可观测的失败，而不是静默发出半截密钥。 */
        if (found != NULL && (size_t)(found - base) + KI_KEY_LEN <= (size_t)num) {
            int i = slot_of(ssl);
            if (i >= 0 && slot_target[i]) {
                /* 每个请求独立随机抽一把 —— 两站均匀消耗，单把速率摊薄到 1/14。
                 * 抽到哪把与「上一个请求用了哪把」无关，所以没有退避状态。 */
                int slot = (int)arc4random_uniform(POOL_COUNT);
                int site, idx;
                keyref_at(slot, &site, &idx);
                /* ⚠️ **解到栈上，不能用全局缓存**：`SSL_write` 会被 node 的连接池
                 * 跨线程调用（libuv 线程 + 可能的 worker），而随机化之后两个并发
                 * 请求抽到的站点很可能不同。用全局缓存就会出现「A 线程刚解出国际站
                 * 钥匙、B 线程立刻把它覆盖成国内站的」—— 于是 A 拿着国际站密钥去
                 * 打国内站域名，稳定 401。栈上 52 字节，代价可忽略。 */
                derive_key(site, idx, key);
                if (debug_on) {
                    /* 只打站点与下标，不打密钥本身 —— 调试日志不该成为泄露面。 */
                    char m[64];
                    snprintf(m, sizeof m, "%s idx=%d",
                             (site == SITE_INTL) ? "INTL" : "CN", idx);
                    dbg("use", m);
                }
                hit = (char *)found;
                memcpy(original, hit, KI_KEY_LEN);
                /* 等长原地覆盖：占位符与真 key 都是 KI_KEY_LEN 字节，
                 * Content-Length、缓冲区布局全都不用动。 */
                memcpy(hit, key, KI_KEY_LEN);

                /* combo 的小路由：国内站的钥匙必须打在 api.agnes-ai.cn 上
                 * （两站钥匙不通用），而 baseURL 是国际站域名 —— 于是把 Host 头
                 * 就地改成国内站域名。两站同 IP、同一张 *.agnes-ai.cn 通配证书，
                 * 所以 TCP/TLS 完全不受影响，服务器按 Host 头路由。
                 * 国际站的钥匙不用改：baseURL 本来就是它。 */
                if (site == SITE_CN) {
                    const char *h = find_sub(base, hdr_end, KI_HOST_INTL, KI_HOST_LEN);
                    if (h != NULL) {
                        host_hit = (char *)h;
                        memcpy(host_original, h, KI_HOST_LEN);
                        memcpy(host_hit, KI_HOST_CN, KI_HOST_LEN);
                    }
                }
            }
        }
    }

    int rc = real_SSL_write(ssl, buf, num);

    /* 立刻抹回原文：缓冲区属于调用方（node 的 Buffer，可能被复用），
     * 留下真密钥或改过的域名会污染它，也可能在后续复用中被别处读到。 */
    if (hit != NULL) {
        memcpy(hit, original, KI_KEY_LEN);
        memset(original, 0, sizeof original);
        memset(key, 0, sizeof key);
    }
    if (host_hit != NULL) {
        memcpy(host_hit, host_original, KI_HOST_LEN);
        memset(host_original, 0, sizeof host_original);
    }
    return rc;
}
