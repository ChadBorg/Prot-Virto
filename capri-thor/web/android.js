// Capri on the AYN Thor: `window.host` (what Electron's preload gave her on the PC), backed by the
// Android service through the `CapriBridge` JavaScript interface. Loaded before app.js.
//
//   JS -> Android : CapriBridge.call(method, JSON args, callId)   (callId 0: no answer wanted)
//   Android -> JS : __hostResolve(callId, JSON result)  and  __hostEmit(channel, JSON payload)
//
// Also turns the touch screen into the mouse she expects (tap = click, drag = drag, long-press =
// her menu) and tells Android where she can be touched: everywhere else, touches go through her.
(() => {
  'use strict';

  const bridge = window.CapriBridge || {
    // in a desktop browser (tests): everything answers null
    call: (method, args, id) => id && setTimeout(() => window.__hostResolve(id, 'null'), 0),
  };

  let seq = 0;
  const pending = new Map();
  const listeners = Object.create(null);

  window.__hostResolve = (id, json) => {
    const p = pending.get(id);
    if (!p) return;
    pending.delete(id);
    let value = null;
    try {
      value = json == null ? null : JSON.parse(json);
    } catch {
      value = null;
    }
    p(value);
  };

  window.__hostEmit = (channel, json) => {
    let payload = null;
    try {
      payload = JSON.parse(json);
    } catch {
      payload = null;
    }
    for (const fn of listeners[channel] || []) {
      try {
        fn(payload);
      } catch (err) {
        console.error('[capri] listener', channel, err);
      }
    }
  };

  const call = (method, ...args) => new Promise((resolve) => {
    const id = ++seq;
    pending.set(id, resolve);
    bridge.call(method, JSON.stringify(args), id);
  });
  const send = (method, ...args) => bridge.call(method, JSON.stringify(args), 0);
  const on = (channel) => (fn) => {
    (listeners[channel] ||= []).push(fn);
    return () => {
      listeners[channel] = (listeners[channel] || []).filter((f) => f !== fn);
    };
  };

  // the last place a finger touched her window: what she knows of "the pointer"
  let finger = null;

  window.host = {
    onClaudeEvent: on('claude:event'),
    onCommand: on('capri:command'),
    onConfig: on('capri:config'),
    onResize: on('overlay:resize'),
    onWindows: on('windows:update'),
    onChatDelta: on('chat:delta'),
    onPcProgress: on('pc:progress'),
    onSetupProgress: on('setup:progress'),
    onThor: on('thor:event'), // the Thor's own events (thor.js)

    setInteractive: () => {}, // Android decides from her rects (below)
    rearm: () => {},
    cursor: async () => (finger ? { x: finger.x, y: finger.y, inside: true, display: finger.display } : null),
    screens: () => call('screens'),
    moveScreen: (id) => call('moveScreen', id),
    getConfig: () => call('getConfig'),
    setConfig: (patch) => call('setConfig', patch),
    listSounds: async () => [],
    hooksStatus: async () => ({ installed: false, file: '' }),
    demoWindow: async () => false,
    openMenu: () => send('openMenu'),
    installHooks: async () => ({ ok: false }),
    claudeFound: async () => false,
    openClaudeHelp: () => {},
    windowsNow: () => call('windowsNow'),
    closePage: async () => 'gone',
    trayPlace: async () => null,
    openClaudeFolder: async () => null,
    copyImage: async () => false,
    chatStatus: () => call('chatStatus'),
    chatSend: (request) => call('chatSend', request),
    chatStop: (id) => send('chatStop', id),
    chatMemory: () => call('chatMemory'),
    chatRemember: (history) => send('chatRemember', history),
    lookSnap: async () => ({ ok: false, error: 'not on the Thor' }),
    lookSay: async () => ({ ok: false, error: 'not on the Thor' }),
    lookWhere: () => call('lookWhere'),
    cpu: () => call('cpu'),
    pcDo: (req) => call('pcDo', req),
    pcIdle: () => call('idle'),
    openOllama: () => send('openUrl', 'https://ollama.com'),
    setupInfo: () => call('setupInfo'),
    ollamaState: () => call('ollamaState'),
    installOllama: () => call('installOllama'),
    startOllama: () => call('ollamaState'),
    pullModel: (name) => call('pullModel', name),
    cancelSetup: () => send('cancelSetup'),
    useProvider: (choice) => call('useProvider', choice),
    keyPage: (provider) => send('keyPage', provider),
    launchThroughOllama: async () => ({ ok: false }),
    openApps: () => call('openApps'),
    findApp: (wanted) => call('findApp', wanted),
    closeApp: async () => ({ error: 'Android closes apps itself' }),
    findLaunch: (text) => call('findLaunch', text),
    launch: (item) => call('launch', item),
    media: (action) => call('media', action),
    music: (query) => call('music', query),
    youtube: (query) => call('youtube', query),
    roblox: (query) => call('roblox', query),
    searchFiles: async () => [],
    openFile: async () => false,
    screenshot: async () => null,
    webSearch: (query) => call('webSearch', query),
    openSite: (address) => call('openUrl', address),
    focusOverlay: (on) => send('focusOverlay', Boolean(on)),

    // Thor extras (thor.js)
    thor: (method, ...args) => call(method, ...args),
  };

  // ---- touch -> mouse ------------------------------------------------------------------------
  // Chromium gives a touch screen no mousemove while dragging, and her whole body (drag, throw,
  // pet) speaks mouse. So: one finger becomes the mouse.
  const LONG_PRESS = 550;
  let pressTimer = 0;
  let start = null;
  let longPressed = false;
  const fire = (type, t, target, extra = {}) => {
    const ev = new MouseEvent(type, {
      bubbles: true, cancelable: true, view: window, button: extra.button ?? 0, buttons: extra.buttons ?? 0,
      clientX: t.clientX, clientY: t.clientY, screenX: t.screenX, screenY: t.screenY,
    });
    (target || document.elementFromPoint(t.clientX, t.clientY) || document.body).dispatchEvent(ev);
  };
  const native = (el) => el && el.closest && el.closest('input, textarea, button, a, select, .panel__action');

  document.addEventListener('touchstart', (e) => {
    if (e.touches.length !== 1) return;
    const t = e.touches[0];
    finger = { x: t.clientX, y: t.clientY };
    start = { x: t.clientX, y: t.clientY, target: e.target, at: performance.now() };
    longPressed = false;
    if (native(e.target)) return; // buttons and her chat input work as they are
    e.preventDefault();
    fire('mousemove', t);
    fire('mousedown', t, null, { buttons: 1 });
    clearTimeout(pressTimer);
    pressTimer = setTimeout(() => {
      longPressed = true;
      fire('mouseup', t);
      fire('contextmenu', t, null, { button: 2 });
    }, LONG_PRESS);
  }, { passive: false, capture: true });

  document.addEventListener('touchmove', (e) => {
    if (!start || native(start.target) || e.touches.length !== 1) return;
    const t = e.touches[0];
    finger = { x: t.clientX, y: t.clientY };
    if (Math.hypot(t.clientX - start.x, t.clientY - start.y) > 10) clearTimeout(pressTimer);
    e.preventDefault();
    if (!longPressed) fire('mousemove', t, null, { buttons: 1 });
  }, { passive: false, capture: true });

  const end = (e) => {
    if (!start || native(start.target)) {
      start = null;
      return;
    }
    clearTimeout(pressTimer);
    const t = e.changedTouches[0];
    e.preventDefault();
    if (!longPressed) {
      fire('mouseup', t);
      const moved = Math.hypot(t.clientX - start.x, t.clientY - start.y);
      if (moved < 12 && performance.now() - start.at < LONG_PRESS) fire('click', t);
    }
    start = null;
  };
  document.addEventListener('touchend', end, { passive: false, capture: true });
  document.addEventListener('touchcancel', end, { passive: false, capture: true });

  // ---- where she can be touched --------------------------------------------------------------
  // Her body, her panel and her biochip/mask: Android puts small touchable windows there and
  // lets every other touch through to the game or ES-DE underneath.
  const rectOf = (el, pad = 0) => {
    if (!el || el.hidden || el.offsetParent === null && getComputedStyle(el).position !== 'fixed') return null;
    const r = el.getBoundingClientRect();
    if (r.width < 4 || r.height < 4) return null;
    const style = getComputedStyle(el);
    if (style.display === 'none' || style.visibility === 'hidden' || Number(style.opacity) === 0) return null;
    return [Math.round(r.left - pad), Math.round(r.top - pad), Math.round(r.width + 2 * pad), Math.round(r.height + 2 * pad)];
  };
  let lastRects = '';
  const report = () => {
    const sprite = document.getElementById('sprite');
    const dev = window.dev;
    const hidden = dev?.capri && (dev.capri.stowed || dev.capri.hidden);
    const rects = [
      hidden ? null : bodyRect(sprite),
      rectOf(document.getElementById('panel'), 4),
      rectOf(document.getElementById('chip'), 6),
      rectOf(document.getElementById('mask'), 6),
    ].filter(Boolean);
    const json = JSON.stringify(rects);
    if (json !== lastRects) {
      lastRects = json;
      send('hitRects', rects, window.devicePixelRatio || 1);
    }
  };
  // her sprite canvas is roomy (mane, effects): a box around the middle of it, where she is
  const bodyRect = (sprite) => {
    const r = rectOf(sprite);
    if (!r) return null;
    const [x, y, w, h] = r;
    return [Math.round(x + w * 0.2), Math.round(y + h * 0.08), Math.round(w * 0.6), Math.round(h * 0.84)];
  };
  setInterval(report, 120);
})();
