package dev.exodaily.core;

import dev.exodaily.core.config.PluginSettings;
import dev.exodaily.core.delivery.InventoryFit;
import dev.exodaily.core.text.TextStyler;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryAndTextTest {

    /** A stack of {@code type} with {@code amount}, max stack size {@code max}. */
    record Stack(String type, int amount, int max) {
    }

    private static final InventoryFit.StackOps<Stack> OPS = new InventoryFit.StackOps<>() {
        @Override
        public boolean isEmpty(Stack stack) {
            return stack.amount() <= 0;
        }

        @Override
        public int amount(Stack stack) {
            return stack.amount();
        }

        @Override
        public int maxStackSize(Stack stack) {
            return stack.max();
        }

        @Override
        public boolean similar(Stack a, Stack b) {
            return a.type().equals(b.type());
        }
    };

    private static List<Stack> slots(int size, Stack... filled) {
        List<Stack> slots = new ArrayList<>(Arrays.asList(new Stack[size]));
        for (int i = 0; i < filled.length; i++) {
            slots.set(i, filled[i]);
        }
        return slots;
    }

    @Test
    void completelyFullInventoryRejectsTheReward() {
        Stack[] full = new Stack[36];
        Arrays.fill(full, new Stack("dirt", 64, 64));
        assertFalse(InventoryFit.fits(slots(36, full), List.of(new Stack("diamond", 1, 64)), OPS));
    }

    @Test
    void partialStacksAreFilledBeforeEmptySlots() {
        Stack[] almostFull = new Stack[36];
        Arrays.fill(almostFull, new Stack("dirt", 64, 64));
        almostFull[0] = new Stack("diamond", 60, 64);
        assertTrue(InventoryFit.fits(slots(36, almostFull), List.of(new Stack("diamond", 4, 64)), OPS));
        assertFalse(InventoryFit.fits(slots(36, almostFull), List.of(new Stack("diamond", 5, 64)), OPS));
    }

    @Test
    void multiStackRewardsNeedEnoughSlots() {
        Stack[] filled = new Stack[34];
        Arrays.fill(filled, new Stack("dirt", 64, 64));
        List<Stack> twoFree = slots(36, filled);
        assertTrue(InventoryFit.fits(twoFree, List.of(new Stack("coal", 64, 64), new Stack("coal", 64, 64)), OPS));
        assertFalse(InventoryFit.fits(twoFree, List.of(new Stack("coal", 64, 64), new Stack("coal", 64, 64),
                new Stack("coal", 1, 64)), OPS));
        // Unstackable items need one slot each.
        assertFalse(InventoryFit.fits(twoFree, List.of(new Stack("sword", 1, 1), new Stack("sword", 1, 1),
                new Stack("sword", 1, 1)), OPS));
    }

    @Test
    void smallCapsConvertTextButNotTagsOrPlaceholders() {
        assertEquals("ᴅᴀʏ <day>", TextStyler.toSmallCaps("day <day>"));
        assertEquals("<#A78BFA>ʜᴏᴡ ɪᴛ ᴡᴏʀᴋꜱ", TextStyler.toSmallCaps("<#A78BFA>how it works"));
        assertEquals("x <sc>", TextStyler.applySmallCaps("x <sc>", true));
        assertEquals("a ᴅᴀʏ b", TextStyler.applySmallCaps("a <sc>day</sc> b", true));
        assertEquals("a day b", TextStyler.applySmallCaps("a <sc>day</sc> b", false));
    }

    @Test
    void symbolsHavePlainAlternatives() {
        Map<String, PluginSettings.Symbol> symbols = Map.of("star", new PluginSettings.Symbol("✦", "*"));
        TextStyler fancy = new TextStyler(new PluginSettings.Style(true, true, symbols));
        TextStyler plain = new TextStyler(new PluginSettings.Style(false, false, symbols));
        String template = "<#A78BFA><sym:star> <sc>day 7</sc>";
        assertEquals("✦ ᴅᴀʏ 7", PlainTextComponentSerializer.plainText().serialize(fancy.render(template)));
        assertEquals("* day 7", PlainTextComponentSerializer.plainText().serialize(plain.render(template)));
    }

    @Test
    void countdownFormatting() {
        assertEquals("5h 24m", TextStyler.formatDuration(Duration.ofMinutes(5 * 60 + 24).plusSeconds(59), "<1m"));
        assertEquals("24m", TextStyler.formatDuration(Duration.ofMinutes(24), "<1m"));
        assertEquals("<1m", TextStyler.formatDuration(Duration.ofSeconds(30), "<1m"));
        assertEquals("<1m", TextStyler.formatDuration(Duration.ofSeconds(-5), "<1m"));
    }
}
