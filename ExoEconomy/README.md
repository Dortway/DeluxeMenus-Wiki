# ExoEconomy

Shards rewards for your Paper 26.1.2 server, with ExcellentEconomy as the bank:

- **Activity rewards** for mining natural blocks, chopping tree logs, harvesting fully grown crops and killing eligible mobs. Custom plugins can add their own activities through the API. Every deposit shows a chat line and an action bar message; both can be turned off server-wide, and players can turn them off for themselves with `/shardnotify`.
- **`/sell`**: a menu where players deposit plain vanilla items and confirm a sale. **It pays in money** (Vault by default, or any ExcellentEconomy currency). Prices come from a catalogue that lists every vanilla block item in 26.1.
- **ExoCubes / ExoWoodCube**: mining ore cubes pays mining rewards and chopping wood cubes pays woodcutting rewards. Each plugin has its own `reward-multiplier`. Payment goes through their harvest events: ExoCubes' built-in `CubeHarvestEvent`, and a `WoodCubeHarvestEvent` that ExoWoodCube gets from the patch in [integrations/ExoWoodCube](integrations/ExoWoodCube).
- **`/boosters`**: personal and global timed boosters bought with Shards, with stacking rules, queues, persistence and announcements.

**ExcellentEconomy owns the Shards currency and every balance, and your money economy (through Vault) owns money balances.** ExoEconomy never stores, caches or modifies balances itself. It asks ExcellentEconomy's native currency API to deposit or withdraw, and keeps only its own records: boosters, a transaction journal, sell escrow and claims, block provenance and daily counters.

## Detected setup

| | Value | Source |
|---|---|---|
| Server | Paper, Minecraft 26.1.2 | ExoSkills 1.0.1 `plugin.yml` (`api-version: 26.1.2`) and Paper/Adventure API use in its bytecode |
| Java | 25 | ExoSkills class files (major 69), your `build.ps1` (`jdk-25.0.2`, `--release 25`) |
| Build | `javac` + `jar` PowerShell script against `<server>/libraries` and plugin jars | your `build.ps1` |
| ExcellentEconomy | 2.8.0 (master), API `su.nightexpress.excellenteconomy.api.ExcellentEconomyAPI` via ServicesManager | GitHub source, `nulli0n/ExcellentEconomy`. **Your installed version and Shards currency id were not provided; see docs/SETUP.md** |
| ExoSkills | 1.0.1: mining/farming/slaying skill XP, `SaleMultiplierAPI` sell bonus (with ExoArmorTrims), ExoSpawners stack kills | decompiled jar + your sources |

## Quick start

1. Make sure ExcellentEconomy 2.8.x has a Shards currency (see [docs/SETUP.md](docs/SETUP.md)).
2. Build against your server: `.\build.ps1 -ServerDirectory C:\path\to\server` (or `./build.sh --server /path/to/server`).
3. Put `ExoEconomy-1.0.0.jar` in `plugins/` and start the server once.
4. Set `economy.currency-id` in `plugins/ExoEconomy/config.yml` to your currency's id, then restart. The log says `Connected to ExcellentEconomy ... currency 'shards'`, or lists the available ids if yours is wrong.
5. Review `rewards.yml`, `boosters.yml` and `prices.yml`, then run `/exoeconomy audit`.

## Documentation

- [docs/SETUP.md](docs/SETUP.md): ExcellentEconomy Shards setup, installation, building, upgrading.
- [docs/REFERENCE.md](docs/REFERENCE.md): commands, permissions, configuration, placeholders, API and events.
- [docs/DESIGN.md](docs/DESIGN.md): how transactions, recovery, provenance, exploit protection and threading work. It also covers ExoSkills combination rules and crash windows.
- [docs/VERIFICATION.md](docs/VERIFICATION.md): build and test results, what is verified versus assumed, known limitations and follow-up.

## Layout

```
src/main/java/net/exoeconomy/
  api/            public API + events (ExoEconomyAPI service)
  core/           server-independent logic: Money, boosters, transaction coordinator, caps, batching
  storage/        SQLite journal, boosters, escrow/claims, provenance, migrations, recovery
  economy/        ExcellentEconomy gateway (the only code touching ExcellentEconomy types)
  reward/         activity listeners, block provenance tracking, reward pipeline
  booster/        booster service and menus
  sell/           /sell menu, item inspection, price catalogue, claims, recipe audit
  integration/    ExoSkills, ExoSpawners, PlaceholderAPI
  command/        /sell, /boosters, /exoeconomy, optional /shards
src/main/resources/   plugin.yml, config.yml, rewards.yml, boosters.yml, prices.yml, messages.yml
src/test/java/        CoreTests (40 tests)
tools/generate_prices.py   regenerates prices.yml from minecraft-data with the no-arbitrage check
stubs/            compile-only API stubs for offline builds (never shipped)
```
