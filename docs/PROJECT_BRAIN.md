# PROJECT_BRAIN — DSHAPP
Persistent memory for agent sessions. Short, current, true. Newest wins.

## Goal
Complete the 1.3.0 second review pass against the frozen requirements, with local gates and both CI workflows green
on the final feature branch head. No merge, tag or release.

## Status
- Version / branch: 1.3.0 / 31, feature/v1.3.0; pass 1 baseline 277daa8.
- CI: baseline Release APK 39962242220 and Emulator smoke 39962242246 green.
- Review: account lifecycle, incomplete streams, update activation/retry, opacity and plugin ownership gaps found.
- Plan: docs/REVIEW_130_P2.md records requirement evidence and ordered implementation/tests.
- Local watcher tests encounter ENOSPC on the shared host; handle failure without changing global system settings.

## Next step
1. Plan committed; execute the account/bridge, update and mobile plugin tasks with regression tests.
2. Execute account/bridge, updater and mobile plugin regression fixes; run gates and final CI.

## Decisions (why things are the way they are)
- 2026-10-23: preserve fork package IDs, targetSdk 28 and versionCode 31; upstream package examples are historical.
- 2026-10-23: keep official frontend and slash palette; corrections belong in host services and plugin overlay.

## Gotchas
- Pending update validation/gateway failures must roll back before normal rescue; never quarantine user configuration.
- Plugin changes require mobile and compat stamp bumps and zip/source byte comparisons.
- Gradle uses no daemon, at most two workers and a 2 GB heap; stop it after builds, wait below 3 GB available RAM.

## Session log (one line per session, append only)
- 2026-10-23 13:54Z Codex/gpt-6-astra: reviewed pass 1 and wrote P2 plan; next: commit plan and implement regressions.
