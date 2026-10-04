package com.exoblacksmith.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SignerTest {
    private final Signer signer = new Signer("0123456789abcdef0123456789abcdef".getBytes());

    @Test
    void validSignatureVerifies() {
        ItemData data = ItemData.stackable(ItemKind.RUNE, "blast_rune", 2);
        assertTrue(signer.verify(data, signer.sign(data)));
    }

    @Test
    void anyIdentityChangeBreaksSignature() {
        ItemData rune = ItemData.stackable(ItemKind.RUNE, "blast_rune", 1);
        String sig = signer.sign(rune);
        assertFalse(signer.verify(ItemData.stackable(ItemKind.RUNE, "blast_rune", 3), sig), "tier edit");
        assertFalse(signer.verify(ItemData.stackable(ItemKind.RUNE, "phoenix_aura", 1), sig), "id edit");
        assertFalse(signer.verify(ItemData.stackable(ItemKind.MATERIAL, "blast_rune", 1), sig), "kind edit");

        ItemData armor = new ItemData(ItemKind.ARMOR, "riftguard_chestplate", 1, "uid-1", List.of());
        String armorSig = signer.sign(armor);
        assertFalse(signer.verify(armor.withRune(new RuneSlot("blast_rune", 3)), armorSig), "added rune");
        assertFalse(signer.verify(new ItemData(ItemKind.ARMOR, "riftguard_chestplate", 1, "uid-2", List.of()), armorSig), "uid edit");
    }

    @Test
    void otherServerSecretRejected() {
        ItemData data = ItemData.stackable(ItemKind.HEAD, "creeper_value_head", 1);
        Signer other = new Signer("ffffffffffffffffffffffffffffffff".getBytes());
        assertFalse(other.verify(data, signer.sign(data)));
        assertFalse(signer.verify(data, null));
        assertFalse(signer.verify(data, "not-a-signature"));
    }

    @Test
    void runeEncodingRoundTripsAndRejectsGarbage() {
        List<RuneSlot> runes = List.of(new RuneSlot("blast_rune", 1), new RuneSlot("phoenix_aura", 3));
        assertEquals(runes, RuneSlot.decode(RuneSlot.encode(runes)));
        assertEquals(List.of(), RuneSlot.decode(""));
        assertThrows(IllegalArgumentException.class, () -> RuneSlot.decode("blast_rune"));
        assertThrows(IllegalArgumentException.class, () -> RuneSlot.decode("a:1,b:1,c:1,d:1"));
        assertThrows(IllegalArgumentException.class, () -> RuneSlot.decode("Bad-Id:1"));
        assertThrows(IllegalArgumentException.class, () -> RuneSlot.decode("blast_rune:0"));
    }

    @Test
    void itemDataInvariants() {
        assertThrows(IllegalArgumentException.class, () -> new ItemData(ItemKind.MASK, "sedge", 1, null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ItemData(ItemKind.RUNE, "x", 1, "uid", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ItemData(ItemKind.MASK, "sedge", 1, "u", List.of(new RuneSlot("blast_rune", 1))));
        assertThrows(IllegalArgumentException.class, () -> ItemData.stackable(ItemKind.RUNE, "x", 0));
    }
}
