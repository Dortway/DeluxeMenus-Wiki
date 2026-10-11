package net.exoeconomy.config;

import net.exoeconomy.api.BoosterScope;
import net.exoeconomy.core.Money;
import net.exoeconomy.core.booster.BoosterRules;
import net.exoeconomy.core.booster.BoosterSpec;
import net.exoeconomy.core.booster.CombineMode;
import net.exoeconomy.util.Text;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.math.BigDecimal;
import java.util.*;

/** boosters.yml */
public record BoosterSettings(BoosterRules rules, Map<String, Tier> tiers, Menu menu, Announcements announcements) {

    public record Tier(BoosterSpec spec, String displayName, Material icon, int slot, String permission, List<String> description) {}

    public record Menu(String title, int size, Material filler, int infoSlot, int activeSlot, int closeSlot,
                       String confirmTitle, int confirmSlot, int cancelSlot, int summarySlot) {}

    public record Announcements(boolean globalActivated, boolean globalExpired, boolean personalExpired, boolean personalActivated) {}

    static BoosterSettings load(ConfigurationSection root, boolean sellingBoostersEnabled, List<String> problems, List<String> warnings) {
        ConfigurationSection r = RewardSettings.section(root, "rules");
        BoosterRules rules = BoosterRules.defaults();
        try {
            rules = new BoosterRules(
                    BoosterRules.SameTier.valueOf(r.getString("same-tier", "EXTEND").toUpperCase(Locale.ROOT)),
                    BoosterRules.Overlap.valueOf(r.getString("overlapping", "STACK").toUpperCase(Locale.ROOT)),
                    r.getInt("max-active-per-owner", 3), r.getInt("max-queued-per-owner", 3),
                    Text.parseDuration(r.getString("max-remaining-duration", "6h")),
                    CombineMode.valueOf(r.getString("combine", "ADDITIVE").toUpperCase(Locale.ROOT)),
                    Money.multiplier(r.get("max-total-multiplier", 4.0), "rules.max-total-multiplier"),
                    BoosterRules.OfflineTime.valueOf(r.getString("personal-offline-time", "CONTINUE").toUpperCase(Locale.ROOT)),
                    BoosterRules.OfflineTime.valueOf(r.getString("server-offline-time", "CONTINUE").toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            problems.add("boosters.yml rules: " + e.getMessage());
        }

        ConfigurationSection m = RewardSettings.section(root, "menu");
        int size = m.getInt("size", 54);
        if (size < 27 || size > 54 || size % 9 != 0) problems.add("boosters.yml menu.size must be 27, 36, 45 or 54");
        Menu menu = new Menu(m.getString("title", "&8Shard Boosters"), size, material(m.getString("filler", "BLACK_STAINED_GLASS_PANE"), "menu.filler", problems),
                m.getInt("info-slot", 4), m.getInt("active-slot", size - 5), m.getInt("close-slot", size - 1),
                m.getString("confirm.title", "&8Confirm purchase"), m.getInt("confirm.confirm-slot", 11),
                m.getInt("confirm.cancel-slot", 15), m.getInt("confirm.summary-slot", 13));
        Set<Integer> used = new HashSet<>(List.of(menu.infoSlot(), menu.activeSlot(), menu.closeSlot()));
        if (used.size() != 3 || used.stream().anyMatch(s -> s < 0 || s >= size)) problems.add("boosters.yml menu: info, active and close slots must be distinct and inside the menu");

        Map<String, Tier> tiers = new LinkedHashMap<>();
        ConfigurationSection t = RewardSettings.section(root, "tiers");
        for (String id : t.getKeys(false)) {
            ConfigurationSection c = t.getConfigurationSection(id);
            if (c == null) continue;
            String path = "boosters.yml tiers." + id;
            try {
                BoosterScope scope = BoosterScope.valueOf(c.getString("scope", "PERSONAL").toUpperCase(Locale.ROOT));
                String activity = c.getString("activity", BoosterSpec.ALL).toLowerCase(Locale.ROOT);
                if (RewardSettings.SELLING.equals(activity) && !sellingBoostersEnabled) {
                    warnings.add(path + " boosts selling but selling.boosters.enabled is false in config.yml; tier hidden");
                    continue;
                }
                BoosterSpec spec = new BoosterSpec(id, scope, activity, Money.multiplier(c.get("multiplier"), path + ".multiplier"),
                        Text.parseDuration(c.getString("duration", "")), Money.fromConfig(c.get("price"), path + ".price"));
                int slot = c.getInt("slot", -1);
                if (slot < 0 || slot >= size || !used.add(slot)) { problems.add(path + ".slot must be unique and inside the menu"); continue; }
                tiers.put(id, new Tier(spec, c.getString("display-name", "&f" + Text.title(id)),
                        material(c.getString("icon", "EXPERIENCE_BOTTLE"), path + ".icon", problems), slot,
                        c.getString("permission", ""), c.getStringList("description")));
            } catch (IllegalArgumentException e) {
                problems.add(path + ": " + e.getMessage());
            }
        }
        ConfigurationSection a = RewardSettings.section(root, "announcements");
        Announcements announcements = new Announcements(a.getBoolean("global-activated", true), a.getBoolean("global-expired", true),
                a.getBoolean("personal-expired", true), a.getBoolean("personal-activated", true));
        return new BoosterSettings(rules, Collections.unmodifiableMap(tiers), menu, announcements);
    }

    static Material material(String name, String path, List<String> problems) {
        Material m = name == null ? null : Material.getMaterial(name.toUpperCase(Locale.ROOT));
        if (m == null || !m.isItem()) { problems.add(path + ": unknown item material " + name); return Material.STONE; }
        return m;
    }
}
