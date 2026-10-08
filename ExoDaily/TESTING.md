# ExoDaily — manual in-game checklist

Run this on a **test server** (Paper 1.21.11, Java 21) before going live. You need two accounts: an op (**Admin**) and a non-op (**Player**). Give **Player** a large stack of a cheap item such as dirt for inventory tests. For each check, tick the box when what you see matches the expected result.

## Setup

- [ ] The server starts with no ExoDaily errors. The console shows `Storage ready (SQLite, schema v1).`
- [ ] `plugins/ExoDaily/` contains `config.yml`, `messages.yml`, `menus.yml`, `rewards.yml` and `data.db`.
- [ ] Player `/daily` opens a 3-row menu titled `✦ ᴅᴀɪʟʏ ʀᴇᴡᴀʀᴅꜱ`.

## Visual presentation

- [ ] The border is black glass with purple accents in the corners. Border panes show **no tooltip** on hover.
- [ ] Slot 4 (amethyst shard) shows cycle progress: `day 1 of 30`, a 10-segment bar, start and end dates, and "click to view every day".
- [ ] Slot 11 (iron ingot) shows standard info. For a standard player it says "your current tier".
- [ ] Slot 13 (glowing chest) shows `✦ ᴅᴀʏ 1`, "your rewards for today", three `◆` lines with the **actual** assigned rewards (premium lines end in `(locked)` for a standard player), `claimed: 0/1`, `next reset: Xh Ym`, and "➜ click to view and claim".
- [ ] Slot 15 (gold ingot) explains premium: "3 in total, not 3 extra".
- [ ] Slot 18 (dye) shows yesterday's status ("your cycle started today" on day 1).
- [ ] Slot 22 (book) shows "⌛ ʜᴏᴡ ɪᴛ ᴡᴏʀᴋꜱ" with the six lines, including "miss a day and its rewards are skipped." and "reset timezone: Europe/London".
- [ ] Slot 26 (clock) shows the countdown. Leave the menu open across a minute boundary: the countdown updates without the menu flickering or the cursor item moving.
- [ ] All text is lowercase, the colours match the palette, and lore lines are not italic.
- [ ] Set `style.small-caps: false` and `style.symbols: false`, then `/exodaily reload`. Headings become plain lowercase and symbols become `* - + x ~ >`. Change them back.
- [ ] Sounds: opening plays a soft chime, a navigation click plays a click, a successful claim plays a level-up sound plus a few green particles that only the claiming player sees, and an error plays a low note.

## Claiming

- [ ] Click slot 13. The details menu opens with the three rewards at slots 11/13/15 and buttons at 20/22/24. Each reward shows "position N · standard/premium", its full item tooltip and a status line.
- [ ] Click the green button (position 1). The exact item goes into your inventory, the chat says `✓ claimed <reward>.`, and the button turns into "✓ ᴄʟᴀɪᴍᴇᴅ".
- [ ] Click position 1 again. You get "already claimed" and no item.
- [ ] As a standard player, click positions 2 and 3. They are red "premium only" buttons: you get the locked message and no item.
- [ ] `lp user Player permission set exodaily.premium true` while the menu is open. Within about a second, positions 2 and 3 turn green. Claim both: you receive exactly 2 more rewards and the header shows `claimed: 3/3`.
- [ ] Remove premium, then add it again. Positions 2 and 3 stay claimed, and none can be claimed again.
- [ ] `/daily` again and relog. The same three rewards are shown and all claims remain.
- [ ] `/exodaily reload` and restart the server. The rewards stay the same and claims remain.

## Inventory exploits (with the menu open)

For each action, nothing may move into or out of the menu, and no menu icon may end up in your inventory or on the ground:

- [ ] shift-click a menu item
- [ ] shift-click an item in **your own** inventory (it must not move into the menu)
- [ ] number keys 1–9 while hovering over a menu slot and while hovering over your own slots
- [ ] the off-hand swap key (`F`) while hovering over a menu slot
- [ ] double-click an item in your inventory that matches a menu icon (for example, hold glass panes)
- [ ] drag a stack across several menu slots, and across your own slots
- [ ] `Q` / `Ctrl+Q` over a menu item
- [ ] middle-click, in creative mode
- [ ] click outside the window while holding an item
- [ ] spam-click a claim button as fast as you can (an autoclicker if you have one). You receive **one** reward and the rest of the clicks are ignored.
- [ ] Click claim and close the menu in the same instant. You get at most one reward, and reopening shows the correct state.
- [ ] Click claim and disconnect in the same instant. After relogging you have either the reward (claimed) or no reward (still claimable), never both.

## Full inventory

- [ ] Fill every main-inventory slot. Claiming a reward shows "your inventory is too full… it's kept for you until reset". No item is given or dropped (check the ground), and the button stays green.
- [ ] Free one slot and claim again. You receive the reward.

## Day boundaries and missed days

Use a test server whose clock you can change, or set `timezone` to a zone where midnight is a few minutes away and run `/exodaily reload`.

- [ ] Keep the menu open across midnight. You get "a new day has started" and the menu refreshes to the next day with new rewards.
- [ ] Click a claim button just before midnight with the click landing just after. You get "that day has passed or changed" and no item from the old day.
- [ ] Skip a day (`/exodaily setday Player <current+2>` simulates this). In the overview (slot 4), the skipped day shows `✕` and "missed", and it can't be claimed.
- [ ] With real calendar days: claim nothing on one day, and the next day slot 18 shows "✕ yesterday … missed".
- [ ] Day 30 → next day: the cycle number goes up by one and day 1 of the new cycle is claimable.

## Overview menu

- [ ] Slot 4 opens a 6-row overview. Past days show claimed `✓` or missed `✕`. Today glows purple. Milestone days (7, 14, 21, 30) are yellow and glow. Each stack size equals the day number.
- [ ] Future days list **possible** rewards per pool and say "not guaranteed". Looking at them does not change what you later receive on those days.
- [ ] Back (slot 45) returns to the main menu.

## Administration

- [ ] `/exodaily` without permission (as Player) shows the no-permission message. Tab completion offers nothing.
- [ ] As Admin, tab completion offers subcommands, online player names, day numbers, `confirm`, `delivered`/`release` and existing reward ids.
- [ ] `/exodaily status Player` shows cycle, day, start date, today's rewards with their states, and any uncertain claims.
- [ ] `/exodaily setday Player 7` while Player has the menu open. Player's menu refreshes to day 7 with "updated by staff". Moving back to an already-claimed day shows those positions as claimed.
- [ ] `/exodaily reset Player` only warns. `/exodaily reset Player confirm` starts a new cycle at day 1, and the warning text says rewards can be earned again.
- [ ] `audit.log` contains the setday and reset lines.
- [ ] Hold a renamed, enchanted item and run `/exodaily reward save test_item`. `rewards.yml` gains `test_item` with `serialized-item`. Add it to a pool, reload, and when it is assigned, the delivered item is identical to the original (name, enchantments, other data).
- [ ] Break `rewards.yml` (for example `material: NOT_A_THING`) and `/exodaily reload`. The reload is rejected with `[rewards.yml] rewards.<id>.material: unknown material`, and menus keep working with the old configuration.
- [ ] Put two items in the same slot in `menus.yml` and reload. The reload is rejected with `slot N conflicts with …`.

## Crash recovery (optional, destructive — test server only)

- [ ] Claim a reward, and immediately kill the server process (`kill -9`) while the claim is in flight. On restart, the console reports any released reservations, or any `UNCERTAIN` claims together with "NOT reissued automatically".
- [ ] `/exodaily pending` lists uncertain claims. The player sees "being checked by staff" for that position. `/exodaily resolve <id> delivered` keeps it claimed; `/exodaily resolve <id> release` makes it claimable again (same day only).
- [ ] Make `data.db` unreadable, for example by replacing it with a directory, and start the server. ExoDaily logs that storage is unavailable, and `/daily` says rewards are temporarily unavailable. Nothing can be claimed.
