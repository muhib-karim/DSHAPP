# DSHAPP 1.3.0 Pass 2 Review and Implementation Plan

> For agentic workers: execute the checked tasks below with test-driven development, independent subsystem review,
> and verification before completion. The frozen requirements authorize execution, commits, push and CI monitoring.

**Goal:** complete and correct the first pass of 1.3.0, preserving the official DSH frontend and plugin logic.
**Architecture:** retain Kotlin account/bridge/update services and the mobile plugin overlay; fix their boundaries
and lifecycle handling. Keep Android compatibility patches in the native host. No replacement chat or slash palette.
**Tech stack:** Kotlin/JVM, Android Views/WebView/GeckoView, Node test runner, browser checks, GitHub Actions.
**Spec:** the original frozen owner requirements supplied outside the repository, summarized by `docs/PLAN_130.md`.
**Reviewed baseline:** `277daa8cb7ee20c8279a558b64375d8b4f115b6b`, including `git log --stat origin/main..HEAD`.

## Global constraints

- Preserve fork application IDs `com.mkdsh.droid` / `com.mkdsh.gecko`, targetSdk 28, version 1.3.0 / versionCode 31.
  The upstream package examples in AGENTS.md are historical; the fork IDs and runtime ELF rewrite already coexist.
- Solid light/dark surfaces, no wallpaper/glass, no header/cutout/IME overlap; keep upstream features and slash logic.
- Credentials stay private (mode 600); local bridge binds loopback, random port, per-install bearer secret.
- Bump mobile/compat stamps together for plugin changes; byte-compare every generated plugin zip with source.
- Each new commit uses the configured owner identity and trailer `Pass: 2 (Codex gpt-6-astra)`.
- Before every push: clean gitleaks for `origin/main..HEAD`, clean public-content diff gate, green local build/tests.
- Gradle: `--no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2g`, then `bash ./gradlew --stop`;
  start only with at least 3 GB available memory. Do not change global machine limits or interrupt other projects.
- Push only `feature/v1.3.0`; no merge, tag or release. Release APK and Emulator smoke must pass on the final head.

## Requirement review (baseline evidence)

| Requirement | Status | Evidence and gap |
|---|---|---|
| Remove animated backgrounds; opaque light/dark sheets, palettes, header, preview | Partly | `client.js:1291` CLEAN_CSS and `:1431` token guard exist, but task-plan panel at `:713` remains alpha .97 and `#root` background is omitted at `:1301`. Original six defect screenshots inspected. |
| Header/status bar/cutout/IME spacing | Met in implementation; device verification limited | `MainActivity.kt:2384,2428` cutout helper and fullscreen bottom observer; native geometry requires arm64 device acceptance, emulator is not an engine test. |
| First screen: Google and API key | Met | `SignInActivity.kt:31`, `activity_sign_in.xml`; smoke `emulator-smoke.py:189` requires both paths. |
| API validation, own DSH config, mode 600, actionable error, chat | Partly | `ApiKeyCheck.kt:18`, `DshCredentials.kt:93`, `AccountConfig.kt:18`; `SignInActivity.kt:80` writes credentials before checking whether login was canceled/destroyed. |
| DeepSeek web session, PoW, authenticated local OpenAI bridge | Partly | `DeepSeekLoginActivity.kt:97`, `DeepSeekWebClient.kt:35`, `BridgeServer.kt:36`; `DeepSeekWebClient.kt:98` marks interrupted EOF successful. `OpenAiMapping.kt:54` skips the prompt limit for a single message. |
| Settings sign-out and switch account | Partly | `AppState.kt:38,55`; switching does not clear/avoid existing WebView login state, so polling at `DeepSeekLoginActivity.kt:50` immediately reuses the old account. Sign-out must invalidate outstanding login attempts. |
| Settings Plugins list, install/market, enable/disable, remove | Partly | `client.js:9438,9540`; ownership inferred from npm scope at `:9444` incorrectly makes user-installed official packages non-removable; read-only server policy must be honored. |
| Upstream slash palette refreshes live, regression test | Partly | `skill-refresh.js:31`, `index.js` wiring and five node tests preserve upstream events. Watcher allocation failure silently loses disk refresh; local baseline exposed ENOSPC. Missing/new roots and disposal need coverage. |
| Ship latest harness | Met at review | npm `latest` verified as `0.2.1-rc.2`, matching `MainActivity.assetStamp`; CI freshness check at `release-apk.yml:77`. |
| Daily and About harness update with bundled pnpm and rollback | Partly | `HarnessUpdater.kt:23`, `UpdateCenter.kt:137`, `EngineSlots.kt:32`; bad candidate config reaches destructive preflight repair (`EngineManager.kt:4855,5254,6715`); timeout rollback leaves the candidate running (`:7497,7653`); gateway failure can confirm the candidate (`:7359`). Crash/promotion recovery also needs tests. |
| GitHub app update/download/install | Partly | `UpdateCenter.kt:188`, `AppUpdater.kt:12`, manifest FileProvider; permission/error sets status that hides Update (`client.js:9721`), leaving no retry. |
| 1.3.0/31, changelog, EN/CN README screenshots, fork gaps, credits | Met; update for P2 | `app/build.gradle.kts:38,177`, CHANGELOG 1.3.0, both README feature sections, nine screenshots, FORK_NOTES and THIRD-PARTY. Installation tables still show upstream APK IDs. |
| Credentials, mapping, PoW, versions, plugins tests and emulator screens | Partly | `app/src/test`, `tools/tests`, smoke script cover baseline happy paths; missing lifecycle, failed-stream, updater integration and visual DOM regressions. Baseline CI runs 39962242220 / 39962242246 passed. |

Paths abbreviated above: Kotlin files are under `app/src/main/java/com/dsh/android/` in their account/webbridge/update
subdirectories; `client.js` and `skill-refresh.js` are in `mobile-ui-plugin/dsh-android-mobile/lib/`.

## Findings and review focus

1. **High: account changes must be deliberate and atomic.** Cancel/rotation/sign-out cannot allow an old network
   result to save credentials; switching web accounts must let the user choose another account. Sign-out cannot
   report success when persisted credentials could not be removed. Preserve existing
   DSH records and user-selected models; browser hand-off must remain explicit and bounded.
2. **High: stream failure is not completion.** Reject incomplete/malformed upstream streams, never release partial
   tool calls as successful; consistently enforce prompt bounds without silently destroying an agent transcript.
3. **High: pending harness failures belong to the updater.** Failed candidate validation or gateway startup
   restores the old slot without quarantining user config or marking a broken gateway healthy. Stop and reap a live candidate before swapping;
   interrupted promotions and failed rollbacks must preserve a runnable old version. Slot replacement must wait for
   in-progress extraction and startup before stopping and moving the engine.
4. **Required: retry remains reachable.** Permission denial, a download failure or a canceled installer must not
   permanently hide app/harness update controls. Refresh checks clear transient errors without masking eligibility.
5. **Required: real UI/plugin ownership and watcher failures.** No alpha task-plan/root wallpaper; retain theme
   changes, real media previews and upstream plugin policy. Refresh survives unavailable watchers without polling
   an unbounded dependency tree; watcher disposal must stop work.

## Ordered implementation tasks

### Task 1: Account and web bridge correctness

**Files:** account `SignInActivity.kt`, `AccountStore.kt`, account lifecycle helper if needed; `AppState.kt`;
webbridge `DeepSeekLoginActivity.kt`, `DeepSeekWebClient.kt`, `WebSseParser.kt`, `OpenAiMapping.kt`, related JVM tests.
**Interfaces:** preserve AccountConfig / PageBridge contracts; login attempts own cancellation/generation and publish
credentials plus mode only when current; WebChat must fail rather than synthesize successful Finished on EOF.

- [x] Add failing regression tests for canceled/superseded login work, web account switching, incomplete/error streams,
  oversized single/system/tool prompts, and shutdown during an active bridge request. Use loopback HTTP fixtures
  for the real web-client protocol; stopping the bridge must cancel accepted sockets and upstream work.
- [x] Fix the narrow lifecycle/protocol boundaries; retain Google browser fallback and owner-only credential writes.
- [x] Run targeted JVM tests followed by the full JVM suite, review the diff, commit with the pass trailer.

Expected: delayed old results make no account changes; a new account can be selected; interrupted streams fail;
normal text/tool streaming and PoW vectors remain green.

### Task 2: Safe harness activation and retryable app updates

**Files:** `update/EngineSlots.kt`, `HarnessUpdater.kt`, `UpdateCenter.kt`, `EngineManager.kt`; pure lifecycle helper
if needed; updater JVM tests. Coordinate update-control UI changes with Task 3.
**Interfaces:** preserve disk slots `engine`, `engine.next`, `engine.prev`; pending lifecycle protects user profile;
update state exposes eligibility independently from transient progress/errors.

- [x] Add failing tests for interrupted slot promotion, failed rollback, pending candidate preparation/boot failure,
  timeout with live process, and permission/error/canceled-install retry eligibility.
- [x] Make promotion recoverable and stop/reap the candidate before
  rollback. Route pending preflight/runtime failures to rollback ahead of destructive profile repair.
- [x] Restore reachable app/harness retries; reuse a verified ready APK after a canceled installer where possible.
- [x] Run targeted then full JVM tests and commit the verified changes.

Expected: old harness and user configuration survive candidate failure; recovery can start the old process;
permission grant and transient errors allow another update attempt.

### Task 3: Opaque UI, correct plugin controls, reliable live skills

**Files:** mobile plugin `client.js`, `skill-refresh.js`, `index.js` as needed; node/browser regression tests;
`EngineManager.MOBILE_UI_VERSION`, `tools/build-webview-compat.ps1`, asset-stamp documentation.
**Interfaces:** retain upstream `pluginManager` methods and `plugin-manager/changed`; use server-provided ownership,
removable and read-only fields; consume update eligibility from Task 2.

- [x] Add failing tests for root wallpaper/task-plan opacity, custom/same-scheme theme changes, user-installed official plugin removal and read-only
  toggles, missing roots/watch failure/disposal, and update retry rendering.
- [x] Preserve theme-owned inline tokens and same-scheme changes; fix overlay rules and ownership mapping. Provide
  bounded watcher fallback and delayed-root recovery, without
  forking slash-palette logic or introducing a permanent high-frequency whole-tree scan.
- [x] Run all node tests and browser checks at phone size in light/dark, with wallpaper fixture, real preview,
  task plan, menus and Settings; check live skill refresh without reload.
- [x] Bump mobile stamp to `1.0.0-102` and compat to `36`; pack and byte-compare all plugin zips; commit.

Expected: all actual surface checks pass; user packages are removable according to upstream policy; live refresh
works with both normal watchers and forced allocation failure; no timer/observer loop after disposal.

### Task 4: Docs, smoke and delivery gates

**Files:** `CHANGELOG.md`, both README files, `FORK_NOTES.md`, `docs/agents/v130-signin-plugins-updates.md`, this review,
`docs/PROJECT_BRAIN.md`, project memory pointers, `tools/ci/emulator-smoke.py`, build/test workflows as needed.

- [x] Extend native smoke for canceled API entry/reopening and a local validation error before the network check;
  retain the honest x86_64 scope and run it in CI. Make the Gradle wrapper executable for documented commands.
- [x] Update 1.3.0 docs and fork installation tables with P2 fixes and actual validation limitations; keep 31.
- [x] Run both local debug builds and JVM suites, all node tests, Python syntax/tests, plugin zip byte comparisons;
  stop Gradle afterwards. Local APKs lack upstream binary assets; CI assembles the full release artifacts.
- [x] Independent final review and fix required findings with regression tests. Record completed evidence below.

Post-commit delivery gates: check identity/trailers, gitleaks and the forbidden public-content diff; push only
`feature/v1.3.0`; watch Release APK and Emulator smoke on the exact pushed head, reading failed logs and correcting
any failure. Completion requires both green; the final `P2_DONE` line records the head and both run IDs. This record
is committed before those gates so recording CI does not create another untested head.

## Execution evidence

Plan committed before implementation. Review correction: upstream dump-config is boot-free, so missing native
addons do not inherently fail preflight. The confirmed issue is destructive repair on an actually invalid pending
candidate; keep existing addon ordering unless a test proves it wrong. Subsequent task commits update this section
and PROJECT_BRAIN.


- Account/bridge: targeted 103-test run passed after regression failures for cancellation, stale publication,
  incomplete streams and prompt bounds. Final review added sign-out storage-failure coverage: 8 tests ran,
  2 intended assertions failed before the ordering fix. Failed credential removal now preserves the account and
  bridge and displays a retry message; superseded callbacks cannot clear a newer web session.
- Updater: targeted 44-test run passed, covering slot transaction recovery, failed rollback, stopped/reaped
  candidates, validation and gateway failures, downloaded APK reuse/checksum and retry eligibility. Final review
  reproduced replacement racing with boot preparation (2 concurrency tests, 1 intended failure); lifecycle
  serialization now guards extraction, startup and replacement as one operation.
- Mobile: final dependency-free Node suite passed 34/34; real Chromium suite passed 5/5, including light/dark
  opaque surfaces, wallpaper removal with visible image preview, same-scheme custom theme updates and disposal.
  All 13 generated plugin archives byte-match their complete source file sets. Smoke script syntax parses.
- Limitations: desktop Chromium verifies the actual upstream frontend and overlay, not Android WebView inset/IME
  geometry. The x86_64 CI emulator verifies native screens and API-entry behavior, not the arm64 Termux engine.
  A real signed-in DeepSeek/Google account and arm64 device were not available for live service/device acceptance.

- Final native gate: `:app:testSystemDebugUnitTest :app:testGeckoDebugUnitTest :app:assembleSystemDebug
  :app:assembleGeckoDebug` passed together, using no daemon, two workers and a 2 GB Gradle heap. System and Gecko
  each ran 152 tests with zero failures/errors/skips; both debug APKs assembled. Gradle stopped afterward. Local
  APKs omit upstream binary runtime assets; Release APK CI assembles and verifies the complete asset set.
- Independent review identified and verified fixes for failed sign-out persistence and boot/promotion concurrency;
  no critical or important findings remain. Optional follow-up: define whether choosing browser login should cancel
  an already-running verification from the in-app page. No live Google/DeepSeek authentication was claimed.
- npm `latest` rechecked before delivery: `0.2.1-rc.2`, matching the bundled harness. Version remains 1.3.0 / 31.

- Live upstream frontend check used DSH 0.2.1-rc.2 with the final mobile host/client at phone size: after adding and
  removing a temporary skill, native change events caused `skills/list` to refetch and slash results updated without
  document reload. Actual Settings surfaces in light/dark had opaque RGB backgrounds, opacity 1 and no backdrop
  filter. This was an isolated local engine/session and did not send a request to an external model provider.
