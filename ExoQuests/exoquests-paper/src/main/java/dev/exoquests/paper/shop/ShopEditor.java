package dev.exoquests.paper.shop;

import dev.exoquests.core.config.ConfigException;
import dev.exoquests.core.config.ConfigLoader;
import dev.exoquests.core.config.ShopLoader;
import dev.exoquests.core.config.YamlFiles;
import dev.exoquests.core.shop.ItemSpec;
import dev.exoquests.core.shop.RewardType;
import dev.exoquests.core.shop.ShopCatalog;
import dev.exoquests.core.shop.ShopEntry;
import dev.exoquests.paper.ExoQuestsPlugin;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.UnaryOperator;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * Applies admin shop edits. Each edit re-reads {@code shop.yml} from disk (so manual edits are not lost),
 * applies the change, validates the result exactly like a reload, writes it atomically and only then
 * activates it. Edits are serialized on the configuration I/O thread.
 */
public final class ShopEditor {

    /** Edit outcome for user feedback. */
    public enum Outcome { OK, EXISTS, UNKNOWN }

    private final ExoQuestsPlugin plugin;

    public ShopEditor(ExoQuestsPlugin plugin) {
        this.plugin = plugin;
    }

    private Path file() {
        return plugin.configs().directory().resolve("shop.yml");
    }

    /**
     * Runs an edit. The function returns the new catalog, or {@code null} with an outcome stored in the holder.
     */
    private CompletableFuture<Outcome> edit(UnaryOperator<ShopCatalog> change, Outcome[] outcome) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ShopCatalog disk = ConfigLoader.loadShop(file(), plugin.configs().validator());
                ShopCatalog updated = change.apply(disk);
                if (updated == null) {
                    return null;
                }
                // Validate the exact YAML that will be written, before replacing the file.
                java.util.Map<String, Object> yaml = ShopLoader.toYaml(updated);
                Path tmp = file().resolveSibling("shop.yml.validate");
                YamlFiles.writeAtomically(tmp, ShopLoader.HEADER, yaml);
                ShopCatalog validated = ConfigLoader.loadShop(tmp, plugin.configs().validator());
                java.nio.file.Files.deleteIfExists(tmp);
                YamlFiles.writeAtomically(file(), ShopLoader.HEADER, yaml);
                return validated;
            } catch (ConfigException e) {
                throw new CompletionException(e);
            } catch (java.io.IOException e) {
                throw new CompletionException(e);
            }
        }, plugin.configs().io()).thenApplyAsync(catalog -> {
            if (catalog == null) {
                return outcome[0];
            }
            plugin.configs().applyShop(catalog);
            return Outcome.OK;
        }, plugin.mainExecutor());
    }

    public CompletableFuture<Outcome> add(ShopEntry entry) {
        Outcome[] outcome = {Outcome.OK};
        return edit(catalog -> {
            if (catalog.get(entry.id()).isPresent()) {
                outcome[0] = Outcome.EXISTS;
                return null;
            }
            return catalog.with(entry);
        }, outcome);
    }

    public CompletableFuture<Outcome> remove(String id) {
        Outcome[] outcome = {Outcome.OK};
        return edit(catalog -> {
            if (catalog.get(id).isEmpty()) {
                outcome[0] = Outcome.UNKNOWN;
                return null;
            }
            return catalog.without(id);
        }, outcome);
    }

    public CompletableFuture<Outcome> setPrice(String id, int price) {
        Outcome[] outcome = {Outcome.OK};
        return edit(catalog -> {
            Optional<ShopEntry> e = catalog.get(id);
            if (e.isEmpty()) {
                outcome[0] = Outcome.UNKNOWN;
                return null;
            }
            return catalog.replace(e.get().withPrice(price));
        }, outcome);
    }

    /** New item entry; revision is computed when the saved file is re-read. */
    public static ShopEntry itemEntry(String id, int price, ItemSpec spec) {
        return new ShopEntry(id, price, RewardType.ITEM, spec, null, null, null, null, null, true, "");
    }

    public static ShopEntry commandEntry(String id, int price, String command) {
        return new ShopEntry(id, price, RewardType.COMMAND, null, java.util.List.of(command), "PAPER",
                "<text>" + id.replace('_', ' '), null, null, true, "");
    }

    /** Plain, readable reward name for chat messages, e.g. {@code 4 diamond} or the configured display name. */
    public String plainName(String itemId) {
        if (itemId == null) {
            return "reward";
        }
        Optional<ShopEntry> entry = plugin.configs().current().shop().get(itemId);
        if (entry.isEmpty()) {
            return itemId.replace('_', ' ');
        }
        ShopEntry e = entry.get();
        if (e.displayName() != null) {
            return PlainTextComponentSerializer.plainText().serialize(plugin.text().parse(e.displayName()));
        }
        if (e.type() == RewardType.ITEM) {
            try {
                ItemStack template = ItemRewards.template(e.item(), plugin.text());
                int total = ItemRewards.totalAmount(e.item(), template);
                if (e.item().isSerialized() || e.item().name() != null) {
                    var meta = template.getItemMeta();
                    if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
                        return total + " " + PlainTextComponentSerializer.plainText().serialize(meta.displayName());
                    }
                }
                return total + " " + materialName(template.getType());
            } catch (RuntimeException ex) {
                return itemId.replace('_', ' ');
            }
        }
        return itemId.replace('_', ' ');
    }

    public static String materialName(Material material) {
        return material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
