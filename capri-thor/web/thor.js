// Capri on the AYN Thor: she lives in your handheld now. She watches ES-DE and the games you
// launch from it, gets out of the way while you play (down to the bottom screen, or into her
// biochip), and has opinions about how you spend your time. Strong opinions.
//
// Events come from the Android service on host.onThor (see ThorWatch.kt):
//   { type: 'app', pkg, label, kind }            the app in front changed
//   { type: 'launch', game, system }             ES-DE launched a game (read off its log)
//   { type: 'library', total, played, never, favorites, favNever, lastPlayedAt, top, picks }
//   { type: 'screen', on, offFor }               the screen went off / came back on
//   { type: 'power', level, charging, chargeMs } the battery (chargeMs: Android's guess to full)
//   { type: 'thermal', level }                   0 (cool) .. 6 (shutdown)
//   { type: 'display', current, count, top }     which screen she's on, how many there are
//   { type: 'shake' }                            you shook the Thor (she noticed)

import { pick, fmt } from './util.js';
import { LINES } from './thor-lines.js';

const host = window.host;
const MIN = 60 * 1000;
const STORE = 'capri.thor.day';

const GREETED = 'capri.thor.greeted';
// REDLINE counts too: it's PC gaming, and (dualScreen) she gets off the bottom screen its HUD uses
const GAMEY = new Set(['game', 'emulator', 'stream', 'redline']);
// battery levels she speaks up at, once per discharge (lowest crossed wins)
const THRESHOLDS = [30, 20, 15, 10, 5, 2];
// how mouthy she is (her settings): cooldowns, patience in ES-DE, random banter
const SASS = {
  chill: { every: 2.5, scroll: 1.6, banter: 0.3 },
  normal: { every: 1, scroll: 1, banter: 1 },
  unhinged: { every: 0.5, scroll: 0.6, banter: 2.2 },
};
const today = () => new Date().toDateString();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function waitForCapri() {
  return new Promise((resolve) => {
    const t = setInterval(() => {
      if (window.dev?.capri && window.dev?.panel) {
        clearInterval(t);
        resolve(window.dev);
      }
    }, 250);
  });
}

class Thor {
  constructor(dev) {
    Object.assign(this, dev); // capri, brain, panel, chat, guide, fx, voice, bond, pc, ...
    this.app = null;        // { pkg, label, kind, since }
    this.launched = null;   // the last game ES-DE launched: { game, system, at }
    this.session = null;    // a game being played: { name, system, since, pkg, moved, stowed }
    this.library = null;
    this.display = { current: 0, count: 1, top: 0 };
    this.said = {};         // key -> last time she said it (ms)
    this.hops = [];         // short sessions lately (commitment issues)
    this.day = this.loadDay();
    this.quietUntil = 0;
    this.quips = 0;         // AI-written remarks asked for (ids)
    this.aiDownUntil = 0;   // after a failed AI remark: written lines only until then
    this.cfg = {};
    this.batt = null;       // { pct, charging, samples: [[t, pct]], warned: [], full, nagAt }
    this.shakes = [];
    host.getConfig().then((c) => { if (c) this.cfg = c; });
    host.onConfig((c) => { if (c) this.cfg = c; });
    // the battery is hers now: pc.js would say it twice (and wrongly as she starts)
    this.pc.battery = () => {};

    host.onThor((evt) => evt && this.on(evt));
    // from her menu on the Thor (MenuActivity)
    host.onCommand((cmd) => {
      if (cmd?.name === 'thorPick') this.suggest(true);
      else if (cmd?.name === 'thorStats') this.stats();
      else if (cmd?.name === 'thorBattery') this.batteryCheck();
    });
    this.patchChat();
    setInterval(() => this.tick(), 30 * 1000);
    host.thor('thorReady');
  }

  // ---- the day, kept across restarts ---------------------------------------------------------
  loadDay() {
    try {
      const d = JSON.parse(localStorage.getItem(STORE) || 'null');
      if (d?.date === today()) return d;
    } catch {
      // a fresh day
    }
    return { date: today(), playMs: 0, sessions: 0, games: [], esdeMs: 0, scrollMs: 0, nagged: false };
  }

  saveDay() {
    try {
      localStorage.setItem(STORE, JSON.stringify(this.day));
    } catch {
      // she forgets: no harm done
    }
  }

  rollDay() {
    if (this.day.date !== today()) this.day = this.loadDay();
  }

  // ---- talking -------------------------------------------------------------------------------
  get t() {
    return LINES;
  }

  get sass() {
    return SASS[this.cfg.sass] || SASS.normal;
  }

  // quiet hours (her settings): 23:00-08:00, only what you asked for and a dying battery
  quietNow() {
    if (!this.cfg.quietHours) return false;
    const h = new Date().getHours();
    return h >= 23 || h < 8;
  }

  // a line from thor-lines.js by its dotted key, filled in
  line(key, vars = {}) {
    const lines = key.split('.').reduce((o, k) => o?.[k], this.t);
    if (!lines?.length) return '';
    return fmt(pick(lines), { name: this.brain.custom?.userName || 'choom', ...vars });
  }

  // not in the middle of something that matters more, not put away, not mid-game
  get free() {
    const c = this.capri;
    return !this.chat.open && !this.guide.running && c.mode !== 'alert' && !c.stowed && !c.dormant;
  }

  // once per `every` ms per key (stretched by her sass), if she's free (or `force`). `urgent`:
  // you asked, or it can't wait (quiet hours don't apply)
  say(key, vars = {}, { face = 'smug', hold = 5200, every = 0, force = false, urgent = false, act = null, actions = null, onAction = null } = {}) {
    const now = Date.now();
    if (!urgent && this.quietNow()) return false;
    if (every && now - (this.said[key] || 0) < every * (urgent ? 1 : this.sass.every)) return false;
    if (!force && !urgent && (!this.free || now < this.quietUntil)) return false;
    const text = this.line(key, vars);
    if (!text) return false;
    this.said[key] = now;
    const show = (line) => {
      this.capri.wake();
      const shown = this.panel.say({ kind: 'info', face, line, hold, ...(actions && { actions, onAction }) });
      if (shown !== false && act && this.capri.free) this.capri.perform(act);
      return shown !== false;
    };
    // what you asked for, and what can't wait (a dying battery), comes at once and word for word
    if (urgent || !this.aiReady) return show(text);
    // her own words from her AI brain; the written line is the fallback (offline, slow, odd answer)
    this.aiLine(key, text).then((own) => {
      if (!force && !this.free) return; // something more important came up while she thought
      show(own || text);
    });
    return true;
  }

  // Needs a real brain: the keyless Pollinations gateway took 0.3-20 s on the Thor, answered 429 (too
  // many requests) after two calls and repeated itself, and her chat shares its allowance. With a key
  // (Groq, Gemini...) or Ollama, she writes her own remarks.
  get aiReady() {
    const provider = this.cfg.provider || 'pollinations';
    return this.cfg.aiLines !== false && provider !== 'pollinations' && Date.now() > this.aiDownUntil && !this.chat.open;
  }

  // One spontaneous remark from her chat brain, in the spirit of `line` (which keeps the facts:
  // game names, numbers). null when it's off, too slow or not usable: then she says `line`.
  async aiLine(key, line) {
    const id = `quip-${++this.quips}`;
    const started = Date.now();
    const system = this.chat.persona() + '\n' + [
      'Right now you are not chatting: you make one spontaneous remark out loud, on your own, about what just happened.',
      'Reply with only that remark: at most 25 words, one or two sentences, plain text. No quotes, no tags, no emoji, no markdown.',
    ].join('\n');
    const user = `The moment (${key}). Your usual line here would be: "${line}"\n` +
      'Say something in that spirit, in fresh words of your own, reacting to this moment and what you know about them today. ' +
      'Keep game names and numbers exactly as they are; add no facts you weren\'t given.';
    const timeout = new Promise((resolve) => setTimeout(() => resolve(null), AI_LINE_TIMEOUT));
    let res = null;
    try {
      res = await Promise.race([
        host.chatSend({ id, messages: [{ role: 'system', content: system }, { role: 'user', content: user }] }),
        timeout,
      ]);
    } catch (e) {
      res = null;
    }
    const own = res?.ok ? cleanQuip(res.text) : null;
    if (!res) host.chatStop(id); // too slow: don't let it finish in the background
    // the free gateway is sometimes just slow (0.4 s one try, 9 s the next): a short break after a
    // timeout, a longer one after an error
    if (!res) this.aiDownUntil = Date.now() + MIN;
    else if (!res.ok) this.aiDownUntil = Date.now() + 5 * MIN;
    console.log(`[thor] ai line ${key}: ${own ? 'own' : res ? (res.ok ? 'unusable' : `error ${res.error}`) : 'timeout'} in ${Date.now() - started} ms`);
    return own;
  }

  // ---- events --------------------------------------------------------------------------------
  on(evt) {
    this.rollDay();
    switch (evt.type) {
      case 'app': return this.onApp(evt);
      case 'launch': return this.onLaunch(evt);
      case 'library': return this.onLibrary(evt);
      case 'screen': return this.onScreen(evt);
      case 'power': return this.onPower(evt);
      case 'shake': return this.onShake();
      case 'thermal': return this.onThermal(evt);
      case 'display': this.display = { ...this.display, ...evt }; return null;
      case 'say': return this.say(evt.key, evt.vars || {}, { force: true });
      default: return null;
    }
  }

  onApp(evt) {
    const prev = this.app;
    const now = Date.now();
    if (prev?.pkg === evt.pkg) return;
    if (prev) this.leave(prev, now - prev.since);
    this.app = { ...evt, since: now };

    if (GAMEY.has(evt.kind)) return this.startSession(evt);
    if (this.session) this.endSession();

    if (evt.kind === 'esde') {
      this.scrollStart = now;
      this.scrollWarned = 0;
      if (prev && GAMEY.has(prev.kind)) return; // back from a game: endSession said it
      if (Math.random() < 0.45) this.say('esde.open', {}, { every: 20 * MIN, face: 'look' });
    } else if (evt.kind === 'video' || evt.kind === 'social') {
      this.say(`${evt.kind}.open`, { app: evt.label }, { every: 15 * MIN, face: 'aside' });
    } else if (evt.kind === 'store') {
      this.say('store.open', { app: evt.label }, { every: 30 * MIN, face: 'smug' });
    }
  }

  // what you were doing, now that you stopped
  leave(prev, ms) {
    if (prev.kind === 'esde') this.day.esdeMs += ms;
    this.saveDay();
  }

  onLaunch({ game, system }) {
    if (!game) return;
    this.launched = { game, system: system || '', at: Date.now() };
    // the emulator comes to the front right after: startSession picks the name up from here
    this.quietUntil = 0;
    const shown = this.say('launch', { game, system: prettySystem(system) }, { face: 'laugh', hold: 3200, act: 'hop', force: true });
    // logcat tag CapriWeb: why a launch line didn't show (quiet hours, panel busy, stowed...)
    const c = this.capri;
    console.log(`[thor] launch "${game}" (${system || '?'}) shown=${shown} quiet=${this.quietNow()} ` +
      `stowed=${!!c.stowed} dormant=${!!c.dormant} hidden=${!!c.hidden} panel=${this.panel.current?.kind || '-'}/${!!this.panel.visible}`);
  }

  async startSession(evt) {
    if (this.session && this.session.pkg === evt.pkg) return;
    const recent = this.launched && Date.now() - this.launched.at < 90 * 1000 ? this.launched : null;
    const name = recent?.game || evt.label;
    this.session = { name, system: recent?.system || '', pkg: evt.pkg, kind: evt.kind, since: Date.now(), moved: null, stowed: false };
    this.day.sessions += 1;
    if (!this.day.games.includes(name)) this.day.games.push(name);
    this.saveDay();

    if (evt.kind === 'redline') {
      this.say('redline.open', {}, { every: 20 * MIN, face: 'laugh', act: 'hop', hold: 2400 });
    } else if (!recent) {
      const key = evt.kind === 'stream' ? 'stream.open' : 'game.start';
      this.say(key, { game: name, app: evt.label }, { face: 'smug', hold: 3000, force: true });
    }
    await sleep(2600);
    if (this.session?.pkg !== evt.pkg) return;
    await this.dodge(evt);
  }

  // Out of the way while you play: down to the other screen if there is one, else into her chip.
  async dodge(evt) {
    const s = this.session;
    const { count, current, top } = this.display;
    if (evt.dualScreen || count < 2) {
      if (!this.capri.stowed) {
        s.stowed = true;
        this.capri.stow();
      }
      return;
    }
    if (current === top) {
      const other = await host.thor('otherScreen');
      if (other == null) {
        s.stowed = true;
        this.capri.stow();
        return;
      }
      s.moved = current;
      this.say('game.dodge', {}, { face: 'laugh', hold: 2400, force: true });
      host.thor('goScreen', other);
    }
  }

  endSession() {
    const s = this.session;
    this.session = null;
    if (!s) return;
    const ms = Date.now() - s.since;
    this.day.playMs += ms;
    this.saveDay();
    const min = Math.max(1, Math.round(ms / MIN));
    const back = async () => {
      if (s.stowed && this.capri.stowed) await this.capri.unstow();
      if (s.moved != null) host.thor('goScreen', s.moved);
      await sleep(900);
      // straight into another game: its launch line beats a roast about the last one. ES-DE only
      // counts as "in front" after a moment, so the next launch can even come *before* this session
      // ends, and the roast then landed a second later on top of the launch line (seen on the Thor).
      if ((this.launched && Date.now() - this.launched.at < 15 * 1000) || this.session) return;
      // a peek at REDLINE's menus isn't "quitting a game": no roast, she just comes back
      if (s.kind === 'redline' && ms < 3 * MIN) return;
      if (ms < 3 * MIN) {
        this.hops.push(Date.now());
        this.hops = this.hops.filter((t) => Date.now() - t < 20 * MIN);
        if (this.hops.length >= 3) {
          this.hops = [];
          this.say('hopping', { game: s.name }, { face: 'many', force: true, act: 'huff' });
        } else {
          this.say('game.quick', { game: s.name, min }, { face: 'wide', force: true, act: 'shake' });
        }
      } else if (ms > 90 * MIN) {
        this.say('game.marathon', { game: s.name, min }, { face: 'laugh', force: true, act: 'victory' });
      } else {
        this.say('game.end', { game: s.name, min }, { face: 'look', force: true });
      }
    };
    back();
  }

  onLibrary(lib) {
    const first = !this.library;
    this.library = lib;
    if (!first) return;
    // the first time she reads your library: an opinion, obviously
    setTimeout(() => {
      if (lib.total > 50 && lib.never / lib.total > 0.7) {
        this.say('library.backlog', { total: lib.total.toLocaleString(), never: lib.never.toLocaleString() }, { face: 'many', every: 6 * 60 * MIN });
      }
    }, 25 * 1000);
  }

  onScreen({ on, offFor = 0 }) {
    if (!on) {
      if (this.session) this.endSession();
      return;
    }
    // back after a while: she noticed
    const lastPlayed = this.library?.lastPlayedAt || 0;
    const days = lastPlayed ? Math.floor((Date.now() - lastPlayed) / (24 * 60 * MIN)) : 0;
    setTimeout(() => {
      if (offFor > 4 * 60 * MIN && days >= 2) {
        this.say('away.days', { days }, { face: 'aside', every: 4 * 60 * MIN, act: 'huff' });
      } else if (!this.greet() && offFor > 3 * 60 * MIN) {
        this.say('away.hello', {}, { face: 'laugh', every: 2 * 60 * MIN, act: 'hop' });
      }
    }, 2500);
  }

  // the first time you pick the Thor up each day: good morning (or whatever time it is)
  greet() {
    let last = null;
    try {
      last = localStorage.getItem(GREETED);
    } catch {
      // no memory: she greets you again, no harm
    }
    if (last === today()) return false;
    const h = new Date().getHours();
    const key = h >= 5 && h < 12 ? 'greet.morning' : h >= 12 && h < 18 ? 'greet.afternoon' : h >= 18 && h < 23 ? 'greet.evening' : 'greet.night';
    const shown = this.say(key, {}, { face: 'laugh', force: true, act: 'hop' });
    if (shown) {
      try {
        localStorage.setItem(GREETED, today());
      } catch {
        // see above
      }
    }
    return shown;
  }

  // ---- the battery --------------------------------------------------------------------------
  onPower({ level, charging, chargeMs = 0 }) {
    const pct = Math.round(level * 100);
    const now = Date.now();
    const b = this.batt;
    if (!b) {
      // how it is when she starts: nothing to say about that
      this.batt = { pct, charging, samples: charging ? [] : [[now, pct]], warned: THRESHOLDS.filter((s) => pct <= s), full: pct >= 100, nagAt: now };
      return;
    }
    const plugged = charging !== b.charging;
    b.pct = pct;
    b.charging = charging;
    if (this.cfg.batteryWarn === false) return;
    if (plugged) {
      b.samples = charging ? [] : [[now, pct]];
      if (charging) {
        const low = b.warned.some((s) => s <= 15);
        b.warned = [];
        b.full = false;
        const mins = chargeMs > 0 ? Math.round(chargeMs / MIN) : 0;
        const full = mins ? (mins >= 90 ? `${(mins / 60).toFixed(1)} hours` : `${mins} minutes`) : 'a bit';
        if (this.say(low ? 'battery.saved' : 'battery.plugged', { pct, full }, { face: 'laugh', every: MIN, force: true }) && this.capri.free) {
          this.capri.recharge?.();
        }
      } else {
        this.say('battery.unplugged', { pct }, { face: 'aside', every: MIN });
      }
      return;
    }
    if (charging) {
      if (pct >= 100 && !b.full) {
        b.full = true;
        this.say('battery.full', {}, { face: 'smug', every: 30 * MIN });
      }
      return;
    }
    b.samples.push([now, pct]);
    b.samples = b.samples.filter(([t]) => now - t < 40 * MIN);
    const crossed = THRESHOLDS.filter((s) => pct <= s && !b.warned.includes(s));
    if (!crossed.length) return;
    b.warned.push(...crossed);
    this.warnBattery(Math.min(...crossed), `battery.low${Math.min(...crossed)}`);
  }

  // % per minute over the last while, once there's enough to tell
  drainRate() {
    const s = this.batt?.samples || [];
    if (s.length < 2) return 0;
    const [t0, p0] = s[0];
    const [t1, p1] = s[s.length - 1];
    if (t1 - t0 < 8 * MIN || p0 <= p1) return 0;
    return (p0 - p1) / ((t1 - t0) / MIN);
  }

  eta(fallback = 'not long') {
    const r = this.drainRate();
    if (!r || !this.batt) return fallback;
    const m = Math.round(this.batt.pct / r);
    return m >= 90 ? `about ${(m / 60).toFixed(1)} hours` : `about ${Math.max(1, m)} minutes`;
  }

  // On screen if she can be seen; put away (a game on the only screen), a notification instead.
  warnBattery(step, key) {
    const b = this.batt;
    b.nagAt = Date.now();
    const vars = { pct: b.pct, eta: this.eta(), game: this.session?.name || 'whatever this is' };
    const urgent = step <= 10;
    if (!this.capri.stowed) {
      const shown = this.say(key, vars, { face: step <= 5 ? 'wide' : step <= 15 ? 'aside' : 'smug', hold: 7000, force: step <= 20, urgent });
      if (shown && step <= 10 && this.capri.free) this.capri.drain?.();
      if (shown) return;
    }
    if (step <= 15 && (urgent || !this.quietNow())) host.thor('notify', this.line(key, vars));
  }

  // under 15% and still not plugged in: she brings it up again now and then
  batteryNag(now) {
    const b = this.batt;
    if (!b || b.charging || b.pct > 15 || this.cfg.batteryWarn === false) return;
    if (now - b.nagAt < 15 * MIN * this.sass.every) return;
    this.warnBattery(b.pct <= 10 ? 10 : 15, 'battery.nag');
  }

  // "Battery check" in her menu
  batteryCheck() {
    const b = this.batt;
    if (!b) return this.say('battery.unknown', {}, { face: 'aside', urgent: true });
    if (b.charging) return this.say('battery.checkCharging', { pct: b.pct }, { face: 'smug', urgent: true, hold: 6000 });
    const rate = this.drainRate();
    return this.say('battery.check', {
      pct: b.pct, eta: this.eta('hard to say yet'), rate: rate ? `${Math.round(rate * 60)}% an hour` : 'not sure yet',
    }, { face: b.pct <= 20 ? 'aside' : 'smug', urgent: true, hold: 7000 });
  }

  // ---- shaking the Thor -------------------------------------------------------------------------
  onShake() {
    if (this.session || this.capri.dragging || this.capri.stowed || this.chat.open || this.cfg.shake === false) return;
    const now = Date.now();
    this.shakes = this.shakes.filter((t) => now - t < 2 * MIN);
    this.shakes.push(now);
    this.capri.wake();
    if (this.capri.free) this.capri.perform('shake');
    if (this.shakes.length >= 3) {
      this.shakes = [];
      this.say('shake.angry', {}, { face: 'many', urgent: true });
      this.bond?.lose?.(1);
      this.bond?.feel?.('angry');
    } else {
      const again = this.shakes.length === 2;
      this.say(again ? 'shake.again' : 'shake.first', {}, { face: again ? 'aside' : 'wide', urgent: true });
      this.bond?.feel?.('dizzy');
    }
  }

  onThermal({ level }) {
    if (level >= 3) {
      this.say('hot', {}, { face: 'wide', every: 20 * MIN, act: 'overheat' });
    }
  }

  // ---- the procrastination engine ------------------------------------------------------------
  tick() {
    this.rollDay();
    const now = Date.now();
    this.batteryNag(now);
    if (this.session) return this.sessionTick(now);
    const app = this.app;
    if (!app) return;
    const there = now - app.since;

    // scrolling through ES-DE without picking anything
    if (app.kind === 'esde') {
      const k = this.sass.scroll;
      const steps = [[6, 'scroll.one'], [12, 'scroll.two'], [20, 'scroll.three'], [35, 'scroll.four']].map(([m, key]) => [Math.round(m * k), key]);
      const level = steps.filter(([m]) => there >= m * MIN).length - 1; // the last step passed
      if (level >= 0 && level >= (this.scrollWarned || 0)) {
        const [m, key] = steps[level];
        this.scrollWarned = level + 1;
        const offer = this.library?.picks?.length ? [{ id: 'pick', label: LINES.ui.pickForMe, primary: true }] : null;
        this.say(key, { min: m }, {
          face: level >= 2 ? 'many' : 'aside', force: level >= 2, act: level >= 2 ? 'huff' : 'look',
          actions: offer, onAction: () => this.suggest(),
        });
      }
    }

    // watching other people play / doomscrolling
    if ((app.kind === 'video' || app.kind === 'social') && there > 12 * MIN) {
      const key = there > 30 * MIN ? `${app.kind}.long` : `${app.kind}.while`;
      this.say(key, { app: app.label, min: Math.round(there / MIN) }, { face: 'aside', every: 25 * MIN });
    }

    // evening and nothing played today
    const hour = new Date().getHours();
    if (hour >= 19 && hour <= 23 && !this.day.nagged && this.day.playMs < 5 * MIN && there < 10 * MIN) {
      if (this.say('lazy.evening', {}, { face: 'many', act: 'huff' })) {
        this.day.nagged = true;
        this.saveDay();
      }
    }

    // way past bedtime
    if (hour >= 1 && hour < 5 && there < 20 * MIN) this.say('late.idle', {}, { face: 'aside', every: 3 * 60 * MIN });

    // and now and then, just because
    if (Math.random() < 0.035 * this.sass.banter) this.banter();
  }

  // during a game she's quiet, but from the other screen she still keeps an eye on you
  sessionTick(now) {
    const s = this.session;
    if (this.capri.stowed) return; // put away on your only screen: not a peep
    const ms = now - s.since;
    const hour = new Date().getHours();
    if (!s.lateSaid && hour >= 1 && hour < 5 && ms > 10 * MIN) {
      s.lateSaid = this.say('late.game', { game: s.name }, { face: 'look', force: true, every: 2 * 60 * MIN });
    }
    if (!s.longSaid && ms > 120 * MIN) {
      s.longSaid = this.say('game.long', { game: s.name, hours: Math.floor(ms / (60 * MIN)) }, { face: 'laugh', force: true });
    }
    const perHour = this.drainRate() * 60;
    if (!s.hungrySaid && perHour > 30 && ms > 10 * MIN && !this.batt?.charging) {
      s.hungrySaid = this.say('battery.hungry', { game: s.name, rate: Math.round(perHour), eta: this.eta() }, { face: 'wide', force: true });
    }
  }

  banter() {
    const lib = this.library;
    const options = ['idle'];
    if (lib?.never) options.push('library.never');
    if (lib?.top?.name) options.push('library.top');
    if (this.day.playMs > 60 * MIN) options.push('praise');
    const key = pick(options);
    this.say(key, {
      never: lib?.never?.toLocaleString(), total: lib?.total?.toLocaleString(),
      game: lib?.top?.name, count: lib?.top?.count, hours: Math.round(this.day.playMs / (60 * MIN)),
    }, { face: pick(['smug', 'look', 'laugh']), every: 20 * MIN });
  }

  // "Pick for me": a game from your library you never touched (favorites first)
  suggest(asked = false) {
    const picks = this.library?.picks || [];
    if (!picks.length) {
      if (asked) this.say('noLibrary', {}, { face: 'aside', urgent: true });
      return;
    }
    const g = pick(picks);
    this.say('suggest', { game: g.name, system: prettySystem(g.system) }, {
      face: 'laugh', hold: 9000, urgent: true, act: 'curious',
    });
  }

  // "How am I doing?" in her menu: today, in numbers, with commentary
  stats() {
    this.rollDay();
    const d = this.day;
    const min = Math.round(d.playMs / MIN);
    const key = min === 0 ? 'stats.none' : min < 30 ? 'stats.some' : 'stats.lots';
    this.say(key, {
      min, sessions: d.sessions, scroll: Math.round(d.esdeMs / MIN),
      games: d.games.slice(-3).join(', ') || 'nothing',
    }, { face: min === 0 ? 'many' : 'laugh', hold: 8000, urgent: true });
  }

  // ---- her chat knows she lives in a Thor now --------------------------------------------------
  patchChat() {
    const chat = this.chat;
    const persona = chat.persona.bind(chat);
    chat.persona = () => persona()
      .replace("living on the user's desktop as a small glitchy figure", "living in the user's AYN Thor handheld")
      .replace("You live on the user's desktop as a small glitchy figure", "You live in the user's AYN Thor (a dual-screen Android gaming handheld) as a small glitchy figure")
      .concat('\n', this.context());
    chat.powers = () => [
      'Only when the user asks you to open an app or to play music or control it: say in a few words that you will, and end your answer with one tag: [[open: APP NAME]], [[music: ARTIST - SONG]] (it plays on YouTube), [[media: ACTION]] (ACTION: pause, play, next, previous, volup, voldown or mute), or [[site: ADDRESS]]. Any other time, write no tag at all.',
      'You also keep reminders and timers, and search the web when asked. Games are launched from ES-DE, the frontend on the Thor: you can recommend one from their library, but they press the button.',
      'You can\'t see the screen or the user\'s files: if asked, say so plainly, in character.',
    ];
  }

  // what she knows about how they game, for her chat
  context() {
    const d = this.day;
    const lib = this.library;
    const parts = [
      'Tease the user about procrastination and their gaming habits now and then (scrolling the library instead of playing, never finishing anything, huge backlog), playfully, never mean.',
      `Today on the Thor: ${Math.round(d.playMs / MIN)} minutes of games over ${d.sessions} session(s)${d.games.length ? ` (${d.games.slice(-5).join(', ')})` : ''}, ${Math.round(d.esdeMs / MIN)} minutes browsing ES-DE.`,
    ];
    if (this.batt) parts.push(`The Thor's battery: ${this.batt.pct}%${this.batt.charging ? ', charging' : `, on battery (${this.eta('unknown time')} left)`}.`);
    if (this.session) parts.push(`Right now they are playing ${this.session.name}.`);
    else if (this.app) parts.push(`Right now the app in front is ${this.app.label}.`);
    if (lib) {
      parts.push(`Their ES-DE library: ${lib.total} games, ${lib.never} never played, ${lib.favorites} favorites (${lib.favNever} of them never played).`);
      if (lib.top?.name) parts.push(`Most played: ${lib.top.name} (${lib.top.count} times).`);
      if (lib.picks?.length) parts.push(`If they ask what to play, suggest one of these unplayed ones: ${lib.picks.slice(0, 6).map((p) => `${p.name} (${prettySystem(p.system)})`).join(', ')}.`);
    }
    return parts.join('\n');
  }
}

const SYSTEMS = {
  nes: 'NES', snes: 'SNES', n64: 'N64', gc: 'GameCube', wii: 'Wii', wiiu: 'Wii U', switch: 'Switch', gb: 'Game Boy',
  gbc: 'Game Boy Color', gba: 'GBA', nds: 'DS', n3ds: '3DS', psx: 'PS1', ps2: 'PS2', ps3: 'PS3', psp: 'PSP', psvita: 'Vita',
  genesis: 'Genesis', megadrive: 'Mega Drive', saturn: 'Saturn', dreamcast: 'Dreamcast', mastersystem: 'Master System',
  gamegear: 'Game Gear', arcade: 'Arcade', mame: 'MAME', fbneo: 'FinalBurn Neo', neogeo: 'Neo Geo', pcengine: 'PC Engine',
  atari2600: 'Atari 2600', windows: 'PC', steam: 'Steam', android: 'Android', segacd: 'Sega CD', sega32x: '32X', xbox: 'Xbox',
};
// her AI-written remarks: how long she may think before saying her written line instead
// (Pollinations answers in 0.4-9 s on the Thor; Groq or a local Ollama in well under a second)
const AI_LINE_TIMEOUT = 7000;

// an AI remark as she'd say it, or null if it isn't one (too long, a refusal, a list...)
export function cleanQuip(text) {
  if (!text) return null;
  let s = String(text)
    .replace(/\[\[[^\]]*\]\]/g, '')        // her chat's action tags
    .replace(/[*_`#>]/g, '')               // markdown
    .replace(/\s+/g, ' ')
    .trim()
    .replace(/^capri\s*:\s*/i, '')
    .replace(/^["'“”‘’]+|["'“”‘’]+$/g, '')
    .trim();
  if (s.length < 4 || s.length > 220) return null;
  if (/\b(as an ai|language model|i can(?:'|no)t help|i'm sorry, but)\b/i.test(s)) return null;
  return s;
}

export function prettySystem(s) {
  return SYSTEMS[s] || (s ? s.toUpperCase() : 'that system');
}

waitForCapri().then((dev) => {
  window.thor = new Thor(dev);
});
