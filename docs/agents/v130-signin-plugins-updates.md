# 1.3.0: sign-in, web bridge, opaque UI, plugins, updates

> Part of [AGENTS.md](../../AGENTS.md); index: [README.md](README.md). Fork feature set of v1.3.0 (versionCode 31).

## Where things are

| Feature | Code | Tests |
|---|---|---|
| First screen ("Continue with Google" / "Use API key") | `account/SignInActivity.kt`, `res/layout/activity_sign_in.xml`; started by `MainActivity.onCreate` while `AccountStore.needsFirstScreen()` | emulator smoke (`tools/ci/emulator-smoke.py`) |
| Key store and config | `account/DshCredentials.kt` (`$DSH_HOME/.credentials.yaml`, 600, records kept), `account/ProfilePatch.kt` + `account/AccountConfig.kt` (`profiles/<p>/cordis.patch.yml`), applied by `EngineManager.ensureAccountConfig` before every launch | `DshCredentialsTest`, `ProfilePatchTest`, `AccountConfigTest`, `ApiKeyCheckTest` |
| Web account | `webbridge/DeepSeekLoginActivity.kt` (WebView + browser hand-off `mkdsh://session`), `WebToken.kt`, `DeepSeekWebClient.kt`, `DeepSeekHashV1.kt`, `PowChallenge.kt`, `WebSseParser.kt` | `WebTokenTest`, `DeepSeekHashV1Test`, `PowChallengeTest`, `WebSseParserTest` |
| Local OpenAI-compatible bridge | `webbridge/BridgeServer.kt`, `OpenAiMapping.kt`, `WebAccount.kt` (route `deepseek-web`, `apiKeyEnv DEEPSEEK_WEB_BRIDGE_KEY`) | `BridgeServerTest`, `OpenAiMappingTest`; `demo/LocalBridgeDemo.kt` for a desktop dsh |
| Opaque UI | `mobile-ui-plugin/dsh-android-mobile/lib/client.js` (`CLEAN_CSS`, `mpApplyOpaqueTokens`, `mpHideWallpaperLayers`), `MainActivity.cutoutTopPx` / `observeFullscreenBottomInset` | `tools/tests/mobile-clean.test.mjs`, `mobile-clean.browser.mjs`, fixture `tools/tests/fixtures/dsh-test-glass-theme` |
| Settings > Account / Plugins / About & updates | `client.js` (`mpAccountSection`, `mpPluginsSection`, `mpAboutSection`); native side `AppState.kt` through `PageBridge.appState()` / `appAction(name, arg)` | `tools/tests/plugin-list.test.mjs` |
| Live "/" palette | `mobile-ui-plugin/dsh-android-mobile/lib/skill-refresh.js` (re-emits `plugin-manager/changed`) | `tools/tests/skill-refresh.test.mjs` |
| Updates | `update/Versions.kt`, `EngineSlots.kt`, `HarnessUpdater.kt`, `AppUpdater.kt`, `UpdateCenter.kt`; `EngineManager` hooks `rollBackPendingHarness` / `pendingHarnessReady` / `activateHarnessUpdate` / `runWithEngineEnv` | `VersionsTest`, `EngineSlotsTest`, `HarnessUpdaterTest`, `AppUpdaterTest`, `HarnessActivationTest`, `EngineLifecycleTest`, `UpdateActionsTest` |

## Rules that are easy to break

1. **Never overwrite a model the user picked.** `AccountConfig` changes `agent-default-model` only while it names a
   provider the app set (`agnes`, `deepseek-official`, `deepseek-web`).
2. **`.credentials.yaml` holds dsh's cookie-signing secret under `records`.** Only touch the `refs` lines you own.
3. **The bridge port is random per process.** The route is rewritten before every engine launch, so the bridge must be
   running first (`EngineManager.webBridgePort`, set in `DshApplication`).
4. **Do not disguise the WebView to get Google sign-in.** Google refuses embedded WebViews on purpose; the browser route
   is the supported fallback.
5. **New bridge needs for v1.3.0 settings go through `appState()` / `appAction()`**, not new `PageBridge` methods,
   so the two flavor whitelists stay in step (system `SystemWebHost`, gecko `content.js` + manifest version).
6. **An updated harness is `engine/` with stamp `npm:<v>`.** `ensureExtracted` keeps it only while it is newer than the
   bundled engine; `ensureWebViewCompat` never applies the compat frontend to it. A promoted harness stays pending until
   Ready; a start failure while pending rolls back **before** `RescueBoot.recordFailure` (so the rescue ladder does not
   count it) and the version goes to `engine.bad`. Slot transactions recover before extraction; lifecycle serialization
   keeps extraction/startup and stop/promote/recovery from overlapping, and candidate processes are reaped before file moves.
7. **Changing `client.js` or `skill-refresh.js` → bump `MOBILE_UI_VERSION` and `-CompatVersion`** (see
   [asset-stamps.md](asset-stamps.md)); CI packs the zip from source.

## Tests

```bash
node --test tools/tests/*.test.mjs
./gradlew --no-daemon testSystemDebugUnitTest
```

## Pass 2 review

See [the requirement review and verification record](../REVIEW_130_P2.md). Regression coverage includes canceled
account operations, incomplete web streams, pending-harness rollback and interrupted slot activation, retryable
updates, theme-owned inline colors, plugin ownership and unavailable file watchers. The emulator remains a native
screen smoke test; account service access and Android engine boot still require a real account and arm64 device.
