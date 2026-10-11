package net.exoeconomy.config;

import net.exoeconomy.core.Money;
import org.bukkit.GameMode;
import org.bukkit.configuration.ConfigurationSection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/** config.yml selling section. */
public record SellSettings(boolean enabled, Set<GameMode> gameModes, Set<String> disabledWorlds, String title,
                           BigDecimal maxPayoutPerSale, long confirmWindowMs, boolean returnEscrowAfterCrash,
                           boolean boostersEnabled, boolean exoSkillsBonus, BigDecimal maxTotalMultiplier) {
    static SellSettings load(ConfigurationSection s, List<String> problems) {
        long window = s.getLong("confirm-window-seconds", 10) * 1000;
        if (window < 2000 || window > 120_000) problems.add("selling.confirm-window-seconds must be 2..120");
        String recovery = s.getString("crash-recovery", "hold-for-review");
        if (!recovery.equals("hold-for-review") && !recovery.equals("return-to-player"))
            problems.add("selling.crash-recovery must be hold-for-review or return-to-player");
        BigDecimal cap;
        try { cap = Money.multiplier(s.get("max-total-multiplier", 3.0), "selling.max-total-multiplier"); }
        catch (IllegalArgumentException e) { problems.add(e.getMessage()); cap = BigDecimal.ONE; }
        return new SellSettings(s.getBoolean("enabled", true),
                RewardSettings.modes(s.getStringList("game-modes"), "selling.game-modes", problems),
                Set.copyOf(s.getStringList("disabled-worlds")), s.getString("title", "&8Sell Items"),
                RewardSettings.amount(s.get("max-payout-per-sale", 1_000_000), "selling.max-payout-per-sale", problems),
                window, recovery.equals("return-to-player"), s.getBoolean("boosters.enabled", false),
                s.getBoolean("exoskills-sale-bonus", false), cap);
    }
}
