#!/usr/bin/env python3
"""CI (fork): emulator smoke test of the release APK. Install, launch, screenshot the first screen, then the app's own
native screens; a PNG of each plus the UI dumps and logcat land in <out>.

The CI emulator is x86_64 (arm64 code runs through translation). The app ships an arm64 Termux bootstrap only and picks
it from Build.SUPPORTED_ABIS[0], so on this image its engine cannot boot ("Termux 环境未就绪"). The DSH chat and
settings web UI therefore needs a real arm64 device (FORK_NOTES.md). When the engine does come up (an arm64 runner), the
test goes on to the chat and settings screens as before; otherwise it checks the native boot-status screen (safe mode,
view log) and the native log viewer.

v1.3.0: before any of that the app shows its first screen ("Continue with Google" / "Use API key"). The test requires
both buttons, opens the API-key screen, submits an invalid key and requires the error text (DeepSeek's 401, or the
offline message when the emulator has no network), then taps "Set up later" and goes on as before.

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
VIEW_LOG = re.compile(r"^(查看日志|View log|View logs)$", re.I)
ENGINE_FAIL = re.compile(r"(启动失败|failed to start|CANNOT LINK|救援模式|Rescue)", re.I)
GOOGLE = re.compile(r"^Continue with Google$", re.I)
API_KEY = re.compile(r"^Use API key$", re.I)
LATER = re.compile(r"^Set up later$", re.I)
KEY_ERROR = re.compile(r"(rejected this key|Could not reach api\.deepseek\.com|DeepSeek answered HTTP|no balance|rate limiting)", re.I)


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


# System dialogs that cover the app on a slow CI emulator: a launcher ANR ("Wait") and the app's own request to run in
# the background ("Allow", which the app needs to keep its engine alive).
DISMISS = re.compile(r"^(Wait|Allow|允许|始终允许|OK|确定)$")
COVER = re.compile(r"(isn't responding|无响应|run in background|后台运行)", re.I)


def dismiss(root):
    texts = " | ".join(label(n) for n in nodes(root))
    if not COVER.search(texts):
        return False
    for n in nodes(root):
        if DISMISS.match((n.get("text") or "").strip()):
            print("dismissing system dialog:", texts[:160], flush=True)
            return tap(n)
    return False


def screen(tag):
    """A dump with system dialogs out of the way."""
    for _ in range(4):
        root = dump(tag)
        if not dismiss(root):
            return root
        time.sleep(2)
    return dump(tag)


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
    print("abilist:", adb("shell", "getprop", "ro.product.cpu.abilist").strip(), flush=True)
    adb("install", "-r", "-g", APK, check=True, timeout=600)
    adb("shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS")
    adb("logcat", "-c")
    adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1", check=True)
    time.sleep(25)
    first_ok, key_ok = sign_in_screens()

    chat = False
    boot_status = False
    deadline = time.time() + 6 * 60
    while time.time() < deadline:
        root = screen("wait")
        texts = " | ".join(label(n) for n in nodes(root))
        if on_sign_in(root):
            print("still on the sign-in screen; Back", flush=True)
            adb("shell", "input", "keyevent", "4")
        elif any(n.get("class") == "android.widget.EditText" for n in nodes(root)) or CHAT_HINT.search(texts):
            chat = True
            break
        if ENGINE_FAIL.search(texts) or VIEW_LOG.search(texts):
            print("engine status on screen:", texts[:300], flush=True)
            boot_status = True
            break
        time.sleep(10)
    time.sleep(5)
    root = screen("status")
    if chat:
        shot("04-chat")
        target = find(root, SETTINGS)
        if target is None:
            corner = [n for n in nodes(root) if n.get("clickable") == "true" and re.match(r"\[(\d+),(\d+)\]", n.get("bounds", ""))]
            corner.sort(key=lambda n: tuple(int(v) for v in re.match(r"\[(\d+),(\d+)\]", n.get("bounds")).groups())[::-1])
            if corner:
                tap(corner[0])
                time.sleep(3)
                target = find(screen("sidebar"), SETTINGS)
        ok = target is not None and tap(target)
        time.sleep(4)
        ok = ok and SETTINGS_OPEN.search(" | ".join(label(n) for n in nodes(screen("settings")))) is not None
        shot("05-settings")
        result = f"engine up: chat screen and settings screen opened: {ok}"
    else:
        shot("04-boot-status")
        button = find(root, VIEW_LOG)
        ok = boot_status and button is not None and tap(button)
        time.sleep(4)
        screen("log")
        resumed = adb("shell", "dumpsys", "activity", "activities")
        ok = ok and "LogViewerActivity" in next((l for l in resumed.splitlines() if "mResumedActivity" in l or "topResumedActivity" in l), "")
        shot("05-log-viewer")
        result = ("engine not up on this x86_64 emulator (arm64-only Termux bootstrap); native boot-status screen shown and "
                  f"log viewer opened: {ok}")
    result = f"first screen (Google + API key): {first_ok}; API-key screen with error on an invalid key: {key_ok}; " + result
    ok = ok and first_ok and key_ok
    with open(os.path.join(OUT, "logcat.txt"), "w", encoding="utf-8") as fh:
        fh.write(adb("logcat", "-d", "-v", "time", timeout=120))
    with open(os.path.join(OUT, "RESULT.txt"), "w", encoding="utf-8") as fh:
        fh.write(result + "\n")
    print(result, flush=True)
    if not ok:
        sys.exit(1)


def sign_in_screens():
    """First screen, API-key screen, an invalid key's error; then "Set up later" back to the app."""
    root = None
    deadline = time.time() + 120
    while time.time() < deadline:
        root = screen("first")
        if find(root, GOOGLE) is not None and find(root, API_KEY) is not None:
            break
        time.sleep(5)
    shot("01-first-screen")
    first_ok = find(root, GOOGLE) is not None and find(root, API_KEY) is not None
    print("first screen:", first_ok, flush=True)
    if not first_ok:
        return False, False
    tap(find(root, API_KEY))
    time.sleep(3)
    root = screen("api-key")
    shot("02-api-key-screen")
    field = next((n for n in nodes(root) if (n.get("resource-id") or "").endswith(":id/keyInput")), None)
    key_ok = False
    if field is not None:
        tap(field)
        time.sleep(1)
        adb("shell", "input", "text", "sk-smoke-invalid-key-0000000000")
        adb("shell", "input", "keyevent", "66")   # IME "Done" submits
        deadline = time.time() + 45
        while time.time() < deadline and not key_ok:
            time.sleep(3)
            root = screen("api-key-error")
            key_ok = find(root, KEY_ERROR) is not None
        adb("shell", "input", "keyevent", "111")   # hide the keyboard (ESC) for a clean screenshot
        time.sleep(1)
        shot("03-api-key-error")
    print("API-key screen error shown:", key_ok, flush=True)
    # Leave the key panel with the system Back key (its own Back button can be below the fold); a first Back may only
    # close the keyboard, so repeat until the first screen's "Set up later" is on screen.
    root = screen("api-key-done")
    for _ in range(4):
        later = find(root, LATER)
        if later is not None and not on_sign_in_key_panel(root):
            tap(later)
            time.sleep(3)
            break
        adb("shell", "input", "keyevent", "4")
        time.sleep(2)
        root = screen("first-again")
    return first_ok, key_ok


def on_sign_in_key_panel(root):
    return any((n.get("resource-id") or "").endswith(":id/keyInput") for n in nodes(root))


def on_sign_in(root):
    """The app's own first screen (its key field is not the chat composer)."""
    return any((n.get("resource-id") or "").endswith((":id/keyInput", ":id/btnGoogle", ":id/btnApiKey")) for n in nodes(root))


def find(root, pattern):
    return next((n for n in nodes(root) if pattern.search((n.get("text") or "").strip()) or pattern.search((n.get("content-desc") or "").strip())), None)


if __name__ == "__main__":
    main()
