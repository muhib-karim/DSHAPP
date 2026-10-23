# Third-party software

This repository is a fork of **DSHAPP** with the fork's own additions listed below.

## Upstream

| Project | Licence | Source |
|---|---|---|
| DSHAPP (DSH for Android) by pht88888888 | MIT (see [LICENSE](LICENSE), kept unchanged) | https://github.com/pht88888888/DSHAPP |

Application code, plugins, documentation and design in this repository are upstream's, except the fork's own work:
the CI files (`.github/workflows/release-apk.yml`, `.github/workflows/emulator-smoke.yml`, `tools/ci/`), the package
name and version, and the 1.3.0 features: `app/src/main/java/com/dsh/android/{account,webbridge,update}/`,
`AppState.kt`, the sign-in layouts, the v1.3.0 sections of `mobile-ui-plugin/dsh-android-mobile/lib/client.js`,
`lib/skill-refresh.js`, `tools/tests/` and these credit notes.

| Ported from | Licence | What |
|---|---|---|
| [muhib-karim/deepseek-android](https://github.com/muhib-karim/deepseek-android) (same owner) | MIT | `DeepSeekHashV1` (proof-of-work solver, ported verbatim with its test vectors), the DeepSeek web API endpoints, headers and stream format, the session-token capture and browser hand-off, and the dsh credentials writer |

## Bundled in the APK

The release APK bundles the components below, through upstream's build assets (`app/src/main/assets/`, see
`tools/ci/assemble-assets.py` and the build's `ASSETS.json`) and Gradle dependencies. Licences are the ones each project
publishes. Where the upstream repository names a component, its own wording is in README.md ("致谢") and docs/agents/.

| Component | Where | Licence | Source |
|---|---|---|---|
| DSH / DeepSeek Harness engine (`@deepseek-ai/dsh` and its `@deepseek-ai/*` packages, plus npm dependencies) | `engine.bin` | MIT (DeepSeek); dependencies under their own licences (MIT, ISC, BSD, Apache-2.0) | https://www.npmjs.com/package/@deepseek-ai/dsh |
| Termux bootstrap (bash, dpkg, apt, coreutils, … and 64 shared libraries) | `bootstrap-aarch64.zip` | Per package: GPL-3.0 (bash, coreutils), GPL-2.0 (dpkg, apt), and others | https://github.com/termux/termux-packages |
| Node.js (Termux build, 26.4.0) | `node-runtime.bin` | MIT (bundled V8, OpenSSL, ICU, libuv under their own licences) | https://github.com/termux/termux-packages/tree/master/packages/nodejs |
| adb (Termux `android-tools`) | `adb-wireless-arm64.zip` | Apache-2.0 | https://github.com/termux/termux-packages/tree/master/packages/android-tools |
| pnpm 11.28.3 | `pnpm-android-arm64.zip` | MIT | https://github.com/pnpm/pnpm |
| ripgrep | `ripgrep-android-arm64.zip` | MIT or Unlicense | https://github.com/BurntSushi/ripgrep |
| sharp (wasm32 build, includes libvips) | `sharp-wasm32.zip` | Apache-2.0 (sharp), LGPL-3.0-or-later (libvips) | https://github.com/lovell/sharp |
| node-pty (native binding compiled for Android by upstream) | `node-pty-android-arm64.zip` | MIT | https://github.com/microsoft/node-pty |
| Native addons `node-addon-require-builtin`, `node-addon-system` (compiled for Android by upstream) | `node-addon-*.zip` | MIT | npm |
| Mozilla GeckoView 140 (gecko build only) | Gradle dependency | MPL-2.0 | https://wiki.mozilla.org/Mobile/GeckoView |
| Shizuku API | Gradle dependency | Apache-2.0 | https://github.com/RikkaApps/Shizuku-API |
| AndroidX, Material Components, Kotlin, kotlinx.coroutines | Gradle dependencies | Apache-2.0 | https://developer.android.com/jetpack |

Test-only dependencies (not in the APK): JUnit 4 (EPL-1.0), org.json (Public Domain / JSON licence), SnakeYAML
(Apache-2.0). Local UI checks used Playwright (Apache-2.0) and Chromium outside the repository.

GPL/LGPL components are unmodified upstream binaries. Their corresponding source is at the links above, at the
versions in the APK.

## Not included

- `keyinject.bin`: upstream's built-in API account pool (upstream's own credentials; optional per upstream's README).
