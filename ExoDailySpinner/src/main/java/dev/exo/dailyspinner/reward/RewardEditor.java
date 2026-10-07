package dev.exo.dailyspinner.reward;

import dev.exo.dailyspinner.ExoDailySpinner;
import dev.exo.dailyspinner.config.ConfigBundle;
import dev.exo.dailyspinner.config.ConfigErrors;
import dev.exo.dailyspinner.config.ConfigLoader;
import dev.exo.dailyspinner.menu.GuiItems;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Applies administrator reward changes. Every change is validated by rebuilding the full reward
 * registry before it replaces the active one; rewards.yml is then written asynchronously.
 * Main thread only.
 */
public final class RewardEditor {

    private final ExoDailySpinner plugin;

    public RewardEditor(ExoDailySpinner plugin) {
        this.plugin = plugin;
    }

    public boolean addFromHand(Player player, String requestedId, double weight) {
        ConfigBundle bundle = plugin.bundle();
        if (bundle == null) {
            return false;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir() || held.getAmount() <= 0) {
            bundle.messages().send(player, "admin-hand-empty");
            return false;
        }
        if (GuiItems.isGuiItem(held)) {
            bundle.messages().send(player, "admin-hand-invalid");
            return false;
        }
        // Copy only: the held stack is never modified or consumed.
        ItemStack copy = held.clone();
        int amount = copy.getAmount();
        copy.setAmount(1);
        String serialized;
        try {
            serialized = Base64.getEncoder().encodeToString(copy.serializeAsBytes());
        } catch (RuntimeException e) {
            bundle.messages().send(player, "admin-hand-invalid");
            return false;
        }
        String id = requestedId != null ? requestedId : generateId(bundle, copy.getType());
        if (!validId(player, bundle, id) || !validWeight(player, bundle, weight)) {
            return false;
        }
        String rarity = defaultRarity(bundle);
        return mutate(player, y -> {
            String base = "rewards." + id;
            y.set(base + ".weight", weight);
            y.set(base + ".rarity", rarity);
            y.set(base + ".type", "item");
            y.set(base + ".item.serialized", serialized);
            y.set(base + ".item.amount", amount);
        }, "admin-reward-added", Placeholder.unparsed("id", id), Placeholder.unparsed("weight", fmt(weight)));
    }

    public boolean addItem(CommandSender sender, String id, Material material, int amount, double weight) {
        ConfigBundle bundle = plugin.bundle();
        if (bundle == null || !validId(sender, bundle, id) || !validWeight(sender, bundle, weight)) {
            return false;
        }
        if (amount < 1 || amount > ConfigLoader.MAX_ITEM_AMOUNT) {
            bundle.messages().send(sender, "admin-invalid-amount",
                    Placeholder.unparsed("max", String.valueOf(ConfigLoader.MAX_ITEM_AMOUNT)));
            return false;
        }
        String rarity = defaultRarity(bundle);
        return mutate(sender, y -> {
            String base = "rewards." + id;
            y.set(base + ".weight", weight);
            y.set(base + ".rarity", rarity);
            y.set(base + ".type", "item");
            y.set(base + ".item.material", material.name());
            y.set(base + ".item.amount", amount);
        }, "admin-reward-added", Placeholder.unparsed("id", id), Placeholder.unparsed("weight", fmt(weight)));
    }

    public boolean addCommand(CommandSender sender, String id, double weight, String command) {
        ConfigBundle bundle = plugin.bundle();
        if (bundle == null || !validId(sender, bundle, id) || !validWeight(sender, bundle, weight)) {
            return false;
        }
        String normalized;
        try {
            normalized = CommandTemplate.normalize(command);
        } catch (IllegalArgumentException e) {
            bundle.messages().send(sender, "admin-invalid-command", Placeholder.unparsed("reason", e.getMessage()));
            return false;
        }
        String rarity = defaultRarity(bundle);
        return mutate(sender, y -> {
            String base = "rewards." + id;
            y.set(base + ".weight", weight);
            y.set(base + ".rarity", rarity);
            y.set(base + ".type", "command");
            y.set(base + ".commands", List.of(normalized));
            y.set(base + ".display.material", bundle.settings().commandDisplayMaterial().name());
            y.set(base + ".display.name", "<accent>" + id);
        }, "admin-reward-added", Placeholder.unparsed("id", id), Placeholder.unparsed("weight", fmt(weight)));
    }

    public boolean remove(CommandSender sender, String id) {
        ConfigBundle bundle = plugin.bundle();
        if (bundle == null || !exists(sender, bundle, id)) {
            return false;
        }
        return mutate(sender, y -> y.set("rewards." + id, null), "admin-reward-removed", Placeholder.unparsed("id", id));
    }

    public boolean setWeight(CommandSender sender, String id, double weight) {
        ConfigBundle bundle = plugin.bundle();
        if (bundle == null || !exists(sender, bundle, id) || !validWeight(sender, bundle, weight)) {
            return false;
        }
        return mutate(sender, y -> y.set("rewards." + id + ".weight", weight), "admin-weight-set",
                Placeholder.unparsed("id", id), Placeholder.unparsed("weight", fmt(weight)));
    }

    public boolean setRarity(CommandSender sender, String id, String rarity) {
        ConfigBundle bundle = plugin.bundle();
        if (bundle == null || !exists(sender, bundle, id)) {
            return false;
        }
        if (!bundle.rewards().rarities().containsKey(rarity)) {
            bundle.messages().send(sender, "admin-unknown-rarity", Placeholder.unparsed("rarity", rarity),
                    Placeholder.unparsed("rarities", String.join(", ", bundle.rewards().rarities().keySet())));
            return false;
        }
        return mutate(sender, y -> y.set("rewards." + id + ".rarity", rarity), "admin-rarity-set",
                Placeholder.unparsed("id", id), Placeholder.unparsed("rarity", rarity));
    }

    // ------------------------------------------------------------------ helpers

    private boolean mutate(CommandSender sender, Consumer<YamlConfiguration> change, String successKey, TagResolver... resolvers) {
        ConfigBundle bundle = plugin.bundle();
        if (plugin.isReloading()) {
            bundle.messages().send(sender, "reload-in-progress");
            return false;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(bundle.rewardsYaml());
        } catch (InvalidConfigurationException e) {
            bundle.messages().send(sender, "admin-edit-failed", Placeholder.unparsed("reason", e.getMessage()));
            return false;
        }
        change.accept(yaml);
        String text = yaml.saveToString();
        ConfigErrors errors = new ConfigErrors();
        RewardRegistry registry = ConfigLoader.loadRewardsOnly(text, bundle, errors);
        if (registry == null) {
            String reason = errors.errors().isEmpty() ? "unknown error" : errors.errors().get(0);
            bundle.messages().send(sender, "admin-edit-failed", Placeholder.unparsed("reason", reason));
            return false;
        }
        plugin.applyRewards(registry, text);
        plugin.writeFileAsync(ConfigLoader.REWARDS, text);
        bundle.messages().send(sender, successKey, resolvers);
        plugin.getLogger().info(sender.getName() + " edited rewards (" + successKey + ").");
        return true;
    }

    private boolean validId(CommandSender sender, ConfigBundle bundle, String id) {
        if (id == null || !ConfigLoader.REWARD_ID.matcher(id).matches()) {
            bundle.messages().send(sender, "admin-invalid-id");
            return false;
        }
        if (bundle.rewards().get(id) != null) {
            bundle.messages().send(sender, "admin-duplicate-id", Placeholder.unparsed("id", id));
            return false;
        }
        return true;
    }

    private boolean exists(CommandSender sender, ConfigBundle bundle, String id) {
        if (id == null || bundle.rewards().get(id) == null) {
            bundle.messages().send(sender, "reward-unknown", Placeholder.unparsed("id", String.valueOf(id)));
            return false;
        }
        return true;
    }

    private boolean validWeight(CommandSender sender, ConfigBundle bundle, double weight) {
        try {
            WeightedPicker.validateWeight(weight);
            return true;
        } catch (IllegalArgumentException e) {
            bundle.messages().send(sender, "admin-invalid-weight", Placeholder.unparsed("reason", e.getMessage()));
            return false;
        }
    }

    private String defaultRarity(ConfigBundle bundle) {
        String preferred = bundle.settings().adminDefaultRarity();
        if (bundle.rewards().rarities().containsKey(preferred)) {
            return preferred;
        }
        return bundle.rewards().rarities().keySet().iterator().next();
    }

    private String generateId(ConfigBundle bundle, Material material) {
        String base = "hand_" + material.name().toLowerCase(Locale.ROOT);
        if (base.length() > 28) {
            base = base.substring(0, 28);
        }
        String id = base;
        for (int i = 2; bundle.rewards().get(id) != null; i++) {
            id = base + "_" + i;
        }
        return id;
    }

    /** Parses a weight argument; returns NaN when not a number (rejected by validation). */
    public static double parseWeight(String raw) {
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    public static String fmt(double weight) {
        return dev.exo.dailyspinner.menu.RewardItems.formatWeight(weight);
    }
}
