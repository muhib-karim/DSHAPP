#!/usr/bin/env python3
"""CI (fork): emulator smoke test of the release APK. Install, launch, wait for the first screen, then the chat screen
and the settings screen; a PNG of each plus the UI dumps and logcat land in <out>.

usage: emulator-smoke.py <apk> <package> <out dir>
Runs inside reactivecircus/android-emulator-runner (an x86_64 image that translates arm64 apps).
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

APK, PKG, OUT = sys.argv[1], sys.argv[2], sys.argv[3]
os.makedirs(OUT, exist_ok=True)
CHAT_HINT = re.compile(r"(输入|消息|Message|Ask|问点什么|发送|Send|新建会话|New session|New chat)", re.I)
SETTINGS = re.compile(r"^(设置|Settings)$", re.I)
SETTINGS_OPEN = re.compile(r"(通用|General|模型|Models|插件|Plugins|外观|Appearance)", re.I)
ENGINE_FAIL = re.compile(r"(启动失败|failed to start|CANNOT LINK|救援模式|Rescue)", re.I)


def adb(*args, check=False, timeout=120):
    r = subprocess.run(["adb", *args], capture_output=True, timeout=timeout)
    if check and r.returncode != 0:
        sys.exit(f"adb {' '.join(args)} failed: {r.stderr.decode(errors='replace')}")
    return r.stdout.decode(errors="replace")


def shot(name):
    with open(os.path.join(OUT, name + ".png"), "wb") as fh:
        fh.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, timeout=60).stdout)
    print("screenshot", name, flush=True)


def dump(tag):
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("shell", "cat", "/sdcard/ui.xml")
    with open(os.path.join(OUT, f"ui-{tag}.xml"), "w", encoding="utf-8") as fh:
        fh.write(xml)
    try:
        return ET.fromstring(xml[xml.index("<?xml"):] if "<?xml" in xml else xml)
    except (ET.ParseError, ValueError):
        return None


def nodes(root):
    return [] if root is None else list(root.iter("node"))


def label(n):
    return (n.get("text") or "") + " " + (n.get("content-desc") or "")


def tap(n):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
    if not m:
        return False
    x1, y1, x2, y2 = map(int, m.groups())
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    return True


def main():
    adb("install", "-r", "-g", APK, check=True, timeout=600)
    adb("shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS")
    adb("logcat", "-c")
    adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1", check=True)
    time.sleep(25)
    shot("01-first-screen")
    dump("first")

    chat = False
    deadline = time.time() + 12 * 60
    while time.time() < deadline:
        root = dump("wait")
        texts = " | ".join(label(n) for n in nodes(root))
        if any(n.get("class") == "android.widget.EditText" for n in nodes(root)) or CHAT_HINT.search(texts):
            chat = True
            break
        if ENGINE_FAIL.search(texts):
            print("engine reported a failure on screen:", texts[:400], flush=True)
            break
        time.sleep(10)
    time.sleep(5)
    shot("02-chat")
    root = dump("chat")

    settings = False
    target = next((n for n in nodes(root) if SETTINGS.search((n.get("text") or "").strip()) or SETTINGS.search((n.get("content-desc") or "").strip())), None)
    if target is None:
        # The settings entry lives in the sidebar: open it with the first clickable control at the top left.
        top_left = [n for n in nodes(root) if n.get("clickable") == "true" and re.match(r"\[(\d+),(\d+)\]", n.get("bounds", ""))]
        top_left.sort(key=lambda n: tuple(int(v) for v in re.match(r"\[(\d+),(\d+)\]", n.get("bounds")).groups())[::-1])
        if top_left:
            tap(top_left[0])
            time.sleep(3)
            root = dump("sidebar")
            target = next((n for n in nodes(root) if SETTINGS.search((n.get("text") or "").strip()) or SETTINGS.search((n.get("content-desc") or "").strip())), None)
    if target is not None and tap(target):
        time.sleep(4)
        root = dump("settings")
        settings = SETTINGS_OPEN.search(" | ".join(label(n) for n in nodes(root))) is not None
    shot("03-settings")
    with open(os.path.join(OUT, "logcat.txt"), "w", encoding="utf-8") as fh:
        fh.write(adb("logcat", "-d", "-v", "time", timeout=120))
    print(f"chat screen reached: {chat}; settings opened: {settings}", flush=True)
    if not chat or not settings:
        sys.exit(1)


if __name__ == "__main__":
    main()
