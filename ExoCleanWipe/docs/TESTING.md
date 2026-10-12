# Test results

`mvn package` on JDK 25.0.4 (Maven 3.9.11), 2026-10-12, against the Paper API `26.1.2.build.72-stable` jar taken from
dogo's `libraries/` folder and the installed ExcellentEconomy 2.8.0, nightcore 2.16.4, EssentialsX 2.22.1-dev+12 and
ESkyblock 2.0.0 jars.

**67 tests, 0 failures, 0 errors.** Two real defects were found by the tests and fixed before release: a `§l` colour
code in a reason left an `l` behind, and the test environment exposed a missing Adventure logger class (test-only).

No real wipe was run and nothing was deployed to the live server. Every database test uses a fresh SQLite file built
from the plugin's own `CREATE TABLE`/`ALTER TABLE` statements extracted from the installed jar.

| Requirement | Test(s) | Result |
|---|---|---|
| Wipe rules valid for every installed plugin version (preview, wipe, season reset) | `PluginSpecsTest.everyRuleRunsAgainstRealSchema` ×15 | pass |
| Target's rows removed/marked, other players untouched, history kept | `exoSkills`, `exoWoodCubeMarksRemovedAndClearsStorage`, `exoCubes…`, `exoFarmCube…`, `exoMultiTools…`, `exoNightMarket…`, `exoEconomy…`, `lightZone…`, `exoTools…`, `exoBoxesAndDailySpinner…` | pass |
| Unfinished transactions block (no guessing who paid) | `openLedgerBlocksSinglePlayerButNotSeasonReset`, `exoTokenShopOnlyBlocks`, `exoEconomy…`, `chunkCollector…`, `ePlayerShop…` | pass |
| Failed integration: unexpected schema fails, missing database is "nothing stored" | `schemaMismatchFailsInsteadOfGuessing`, `missingDatabaseIsNothingStored` | pass |
| Repeated execution is safe, first backup kept | `exoSkills` (second run), `exoVaults…` (second run), `offlineWipe…` (backup still holds items) | pass |
| Restore script restores exact data (newlines, quotes, SQL-looking text, blobs) | `restoreScriptHandlesNewlinesQuotesAndBlobs`, restores in several tests | pass |
| Offline wipe of player data (inventory, armour/offhand, ender chest, XP, mount storage, ExoVaults markers, `.dat_old`, advancements) | `VanillaPlayerDataTest` (5) | pass |
| Vaults (every vault, backups, corrupt copies) | `exoVaultsMovesEveryRecordFileAndRestores` | pass |
| EGen chest storage, ExoSpawners progress, ChunkCollector snapshot owner, EPlayerShop JSON owner | `FileIntegrationsTest` (5) | pass |
| Restart recovery: job survives a restart, startup steps run once | `jobSurvivesRestartAndStartupStepsRunOnce` | pass |
| Partial failure keeps the account locked; retry finishes and unlocks; successful steps not repeated | `failedStepKeepsAccountLockedUntilRetrySucceeds` | pass |
| Uninstalled plugin reported as skipped with the reason | `stepsForUninstalledPluginsAreSkippedWithReason` | pass |
| Season reset holds the global lock; 250-row reset and restore | `seasonResetHoldsGlobalLockAndRealSpecWorksEndToEnd` | pass |
| Punishment created atomically with its job | `punishmentIsCreatedAtomicallyWithTheJob` | pass |
| Ban screen: reason, duration "30 days", remaining "29 days, 23 hours", reference, appeal; expired ban lets the player in | `temporaryBanShowsAccurateScreenAndExpires` | pass |
| `unpunish` removes only account punishments, not the IP punishment | `unpunishRemovesOnlyAccountPunishmentsNotIpPunishments` | pass |
| `unpunish` tells staff what still blocks the player (wipe lock until restart, ip punishment, vanilla ban) | `unpunishExplainsWhatStillBlocksThePlayer` | pass |
| `ippunish` restricts other accounts on the address, never bans private addresses, `unippunish` releases them | `ipPunishRestrictsAccountsOnThoseAddressesAndUnipPunishReleasesThem` | pass |
| Locked / season-reset accounts cannot join | `lockedAccountsAndSeasonResetCannotJoin` | pass |
| Moving currency into/out of a locked account during a wipe | `lockedAccountsCannotReceiveOrSendCurrency` | pass |
| VPN: cache (memory + database), timeout bound, flag/deny, outage policy, API key never in errors | `vpnLookupsAreCachedTimedOutAndNeverLeakTheKey`, `vpnPolicies` | pass |
| Proxy handling: direct, Velocity with/without secret, legacy BungeeCord, both enabled, forced mode mismatch, proxy address never used as player address, private ranges | `proxyForwardingValidation` | pass |
| Linked accounts, shared networks, name history | `linkReportMarksSharedNetworks` | pass |
| Permissions: ordinary players no access, one node per action, `+alt` needs its own node, permission-aware tab completion | `CommandPermissionTest` | pass |
| Argument validation and injection: durations, names, reasons with colour codes / MiniMessage tags / click events | `readableDurations`, `namesAndReasonsAreValidated`, `invalidNamesAreRejectedBeforeAnyLookup`, `punishValidatesDurationAndCleansReason`, `userTextCannotInjectFormattingOrClicks` | pass |
| Confirmation codes bound to the staff member, single use, expire | `confirmationCodesAreBoundToOwnerAndExpire` | pass |
| ASCII fallback symbols, colour validation | `asciiFallbackSymbols` | pass |

## Not testable in the build environment

The build environment could not download the Minecraft server, so no Paper server was started. The following were
therefore **not** executed and must be checked on a staging copy:

* live calls into ExcellentEconomy (`setBalanceAsync`), EssentialsX (mail) and ESkyblock (disband, membership);
* kick timing (player file saved before the vanilla step), the repeating batch task and the event registration;
* the real player file layout of 26.1.2 (the editor refuses unknown layouts and verifies after writing, so a
  difference shows up as a *failed* step, not as silent damage);
* `onLoad` ordering relative to the Exo plugins (declared with `loadbefore`).

## Staging checklist

Copy the whole `dogo` folder (stop the server first), set another `server-port`, install the jar, start it.
Use two test accounts (A, B) and a third if possible (C).

1. `/wipe integrations` — every installed plugin is listed as available; the not-installed ones are explained.
2. **Online wipe:** A holds items in inventory, armour, offhand, cursor (hold an item in an open inventory), ender chest,
   `/pv 1`–`/pv 5`, money/shards/tokens, an island with a chest and bank, a cube, a backpack. `/wipe A all` → preview
   lists all of it, A is kicked. `/wipe confirm <code>`. A cannot join. Restart. `/wipe status <job>` → completed.
   A joins: everything is empty, `/pv` vaults empty, balances at start values, no island.
3. **Offline wipe:** same as 2 but A is offline the whole time.
4. **Shared island:** B is a member of A's island. `/wipe A all` → preview names B and refuses a code until `--shared`
   or `--keep-shared` is given.
5. **Linked account:** A and C join from the same network. `/wipe links A` shows C. `/wipe A all +alt:C` includes C;
   without `+alt` C is untouched.
6. **Punish / expiry:** `/wipe punish A 2minutes testing` → ban screen with remaining time; after 2 minutes (and after
   the job completed) A can join. `/wipe punish A 30days duping` → `/wipe unpunish A` → A can join.
7. **IP punish:** `/wipe ippunish A evasion` → preview lists C as affected; confirm; C (same network) is refused with the
   network screen; `/wipe unpunish A` does **not** let A in; `/wipe unippunish A` lets A and C in.
8. **Moving items during a wipe:** during step 2, have B try `/pay A`, deposit to A's island, buy from A's shop: payments
   to A are cancelled (console logs "blocked a … balance change"), A cannot join to move anything.
9. **Failed integration:** make `plugins/ExoSkills/skills.db` read-only (or rename a table in a copy), wipe A, restart:
   the job is *partial*, A stays locked, `/wipe status` explains; fix it, `/wipe retry <job>`, restart → completed.
10. **Restart recovery:** confirm a wipe and stop the server immediately (`stop`) before it finishes. Start again: the
    job resumes and completes; nothing is applied twice.
11. **Restore:** `/wipe restore <job>` → confirm → restart → A's items, vaults and balances are back.
12. **Season reset:** `/wipeall` → `/wipe confirm <code> wipeall` → everyone kicked, nobody can join, restart → completed.
13. **Formatting:** `/wipe punish A 1minutes <red>x</red><click:run_command:'/op A'>y` → the ban screen shows the text
    literally, nothing is clickable.
