package com.exoblacksmith.item;

import com.exoblacksmith.config.Registry;
import com.exoblacksmith.config.model.ArmorPieceDef;
import com.exoblacksmith.config.model.ArmorSetDef;
import com.exoblacksmith.config.model.DamageCategory;
import com.exoblacksmith.config.model.EquipSlot;
import com.exoblacksmith.config.model.ItemDef;
import com.exoblacksmith.config.model.MaskDef;
import com.exoblacksmith.config.model.MaskLevel;
import com.exoblacksmith.config.model.PotionSpec;
import com.exoblacksmith.config.model.Rarity;
import com.exoblacksmith.config.model.Reduction;
import com.exoblacksmith.config.model.RuneDef;
import com.exoblacksmith.config.model.RuneMechanic;
import com.exoblacksmith.integration.VisualProvider;
import com.exoblacksmith.util.Durations;
import com.exoblacksmith.util.Roman;
import com.exoblacksmith.util.Text;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;

/** Renders item names and lore from templates in messages.yml. Pure presentation. */
public final class LoreBuilder {
    private final Supplier<Registry> registry;
    private final Supplier<VisualProvider> visuals;

    LoreBuilder(Supplier<Registry> registry, Supplier<VisualProvider> visuals) {
        this.registry = registry;
        this.visuals = visuals;
    }

    private Map<String, String> rarityValues(Rarity rarity) {
        Registry reg = registry.get();
        Map<String, String> v = new HashMap<>();
        v.put("rarity", rarity.displayName());
        v.put("rarity_hex", rarity.color());
        String symbol;
        VisualProvider provider = visuals.get();
        if (!rarity.glyph().isEmpty() && provider.ready()) {
            symbol = provider.replaceGlyphs(rarity.glyph());
        } else {
            symbol = reg.settings.unicodeSymbols ? rarity.symbol() : rarity.plainSymbol();
        }
        v.put("rarity_symbol", symbol);
        return v;
    }

    public Component name(ItemDef def, ItemData data) {
        Map<String, String> v = rarityValues(def.rarity(data.level()));
        v.put("name", def.name());
        v.put("level", Roman.of(data.level()));
        return Text.item(registry.get(), registry.get().messages.raw("item-name"), v);
    }

    public String plainName(ItemDef def) {
        return Text.plainName(def.name());
    }

    public List<Component> lore(ItemDef def, ItemData data) {
        Registry reg = registry.get();
        List<String> lines = new ArrayList<>();
        Map<String, String> v = rarityValues(def.rarity(data.level()));
        v.put("level", Roman.of(data.level()));
        v.put("max_level", Roman.of(def.maxLevel()));
        if (def.maxLevel() > 1) {
            v.put("level_label", reg.messages.raw(def.kind() == ItemKind.RUNE ? "lore-tier-label" : "lore-level-label"));
            lines.add(reg.messages.raw("lore-rarity"));
        } else {
            lines.add(reg.messages.raw("lore-rarity-nolevel"));
        }
        List<Component> out = new ArrayList<>(Text.lines(reg, lines, v));
        lines.clear();
        if (!def.lore().isEmpty()) {
            out.add(Component.empty());
            out.addAll(Text.lines(reg, def.lore(), v));
        }
        switch (def) {
            case MaskDef mask -> out.addAll(maskLore(mask, mask.level(data.level())));
            case RuneDef rune -> out.addAll(runeLore(rune, data.level()));
            case ArmorPieceDef piece -> out.addAll(armorLore(piece, data));
            default -> {
            }
        }
        return out;
    }

    private Component line(String key, Map<String, String> values) {
        return Text.item(registry.get(), registry.get().messages.raw(key), values);
    }

    public String category(DamageCategory category) {
        Registry reg = registry.get();
        String key = "category-" + category.name().toLowerCase(Locale.ROOT).replace('_', '-');
        return reg.messages.has(key) ? reg.messages.raw(key) : category.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private List<Component> reductionLines(List<Reduction> reductions) {
        List<Component> out = new ArrayList<>();
        for (Reduction r : reductions) {
            out.add(line("lore-reduction", Map.of("value", Durations.percent(r.value()), "category", category(r.category()))));
        }
        return out;
    }

    private List<Component> maskLore(MaskDef mask, MaskLevel level) {
        List<Component> out = new ArrayList<>();
        out.add(Component.empty());
        out.add(line("lore-section-effects", Map.of()));
        for (PotionSpec effect : level.effects()) {
            String name = effect.type().getKey().getKey().replace('_', ' ');
            out.add(line("lore-potion", Map.of("effect", name, "amplifier", Roman.of(effect.amplifier() + 1))));
        }
        if (level.bonusHearts() > 0) {
            out.add(line("lore-hearts", Map.of("value", Durations.number(level.bonusHearts()))));
        }
        if (level.tridentBonus() > 0) {
            out.add(line("lore-trident", Map.of("value", Durations.number(level.tridentBonus()))));
        }
        out.addAll(Text.lines(registry.get(), level.effectLore(), Map.of()));
        List<Long> cooldowns = new ArrayList<>();
        if (level.heal() != null) {
            cooldowns.add(level.heal().cooldownSeconds());
        }
        if (level.cobweb() != null) {
            cooldowns.add(level.cobweb().cooldownSeconds());
        }
        if (level.summon() != null) {
            cooldowns.add(level.summon().cooldownSeconds());
        }
        if (level.arrowTeleport() != null) {
            cooldowns.add(level.arrowTeleport().cooldownSeconds());
        }
        if (level.explosiveHit() != null) {
            cooldowns.add(level.explosiveHit().cooldownSeconds());
        }
        if (level.hasActivatedAbility()) {
            out.add(line("lore-mask-activation", Map.of()));
        }
        if (level.arrowTeleport() != null) {
            out.add(line("lore-mask-arrow-activation", Map.of()));
        }
        for (long cd : cooldowns) {
            out.add(line("lore-cooldown", Map.of("value", Durations.seconds(cd))));
        }
        out.add(Component.empty());
        out.add(line("lore-equip", Map.of("slot", registry.get().messages.raw("lore-mask-slot"))));
        if (mask.maxLevel() > level.level()) {
            out.add(line("lore-upgradable", Map.of("max_level", Roman.of(mask.maxLevel()))));
        }
        return out;
    }

    /** Formats a rune tier value according to its mechanic. */
    public static String runeValue(RuneDef rune, double value) {
        return switch (rune.mechanic()) {
            case TOTEM_SURGE, TIDAL_BREATH -> Durations.seconds(value);
            default -> Durations.percent(value);
        };
    }

    private List<Component> runeLore(RuneDef rune, int tier) {
        List<Component> out = new ArrayList<>();
        out.add(Component.empty());
        out.add(line("lore-section-effects", Map.of()));
        Map<String, String> v = new HashMap<>();
        v.put("value", runeValue(rune, rune.tier(tier).value()));
        v.put("cooldown", Durations.seconds(rune.param("cooldown", 0)));
        out.add(Text.item(registry.get(), rune.effectLine(), v));
        if (rune.mechanic() == RuneMechanic.TOTEM_SURGE || rune.mechanic() == RuneMechanic.TIDAL_BREATH) {
            out.add(line("lore-cooldown", Map.of("value", Durations.seconds(rune.param("cooldown", 0)))));
        }
        out.add(Component.empty());
        out.add(line("lore-rune-fits", Map.of("slot", rune.slot().display())));
        return out;
    }

    private List<Component> armorLore(ArmorPieceDef piece, ItemData data) {
        Registry reg = registry.get();
        List<Component> out = new ArrayList<>();
        if (!piece.reductions().isEmpty() || piece.speedBonus() > 0) {
            out.add(Component.empty());
            out.add(line("lore-section-effects", Map.of()));
            out.addAll(reductionLines(piece.reductions()));
            if (piece.speedBonus() > 0) {
                out.add(line("lore-speed", Map.of("value", Durations.percent(piece.speedBonus()))));
            }
        }
        ArmorSetDef set = reg.set(piece.setId());
        if (set != null) {
            out.add(Component.empty());
            List<String> slots = set.requiredSlots().stream().sorted().map(EquipSlot::display).toList();
            out.add(Text.item(reg, reg.messages.raw("lore-set"), Map.of("set", set.name(), "slots", String.join(", ", slots))));
            out.addAll(reductionLines(set.bonusReductions()));
            if (set.bonusSpeed() > 0) {
                out.add(line("lore-speed", Map.of("value", Durations.percent(set.bonusSpeed()))));
            }
            out.addAll(Text.lines(reg, set.bonusLore(), Map.of()));
            if (set.ability() != null) {
                out.add(Text.item(reg, reg.messages.raw("lore-set-ability"), Map.of("ability", set.ability().name(),
                        "cooldown", Durations.seconds(set.ability().cooldownSeconds()))));
                out.addAll(Text.lines(reg, set.ability().lore(), Map.of(
                        "duration", Durations.seconds(set.ability().durationSeconds()))));
            }
        }
        out.add(Component.empty());
        for (int i = 0; i < RuneSlot.MAX_SLOTS; i++) {
            if (i < data.runes().size()) {
                RuneSlot slot = data.runes().get(i);
                RuneDef rune = reg.rune(slot.runeId());
                if (rune == null) {
                    continue;
                }
                Map<String, String> v = rarityValues(rune.rarity(slot.tier()));
                v.put("rune", rune.name());
                v.put("tier", Roman.of(slot.tier()));
                out.add(line("lore-rune-slot-filled", v));
            } else {
                out.add(line("lore-rune-slot-empty", Map.of()));
            }
        }
        out.add(line("lore-equip", Map.of("slot", piece.slot().display())));
        return out;
    }
}
