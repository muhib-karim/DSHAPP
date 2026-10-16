#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""dsh-pair —— 面向大众客户的「配对」服务。

## 它解决什么

客户装完插件，机器上没有任何隧道、没有域名、没有令牌。这个服务负责：

1. 校验**激活码**（防止白嫖 Cloudflare 配额）；
2. 在 Cloudflare 上为这台电脑建一条**专属隧道** + 一条 DNS 记录；
3. 把「子域名 + 隧道 token + 访问令牌」三样下发；
4. 让客户机器上的 `cloudflared` 用隧道 token 连上来。

之后两端就靠「手机发消息 → 隧道 → 电脑」通信，公网只跑那几行字。

## 为什么用隧道 token 而不是证书

Cloudflare 的 `POST /cfd_tunnel` 会在响应里直接带回 `token` 字段
（实测：建完立刻可用，不需要再调 `/token` 接口）。客户机器上只要
`cloudflared tunnel run --token <token>` 就能连，**不需要任何证书文件**。
这是面向大众客户的关键：零文件分发。

## 激活码存哪

`CODES_PATH`（JSON）。一个码 = 一台电脑 = 一条隧道。用过后
`usedBy` 记下隧道 id，**不能再用第二次**（除非管理脚本显式重置）。

## 安全边界

- 服务只监听 `127.0.0.1:18901`，对外由 `dsh-mux` 按 `/pair/` 前缀转发；
- 注册接口需要激活码；管理接口需要 `ADMIN_SECRET`；
- **Cloudflare token 只存在服务器上**，绝不下发给客户机（下发的只是
  那条隧道自己的 token，作用域仅限那一条隧道）。

@module dsh-pair
"""

import json
import os
import re
import secrets
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# ---------------------------------------------------------------- 配置

STATE_DIR = os.environ.get("PAIR_STATE_DIR", "/workspace/dsh-pair")
CODES_PATH = os.path.join(STATE_DIR, "codes.json")
RECORDS_PATH = os.path.join(STATE_DIR, "records.json")

CF_API = "https://api.cloudflare.com/client/v4"
CF_TOKEN = os.environ.get("CF_API_TOKEN", "")
CF_ACCOUNT = os.environ.get("CF_ACCOUNT_ID", "")
CF_ZONE = os.environ.get("CF_ZONE_ID", "")
CF_ZONE_NAME = os.environ.get("CF_ZONE_NAME", "jiulingssh.xyz")

ADMIN_SECRET = os.environ.get("PAIR_ADMIN_SECRET", "")
LISTEN_HOST = os.environ.get("PAIR_HOST", "127.0.0.1")
LISTEN_PORT = int(os.environ.get("PAIR_PORT", "18901"))

# 客户电脑上的 cloudflared 连到本服务的 ingress 目标。
# 客户机器上的 DSH 中继固定监听 17890（见 dsh-jiuling-remote-desktop）。
RELAY_SERVICE = os.environ.get("PAIR_RELAY_SERVICE", "http://localhost:17890")

# cloudflared 分发包所在目录，以及允许分发的文件名白名单。
# ⚠️ 白名单是硬约束：这是唯一一处把服务器上的文件按名字读出来吐给公网的地方，
#    不白名单就等于把整个目录暴露出去（路径穿越也不用试了）。
CLOUDFLARED_DIR = os.environ.get("PAIR_CLOUDFLARED_DIR", os.path.join(STATE_DIR, "cloudflared"))
CLOUDFLARED_FILES = {
    "cloudflared-windows-amd64.exe",
    "cloudflared-linux-amd64",
    "cloudflared-darwin-amd64",
    "cloudflared-darwin-arm64",
}

# 子域名前缀形态：dsh-<8 位小写字母数字>
SUB_PREFIX = "dsh-"
SUB_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
SUB_RANDOM_LEN = 8

_LOCK = threading.Lock()


# ---------------------------------------------------------------- 工具


def log(msg):
    print("[%s] %s" % (time.strftime("%Y-%m-%d %H:%M:%S"), msg), flush=True)


def read_json(path, default):
    """读 JSON；文件不存在或损坏时返回默认值（服务绝不因状态文件而挂）。"""
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, ValueError):
        return default


def write_json(path, value):
    """原子写：先写临时文件再 rename，避免半截文件。"""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as handle:
        json.dump(value, handle, ensure_ascii=False, indent=2)
    os.replace(tmp, path)


def cf_request(method, path, body=None, timeout=25):
    """调 Cloudflare API。

    @returns `(ok, payload)`；`payload` 是解析后的 JSON（或错误文本）。
    """
    url = CF_API + path
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("Authorization", "Bearer " + CF_TOKEN)
    request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return True, json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8", "replace")
        try:
            return False, json.loads(raw)
        except ValueError:
            return False, {"raw": raw, "status": error.code}
    except Exception as error:  # noqa: BLE001 —— 网络异常统一成 (False, ...)
        return False, {"raw": repr(error)}


def cf_errors(payload):
    """把 Cloudflare 的错误结构压成一行人类可读文本。"""
    errors = payload.get("errors") if isinstance(payload, dict) else None
    if not errors:
        return json.dumps(payload, ensure_ascii=False)[:400]
    return "; ".join(
        "%s: %s" % (item.get("code"), item.get("message")) for item in errors
    )


def new_subdomain():
    """生成一个随机子域名（8 位小写字母数字）。"""
    body = "".join(secrets.choice(SUB_ALPHABET) for _ in range(SUB_RANDOM_LEN))
    return SUB_PREFIX + body


# ---------------------------------------------------------------- 业务


def register(activation_code, device_label):
    """用激活码注册一台电脑。

    幂等性：**同一个激活码重复注册会返回同一条隧道**，不会越建越多。
    这对客户很重要 —— 重装插件、改配置、App 崩溃重启都会再调一次。

    @param activation_code - 客户手里的激活码。
    @param device_label - 客户机标识（仅用于隧道命名，便于你在面板里认人）。
    @returns `(ok, payload)`。
    """
    code = (activation_code or "").strip()
    if code == "":
        return False, {"error": "missing-code", "message": "请填写激活码"}

    with _LOCK:
        codes = read_json(CODES_PATH, {})
        entry = codes.get(code)
        if entry is None:
            return False, {"error": "bad-code", "message": "激活码无效"}
        if entry.get("revoked"):
            return False, {"error": "revoked", "message": "这个激活码已被停用"}

        records = read_json(RECORDS_PATH, {})
        existing = records.get(code)
        if existing is not None:
            # 已注册过：直接复用，不再消耗 Cloudflare 配额。
            log("复用已有配对 code=%s sub=%s" % (code[:8], existing.get("subdomain")))
            return True, {
                "subdomain": existing["subdomain"],
                "hostname": existing["hostname"],
                "tunnelId": existing["tunnelId"],
                "tunnelToken": existing["tunnelToken"],
                "accessToken": existing["accessToken"],
                "reused": True,
            }

        # 新建：子域名 + 隧道 + DNS + 访问令牌
        subdomain = None
        for _ in range(12):
            candidate = new_subdomain()
            if not any(item.get("subdomain") == candidate for item in records.values()):
                subdomain = candidate
                break
        if subdomain is None:
            return False, {"error": "no-subdomain", "message": "子域名生成失败，请重试"}

        hostname = "%s.%s" % (subdomain, CF_ZONE_NAME)
        label = re.sub(r"[^A-Za-z0-9_-]+", "-", (device_label or "pc"))[:24] or "pc"

        ok, payload = cf_request(
            "POST",
            "/accounts/%s/cfd_tunnel" % CF_ACCOUNT,
            {
                "name": "dsh-%s-%s" % (subdomain, label),
                "config_src": "cloudflare",
            },
        )
        if not ok:
            return False, {
                "error": "tunnel-create-failed",
                "message": "创建隧道失败：" + cf_errors(payload),
            }
        tunnel = payload.get("result") or {}
        tunnel_id = tunnel.get("id")
        tunnel_token = tunnel.get("token")
        if not tunnel_id or not tunnel_token:
            return False, {
                "error": "tunnel-token-missing",
                "message": "隧道已建但没拿到 token，请稍后重试",
            }

        # 隧道 ingress 配置：客户机器上的 cloudflared 拉这份配置，
        # 把公网流量转给本机 17890（DSH 中继）。
        ok, payload = cf_request(
            "PUT",
            "/accounts/%s/cfd_tunnel/%s/configurations" % (CF_ACCOUNT, tunnel_id),
            {
                "config": {
                    "ingress": [
                        {"hostname": hostname, "service": RELAY_SERVICE},
                        {"service": "http_status:404"},
                    ]
                }
            },
        )
        if not ok:
            cf_request(
                "DELETE", "/accounts/%s/cfd_tunnel/%s" % (CF_ACCOUNT, tunnel_id)
            )
            return False, {
                "error": "tunnel-config-failed",
                "message": "配置隧道失败：" + cf_errors(payload),
            }

        ok, payload = cf_request(
            "POST",
            "/zones/%s/dns_records" % CF_ZONE,
            {
                "type": "CNAME",
                "name": hostname,
                "content": "%s.cfargotunnel.com" % tunnel_id,
                "proxied": True,
                "comment": "dsh-pair %s" % code[:8],
            },
        )
        if not ok:
            cf_request(
                "DELETE", "/accounts/%s/cfd_tunnel/%s" % (CF_ACCOUNT, tunnel_id)
            )
            return False, {
                "error": "dns-create-failed",
                "message": "创建域名失败：" + cf_errors(payload),
            }
        dns_id = (payload.get("result") or {}).get("id")

        access_token = secrets.token_hex(32)
        records[code] = {
            "subdomain": subdomain,
            "hostname": hostname,
            "tunnelId": tunnel_id,
            "tunnelToken": tunnel_token,
            "accessToken": access_token,
            "dnsRecordId": dns_id,
            "deviceLabel": label,
            "createdAt": int(time.time()),
        }
        write_json(RECORDS_PATH, records)

        entry["usedBy"] = tunnel_id
        entry["usedAt"] = int(time.time())
        codes[code] = entry
        write_json(CODES_PATH, codes)

        log("新建配对 code=%s sub=%s tunnel=%s" % (code[:8], subdomain, tunnel_id))
        return True, {
            "subdomain": subdomain,
            "hostname": hostname,
            "tunnelId": tunnel_id,
            "tunnelToken": tunnel_token,
            "accessToken": access_token,
            "reused": False,
        }


def rotate_token(activation_code):
    """换一把访问令牌（隧道与域名不动）。

    用途：客户怀疑令牌泄露时，改完两端各刷一次即可，不用重建隧道。

    @param activation_code - 激活码。
    @returns `(ok, payload)`。
    """
    code = (activation_code or "").strip()
    with _LOCK:
        records = read_json(RECORDS_PATH, {})
        record = records.get(code)
        if record is None:
            return False, {"error": "not-registered", "message": "这个激活码还没注册过"}
        record["accessToken"] = secrets.token_hex(32)
        records[code] = record
        write_json(RECORDS_PATH, records)
        log("轮换访问令牌 code=%s sub=%s" % (code[:8], record["subdomain"]))
        return True, {
            "subdomain": record["subdomain"],
            "hostname": record["hostname"],
            "tunnelId": record["tunnelId"],
            "tunnelToken": record["tunnelToken"],
            "accessToken": record["accessToken"],
            "reused": True,
        }


def unregister(activation_code):
    """彻底注销：删隧道 + 删 DNS 记录 + 清记录（激活码可再次使用）。

    @param activation_code - 激活码。
    @returns `(ok, payload)`。
    """
    code = (activation_code or "").strip()
    with _LOCK:
        records = read_json(RECORDS_PATH, {})
        record = records.pop(code, None)
        if record is None:
            return False, {"error": "not-registered", "message": "这个激活码还没注册过"}
        write_json(RECORDS_PATH, records)

        codes = read_json(CODES_PATH, {})
        if code in codes:
            codes[code].pop("usedBy", None)
            codes[code].pop("usedAt", None)
            write_json(CODES_PATH, codes)

        if record.get("dnsRecordId"):
            cf_request(
                "DELETE",
                "/zones/%s/dns_records/%s" % (CF_ZONE, record["dnsRecordId"]),
            )
        cf_request(
            "DELETE",
            "/accounts/%s/cfd_tunnel/%s" % (CF_ACCOUNT, record["tunnelId"]),
        )
        log("注销 code=%s sub=%s" % (code[:8], record["subdomain"]))
        return True, {"removed": record["subdomain"]}


HANDSHAKE_PATH = os.path.join(STATE_DIR, "handshakes.json")

# 配对码有效期（秒）。够客户从电脑走到手机输完，又不至于长期挂在网上。
HANDSHAKE_TTL = int(os.environ.get("PAIR_HANDSHAKE_TTL", "600"))

# 配对码字符集：去掉了 0/O/1/I/L 这些看着会认错的。
HANDSHAKE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
HANDSHAKE_LEN = 6


def make_handshake(activation_code):
    """电脑端拿到凭据后，换一个**短配对码**给手机用。

    ## 为什么要多这一跳

    电脑端手里的 `{hostname, accessToken}` 直接编码进二维码会很长，
    手输更不可能（64 位十六进制）。所以让服务器把它换成 6 位码：
    二维码和手输都变得可行，而**凭据本身不经过二维码**。

    ## 一次性

    `claim` 成功后立即删除。所以就算码被旁人瞥见，也只有极短的时间窗
    可以抢先使用；客户发现不对劲重新生成一次即可。

    @param activation_code - 激活码（用来定位是哪台电脑）。
    @returns `(ok, payload)`；成功时 payload 含 `pairCode` 与 `expiresIn`。
    """
    code = (activation_code or "").strip()
    with _LOCK:
        records = read_json(RECORDS_PATH, {})
        record = records.get(code)
        if record is None:
            return False, {"error": "not-registered", "message": "这个激活码还没注册过"}

        now = int(time.time())
        store = read_json(HANDSHAKE_PATH, {})
        # 顺手清掉过期的，避免文件无限增长。
        store = {
            key: value
            for key, value in store.items()
            if int(value.get("expiresAt", 0)) > now
        }
        pair_code = None
        for _ in range(50):
            candidate = "".join(
                secrets.choice(HANDSHAKE_ALPHABET) for _ in range(HANDSHAKE_LEN)
            )
            if candidate not in store:
                pair_code = candidate
                break
        if pair_code is None:
            return False, {"error": "no-code", "message": "配对码生成失败，请重试"}

        store[pair_code] = {
            "activationCode": code,
            "expiresAt": now + HANDSHAKE_TTL,
            "createdAt": now,
        }
        write_json(HANDSHAKE_PATH, store)
        log("生成配对码 %s（%d 秒有效）" % (pair_code, HANDSHAKE_TTL))
        return True, {
            "pairCode": pair_code,
            "expiresIn": HANDSHAKE_TTL,
            "hostname": record["hostname"],
        }


def claim_handshake(pair_code):
    """手机端用配对码换取连接凭据（一次性）。

    @param pair_code - 电脑端显示的那个 6 位码。
    @returns `(ok, payload)`；成功时含 `hostname` 与 `accessToken`。
    """
    key = (pair_code or "").strip().upper().replace(" ", "").replace("-", "")
    if key == "":
        return False, {"error": "missing-code", "message": "请填写配对码"}

    with _LOCK:
        store = read_json(HANDSHAKE_PATH, {})
        entry = store.get(key)
        if entry is None:
            return False, {"error": "bad-code", "message": "配对码无效或已过期"}
        if int(entry.get("expiresAt", 0)) <= int(time.time()):
            store.pop(key, None)
            write_json(HANDSHAKE_PATH, store)
            return False, {"error": "expired", "message": "配对码已过期，请在电脑上重新生成"}

        records = read_json(RECORDS_PATH, {})
        record = records.get(entry["activationCode"])
        if record is None:
            return False, {"error": "not-registered", "message": "这台电脑的配对已被注销"}

        # 一次性：换完即焚。
        store.pop(key, None)
        write_json(HANDSHAKE_PATH, store)

        log("配对码 %s 已被领取 → %s" % (key, record["subdomain"]))
        return True, {
            "hostname": record["hostname"],
            "accessToken": record["accessToken"],
            "subdomain": record["subdomain"],
        }


def lookup(activation_code):
    """查一个激活码的状态（管理用，不含 token）。"""
    code = (activation_code or "").strip()
    codes = read_json(CODES_PATH, {})
    records = read_json(RECORDS_PATH, {})
    entry = codes.get(code)
    record = records.get(code)
    return {
        "code": code,
        "exists": entry is not None,
        "revoked": bool(entry and entry.get("revoked")),
        "note": (entry or {}).get("note", ""),
        "registered": record is not None,
        "subdomain": (record or {}).get("subdomain"),
        "hostname": (record or {}).get("hostname"),
        "tunnelId": (record or {}).get("tunnelId"),
        "createdAt": (record or {}).get("createdAt"),
    }


# ---------------------------------------------------------------- HTTP


class Handler(BaseHTTPRequestHandler):
    """极简 HTTP 处理器。只服务 `/pair/` 前缀下的几个 JSON 接口。"""

    server_version = "dsh-pair/1.0"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # noqa: A003 —— 覆盖基类，走统一日志
        log("[http] " + (fmt % args))

    def _send(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _read_body(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            return {}
        raw = self.rfile.read(length)
        try:
            return json.loads(raw.decode("utf-8"))
        except ValueError:
            return {}

    def _admin_ok(self):
        """管理接口鉴权：`X-Pair-Admin` 头必须等于 `PAIR_ADMIN_SECRET`。"""
        if ADMIN_SECRET == "":
            return False
        return self.headers.get("X-Pair-Admin", "") == ADMIN_SECRET

    def _send_binary(self, status, body, content_type, filename=None):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "public, max-age=86400")
        if filename is not None:
            self.send_header("Content-Disposition", 'attachment; filename="%s"' % filename)
        self.end_headers()
        self.wfile.write(body)

    def _serve_cloudflared(self, name):
        """分发 cloudflared 可执行文件。

        ## 为什么由我们中转，而不是让客户去 GitHub 下

        实测：**国内服务器直连 github.com 是不通的**（`curl` 返回 000），
        客户电脑大概率同样。插件自己去 GitHub 下载 ⇒ 大量客户卡在这一步，
        而这一步失败的表现是「隧道起不来」，用户完全看不出是网络问题。

        所以由我们这台已经能出网的服务器中转一次，插件只认我们这一个地址。

        @param name - 请求的文件名，必须白名单里的那一个。
        """
        if name not in CLOUDFLARED_FILES:
            self._send(404, {"error": "not-found", "message": "不支持的平台"})
            return
        path = os.path.join(CLOUDFLARED_DIR, name)
        try:
            with open(path, "rb") as handle:
                body = handle.read()
        except OSError:
            log("分发失败：%s 不存在" % path)
            self._send(503, {"error": "unavailable", "message": "该平台暂未提供，请稍后再试"})
            return
        log("分发 cloudflared: %s (%d 字节)" % (name, len(body)))
        self._send_binary(200, body, "application/octet-stream", name)

    def do_GET(self):  # noqa: N802 —— 基类接口名
        path = self.path.split("?", 1)[0]
        if path == "/pair/health":
            self._send(200, {"ok": True, "zone": CF_ZONE_NAME})
            return
        if path.startswith("/pair/cloudflared/"):
            self._serve_cloudflared(path[len("/pair/cloudflared/"):])
            return
        if path == "/pair/cloudflared":
            # 给插件一个「我这个平台该下哪个」的清单，省得两端各写一份映射。
            self._send(200, {"files": sorted(CLOUDFLARED_FILES)})
            return
        if path == "/pair/lookup":
            if not self._admin_ok():
                self._send(401, {"error": "unauthorized"})
                return
            query = self.path.split("?", 1)[1] if "?" in self.path else ""
            code = ""
            for pair in query.split("&"):
                if pair.startswith("code="):
                    code = pair[5:]
            self._send(200, lookup(code))
            return
        self._send(404, {"error": "not-found"})

    def do_POST(self):  # noqa: N802
        path = self.path.split("?", 1)[0]
        body = self._read_body()

        if path == "/pair/register":
            ok, payload = register(body.get("code"), body.get("device"))
            self._send(200 if ok else 400, payload)
            return

        if path == "/pair/rotate":
            ok, payload = rotate_token(body.get("code"))
            self._send(200 if ok else 400, payload)
            return

        if path == "/pair/handshake":
            # 电脑端：拿到凭据后换一个短配对码给手机。
            ok, payload = make_handshake(body.get("code"))
            self._send(200 if ok else 400, payload)
            return

        if path == "/pair/claim":
            # 手机端：用配对码换连接凭据（一次性）。
            ok, payload = claim_handshake(body.get("pairCode"))
            self._send(200 if ok else 400, payload)
            return

        if path == "/pair/unregister":
            if not self._admin_ok():
                self._send(401, {"error": "unauthorized"})
                return
            ok, payload = unregister(body.get("code"))
            self._send(200 if ok else 400, payload)
            return

        if path == "/pair/codes":
            # 批量生成激活码（管理接口）。
            if not self._admin_ok():
                self._send(401, {"error": "unauthorized"})
                return
            count = int(body.get("count") or 1)
            count = max(1, min(count, 100))
            note = str(body.get("note") or "")
            with _LOCK:
                codes = read_json(CODES_PATH, {})
                made = []
                while len(made) < count:
                    candidate = secrets.token_hex(4).upper()
                    if candidate in codes:
                        continue
                    codes[candidate] = {"note": note, "createdAt": int(time.time())}
                    made.append(candidate)
                write_json(CODES_PATH, codes)
            log("生成 %d 个激活码" % len(made))
            self._send(200, {"codes": made})
            return

        if path == "/pair/revoke":
            if not self._admin_ok():
                self._send(401, {"error": "unauthorized"})
                return
            code = (body.get("code") or "").strip()
            with _LOCK:
                codes = read_json(CODES_PATH, {})
                if code not in codes:
                    self._send(400, {"error": "bad-code"})
                    return
                codes[code]["revoked"] = bool(body.get("revoked", True))
                write_json(CODES_PATH, codes)
            log("激活码 %s revoked=%s" % (code, body.get("revoked", True)))
            self._send(200, {"code": code, "revoked": codes[code]["revoked"]})
            return

        self._send(404, {"error": "not-found"})


def main():
    os.makedirs(STATE_DIR, exist_ok=True)
    for required, label in (
        (CF_TOKEN, "CF_API_TOKEN"),
        (CF_ACCOUNT, "CF_ACCOUNT_ID"),
        (CF_ZONE, "CF_ZONE_ID"),
        (ADMIN_SECRET, "PAIR_ADMIN_SECRET"),
    ):
        if not required:
            log("⚠️ 缺少环境变量 %s，服务仍会启动但相关接口不可用" % label)

    server = ThreadingHTTPServer((LISTEN_HOST, LISTEN_PORT), Handler)
    log("dsh-pair listening on %s:%d (zone=%s)" % (LISTEN_HOST, LISTEN_PORT, CF_ZONE_NAME))
    server.serve_forever()


if __name__ == "__main__":
    main()
