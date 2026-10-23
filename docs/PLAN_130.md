# DSHAPP v1.3.0 "DeepSeek, clean" Implementation Plan

> For agentic workers: execute task by task, commit after each task (conventional messages, author
> `Muhib Karim <62206502+muhib-karim@users.noreply.github.com>`). Steps use `- [ ]` checkboxes.

**Goal:** a cleaner, opaque DSH for Android with two sign-ins (Google web account, API key), plugin management in
Settings, and in-app updates of the DSH harness and of the APK.

**Architecture:** the official dsh web UI stays as-is. Visual changes live in the mobile-ui plugin
(`mobile-ui-plugin/dsh-android-mobile`, CSS + JS overlay). Sign-in, the web-account bridge and the updaters are native
Kotlin, plain JVM classes where possible so they can be unit tested. The page reaches native code through the existing
`PageBridge` (`window.DSHAndroid`). dsh is configured only through its own files:
`$DSH_HOME/.credentials.yaml` (keys) and `$DSH_HOME/profiles/web/cordis.patch.yml` (providers, default model).

**Tech stack:** Kotlin 2.0.21 / AGP 8.8.2 / JDK 17, JUnit 4 + org.json + SnakeYAML (test only), Node 22 `node:test`
for the plugin JS, Playwright (local only) for screenshots of the real web UI, GitHub Actions for the release APKs and
the emulator smoke test.

**Spec:** `/home/user/pro_dev/cc_dsh130.txt` (frozen task). Owner's screenshots: `mk_shots/mk_dsh_1..6`.

## Global constraints

- Package ids stay `com.mkdsh.droid` / `com.mkdsh.gecko` (15 characters); `targetSdk = 28`. Never change either.
- Version `1.3.0`, `versionCode = 31`, both flavors.
- Any change to a plugin source dir needs its version stamp bumped: `EngineManager.MOBILE_UI_VERSION`
  (`1.0.0-100` → `1.0.0-101`), `tools/build-webview-compat.ps1 -CompatVersion` (`34` → `35`),
  `PRESET_SKILLS_VERSION` (`1.0.0-1` → `1.0.0-2`). The gecko bridge `manifest.json` version
  (`1.0.13` → `1.0.14`) changes whenever `content.js` changes.
- A new `PageBridge` method goes into five places: `PageBridge.kt`, `MainActivity.StatusBridge`, `SystemWebHost`'s
  `SystemPageBridge`, `GeckoWebHost.handleExtensionMessage`, and `dsh-bridge/content.js`.
- Secrets: the API key and the web token never go into logs, prefs in clear text, or git. `.credentials.yaml` is mode
  600 (dsh refuses any group or other bit).
- The bridge listens on `127.0.0.1` only, on a random port, and requires `Authorization: Bearer <per-install secret>`.
- No tags, no releases, no merge to main. Push `feature/v1.3.0`; CI must be green (release APK + emulator smoke).
- Gradle runs locally with `--no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2g -Pkotlin.daemon.jvmargs=-Xmx1g`,
  then `./gradlew --stop`. Start only with at least 3 GB available memory.

## Facts this plan relies on (checked against dsh 0.2.1-rc.2)

- npm `latest` is `0.2.1-rc.2`, the version already bundled (`MainActivity.assetStamp = "0.2.1-rc.2-1"`); `next` is
  rc.3. "Ship the latest" holds; CI adds a freshness check, and the in-app updater handles newer versions.
- Keys: `.credentials.yaml` = `version: 1`, `refs: {NAME: value}`, `records: {...}` (dsh stores its cookie-signing
  secret in `records`, which must be preserved byte for byte).
- Official provider `deepseek-official` reads ref `DEEPSEEK_API_KEY`, models `deepseek-flash`, `deepseek-v4-pro`.
  Default model row: `- id: agent-default-model` / `config: {provider, model}`. The fork currently seeds
  `agnes / agnes-3.0-flash`, which has no key without upstream's key pool, so the first chat fails.
- Custom provider: the `llm-pi-ai` row, `config.providers.<route>` with `api: openai-completions`, `baseURL`,
  `apiKeyEnv`, and `models: [{id, name, contextWindow, maxTokens, input}]`.
- "/" palette: `dsh-client-ui-skill` caches `skills/list` per session and clears it only on the server event
  `plugin-manager/changed` (or a preset switch or reconnect). The plugin manager emits that event from its server
  context (`ownerContext.emit("plugin-manager/changed", {reason})`, forwarded by `dsh-api-remotes`). Skill files that
  change on disk emit nothing, so an open palette stays stale.
- Google sign-in refuses embedded WebViews (`disallowed_useragent`). The in-app WebView handles DeepSeek's own sign-in
  page; if Google refuses, the app hands off to the browser and brings the token back (link or paste), as in the
  reference app. The user agent is not spoofed.

## Review focus (inputs no happy-path test covers)

1. A `.credentials.yaml` that already holds `records` (browser-session secret) or other refs must survive a key write
   byte for byte → `DshCredentialsTest.preservesRecordsAndOtherRefs`.
2. A user who already picked a model in Settings must keep it; only the app-seeded `agnes` default is replaced →
   `ProfilePatchTest.keepsUserChosenDefaultModel`.
3. The bridge gets a request with `tools` and history (an agent turn), or a stream the client aborts →
   `OpenAiMappingTest.toolCallBlockBecomesToolCalls`, `BridgeServerTest.rejectsWrongSecret`,
   `BridgeServerTest.streamsSseChunksAndDone`.
4. A harness update that installs but does not boot must roll back without user action →
   `EngineSlotsTest.rollbackRestoresPrevious`, `HarnessUpdaterTest.badVersionIsNotOfferedAgain`.
5. Prerelease ordering (`0.2.1-rc.10` > `0.2.1-rc.9` > `0.2.1-alpha.5`; `0.2.1` > `0.2.1-rc.3`; `v1.3.0` = `1.3.0`;
   `-gecko` suffix ignored) → `VersionsTest`.

---

### Task 0: Test scaffolding and CI triggers

**Files:** modify `app/build.gradle.kts` (testImplementation `junit:junit:4.13.2`, `org.json:json:20240303`,
`org.yaml:snakeyaml:2.2`; `testOptions.unitTests.isReturnDefaultValues = true`), `.github/workflows/release-apk.yml`
and `emulator-smoke.yml` (push trigger adds `feature/**`; the build job runs
`bash ./gradlew testSystemDebugUnitTest` and `node --test tools/tests/*.test.mjs` before assembling). Create `tools/tests/`.

- [ ] Add a trivial passing test, run `testSystemDebugUnitTest`, commit `test: add JVM and node unit test scaffolding`.

### Task 1 (Goal 1): Opaque, decluttered UI

**Files:**
- Modify `mobile-ui-plugin/dsh-android-mobile/lib/client.js`: a "clean surfaces" CSS block, plus the JS guards
  `mpOpaqueGuard()` and `mpWallpaperGuard()` called from `apply(ctx)`. Pure helpers exported as
  `exports.__test = { flattenColor, isWallpaperLayer, CLEAN_CSS }`.
- Modify `mobile-ui-plugin/dsh-android-statusbar/lib/index.js`: drop the "background image" advice from the
  `fullscreen` description.
- Modify `preset-skills-plugin/.../dsh-mobile-theme/SKILL.md`: themes recolour tokens only; no wallpaper,
  background image, video, canvas, blur or translucent surfaces.
- Modify `MainActivity.kt`: the top inset is `max(status bar, displayCutout.safeInsetTop)` from `rootWindowInsets`.
  In fullscreen, keep the IME from covering the composer (content bottom padding = IME inset).
- Bump `MOBILE_UI_VERSION` → `1.0.0-101`, `-CompatVersion` → `35`, `PRESET_SKILLS_VERSION` → `1.0.0-2`.
- Test: `tools/tests/mobile-clean.test.mjs`.

**Rules the CSS enforces under `[data-mobile]`:**
- Every element: `backdrop-filter: none` and `-webkit-backdrop-filter: none`.
- Tokens `--dsw-menu-backdrop-filter: none`. `--dsw-specific-menu` and `--dsw-menu-surface-fill` are opaque (light
  `#f8f9fa`, dark `#26272c`).
- `html`, `body`, `#root`: `background-image: none` and a solid `--dsw-alias-bg-base`.
- `.wSkVaW_header` and the tab row get an opaque background and stay above the message list (no see-through header).
- The trigger menu (`[data-trigger-menu]`, "+" and "/") and its options are flat: opaque surface, no glow
  (`box-shadow`/`text-shadow` none), no gradient. Dialog masks keep a plain dim (`rgba(0,0,0,.5)`), never blur.
- Sheets, dialogs, panels and the image preview/lightbox are opaque.

**JS guards:**
- `mpOpaqueGuard` reads the computed surface tokens. Any with alpha < 1 is replaced, inline on `body`, by
  `flattenColor(value, base)` (composited over the theme base).
- `mpWallpaperGuard` hides decorative full-screen layers: fixed or absolute `canvas`/`video`/`img`/`div` that cover
  at least 90% of the viewport, have `pointer-events: none` or a background image, and are not part of a known dsh UI
  root. They get `data-mp-wallpaper-hidden` and `display: none`. A MutationObserver on `body` children re-runs it.

- [ ] **Test first** (`mobile-clean.test.mjs`): load `client.js` through a fake `window.__ModuleLoader__`. Then:
  - `flattenColor('#30313673', '#16161a')` returns an opaque `rgb(...)`.
  - `flattenColor('rgba(0,0,0,0)', '#ffffff')` = `rgb(255, 255, 255)`.
  - `isWallpaperLayer` is true for a fixed canvas covering 100% with `pointer-events: none`, and false for the
    composer or a 300×200 image.
  - `CLEAN_CSS` contains no `backdrop-filter` value other than `none`, and `client.js` contains no `blur(`.
- [ ] Implement; `node --test tools/tests/*.test.mjs` passes.
- [ ] Verify against the real web UI: run dsh 0.2.1-rc.2 locally with the plugin patched in, and take Playwright
  screenshots at 412×915 (chat, "+" menu, "/" palette, settings, preview) in light and dark. Any computed
  `backdrop-filter` other than `none` fails, as does a surface with alpha < 1.
- [ ] Commits: `feat(mobile-ui): opaque surfaces and no wallpaper layers`,
  `fix(android): header insets include the display cutout and IME in fullscreen`.

### Task 2 (Goal 2a): Credentials writer, profile patch, API-key sign-in

**Files (package `com.dsh.android.account`):**
- `DshCredentials.kt`: `object DshCredentials`
  - `fun problem(key: String): String?` (shape check: 8..512 printable ASCII, no spaces or quotes).
  - `fun render(existing: String, set: Map<String, String?>): String` (null value = remove the ref; `records` and
    other refs are preserved).
  - `fun write(dshHome: File, set: Map<String, String?>)` (atomic: tmp file + rename, POSIX 600).
- `ProfilePatch.kt`: `object ProfilePatch`
  - `fun setDefaultModel(body: String, provider: String, model: String, replaceOnly: Set<String>): String` (replaces
    the row only when absent or its provider is in `replaceOnly`, e.g. `agnes`).
  - `fun upsertPiAiRoute(body: String, route: String, routeYaml: String): String`.
  - `fun removePiAiRoute(body: String, route: String): String`.
- `ApiKeyCheck.kt`: `fun check(key: String, http: (url, headers) -> HttpResult): KeyCheck`.
  - One `GET https://api.deepseek.com/models` with `Authorization: Bearer`.
  - 200 → Ok; 401/403 → "DeepSeek rejected this key…"; 402 → "This key has no balance…"; other codes → "DeepSeek
    answered HTTP n"; IOException → "Could not reach api.deepseek.com…".
- `AccountStore.kt`: SharedPreferences `dsh_account`.
  - `mode` is one of `none`, `api_key`, `web`, `later`.
  - The web token is kept AES-GCM encrypted with an Android Keystore key.
  - Also holds the bridge secret and `firstScreenDone`.
- `SignInActivity.kt` + `res/layout/activity_sign_in.xml`:
  - Opaque, follows the system theme.
  - Logo + "DeepSeek"; buttons **"Continue with Google"** and **"Use API key"**; text button "Set up later".
  - The API-key panel: password field "DeepSeek API key", **"Continue"**, link "Get a key" to
    `https://platform.deepseek.com/api_keys`, an error text view and a progress indicator.
  - Exported = false.
- `MainActivity.kt`: when `AccountStore.needsFirstScreen()`, start `SignInActivity` after `bootEngine()` (the engine
  boots behind it). On result, apply the config and `engine.requestRestart("account")` if the engine is up.
- `EngineManager.kt`: `ensureAccountConfig()` before launch. It applies the stored mode:
  - `api_key`: default model `deepseek-official/deepseek-flash`.
  - `web`: the route `deepseek-web` and default model `deepseek-web/deepseek-web-chat`.
  - Signed out: neither row; the app-seeded agnes default is replaced by `deepseek-official/deepseek-flash` so DSH
    asks for a key instead of failing on agnes.
- Tests: `DshCredentialsTest`, `ProfilePatchTest`, `ApiKeyCheckTest`. YAML is validated by parsing with SnakeYAML.

- [ ] **Tests first:**
  - writes `version: 1` + `refs.DEEPSEEK_API_KEY`;
  - mode is `rw-------`;
  - `preservesRecordsAndOtherRefs`;
  - removing the key keeps other refs;
  - `problem("sk abc")` is not null;
  - patch upsert on an empty file, on a `[]` template, and on the seeded settings → valid YAML with exactly one
    `agent-default-model` row;
  - `keepsUserChosenDefaultModel`;
  - a route upsert twice is idempotent;
  - `ApiKeyCheck` code mapping.
- [ ] Implement until green; build `assembleSystemDebug`.
- [ ] Commits: `feat(account): dsh credentials writer and profile patch editing`,
  `feat(account): first screen with Google and API-key sign-in`.

### Task 3 (Goal 2b): DeepSeek web account and local OpenAI-compatible bridge

**Files (package `com.dsh.android.webbridge`, ported from `muhib-karim/deepseek-android` `core/`, MIT, same owner):**
- `DeepSeekHashV1.kt`: SHA3-256 without Keccak round 0.
  `fun solve(challenge, salt, expireAt, difficulty, cancelled: () -> Boolean): Long?`.
- `PowChallenge.kt`: `fromJson(JSONObject)` and `header(answer, targetPath): String` (base64 JSON).
- `WebSseParser.kt`: `class WebSseParser { fun feed(event: String?, data: String): List<WebDelta> }`, where
  `WebDelta` = `Thinking(text) | Text(text) | Finished`. It applies the APPEND/SET/BATCH patch rules on
  `response/fragments`.
- `DeepSeekWebClient.kt` (HttpURLConnection, origin `https://chat.deepseek.com`, the reference headers):
  - `createSession()`;
  - `powHeader(targetPath)`;
  - `complete(sessionId, prompt, thinking, search, onDelta)`;
  - `currentUser()`;
  - error codes mapped to `WebError.Expired | RateLimited | PowRejected | Busy`.
- `OpenAiMapping.kt` (pure):
  - `fun toPrompt(request: JSONObject): WebPrompt(prompt, thinking)`. Flattens system, user, assistant and tool
    messages into one transcript. When `tools` are present, appends a tool protocol that asks for
    `<tool_call>{"name":…,"arguments":{…}}</tool_call>`.
  - `fun parseToolCalls(text): Pair<String, List<ToolCall>>`.
  - `fun completionJson(...)`, `fun chunkJson(...)`, `fun modelsJson()`.
  - Models: `deepseek-web-chat` and `deepseek-web-reasoner` (thinking on).
- `BridgeServer.kt`: a minimal HTTP/1.1 server on a `ServerSocket` bound to `127.0.0.1:0`.
  - `GET /v1/models`; `POST /v1/chat/completions` with `stream` true or false.
  - Bearer secret compared in constant time. Bodies are capped at 8 MB; one thread per connection.
  - The upstream sits behind an interface `WebChat`, so tests use a fake.
- `WebAccount.kt`: starts and stops the bridge in the app process and exposes `port` and `secret`. The provider route
  has `baseURL http://127.0.0.1:<port>/v1` and `apiKeyEnv DEEPSEEK_WEB_BRIDGE_KEY`, and the secret is written to
  `.credentials.yaml`. This is applied before every engine launch, because the port is random per process.
- `DeepSeekLoginActivity.kt`:
  - A WebView on `https://chat.deepseek.com/sign_in`.
  - Polls `localStorage.userToken` on the chat.deepseek.com origin and checks it with `users/current`; on success it
    stores the token and returns RESULT_OK.
  - On a Google refusal, or on request, offers "Sign in in your browser". That opens the browser and shows a paste
    field plus a bookmarklet, ported from the reference app's `BrowserSignIn`, with the `mkdsh://session` link
    accepted within 30 minutes.
- Settings: bridge methods `accountState(): String` (JSON `{mode, label}`), `accountSignOut()` and `accountSwitch()`.
  The mobile plugin's settings get an **Account** section showing the current sign-in with *Switch account* and
  *Sign out*. Sign-out clears the token, cookies and WebStorage for the origin, removes the route and key, and
  restarts the engine.
- Tests:
  - `DeepSeekHashV1Test` (reference vectors: `""` → `e594808b…`, salt `3f1c0e9a7b5d2c4e6a80` / `1792534088468` →
    answer 143999 at difficulty 144000);
  - `PowChallengeTest` (exact header JSON);
  - `WebSseParserTest` (captured stream: ready → fragments → APPEND → BATCH → FINISHED);
  - `OpenAiMappingTest`: `toolCallBlockBecomesToolCalls`, system and history flattening, chunk JSON shape;
  - `BridgeServerTest`: `rejectsWrongSecret` (401), `/v1/models`, non-stream completion,
    `streamsSseChunksAndDone`, a body over the cap → 413.
- [ ] Tests first, then implement.
- [ ] Commits: `feat(webbridge): DeepSeek web client with PoW and SSE parsing`,
  `feat(webbridge): local OpenAI-compatible bridge for the web account`,
  `feat(account): Continue with Google sign-in, account settings, sign-out and switch`.

### Task 4 (Goal 3): Settings > Plugins and live "/" palette

**Files:**
- `client.js`: a **Plugins** settings section. Rows come from
  `buildPluginRows(bundles, appBuiltIns): Array<{name, label, kind: 'builtin'|'app'|'user', enabled, removable}>`.
  - Sources: `ctx.remote.pluginManager.listBundles()` plus the app's own tool plugins (read-only, "built into the
    app").
  - Install box: npm name → `installBundle`, with progress from `waitForInstall`. "Browse market" opens the existing
    market panel.
  - Per-row toggle → `setBundleEnabled`; Remove → `removeBundle` (user plugins only).
  - Inject list gains `remote.pluginManager`.
- `index.js` (Node half): `createSkillRefresher({ emit, roots, debounceMs = 800, followUpMs = 2000 })`.
  - Watches `$DSH_HOME/skills` and the profile's `node_modules` top level.
  - Calls `ctx.emit('plugin-manager/changed', { reason: 'dsh-android: skills changed' })` after changes settle,
    and once more `followUpMs` after any plugin-manager change whose reason is not its own (bundles that register
    skills after HMR).
  - The upstream palette logic is unchanged; it simply refetches.
- Tests:
  - `tools/tests/plugin-list.test.mjs`: built-in vs user classification, an app plugin is never removable, the
    disabled state, sort order;
  - `tools/tests/skill-refresh.test.mjs`: an fs change on a temp dir triggers exactly one emit; its own reason does
    not loop; the follow-up after an external change; disposal stops the watchers.
- [ ] Local e2e on the real engine (Playwright):
  1. Open "/" and record the skill list.
  2. Install a skill plugin or drop `SKILL.md` into `$DSH_HOME/skills`.
  3. Reopen "/" without a reload: the new skill is listed.
- [ ] Commit `feat(mobile-ui): Settings > Plugins and live skill palette refresh`.

### Task 5 (Goal 4): Harness and app auto-update

**Files (package `com.dsh.android.update`):**
- `Versions.kt`: `fun compare(a: String, b: String): Int` (semver with prereleases; strips `v` and `-gecko`).
- `EngineSlots.kt` (pure file operations on `filesDir`):
  - `promote(next: File)`: `engine` → `engine.prev`, `next` → `engine`.
  - `rollback()`; `pending(): String?`; `confirm()`; the bad-version list.
- `HarnessUpdater.kt`:
  - `latestFromRegistry(json): String` (`dist-tags.latest` of `https://registry.npmjs.org/@deepseek-ai/dsh`).
  - `shouldCheck(now, lastCheck)`: at most daily.
  - `install(version)`: runs the bundled pnpm with the engine's env:
    `pnpm add @deepseek-ai/dsh@<v> --dir engine.next --config.node-linker=hoisted --ignore-scripts`, then checks
    that `node_modules/@deepseek-ai/dsh/lib/bin.js` exists.
  - Writes `.stamp` = `npm:<v>`, promotes the slot, restarts.
- `EngineManager.ensureExtracted` keeps an `npm:<v>` engine whose version is newer than the bundled one. The
  Android patch steps re-run on the new tree.
- Boot watchdog: a pending engine that fails to become Ready (Error state or no Ready within 180 s) → `rollback()`,
  the version is recorded as bad, restart.
- `AppUpdater.kt`:
  - `pickAsset(releaseJson, flavor)`: `DSH<v>-mk.apk` or `DSH<v>-mk-gecko.apk`, taken from
    `https://api.github.com/repos/muhib-karim/DSHAPP/releases/latest`.
  - Download to `cacheDir/updates`, check the sha256 against `SHA256SUMS.txt`, then open an install intent through a
    `FileProvider` (`${applicationId}.updates`).
  - Manifest: `REQUEST_INSTALL_PACKAGES`, the provider, `res/xml/update_paths.xml`.
- `MainActivity`: once the UI is ready, at most daily, check both. When something is newer, show a native dialog
  with one tap: "Update" or "Later".
- Bridge methods: `updateState()` (JSON: app and harness current/latest/status), `updateCheck()`, `updateHarness()`,
  `updateApp()`. The mobile plugin's settings get an **About & updates** section.
- Tests:
  - `VersionsTest` (review focus 5);
  - `EngineSlotsTest` (`promoteKeepsPrevious`, `rollbackRestoresPrevious`, promote without an existing engine);
  - `HarnessUpdaterTest` (registry JSON parse, daily throttle, `badVersionIsNotOfferedAgain`);
  - `AppUpdaterTest` (asset pick per flavor, SHA256SUMS parse, an older or equal release → no update).
- [ ] Commits: `feat(update): harness auto-update with rollback`, `feat(update): app update from GitHub releases`.

### Task 6 (Goals 5 and 6): Version, docs, smoke test, CI to green

- `app/build.gradle.kts`: `versionCode = 31`, `versionName = "1.3.0"`.
- `CHANGELOG.md` `[1.3.0]`, `README.md` + `README.en.md` "What's new" with screenshots in
  `docs/screenshots/v1.3.0/` (emulator: first screen, API-key screen; local renders: chat, palette, settings,
  plugins), `FORK_NOTES.md` gaps, `THIRD-PARTY.md` (deepseek-android port, SnakeYAML test-only,
  security-crypto if used).
- `tools/ci/emulator-smoke.py`:
  1. After launch, require the first screen ("Continue with Google" + "Use API key") and screenshot it.
  2. Tap "Use API key" and screenshot.
  3. Enter `sk-smoke-invalid-key`, tap Continue, and require an error text ("rejected" or "Could not reach"); screenshot.
  4. Tap "Set up later", then continue with the existing boot-status and log-viewer checks.
- gitleaks clean; push; `gh run list/view --log-failed` until the release APK and emulator smoke runs are green on
  HEAD; copy the smoke screenshots into the README.
- Commits: `chore(release): 1.3.0 (versionCode 31)`, `docs: 1.3.0 changelog, README what's new, fork notes`,
  `test(ci): emulator smoke reaches the first screen and the API-key screen`.
