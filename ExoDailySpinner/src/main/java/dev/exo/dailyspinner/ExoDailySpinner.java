package dev.exo.dailyspinner;

import dev.exo.dailyspinner.command.SpinnerCommand;
import dev.exo.dailyspinner.config.ConfigBundle;
import dev.exo.dailyspinner.config.ConfigLoader;
import dev.exo.dailyspinner.config.SoundSpec;
import dev.exo.dailyspinner.listener.MenuListener;
import dev.exo.dailyspinner.listener.PlayerListener;
import dev.exo.dailyspinner.menu.GuiItems;
import dev.exo.dailyspinner.menu.MenuManager;
import dev.exo.dailyspinner.menu.Menus;
import dev.exo.dailyspinner.reward.RewardEditor;
import dev.exo.dailyspinner.reward.RewardRegistry;
import dev.exo.dailyspinner.spin.OperationGuard;
import dev.exo.dailyspinner.spin.ReconciliationLog;
import dev.exo.dailyspinner.spin.SpinService;
import dev.exo.dailyspinner.spin.Throttle;
import dev.exo.dailyspinner.storage.Database;
import dev.exo.dailyspinner.storage.ReconcileEntry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * ExoDailySpinner: a crate-style daily reward spinner for Paper.
 */
public class ExoDailySpinner extends JavaPlugin {

    private static final List<String> FILES = List.of(ConfigLoader.CONFIG, ConfigLoader.MESSAGES, ConfigLoader.MENUS,
            ConfigLoader.REWARDS);
    private static final List<String> DEFAULTED = List.of(ConfigLoader.CONFIG, ConfigLoader.MESSAGES, ConfigLoader.MENUS);

    private volatile ConfigBundle bundle;
    private final Map<String, String> defaults = new HashMap<>();
    private final AtomicBoolean reloading = new AtomicBoolean();
    private ExecutorService io;
    private Database database;
    private String databaseFile;
    private OperationGuard guard;
    private Throttle clickThrottle;
    private Throttle commandThrottle;
    private MenuManager menuManager;
    private Menus menus;
    private SpinService spins;
    private RewardEditor rewardEditor;
    private ReconciliationLog reconciliation;
    private PlayerListener playerListener;

    @Override
    public void onEnable() {
        GuiItems.init(this);
        io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ExoDailySpinner-IO");
            t.setDaemon(true);
            return t;
        });
        try {
            Files.createDirectories(getDataFolder().toPath());
            for (String file : FILES) {
                if (!Files.exists(dataPath(file))) {
                    saveResource(file, false);
                }
            }
            for (String file : DEFAULTED) {
                try (InputStream in = getResource(file)) {
                    if (in != null) {
                        defaults.put(file, new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
            }
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Could not prepare the plugin folder; disabling.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        ConfigLoader.Result result;
        try {
            result = ConfigLoader.load(readFiles(), defaults);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Could not read configuration files.", e);
            result = null;
        }
        if (result != null && result.ok()) {
            bundle = result.bundle();
            result.errors().warnings().forEach(w -> getLogger().warning(w));
            getLogger().info("Loaded " + bundle.rewards().rewards().size() + " reward(s).");
        } else {
            getLogger().severe("Configuration is invalid. Spins are disabled until it is fixed and /ds reload succeeds:");
            if (result != null) {
                result.errors().errors().forEach(e -> getLogger().severe("  " + e));
            }
        }

        databaseFile = bundle != null ? bundle.settings().databaseFile() : "data.db";
        int busy = bundle != null ? bundle.settings().busyTimeoutMillis() : 5000;
        try {
            database = Database.open(dataPath(databaseFile), busy, getLogger());
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Could not open the SQLite database; disabling ExoDailySpinner.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        guard = new OperationGuard();
        clickThrottle = new Throttle(bundle != null ? bundle.settings().clickCooldownMillis() : 150);
        commandThrottle = new Throttle(bundle != null ? bundle.settings().commandCooldownMillis() : 600);
        reconciliation = new ReconciliationLog(getLogger(), dataPath("reconciliation.log"), io);
        menuManager = new MenuManager(this);
        menus = new Menus(this, menuManager);
        spins = new SpinService(this, guard);
        rewardEditor = new RewardEditor(this);

        playerListener = new PlayerListener(this);
        getServer().getPluginManager().registerEvents(new MenuListener(this), this);
        getServer().getPluginManager().registerEvents(playerListener, this);
        PluginCommand command = getCommand("dailyspinner");
        if (command != null) {
            SpinnerCommand executor = new SpinnerCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        // Crash recovery: anything left mid-delivery is flagged for administrators, never replayed.
        database.submit(repo -> repo.flagInterruptedDeliveries(System.currentTimeMillis()))
                .whenComplete((entries, error) -> {
                    if (error != null) {
                        return;
                    }
                    for (ReconcileEntry entry : entries) {
                        reconciliation.record("UNCERTAIN_AFTER_RESTART", entry);
                    }
                    if (!entries.isEmpty()) {
                        getLogger().warning(entries.size() + " delivery(ies) were interrupted by a stop/crash and need review: "
                                + "use /ds reconcile list");
                    }
                });

        // Support enabling while players are online (e.g. after a server reload).
        for (Player player : Bukkit.getOnlinePlayers()) {
            playerListener.scheduleJoinTasks(player);
        }
        getLogger().info("ExoDailySpinner enabled.");
    }

    @Override
    public void onDisable() {
        if (spins != null) {
            spins.shutdown();
        }
        if (menuManager != null) {
            menuManager.closeAll();
        }
        if (database != null) {
            database.close();
        }
        if (io != null) {
            io.shutdown();
            try {
                if (!io.awaitTermination(5, TimeUnit.SECONDS)) {
                    io.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ------------------------------------------------------------------ reload & files

    /** Reads files off-thread, validates on the main thread, and swaps only if everything is valid. */
    public void reload(CommandSender sender) {
        if (!reloading.compareAndSet(false, true)) {
            message(sender, "reload-in-progress", "A reload is already running.");
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            try {
                return readFiles();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, io).whenComplete((files, error) -> sync(() -> {
            try {
                if (error != null) {
                    getLogger().log(Level.SEVERE, "Reload failed: could not read files", error);
                    message(sender, "reload-failed", "Reload failed: could not read configuration files.");
                    return;
                }
                ConfigLoader.Result result = ConfigLoader.load(files, defaults);
                if (!result.ok()) {
                    getLogger().severe("Reload rejected; keeping the previous working configuration:");
                    result.errors().errors().forEach(e -> getLogger().severe("  " + e));
                    if (bundle != null) {
                        bundle.messages().send(sender, "reload-failed");
                    } else {
                        sender.sendMessage(Component.text("Reload rejected. See console for details.", NamedTextColor.RED));
                    }
                    result.errors().errors().stream().limit(8)
                            .forEach(e -> sender.sendMessage(Component.text(" - " + e, NamedTextColor.RED)));
                    return;
                }
                ConfigBundle previous = bundle;
                bundle = result.bundle();
                clickThrottle.setInterval(bundle.settings().clickCooldownMillis());
                commandThrottle.setInterval(bundle.settings().commandCooldownMillis());
                result.errors().warnings().forEach(w -> getLogger().warning(w));
                menuManager.closeForReload();
                bundle.messages().send(sender, "reload-success",
                        Placeholder.unparsed("rewards", String.valueOf(bundle.rewards().rewards().size())),
                        Placeholder.unparsed("warnings", String.valueOf(result.errors().warnings().size())));
                if (!bundle.settings().databaseFile().equals(databaseFile)
                        || (previous != null && previous.settings().busyTimeoutMillis() != bundle.settings().busyTimeoutMillis())) {
                    bundle.messages().send(sender, "reload-restart-needed");
                }
                if (previous == null) {
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        playerListener.scheduleJoinTasks(player);
                    }
                }
            } finally {
                reloading.set(false);
            }
        }));
    }

    private Map<String, String> readFiles() throws IOException {
        Map<String, String> files = new HashMap<>();
        for (String file : FILES) {
            Path path = dataPath(file);
            if (Files.exists(path)) {
                files.put(file, Files.readString(path, StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    /** Atomically replaces a data file on the IO thread (writes are ordered). */
    public void writeFileAsync(String name, String content) {
        Path target = dataPath(name);
        io.execute(() -> {
            try {
                Path temp = target.resolveSibling(name + ".tmp");
                Files.writeString(temp, content, StandardCharsets.UTF_8);
                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicUnsupported) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "Could not save " + name + "; the change is active until restart only.", e);
            }
        });
    }

    /** Swaps in a new reward registry after an in-game edit (main thread). */
    public void applyRewards(RewardRegistry registry, String rewardsYaml) {
        ConfigBundle current = bundle;
        bundle = new ConfigBundle(current.settings(), current.text(), current.messages(), current.menus(), registry, rewardsYaml);
    }

    private Path dataPath(String name) {
        return getDataFolder().toPath().resolve(name);
    }

    // ------------------------------------------------------------------ helpers

    /** Runs on the server thread; silently dropped if the plugin is disabled. */
    public void sync(Runnable task) {
        if (!isEnabled()) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }
        try {
            Bukkit.getScheduler().runTask(this, task);
        } catch (IllegalPluginAccessException ignored) {
            // Plugin is disabling.
        }
    }

    public void sound(Player player, String name) {
        ConfigBundle b = bundle;
        if (b == null) {
            return;
        }
        SoundSpec spec = b.settings().sound(name);
        if (spec != null) {
            spec.play(player);
        }
    }

    public void debug(String message) {
        ConfigBundle b = bundle;
        if (b != null && b.settings().debug()) {
            getLogger().info("[debug] " + message);
        }
    }

    private void message(CommandSender sender, String key, String fallback) {
        if (bundle != null) {
            bundle.messages().send(sender, key);
        } else {
            sender.sendMessage(Component.text(fallback, NamedTextColor.RED));
        }
    }

    public boolean isReloading() {
        return reloading.get();
    }

    public ConfigBundle bundle() {
        return bundle;
    }

    public Database database() {
        return database;
    }

    public Menus menus() {
        return menus;
    }

    public SpinService spins() {
        return spins;
    }

    public RewardEditor rewardEditor() {
        return rewardEditor;
    }

    public ReconciliationLog reconciliation() {
        return reconciliation;
    }

    public Throttle clickThrottle() {
        return clickThrottle;
    }

    public Throttle commandThrottle() {
        return commandThrottle;
    }
}
