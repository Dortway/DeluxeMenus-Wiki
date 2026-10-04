# ExoBlackSmith test report

**Build:** `mvn package` → `target/ExoBlackSmith-1.0.0.jar` (also copied to `dist/`).
**Result: 54 tests, 54 passed, 0 failed, 0 skipped.**

## Environment

* Java 21 (OpenJDK 21.0.11), Maven 3.9.11.
* Compiled against **Paper API 1.21.11-R0.1-SNAPSHOT**. The PaperMC Maven repository was blocked from the build
  environment, so the API was compiled locally from the PaperMC source branch `ver/1.21.11`, with Brigadier built from
  Mojang's public source. Every API call in the plugin is checked against that real API at compile time.
* Integration tests run on **MockBukkit `mockbukkit-v1.21:4.116.3`**, built for Paper 1.21.11.
* ItemsAdder API: `beer.devs:itemsadder-api:4.0.18-beta-10` from Maven Central (compile only).
* A bytecode-verification check (`-Xverify:all`) confirmed the main plugin classes link **without** ItemsAdder on the
  classpath, while the ItemsAdder bridge class stays isolated.

**Not done: no live Paper server was run.** The server jar, ItemsAdder and ExoSpawners could not be downloaded from the
build environment. MockBukkit simulates the Bukkit API but not vanilla game mechanics (physics, real combat, potion
ticking, resource packs). Anything below marked *untested* needs a live check.

### A note on skipped tests

MockBukkit reports API methods it has not implemented as **skipped** tests, not failures. Every skip found during
development was investigated rather than ignored:
* `Material#getEquipmentSlot`, `Inventory#getHolder`, `Block#isPassable` (solid blocks only),
  `LivingEntity#setRemoveWhenFarAway`, `setCanPickupItems` and `Damageable#heal(double, RegainReason)` were replaced or
  given fallbacks that behave the same on Paper.
* `Bukkit#selectEntities` (vanilla `@p` selectors) could not be exercised, so selector support is untested.

The final run has **zero** skips; `tools/test-report.py` lists every skip with its cause.

## Required verification points

| # | Requirement | Status | Evidence |
|---|---|---|---|
| 1 | Every base item and upgrade has a reachable recipe | **Verified** (config level) | `DefaultConfigTest.everyRequiredItemHasAReachableRecipe`: 12 armor, 12 mask level-1s plus 15 mask level-ups, 27 rune tiers, 2 upgrade materials and 10 totems all have recipes and are reachable from vanilla items and heads. The loader also enforces this at every load/reload (`unreachableOrMissingRecipesAreRejected`). |
| 2 | Exact quantities charged; forged lookalikes rejected | **Verified** | `CraftPlannerTest` (split stacks, forged stacks untouched); `PluginFlowTest.craftsTierOneRuneConsumingExactly120Heads` (74 creeper + 61 skeleton heads → exactly 60+60 taken); `forgedLookalikesAreRejectedAndNotConsumed` (bad signature, edited tier and plain heads are all rejected and left in place); `noSpaceLeavesInventoryUntouched` (exact gold, diamond and head counts). |
| 3 | No multiple outputs for one payment | **Verified** for the tested interactions | `GameplayTest.rapidConfirmClicksCraftExactlyOnce` (5 clicks in one tick → 1 craft); `closedMenuNeverExecutesAQueuedClick`; `CraftPlannerTest.repeatedPlanningOnUnchangedInventoryNeverDoublePays` and `oneStackNeverPaysForTwoRequirements`; `menusAreLockedAgainstEveryClickType` (left, shift, number key, offhand swap, double-click, drop and middle click are all cancelled in both inventories, and no icon leaks). |
| 4 | Rune effects trigger on intended causes; slot limits and stacking | **Partly verified** | Verified: `runeApplicationEnforcesSlotsDuplicatesAndCapacity` (incompatible slot, duplicate, 4th rune, rune consumed only on success); `featherWardReducesOnlyFallDamage`; `phoenixAuraReducesFireAndLavaButNeverGrantsImmunity` (exact multiplicative math, never zero); `blastRuneDoesNotAffectOrdinaryExplosions`; `runeStackingDefaultsToHighestTier`; `ReductionMathTest` (cap, no healing). **Untested:** real end crystal / respawn anchor events, `isCritical()` from real combat, mace smash, elytra collisions, the Totem Surge dash, the Void Stride speed feel and Tidal Breath underwater detection. |
| 5 | Masks clean up effects and health modifiers | **Verified** | `maskEffectsAndHealthApplyOnceAndCleanUp` (+3 hearts applied once across repeated syncs, removed on unequip); `strongerExternalEffectsAreNeverErasedAndWeakerOnesAreRestored`; `retiredOrUnsignedMasksGrantNothing`. Death, respawn and quit paths are implemented but only exercised indirectly. |
| 6 | Cooldowns can't be bypassed by swapping or relogging | **Verified** (persistence) | `cooldownsSurviveRestartAndAreKeyedByAbility`: a fresh service instance reads the cooldown from player data. Keys are per ability, not per item. `canapyHealsRespectingMaxHealthAndCooldown`, `creepyProcRespectsCooldownAndCancelledPvp`. |
| 7 | Summons, cobwebs and teleports honour protection; clean up safely | **Partly verified** | Verified: `summonsOnlyChaseTheirTargetAndLeaveNoDrops` (can't target the owner, can't damage non-targets, non-persistent, removed on owner logout); `temporaryCobwebsExpireWithoutOverwritingLaterChanges`; `headsAndMasksCannotBePlaced`. **Untested:** real WorldGuard/GriefPrevention responses to the synthetic `BlockPlaceEvent` and to owner-attributed summon damage; Skelly arrow flight and landing. |
| 8 | ExoSpawners drops once per kill; dungeon commands create matching ingredients | **Partly verified** | Verified: `consoleGrantsAuthenticHeadsAndItems` (console give/givehead produce authentic items; invalid input gives nothing); `dropApiGrantsAuthenticHeadsForRealDeathsOnly` (chance scales with real deaths, killer required, unknown mobs give nothing); the per-entity de-duplication is implemented. **Not verified: ExoSpawners itself.** Its API could not be inspected, so the stacked-mob binding is configuration-driven and disabled by default. |
| 9 | ItemsAdder assets resolve; fallback documented | **Not verified** with ItemsAdder | The bridge compiles against the real ItemsAdder API and is isolated, and the fallback visuals (vanilla materials, item models, textures) are used in all tests. The ItemsAdder YAML format could not be checked against current docs, and no textures exist yet. See README §9. |

## Other checks

* `DefaultConfigTest.texturesArePreservedExactly`: all 24 base64 textures in `heads.yml`/`masks.yml` are byte-identical to
  the development prompt. Each mask maps to its ingredient head, and ingredient and mask textures are always different.
* `DefaultConfigTest.runeRecipesFollowTheSpecification`: 15 heads in each outer cell, an empty center, a distinct head
  pair per rune, and upgrade recipes with the previous tier in the center plus 120 legendary/fabled upgrades.
* `DefaultConfigTest.runeValuesMatchTheSpecification` and `masksMatchTheSpecification`: exact values, levels and
  cooldowns.
* `DefaultConfigTest.invalidConfigIsRejectedWithFileAndKey`, `syntaxErrorsAreReported`, `wrongTotemIsRejected` and
  `PluginFlowTest.brokenReloadKeepsThePreviousConfiguration`: invalid config is reported by file and key, and a broken
  reload leaves the live config untouched.
* `PluginFlowTest.maskUpgradeConsumesOldMaskOnceAndRetiresItsId`: an upgrade consumes the mask, the totem and 105 heads;
  the other mask's totem is untouched; a duplicated copy of the consumed mask is dead.
* `GameplayTest.setBonusNeedsChestLegsBootsAndIgnoresHelmet`: a mask in the helmet slot still completes the set.
* `GameplayTest.tridentMaskAddsDamageOnlyToTridentHits`.
* `PresentationTest`: every default item at every level renders without unresolved placeholders, and armor shows
  three `+ rune slot` lines that update after socketing. Sample output goes to `target/lore-samples.txt`.

## Recommended live-server checklist

1. Start Paper 1.21.11 with only ExoBlackSmith and confirm a clean startup log.
2. Crafting: give heads with `/exoblacksmith givehead`, craft a rune and a mask in `/blacksmith`, then try shift-clicks,
   number keys and spam-clicking on the menu.
3. Combat: crystal PvP and an anchor explosion with Blast Rune; critical sword hits with Hardened Shell; a mace smash;
   flying into a wall with an elytra; lava with Phoenix Aura; a totem pop with Totem Surge.
4. Masks: wear and remove Golom and check hearts and effects; die, respawn and relog; drink a Strength II potion and
   then wear Golom.
5. Abilities in a WorldGuard region with `pvp deny` and `build deny`: Syder webs, Wolfski/Guard summons, Skelly arrows.
6. With ItemsAdder: install the content folder and textures, `/iazip`, and confirm armor and rune visuals.
7. With ExoSpawners: kill single and stacked spawner mobs and check exactly one roll per real death; configure the
   custom event if needed.
