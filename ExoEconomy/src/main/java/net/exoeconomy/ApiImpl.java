package net.exoeconomy;

import net.exoeconomy.api.*;
import net.exoeconomy.core.booster.BoosterSpec;
import net.exoeconomy.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@link ExoEconomyAPI} implementation; all Shards operations go through ExcellentEconomy. */
final class ApiImpl implements ExoEconomyAPI {
    private final ExoEconomyPlugin plugin;

    ApiImpl(ExoEconomyPlugin plugin) { this.plugin = plugin; }

    @Override public boolean isEconomyAvailable() { return plugin.ledger().available(); }
    @Override public String currencyId() { return plugin.ledger().currencyId(); }
    @Override public Optional<BigDecimal> balance(Player player) { return plugin.ledger().balance(player.getUniqueId()); }
    @Override public String format(BigDecimal amount) { return plugin.ledger().format(amount); }

    @Override public void registerActivity(Plugin owner, String activityId) {
        if (!plugin.rewards().registerActivity(owner.getName(), activityId))
            throw new IllegalArgumentException("Invalid or reserved activity id: " + activityId);
    }

    @Override public RewardOutcome reward(Player player, String activityId, String key, BigDecimal baseAmount) {
        BigDecimal base = baseAmount != null ? baseAmount : plugin.rewards().configured(activityId, key == null ? "" : key.toUpperCase(java.util.Locale.ROOT));
        if (base.signum() < 0 || base.compareTo(new BigDecimal("1000000")) > 0) throw new IllegalArgumentException("Base amount must be 0..1000000");
        return plugin.rewards().award(player, activityId, key == null ? "" : key, base);
    }

    @Override public RewardOutcome rewardBlockBreak(Player player, Material material) { return plugin.rewards().awardBlock(player, material); }

    @Override public BigDecimal boosterMultiplier(UUID player, String activityId) { return plugin.boosters().book().multiplier(player, activityId); }

    @Override public Optional<BigDecimal> vanillaPrice(Material material) {
        return plugin.settings().catalogue().price(material).map(p -> p.multiply(plugin.settings().selling().priceMultiplier()));
    }

    @Override public void registerPriceProvider(Plugin owner, SellPriceProvider provider) { plugin.sell().inspector().addProvider(owner.getName(), provider); }

    @Override public void registerItemFilter(Plugin owner, SellItemFilter filter) { plugin.sell().inspector().addFilter(owner.getName(), filter); }

    @Override public List<BoosterInfo> boosters(UUID player) {
        return plugin.boosters().book().snapshot().stream()
                .filter(b -> b.scope() == BoosterScope.GLOBAL || player.equals(b.owner())).toList();
    }

    @Override public List<BoosterInfo> globalBoosters() {
        return plugin.boosters().book().snapshot().stream().filter(b -> b.scope() == BoosterScope.GLOBAL).toList();
    }

    @Override public Optional<BoosterInfo> grantBooster(BoosterScope scope, UUID owner, String activityId, BigDecimal multiplier, long durationMs, String reason) {
        BoosterSpec spec = new BoosterSpec("api", scope, activityId, multiplier, durationMs, BigDecimal.ZERO);
        Object result = plugin.boosters().grant(spec, owner, null, reason == null ? "" : Text.title(reason));
        return result instanceof BoosterInfo info ? Optional.of(info) : Optional.empty();
    }

    @Override public boolean cancelBooster(String boosterId) { return plugin.boosters().cancel(boosterId).isPresent(); }
}
