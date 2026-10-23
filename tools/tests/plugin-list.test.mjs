// Goal 3 (v1.3.0): Settings > Plugins lists built-in and user plugins side by side; install / enable / remove.
import test from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { loadClient } from './load-client.mjs';

const CLIENT = fileURLToPath(new URL('../../mobile-ui-plugin/dsh-android-mobile/lib/client.js', import.meta.url));
const { buildPluginRows, pluginSpecProblem, APP_PLUGINS, mpFilterPluginRows, mpPluginGroupCounts } = loadClient(CLIENT).__test;
const plain = (x) => JSON.parse(JSON.stringify(x));

const bundles = [
  { name: '@deepseek-ai/dsh-experimental-voice-input-bundle', official: true, optional: true, installed: false, enabled: false, removable: false, availability: 'installation', description: 'Voice input' },
  { name: '@deepseek-ai/dsh-skill-office', official: true, optional: false, installed: false, enabled: false, removable: false, availability: 'missing' },
  { name: 'dsh-core-memory', official: false, optional: false, installed: true, enabled: true, removable: true, source: 'link:/data/x', availability: 'profile', meta: { title: 'Core memory' } },
  { name: 'zeta-skills', official: false, optional: false, installed: true, enabled: false, removable: true, availability: 'profile', version: '1.2.0', description: 'Zeta skills\nsecond line' },
  { name: '@deepseek-ai/dsh-web-app', official: false, optional: false, installed: true, enabled: true, removable: false, availability: 'installation' },
  { name: '@acme/alpha-tools', official: false, optional: false, installed: true, enabled: true, removable: true, availability: 'profile', error: { message: 'patch failed' } },
];

test('groups: user plugins, then the app, then DSH built-ins; alphabetical inside a group', () => {
  const rows = plain(buildPluginRows(bundles, APP_PLUGINS));
  const kinds = rows.map((r) => r.kind);
  assert.deepEqual([...new Set(kinds)], ['user', 'app', 'builtin']);
  assert.deepEqual(rows.filter((r) => r.kind === 'user').map((r) => r.name), ['@acme/alpha-tools', 'zeta-skills']);
});

test('user plugins can be switched and removed; app and DSH plugins are never removable', () => {
  const rows = plain(buildPluginRows(bundles, APP_PLUGINS));
  const zeta = rows.find((r) => r.name === 'zeta-skills');
  assert.equal(zeta.enabled, false);
  assert.equal(zeta.toggleable, true);
  assert.equal(zeta.removable, true);
  assert.equal(zeta.description, 'Zeta skills');
  assert.equal(zeta.version, '1.2.0');
  for (const r of rows.filter((r) => r.kind !== 'user')) assert.equal(r.removable, false, r.name);
});

test('app-installed bundles (core memory) are listed as part of the app but can be switched off', () => {
  const memory = plain(buildPluginRows(bundles, APP_PLUGINS)).find((r) => r.name === 'dsh-core-memory');
  assert.equal(memory.kind, 'app');
  assert.equal(memory.label, 'Core memory');
  assert.equal(memory.toggleable, true);
});

test('the app\'s own tool plugins are listed, always on, not switchable here', () => {
  const rows = plain(buildPluginRows([], APP_PLUGINS));
  assert.ok(rows.length >= 5);
  const mobile = rows.find((r) => r.name === 'dsh-android-mobile');
  assert.deepEqual([mobile.kind, mobile.enabled, mobile.toggleable, mobile.removable], ['app', true, false, false]);
});

test('optional DSH bundles can be switched on; catalog entries that are not installed are hidden', () => {
  const rows = plain(buildPluginRows(bundles, APP_PLUGINS));
  const voice = rows.find((r) => r.name.endsWith('voice-input-bundle'));
  assert.deepEqual([voice.kind, voice.toggleable, voice.enabled], ['builtin', true, false]);
  assert.equal(rows.find((r) => r.name.endsWith('skill-office')), undefined);
});

test('the profile base bundles (@deepseek-ai/*) count as DSH built-ins even when not flagged official', () => {
  const web = plain(buildPluginRows(bundles, APP_PLUGINS)).find((r) => r.name === '@deepseek-ai/dsh-web-app');
  assert.deepEqual([web.kind, web.removable], ['builtin', false]);
});

test('official skills installed into the profile belong to the user and can be removed', () => {
  const rows = plain(buildPluginRows([
    { name: '@deepseek-ai/dsh-skill-office', official: true, installed: true, enabled: true,
      optional: false, availability: 'profile', removable: true },
    { name: '@deepseek-ai/dsh-custom-tools', official: false, installed: true, enabled: false,
      availability: 'profile', removable: true },
  ], []));
  assert.ok(rows.every((r) => r.kind === 'user' && r.removable && r.toggleable));
});

test('upstream read-only bundles never offer an unusable switch or remove control', () => {
  const rows = plain(buildPluginRows([
    { name: '@deepseek-ai/dsh-web-app', installed: true, enabled: true, availability: 'installation',
      removable: false, readOnlyReason: 'management-required' },
    { name: 'custom-manager', installed: true, enabled: true, availability: 'profile',
      removable: true, readOnlyReason: 'management-required' },
  ], []));
  assert.ok(rows.every((r) => !r.toggleable && !r.removable));
});

test('a bundle error is surfaced', () => {
  assert.equal(plain(buildPluginRows(bundles, APP_PLUGINS)).find((r) => r.name === '@acme/alpha-tools').error, 'patch failed');
});

test('install spec validation', () => {
  for (const ok of ['dsh-foo', '@acme/dsh-foo', 'dsh-foo@1.2.3', '@acme/dsh-foo@^2', 'dsh-foo@latest']) assert.equal(pluginSpecProblem(ok), null, ok);
  for (const bad of ['', '  ', 'Has Space', 'UPPER', '../evil', 'https://evil/x.tgz', 'git+ssh://x', 'a;rm -rf /', '@acme']) assert.notEqual(pluginSpecProblem(bad), null, bad);
});

const filterRows = [
  { name: 'dsh-log-viewer', label: 'Log viewer', description: 'Tail the app log', kind: 'user' },
  { name: '@acme/theme-pack', label: 'Themes', description: 'Color themes for the shell', kind: 'user' },
  { name: 'dsh-core-memory', label: 'Core memory', description: 'Long-term notes', kind: 'app' },
  { name: 'dsh-web-app', label: 'DSH web UI', description: 'The upstream web interface', kind: 'builtin' },
];

test('plugin search matches names, labels and descriptions case-insensitively', () => {
  assert.deepEqual(plain(mpFilterPluginRows(filterRows, 'LOG-VIEWER')).map((r) => r.name), ['dsh-log-viewer']);
  assert.deepEqual(plain(mpFilterPluginRows(filterRows, 'themes')).map((r) => r.name), ['@acme/theme-pack']);
  assert.deepEqual(plain(mpFilterPluginRows(filterRows, 'long-term')).map((r) => r.name), ['dsh-core-memory']);
});

test('an empty or whitespace-only query keeps every row in its original order', () => {
  for (const query of ['', '   ', '\t\n', null, undefined]) {
    assert.deepEqual(plain(mpFilterPluginRows(filterRows, query)).map((r) => r.name), filterRows.map((r) => r.name));
  }
  assert.notEqual(mpFilterPluginRows(filterRows, ''), filterRows, 'the filtered list is a copy');
});

test('no match gives an empty list, and a query never reshuffles survivors', () => {
  assert.deepEqual(plain(mpFilterPluginRows(filterRows, 'not-a-plugin')), []);
  assert.deepEqual(plain(mpFilterPluginRows(filterRows, 'dsh')).map((r) => r.name),
    ['dsh-log-viewer', 'dsh-core-memory', 'dsh-web-app']);
});

test('group counts cover all / user / app / builtin and tolerate empty or odd input', () => {
  assert.deepEqual(plain(mpPluginGroupCounts(filterRows)), { all: 4, user: 2, app: 1, builtin: 1 });
  assert.deepEqual(plain(mpPluginGroupCounts([])), { all: 0, user: 0, app: 0, builtin: 0 });
  assert.deepEqual(plain(mpPluginGroupCounts(null)), { all: 0, user: 0, app: 0, builtin: 0 });
  assert.deepEqual(plain(mpPluginGroupCounts([null, { name: 'x', kind: 'other' }])), { all: 1, user: 0, app: 0, builtin: 0 });
  assert.deepEqual(plain(mpPluginGroupCounts(mpFilterPluginRows(filterRows, 'themes'))), { all: 1, user: 1, app: 0, builtin: 0 });
});
