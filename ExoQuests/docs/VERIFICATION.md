# ExoQuests in-server verification checklist

The core logic is unit-tested; everything below needs a real Paper server and must be checked by hand
before production. Use a test server with a copy of your plugins (protection, island, generator,
stacker). Record the result of each line. Unless stated otherwise, test as a non-op survival player
("player") and an op ("admin").

Tips: `/exoquests reset <player>` rerolls today's quests until you get the one you need; `quests.yml`
`weight` can be raised temporarily for the quest under test; `sqlite3 plugins/ExoQuests/exoquests.db`
lets you inspect `daily_assignments`, `point_ledger`, `purchases` and `placed_blocks` (stop the server or
open read-only).

## 1. Startup, configuration and reload

- [ ] First start creates the five yml files and `exoquests.db`; console shows "Applied database migration 1" and the enabled summary.
- [ ] Second start: no migration message; no warnings.
- [ ] Break `shop.yml` (price `5`), `/exoquests reload` → "reload failed with 1 problem(s)", console lists `shop.yml.items.<id>.price`; shop still works with old prices.
- [ ] Duplicate a quest id in `quests.yml` → reload rejected ("duplicate key").
- [ ] Invalid material/entity/enchantment names → reload rejected with the path.
- [ ] Fix files → reload succeeds; an open shop menu reopens with new data; an open confirm menu closes.
- [ ] Start with an invalid config → plugin disables itself with the problem list (no partial operation).

## 2. Menus (do every step for /quests, /questshop and the confirm menu)

- [ ] `/quests` shows exactly 3 quests with category, objective, progress number, bar, percent, reward and status; info head shows balance, x/3 completed and the reset countdown (ticks down every second); shop button opens the shop; close button closes.
- [ ] Rename the menu titles in `menus.yml` and reload: menus still work (identification is not title-based).
- [ ] Item theft attempts — none may move an item into or out of the menu, and nothing may appear in the player's inventory:
  - [ ] left/right click, shift-click on menu items
  - [ ] shift-click an item **from the player inventory** while a menu is open
  - [ ] drag an item across menu slots (single and multi-slot drag)
  - [ ] number keys 1–9 over menu slots and over own inventory slots
  - [ ] F (offhand swap) over menu slots
  - [ ] double-click on an own item matching a menu item (collect to cursor)
  - [ ] Q / Ctrl+Q over menu slots
  - [ ] middle click (creative admin)
  - [ ] clicking outside the window with an item on the cursor
- [ ] Spam-click the shop button: one menu opens, no errors.
- [ ] Rapid page flipping in a multi-page shop (add >28 rewards): page numbers and buttons correct.
- [ ] `style.unicode: false` → fallback icons/bars appear everywhere.

## 3. Quests, completion and reset

- [ ] Complete a quest → chat message, action bar, sound; balance increases exactly once by the reward (check `point_ledger`: one `quest:<id>` row).
- [ ] Keep doing the action after completion → no further points.
- [ ] Reconnect and restart the server → same 3 quests, progress kept (up to the flush interval).
- [ ] Set `reset.time` to 2 minutes from now and reload; at that minute online players get "quests have reset", new quests, zero progress, unchanged balance.
- [ ] A player offline during the reset joins later → new day's quests.
- [ ] `/exoquests reset <player>` (online and offline) → new quests, points kept, audit row written.
- [ ] Creative/spectator player does the actions → no progress. Disallowed world → no progress and `/quests` shows the note.
- [ ] Remove `exoquests.progress` from a player → no progress.

## 4. Mining and generators

- [ ] Cobblestone from a lava/water generator counts 1 per block, also with Fortune.
- [ ] Place cobblestone and break it → no progress; repeat 20 times → still none.
- [ ] Place cobblestone, push it with a piston, break it → no progress. Restart the server between placing and breaking → no progress.
- [ ] Silk-touch a diamond ore, place it, break it → no progress. Natural/generator diamond ore with Fortune III → 1 per block.
- [ ] Deepslate variant of an ore counts for the same quest.
- [ ] With your ore-generator plugin: every generated ore/deepslate block counts; note any material it produces that is not in `quests.yml`.
- [ ] Explode a placed ore with TNT, let the generator refill the spot, mine → counts.

## 5. Farming

- [ ] Wheat/carrots/potatoes/beetroots/nether wart: breaking a fully grown crop counts 1; breaking an immature one counts 0; bone-mealed to maturity counts.
- [ ] Sugar cane 3 tall, player planted the bottom: break the middle → +2 (middle and top). Break the bottom of a 3-tall cane → +2 (bottom is placed). Break the sand under a 3-tall cane → +2.
- [ ] Place cane on top of cane manually (2 placed segments), break → +0.
- [ ] Cactus and bamboo: same behaviour; bamboo up to 16 tall counts every grown segment.
- [ ] Melons/pumpkins grown from a stem count; a placed melon block (crafted or silk-touched) does not, including after a restart.
- [ ] Piston/observer cane or melon farm running without the player touching it → no progress.

## 6. Trees and logging

- [ ] Plant an oak sapling, let it grow naturally → +1 oak tree. Bone-meal another → +1.
- [ ] Plant a 2x2 dark oak/jungle/spruce → +1 tree (not 4).
- [ ] Planter offline while the tree grows (another player keeps the chunk loaded) → credited when `credit-offline-planter: true` (check `daily_assignments`), not credited when false.
- [ ] `credit: BONEMEALER_IF_PRESENT`: player B bone-meals player A's sapling → B gets credit.
- [ ] Logs of a grown tree count for the logging quest; a placed log (and a placed-then-stripped log) does not.
- [ ] Sapling placed in creative grows → no credit.

## 7. Entities and items

- [ ] Breeding: feed two cows → +1 per calf; breeding blocked by island protection → no progress.
- [ ] Kills: melee and bow kills count; mob that dies from fall/lava after a player hit does not; spawner mob does not; with your mob stacker, killing a stack counts 1.
- [ ] Shearing a sheep counts 1; dispenser shearing does not.
- [ ] Fishing: each catch counts 1 (any-catch quest and cod/salmon quests).
- [ ] Eggs: pick up chicken-laid eggs → counts; drop 16 eggs and pick them up again → no progress; dropped eggs do not merge into laid eggs; hopper collection → no progress.
- [ ] Crafting: craft bread one at a time, with shift-click (full and nearly full inventory), number key into an empty and an occupied hotbar slot, Q on the result; progress equals bread actually received.
- [ ] Smelting: taking iron ingots from a furnace (click and shift-click) counts the amount taken; hopper extraction does not.

## 8. Protection plugins

For WorldGuard regions, your island plugin (visitor on another island) and any claim plugin:

- [ ] Breaking/placing blocked blocks, harvesting blocked crops, breeding/killing/shearing blocked mobs → no progress and no placement record change.
- [ ] Allowed actions on the player's own island count normally.

## 9. Shop and transactions

- [ ] Each default reward shows the correct preview (Efficiency IV book shows the stored enchantment; pickaxe shows Efficiency V, Unbreaking III), price, balance and affordability.
- [ ] Buy torches with exactly 10 points → balance 0, 16 torches received, `purchases` row `DELIVERED`.
- [ ] Not enough points → message, no charge, confirm menu not opened.
- [ ] Full inventory → "not enough inventory space", no charge.
- [ ] Open confirm, admin changes the price (`/exoquests shop setprice`) → clicking confirm says the confirmation expired / price changed; no charge.
- [ ] Open confirm, wait longer than `confirm-timeout-seconds`, confirm → expired, no charge.
- [ ] Spam the confirm button (autoclicker / macro) → exactly one purchase.
- [ ] Two clients on the same account is impossible; instead buy from two menus quickly (close and reopen) → never more purchases than the balance allows.
- [ ] Reward with `permission:` → locked for players without it; buying is refused.
- [ ] `addhand` with a named, enchanted, custom-model-data, potion and PDC-tagged item → bought copy is identical (compare with `/data get entity @s Inventory` or an item inspector); the admin's held item is unchanged.
- [ ] `additem`, `addcommand`, `remove`, `setprice`, `list`: invalid ids, prices (9, 1001, 10.5, abc), materials and amounts are rejected; edits survive a restart; comments are not duplicated.
- [ ] Command reward: placeholders filled; command with an unknown placeholder rejected; a player whose name does not match the pattern gets "can't be delivered" and is not charged.

## 10. Crash and recovery drills

- [ ] Kill the server process (`kill -9`) right after confirming many purchases in a loop; on restart: no duplicated items, every charged purchase is `DELIVERED`, `REFUNDED`, `PENDING` (delivered on join) or `NEEDS_REVIEW`; the console names the review count.
- [ ] `/exoquests recovery list` shows reviewed purchases; `resolve <id> refund` refunds once (second attempt "not found"); `resolve <id> delivered` closes it without changes.
- [ ] Make a command reward fail (unknown command) → purchase `NEEDS_REVIEW`, staff alerted.
- [ ] Disconnect during a purchase (quit immediately after confirm) → delivered or refunded on rejoin.
- [ ] Kill the server mid-quest → at most `progress-flush-seconds` of progress lost; no duplicate completion.

## 11. Performance

- [ ] With many players mining generators, `/timings` or spark shows ExoQuests listeners well below 1 ms per tick and no main-thread database calls (database work runs on `ExoQuests-Database`).
- [ ] `placed_blocks` row count (`SELECT COUNT(*)`) stays proportional to placed tracked blocks, not to blocks mined.
