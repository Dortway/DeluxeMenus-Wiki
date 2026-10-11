# Design notes

## Ownership of Shards

ExcellentEconomy is the only ledger. ExoEconomy calls `ExcellentEconomyAPI` (from Bukkit's ServicesManager) with the currency configured in `economy.currency-id`. It never writes ExcellentEconomy's database, never registers an economy provider, never uses Vault and never caches balances. Balances are read live when needed, for example in the menu info item and before a withdrawal. Every Shards movement ExoEconomy makes is one of these:

| Movement | API call | Journaled |
|---|---|---|
| Activity rewards | `deposit(Player, currency, amount)`, batched every 2 s | No; ExcellentEconomy's operations log records them |
| Sale payout | `deposit(Player, ...)` | Yes (`SALE`) |
| Booster purchase | `withdraw(Player, ...)` | Yes (`BOOSTER_PURCHASE`) |
| Refund, admin payout | `deposit(Player, ...)` online, or `depositAsync(UUID, ...)` offline | Yes (`REFUND`/`PAYOUT`, one live per parent) |

## What ExcellentEconomy 2.8.0 guarantees, and what ExoEconomy adds

From the API source:

- **Results:** synchronous calls return `boolean`; async calls return `CompletableFuture<OperationResult>` (`SUCCESS`/`FAILURE`).
- **No transaction ids or idempotency keys:** a call cannot be safely repeated or queried later.
- **Insufficient funds:** behaviour is not documented. `CoinsUser.removeBalance` was not inspected.
- **Limited currencies:** may clamp silently (`limitIfNeeded`).
- **Unknown currency or a reset in progress:** calls return `false`/`FAILURE`.
- **Silent default context:** the default `OperationContext` ("API") silences player, executor and console messages, but writes ExcellentEconomy's operations log.

What ExoEconomy adds:

1. **Server-thread calls:** all synchronous calls run on the server thread for online players. The gateway refuses other threads.
2. **Balance checks around every call:** the balance is read before and after each call (within the same tick):
   - A withdrawal is never attempted for more than the balance.
   - A deposit that would exceed a maximum balance is refused instead of clamped.
   - Results are classified as SUCCESS (with "verified" set if the balance moved by exactly the amount), REJECTED (refused and unchanged), or UNCERTAIN (refused or threw, but the balance changed, or the balance was unreadable).
3. **Rounding:** amounts are BigDecimal and are rounded down to the currency precision (0 or 2 decimals; `economy.scale`) before conversion to `double`. Fractions are never rounded up.

## Transaction sequence (purchases and sales)

```
server thread   per-player lock, storage healthy?, economy available?, amount valid?
storage thread  INSERT transaction PENDING  (+ escrow SELLING snapshot for sales)   one SQLite transaction, fsync
server thread   re-validate (online, menu open, contents unchanged, tier still offered, stacking allows)
                take sold items out of the menu → ExcellentEconomy call → classify
storage thread  UPDATE → COMMITTED / FAILED / UNCERTAIN  (+ booster row / remaining escrow / held-items claim)
```

**Crash windows.** ExcellentEconomy and SQLite never share a transaction:

| Crash point | Money moved? | Record after restart | Handling |
|---|---|---|---|
| Before the PENDING insert is durable | no | none | Nothing happened. |
| After PENDING, before the economy call | no | PENDING → **UNCERTAIN** | Admin resolves `not-charged` / `not-paid`. On a clean shutdown these are marked ABORTED automatically. |
| After the economy call, before the final write | yes¹ | PENDING → **UNCERTAIN** | Admin resolves `charged` (activates the booster under its original id) or `paid`. The sale's pre-sale items are held as a REVIEW claim. |
| After the final write | yes¹ | final state | Nothing to do. |

¹ ExcellentEconomy saves user data on its own schedule, so a crash can also roll back its last change. That is why nothing is ever retried automatically: an admin compares the journal (balance before and after) with ExcellentEconomy's `operations.log`.

**Other protections:**

- **Repeated requests:** the per-player lock refuses a second transaction while one is in flight (double clicks, macros, two menus).
- **Refunds and payouts:** a partial unique index `(parent, kind)` over live states makes each happen at most once per parent. A failed refund frees the slot for an admin retry.
- **Admin resolutions:** these only move a record that is still UNCERTAIN (compare-and-set in SQL), so two admins cannot resolve the same one twice.
- **Post-payment failure:** if activation fails after payment (the stacking rules changed in the meantime), the purchase is refunded automatically through the same idempotent refund path.

## Selling safety

**Click policy.** Every click is cancelled at LOWEST priority and re-checked at HIGHEST, so other plugins cannot un-cancel it.

Allowed:
- Pickup, place and swap in deposit slots 0–44.
- Shift-click from the menu back to the player.
- Number-key and offhand swaps on deposit slots.
- Drops from deposit slots or the cursor (the item belongs to the player).
- Normal moves inside the player's own inventory.

Refused:
- Anything touching the control row.
- Collect-to-cursor (double click).
- Bundle actions, creative clone and unknown actions.
- Drags touching the control row.
- Every click while a sale is processing.

A shift-click from the player's inventory into the menu is performed by ExoEconomy itself: deposit slots only, with the source slot written directly. Menu icons carry a marker tag, so nothing can stack onto them. Creative and spectator players cannot open `/sell` (configurable), and switching to a disallowed game mode closes the menu.

**Two-step confirmation.** The first click arms a quote for the exact contents (fingerprinted with full item data). The second click within `confirm-window-seconds` sells exactly that quote; any change disarms it. A reload cannot change an armed quote.

**What can be sold.** Only items equal to `new ItemStack(type)` (Paper data components), plus items priced by an API provider. Anything else is rejected and returned:
- custom names, lore or enchantments
- any persistent data from any plugin
- custom model data or damage
- stored contents: filled shulker boxes, bundles, block entity data

API filters can veto even plain-looking items.

**Returning items.** Close, disconnect, kick, death, game-mode change and shutdown all return every deposited item. Whatever does not fit is stored as a CLAIMABLE claim (`/sell claim`); nothing is dropped or deleted. Claims are deleted in the database before delivery, so they can never be delivered twice.

**Escrow.** Deposit contents are written to the escrow table (Paper `serializeAsBytes`, which keeps item identity) one tick after any change. After a crash they become REVIEW claims by default.

**Default prices.** Defaults are derived from recipes, so no crafting, smelting or stonecutting recipe produces more sell value than its inputs (verified by the generator on 2,142 modelled recipes). `/exoeconomy audit` re-checks against the server's real recipe list at every startup.

## Activity rewards and provenance

**Pipeline.** Handlers run at MONITOR with `ignoreCancelled`: anything a protection plugin cancels pays nothing and changes no provenance. Each reward goes through:

1. eligibility (enabled, permission, world, game mode)
2. `ShardRewardEvent`
3. booster multiplier
4. daily caps (counted on accrual, including undeposited rewards)
5. the per-player accumulator

One global task deposits the totals every `flush-interval-ticks`; there is no per-player or per-booster task.

| Activity | Pays when | Never pays for |
|---|---|---|
| Mining | The block's provenance is natural, or generated if `reward-generated-blocks`. | Player-placed blocks, blocks pushed or fallen from placed ones, any block while provenance is still loading at startup. |
| Farming (crops) | The crop is fully grown. Bone-meal-grown crops only if `allow-fertilized`. | Immature crops. |
| Farming (cane, cactus, bamboo, kelp) | Each grown segment broken (up to `stem-scan-limit`). | The planted base. |
| Farming (melons, pumpkins) | Grown, not placed. | Placed melons and pumpkins. |
| Slaying | A player killed the mob and its origin is allowed. Origins: spawner (spawn reason, Paper `fromMobSpawner()`, ExoSpawners tag), bred, summoned, unknown. All four are off by default. | Mobs already paid (persistent tag), players (unless `reward-players`). |

**Provenance tracking.**
- **Kinds:** PLACED, GENERATED or FERTILIZED, indexed by world and chunk in memory.
- **Persistence:** saved to SQLite in batches (up to 5,000 rows every flush). Pending changes during the startup load win over loaded rows.
- **Events tracked:** place and multi-place, liquid form (cobblestone, stone, basalt and obsidian generators), entity form, growth, tree structure growth, spread, fertilize, pistons (origin moves with the block), falling blocks (origin carried on the entity), explosions, burn, fade, decay, and enderman or ravager changes.

**What the server cannot reliably identify.** These are documented, not solved:
- Blocks changed without Bukkit events: WorldEdit/FAWE, schematic pastes, some plugin generators.
- The other half of doors, beds and tall plants when broken indirectly. A stale mark stays until the position is overwritten.
- Changes in the last batch interval before a crash (about 2 s): a placed block can later count as natural.
- Mobs spawned before ExoEconomy was installed have no recorded origin; they are "unknown", denied by default.
- Plugins that break blocks for a player by firing BlockBreakEvent (vein miners, tree fellers) are treated as that player's mining.
- Automated farms are excluded only because they fire no player event. Player-finished mob grinders count as slaying if the origin is allowed. Use `blocked-spawn-reasons` or caps to limit them.

## ExoSkills integration (verified against ExoSkills 1.0.1)

ExoSkills awards **skill XP**, not Shards. Both plugins listen to the same mining, farming and slaying events, but they reward different things, so there is no double Shards reward. ExoEconomy mirrors ExoSkills' handling of ExoSpawners: managed mobs (`exospawners:managed_mob`) are skipped in EntityDeathEvent and paid only through `StackMobKilledEvent`, and only if `exospawners-stack-kills` is enabled.

**Sell bonus.** ExoSkills' only public integration point is the `net.exoarmor.SaleMultiplierAPI` service. It registers it only while ExoArmorTrims is installed. With `selling.exoskills-sale-bonus: true`, ExoEconomy:
- calls `multiplier(player, material, 1.0)` through ExoSkills' own registration, which returns the pure milestone bonus for `sales.ores` and `sales.crops` materials (tested against ExoSkills' shipped `Progress.combine`)
- multiplies it with the selling booster (if enabled)
- caps the product at `selling.max-total-multiplier`

`payout = floor(Σ price × amount × min(cap, exoskillsBonus × sellingBooster))`

The bonus is off by default, because ExoSkills' lore describes it as a money-shop bonus.

**Activity rewards.** ExoSkills exposes no API for skill levels; its levels and storage are package-private and it fires no events. ExoEconomy therefore does **not** scale activity Shards by skill level. Activity rewards are `base × booster multiplier (capped)`. If you want skill-scaled Shards, add a public method to ExoSkills, for example `public int level(UUID, Skill)` (see VERIFICATION.md).

**ESkyblock.** ESkyblock is only used by ExoSkills for its own XP bonus; ExoEconomy does not need it. Island protection works through cancelled events.

## Threading and performance

**Server thread:** event handlers, menus, the economy calls and all booster and reward state.

**Storage thread:** one dedicated writer with a bounded queue, configurable via `storage.write-queue-capacity`. When the queue is full, the request is refused and the player sees a message; the server thread never blocks.

**Startup:** loading boosters, daily counters and recovery is synchronous, before players can interact. Provenance loads asynchronously.

**PlaceholderAPI:** reads an immutable booster snapshot (thread-safe).

**Repeating tasks:** two in total:
- reward flush plus batched persistence, every `flush-interval-ticks`
- booster expiry plus menu refresh, every 20 ticks

**Not supported:** Folia (ExcellentEconomy does not support it either). Multi-server or shared databases are not implemented or tested; boosters and journals are per server.
