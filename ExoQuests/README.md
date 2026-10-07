# ExoQuests

Daily quests and a private rewards shop for Paper Skyblock servers.

Every day each player gets **3 different quests** rolled from a configurable pool. Completing a quest
awards **Quest Points** once — nothing else. Points are spent in `/questshop` on item or console-command
rewards. Quest Points belong to ExoQuests only: they are not a Vault economy, cannot be paid to other
players and have no item form.

- [Requirements](#requirements) · [Build](#build) · [Install](#install) · [Commands and permissions](#commands-and-permissions)
- [Quest pool](#quest-pool) · [How progress is counted](#how-progress-is-counted) · [Shop](#shop)
- [Storage, transactions and recovery](#storage-transactions-and-recovery) · [Exploit resistance and limitations](#exploit-resistance-and-limitations)
- [Testing](#testing) · [In-server verification checklist](docs/VERIFICATION.md)

## Requirements

| | |
|---|---|
| Server | **Paper 1.21.4 or newer 1.21.x** (`api-version: 1.21`). Spigot and Folia are not supported. |
| Java | **21** (required by Paper 1.21.4) |
| Build | JDK 21 and Maven 3.9+ |
| Dependencies | None besides Paper. SQLite (`org.xerial:sqlite-jdbc`) is bundled with Paper and is also declared under `libraries:` in `plugin.yml`. Adventure, MiniMessage and SnakeYAML come from Paper. |

The plugin compiles against `io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT`. To target another 1.21.x
build, change `<paper.version>` in `pom.xml`.

## Build

```bash
cd ExoQuests
mvn clean package
```

The plugin jar is `exoquests-paper/target/ExoQuests-1.0.0.jar`. `mvn package` also runs the unit tests
of the `exoquests-core` module (`mvn -pl exoquests-core test` runs only those).

Project layout:

| Module | Contents |
|---|---|
| `exoquests-core` | Platform-independent logic, no Bukkit imports: config parsing/validation, quest pool, reset schedule, progress service, SQLite storage and migrations, points ledger, purchase state machine, placement tracking. Default config files live in `src/main/resources`. All unit tests are here. |
| `exoquests-paper` | Paper adapter: plugin bootstrap, event listeners, menus, commands, item serialization, delivery. Shades `exoquests-core` into the plugin jar. |

## Install

1. Stop the server and put `ExoQuests-1.0.0.jar` into `plugins/`.
2. Start the server once. ExoQuests writes `config.yml`, `quests.yml`, `shop.yml`, `messages.yml` and
   `menus.yml` to `plugins/ExoQuests/` and creates the database `plugins/ExoQuests/exoquests.db`.
   Because of the `libraries:` entry Paper may download `sqlite-jdbc` from Maven Central on first start;
   hosts that block outbound connections must allow that once (Paper's own bundled driver is used if present).
3. Edit the files and run `/exoquests reload`. If any file is invalid the reload is rejected, every
   problem is printed to the console and the previous configuration stays active. On startup an invalid
   configuration disables the plugin rather than running with guessed values.
4. Optionally grant `exoquests.admin.recovery` to staff who should be alerted about purchases that need review.

Storage settings (`storage.*`) only take effect after a restart. Back up `exoquests.db` (plus the
`-wal` file while the server runs) like any other server data.

## Commands and permissions

| Command | Permission | Console | Description |
|---|---|---|---|
| `/quests` (aliases `/quest`, `/dailyquests`) | `exoquests.use` (default: everyone) | no | Opens the daily quest menu. |
| `/quests points` | `exoquests.use` | — | Shows your Quest Points. |
| `/quests points <player>` | `exoquests.points.others` (op) | yes | Shows another player's points. |
| `/quests help` | `exoquests.use` | yes | Player help. |
| `/questshop` (alias `/qshop`) | `exoquests.shop` (default: everyone) | no | Opens the shop (also reachable from `/quests`). |
| `/exoquests reload` | `exoquests.admin.reload` | yes | Reloads all files atomically. |
| `/exoquests points <give\|take\|set> <player> <amount>` | `exoquests.admin.points` | yes | Adjusts a balance. `take` never goes below 0 (rejected instead); `give` is capped at `points.max-balance`. |
| `/exoquests points view <player>` | `exoquests.admin.points` | yes | Shows a balance. |
| `/exoquests reset <player>` | `exoquests.admin.reset` | yes | Discards the player's quests for the current day (progress and completion state); a fresh set of 3 is rolled. Points already earned are kept. |
| `/exoquests shop additem <id> <price> <material> [amount]` | `exoquests.admin.shop` | yes | Adds an item reward (amount 1–2304). |
| `/exoquests shop addhand <id> <price>` | `exoquests.admin.shop` | no | Adds an exact copy of the held item and stack size (Paper item serialization keeps all components). The held item is not changed. Empty hand is rejected. |
| `/exoquests shop addcommand <id> <price> <command...>` | `exoquests.admin.shop` | yes | Adds a console-command reward. Allowed placeholders: `{player}` `{uuid}` `{item_id}` `{purchase_id}` `{price}`. |
| `/exoquests shop remove <id>` | `exoquests.admin.shop` | yes | Removes a reward. |
| `/exoquests shop setprice <id> <price>` | `exoquests.admin.shop` | yes | Changes a price. |
| `/exoquests shop list` | `exoquests.admin.shop` | yes | Lists rewards. |
| `/exoquests recovery list` | `exoquests.admin.recovery` | yes | Lists purchases that need a staff decision. |
| `/exoquests recovery resolve <purchase-id> <delivered\|refund>` | `exoquests.admin.recovery` | yes | Closes a reviewed purchase, optionally refunding it (once). |

Other permissions:

| Permission | Default | Meaning |
|---|---|---|
| `exoquests.progress` | everyone | Earn quest progress. Remove it to exclude e.g. staff accounts. |
| `exoquests.admin` | op | Parent of every `exoquests.admin.*` node and `exoquests.points.others`. |
| `exoquests.admin.recovery` | op | Also receives an alert on join when purchases need review. |
| per reward | — | A shop entry may set `permission: some.node`; players without it see the reward as locked. |

Prices are whole numbers from **10 to 1000**. They are validated in commands (`1e3`, `10.0`, `+10`,
`1,000`, out-of-range values are rejected) and in `shop.yml` (decimals, quoted numbers and out-of-range
values fail the reload).

**Player arguments and offline players.** Player data is keyed by UUID. Commands accept an online
player's name, the name of any player who has joined the server before (Paper's user cache, no web
lookups), or a literal UUID. Points, resets and recovery work for offline players. Purchased rewards are
only handed out while the buyer is online; a purchase interrupted by a disconnect is delivered on the next
join (or refunded if it no longer fits).

**Audit log.** Every admin action (points changes, resets, shop edits, reloads, recovery decisions) is
written to the `audit_log` table and to the console prefixed with `[audit]`. Every balance change also
has a row in `point_ledger` with a unique reference, the actor and the resulting balance.

## Quest pool

All quests live in `quests.yml`, each with a stable id, category, tracking type, target, reward, icon,
`enabled` flag and selection `weight`. The shipped pool (54 quests: the 33 required plus 21 additional
Skyblock quests rewarding 9–18 points):

| id | category | type | counts | target | points |
|---|---|---|---|---:|---:|
| `mining_cobblestone` | mining | `BLOCK_BREAK` | cobblestone (natural only) | 1,000 | 10 |
| `mining_deepslate` | mining | `BLOCK_BREAK` | deepslate, cobbled_deepslate (natural only) | 700 | 12 |
| `mining_diamond_ore` | mining | `BLOCK_BREAK` | diamond_ore, deepslate_diamond_ore (natural only) | 100 | 19 |
| `mining_iron_ore` | mining | `BLOCK_BREAK` | iron_ore, deepslate_iron_ore (natural only) | 100 | 13 |
| `mining_gold_ore` | mining | `BLOCK_BREAK` | gold_ore, deepslate_gold_ore (natural only) | 100 | 14 |
| `mining_coal_ore` | mining | `BLOCK_BREAK` | coal_ore, deepslate_coal_ore (natural only) | 500 | 9 |
| `mining_copper_ore` | mining | `BLOCK_BREAK` | copper_ore, deepslate_copper_ore (natural only) | 200 | 11 |
| `mining_redstone_ore` | mining | `BLOCK_BREAK` | redstone_ore, deepslate_redstone_ore (natural only) | 150 | 13 |
| `farming_potatoes` | farming | `CROP_HARVEST` | potatoes | 200 | 12 |
| `farming_wheat` | farming | `CROP_HARVEST` | wheat | 500 | 12 |
| `farming_sugar_cane` | farming | `STACKED_PLANT_HARVEST` | sugar_cane (natural only) | 300 | 15 |
| `farming_cactus` | farming | `STACKED_PLANT_HARVEST` | cactus (natural only) | 1,000 | 12 |
| `farming_carrots` | farming | `CROP_HARVEST` | carrots | 900 | 12 |
| `farming_melons` | farming | `BLOCK_BREAK` | melon (natural only) | 2,000 | 12 |
| `farming_bamboo` | farming | `STACKED_PLANT_HARVEST` | bamboo (natural only) | 300 | 12 |
| `farming_pumpkins` | farming | `BLOCK_BREAK` | pumpkin (natural only) | 300 | 12 |
| `farming_nether_wart` | farming | `CROP_HARVEST` | nether_wart | 400 | 13 |
| `farming_beetroot` | farming | `CROP_HARVEST` | beetroots | 400 | 12 |
| `breeding_chickens` | breeding | `ENTITY_BREED` | chicken | 20 | 20 |
| `breeding_pigs` | breeding | `ENTITY_BREED` | pig | 10 | 20 |
| `breeding_cows` | breeding | `ENTITY_BREED` | cow | 15 | 20 |
| `breeding_sheep` | breeding | `ENTITY_BREED` | sheep | 18 | 20 |
| `kills_sheep` | mob kills | `ENTITY_KILL` | sheep | 100 | 20 |
| `kills_pigs` | mob kills | `ENTITY_KILL` | pig | 100 | 20 |
| `kills_cows` | mob kills | `ENTITY_KILL` | cow | 100 | 20 |
| `kills_chickens` | mob kills | `ENTITY_KILL` | chicken | 100 | 20 |
| `trees_jungle` | growing trees | `TREE_GROW` | jungle | 10 | 16 |
| `trees_oak` | growing trees | `TREE_GROW` | oak | 10 | 16 |
| `trees_spruce` | growing trees | `TREE_GROW` | spruce | 10 | 16 |
| `trees_birch` | growing trees | `TREE_GROW` | birch | 10 | 16 |
| `trees_acacia` | growing trees | `TREE_GROW` | acacia | 10 | 16 |
| `trees_dark_oak` | growing trees | `TREE_GROW` | dark_oak | 10 | 16 |
| `logging_jungle` | logging | `BLOCK_BREAK` | jungle_log (natural only) | 300 | 19 |
| `logging_oak` | logging | `BLOCK_BREAK` | oak_log (natural only) | 200 | 19 |
| `logging_spruce` | logging | `BLOCK_BREAK` | spruce_log (natural only) | 200 | 19 |
| `logging_birch` | logging | `BLOCK_BREAK` | birch_log (natural only) | 200 | 19 |
| `logging_acacia` | logging | `BLOCK_BREAK` | acacia_log (natural only) | 200 | 19 |
| `logging_dark_oak` | logging | `BLOCK_BREAK` | dark_oak_log (natural only) | 300 | 19 |
| `fishing_any` | fishing | `FISH_CATCH` | any catch | 40 | 14 |
| `fishing_cod` | fishing | `FISH_CATCH` | cod | 25 | 13 |
| `fishing_salmon` | fishing | `FISH_CATCH` | salmon | 15 | 16 |
| `crafting_bread` | crafting | `CRAFT_ITEM` | bread | 64 | 12 |
| `crafting_torches` | crafting | `CRAFT_ITEM` | torch | 128 | 9 |
| `crafting_paper` | crafting | `CRAFT_ITEM` | paper | 192 | 10 |
| `smelting_iron` | smelting | `SMELT_EXTRACT` | iron_ingot | 64 | 14 |
| `smelting_stone` | smelting | `SMELT_EXTRACT` | stone | 256 | 10 |
| `smelting_glass` | smelting | `SMELT_EXTRACT` | glass | 128 | 10 |
| `cooking_meat` | smelting | `SMELT_EXTRACT` | cooked_beef, cooked_porkchop, cooked_chicken, cooked_mutton | 64 | 12 |
| `shearing_sheep` | ranching | `SHEAR_ENTITY` | sheep | 40 | 14 |
| `collecting_eggs` | ranching | `COLLECT_LAID_EGGS` | egg | 48 | 13 |
| `combat_zombies` | combat | `ENTITY_KILL` | zombie | 50 | 16 |
| `combat_skeletons` | combat | `ENTITY_KILL` | skeleton | 50 | 17 |
| `combat_spiders` | combat | `ENTITY_KILL` | spider, cave_spider | 40 | 15 |
| `combat_creepers` | combat | `ENTITY_KILL` | creeper | 30 | 18 |

Quests are picked by weight without replacement, so the three are always different. The target and
reward are copied into the player's assignment when it is rolled; editing `quests.yml` affects the next
roll. Disabling or deleting a quest does not remove it from assignments already made today: a disabled
quest keeps working, a deleted one shows as "removed from the pool" and can no longer progress (staff can
`/exoquests reset` the player).

### Daily reset

`reset.time` (default `00:00`) in `reset.timezone` (default `Europe/London`). A "quest day" starts at the
reset instant and lasts until the next one, so days are 23 or 25 hours long across daylight-saving changes.
A reset time inside a spring-forward gap happens right after the gap; one inside an autumn overlap
happens once (at the first occurrence).

- Assignments are stored per player and quest day. Rejoining, restarting or reloading never rerolls them.
- Players who were offline during a reset simply get the new day's quests when they next join.
- Online players are moved to the new day within a second of the reset (they get a message). Progress
  from the previous day stays attached to that day and is discarded; actions that happened before the
  reset but are processed after it are not credited to the new day.
- Point balances never expire.
- The menu shows the time until the next reset.

## How progress is counted

ExoQuests counts the qualifying **action**, never the number of dropped items. Fortune, Looting and
double drops have no effect. All listeners run at `MONITOR` priority with `ignoreCancelled = true`:
anything a protection plugin (WorldGuard, SuperiorSkyblock2, BentoBox, ...) cancels is not counted.
Progress is only earned by players with `exoquests.progress`, in an allowed game mode
(`tracking.allowed-gamemodes`, default survival and adventure — creative and spectator can never be
enabled) and in an allowed world (`tracking.worlds`).

| Type | Counted when | Notes |
|---|---|---|
| `BLOCK_BREAK` | A player breaks a block of a listed material. | With `natural-only` (default), blocks recorded as player-placed do not count. |
| `CROP_HARVEST` | A player breaks a crop at its **maximum growth stage**. | Immature crops never count. Crops cannot be placed fully grown in survival, so no placement record is needed. |
| `STACKED_PLANT_HARVEST` | A player breaks sugar cane, cactus or bamboo. | The broken segment **and every segment above it** (which pop off) count once each. Breaking the block a column stands on also counts the column (`count-support-breaks`). Player-placed segments never count, so the usual "leave the bottom block" harvesting earns the grown segments only. |
| `ENTITY_BREED` | A baby is born from parents a player fed (`EntityBreedEvent` with that player as breeder). | One per offspring. |
| `ENTITY_KILL` | The mob's killer is the player **and** (by default) the killing blow came from the player's melee or own projectile. | Fall, lava, fire, suffocation, cramming, drowning and other environmental deaths never count, even after a player hit the mob. Mobs from `excluded-spawn-reasons` (default spawners, trial spawners, spawn eggs, commands and plugin spawns) never count. Pet kills are off by default. |
| `TREE_GROW` | A sapling **planted by a player** grows into a tree (natural growth or bone meal). | One tree per growth event; 2x2 trees count once. Credit goes to the planter (or the bone-mealer with `credit: BONEMEALER_IF_PRESENT`), including when the planter is offline (`credit-offline-planter`). Saplings that existed before ExoQuests was installed have no planter and do not count. |
| `FISH_CATCH` | A player reels in an item with a fishing rod. | One per catch. An empty material list accepts any catch. |
| `CRAFT_ITEM` | A player takes items from a crafting result. | Counts the items actually produced: shift-click = as many crafts as ingredients **and** inventory space allow; other clicks craft once if the result can go to the cursor, hotbar slot, offhand or be dropped. |
| `SMELT_EXTRACT` | A player takes items from a furnace, smoker or blast furnace output slot. | Hopper extraction does not fire the event and does not count. |
| `SHEAR_ENTITY` | A player shears an entity with shears. | Dispenser shearing does not count. |
| `COLLECT_LAID_EGGS` | A player picks up an egg that a chicken laid. | Laid eggs are tagged on the item entity (survives restarts). Player-dropped eggs are untagged and are prevented from merging with laid eggs. Hoppers do not count. |

### Mining and generators

Generator output counts because it is never recorded as placed:

| Quest | Qualifying blocks |
|---|---|
| cobblestone | `COBBLESTONE` from a lava/water generator, an ore-generator plugin, or anywhere else, unless a player placed that block. **Stone does not count.** |
| deepslate | `DEEPSLATE` and `COBBLED_DEEPSLATE` (vanilla has no deepslate generator; this covers generator plugins that output either) unless player-placed. |
| ores | Standard and deepslate variants, e.g. `DIAMOND_ORE` and `DEEPSLATE_DIAMOND_ORE`, unless player-placed. Silk-touched ores that are placed again do not count. |

Edit the material lists in `quests.yml` to match your generator plugin.

### Anti-farming placement records

For every material used by a `natural-only` quest (and saplings, if any tree quest exists) ExoQuests
stores a row in the `placed_blocks` table when a player places one. Breaking that block deletes the row
and earns no natural-only progress. The records:

- persist across restarts (SQLite);
- move with blocks pushed or pulled by pistons (blocks a piston destroys lose their record);
- are removed when the block explodes, burns, fades, is washed away, changed by an entity, or a new block
  grows into that position (cane/cactus segment, melon or pumpkin from a stem);
- are treated as stale if the block at that position is now a different material (e.g. a generator filled
  the position after the placed block vanished without an event), except for in-place transformations
  (stripping a log, a bamboo shoot growing into bamboo), which keep the record;
- are removed when a tree grows over them, so logs of player-grown trees count for logging quests;
- grow only with the number of player-placed tracked blocks that currently exist, not with the number of actions.

## Shop

`/questshop` (or the emerald in `/quests`) opens a paginated shop showing the balance, the real reward
item as preview (enchantments, name, lore), its price and whether the player can afford it. Clicking an
affordable reward opens a confirmation menu. On confirm ExoQuests re-checks, against the configuration at
that moment: the reward still exists and is enabled, the price and the reward contents (a revision hash)
are unchanged, the permission, the inventory space for item rewards, and finally the balance with a
conditional database debit. Any mismatch cancels the purchase without charging.

The default `shop.yml` contains: 16 torches (10), 32 oak logs (25), 16 iron ingots (60), 4 diamonds (120),
an Efficiency IV book (300), a diamond pickaxe with Efficiency V and Unbreaking III (1000), and a disabled
command reward example.

Item rewards are written either as readable fields (`material`, `amount`, `name`, `lore`, `enchantments`,
`stored-enchantments`, `custom-model-data`, `unbreakable`, `item-flags`) or as `serialized:` data created by
`/exoquests shop addhand`. Serialized items use Paper's `ItemStack#serializeAsBytes`, which preserves every
item component (names, lore, enchantments, potion contents, custom model data, persistent data, ...) and
is upgraded automatically when the server updates. The exact stacks paid for are stored with the purchase,
so later edits never change what an in-flight purchase delivers.

Command rewards run from the console. Their templates are written by administrators only; the
placeholders are filled with server-generated values (the player name must match
`shop.command-player-name-pattern`, which can never allow spaces or `;`). Nothing a player types is ever
inserted into a command.

`/exoquests shop ...` edits re-read `shop.yml` from disk, apply the change, validate the result like a
reload, write it atomically and then activate it. Comments in `shop.yml` other than the header are not kept.

## Storage, transactions and recovery

All state is in SQLite (`plugins/ExoQuests/exoquests.db`, WAL mode, `synchronous=FULL` by default):
balances, the points ledger, daily assignments and progress, completion records, purchases with their
delivery state, placement records and the audit log. Schema changes are applied by numbered, transactional
migrations (`schema_version` table); a database from a newer build is refused rather than modified.

All database work runs on one dedicated thread, in order, each unit in its own transaction. Bukkit world
and inventory access happens only on the main thread.

**Quest completion is exactly-once.** Progress is held in memory and flushed every
`progress-flush-seconds`. Completion is a single transaction that marks the slot completed only if it is
not completed yet (`completed_at IS NULL`), writes a completion record with a unique id, and credits the
reward with a ledger row whose reference is unique. Concurrent or repeated attempts credit nothing. A
crash can lose at most the last few seconds of unfinished progress, never duplicate or lose a reward.

**Purchases are not atomic with delivery — and are not claimed to be.** They are a sequence of durable steps:

```
confirm ─▶ re-validate (main) ─▶ debit + record PENDING (1 transaction)
        ─▶ re-check space (main) ─▶ record DELIVERING ─▶ hand out items / run commands (main) ─▶ DELIVERED
```

| Crash or failure point | State on restart | What happens |
|---|---|---|
| before the debit commits | nothing recorded | Nothing was charged. |
| after the debit, before delivery started | `PENDING` | Delivered when the player next joins; refunded if it does not fit. Safe because nothing was handed out. |
| after `DELIVERING` committed (during or right after hand-out) | `DELIVERING` → flagged `NEEDS_REVIEW` at startup | **Never retried automatically**, so commands never run twice and items are never duplicated. Staff see an alert and decide with `/exoquests recovery resolve <id> delivered\|refund`. |
| inventory became full between the checks | `REFUNDED` | Points returned in the same transaction that closes the purchase. |
| a console command fails or delivery throws | `NEEDS_REVIEW` | Manual review (partial command execution cannot be undone automatically). |
| player disconnects mid-purchase | `PENDING` | Delivered on next join. |

A refund uses the ledger reference `refund:<purchase-id>`, so a purchase can be refunded at most once.
Purchases are serialized per player (a second purchase while one is in flight is rejected), and the debit
is a conditional update (`points >= price`), so concurrent requests can never overspend. Item delivery
re-checks inventory space on the same server tick as `addItem`; if another plugin still interferes, any
overflow is dropped at the player's feet and logged rather than lost.

## Exploit resistance and limitations

Duplication and exploit resistance are engineering goals, not an absolute guarantee. What is protected:

- **Menus** are recognised only by their server-side holder object, never by title or lore. While an
  ExoQuests menu is open, every click (top or bottom inventory, shift-click, number keys, offhand swap,
  double-click collect, drop keys) and every drag is cancelled at the lowest and again at the highest
  priority. Only plain left/right clicks on menu slots trigger actions, rate-limited per player
  (`shop.click-cooldown-ms`) and executed on the next tick only if that same menu is still open.
- **Confirmations** are single-use server-side sessions that expire (`shop.confirm-timeout-seconds`), are
  invalidated by any configuration or shop change, and carry the price and reward revision the player saw.
  Repeated confirm packets after the first are ignored; a second concurrent purchase is rejected.
- **Points** only change through ledgered database operations with unique references and non-negative
  balance constraints. Amounts are validated (no negatives, no overflow; `points.max-balance` cap).
- **Configuration**: unknown materials, entity types, enchantments, sounds, duplicate ids (duplicate YAML
  keys), out-of-range numbers, overlapping menu slots and unknown command placeholders fail validation;
  a failed reload keeps the last valid configuration.

Known limitations (configure or moderate around these):

- **Placement records only cover blocks placed by players through normal placement.** Blocks placed by
  other plugins, schematics (island templates), WorldEdit or dispensers are treated as natural. Island
  starter schematics containing ores or logs therefore count once when mined.
- Materials become tracked when a natural-only quest uses them. Blocks placed *before* a material was
  tracked (or before ExoQuests was installed) count as natural once.
- Cane/cactus segments popped by block updates other than a player break (e.g. water, a piston breaking
  the support) keep a stale record only if they were player-placed; a cane later growing exactly there is
  counted as placed once. This only ever under-counts.
- Bamboo: when a planted shoot becomes the first bamboo block, the grow event clears its record, so the
  bottom segment of a bamboo grown from a shoot counts once.
- **Kills**: mob-stacking plugins that merge many mobs into one entity report a single death; ExoQuests
  counts one kill. Kills by the player's TNT, fire aspect damage-over-time or lava buckets do not count
  with `require-direct-damage: true`.
- **Fishing** cannot distinguish an AFK fishing setup from active fishing; both count.
- **Crafting** counts are computed from the click type, ingredient counts and inventory space; recipes
  that consume more than one item from a single grid slot (none in vanilla crafting tables) would be
  over-estimated. Avoid reversible recipes (e.g. hay bale ↔ wheat) for crafting quests.
- **Smelting** counts what a player takes out of the output slot, not what was smelted; it cannot be
  inflated because players cannot insert into output slots.
- **Breeding** relies on the breeder recorded by the server; feeding with dispensers (not possible in
  vanilla) would not count.
- Tree credit needs the sapling's placement record; saplings planted by players without
  `exoquests.progress`, in creative, or before installation earn nothing.
- Up to `progress-flush-seconds` of unfinished progress can be lost on a hard crash (rewards are never
  lost or duplicated).
- Item delivery uses `PlayerInventory#addItem`, which only fills the 36 main slots (not armor or offhand),
  matching the space check.
- `custom-model-data` sets the legacy integer form; on 1.21.4+ resource packs read it as the first float
  of the custom model data component.
- Folia is not supported.

## Testing

`mvn test` runs 61 unit tests in `exoquests-core` against a real SQLite database:

| Area | Tests |
|---|---|
| Exactly-once completion | 32 threads completing the same slot → 1 credit, 1 ledger row; offline progress completes once; progress persistence never reaches the target without a completion; service-level completion with many actions past the target. |
| Concurrent purchases | 64 simultaneous purchases by one player (guarded); 24 purchases through independent service instances (database is the only guard) → exactly as many as the balance allows, never negative. |
| Reset boundaries | Exact midnight boundaries in summer and winter, 23h/25h days, reset times inside DST gaps and overlaps, one-year monotonicity sweep, other timezones; service rollover at the boundary, discarding stale actions, offline players, points kept. |
| Insufficient funds / stale confirmations | No charge on insufficient funds, changed price, changed reward, removed reward, no space, no permission. |
| Invalid prices and config | Prices 9, 1001, 0, negative, decimal, quoted, text, overflow; duplicate ids; invalid materials; unknown placeholders; malformed YAML; overlapping menu slots; exact required targets/rewards; shop round-trip. |
| Crash recovery | Simulated crash after debit (PENDING) and during delivery (DELIVERING) with database close/reopen: PENDING delivered once on join, DELIVERING flagged for review and never replayed; refund-once; space lost after debit → refund; delivery exception → review. |
| Persisted anti-farming | Placement records survive a database reopen; place/break loops never count; piston chains move records; stale records; log stripping; column counting; sapling consumption; operation ordering under load. |

The Paper adapter (`exoquests-paper`) has no automated tests: it needs a running server. See
[docs/VERIFICATION.md](docs/VERIFICATION.md) for the in-server checklist.
