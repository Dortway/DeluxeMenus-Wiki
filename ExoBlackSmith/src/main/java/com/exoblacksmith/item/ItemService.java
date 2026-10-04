package com.exoblacksmith.item;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.exoblacksmith.config.Registry;
import com.exoblacksmith.config.model.Appearance;
import com.exoblacksmith.config.model.ItemDef;
import com.exoblacksmith.config.model.ItemRef;
import com.exoblacksmith.integration.VisualProvider;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Creates, identifies and re-renders ExoBlackSmith items. Identification requires a valid signature,
 * a known id, a level within range, and (for unique items) a UID that has not been retired.
 */
public final class ItemService {

    /** An authenticated item together with its current definition. */
    public record Identified(ItemData data, ItemDef def) {
    }

    private final ItemKeys keys;
    private final Signer signer;
    private final RetiredUids retired;
    private final Supplier<Registry> registry;
    private final Supplier<VisualProvider> visuals;
    private final LoreBuilder lore;

    public ItemService(ItemKeys keys, Signer signer, RetiredUids retired, Supplier<Registry> registry,
                       Supplier<VisualProvider> visuals) {
        this.keys = keys;
        this.signer = signer;
        this.retired = retired;
        this.registry = registry;
        this.visuals = visuals;
        this.lore = new LoreBuilder(registry, visuals);
    }

    public ItemKeys keys() {
        return keys;
    }

    public RetiredUids retired() {
        return retired;
    }

    // ------------------------------------------------------------------ identification

    /** True if the stack carries any ExoBlackSmith marker, authentic or not (used to block transformations). */
    public boolean isTagged(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasItemMeta()) {
            return false;
        }
        PersistentDataContainer pdc = stack.getItemMeta().getPersistentDataContainer();
        return pdc.has(keys.id) || pdc.has(keys.menuIcon);
    }

    public boolean isMenuIcon(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.hasItemMeta()
                && stack.getItemMeta().getPersistentDataContainer().has(keys.menuIcon);
    }

    /** Reads and verifies identity data. Returns null for vanilla items, forgeries and malformed data. */
    public ItemData read(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasItemMeta()) {
            return null;
        }
        PersistentDataContainer pdc = stack.getItemMeta().getPersistentDataContainer();
        if (pdc.has(keys.menuIcon)) {
            return null;
        }
        String id = pdc.get(keys.id, PersistentDataType.STRING);
        String kindName = pdc.get(keys.kind, PersistentDataType.STRING);
        Integer level = pdc.get(keys.level, PersistentDataType.INTEGER);
        Integer schema = pdc.get(keys.schema, PersistentDataType.INTEGER);
        String sig = pdc.get(keys.signature, PersistentDataType.STRING);
        if (id == null || kindName == null || level == null || schema == null || sig == null || schema != ItemData.SCHEMA) {
            return null;
        }
        try {
            ItemKind kind = ItemKind.valueOf(kindName);
            String uid = pdc.get(keys.uid, PersistentDataType.STRING);
            List<RuneSlot> runes = RuneSlot.decode(pdc.get(keys.runes, PersistentDataType.STRING));
            ItemData data = new ItemData(kind, id, level, uid, runes);
            return signer.verify(data, sig) ? data : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Full authentication against the current registry. */
    public Identified identify(ItemStack stack) {
        ItemData data = read(stack);
        if (data == null) {
            return null;
        }
        ItemDef def = registry.get().item(data.id());
        if (def == null || def.kind() != data.kind() || data.level() > def.maxLevel()) {
            return null;
        }
        if (data.kind().unique() && (stack.getAmount() != 1 || retired.isRetired(data.uid()))) {
            return null;
        }
        for (RuneSlot rune : data.runes()) {
            if (registry.get().rune(rune.runeId()) == null) {
                return null;
            }
        }
        return new Identified(data, def);
    }

    public boolean matches(ItemStack stack, ItemRef ref) {
        if (ref.isVanilla()) {
            return false;
        }
        ItemData data = read(stack);
        return data != null && data.id().equals(ref.exoId()) && data.level() == ref.level()
                && identify(stack) != null;
    }

    // ------------------------------------------------------------------ creation

    public ItemStack create(ItemRef ref, int amount) {
        ItemDef def = registry.get().item(ref.exoId());
        if (def == null) {
            throw new IllegalArgumentException("unknown item " + ref.exoId());
        }
        return create(def, ref.level(), amount);
    }

    public ItemStack create(ItemDef def, int level, int amount) {
        if (level < 1 || level > def.maxLevel()) {
            throw new IllegalArgumentException(def.id() + " has no level " + level);
        }
        ItemData data = def.kind().unique()
                ? new ItemData(def.kind(), def.id(), level, UUID.randomUUID().toString(), List.of())
                : ItemData.stackable(def.kind(), def.id(), level);
        return build(def, data, def.kind().unique() ? 1 : amount);
    }

    /** Builds a stack for existing data (e.g. after applying a rune), re-signing it. */
    public ItemStack build(ItemDef def, ItemData data, int amount) {
        Appearance appearance = def.appearance(data.level());
        ItemStack stack = ItemStack.of(appearance.material(), Math.max(1, amount));
        ItemMeta meta = stack.getItemMeta();
        writeData(meta.getPersistentDataContainer(), data);
        present(meta, def, data);
        stack.setItemMeta(meta);
        return stack;
    }

    private void writeData(PersistentDataContainer pdc, ItemData data) {
        pdc.set(keys.id, PersistentDataType.STRING, data.id());
        pdc.set(keys.kind, PersistentDataType.STRING, data.kind().name());
        pdc.set(keys.level, PersistentDataType.INTEGER, data.level());
        pdc.set(keys.schema, PersistentDataType.INTEGER, ItemData.SCHEMA);
        if (data.uid() != null) {
            pdc.set(keys.uid, PersistentDataType.STRING, data.uid());
        } else {
            pdc.remove(keys.uid);
        }
        if (!data.runes().isEmpty()) {
            pdc.set(keys.runes, PersistentDataType.STRING, RuneSlot.encode(data.runes()));
        } else {
            pdc.remove(keys.runes);
        }
        pdc.set(keys.signature, PersistentDataType.STRING, signer.sign(data));
    }

    /** Applies name, lore and visuals. Identity data is untouched. */
    private void present(ItemMeta meta, ItemDef def, ItemData data) {
        Registry reg = registry.get();
        meta.displayName(lore.name(def, data));
        meta.lore(lore.lore(def, data));
        VisualProvider provider = visuals.get();
        Appearance appearance = def.appearance(data.level());
        if (appearance.itemModel() != null) {
            meta.setItemModel(NamespacedKey.fromString(appearance.itemModel()));
        }
        if (appearance.customModelData() != null) {
            CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
            cmd.setFloats(List.of(appearance.customModelData()));
            meta.setCustomModelDataComponent(cmd);
        }
        if (appearance.itemsAdderId() != null && reg.settings.itemsAdderEnabled) {
            provider.applyVisuals(meta, appearance.itemsAdderId());
        }
        if (appearance.glint()) {
            meta.setEnchantmentGlintOverride(true);
        }
        if (appearance.texture() != null && meta instanceof SkullMeta skull) {
            skull.setPlayerProfile(profile(appearance.texture()));
        }
        if (def.kind().unique()) {
            meta.setMaxStackSize(1);
        }
        if (def.kind() != ItemKind.ARMOR) {
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        }
    }

    /**
     * Exact supplied texture, attached to a deterministic profile UUID derived from the texture so that
     * identical heads stack. The texture is presentation only.
     */
    public static PlayerProfile profile(String textureBase64) {
        UUID uuid = UUID.nameUUIDFromBytes(("exoblacksmith:" + textureBase64).getBytes(StandardCharsets.UTF_8));
        PlayerProfile profile = Bukkit.createProfile(uuid);
        profile.setProperty(new ProfileProperty("textures", textureBase64));
        return profile;
    }

    /**
     * Re-renders presentation for an authentic item (after config changes or once ItemsAdder loads).
     * Returns false if the stack is not an authentic ExoBlackSmith item.
     */
    public boolean refresh(ItemStack stack) {
        Identified identified = identify(stack);
        if (identified == null) {
            return false;
        }
        ItemStack fresh = build(identified.def(), identified.data(), stack.getAmount());
        if (fresh.getType() != stack.getType()) {
            stack.setType(fresh.getType());
        }
        carryPlayerChanges(stack, fresh);
        stack.setItemMeta(fresh.getItemMeta());
        return true;
    }

    /** Copies enchantments, durability and armor trims the player added from one stack to another. */
    public static void carryPlayerChanges(ItemStack from, ItemStack to) {
        ItemMeta old = from.getItemMeta();
        ItemMeta meta = to.getItemMeta();
        if (old == null || meta == null) {
            return;
        }
        old.getEnchants().forEach((ench, lvl) -> meta.addEnchant(ench, lvl, true));
        if (old instanceof org.bukkit.inventory.meta.Damageable od && meta instanceof org.bukkit.inventory.meta.Damageable nd) {
            nd.setDamage(od.getDamage());
        }
        if (old instanceof org.bukkit.inventory.meta.ArmorMeta oa && meta instanceof org.bukkit.inventory.meta.ArmorMeta na
                && oa.hasTrim()) {
            na.setTrim(oa.getTrim());
        }
        to.setItemMeta(meta);
    }

    /** Display copy for menus: same look, identity stripped, marked as a menu icon. */
    public ItemStack icon(ItemStack source, List<Component> extraLore, int amount) {
        ItemStack icon = source.clone();
        icon.setAmount(Math.max(1, Math.min(amount, icon.getMaxStackSize())));
        ItemMeta meta = icon.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        for (NamespacedKey key : List.of(keys.id, keys.kind, keys.level, keys.uid, keys.runes, keys.schema, keys.signature)) {
            pdc.remove(key);
        }
        pdc.set(keys.menuIcon, PersistentDataType.BYTE, (byte) 1);
        if (extraLore != null && !extraLore.isEmpty()) {
            List<Component> lines = meta.hasLore() ? new java.util.ArrayList<>(meta.lore()) : new java.util.ArrayList<>();
            lines.addAll(extraLore);
            meta.lore(lines);
        }
        icon.setItemMeta(meta);
        return icon;
    }

    public void markIcon(ItemMeta meta) {
        meta.getPersistentDataContainer().set(keys.menuIcon, PersistentDataType.BYTE, (byte) 1);
    }

    public LoreBuilder lore() {
        return lore;
    }
}
