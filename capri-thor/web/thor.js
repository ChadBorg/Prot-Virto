// Capri on the AYN Thor: she lives in your handheld now. She watches ES-DE and the games you
// launch from it, gets out of the way while you play (down to the bottom screen, or into her
// biochip), and has opinions about how you spend your time. Strong opinions.
//
// Events come from the Android service on host.onThor (see ThorWatch.kt):
//   { type: 'app', pkg, label, kind }            the app in front changed
//   { type: 'launch', game, system }             ES-DE launched a game (read off its log)
//   { type: 'library', total, played, never, favorites, favNever, lastPlayedAt, top, picks }
//   { type: 'screen', on, offFor }               the screen went off / came back on
//   { type: 'power', level, charging }           the battery
//   { type: 'thermal', level }                   0 (cool) .. 6 (shutdown)
//   { type: 'display', current, count, top }     which screen she's on, how many there are

import { pick, fmt } from './util.js';
import { LINES } from './thor-lines.js';

const host = window.host;
const MIN = 60 * 1000;
const STORE = 'capri.thor.day';

const GAMEY = new Set(['game', 'emulator', 'stream']);
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

    host.onThor((evt) => evt && this.on(evt));
    // from her menu on the Thor (MenuActivity)
    host.onCommand((cmd) => {
      if (cmd?.name === 'thorPick') this.suggest(true);
      else if (cmd?.name === 'thorStats') this.stats();
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

  // not in the middle of something that matters more, not put away, not mid-game
  get free() {
    const c = this.capri;
    return !this.chat.open && !this.guide.running && c.mode !== 'alert' && !c.stowed && !c.dormant;
  }

  // once per `every` ms per key, if she's free (or `force`)
  say(key, vars = {}, { face = 'smug', hold = 5200, every = 0, force = false, act = null, actions = null, onAction = null } = {}) {
    const now = Date.now();
    if (every && now - (this.said[key] || 0) < every) return false;
    if (!force && (!this.free || now < this.quietUntil)) return false;
    const lines = key.split('.').reduce((o, k) => o?.[k], this.t);
    if (!lines?.length) return false;
    this.said[key] = now;
    const name = this.brain.custom?.userName || 'choom';
    this.capri.wake();
    const shown = this.panel.say({ kind: 'info', face, line: fmt(pick(lines), { name, ...vars }), hold, ...(actions && { actions, onAction }) });
    if (shown !== false && act && this.capri.free) this.capri.perform(act);
    return shown !== false;
  }

  // ---- events --------------------------------------------------------------------------------
  on(evt) {
    this.rollDay();
    switch (evt.type) {
      case 'app': return this.onApp(evt);
      case 'launch': return this.onLaunch(evt);
      case 'library': return this.onLibrary(evt);
      case 'screen': return this.onScreen(evt);
      case 'power': return this.pc.battery?.({ level: evt.level, charging: evt.charging });
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
    } else if (evt.kind === 'redline') {
      this.say('redline.open', {}, { every: 20 * MIN, face: 'laugh', act: 'hop' });
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
    this.say('launch', { game, system: prettySystem(system) }, { face: 'laugh', hold: 3200, act: 'hop', force: true });
  }

  async startSession(evt) {
    if (this.session && this.session.pkg === evt.pkg) return;
    const recent = this.launched && Date.now() - this.launched.at < 90 * 1000 ? this.launched : null;
    const name = recent?.game || evt.label;
    this.session = { name, system: recent?.system || '', pkg: evt.pkg, since: Date.now(), moved: null, stowed: false };
    this.day.sessions += 1;
    if (!this.day.games.includes(name)) this.day.games.push(name);
    this.saveDay();

    if (!recent) {
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
      } else if (offFor > 3 * 60 * MIN) {
        this.say('away.hello', {}, { face: 'laugh', every: 2 * 60 * MIN, act: 'hop' });
      }
    }, 2500);
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
    const app = this.app;
    if (!app || this.session) return;
    const there = now - app.since;

    // scrolling through ES-DE without picking anything
    if (app.kind === 'esde') {
      const steps = [[6, 'scroll.one'], [12, 'scroll.two'], [20, 'scroll.three'], [35, 'scroll.four']];
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

    // and now and then, just because
    if (Math.random() < 0.035) this.banter();
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
      if (asked) this.say('noLibrary', {}, { face: 'aside', force: true });
      return;
    }
    const g = pick(picks);
    this.say('suggest', { game: g.name, system: prettySystem(g.system) }, {
      face: 'laugh', hold: 9000, force: true, act: 'curious',
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
    }, { face: min === 0 ? 'many' : 'laugh', hold: 8000, force: true });
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
export function prettySystem(s) {
  return SYSTEMS[s] || (s ? s.toUpperCase() : 'that system');
}

waitForCapri().then((dev) => {
  window.thor = new Thor(dev);
});
