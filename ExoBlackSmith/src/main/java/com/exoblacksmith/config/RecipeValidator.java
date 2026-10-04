package com.exoblacksmith.config;

import com.exoblacksmith.config.model.HeadDef;
import com.exoblacksmith.config.model.Ingredient;
import com.exoblacksmith.config.model.ItemDef;
import com.exoblacksmith.config.model.ItemRef;
import com.exoblacksmith.config.model.MaterialDef;
import com.exoblacksmith.config.model.RecipeDef;
import com.exoblacksmith.item.ItemKind;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Cross-checks recipes against item definitions:
 * <ul>
 *   <li>every armor piece, mask level, rune tier, upgrade material and totem has a recipe;</li>
 *   <li>every such output is actually reachable starting from vanilla items and value heads;</li>
 *   <li>upgrade recipes consume the previous level of the same item, and totems match their mask;</li>
 *   <li>unique items (armor, masks) are only consumed one at a time.</li>
 * </ul>
 */
public final class RecipeValidator {
    private static final String FILE = "recipes.yml";

    private RecipeValidator() {
    }

    public static void validate(Registry registry, Problems problems) {
        Set<ItemRef> required = requiredOutputs(registry);

        for (RecipeDef recipe : registry.recipes()) {
            String key = "recipes." + recipe.id();
            ItemDef outDef = registry.item(recipe.output().exoId());
            if (outDef == null) {
                continue;
            }
            if (outDef.kind() == ItemKind.HEAD) {
                problems.warn(FILE, key, "value heads are normally obtained from drops; a head recipe is unusual");
            }
            boolean hasPrevious = false;
            for (Map.Entry<ItemRef, Integer> entry : recipe.totals().entrySet()) {
                ItemRef ref = entry.getKey();
                if (ref.isVanilla()) {
                    continue;
                }
                ItemDef def = registry.item(ref.exoId());
                if (def.kind().unique() && entry.getValue() != 1) {
                    problems.error(FILE, key, "unique item '" + ref.exoId() + "' must appear once with amount 1");
                }
                if (ref.exoId().equals(recipe.output().exoId())) {
                    if (ref.level() != recipe.output().level() - 1) {
                        problems.error(FILE, key, "upgrade input " + ref.describe() + " must be exactly one level below the output");
                    }
                    hasPrevious = true;
                }
                if (def instanceof MaterialDef material && material.kind() == ItemKind.TOTEM) {
                    if (outDef.kind() != ItemKind.MASK || !outDef.id().equals(material.maskId())) {
                        problems.error(FILE, key, "totem '" + material.id() + "' belongs to mask '" + material.maskId()
                                + "' and cannot be used for " + outDef.id());
                    }
                }
            }
            if (recipe.output().level() > 1 && !hasPrevious) {
                problems.error(FILE, key, "a level/tier " + recipe.output().level()
                        + " output must consume the previous level of the same item");
            }
        }

        for (ItemRef ref : required) {
            if (registry.recipesProducing(ref).isEmpty()) {
                problems.error(FILE, "(coverage)", "no recipe produces " + ref.describe());
            }
        }

        Set<ItemRef> reachable = reachable(registry);
        for (ItemRef ref : required) {
            if (!registry.recipesProducing(ref).isEmpty() && !reachable.contains(ref)) {
                problems.error(FILE, "(reachability)", ref.describe()
                        + " has a recipe but its ingredients can never be obtained (circular or missing inputs)");
            }
        }
        for (HeadDef head : registry.heads()) {
            if (head.dropChance() <= 0) {
                problems.warn("heads.yml", "heads." + head.id() + ".drop.chance",
                        "drop chance is 0; this head is only obtainable through admin/dungeon commands");
            }
        }
    }

    /** Everything the specification requires to be craftable. */
    public static Set<ItemRef> requiredOutputs(Registry registry) {
        Set<ItemRef> required = new LinkedHashSet<>();
        registry.armorPieces().forEach(a -> required.add(ItemRef.exo(a.id(), 1)));
        registry.masks().forEach(m -> {
            for (int level = 1; level <= m.maxLevel(); level++) {
                required.add(ItemRef.exo(m.id(), level));
            }
        });
        registry.runes().forEach(r -> {
            for (int tier = 1; tier <= r.maxLevel(); tier++) {
                required.add(ItemRef.exo(r.id(), tier));
            }
        });
        registry.materials().forEach(m -> required.add(ItemRef.exo(m.id(), 1)));
        return required;
    }

    /** Fixpoint over recipes, seeded with vanilla items (implicit) and value heads. */
    public static Set<ItemRef> reachable(Registry registry) {
        Set<ItemRef> have = new HashSet<>();
        registry.heads().forEach(h -> have.add(ItemRef.exo(h.id(), 1)));
        boolean changed = true;
        while (changed) {
            changed = false;
            for (RecipeDef recipe : registry.recipes()) {
                if (have.contains(recipe.output())) {
                    continue;
                }
                boolean ok = true;
                for (Ingredient ingredient : recipe.cells()) {
                    if (ingredient != null && !ingredient.ref().isVanilla() && !have.contains(ingredient.ref())) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    have.add(recipe.output());
                    changed = true;
                }
            }
        }
        return have;
    }
}
