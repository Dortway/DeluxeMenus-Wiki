package dev.exoquests.core.quest;

import java.util.Locale;
import java.util.Optional;

/** Tree families used by {@link QuestType#TREE_GROW}, resolved from the planted sapling. */
public enum WoodType {
    OAK("OAK_SAPLING"),
    SPRUCE("SPRUCE_SAPLING"),
    BIRCH("BIRCH_SAPLING"),
    JUNGLE("JUNGLE_SAPLING"),
    ACACIA("ACACIA_SAPLING"),
    DARK_OAK("DARK_OAK_SAPLING"),
    CHERRY("CHERRY_SAPLING"),
    MANGROVE("MANGROVE_PROPAGULE"),
    PALE_OAK("PALE_OAK_SAPLING");

    private final String sapling;

    WoodType(String sapling) {
        this.sapling = sapling;
    }

    public String saplingMaterial() {
        return sapling;
    }

    public static Optional<WoodType> fromSapling(String material) {
        for (WoodType t : values()) {
            if (t.sapling.equals(material)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    public static Optional<WoodType> parse(String name) {
        try {
            return Optional.of(valueOf(name.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static boolean isSapling(String material) {
        return fromSapling(material).isPresent();
    }
}
