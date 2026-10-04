package com.exoblacksmith.item;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/** Persistent data keys. Changing these breaks existing items; treat them as a stable schema. */
public final class ItemKeys {
    public final NamespacedKey id;
    public final NamespacedKey kind;
    public final NamespacedKey level;
    public final NamespacedKey uid;
    public final NamespacedKey runes;
    public final NamespacedKey schema;
    public final NamespacedKey signature;
    public final NamespacedKey menuIcon;
    public final NamespacedKey summonOwner;
    public final NamespacedKey summonTarget;
    public final NamespacedKey abilityArrow;
    public final NamespacedKey cooldowns;

    public ItemKeys(Plugin plugin) {
        this.id = new NamespacedKey(plugin, "id");
        this.kind = new NamespacedKey(plugin, "kind");
        this.level = new NamespacedKey(plugin, "level");
        this.uid = new NamespacedKey(plugin, "uid");
        this.runes = new NamespacedKey(plugin, "runes");
        this.schema = new NamespacedKey(plugin, "schema");
        this.signature = new NamespacedKey(plugin, "sig");
        this.menuIcon = new NamespacedKey(plugin, "menu_icon");
        this.summonOwner = new NamespacedKey(plugin, "summon_owner");
        this.summonTarget = new NamespacedKey(plugin, "summon_target");
        this.abilityArrow = new NamespacedKey(plugin, "ability_arrow");
        this.cooldowns = new NamespacedKey(plugin, "cooldowns");
    }
}
