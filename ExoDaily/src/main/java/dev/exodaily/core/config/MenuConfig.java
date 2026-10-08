package dev.exodaily.core.config;

import dev.exodaily.core.reward.RewardPosition;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validated menus.yml. */
public record MenuConfig(
        Main main,
        Details details,
        Overview overview,
        Map<String, SoundSpec> sounds,
        ClaimEffect claimEffect,
        Map<String, String> lines
) {

    public MenuConfig {
        sounds = Map.copyOf(sounds);
        lines = Map.copyOf(lines);
    }

    public String line(String key) {
        String value = lines.get(key);
        return value == null ? "<red>missing line: " + key : value;
    }

    /** Title, size and border shared by all menus. */
    public record Frame(String title, int rows, Border border) {

        public int size() {
            return rows * 9;
        }
    }

    public record Border(boolean enabled, String material, String accentMaterial, Set<Integer> accentSlots) {

        public Border {
            accentSlots = Set.copyOf(accentSlots);
        }
    }

    /** Appearance of an item; null fields in a state variant inherit from the base variant. */
    public record Variant(String material, String name, List<String> lore, Boolean glow) {

        public Variant {
            lore = lore == null ? null : List.copyOf(lore);
        }

        Variant inherit(Variant base) {
            return new Variant(
                    material != null ? material : base.material,
                    name != null ? name : base.name,
                    lore != null ? lore : base.lore,
                    glow != null ? glow : base.glow);
        }
    }

    /** A configurable item: slot (or -1 for templates), base look and optional per-state overrides. */
    public record ItemSpec(int slot, Variant base, Map<String, Variant> states) {

        public ItemSpec {
            states = Map.copyOf(states);
        }

        public Variant resolve(String state) {
            Variant variant = state == null ? null : states.get(state);
            Variant resolved = variant == null ? base : variant.inherit(base);
            return new Variant(resolved.material(), resolved.name() == null ? "" : resolved.name(),
                    resolved.lore() == null ? List.of() : resolved.lore(), resolved.glow() != null && resolved.glow());
        }
    }

    public record Main(Frame frame, Map<String, ItemSpec> items) {

        public static final List<String> REQUIRED = List.of(
                "progress", "standard-info", "today", "premium-info", "previous-day", "instructions", "countdown");

        public Main {
            items = Map.copyOf(items);
        }
    }

    public record PositionSlots(int rewardSlot, int buttonSlot) {
    }

    public record Details(Frame frame, Map<String, ItemSpec> items, Map<RewardPosition, PositionSlots> positions,
                          List<String> rewardLoreBefore, List<String> rewardLoreAfter, boolean showRewardLore,
                          ItemSpec claimButton) {

        public static final List<String> REQUIRED = List.of("header", "back", "countdown");

        public Details {
            items = Map.copyOf(items);
            positions = Map.copyOf(new HashMap<>(positions));
            rewardLoreBefore = List.copyOf(rewardLoreBefore);
            rewardLoreAfter = List.copyOf(rewardLoreAfter);
        }
    }

    public record Overview(Frame frame, Map<String, ItemSpec> items, List<Integer> daySlots, ItemSpec dayItem) {

        public static final List<String> REQUIRED = List.of("header", "back");

        public Overview {
            items = Map.copyOf(items);
            daySlots = List.copyOf(daySlots);
        }
    }

    /** A sound; a null key disables it. */
    public record SoundSpec(String key, float volume, float pitch) {
    }

    public record ClaimEffect(boolean enabled, String particle, int count, double spread) {
    }
}
