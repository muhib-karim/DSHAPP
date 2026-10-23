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
- P2 evidence remains: 152 JVM tests per flavor, 34 Node tests, 5 Chromium tests, plugin archive/source byte checks.
- Bundled harness matches npm latest 0.2.1-rc.2; local APKs omit upstream runtime assets supplied by CI.
- Live chat/Settings acceptance still needs an arm64 device; the x86_64 emulator covers native screens only.

## Next step
1. Implement P3 item 2 (manual harness rollback) with its tests and version-stamp/zip checks.
2. After all items, refresh CHANGELOG/README/PROJECT_BRAIN and the affected screenshots, then run the full matrix.


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
