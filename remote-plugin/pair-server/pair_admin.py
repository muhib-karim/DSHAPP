#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""dsh-pair 管理脚本 —— 在服务器上生成 / 查看 / 停用激活码。

用法（都跑在服务器上）：

    python3 pair_admin.py new 5 --note "微信群第一批"
    python3 pair_admin.py list
    python3 pair_admin.py show ABC12345
    python3 pair_admin.py revoke ABC12345
    python3 pair_admin.py restore ABC12345
    python3 pair_admin.py unregister ABC12345   # 删隧道 + 删 DNS，码可再用

`new` 输出的激活码直接发给客户；客户在插件里填一次即可。
"""

import argparse
import json
import os
import sys
import urllib.error
import urllib.request

BASE = os.environ.get("PAIR_BASE", "http://127.0.0.1:18901")
SECRET_PATH = "/root/dsh/pair/admin.secret"


def secret():
    """读管理密钥；读不到就报错退出（绝不猜默认值）。"""
    try:
        with open(SECRET_PATH, "r", encoding="utf-8") as handle:
            return handle.read().strip()
    except OSError as error:
        print("读不到管理密钥 %s：%s" % (SECRET_PATH, error), file=sys.stderr)
        sys.exit(1)


def call(path, body=None, method="POST"):
    """调管理接口，返回 `(ok, payload)`。"""
    url = BASE + path
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("X-Pair-Admin", secret())
    request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return True, json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8", "replace")
        try:
            return False, json.loads(raw)
        except ValueError:
            return False, {"raw": raw}


def main():
    parser = argparse.ArgumentParser(description="dsh-pair 激活码管理")
    sub = parser.add_subparsers(dest="command", required=True)

    new = sub.add_parser("new", help="生成激活码")
    new.add_argument("count", nargs="?", type=int, default=1)
    new.add_argument("--note", default="")

    show = sub.add_parser("show", help="查一个激活码的状态")
    show.add_argument("code")

    revoke = sub.add_parser("revoke", help="停用激活码")
    revoke.add_argument("code")

    restore = sub.add_parser("restore", help="恢复激活码")
    restore.add_argument("code")

    unregister = sub.add_parser("unregister", help="注销：删隧道 + 删 DNS，码可再用")
    unregister.add_argument("code")

    sub.add_parser("list", help="列出所有激活码")

    args = parser.parse_args()

    if args.command == "new":
        ok, payload = call("/pair/codes", {"count": args.count, "note": args.note})
        if not ok:
            print("失败：%s" % json.dumps(payload, ensure_ascii=False))
            sys.exit(1)
        print("新激活码（发给客户）：")
        for code in payload["codes"]:
            print("  " + code)
        return

    if args.command == "show":
        ok, payload = call("/pair/lookup?code=" + args.code, method="GET")
        print(json.dumps(payload, ensure_ascii=False, indent=2))
        return

    if args.command in ("revoke", "restore"):
        ok, payload = call(
            "/pair/revoke",
            {"code": args.code, "revoked": args.command == "revoke"},
        )
        print(json.dumps(payload, ensure_ascii=False))
        return

    if args.command == "unregister":
        ok, payload = call("/pair/unregister", {"code": args.code})
        print(json.dumps(payload, ensure_ascii=False))
        return

    if args.command == "list":
        # 没有列表接口，直接读状态文件（脚本和 server 在同一台机器上）。
        state = os.environ.get("PAIR_STATE_DIR", "/workspace/dsh-pair")
        try:
            with open(os.path.join(state, "codes.json"), encoding="utf-8") as handle:
                codes = json.load(handle)
            with open(os.path.join(state, "records.json"), encoding="utf-8") as handle:
                records = json.load(handle)
        except OSError as error:
            print("读状态失败：%s" % error, file=sys.stderr)
            sys.exit(1)
        print("%-10s %-8s %-22s %s" % ("激活码", "状态", "子域名", "备注"))
        for code, entry in sorted(codes.items()):
            record = records.get(code)
            state_text = "已停用" if entry.get("revoked") else (
                "已注册" if record else "未使用"
            )
            print(
                "%-10s %-8s %-22s %s"
                % (code, state_text, (record or {}).get("subdomain", "-"), entry.get("note", ""))
            )
        return


if __name__ == "__main__":
    main()
