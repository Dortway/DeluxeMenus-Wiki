package net.exoeconomy.integration;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.logging.Logger;

/**
 * ExoSkills 1.0.1 selling bonus. ExoSkills has no public level API and fires no events; the only
 * supported integration point is the {@code net.exoarmor.SaleMultiplierAPI} service it registers
 * (only while ExoArmorTrims is installed), with {@code double multiplier(Player, Material, double armor)}.
 * ExoSkills returns its skill milestone multiplier combined with the passed armor multiplier using
 * its sales.stacking rule; passing 1.0 yields the pure skill bonus (multiply: 1 x skill; add: 1 + skill - 1).
 * It returns the input unchanged for materials outside sales.ores / sales.crops and for players
 * in excluded game modes or worlds.
 *
 * <p>Bound by reflection (ExoArmorTrims is not a compile dependency). Only the registration owned
 * by ExoSkills is used, so an ExoArmorTrims provider cannot be picked up by mistake.
 * Results outside 1..100 are ignored.
 */
public final class ExoSkillsBridge {
    private final Object provider;
    private final Method method;
    private final Logger log;
    private boolean warned;

    private ExoSkillsBridge(Object provider, Method method, Logger log) {
        this.provider = provider; this.method = method; this.log = log;
    }

    public static ExoSkillsBridge connect(Logger log) {
        Plugin skills = Bukkit.getPluginManager().getPlugin("ExoSkills");
        if (skills == null || !skills.isEnabled()) return null;
        for (Class<?> service : Bukkit.getServicesManager().getKnownServices()) {
            if (!service.getName().equals("net.exoarmor.SaleMultiplierAPI")) continue;
            for (RegisteredServiceProvider<?> rsp : Bukkit.getServicesManager().getRegistrations(service)) {
                if (rsp.getPlugin() != skills) continue;
                try {
                    Method m = service.getMethod("multiplier", Player.class, Material.class, double.class);
                    if (m.getReturnType() != double.class) break;
                    String version = skills.getPluginMeta().getVersion();
                    log.info("ExoSkills " + version + " sale bonus available" + ("1.0.1".equals(version) ? "" : " (unverified version; written for 1.0.1)") + ".");
                    return new ExoSkillsBridge(rsp.getProvider(), m, log);
                } catch (NoSuchMethodException e) {
                    log.warning("ExoSkills SaleMultiplierAPI has an unexpected signature; skill sale bonus disabled");
                }
            }
        }
        log.info("ExoSkills is installed but registered no SaleMultiplierAPI (ExoSkills only does so when ExoArmorTrims is present); skill sale bonus unavailable.");
        return null;
    }

    /** Skill multiplier for selling one material, 1 when not applicable. Server thread only. */
    public BigDecimal multiplier(Player player, Material material) {
        try {
            double value = (double) method.invoke(provider, player, material, 1.0);
            if (Double.isFinite(value) && value >= 1 && value <= 100) return BigDecimal.valueOf(value);
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!warned) log.warning("ExoSkills sale multiplier failed: " + e + "; using 1x");
            warned = true;
        }
        return BigDecimal.ONE;
    }
}
