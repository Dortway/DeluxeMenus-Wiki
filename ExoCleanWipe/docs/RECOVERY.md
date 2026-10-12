# Backups and recovery

Every job writes its backups **before** changing anything, to

```
plugins/ExoCleanWipe/backups/job-<id>/<uuid or "all">/<integration>/
```

A retried step never overwrites a backup written by an earlier attempt, so the backup always shows the data as it was
before the job. ExoCleanWipe never deletes backups; remove old job folders yourself when you no longer need them.

| Integration | Files in the backup |
|---|---|
| Vanilla | `<uuid>.dat` (player file), `<uuid>.dat_old`, `advancements-<uuid>.json`, `stats-<uuid>.json`, `online-*.items` (live inventory, ender chest, cursor and crafting grid, Paper `ItemStack.serializeItemsAsBytes`) |
| ExcellentEconomy | `balances.json` (per currency); season reset: `all/ExcellentEconomy/data.db.before` (consistent `VACUUM INTO` copy) |
| Essentials | `<uuid>.yml` (userdata file) |
| ESkyblock | `islands.db.before` (consistent copy), `region-r.X.Z.mca`, `entities-…`, `poi-…` of the island |
| Database plugins (Exo*, LightZone, ChunkCollector, EPlayerShop) | `restore.sql` (the exact affected rows as `INSERT OR REPLACE`), `<file>.db.before` (whole database before the job) |
| ExoVaults | the moved `<uuid>.dat*` files |
| EGen, ExoSpawners | `restore.yml` (removed sections), `*.yml.before` |

The audit trail is in `plugins/ExoCleanWipe/audit.log` (one JSON line per action: staff member, target UUIDs, reason,
time, integrations, result) and in the `audit` table of `exocleanwipe.db`.

## Automatic restore: `/wipe restore <job>`

1. `/wipe restore 12` shows what will be restored; confirm with `/wipe confirm <code>`.
2. A RESTORE job locks the accounts again, then:
   * restores player files and ExcellentEconomy balances live;
   * applies every `restore.sql`, vault file and YAML section at the **next server start** (before those plugins load).
3. Restart the server; `/wipe status <restore job>` shows the result. The accounts unlock when it completes.

Rows are put back exactly as they were. Rows with the same key that changed after the wipe are overwritten; rows that
were deleted are inserted again. Other players' rows are never touched.

Not automatic (the restore job reports them as *skipped* with the file location):

* **Cursor / crafting items** of an online target: `online-cursor-and-crafting.items` holds them; give them back
  in game (they are listed in the file's byte form; staff usually recreate them).
* **Essentials**: stop the server, copy `<uuid>.yml` back to `plugins/Essentials/userdata/`.
* **ESkyblock islands** (blocks were cleared): stop the server, then
  1. copy the backed-up `region-r.X.Z.mca` / `entities-…` / `poi-…` files back to
     `world/dimensions/minecraft/eskyblock/region|entities|poi/` (these region files also contain neighbouring islands as
     they were at backup time — only do this if those islands have not changed, or restore from your full world
     backup instead);
  2. re-insert the island rows from `islands.db.before`:
     ```
     sqlite3 plugins/ESkyblock/islands.db
     ATTACH 'plugins/ExoCleanWipe/backups/job-12/<uuid>/ESkyblock/islands.db.before' AS b;
     INSERT OR REPLACE INTO islands SELECT * FROM b.islands WHERE id = '<island id>';
     INSERT OR REPLACE INTO island_members SELECT * FROM b.island_members WHERE island_id = '<island id>';
     INSERT OR REPLACE INTO island_coops SELECT * FROM b.island_coops WHERE island_id = '<island id>';
     INSERT OR REPLACE INTO island_chests SELECT * FROM b.island_chests WHERE island_id = '<island id>';
     ```
  3. start the server.

## Manual restore of one database plugin

With the server stopped:

```
sqlite3 plugins/ExoWoodCube/exowoodcube.db < plugins/ExoCleanWipe/backups/job-12/<uuid>/ExoWoodCube/restore.sql
```

or, to roll the whole file back (this also reverts every other player's changes since the job):

```
copy plugins\ExoCleanWipe\backups\job-12\<uuid>\ExoWoodCube\exowoodcube.db.before plugins\ExoWoodCube\exowoodcube.db
```

## Partial jobs

`/wipe status <job>` lists every step. For each problem:

* **blocked** — an unfinished transaction in that plugin involves the player. Finish or resolve it with that plugin's
  own commands (for example its recovery/review command), then `/wipe retry <job>`.
* **failed** — the console has the full error. Fix the cause (missing file permissions, a locked file on OneDrive,
  a changed schema after a plugin update), then `/wipe retry <job>`. Startup steps run again at the next restart.
* To give up on a step and let the player back in: `/wipe unlock <player>` (needs `exocleanwipe.unlock`). The job
  stays *partial* in the history.

Never edit `exocleanwipe.db` while the server is running.

## Note on OneDrive

The server lives in a OneDrive folder. Sync clients can lock files for a moment; a locked file makes a step fail
(nothing is half-written: every file is written to a temporary file and moved into place). Retry the job, or move the
server out of the synced folder.
