// Goal 3 (v1.3.0): the "/" palette refreshes after a plugin install or a skill change without a restart.
// Upstream's palette clears its skill cache on the server event "plugin-manager/changed"; the mobile plugin's Node
// half re-emits it when skill folders change and once more after any other plugin change.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { EventEmitter } from 'node:events';
import { createSkillRefresher, OWN_REASON, EVENT } from '../../mobile-ui-plugin/dsh-android-mobile/lib/skill-refresh.js';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function harness(roots, opts = {}) {
  const emitted = [];
  const listeners = new Set();
  const refresher = createSkillRefresher({
    emit: (event, payload) => { emitted.push({ event, payload }); for (const l of listeners) l(payload); },
    on: (event, listener) => { assert.equal(event, EVENT); listeners.add(listener); return () => listeners.delete(listener); },
    roots, debounceMs: 80, followUpMs: 120, pollMs: 25, retryMs: 1000, ...opts,
  });
  const external = (reason) => { for (const l of [...listeners]) l({ reason }); };
  return { emitted, refresher, external, listeners };
}

test('a SKILL.md written on disk triggers exactly one refresh after the folder settles', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'skills-'));
  const h = harness([{ path: dir, recursive: true }]);
  fs.mkdirSync(path.join(dir, 'late-skill'));
  fs.writeFileSync(path.join(dir, 'late-skill', 'SKILL.md'), '---\nname: late-skill\ndescription: x\n---\n');
  fs.appendFileSync(path.join(dir, 'late-skill', 'SKILL.md'), 'body\n');
  await sleep(400);
  assert.deepEqual(h.emitted, [{ event: EVENT, payload: { reason: OWN_REASON } }]);
  h.refresher.dispose();
});

test('a plugin install (external plugin-manager/changed) gets one follow-up refresh, and its own events do not loop', async () => {
  const h = harness([]);
  h.external('install @acme/dsh-skills');
  await sleep(50);
  assert.equal(h.emitted.length, 0, 'follow-up waits for the new bundle to register its skills');
  await sleep(200);
  assert.equal(h.emitted.length, 1);
  await sleep(300);
  assert.equal(h.emitted.length, 1, 'our own event did not schedule another one');
  h.refresher.dispose();
});

test('bursts of plugin changes collapse into one follow-up', async () => {
  const h = harness([]);
  h.external('a'); await sleep(40); h.external('b'); await sleep(40); h.external('c');
  await sleep(300);
  assert.equal(h.emitted.length, 1);
  h.refresher.dispose();
});

test('missing folders are skipped; dispose stops watchers, timers and the listener', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'skills-'));
  const h = harness([{ path: path.join(dir, 'missing') }, { path: dir }]);
  h.external('install');
  h.refresher.dispose();
  assert.equal(h.listeners.size, 0);
  fs.writeFileSync(path.join(dir, 'x.md'), 'x');
  await sleep(300);
  assert.equal(h.emitted.length, 0);
});

test('an emit that throws is contained', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'skills-'));
  const logs = [];
  const r = createSkillRefresher({ emit: () => { throw new Error('boom'); }, roots: [{ path: dir }], debounceMs: 30,
    pollMs: 25, log: (m) => logs.push(m) });
  fs.writeFileSync(path.join(dir, 'a.md'), 'a');
  await sleep(200);
  assert.ok(logs.some((m) => m.includes('boom')));
  r.dispose();
});

const noWatch = () => { throw Object.assign(new Error('watch allocation failed'), { code: 'ENOSPC' }); };

async function waitFor(predicate, label) {
  const end = Date.now() + 1500;
  while (!predicate() && Date.now() < end) await sleep(20);
  assert.ok(predicate(), label);
}

function temporary(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'skills-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  return dir;
}

test('ENOSPC fallback detects nested skill edits and removal without emitting while unchanged', async (t) => {
  const dir = temporary(t);
  fs.mkdirSync(path.join(dir, 'existing'));
  const skill = path.join(dir, 'existing', 'SKILL.md');
  fs.writeFileSync(skill, 'old');
  const h = harness([{ path: dir, recursive: true }], { watch: noWatch });
  t.after(() => h.refresher.dispose());
  await sleep(250); // initial fallback snapshot settles before edits
  h.emitted.length = 0;
  fs.writeFileSync(skill, 'changed description');
  await waitFor(() => h.emitted.length === 1, 'nested file change invalidates the palette');
  await sleep(180);
  assert.equal(h.emitted.length, 1, 'unchanged snapshots are silent');
  fs.rmSync(skill);
  await waitFor(() => h.emitted.length === 2, 'skill deletion invalidates the palette');
});

test('a root created after startup is discovered and watched', async (t) => {
  const dir = temporary(t);
  const root = path.join(dir, 'later');
  const h = harness([{ path: root, recursive: true }], { watch: noWatch });
  t.after(() => h.refresher.dispose());
  await sleep(180);
  h.emitted.length = 0;
  fs.mkdirSync(root);
  fs.mkdirSync(path.join(root, 'new'));
  fs.writeFileSync(path.join(root, 'new', 'SKILL.md'), 'new skill');
  await waitFor(() => h.emitted.length > 0, 'late root creation invalidates the palette');
});

test('an asynchronous watcher failure falls back and disposal closes resources and cancels callbacks', async (t) => {
  const dir = temporary(t);
  const skill = path.join(dir, 'SKILL.md');
  fs.writeFileSync(skill, 'before');
  const watcher = new EventEmitter();
  let closed = 0;
  let watches = 0;
  watcher.close = () => { closed++; };
  const h = harness([{ path: dir, recursive: true }], { watch: () => { watches++; return watcher; } });
  t.after(() => h.refresher.dispose());
  watcher.emit('error', new Error('watch stopped'));
  await sleep(200);
  h.emitted.length = 0;
  fs.writeFileSync(skill, 'after error');
  await waitFor(() => h.emitted.length > 0, 'failed watcher falls back to file observation');
  h.refresher.dispose();
  const before = h.emitted.length;
  fs.writeFileSync(skill, 'after disposal');
  await sleep(180);
  assert.equal(h.emitted.length, before);
  assert.equal(h.listeners.size, 0);
  assert.equal(closed, watches);
});

test('failed allocation retries later and successful watchers stop recursive polling', async (t) => {
  const dir = temporary(t);
  let calls = 0;
  let closed = 0;
  const watcher = new EventEmitter();
  watcher.close = () => { closed++; };
  const h = harness([{ path: dir, recursive: true }], { retryMs: 100,
    watch: () => { if (++calls === 1) return noWatch(); return watcher; } });
  t.after(() => h.refresher.dispose());
  await waitFor(() => h.refresher.watching === 1, 'watch allocation eventually recovers');
  assert.equal(calls, 2);
  h.refresher.dispose();
  assert.equal(closed, 1);
});

test('fallback advances bounded scan batches to detect skills beyond the first batch', async (t) => {
  const dir = temporary(t);
  for (let i = 0; i < 16; i++) fs.writeFileSync(path.join(dir, `skill-${i}.md`), 'before');
  const h = harness([{ path: dir, recursive: true }], { watch: noWatch, scanBatchEntries: 4 });
  t.after(() => h.refresher.dispose());
  await sleep(450);
  h.emitted.length = 0;
  fs.writeFileSync(path.join(dir, 'skill-15.md'), 'after a bounded batch');
  await waitFor(() => h.emitted.length > 0, 'scan resumes beyond its first batch');
});
