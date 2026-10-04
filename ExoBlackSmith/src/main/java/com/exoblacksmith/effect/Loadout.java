package com.exoblacksmith.effect;

import com.exoblacksmith.config.Registry;
import com.exoblacksmith.config.Settings;
import com.exoblacksmith.config.model.ArmorPieceDef;
import com.exoblacksmith.config.model.ArmorSetDef;
import com.exoblacksmith.config.model.EquipSlot;
import com.exoblacksmith.config.model.MaskDef;
import com.exoblacksmith.config.model.MaskLevel;
import com.exoblacksmith.config.model.RuneDef;
import com.exoblacksmith.config.model.RuneMechanic;
import com.exoblacksmith.item.ItemData;
import com.exoblacksmith.item.ItemKind;
import com.exoblacksmith.item.ItemService;
import com.exoblacksmith.item.RuneSlot;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Snapshot of what a player is wearing, resolved from authenticated items only.
 *
 * @param mask        worn mask definition, or null
 * @param maskLevel   level definition of the worn mask, or null
 * @param armor       worn ExoBlackSmith armor pieces by slot
 * @param runeValues  effective value per rune mechanic after the stacking policy
 * @param runeDefs    the rune definition that supplied each mechanic (for params such as cooldowns)
 * @param activeSet   set whose bonus is active, or null
 * @param signature   compact identity string used to detect changes
 */
public record Loadout(MaskDef mask, MaskLevel maskLevel, Map<EquipSlot, ArmorPieceDef> armor,
                      Map<RuneMechanic, Double> runeValues, Map<RuneMechanic, RuneDef> runeDefs,
                      ArmorSetDef activeSet, String signature) {

    public static final Loadout EMPTY = new Loadout(null, null, Map.of(), Map.of(), Map.of(), null, "");

    public double rune(RuneMechanic mechanic) {
        return runeValues.getOrDefault(mechanic, 0.0);
    }

    public boolean hasRune(RuneMechanic mechanic) {
        return runeValues.containsKey(mechanic);
    }

    public static Loadout resolve(Player player, ItemService items, Registry registry) {
        PlayerInventory inv = player.getInventory();
        StringBuilder sig = new StringBuilder();
        MaskDef mask = null;
        MaskLevel maskLevel = null;
        Map<EquipSlot, ArmorPieceDef> armor = new EnumMap<>(EquipSlot.class);
        Map<EquipSlot, List<RuneSlot>> runes = new EnumMap<>(EquipSlot.class);

        for (EquipSlot slot : EquipSlot.ARMOR_SLOTS) {
            ItemStack stack = inv.getItem(slot.bukkit());
            ItemService.Identified id = items.identify(stack);
            if (id == null) {
                continue;
            }
            ItemData data = id.data();
            if (slot == EquipSlot.HELMET && data.kind() == ItemKind.MASK) {
                mask = (MaskDef) id.def();
                maskLevel = mask.level(data.level());
                sig.append("M:").append(mask.id()).append(':').append(data.level()).append(';');
            } else if (data.kind() == ItemKind.ARMOR) {
                ArmorPieceDef piece = (ArmorPieceDef) id.def();
                if (piece.slot() != slot) {
                    continue; // e.g. a chestplate forced into the helmet slot by another plugin
                }
                armor.put(slot, piece);
                runes.put(slot, data.runes());
                sig.append(slot.name().charAt(0)).append(':').append(piece.id()).append(':')
                        .append(RuneSlot.encode(data.runes())).append(';');
            }
        }

        Map<RuneMechanic, Double> values = new EnumMap<>(RuneMechanic.class);
        Map<RuneMechanic, RuneDef> defs = new EnumMap<>(RuneMechanic.class);
        Map<RuneMechanic, Double> caps = new HashMap<>();
        boolean sum = registry.settings.runeStacking == Settings.RuneStacking.SUM;
        for (Map.Entry<EquipSlot, List<RuneSlot>> entry : runes.entrySet()) {
            ArmorPieceDef piece = armor.get(entry.getKey());
            for (RuneSlot slot : entry.getValue()) {
                RuneDef rune = registry.rune(slot.runeId());
                if (rune == null || rune.mechanic() == null || !rune.slot().accepts(piece.slot())) {
                    continue;
                }
                double value = rune.tier(slot.tier()).value();
                RuneMechanic mechanic = rune.mechanic();
                caps.merge(mechanic, rune.tier(rune.maxLevel()).value(), Math::max);
                Double previous = values.get(mechanic);
                if (previous == null || (!sum && value > previous)) {
                    values.put(mechanic, value);
                    defs.put(mechanic, rune);
                } else if (sum) {
                    values.put(mechanic, Math.min(previous + value, caps.get(mechanic)));
                }
            }
        }

        ArmorSetDef active = null;
        for (ArmorSetDef set : registry.sets()) {
            boolean complete = !set.requiredSlots().isEmpty();
            for (EquipSlot required : set.requiredSlots()) {
                ArmorPieceDef piece = armor.get(required);
                complete &= piece != null && piece.setId().equals(set.id());
            }
            if (complete) {
                active = set;
                sig.append("S:").append(set.id());
                break;
            }
        }
        return new Loadout(mask, maskLevel, Collections.unmodifiableMap(armor), Collections.unmodifiableMap(values),
                Collections.unmodifiableMap(defs), active, sig.toString());
    }
}
