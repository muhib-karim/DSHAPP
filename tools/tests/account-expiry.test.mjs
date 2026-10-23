import test from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { loadClient } from './load-client.mjs';

const CLIENT = fileURLToPath(new URL('../../mobile-ui-plugin/dsh-android-mobile/lib/client.js', import.meta.url));

// Render the real Account section. React's hooks and the Android state/action bridge are the external boundaries;
// the card, warning and button labels come from the shipped client bundle unchanged.
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

test('an expired web session warns and offers to sign in again', () => {
  const { tree, actions } = render({ mode: 'web', label: 'someone@example.com', webExpired: true });
  const card = find(tree, (node) => node.props['data-mp-account'] === 'web');
  const warning = find(card, (node) => node.props['data-mp-account-expired'] === '1');
  assert.ok(warning, 'the expired-session warning is visible');
  assert.match(warning.children.join(''), /expired/i);
  const primary = find(card, (node) => node.tag === 'button');
  assert.equal(primary.children.join(''), 'Sign in again');
  primary.props.onClick();
  assert.deepEqual(actions, [['accountSwitch', '']]);
});

test('a working web session keeps the ordinary switch label and no warning', () => {
  const { tree } = render({ mode: 'web', label: 'someone@example.com', webExpired: false });
  const card = find(tree, (node) => node.props['data-mp-account'] === 'web');
  assert.equal(find(card, (node) => node.props['data-mp-account-expired']), undefined);
  assert.equal(find(card, (node) => node.tag === 'button').children.join(''), 'Switch account');
});

test('a stale flag on another sign-in does not leak into the API-key card', () => {
  const { tree } = render({ mode: 'api_key', webExpired: true });
  const card = find(tree, (node) => node.props['data-mp-account'] === 'api_key');
  assert.equal(find(card, (node) => node.props['data-mp-account-expired']), undefined);
  assert.equal(find(card, (node) => node.tag === 'button').children.join(''), 'Switch account');
});

test('signed out still offers sign in even if a stale expiry flag exists', () => {
  const { tree } = render({ mode: 'none', webExpired: true });
  const card = find(tree, (node) => node.props['data-mp-account'] === 'none');
  assert.equal(find(card, (node) => node.props['data-mp-account-expired']), undefined);
  assert.equal(find(card, (node) => node.tag === 'button').children.join(''), 'Sign in');
});
