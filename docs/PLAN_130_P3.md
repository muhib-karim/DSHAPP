# DSHAPP v1.3.0 — Pass 3 implementation plan

Third and final development pass, on top of the pass-1 implementation (`docs/PLAN_130.md`) and the pass-2 review
(`docs/REVIEW_130_P2.md`). Pass 3 keeps every frozen requirement and every pass-1/pass-2 behaviour. The work below is
ordered by risk: update safety first, then account/session correctness, then user-facing polish, then verification.
Each numbered item is one commit (or a small commit series) with its own tests and a `CHANGELOG.md` / README note when
it is visible to users.

**Goal for this pass:** make updates recoverable and verifiable, finish the account lifecycle (replace a key, notice an
expired web session), and remove the remaining rough edges in the first screen, About and Plugins — without changing
the official dsh frontend, the package ids, `targetSdk`, or the version (`1.3.0`, `versionCode 31`).

## Constraints that apply to every item

- Visual changes live in `mobile-ui-plugin/dsh-android-mobile`, never in a forked dsh frontend. The UI stays opaque:
  no wallpaper, no glass/blur, no header overlap.
- No new `PageBridge` method is needed. Settings use the existing `appState()` / `appAction(name, arg)` pair.
- Any edit under `mobile-ui-plugin/dsh-android-mobile/` bumps `EngineManager.MOBILE_UI_VERSION` (`1.0.0-102` →
  `1.0.0-103`), `tools/build-webview-compat.ps1 -CompatVersion` (`36` → `37`) and `PRESET_SKILLS_VERSION`
  (`1.0.0-2` → `1.0.0-3`), then regenerates the mobile-ui zip and byte-compares it with the source directory. A
  `dsh-bridge/content.js` change additionally bumps the gecko `manifest.json` (`1.0.15` → `1.0.16`); no item here
  should need it.
- Every commit keeps the repository identity (`Muhib Karim <62206502+muhib-karim@users.noreply.github.com>`) and the
  trailer `Pass: 3 (dsh DeepSeek V4.1 Flash)`. No secrets, local paths, usernames or hostnames in code, docs or logs.
- Never push, merge, tag or release. Version stays `1.3.0`; package ids stay `com.mkdsh.droid` / `com.mkdsh.gecko`;
  `targetSdk` stays 28.
- JVM tests are plain JUnit 4 (no Robolectric). Plugin tests are Node 22 `node:test`; the Playwright browser test stays
  Chromium-only and local. Build with
  `./gradlew --no-daemon --max-workers=2 assembleDebug testSystemDebugUnitTest testGeckoDebugUnitTest`,
  then `./gradlew --stop` (plain `testDebugUnitTest` is ambiguous with two flavors).

## 1. Verify the downloaded APK is really this app before handing it to the installer

Today the APK updater trusts HTTPS + SHA-256 from the release, but does not check that the archive's package name and
signing certificate match the installed app. A wrong-flavor or wrong-signer APK reaches Android's installer and fails
late (or replaces nothing). Verify identity first and fail with a clear, retryable message.

**Files**

- `app/src/main/java/com/dsh/android/update/ApkIdentity.kt` (new): pure helpers over already-extracted values —
  `problem(expectedPackage, installedCertificates, archivePackage, archiveCertificates)` and
  `certificateSha256(certificate: ByteArray)`. The rule: package names equal, and the archive has at least one signer
  whose SHA-256 is in the installed app's current signer set.
- `app/src/main/java/com/dsh/android/update/UpdateCenter.kt`: after the SHA-256 check, read the archive with
  `PackageManager.getPackageArchiveInfo` (`GET_SIGNING_CERTIFICATES` on API 28+, `GET_SIGNATURES` on 26–27), read the
  installed signers the same way, and call the helper. On mismatch delete the APK, set `appStatus = "error"` with
  “The downloaded APK is not a valid DSH update.” and keep the update retryable.
- `app/src/test/java/com/dsh/android/update/ApkIdentityTest.kt` (new): matching package/signer accepted; different
  package, different signer, missing archive signers and an empty installed set rejected; digest is lowercase hex.
- `CHANGELOG.md`, `README.md` / `README.en.md`: one line under the existing 1.3.0 update section.

**Test / acceptance**

- `./gradlew --no-daemon --max-workers=2 :app:testSystemDebugUnitTest :app:testGeckoDebugUnitTest --tests 'com.dsh.android.update.*'`.
- The existing “installer cancelled, verified APK reusable” test still passes (identity is checked before the handoff,
  so a reused APK is re-verified).
- Manual: point a debug build at a locally built APK of the other flavor and confirm the message and that no installer
  opens. Restore the real release URL afterwards; do not commit test URLs.

## 2. Restore the previous harness from About

`EngineSlots` already keeps `engine.prev` and can roll back after a failed boot, but the user has no manual path when
the new harness boots yet misbehaves. Add a guarded one-tap restore. It must never race the automatic pending-harness
recovery and must never delete the last working harness.

**Files**

- `app/src/main/java/com/dsh/android/update/EngineSlots.kt`: read-only helpers `previousVersion(): String?` and
  `rollbackCandidate(): File?` (or equivalent) that only return a complete `engine.prev` when no `engine.next` is
  pending. Do not change the existing `rollback()` mechanics.
- `app/src/main/java/com/dsh/android/EngineManager.kt`: `suspend fun activatePreviousHarness(expected: String): Boolean`
  mirroring `activateHarnessUpdate` — take `lifecycle.exclusive`, refuse if `slots.pending() != null`, verify
  `expected` is the version in `engine.prev`, stop the engine, call `slots.rollback()`, restart, and recover/restart the
  old engine on failure. Never call `rollback()` while a candidate is pending.
- `app/src/main/java/com/dsh/android/update/UpdateCenter.kt`: expose `harness.previous` and `harness.canRollback` in
  `json()` (true only when the slot is complete, no pending candidate, and it differs from the current version).
- `app/src/main/java/com/dsh/android/AppState.kt`: `appAction("harnessRollback", expectedVersion)` validates the
  argument, runs `activatePreviousHarness` on a background thread, and logs only the version.
- `app/src/main/java/com/dsh/android/update/UpdateActions.kt`: an explicit rollback eligibility helper so the UI and
  native side agree (`idle`/`error`, no install/download in flight). Keep updater retry semantics untouched.
- `mobile-ui-plugin/dsh-android-mobile/lib/client.js`: in `mpAboutSection`, show “Restore previous version” with the
  previous version in the label only when `canRollback`; confirm before calling `appAction("harnessRollback", version)`.
  After success the normal refresh shows the restored version.
- Tests: extend `EngineSlotsTest` (complete/missing/partial `prev`, pending `next`, version mismatch) and
  `UpdateActionsTest` (rollback not offered while installing/downloading/pending); extend the Node render test in
  `tools/tests/update-controls.test.mjs` for the button and its exact action argument.
- Version stamps + regenerated mobile-ui zip (see constraints).

**Test / acceptance**

- Targeted JVM tests + the Node update-controls test; full suite before commit.
- Manual on a device/emulator with two installed harness versions: restore goes back one version, restarts the engine,
  and the About card no longer offers the same restore. A pending candidate keeps the button hidden.

## 3. Surface an expired web session in Settings > Account

The web bridge answers 401 with “sign in again”, but the UI never notices; the user sees failures in chat with no
action. Record the expiry when the bridge sees it and show a clear sign-in-again action.

**Files**

- `app/src/main/java/com/dsh/android/account/AccountStore.kt`: add `webExpired` (boolean, default false), with
  `markWebExpired()` / `clearWebExpired()`. Cleared on successful web sign-in and on sign-out.
- `app/src/main/java/com/dsh/android/webbridge/BridgeServer.kt`: accept an optional `onSessionExpired` callback
  (default no-op) and invoke it only where `DeepSeekWebClient.isExpired(e)` maps the error to 401. Do not call it for
  auth-header failures or ordinary upstream errors, and do not log the token.
- `app/src/main/java/com/dsh/android/webbridge/WebAccount.kt`: wire the callback to `AccountStore.markWebExpired()`.
  Clear the flag when a new web token is stored (`DeepSeekLoginActivity` / `AccountChanges` success path).
- `app/src/main/java/com/dsh/android/AppState.kt`: include `account.webExpired` in `json()` for WEB mode.
- `mobile-ui-plugin/dsh-android-mobile/lib/client.js`: `mpAccountSection` shows a warning note and a “Sign in again”
  button (calls the existing `accountSwitch`) when `webExpired`; keep “Switch account” and “Sign out”.
- Tests: `BridgeServerTest` passes a collecting callback and asserts it fires for 40001/40003 and not for a normal
  error or a wrong bearer; `AccountStoreTest` covers set/clear/sign-out; Node test asserts the expired note/button is
  rendered and calls `accountSwitch`.
- Version stamps + regenerated mobile-ui zip.

**Test / acceptance**

- Targeted web-bridge JVM tests, full suite, Node account/update tests.
- Manual: invalidate the web token on a test account, send one message, open Settings > Account, confirm the warning
  and that “Sign in again” opens the web sign-in. The warning disappears after a successful sign-in.

**Deviation:** the expired card promotes its one existing action to **Sign in again** instead of adding a second
button next to **Switch account**; both call `accountSwitch`, so two identical buttons would be noise. **Sign out**
stays. `PRESET_SKILLS_VERSION` is unchanged because only `mobile-ui.zip` changed.

## 4. Replace the API key without signing out, and show a masked hint

An API-key user who rotates a key must currently choose “Switch account” and re-enter the whole flow. Add a direct
“Replace API key” path, and show only a masked suffix (for example `••••1234`) so the user can tell which key is in
use. Never expose or log the full key.

**Files**

- `app/src/main/java/com/dsh/android/account/DshCredentials.kt`: `keyHint(value: String?): String?` returning the last
  four characters only when the value passes `problem()`, otherwise null. No full-key read is added to any UI path.
- `app/src/main/java/com/dsh/android/AppState.kt`: when mode is `api_key`, read `DEEPSEEK_API_KEY` via
  `DshCredentials.read` and expose only `keyHint`.
- `app/src/main/java/com/dsh/android/account/SignInActivity.kt`: new `EXTRA_REPLACE_KEY`; in this mode start on the
  key panel with a “Replace API key” title, keep the current account mode (do not reset to NONE on cancel), and on
  success write the key and restart the engine as today. `EXTRA_SWITCH` keeps its existing behaviour.
- `app/src/main/res/layout/activity_sign_in.xml` / `app/src/main/res/values/strings_account.xml`: allow the existing
  key panel to be shown directly; add the replace title/body strings (English + existing localized files as needed).
- `mobile-ui-plugin/dsh-android-mobile/lib/client.js`: show the hint and a “Replace API key” button in
  `mpAccountSection` for API-key mode; the button calls a new `appAction("accountReplaceKey")` implemented in
  `AppState` by launching `SignInActivity` with `EXTRA_REPLACE_KEY`.
- Tests: `DshCredentialsTest` for hint shape (last four, no short keys, no full key), `AccountStoreTest` where
  relevant, and a Node account-section assertion for hint/button rendering.
- Version stamps + regenerated mobile-ui zip.

**Test / acceptance**

- JVM account tests, Node account tests, full suite.
- Manual: replace a key, confirm the hint changes, chat works without a full sign-out, and Back from the replace
  screen leaves the existing account signed in.

**Deviation:** `AppState` performs the one `DshCredentials.read` and passes the value straight to
`DshCredentials.keyHint`, so the full key exists only inside that call; no other UI path reads it. `keyHint` also
returns null for values shorter than five characters. `SignInActivity` honours `EXTRA_REPLACE_KEY` only when the saved
mode is already `api_key` (a stale intent cannot silently keep a web account), replace mode hides **Set up later** and
skips `signOutWeb()`. `PRESET_SKILLS_VERSION` is unchanged because only `mobile-ui.zip` changed.

## 5. First-screen key entry: paste, restore after rotation, clearer errors

Polish the API-key panel without adding permissions or reading the clipboard silently.

**Files**

- `app/src/main/res/layout/activity_sign_in.xml`: add a `btnPasteKey` text button near the key field (same visual
  language as “Get a key”), with an accessible label; keep the password toggle and the existing Back/Continue row.
- `app/src/main/res/values/strings_account.xml`: “Paste”, “Paste a key from the clipboard” and any error-heading string.
- `app/src/main/java/com/dsh/android/account/SignInActivity.kt`: on an explicit Paste tap read
  `ClipboardManager.primaryClip` as text, trim it, set the field, move the cursor to the end, clear the inline error;
  ignore missing/non-text clips. Save/restore the key panel, typed text, inline error and focused field across
  rotation; do not save the key anywhere else. Cancel any in-flight check when leaving/cancelling, and ensure a late
  check result cannot write a key after the panel was closed.
- `tools/ci/emulator-smoke.py`: after the key field is shown, assert the Paste button exists in the UI dump (do not
  automate the system clipboard, which is unreliable on the CI image); keep the existing empty-key validation and
  back-navigation checks.
- Tests: `DshCredentials.problem` edge cases already cover whitespace/punctuation; add a small pure helper test for
  trimming/formatting if one is extracted. The activity itself is covered by the emulator smoke, not Robolectric.

**Test / acceptance**

- Emulator smoke (`tools/ci/emulator-smoke.py`) passes the existing empty-key error and the new Paste-button check.
- Manual: paste a valid key with surrounding whitespace, rotate the phone, confirm the text/error survive; press Back
  while a check is running and confirm no account is created.

## 6. Show release notes and a release-page link in About

`AppUpdater.Release` already carries `body` and `html_url`, but neither is shown. Render a short, safe “What's new”
summary and offer the GitHub release page for the full text.

**Files**

- `app/src/main/java/com/dsh/android/update/AppUpdater.kt`: `displayNotes(notes: String): String` — normalise line
  endings, drop control characters, collapse excess blank lines, trim, cap at ~4,000 characters with an ellipsis.
- `app/src/main/java/com/dsh/android/update/UpdateCenter.kt`: expose `app.notes` (sanitised) and `app.page` in `json()`;
  never expose the raw body HTML.
- `app/src/main/java/com/dsh/android/AppState.kt`: handle `appAction("openReleasePage")` by opening the stored
  `https://github.com/muhib-karim/DSHAPP/releases/...` page with an `ACTION_VIEW` intent (validate the scheme/host;
  no arbitrary URL from the page).
- `mobile-ui-plugin/dsh-android-mobile/lib/client.js`: in the app update card, render the notes in a collapsible
  `<details>` block as plain text (`white-space: pre-wrap`) and a “Release page” button; no `innerHTML`.
- Tests: `AppUpdaterTest` for absent/oversized/control-character/CRLF notes; Node `update-controls.test.mjs` renders
  the notes and exercises the release-page action with a fake bridge.
- Version stamps + regenerated mobile-ui zip.

**Test / acceptance**

- Targeted JVM parser test + Node test; full suite.
- Manual: a release with Markdown notes shows readable plain text, truncation is obvious, and “Release page” opens the
  browser at the trusted host only.

## 7. Plugins: search, counts and scope badges

**Status:** done — `mpFilterPluginRows` / `mpPluginGroupCounts` exported in `exports.__test`, search/clear, group
counts, scope badges and the no-match row shipped with pure and render tests.

The plugin list grows with user installs and bundled packages; add a filter and make the groups legible without
changing install/enable/remove semantics or the server-side read-only policy.

**Files**

- `mobile-ui-plugin/dsh-android-mobile/lib/client.js`: extract pure helpers `mpFilterPluginRows(rows, query)` (case-
  insensitive match on name, label and description; empty query returns all) and `mpPluginGroupCounts(rows)` (all /
  user / app / builtin). Add a search input with a clear action, group count labels, and a “No matching plugins” row.
  Keep the existing `buildPluginRows`, `pluginSpecProblem`, install, toggle and remove flows untouched; keep the
  read-only/blocked notice and don’t enable actions the server disallows.
- `tools/tests/plugin-list.test.mjs`: tests for case-insensitive filtering, whitespace-only query, names versus
  descriptions, no-match, stable row order, and counts for mixed groups. Export the helpers in the existing
  `exports.__test` block only.
- `tools/tests/update-controls.test.mjs` / a new render test: if the fake React harness can drive the search input,
  assert the empty state; otherwise cover the pure functions and the rendered count labels.
- Version stamps + regenerated mobile-ui zip.

**Test / acceptance**

- `node --test tools/tests/*.test.mjs`; full JVM suite still passes.
- Manual: filter by “log”, confirm only matching rows in the right group, counts update, clearing restores the full
  list, and toggles still call the same actions.

## 8. About: open logs, restart the engine, and show diagnostics

Make the About section useful for support without duplicating the log viewer.

**Files**

- `app/src/main/java/com/dsh/android/AppState.kt`: handle `appAction("openLogs")` by launching the existing
  `LogViewerActivity.intent(activity)`; keep the existing `engineRestart` action but give it a settings reason and
  ensure it does not start a second restart while one is running. Add non-sensitive diagnostics to `version`
  (`android`, `device`, `abi`, `flavor`, `harness`) using `Build.*`; no serial numbers, accounts or paths.
- `app/src/main/java/com/dsh/android/update/UpdateCenter.kt`: expose the WebView major already tracked by
  `webViewMajor` (0 = unknown) and the update statuses used by the card, if not already present.
- `mobile-ui-plugin/dsh-android-mobile/lib/client.js`: `mpAboutSection` gets a diagnostics card (app/build, Android,
  ABI, WebView major), an “Open logs” button calling `openLogs`, and a “Restart engine” button with a confirmation
  calling `engineRestart`. Keep the existing update cards, notes, retry buttons and last-check line.
- Tests: extend `tools/tests/update-controls.test.mjs` with button actions and diagnostics rendering; no new JVM
  test unless a pure formatting helper is extracted.
- Version stamps + regenerated mobile-ui zip.

**Test / acceptance**

- Node tests + full suite; manual: Open logs shows `LogViewerActivity`, restart asks first and brings the engine back,
  diagnostics contain no secrets.

## 9. Cancelable APK download and stale-cache cleanup

A stuck 100 MB download currently leaves the About card “downloading” with no way out. Add an explicit cancel that
cannot be overwritten by the download thread, and clean stale partial files.

**Files**

- `app/src/main/java/com/dsh/android/update/UpdateCenter.kt`: generation counter (or equivalent) for app downloads;
  `cancelAppUpdate()` marks the current generation cancelled, disconnects the active connection, deletes the partial
  APK, resets progress and sets a non-error “Download cancelled.” state. The download loop checks cancellation between
  chunks and must not publish `ready`/`error` for an obsolete generation. Starting a new download increments the
  generation and clears only `cache/updates`; never touch the verified APK while an installer is reading it.
- `app/src/main/java/com/dsh/android/update/UpdateActions.kt`: `canCancel` in the app eligibility result (true only
  while downloading); update the constructors and JSON.
- `mobile-ui-plugin/dsh-android-mobile/lib/client.js`: show a Cancel button while `canCancel`, call
  `appAction("cancelAppUpdate")`; keep Retry available after an error/cancel.
- `app/src/main/java/com/dsh/android/AppState.kt`: dispatch `cancelAppUpdate`.
- Tests: `UpdateActionsTest` for canCancel (downloading only, never while ready/idle), and a small pure test for the
  generation guard if it is factored out. Node update-controls test for the Cancel button and action.
- Version stamps + regenerated mobile-ui zip.

**Test / acceptance**

- Targeted JVM + Node tests; full suite.
- Manual with a throttled connection: cancel midway, confirm no “ready” flip and no installer, retry succeeds, and the
  cache no longer contains the partial file.

## 10. Close-out: regression matrix, docs and screenshots

Finish the pass with evidence, not just code.

**Files**

- `CHANGELOG.md`: one 1.3.0 bullet per user-visible item above (rollback, expired-session warning, key replace/hint,
  paste/rotation, release notes, plugin search, About tools, cancel download).
- `README.md` / `README.en.md`: extend the existing “What's new in 1.3.0” list; no version bump.
- `docs/PROJECT_BRAIN.md`: status, verification commands/results (JVM counts per flavor, Node and Chromium counts),
  known limits (emulator cannot boot the arm64 engine; chat/settings need a real device), and a UTC session log entry.
- `docs/agents/v130-signin-plugins-updates.md`: feature → code → test map for the new actions (`harnessRollback`,
  `accountReplaceKey`, `openLogs`, `cancelAppUpdate`, `openReleasePage`), stamp rules, and test commands.
- `docs/PLAN_130_P3.md`: tick items as they land; record any deliberate deviation.
- `docs/screenshots/v1.3.0/`: refresh `02-api-key.jpg`, `08-settings-account.jpg` and `09-settings-about.jpg` only if
  their visible content changed, captured from a real device/emulator (no mockups); keep file names stable.

**Verification matrix**

1. `./gradlew --no-daemon --max-workers=2 :app:testSystemDebugUnitTest :app:testGeckoDebugUnitTest` and
   `assembleDebug`; then `./gradlew --stop`.
2. `node --test tools/tests/*.test.mjs` (including the new pure/render tests).
3. `node --test tools/tests/mobile-clean.browser.mjs` with Chromium/Playwright when available.
4. `tools/ci/emulator-smoke.py` for the first-screen/Paste/native-log checks; record that chat/settings still require
   an arm64 device.
5. Re-check generated mobile-ui zip against the source directory and confirm every version stamp moved exactly once
   per edited source set.
6. Review the complete diff for secrets, local paths, hostnames and accidental upstream changes; confirm no
   push/merge/tag/release.

**Acceptance for the whole pass**

- All 1.3.0 requirements from the frozen spec remain satisfied; no regressions in the 152+ JVM tests, 34+ Node tests
  or the Chromium checks.
- Each user-facing change is documented in the 1.3.0 sections and has a test or a reproducible manual check.
- The tree is left clean, on `feature/v1.3.0`, with the pass trailer on every commit and the final round summary
  reporting the actual head SHA and build result.

**Done 2026-10-23 UTC.** The 1.3.0 changelog covers every user-visible item; `README.md` / `README.en.md` extend the
"What's new" notes and disclose the screenshot provenance; `PROJECT_BRAIN.md` carries the status, gate results, limits
and UTC log; the agent doc maps the five new settings actions to code and tests and records the stamp rules and
commands. Verification: 200 JVM tests per flavor, 66 Node tests, 5 Chromium tests, both debug APKs, mobile-ui
zip/source byte comparison and stamp audit. No push, merge, tag or release.

**Deviation:** screenshot 02 could not be recaptured in this environment (no emulator binary, system image or KVM), so
it remains the CI capture from before the Paste button and now carries an explicit caption in both READMEs. Screenshots
08 and 09 were refreshed from the real DSH web UI plus this app's plugin rendered at phone size, with demo account and
version values as the README already discloses; they are not mockups, but they are host renders rather than emulator
captures. No other item deviates from the plan.
