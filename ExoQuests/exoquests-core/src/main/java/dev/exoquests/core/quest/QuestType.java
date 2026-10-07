package dev.exoquests.core.quest;

/** How a quest's progress is detected. Each type reads a specific kind of key. */
public enum QuestType {
    /** Breaking blocks of the listed materials; {@code natural-only} rejects player-placed blocks. */
    BLOCK_BREAK(KeyKind.BLOCK, true),
    /** Breaking fully grown {@code Ageable} crops of the listed materials. */
    CROP_HARVEST(KeyKind.BLOCK, false),
    /** Sugar cane, cactus and bamboo: every non-placed segment broken, including the column above. */
    STACKED_PLANT_HARVEST(KeyKind.BLOCK, true),
    /** Successful breeding where the feeding player is recorded as the breeder. */
    ENTITY_BREED(KeyKind.ENTITY, false),
    /** Kills credited to the player under the configured kill policy. */
    ENTITY_KILL(KeyKind.ENTITY, false),
    /** A recorded player-planted sapling growing into a tree of the listed wood types. */
    TREE_GROW(KeyKind.TREE, false),
    /** Items caught with a fishing rod; an empty list accepts any catch. */
    FISH_CATCH(KeyKind.ITEM, false),
    /** Items taken from a crafting result slot. */
    CRAFT_ITEM(KeyKind.ITEM, false),
    /** Items taken by a player from a furnace, smoker or blast furnace output slot. */
    SMELT_EXTRACT(KeyKind.ITEM, false),
    /** Shearing an entity with shears. */
    SHEAR_ENTITY(KeyKind.ENTITY, false),
    /** Picking up eggs that were laid by a chicken (not eggs dropped by players). */
    COLLECT_LAID_EGGS(KeyKind.ITEM, false);

    public enum KeyKind { BLOCK, ITEM, ENTITY, TREE }

    private final KeyKind keyKind;
    private final boolean supportsNaturalOnly;

    QuestType(KeyKind keyKind, boolean supportsNaturalOnly) {
        this.keyKind = keyKind;
        this.supportsNaturalOnly = supportsNaturalOnly;
    }

    public KeyKind keyKind() {
        return keyKind;
    }

    public boolean supportsNaturalOnly() {
        return supportsNaturalOnly;
    }

    public boolean allowsEmptyKeys() {
        return this == FISH_CATCH;
    }
}
