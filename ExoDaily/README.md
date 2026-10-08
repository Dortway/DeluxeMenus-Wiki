# ExoDaily

Calendar-based daily rewards for Paper with a standard tier and a premium tier, randomized per player, built to make reward duplication hard. Rewards are items, console commands, or both.

| | |
|---|---|
| **Minecraft / server** | Paper **1.21.11** (`api-version: '1.21.11'`) |
| **Java** | **21** |
| **Runtime dependencies** | None. SQLite uses the `org.xerial:sqlite-jdbc` driver that Paper already bundles (3.49.1.0 in 1.21.11). |
| **Deployment** | **Single server only.** See [Multi-server](#multi-server). |
| **Folia** | Not supported (`folia-supported: false`). |

---

## Installation

1. Use Paper 1.21.11 on Java 21.
2. Put `ExoDaily-1.0.0.jar` (prebuilt in [`dist/`](dist/)) into `plugins/`.
3. Start the server. It creates `plugins/ExoDaily/` containing `config.yml`, `messages.yml`, `menus.yml`, `rewards.yml`, `data.db` (SQLite) and `audit.log`.
4. Give premium players `exodaily.premium` with your permissions plugin, e.g. `lp group vip permission set exodaily.premium true`.
5. Edit the files, then run `/exodaily reload`. If a file has errors, the reload is rejected and the previous configuration stays active. Each error names the file and the entry.

## Building from source

```bash
cd ExoDaily
mvn clean package          # compiles, runs all tests, builds target/ExoDaily-1.0.0.jar
```

Maven fetches `io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT` from `https://repo.papermc.io/repository/maven-public/`. Everything else comes from Maven Central. Tests use JUnit 6 and MockBukkit `mockbukkit-v1.21` 4.116.3, which also targets Paper 1.21.11.

---

## How it works for players

- `/daily` opens a 27-slot menu. The reward summary for today is in the middle (slot 13).
- A player's cycle starts on the calendar date of their first `/daily`. Day 1 is available straight away.
- A day is a **calendar date** in the configured timezone (default `Europe/London`). New days start at local midnight. Progress is worked out from saved dates, so the plugin never assumes a day is 24 hours long:
  - On daylight-saving changeover days, the day lasts 23 or 25 hours.
  - If a timezone has no midnight on some date, that day starts at the first valid time.
  - Offline players keep progressing because their saved cycle start date is fixed while the calendar moves on.
- **Missed days are skipped for good.** Only today's rewards can be claimed; there are no catch-up claims.
- After the last day (30 by default), a new cycle starts automatically.
- Each day has three numbered positions, and each one is claimed separately:
  1. standard reward (everyone)
  2. premium reward (`exodaily.premium`)
  3. premium reward (`exodaily.premium`)

  Standard players can claim at most 1 reward per day. Premium players can claim at most **3 in total**: the standard reward is one of the three, not an extra.
- Players who upgrade after claiming their standard reward can claim positions 2 and 3 the same day. Losing premium and getting it back never resets claims, because every claim is stored per position.
- Click the central item to open the details menu. It shows the three actual rewards with full descriptions and claim buttons. Click the cycle-progress item (slot 4) to see every day of the cycle: past days show claimed or missed, and future days show only the **possible** rewards from their pools. Nothing is assigned or promised in advance.

## Random rewards

- The first time a player views a day, all three positions are drawn **independently for that player** from weighted pools, then saved. This includes the premium positions of standard players, which are drawn even though they are locked.
- The saved rewards never change, whatever happens afterwards: reopening the menu, reconnecting, changing permissions, restarting or reloading the configuration.
- Each assignment stores a **snapshot** of the reward definition in the database. What the player sees and receives comes from that snapshot, so editing or deleting a reward in `rewards.yml` cannot change an assigned reward or break a claim that hasn't happened yet.
- Snapshots include the reward's type and commands, so editing a command in `rewards.yml` does not change a command reward that has already been assigned.
- Players can get the same reward by coincidence. The `prevent-duplicates-per-day` option stops the same reward id from appearing twice in one player's day.
- If a pool has run out of eligible entries, the selector tries that pool's `fallback`, then the global `fallback-pool`. A duplicate is used only as a last resort, and that is logged once per day and position. The plugin also warns at load time when a day can't reach 3 distinct rewards.
- Rewards are never generated for skipped days.

---

## Commands and permissions

| Command | Permission | What it does |
|---|---|---|
| `/daily` | `exodaily.use` (default: everyone) | Opens the rewards menu |
| `/exodaily help` | `exodaily.admin` (default: op) | Lists the admin commands |
| `/exodaily reload` | `exodaily.admin` | Reloads all four files. **If anything is invalid, the last valid configuration stays active.** |
| `/exodaily status <player>` | `exodaily.admin` | Shows cycle, day, start date, today's assigned rewards with their claim states, and any uncertain claims. Read-only. |
| `/exodaily setday <player> <day>` | `exodaily.admin` | Moves the player to a day of their current cycle |
| `/exodaily reset <player> confirm` | `exodaily.admin` | Starts a new cycle at day 1 today. Without `confirm` it only shows a warning. |
| `/exodaily reward save <id>` | `exodaily.admin` | Creates or updates reward `<id>` from the item in your main hand |
| `/exodaily pending` | `exodaily.admin` | Lists claims waiting for administrative reconciliation |
| `/exodaily resolve <claim-id> <delivered\|release>` | `exodaily.admin` | Settles an uncertain claim |
| — | `exodaily.premium` (default: nobody) | Allows claims at positions 2 and 3. Checked live on every claim. |

All commands have tab completion. Players without permission see the `no-permission` message.

### What each admin operation does to claims

| Operation | Claims | Assignments | Notes |
|---|---|---|---|
| `setday` | **Kept** | Kept | Moves the cycle start date inside the same cycle. A (cycle, day, position) that was already claimed stays claimed if the player returns to that day. |
| `reset … confirm` | **Kept as history but stop counting** | New cycle | Starts a new cycle number at day 1 today. ⚠ **The player can earn rewards again, including today's.** The command warns about this and needs `confirm`. |
| `resolve <id> delivered` | Uncertain claim → delivered | — | Use this when you have confirmed the player received the item. |
| `resolve <id> release` | Uncertain claim deleted | — | The position can be claimed again only while that day is still the player's current day. |
| `reward save` | — | Already-assigned snapshots are unchanged | Only affects future assignments, and only once the reward is in a pool. |
| `reload` | — | Unchanged | Open menus are refreshed. |

Every admin change is written to the `audit_log` database table and to `plugins/ExoDaily/audit.log` (can be turned off with `audit.file`). Each change also invalidates the affected player's open menus: they are re-rendered from fresh data, and an old menu can never be used to claim.

---

## Configuration files

All four files are commented throughout. In short:

| File | Contents |
|---|---|
| `config.yml` | Timezone, cycle length, storage, click/open cooldowns, menu update interval, small caps and symbols (each with a plain-text alternative), audit and notification options |
| `messages.yml` | Every chat message (MiniMessage) |
| `menus.yml` | Titles, rows, borders, slots, materials, names, lore, per-state looks (available, claimed, locked, processing, review, missed…), sounds, claim particle effect, shared text lines |
| `rewards.yml` | Rewards, weighted pools with fallbacks, the default schedule and per-day overrides (all 30 days listed, milestone days 7/14/21/30) |

Text extras on top of MiniMessage:
- `<sym:star>` inserts a symbol from `config.yml` (`✦ ◆ ✓ ✕ ⌛ ➜ · ■ ⚠`), or its plain alternative when `style.symbols: false`.
- `<sc>text</sc>` renders as small caps (`ᴅᴀʏ 7`), or as plain lowercase when `style.small-caps: false`.

### Item rewards

```yaml
rewards:
  milestone_pickaxe:
    material: DIAMOND_PICKAXE
    amount: 1                       # split into stacks, at most 36 stacks
    summary: "milestone pickaxe"    # short text used in menus
    name: "<#67E8F9><sym:star> milestone pickaxe"
    lore: ["<#9CA3AF>earned by a full week of rewards"]
    enchantments: { efficiency: 3, unbreaking: 2 }
    flags: [HIDE_ENCHANTS]
    custom-model-data: 1001         # first custom-model-data float (1.21.4+ component)
    weight: 15
```

`/exodaily reward save <id>` stores the held item using Paper's `ItemStack#serializeAsBytes()`, so all of its data (components, books, potions, custom data and so on) is kept exactly. Field-by-field rebuilding would lose some of it. The file is written atomically. If the result fails validation, the previous `rewards.yml` is restored. To make the reward selectable, add its id to a pool.

### Validation

Every load checks the following and reports errors as `[file] path: message`:
- materials, amounts, weights and enchantments
- item flags, particles and sound keys
- empty pools, unknown reward or pool references and fallback references
- reward ids, including duplicate YAML keys (reported with a line number)
- malformed YAML and timezones
- menu slots that are out of range or used twice in one menu
- the overview having fewer day slots than the cycle length
- date formats

A message or line key that is missing falls back to the built-in default and logs a warning. Any error rejects the whole reload.

---

## Persistence, duplication safeguards and recovery

### Storage
- SQLite (`data.db`) in WAL mode with `synchronous=FULL`, so a committed claim state is durable when the call returns. The schema is versioned in a `schema_version` table with forward migrations. A database from a newer plugin version is refused.
- Players are identified by UUID only. Every query uses prepared statements, and every multi-step change runs in one transaction.
- Tables: `players` (cycle dates), `assignments` (reward snapshots), `claims` (claim and delivery records), `audit_log`.
- Each claim's identity is `uuid:cycle:day:position`. It is `UNIQUE` in the database (twice: as `claim_key` and as the column tuple), so a second claim of the same position cannot be inserted, whatever the code path.

### Claim pipeline

All database work runs on a single bounded storage thread. That serializes competing claims, and when the queue is full, new requests are refused with a "busy" message. Inventory work runs on the server thread.

```
storage  re-resolve today's date / cycle / day from persisted dates; must equal the menu's
         assignment must exist; INSERT claim as RESERVED (UNIQUE)          → else nothing is given
server   online? premium (live check)? item can be built? fits COMPLETELY? → else delete reservation
storage  RESERVED → DELIVERING   (committed before any inventory change)
server   re-check; add items all-or-nothing; restore inventory if anything is left over
storage  DELIVERING → DELIVERED
```

- **Nothing is delivered before the reservation is committed.** If storage is unavailable or fails at any step before delivery, the claim fails closed.
- **No rewards are dropped on the ground.** If the inventory can't hold the whole reward, nothing is given, the reservation is deleted, and the reward stays claimable until reset.
- **Midnight races.** The date is read again on the storage thread when the claim is reserved. A menu rendered for a day that has since ended is "stale" and is refreshed instead of being acted on, so past days can never be claimed.
- **Stale menus.** Each menu records the cycle, day, date and session epoch it was rendered for. Admin changes and day rollovers bump the epoch.
- **Repeated clicks** are covered by a per-player single-flight guard (one claim in flight at a time) plus a click cooldown. Each claim attempt also has a unique attempt id, so a late or duplicate async callback can't change another attempt's claim.
- **Never trusting the client.** Menus are identified by a server-side `InventoryHolder` (`ExoMenu`). Slot actions come from that holder. Item names, lore and inventory titles are never used for authorization.
- **Inventory locking.** While an ExoDaily menu is open, every click is cancelled: shift-clicks, number keys, double-clicks, off-hand swaps, drops, creative actions and clicks in the player's own inventory. Drags are cancelled too. Cancellation is applied again at `HIGHEST` priority in case another plugin un-cancels it. Only plain left and right clicks on the menu are interpreted.
- **Leaked icons.** Every menu icon is tagged in its persistent data container. As defence in depth, tagged items are removed from inventories on close and on join, and destroyed if dropped.

### What a database transaction cannot do

A database transaction **cannot atomically include a Minecraft inventory change**: the inventory lives in server memory and in player data files that the server saves on its own schedule. ExoDaily therefore records how far delivery got (`RESERVED` → `DELIVERING` → `DELIVERED`) and applies this recovery policy at startup:

| State found at startup | Was anything given? | Action |
|---|---|---|
| `RESERVED` | No. The inventory change only happens after `DELIVERING` is committed. | The reservation is deleted, and the reward becomes claimable again if its day is still current. |
| `DELIVERING` | **Unknown.** The server stopped between committing `DELIVERING` and committing `DELIVERED`. If it crashed, the player's inventory may not have been saved either. | Marked **`UNCERTAIN`**. It is **never reissued automatically**, it stays blocked for the player ("being checked by staff"), and admins are told on join and in the console. |
| `DELIVERED` | Yes | Nothing to do |

Admins reconcile uncertain claims with `/exodaily pending` and `/exodaily status <player>`, then `/exodaily resolve <id> delivered` or `/exodaily resolve <id> release`. The same `UNCERTAIN` state is used if items were given but recording `DELIVERED` failed (the player is told staff will double-check), or if the inventory change itself threw and couldn't be rolled back.

The window for an uncertain claim is only the time between two server ticks. It is still a real window, and ExoDaily does not claim otherwise.

### Command rewards

A reward can run console commands instead of, or after, giving an item:

```yaml
rewards:
  money_500:
    type: command              # item (default) | command | both
    material: GOLD_NUGGET      # menu icon only for type: command
    summary: "$500"            # required for command rewards
    commands:
      - "eco give {player} 500"
```

Placeholders: `{player}` `{uuid}` `{claim_id}` `{cycle}` `{day}` `{position}` `{reward}`. A leading `/` is optional. You can have up to 16 commands per reward, each on a single line. Unknown placeholders are rejected when the config loads. A command label that no plugin has registered produces a warning. That check runs after all plugins have enabled, and again on every reload.

**These rewards have weaker guarantees than item rewards. They are not dupe-proof, and ExoDaily does not claim otherwise.**

- They go through the same claim pipeline. Commands run only after the claim is reserved and `DELIVERING` is committed, at most once per claim attempt, and never for a claim that already exists.
- They **cannot be rolled back**. For `both`, items are given all-or-nothing first, then the commands run in order.
- ExoDaily **cannot see what a command did**. If a command throws or reports failure (for example an unknown command or a usage error), the claim becomes **`UNCERTAIN`**: it stays blocked, is never re-run automatically, and is logged with the failing command for `/exodaily pending` and `/exodaily resolve`. Earlier commands in the list may already have taken effect.
- A crash while commands are running leaves the claim `DELIVERING`. On restart it becomes `UNCERTAIN`, exactly as for items.
- A command that "succeeds" but does nothing (for example an economy plugin that silently rejects the player) cannot be detected.
- **Use idempotent integrations where possible.** `{claim_id}` (`uuid:cycle:day:position`) is unique per claimable position. Pass it to plugins or scripts that can ignore an id they have already processed, so a reward can never be applied twice even after a manual `resolve … release`.
- Commands run as the console with full permissions. Only server administrators should be able to edit `rewards.yml`.

### Multi-server

The default deployment supports **one server**. Claim uniqueness is enforced by a local SQLite file and a per-server storage thread. Sharing `data.db` between servers, or running the same players on several servers with separate databases, is **not** safe and is not supported. Cross-server safety would need shared storage with distributed claim coordination, which is not implemented.

---

## Performance

- Database and file I/O never run on the server thread. They use one bounded storage thread, and its queue capacity is configurable. Inventory and player work always runs on the server thread.
- There is no task per player. One repeating task, at `menus.countdown-update-ticks` (default every second), visits **only players with an ExoDaily menu open**. It redraws a menu only when its visible countdown minute, the date or the player's premium status has changed.
- Sessions exist only for online players who used ExoDaily and are removed on quit, so they are bounded by the player count.
- Rendering (`MenuRenderer`) is pure presentation. Reward selection, progression, storage and delivery are separate classes in a core package that doesn't depend on Bukkit.
- No reflection and no server internals (NMS) are used.
- Shutdown closes open menus, drains the storage queue (waiting up to 10 seconds) and closes the database. Interrupted claims are handled by the recovery policy above.

---

## Verification

### What was actually compiled and tested

- The plugin was compiled with `javac` 21 (`--release 21`) against **Paper API 1.21.11** with no warnings.
- **92 automated tests pass** (`mvn clean package`):
  - Core tests run against a real SQLite database, without a server.
  - **4 command-reward tests** run on MockBukkit's real command map: placeholder expansion, no items for `command`, items before commands for `both`, no commands when the items don't fit, and failing or unknown commands → `UNCERTAIN`.
  - **11 integration tests** boot the real plugin on MockBukkit 4.116.3 (a mock Paper 1.21.11 server). They cover enabling with the bundled configuration and storage, menu contents, every click type plus drags being cancelled, standard and premium claims through the GUI, full inventories, leaked-icon removal, admin `setday` refreshing open menus, permission denial, reload rollback, the overview previews and `reward save` from the hand.
- **Build environment note:** the network used for this build could not reach `repo.papermc.io`. The Paper API jar was therefore compiled locally from the official `PaperMC/Paper` git tag `1.21.11`, with its declared dependencies from Maven Central and Mojang Brigadier built from source. `pom.xml` uses the normal Paper repository and coordinates, so a normal build fetches the official artifact.
- **Not done:** the plugin has **not** been run on a real Paper server with a real client. Use [TESTING.md](TESTING.md) for the in-game checklist (GUI exploits and visual presentation).

### Test coverage

| Requirement | Tests |
|---|---|
| Midnight rollover, DST (23-hour and 25-hour days, missing midnight) | `TimeAndProgressionTest.MidnightAndDaylightSaving` |
| Offline progression, missed days, day 30 → new cycle, long absences, cycle-length changes | `TimeAndProgressionTest.CycleProgression`, `ClaimServiceTest.DatesAndStaleMenus` |
| Standard 1/day, premium 3 total, upgrades, premium removal and restoration | `ClaimServiceTest.TierLimits`, `PaperIntegrationTest.claimingDeliversOnceAndRespectsPremium` |
| Stable assignments across reopen, restart and reload; independent per player; skipped days not generated | `AssignmentAndAdminTest` |
| Weighted pools, duplicate prevention, fallbacks, milestone overrides, all 30 days covered | `RewardSelectorTest` |
| Concurrent claims (32 racing attempts → 1 delivery), parallel storage threads, repeated clicks | `ClaimServiceTest.Concurrency` |
| Full inventories, inventory filling up mid-claim, player leaving mid-claim | `ClaimServiceTest.Inventory`, `InventoryAndTextTest`, `PaperIntegrationTest.fullInventoryKeepsTheRewardAndDropsNothing` |
| Database failure (fail closed), crash before delivery (released), crash after delivery (`UNCERTAIN`, never reissued), admin resolution | `ClaimServiceTest.FailuresAndRecovery` |
| Invalid configuration (materials, amounts, weights, enchantments, flags, empty pools, duplicate ids, malformed YAML, slot conflicts) keeping the previous configuration | `ConfigLoaderTest`, `PaperIntegrationTest.failedReloadKeepsTheActiveConfiguration` |
| `setday` keeps claims, `reset` allows re-earning, audit entries, read-only status | `AssignmentAndAdminTest` |
| Command rewards: validation, snapshots, `{claim_id}`, `both` ordering, failures flagged | `ConfigLoaderTest`, `RewardSelectorTest`, `CommandRewardTest` |

## Limitations

- Paper 1.21.11 and Java 21 only, as compiled. Newer Paper versions usually keep plugin compatibility, but they have not been tested.
- Single server only (see above). Folia is not supported.
- Command rewards have weaker guarantees than item rewards (see [Command rewards](#command-rewards)). There is no built-in economy integration; it is reached through commands.
- `custom-model-data` sets one float. For more complex models, use `/exodaily reward save` with a prepared item.
- An uncertain delivery after a crash needs manual reconciliation. This is deliberate.
- Changing `storage.*` needs a restart. Changing `timezone` can move "today" for every player; claims are never repeated, but a player may skip or repeat a calendar *date* boundary once.
- Day changes are noticed by open menus within one update interval (default 1 second). Claims are always checked against the exact date at reservation time.

## Project layout

```
ExoDaily/
├── pom.xml
├── dist/ExoDaily-1.0.0.jar             prebuilt plugin
├── src/main/java/dev/exodaily/
│   ├── core/          Bukkit-free domain (unit tested)
│   │   ├── time/        DailyClock (calendar dates, DST-safe reset)
│   │   ├── progression/ Progression, PlayerProfile, CycleState
│   │   ├── reward/      definitions, pools, schedule, weighted RewardSelector, snapshots
│   │   ├── storage/     SqliteStore (schema, migrations, transactions), claim states
│   │   ├── service/     DailyService (views, assignment), AdminService (admin, recovery)
│   │   ├── claim/       ClaimService pipeline, ClickGuard
│   │   ├── delivery/    InventoryFit (all-or-nothing space simulation)
│   │   ├── config/      ConfigLoader (validation), ConfigManager (last-valid config)
│   │   ├── text/        TextStyler (MiniMessage, <sym>, <sc>)
│   │   └── audit/       AuditLog
│   └── paper/         Paper integration
│       ├── ExoDailyPlugin, PaperPlatformValidator, ItemFactory, PaperClaimParticipant, Messenger
│       ├── menu/        ExoMenu holder, MenuRenderer, MenuService, MenuListener
│       ├── command/     /daily, /exodaily
│       └── session/     per-player sessions
├── src/main/resources/   plugin.yml, config.yml, messages.yml, menus.yml, rewards.yml
└── src/test/java/        unit, storage and MockBukkit integration tests
```
