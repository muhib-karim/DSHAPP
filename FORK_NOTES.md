# Fork notes (muhib-karim/DSHAPP)

Status of the fork and answers to the owner's questions; notes for review.

## What the fork ships
- **1.3.0 / versionCode 31** (both flavors): two sign-ins, opaque UI, Settings > Plugins, in-app updates (see README
  "What's new in 1.3.0" and CHANGELOG). Before that: upstream head `fce1bc0` (0.3.3, versionCode 29) as 0.3.4 / 30, with package names `com.mkdsh.droid` (system WebView) and
  `com.mkdsh.gecko` (GeckoView). Both are 15 characters, which the Termux ELF rewrite requires.
- Built on GitHub Actions only (`release-apk.yml`); emulator smoke test in `emulator-smoke.yml`.
- Build assets: upstream does not publish a script for its ~135 MB of assets. The workflow takes the unchanged binary
  ones (engine, node, bootstrap, adb, pnpm, sharp, ripgrep, native addons) from upstream's v0.3.0 release APK
  (sha256 `5ac0299c…61682`). Their version stamps equal head's. It packs the plugin zips from head's source,
  compiles the JNI helpers with the NDK, and rebuilds `webview-compat.zip` with upstream's
  `tools/build-webview-compat.ps1` on a Windows runner. `keyinject.bin` (upstream's API account pool) is left out.

## Owner's questions
| Question | Answer (1.3.0) | Evidence |
|---|---|---|
| Does it bundle the latest DSH harness? | **Yes**: `@deepseek-ai/dsh` 0.2.1-rc.2 is npm `latest` (rc.3 is on `next`). CI warns when npm `latest` moves past the bundled engine. **In-app update**: Settings > About & updates (and a daily check) installs a newer `latest` with the bundled pnpm, keeps the old harness and rolls back if the new one does not boot. | `MainActivity.assetStamp`, `update/HarnessUpdater.kt`, `release-apk.yml` "Harness freshness" |
| Plugin install? | **Yes**, now in Settings > Plugins (install by npm name or the marketplace, on/off, remove), next to the built-ins. The "/" palette refreshes after an install or a new skill file. | `mobile-ui-plugin/dsh-android-mobile/lib/client.js` (`mpPluginsSection`), `lib/skill-refresh.js` |
| Google sign-in / API-key first screen? | **Yes**: first screen with "Continue with Google" and "Use API key". | `account/SignInActivity.kt`, `webbridge/` |

## How 1.3.0 was checked
- JVM and node unit tests (in CI before every build).
- The web UI parts against the real `@deepseek-ai/dsh` 0.2.1-rc.2 (and rc.3) running on a desktop, rendered at phone
  size with the mobile plugin: opaque surfaces with a wallpaper/glass test theme
  (`tools/tests/fixtures/dsh-test-glass-theme`), the "/" palette refreshing after a new skill file, Settings > Plugins
  installing, switching and removing a real npm bundle (`dsh-preset-su`) through dsh's plugin manager.
- The web-account bridge serving a real dsh agent turn with a tool call (scripted web chat,
  `app/src/test/.../demo/LocalBridgeDemo.kt`). The live chat.deepseek.com path needs a signed-in account and was not run.
- The harness update path: the updater's `package.json`/`.npmrc` and pnpm 11 arguments installing 0.2.1-rc.3 into a
  hoisted tree that boots (`dsh web:`). On a phone it additionally runs the app's Android patch steps.
- The first screen and the API-key screen (with DeepSeek's real 401 for an invalid key) on the CI emulator.

## Emulator smoke test: what it does and does not cover
- CI's emulator is an x86_64 Android 14 image that runs arm64 apps through translation. The app ships an arm64 Termux
  bootstrap only and picks it from `Build.SUPPORTED_ABIS[0]` (x86_64 there). So on that image the engine cannot boot and
  the app shows its own "引擎启动失败 / Termux 环境未就绪" (engine failed to start / Termux not ready) screen.
  Booting the emulator with an arm64-first ABI list did not change the image's list. The app is kept as-is.
- The smoke test therefore covers: install, launch, the 1.3.0 first screen ("Continue with Google" and "Use API key"),
  the API-key screen with the error for an invalid key, the app's native boot-status screen (safe mode, View log) and
  the native log viewer. **The DSH chat and settings web UI need a real arm64 device**; the test goes on
  to them automatically if the engine comes up (for example on an arm64 runner).

## Gaps (not built or not verifiable here)
- The web account depends on chat.deepseek.com's web API (not a public API; it can change). Replies have no images
  and tools go through a text protocol, so agent runs are less reliable than with an API key.
- Google may refuse its sign-in inside the app's WebView; the browser route (bookmark or paste) is the fallback.
  dsh also ships an official DeepSeek Platform account sign-in (PKCE, `dsh-deepseek-account-platform`), which was not
  wired into the app.
- In-app harness updates were not run on a phone in this round (no arm64 device in CI). They need ~0.5 GB free while
  installing; on the system-WebView build an updated harness needs WebView 94+ (the old-WebView compat frontend is
  built for the bundled harness only).
- App updates need a published GitHub release (none is tagged by this work) and the `DSHAPP_KEYSTORE_*` secrets, so
  that releases are signed with a stable key.
- arm64-v8a only; targetSdk 28, so sideload only.
- Release signing: set the `DSHAPP_KEYSTORE_BASE64`, `DSHAPP_KEYSTORE_PASSWORD`, `DSHAPP_KEY_ALIAS` and
  `DSHAPP_KEY_PASSWORD` repository secrets before tagging. Without them, branch builds use a throwaway key and tag
  builds fail on purpose.
- `webview-compat.zip` (old-WebView fallback) is rebuilt in CI with upstream's script. Upstream's manual gates for it
  (device checks on Chrome 73/74) are not run.
