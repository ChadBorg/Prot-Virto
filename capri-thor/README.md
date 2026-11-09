# Capri for the AYN Thor

[Capri](https://github.com/mochiiwuff/capri-desktop) (the Project Capricorn construct by mochiiwuff) ported from the
Windows desktop to the **AYN Thor**. She starts with the Thor, lives on your **bottom screen** and haunts **ES-DE**.
She comments on what you launch, gets out of the way while you play, and has a lot to say about how long you scroll
through your library without picking anything.

Her renderer is upstream's own code and art: her animations, intro, satellite, throwing, chat, bond and jealousy all
come from upstream. The build pulls them from upstream at a pinned commit (`tools/sync_upstream.py`). This folder
adds the Android side, which replaces Electron and Windows:

| On the PC (upstream) | On the Thor (here) |
|---|---|
| Electron transparent window | Foreground service + `TYPE_APPLICATION_OVERLAY` WebView, click-through except on her |
| Mouse hover / click / right-click | Tap = click, drag/throw = drag, **long-press = her menu** |
| Tray icon + menu | Notification (Menu · Put away · **Turn off**), Quick Settings tile, long-press menu |
| Start with Windows | Starts on **boot** (toggle in the app) |
| windows.ps1: window in front, games | Usage access: app in front (ES-DE, emulators, YouTube, ChatGPT…) |
| — | **ES-DE log** (`logs/es_log.txt`): the exact game + system you launched |
| — | **ES-DE gamelists**: library size, never-played count, favorites, most played, last played |
| Multiple monitors | The Thor's two screens: during a game on the top screen she **jumps to the bottom one**. Dual-screen emulators (melonDS, DraStic, Azahar…) make her stow away instead. |
| Battery / CPU / USB | Battery, **thermal status**, controllers plugged in or out |
| Ollama via winget, API keys | Pollinations (no key), Groq/Gemini/OpenRouter/OpenAI/Mistral/DeepSeek keys, **Ollama in Termux on the Thor** or on your PC |

## What's new for the Thor (thor.js)

- **ES-DE awareness.** She notices when you open ES-DE and reacts to every game it launches by name and system
  ("Chrono Trigger! On SNES! Finally, some action.").
- **The procrastination engine.** She escalates as you scroll ES-DE without launching anything, at 6, 12, 20 and 35
  minutes, and offers a **Pick for me** button that suggests a never-played favorite from your library.
- **Game sessions.** She roasts you if you quit within 3 minutes, notices game-hopping ("commitment issues"), asks
  whether you won, and cheers marathons (then tells you to drink water).
- **Laziness.** In the evening, if you haven't played anything today, she lets you know. Coming back after days
  away, she counts them. Long YouTube, TikTok or Reddit sessions on a gaming handheld get commentary.
- **Your library.** She reads your gamelists and has opinions ("812 games. 772 never launched. That's not a
  library, that's a hoard.").
- **Her chat knows.** When you talk to her, she knows today's playtime, what you're playing, your backlog, and what
  to recommend.
- **REDLINE.** She recognises the REDLINE streaming app from this repo.

## Install

1. Get `CapriThor-apk` from the repo's **Actions** tab, the *Build Thor apps* run, and install it.
2. Open **Capri** and grant everything on the *Jack her in* panel:
   - **Draw over other apps** is required.
   - **Usage access** tells her which app is in front.
   - **All files access** lets her read ES-DE's `logs/` and `gamelists/`. The default folder is
     `/storage/emulated/0/ES-DE`; change it in the app if yours lives elsewhere.
   - **Ignore battery optimisation**, so Android doesn't kill her.
   - **Notifications**, where her off switch lives.
3. Tap **Wake her up**. From then on she starts with the Thor. To turn her off, use the notification's
   **Turn off**, the Quick Settings tile, or the button in the app. **Start when the Thor boots** can be unticked.

### Her brain (chat)

- **Online, no key** (Pollinations) is the default and works immediately.
- **Ollama on the Thor itself.** The Thor Max has plenty of RAM. Install Termux, then:
  ```
  pkg install ollama
  ollama serve &
  ollama pull gemma3:4b
  ```
  Then choose **Ollama** in the app with the address `http://127.0.0.1:11434`. Nothing leaves the handheld.
- **Ollama on your PC** works too: put the PC's LAN or Tailscale address in the same field.
- **Keys:** paste a Groq or Gemini key (both have free tiers) in the app.

## Controls

| Do this | And she… |
|---|---|
| Tap her | Says what's up, or offers to talk |
| Long-press her | Opens her menu: talk, pick me a game, today's stats, teleport, satellite, put away, switch screen, AI setup, turn off |
| Drag and release | Throws her (she has feelings about that) |
| Tap her chip/mask when she's put away | Brings her back |

## Building

```bash
python3 tools/sync_upstream.py      # pulls her renderer + art from upstream into app/src/main/assets/capri
./gradlew assembleRelease           # (gradle runs the sync itself if it hasn't been done)
```

## Credits and licence

Capri and her code are by **mochiiwuff**, released under the
[PolyForm Noncommercial 1.0.0](https://polyformproject.org/licenses/noncommercial/1.0.0) licence. This port follows
the same terms: free, personal, never sold. The character artwork belongs to its rights holders
(*Cyberpunk: Edgerunners*). It isn't stored in this repo; the build fetches it from upstream. The Thor additions
(the `web/` scripts and the Android app) are fan work for personal fun.
