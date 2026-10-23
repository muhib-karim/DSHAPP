# PROJECT_BRAIN — DSHAPP
Persistent memory for agent sessions. Short, current, true. Newest wins.

## Goal
Complete the 1.3.0 second review pass against the frozen requirements, with local gates and both CI workflows green
on the final feature branch head. No merge, tag or release.

## Status
- Version / branch: 1.3.0 / 31, feature/v1.3.0; pass 1 baseline 277daa8.
- CI: baseline Release APK 39962242220 and Emulator smoke 39962242246 green.
- Review: account lifecycle, incomplete streams, update activation/retry, opacity and plugin ownership gaps found.
- Plan committed: 2f77d8d; account, updater and UI fixes assigned by subsystem, regression tests first.
- Baseline JVM build successful (97 existing tests); node watcher baseline has ENOSPC on shared host.
- Mobile fixes verified: 34/34 Node tests, 5/5 Chromium tests; all 13 generated plugin zips byte-match source.
- Actual upstream modal/header checked after animations: opaque colors, opacity 1, no backdrop filter.
- Account/bridge regressions green: 103 tests, zero failures; RED covered stream, cancellation and account-generation failures.
- Mobile code/stamps committed as 499587a; account/bridge committed as 1a8826e.
- Updater targeted green: 44 tests. Sign-out write-failure and lifecycle-race regressions reproduced and fixed.
- Independent final review found no unresolved important issues; final targeted gate passed updater 46/46 and account changes 8/8.
- Full both-flavor JVM suites and debug assembly are running under the shared Gradle resource guard.
- Final Node/Chromium gates repeated green: 34 + 5 tests; smoke syntax and all 13 archive/source comparisons pass.
- Server restart interrupted work; existing edits retained and subsystem workers resumed from their diffs.

## Next step
1. Complete sign-out failure and update lifecycle regressions, then run both flavor test/build gates.
2. Finish live browser checks, update delivery evidence, commit and push; monitor both CI workflows.


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
