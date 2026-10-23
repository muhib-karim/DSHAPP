import test from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { loadClient } from './load-client.mjs';

const CLIENT = fileURLToPath(new URL('../../mobile-ui-plugin/dsh-android-mobile/lib/client.js', import.meta.url));

// Render the real About section into an element tree. React's hooks and the Android bridge are the external
// boundaries; card selection, labels and click handlers all run unchanged from the shipped client bundle.
function render(updates, { confirm = true } = {}) {
  const actions = [];
  const confirms = [];
  const React = {
    createElement: (tag, props, ...children) => ({ tag, props: props || {}, children: children.flat().filter(Boolean) }),
    useState: (init) => [typeof init === 'function' ? init() : init, () => {}],
    useCallback: (callback) => callback,
    useEffect: () => {},
  };
  const client = loadClient(CLIENT, {
    transform: (source) => source.replace('let mpPrivReact = null;', 'let mpPrivReact = require("react");')
      .replace('exports.__test = {', 'exports.__test = { mpAboutSection,'),
    requireModule: (name) => name === 'react' ? React : {},
    globals: { confirm: (message) => { confirms.push(message); return confirm; },
      DSHAndroid: { appState: () => JSON.stringify({ update: updates }),
        appAction: (name, argument) => actions.push([name, argument]) } },
  });
  return { tree: client.__test.mpAboutSection({}), actions, confirms };
}

function find(node, predicate) {
  if (!node || typeof node !== 'object') return null;
  if (predicate(node)) return node;
  return node.children.map((child) => find(child, predicate)).find(Boolean);
}

for (const [kind, update, label, action] of [
  ['harness', { status: 'error', canUpdate: true }, 'Update', 'updateHarness'],
  ['app', { status: 'error', canUpdate: true, canInstall: false }, 'Update', 'updateApp'],
  ['app', { status: 'error', canUpdate: false, canInstall: true }, 'Install', 'updateApp'],
  ['app', { status: 'ready', canUpdate: false, canInstall: true }, 'Install', 'updateApp'],
]) test(`${kind} ${update.status} keeps the eligible ${label.toLowerCase()} action available`, () => {
  const { tree, actions } = render({ [kind]: { current: '1.3.0', latest: '1.4.0', ...update } });
  const card = find(tree, (node) => node.props['data-mp-update'] === kind);
  const button = find(card, (node) => node.tag === 'button');
  assert.ok(button, 'the retry action remains visible');
  assert.equal(button.children.join(''), `${label} → 1.4.0`);
  button.props.onClick();
  assert.deepEqual(actions, [[action, '']]);
});

for (const status of ['downloading', 'installing', 'blocked', 'current', 'available']) {
  test(`native ineligibility hides the action even with status ${status}`, () => {
    const { tree } = render({ app: { status, current: '1.3.0', latest: '1.4.0', canUpdate: false, canInstall: false } });
    const card = find(tree, (node) => node.props['data-mp-update'] === 'app');
    assert.equal(find(card, (node) => node.tag === 'button'), undefined);
  });
}

test('harness offers the previous version and confirms before restoring exactly that version', () => {
  const { tree, actions, confirms } = render({ harness: { current: '0.2.3', previous: '0.2.2', canRollback: true,
    status: 'idle', canUpdate: false } });
  const card = find(tree, (node) => node.props['data-mp-update'] === 'harness');
  const button = find(card, (node) => node.props['data-mp-update-action'] === 'harnessRollback');
  assert.ok(button, 'the restore action is visible');
  assert.equal(button.children.join(''), 'Restore previous version (0.2.2)');
  assert.equal(confirms.length, 0, 'nothing is asked before the tap');
  button.props.onClick();
  assert.deepEqual(confirms, ['Go back to @deepseek-ai/dsh 0.2.2? The engine will restart.']);
  assert.deepEqual(actions, [['harnessRollback', '0.2.2']]);
});

test('cancelling the confirmation does not restore anything', () => {
  const { tree, actions, confirms } = render({ harness: { current: '0.2.3', previous: '0.2.2', canRollback: true,
    status: 'idle', canUpdate: false } }, { confirm: false });
  const card = find(tree, (node) => node.props['data-mp-update'] === 'harness');
  find(card, (node) => node.props['data-mp-update-action'] === 'harnessRollback').props.onClick();
  assert.equal(confirms.length, 1);
  assert.deepEqual(actions, []);
});

for (const [description, harness] of [
  ['native says rollback is not eligible', { current: '0.2.3', previous: '0.2.2', canRollback: false, status: 'installing' }],
  ['there is no previous version', { current: '0.2.3', previous: null, canRollback: true, status: 'idle' }],
]) {
  test(`harness restore is hidden when ${description}`, () => {
    const { tree } = render({ harness });
    const card = find(tree, (node) => node.props['data-mp-update'] === 'harness');
    assert.equal(find(card, (node) => node.props['data-mp-update-action'] === 'harnessRollback'), undefined);
  });
}
