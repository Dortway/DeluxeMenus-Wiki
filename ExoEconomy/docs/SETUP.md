# Setup

## 1. ExcellentEconomy Shards currency (ExcellentEconomy 2.8.x)

What follows comes from reading the ExcellentEconomy 2.8.0 source. I could not reach its wiki or your server's configuration, so check anything marked *(check)* against `/execo help` or the ExcellentEconomy docs.

- **Requirements:** ExcellentEconomy 2.8.x requires **NightCore**. It supports Paper and Spigot 1.21.8 to 26.2 on Java 25. Folia is not supported.
- **Where currencies live:** each currency is a file in `plugins/ExcellentEconomy/currencies/`. **The file name without `.yml` is the currency id** ExoEconomy needs. On first run ExcellentEconomy creates `coins.yml` (whole numbers) and `money.yml` (decimal).
- **Creating `shards`:** use either option:
  - Run ExcellentEconomy's create command *(check)*: `/execo create shards` (root aliases `execo`, `excellenteco`, `excellenteconomy`).
  - Or copy `coins.yml` to `shards.yml`, then change the name, symbol, column name (must be unique per currency), icon and command aliases. Restart.
- **Recommended Shards settings:**
  - **Whole numbers** (non-decimal). ExoEconomy then pays in whole Shards and carries fractions of activity rewards over to the next deposit. A decimal currency also works; ExoEconomy then uses 2 decimals.
  - **Command aliases** such as `shards`. Players then get ExcellentEconomy's own `/shards balance|pay|...` commands, and ExoEconomy's optional `/shards` command stays off.
  - **A maximum balance, if you want one.** ExoEconomy refuses any deposit that would exceed it rather than losing Shards to clamping.
  - **Vault:** leave as you have it. ExcellentEconomy exposes only one primary currency through Vault, and ExoEconomy does not use Vault.
- **Point ExoEconomy at it:** set `economy.currency-id: shards` in `plugins/ExoEconomy/config.yml`. ExoEconomy checks it at startup:
  - If the currency exists, the log shows `Connected to ExcellentEconomy 2.8.0 currency 'shards' (...)`.
  - If it doesn't, the log shows `ExcellentEconomy has no currency 'shards'. Available: [coins, money]` and rewards, selling and purchases stay disabled. Players see "Shards are temporarily unavailable".

Use ExcellentEconomy for balances, payments, give/take/set and resets. ExoEconomy does not duplicate those commands.

## 2. Install ExoEconomy

1. Build the jar (section 3) and copy `ExoEconomy-1.0.0.jar` into `plugins/`.
2. Optional plugins, used when present:
   - **PlaceholderAPI**
   - **ExoSkills**, for its sell bonus. That bonus needs ExoArmorTrims, because ExoSkills registers it through ExoArmorTrims' API.
   - **ExoSpawners**
3. Start the server. ExoEconomy creates `config.yml`, `rewards.yml`, `boosters.yml`, `prices.yml`, `messages.yml` and `exoeconomy.db`.
4. Set the currency id and restart. Then check `/exoeconomy info`; it shows the economy status, storage and integrations.

## 3. Build

You need JDK 25 for either build.

| Command | Use |
|---|---|
| `.\build.ps1 -ServerDirectory <server>` | **Production build.** Compiles against `<server>/libraries/**.jar` plus `plugins/ExcellentEconomy*.jar`, `nightcore*.jar` and `PlaceholderAPI*.jar`, runs the tests and prints the SHA-256. Same approach as your ExoSkills `build.ps1`. Set `-ExoSkillsJar` if ExoSkills is not at `..\ExoSkills\ExoSkills-1.0.1.jar`. |
| `./build.sh --server <server>` | The same on Linux or macOS. |
| `./build.sh --stubs` | Offline build against `stubs/` (used in this session, because Paper and ExcellentEconomy could not be downloaded). Good for CI and tests. **Do not skip the production build before going live:** it is what proves every API call exists in your exact jars. |

To regenerate the price catalogue for a new Minecraft version:

```
npm pack minecraft-data && tar xzf minecraft-data-*.tgz
python3 -I tools/generate_prices.py package/minecraft-data/data <version> src/main/resources/prices.yml
```

The generator fails if any modelled recipe would sell for more than its inputs.

## 4. Upgrading and backups

- **Backups:** back up `plugins/ExoEconomy/exoeconomy.db` together with ExcellentEconomy's data. The two must be restored from the same moment, or uncertain transactions cannot be reconciled.
- **Migrations:** schema changes run automatically on startup (`meta.schema_version`). An older ExoEconomy build refuses to open a newer database rather than damaging it.
- **Config files:** files are never overwritten. When `config-version` changes, compare yours with the bundled defaults (the log warns).

## 5. Recovery after a crash

- **Interrupted transactions:** on startup, every transaction that was in flight becomes **UNCERTAIN**, and nothing is retried. Run `/exoeconomy tx list uncertain`, inspect each one with `/exoeconomy tx inspect <id>`, and compare it with ExcellentEconomy's `operations.log` or the player's balance. Then resolve it with the matching action:
  - `charged` / `not-charged` / `refund` for booster purchases
  - `paid` / `not-paid` / `pay` for sales
  - `paid` / `not-paid` for refunds and payouts
- **Items left in `/sell`:** items still in a sell menu become claims. With the default `selling.crash-recovery: hold-for-review`, check `/exoeconomy claims list review` and use `approve` (the player collects the items with `/sell claim`) or `discard ... confirm`. Review is the default because after a crash the player's own inventory file may still contain the same items.
- **Storage failures:** if a storage write fails, purchases, selling and refunds pause. Activity rewards keep working. Fix the disk problem, then run `/exoeconomy storage`.
