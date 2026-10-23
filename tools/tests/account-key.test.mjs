import test from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { loadClient } from './load-client.mjs';

const CLIENT = fileURLToPath(new URL('../../mobile-ui-plugin/dsh-android-mobile/lib/client.js', import.meta.url));

// The real Account section from the shipped bundle; React and the Android bridge are the outer boundaries.
function render(account) {
  const actions = [];
  const React = {
    createElement: (tag, props, ...children) => ({ tag, props: props || {}, children: children.flat().filter(Boolean) }),
    useState: (init) => [typeof init === 'function' ? init() : init, () => {}],
    useCallback: (callback) => callback,
    useEffect: () => {},
  };
  const client = loadClient(CLIENT, {
    transform: (source) => source.replace('let mpPrivReact = null;', 'let mpPrivReact = require("react");')
      .replace('exports.__test = {', 'exports.__test = { mpAccountSection,'),
    requireModule: (name) => name === 'react' ? React : {},
    globals: { confirm: () => true,
      DSHAndroid: { appState: () => JSON.stringify({ account }),
        appAction: (name, argument) => actions.push([name, argument]) } },
  });
  return { tree: client.__test.mpAccountSection({}), actions };
}

function find(node, predicate) {
  if (!node || typeof node !== 'object') return null;
  if (predicate(node)) return node;
  return node.children.map((child) => find(child, predicate)).find(Boolean);
}

test('an API-key account shows the masked suffix and can replace the key in place', () => {
  const { tree, actions } = render({ mode: 'api_key', keyHint: '••••1234' });
  const card = find(tree, (node) => node.props['data-mp-account'] === 'api_key');
  const hint = find(card, (node) => node.props['data-mp-account-key-hint'] === '1');
  assert.ok(hint, 'the masked key line is visible');
  assert.equal(hint.children.join(''), 'Key in use ••••1234');
  assert.doesNotMatch(hint.children.join(''), /sk-/i, 'the hint is a suffix, never the key itself');
  // Switching accounts still wins the primary slot; replacing the key is a secondary action.
  assert.equal(find(card, (node) => node.tag === 'button').children.join(''), 'Switch account');
  const replace = find(card, (node) => node.props['data-mp-account-replace'] === '1');
  assert.ok(replace, 'the replace button is present');
  assert.equal(replace.children.join(''), 'Replace API key');
  replace.props.onClick();
  assert.deepEqual(actions, [['accountReplaceKey', '']]);
});

test('an API-key account without a readable key keeps the replace path and hides the hint', () => {
  const { tree } = render({ mode: 'api_key', keyHint: null });
  const card = find(tree, (node) => node.props['data-mp-account'] === 'api_key');
  assert.equal(find(card, (node) => node.props['data-mp-account-key-hint']), undefined);
  assert.ok(find(card, (node) => node.props['data-mp-account-replace'] === '1'));
});

test('a hinted key never leaks into the web or signed-out cards', () => {
  const web = render({ mode: 'web', label: 'someone@example.com', keyHint: '••••1234' });
  const webCard = find(web.tree, (node) => node.props['data-mp-account'] === 'web');
  assert.equal(find(webCard, (node) => node.props['data-mp-account-key-hint']), undefined);
  assert.equal(find(webCard, (node) => node.props['data-mp-account-replace']), undefined);

  const none = render({ mode: 'none', keyHint: '••••1234' });
  const noneCard = find(none.tree, (node) => node.props['data-mp-account'] === 'none');
  assert.equal(find(noneCard, (node) => node.props['data-mp-account-key-hint']), undefined);
  assert.equal(find(noneCard, (node) => node.props['data-mp-account-replace']), undefined);
});
