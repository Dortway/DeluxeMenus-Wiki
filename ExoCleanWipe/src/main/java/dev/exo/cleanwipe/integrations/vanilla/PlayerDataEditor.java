package dev.exo.cleanwipe.integrations.vanilla;

import dev.exo.cleanwipe.integrations.vanilla.Nbt.Tag;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Edits a decoded player file. Since 1.21.5 armour and offhand live in {@code equipment}; older files keep them in
 * {@code Inventory} slots 100-103/-106 - both layouts are handled. The cursor is not stored in the file (the server
 * puts it back into the inventory or drops it when the player leaves), which is why online targets are cleared
 * through the API before they are kicked.
 */
public final class PlayerDataEditor {

    public record Options(boolean inventory, boolean enderChest, boolean experience) {
    }

    public record Result(int inventoryStacks, int equipmentStacks, int enderStacks, boolean experienceReset,
                         boolean vehicleRemoved, int markersRemoved) {
        public boolean changedAnything() {
            return inventoryStacks + equipmentStacks + enderStacks + markersRemoved > 0 || experienceReset || vehicleRemoved;
        }

        public String summary() {
            List<String> parts = new ArrayList<>();
            if (inventoryStacks > 0) {
                parts.add("inventory " + inventoryStacks);
            }
            if (equipmentStacks > 0) {
                parts.add("armour/offhand " + equipmentStacks);
            }
            if (enderStacks > 0) {
                parts.add("ender chest " + enderStacks);
            }
            if (experienceReset) {
                parts.add("experience reset");
            }
            if (vehicleRemoved) {
                parts.add("ridden mount with storage removed");
            }
            return parts.isEmpty() ? "empty" : String.join(", ", parts);
        }
    }

    /** ExoVaults stores its consistency markers in the player's persistent data (see ExoVaults docs/STORAGE.md). */
    static final List<String> EXOVAULTS_MARKERS = List.of("exovaults:vault_revision", "exovaults:vault_dirty");

    private PlayerDataEditor() {
    }

    public static Result inspect(Map<String, Tag> root, Options o) throws IOException {
        return apply(root, o, false);
    }

    public static Result apply(Map<String, Tag> root, Options o) throws IOException {
        return apply(root, o, true);
    }

    private static Result apply(Map<String, Tag> root, Options o, boolean write) throws IOException {
        if (!root.containsKey("DataVersion") && !root.containsKey("Inventory") && !root.containsKey("EnderItems")) {
            throw new IOException("unrecognised player data layout (no DataVersion, Inventory or EnderItems)");
        }
        int inv = 0;
        int equip = 0;
        int ender = 0;
        boolean xp = false;
        boolean vehicle = false;
        int markers = 0;
        if (o.inventory()) {
            inv = size(root.get("Inventory"));
            if (write && root.containsKey("Inventory")) {
                root.put("Inventory", Nbt.emptyList());
            }
            Tag eq = root.get("equipment");
            if (eq != null && eq.type() == Nbt.COMPOUND) {
                equip = eq.compound().size();
                if (write) {
                    root.remove("equipment");
                }
            }
            Tag rv = root.get("RootVehicle");
            if (rv != null && rv.type() == Nbt.COMPOUND && Nbt.COMPOUND == typeOf(rv.compound().get("Entity"))
                    && carriesItems(rv.compound().get("Entity").compound())) {
                vehicle = true;
                if (write) {
                    root.remove("RootVehicle");
                }
            }
        }
        if (o.enderChest()) {
            ender = size(root.get("EnderItems"));
            if (write && root.containsKey("EnderItems")) {
                root.put("EnderItems", Nbt.emptyList());
            }
        }
        if (o.experience()) {
            xp = nonZero(root.get("XpLevel")) || nonZero(root.get("XpTotal")) || nonZero(root.get("XpP")) || nonZero(root.get("Score"));
            if (write && xp) {
                setIfPresent(root, "XpLevel", Nbt.intTag(0));
                setIfPresent(root, "XpTotal", Nbt.intTag(0));
                setIfPresent(root, "XpP", Nbt.floatTag(0f));
                setIfPresent(root, "Score", Nbt.intTag(0));
            }
        }
        Tag bukkit = root.get("BukkitValues");
        if (bukkit != null && bukkit.type() == Nbt.COMPOUND) {
            for (String key : EXOVAULTS_MARKERS) {
                if (bukkit.compound().containsKey(key)) {
                    markers++;
                    if (write) {
                        bukkit.compound().remove(key);
                    }
                }
            }
        }
        return new Result(inv, equip, ender, xp, vehicle, markers);
    }

    private static byte typeOf(Tag t) {
        return t == null ? -1 : t.type();
    }

    private static boolean carriesItems(Map<String, Tag> entity) {
        for (String key : List.of("Items", "Inventory", "equipment", "body_armor_item", "SaddleItem", "ArmorItem")) {
            Tag t = entity.get(key);
            if (t != null && (t.type() == Nbt.COMPOUND || size(t) > 0)) {
                return true;
            }
        }
        return false;
    }

    private static int size(Tag t) {
        return t != null && t.type() == Nbt.LIST ? t.list().items().size() : 0;
    }

    private static boolean nonZero(Tag t) {
        if (t == null) {
            return false;
        }
        return switch (t.type()) {
            case Nbt.INT -> (Integer) t.value() != 0;
            case Nbt.FLOAT -> (Float) t.value() != 0f;
            default -> false;
        };
    }

    private static void setIfPresent(Map<String, Tag> root, String key, Tag value) {
        Tag old = root.get(key);
        if (old != null && old.type() == value.type()) {
            root.put(key, value);
        }
    }
}
