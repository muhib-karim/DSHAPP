# Fork notes (muhib-karim/DSHAPP)

Status of the fork and answers to the owner's questions. Nothing here changes the app; these are notes for review.

## What the fork ships
- Upstream head `fce1bc0` (0.3.3, versionCode 29) as 0.3.4 / 30, with package names `com.mkdsh.droid` (system WebView) and
  `com.mkdsh.gecko` (GeckoView). Both are 15 characters, which the Termux ELF rewrite requires.
- Built on GitHub Actions only (`release-apk.yml`); emulator smoke test in `emulator-smoke.yml`.
- Build assets: upstream does not publish a script for its ~135 MB of assets. The workflow takes the unchanged binary
  ones (engine, node, bootstrap, adb, pnpm, sharp, ripgrep, native addons) from upstream's v0.3.0 release APK
  (sha256 `5ac0299c…61682`). Their version stamps equal head's. It packs the plugin zips from head's source,
  compiles the JNI helpers with the NDK, and rebuilds `webview-compat.zip` with upstream's
  `tools/build-webview-compat.ps1` on a Windows runner. `keyinject.bin` (upstream's API account pool) is left out.

## Owner's questions (answered from the source; nothing built)
| Question | Answer | Evidence |
|---|---|---|
| Does it bundle the latest DSH harness? | **Yes, as of 2026-10-21.** The engine is `@deepseek-ai/dsh` 0.2.1-rc.2 (`MainActivity.assetStamp = "0.2.1-rc.2-1"`), the current npm `latest`. **No in-app harness update**: a newer harness needs a new APK with a rebuilt `engine.bin`. | `app/src/main/java/com/dsh/android/MainActivity.kt`, npm dist-tags |
| Does it have plugin install? | **Yes.** pnpm is bundled, so the harness's own plugin manager works. The app adds a plugin market (`dsh-android-market`: browse npm, featured list, one-tap install, Android compatibility warnings) and an installed-plugins list with remove/restore in the log screen's rescue actions. | `market-plugin/`, `docs/agents/pnpm.md`, `docs/agents/rescue-mode.md` |
| Google sign-in / API-key first screen? | **No.** The app opens straight into DSH's own web UI. A key is entered in DSH's Settings > Models. There is no Google or DeepSeek web-account sign-in route, and no separate first screen. | `MainActivity.kt` (no sign-in or key screen) |

## Emulator smoke test: what it does and does not cover
- CI's emulator is an x86_64 Android 14 image that runs arm64 apps through translation. The app ships an arm64 Termux
  bootstrap only and picks it from `Build.SUPPORTED_ABIS[0]` (x86_64 there). So on that image the engine cannot boot and
  the app shows its own "引擎启动失败 / Termux 环境未就绪" (engine failed to start / Termux not ready) screen.
  Booting the emulator with an arm64-first ABI list did not change the image's list. The app is kept as-is.
- The smoke test therefore covers: install, launch, the first screen, the app's native boot-status screen (safe mode,
  View log) and the native log viewer. **The DSH chat and settings web UI need a real arm64 device**; the test goes on
  to them automatically if the engine comes up (for example on an arm64 runner).

## Gaps (not built here)
- No Google / DeepSeek web-account route; API key only, through DSH's settings.
- No in-app update of the DSH harness from npm (only by a new APK).
- arm64-v8a only; targetSdk 28, so sideload only.
- Release signing: set the `DSHAPP_KEYSTORE_BASE64`, `DSHAPP_KEYSTORE_PASSWORD`, `DSHAPP_KEY_ALIAS` and
  `DSHAPP_KEY_PASSWORD` repository secrets before tagging. Without them, branch builds use a throwaway key and tag
  builds fail on purpose.
- `webview-compat.zip` (old-WebView fallback) is rebuilt in CI with upstream's script. Upstream's manual gates for it
  (device checks on Chrome 73/74) are not run.
