# PROJECT_BRAIN — DSHAPP
Persistent memory for agent sessions. Short, current, true. Newest wins.

## Goal
Complete the 1.3.0 second review pass against the frozen requirements, with local gates and both CI workflows green
on the final feature branch head. No merge, tag or release.

## Status
- Version / branch: 1.3.0 / 31, feature/v1.3.0; P2 plan committed before code as 2f77d8d.
- Account/bridge, opaque theme/plugin controls, watcher fallback and recoverable update activation are implemented.
- Independent final review found no unresolved important issues; sign-out persistence and update/start races fixed.
- Final local gates: 152 JVM tests per flavor, both debug APKs, 34 Node tests and 5 Chromium tests passed.
- Smoke script syntax and all 13 plugin archive/source byte comparisons pass; Gradle is stopped.
- Bundled harness matches npm latest 0.2.1-rc.2; local APKs omit upstream runtime assets supplied by CI.
- Live upstream frontend: skill add/remove refreshes without reload; real light/dark Settings surfaces are opaque.
- Delivery gate: security checks, push and exact-head Release APK + Emulator smoke; final line records both run IDs.

## Next step
1. Run security gates and push the completed P2 commits; watch both exact-head CI workflows until green.
2. Hand off P3; verify CI IDs from the final delivery line and retain live account/arm64 acceptance limitations.


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
