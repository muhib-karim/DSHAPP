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

/** `reason` of the events this module emits; its own events never schedule a follow-up (no loop). */
export const OWN_REASON = 'dsh-android: skills changed';
export const EVENT = 'plugin-manager/changed';

/**
 * @param {object} o
 * @param {(event: string, payload: object) => void} o.emit       server-context emit
 * @param {(event: string, listener: Function) => Function} [o.on] server-context listener; returns a disposer
 * @param {{path: string, recursive?: boolean}[]} o.roots           folders whose changes mean "skills may have changed"
 * @param {number} [o.debounceMs]   quiet time after the last file change
 * @param {number} [o.followUpMs]   delay of the follow-up after another plugin change
 * @param {Function} [o.watch]      fs.watch (injectable for tests)
 * @param {(msg: string) => void} [o.log]
 */
export function createSkillRefresher({ emit, on, roots, debounceMs = 800, followUpMs = 2000, watch = fs.watch, log = () => {} }) {
	let fileTimer = null;
	let followTimer = null;
	let disposed = false;
	const watchers = [];

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
		if (fileTimer) clearTimeout(fileTimer);
		fileTimer = setTimeout(() => { fileTimer = null; fire('skill files changed'); }, debounceMs);
	};

	for (const root of roots) {
		try {
			if (!fs.existsSync(root.path)) continue;
			const watcher = watch(root.path, { recursive: !!root.recursive, persistent: false }, onFiles);
			watcher.on?.('error', () => {});
			watchers.push(watcher);
		} catch (error) {
			log(`cannot watch ${root.path}: ${error?.message ?? error}`);
		}
	}

	const off = on ? on(EVENT, (payload) => {
		if (payload?.reason === OWN_REASON) return;
		if (followTimer) clearTimeout(followTimer);
		followTimer = setTimeout(() => { followTimer = null; fire('after a plugin change'); }, followUpMs);
	}) : null;

	return {
		watching: watchers.length,
		dispose() {
			disposed = true;
			if (fileTimer) clearTimeout(fileTimer);
			if (followTimer) clearTimeout(followTimer);
			for (const w of watchers) { try { w.close(); } catch { /* already closed */ } }
			if (typeof off === 'function') off();
		},
	};
}
