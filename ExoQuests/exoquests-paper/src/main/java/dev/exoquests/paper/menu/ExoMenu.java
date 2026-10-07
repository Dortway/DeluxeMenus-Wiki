package dev.exoquests.paper.menu;

import dev.exoquests.core.config.MenuLayouts;
import dev.exoquests.paper.ExoQuestsPlugin;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

/**
 * Base class for ExoQuests menus. Menus are recognised exclusively by this server-side holder type, never
 * by title or item text. Every click inside an open menu is cancelled by {@link MenuListener}; only
 * registered slot actions run.
 */
public abstract class ExoMenu implements InventoryHolder {

    protected final ExoQuestsPlugin plugin;
    protected final UUID viewer;
    private final Map<Integer, Runnable> actions = new HashMap<>();
    private Inventory inventory;

    protected ExoMenu(ExoQuestsPlugin plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer.getUniqueId();
    }

    protected void create(int rows, Component title) {
        this.inventory = Bukkit.createInventory(this, rows * 9, title);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public UUID viewer() {
        return viewer;
    }

    /** Rebuilds the menu contents in place. */
    public abstract void render();

    void click(int slot) {
        Runnable action = actions.get(slot);
        if (action != null) {
            action.run();
        }
    }

    protected void clear() {
        actions.clear();
        inventory.clear();
    }

    protected void set(int slot, ItemStack item, Runnable action) {
        inventory.setItem(slot, item);
        if (action != null) {
            actions.put(slot, action);
        } else {
            actions.remove(slot);
        }
    }

    protected void fill(MenuLayouts.Filler filler) {
        if (!filler.enabled()) {
            return;
        }
        ItemStack pane = displayItem(Material.getMaterial(filler.material()), Component.space(), List.of());
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, pane);
            }
        }
    }

    protected ItemStack button(MenuLayouts.Button button, Player player, TagResolver... placeholders) {
        Material material = Material.getMaterial(button.material());
        ItemStack item = displayItem(material, plugin.text().item(button.name(), placeholders),
                plugin.text().itemLines(button.lore(), placeholders));
        if (material == Material.PLAYER_HEAD && player != null) {
            ItemMeta meta = item.getItemMeta();
            if (meta instanceof SkullMeta skull) {
                skull.setOwningPlayer(player);
                item.setItemMeta(skull);
            }
        }
        return item;
    }

    /** Builds a display-only item with every tooltip extra hidden. */
    protected static ItemStack displayItem(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material == null ? Material.PAPER : material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.values());
            item.setItemMeta(meta);
        }
        return item;
    }

    protected Player player() {
        return Bukkit.getPlayer(viewer);
    }
}
