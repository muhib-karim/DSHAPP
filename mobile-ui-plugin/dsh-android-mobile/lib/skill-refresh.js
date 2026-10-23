/**
 * Keeps the "/" palette's skill list fresh (v1.3.0, owner: "in DeepSeek everything is a plugin").
 *
 * Upstream's palette (`@deepseek-ai/dsh-client-ui-skill`) caches `skills/list` per session and drops the cache only on
 * the server event `plugin-manager/changed` (or a preset switch / reconnect). The engine itself sees new skills right
 * away (it watches the skill folders), but nothing tells the browser, so an open palette keeps the old list:
 *   - a SKILL.md added or removed on disk (by the user, the AI, or an unpacked preset) emits no event at all;
 *   - after an install, the plugin manager emits once, possibly before the new bundle's skills are registered.
 * This module re-emits that same event (with its own `reason`) when the skill folders settle and once more shortly
 * after any other plugin change. Upstream's palette logic is unchanged; it simply refetches.
 *
 * @module dsh-android-mobile/skill-refresh
 */

import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';

/** `reason` of the events this module emits; its own events never schedule a follow-up (no loop). */
export const OWN_REASON = 'dsh-android: skills changed';
export const EVENT = 'plugin-manager/changed';

const identity = (stat) => stat && `${stat.dev}:${stat.ino}`;
const metadata = (name, stat) => `${name}\0${stat.size}:${stat.mtimeMs}:${stat.ctimeMs}:${stat.ino}\n`;

function watchRoot(file, options, listener) {
	// Some Node versions swallow allocation errors while constructing a recursive watcher on Linux.
	// Probe the native root watch first so ENOSPC reaches our fallback instead of returning an inert handle.
	const probe = options.recursive ? fs.watch(file, { persistent: false }, () => {}) : null;
	try { return fs.watch(file, options, listener); }
	finally { probe?.close(); }
}

// A resumable metadata walk for roots whose native watcher failed. No file contents are read, symlinks are
// never followed, and opendir releases its handles when the iterator completes or the refresher is disposed.
async function* snapshotEntries(root) {
	async function* visit(file, relative) {
		try {
			const stat = await fs.promises.lstat(file);
			yield metadata(relative, stat);
			if (!stat.isDirectory() || (relative && !root.recursive)) return;
			const dir = await fs.promises.opendir(file);
			for await (const entry of dir) {
				if (entry.name === '.git' || entry.name === 'node_modules') continue;
				yield* visit(path.join(file, entry.name), `${relative}/${entry.name}`);
			}
		} catch (error) {
			yield `${relative}\0${error.code || 'unreadable'}\n`;
		}
	}
	yield* visit(root.path, '');
}

/**
 * @param {object} o
 * @param {(event: string, payload: object) => void} o.emit       server-context emit
 * @param {(event: string, listener: Function) => Function} [o.on] server-context listener; returns a disposer
 * @param {{path: string, recursive?: boolean}[]} o.roots           folders whose changes mean "skills may have changed"
 * @param {number} [o.debounceMs]   quiet time after the last file change
 * @param {number} [o.followUpMs]   delay of the follow-up after another plugin change
 * @param {Function} [o.watch]      fs.watch (injectable for tests)
 * @param {number} [o.pollMs]      fallback/root-health interval; native watchers never scan the tree
 * @param {number} [o.retryMs]     minimum interval between native watcher allocation retries
 * @param {number} [o.scanBatchEntries] maximum metadata entries visited per root per fallback tick
 * @param {(msg: string) => void} [o.log]
 */
export function createSkillRefresher({ emit, on, roots, debounceMs = 800, followUpMs = 2000, watch = watchRoot,
	pollMs = 5000, retryMs = 30000, scanBatchEntries = 256, log = () => {} }) {
	let fileTimer = null;
	let followTimer = null;
	let disposed = false;
	let pollTimer = null;
	const states = roots.map((root) => ({ root, watcher: null, identity: null, retryAt: 0, scan: null, signature: null }));

	const fire = (why) => {
		if (disposed) return;
		try {
			emit(EVENT, { reason: OWN_REASON });
			log(`skill palette refresh (${why})`);
		} catch (error) {
			log(`skill palette refresh failed: ${error?.message ?? error}`);
		}
	};
	const onFiles = () => {
		if (disposed) return;
		if (fileTimer) clearTimeout(fileTimer);
		fileTimer = setTimeout(() => { fileTimer = null; fire('skill files changed'); }, debounceMs);
	};

	const closeWatcher = (state) => {
		try { state.watcher?.close(); } catch { /* already closed */ }
		state.watcher = null;
	};
	const closeScan = (state) => {
		const scan = state.scan;
		state.scan = null;
		if (scan) scan.entries.return().catch(() => {});
	};
	const tryWatch = (state) => {
		state.retryAt = Date.now() + retryMs;
		try {
			const stat = fs.lstatSync(state.root.path);
			state.identity = identity(stat);
			if (!stat.isDirectory()) return;
			const watcher = watch(state.root.path, { recursive: !!state.root.recursive, persistent: false }, onFiles);
			state.watcher = watcher;
			watcher.on?.('error', (error) => {
				if (disposed || state.watcher !== watcher) return;
				closeWatcher(state);
				state.signature = null;
				state.retryAt = Date.now() + retryMs;
				log(`skill watcher stopped; using bounded polling: ${error?.message ?? error}`);
			});
			closeScan(state);
		} catch (error) {
			if (error.code !== 'ENOENT') log(`cannot watch ${state.root.path}; using bounded polling: ${error?.message ?? error}`);
		}
	};
	for (const state of states) tryWatch(state);

	async function maintain() {
		try {
			for (const state of states) {
				if (disposed) break;
				const stat = await fs.promises.lstat(state.root.path).catch(() => null);
				if (disposed) break;
				if (identity(stat) !== state.identity) {
					closeWatcher(state);
					closeScan(state);
					state.identity = identity(stat);
					state.signature = null;
					state.retryAt = 0;
					onFiles(); // deleted/recreated root: fs.watch otherwise stays attached to the old inode
				}
				if (!state.watcher && Date.now() >= state.retryAt) tryWatch(state);
				if (state.watcher) continue;
				if (!state.scan) state.scan = { entries: snapshotEntries(state.root), hash: createHash('sha256') };
				const scan = state.scan;
				for (let count = 0; count < Math.max(1, scanBatchEntries) && !disposed; count++) {
					const next = await scan.entries.next();
					if (disposed) break;
					if (next.done) {
						const signature = scan.hash.digest('hex');
						// One initial invalidation closes the gap while fallback monitoring was starting.
						if (signature !== state.signature) onFiles();
						state.signature = signature;
						state.scan = null;
						break;
					}
					scan.hash.update(next.value);
				}
			}
		} catch (error) {
			log(`skill watcher health check failed: ${error?.message ?? error}`);
		} finally {
			if (!disposed) schedulePoll();
		}
	}
	function schedulePoll() {
		if (!states.length) return;
		pollTimer = setTimeout(maintain, Math.max(1, pollMs));
		pollTimer.unref?.();
	}
	schedulePoll();

	const off = on ? on(EVENT, (payload) => {
		if (payload?.reason === OWN_REASON) return;
		if (followTimer) clearTimeout(followTimer);
		followTimer = setTimeout(() => { followTimer = null; fire('after a plugin change'); }, followUpMs);
	}) : null;

	return {
		get watching() { return states.filter((state) => state.watcher).length; },
		dispose() {
			if (disposed) return;
			disposed = true;
			if (fileTimer) clearTimeout(fileTimer);
			if (followTimer) clearTimeout(followTimer);
			if (pollTimer) clearTimeout(pollTimer);
			for (const state of states) { closeWatcher(state); closeScan(state); }
			if (typeof off === 'function') off();
		},
	};
}
