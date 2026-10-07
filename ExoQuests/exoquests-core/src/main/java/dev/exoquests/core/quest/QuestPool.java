package dev.exoquests.core.quest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.EnumSet;

/** Immutable, validated quest pool plus categories. */
public final class QuestPool {

    public static final int SLOTS = 3;

    private final Map<String, QuestDefinition> quests;
    private final Map<String, QuestCategory> categories;
    private final List<QuestDefinition> enabled;
    private final Set<QuestType> activeTypes;

    public QuestPool(Collection<QuestDefinition> quests, Collection<QuestCategory> categories) {
        Map<String, QuestDefinition> q = new LinkedHashMap<>();
        for (QuestDefinition d : quests) {
            if (q.put(d.id(), d) != null) {
                throw new IllegalArgumentException("duplicate quest id " + d.id());
            }
        }
        Map<String, QuestCategory> c = new LinkedHashMap<>();
        for (QuestCategory cat : categories) {
            c.put(cat.id(), cat);
        }
        this.quests = Collections.unmodifiableMap(q);
        this.categories = Collections.unmodifiableMap(c);
        this.enabled = q.values().stream().filter(QuestDefinition::enabled).toList();
        if (enabled.size() < SLOTS) {
            throw new IllegalArgumentException("at least " + SLOTS + " quests must be enabled");
        }
        EnumSet<QuestType> types = EnumSet.noneOf(QuestType.class);
        q.values().forEach(d -> types.add(d.type()));
        this.activeTypes = Collections.unmodifiableSet(types);
    }

    public Optional<QuestDefinition> get(String id) {
        return Optional.ofNullable(quests.get(id));
    }

    public Collection<QuestDefinition> all() {
        return quests.values();
    }

    public List<QuestDefinition> enabled() {
        return enabled;
    }

    public Optional<QuestCategory> category(String id) {
        return Optional.ofNullable(categories.get(id));
    }

    public Collection<QuestCategory> categories() {
        return categories.values();
    }

    public boolean usesType(QuestType type) {
        return activeTypes.contains(type);
    }

    /** Materials that must be tracked for placement because a natural-only quest depends on them. */
    public Set<String> naturalOnlyMaterials() {
        java.util.HashSet<String> out = new java.util.HashSet<>();
        for (QuestDefinition d : quests.values()) {
            if (d.naturalOnly() && d.type().supportsNaturalOnly()) {
                out.addAll(d.keys());
            }
        }
        return Collections.unmodifiableSet(out);
    }

    /**
     * Weighted selection of {@link #SLOTS} distinct enabled quests without replacement.
     */
    public List<QuestDefinition> pick(Random random) {
        List<QuestDefinition> candidates = new ArrayList<>(enabled);
        List<QuestDefinition> picked = new ArrayList<>(SLOTS);
        while (picked.size() < SLOTS) {
            long total = 0;
            for (QuestDefinition d : candidates) {
                total += d.weight();
            }
            long roll = (long) (random.nextDouble() * total);
            QuestDefinition chosen = candidates.get(candidates.size() - 1);
            long acc = 0;
            for (QuestDefinition d : candidates) {
                acc += d.weight();
                if (roll < acc) {
                    chosen = d;
                    break;
                }
            }
            picked.add(chosen);
            candidates.remove(chosen);
        }
        return picked;
    }
}
