// Goal 3 (v1.3.0): the "/" palette refreshes after a plugin install or a skill change without a restart.
// Upstream's palette clears its skill cache on the server event "plugin-manager/changed"; the mobile plugin's Node
// half re-emits it when skill folders change and once more after any other plugin change.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createSkillRefresher, OWN_REASON, EVENT } from '../../mobile-ui-plugin/dsh-android-mobile/lib/skill-refresh.js';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function harness(roots, opts = {}) {
  const emitted = [];
  const listeners = new Set();
  const refresher = createSkillRefresher({
    emit: (event, payload) => { emitted.push({ event, payload }); for (const l of listeners) l(payload); },
    on: (event, listener) => { assert.equal(event, EVENT); listeners.add(listener); return () => listeners.delete(listener); },
    roots, debounceMs: 80, followUpMs: 120, ...opts,
  });
  const external = (reason) => { for (const l of [...listeners]) l({ reason }); };
  return { emitted, refresher, external, listeners };
}

test('a SKILL.md written on disk triggers exactly one refresh after the folder settles', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'skills-'));
  const h = harness([{ path: dir, recursive: true }]);
  assert.equal(h.refresher.watching, 1);
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
  assert.equal(h.refresher.watching, 1);
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
  const r = createSkillRefresher({ emit: () => { throw new Error('boom'); }, roots: [{ path: dir }], debounceMs: 30, log: (m) => logs.push(m) });
  fs.writeFileSync(path.join(dir, 'a.md'), 'a');
  await sleep(200);
  assert.ok(logs.some((m) => m.includes('boom')));
  r.dispose();
});
