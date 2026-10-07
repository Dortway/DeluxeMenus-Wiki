package dev.exo.dailyspinner.reward;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/** Immutable set of validated rewards with a precomputed weighted picker. */
public final class RewardRegistry {

    private final List<RewardDefinition> rewards;
    private final Map<String, RewardDefinition> byId;
    private final Map<String, Rarity> rarities;
    private final WeightedPicker<RewardDefinition> picker;
    private final Map<String, Double> probabilities;

    public RewardRegistry(List<RewardDefinition> rewards, Map<String, Rarity> rarities) {
        this.rewards = List.copyOf(rewards);
        this.rarities = Collections.unmodifiableMap(new LinkedHashMap<>(rarities));
        Map<String, RewardDefinition> map = new LinkedHashMap<>();
        for (RewardDefinition reward : rewards) {
            if (map.put(reward.id(), reward) != null) {
                throw new IllegalArgumentException("duplicate reward id " + reward.id());
            }
        }
        this.byId = Collections.unmodifiableMap(map);
        if (rewards.isEmpty()) {
            this.picker = null;
            this.probabilities = Map.of();
        } else {
            double[] weights = new double[rewards.size()];
            for (int i = 0; i < weights.length; i++) {
                weights[i] = rewards.get(i).weight();
            }
            this.picker = new WeightedPicker<>(rewards, weights);
            Map<String, Double> probs = new LinkedHashMap<>();
            for (int i = 0; i < rewards.size(); i++) {
                probs.put(rewards.get(i).id(), picker.probability(i));
            }
            this.probabilities = Collections.unmodifiableMap(probs);
        }
    }

    public boolean isEmpty() {
        return picker == null;
    }

    public RewardDefinition pick(RandomGenerator random) {
        if (picker == null) {
            throw new IllegalStateException("no rewards configured");
        }
        return picker.pick(random);
    }

    public double probability(String id) {
        return probabilities.getOrDefault(id, 0.0);
    }

    public double totalWeight() {
        return picker == null ? 0 : picker.totalWeight();
    }

    public List<RewardDefinition> rewards() {
        return rewards;
    }

    /** Rewards sorted for display: common first, then by descending chance. */
    public List<RewardDefinition> sortedForDisplay() {
        List<RewardDefinition> list = new ArrayList<>(rewards);
        list.sort(Comparator.comparingInt((RewardDefinition r) -> r.rarity().order())
                .thenComparing(Comparator.comparingDouble(RewardDefinition::weight).reversed())
                .thenComparing(RewardDefinition::id));
        return list;
    }

    public RewardDefinition get(String id) {
        return byId.get(id);
    }

    public Map<String, Rarity> rarities() {
        return rarities;
    }
}
