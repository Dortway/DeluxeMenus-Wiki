package dev.exo.dailyspinner.config;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.entity.Player;

/** A configured sound. Uses Adventure keys so any vanilla or resource-pack sound id works. */
public record SoundSpec(Sound sound) {

    public void play(Player player) {
        player.playSound(sound, Sound.Emitter.self());
    }

    public void play(Player player, float pitchOverride) {
        player.playSound(Sound.sound(sound.name(), sound.source(), sound.volume(), pitchOverride), Sound.Emitter.self());
    }

    public float pitch() {
        return sound.pitch();
    }

    public static SoundSpec of(String key, float volume, float pitch) {
        return new SoundSpec(Sound.sound(Key.key(key), Sound.Source.MASTER, volume, pitch));
    }
}
