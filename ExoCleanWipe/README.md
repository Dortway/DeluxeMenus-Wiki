# ExoCleanWipe

Staff tool for the **dogo** server (Paper 26.1.2, Java 25) to remove duplicated items, reset player progress,
run season resets and apply punishments that ExoCleanWipe owns. It was written against the plugins and versions
installed on dogo (inventory taken 2026-10-12); see [docs/INTEGRATIONS.md](docs/INTEGRATIONS.md) for exactly what each
integration covers and what it does not.

ExoCleanWipe is a strong set of protections, not a guarantee. It is **not** "unbypassable" or "exploit-proof": items
already given to other accounts, items stored in the world, alternate accounts on new networks and VPNs it does not
know about can all escape it. The limits are listed at the end of this file.

## Installation

1. **Stop the server** and make a full backup of the server folder (ExoCleanWipe also backs up everything it changes,
   but a full backup is the safety net for everything else).
2. Copy `dist/ExoCleanWipe-1.0.0.jar` into `plugins/`.
3. Start the server once. `plugins/ExoCleanWipe/config.yml` is created; Paper downloads `sqlite-jdbc` from Maven
   Central on first start (the same way ESkyblock does).
4. Edit `config.yml`: at least `server.appeal`. Restart (configuration is read at startup only).
5. Give staff the permissions they need (LuckPerms), for example
   `lp group mod permission set exocleanwipe.use true`, `... exocleanwipe.wipe true`, `... exocleanwipe.punish true`.
   `exocleanwipe.wipeall`, `exocleanwipe.restore` and `exocleanwipe.viewip` are `false` by default,
   even for operators. `exocleanwipe.admin` grants everything.
6. Check `/wipe integrations`: it lists every integration, whether it is available and why not.

Try every command on a **staging copy** of the server first (copy the whole folder, change the port, start it). The
checklist in [docs/TESTING.md](docs/TESTING.md) covers what to try there.

## How a wipe runs

```
/wipe Steve all          -> preview (nothing changed; Steve is kicked and frozen so nothing can be moved away)
/wipe confirm K7M2QX     -> job 12 written to the database, Steve locked, wipe starts immediately
   live, no restart      -> player file, ExcellentEconomy, Essentials mail, ESkyblock island,
                            ExoVaults, every Exo plugin, LightZone, EGen, ExoSpawners
   only if needed        -> a part a plugin cannot change live waits for the next server start (see below)
   all steps finished    -> lock released (a /wipe punish suspension stays until it expires)
```

* **Preview first.** Every destructive command shows a preview: each account, what each integration would remove,
  every *other* player affected (island members and co-ops), unfinished transactions that would block, and which
  integrations are not available. Nothing changes until the same staff member types `/wipe confirm <code>` within
  60 seconds (45 seconds for `/wipeall`, which also needs the word `wipeall`).
* **Locking.** Targets are kicked and cannot join while the job runs. ExcellentEconomy balance changes to or from a
  locked account are cancelled. A job only unlocks when every step finished; if a step is blocked or failed the
  account **stays locked** and `/wipe status <job>` explains how to resolve it.
* **No restart needed** (`wipe.live: true`, the default). The Exo plugins keep player data in memory, so their data
  is changed through the plugin itself where it has a way to do it (ExoWoodCube and ExoFarmCube admin remove,
  ExoTools revoke, LightZone backpack invalidation, an empty ExoVaults record written by ExoVaults' own repository
  with the next revision) and otherwise in the database once the kicked player's data has been saved. Every change
  is checked again `wipe.live-settle-seconds` later.
* **When a restart is still needed.** Only that one step waits for the next server start, and the account stays
  locked until then, when: a plugin wrote the data back after the wipe; the player owns placed ExoCubes, chunk
  collectors, player shops or running ExoEconomy boosters (those plugins keep them in memory with no way to remove
  them live); ExoVaults still had the player's vaults loaded; or a plugin was updated and its internals changed.
  Staff are told which step and why (`/wipe status <job>`). A season reset (`/wipeall`) still finishes the
  plugin data at the next start.
* **Offline players** are wiped the same way: persistent data is changed directly, nothing waits for them to join.
* **Backups.** Before anything is changed it is saved under `plugins/ExoCleanWipe/backups/job-<id>/<uuid>/<integration>/`
  (consistent database copies plus `restore.sql` with the exact rows, player files, balances, vault files...). See
  [docs/RECOVERY.md](docs/RECOVERY.md).
* **Restarts and crashes.** Jobs, steps, locks and punishments are stored in `plugins/ExoCleanWipe/exocleanwipe.db`.
  An interrupted job resumes on the next start. Every step is idempotent and keeps the backup of its first attempt.
* **Bounded batches.** At most `limits.batch-size` steps start every `limits.batch-interval-ticks`; file and database
  work runs off the main thread; islands are disbanded one at a time.

## Commands

| Command | Permission | What it does |
|---|---|---|
| `/wipe help` | `exocleanwipe.use` | commands you are allowed to use |
| `/wipe <player> all [+alt:<name>...] [--shared\|--keep-shared]` | `exocleanwipe.wipe` (+ `exocleanwipe.wipe.linked` for `+alt`) | wipe all supported possessions and progress, no ban |
| `/wipe punish <player> <duration> <reason...>` | `exocleanwipe.punish` | wipe + temporary (or `permanent`) suspension, e.g. `/wipe punish Steve 30days duping` |
| `/wipe ippunish <player> [reason...]` | `exocleanwipe.ippunish` | permanent ban of the account and its known addresses; accounts connecting from those addresses are refused. **No wipe.** |
| `/wipe unippunish <player>` | `exocleanwipe.unippunish` | removes that IP punishment, its address bans and the restrictions it caused |
| `/wipe unpunish <player>` | `exocleanwipe.unpunish` | removes account suspensions; a separate IP punishment stays. If something else still keeps the player out (the wipe lock of a `/wipe punish` job until the restart, an IP punishment, a vanilla `/ban`), it says so and how to clear it |
| `/wipeall` | `exocleanwipe.wipeall` | season reset of every online and offline player, no bans |
| `/wipe confirm <code> [wipeall]` / `/wipe cancel <code>` | `exocleanwipe.use` | confirm / cancel your own preview |
| `/wipe links <player>` | `exocleanwipe.links` | name history, addresses, accounts seen on the same addresses |
| `/wipe info <player>` | `exocleanwipe.links` | punishments (active / expired / removed), locks |
| `/wipe status [job]`, `/wipe jobs`, `/wipe integrations` | `exocleanwipe.status` | progress, problems and how to fix them |
| `/wipe retry <job>` | `exocleanwipe.retry` | re-run blocked or failed steps |
| `/wipe unlock <player>` | `exocleanwipe.unlock` | release a lock left by a partial job without finishing it |
| `/wipe restore <job>` | `exocleanwipe.restore` | put a job's backups back (preview + confirm) |

Other nodes: `exocleanwipe.viewip` (full addresses instead of `203.0.x.x`), `exocleanwipe.notify` (staff alerts),
`exocleanwipe.admin` (all). Players without nodes get no access and no tab completions.

Durations: `30days`, `12hours`, `45minutes`, `2w`, `1d12h`, `permanent` (max `limits.max-punish-duration`).
Flags may be placed anywhere after the player name. Player names are validated (`A-Z a-z 0-9 _`, 1–16) before any
lookup; a UUID may be used instead. Reasons have colour codes and control characters removed and are always shown as
plain text, so they cannot add formatting, click events or fake lines.

### Shared islands and linked accounts

* If the target **owns** an ESkyblock island with other members, the preview names every member and co-op and the
  wipe will not run until staff choose `--shared` (disband it for everyone) or `--keep-shared` (keep the island, its
  bank and chest). `wipe.eskyblock.owned-shared-island` can make either the default.
* Membership of someone else's island is simply removed; that island is kept.
* `/wipe links` shows accounts seen on the same addresses. **An address is not proof** that two accounts belong to the
  same person (households, schools, dorms, mobile carriers, public Wi-Fi). Addresses used by more than
  `identity.shared-network-threshold` accounts are marked as shared networks. Linked accounts are only wiped when
  staff add them explicitly with `+alt:<name>` (or `+alt:<uuid>`); the preview warns when a named account has no
  shared address at all.
* The preview also lists other stored profiles of the same name (earlier holders of the name, and the offline-mode
  UUID of the name if data exists for it). They are never included automatically.

## Punishments

ExoCleanWipe punishments live in its own database and are enforced by its own login check
(`AsyncPlayerPreLoginEvent` + `PlayerConnectionValidateLoginEvent`). Vanilla `/ban`, LuckPerms and other plugins'
records are never read or changed, and the unpunish commands only ever touch ExoCleanWipe's own records.

Ban screen (colours and text in `config.yml` → `messages.ban-screen`):

```
⛔ Exity Skyblock
your access to this server has been suspended

reason › duping
ban length › 30 days
remaining › 29 days, 23 hours
reference › ew-000123
appeal › discord.gg/your-invite
```

`remaining` is computed at each login attempt; permanent punishments show `permanent`. Expired suspensions simply stop
applying (and show as `expired` in `/wipe info`). Symbols fall back to ASCII (`[x]`, `>`) with
`appearance.symbols: false`; colours are the hex values in `appearance.colors`.

When a database read fails at login, the connection is refused rather than letting a banned player in.

## Addresses, proxies and VPN checks

* **Proxy check at startup.** dogo currently has no proxy (`spigot.yml bungeecord: false`, Velocity disabled,
  `online-mode=true`), so the address the server sees is the player's. If a proxy is added later, ExoCleanWipe only
  trusts forwarded addresses for Velocity modern forwarding **with a secret** (Paper verifies it); legacy BungeeCord
  forwarding is only trusted after `proxy.allow-legacy-bungeecord-forwarding: true` (firewall the backend first).
  Anything inconsistent disables all address features (nothing recorded, linked or banned by address) and logs why.
  Addresses in `proxy.trusted-proxy-addresses` are never treated as a player's address, so the shared proxy address
  can never link or ban everyone.
* **Never banned:** loopback, private (10/8, 172.16/12, 192.168/16), link-local, CGNAT (100.64/10) and IPv6 ULA
  addresses (`punishments.never-ban-private-addresses`).
* **VPN/proxy detection** (`vpn.*`, off by default) uses the proxycheck.io v2 API: asynchronous, hard timeout
  (`vpn.timeout-ms`), cached in memory and in the database (`vpn.cache-hours`). `vpn.action` is `flag` (alert staff) or
  `deny`; `vpn.on-error` decides what happens during an outage (`allow`, `deny`, `deny-new`). The key can be set with
  the `EXOCLEANWIPE_VPN_KEY` environment variable and is never logged. VPN detection cannot find every VPN or
  residential proxy, cannot identify alternate accounts and cannot guarantee that a banned player does not return.

## Building

Requires JDK 25 and Maven 3.9.

1. Copy the server's plugin jars used at compile time into `libs/` (they are not redistributed here):
   `powershell -ExecutionPolicy Bypass -File tools\copy-libs.ps1` (reads `C:\Users\dortw\OneDrive\Dators\dogo`).
2. `mvn package` → `target/ExoCleanWipe-1.0.0.jar` (runs the tests).

The Paper API comes from `repo.papermc.io` (`26.1.2.build.72-stable`, the build dogo runs).

## Limits (read before relying on it)

* Items moved to unrelated accounts before the wipe cannot be traced; there is no item provenance or full transaction
  history on dogo. Plugin transaction tables are kept untouched so staff can investigate.
* Items stored in the world (chests, shulker boxes, item frames, dropped items, entities) are only removed when they
  are inside the target's island region that is disbanded.
* Placed EGen machines, cubes and player-shop blocks outside the island stay as inactive blocks; their stored contents
  are removed and their item handles become invalid.
* Plugins whose data folder exists but whose jar is **not installed** (ShardBoost, ExoEnchants, ExoArmorTrims,
  ExoLootBoxes, AxHoes, AxSpawners, EnhancedGenerators) are not wiped. If you reinstall one, its old balances come back.
* Live wipes of the Exo plugins reach into those plugins' in-memory state (names checked against the installed
  versions). After a plugin update, a step that no longer matches is moved to the next restart instead of guessing.
* A block (unfinished transaction in another plugin) means staff must resolve it in that plugin; ExoCleanWipe does not
  guess who already paid.
* Preview figures for startup integrations are "as last saved" by those plugins.
* Live API behaviour of ExcellentEconomy, EssentialsX and ESkyblock could not be exercised in this build environment
  (no Minecraft server could be started there); see [docs/TESTING.md](docs/TESTING.md) for the staging checklist.
