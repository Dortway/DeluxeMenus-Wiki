# Integration support table

Inventory of dogo taken 2026-10-12: Paper 26.1.2 build 72 (MC 26.1.2, Java 25), no proxy, `online-mode=true`,
42 plugin jars. Every rule below was written against the **installed jar** (decompiled) and, where available, the
plugin's source folder in `plugins/*-Plugin`. Table and column names, states and file layouts are taken from that code;
the automated tests create each database from the exact `CREATE TABLE`/`ALTER TABLE` statements of the installed
version (`src/test/resources/fixtures`).

**Phase:** *live* = runs while the server is up, target kicked and locked. *startup* = the same rules applied in
ExoCleanWipe's `onLoad` before the plugin loads its data. With `wipe.live: true` (default) the "startup" rows below
also run live:

| Plugin | How it is changed live | Falls back to the next restart when |
|---|---|---|
| ExoVaults | empty record written with ExoVaults' own `RecordRepository.write`, next revision; old `.bak`/corrupt copies removed | vaults still loaded, a vault save pending, record unreadable |
| ExoWoodCube | `service().remove(console, cube)` per owned cube (its admin remove; also clears the structure) | blocked ledger entries (step is blocked instead) |
| ExoFarmCube | `service().adminRemove(console, cube, false, null)` per owned cube | — |
| ExoTools | `store().setState(id, REVOKED, …)` (its /exotools revoke) | — |
| LightZone | `backpacks().invalidate(id, true)` per backpack, other rows in the database | data written back |
| ExoSkills | cached entry removed from `storage.players`, rows deleted | data written back |
| EGen | `ChestStorageManager.remove(id)` per owned chest, purchased limit removed | someone has the chest open |
| ExoSpawners | entry removed from `Progress.players`, then its own `saveAsync()` | — |
| ExoEconomy | database | the player owns a running booster |
| ExoCubes, ChunkCollector, EPlayerShop | nothing to do when the player owns none | the player owns any (kept in memory, no live removal) |
| ExoBoxes, ExoDaily, ExoDailySpinner, ExoNightMarket, ExoQuests, ExoWands, ExoMultiTools, ExoTokenShop | database, after the kicked player's quit save | data written back |

Every live database change is re-checked after `wipe.live-settle-seconds`; data that comes back is wiped once more,
and if it comes back again the step moves to the next restart (it is never reported as done).

**Shared:** whether the deletion can affect other players. The preview lists them by name.

## Covered

| Data | Plugin / version | Phase | What happens | Shared | Restore |
|---|---|---|---|---|---|
| Main inventory, hotbar, armour, offhand | Paper 26.1.2 player file `world/players/data/<uuid>.dat` | live (file, target offline) | `Inventory` emptied, `equipment` removed, verified after writing; `<uuid>.dat_old` (previous save, still holding the items) removed | no | automatic (`/wipe restore`) |
| Cursor and crafting grid | Paper API | live, before the kick | backed up and cleared (they are not stored in the file) | no | items saved to `online-cursor-and-crafting.items`; give back manually |
| Ender chest (`ec`) | player file `EnderItems` | live | emptied | no | automatic |
| Experience | player file `XpLevel`, `XpP`, `XpTotal`, `Score` | live | reset to 0 (`wipe.vanilla.experience`) | no | automatic |
| Advancements / statistics | `world/players/advancements|stats/<uuid>.json` | live | deleted if enabled (off by default) | no | automatic |
| Mount ridden at logout with storage (donkey/llama chest) | player file `RootVehicle` | live | removed together with the inventory | no | automatic |
| ExoVaults markers in player data | player file `BukkitValues` `exovaults:vault_revision/vault_dirty` | live | removed so ExoVaults adopts the empty state instead of "restoring" an old checkpoint | no | automatic |
| Money, shards, tokens, lightcoin (every currency) | ExcellentEconomy 2.8.0 (`ExcellentEconomyAPI` service) | live | `setBalanceAsync(uuid, currency, Start_Value or 0)`; works for offline users through the plugin's own cache | no | automatic (`balances.json`) |
| Balance changes during a wipe | ExcellentEconomy `ChangeBalanceEvent` | live | cancelled for locked accounts (payments, island deposits, shop payouts) | — | — |
| Mail | EssentialsX 2.22.1 (`IEssentials#getUser`) | live | mail list cleared | no | manual (userdata copy) |
| Essentials balance | EssentialsX | live | only when Essentials is the Vault economy provider (`money: auto`); on dogo ExcellentEconomy provides Vault, so it is left alone and the preview says so | no | manual |
| Owned island: blocks, bank (money/shards/exp), value, upgrades, OneBlock progress, shared island chest, settings | ESkyblock 2.0.0 (`IslandLifecycle#delete`, same path as `/is admin disband`) | live | island disbanded, region cleared, rows removed, raids/invites cleared, logged in ESkyblock's `admin.log` | **yes**: members and co-ops lose the island; requires `--shared` or `--keep-shared` | manual (`islands.db.before` + region files) |
| Membership / co-op on other islands | ESkyblock (`IslandManager#purgeMembership`) | live | removed, island kept | no | manual |
| Player vaults (`/pv`, all 5) incl. stored inventory checkpoint | ExoVaults 1.0.0 `data/<uuid>.dat(.bak/.tmp/.corrupt-*)` | startup | files moved into the backup | no | automatic |
| Box key balances, unclaimed box rewards, unfinished openings | ExoBoxes 1.0.0 `database.db` | startup | rows deleted | no | automatic |
| Generator cubes + stored ores | ExoCubes 1.0.0 `exocubes.db` | startup | cube rows and items deleted (no "removed" state exists); cube item handles become invalid | no | automatic |
| Wood cubes + stored logs | ExoWoodCube 1.1.1 `exowoodcube.db` | startup | cubes set `REMOVED` (token cleared, version bumped), stored logs deleted | no | automatic |
| Farm cubes + stored kelp, cube tokens | ExoFarmCube 1.1.0 `exofarmcube.db` | startup | cubes `REMOVED`, kelp counts 0, issued tokens `REVOKED` | no | automatic |
| Daily rewards cycle, claims | ExoDaily 1.0.0 `data.db` | startup | rows deleted | no | automatic |
| Bonus spins, unclaimed spinner rewards, undelivered spins | ExoDailySpinner 1.0.0 `data.db` | startup | rows deleted | no | automatic |
| Unclaimed sell payouts/items, escrowed items, owned boosters, daily earning caps | ExoEconomy 1.0.0 `exoeconomy.db` | startup | rows deleted (global boosters kept) | no | automatic |
| Multitool shards, multitools, daily counters, sell caps | ExoMultiTools 1.1.0 `data.db` | startup | tools `QUARANTINED`, player row (shards) and counters deleted | no | automatic |
| Undelivered night-market purchases | ExoNightMarket 1.0.0 `data.db` | startup | deliveries cancelled (`cancelled_at`), queued purchases `VOIDED` | no | automatic |
| Quest points, current daily quests | ExoQuests 1.0.0 `exoquests.db` | startup | rows deleted | no | automatic |
| Skill levels and XP | ExoSkills 1.0.3 `skills.db` | startup | rows deleted | no | automatic |
| Upgradeable tools | ExoTools 1.0.0 `exotools.db` | startup | tools `REVOKED` | no | automatic |
| Wand token balance, undelivered wand items | ExoWands 1.0.0 `data/data.db` | startup | rows deleted | no | automatic |
| Backpacks, pending rewards, crate pity, kill progress, boss/crate stats | LightZone 2.0.0 `data/lightzone.db` (SQLite only) | startup | backpacks `invalidated=1` (contents kept for restore), other rows deleted/reset | no | automatic |
| Chunk collectors + stored items | ChunkCollector 1.1.2 `collectors.db` | startup | collector rows deleted (owner read from the snapshot header) | no | automatic |
| Player shops (listings and stock), undelivered parcels | EPlayerShop 1.0.9 `shops.db` | startup | rows deleted (`$.owner` in the JSON) | buyers of an unfinished trade (blocks) | automatic |
| Gen chest storage, placement limit | EGen 1.5.0 `machines.yml`, `chestdata.yml`, `playerdata.yml` | startup | owned chests emptied, limit entry removed | no | automatic (`restore.yml`) |
| Spawner tier unlocks, kill progress | ExoSpawners 2.1.0 `players.yml` | startup | entry removed | no | automatic |
| Unfinished token-shop purchases | ExoTokenShop 1.0.0 `tokenshop.db` | startup | **check only** (tokens are an ExcellentEconomy currency) | blocks | — |

### What blocks a single-player wipe

A step refuses to run (and the account stays locked) when the plugin has an unfinished transaction for the target
that its own startup recovery would complete, because another player may already have paid or received something:

| Plugin | Blocking states |
|---|---|
| ExoCubes | ledger `PENDING` |
| ExoWoodCube, ExoFarmCube | ledger `PENDING`, `AMBIGUOUS` |
| ExoTools | ledger `PENDING`, `AMBIGUOUS`, `REFUND_FAILED` |
| ExoDaily | claims `DELIVERING`, `UNCERTAIN` |
| ExoDailySpinner | pending rewards `CLAIMING`, `UNCERTAIN`; spins `DELIVERING`, `UNCERTAIN` |
| ExoEconomy | transactions `PENDING`, `UNCERTAIN` |
| ExoMultiTools | transactions / milestone claims `PENDING`, `UNCERTAIN` |
| ExoNightMarket | transactions `RESERVED`, `REVIEW`, `REFUNDING` |
| ExoQuests | purchases `PENDING`, `DELIVERING`, `NEEDS_REVIEW` |
| ExoTokenShop | `PENDING`, `DEBITED`, `REWARD_ATTEMPTED`, `REFUNDING`, `NEEDS_REVIEW` |
| ExoWands | build jobs `RUNNING` |
| ChunkCollector | transactions not `DONE/ABORTED/RESOLVED_*` |
| EPlayerShop | journal entries not `DONE/ABORTED/RESOLVED` that mention the player or one of their shops |

Resolve them with that plugin's own recovery/admin commands, then `/wipe retry <job>`. A season reset does not block
on these (everyone is reset); they are listed as warnings in its preview and left as they are.

### Kept on purpose

* Transaction, audit, ledger and journal tables of every plugin (the only trail for investigating where items went).
* LuckPerms ranks and permissions, Essentials homes/nicknames, ESkyblock name cache, ExoSkills name cache,
  ExoBoxes preferences, ExoEconomy preferences and placed-block markers, ExoQuests completions and point ledger.

## Not covered

| Plugin / data | Why |
|---|---|
| ItemsAdder `storage/players` (2 files) | format not documented and not inspected; ItemsAdder items held by players are removed with the inventory |
| Pouches 1.0.0 `pouches.db` | only stores redemption records; pouches themselves are items and are removed with the inventory |
| ExoBlackSmith 1.0.0 | no per-player storage found (only `data/temp-blocks.yml`) |
| MythicMobs `data/players` | mob/skill variables, no possessions |
| TAB, DeluxeMenus, FancyNpcs/Holograms, Multiverse, FAWE, ProtocolLib, ViaVersion/ViaBackwards, PlaceholderAPI, nightcore, Vault, LuckPerms | no player possessions or progress (LuckPerms deliberately preserved) |
| ShardBoost, ExoEnchants, ExoArmorTrims, ExoLootBoxes, AxHoes, AxSpawners, EnhancedGenerators, ExoDeaths | data folders or source only; **jar not installed**. Not wiped. Reinstalling one brings its old data back |
| Additional profiles | no profile plugin is installed; data is keyed by UUID everywhere. The preview lists other accounts that used the same name and the offline-mode UUID if it has data; add them with `+alt:` |
| Auction house | none installed; EPlayerShop and ExoNightMarket are covered |
| Items in the world outside the target's island, items already given to other players | not traceable |
| MySQL storage | only the SQLite layouts dogo uses; if LightZone is switched to MySQL the integration reports itself unavailable |

If a plugin is updated, check `/wipe integrations` and run the test suite against the new version's schema before
relying on a wipe (an unexpected schema makes the step fail instead of guessing).
