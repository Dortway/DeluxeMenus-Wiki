package com.exoblacksmith.config;

import com.exoblacksmith.config.model.ArmorPieceDef;
import com.exoblacksmith.config.model.ArmorSetDef;
import com.exoblacksmith.config.model.Category;
import com.exoblacksmith.config.model.HeadDef;
import com.exoblacksmith.config.model.ItemDef;
import com.exoblacksmith.config.model.ItemRef;
import com.exoblacksmith.config.model.MaskDef;
import com.exoblacksmith.config.model.MaterialDef;
import com.exoblacksmith.config.model.RecipeDef;
import com.exoblacksmith.config.model.RuneDef;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable, fully validated configuration snapshot. A reload builds a new Registry and swaps it in
 * only if validation succeeded; holders of the old instance keep a consistent view.
 */
public final class Registry {
    public final Settings settings;
    public final Messages messages;
    public final MenuSettings menus;
    private final Map<String, HeadDef> heads;
    private final Map<String, MaskDef> masks;
    private final Map<String, RuneDef> runes;
    private final Map<String, ArmorPieceDef> armor;
    private final Map<String, ArmorSetDef> sets;
    private final Map<String, MaterialDef> materials;
    private final Map<String, RecipeDef> recipes;
    private final Map<String, ItemDef> all;

    Registry(Settings settings, Messages messages, MenuSettings menus, Map<String, HeadDef> heads,
             Map<String, MaskDef> masks, Map<String, RuneDef> runes, Map<String, ArmorPieceDef> armor,
             Map<String, ArmorSetDef> sets, Map<String, MaterialDef> materials, Map<String, RecipeDef> recipes) {
        this.settings = settings;
        this.messages = messages;
        this.menus = menus;
        this.heads = unmodifiable(heads);
        this.masks = unmodifiable(masks);
        this.runes = unmodifiable(runes);
        this.armor = unmodifiable(armor);
        this.sets = unmodifiable(sets);
        this.materials = unmodifiable(materials);
        this.recipes = unmodifiable(recipes);
        Map<String, ItemDef> combined = new LinkedHashMap<>();
        combined.putAll(heads);
        combined.putAll(masks);
        combined.putAll(runes);
        combined.putAll(armor);
        combined.putAll(materials);
        this.all = unmodifiable(combined);
    }

    private static <V> Map<String, V> unmodifiable(Map<String, V> map) {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    public ItemDef item(String id) {
        return all.get(id);
    }

    public Collection<ItemDef> items() {
        return all.values();
    }

    public HeadDef head(String id) {
        return heads.get(id);
    }

    public Collection<HeadDef> heads() {
        return heads.values();
    }

    public MaskDef mask(String id) {
        return masks.get(id);
    }

    public Collection<MaskDef> masks() {
        return masks.values();
    }

    public RuneDef rune(String id) {
        return runes.get(id);
    }

    public Collection<RuneDef> runes() {
        return runes.values();
    }

    public ArmorPieceDef armor(String id) {
        return armor.get(id);
    }

    public Collection<ArmorPieceDef> armorPieces() {
        return armor.values();
    }

    public ArmorSetDef set(String id) {
        return sets.get(id);
    }

    public Collection<ArmorSetDef> sets() {
        return sets.values();
    }

    public MaterialDef material(String id) {
        return materials.get(id);
    }

    public Collection<MaterialDef> materials() {
        return materials.values();
    }

    public RecipeDef recipe(String id) {
        return recipes.get(id);
    }

    public Collection<RecipeDef> recipes() {
        return recipes.values();
    }

    public List<RecipeDef> recipes(Category category) {
        List<RecipeDef> out = new ArrayList<>();
        for (RecipeDef recipe : recipes.values()) {
            if (recipe.category() == category) {
                out.add(recipe);
            }
        }
        out.sort(java.util.Comparator.comparingInt(RecipeDef::sortOrder));
        return out;
    }

    /** Recipes whose output is exactly {@code ref}. */
    public List<RecipeDef> recipesProducing(ItemRef ref) {
        List<RecipeDef> out = new ArrayList<>();
        for (RecipeDef recipe : recipes.values()) {
            if (recipe.output().equals(ref)) {
                out.add(recipe);
            }
        }
        return out;
    }
}
