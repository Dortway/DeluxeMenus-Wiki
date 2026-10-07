package dev.exo.dailyspinner.menu;

import dev.exo.dailyspinner.ExoDailySpinner;
import dev.exo.dailyspinner.config.ConfigBundle;
import dev.exo.dailyspinner.storage.PlayerData;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.function.Consumer;

/** Entry points for opening each menu. Player state is loaded off-thread before opening. */
public final class Menus {

    private final ExoDailySpinner plugin;
    private final MenuManager manager;

    public Menus(ExoDailySpinner plugin, MenuManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public MenuManager manager() {
        return manager;
    }

    public void openMain(Player player) {
        withData(player, data -> {
            ConfigBundle bundle = plugin.bundle();
            manager.open(player, new MainMenu(plugin, bundle, data));
            plugin.sound(player, "menu-open");
        });
    }

    public void openSpinner(Player player) {
        withData(player, data -> manager.open(player, new SpinnerMenu(plugin, plugin.bundle(), data)));
    }

    public void openPreview(Player player, int page, boolean fromMain) {
        ConfigBundle bundle = plugin.bundle();
        if (bundle == null) {
            return;
        }
        manager.open(player, new PreviewMenu(plugin, bundle, player.getUniqueId(), page, fromMain));
    }

    public void openAdmin(Player player, int page) {
        ConfigBundle bundle = plugin.bundle();
        if (bundle == null) {
            return;
        }
        manager.open(player, new AdminMenu(plugin, bundle, player.getUniqueId(), page));
    }

    /** Loads player data asynchronously, then runs {@code then} on the main thread if still valid. */
    public void withData(Player player, Consumer<PlayerData> then) {
        UUID id = player.getUniqueId();
        plugin.database().submit(repo -> repo.loadPlayer(id)).whenComplete((data, error) -> plugin.sync(() -> {
            if (!player.isOnline() || plugin.bundle() == null) {
                return;
            }
            if (error != null) {
                plugin.bundle().messages().send(player, "error-generic");
                return;
            }
            then.accept(data);
        }));
    }
}
