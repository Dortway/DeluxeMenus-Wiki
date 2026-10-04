# ExoBlackSmith — Complete Plugin Development Prompt

You are an experienced Minecraft plugin developer. Build a complete, professional plugin named **ExoBlackSmith**, featuring craftable custom armor, masks, runes, upgrade items, and a polished custom blacksmith crafting interface.

Implement working code, configuration, integration adapters, and documentation, not just a plan or pseudocode. Inspect the existing project and supplied dependencies first. Use the server's actual Minecraft/Paper and Java versions; if none are supplied, state a proposed supported version before implementation. Verify APIs against the selected versions. Do not invent ExoSpawners or ItemsAdder APIs or claim untested compatibility. If a required dependency cannot be inspected, isolate its integration and clearly identify the missing information.

## 1. Core requirements

- All armor, masks, runes, rune upgrades, and mask upgrade totems must be craftable through `/blacksmith`.
- Add permission-protected, console-compatible admin commands to grant every custom item, including mob value heads for dungeon rewards.
- Integrate ItemsAdder for custom assets and rarity presentation. Supply the required configuration and document any resource-pack assets still needed. Preserve the exact supplied player-head textures.
- Support configurable rarities: Common, Uncommon, Rare, Epic, Legendary, and Fabled. Suggested default rune progression: Epic tier I, Legendary tier II, Fabled tier III.
- Make recipes, names, lore, textures, item models, rarity styling, effects, cooldowns, drop rates, and permissions configurable.
- Use stable internal IDs and persistent item metadata. Display names, lore, textures, and model appearance must never be sufficient proof of item identity.
- Build robust duplication prevention and exploit defenses. Do not promise absolute exploit immunity; document tested cases and remaining limitations.

## 2. Blacksmith interface and crafting

`/blacksmith` opens a polished catalog with separate, paginated categories for **Armor**, **Masks**, **Runes**, and **Upgrades**. Show each item's name, rarity, tier, effects, required ingredients, owned quantities, and craftability. Include back, next, previous, and close controls, plus clear success and failure feedback.

Clicking an item opens a custom crafting-table-style GUI with a 3×3 ingredient layout, an output preview, and a confirmation button. Interpret the requested “8 rows around the middle” as the **eight outer slots of a 3×3 grid**, not eight actual rows.

Use a recipe preview and an explicit craft action that consumes ingredients from the player's inventory. Menu icons are previews and cannot be removed. Clearly explain this interaction in the GUI. Validate and consume the full recipe atomically, then grant the output only once. Reject crafting before consumption if the output cannot fit. Handle separate stacks correctly and never consume ordinary items that merely look similar.

For tier-I runes, require **15 value heads in each of the eight outer slots**, totaling 120 heads. Each rune needs its own configured head recipe. Use a distinct combination for every rune; an example default is one head type in the four corners and another in the four edges. The center can remain empty for base crafting.

For rune upgrades, place the existing rune in the center, with 15 upgrade items in each of the eight outer slots:
- Tier I → II: 120 **Legendary Rune Upgrade** items.
- Tier II → III: 120 **Fabled Rune Upgrade** items.

Consume the original rune and upgrade materials and return exactly one upgraded rune. Both upgrade materials must also have complete craftable recipes.

All unspecified armor, mask, and upgrade recipes need sensible, complete default recipes. Label these as proposed balancing choices and expose every ingredient and quantity in configuration. Do not leave empty recipes or inaccessible progression.

## 3. Runes

Only ExoBlackSmith custom armor can accept runes. Each armor piece has exactly **three rune slots**. Display three separate lore lines, such as `+ rune slot: empty`, replacing each line with its installed rune and tier when occupied.

Provide a rune application GUI with compatible equipment selection, preview, and confirmation. Consume a rune only after successful application. Reject incompatible slots, ordinary armor, a fourth rune, and duplicate copies of the same rune on one item. Default to the highest equipped tier for duplicate rune types across armor pieces; make stacking policy configurable and clearly describe it.

Use these exact core effects; the middle-tier values below are proposed defaults interpolated from the requested ranges:

| Rune | Equipment | Tier I | Tier II | Tier III |
|---|---|---|---|---|
| Blast Rune | Any custom armor | 15% reduction | 20% reduction | 25% reduction |
| Totem Surge | Custom chestplate | 2s invisibility | 3s invisibility | 4s invisibility |
| Hardened Shell | Any custom armor | 12% reduction | 16% reduction | 20% reduction |
| Kinetic Reducer | Any custom armor | 20% reduction | 30% reduction | 40% reduction |
| Phoenix Aura | Custom chestplate | 30% reduction | 40% reduction | 50% reduction |
| Void Stride | Custom boots | +10% walking speed | +15% walking speed | +20% walking speed |

Effect definitions:
- **Blast Rune:** reduces damage specifically from end crystals and respawn anchors. Distinguish these from other explosions.
- **Totem Surge:** on a successful totem resurrection, grants invisibility and a directional dash based on facing direction. The dash must respect collisions, world boundaries, and protection rules. Make dash force and cooldown configurable. State the vanilla visibility limitations of invisibility while wearing armor.
- **Hardened Shell:** reduces critical sword and axe strike damage. Verify critical-hit detection for the target server version and document any approximation.
- **Kinetic Reducer:** reduces mace smash damage and high-speed elytra collision damage.
- **Phoenix Aura:** reduces fire and lava damage and extinguishes burning within one second. Do not describe this as complete lava immunity.
- **Void Stride:** negates soul-sand and mud movement impediments and increases walking speed. Verify behavior on the target version, avoid affecting flight, and avoid overriding unrelated speed modifiers.

Add these fitting extra runes as proposed, configurable defaults:
- **Feather Ward — boots:** 10% / 20% / 30% fall-damage reduction.
- **Anchor Guard — leggings:** 10% / 15% / 20% knockback resistance.
- **Tidal Breath — helmet:** grants 10 / 20 / 30 seconds of underwater breathing when submerged, with a 60-second cooldown.

Give each extra rune its own recipe, three tiers, rarity styling, lore, and compatible-slot validation. Define damage-reduction ordering and caps so combined effects cannot unintentionally yield immunity or healing.

## 4. Mob value heads and ExoSpawners

Use the texture registry at the end of this prompt. Ingredient heads and crafted mask heads have different textures and identities; keep them separate.

Integrate mob-head drops with ExoSpawners using its actual available API/events. Account for stacked mobs, credited killers, and how many mobs actually die per event. Never multiply rewards merely by a stack's displayed size. Avoid duplicate drops from overlapping vanilla and ExoSpawners handlers. Document natural-mob behavior and make it configurable.

Configure drop chance, quantity, eligible worlds, killer requirements, spawner/natural eligibility, and stacked-mob handling. Dungeon plugins must be able to grant authentic value heads via console commands without needing their own item-construction logic.

## 5. Masks

Masks are custom player-head helmets. Their passive effects and abilities work only while equipped in the helmet slot. Removing or replacing a mask must remove only ExoBlackSmith-owned modifiers, without erasing unrelated stronger effects. Correctly handle death, respawn, joining, leaving, world changes, equipment changes, plugin disable, and reload.

Use these exact names and mob ingredient mappings. Preserve the user's unusual names: **Canapy**, **Syder**, and **Golom**. The texture registry's crafted “golem” texture belongs to Golom.

| Mask | Ingredient mob | Required effects and progression |
|---|---|---|
| Sedge | Sheep | Speed I; upgrade to Speed II; max level 2. |
| Wisdom | Pig | Haste I; upgrade to Haste II; max level 2. |
| Canapy | Polar bear | Activated healing: level 1 heals 4 hearts; proposed level 2 heals 7 hearts; level 3 restores full health. 40-second cooldown at every level. |
| Trident | Zombie | +1 / +2 / +3 trident damage points at levels 1–3. Support melee and thrown tridents, with each hit modified once. |
| Skelly | Skeleton | A bow-shot ability teleports the wearer to the arrow's valid landing location. 40-second cooldown. Default max level 1. |
| Creepy | Creeper | Hitting another player adds explosive damage; max level 2. Proposed defaults: 2 / 4 bonus damage points with an 8-second proc cooldown; no block destruction. |
| Syder | Spider | Activated cobweb placement on the targeted ground. Level 1 cooldown 30 seconds; level 2 cooldown 20 seconds. |
| Wolfski | Wolf | Summons wolves that attack the targeted hostile player. Proposed default: 2 wolves, 15-second lifetime, 45-second cooldown, max level 1. |
| Guard | Villager | Summons 1 iron golem at level 1 or 2 at level 2 to attack the targeted hostile player. Proposed defaults: 15-second lifetime and 60-second cooldown. |
| Golom | Iron golem | Level 1: Regeneration I, Strength I, and +3 hearts. Max level 3. Proposed level 2: Regeneration I, Strength I, +4 hearts; level 3: Regeneration II, Strength I, +5 hearts. |
| Stray | Stray | Speed, Strength, and extra hearts; max level 3. Proposed levels: Speed I / I / II, Strength I / I / I, and +2 / +3 / +4 hearts. |
| Drownie | Drowned | Level 1: +1 trident damage point, +8 hearts, Strength I, Regeneration I, and activated cobweb placement with a 30-second cooldown. Max level 3. Proposed levels 2–3: +2 / +3 trident damage, +9 / +10 hearts; retain Strength I, Regeneration I, and 30-second cobweb cooldown. |

One heart equals two health points. “Damage points” in this prompt are not hearts. Full healing must respect the player's current maximum health. Added hearts must be reversible maximum-health modifiers and must not duplicate on re-equipping.

Suggested activation controls, configurable because the original gestures were ambiguous:
- Canapy: sneak + right-click while wearing it.
- Syder and Drownie: sneak + right-click a valid ground block.
- Wolfski and Guard: sneak + right-click while targeting a valid enemy player within configurable range.
- Skelly: sneak while firing a bow to mark one ability arrow; teleport on a valid impact, not every ordinary bow shot. Define cooldown reservation and miss handling to prevent multiple pending teleports.

Do not interpret inventory shift-clicks as combat ability activation. Avoid repeated activation from the offhand or duplicate interaction events.

Summons must not target their owner, allies, or protected players. Limit active summons and clean them up on expiry, owner logout/death, and plugin shutdown. Teleports must avoid suffocation, unloaded/invalid destinations, prohibited regions, and world borders. Temporary cobwebs must respect placement rules, expire safely, and never overwrite unrelated later block changes. Damage must respect cancelled events and PvP restrictions without recursive bonus-damage loops.

## 6. Mask Totems and progression

Create a craftable item family named **Mask Totem**, with a distinct upgrade totem for each upgradable mask, for example `Sedge Mask Totem` and `Canapy Mask Totem`. Distinguish totems by stable mask ID, not just display name.

Provide complete, configurable recipes for each totem and each mask upgrade. A totem works only on its matching mask. Consume the old mask and required materials exactly once, preserve compatible data, and produce the next level. Reject the wrong totem and upgrades beyond the mask's cap.

Show these recipes in the blacksmith catalog. Suggested default upgrade recipe: current mask in the center, one matching Mask Totem in one outer slot, and 15 matching mob heads in each of the other seven outer slots. Make the exact layout configurable.

## 7. Custom armor

Design and implement craftable armor that fits this system, with complete recipes, ItemsAdder definitions, effects, abilities, and all three rune slots per piece.

Suggested default themes:
- **Emberforged:** fire/lava defense with an active defensive ability.
- **Riftguard:** crystal/anchor defense with a short defensive barrier ability.
- **Stormstride:** movement and impact resistance with a controlled mobility ability.

Treat their exact statistics, recipes, rarity tiers, and ability controls as proposed balancing choices. Supply a concrete configuration table before implementation, then implement those defaults. Include helmets, chestplates, leggings, and boots. Because masks occupy the helmet slot, default set bonuses should require chestplate + leggings + boots, allowing a mask to complete the loadout. Make this rule configurable.

Clearly separate per-piece effects, set bonuses, and active abilities. Explain stacking with runes, enchantments, masks, and other plugins. Define cooldowns and damage caps. Avoid control conflicts between armor abilities and mask abilities.

## 8. Presentation

Use a coherent, professional visual style: lowercase display text or readable small-cap typography, hex colors, subtle symbols/emojis, textured value heads in menus, consistent rarity colors, and concise lore. Keep internal IDs and commands conventional and readable. Include plain-text fallbacks for unsupported glyphs.

Lore should show rarity, level/tier, effects, cooldown, equipment restrictions, and rune slots where applicable. Use configurable messages and a supported rich-text format. Do not clutter player-facing menus with implementation details.

## 9. Commands and configuration

Implement and document commands equivalent to:
- `/blacksmith`
- `/exoblacksmith give <player> <item_id> [amount] [tier]`
- `/exoblacksmith givehead <player> <head_id> [amount]`
- `/exoblacksmith reload`

Provide tab completion, meaningful permissions, console support for admin actions, and clear validation errors. Include example dungeon reward commands.

Separate configuration for general settings, armor, masks, runes, recipes, heads, upgrades, menus, and messages. Reject invalid configuration with useful file/key errors. Reload should not partially apply broken configuration or invalidate active crafting transactions. Persist cooldowns or use a documented policy that prevents relogging/reloading from resetting important combat cooldowns.

## 10. Reliability and verification

Protect GUI handling against shift-clicking, dragging, number-key swaps, offhand swaps, double-click collection, hotbar changes, full inventories, disconnects, closing menus, rapid repeated clicks, stale previews, and concurrent crafting requests. Keep item consumption and output creation on the appropriate server thread. Document crash-consistency limits rather than claiming guarantees the inventory/persistence design does not provide.

Do not trust item lore or arbitrary client-provided fields. Validate item schema, IDs, tiers, rune compatibility, ingredients, quantities, and upgrade paths at the time of each action. Exclude unsupported vanilla crafting/anvil/grindstone transformations that would corrupt or counterfeit plugin items.

Verify:
1. Every base item and upgrade has a reachable crafting recipe.
2. Recipes charge exact quantities and reject forged lookalikes.
3. Crafting and upgrading cannot grant multiple outputs for one payment under tested interactions.
4. Rune effects trigger on the intended causes and respect slot limits and stacking rules.
5. Masks clean up effects and health modifiers correctly.
6. Cooldowns cannot be bypassed by swapping equipment or relogging.
7. Summons, cobwebs, and teleportation honor protection rules and clean up safely.
8. ExoSpawners drops occur once per eligible kill and dungeon grant commands create matching ingredients.
9. ItemsAdder assets/configuration resolve correctly, with documented fallback behavior.

Deliver the source project, build configuration, plugin metadata, all default configs and recipes, ItemsAdder configuration/assets actually created, installation instructions, commands/permissions, integration notes, and a test report. Produce a compiled JAR if the environment allows it. Report exactly what was built and tested; clearly separate verified behavior from untested integrations. No fake success claims, silent omissions, or placeholder implementations of required features.

## 11. Exact texture registry

The following base64 values are supplied by the user. Preserve them exactly. Each ingredient entry is the corresponding mob value head; each mask entry is the finished wearable mask. A head's texture is visual data, not its security identity.

### Sedge

Ingredient ID: `sheep_value_head`

Ingredient texture:
```text
eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZDMwNTMxZTczMWI3MjFkNjk0NDBmMWUzYjI2ODI0MGYxZmQ4NmIyYWIxYzU4YjhhMGYzNmYxYjAwZTQ4NTY4OSJ9fX0=
```

Crafted mask texture:
```text
eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZjEyZGI0NTQxNmUzNWFlZDM2ZjVjNWM1NTE2NTQ3NDgzNWVmNTNiYmRhNTkzZTI3M2M1Y2Q1NTJkNDM2NDQzIn19fQ==
```


### Wisdom

Ingredient ID: `pig_value_head`

Ingredient texture:
```text
eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNGU5M2ZlMmYyNDQ4NmUwMGMyYzU1ZmZmNmNiYTQ5ZjA1ZTIzN2Q4Y2NjMzYzN2QwZDE1M2UyZjU0NWY3ZmVjMCJ9fX0=
```

Crafted mask texture:
```text
eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZTUzOWUzMjk4YWE0MWU1NzllNWQxOWJmZGYyZTUwMGZlOTUzMDVkODI2YWQxMTc2MWMxYjFmMmU5MTAyYTkxNyJ9fX0=
```


### Canapy

Ingredient ID: `polar_bear_value_head`

Ingredient texture:
```text
eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZGQxN2Y1NGQ1ZDk1ZGEwZjQ4NjZhYWYwZTVkMmQ4NWRjMjdlMmFiMjJjOGQwZjRlNTNlODQ2ZDEwZTg3NTVlZSJ9fX0=
```

Crafted mask texture:
```text
eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZjIyZTNhNTY5ZThkNTgxNDM3YzUwM2UyYWQxZDRiNTkxYmNiODI5MWE3MWVmN2IzNzM4OGVlYTNiMDhlNzIifX19
```


### Trident

Ingredient ID: `zombie_value_head`

Ingredient texture:
```text
eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNGM4MTM5MTYxNzkxMTA2ZmRmZTkwNmNjYWYzOWRhM2FjZWNkYTlhMmY4YTQ1MDJlMWE0YTMwNzg1Y2ViNmEwYiJ9fX0=
```

Crafted mask texture:
```text
eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYWRlMDk1MzMyNzIwMjE1Y2E5Yjg1ZTdlYWNkMWQwOTJiMTY5N2ZhZDM0ZDY5NmFkZDk0ZDNiNzA5NzY3MDJjIn19fQ==
```


### Skelly

Ingredient ID: `skeleton_value_head`

Ingredient texture:
```text
eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjgyODRlMTFlZDYyMTcyYmQ3ZjIzYjIzNGY3NWRiMjliM2M1NTVlN2EzNDQwMTBiYzBiNjM5MjE0YTc5ZDQzYiJ9fX0=
```

Crafted mask texture:
```text
eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYzcwYzdlN2U0OTMyZTdmOGJlZGFlNmZlYTkwZDhlMzFhZTdjMWYyNWVlZjZhYzVlOTJhOTRkN2VjMWM3NTNhZiJ9fX0=
```


### Creepy

Ingredient ID: `creeper_value_head`

Ingredient texture:
```text
eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvN2Y3NjNiODVkYjE3MTRlMGZhNTcyNDY5ZjVhZmMzZWM0ZGMyYWE1Njk3NTEyMTlmNzM2YjU2YTAyMjkzMzVkOCJ9fX0=
```

Crafted mask texture:
```text
eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNWNjNWNhMWRlZTgyYThmNjg2NzI2ZmM2M2MyMjFhZmZhMTNjZjg1YmJiYzg3YzNmZjU4NjFlMjI3NGIzNGYzIn19fQ==
```


### Syder

Ingredient ID: `spider_value_head`

Ingredient texture:
```text
eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvM2YwMTVkNDRmMTdjNWQ4MjRjM2I3M2Q1YmNjMjZhYzM1OGFkYmRhNWVkNDdiZGQwNWNmNzJiZjMxYmY4YWY3OCJ9fX0=
```

Crafted mask texture:
```text
eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZTMzOTg3NGE4MjliODJjMWJjMzc0YjMzOTVhMzEwNzg3Zjk5ODRkN2ZiMGMwZWY3ZTU1ODQ3MjQ5ODM3YTkyYiJ9fX0=
```


### Wolfski

Ingredient ID: `wolf_value_head`

Ingredient texture:
```text
eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNWU1NTBjZjQ2M2M1NzQ1YzM0MjQ3ZjgyMWZkZmQ1ZjdhNWI1ZjA2OTgwMjA1YTI1ZTM0YWQzMWIwMDNhMzQ5MSJ9fX0=
```

Crafted mask texture:
```text
eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjI4ZTU3YzJhZjczZjk0MWFmYjM4OTYyOTAzMDM3Mzc2MjA2ZjBhNjQyMDhkZTk1OWQzMmMxMGFjOWY5NGQifX19
```


### Guard

Ingredient ID: `villager_value_head`

Ingredient texture:
```text
eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzZkMWNmN2U2NjRhZTBkZjVjZGNiZTg3ZGVmN2I2ZmNjZDczMDZlNGNiNjViMWRkOTVhMzRkYzY0ODQ4NDIwYyJ9fX0=
```

Crafted mask texture:
```text
eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzQ4NWI4MGI0YTQ5MzA4ZGEzMzZmNGMwZjU5ZjI2YjNkNjYzYWU1ZTk5OTgxYzI4NmY3MDMzMzBlMDBiMDQzZSJ9fX0=
```


### Golom

Ingredient ID: `iron_golem_value_head`

Ingredient texture:
```text
eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYjI0N2YyNjU4YWViYmIyNzY2MDQ2NjI0ZGE4NWVlMDRjZDJiMThhODI1MjA0NWQ0MjYwYmQ4MWQ2MmM3YjZkOCJ9fX0=
```

Crafted mask texture:
```text
eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMThjZTYxMDU1NTU0YWUzY2Q0YjFhNzdjMDRlZmUxY2RmNTM4NzM4ZmQ2MWJiNTczMzVhZjBiYWQzZDBmODk3ZSJ9fX0=
```


### Stray

Ingredient ID: `stray_value_head`

Ingredient texture:
```text
eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNGZhMWVmNDdkYWVjZmJkYTdlNTUwNWExYmE2NTc5MjZmNjIxMjQ3Yzg3NjFjZDQ2YzkwNzczNjY2MWJiZSJ9fX0=
```

Crafted mask texture:
```text
eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzlkMDkxZDdhNmFkNDcwYjYwZjY0ZGUwMTkwMDhhYWY4YjQyNTZjZmQ4NzJkOWM3NWU3M2E0YzJiOWVmMDgzMSJ9fX0=
```


### Drownie

Ingredient ID: `drowned_value_head`

Ingredient texture:
```text
eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOTA5ODNjNTZjMjZjYzI3M2FhZGIwNWM3NGRjZmJjNjE2ZjY5YTdjMWRiY2JlYzEwMmZmZDhiNjNhZTZiNWYifX19
```

Crafted mask texture:
```text
eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZGU1M2Q1OTU5Mjg1YzIwYjk0OGVkZWM3NjNjMzE1NmRkYWNlY2I5NjgyN2MzYTA2MzQwNDNjYjBmODlkZjA2MiJ9fX0=
```

