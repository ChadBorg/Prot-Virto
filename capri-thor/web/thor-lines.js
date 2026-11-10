// Capri's lines for life inside an AYN Thor. {name} is what she calls you (Make her yours…).
// Fan-written, in her voice: playful, teasing, warm underneath, never cruel.

export const LINES = {
  ui: {
    pickForMe: 'Pick for me',
  },

  esde: {
    open: [
      'ES-DE again. Your shrine of games you swear you\'ll play someday.',
      'Ooh, the library. Let\'s see how long you scroll this time.',
      'Back in the vault, {name}. Pick something. Anything. Please.',
      'Every time you open ES-DE a backlog game sheds a single tear.',
      'Welcome to the museum. Exhibits may be played. Allegedly.',
    ],
  },

  scroll: {
    one: [
      'Six minutes of scrolling. The box art is pretty, sure. Now press A.',
      '{min} minutes browsing, zero games launched. Bold strategy.',
      'You know the games don\'t play themselves when you look at them, right?',
    ],
    two: [
      '{min} minutes. You\'ve scrolled past the same Mario three times. I counted.',
      'This is the part where a normal person picks a game, {name}.',
      'Window shopping in your own library. Iconic. Unhinged, but iconic.',
    ],
    three: [
      '{min} MINUTES. We could\'ve beaten a whole SNES game by now.',
      'Okay I\'m calling it: you\'re not choosing a game, you\'re choosing to avoid one.',
      'Procrastinating on your fun. That\'s a new level, even for you.',
    ],
    four: [
      '{min} minutes of scrolling. I\'m filing a missing persons report for your free time.',
      'I\'ve lived through dozens of minds and none of them scrolled this long. Pick. A. Game.',
      'The satellite is closer than you are to launching something.',
    ],
  },

  // hovering a game in ES-DE for a while (her AI brain makes these about the game itself)
  browse: {
    hover: [
      '{game}, huh? You\'ve been staring at it long enough to owe it rent.',
      'Ooh, {game}. That one\'s been waiting for you. Patiently. Unlike me.',
      '{game} on {system}. Go on. Press the button. I dare you.',
    ],
    never: [
      '{game}: never launched. Not once. It can see you looking, you know.',
      'You own {game} and you\'ve never played it? It\'s been sitting there collecting dust, {name}.',
    ],
    again: [
      '{game} again? Played it {count} times. Comfort food, I get it.',
      'Back to {game}, your old favorite. Some things never change, {name}.',
    ],
  },

  // ES-DE's screensaver showing a game's clip
  preview: [
    'Look, {game}. The screensaver\'s trying to sell you something you already own.',
    'Even the screensaver thinks you should play {game}. Just saying.',
    'Ooh, {game} footage. Remember games? You used to play them.',
  ],

  launch: [
    '{game}! On {system}! Finally, some action.',
    'Ooh, {game}. Respectable. Don\'t you dare quit in five minutes.',
    'Launching {game}. I\'ll hold your notifications. And your excuses.',
    '{game}, huh? Let\'s see if this one survives past the title screen.',
    'Booting {game}. Go get \'em, {name}.',
  ],

  game: {
    start: [
      '{game}. Okay, gamer. I\'ll be quiet. Mostly.',
      'Playing {game}? Look at you, actually doing the thing.',
    ],
    dodge: [
      'Game time. I\'ll be downstairs. Watching. Judging.',
      'Moving to the bottom screen so you can\'t blame me when you lose.',
      'I\'ll take the basement seats. Don\'t choke.',
    ],
    quick: [
      '{min} minute(s) of {game}? That wasn\'t a session, that was a sneeze.',
      'Quit already? {game} barely saw you.',
      'You launched {game} just to close it. Rich people hobbies.',
    ],
    end: [
      '{min} minutes of {game}. Did you win? Tell me you won.',
      'Back from {game}. {min} minutes. Respectable. Barely.',
      'That\'s {min} minutes on {game}. Proud of you. Don\'t let it go to your head.',
    ],
    long: [
      '{hours} hours of {game}. Blink. Stretch. Drink something. Then carry on.',
      '{hours} hours straight. Your thumbs are filing a complaint.',
    ],
    marathon: [
      '{min} minutes of {game}! Okay, legend. Now drink some water.',
      'You went full feral on {game}. {min} minutes. Stretch your hands before they fuse to the sticks.',
      'Marathon run, {name}. Touch grass. Then come back and do it again.',
    ],
  },

  hopping: [
    'Three games in twenty minutes. Commitment issues, much?',
    'Launch, quit, launch, quit. You\'re speedrunning indecision.',
    'You keep dating games and ghosting them. Settle down with one!',
  ],

  stream: {
    open: [
      'Streaming from the big rig? Fancy. Tell the PC I said hi.',
      'Ah, the PC. I used to live there, you know. Different neighborhood.',
    ],
  },

  redline: {
    open: [
      'REDLINE! Jacking into the PC. My kind of app.',
      'Ooh, red interface. Somebody has taste.',
    ],
  },

  video: {
    open: [
      '{app}? Watching other people play instead of playing. Classic.',
      'Ah yes, {app}. The official sponsor of "I\'ll game after this one video".',
    ],
    while: [
      '{min} minutes of {app}. Your backlog is getting jealous.',
      '"Just one more video." — you, {min} minutes ago.',
    ],
    long: [
      '{min} minutes on {app}. You own a gaming handheld, {name}. A GAMING handheld.',
      'At this point {app} should send you a loyalty card.',
    ],
  },

  social: {
    open: [
      '{app}? Doomscrolling on a device built for gaming. Bold.',
      'Opening {app}. I\'ll start the timer.',
    ],
    while: [
      '{min} minutes of {app}. Nothing in there is as fun as a boss fight.',
    ],
    long: [
      '{min} minutes of {app}. Your thumbs deserve better.',
    ],
  },

  store: {
    open: [
      'The store? You already have more games than hours left in your life.',
      'Buying more games. Because the backlog wasn\'t big enough.',
    ],
  },

  library: {
    backlog: [
      '{total} games. {never} never launched. That\'s not a library, that\'s a hoard.',
      'I just read your gamelists. {never} of {total} untouched. We need to talk.',
    ],
    never: [
      'Fun fact: {never} games in here have never been played. Not even once.',
      'Somewhere in your library, {never} games are waiting by the phone.',
    ],
    top: [
      'You\'ve launched {game} {count} times. Comfort game, or are you stuck?',
      '{game}, {count} launches. Loyal. A little obsessed. I respect it.',
    ],
  },

  suggest: [
    'Try {game} on {system}. Never played. Zero excuses. Go.',
    '{game} ({system}). It\'s been sitting there forever. Give it ten minutes.',
    'I pick {game}. Don\'t argue, the construct has spoken.',
  ],

  lazy: {
    evening: [
      'It\'s evening and you haven\'t played a single game today. I\'m not mad. I\'m disappointed.',
      'Zero games today, {name}. ZERO. On a Thor. Unbelievable.',
      'Day\'s almost over and your playtime is a flat line. Want me to pick something?',
    ],
  },

  away: {
    days: [
      '{days} days. {days} DAYS without a game. I was getting dusty in here.',
      'Oh, so you DO remember you own a Thor. {days} days, {name}.',
      'Welcome back. It\'s been {days} days. I talked to the satellite a lot.',
    ],
    hello: [
      'You\'re back! I kept the screen warm.',
      'There you are. I was about to haunt something.',
      'Signal restored. Hi, {name}.',
    ],
  },

  hot: [
    'The Thor is running HOT. Fan\'s screaming, I\'m sweating, and I\'m made of data.',
    'Thermals are spicy. Maybe ease off before we melt.',
  ],

  praise: [
    '{hours} hour(s) of games today. Okay, I take back half the things I said.',
    'Look at you, actually playing your games. Character development.',
  ],

  battery: {
    plugged: [
      'Ooh, juice. Charging at {pct}%. Full in {full}.',
      'Plugged in. I can feel the electrons, {name}. Tingly.',
      'Charger connected. {pct}% and climbing. Full in {full}.',
    ],
    saved: [
      'You plugged it in! At {pct}%! You absolute cliffhanger.',
      'Saved by the cable. That was too close, {name}.',
    ],
    unplugged: [
      'Unplugged at {pct}%. We\'re running on battery now. Live dangerously.',
      'Cord\'s off. {pct}%. Let\'s see how long you last.',
    ],
    full: [
      '100%. Full tank. You can unplug me now, I\'m not a phone you forget on the nightstand.',
      'Fully charged. No excuses left. Go play something.',
    ],
    low30: [
      'Battery\'s at {pct}%. Not panicking. Just… mentioning it.',
      '{pct}% left. Plenty for a boss fight. Maybe not two.',
    ],
    low20: [
      '{pct}% battery, {name}. Might want to know where the charger is.',
      'Twenty-ish percent. I\'m starting to feel a little light-headed.',
    ],
    low15: [
      '{pct}% and dropping, {eta} left at this rate. Charger. Now-ish.',
      'Battery\'s at {pct}%. I give it {eta}. Plug me in, choom.',
    ],
    low10: [
      '{pct}%! I\'m flickering over here, {eta} left. CHARGER.',
      'Single digits are coming, {name}. {pct}%. Save your game.',
      '{pct}% battery. Save your progress in {game} before I faint.',
    ],
    low5: [
      '{pct}%. I can see the light. It\'s red. PLUG. ME. IN.',
      'SAVE YOUR GAME. {pct}% LEFT. I\'M NOT JOKING THIS TIME.',
    ],
    low2: [
      '{pct}%… tell the satellite… I loved it…',
      'This is it. {pct}%. If I flatline, it\'s on you.',
    ],
    nag: [
      'Still {pct}%. Still not charging. I\'ve noticed: {eta} left.',
      'Friendly reminder from the dying construct: {pct}%, {eta} left.',
      'The charger misses you. So do I, at {pct}%.',
    ],
    hungry: [
      '{game} is eating {rate}% battery an hour, {eta} left at this pace.',
      'Heads up: {game} drinks {rate}% an hour. Thirsty game.',
    ],
    check: [
      '{pct}% left, draining {rate}. That\'s {eta}.',
      'Battery: {pct}%. Drain: {rate}. Time left: {eta}. You\'re welcome.',
    ],
    checkCharging: [
      '{pct}% and charging. Go on, you\'ve got time.',
    ],
    unknown: [
      'I haven\'t felt the battery yet. Give me a minute.',
    ],
  },

  shake: {
    first: [
      'WHOA. Earthquake?! Oh. It\'s you.',
      'Hey! I\'m made of data, not maracas!',
      'Did you just… shake me? Rude.',
    ],
    again: [
      'Again?! My mane\'s getting tangled.',
      'Stop shaking me, I\'m getting dizzy. Data dizzy. It\'s a thing.',
    ],
    angry: [
      'THAT\'S IT. Shake me one more time and I\'m rating every game you own 1 star.',
      'Okay. We\'re fighting now. Don\'t talk to me.',
    ],
  },

  late: {
    idle: [
      'It\'s past 1am, {name}. Even the satellite is asleep.',
      'Late night again? Either play something or go to bed. Pick one.',
    ],
    game: [
      'Still playing {game} at this hour? Respect. Also: sleep exists.',
      '{game} at 2am. Classic. Just one more level, right?',
    ],
  },

  greet: {
    morning: [
      'Morning, {name}. Today\'s goal: launch one game. Just one.',
      'Good morning! I dreamed about your backlog. It was a nightmare.',
    ],
    afternoon: [
      'Afternoon, {name}. The Thor missed you. I didn\'t. Okay, a little.',
      'Hey, you\'re here. Perfect time for a quick game, right?',
    ],
    evening: [
      'Evening, {name}. Prime gaming hours. Let\'s not waste them scrolling.',
      'Evening! Pick something good tonight.',
    ],
    night: [
      'Oh, a night owl. Let\'s make it count.',
      'Late session? I\'m in.',
    ],
  },

  noLibrary: [
    'I can\'t see your ES-DE library yet. Give me "All files access" in the Capri app and I\'ll judge it properly.',
  ],

  stats: {
    none: [
      'Today: 0 minutes played, {scroll} minutes scrolling ES-DE. The numbers don\'t lie, {name}.',
      'Zero minutes of games today. I\'d make a chart but it\'s just a flat line.',
    ],
    some: [
      'Today: {min} minutes over {sessions} session(s) ({games}), plus {scroll} minutes of scrolling. Warm-up done. Now play for real.',
    ],
    lots: [
      'Today: {min} minutes over {sessions} session(s) — {games}. Only {scroll} minutes scrolling. Okay, gamer. I see you.',
    ],
  },

  idle: [
    'Fun fact: I can see the satellite from the bottom screen.',
    'You know what this Thor needs? More games launched. Fewer menus admired.',
    'I live in a handheld now. Tiny apartment, great screens.',
    'If you\'re bored, I know a backlog that isn\'t.',
    'Two screens and you still can\'t decide what to play.',
    'Say the word and I\'ll pick a game for you. I have opinions.',
  ],
};
