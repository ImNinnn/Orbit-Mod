# Orbit

**Website: [orbitcosmetics.dev](https://orbitcosmetics.dev)** · [Creator guide](https://orbitcosmetics.dev/guide.html)

Orbit (the Orbit Mod) for Minecraft Java Edition **26.x**: wear your own Blockbench cosmetics and play emotes that
other Orbit players see on any server, chat with your friends, host your worlds for them, manage your screenshots
and zoom.

* Put cosmetics in `Orbit/Cosmetics/<name>/` and emotes in `Orbit/Emotes/<name>/`, then pick them in the Orbit menu.
  No resource packs, no restarts, no converting.
* Attach geometry to the head, body, arms or legs with named groups. Cosmetics follow the player's real
  animated body, including custom player models from Entity Model Features packs such as Fresh Animations.
* Animated cosmetics: assign each animation to idle, walk, sprint, jump, fall, sneak, crouch, swim, fly,
  random or "always", with loop mode, speed, priority and interruption, plus movement physics.
* Emotes: Blockbench animations of the player's body (or the whole player), played from an emote wheel (hold **R**).
* Particles: any group can emit particles that live in the world, set up in game; scale it to make fewer.
* Orbit Cloud: every Orbit player on the same server sees your cosmetics and emotes. Servers need nothing
  installed, and players without the mod join and play normally.
* Friends: friend requests (or import your Minecraft friends), blocking, and end-to-end encrypted messages with
  screenshots, cosmetics and world invites.
* Hosting: put a singleplayer world online for your friends in two clicks (with the optional e4mc mod).
* Screenshots: F2 shows a preview to edit, favorite, copy or send the picture; a manager to browse them, and an
  editor to draw on, crop, rotate and rename them.
* Zoom: hold **C**, scroll to zoom more or less.

| Minecraft | File |
|---|---|
| 26.1, 26.1.1, 26.1.2 | `Mod/versions/26.1/build/libs/orbit-mod-mc26.1-<version>.jar` |
| 26.2 | `Mod/versions/26.2/build/libs/orbit-mod-mc26.2-<version>.jar` |
| 26.3 | `Mod/versions/26.3/build/libs/orbit-mod-mc26.3-<version>.jar` |

Install the jar for your Minecraft version in `mods/`, with Fabric Loader 0.20.1+ and Fabric API. Java 25.
Orbit is client-only: servers do not need it.

## For players

### The Orbit menu

Open it with **B**, or with **Wardrobe** in Orbit's buttons on the title and pause screens (see below).

* **User Catalog**: only your own cosmetics and emotes (not the claimed featured ones).
* **Featured Catalog**: cosmetics, emotes and bundles picked by the Orbit moderators. **Claim** adds them to
  your catalog for free: claimed cosmetics are equipped, claimed emotes go on your emote wheel. Claimed items are
  kept exactly as Orbit Cloud has them (in `Orbit/.featured`, marked with a star) and are never uploaded.
  The pencil button makes an editable copy in `Orbit/Cosmetics` or `Orbit/Emotes`, one of your own items that you
  can change; the claimed one stays. Every featured item has its tile colours: chosen by the moderators, or else its
  rarity, which grows with its size (grey, green, blue, purple, gold, then red for the biggest; emotes get a random
  one). Feature+ items are shown four times as large, spread out among the others; bundles (several items
  claimed together) take two whole rows in their own section: the player wearing the bundle (playing its emotes in
  turn) on the left, the list of its items on the right. The filter shows everything, only cosmetics, only
  emotes or only bundles.
* **Wardrobe**: everything you can wear.
  * **All Items**: your own cosmetics and emotes and the ones you claimed. Click one to see it on you on the right;
    double-click (or **Equip**) to wear it. Unequipping takes it off the preview too; clicking the background
    deselects. **Animations** opens its animation and physics settings.
  * **Head, Arms, Legs, Body**: your cosmetics (claimed ones included) by the body part they sit on.
  * **Skins**: the skins in `Orbit/Skins` (`name_classic.png`, or `name_slim.png` for slim arms) and the capes
    your Minecraft account owns. **Wear** shows it on you at once and puts it on your Minecraft account too, right
    away. After a change, the next skin change waits until a minute has passed (capes have their own minute);
    changes made meanwhile wait, and only the last one is sent. Other Orbit
    players see a skin as soon as Mojang has accepted it, and at once for a skin you wore before; capes always at
    once. Mojang checks every skin, so a skin it refuses is never shown to anyone. **+** saves your current
    Minecraft skin into `Orbit/Skins`. Skins and capes are not featured items: a feature ban does not limit them.
  * **Outfits**: saved looks (cosmetics with their textures, skin and cape). **+** keeps what you wear as a new
    outfit; select one to rename it, **Wear** it, or replace it with what you wear now.
* **Alternate textures**: hover a cosmetic that has several styles (also in the Featured Catalog) and pick one with
  the colour buttons on the left of its tile. Other players see the style you chose; a style picked in the catalog
  is kept when you claim it.
* **Emotes**: your emotes and the emote wheel editor.
* **The player on the right** wears what you have on plus the selection; drag it to turn it. Hovering one of its
  cosmetics turns it white, and clicking it selects that cosmetic (shown red), as if you had clicked its tile.
* While a player plays an emote, they are drawn with the vanilla player model (and vanilla cape), so emotes also
  work with model packs such as Fresh Animations (cosmetics follow that body during the emote). The players in
  Orbit's menus and on the title and pause screens always use the vanilla model.
* The skin's outer layer (hat, jacket, sleeves, trouser legs) would poke through cosmetics that sit close to the
  body, so the pixels of it that a cosmetic covers (and the ones right next to them, for small gaps such as the
  back of a cap) are painted onto the skin underneath while it is worn, everywhere (first person too). Each
  cosmetic is measured once on the standing pose: a moving part is measured where it rests.
* Your name and the settings button are at the bottom of the sidebar; the folder button opens the Orbit folder.

The coloured square on a cosmetic shows where it stands; hover it for details. An emote's icon uses the same
colours: grey when it is on no wheel, green when only you see it played, blue when Orbit players on the server see
it, yellow when it is not shared.

| Colour | Meaning |
|---|---|
| Grey | Not equipped |
| Green | Equipped, only you see it here (singleplayer, or not uploaded yet) |
| Blue | Equipped and shared: every Orbit player on this server sees it |
| Yellow | Equipped, but not shared: you are banned, it was blocked, or it is too large |
| Red | The file has errors |

A gold star marks items claimed from the Featured Catalog (grey once Orbit Cloud no longer features it). They are kept apart from your own cosmetics and emotes,
even when a file of yours is the same. A claimed copy only counts as featured while Orbit Cloud features that exact
file: once it is unfeatured, or the copy was changed, it is treated (and shared) like your own items. When the
catalog has another version of it under the same name (or your copy was changed), a blue arrow replaces the star
and **Update** puts the catalog's version back in place, still equipped.

### The Orbit folder

```
<minecraft instance>/Orbit/
├── Cosmetics/
│   ├── cool_wings/
│   │   ├── cool_wings.bbmodel
│   │   └── cool_wings.json     the cosmetic's settings (optional)
│   └── top_hat/
│       └── top_hat.bbmodel
├── Emotes/
│   └── big_wave/
│       ├── big_wave.bbmodel
│       └── big_wave.json       its settings: Standard or Mobile, particles
├── Body Parts/
│   └── pirate_arm/
│       ├── pirate_arm.bbmodel
│       └── pirate_arm.json     which parts it replaces, particles
├── .featured/                   items claimed from the Featured Catalog (Cosmetics/, Emotes/, Body Parts/), kept unchanged
└── orbit-state.json             what you equipped, your outfits and emote wheels
```

Each cosmetic and each emote has its own folder, and the folder's name is its name (`cool_wings` shows as
"cool wings"). A `.bbmodel` saved straight into `Cosmetics/` or `Emotes/` is moved into a folder of its own.
Files dropped onto the Orbit menu are copied in (onto the Emotes section: into `Emotes/`). Orbit watches these folders: files you add, change or delete
show up right away, without a restart.

### Making a cosmetic

Click **+** in the menu (in any section but Emotes). Orbit makes a new cosmetic from a template straight in your
folder, `Orbit/Cosmetics/new_cosmetic/new_cosmetic.bbmodel` (with a short `HOW_TO.txt`), and opens the folder: it is
already in your User Catalog, so just edit it (and rename the folder and file to name it). The template contains:

* `REFERENCE_PLAYER`: a locked player body for orientation. It is never rendered.
* `HEAD`, `BODY`, `LEFT_ARM`, `RIGHT_ARM`, `LEFT_LEG`, `RIGHT_LEG`: put your geometry inside these.
* `example_top_hat` under `HEAD`, with Export turned off so the template itself renders nothing.
* An example `IDLE` animation and a blank `cosmetic_texture.png`.

Geometry outside the anchor groups follows only the player's position and body rotation.

A texture named `PLAYER` or `SKIN` (any case, with or without `.png`), 64x64 and left completely empty, is drawn
with the skin of whoever wears it: map faces onto it like a skin to show the player's own skin on your model. It
must stay empty (a painted one is a normal texture), so files never carry a skin of their own this way.
The **+** button in the Orbit menu writes a Blockbench template to start from.

### Body parts

Body parts replace parts of the player instead of adding to them: a pirate arm instead of your arm, a robot head,
or a whole new body. They live in `Orbit/Body Parts/` and in the menu's **Body Parts** section, and work in the
world, in menus, in first person (a replaced arm is your hand), during emotes, and with model mods such as Entity
Model Features and Fresh Animations.

Click **+** in the Body Parts section for a template (`Orbit/Body Parts/new_body_part/`): the six parts of the
player in their groups `HEAD`, `BODY`, `RIGHT_ARM`, `LEFT_ARM`, `RIGHT_LEG` and `LEFT_LEG`, mapped onto an empty
`PLAYER` texture, so it looks exactly like you until you change it. Every group with something in it replaces that
part; the group origins are the player's joints, so a part moves like the real one.

* Each part may be up to 50% bigger than the player's own part (on every axis, around its centre), and cannot be
  empty. A part that breaks these rules is not used: the player's own part shows instead. Every Orbit player
  checks this themselves, so a body part can never make a player invisible or huge.
* **Settings** → **Parts** turns each part of the file on or off (the last one stays on); **Particles** adds
  particles. Body parts have no animations or physics: they move with the player.
* When two equipped body parts replace the same part, the one equipped last shows it.

### Animations

Select a cosmetic and click **Settings**: it has three tabs, **Animations**, **Velocity** and **Particles**. Animations named `IDLE`, `WALK`, `SPRINT`, `BACKWARDS`, `JUMP`,
`FALL`, `SNEAK`, `CROUCH`, `SWIM`, `FLY`, `DAMAGE`, `ATTACK`, `MINE` or `RANDOM` (also `animation.x.walk`, `run`,
`walk_backwards`, `glide`, `hurt`, `swing`, `dig`, `fidget`, and similar) are assigned automatically. Others start
as *Never* and can be assigned by hand. Walking backwards also counts as walking, and with nothing set to
*Sprint*, the *Walk* animation keeps playing while sprinting. *Damage* plays once each time the player is hurt,
*Attack* once each time they hit a creature or player (a swing that hurts something; swinging at nothing, placing
blocks or opening doors does not count), and *Mine* while they break a block; all three are seen the same way by
every Orbit player.

**Smooth animations** (in the settings, on by default) fades one animation into the next when they follow each
other (walking, then standing, then jumping...), over a quarter of a second, instead of jumping straight to the
new pose, so parts don't snap or clip through each other.

| Setting | Meaning |
|---|---|
| Plays | The player state that triggers it, or *Always*, *Random* or *Never* |
| Mode | *Loop*; *Once* (then back to the default pose); *Hold* (stay on the last frame) |
| Min / Max wait | For *Random*: the pause between plays is a random time in this range (0 to 120 s) |
| Speed | 0.1x to 4x |
| Priority | When animations move the same part, the higher priority wins; equal priorities add up |
| Interrupt | While playing, pauses this cosmetic's lower-priority animations |

*Random* animations are scheduled from the game time every client shares and a seed made from the player,
the cosmetic and the animation, so every player sees them start at the same moment.

**Velocity** makes parts react to how the wearer moves (a cape that lifts while running, a tail that swings
when turning). Pick a group, then set any of position X/Y/Z and rotation X/Y/Z: what it moves with
(*Forward*, *Sideways*, *Vertical*, *Any speed*, *Turning*), the strength, the minimum and maximum and the
resistance (0 reacts instantly; close to 1 settles smoothly). A minimum of 0 keeps a part from going the other way,
like a chest lid that opens but never sinks into the chest. Up to 8 parts per cosmetic. A player on the left shows
it live: set its speed forward, sideways and up under it, and the background moves to show how fast it goes.

### Particles

Any group of a cosmetic or emote can emit particles: in the cosmetic's **Particles** tab (an emote's **Settings**),
click **Add emitter** and choose a group, up to 8. Particles appear where the group is, so animating the group moves
where they appear, and the group's size sets how many: at its normal size it emits normally, scaled down it emits
less and less often, and at size 0 it stops. An empty group works well as an emitter. Once they appear, particles
live in the world on their own, so a moving player leaves a trail. Every Orbit player sees them; each game
simulates them itself. At most 20 particles per player at a time.

A particle is a flat square, always facing you, showing one texture of the model (every texture in the file can be
used, not only the ones faces use). A new emitter uses the first texture with "particle" in its name, else the
first one. With alternate textures, a particle drawn with `particle_t1` uses `particle_t2` in the second style, and
so on.

**Edit** shows a player wearing the cosmetic (or playing the emote) on the left, so you see the particles change as
you edit them; drag it to turn it. It has two tabs.

**Look:**

| Setting | Meaning |
|---|---|
| Texture, Particles, Glow | Which texture, whether the emitter is on, and drawn at full brightness |
| Opacity, Lifetime, Fade out | How see-through, how long it lives, and how long it takes to fade at the end |
| Start size / End size | A particle grows or shrinks from one to the other over its life (pixels, 16 to a block) |
| Rotation | Each particle starts turned by a random angle, up to this far either way (degrees) |
| Spin min / max | How fast it turns on itself: each particle picks a speed in between (degrees per second; negative turns the other way) |
| Wait min / max | A new particle comes after a random wait in between (ticks; 20 is a second) |

**Motion** (X / Y / Z each, relative to the player when the particle appears; Blockbench axes: X to the side, Y up,
Z to the back):

| Setting | Meaning |
|---|---|
| Offset | Particles appear up to this far from the group either way, at random (pixels) |
| Speed | How it moves, in blocks per second |
| Turn | How fast its direction turns, like steering a car: moving forward while turning around Y goes in a circle |
| Speed change, Turn change | How much the speeds and turns change every second (negative slows down, then reverses) |

### The settings file

Each cosmetic's settings are stored in a JSON file with the same name next to the model; Orbit creates it the first
time it reads a cosmetic that has none (emote files get one too). To share a cosmetic exactly as you configured it,
share its folder. You can add details by editing the file (`particles` is written by the Particles tab):

```json
{
  "orbitCosmetic": 1,
  "model": "top_hat.bbmodel",
  "name": "Top hat",
  "author": "Your name",
  "description": "A tall hat with a swinging brim.",
  "gradient": ["#3B82F6"],
  "animations": { "IDLE": { "trigger": "IDLE", "loop": "LOOP", "speed": 1.0, "priority": 10, "interrupt": false } },
  "physics": { "<group uuid>": { "part": "brim", "rotation_x": { "source": "FORWARD", "strength": 8, "min": 0, "max": 40, "resistance": 0.6 } } },
  "particles": { "<group uuid>": { "part": "particle1", "enabled": true, "texture": "particle", "glow": true, "speedY": 0.5, "lifetime": 2 } }
}
```

A `"name"` here wins over the folder name. `"gradient"` colours the cosmetic's tile in the Orbit menu: one colour
the tile fades into, or two for the top and the bottom (`"#RRGGBB"` or `"#AARRGGBB"`). An emote's `.json` is the
same kind of file, with `"name"`, `"gradient"`, `"mode"` (`"standard"` or `"mobile"`) and `"particles"`. A
same-named `.json` without the `"orbitCosmetic"` key (such as a geometry file exported by Blockbench) is never
overwritten.

### Animated textures

Textures can be animated like Minecraft's `.mcmeta` textures: frames stacked one under the other in the image (a
16x64 image holds four 16x16 frames). Blockbench's animated textures work as they are (their frame time too). In a
cosmetic's **Animations** tab, under its animations, each texture has a row: **Frame width**, **Frame height** (the
whole image: not animated) and **Frame time** in ticks (20 a second). Saved in the settings file as `"textures"`,
by texture name, and sent to other players with what you wear.

### Alternate textures

A cosmetic can have up to five styles (a backpack in several colours, for example). Name its texture
`backpack_t1` and add `backpack_t2` to `backpack_t5`, the same size; faces use the `_t1` texture. Several textures
can change together: `upperwings_t1/_t2` and `lowerwings_t1/_t2` give two styles. Textures without `_t1` never
change. The colour buttons show each style's average colour; the chosen style is saved in `orbit-state.json`
and sent to other players with what you wear.

### Emotes

An emote is one file in a folder of its own (`Orbit/Emotes/big_wave/big_wave.bbmodel`). It has this group tree,
with the player body inside (Export off, so it is only a guide):

```
ROOT             moves the whole player
├── LEFT_LEG
├── RIGHT_LEG
└── UPPERHALF    everything but the legs, bending at the bottom of the body
    ├── BODY
    ├── LEFT_ARM
    ├── RIGHT_ARM
    └── HEAD
```

Animating a group moves that part of the player; parts the emote does not animate keep their normal pose (your
head still follows where you look). Other geometry in the file, such as a prop in a hand, is drawn while the
emote plays. Its animations are known by name:

| Animation | Plays |
|---|---|
| `PLAY` | Once, at the start |
| `LOOP` (or `IDLE`) | Then over and over, until the emote stops |
| `WALKING` | Instead of `LOOP` while you walk (and while you sprint, without `SPRINTING`) |
| `SPRINTING` | Instead of `LOOP` while you sprint |
| `BACKWARDS` | Instead of `LOOP` while you walk backwards (else `WALKING`) |

An emote needs `PLAY`, `LOOP` or both. One with `WALKING`, `SPRINTING` or `BACKWARDS` is made for moving, so it
is always *Mobile*. Click **+** in the Emotes section for a new emote from a template, straight in
`Orbit/Emotes/new_emote/`, with an example `PLAY` (a wave).

* **Playing**: hold **R** to open the emote wheel, point at an emote and let go of the key. The last emote
  you pointed at stays chosen, so you can let go anywhere. Clicking an emote plays it too.
* **Wheels**: in the Orbit menu's Emotes section, select an emote and click a slot of the wheel (right-click
  empties a slot). The arrows (or the mouse wheel) switch between wheels; **New** adds one (up to 10).
* **Camera**: an emote switches to the third-person view, and back when it ends.
* **Stopping**: crouch. *Standard* emotes (the default) also stop when you move or jump; *Mobile* emotes,
  made for walking and running animations, keep playing.
* **Settings**: select an emote and click **Settings** for its particles and its mode (*Standard* or *Mobile*).

### Orbit's buttons

The title and pause screens show your player on one side of Minecraft's buttons, with **Wardrobe** (the Orbit
menu) and **Players** (pause screen) under its feet, and Orbit's buttons on the other side: **Host**, **Social**,
**Screenshots**, **Settings** and **Statistics**. Social shows how many unread messages and friend requests you have.
News from the Orbit team ("Summer Cosmetics!") shows in a box under **Wardrobe** while it lasts (in the top left
corner on small windows); a new one shows at once. In the Featured
Catalog, the item you pick shows on the player without a selection tint.
With Essential installed, your player moves to the left edge of the screen and Orbit's buttons to the right edge.
When their names don't fit, the buttons turn square (their names show when you point at them); on very small
windows they line up along the top. In the settings, **Grid background** and **Minecraft buttons** switch Orbit's
screens to Minecraft's own menu background and button texture; small windows (Host, Statistics, dialogs) always
sit on Minecraft's blurred background.

The first time you start the game with Orbit, it asks you to accept the
[Terms of Service](https://orbitcosmetics.dev/terms.html) and [Privacy Policy](https://orbitcosmetics.dev/privacy.html).
Until you do, Orbit does not connect to Orbit Cloud (it asks again on the next start, or from Social).

**Statistics** shows your page: your pronouns and description, the outfit you chose (on your player, at the
right; set them with **Edit**, with the page's colour), friends, time played (in worlds and on servers, counted by Orbit), your
screenshots, cosmetics, emotes and body parts, and since when you use Orbit Cloud, on a background in the colour
you pick. Your friends can see it (click a friend's name in the Friends tab, the button in a conversation, or
Statistics in a server's Players menu) unless you turn off **Share statistics** in the settings, which hides it from them. Your page, time
played and screenshots taken are kept by Orbit Cloud for your account, so they are the same on every computer.

The featured items you claimed and your outfits follow your account too: on another computer, Orbit brings them back
when it starts (claimed items from the catalog, outfit cosmetics from Orbit Cloud's copies). A cosmetic or skin
that is on neither that computer nor Orbit Cloud shows as missing on its outfit (a red ✕; point at it to see what). The Players menu also has **Add friend** and **Block**. A friend's page has **Report**, which
sends their profile as it is to Orbit's moderators.

### Keys

All of Orbit's keys are in Controls, under Orbit: **B** the wardrobe, **R** the emote wheel (hold), **C** zoom
(hold), **H** Host, **J** Social, **I** Screenshots, **Z** chat peek (hold to see the whole chat without typing).
Not bound until you bind them: the quick menu (hold, point at Host, Social, Wardrobe, Settings or Players, and
let go), showing or hiding every cosmetic, and one key per emote wheel slot (plays that slot's emote).

### Multiplayer tabs

Minecraft's Multiplayer screen gets three tabs: **Favorites** (Minecraft's own server list), **Friends** (the
worlds your friends host right now, shared through Orbit Cloud while they host) and **Recommended** (popular
servers Orbit Cloud lists, most players first). Join with **Join Server** or a double click. On Recommended,
**Add to Favorites** puts the selected server into Minecraft's list (the Favorites tab); a star marks the ones
already there.

### Discord

While Discord runs on your computer, your Discord profile shows "Orbit Mod - 26.3" and what you're doing: playing
on a server (its address), in singleplayer, hosting a world, in the main menu, chatting to friends, changing
cosmetics or settings, or looking at screenshots. Turn it off with **Discord status** in the settings.

### Zoom

Hold **C** (change it in Controls) to zoom in 3 times. While zooming, the mouse wheel zooms further in or out
(from 1.25× to 60×), the camera turns smoothly like Minecraft's cinematic camera, and your hand is hidden.
Releasing the key goes back to your normal view and camera setting.

### Screenshots

Taking a screenshot (F2) shows a preview in the top right corner instead of the chat message. Hover it for
**Edit**, **Favorite** (moves the file to `screenshots/favorites`), **Copy** (to the clipboard, as an image) and
**Send to friends**; click the picture to open it. Turn the previews off with **Screenshot previews** in the
settings.

The **Screenshots** button opens the manager: all your screenshots (or only the favorites) by day and month, in
small or large previews, with a search. Clicking one opens it: rename it (the name box), step through them with
the arrows, favorite, copy, send, show it in its folder or delete it. **Edit** draws on it (brush sizes, any colour
and opacity, undo and redo with Ctrl+Z and Ctrl+Y or Ctrl+Shift+Z), crops it (drag the handles), rotates it, and saves the result as a copy
("Copy of ..."), so the original stays as it was.

Copying uses the system clipboard tools (PowerShell on Windows, `osascript` on macOS, `wl-copy` or `xclip` on
Linux), because Minecraft runs without Java's desktop clipboard.

### Friends and messages

**Social** has two tabs. **Friends** lists your friends (online or when they were last online), friend requests
you received or sent, and the players you blocked. **Add friend** sends a request by Minecraft name; **Import
Minecraft friends** sends a request to everyone on your Minecraft friend list who uses Orbit (Minecraft 26.2 and
26.3). When two players ask each other, they are friends right away.

**Messages** are your conversations: text, screenshots (the picture button), cosmetics, emotes and body parts
(the send button of a selected item in the Orbit menu, with an **Add** button for your friend, which puts a copy
in their wardrobe with its settings) and world invites. Server addresses in a message (`play.example.net`,
`host:25565`, e4mc links) become a card with the server's icon, description, player count and a **Join**
button. On a multiplayer server, **Host** has **Invite friends to this server**.

The lock in a conversation's header turns end-to-end encryption on or off for your new messages in it (off at
first). Encrypted messages have a dashed outline: every computer you play on has its own key (in
`Orbit/.social/<your uuid>/device.json`, never uploaded), and each message is encrypted for the computers of you
and your friend only (X25519, HKDF-SHA256, AES-256-GCM), so Orbit Cloud stores only what it cannot read, and a
computer added later cannot read it. Messages without encryption are sent as they are (over HTTPS) and any of
your computers can read them. Orbit Cloud deletes a message 30 days after your friend saw it; your conversations
are kept on your computer.

New messages, friend requests and invites pop up in the bottom right corner: a message with your friend's head and
name over the message, a world invite with the world's icon, name and how many players are on it, a friend request
with your friend's head. Click one to open the conversation, or **Play** on an invite to join. Orbit tells you when a friend comes online (turn it off with **Friends coming online**). Messages arrive within a
moment: from launch on, Orbit keeps a request open on Orbit Cloud that answers as soon
as something new arrives for you (it also keeps you shown as online to your friends).

Everything in Social works from the keyboard: Tab and the arrow keys move between buttons, conversations and
cards, Enter presses them, and typing writes your message, which Enter sends (Shift+Enter starts a new line; the box grows with it). Alt+Up and Alt+Down open the
conversation above or below, Page Up and Page Down scroll it, and Ctrl+Tab switches between Messages and Friends.
In the Orbit menu, Ctrl+F searches, the arrow keys move between tiles (the one you are on is selected), Enter
equips or takes off a cosmetic (or plays, wears or claims the item), and on the emote wheel Enter puts the selected
emote in a slot and Delete empties it.

Your own messages can be edited and deleted: point at one and use the pencil or the bin. An edited message shows
*edited* next to its bubble, and Escape stops editing. A deleted message turns into a grey *this message has been
deleted* bubble, for your friend as well. Orbit Cloud keeps the edit in place of the old message and forgets a
deleted one's content, so every computer shows it so, also one you start playing on later.

**Floating chat windows.** In a world, the pop-out button in a conversation's header turns it into a small window
over the game. While you play, it shows on the screen and hides after 30 seconds without new messages. With the
chat or the pause menu open, you can move it by its title bar, resize it from its bottom right corner, scroll it
and click its message box to type (Enter sends, Escape goes back to the game). Encrypted messages have the dashed
outline there too; screenshots, cosmetics and invites show as a grey *sent a screenshot* (and so on): open the
conversation in Social to see them. **–** hides it until the next
message or until you click it: over the pause menu, hidden windows show see-through, and a click on one's title bar brings it back.
**×** closes it. A conversation with a floating window gets no popups.

**Messages from Orbit.** The Orbit team can send a message to every player (text, and a picture). It shows as a
conversation with **Orbit**, with the Orbit icon, and pops up when it is new. You cannot answer it, and it
disappears if the team takes it back.

An Orbit Cloud ban keeps your friends and messages; it only limits sharing: no cosmetics with a feature ban, no
cosmetics or screenshots with a complete ban. The game learns of a ban, and of its end, within seconds.

**Group chats.** The **+** at the top of Social makes a group with friends you pick (up to 10 members). Its name is
its members' names until it gets one, and its picture their heads until it gets a screenshot. In the chat, others'
messages show their head and name; the header says how many members are online. The members button opens the member
list on the right. The player who made the group leads it: they add friends, remove members, pass the lead on, and
set the name, colour and picture (the pencil). Group messages are not end-to-end encrypted.

Each conversation is tinted with a colour: a friend's statistics page colour, or the group's own. Colours can be
picked from the swatches or typed as a hex code (`#3A9D4A`). The settings' top right corner has buttons for the
website, GitHub and Discord.

Blocking a player removes them from your friends; they can no longer send you requests or messages, and their
cosmetics are hidden for you (unblock them in the Friends tab).

### Hosting a world

**Host** opens a world for your friends. From the title screen it lists your worlds; pick one and it opens. In a
singleplayer world, choose the game mode for players who join and whether they may use commands, then **Start
hosting**. Orbit uses [e4mc](https://modrinth.com/mod/e4mc), an optional free mod that puts LAN worlds online
without opening ports: install it next to Orbit. Once the world is online, its address shows (copy it) and
**Invite friends** sends it to the friends you pick, as a card with a Join button. The host window also lists who
is in the world and changes the difficulty. Minecraft 26.1 cannot close a world to others again: leave the world to
stop hosting there.

### Orbit Cloud

Orbit connects to Orbit Cloud when the game starts and stays connected while it runs (for friends, messages and
statistics), signing in with your Minecraft account (through Mojang's session check; your access token never
leaves the game). On every multiplayer server (and worlds opened to LAN), it also uploads the cosmetics and emote
files you use that Orbit Cloud does not have yet, and publishes what you wear and which emote you play.
It asks what the other players in the tab list wear every 2 seconds (every 10 seconds while none of them uses
Orbit, and right away when the tab list changes). Downloaded files are checked against their SHA-256, cached in
`Orbit/.cache`, and validated like your own.

Leaving a server keeps the Orbit Cloud session for 10 seconds, so moving between the servers of a network
(BungeeCord, Velocity) does not sign in again. You are told when a cosmetic you equip becomes visible to others,
not about what you already wear when you join. If Orbit Cloud cannot be reached, you are told once; not again until
a connection works or the game restarts.

Orbit Cloud moderators can:

* **Block** an item: it is deleted and the same file can never be uploaded or worn again.
* **Feature ban** a player: they cannot upload, and only Featured Catalog items they wear are shared.
  Players reported by 6 different players in a week get a feature ban for a week automatically.
* **Completely ban** a player: nothing they wear is shared.

Uploaded cosmetics and emotes are only a cache that lets other players download them: items nobody wore or
downloaded for 24 hours are deleted from Orbit Cloud and uploaded again the next time someone wears them. The
Featured Catalog is kept separately and is not affected.

### Players menu and reports

The pause screen's player button lists the Orbit players on the server. **Hide** stops showing a player's
cosmetics (on your screen only, remembered); **Report** sends the player and what they wear to the Orbit Cloud
moderators.

### Updates

When Minecraft starts, Orbit asks Orbit Cloud whether a newer Orbit exists for your Minecraft version.
If one does, a small window opens while Minecraft waits: **Update** downloads it, puts it into your mods folder,
removes the old jar and closes Minecraft (start it again to play with the new version); **Not now**, or no
answer within 10 seconds, lets Minecraft load as usual. Doing anything in the window (a click, dragging it, a key)
stops the countdown: Minecraft then waits for your answer. Enter updates, Escape is Not now. The window looks like
Orbit's menus, in Minecraft's font. Updates are signed: Orbit only installs a jar signed
with the Orbit release key, whatever the server sends. When Orbit Cloud needs a newer version than yours (its
data changed), it refuses to share cosmetics until you update, and says so in game. Turn the check off with
**Check for updates** in Orbit's settings.

When the update is optional (your version still works with Orbit Cloud), Minecraft loads straight away, and the
update is offered in the game instead: a download arrow next to **Settings** in the Orbit menu, an **Update Orbit**
row in the settings, and a prompt on the title screen (once per start; tick **Don't show this again** to stop it
for that version). **Update** runs the same signed download in the background; the new version is used the next
time Minecraft starts. While the game runs, Orbit Cloud tells the game as soon as a new version is out (through the
connection that brings messages), and you get a notification. Only a required update (Orbit Cloud needs a newer version) still asks before Minecraft
loads.

Mod sites (Modrinth, CurseForge) do not allow mods that update themselves, so every build also makes a
`-store` jar without the update check: it has no update code at all (the build checks this), and those sites
update it instead. Without a full Java (some Linux "headless" Java packages lack the
window libraries) the window cannot open and Minecraft just loads.

### Settings

The gear in the Orbit menu (also the **Configure** button of Orbit in Mod Menu's mod list): what is drawn (your cosmetics, other players', first-person arms, animations,
other players' emotes), the player on the title and pause screens, nameplate icons, Orbit Cloud, render and
animation distances, the download cache, and, at the very bottom of Advanced, something just for fun.

## Security

All cosmetic and emote files, local or downloaded, are treated as untrusted:

* Models are parsed as data only. Nothing is executed. Molang is a small sandboxed expression evaluator
  without loops or access to anything but the player's animation state.
* Sizes and counts are checked before work proportional to them is done (JSON nesting, elements, groups,
  faces, textures, texture dimensions read from the PNG header before decoding, animations, keyframes,
  expression length).
* Textures referenced by a model are only read from the model's own folder, so a model cannot pull other
  files from your disk into an upload.
* What other players publish is decoded with strict limits; anything malformed is ignored. Orbit never
  downloads from URLs inside models; only data embedded in them is used.

## Building

```
./gradlew build
```

builds the three client jars (and their `-store` jars without self-updating, for mod sites). Gradle provisions
Java 25 automatically if needed.

Orbit Cloud, the server every Orbit player shares, is private: its code is not part of this repository, and the
mod only talks to the official Orbit Cloud at `api.orbitcosmetics.dev` (development runs can use a local test
server with `-Dorbit.backendUrl=...`, which released games ignore).

Development runs (run directories are under `run/`):

* `./gradlew :client-26.3:runClient` (or `client-26.1`, `client-26.2`).

Versions: `mod_version` in `gradle.properties`.
`./gradlew publishRelease` builds and signs the jars and hands them to Orbit Cloud, which offers them to
players (the release key never leaves the maintainer's computer). `./gradlew storeRelease` puts the jars for mod sites
(Modrinth, CurseForge: no self-updating) into `~/orbit-data/releases/<version>-store/`, which Orbit Cloud never
offers as an update.

Project layout:

```
common/                     Pure Java, shared by every Minecraft version: .bbmodel parser and validation,
                            animation engine and Molang, emotes, the equip encoding
Mod/
  src/                      The client mod: libraries, Orbit menu, emotes, rendering, Orbit Cloud
  versions/26.x/            One Gradle project per Minecraft version: its gradle.properties and the few
                            classes that differ between versions (compat/Compat, compat/PreviewRenderer)
  version.gradle            The build shared by every version
website/                    orbitcosmetics.dev
```

### Adding a Minecraft version

The shared code compiles against every 26.x release, but the compiled classes differ (methods and classes
moved), so each version gets its own jar. To add one: copy `Mod/versions/26.3` to the new
version's folder, set `minecraft_version`, `minecraft_dependency` and `fabric_api_version` in its
`gradle.properties`, add the version to `settings.gradle`, and fix whatever its `compat` classes no longer
compile against.

## How the EMF / Fresh Animations compatibility works

Entity Model Features applies its custom player animations to the vanilla `head`, `body`, arm and leg parts
at the end of `PlayerModel.setupAnim`, and its parts apply custom pivots in `translateAndRotate`. Orbit's
render layer walks the parts of the model that is actually being rendered, from the root to the body part,
and applies each part's own transform, so cosmetics follow custom animations, changed proportions and moved
pivots. Emotes are applied after the model (and EMF) posed the player. *Attach to: Vanilla model* in Settings
ignores replaced models entirely. EMF is optional.

## Known limitations

* Importing works by drag and drop or the folder; Minecraft has no native file picker.
* Cosmetics on slim (Alex) arms use the same anchor pivot, which sits half a pixel lower on slim arms.
* Sound and particle keyframes, animation controllers and quaternion interpolation are not supported.
* Cosmetics are not hidden by armor or held items.
* Other players' emotes reach you through Orbit Cloud, so they start up to about 2 seconds late.
* Emotes cannot move the whole player (only the six body parts). During an emote, model packs such as Fresh
  Animations are paused for that player.

## Website

The website in [`website/`](website/) is plain HTML and CSS (no build step, no API calls). Pushing changes to
`website/` on `main` publishes it to GitHub Pages at https://orbitcosmetics.dev through
[`.github/workflows/pages.yml`](.github/workflows/pages.yml). To preview it locally:
`python3 -m http.server --directory website 8080`, then open http://127.0.0.1:8080.
