package dev.exoquests.core.config;

import dev.exoquests.core.quest.QuestCategory;
import dev.exoquests.core.quest.QuestDefinition;
import dev.exoquests.core.quest.QuestPool;
import dev.exoquests.core.quest.QuestType;
import dev.exoquests.core.quest.WoodType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Parses and validates {@code quests.yml}. */
public final class QuestPoolLoader {

    public static final Pattern ID = Pattern.compile("[a-z0-9_]{1,48}");
    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final Set<String> STACKED = Set.of("SUGAR_CANE", "CACTUS", "BAMBOO");

    private QuestPoolLoader() {
    }

    public static QuestPool load(ConfigNode root, PlatformValidator platform) {
        ConfigErrors errors = root.errors();
        List<QuestCategory> categories = new ArrayList<>();
        ConfigNode cats = root.section("categories");
        for (Map.Entry<String, ConfigNode> e : cats.sections().entrySet()) {
            String id = e.getKey();
            ConfigNode c = e.getValue();
            if (!ID.matcher(id).matches()) {
                errors.add(c.path(), "category id must match " + ID.pattern());
            }
            String color = c.string("color", "#5CE1E6");
            if (!HEX.matcher(color).matches()) {
                errors.add(c.child("color"), "must be a hex color like #5CE1E6");
            }
            categories.add(new QuestCategory(id, c.string("name", id), c.string("icon", ""),
                    c.string("fallback-icon", ""), color));
        }
        if (categories.isEmpty()) {
            errors.add(cats.path(), "at least one category is required");
        }
        Set<String> categoryIds = new java.util.HashSet<>();
        categories.forEach(c -> categoryIds.add(c.id()));

        List<QuestDefinition> quests = new ArrayList<>();
        ConfigNode section = root.section("quests");
        for (Map.Entry<String, ConfigNode> e : section.sections().entrySet()) {
            QuestDefinition d = parseQuest(e.getKey(), e.getValue(), categoryIds, platform);
            if (d != null) {
                quests.add(d);
            }
        }
        long enabled = quests.stream().filter(QuestDefinition::enabled).count();
        if (enabled < QuestPool.SLOTS) {
            errors.add(section.path(), "at least " + QuestPool.SLOTS + " quests must be enabled, found " + enabled);
        }
        if (errors.hasProblems()) {
            return null;
        }
        return new QuestPool(quests, categories);
    }

    private static QuestDefinition parseQuest(String id, ConfigNode q, Set<String> categoryIds,
                                              PlatformValidator platform) {
        ConfigErrors errors = q.errors();
        int before = errors.problems().size();
        if (!ID.matcher(id).matches()) {
            errors.add(q.path(), "quest id must match " + ID.pattern());
        }
        String category = q.requiredString("category");
        if (!category.isEmpty() && !categoryIds.contains(category)) {
            errors.add(q.child("category"), "unknown category '" + category + "'");
        }
        String typeName = q.requiredString("type");
        QuestType type = null;
        try {
            type = QuestType.valueOf(typeName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            if (!typeName.isEmpty()) {
                errors.add(q.child("type"), "unknown tracking type '" + typeName + "'");
            }
        }
        Set<String> keys = new LinkedHashSet<>();
        String keyField = type == null ? "materials" : switch (type.keyKind()) {
            case BLOCK, ITEM -> "materials";
            case ENTITY -> "entities";
            case TREE -> "trees";
        };
        for (String raw : q.stringList(keyField)) {
            String key = raw.trim().toUpperCase(Locale.ROOT);
            if (type != null && !validKey(type, key, platform)) {
                errors.add(q.child(keyField), "invalid value '" + raw + "' for " + type);
            }
            if (!keys.add(key)) {
                errors.add(q.child(keyField), "duplicate value '" + raw + "'");
            }
        }
        if (type != null && keys.isEmpty() && !type.allowsEmptyKeys()) {
            errors.add(q.child(keyField), "at least one value is required");
        }
        if (type == QuestType.STACKED_PLANT_HARVEST) {
            for (String k : keys) {
                if (!STACKED.contains(k)) {
                    errors.add(q.child(keyField), "STACKED_PLANT_HARVEST supports only " + STACKED);
                }
            }
        }
        boolean naturalOnly = q.bool("natural-only", type != null && type.supportsNaturalOnly());
        if (naturalOnly && type != null && !type.supportsNaturalOnly()) {
            errors.add(q.child("natural-only"), "is only supported for BLOCK_BREAK and STACKED_PLANT_HARVEST");
        }
        int target = (int) q.requiredInteger("target", 1, 1_000_000);
        int reward = (int) q.requiredInteger("reward", 1, 100_000);
        String icon = q.requiredString("icon").toUpperCase(Locale.ROOT);
        if (!icon.isEmpty() && !platform.isItemMaterial(icon)) {
            errors.add(q.child("icon"), "unknown item material '" + icon + "'");
        }
        String name = q.requiredString("name");
        String description = q.requiredString("description");
        boolean enabled = q.bool("enabled", true);
        int weight = (int) q.integer("weight", 10, 1, 10_000);
        if (errors.problems().size() != before || type == null) {
            return null;
        }
        return new QuestDefinition(id, category, type, keys, naturalOnly, target, reward, icon, name,
                description, enabled, weight);
    }

    private static boolean validKey(QuestType type, String key, PlatformValidator platform) {
        return switch (type.keyKind()) {
            case BLOCK -> platform.isBlockMaterial(key);
            case ITEM -> platform.isItemMaterial(key);
            case ENTITY -> platform.isEntityType(key);
            case TREE -> WoodType.parse(key).isPresent();
        };
    }
}
