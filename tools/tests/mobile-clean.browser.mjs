// Run separately from the dependency-free node suite:
// PLAYWRIGHT_MODULE=<playwright entry> CHROMIUM_EXECUTABLE=<chrome binary> node --test tools/tests/mobile-clean.browser.mjs
import test, { before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { pathToFileURL } from 'node:url';

const bundle = fs.readFileSync(new URL('../../mobile-ui-plugin/dsh-android-mobile/lib/client.js', import.meta.url), 'utf8');
// Expose the real private lifecycle in the test loader; production code and DOM APIs are unchanged.
const instrumented = bundle.replace('exports.__test = {', 'exports.__test = { injectStyles, setupCleanSurfaces,');
let browser;
before(async () => {
  const entry = process.env.PLAYWRIGHT_MODULE;
  const { chromium } = await import(entry ? pathToFileURL(entry).href : 'playwright');
  browser = await chromium.launch({ executablePath: process.env.CHROMIUM_EXECUTABLE, args: ['--no-sandbox'] });
});
after(async () => browser?.close());

async function fixture(t, dark = false) {
  const page = await browser.newPage({ viewport: { width: 412, height: 915 }, isMobile: true, hasTouch: true });
  t.after(() => page.close());
  await page.setContent(`<html data-mobile><head><style>
    body { --dsw-alias-bg-base: #fff; --dsw-specific-menu: #f8f9fa;
      --dsw-alias-bg-layer-1: #f8f9fa; --dsw-alias-bg-document-preview: #fff; }
    body[data-ds-dark-theme] { --dsw-alias-bg-base: #16161a; --dsw-specific-menu: #26272c;
      --dsw-alias-bg-layer-1: #26272c; --dsw-alias-bg-document-preview: #16161a; }
    #root { background-image: linear-gradient(red, blue); }
    [role=dialog] { background: var(--dsw-alias-bg-document-preview); backdrop-filter: blur(10px); }
  </style></head><body ${dark ? 'data-ds-dark-theme' : ''}><div id="root"><main>
    <header class="wSkVaW_header">Conversation</header>
    <section class="lXshSW_root">Task plan</section>
    <div data-trigger-menu><div role="option">/example</div></div>
    <div role="dialog" aria-label="Image preview"><img alt="Attachment" width="300" height="200"
      src="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='300' height='200'%3E%3Crect width='300' height='200' fill='red'/%3E%3C/svg%3E"></div>
  </main></div></body></html>`);
  await page.evaluate(() => {
    window.__ModuleLoader__ = { load: ({ factory }) => { window.mobile = factory(() => ({})); } };
  });
  await page.addScriptTag({ content: instrumented });
  return page;
}

async function start(page) {
  await page.evaluate(() => {
    window.mobile.__test.injectStyles();
    window.disposeClean = window.mobile.__test.setupCleanSurfaces();
  });
}

for (const dark of [false, true]) test(`real CSS clears root wallpaper and makes task plan, menu, header and preview opaque (${dark ? 'dark' : 'light'})`, async (t) => {
  const page = await fixture(t, dark);
  await start(page);
  const actual = await page.evaluate(() => {
    const css = (q) => getComputedStyle(document.querySelector(q));
    return { image: css('#root').backgroundImage, task: css('.lXshSW_root').backgroundColor,
      backgrounds: ['.wSkVaW_header', '[data-trigger-menu]', '[role=dialog]'].map((q) => css(q).backgroundColor),
      blur: css('[role=dialog]').backdropFilter, previewVisible: document.querySelector('img').getBoundingClientRect().width > 0 };
  });
  assert.deepEqual({ image: actual.image, task: actual.task }, {
    image: 'none', task: dark ? 'rgb(67, 69, 74)' : 'rgb(248, 249, 250)',
  });
  for (const color of actual.backgrounds) assert.match(color, /^rgb\(/, color);
  assert.equal(actual.blur, 'none');
  assert.equal(actual.previewVisible, true);
});

test('the guard preserves theme-owned opaque tokens and restores translucent originals on disposal', async (t) => {
  const page = await fixture(t);
  await page.evaluate(() => {
    document.body.style.setProperty('--dsw-alias-bg-base', 'rgb(12, 34, 56)', 'important');
    document.body.style.setProperty('--dsw-specific-menu', 'rgba(200, 100, 0, 0.5)', 'important');
  });
  await start(page);
  assert.equal(await page.$eval('[data-trigger-menu]', (el) => getComputedStyle(el).backgroundColor), 'rgb(106, 67, 28)');
  await page.evaluate(() => window.dispatchEvent(new Event('resize')));
  await page.waitForTimeout(350);
  assert.equal(await page.$eval('[data-trigger-menu]', (el) => getComputedStyle(el).backgroundColor), 'rgb(106, 67, 28)');
  const restored = await page.evaluate(() => {
    window.disposeClean();
    return ['--dsw-alias-bg-base', '--dsw-specific-menu'].map((token) => [document.body.style.getPropertyValue(token), document.body.style.getPropertyPriority(token)]);
  });
  assert.deepEqual(restored, [['rgb(12, 34, 56)', 'important'], ['rgba(200, 100, 0, 0.5)', 'important']]);
});

test('same-scheme theme updates and dark/light transitions flatten the new palette without observer loops', async (t) => {
  const page = await fixture(t);
  await start(page);
  for (const dark of [false, true, false]) {
    await page.evaluate((dark) => {
      document.body.toggleAttribute('data-ds-dark-theme', dark);
      document.body.style.setProperty('--dsw-alias-bg-base', dark ? 'rgb(20, 40, 60)' : 'rgb(60, 40, 20)');
      document.body.style.setProperty('--dsw-specific-menu', 'rgba(100, 200, 0, 0.5)');
    }, dark);
    await page.waitForFunction((color) => getComputedStyle(document.querySelector('[data-trigger-menu]')).backgroundColor === color,
      dark ? 'rgb(60, 120, 30)' : 'rgb(80, 120, 10)', { timeout: 2000 });
  }
  await page.waitForTimeout(350);
  const runs = await page.evaluate(() => window.__mpCleanRuns);
  await page.waitForTimeout(350);
  assert.equal(await page.evaluate(() => window.__mpCleanRuns), runs, 'guard mutations must not retrigger the guard');
  await page.evaluate(() => {
    // A new theme wins even if disposal happens before the scheduled guard runs.
    document.body.style.setProperty('--dsw-specific-menu', 'rgb(90, 80, 70)');
    window.disposeClean();
    document.body.toggleAttribute('data-ds-dark-theme', true);
  });
  await page.waitForTimeout(350);
  assert.equal(await page.evaluate(() => window.__mpCleanRuns), runs);
  assert.equal(await page.$eval('body', (el) => el.style.getPropertyValue('--dsw-specific-menu')), 'rgb(90, 80, 70)');
});

test('wallpaper fixture is hidden while an attachment preview remains visible', async (t) => {
  const page = await fixture(t);
  await start(page);
  await page.evaluate(() => {
    window.__ModuleLoader__.load = ({ factory }) => { window.disposeTheme = factory(() => ({})).apply(); };
  });
  await page.addScriptTag({ content: fs.readFileSync(new URL('./fixtures/dsh-test-glass-theme/lib/client.js', import.meta.url), 'utf8') });
  await page.waitForFunction(() => document.querySelector('#glass-theme-wallpaper').hasAttribute('data-mp-wallpaper-hidden'));
  assert.equal(await page.$eval('#glass-theme-wallpaper', (el) => getComputedStyle(el).display), 'none');
  assert.equal(await page.$eval('img', (el) => el.getBoundingClientRect().width > 0), true);
  assert.match(await page.$eval('[data-trigger-menu]', (el) => getComputedStyle(el).backgroundColor), /^rgb\(/);
});
