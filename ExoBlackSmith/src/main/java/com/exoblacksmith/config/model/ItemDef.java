package com.exoblacksmith.config.model;

import com.exoblacksmith.item.ItemKind;
import java.util.List;

/** Common view over every configured ExoBlackSmith item type. */
public interface ItemDef {
    String id();

    ItemKind kind();

    String name();

    /** Highest tier/level; 1 for items without progression. */
    default int maxLevel() {
        return 1;
    }

    Rarity rarity(int level);

    Appearance appearance(int level);

    /** Item-specific description lines (MiniMessage) shown under the rarity header. */
    List<String> lore();
}
