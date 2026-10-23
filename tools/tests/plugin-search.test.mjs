// Goal 7 (v1.3.0): the Plugins section filters as you type, shows per-group counts and scope badges, and
// keeps the install / switch / remove actions on the same manager calls.
import test from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { loadClient } from './load-client.mjs';

const CLIENT = fileURLToPath(new URL('../../mobile-ui-plugin/dsh-android-mobile/lib/client.js', import.meta.url));

// A tiny hook runtime: React itself is the external boundary here. State setters mutate slots and the test
// asks for the next render explicitly, which is enough to drive the real section (no DOM reconciler needed).
function createHooks() {
  const slots = [];
  let index = 0;
  let pendingEffects = [];
  const sameDeps = (a, b) => Array.isArray(a) && Array.isArray(b) && a.length === b.length && a.every((v, i) => Object.is(v, b[i]));
  return {
    useState(init) {
      const i = index++;
      if (!slots[i]) slots[i] = { kind: 'state', value: typeof init === 'function' ? init() : init };
      return [slots[i].value, (next) => {
        slots[i].value = typeof next === 'function' ? next(slots[i].value) : next;
      }];
    },
    useCallback(fn, deps) {
      const i = index++;
      const previous = slots[i];
      if (previous && previous.kind === 'callback' && sameDeps(previous.deps, deps)) return previous.fn;
      slots[i] = { kind: 'callback', fn, deps };
      return fn;
    },
    useEffect(fn, deps) {
      const i = index++;
      const previous = slots[i];
      if (previous && previous.kind === 'effect' && sameDeps(previous.deps, deps)) return;
      slots[i] = { kind: 'effect', fn, deps };
      pendingEffects.push(fn);
    },
    begin() { index = 0; pendingEffects = []; },
    flush() { const run = pendingEffects; pendingEffects = []; for (const fn of run) fn(); },
  };
}

const bundles = [
  { name: 'dsh-log-viewer', installed: true, enabled: true, removable: true, availability: 'profile',
    description: 'Quasar log tail', meta: { title: 'Log viewer' } },
  { name: 'dsh-theme-pack', installed: true, enabled: true, removable: true, availability: 'profile',
    description: 'Color themes for the shell' },
  { name: '@deepseek-ai/dsh-web-app', installed: true, enabled: true, removable: false, availability: 'installation' },
];

function find(node, predicate) {
  if (!node || typeof node !== 'object') return null;
  if (predicate(node)) return node;
  return node.children.map((child) => find(child, predicate)).find(Boolean);
}

function findAll(node, predicate, found = []) {
  if (!node || typeof node !== 'object') return found;
  if (predicate(node)) found.push(node);
  for (const child of node.children) findAll(child, predicate, found);
  return found;
}

async function mount() {
  const runtime = createHooks();
  const calls = [];
  const manager = {
    listBundles: () => Promise.resolve({ ok: true, value: bundles }),
    setBundleEnabled: (name, enabled) => { calls.push(['setBundleEnabled', name, enabled]); return Promise.resolve({ ok: true }); },
    installBundle: (spec) => { calls.push(['installBundle', spec]); return Promise.resolve({ ok: true }); },
    removeBundle: (name) => { calls.push(['removeBundle', name]); return Promise.resolve({ ok: true }); },
  };
  const React = {
    createElement: (tag, props, ...children) => ({ tag, props: props || {}, children: children.flat().filter(Boolean) }),
    useState: (init) => runtime.useState(init),
    useCallback: (fn, deps) => runtime.useCallback(fn, deps),
    useEffect: (fn, deps) => runtime.useEffect(fn, deps),
  };
  const client = loadClient(CLIENT, {
    transform: (source) => source.replace('let mpPrivReact = null;', 'let mpPrivReact = require("react");')
      .replace('exports.__test = {', 'exports.__test = { mpPluginsSection,'),
    requireModule: (name) => name === 'react' ? React : {},
    globals: {
      confirm: () => true,
      DSHAndroid: { appState: () => '{}', appAction: (...args) => calls.push(args) },
    },
  });
  const scope = { remote: { pluginManager: manager } };
  const render = () => {
    runtime.begin();
    const tree = client.__test.mpPluginsSection({}, scope);
    runtime.flush();
    return tree;
  };
  let tree = render();
  // Let the initial listBundles() promise settle, then render the loaded rows.
  await new Promise((resolve) => setTimeout(resolve, 0));
  tree = render();
  return {
    get tree() { return tree; },
    calls,
    render(next) { tree = render(); return tree; },
    async settle() { await new Promise((resolve) => setTimeout(resolve, 0)); tree = render(); return tree; },
  };
}

const searchInput = (tree) => find(tree, (node) => 'data-mp-plugin-search' in node.props);
const pluginRows = (tree) => findAll(tree, (node) => 'data-mp-plugin' in node.props);
const groupHeads = (tree) => findAll(tree, (node) => 'data-mp-plugin-group' in node.props);

function type(harness, value) {
  const input = searchInput(harness.tree);
  assert.ok(input, 'the search field is rendered');
  input.props.onChange({ target: { value } });
  return harness.render();
}

test('loaded plugins render in their groups with count labels and scope badges', async () => {
  const harness = await mount();
  const rows = pluginRows(harness.tree);
  assert.equal(rows.length, 13, 'three profile/installation bundles plus the ten app plugins');
  const heads = groupHeads(harness.tree);
  const counts = Object.fromEntries(heads.map((head) => [head.props['data-mp-plugin-group'],
    Number(find(head, (node) => 'data-mp-plugin-count' in node.props).children.join(''))]));
  assert.deepEqual(counts, { user: 2, app: 10, builtin: 1 });
  const scope = (name) => find(harness.tree, (node) => node.props['data-mp-plugin'] === name)
    .children[0].children.find((child) => child.props && 'data-mp-plugin-scope' in child.props).props['data-mp-plugin-scope'];
  assert.equal(scope('dsh-log-viewer'), 'user');
  assert.equal(scope('dsh-android-mobile'), 'app');
  assert.equal(scope('@deepseek-ai/dsh-web-app'), 'builtin');
  assert.ok(!find(harness.tree, (node) => node.props && 'data-mp-plugin-no-match' in node.props));
});

test('typing filters case-insensitively by name, label, description and updates the counts', async () => {
  const harness = await mount();
  const total = pluginRows(harness.tree).length;

  let tree = type(harness, 'Dsh-LoG');
  assert.deepEqual(pluginRows(tree).map((row) => row.props['data-mp-plugin']), ['dsh-log-viewer']);
  assert.equal(groupHeads(tree).length, 1, 'empty groups disappear while filtering');
  assert.equal(groupHeads(tree)[0].props['data-mp-plugin-group'], 'user');

  tree = type(harness, 'COLOR THEMES');
  assert.deepEqual(pluginRows(tree).map((row) => row.props['data-mp-plugin']), ['dsh-theme-pack']);

  tree = type(harness, 'zeta');
  assert.equal(pluginRows(tree).length, 0);
  const summary = find(tree, (node) => 'data-mp-plugin-count-summary' in node.props);
  assert.equal(summary.props['data-mp-plugin-count-summary'], '0/' + total);
  assert.equal(find(tree, (node) => 'data-mp-plugin-no-match' in node.props).children.join(''), 'No matching plugins.');
  assert.equal(groupHeads(tree).length, 0);
});

test('clearing the query restores every row and removes the clear action', async () => {
  const harness = await mount();
  const all = pluginRows(harness.tree).map((row) => row.props['data-mp-plugin']);
  const tree = type(harness, 'quasar');
  assert.ok(find(tree, (node) => 'data-mp-plugin-clear' in node.props), 'clear is offered while filtering');
  const clear = find(tree, (node) => 'data-mp-plugin-clear' in node.props);
  clear.props.onClick();
  const restored = harness.render();
  assert.deepEqual(pluginRows(restored).map((row) => row.props['data-mp-plugin']), all, 'original order is kept');
  assert.ok(!find(restored, (node) => node.props && 'data-mp-plugin-clear' in node.props));
  assert.ok(!find(restored, (node) => node.props && 'data-mp-plugin-count-summary' in node.props));
});

test('switching a filtered row still calls the plugin manager with the bundle name and opposite state', async () => {
  const harness = await mount();
  const tree = type(harness, 'quasar');
  const button = find(tree, (node) => node.tag === 'button' && node.children.join('') === 'Turn off');
  assert.ok(button, 'a toggleable user plugin keeps its switch while filtered');
  button.props.onClick();
  assert.deepEqual(harness.calls, [['setBundleEnabled', 'dsh-log-viewer', false]]);
});
