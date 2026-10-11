package net.exoeconomy.api;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Public ExoEconomy API, registered in Bukkit's ServicesManager:
 * <pre>{@code
 * ExoEconomyAPI api = Bukkit.getServicesManager().load(ExoEconomyAPI.class);
 * }</pre>
 * Unless stated otherwise, call methods on the server thread. Every Shards movement goes through
 * ExcellentEconomy; ExoEconomy never stores balances. Registrations are removed automatically when
 * the registering plugin disables.
 */
public interface ExoEconomyAPI {
    /** True when ExcellentEconomy is connected and the configured Shards currency exists. Thread-safe. */
    boolean isEconomyAvailable();

    /** The ExcellentEconomy currency id ExoEconomy uses for Shards. Thread-safe. */
    String currencyId();

    /** The player's Shards balance as reported by ExcellentEconomy (online players only). */
    Optional<BigDecimal> balance(Player player);

    /** Formats an amount with ExcellentEconomy's currency format. */
    String format(BigDecimal amount);

    // ---------------------------------------------------------------- rewards

    /**
     * Declares a custom activity so boosters can target it ("activity: id" in boosters.yml) and
     * rewards.yml can configure it (activities.&lt;id&gt;: enabled, permission, worlds, caps).
     * Ids are lowercase [a-z0-9_-]; built-in ids cannot be registered.
     */
    void registerActivity(Plugin owner, String activityId);

    /**
     * Awards an activity reward through the normal pipeline: eligibility (enabled, permission,
     * world, game mode), {@link net.exoeconomy.api.event.ShardRewardEvent}, booster multiplier,
     * daily caps, batching and notification. Your plugin is responsible for deciding that the
     * activity really happened once (ExoEconomy cannot see custom activities).
     *
     * @param key        reward key looked up in rewards.yml activities.&lt;id&gt;.rewards when baseAmount is null
     * @param baseAmount explicit base amount before boosters, or null to use the configured reward
     */
    RewardOutcome reward(Player player, String activityId, String key, BigDecimal baseAmount);

    /**
     * For plugins that break blocks themselves (regenerating mines, ExoCubes/ExoWoodCubes-style cubes that
     * cancel the BlockBreakEvent): pays the farming, woodcutting or mining reward configured for the
     * material, with the normal eligibility, boosters, caps and notifications. Block provenance is not
     * checked, so only call it for breaks your plugin knows are legitimate, and never for breaks that
     * ExoEconomy already saw as an uncancelled BlockBreakEvent (that would pay twice).
     */
    RewardOutcome rewardBlockBreak(Player player, Material material);

    /** Combined booster multiplier currently applying to a player's activity. Thread-safe. */
    BigDecimal boosterMultiplier(UUID player, String activityId);

    // ---------------------------------------------------------------- selling

    /** The /sell price of one plain vanilla item in the selling currency (prices.yml x selling.price-multiplier), if sellable. */
    Optional<BigDecimal> vanillaPrice(Material material);

    /** Lets a plugin price its own custom items in /sell, in the selling currency (otherwise they are always rejected). */
    void registerPriceProvider(Plugin owner, SellPriceProvider provider);

    /** Lets a plugin veto selling any item, including plain-looking ones. */
    void registerItemFilter(Plugin owner, SellItemFilter filter);

    // ---------------------------------------------------------------- boosters

    /** Boosters currently affecting a player (their personal boosters and all global ones). Thread-safe. */
    List<BoosterInfo> boosters(UUID player);

    /** All global boosters, including queued ones. Thread-safe. */
    List<BoosterInfo> globalBoosters();

    /**
     * Grants a free booster (no Shards are charged), following the stacking rules in boosters.yml.
     *
     * @param owner null for a global booster
     * @return the created or extended booster, or empty if the stacking rules refuse it
     */
    Optional<BoosterInfo> grantBooster(BoosterScope scope, UUID owner, String activityId, BigDecimal multiplier, long durationMs, String reason);

    /** Cancels a booster without refund. */
    boolean cancelBooster(String boosterId);

    enum RewardOutcome { AWARDED, CAPPED, INELIGIBLE, CANCELLED, UNKNOWN_ACTIVITY, ECONOMY_UNAVAILABLE }
}
