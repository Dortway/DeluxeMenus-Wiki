# ExoDailySpinner

A crate-style daily reward spinner for Paper servers. Players open a 9-slot hub with `/ds`, start a
27-slot spinner with a horizontal reel, and watch it slow down until the reward lands under the
pointers. Rewards, odds, menus, colours, symbols, sounds and messages are all configurable. The
defaults work right away, with no resource pack and no other plugins.

```
┌───────────────── ᴅᴀɪʟʏ ꜱᴘɪɴɴᴇʀ ─────────────────┐
│ ▓  ▓  ▓  ▓  ▼  ▓  ▓  ▓  ▓ │  ▼ / ▲  pointers (slots 4 and 22)
│ ◆  ◆  ◆  ◆ [◆] ◆  ◆  ◆  ◆ │  reel (slots 9–17), winner lands on 13
│ «  ▓  ▓  ▓  ▲  ▓  ▓  ▓  i │  back · spin button/pointer · info
└───────────────────────────┘
```

## Compatibility

| | |
|---|---|
| **Server** | **Paper 1.21.4 – 1.21.11** (built against the Paper API `1.21.4-R0.1-SNAPSHOT`, `api-version: 1.21`) |
| **Java** | **Java 21** or newer (the plugin is compiled for Java 21) |
| **Dependencies** | None. SQLite comes from the JDBC driver that Paper already ships. |
| Paper 26.x | Probably works. Every API call the plugin makes is still in the 26.2 API, but I haven't tested it on a 26.x server. Those servers need Java 25, which runs Java 21 plugins fine. |
| Paper 1.21 – 1.21.3 | Not supported. The plugin might load (api-version 1.21), but it was only built and tested against the 1.21.4 API. |
| **Not supported** | Spigot/CraftBukkit (the plugin needs Paper's Adventure API), **Folia** (it isn't region-thread aware), proxies (install it on the backend server instead), and several servers sharing one database (SQLite is local, so each server keeps its own cooldowns). |

## Installation

1. Put `ExoDailySpinner.jar` into your server's `plugins/` folder.
2. Start the server. This creates `plugins/ExoDailySpinner/` with `config.yml`, `messages.yml`,
   `menus.yml`, `rewards.yml` and the SQLite database `data.db`.
3. Run `/ds` in-game. The 10 example rewards are ready to use.
4. Edit `rewards.yml` (or use `/ds admin` / `/ds reward ...`), then run `/ds reload`.

## Commands

The base command is `/dailyspinner`, with aliases `/ds` and `/dailyspiner`.

| Command | Description | Permission |
|---|---|---|
| `/ds` | Open the main menu | `exodailyspinner.use` |
| `/ds help` | Show help (admins see admin help) | – |
| `/ds preview` | Open the paginated rewards preview | `exodailyspinner.preview` |
| `/ds claim` | Claim stored (overflow) item rewards | `exodailyspinner.claim` |
| `/ds admin` | Open the reward management menu | `exodailyspinner.admin.menu` |
| `/ds reward addhand <id> <weight>` | Add a copy of the main-hand item | `exodailyspinner.admin.rewards` |
| `/ds reward additem <id> <material> <amount> <weight>` | Add an item reward (amount 1–2304) | `exodailyspinner.admin.rewards` |
| `/ds reward addcommand <id> <weight> <command...>` | Add a console-command reward | `exodailyspinner.admin.rewards.command` (or console) |
| `/ds reward remove <id>` | Remove a reward | `exodailyspinner.admin.rewards` |
| `/ds reward list` | List IDs, weights and chances | `exodailyspinner.admin.rewards` |
| `/ds reward setweight <id> <weight>` | Change a weight | `exodailyspinner.admin.rewards` |
| `/ds reward setrarity <id> <rarity>` | Change a rarity | `exodailyspinner.admin.rewards` |
| `/ds reset <player>` | Reset a player's daily cooldown | `exodailyspinner.admin.reset` |
| `/ds give <player> <amount>` | Grant bonus spins | `exodailyspinner.admin.give` |
| `/ds reload` | Reload and validate every file | `exodailyspinner.admin.reload` |
| `/ds reconcile [list \| <key> regrant\|dismiss]` | Review interrupted or failed deliveries | `exodailyspinner.admin.reconcile` |

All commands have tab completion, usage messages and input validation. `<player>` must have joined
the server before. The lookup only uses the server's cache, so it never makes a blocking web request.

## Permissions

| Permission | Default | Grants |
|---|---|---|
| `exodailyspinner.use` | everyone | Open the menu and spin |
| `exodailyspinner.claim` | everyone | `/ds claim` and the stored-rewards button |
| `exodailyspinner.preview` | everyone | The rewards preview |
| `exodailyspinner.admin` | op | All `admin.*` nodes below **except** `admin.rewards.command` |
| `exodailyspinner.admin.menu` | op | `/ds admin` |
| `exodailyspinner.admin.rewards` | op | Item reward management (commands and admin menu) |
| `exodailyspinner.admin.rewards.command` | **nobody** | Creating console-command rewards in-game. You must grant it explicitly; the console can always do it. |
| `exodailyspinner.admin.reset` | op | `/ds reset` |
| `exodailyspinner.admin.give` | op | `/ds give` |
| `exodailyspinner.admin.reload` | op | `/ds reload` and the admin menu's reload button |
| `exodailyspinner.admin.reconcile` | op | `/ds reconcile` |

## Configuration

| File | Contents |
|---|---|
| `config.yml` | Cooldown, consume order, protection timings, database, animation, effects, theme colours, symbols, sounds |
| `messages.yml` | Every chat message (MiniMessage) |
| `menus.yml` | Titles, slots, materials, names and lore for all four menus |
| `rewards.yml` | Rarities and rewards |

**Formatting.** All text uses [MiniMessage](https://docs.advntr.dev/minimessage/format.html).
The plugin adds these tags on top of it:

- Theme colours: `<primary>` (cyan), `<secondary>` (purple), `<accent>` (gold), `<muted>`, `<body>`,
  `<success>` and `<danger>`. Set the hex values under `theme.colors`.
- Symbols: `<sym:star>` ✦, `<sym:diamond>` ◆, `<sym:hourglass>` ⌛, `<sym:check>` ✓ and others. Set
  `symbols.use-unicode: false` to show the plain fallback labels instead.
- Small caps: `<sc>Daily Spinner</sc>` renders as `ᴅᴀɪʟʏ ꜱᴘɪɴɴᴇʀ`. Set `theme.small-caps: false` for
  normal text.

**Weights are relative probabilities.** A reward's chance is its weight divided by the total weight
of all rewards. With the default rewards (total weight 143), `diamonds` (weight 10) has a 10/143 ≈ 7.0%
chance. Weights must be finite numbers greater than 0 and at most 1,000,000,000; decimals are allowed.
The preview menu and `/ds reward list` calculate chances from the weights that are currently active.

**Cooldown.** `spins.daily-cooldown` (default `24h`) is a rolling timer that starts at each player's
last daily spin. It accepts values like `24h`, `1d 12h`, `90m` or a plain number of seconds.

**Daily vs bonus spins.** When a player has both a ready daily spin and bonus spins, the daily spin is
used first by default (`spins.consume-order: DAILY_FIRST`), so bonus spins are saved for later. Set
`BONUS_FIRST` to reverse this.

**Command rewards.** Commands run from the console. `{player}` is replaced with the player's name and
`{uuid}` with their UUID. Before substitution the name is checked against a strict character set, so a
crafted name can't inject extra command text. Treat command rewards as trusted administrator
configuration. Players can never supply executable commands.

**Item rewards.** You can define an item reward three ways:

- with `material`, `name`, `lore`, `enchantments` and `custom-model-data`;
- as an exact serialized copy, which `/ds reward addhand` and the admin menu's *Add Held Item* button
  write. This keeps the item's name, lore, enchantments, custom model data, persistent data and all
  other components. The held item is only copied: it isn't consumed or modified.
- `item.amount` can be up to 2304. Large amounts are split into stacks when delivered.

**Reloading.** `/ds reload` reads the files off the main thread, then validates all of them: YAML
syntax, materials, duplicate or invalid IDs, weights, amounts, slots, sounds, particles and colours.
If anything is wrong, it lists the errors and **keeps the previous working configuration**. Spins that
are already running or reserved aren't affected, because each one stores its own snapshot of the
reward.

## How spins stay safe

1. **The reward is chosen and saved before the animation starts.** The server picks the reward
   (`SecureRandom`, weighted) and runs one SQLite transaction (`BEGIN IMMEDIATE`) that:
   - checks the player's entitlement;
   - uses up the daily or bonus spin, using conditional updates;
   - stores a full **snapshot** of the reward: the serialized item, amount, commands, display item and
     name.

   The animation is only cosmetic. It never decides or changes the result, and delivery always reads
   the stored snapshot.
2. **Undelivered spins are reused, not re-rolled.** If an undelivered spin exists (the player closed
   the menu, disconnected, or the server restarted), any new attempt returns that same spin.
3. **Each spin can only be delivered once.** Delivery moves the spin from `RESERVED` to `DELIVERING`
   with a conditional update, and only one caller can ever win that. Repeated callbacks, double
   clicks and close-during-landing races do nothing.
4. **Closing the menu mid-spin** shows the result early and delivers the stored reward.
   **Disconnecting** stops the animation and leaves the spin `RESERVED`; it is delivered when the
   player rejoins. **Death** closes the menu, and item rewards go to stored rewards.
5. **Full inventories:** whatever doesn't fit is saved in the database in the same transaction that
   marks the spin delivered. Items are never dropped on the ground. Players collect them with
   `/ds claim` or the *Stored Rewards* button. Claims lock rows (`PENDING → CLAIMING`) under a unique
   claim ID, so a second claim at the same time finds nothing to take.
6. **One operation per player:** an in-memory guard with unique tokens allows only one spin or claim
   per player at a time. A stale token can't release a newer operation. Clicks and commands are also
   rate-limited.
7. **Menus:** every GUI has its own `InventoryHolder` and a session ID, and is bound to one viewer.
   The plugin never relies on inventory titles. While a plugin menu is open, all of these are
   cancelled, in both the menu and the player's own inventory:
   - clicks of every type: shift-clicks, number keys, double-clicks, creative actions and drops;
   - drags, offhand swaps and item drops.

   Button actions run one tick later, and only if the same menu session is still open. As a second
   line of defence, every GUI item is tagged, and any tagged item found in a player inventory is
   removed.
8. **Without valid rewards, nothing is consumed.** With an invalid configuration or an empty reward
   list, spins are refused before any entitlement is used.

### Crash-consistency boundary (please read)

The SQLite database, the player's inventory (saved by Minecraft in its own player files) and console
commands (which can affect other plugins' storage) **don't share a transaction**. Delivery happens
across them like this:

```
DB commit: RESERVED → DELIVERING
   ── main thread: add items to the inventory / dispatch console commands ──
DB commit: DELIVERING → DELIVERED (+ overflow stored as pending)
```

There is a short window between the two commits. A hard crash or kill there means the database
can't know whether the delivery happened, and even items that were handed out may be lost if Minecraft
hadn't saved the player yet. The plugin handles this conservatively:

- On the next start, any spin still `DELIVERING` (and any claim still `CLAIMING`) is marked
  **`UNCERTAIN`**. It is written to the console and to `plugins/ExoDailySpinner/reconciliation.log`.
- Uncertain deliveries are **never replayed automatically**. That matters most for command rewards:
  running `eco give` twice is worse than a delay.
- An administrator reviews them with `/ds reconcile list`, then either
  `/ds reconcile <key> regrant` or `/ds reconcile <key> dismiss`:
  - for items, regrant queues them for the player's `/ds claim`;
  - for commands, regrant re-runs them, as an explicit admin decision.
- Commands that fail or return false, items that can't be read, and database errors after a hand-out
  are logged the same way.
- A normal shutdown (`/stop`) is not ambiguous. Running animations are stopped, and spins that hadn't
  been handed out yet are returned to `RESERVED`, then delivered on next join.

The plugin keeps this window as small as possible and makes sure every outcome can be audited. It
can't promise exactly-once delivery across a power cut, and no Bukkit plugin can.

## Building from source

Requirements: JDK 21+ and Maven 3.9+.

```bash
cd ExoDailySpinner
mvn clean package
# → target/ExoDailySpinner.jar
```

The build downloads `io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT` from
`https://repo.papermc.io/repository/maven-public/` and the test libraries (JUnit 5, MockBukkit,
sqlite-jdbc) from Maven Central. Nothing is shaded, so the jar is small.

A prebuilt jar is in `release/ExoDailySpinner.jar`. It was built in an environment where
`repo.papermc.io` was blocked. For that build, the identical `paper-api` 1.21.4 jar was compiled from
PaperMC's official `ver/1.21.4` source and installed locally under the same Maven coordinates. When
you build normally, Maven downloads the official artifact instead.

`mvn test` runs:

- **Unit tests** for:
  - weighted selection: distribution, boundaries, invalid weights;
  - cooldown maths and duration parsing;
  - small caps;
  - command placeholders;
  - the reel plan (the winner always lands at the centre);
  - the operation guard: one operation per player, stale tokens, concurrency.
- **SQLite tests** against a real database file:
  - atomic reservation under concurrent requests from 16 connections;
  - bonus spins never going negative;
  - existing spin reused instead of re-rolled;
  - duplicate operation IDs rejected;
  - delivery and claim transitions happening once;
  - crash flagging and reconciliation;
  - migrations.
- **Integration tests** on MockBukkit (an in-memory Bukkit server):
  - menus open with the right sizes and items;
  - every click type and drags are cancelled;
  - spam clicks;
  - full spin and cooldown;
  - close mid-spin;
  - disconnect and resume;
  - full inventory, overflow and claim;
  - bonus spins and reset;
  - invalid reloads keep the old config;
  - addhand copies an item exactly;
  - command-reward permissions;
  - stale-menu clicks.

MockBukkit is a simulation, not a Paper server. Use [`TESTING.md`](TESTING.md) for an in-game check
before going live.

## Project layout

```
src/main/java/dev/exo/dailyspinner/
  ExoDailySpinner.java   plugin lifecycle, reload, file IO
  command/               /ds command + tab completion
  config/                loading/validation, theme text, item templates, settings, messages
  menu/                  menu holders/manager, main, spinner, preview, admin menus
  animation/             reel plan (pure) + animation task
  reward/                registry, weighted picker, snapshots, command templates, reward editor
  spin/                  spin/claim orchestration, operation guard, throttle, reconciliation log
  storage/               SQLite database, migrations, repository (all state transitions)
  cooldown/ util/        cooldown maths, durations, small caps, time formatting
```
