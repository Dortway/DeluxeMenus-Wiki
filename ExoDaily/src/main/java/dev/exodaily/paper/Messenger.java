package dev.exodaily.paper;

import dev.exodaily.core.config.MenuConfig;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.function.Supplier;

/** Sends configured messages, sounds and the claim effect. Server thread only. */
public final class Messenger {

    private final Supplier<Presentation> presentation;

    public Messenger(Supplier<Presentation> presentation) {
        this.presentation = presentation;
    }

    public Component render(String key, TagResolver... resolvers) {
        Presentation p = presentation.get();
        TagResolver prefix = Placeholder.component("prefix", p.styler().render(p.config().messages().get("prefix")));
        return p.styler().render(p.config().messages().get(key), TagResolver.resolver(TagResolver.resolver(resolvers), prefix));
    }

    public void send(CommandSender sender, String key, TagResolver... resolvers) {
        if (presentation.get() == null) {
            sender.sendPlainMessage("ExoDaily is not configured correctly; check the console.");
            return;
        }
        sender.sendMessage(render(key, resolvers));
    }

    public void sendList(CommandSender sender, String key, TagResolver... resolvers) {
        Presentation p = presentation.get();
        if (p == null) {
            sender.sendPlainMessage("ExoDaily is not configured correctly; check the console.");
            return;
        }
        TagResolver prefix = Placeholder.component("prefix", p.styler().render(p.config().messages().get("prefix")));
        for (String line : p.config().messages().list(key)) {
            sender.sendMessage(p.styler().render(line, TagResolver.resolver(TagResolver.resolver(resolvers), prefix)));
        }
    }

    public void sound(Player player, String name) {
        Presentation p = presentation.get();
        if (p == null) {
            return;
        }
        MenuConfig.SoundSpec spec = p.config().menus().sounds().get(name);
        if (spec == null || spec.key() == null) {
            return;
        }
        player.playSound(Sound.sound(Key.key(spec.key()), Sound.Source.MASTER, spec.volume(), spec.pitch()), Sound.Emitter.self());
    }

    public void claimEffect(Player player) {
        Presentation p = presentation.get();
        if (p == null) {
            return;
        }
        MenuConfig.ClaimEffect effect = p.config().menus().claimEffect();
        if (!effect.enabled()) {
            return;
        }
        Particle particle;
        try {
            particle = Particle.valueOf(effect.particle().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return;
        }
        Location location = player.getLocation().add(0, 1.0, 0);
        // Only the claiming player sees it, keeping the effect subtle.
        player.spawnParticle(particle, location, effect.count(), effect.spread(), effect.spread(), effect.spread(), 0.0);
    }
}
