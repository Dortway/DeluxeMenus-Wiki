package com.exoblacksmith.integration;

import dev.lone.itemsadder.api.CustomStack;
import dev.lone.itemsadder.api.Events.ItemsAdderLoadDataEvent;
import dev.lone.itemsadder.api.FontImages.FontImageWrapper;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * ItemsAdder bridge. Only loaded when the ItemsAdder plugin is present (this class references its API).
 * ExoBlackSmith items are NOT ItemsAdder items: only the visual components (item_model, custom model
 * data, equippable asset) are copied from the ItemsAdder template, so ItemsAdder never rewrites our
 * name/lore/data and identity stays in ExoBlackSmith's signed data.
 */
public final class ItemsAdderVisuals implements VisualProvider, Listener {
    private final Logger logger;
    private final Runnable onReady;
    private final Set<String> warned = new HashSet<>();
    private volatile boolean ready;

    public ItemsAdderVisuals(Logger logger, Runnable onReady) {
        this.logger = logger;
        this.onReady = onReady;
    }

    @EventHandler
    public void onLoad(ItemsAdderLoadDataEvent event) {
        ready = true;
        logger.info("ItemsAdder data loaded; custom visuals enabled.");
        onReady.run();
    }

    @Override
    public boolean applyVisuals(ItemMeta meta, String namespacedId) {
        if (!ready) {
            return false;
        }
        CustomStack stack = CustomStack.getInstance(namespacedId);
        if (stack == null) {
            if (warned.add(namespacedId)) {
                logger.warning("ItemsAdder item '" + namespacedId + "' does not exist; using vanilla fallback visuals.");
            }
            return false;
        }
        ItemStack template = stack.getItemStack();
        ItemMeta source = template.getItemMeta();
        if (source == null) {
            return false;
        }
        if (source.hasItemModel()) {
            meta.setItemModel(source.getItemModel());
        }
        if (source.hasCustomModelDataComponent()) {
            meta.setCustomModelDataComponent(source.getCustomModelDataComponent());
        }
        if (source.hasEquippable()) {
            meta.setEquippable(source.getEquippable());
        }
        return true;
    }

    @Override
    public String replaceGlyphs(String text) {
        if (!ready || text.indexOf(':') < 0) {
            return text;
        }
        return FontImageWrapper.replaceFontImages(text);
    }

    @Override
    public boolean ready() {
        return ready;
    }

    @Override
    public String name() {
        return "ItemsAdder";
    }
}
