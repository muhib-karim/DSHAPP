// Loads a dsh client bundle (window.__ModuleLoader__.load({id, factory}) format) in Node and returns its exports.
// Only the factory runs (no apply), with just enough of window/document for module-level code.
import fs from 'node:fs';
import vm from 'node:vm';

export function loadClient(file, { transform = (source) => source, requireModule = () => ({}), globals = {} } = {}) {
  const source = transform(fs.readFileSync(file, 'utf8'));
  let exportsOut;
  const noop = () => {};
  const fakeElement = () => ({ style: { setProperty: noop, removeProperty: noop }, dataset: {}, setAttribute: noop,
    appendChild: noop, addEventListener: noop, removeEventListener: noop, querySelector: () => null,
    querySelectorAll: () => [], classList: { add: noop, remove: noop, contains: () => false } });
  const document = { documentElement: fakeElement(), body: fakeElement(), head: fakeElement(),
    createElement: fakeElement, querySelector: () => null, querySelectorAll: () => [], addEventListener: noop,
    removeEventListener: noop, getElementById: () => null };
  const window = {
    __ModuleLoader__: { load: ({ factory }) => { exportsOut = factory(requireModule); } },
    addEventListener: noop, removeEventListener: noop, matchMedia: () => ({ matches: false, addEventListener: noop }),
    setTimeout, clearTimeout, setInterval: () => 0, clearInterval: noop, innerWidth: 412, innerHeight: 915,
    getComputedStyle: () => ({ getPropertyValue: () => '' }), location: { href: 'http://127.0.0.1/' },
    navigator: { userAgent: 'node' }, localStorage: { getItem: () => null, setItem: noop, removeItem: noop },
    ...globals,
  };
  window.window = window;
  window.document = document;
  const context = vm.createContext({ ...window, window, document, console, MutationObserver: class { observe() {} disconnect() {} },
    ResizeObserver: class { observe() {} disconnect() {} }, requestAnimationFrame: noop, cancelAnimationFrame: noop,
    navigator: window.navigator, location: window.location, localStorage: window.localStorage });
  vm.runInContext(source, context, { filename: file });
  return exportsOut;
}
