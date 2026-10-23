// Goal 1 (v1.3.0): no wallpaper, no glass. The mobile-ui plugin flattens translucent surface tokens to opaque colours,
// hides full-screen decorative layers, and its CSS sets every backdrop-filter to none.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { loadClient } from './load-client.mjs';

const CLIENT = fileURLToPath(new URL('../../mobile-ui-plugin/dsh-android-mobile/lib/client.js', import.meta.url));
const mod = loadClient(CLIENT);
const { flattenColor, isWallpaperLayer, CLEAN_CSS, SURFACE_TOKENS } = mod.__test;
// Arrays made inside the vm realm have another Array.prototype; copy them for deepStrictEqual.
const parseColor = (v) => { const c = mod.__test.parseColor(v); return c && [...c]; };

test('parseColor reads rgb, rgba, hex with alpha and color(srgb)', () => {
  assert.deepEqual(parseColor('rgb(1, 2, 3)'), [1, 2, 3, 1]);
  assert.deepEqual(parseColor('rgba(10, 20, 30, 0.5)'), [10, 20, 30, 0.5]);
  assert.deepEqual(parseColor('#30313673'), [0x30, 0x31, 0x36, 0x73 / 255]);
  assert.deepEqual(parseColor('#fff'), [255, 255, 255, 1]);
  assert.deepEqual(parseColor('color(srgb 1 0 0.5 / 0.25)'), [255, 0, 128, 0.25]);
  assert.equal(parseColor('oklch(0.5 0.1 200)'), null);
});

test('flattenColor composites a translucent surface over the base into an opaque rgb()', () => {
  assert.equal(flattenColor('#30313673', '#16161a'), 'rgb(34, 34, 39)');
  assert.equal(flattenColor('rgba(0, 0, 0, 0)', '#ffffff'), 'rgb(255, 255, 255)');
  assert.equal(flattenColor('rgb(5, 6, 7)', '#ffffff'), null, 'opaque colours are left alone');
  assert.equal(flattenColor('garbage', '#ffffff'), null);
  // A translucent base is itself flattened over black (dark) before use.
  assert.equal(flattenColor('rgba(255, 255, 255, 0.5)', 'rgba(0, 0, 0, 0.5)'), 'rgb(128, 128, 128)');
});

test('isWallpaperLayer: full-screen decorative layers yes, real UI no', () => {
  const vp = { width: 412, height: 915 };
  const base = { tag: 'canvas', position: 'fixed', rect: { width: 412, height: 915 }, pointerEvents: 'none',
    backgroundImage: 'none', textLength: 0, interactive: 0, uiRoot: false };
  assert.equal(isWallpaperLayer(base, vp), true);
  assert.equal(isWallpaperLayer({ ...base, tag: 'video', pointerEvents: 'auto' }, vp), true);
  assert.equal(isWallpaperLayer({ ...base, tag: 'div', pointerEvents: 'auto', backgroundImage: 'url(x.png)' }, vp), true);
  assert.equal(isWallpaperLayer({ ...base, rect: { width: 300, height: 200 } }, vp), false, 'small image');
  assert.equal(isWallpaperLayer({ ...base, position: 'static' }, vp), false, 'in-flow content');
  assert.equal(isWallpaperLayer({ ...base, tag: 'div', pointerEvents: 'auto', textLength: 40, interactive: 3 }, vp), false, 'a dialog');
  assert.equal(isWallpaperLayer({ ...base, uiRoot: true }, vp), false, 'dsh roots are never hidden');
  assert.equal(isWallpaperLayer({ ...base, tag: 'div', pointerEvents: 'none' }, vp), false, 'an empty toast/portal container is not a wallpaper');
});

test('clean CSS: every backdrop-filter is none, no blur, opaque menu and header', () => {
  const values = [...CLEAN_CSS.matchAll(/backdrop-filter\s*:\s*([^;!}]+)/g)].map((m) => m[1].trim());
  assert.ok(values.length >= 2);
  for (const v of values) assert.equal(v, 'none');
  assert.ok(!/blur\(/.test(CLEAN_CSS));
  assert.match(CLEAN_CSS, /\[data-trigger-menu\]/);
  assert.match(CLEAN_CSS, /\.wSkVaW_header/);
  assert.match(CLEAN_CSS, /background-image:\s*none\s*!important/);
});

test('no glass anywhere in the plugin source', () => {
  const src = fs.readFileSync(CLIENT, 'utf8');
  assert.ok(!/filter\s*:[^;]*blur\(/.test(src), "client.js must not set a blur filter");
  for (const token of ['--dsw-alias-bg-base', '--dsw-specific-menu', '--dsw-menu-surface-fill', '--dsw-alias-bg-layer-1'])
    assert.ok(SURFACE_TOKENS.includes(token), token);
});
