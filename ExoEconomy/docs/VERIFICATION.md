# Verification report

## Build

| Step | Result |
|---|---|
| `./build.sh --stubs` with OpenJDK 25.0.4, `--release 25`, `-Xlint:all` | Compiles with **0 errors, 0 warnings** |
| Jar contents | 140 entries: ExoEconomy classes and resources only, no stubs |
| Tests (`CoreTests`, SQLite JDBC 3.53.4.0, ExoSkills 1.0.1 jar) | **36 passed, 0 failed** |
| Price generator (`tools/generate_prices.py`, minecraft-data 26.1) | 1,014 block items: 980 priced, 34 disabled with reasons. **0 arbitrage violations** over 2,142 modelled recipes. |

The Paper API and ExcellentEconomy could not be downloaded in this environment (network policy). The jar was therefore compiled against **compile-only stubs** that reproduce the exact API surface used. The stub kinds and descriptors were cross-checked against ExoSkills 1.0.1's constant pool (compiled against Paper 26.1.2) and the ExcellentEconomy 2.8.0 source.

**Before production, run `build.ps1` against your server.** That compile is what proves every call exists in your exact jars.

## Tested here, in plain Java with a real SQLite database

| Area | Tests |
|---|---|
| Amounts | Rejects negative, NaN, exponent and overflow values. Rounding is always down. |
| Booster rules | Activation scope (personal, global, late joiners). `all` never boosts selling. EXTEND with cap, QUEUE and automatic promotion, REJECT modes, max live, multiplier cap, exact expiry, single expiry event. |
| Booster time | Personal PAUSE. Server PAUSE across a clean restart. Crash downtime counted. Cancel promotes the queue. Re-planning when an extended booster expired mid-payment. Persistence round trip. |
| Transactions | Success commits once, with balance before and after. Insufficient funds. Economy unavailable (nothing journaled). Double and triple clicks (one charge). Validation abort (economy never called). Uncertain outcome (no retry, no blind refund). Post-payment failure → automatic refund once. Refund idempotency and retry after failure. Storage failure (economy untouched). 150 concurrent submissions from 50 players (exactly 50 charges). Shutdown reporting. |
| Recovery | PENDING → UNCERTAIN. SELLING escrow → REVIEW claim linked to its transaction. OPEN escrow → REVIEW or CLAIMABLE. Idempotent recovery. Crash versus clean-shutdown detection. A claim cannot be delivered twice. A newer schema is refused. |
| Rewards | Daily caps (activity and total, counted on accrual, give-back, day reset, reload). Batching carries fractions and restores on refusal. |
| Catalogue | All 1,014 block items present, valid keys, every disabled entry has a reason. |
| ExoSkills | `Progress.combine(skill, 1.0, add\|multiply) == skill` on the real 1.0.1 code, which is the assumption ExoSkillsBridge relies on. |

## Not tested: needs your server

These are implemented, but no Paper server could run here:

| Area | What to check |
|---|---|
| ExcellentEconomy | Connection and currency validation; real deposit/withdraw behaviour, including what `withdraw` does on insufficient funds and whether `ChangeBalanceEvent` cancellation returns `false`. |
| `/sell` clicks | Every click type (shift, number key, offhand, drag, double click, Q-drop), full-inventory claims, disconnect and kick returns, game-mode change, shutdown returns. |
| `/sell` items | Custom item rejection on real items (ExoArmorTrims items, renamed and enchanted items, filled shulkers). |
| Boosters | Menus, purchases with real Shards, announcements. |
| Provenance | Pistons, falling blocks, generators, tree growth, bone meal. |
| Slaying | Spawn reasons, Paper `fromMobSpawner()`, ExoSpawners `StackMobKilledEvent`. |
| Integrations | PlaceholderAPI registration; ExoSkills sale bonus through ExoArmorTrims' service. |

Suggested acceptance pass on a test server:
1. Run `/exoeconomy info`.
2. Mine placed and natural stone and compare the payouts.
3. Plant wheat and harvest it immature, then mature.
4. Kill a spawner mob and a natural mob.
5. Buy each booster type and confirm the EXTEND message.
6. `/sell` with a full inventory, then close, then `/sell claim`.
7. Kill the server during `/sell` and use `/exoeco claims list review`.
8. Run `/exoeco tx list` and `/exoeco audit`.

## Assumptions and unverified points

| Point | Status and mitigation |
|---|---|
| Installed ExcellentEconomy version | **Not provided.** Written for 2.8.0, the version that supports 26.1.2; any other version gets a startup warning. The API is reached by class name, so a mismatch disables economy features with a clear log message instead of crashing. |
| Shards currency id | **Not provided.** Configurable (`shards` by default) and checked at startup; the log lists the available ids. |
| ExcellentEconomy plugin name in `softdepend` | Assumed `ExcellentEconomy`. Load order does not depend on it: ExoEconomy reconnects after all plugins enable and on service registration. |
| ExcellentEconomy `withdraw` with insufficient funds | Unknown, so ExoEconomy never asks for more than the balance it just read. |
| ExcellentEconomy create command syntax and currency YAML keys | Inferred from source (`CreateCommand`, `currencies/<id>.yml`); check with `/execo help`. |
| `net.exospawners.api.StackMobKilledEvent` | Signatures taken from ExoSkills' compiled hook, not from ExoSpawners itself. Bound by reflection and validated at runtime. |
| `net.exoarmor.SaleMultiplierAPI` | Signature taken from ExoSkills' implementation. Bound by reflection; only ExoSkills' own registration is used. |
| `Material` / `InventoryAction` remaining enums in 26.1.2 | Code avoids `EnumMap`/`EnumSet`/`switch` on Paper types, so it works either way. |
| Minecraft 26.1.2 versus 26.1 data | The catalogue was generated from 26.1 registries (minecraft-data has no 26.1.2). At startup ExoEconomy warns about any block item on your server that `prices.yml` does not list; those items are unsellable until priced. |

## Known limitations

- Rewards in the last flush interval (about 2 s) are lost on a crash. The remainder below one Shard is discarded on logout or restart.
- After a crash, `/sell` items are held for review by default. Automatic return could duplicate items if the player's own inventory file had not been saved since they were deposited.
- ExcellentEconomy's API has no transaction ids, so uncertain outcomes need an admin decision. Nothing is guessed or retried.
- Provenance limits are listed in DESIGN.md (WorldEdit, indirect door/bed halves, crash window, pre-install mobs).
- Not Folia-compatible; single server only.

## Follow-up information needed

1. Your installed **ExcellentEconomy and NightCore versions** and the **Shards currency file** (`plugins/ExcellentEconomy/currencies/<id>.yml`).
2. Results of **`build.ps1`** against your server. Any compile error there means an API difference, and I can adjust it immediately.
3. **ExoArmorTrims** and **ExoSpawners** source or jars, to confirm the reflected signatures. Also: does ExoArmorTrims run its own `/sell`? If so, decide which sell command players should use.
4. Whether you want **skill-scaled activity Shards**. That needs a small public method in ExoSkills, for example `public int level(UUID player, Skill skill) { return storage.get(player, skill).level(); }`, which ExoEconomy would then call.
