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
const MEMORY = 'capri.thor.memory'; // per-game history across days + what she said lately

// "45 minutes", "3.5 hours"
function fmtMinutes(ms) {
  const m = Math.round(ms / (60 * 1000));
  return m >= 90 ? `${(m / 60).toFixed(1)} hours` : `${m} minutes`;
}

// a timestamp as "today", "yesterday", "5 days ago"
function daysAgo(at) {
  const start = new Date();
  start.setHours(0, 0, 0, 0);
  const days = Math.ceil((start.getTime() - at) / 86400000);
  return days <= 0 ? 'today' : days === 1 ? 'yesterday' : `${days} days ago`;
}
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
    this.memory = this.loadMemory();
    this.selected = null;   // the game you're on in ES-DE: { game, system, info, at }
    this.quietUntil = 0;
    this.roamedAt = Date.now(); // her last trip to the other screen (roam)
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
    setTimeout(() => this.syncView(), 3000);
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

  // a line from thor-lines.js by its dotted key, filled in; never the same one twice in a row for a key
  line(key, vars = {}) {
    const lines = key.split('.').reduce((o, k) => o?.[k], this.t);
    if (!lines?.length) return '';
    const last = (this.lastLine ||= {})[key];
    const fresh = lines.length > 1 ? lines.filter((l) => l !== last) : lines;
    const chosen = pick(fresh);
    this.lastLine[key] = chosen;
    return fmt(chosen, { name: this.brain.custom?.userName || 'choom', ...vars });
  }

  // ---- her long-term memory of your games (kept across days) ---------------------------------
  loadMemory() {
    try {
      const m = JSON.parse(localStorage.getItem(MEMORY) || 'null');
      if (m && m.games) return { games: m.games, said: m.said || [] };
    } catch {
      // she starts over
    }
    return { games: {}, said: [] };
  }

  saveMemory() {
    try {
      localStorage.setItem(MEMORY, JSON.stringify(this.memory));
    } catch {
      // she forgets: no harm done
    }
  }

  memGame(name) {
    const k = String(name || '').toLowerCase().trim();
    if (!k) return null;
    return (this.memory.games[k] ||= { name, launches: 0, playMs: 0, quick: 0, last: [] });
  }

  // a session she saw end: how long, and whether you bailed
  remember(name, ms) {
    const g = this.memGame(name);
    if (!g) return;
    g.playMs += ms;
    if (ms < 3 * MIN) g.quick += 1;
    g.last = [{ at: Date.now(), min: Math.round(ms / MIN) }, ...(g.last || [])].slice(0, 5);
    // keep the 150 most recent games
    const all = Object.entries(this.memory.games);
    if (all.length > 150) {
      all.sort((a, b) => (b[1].last?.[0]?.at || 0) - (a[1].last?.[0]?.at || 0));
      this.memory.games = Object.fromEntries(all.slice(0, 150));
    }
    this.saveMemory();
  }

  // what she remembers about a game with you, in a sentence (for her AI brain and her chat)
  recall(name) {
    const g = this.memory.games[String(name || '').toLowerCase().trim()];
    if (!g || (!g.launches && !g.last?.length)) return '';
    const bits = [];
    if (g.launches) bits.push(`you have seen them launch it ${g.launches} time(s)`);
    if (g.playMs >= MIN) bits.push(`about ${fmtMinutes(g.playMs)} played in total`);
    const l = g.last?.[0];
    if (l) bits.push(`last session ${l.min} minute(s), ${daysAgo(l.at)}`);
    if (g.quick >= 2) bits.push(`they quit it within 3 minutes ${g.quick} times`);
    return bits.join('; ');
  }

  // what she said lately, so she doesn't repeat herself
  rememberSaid(text) {
    this.memory.said = [text, ...this.memory.said.filter((t) => t !== text)].slice(0, 12);
    this.saveMemory();
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
      if (shown !== false) this.rememberSaid(line);
      return shown !== false;
    };
    // what you asked for, and what can't wait (a dying battery), comes at once and word for word
    if (urgent || !this.aiReady) return show(text);
    // her own words from her AI brain; the written line is the fallback (offline, slow, odd answer)
    this.aiLine(key, text, vars).then((own) => {
      if (!force && !this.free) return; // something more important came up while she thought
      show(own || text);
    });
    return true;
  }

  // Needs a real brain: the keyless Pollinations gateway took 0.3-20 s on the Thor, answered 429 (too
  // many requests) after two calls and repeated itself, and her chat shares its allowance. With a key
  // (Groq, Gemini...) or Ollama, she writes her own remarks.
  get aiReady() {
    return !this.aiWhyNot;
  }

  // why her remarks are written lines right now ('' = they're her own)
  get aiWhyNot() {
    if (this.cfg.aiLines === false) return 'switched off';
    const provider = this.cfg.provider || 'pollinations';
    if (provider === 'pollinations') return `no AI provider (${this.cfg.provider || 'unset'})`;
    if (Date.now() <= this.aiDownUntil) return 'backing off';
    if (this.chat.open) return 'chat open';
    return '';
  }

  // One spontaneous remark from her chat brain, in the spirit of `line` (which keeps the facts:
  // game names, numbers). null when it's off, too slow or not usable: then she says `line`.
  async aiLine(key, line, vars = {}) {
    const id = `quip-${++this.quips}`;
    const started = Date.now();
    const system = this.chat.persona() + '\n' + [
      'Right now you are not chatting: you make one spontaneous remark out loud, on your own, about ONE thing that just happened.',
      'React to that thing only. The rest of what you know about their day is background: don\'t narrate it, and never say something is happening unless you are told it is happening now.',
      'Reply with only that remark: at most 25 words, one or two sentences, plain text. No quotes, no tags, no emoji, no markdown.',
    ].join('\n');
    // On the Thor, Groq answered the Chrono Trigger launch with "Firing up REDLINE now..." (from the
    // day's context) and never named the game: the event goes first and plainly, and is checked below.
    const moment = MOMENTS[key] || key.replace(/\./g, ' ');
    let about = vars.about ? `\nWhat their library says about it: ${vars.about}` : '';
    const history = vars.game ? this.recall(vars.game) : '';
    if (history) about += `\nWhat you remember of them and this game: ${history}.`;
    const recent = this.memory.said.slice(0, 6);
    if (recent.length) about += `\nThings you said lately (don't repeat them or their jokes):\n- ${recent.join('\n- ')}`;
    const opinion = vars.game
      ? '\nIf you know this game, give your real take on it, in character: its story, how it plays, its reputation, a ' +
        'detail fans know. Only say what you are sure is true about it; if you don\'t know it, react to its name and their history with it.'
      : '';
    const user = `What just happened: ${moment}. Your usual line here: "${line}"${about}${opinion}\n` +
      'Say something your own way, fresh words, reacting to exactly this. Mention the same game, app or numbers, unchanged.';
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
    let own = res?.ok ? cleanQuip(res.text) : null;
    // about the right thing? A game or app named in the moment has to be in her remark
    const named = vars.game || vars.app;
    if (own && named && !mentions(own, named)) {
      console.log(`[thor] ai line ${key}: off topic (no "${named}"): ${own}`);
      own = null;
    }
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
      case 'select': return this.onSelect(evt);
      case 'preview': return this.onPreview(evt);
      case 'library': return this.onLibrary(evt);
      case 'screen': return this.onScreen(evt);
      case 'power': return this.onPower(evt);
      case 'shake': return this.onShake();
      case 'thermal': return this.onThermal(evt);
      case 'display':
        this.display = { ...this.display, ...evt };
        // after a screen change her view must be the WebView's real size (what she's drawn in)
        for (const ms of [400, 1500]) setTimeout(() => this.syncView(), ms);
        // safety net after a screen change: never left standing outside the screen
        setTimeout(() => {
          const c = this.capri;
          const p = c.pos;
          if (!p || !c.view || c.busy || c.dragging || c.stowed || c.flight) return;
          if (p.x < 0 || p.x > c.view.w || p.y > c.view.h) {
            console.log(`[thor] off screen at ${Math.round(p.x)},${Math.round(p.y)}: back in`);
            c.teleport?.(c.spot?.('random'));
          }
        }, 4000);
        return null;
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

  onLaunch({ game, system, info }) {
    if (!game) return;
    this.launched = { game, system: system || '', info, at: Date.now() };
    clearTimeout(this.hoverTimer);
    const g = this.memGame(game);
    if (g) {
      g.launches += 1;
      g.system = system || g.system;
      this.saveMemory();
    }
    // the emulator comes to the front right after: startSession picks the name up from here
    this.quietUntil = 0;
    const shown = this.say('launch', { game, system: prettySystem(system), about: aboutGame(info) },
      { face: 'laugh', hold: 4200, act: 'hop', force: true });
    // logcat tag CapriWeb: why a launch line didn't show (quiet hours, panel busy, stowed...)
    const c = this.capri;
    console.log(`[thor] launch "${game}" (${system || '?'}) shown=${shown} ai=${this.aiWhyNot || 'ready'} quiet=${this.quietNow()} ` +
      `stowed=${!!c.stowed} dormant=${!!c.dormant} hidden=${!!c.hidden} panel=${this.panel.current?.kind || '-'}/${!!this.panel.visible}`);
  }

  // ES-DE: the game you're on (its clip playing). Rest on one a few seconds and she has a thought about it.
  onSelect({ game, system, info }) {
    if (!game) return;
    this.selected = { game, system: system || '', info, at: Date.now() };
    clearTimeout(this.hoverTimer);
    this.hoverTimer = setTimeout(() => this.hoverRemark(), HOVER_DWELL);
  }

  hoverRemark() {
    const s = this.selected;
    const now = Date.now();
    if (!s || now - s.at < HOVER_DWELL - 300 || this.session || this.app?.kind !== 'esde') return;
    if (this.launched && now - this.launched.at < 30 * 1000) return;
    if (now - (this.hoverSaidAt || 0) < HOVER_GAP * (this.sass?.every || 1)) return;
    // her written lines run out fast: without her AI brain, only now and then
    if (!this.aiReady && Math.random() > 0.3) return;
    const n = s.info?.playcount || 0;
    const key = !s.info ? 'browse.hover' : n === 0 ? 'browse.never' : n >= 5 ? 'browse.again' : 'browse.hover';
    const vars = { game: s.game, system: prettySystem(s.system), count: n, about: aboutGame(s.info) };
    if (this.say(key, vars, { face: 'look', hold: 7000 })) this.hoverSaidAt = now;
  }

  // ES-DE's screensaver playing a game's clip: now and then she reacts to it
  onPreview({ game, system, info }) {
    if (!game || this.session) return;
    if (Date.now() - (this.previewSaidAt || 0) < PREVIEW_GAP * (this.sass?.every || 1)) return;
    setTimeout(() => {
      if (this.session) return;
      const vars = { game, system: prettySystem(system), about: aboutGame(info) };
      if (this.say('preview', vars, { face: 'look', hold: 7000 })) this.previewSaidAt = Date.now();
    }, 2500); // let the clip get going
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
    if (s.kind !== 'redline') this.remember(s.name, ms);
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
  // Her view = the WebView's real size, like app.js's resize handler does (no floor gap on Android).
  syncView() {
    const c = this.capri;
    const w = innerWidth;
    const h = innerHeight;
    console.log(`[thor] screen ${this.display.current}: inner ${w}x${h} dpr ${devicePixelRatio} screen ${screen.width}x${screen.height} ` +
      `view ${Math.round(c.view?.w)}x${Math.round(c.view?.h)} floor ${Math.round(c.view?.floor)} feet ${Math.round(c.pos?.x)},${Math.round(c.pos?.y)}`);
    if (!w || !h || !c.view || (Math.abs(c.view.w - w) < 2 && Math.abs(c.view.h - h) < 2)) return;
    console.log(`[thor] view ${Math.round(c.view.w)}x${Math.round(c.view.h)} -> ${w}x${h} (dpr ${devicePixelRatio})`);
    this.fx?.resize?.(w, h);
    this.chip?.place?.(w, h);
    c.setView(w, h, h);
  }

  // Now and then, with nothing going on, she leaps over to the other screen through their shared
  // edge (Overlay.kt lays them side by side for that). Never during a game or a stream (the dodge
  // keeps her off your game's screen), nor over a video, nor when wandering is off in her menu.
  async roam(now) {
    const c = this.capri;
    if (this.cfg.wander === false || this.display.count < 2 || this.session) return;
    if (!this.free || c.busy || c.dragging || c.act || c.mode === 'sleep' || c.mode === 'alert') return;
    if (['video', 'social', 'stream', 'redline', 'game', 'emulator'].includes(this.app?.kind)) return;
    if (now - this.roamedAt < ROAM_GAP || Math.random() > ROAM_CHANCE) return;
    this.roamedAt = now;
    const other = await host.thor('otherScreen');
    if (other == null || this.session) return;
    console.log(`[thor] roam ${this.display.current} -> ${other}`);
    host.thor('goScreen', other);
  }

  tick() {
    this.rollDay();
    const now = Date.now();
    this.batteryNag(now);
    if (this.session) return this.sessionTick(now);
    this.roam(now);
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
    // what they're looking at in ES-DE ("what do you think of this one?")
    const sel = this.selected;
    if (!this.session && sel && this.app?.kind === 'esde' && Date.now() - sel.at < 10 * MIN) {
      parts.push(`In ES-DE they have ${sel.game} (${prettySystem(sel.system)}) selected right now; "this one" means it. ${aboutGame(sel.info)}`);
    }
    // her memory of the games she's seen them play, most recent first
    const known = Object.values(this.memory.games)
      .filter((g) => g.last?.length || g.launches)
      .sort((a, b) => (b.last?.[0]?.at || 0) - (a.last?.[0]?.at || 0))
      .slice(0, 8)
      .map((g) => `${g.name}: ${this.recall(g.name)}`);
    if (known.length) parts.push(`What you remember of their recent games:\n- ${known.join('\n- ')}`);
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
// ES-DE browsing: a thought about the game you rest on for HOVER_DWELL, at most every HOVER_GAP (and
// her screensaver remarks at most every PREVIEW_GAP), both stretched by her sass level
const HOVER_DWELL = 5000;
const HOVER_GAP = 75 * 1000;
const PREVIEW_GAP = 4 * MIN;

// what's happening, for her AI brain (the key alone was too vague)
const MOMENTS = {
  launch: 'they just launched this game from ES-DE, it is starting now',
  'browse.hover': 'they are browsing ES-DE and have stopped on this game for a few seconds; its preview clip is playing',
  'browse.never': 'they are browsing ES-DE and have stopped on this game, which they own but have never launched; its preview clip is playing',
  'browse.again': 'they are browsing ES-DE and have stopped on this game, one they play a lot; its preview clip is playing',
  preview: 'ES-DE\'s screensaver is playing a clip of this game from their library while they are idle',
  'game.quick': 'they just quit this game after only a few minutes',
  'game.end': 'they just finished a play session of this game and came back to ES-DE',
  'game.marathon': 'they just finished a very long session of this game',
  'game.start': 'they just started playing this game',
  hopping: 'they keep launching games and quitting them within minutes, this one was the latest',
};

// What their gamelist says about a game, for her AI brain: genre, year, developer, how much they play it.
export function aboutGame(info) {
  if (!info) return '';
  const bits = [];
  const what = [info.genre, info.year && `from ${info.year}`, info.developer && `by ${info.developer}`].filter(Boolean).join(', ');
  if (what) bits.push(what);
  const n = info.playcount || 0;
  bits.push(n ? `they have launched it ${n} time(s)` : 'they have never launched it');
  if (info.favorite) bits.push('it is one of their favorites');
  if (info.lastplayed) bits.push(`last played ${ago(info.lastplayed)}`);
  let s = `${bits.join('; ')}.`;
  if (info.desc) s += ` The library's blurb: ${String(info.desc).slice(0, 350)}`;
  return s;
}

// ES-DE's "20261109T175102" as "today", "yesterday", "12 days ago"
function ago(stamp) {
  const m = /^(\d{4})(\d{2})(\d{2})T/.exec(stamp || '');
  if (!m) return 'a while ago';
  const days = Math.floor((Date.now() - new Date(+m[1], +m[2] - 1, +m[3]).getTime()) / 86400000);
  return days <= 0 ? 'today' : days === 1 ? 'yesterday' : `${days} days ago`;
}

// roaming between the Thor's screens: at least ROAM_GAP apart, then a ROAM_CHANCE per 30 s tick
// (about every 4-6 minutes when nothing's going on)
const ROAM_GAP = 3 * MIN;
const ROAM_CHANCE = 0.3;

// her AI-written remarks: how long she may think before saying her written line instead
// (Pollinations answers in 0.4-9 s on the Thor; Groq or a local Ollama in well under a second)
const AI_LINE_TIMEOUT = 7000;

// does `text` name `name`? Exactly, or by any distinctive word of it ("Chrono" for "Chrono Trigger",
// "Zelda" for "The Legend of Zelda: A Link Between Worlds"), ignoring case and punctuation
const FILLER = new Set(['edition', 'remastered', 'deluxe', 'definitive', 'collection', 'version', 'remake', 'game',
  'with', 'from', 'into', 'your', 'this', 'that', 'emulator']);
export function mentions(text, name) {
  const norm = (s) => String(s).toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
  const t = ` ${norm(text)} `;
  const n = norm(name);
  if (!n || t.includes(` ${n} `)) return true;
  const words = n.split(' ').filter((w) => w.length >= 4 && !FILLER.has(w));
  return words.length === 0 || words.some((w) => t.includes(` ${w}`));
}

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
