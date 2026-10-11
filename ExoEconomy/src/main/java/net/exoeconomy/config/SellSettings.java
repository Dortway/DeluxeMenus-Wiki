package net.exoeconomy.config;

import net.exoeconomy.core.Money;
import org.bukkit.GameMode;
import org.bukkit.configuration.ConfigurationSection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * config.yml selling section.
 *
 * @param currency        "vault" (Vault money, default), "shards" (the Shards currency) or "excellenteconomy:&lt;id&gt;"
 * @param priceMultiplier every prices.yml price is multiplied by this (prices.yml is scaled for Shards;
 *                        100 turns it into typical money prices). Uniform, so recipes stay arbitrage-free.
 */
public record SellSettings(boolean enabled, String currency, BigDecimal priceMultiplier, Set<GameMode> gameModes,
                           Set<String> disabledWorlds, String title,
                           BigDecimal maxPayoutPerSale, long confirmWindowMs, boolean returnEscrowAfterCrash,
                           boolean boostersEnabled, boolean exoSkillsBonus, BigDecimal maxTotalMultiplier) {
    public boolean usesVault() { return currency.equals("vault"); }
    public boolean usesShards() { return currency.equals("shards"); }
    /** The ExcellentEconomy currency id for "excellenteconomy:&lt;id&gt;", else null. */
    public String excellentCurrency() { return currency.startsWith("excellenteconomy:") ? currency.substring(17) : null; }

    static SellSettings load(ConfigurationSection s, List<String> problems) {
        long window = s.getLong("confirm-window-seconds", 10) * 1000;
        if (window < 2000 || window > 120_000) problems.add("selling.confirm-window-seconds must be 2..120");
        String recovery = s.getString("crash-recovery", "hold-for-review");
        if (!recovery.equals("hold-for-review") && !recovery.equals("return-to-player"))
            problems.add("selling.crash-recovery must be hold-for-review or return-to-player");
        String currency = s.getString("currency", "vault").trim().toLowerCase(java.util.Locale.ROOT);
        if (!currency.equals("vault") && !currency.equals("shards") && !currency.matches("excellenteconomy:[a-z0-9_\\-]+"))
            problems.add("selling.currency must be vault, shards or excellenteconomy:<currency id>");
        BigDecimal priceMultiplier = RewardSettings.amount(s.get("price-multiplier", 100), "selling.price-multiplier", problems);
        if (priceMultiplier.signum() <= 0) problems.add("selling.price-multiplier must be above 0");
        BigDecimal cap;
        try { cap = Money.multiplier(s.get("max-total-multiplier", 3.0), "selling.max-total-multiplier"); }
        catch (IllegalArgumentException e) { problems.add(e.getMessage()); cap = BigDecimal.ONE; }
        return new SellSettings(s.getBoolean("enabled", true), currency, priceMultiplier,
                RewardSettings.modes(s.getStringList("game-modes"), "selling.game-modes", problems),
                Set.copyOf(s.getStringList("disabled-worlds")), s.getString("title", "&8Sell Items"),
                RewardSettings.amount(s.get("max-payout-per-sale", 1_000_000), "selling.max-payout-per-sale", problems),
                window, recovery.equals("return-to-player"), s.getBoolean("boosters.enabled", false),
                s.getBoolean("exoskills-sale-bonus", true), cap);
    }
}
