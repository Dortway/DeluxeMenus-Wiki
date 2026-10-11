package net.exoeconomy.integration;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.exoeconomy.ExoEconomyPlugin;
import net.exoeconomy.api.BoosterInfo;
import net.exoeconomy.api.BoosterScope;
import net.exoeconomy.core.Money;
import net.exoeconomy.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * %exoeconomy_...% placeholders. Booster placeholders read an immutable snapshot and are safe from
 * any thread. %exoeconomy_balance% asks ExcellentEconomy only when called on the server thread for
 * an online player (otherwise it is empty); prefer ExcellentEconomy's own balance placeholders.
 *
 * <ul>
 *   <li>multiplier_&lt;activity&gt; – combined booster multiplier, e.g. 1.5</li>
 *   <li>boosters_personal, boosters_global – number of active boosters</li>
 *   <li>booster_time_&lt;activity&gt; – longest remaining time of an active booster covering the activity</li>
 *   <li>balance – Shards balance formatted by ExcellentEconomy</li>
 *   <li>currency – configured currency id</li>
 * </ul>
 */
public final class PapiExpansion extends PlaceholderExpansion {
    private final ExoEconomyPlugin plugin;

    public PapiExpansion(ExoEconomyPlugin plugin) { this.plugin = plugin; }

    @Override public String getIdentifier() { return "exoeconomy"; }
    @Override public String getAuthor() { return "ExoEconomy"; }
    @Override public String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (params.equals("currency")) return plugin.ledger().currencyId();
        if (player == null) return "";
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (params.startsWith("multiplier_")) return Money.plain(plugin.boosters().book().multiplier(id, params.substring(11)));
        if (params.equals("multiplier")) return Money.plain(plugin.boosters().book().multiplier(id, "all"));
        if (params.equals("boosters_personal") || params.equals("boosters_global")) {
            BoosterScope scope = params.endsWith("personal") ? BoosterScope.PERSONAL : BoosterScope.GLOBAL;
            return String.valueOf(plugin.boosters().book().snapshot().stream()
                    .filter(b -> b.scope() == scope && "ACTIVE".equals(b.state()) && (scope == BoosterScope.GLOBAL || id.equals(b.owner()))).count());
        }
        if (params.startsWith("booster_time_")) {
            List<BoosterInfo> list = plugin.boosters().book().applicable(id, params.substring(13));
            return list.stream().mapToLong(b -> b.remaining(now)).max().stream().mapToObj(Text::duration).findFirst().orElse("");
        }
        if (params.equals("balance")) {
            Player online = player.getPlayer();
            if (online == null || !Bukkit.isPrimaryThread()) return "";
            return plugin.ledger().balance(id).map(plugin.ledger()::format).orElse("");
        }
        return null;
    }
}
