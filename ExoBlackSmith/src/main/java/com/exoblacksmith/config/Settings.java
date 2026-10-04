package com.exoblacksmith.config;

import com.exoblacksmith.config.model.EquipSlot;
import com.exoblacksmith.config.model.Rarity;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.event.player.PlayerTeleportEvent;

/** Immutable snapshot of config.yml. */
public final class Settings {
    public enum RuneStacking { HIGHEST, SUM }

    public enum ReductionCombine { MULTIPLICATIVE, ADDITIVE }

    public final boolean unicodeSymbols;
    public final Map<String, String[]> symbols;
    public final boolean smallCaps;
    public final Map<String, Rarity> rarities;

    public final int equipmentScanTicks;
    public final long clickCooldownMs;
    public final boolean strictVanilla;
    public final boolean refreshItemsOnJoin;

    public final boolean blockCrafting;
    public final boolean blockAnvil;
    public final boolean blockSmithing;
    public final boolean blockGrindstone;
    public final boolean blockEnchanting;
    public final boolean blockPlacement;

    public final double maxTotalReduction;
    public final ReductionCombine combine;
    public final RuneStacking runeStacking;
    public final double maxBonusDamagePerHit;
    public final Set<EquipSlot> setBonusSlots;
    public final boolean speedDisabledWhileFlying;

    public final boolean abilityRequireSneak;
    public final boolean abilityRequireEmptyHand;
    public final long abilityDebounceMs;
    public final int summonMaxActive;
    public final boolean summonRespectTeams;
    public final PlayerTeleportEvent.TeleportCause teleportCause;
    public final boolean respectSpawnProtection;

    public final boolean itemsAdderEnabled;

    public final List<String> dropWorlds;
    public final boolean dropRequirePlayerKiller;
    public final boolean dropAllowSpawner;
    public final boolean dropAllowNatural;
    public final double dropLootingBonus;
    public final boolean dropToInventory;

    public final boolean exoEnabled;
    public final String exoEventClass;
    public final String exoKillerMethod;
    public final String exoEntityMethod;
    public final String exoAmountMethod;
    public final List<String> exoStackedMarkers;

    Settings(Builder b) {
        this.unicodeSymbols = b.unicodeSymbols;
        this.symbols = Map.copyOf(b.symbols);
        this.smallCaps = b.smallCaps;
        this.rarities = Map.copyOf(b.rarities);
        this.equipmentScanTicks = b.equipmentScanTicks;
        this.clickCooldownMs = b.clickCooldownMs;
        this.strictVanilla = b.strictVanilla;
        this.refreshItemsOnJoin = b.refreshItemsOnJoin;
        this.blockCrafting = b.blockCrafting;
        this.blockAnvil = b.blockAnvil;
        this.blockSmithing = b.blockSmithing;
        this.blockGrindstone = b.blockGrindstone;
        this.blockEnchanting = b.blockEnchanting;
        this.blockPlacement = b.blockPlacement;
        this.maxTotalReduction = b.maxTotalReduction;
        this.combine = b.combine;
        this.runeStacking = b.runeStacking;
        this.maxBonusDamagePerHit = b.maxBonusDamagePerHit;
        this.setBonusSlots = Set.copyOf(b.setBonusSlots);
        this.speedDisabledWhileFlying = b.speedDisabledWhileFlying;
        this.abilityRequireSneak = b.abilityRequireSneak;
        this.abilityRequireEmptyHand = b.abilityRequireEmptyHand;
        this.abilityDebounceMs = b.abilityDebounceMs;
        this.summonMaxActive = b.summonMaxActive;
        this.summonRespectTeams = b.summonRespectTeams;
        this.teleportCause = b.teleportCause;
        this.respectSpawnProtection = b.respectSpawnProtection;
        this.itemsAdderEnabled = b.itemsAdderEnabled;
        this.dropWorlds = List.copyOf(b.dropWorlds);
        this.dropRequirePlayerKiller = b.dropRequirePlayerKiller;
        this.dropAllowSpawner = b.dropAllowSpawner;
        this.dropAllowNatural = b.dropAllowNatural;
        this.dropLootingBonus = b.dropLootingBonus;
        this.dropToInventory = b.dropToInventory;
        this.exoEnabled = b.exoEnabled;
        this.exoEventClass = b.exoEventClass;
        this.exoKillerMethod = b.exoKillerMethod;
        this.exoEntityMethod = b.exoEntityMethod;
        this.exoAmountMethod = b.exoAmountMethod;
        this.exoStackedMarkers = List.copyOf(b.exoStackedMarkers);
    }

    /** Resolves a symbol name to its unicode or plain-text form. */
    public String symbol(String name) {
        String[] pair = symbols.get(name);
        if (pair == null) {
            return "";
        }
        return unicodeSymbols ? pair[0] : pair[1];
    }

    static final class Builder {
        boolean unicodeSymbols = true;
        Map<String, String[]> symbols = Map.of();
        boolean smallCaps;
        Map<String, Rarity> rarities = Map.of();
        int equipmentScanTicks = 10;
        long clickCooldownMs = 250;
        boolean strictVanilla = true;
        boolean refreshItemsOnJoin = true;
        boolean blockCrafting = true;
        boolean blockAnvil = true;
        boolean blockSmithing = true;
        boolean blockGrindstone = true;
        boolean blockEnchanting = true;
        boolean blockPlacement = true;
        double maxTotalReduction = 0.8;
        ReductionCombine combine = ReductionCombine.MULTIPLICATIVE;
        RuneStacking runeStacking = RuneStacking.HIGHEST;
        double maxBonusDamagePerHit = 6;
        Set<EquipSlot> setBonusSlots = Set.of(EquipSlot.CHESTPLATE, EquipSlot.LEGGINGS, EquipSlot.BOOTS);
        boolean speedDisabledWhileFlying = true;
        boolean abilityRequireSneak = true;
        boolean abilityRequireEmptyHand;
        long abilityDebounceMs = 300;
        int summonMaxActive = 4;
        boolean summonRespectTeams = true;
        PlayerTeleportEvent.TeleportCause teleportCause = PlayerTeleportEvent.TeleportCause.ENDER_PEARL;
        boolean respectSpawnProtection = true;
        boolean itemsAdderEnabled = true;
        List<String> dropWorlds = List.of();
        boolean dropRequirePlayerKiller = true;
        boolean dropAllowSpawner = true;
        boolean dropAllowNatural = true;
        double dropLootingBonus = 0.01;
        boolean dropToInventory;
        boolean exoEnabled;
        String exoEventClass = "";
        String exoKillerMethod = "";
        String exoEntityMethod = "";
        String exoAmountMethod = "";
        List<String> exoStackedMarkers = List.of();
    }
}
