package com.exoblacksmith.gui;

import com.exoblacksmith.Services;
import com.exoblacksmith.craft.RuneService;
import com.exoblacksmith.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/** Lists every ExoBlackSmith armor piece the player carries or wears; pick one to add runes. */
public final class RuneForgeMenu extends Menu {
    public RuneForgeMenu(Services services, Player viewer) {
        super(services, viewer);
    }

    @Override
    protected int size() {
        return 54;
    }

    @Override
    protected Component title() {
        return Text.trusted(reg(), reg().menus.title("runeforge"), Map.of());
    }

    @Override
    protected void render() {
        set(4, configured("info", Map.of()));
        List<RuneService.ArmorLocation> armor = services.runes().allArmor(viewer);
        for (int i = 0; i < armor.size() && i < CategoryMenu.CONTENT.length; i++) {
            RuneService.ArmorLocation loc = armor.get(i);
            List<Component> lore = new ArrayList<>();
            lore.add(Component.empty());
            lore.add(Text.item(reg(), reg().messages.raw(loc.data().runes().size() >= 3 ? "menu-rune-full" : "menu-rune-select"),
                    Map.of("used", Integer.toString(loc.data().runes().size()))));
            String uid = loc.data().uid();
            button(CategoryMenu.CONTENT[i], services.items().icon(loc.stack(), lore, 1),
                    click -> open(new RuneApplyMenu(services, viewer, uid, null)));
        }
        if (armor.isEmpty()) {
            set(22, decorate(plain(reg().menus.button("empty-slot").material(), 1),
                    Text.item(reg(), reg().messages.raw("menu-rune-no-armor"), Map.of()), List.of()));
        }
        button(45, configured("back", Map.of()), click -> open(new MainMenu(services, viewer)));
        button(49, configured("close", Map.of()), click -> close());
        fill();
    }
}
