# ExoBlackSmith

Craftable custom armor, masks, runes and upgrades for Paper, with a blacksmith catalog, a 3×3 forge view,
a rune forge, ItemsAdder visuals and value-head drops.

> **Status: built and unit/integration-tested on a mock server, not yet run on a live server.**
> See [TEST-REPORT.md](TEST-REPORT.md) for exactly what was and was not verified.

| | |
|---|---|
| Target server | **Paper 1.21.11** (compiled against `paper-api 1.21.11-R0.1-SNAPSHOT`, built from PaperMC's `ver/1.21.11` source) |
| Java | 21 |
| Other versions | Untested. The code uses 1.21.2+ APIs (`item_model`, `equippable`) and 1.21.3+ attribute names, so older servers will not work. |
| Optional | ItemsAdder (API `beer.devs:itemsadder-api 4.0.18-beta-10`), ExoSpawners (no API available, see below) |

---

## 1. Installation

1. Put `ExoBlackSmith-1.0.0.jar` in `plugins/` (a prebuilt copy is in `dist/`) and start the server once.
2. Configs are written to `plugins/ExoBlackSmith/`. Edit them, then run `/exoblacksmith reload`.
3. **Back up `plugins/ExoBlackSmith/data/secret.key`.** It signs every item. Losing or changing it turns every
   existing ExoBlackSmith item into an inert plain item.
4. Optional, ItemsAdder: copy `itemsadder/contents/exoblacksmith/` into `plugins/ItemsAdder/contents/`, add the textures
   listed in §9, then run `/iazip`. ExoBlackSmith picks up the visuals automatically once ItemsAdder has loaded.

### Building from source

```bash
mvn package          # runs all tests, produces target/ExoBlackSmith-1.0.0.jar
python3 tools/generate_configs.py   # only needed to regenerate the default item/recipe YAML
```

The `paper-api` dependency comes from `repo.papermc.io` (declared in `pom.xml`).

---

## 2. Commands and permissions

| Command | Permission | Console | Description |
|---|---|---|---|
| `/blacksmith [armor\|masks\|runes\|upgrades\|runeforge]` (alias `/smith`) | `exoblacksmith.use` (default: everyone) | no | Opens the catalog or a category |
| `/exoblacksmith give <player> <item_id> [amount] [tier]` (alias `/exobs`) | `exoblacksmith.admin.give` (op) | yes | Gives any item (armor, mask, rune, upgrade, totem, head). `tier` is the rune tier or mask level |
| `/exoblacksmith givehead <player> <head_id> [amount]` | `exoblacksmith.admin.givehead` (op) | yes | Gives authentic value heads (dungeon rewards) |
| `/exoblacksmith drop <player> <mob> [deaths] [natural\|spawner]` | `exoblacksmith.admin.drop` (op) | yes | Rolls head drops as if `player` killed `deaths` mobs (stack-plugin and script hook) |
| `/exoblacksmith reload` | `exoblacksmith.admin.reload` (op) | yes | Validates all files; applies them only if everything is valid |
| `/exoblacksmith validate` | `exoblacksmith.admin.reload` | yes | Confirms the live config passed coverage and reachability checks |
| `/exoblacksmith list` | `exoblacksmith.admin.give` | yes | Lists every item id and its tier range |
| `/exoblacksmith inspect` | `exoblacksmith.admin.inspect` | no | Shows the signed data of the held item and whether it authenticates |

Other permissions (all default to everyone): `exoblacksmith.runeforge`, `exoblacksmith.craft.<armor|masks|runes|upgrades>`
(each recipe can also set its own `permission:`), `exoblacksmith.ability.mask`, `exoblacksmith.ability.armor`,
`exoblacksmith.drops`. `exoblacksmith.admin` grants every admin node.

The `<player>` argument accepts a name or UUID, and also vanilla selectors such as `@p` (via `Bukkit.selectEntities`;
the test server cannot run selectors, so this path is untested). Items that do not fit are dropped at the player's feet
and the player is told.

### Dungeon reward examples

```
exoblacksmith givehead %player% creeper_value_head 15
exoblacksmith givehead %player% iron_golem_value_head 4
exoblacksmith give %player% legendary_rune_upgrade 8
exoblacksmith give %player% blast_rune 1 1
exoblacksmith give %player% sedge_mask_totem 1
exoblacksmith drop %player% zombie 25 spawner
```

Use whatever placeholder your dungeon plugin substitutes for the player name. The items are created by
ExoBlackSmith itself, so they are identical to crafted or dropped ones.

---

## 3. Blacksmith and crafting

`/blacksmith` opens the catalog: **Armor**, **Masks**, **Runes**, **Upgrades** (each paginated, 28 per page) and the
**Rune Forge**. Each catalog icon shows the item's own lore (rarity, tier, effects, cooldowns, slot), then every ingredient
with required and owned counts, and the craft status (ready, missing, locked).

Clicking an item opens the **forge view**: a 3×3 ingredient grid, an output preview, an info book and a craft button.
"8 rows around the middle" is implemented as the eight outer cells of the 3×3 grid.

* Menus are previews: every click and drag in the menu **and** the player inventory is cancelled while a menu is open
  (shift, number keys, offhand swap, double-click collect, drop, creative clone).
* Pressing **craft** re-validates everything at that moment and charges the **total** quantity of each ingredient from
  the 36 main inventory slots (armor, offhand and cursor are never touched). Split stacks are combined.
* The craft is planned on a copy of the inventory, and the output is inserted into that same copy. If anything is
  missing, or the output does not fit, **nothing is consumed**. On success the whole storage array is written in one
  main-thread step, so ingredients and output change together.
* Plugin ingredients must authenticate (§8). Vanilla ingredients must be completely plain in strict mode (no rename,
  lore, enchantments or damage) and never ExoBlackSmith items.
* Clicks are debounced per player (`menus.click-cooldown-ms`). A click executes on the next tick, and only if the same
  menu is still open, so rapid clicks, closing the menu or disconnecting can never double-craft.
* If `/exoblacksmith reload` changes a recipe while someone has it open, pressing craft refreshes the preview and asks
  them to confirm again instead of crafting the stale version.

**Runes, tier I:** 15 value heads in each of the 8 outer cells (120 heads). Each rune has its own head pair: one head
type in the corners and one on the edges. The center stays empty.
**Rune tier II / III:** the rune in the center, plus 120 **Legendary** / **Fabled Rune Upgrade** items (15 per outer cell).
The old rune and the materials are consumed and exactly one upgraded rune is returned.
**Masks:** level 1 is 8 matching heads in each outer cell plus a diamond block (proposed). Each upgrade puts the current mask
in the center, the matching **Mask Totem** in the top cell and 15 matching heads in each of the other 7 cells.
**Totems:** 4 matching heads on each edge, 4 gold blocks in the corners and a vanilla Totem of Undying in the center (proposed).

The full list of all 78 default recipes is in [docs/RECIPES.md](docs/RECIPES.md). Every recipe is in `recipes.yml`.

---

## 4. Runes

Only ExoBlackSmith armor accepts runes. Each piece has **3 slots**, shown as three lore lines
(`+ rune slot: empty` or `+ rune slot: blast rune ii`). Apply runes in **/blacksmith → Rune Forge**: pick an armor piece
(carried or worn), pick a compatible rune, check the preview, then confirm. The rune is consumed only when the apply succeeds.
The rune forge rejects incompatible slots, ordinary armor, a 4th rune and a second copy of the same rune on one piece.

| Rune | Fits | Tier I (epic) | Tier II (legendary) | Tier III (fabled) |
|---|---|---|---|---|
| Blast Rune | any | −15% | −20% | −25% end crystal & respawn anchor damage |
| Totem Surge | chestplate | 2s | 3s | 4s invisibility + dash after a totem pops (30s cooldown) |
| Hardened Shell | any | −12% | −16% | −20% critical sword/axe damage |
| Kinetic Reducer | any | −20% | −30% | −40% mace smash & elytra collision damage |
| Phoenix Aura | chestplate | −30% | −40% | −50% fire & lava damage; burning ends ≤1s |
| Void Stride | boots | +10% | +15% | +20% walking speed; no soul sand/honey slowdown |
| Feather Ward *(added)* | boots | −10% | −20% | −30% fall damage |
| Anchor Guard *(added)* | leggings | +10% | +15% | +20% knockback resistance |
| Tidal Breath *(added)* | helmet | 10s | 20s | 30s water breathing when submerged (60s cooldown) |

Tier II values were interpolated from the requested ranges. The three added runes are proposals.

**Stacking across pieces:** `damage.rune-stacking: HIGHEST` (default) means the highest tier worn counts.
`SUM` adds the values but caps them at that rune's own tier III value.

**Mechanics, as verified against the Paper 1.21.11 API:**
* *Blast Rune*: end crystals are detected as an `EntityDamageByEntityEvent` from an `EnderCrystal`. Respawn anchors are
  detected via `EntityDamageByBlockEvent#getDamagerBlockState()` being a `RESPAWN_ANCHOR`. Beds share the
  `bad_respawn_point` damage type, and the block state is what tells them apart. If the server supplies no block state,
  an Overworld `bad_respawn_point` explosion is treated as an anchor (beds do not explode there). Creeper/TNT explosions
  are not affected; a test checks this.
* *Hardened Shell* uses Paper's `EntityDamageByEntityEvent#isCritical()`, limited to direct player melee with an item
  in `#minecraft:swords` or `#minecraft:axes`. Critical arrows are excluded. This is no approximation: Paper reports the
  server's own critical flag.
* *Kinetic Reducer*: damage type `mace_smash`, and cause `FLY_INTO_WALL` for elytra collisions.
* *Phoenix Aura*: causes `FIRE`, `FIRE_TICK`, `CAMPFIRE` and `LAVA`. Every 5 ticks burning is capped at 15 ticks, so it
  goes out within 1s unless the player is still in fire or lava. **This is not lava immunity.**
* *Void Stride*: an `ADD_SCALAR` modifier on `movement_speed` (additive with other plugins' modifiers, never replacing
  them), plus `movement_efficiency +1`, which removes the soul sand slowdown. Note: mud has no movement slowdown in
  vanilla 1.21, so there is nothing to negate there. The speed bonus is removed while flying or gliding
  (`movement.disable-speed-bonus-while-flying`) and never affects creative flight speed. Untested in-game.
* *Totem Surge*: runs on a successful (not cancelled) `EntityResurrectEvent`. Next tick it applies invisibility plus a
  horizontal velocity impulse in the facing direction (`dash-force`, `dash-vertical`, `cooldown` in `runes.yml`).
  Because the dash is a velocity, normal collision physics and movement checks apply. It is skipped if the estimated end
  point is outside the world border. **Vanilla limitation:** invisible players still show worn armor, held items, arrows
  stuck in them and the totem animation, so invisibility with armor on is only partial concealment.

---

## 5. Masks

Masks are player heads worn in the **helmet slot**. Effects apply only there. The exact supplied textures are used:
ingredient heads and finished masks have different textures and different ids.

| Mask | Ingredient | Lv | Effects (proposed values in *italics*) |
|---|---|---|---|
| Sedge | sheep | 1–2 | Speed I → Speed II |
| Wisdom | pig | 1–2 | Haste I → Haste II |
| Canapy | polar bear | 1–3 | heal 4 hearts → *7 hearts* → full health; 40s cooldown |
| Trident | zombie | 1–3 | +1 / +2 / +3 trident damage points (melee and thrown) |
| Skelly | skeleton | 1 | teleport to a marked arrow's landing spot; 40s cooldown (*10s after a miss*) |
| Creepy | creeper | 1–2 | *+2 / +4* explosive damage on hitting a player; *8s* proc cooldown; no block damage |
| Syder | spider | 1–2 | cobwebs on targeted ground; 30s → 20s cooldown (*plus shape, 6s, 12 blocks*) |
| Wolfski | wolf | 1 | *2 wolves, 15s, 45s cooldown, 4 damage per bite* attacking the targeted player |
| Guard | villager | 1–2 | 1 → 2 iron golems; *15s, 60s cooldown, 6 damage* |
| Golom | iron golem | 1–3 | Regen I + Strength I + 3 hearts → *+4 hearts* → *Regen II + 5 hearts* |
| Stray | stray | 1–3 | *Speed I/I/II, Strength I, +2/+3/+4 hearts* |
| Drownie | drowned | 1–3 | +1 trident, +8 hearts, Strength I, Regen I, cobwebs (30s) → *+2/+9* → *+3/+10* |

One heart is 2 health points. "Damage points" are raw damage, not hearts.

**Controls** (configurable in `config.yml → abilities`):
* Canapy, Syder, Drownie, Wolfski, Guard: **sneak + right-click** with an empty hand or a sword, axe, mace or trident
  (`require-empty-main-hand: true` allows only an empty hand). Syder and Drownie target ground within range. Wolfski and
  Guard target the player you are looking at.
* Skelly: **sneak while firing a bow** marks that one arrow. The full cooldown is reserved at the shot, so only one
  teleport can be pending. A valid impact teleports you to the nearest safe standing spot. A miss, an unsafe spot,
  arrow loss or a cancelled teleport (protection plugins see the configured `teleport-cause`, `ENDER_PEARL` by default)
  shortens the cooldown to the miss cooldown.
* Only main-hand interact events count (the offhand duplicate is ignored), with a per-player debounce. Inventory clicks
  never trigger abilities.

**Effect ownership:**
* Max-health bonuses are a single transient modifier with a fixed key: never duplicated by re-equipping, never saved to
  player data, and removed on unequip, quit and plugin disable. Health is clamped when max health drops.
* Potion effects are infinite, ambient and particle-less (that is the plugin's ownership signature).
  - If an **equal or stronger** effect from another source is active, the mask's effect is not applied, and the stronger
    effect is never touched.
  - A **weaker** external effect is snapshotted and restored, with its remaining time, when the mask comes off.
  - If another source later stacks a stronger effect on top, the mask's effect is removed as soon as it resurfaces. This
    pending cleanup survives relogs.
* Death, respawn, join, quit, world change, gamemode change, flight/glide toggles, armor changes, reloads and plugin
  disable all re-sync or clean up. There is also a periodic scan every 10 ticks as a safety net.

**Summons** (Wolfski, Guard) are non-persistent and tagged.
* They can only target the chosen enemy, and any retarget is cancelled.
* Their bites are cancelled and re-dealt as damage *from the owner*. PvP flags, region protection and combat-tag plugins
  therefore treat it as a normal owner-vs-target hit. Damage to anyone else is cancelled.
* They drop nothing, can't be tamed, leashed or interacted with, and don't use portals.
* They are removed on expiry, on owner quit/death/world change, when the target is lost, on chunk unload and on plugin
  disable. Strays are swept when chunks load.
* They never target the owner, scoreboard teammates (`respect-scoreboard-teams`), creative or spectator players,
  vanished players, or anyone in worlds with PvP off. Max 4 active per player.

**Cobwebs:**
* They are placed only into air on solid ground, after a synthetic `BlockPlaceEvent` (so protection plugins can deny
  them), a spawn-protection check and a world-border check.
* On expiry a web is removed only if it is still a tracked web, so a later block change is never overwritten.
* Breaking a temporary web drops nothing, and pistons and explosions can't move or break them.
* Entries persist in `data/temp-blocks.yml` and are cleaned on startup or when their chunk loads.

**Bonus damage:**
* Trident and Creepy bonuses are added once, inside the original hit's event. No extra damage event is fired, so bonus
  damage can never loop.
* The bonus is capped by `damage.max-bonus-damage-per-hit`. Creepy's cooldown and effects are committed at `MONITOR`,
  and only if the hit was not cancelled, so a PvP-protected hit costs nothing.

---

## 6. Armor: concrete default configuration (proposed balancing)

All pieces use diamond as the base material (vanilla armor points and toughness), have epic rarity and 3 rune slots.
**Set bonuses need chestplate + leggings + boots** (configurable per set or globally), so a mask can fill the helmet slot.
**Set ability control: sneak + swap-hands key (F).** This is deliberately different from mask controls. The swap is
cancelled only while a complete set with an ability is worn.

| Set | Per piece (each of 4) | Set bonus (chest+legs+boots) | Active ability |
|---|---|---|---|
| **Emberforged** | −6% fire, −6% lava | −10% fire, −10% lava | **Ember Ward**: 6s of −40% fire/lava, extinguishes you; 90s cooldown |
| **Riftguard** | −5% explosion (any) | −10% crystal & anchor | **Rift Barrier**: 3s of −30% all damage; 75s cooldown |
| **Stormstride** | −8% fall, −8% elytra collision; boots +4% speed | −8% mace smash, +5% speed | **Gale Step**: horizontal dash (force 1.3), then 3s of −60% fall damage; 45s cooldown |

Recipes: the center cell is the matching plain diamond piece; corners hold 4 heads each (pig / creeper / stray);
edges hold blaze rods ×8, crying obsidian ×8 or breeze rods ×6.

### Damage rules (all sources)
1. **Attacker bonuses** (HIGH priority): added to the base damage and capped per hit.
2. **Victim reductions** (HIGHEST priority) are collected from runes (after the stacking policy), each worn piece, the
   set bonus and active abilities. They are combined **multiplicatively** (`1 − Π(1 − r)`, or `ADDITIVE`), then capped
   at **`damage.max-total-reduction: 0.80`** (hard max 0.95). Damage is never reduced to zero and never becomes healing.
3. Vanilla armor, Protection enchantments, Resistance and other plugins' modifiers then apply as usual to the reduced
   base damage. ExoBlackSmith only scales the base, which Paper re-runs vanilla armor math on.
4. Everything runs with `ignoreCancelled = true`. Other plugins that change damage at HIGHEST/MONITOR may interact, so
   check with your combat plugin.

Cooldowns: mask abilities, set abilities and Totem Surge/Tidal Breath each have their own key, so mask and armor
abilities never share or block each other.

---

## 7. Mob value heads and ExoSpawners

Twelve heads (sheep, pig, polar bear, zombie, skeleton, creeper, spider, wolf, villager, iron golem, stray, drowned),
with chance, quantity and rarity per head in `heads.yml`. Global rules live in `config.yml → drops`: worlds, player-killer
requirement, spawner/natural eligibility (spawner = `fromMobSpawner()` or trial spawner), a looting bonus, and drop-on-ground
vs. straight to the killer.

**How deaths are counted:**
* Vanilla `EntityDeathEvent`: one roll per entity that actually died, credited to `getKiller()`.
* **ExoSpawners: its API could not be inspected** (no public repository or Maven artifact was reachable while building),
  so no ExoSpawners classes are referenced. Instead there are three neutral integration points:
  1. `integrations.exospawners.custom-event`: bind ExoSpawners' stack-death event **by name** from its documentation
     (event class, killer getter, entity getter and an optional getter for the number of mobs that really died).
     It is disabled by default. If the class or methods don't exist, the plugin logs the error and keeps vanilla drops.
  2. `stacked-entity-markers`: metadata or PDC keys that mark stacked entities, so the vanilla handler skips them and
     only the custom event pays. This prevents double drops.
  3. `/exoblacksmith drop <player> <mob> <deaths> [spawner|natural]` and the Java API
     `ExoBlackSmithPlugin#drops().grant(killer, type, deaths, fromSpawner, location)`.
* Every death goes through a 10-second per-entity de-duplication window, so overlapping handlers can never pay twice for
  the same entity. Rewards are **never** multiplied by a displayed stack size, only by the reported number of real deaths.
* Natural mobs: drop by default (`allow-natural-mobs: true`). Set it to `false` for spawner-only heads.
* **What you must verify on your server:** whether ExoSpawners fires one `EntityDeathEvent` per killed mob (then do
  nothing extra), one per stack kill, or its own event (then configure 1 and 2).

---

## 8. Item identity and exploit defenses

* Identity is signed persistent data: `id`, `kind`, `level`, a per-copy `uid` (armor and masks), `runes`, and `schema`,
  plus an **HMAC-SHA256** with the per-server `data/secret.key`. Names, lore, textures and models are presentation:
  they are regenerated from config (on join, after reload, once ItemsAdder loads) and are never trusted.
* Edited data (NBT editors, creative clients) fails the signature and becomes a plain item. Unknown ids, levels above the
  configured maximum, unknown runes or a stacked unique item also fail.
* When a mask or armor piece is consumed by an upgrade, its `uid` is appended to `data/retired-uids.txt` immediately.
  A duplicated copy of a consumed item then grants no effects and can't be used as an ingredient.
* Vanilla transformations are blocked for any tagged item (each check is configurable): crafting grid, crafter, anvil,
  smithing (e.g. netherite upgrades), grindstone and enchanting table. Placing heads or masks as blocks is also blocked,
  because placed heads lose their item data. Totems and rune materials use `PAPER` with an item model, so they never act
  as a real Totem of Undying or any other vanilla item.
* Menu icons carry a marker and no identity. One that ever reaches a real inventory is deleted on touch or on join.

**Limitations (honest list):**
* A **byte-for-byte copy** of a legitimate item (creative pick-block, a server duplication glitch, a backup restore)
  carries a valid signature. For armor and masks the copy shares its `uid`; once one copy is upgraded, the others die.
  Stackable items (heads, runes, materials, totems) have no per-copy id, so dupes of those cannot be told apart.
* Admins with access to `secret.key` can mint items, by design.
* **Crash consistency:** a craft changes the inventory in memory in one tick, but the server writes player data later
  (autosave or quit). A crash in between reverts the player to the pre-craft inventory. Retirement is written
  **before** that, on purpose: a crash cannot be used to keep both the old mask and the new one. The rare cost is that
  a crash right after a mask upgrade can leave the player holding the old, now inert, mask, which an admin can replace
  with `/exoblacksmith give`.
* **Cooldowns** are wall-clock timestamps in the player's persistent data, so swapping gear, relogging, reloading and
  restarting don't reset them. A hard crash can lose cooldowns started after the last player-data save.
* **Relogging with a health-bonus mask** is expected to cap current health at the un-boosted maximum at login (not
  verified on a live server). The bonus is transient and is re-applied a tick after join. This is intentional: the
  modifier is never written to player data.

---

## 9. ItemsAdder

`itemsadder/contents/exoblacksmith/configs/exoblacksmith.yml` declares one ItemsAdder item for every rune, upgrade
material, totem and armor piece, plus 3 armor equipments and 6 rarity font images. ExoBlackSmith items are **not**
ItemsAdder items: after `ItemsAdderLoadDataEvent`, ExoBlackSmith copies only the `item_model`, custom model data and
`equippable` components from the ItemsAdder template whose id is set in `itemsadder-id`. ItemsAdder therefore never
rewrites names, lore or identity.

* **Unverified format:** the ItemsAdder documentation site was blocked from the build environment, so this YAML follows
  ItemsAdder 4.x conventions from memory. Check `display_name`/`resource`/`equipment`/`equipments`/`font_images` against
  your ItemsAdder version's docs before relying on it.
* **Fallback:** without ItemsAdder, or for any id it doesn't know (a warning is logged once), items keep their vanilla
  look: diamond armor, the configured `item-model` (e.g. runes use `minecraft:fire_charge`, totems
  `minecraft:totem_of_undying`), plus an enchantment glint. Heads and masks always use the supplied textures.
* **Rarity glyphs:** set `itemsadder-glyph: ":exoblacksmith:rarity_epic:"` on a rarity to show the font image once
  ItemsAdder is loaded. Otherwise the unicode symbol is shown, or the `plain-symbol` with `unicode-symbols: false`.
* **Assets you still need to create** (none were created; there were no art assets to work from), all under
  `contents/exoblacksmith/textures/`:
  * `item/runes/<rune_id>.png` ×9
  * `item/upgrades/legendary_rune_upgrade.png`, `item/upgrades/fabled_rune_upgrade.png`
  * `item/totems/<mask>_mask_totem.png` ×10
  * `item/armor/<set>_<piece>.png` ×12
  * `armor/<set>_layer_1.png` and `armor/<set>_layer_2.png` ×3 sets
  * `font/rarity_<rarity>.png` ×6

---

## 10. Configuration files

| File | Contents |
|---|---|
| `config.yml` | presentation (symbols, small caps), rarities (hex colors), protection toggles, damage caps and stacking, controls, drops, integrations |
| `heads.yml` | value heads: mob, texture, rarity, drop chance and quantity |
| `masks.yml` | masks: ingredient head, texture, per-level effects, hearts, trident bonus, abilities, cooldowns |
| `runes.yml` | runes: mechanic, slot, tier values and rarities, extra params |
| `armor.yml` | sets (required slots, bonus, ability) and pieces (base material, per-piece reductions, speed) |
| `upgrades.yml` | rune upgrade materials and mask totems |
| `recipes.yml` | every recipe: category, output, 3×3 pattern, ingredients and amounts, optional permission |
| `menus.yml` | menu titles and button icons |
| `messages.yml` | chat, action-bar and lore templates (MiniMessage, `{placeholders}`, `{sym:name}`) |

**Validation:**
* Every load and reload parses all nine files into a new snapshot. Errors are reported as `file: key: message`, e.g.
  `runes.yml: runes.blast_rune.tiers.1.value: must be between 0 and 0.95, got 7`.
* The live config is replaced **only if there are no errors**.
* Coverage checks:
  * every armor piece, mask level, rune tier, upgrade material and totem has a recipe;
  * every such recipe is **reachable**, starting from vanilla items and heads (no circular or missing inputs);
  * level-ups consume the previous level of the same item;
  * a totem may only appear in its own mask's recipes;
  * unique items are consumed one at a time.
* Missing message keys fall back to the bundled defaults, with a warning.

**Text:** MiniMessage with `{placeholder}` substitution. Runtime values such as player names are escaped, so they can't
inject formatting. Use `presentation.unicode-symbols: false` for plain-text fallbacks and
`presentation.small-caps: true` for small-cap typography.

---

## 11. Project layout

```
src/main/java/com/exoblacksmith/
  config/      parsing, validation, immutable Registry
  item/        signed identity, item creation, lore, vanilla guards
  craft/       pure craft planner + Bukkit crafting and rune services
  gui/         menus (catalog, forge view, rune forge)
  effect/      equipment sync, potion ownership, damage pipeline, rune effects, cooldowns
  ability/     mask and set abilities, summons, temporary blocks, protection helpers
  integration/ ItemsAdder visuals, head drops / stacked-mob binding
  command/     /blacksmith and /exoblacksmith
tools/         config generator, extracted texture registry, test report script
itemsadder/    ItemsAdder content config
dist/          prebuilt jar
```
