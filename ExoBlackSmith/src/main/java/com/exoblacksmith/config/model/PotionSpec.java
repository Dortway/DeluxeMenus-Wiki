package com.exoblacksmith.config.model;

import org.bukkit.potion.PotionEffectType;

/** A passive potion effect. {@code amplifier} 0 = level I. */
public record PotionSpec(PotionEffectType type, int amplifier) {
}
