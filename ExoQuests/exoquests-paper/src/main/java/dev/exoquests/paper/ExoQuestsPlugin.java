package dev.exoquests.paper;

import dev.exoquests.core.config.ConfigBundle;
import dev.exoquests.core.config.ConfigException;
import dev.exoquests.core.config.Settings;
import dev.exoquests.core.points.PointsService;
import dev.exoquests.core.quest.QuestProgressService;
import dev.exoquests.core.shop.PurchaseService;
import dev.exoquests.core.storage.Database;
import dev.exoquests.core.storage.PlacementStore;
import dev.exoquests.core.storage.QuestStore;
import dev.exoquests.core.time.Clock;
import dev.exoquests.paper.command.AdminCommand;
import dev.exoquests.paper.command.QuestShopCommand;
import dev.exoquests.paper.command.QuestsCommand;
import dev.exoquests.paper.menu.MenuListener;
import dev.exoquests.paper.menu.MenuService;
import dev.exoquests.paper.shop.PaperDeliveryPort;
import dev.exoquests.paper.shop.ShopEditor;
import dev.exoquests.paper.text.TextService;
import dev.exoquests.paper.tracking.BlockTrackingListener;
import dev.exoquests.paper.tracking.EntityTrackingListener;
import dev.exoquests.paper.tracking.ItemTrackingListener;
import dev.exoquests.paper.tracking.TrackingContext;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** ExoQuests entry point: wires configuration, storage, services, listeners and commands. */
public final class ExoQuestsPlugin extends JavaPlugin {

    private ExecutorService configIo;
    private MainThreadExecutor main;
    private ConfigManager configs;
    private Database database;
    private TextService text;
    private PointsService points;
    private PlacementStore placements;
    private QuestProgressService progress;
    private PurchaseService purchases;
    private MenuService menus;
    private TrackingContext tracking;
    private ShopEditor shopEditor;
    private volatile boolean shuttingDown;
    private int ticks;

    @Override
    public void onEnable() {
        configIo = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ExoQuests-Config");
            t.setDaemon(true);
            return t;
        });
        main = new MainThreadExecutor(this);
        configs = new ConfigManager(this, configIo);
        try {
            configs.loadInitial();
        } catch (ConfigException e) {
            ConfigManager.logProblems(getLogger(), e);
            getLogger().severe("ExoQuests is disabled until the configuration is fixed.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        ConfigBundle cfg = configs.current();
        Settings.StorageSettings storage = cfg.settings().storage();
        Path dbFile = getDataFolder().toPath().resolve(storage.file());
        try {
            database = Database.open(dbFile, storage.synchronous(), storage.busyTimeoutMillis(), getLogger());
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Could not open the database " + dbFile + "; disabling ExoQuests.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        LongSupplier maxBalance = () -> configs.current().settings().maxBalance();
        text = new TextService();
        text.update(cfg);
        points = new PointsService(database, maxBalance, Clock.SYSTEM);
        placements = new PlacementStore(database, System::currentTimeMillis);
        progress = new QuestProgressService(database, main, Clock.SYSTEM, cfg.settings().reset(),
                () -> configs.current().quests(), maxBalance, Random::new, new ProgressNotifier(this));
        purchases = new PurchaseService(database, new PaperDeliveryPort(this), () -> configs.current().shop(),
                maxBalance, Clock.SYSTEM, getLogger());
        menus = new MenuService(this);
        tracking = new TrackingContext(this);
        shopEditor = new ShopEditor(this);

        runStartupMaintenance(cfg);

        var pm = getServer().getPluginManager();
        pm.registerEvents(new MenuListener(this), this);
        pm.registerEvents(new PlayerListener(this), this);
        pm.registerEvents(new BlockTrackingListener(this), this);
        pm.registerEvents(new EntityTrackingListener(this), this);
        pm.registerEvents(new ItemTrackingListener(this), this);

        register("quests", new QuestsCommand(this));
        register("questshop", new QuestShopCommand(this));
        register("exoquests", new AdminCommand(this));

        for (Player online : Bukkit.getOnlinePlayers()) {
            progress.join(online.getUniqueId());
        }
        getServer().getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
        getLogger().info("ExoQuests enabled: " + cfg.quests().enabled().size() + " active quests, "
                + cfg.shop().visible().size() + " shop rewards, reset " + cfg.settings().reset().resetTime()
                + " " + cfg.settings().reset().zone() + ".");
    }

    private <T extends CommandExecutor & TabCompleter> void register(String name, T handler) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            throw new IllegalStateException("command " + name + " missing from plugin.yml");
        }
        command.setExecutor(handler);
        command.setTabCompleter(handler);
    }

    private void runStartupMaintenance(ConfigBundle cfg) {
        try {
            int flagged = purchases.flagInterrupted().get(30, TimeUnit.SECONDS);
            if (flagged > 0) {
                getLogger().warning(flagged + " purchase(s) were interrupted during delivery by a previous shutdown "
                        + "and need staff review: /exoquests recovery list");
            }
            String cutoff = cfg.settings().reset().periodAt(Instant.now())
                    .minusDays(cfg.settings().storage().assignmentRetentionDays()).toString();
            int pruned = database.submit(c -> QuestStore.pruneBefore(c, cutoff)).get(30, TimeUnit.SECONDS);
            if (pruned > 0) {
                getLogger().info("Pruned " + pruned + " expired daily assignment rows.");
            }
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Startup maintenance failed", e);
        }
    }

    private void tick() {
        progress.currentPeriod();
        ticks++;
        if (ticks % configs.current().settings().progressFlushSeconds() == 0) {
            progress.flushAll();
        }
        menus.tick();
    }

    /** Called on the main thread after a new configuration was activated. */
    void onConfigApplied(ConfigBundle bundle) {
        text.update(bundle);
        progress.updateSchedule(bundle.settings().reset());
        tracking.refresh(bundle);
        menus.onConfigChanged();
    }

    @Override
    public void onDisable() {
        shuttingDown = true;
        if (main != null) {
            main.enterShutdownMode();
        }
        if (menus != null) {
            menus.closeAll();
        }
        if (progress != null) {
            try {
                progress.flushAll().get(15, TimeUnit.SECONDS);
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Final quest progress flush failed", e);
            }
        }
        if (database != null) {
            database.close();
        }
        if (configIo != null) {
            configIo.shutdown();
        }
    }

    public boolean isShuttingDown() {
        return shuttingDown;
    }

    public MainThreadExecutor mainExecutor() {
        return main;
    }

    public ConfigManager configs() {
        return configs;
    }

    public Database database() {
        return database;
    }

    public TextService text() {
        return text;
    }

    public PointsService points() {
        return points;
    }

    public PlacementStore placements() {
        return placements;
    }

    public QuestProgressService progress() {
        return progress;
    }

    public PurchaseService purchases() {
        return purchases;
    }

    public MenuService menus() {
        return menus;
    }

    public TrackingContext tracking() {
        return tracking;
    }

    public ShopEditor shopEditor() {
        return shopEditor;
    }
}
