# Reference

## Commands

### Player commands

| Command | Permission | What it does |
|---|---|---|
| `/sell` | `exoeconomy.sell` | Opens the sell menu. Requires an allowed world and game mode (creative is excluded by default), a connected economy and healthy storage. |
| `/sell claim` | `exoeconomy.sell.claim` | Collects items returned while your inventory was full, or released by an admin. Also delivered automatically 3 seconds after joining. |
| `/boosters` | `exoeconomy.boosters` | Opens the booster shop: tiers, your effective multipliers, balance, and active boosters with timers. |
| `/exoeconomy` (`/exoeco`) | `exoeconomy.use` | Shows help. |
| `/exoeconomy info` | `exoeconomy.use` | Shows economy connection, currency and precision, storage health, boosters, prices, provenance and integrations. |
| `/shardnotify [chat\|actionbar\|sound\|all\|status] [on\|off]` (`/rewardnotify`) | `exoeconomy.notify` | Turns your own reward chat line, action bar or sound on or off. With no arguments it toggles all three. Saved across restarts. Needs `notifications.allow-player-toggle: true`. |
| `/shards [player]` | `exoeconomy.shards` (`.others` to look up others) | **Optional, off by default.** Read-only balance through ExcellentEconomy. It is never registered if the name is already taken, for example by ExcellentEconomy's own currency command. |

`/sell` can clash with another plugin's `/sell`. `/exoeconomy:sell` always works, and Bukkit's `commands.yml` can alias it.

### Admin commands

All need `exoeconomy.admin` or the listed child permission.

| Command | Permission | What it does |
|---|---|---|
| `/exoeco reload` | `.admin.reload` | Reloads all five files. If anything is invalid, nothing changes and every problem is listed. `economy.*` and `storage.file` need a restart. Open confirmations and armed sales keep the values the player saw. |
| `/exoeco booster list [player\|global]` | `.admin.boosters` | Lists active, paused and queued boosters. |
| `/exoeco booster give <player\|global> <tier>` | `.admin.boosters` | Free grant of a tier, following the stacking rules. |
| `/exoeco booster givecustom <player\|global> <activity> <multiplier> <duration>` | `.admin.boosters` | Free grant of a custom booster. |
| `/exoeco booster cancel <id> [refund]` | `.admin.boosters` | Cancels a booster. With `refund`, returns the original purchase price. At most one successful refund per purchase (a failed attempt can be retried). |
| `/exoeco tx list [uncertain\|all\|recent] [player]` | `.admin.transactions` | Lists journal entries; the default is pending and uncertain. |
| `/exoeco tx inspect <id>` | `.admin.transactions` | Shows details, balance before and after, child refunds or payouts, and the available resolutions. |
| `/exoeco tx resolve <id> <action> confirm` | `.admin.transactions` | Resolves an UNCERTAIN transaction. Booster purchase: `charged`, `not-charged`, `refund`. Sale: `paid`, `not-paid` (returns the held items), `pay` (pays now). Refund or payout: `paid`, `not-paid`. |
| `/exoeco claims list [player] [review]` | `.admin.claims` | Lists stored items. |
| `/exoeco claims approve <id>` | `.admin.claims` | Releases a REVIEW claim to its player. |
| `/exoeco claims discard <id> confirm` | `.admin.claims` | Deletes a claim; logged with its contents. |
| `/exoeco audit` | `.admin.audit` | Checks prices against the server's real recipes. |
| `/exoeco storage` | `exoeconomy.admin` | Retries storage after a failure. |

All admin commands tab-complete subcommands, players, tiers, booster ids and actions.

## Permissions

| Node | Default | |
|---|---|---|
| `exoeconomy.use`, `exoeconomy.sell`, `exoeconomy.sell.claim`, `exoeconomy.boosters` | everyone | |
| `exoeconomy.boosters.buy.personal`, `exoeconomy.boosters.buy.global` | everyone | Buy boosters per scope; tiers can also require their own `permission`. |
| `exoeconomy.notify` | everyone | `/shardnotify` |
| `exoeconomy.earn.mining`, `.earn.woodcutting`, `.earn.farming`, `.earn.slaying`, `.earn.<custom>` | everyone | Configurable per activity in `rewards.yml`; set it to `""` to require no permission. |
| `exoeconomy.shards` | everyone | |
| `exoeconomy.shards.others` | op | |
| `exoeconomy.admin` | op | Includes `.admin.reload`, `.admin.boosters`, `.admin.transactions`, `.admin.claims`, `.admin.audit`. |

## Configuration files

| File | Contents |
|---|---|
| `config.yml` (integrations) | `exocubes` / `exowoodcube`: `enabled`, `activity` (mining / woodcutting), `reward-multiplier`. Blocks mined inside cubes pay that activity's reward for the block × the multiplier, once per block. |
| `config.yml` | Currency id and precision, storage, optional `/shards`, selling (**`currency`**: `vault` (default), `excellenteconomy:<id>` or `shards`; **`price-multiplier`** (default 100, applied to every `prices.yml` price); game modes, worlds, max payout, confirm window, crash recovery mode, selling boosters, ExoSkills bonus, multiplier cap), integrations. |
| `rewards.yml` | Per activity: enabled, permission, worlds, game modes, daily cap, rewards per material or entity. Mining: `reward-generated-blocks`. Farming: `allow-fertilized`, `stem-scan-limit`. Slaying: `origins.{spawner,bred,summoned,unknown}`, `blocked-spawn-reasons`, `reward-players`, `exospawners-stack-kills`. Woodcutting: log rewards (placed logs never pay). Also notifications (action bar, chat with optional `interval-seconds` batching, sound, `allow-player-toggle`, flush interval), `provenance.forget-when-block-changes`, and a total daily cap. |
| `boosters.yml` | Stacking rules, time model, announcements, menu layout, tiers. |
| `prices.yml` | Shards per item for every 26.1 block item, plus common non-block drops. `false` with a comment means not sellable. |
| `messages.yml` | Every player-facing text and menu lore. Supports `&` codes and `&#RRGGBB`. Missing keys fall back to the defaults. |

Loading checks materials, entity types, game modes, multipliers (1 to 100), prices (0 to 10¹²), durations, slots and the currency precision. All problems are reported at once. Unknown materials (for example, from a different Minecraft version) are warnings, not errors.

## Booster rules

| Situation | Default | Options |
|---|---|---|
| Same tier already running (or paused) for the owner | **EXTEND**: duration added, capped by `max-remaining-duration` (6h) | QUEUE, STACK, REJECT |
| A different booster covering an overlapping activity is running | **STACK**: both run | QUEUE (starts automatically when the other ends), REJECT |
| Combining multipliers | **ADDITIVE**: 1 + Σ(m−1) | MULTIPLICATIVE, HIGHEST |
| Overall cap | `max-total-multiplier: 4.0` (personal and global together) | |
| Personal time while the owner is offline | **CONTINUE** | PAUSE |
| Time while the server is stopped | **CONTINUE** | PAUSE (clean shutdowns only; crash downtime always counts) |

Other rules:

- **Global boosters** apply to everyone online and to anyone who joins while they run.
- **Selling:** `all` never covers selling. Selling is only boosted by tiers with `activity: selling`, and only when `selling.boosters.enabled` is true.
- **What is never multiplied:** transfers, refunds and admin balance changes are never multiplied by boosters.
- **Expiry timing:** expiry is checked by one task every second. Multipliers check the expiry timestamp exactly, so an expired booster never pays even before that task runs.

## PlaceholderAPI

Identifier `exoeconomy`:

| Placeholder | Value |
|---|---|
| `%exoeconomy_multiplier%` / `%exoeconomy_multiplier_<activity>%` | Combined booster multiplier (`1.5`) |
| `%exoeconomy_boosters_personal%` / `%exoeconomy_boosters_global%` | Number of active boosters |
| `%exoeconomy_booster_time_<activity>%` | Longest remaining time covering the activity |
| `%exoeconomy_balance%` | Balance formatted by ExcellentEconomy. Only for an online player on the server thread; otherwise empty. Prefer ExcellentEconomy's own placeholders. |
| `%exoeconomy_currency%` | The configured currency id |

## Developer API

```java
ExoEconomyAPI api = Bukkit.getServicesManager().load(ExoEconomyAPI.class);   // softdepend: [ExoEconomy]
```

| Method | Notes |
|---|---|
| `isEconomyAvailable()`, `currencyId()`, `balance(Player)`, `format(BigDecimal)` | Read through ExcellentEconomy. |
| `registerActivity(plugin, "fishing")` | Then `reward(player, "fishing", "COD", null)` uses `rewards.yml` `activities.fishing.rewards.COD`, or pass an explicit base amount. The normal pipeline applies: eligibility, `ShardRewardEvent`, boosters, caps, batching, notification. Boosters can target `activity: fishing`. |
| `boosterMultiplier(uuid, activity)`, `boosters(uuid)`, `globalBoosters()` | Thread-safe. |
| `grantBooster(scope, owner, activity, multiplier, durationMs, reason)` | Free grant, following the stacking rules. |
| `cancelBooster(id)` | Cancels without refund. |
| `rewardBlockBreak(player, material)` | For plugins that break blocks themselves (regenerating mines, cube plugins that cancel the BlockBreakEvent): pays the configured farming, woodcutting or mining reward with the normal eligibility, boosters, caps and messages. Provenance is not checked, so call it only for legitimate breaks, and never for breaks ExoEconomy already saw as an uncancelled event. |
| `vanillaPrice(Material)` | Price in the selling currency (`prices.yml` × `price-multiplier`). |
| `registerPriceProvider(plugin, (player, item) -> Optional<BigDecimal>)` | Price your own custom items in `/sell`. Without a provider they are always rejected. |
| `registerItemFilter(plugin, (player, item) -> Optional<String>)` | Veto selling any item, such as quest, soulbound or event items, with a reason shown to the player. |

Registrations are removed when your plugin disables. There is no API to move Shards directly: use ExcellentEconomy's own API for that, so every balance change stays in one place.

### Events

All fire on the server thread.

| Event | When |
|---|---|
| `ShardRewardEvent` (cancellable, base amount editable 0 to 1,000,000) | Every eligible activity reward, before boosters and caps. |
| `BoosterPurchaseEvent` (cancellable) | Purchase confirmed, before charging. |
| `BoosterActivatedEvent` | A booster starts, is extended, or leaves the queue. |
| `BoosterExpiredEvent` | A booster ends. |
| `SellRequestEvent` (cancellable) | Sale confirmed, before items are taken or Shards paid. |
| `SaleCompletedEvent` | After ExcellentEconomy confirmed the payout. |
