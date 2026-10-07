# ExoDailySpinner — server test checklist

Run through this on a **test** Paper 1.21.4–1.21.11 server before going live. Use two accounts:

- **A**: a normal player.
- **O**: an operator.

Watch the console while you test, and don't run `/reload confirm` (Bukkit reload).

Tip: set `spins.daily-cooldown: 30s` and `animation.steps: 20` while testing, then `/ds reload`.

## 1. Install and defaults
- [ ] Server starts and logs `Loaded 10 reward(s)`, `Database ready (schema v1)` and `ExoDailySpinner enabled`. There are no errors.
- [ ] `plugins/ExoDailySpinner/` contains `config.yml`, `messages.yml`, `menus.yml`, `rewards.yml` and `data.db`.
- [ ] `/ds`, `/dailyspinner` and `/dailyspiner` all open the 9-slot menu, with the spinner button in the centre (slot 4).
- [ ] Titles and labels show small caps, the cyan/purple/gold theme and the symbols (✦ ◆ ⌛ ✓).

## 2. Spin flow
- [ ] A: click the centre button. The 27-slot spinner opens, with pointers above and below slot 13.
- [ ] Click **Spin Now**. The reel scrolls, slows down smoothly, and the reward lands exactly under the pointers.
- [ ] Ticking sounds, border colour cycling, the landing sound, the title, the particles and the chat result message all appear.
- [ ] The reward is in the inventory exactly once. Epic and legendary rewards are broadcast.
- [ ] Reopen `/ds`. The button shows **On Cooldown** with a live countdown, and clicking Spin in the spinner says when the next spin is ready.

## 3. Inventory exploits (with a menu open, then again mid-spin)
- [ ] Left, right and shift-click on menu items: nothing moves.
- [ ] Number keys 1–9 over menu items and over your own inventory: nothing moves.
- [ ] Double-click an item in your inventory that matches a menu item: nothing is collected.
- [ ] Drag across menu and inventory slots: cancelled.
- [ ] `F` (offhand swap) and `Q` / `Ctrl+Q` (drop): cancelled.
- [ ] In creative mode (O), middle-click and creative clicks on menu items: no copies appear.
- [ ] Shift-click items from your inventory toward the menu: cancelled.
- [ ] After closing, the inventory contains no menu panes or display items.

## 4. Rapid clicks and command spam
- [ ] Click Spin many times very quickly: only one spin starts.
- [ ] Spam `/ds` and `/ds claim`: you get "Slow down", and nothing is duplicated.
- [ ] Click the stored-rewards button rapidly: items are claimed once.

## 5. Closing, disconnecting and dying
- [ ] Close the menu mid-spin (Esc): the same reward is delivered immediately. Reopen: no new spin and no second reward.
- [ ] Press Back mid-spin: same as closing.
- [ ] Disconnect mid-spin, then rejoin: after about 2 seconds you get "Completing your interrupted spin" and the **same** reward, once.
- [ ] Get kicked (`/kick A`) mid-spin, then rejoin: same as above.
- [ ] Die with the spinner open during a spin (e.g. `/kill A` from console): no reward is lost. Item rewards appear in `/ds claim` if you were dead at delivery.

## 6. Full inventory and stored rewards
- [ ] Fill A's inventory completely (e.g. with stone), then `/ds reset A` and spin.
- [ ] You get a "stored safely" message, and **no items are dropped** on the ground.
- [ ] The main menu shows the glowing **Stored Rewards** button with a count.
- [ ] `/ds claim` with the inventory still full: the items stay stored.
- [ ] Free part of the inventory, then `/ds claim`: the inventory fills, and the remainder stays stored with a message.
- [ ] Free more space and `/ds claim` again: everything has been delivered exactly once.
- [ ] Reconnect with stored rewards: you see the reminder message.

## 7. Bonus spins and cooldowns
- [ ] `/ds give A 2`: A is notified.
- [ ] With the daily spin ready: the first spin uses the **daily** spin and bonus stays at 2 (default `DAILY_FIRST`).
- [ ] The next two spins use bonus spins, then the spinner shows the cooldown.
- [ ] `/ds give A 0`, `-1`, `abc`, and an amount above `max-bonus-spins` are all rejected.
- [ ] `/ds reset A` makes the daily spin available again.
- [ ] `/ds reset NeverJoined`: "has never joined".

## 8. Restarts
- [ ] Spin, then `/stop` **while the reel is spinning**. Start again and rejoin: the reserved reward is delivered once.
- [ ] Cooldowns, bonus spins and stored rewards survive a restart.
- [ ] *(Optional, destructive)* Kill the server process with `kill -9` right as a reward lands. On start, check the console and `reconciliation.log` for `UNCERTAIN` entries, then check:
  - `/ds reconcile list` shows the entries;
  - `regrant` queues items into `/ds claim`, and re-runs commands only when you choose to;
  - `dismiss` closes the entry.

## 9. Reloads and validation
- [ ] Set a reward weight to `-1`, `0`, `.nan` or `abc` → `/ds reload` is rejected with the file and path, and the old configuration keeps working.
- [ ] Use an invalid material, a duplicate slot, an uppercase reward ID, broken YAML, or an invalid hex colour → rejected the same way.
- [ ] Fix the errors → `/ds reload` succeeds and open idle menus close.
- [ ] `/ds reload` while A is mid-spin: A's spin finishes normally with its original reward.
- [ ] Empty `rewards: {}` + reload: the spinner shows **Unavailable**, and clicking Spin doesn't use up the daily spin.

## 10. Reward management
- [ ] O holds a renamed, enchanted item with lore and runs `/ds reward addhand test_item 5`: the held item is unchanged, and the reward shows the same name, lore and enchantments in `/ds preview`.
- [ ] `/ds admin` → **Add Held Item** works the same way, with an auto-generated ID.
- [ ] In the admin menu, left/right/shift-click changes weights; pressing the drop key twice removes a reward.
- [ ] `/ds reward additem gold gold_ingot 32 4`, `setweight`, `setrarity`, `remove` and `list` all work, and invalid input is rejected with a message.
- [ ] `/ds reward addcommand cash 5 say hi {player}` as op → no permission (the node isn't granted by default). It works from the console or with `exodailyspinner.admin.rewards.command`.
- [ ] A command reward runs once, with the real player name.
- [ ] The preview chances add up to about 100% and match weight ÷ total weight.

## 11. Permissions
- [ ] A without `exodailyspinner.use` can't open the menu, and admin commands are denied for A.
- [ ] Tab completion only suggests commands the sender may use.
