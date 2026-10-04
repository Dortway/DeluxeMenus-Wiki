package com.exoblacksmith.craft;

import com.exoblacksmith.config.Registry;
import com.exoblacksmith.config.model.ArmorPieceDef;
import com.exoblacksmith.config.model.RuneDef;
import com.exoblacksmith.item.ItemData;
import com.exoblacksmith.item.ItemKind;
import com.exoblacksmith.item.ItemService;
import com.exoblacksmith.item.RuneSlot;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Installs runes on ExoBlackSmith armor. Everything is re-validated at the moment of application:
 * the armor is located again by its UID, the rune stack is re-authenticated, and slot/duplicate/capacity
 * rules are checked before anything changes.
 */
public final class RuneService {

    public enum Result { OK, ARMOR_MISSING, RUNE_MISSING, NOT_ARMOR, INCOMPATIBLE, NO_SLOTS, DUPLICATE }

    /** Where an armor piece currently sits: a storage index (0-35) or an armor slot index (36-39). */
    public record ArmorLocation(int index, ItemStack stack, ItemData data, ArmorPieceDef def) {
    }

    private final ItemService items;
    private final Supplier<Registry> registry;
    private final Logger audit;

    public RuneService(ItemService items, Supplier<Registry> registry, Logger audit) {
        this.items = items;
        this.registry = registry;
        this.audit = audit;
    }

    /** Pure rule check shared by the GUI preview and the final application. */
    public Result check(ItemData armor, ArmorPieceDef piece, RuneDef rune) {
        if (armor == null || armor.kind() != ItemKind.ARMOR || piece == null) {
            return Result.NOT_ARMOR;
        }
        if (!rune.slot().accepts(piece.slot())) {
            return Result.INCOMPATIBLE;
        }
        if (armor.hasRune(rune.id())) {
            return Result.DUPLICATE;
        }
        if (armor.runes().size() >= RuneSlot.MAX_SLOTS) {
            return Result.NO_SLOTS;
        }
        return Result.OK;
    }

    public ArmorLocation findArmor(Player player, String uid) {
        PlayerInventory inv = player.getInventory();
        ItemStack[] storage = inv.getStorageContents();
        for (int i = 0; i < storage.length; i++) {
            ArmorLocation loc = armorAt(i, storage[i], uid);
            if (loc != null) {
                return loc;
            }
        }
        ItemStack[] armor = inv.getArmorContents();
        for (int i = 0; i < armor.length; i++) {
            ArmorLocation loc = armorAt(36 + i, armor[i], uid);
            if (loc != null) {
                return loc;
            }
        }
        return null;
    }

    private ArmorLocation armorAt(int index, ItemStack stack, String uid) {
        ItemService.Identified id = items.identify(stack);
        if (id == null || id.data().kind() != ItemKind.ARMOR || (uid != null && !uid.equals(id.data().uid()))) {
            return null;
        }
        return new ArmorLocation(index, stack, id.data(), (ArmorPieceDef) id.def());
    }

    public java.util.List<ArmorLocation> allArmor(Player player) {
        java.util.List<ArmorLocation> out = new java.util.ArrayList<>();
        PlayerInventory inv = player.getInventory();
        ItemStack[] storage = inv.getStorageContents();
        for (int i = 0; i < storage.length; i++) {
            ArmorLocation loc = armorAt(i, storage[i], null);
            if (loc != null) {
                out.add(loc);
            }
        }
        ItemStack[] armor = inv.getArmorContents();
        for (int i = 0; i < armor.length; i++) {
            ArmorLocation loc = armorAt(36 + i, armor[i], null);
            if (loc != null) {
                out.add(loc);
            }
        }
        return out;
    }

    public Result apply(Player player, String armorUid, String runeId, int tier) {
        Registry reg = registry.get();
        RuneDef rune = reg.rune(runeId);
        if (rune == null || tier < 1 || tier > rune.maxLevel()) {
            return Result.RUNE_MISSING;
        }
        ArmorLocation armor = findArmor(player, armorUid);
        if (armor == null) {
            return Result.ARMOR_MISSING;
        }
        Result rule = check(armor.data(), armor.def(), rune);
        if (rule != Result.OK) {
            return rule;
        }
        PlayerInventory inv = player.getInventory();
        ItemStack[] storage = inv.getStorageContents();
        int runeIndex = -1;
        for (int i = 0; i < storage.length; i++) {
            ItemService.Identified id = items.identify(storage[i]);
            if (id != null && id.data().kind() == ItemKind.RUNE && id.data().id().equals(runeId) && id.data().level() == tier) {
                runeIndex = i;
                break;
            }
        }
        if (runeIndex < 0) {
            return Result.RUNE_MISSING;
        }

        ItemData upgraded = armor.data().withRune(new RuneSlot(runeId, tier));
        ItemStack newArmor = items.build(armor.def(), upgraded, 1);
        ItemService.carryPlayerChanges(armor.stack(), newArmor);

        // Apply both changes in the same tick, after every check passed.
        ItemStack runeStack = storage[runeIndex];
        if (runeStack.getAmount() <= 1) {
            storage[runeIndex] = null;
        } else {
            runeStack = runeStack.clone();
            runeStack.setAmount(runeStack.getAmount() - 1);
            storage[runeIndex] = runeStack;
        }
        if (armor.index() < 36) {
            storage[armor.index()] = newArmor;
            inv.setStorageContents(storage);
        } else {
            inv.setStorageContents(storage);
            ItemStack[] worn = inv.getArmorContents();
            worn[armor.index() - 36] = newArmor;
            inv.setArmorContents(worn);
        }
        player.updateInventory();
        audit.info("[rune] " + player.getName() + " (" + player.getUniqueId() + ") applied " + runeId + " t" + tier
                + " to " + armor.def().id() + " uid=" + armorUid);
        return Result.OK;
    }
}
