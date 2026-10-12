package dev.exo.cleanwipe.integrations.sql;

import static dev.exo.cleanwipe.integrations.sql.SpecIntegration.blocker;
import static dev.exo.cleanwipe.integrations.sql.SpecIntegration.delete;
import static dev.exo.cleanwipe.integrations.sql.SpecIntegration.update;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Wipe rules for the custom plugins installed on dogo, written against their actual schemas (decompiled from the
 * installed jars, cross-checked with the source folders in plugins/*-Plugin). Every table, column and state value
 * used here exists in those versions; see docs/INTEGRATIONS.md for the reasoning behind each rule.
 *
 * <p>Conventions: history tables (audit, ledgers, journals of finished transactions) are never touched, so staff
 * keep the transaction trail. Where a plugin has its own "removed / revoked / invalidated" state it is used instead
 * of deleting rows, so any copied item handle of that object is rejected by the plugin itself.
 */
public final class PluginSpecs {

    private PluginSpecs() {
    }

    public static Optional<SpecIntegration> create(String id, Path db) {
        return Optional.ofNullable(switch (id) {
            case "ExoBoxes" -> new SpecIntegration(id, db,
                    "box key balances, unclaimed box rewards, unfinished box openings",
                    List.of("balances", "claims", "openings", "opening_rewards"),
                    List.of(),
                    List.of(
                            delete("unclaimed box rewards", "claims", "player_uuid = ?", "1=1"),
                            delete("box key balances", "balances", "player_uuid = ?", "1=1"),
                            delete("rewards of unfinished openings", "opening_rewards",
                                    "opening_id IN (SELECT id FROM openings WHERE player_uuid = ? AND state NOT IN ('DELIVERED','FAILED'))",
                                    "opening_id IN (SELECT id FROM openings WHERE state NOT IN ('DELIVERED','FAILED'))"),
                            delete("unfinished openings", "openings",
                                    "player_uuid = ? AND state NOT IN ('DELIVERED','FAILED')",
                                    "state NOT IN ('DELIVERED','FAILED')")),
                    null);

            case "ExoCubes" -> new SpecIntegration(id, db,
                    "generator cubes owned by the player and the ores stored in them",
                    List.of("cubes", "cube_items", "ledger"),
                    List.of(blocker("pending cube ledger entries", "ledger",
                            "status = 'PENDING' AND (actor = ? OR cube_id IN (SELECT id FROM cubes WHERE owner = ?))")),
                    List.of(
                            delete("stored cube items", "cube_items", "cube_id IN (SELECT id FROM cubes WHERE owner = ?)", "1=1"),
                            delete("generator cubes", "cubes", "owner = ?", "1=1")),
                    new SpecIntegration.Placed("generator cube(s)", "SELECT world, x, y, z FROM cubes WHERE owner = ? AND state = 'PLACED'"));

            case "ExoWoodCube" -> new SpecIntegration(id, db,
                    "wood cubes owned by the player (marked removed) and the logs stored in them",
                    List.of("cubes", "cube_items", "ledger"),
                    List.of(blocker("open wood cube ledger entries", "ledger",
                            "status IN ('PENDING','AMBIGUOUS') AND (actor = ? OR payee = ? OR cube_id IN (SELECT id FROM cubes WHERE owner = ?))")),
                    List.of(
                            delete("stored logs", "cube_items", "cube_id IN (SELECT id FROM cubes WHERE owner = ?)", "1=1"),
                            update("wood cubes", "cubes", "state = 'REMOVED', token = NULL, version = version + 1, updated_at = {now}",
                                    "owner = ? AND state <> 'REMOVED'", "state <> 'REMOVED'")),
                    new SpecIntegration.Placed("wood cube(s)", "SELECT world, x, y, z FROM cubes WHERE owner = ? AND state = 'PLACED'"));

            case "ExoFarmCube" -> new SpecIntegration(id, db,
                    "farm cubes owned by the player (marked removed, stored kelp cleared, cube tokens revoked)",
                    List.of("cubes", "tokens", "ledger"),
                    List.of(blocker("open farm cube ledger entries", "ledger",
                            "status IN ('PENDING','AMBIGUOUS') AND (player = ? OR cube_id IN (SELECT id FROM cubes WHERE owner = ?))")),
                    List.of(
                            update("farm cube tokens", "tokens", "state = 'REVOKED', updated_at = {now}",
                                    "state = 'ISSUED' AND cube_id IN (SELECT id FROM cubes WHERE owner = ?)", "state = 'ISSUED'"),
                            update("farm cubes", "cubes",
                                    "state = 'REMOVED', world = NULL, x = NULL, y = NULL, z = NULL, active = 0, kelp = 0, dried_kelp = 0, "
                                            + "dried_kelp_block = 0, version = version + 1, updated_at = {now}",
                                    "owner = ? AND state <> 'REMOVED'", "state <> 'REMOVED'")),
                    new SpecIntegration.Placed("farm cube(s)", "SELECT world, x, y, z FROM cubes WHERE owner = ? AND state IN ('PLACED','PLACING')"));

            case "ExoDaily" -> new SpecIntegration(id, db,
                    "daily reward cycle, assigned and claimed days",
                    List.of("players", "assignments", "claims"),
                    List.of(blocker("daily reward deliveries in progress", "claims", "state IN ('DELIVERING','UNCERTAIN') AND (uuid = ?)")),
                    List.of(
                            delete("daily claims", "claims", "uuid = ?", "1=1"),
                            delete("daily assignments", "assignments", "uuid = ?", "1=1"),
                            delete("daily reward profile", "players", "uuid = ?", "1=1")),
                    null);

            case "ExoDailySpinner" -> new SpecIntegration(id, db,
                    "bonus spins, unclaimed spinner rewards and undelivered spin results",
                    List.of("players", "pending_rewards", "spins"),
                    List.of(
                            blocker("spinner rewards being claimed", "pending_rewards", "status IN ('CLAIMING','UNCERTAIN') AND (player_uuid = ?)"),
                            blocker("spins being delivered", "spins", "status IN ('DELIVERING','UNCERTAIN') AND (player_uuid = ?)")),
                    List.of(
                            delete("unclaimed spinner rewards", "pending_rewards", "player_uuid = ? AND status = 'PENDING'", "status = 'PENDING'"),
                            delete("undelivered spins", "spins", "player_uuid = ? AND status = 'RESERVED'", "status = 'RESERVED'"),
                            delete("spinner profile (bonus spins)", "players", "uuid = ?", "1=1")),
                    null);

            case "ExoEconomy" -> new SpecIntegration(id, db,
                    "unclaimed sell payouts and items, escrowed items, owned boosters, daily earning caps",
                    List.of("claims", "escrow", "boosters", "activity_daily", "transactions"),
                    List.of(blocker("open economy transactions", "transactions", "state IN ('PENDING','UNCERTAIN') AND (player = ?)")),
                    List.of(
                            delete("unclaimed claims", "claims", "player = ?", "1=1"),
                            delete("escrowed items", "escrow", "player = ?", "1=1"),
                            delete("owned boosters", "boosters", "owner = ?", "owner IS NOT NULL"),
                            delete("daily earning counters", "activity_daily", "player = ?", "1=1")),
                    null);

            case "ExoMultiTools" -> new SpecIntegration(id, db,
                    "multitool shards, owned multitools (quarantined), daily counters and sell caps",
                    List.of("players", "tools", "daily_counters", "sell_ledger", "transactions", "milestone_claims"),
                    List.of(
                            blocker("open multitool transactions", "transactions", "status IN ('PENDING','UNCERTAIN') AND (player = ?)"),
                            blocker("open milestone claims", "milestone_claims",
                                    "status IN ('PENDING','UNCERTAIN') AND (tool_id IN (SELECT id FROM tools WHERE owner = ?))")),
                    List.of(
                            update("multitools", "tools", "status = 'QUARANTINED', version = version + 1, updated_at = {now}",
                                    "owner = ? AND status <> 'QUARANTINED'", "status <> 'QUARANTINED'"),
                            delete("multitool profile (shards)", "players", "uuid = ?", "1=1"),
                            delete("daily counters", "daily_counters", "uuid = ?", "1=1"),
                            delete("sell caps", "sell_ledger", "uuid = ?", "1=1")),
                    null);

            case "ExoNightMarket" -> new SpecIntegration(id, db,
                    "undelivered night market purchases (cancelled, queued purchases voided)",
                    List.of("deliveries", "transactions"),
                    List.of(blocker("night market purchases awaiting review or refund", "transactions",
                            "state IN ('RESERVED','REVIEW','REFUNDING') AND (player_uuid = ?)")),
                    List.of(
                            update("undelivered purchases", "deliveries", "cancelled_at = {now}",
                                    "player_uuid = ? AND delivered_at IS NULL AND cancelled_at IS NULL",
                                    "delivered_at IS NULL AND cancelled_at IS NULL"),
                            update("queued purchases", "transactions",
                                    "state = 'VOIDED', note = 'voided by exocleanwipe', updated_at = {now}",
                                    "player_uuid = ? AND state = 'QUEUED'", "state = 'QUEUED'")),
                    null);

            case "ExoQuests" -> new SpecIntegration(id, db,
                    "quest points and current daily quests",
                    List.of("players", "daily_assignments", "purchases"),
                    List.of(blocker("quest shop purchases being delivered", "purchases",
                            "state IN ('PENDING','DELIVERING','NEEDS_REVIEW') AND (uuid = ?)")),
                    List.of(
                            delete("quest points", "players", "uuid = ?", "1=1"),
                            delete("daily quests", "daily_assignments", "uuid = ?", "1=1")),
                    null);

            case "ExoSkills" -> new SpecIntegration(id, db,
                    "skill levels and experience",
                    List.of("progress"),
                    List.of(),
                    List.of(delete("skill progress", "progress", "uuid = ?", "1=1")),
                    null);

            case "ExoTokenShop" -> new SpecIntegration(id, db,
                    "no stored possessions (tokens are an ExcellentEconomy currency); refuses while a purchase is unfinished",
                    List.of("transactions"),
                    List.of(blocker("unfinished token shop purchases", "transactions",
                            "state IN ('PENDING','DEBITED','REWARD_ATTEMPTED','REFUNDING','NEEDS_REVIEW') AND (player_uuid = ?)")),
                    List.of(),
                    null);

            case "ExoTools" -> new SpecIntegration(id, db,
                    "upgradeable tools owned by the player (revoked)",
                    List.of("tools", "ledger"),
                    List.of(blocker("open tool ledger entries", "ledger",
                            "status IN ('PENDING','AMBIGUOUS','REFUND_FAILED') AND (player = ? OR tool_id IN (SELECT id FROM tools WHERE owner = ?))")),
                    List.of(update("tools", "tools",
                            "state = 'REVOKED', note = 'revoked by exocleanwipe', version = version + 1, updated_at = {now}",
                            "owner = ? AND state IN ('ACTIVE','LOCKED')", "state IN ('ACTIVE','LOCKED')")),
                    null);

            case "ExoWands" -> new SpecIntegration(id, db,
                    "wand token balance and undelivered wand items",
                    List.of("tokens", "pending_delivery", "build_jobs"),
                    List.of(blocker("running wand build jobs", "build_jobs", "status = 'RUNNING' AND (player = ?)")),
                    List.of(
                            delete("wand tokens", "tokens", "player = ?", "1=1"),
                            delete("undelivered wand items", "pending_delivery", "player = ?", "1=1")),
                    null);

            case "LightZone" -> new SpecIntegration(id, db,
                    "backpacks (invalidated, contents kept for restore), pending rewards, crate pity, boss kill progress",
                    List.of("lz_backpacks", "lz_pending_rewards", "lz_crate_state", "lz_crate_reward_counts", "lz_player_kills", "lz_players"),
                    List.of(),
                    List.of(
                            update("backpacks", "lz_backpacks", "invalidated = 1, updated_at = {now}",
                                    "owner = ? AND invalidated = 0", "invalidated = 0"),
                            delete("pending rewards", "lz_pending_rewards", "player = ?", "1=1"),
                            delete("crate pity", "lz_crate_state", "player = ?", "1=1"),
                            delete("crate reward counters", "lz_crate_reward_counts", "player = ?", "1=1"),
                            delete("mob kill progress", "lz_player_kills", "uuid = ?", "1=1"),
                            update("boss and crate statistics", "lz_players", "boss_kills = 0, crates_opened = 0",
                                    "uuid = ? AND (boss_kills <> 0 OR crates_opened <> 0)", "boss_kills <> 0 OR crates_opened <> 0")),
                    null);

            default -> null;
        });
    }

    public static final List<String> IDS = List.of("ExoBoxes", "ExoCubes", "ExoWoodCube", "ExoFarmCube", "ExoDaily",
            "ExoDailySpinner", "ExoEconomy", "ExoMultiTools", "ExoNightMarket", "ExoQuests", "ExoSkills", "ExoTokenShop",
            "ExoTools", "ExoWands", "LightZone");
}
