package dev.exodaily.paper;

import dev.exodaily.core.audit.AuditLog;
import dev.exodaily.core.claim.ClaimService;
import dev.exodaily.core.config.ConfigBundle;
import dev.exodaily.core.config.ConfigIssue;
import dev.exodaily.core.config.ConfigLoader;
import dev.exodaily.core.config.ConfigManager;
import dev.exodaily.core.config.PluginSettings;
import dev.exodaily.core.reward.RewardPool;
import dev.exodaily.core.service.AdminService;
import dev.exodaily.core.service.DailyService;
import dev.exodaily.core.storage.SqliteStore;
import dev.exodaily.core.time.DailyClock;
import dev.exodaily.paper.command.AdminCommand;
import dev.exodaily.paper.command.DailyCommand;
import dev.exodaily.paper.menu.MenuListener;
import dev.exodaily.paper.menu.MenuLookup;
import dev.exodaily.paper.menu.MenuRenderer;
import dev.exodaily.paper.menu.MenuService;
import dev.exodaily.paper.session.SessionManager;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.random.RandomGenerator;

/**
 * ExoDaily entry point. Wires the Bukkit-free core (progression, selection, storage, claims) to
 * Paper (menus, commands, delivery). Targets Paper 1.21.11 on Java 21; single server only.
 */
public class ExoDailyPlugin extends JavaPlugin implements Listener {

    private static final int MAX_REPORTED_ISSUES = 8;

    private final AtomicReference<Presentation> presentation = new AtomicReference<>();
    private Map<String, String> defaults;
    private ConfigManager configManager;
    private DailyClock clock;
    private ThreadPoolExecutor storageExecutor;
    private Executor serverExecutor;
    private SqliteStore store;
    private volatile boolean storageReady;
    private PluginSettings.Storage activeStorage;
    private AuditLog auditLog;
    private AdminService adminService;
    private SessionManager sessions;
    private MenuService menuService;
    private MenuListener menuListener;
    private Messenger messenger;
    private BukkitTask ticker;
    private int tickerInterval;

    @Override
    public void onEnable() {
        for (String file : ConfigLoader.FILES) {
            if (!new java.io.File(getDataFolder(), file).exists()) {
                saveResource(file, false);
            }
        }
        defaults = bundledDefaults();
        PaperPlatformValidator validator = new PaperPlatformValidator();
        configManager = new ConfigManager(new ConfigLoader(validator));
        ConfigLoader.Result initial = configManager.reload(readFiles(), defaults);
        logIssues(initial);

        ConfigBundle bundle = initial.bundle();
        clock = new DailyClock(Clock.systemUTC(), bundle != null ? bundle.settings().timezone() : ZoneId.of("Europe/London"));
        int queueCapacity = bundle != null ? bundle.settings().storage().queueCapacity() : 1024;
        storageExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "ExoDaily-Storage");
                    thread.setDaemon(false);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        serverExecutor = task -> {
            if (!isEnabled()) {
                throw new RejectedExecutionException("ExoDaily is disabled");
            }
            getServer().getScheduler().runTask(this, task);
        };

        auditLog = new AuditLog(getDataFolder().toPath().resolve("audit.log"), bundle == null || bundle.settings().auditFile(), getLogger());
        sessions = new SessionManager();
        ItemFactory items = new ItemFactory(new NamespacedKey(this, "menu_item"));
        messenger = new Messenger(presentation::get);
        MenuRenderer renderer = new MenuRenderer(items, clock);

        activeStorage = bundle != null ? bundle.settings().storage() : new PluginSettings.Storage("sqlite", "data.db", 5000, 1024);
        store = new SqliteStore(getDataFolder().toPath().resolve(activeStorage.file()), activeStorage.busyTimeoutMs(), getLogger());
        DailyService dailyService = new DailyService(store, clock, () -> configManager.get().rewards(),
                () -> configManager.get().settings().cycleLength(), RandomGenerator::getDefault, getLogger());
        ClaimService claimService = new ClaimService(store, storageExecutor, serverExecutor, clock,
                () -> configManager.get().settings().cycleLength(), getLogger());
        adminService = new AdminService(store, clock, () -> configManager.get().settings().cycleLength(), auditLog);
        menuService = new MenuService(presentation::get, sessions, dailyService, claimService, storageExecutor, serverExecutor,
                renderer, messenger, items, clock, this::storageReady, menuLookup(), getLogger());

        if (bundle != null) {
            presentation.set(Presentation.of(bundle));
            openStorageBlocking();
        } else {
            getLogger().severe("ExoDaily has no valid configuration and is FAILING CLOSED: /daily is unavailable. "
                    + "Fix the errors above and run /exodaily reload.");
        }

        menuListener = new MenuListener(this, menuService, sessions, items, menuLookup());
        getServer().getPluginManager().registerEvents(menuListener, this);
        getServer().getPluginManager().registerEvents(this, this);
        register("daily", new DailyCommand(menuService, messenger));
        register("exodaily", new AdminCommand(this, messenger));
        startTicker(bundle != null ? bundle.settings().countdownUpdateTicks() : 20);
        // The first tick runs after every plugin has enabled: now reward commands can be checked.
        getServer().getScheduler().runTask(this, () -> {
            validator.enableCommandChecks();
            Presentation p = presentation.get();
            if (p == null) {
                return;
            }
            for (dev.exodaily.core.reward.RewardDefinition reward : p.config().rewards().rewards().values()) {
                for (String command : reward.commands()) {
                    validator.commandWarning(command.split(" ", 2)[0]).ifPresent(warning ->
                            getLogger().warning("Config warning [rewards.yml] rewards." + reward.id() + ".commands: " + warning));
                }
            }
        });
    }

    private void register(String name, org.bukkit.command.TabExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            throw new IllegalStateException("command '" + name + "' is missing from plugin.yml");
        }
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    @Override
    public void onDisable() {
        if (ticker != null) {
            ticker.cancel();
        }
        if (menuService != null) {
            menuService.closeAll();
        }
        storageReady = false;
        if (storageExecutor != null) {
            storageExecutor.shutdown();
            try {
                if (!storageExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    List<Runnable> dropped = storageExecutor.shutdownNow();
                    getLogger().warning("Storage tasks did not finish in time; " + dropped.size() + " queued task(s) dropped."
                            + " Interrupted claims are recovered on the next start.");
                }
            } catch (InterruptedException e) {
                storageExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (store != null) {
            store.close();
        }
        if (sessions != null) {
            sessions.clear();
        }
    }

    // ================================================================== storage

    private void openStorageBlocking() {
        try {
            AdminService.RecoveryReport report = CompletableFuture.supplyAsync(() -> {
                store.open();
                return adminService.recover();
            }, storageExecutor).get(30, TimeUnit.SECONDS);
            storageReady = true;
            getLogger().info("Storage ready (SQLite, schema v" + SqliteStore.SCHEMA_VERSION + ").");
            logRecovery(report);
        } catch (Exception e) {
            storageReady = false;
            getLogger().log(Level.SEVERE, "Storage is UNAVAILABLE. ExoDaily is failing closed: no menus can be opened and"
                    + " no rewards can be claimed until this is fixed (then run /exodaily reload or restart).", e);
        }
    }

    private void openStorageAsync() {
        storage(() -> {
            store.open();
            return adminService.recover();
        }, report -> {
            storageReady = true;
            getLogger().info("Storage ready (SQLite, schema v" + SqliteStore.SCHEMA_VERSION + ").");
            logRecovery(report);
        }, getServer().getConsoleSender());
    }

    private void logRecovery(AdminService.RecoveryReport report) {
        if (report.released() > 0) {
            getLogger().info("Recovery: released " + report.released() + " interrupted reservation(s); nothing had been delivered for them.");
        }
        if (report.flaggedUncertain() > 0) {
            getLogger().warning("Recovery: " + report.flaggedUncertain() + " claim(s) were interrupted during delivery and are"
                    + " now UNCERTAIN. They are NOT reissued automatically. Review them with /exodaily pending.");
        }
        if (report.totalUncertain() > 0) {
            getLogger().warning(report.totalUncertain() + " claim(s) await administrative reconciliation (/exodaily pending).");
        }
    }

    public boolean storageReady() {
        return storageReady && store.isAvailable();
    }

    /**
     * Runs {@code work} on the storage thread and {@code then} on the server thread. Failures are
     * reported to {@code sender} and logged; {@code then} is not called.
     */
    public <T> void storage(Supplier<T> work, Consumer<T> then, CommandSender sender) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            storageExecutor.execute(() -> {
                try {
                    future.complete(work.get());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(e);
        }
        future.whenComplete((value, error) -> {
            try {
                serverExecutor.execute(() -> {
                    if (error != null) {
                        if (error instanceof RejectedExecutionException) {
                            presentationSafeSend(sender, "busy");
                        } else {
                            getLogger().log(Level.SEVERE, "Storage operation failed", error);
                            presentationSafeSend(sender, "storage-error");
                        }
                        return;
                    }
                    then.accept(value);
                });
            } catch (RejectedExecutionException ignored) {
                // Shutting down.
            }
        });
    }

    private void presentationSafeSend(CommandSender sender, String key) {
        if (presentation.get() == null) {
            sender.sendPlainMessage("ExoDaily: operation failed (" + key + "); check the console.");
        } else {
            messenger.send(sender, key);
        }
    }

    // ================================================================== configuration

    private Map<String, String> bundledDefaults() {
        Map<String, String> result = new HashMap<>();
        for (String file : ConfigLoader.FILES) {
            try (InputStream in = getResource(file)) {
                if (in != null) {
                    result.put(file, new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                getLogger().log(Level.WARNING, "Could not read bundled " + file, e);
            }
        }
        return result;
    }

    private Map<String, String> readFiles() {
        Map<String, String> files = new HashMap<>();
        for (String file : ConfigLoader.FILES) {
            Path path = getDataFolder().toPath().resolve(file);
            try {
                files.put(file, Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : null);
            } catch (IOException e) {
                getLogger().log(Level.WARNING, "Could not read " + file, e);
                files.put(file, null);
            }
        }
        return files;
    }

    private void logIssues(ConfigLoader.Result result) {
        for (ConfigIssue issue : result.issues()) {
            if (issue.isError()) {
                getLogger().severe("Config error " + issue);
            } else {
                getLogger().warning("Config warning " + issue);
            }
        }
        if (!result.success()) {
            getLogger().severe("Configuration rejected with " + result.errors().size() + " error(s).");
        }
    }

    /** Reads the files off-thread, validates on the server thread, and applies only a fully valid result. */
    private void reloadFromDisk(Consumer<ConfigLoader.Result> then, CommandSender sender) {
        storage(this::readFiles, files -> {
            ConfigLoader.Result result = configManager.reload(files, defaults);
            logIssues(result);
            if (result.success()) {
                apply(result.bundle());
            }
            then.accept(result);
        }, sender);
    }

    private void apply(ConfigBundle bundle) {
        presentation.set(Presentation.of(bundle));
        clock.zone(bundle.settings().timezone());
        auditLog.enabled(bundle.settings().auditFile());
        if (bundle.settings().countdownUpdateTicks() != tickerInterval) {
            startTicker(bundle.settings().countdownUpdateTicks());
        }
        if (!bundle.settings().storage().equals(activeStorage)) {
            getLogger().warning("Storage settings changed in config.yml; they take effect after a server restart.");
        }
        if (!storageReady && !store.isAvailable()) {
            // Retry storage that was never opened (invalid config at startup) or failed to open.
            openStorageAsync();
        }
    }

    public void reload(CommandSender sender) {
        reloadFromDisk(result -> {
            if (result.success()) {
                messenger.send(sender, "admin-reload-success",
                        Placeholder.unparsed("warnings", Integer.toString(result.warnings().size())));
                reportIssues(sender, result.warnings());
                menuService.invalidateAll("menu-config-reloaded");
                if (storageReady()) {
                    String actor = sender instanceof Player p ? p.getName() + " (" + p.getUniqueId() + ")" : sender.getName();
                    storage(() -> {
                        adminService.audit(actor, "reload", "config", "warnings=" + result.warnings().size());
                        return null;
                    }, ignored -> { }, getServer().getConsoleSender());
                }
            } else {
                presentationSafeSendFailure(sender, result);
            }
        }, sender);
    }

    private void presentationSafeSendFailure(CommandSender sender, ConfigLoader.Result result) {
        if (presentation.get() == null) {
            sender.sendPlainMessage("ExoDaily: reload failed with " + result.errors().size() + " error(s); see the console.");
            result.errors().stream().limit(MAX_REPORTED_ISSUES).forEach(issue -> sender.sendPlainMessage(" - " + issue));
            return;
        }
        messenger.send(sender, "admin-reload-failed", Placeholder.unparsed("errors", Integer.toString(result.errors().size())));
        reportIssues(sender, result.errors());
    }

    private void reportIssues(CommandSender sender, List<ConfigIssue> issues) {
        issues.stream().limit(MAX_REPORTED_ISSUES).forEach(issue ->
                messenger.send(sender, "admin-config-issue", Placeholder.unparsed("issue", issue.toString())));
        if (issues.size() > MAX_REPORTED_ISSUES) {
            messenger.send(sender, "admin-config-more", Placeholder.unparsed("count", Integer.toString(issues.size() - MAX_REPORTED_ISSUES)));
        }
    }

    // ================================================================== reward save from hand

    /**
     * Creates or replaces {@code rewards.<id>} with the held item serialized by Paper, then reloads.
     * If the result does not validate, the previous rewards.yml is restored.
     */
    public void saveRewardFromItem(Player player, String id, ItemStack item) {
        String serialized = Base64.getEncoder().encodeToString(item.serializeAsBytes());
        String summary = item.getAmount() + " " + summaryName(item);
        String material = item.getType().name();
        int amount = item.getAmount();
        Path file = getDataFolder().toPath().resolve(ConfigLoader.REWARDS);
        String actor = player.getName() + " (" + player.getUniqueId() + ")";

        storage(() -> {
            try {
                String previous = Files.readString(file, StandardCharsets.UTF_8);
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.loadFromString(previous);
                String path = "rewards." + id;
                boolean existed = yaml.isConfigurationSection(path);
                int weight = yaml.getInt(path + ".weight", 10);
                yaml.set(path, null);
                yaml.set(path + ".material", material);
                yaml.set(path + ".amount", amount);
                yaml.set(path + ".summary", summary);
                yaml.set(path + ".weight", weight);
                yaml.set(path + ".serialized-item", serialized);
                writeAtomically(file, yaml.saveToString());
                return new String[]{previous, Boolean.toString(existed)};
            } catch (IOException | InvalidConfigurationException e) {
                throw new IllegalStateException("could not update rewards.yml: " + e.getMessage(), e);
            }
        }, saved -> reloadFromDisk(result -> {
            if (!result.success()) {
                storage(() -> {
                    try {
                        writeAtomically(file, saved[0]);
                        return null;
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                }, ignored -> { }, player);
                messenger.send(player, "admin-reward-save-failed", Placeholder.unparsed("id", id));
                reportIssues(player, result.errors());
                return;
            }
            List<String> pools = new ArrayList<>();
            for (RewardPool pool : configManager.get().rewards().pools().values()) {
                if (pool.entries().stream().anyMatch(entry -> entry.rewardId().equals(id))) {
                    pools.add(pool.id());
                }
            }
            messenger.send(player, Boolean.parseBoolean(saved[1]) ? "admin-reward-updated" : "admin-reward-saved",
                    Placeholder.unparsed("id", id),
                    Placeholder.unparsed("pools", pools.isEmpty() ? "-" : String.join(", ", pools)));
            if (pools.isEmpty()) {
                messenger.send(player, "admin-reward-not-in-pool", Placeholder.unparsed("id", id));
            }
            menuService.invalidateAll(null);
            storage(() -> {
                adminService.audit(actor, Boolean.parseBoolean(saved[1]) ? "reward-update" : "reward-create", id,
                        "material=" + material + " amount=" + amount + " (serialized item)");
                return null;
            }, ignored -> { }, getServer().getConsoleSender());
        }, player), player);
    }

    /** Custom name if present, otherwise the material in lowercase words (not a client translation key). */
    private static String summaryName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return PlainTextComponentSerializer.plainText().serialize(meta.displayName()).toLowerCase(Locale.ROOT);
        }
        if (meta != null && meta.hasItemName()) {
            return PlainTextComponentSerializer.plainText().serialize(meta.itemName()).toLowerCase(Locale.ROOT);
        }
        return item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static void writeAtomically(Path file, String content) throws IOException {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ================================================================== misc

    private void startTicker(int interval) {
        if (ticker != null) {
            ticker.cancel();
        }
        tickerInterval = interval;
        ticker = getServer().getScheduler().runTaskTimer(this, menuService::tick, interval, interval);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        menuListener.scrub(player);
        Presentation p = presentation.get();
        if (p == null || !p.config().settings().notifyAdminsOnJoin() || !player.hasPermission(Permissions.ADMIN) || !storageReady()) {
            return;
        }
        storage(adminService::uncertainCount, count -> {
            if (count > 0 && player.isOnline()) {
                messenger.send(player, "admin-uncertain-join",
                        Placeholder.unparsed("count", Integer.toString(count)));
            }
        }, getServer().getConsoleSender());
    }

    /** How ExoDaily inventories are recognised: by their server-side {@link dev.exodaily.paper.menu.ExoMenu} holder. */
    protected MenuLookup menuLookup() {
        return MenuLookup.PAPER;
    }

    public Presentation presentation() {
        return presentation.get();
    }

    public AdminService admin() {
        return adminService;
    }

    public MenuService menus() {
        return menuService;
    }
}
