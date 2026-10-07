package dev.exo.dailyspinner.config;

import dev.exo.dailyspinner.storage.ConsumeOrder;
import org.bukkit.Material;
import org.bukkit.Particle;

import java.util.Map;

/** Validated values from config.yml. */
public record Settings(
        boolean debug,
        long cooldownMillis,
        ConsumeOrder consumeOrder,
        int maxBonusSpins,
        long clickCooldownMillis,
        long commandCooldownMillis,
        String databaseFile,
        int busyTimeoutMillis,
        int animationSteps,
        int minDelayTicks,
        int maxDelayTicks,
        double easing,
        boolean borderCycle,
        boolean titles,
        Particle particle,
        int particleCount,
        double particleSpread,
        double particleSpeed,
        boolean announcements,
        int resumeDelayTicks,
        double adminDefaultWeight,
        String adminDefaultRarity,
        Material commandDisplayMaterial,
        Map<String, SoundSpec> sounds) {

    public SoundSpec sound(String name) {
        return sounds.get(name);
    }
}
