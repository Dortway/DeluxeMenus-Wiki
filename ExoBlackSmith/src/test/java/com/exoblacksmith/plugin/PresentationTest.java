package com.exoblacksmith.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.exoblacksmith.ExoBlackSmithPlugin;
import com.exoblacksmith.config.model.ItemDef;
import com.exoblacksmith.craft.RuneService;
import com.exoblacksmith.item.ItemService;
import com.exoblacksmith.util.Text;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Renders every default item and writes plain-text samples to target/lore-samples.txt for review. */
class PresentationTest {
    ExoBlackSmithPlugin plugin;
    ItemService items;
    PlayerMock player;

    @BeforeEach
    void setUp() {
        var server = MockBukkit.mock();
        plugin = MockBukkit.load(ExoBlackSmithPlugin.class);
        items = plugin.services().items();
        player = server.addPlayer("Smith");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    static List<String> plain(ItemStack stack) {
        List<String> out = new ArrayList<>();
        out.add(Text.plain(stack.getItemMeta().displayName()));
        for (Component line : stack.getItemMeta().lore()) {
            out.add("  " + Text.plain(line));
        }
        return out;
    }

    @Test
    void everyItemRendersAndArmorShowsThreeRuneSlots() throws Exception {
        List<String> dump = new ArrayList<>();
        for (ItemDef def : plugin.registry().items()) {
            for (int level = 1; level <= def.maxLevel(); level++) {
                ItemStack stack = items.create(def, level, 1);
                List<String> lines = plain(stack);
                assertTrue(lines.size() > 1, def.id());
                for (String line : lines) {
                    assertTrue(!line.contains("{") && !line.contains("missing message"), def.id() + ": " + line);
                }
                if (def.id().equals("riftguard_chestplate")) {
                    assertEquals(3, lines.stream().filter(l -> l.contains("+ rune slot: empty")).count());
                }
                dump.addAll(lines);
                dump.add("");
            }
        }
        // a socketed piece replaces one empty line with the rune and tier
        ItemStack chest = items.create(plugin.registry().armor("riftguard_chestplate"), 1, 1);
        player.getInventory().addItem(chest, items.create(plugin.registry().rune("blast_rune"), 2, 1));
        String uid = items.read(chest).uid();
        assertEquals(RuneService.Result.OK, plugin.services().runes().apply(player, uid, "blast_rune", 2));
        List<String> socketed = plain(plugin.services().runes().findArmor(player, uid).stack());
        assertEquals(2, socketed.stream().filter(l -> l.contains("+ rune slot: empty")).count());
        assertEquals(1, socketed.stream().filter(l -> l.contains("+ rune slot: blast rune ii")).count());
        dump.add("== riftguard chestplate after socketing blast rune ii ==");
        dump.addAll(socketed);
        Files.write(Path.of("target/lore-samples.txt"), dump);
    }
}
