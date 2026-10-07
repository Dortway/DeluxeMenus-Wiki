package dev.exoquests.core.config;

import dev.exoquests.core.time.ResetSchedule;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Validated contents of {@code config.yml}. */
public record Settings(
        ResetSchedule reset,
        int progressFlushSeconds,
        Set<String> allowedGameModes,
        WorldFilter worlds,
        KillPolicy kills,
        TreePolicy trees,
        StackedPlantPolicy stackedPlants,
        long maxBalance,
        ShopSettings shop,
        StorageSettings storage,
        Style style,
        Map<String, SoundSpec> sounds) {

    public record WorldFilter(boolean whitelist, Set<String> worlds) {
        public boolean allows(String world) {
            return whitelist == worlds.contains(world);
        }
    }

    /**
     * @param requireDirectDamage   the killing blow must come from the player (melee or own projectile)
     * @param allowProjectiles      projectiles shot by the player count as direct damage
     * @param allowTamedPets        kills by the player's tamed wolves/cats count
     * @param excludedSpawnReasons  mobs spawned for these reasons never count (e.g. SPAWNER)
     */
    public record KillPolicy(boolean requireDirectDamage, boolean allowProjectiles, boolean allowTamedPets,
                             Set<String> excludedSpawnReasons) {
    }

    public enum TreeCredit { PLANTER, BONEMEALER_IF_PRESENT }

    /**
     * @param credit              who receives credit when a recorded sapling grows
     * @param creditOfflinePlanter natural growth while the planter is offline is still credited
     */
    public record TreePolicy(TreeCredit credit, boolean creditOfflinePlanter) {
    }

    /**
     * @param countSupportBreaks breaking the block that supports a column (e.g. sand under cane) counts the column
     * @param maxScanHeight      safety bound for scanning a column upwards
     */
    public record StackedPlantPolicy(boolean countSupportBreaks, int maxScanHeight) {
    }

    public record ShopSettings(int confirmTimeoutSeconds, int clickCooldownMillis, Pattern commandPlayerName) {
    }

    public record StorageSettings(String file, String synchronous, int busyTimeoutMillis,
                                  int assignmentRetentionDays) {
    }

    public record ProgressBar(int length, String filled, String empty, String fallbackFilled,
                              String fallbackEmpty) {
    }

    public record Style(boolean unicode, Map<String, String> colors, ProgressBar bar,
                        Map<String, String> icons, Map<String, String> fallbackIcons) {

        public String icon(String name) {
            Map<String, String> source = unicode ? icons : fallbackIcons;
            String v = source.get(name);
            return v == null ? "" : v;
        }
    }

    public record SoundSpec(boolean enabled, String key, float volume, float pitch) {
    }
}
