# 1.3.0: sign-in, web bridge, opaque UI, plugins, updates

> Part of [AGENTS.md](../../AGENTS.md); index: [README.md](README.md). Fork feature set of v1.3.0 (versionCode 31).

## Where things are

| Feature | Code | Tests |
|---|---|---|
| First screen ("Continue with Google" / "Use API key") | `account/SignInActivity.kt`, `res/layout/activity_sign_in.xml`; started by `MainActivity.onCreate` while `AccountStore.needsFirstScreen()` | emulator smoke (`tools/ci/emulator-smoke.py`) |
| Key store and config | `account/DshCredentials.kt` (`$DSH_HOME/.credentials.yaml`, 600, records kept), `account/ProfilePatch.kt` + `account/AccountConfig.kt` (`profiles/<p>/cordis.patch.yml`), applied by `EngineManager.ensureAccountConfig` before every launch | `DshCredentialsTest`, `ProfilePatchTest`, `AccountConfigTest`, `ApiKeyCheckTest` |
| Web account | `webbridge/DeepSeekLoginActivity.kt` (WebView + browser hand-off `mkdsh://session`), `WebToken.kt`, `DeepSeekWebClient.kt`, `DeepSeekHashV1.kt`, `PowChallenge.kt`, `WebSseParser.kt`; expiry flag in `account/AccountStore.kt` | `WebTokenTest`, `DeepSeekHashV1Test`, `PowChallengeTest`, `WebSseParserTest`, `AccountStoreTest` |
| Local OpenAI-compatible bridge | `webbridge/BridgeServer.kt`, `OpenAiMapping.kt`, `WebAccount.kt` (route `deepseek-web`, `apiKeyEnv DEEPSEEK_WEB_BRIDGE_KEY`) | `BridgeServerTest`, `OpenAiMappingTest`; `demo/LocalBridgeDemo.kt` for a desktop dsh |
| Opaque UI | `mobile-ui-plugin/dsh-android-mobile/lib/client.js` (`CLEAN_CSS`, `mpApplyOpaqueTokens`, `mpHideWallpaperLayers`), `MainActivity.cutoutTopPx` / `observeFullscreenBottomInset` | `tools/tests/mobile-clean.test.mjs`, `mobile-clean.browser.mjs`, fixture `tools/tests/fixtures/dsh-test-glass-theme` |
| Settings > Account / Plugins / About & updates | `client.js` (`mpAccountSection`, `mpPluginsSection`, `mpAboutSection`); native side `AppState.kt` through `PageBridge.appState()` / `appAction(name, arg)` | `tools/tests/plugin-list.test.mjs`, `tools/tests/account-expiry.test.mjs`, `tools/tests/account-key.test.mjs` |
| Live "/" palette | `mobile-ui-plugin/dsh-android-mobile/lib/skill-refresh.js` (re-emits `plugin-manager/changed`) | `tools/tests/skill-refresh.test.mjs` |
| Updates | `update/Versions.kt`, `EngineSlots.kt`, `HarnessUpdater.kt`, `AppUpdater.kt`, `UpdateCenter.kt`; `EngineManager` hooks `rollBackPendingHarness` / `pendingHarnessReady` / `activateHarnessUpdate` / `activatePreviousHarness` / `runWithEngineEnv` | `VersionsTest`, `EngineSlotsTest`, `HarnessUpdaterTest`, `AppUpdaterTest`, `HarnessActivationTest`, `EngineLifecycleTest`, `UpdateActionsTest`, `DownloadGuardTest` |

## Settings actions added in 1.3.0

All go through the existing `PageBridge` pair; no new bridge method. The client sends `appAction(name, arg)` and the
host re-validates everything it needs.

| Action | Argument | Native path | Client control | Tests |
|---|---|---|---|---|
| `harnessRollback` | previous harness version, e.g. `0.2.1-rc.1` | `AppState.restorePreviousHarness` checks the version shape, then `EngineManager.activatePreviousHarness` re-checks the slot and `UpdateActions.rollback` refuses unless a previous harness exists and the updater is idle | About card, **Restore previous version** (`data-mp-update-action="harnessRollback"`) | `UpdateActionsTest`, `EngineSlotsTest`, `update-controls.test.mjs` |
| `accountReplaceKey` | none | `AppState.replaceApiKey` refuses unless the mode is `api_key`, then starts `SignInActivity` with `EXTRA_REPLACE_KEY` | Account card, **Replace API key** (`data-mp-account-replace`) | `DshCredentialsTest`, `account-key.test.mjs` |
| `openLogs` | none | the `openLogs` branch in `AppState.action` starts the host's `LogViewerActivity`; the page never renders or copies logs | About card, **Open logs** (`data-mp-diagnostics-action="openLogs"`) | `DiagnosticsTest`, `update-controls.test.mjs` |
| `cancelAppUpdate` | none | `AppState.cancelAppUpdate` → `UpdateCenter.cancelAppUpdate` → `DownloadGuard.cancel`, which invalidates the generation before disconnecting | About card, **Cancel download** while `canCancel` (`data-mp-update-action="cancelAppUpdate"`) | `UpdateActionsTest`, `DownloadGuardTest`, `update-controls.test.mjs` |
| `openReleasePage` | none (the page comes from stored state, never from the page) | `AppState.openReleasePage` re-checks `AppUpdater.isTrustedReleasePage` before `ACTION_VIEW` | About card, **Release page** (`data-mp-update-action="openReleasePage"`) | `AppUpdaterTest`, `update-controls.test.mjs` |

## Version stamps after pass 3

| Source set | Stamp(s) | Current |
|---|---|---|
| Mobile UI plugin (`mobile-ui-plugin/dsh-android-mobile/lib/client.js`, `skill-refresh.js`) | `EngineManager.MOBILE_UI_VERSION`, `tools/build-webview-compat.ps1 -CompatVersion`, packaged `app/src/main/assets/mobile-ui.zip` | `1.0.0-109`, `43`, repacked |
| Bundled preset skills | `PRESET_SKILLS_VERSION` | `1.0.0-4` |

Any edit under `mobile-ui-plugin/dsh-android-mobile/` bumps **both** the mobile-UI and compat stamps, regenerates the
zip and byte-compares the unpacked tree with the source directory (`diff -r`). Native-only changes do not touch these
stamps; docs and tests do not bump them either. See [asset-stamps.md](asset-stamps.md).

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
   A manual **Restore previous version** (About & updates) goes through the same `slots.rollback()` and is refused while
   `engine.next` exists, a harness is pending or a journal is present, so it cannot race an install or a recovery; the
   rejected version is marked bad exactly like an automatic rollback.
7. **Changing `client.js` or `skill-refresh.js` → bump `MOBILE_UI_VERSION` and `-CompatVersion`** (see
   [asset-stamps.md](asset-stamps.md)); CI packs the zip from source.
8. **The Account card never sees a full API key.** `DshCredentials.keyHint` returns `••••` plus the last four
   characters only when `problem()` accepts the value, and `AppState` reads `DEEPSEEK_API_KEY` once to expose that hint
   in `api_key` mode. **Replace API key** starts `SignInActivity` with `EXTRA_REPLACE_KEY`; it opens on the key panel,
   keeps the account mode (Back just closes it), and on success writes the key and restarts the engine without signing
   the web account or the user's setup out.
9. **A 401/403 (or 40001–40003) from chat.deepseek.com marks the web session expired exactly once.**
   `BridgeServer`'s `onSessionExpired` sets `AccountStore.webExpired`; `AppState` exposes it only in `web` mode, and the
   Account card offers **Sign in again** through the existing `accountSwitch` action. Saving a new token, signing out of
   the web account, switching to an API key and a full sign-out all clear the flag. Wrong bearer tokens and other
   upstream errors must not set it.
10. **A cancelled APK download must stay cancelled.** `DownloadGuard` owns a generation number: `cancelAppUpdate`
    invalidates it before disconnecting the socket, and the worker publishes progress, `ready` and `error` only from
    `publish`/`finish` for its own generation. The download goes to a generation-specific `.part` file and is renamed
    only at commit, so a retry never truncates an APK an installer is reading. Cancelling clears `cache/updates`
    except the verified APK last handed to the installer, and sets the non-error `cancelled` state that still offers
    **Update** for a retry. Never delete or rewrite that installer APK while the package manager may be reading it.

## Tests

```bash
node --test tools/tests/*.test.mjs
./gradlew --no-daemon --max-workers=2 :app:testSystemDebugUnitTest :app:testGeckoDebugUnitTest :app:assembleDebug
./gradlew --stop
# Chromium-only, with Playwright resolvable from the test (5 checks):
node --test tools/tests/mobile-clean.browser.mjs
```

Manual cancel check (arm64 device, an APK release is offered): start the download, tap **Cancel download**, and confirm
the badge turns grey **Cancelled**, progress disappears, no error appears and **Update** remains for a retry. Then start
again, let it reach **Opening installer**, and while the installer screen is open start another download: the installer
must still complete, because the verified APK is not deleted or rewritten under it.

## Pass 2 review

See [the requirement review and verification record](../REVIEW_130_P2.md). Regression coverage includes canceled
account operations, incomplete web streams, pending-harness rollback and interrupted slot activation, retryable
updates, theme-owned inline colors, plugin ownership and unavailable file watchers. The emulator remains a native
screen smoke test; account service access and Android engine boot still require a real account and arm64 device.

## Pass 3 verification

On 2026-10-23 UTC the both-flavor gate passed: 200 JVM tests per flavor (400 total), 66 Node tests, 5 Chromium checks,
and both debug APKs assembled. The unpacked `mobile-ui.zip` is byte-identical to its source and the stamps above are the
ones in the tree. The emulator smoke script could not run here (no emulator binary, system image or KVM), so the native
API-key screenshot 02 remains its pre-Paste CI capture and the README says so; screenshots 08 and 09 were re-rendered
from the real DSH web UI and plugin at phone size with demo values. Nothing was pushed, merged, tagged or released.
