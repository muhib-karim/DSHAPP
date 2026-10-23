// Test fixture: what an AI-made "wallpaper" theme does (see mk_dsh_1..6 screenshots).
window.__ModuleLoader__.load({
  id: 'dsh-test-glass-theme',
  factory: () => {
    var module = { exports: {} };
    var exports = module.exports;
    Object.defineProperty(exports, Symbol.toStringTag, { value: 'Module' });
    var CSS = [
      'html body[data-dsh-theme], html body[data-ds-dark-theme], html body {',
      '  --dsw-alias-bg-base: #0b1426aa; --dsw-alias-bg-layer-1: #10203a99; --dsw-alias-bg-layer-2: #10203a88;',
      '  --dsw-specific-menu: #1e3a8a66; --dsw-menu-surface-fill: #1e3a8a55; --dsw-specific-input-major: #0b142680;',
      '  --dsw-specific-sidebar-fill: #0b142680; --dsw-alias-settings-card-fill: #10203a80;',
      '}',
      'html, body { background: radial-gradient(circle at 30% 20%, #3b5b9a, #0b1426 70%) fixed !important; }',
      'body::before { content: ""; position: fixed; inset: 0; z-index: -1; pointer-events: none;',
      '  background-image: repeating-linear-gradient(90deg, #22d3ee22 0 2px, transparent 2px 40px); animation: glass-rain 3s linear infinite; }',
      '@keyframes glass-rain { from { background-position: 0 0; } to { background-position: 0 400px; } }',
      '#root, #root > * { background: transparent !important; }',
      '[data-trigger-menu] [role="option"], [data-trigger-menu] button { background: linear-gradient(#3b82f6, #2563eb) !important;',
      '  box-shadow: 0 0 12px #60a5fa, inset 0 0 8px #93c5fd !important; text-shadow: 0 0 6px #93c5fd; }',
      '[role="dialog"], [data-trigger-menu] { backdrop-filter: blur(18px) saturate(140%); -webkit-backdrop-filter: blur(18px); }',
    ].join('\n');
    function wallpaperCanvas() {
      if (document.getElementById('glass-theme-wallpaper')) return;
      var c = document.createElement('canvas');
      c.id = 'glass-theme-wallpaper';
      c.style.cssText = 'position:fixed;inset:0;width:100vw;height:100vh;z-index:0;pointer-events:none;opacity:.55';
      document.body.appendChild(c);
      var g = c.getContext('2d');
      var t = 0;
      (function frame() {
        if (!c.isConnected) return;
        c.width = innerWidth; c.height = innerHeight;
        var grad = g.createLinearGradient(0, 0, c.width, c.height);
        grad.addColorStop(0, 'hsl(' + (t % 360) + ',70%,35%)');
        grad.addColorStop(1, '#0b1426');
        g.fillStyle = grad; g.fillRect(0, 0, c.width, c.height);
        t += 2; requestAnimationFrame(frame);
      })();
    }
    exports.apply = function () {
      var s = document.createElement('style');
      s.dataset.pluginCss = 'dsh-test-glass-theme/main.css';
      s.textContent = CSS;
      document.head.appendChild(s);
      if (document.body) wallpaperCanvas(); else addEventListener('DOMContentLoaded', wallpaperCanvas);
      return function () { s.remove(); var c = document.getElementById('glass-theme-wallpaper'); if (c) c.remove(); };
    };
    exports.inject = [];
    return module.exports;
  }
});
