#!/usr/bin/env python3
"""CI (fork): put together app/src/main/assets for a release build. Never run on a developer machine.

Upstream keeps ~135 MB of build assets out of the repository and has no script that rebuilds them (docs/agents/
regen-assets.md). This fork builds them from three sources, all recorded in the build's ASSETS.json:

1. Unchanged binary assets come from upstream's own release APK DSH0.3.0.apk, pinned by sha256. Their version stamps
   (engine 0.2.1-rc.2-1, node 26.4.0-2, pnpm 11.28.3) are the same in v0.3.0 and the head this fork ships.
2. Plugin zips are packed from this repository's source (mobile-ui-plugin, core-memory-plugin, ...), with the file
   layout of upstream's zips, so the APK carries the head's plugin code.
3. The JNI helpers (linkcompat.bin, boosandbox.bin, boo-sandbox-run.bin) are compiled by the workflow with the NDK.
   webview-compat.zip is rebuilt from the pinned engine and head's mobile UI by upstream's own
   tools/build-webview-compat.ps1 (Windows job).

keyinject.bin, upstream's built-in API account pool, is never included: it is optional and holds upstream's own
credentials; users of this fork bring their own key.

usage: assemble-assets.py --apk DSH0.3.0.apk --native DIR --compat webview-compat.zip --out app/src/main/assets
"""
import argparse
import hashlib
import json
import os
import sys
import zipfile

UPSTREAM_APK_SHA256 = "5ac0299c350c92cf47ba7f3925cc0bd69e292e0d7695012c602ef5a1dee61682"
UPSTREAM_APK_URL = "https://github.com/pht88888888/DSHAPP/releases/download/v0.3.0/DSH0.3.0.apk"
FROM_APK = [
    "engine.bin", "node-runtime.bin", "bootstrap-aarch64.zip", "adb-wireless-arm64.zip", "pnpm-android-arm64.zip",
    "sharp-wasm32.zip", "ripgrep-android-arm64.zip", "node-addon-android-arm64.zip",
    "node-addon-system-android-arm64.zip", "node-pty-android-arm64.zip",
]
NEVER = {"keyinject.bin"}
NATIVE = ["linkcompat.bin", "boosandbox.bin", "boo-sandbox-run.bin"]
# zip name -> (source package directory, top-level files besides lib/**; None = the package's skills/ tree)
PLUGIN_ZIPS = {
    "mobile-ui.zip": ("mobile-ui-plugin/dsh-android-mobile", ["package.json"]),
    "bubble-tool.zip": ("mobile-ui-plugin/dsh-android-bubble", ["package.json"]),
    "logs-tool.zip": ("mobile-ui-plugin/dsh-android-logs", ["package.json"]),
    "pet-bridge.zip": ("mobile-ui-plugin/dsh-android-pet", ["package.json"]),
    "root-tool.zip": ("mobile-ui-plugin/dsh-android-root", ["package.json"]),
    "shell-tool.zip": ("mobile-ui-plugin/dsh-android-shell", ["package.json"]),
    "statusbar-tool.zip": ("mobile-ui-plugin/dsh-android-statusbar", ["package.json"]),
    "uiagent-tool.zip": ("mobile-ui-plugin/dsh-android-uiagent", ["package.json"]),
    "core-memory.zip": ("core-memory-plugin/dsh-core-memory", ["package.json", "cordis.patch.yml"]),
    "market-plugin.zip": ("market-plugin/dsh-android-market", ["package.json"]),
    "preset-razor.zip": ("preset-razor-plugin/dsh-preset-razor", ["package.json", "cordis.patch.yml", "README.md"]),
    "agnes-multimodal.zip": ("agnes-multimodal-plugin/dsh-agnes-multimodal", ["package.json"]),
    "preset-skills.zip": ("preset-skills-plugin/dsh-preset-skills", None),
}


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def pack(repo, zip_name, src, top, out_dir):
    base = os.path.join(repo, src)
    entries = []
    if top is None:
        skills = os.path.join(base, "skills")
        for root, _, files in os.walk(skills):
            for f in files:
                full = os.path.join(root, f)
                entries.append((full, os.path.relpath(full, skills)))
    else:
        for f in top:
            if os.path.isfile(os.path.join(base, f)):
                entries.append((os.path.join(base, f), f))
        lib = os.path.join(base, "lib")
        for root, _, files in os.walk(lib):
            for f in files:
                full = os.path.join(root, f)
                entries.append((full, os.path.relpath(full, base)))
    if not entries:
        sys.exit(f"{zip_name}: nothing to pack from {src}")
    target = os.path.join(out_dir, zip_name)
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as z:
        for full, arc in sorted(entries, key=lambda e: e[1]):
            info = zipfile.ZipInfo(arc.replace(os.sep, "/"), date_time=(2026, 10, 21, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            with open(full, "rb") as fh:
                z.writestr(info, fh.read())
    return target, [arc for _, arc in entries]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True)
    ap.add_argument("--native", required=True)
    ap.add_argument("--compat", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--manifest", default="ASSETS.json")
    args = ap.parse_args()
    repo = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
    got = sha256(args.apk)
    if got != UPSTREAM_APK_SHA256:
        sys.exit(f"upstream APK sha256 {got} != pinned {UPSTREAM_APK_SHA256}")
    os.makedirs(args.out, exist_ok=True)
    record = {"upstream_apk": {"url": UPSTREAM_APK_URL, "sha256": got}, "assets": {}}
    with zipfile.ZipFile(args.apk) as apk:
        for name in FROM_APK:
            data = apk.read("assets/" + name)
            with open(os.path.join(args.out, name), "wb") as fh:
                fh.write(data)
            record["assets"][name] = {"from": "upstream DSH0.3.0.apk", "sha256": hashlib.sha256(data).hexdigest()}
    for zip_name, (src, top) in PLUGIN_ZIPS.items():
        target, files = pack(repo, zip_name, src, top, args.out)
        record["assets"][zip_name] = {"from": src + " (this repository)", "files": files, "sha256": sha256(target)}
    for name in NATIVE:
        path = os.path.join(args.native, name)
        if not os.path.isfile(path) or os.path.getsize(path) == 0:
            sys.exit(f"native asset missing: {path}")
        with open(path, "rb") as fh, open(os.path.join(args.out, name), "wb") as out:
            out.write(fh.read())
        record["assets"][name] = {"from": "app/src/main/jni (NDK, this workflow)", "sha256": sha256(path)}
    with zipfile.ZipFile(args.compat) as z:
        if not any(n.startswith("mobileui/") for n in z.namelist()) or not any(n.startswith("dist/") for n in z.namelist()):
            sys.exit("webview-compat.zip lacks dist/ or mobileui/")
    with open(args.compat, "rb") as fh, open(os.path.join(args.out, "webview-compat.zip"), "wb") as out:
        out.write(fh.read())
    record["assets"]["webview-compat.zip"] = {"from": "tools/build-webview-compat.ps1 (this workflow)", "sha256": sha256(args.compat)}
    for name in NEVER:
        if os.path.exists(os.path.join(args.out, name)):
            sys.exit(f"{name} must not be bundled")
    with open(args.manifest, "w", encoding="utf-8") as fh:
        json.dump(record, fh, indent=1, ensure_ascii=False)
    print(f"assets ready: {len(record['assets'])} files, manifest {args.manifest}")


if __name__ == "__main__":
    main()
