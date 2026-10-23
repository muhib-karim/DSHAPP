# PROJECT_BRAIN — DSHAPP
Persistent memory for agent sessions. Short, current, true. Newest wins.

## Goal
Complete the 1.3.0 third implementation pass by delivering the ten items in `docs/PLAN_130_P3.md`, with local gates
green and no merge, tag or release.

## Status
- Version / branch: 1.3.0 / 31, feature/v1.3.0; P2 delivered; P3 plan committed as `51bff9c` before P3 code.
- P3 plan: update identity, manual harness rollback, web-session expiry, API-key replacement/hint, first-screen
  polish, release notes, plugin search, About tools, cancelable downloads, then docs/screenshots and full gates.
- P3 item 1 done: downloaded APKs are checked for package name and signing certificate before the installer;
  `ApkIdentity` has 10 JVM tests. Both debug APKs built and both flavor suites passed; Gradle stopped.
- P3 item 2 done: About & updates offers **Restore previous version** once a promoted harness has booted; it reuses
  `slots.rollback()`, is refused while an install/pending/journal is active, and marks the rejected version bad.
  Native eligibility plus client button and Node/JVM tests; mobile-ui stamp `1.0.0-103`, compat `37`.
- P3 item 3 done: `BridgeServer` reports a 401/403 or 40001–40003 expiry once per request; `AccountStore` records it,
  `AppState` exposes `account.webExpired` only in web mode, and Settings > Account warns with **Sign in again**
  (existing `accountSwitch`). New token, web sign-out, API-key switch and full sign-out clear it. New
  `AccountStoreTest` and `BridgeServerTest` cases plus `account-expiry.test.mjs`; stamps `1.0.0-104` / compat `38`.
  Both-flavor gate: 174 JVM tests each, 42 Node tests, both APKs built with the new zip; Gradle stopped.
- P3 item 4 done: `DshCredentials.keyHint` exposes only `••••` + the last four characters (null for unusable keys);
  `AppState` adds `account.keyHint` in API-key mode and `accountReplaceKey`, which opens `SignInActivity` with
  `EXTRA_REPLACE_KEY` on the key panel ("Replace API key"); Back keeps the old key and the account mode, success writes
  the key and restarts the engine without signing out. Account card shows the hint and a secondary replace button.
  New `DshCredentialsTest` hint cases and `account-key.test.mjs`; stamps `1.0.0-105` / compat `39`.
  Both-flavor gate: 176 JVM tests each, 45 Node tests, both APKs built with the new zip; Gradle stopped.
- P3 item 5 done: the API-key panel has an explicit **Paste** button (clipboard read only on tap; a trailing newline is
  trimmed, blank/non-text clips leave the field alone) and keeps the typed key, inline error and focus across rotation;
  rotating during a check cancels that check, so the destroyed screen cannot write the key. Errors have a heading plus a
  reason (save failures no longer echo the exception), and a consumed/superseded attempt is reported instead of a silent
  no-op. New `KeyEntry` + 6 JVM tests and a smoke assertion for the Paste button.
  Both-flavor gate: 182 JVM tests each, 45 Node tests, both APKs built; Gradle stopped.
- P3 item 6 done: `AppUpdater.displayNotes` normalises line endings, drops control characters and excess blank
  lines, caps notes at 4,000 characters with an ellipsis and never splits a surrogate pair; `UpdateCenter` exposes only
  sanitised `app.notes` and a `page` that passes `AppUpdater.isTrustedReleasePage` (`https`, `github.com`, no
  credentials/tricks, `/muhib-karim/DSHAPP/releases/`). `AppState.openReleasePage` re-checks that page before
  `ACTION_VIEW` and takes no URL from the page; the app card shows notes in a collapsed `<details>` as `<pre>` plain text
  plus a **Release page** button. Stamps `1.0.0-106` / compat `40` / preset `1.0.0-3`.
  Both-flavor gate: 186 JVM tests each, 49 Node tests, both APKs built with the new zip; Gradle stopped.
- CI smoke hardening: `emulator-smoke.yml` now pre-installs the API 34 `google_apis` x86_64 image with three
  attempts, deleting the partial image directory, SDK `.temp` and the SDK-manager cache between tries, before the
  pinned emulator action runs. This targets run 39975831370, where a corrupt system-image archive (`ZipFile unknown
  archive`) left no emulator to boot. The fix is committed but cannot be exercised from here (no push).
- P2 evidence remains: 152 JVM tests per flavor, 34 Node tests, 5 Chromium tests, plugin archive/source byte checks.
- Bundled harness matches npm latest 0.2.1-rc.2; local APKs omit upstream runtime assets supplied by CI.
- Live chat/Settings acceptance still needs an arm64 device; the x86_64 emulator covers native screens only.

## Next step
1. P3 item 6 is done; implement P3 item 7 (plugin search).
2. After all items refresh screenshots and run the full matrix.


## Decisions (why things are the way they are)
- 2026-10-23: preserve fork package IDs, targetSdk 28 and versionCode 31; upstream package examples are historical.
- 2026-10-23: keep official frontend and slash palette; corrections belong in host services and plugin overlay.

## Gotchas
- Pending update validation/gateway failures must roll back before normal rescue; never quarantine user configuration.
- Plugin changes require mobile and compat stamp bumps and zip/source byte comparisons.
- Gradle uses no daemon, at most two workers and a 2 GB heap; stop it after builds, wait below 3 GB available RAM.

## Session log (one line per session, append only)
- 2026-10-23 13:50Z Codex/gpt-6-astra: reviewed pass 1 and wrote P2 plan; next: commit plan and implement regressions.
- 2026-10-23 13:51Z Codex/gpt-6-astra: plan committed before code; baseline JVM check next, then targeted fixes and gates.
- 2026-10-23 13:52Z Codex/gpt-6-astra: baseline JVM build passed; smoke cancel/reopen/local-error checks added; next: regression fixes and browser verification.
- 2026-10-23 13:54Z Codex/gpt-6-astra: recovered after server restart; retained plan, RED evidence and in-progress fixes; next: finish implementation and gates.
- 2026-10-23 13:56Z Codex/gpt-6-astra: mobile regression suites green and plugin archives verified; next: native green runs, live palette check, commits and final gates.
- 2026-10-23 13:57Z Codex/gpt-6-astra: account/bridge targeted suite passed 103 tests; next: commit native fixes, both-flavor gates and final review.
- 2026-10-23 13:58Z Codex/gpt-6-astra: final review identified sign-out and update/start races; regression fixes underway before final gates.
- 2026-10-23 13:59Z Codex/gpt-6-astra: final review fixes complete; Node/browser/archive gates pass; next: both-flavor JVM/build verification and delivery.
- 2026-10-23 14:00Z Codex/gpt-6-astra: sign-out and lifecycle targeted regressions green; starting both-flavor full tests/builds, then commit and push.
- 2026-10-23 14:02Z Codex/gpt-6-astra: local full gates and live frontend checks passed; committing delivery record before security checks, push and exact-head CI watch.
- 2026-10-23 14:27Z dsh/DeepSeek V4.1 Flash: P3 plan `51bff9c` committed after baseline both-flavor build/tests; next: implement plan items.
- 2026-10-23 14:36Z dsh/DeepSeek V4.1 Flash: P3 item 1 (APK identity) implemented and tested; both-flavor gate green; next: item 2.
- 2026-10-23 14:43Z dsh/DeepSeek V4.1 Flash: P3 item 2 (manual harness restore) implemented; targeted JVM and Node tests green; next: both-flavor gate, commit, item 3.
- 2026-10-23 14:52Z dsh/DeepSeek V4.1 Flash: P3 item 3 (web-session expiry) implemented; targeted JVM + new Node test green, zip/stamps refreshed; next: both-flavor gate and commit.
- 2026-10-23 14:56Z dsh/DeepSeek V4.1 Flash: P3 item 3 gate green (174 JVM per flavor, 42 Node, both APK assets match); next: commit, item 4.
- 2026-10-23 14:58Z dsh/DeepSeek V4.1 Flash: P3 item 4 (API-key replace + masked hint) implemented; new Node test green, zip/stamps bumped; next: both-flavor gate and commit.
- 2026-10-23 15:08Z dsh/DeepSeek V4.1 Flash: P3 item 5 (paste, rotation restore, clearer key errors) implemented; KeyEntry tests added and smoke checks the Paste button; next: both-flavor gate and commit.
- 2026-10-23 15:19Z dsh/DeepSeek V4.1 Flash: P3 item 5 gate green (182 JVM per flavor, 45 Node, both APKs); next: commit, item 6.
- 2026-10-23 15:23Z dsh/DeepSeek V4.1 Flash: CI smoke system-image preinstall with retries added; next: commit fix, item 6.
- 2026-10-23 15:31Z dsh/DeepSeek V4.1 Flash: P3 item 6 (release notes + trusted release link) implemented; targeted JVM/Node green; next: full both-flavor gate and commit.
- 2026-10-23 15:35Z dsh/DeepSeek V4.1 Flash: P3 item 6 gate green (186 JVM per flavor, 49 Node, both APKs); next: commit, item 7.
