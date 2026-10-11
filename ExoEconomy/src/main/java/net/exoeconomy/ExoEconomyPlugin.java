package net.exoeconomy;

import net.exoeconomy.api.ExoEconomyAPI;
import net.exoeconomy.booster.BoosterService;
import net.exoeconomy.command.BoostersCommand;
import net.exoeconomy.command.ExoEconomyCommand;
import net.exoeconomy.command.SellCommand;
import net.exoeconomy.command.ShardsCommand;
import net.exoeconomy.config.ConfigException;
import net.exoeconomy.config.Settings;
import net.exoeconomy.core.tx.TransactionCoordinator;
import net.exoeconomy.core.tx.TxState;
import net.exoeconomy.economy.EconomyBridge;
import net.exoeconomy.integration.ExoSkillsBridge;
import net.exoeconomy.integration.ExoSpawnersBridge;
import net.exoeconomy.integration.PapiExpansion;
import net.exoeconomy.menu.Items;
import net.exoeconomy.menu.MenuListener;
import net.exoeconomy.reward.ActivityListener;
import net.exoeconomy.reward.ProvenanceListener;
import net.exoeconomy.reward.RewardService;
import net.exoeconomy.sell.PriceAudit;
import net.exoeconomy.sell.SellService;
import net.exoeconomy.storage.Database;
import net.exoeconomy.booster.BoosterShopMenu;
import net.exoeconomy.booster.ActiveBoostersMenu;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.*;
import java.util.logging.Level;

/**
 * ExoEconomy: activity rewards, /sell and boosters on top of ExcellentEconomy's Shards currency.
 * ExcellentEconomy owns the currency and every balance; ExoEconomy only asks it to move Shards.
 */
public final class ExoEconomyPlugin extends JavaPlugin {
    private static final List<String> FILES = List.of("config.yml", "rewards.yml", "boosters.yml", "prices.yml", "messages.yml");

    private volatile Settings settings;
    private Database database;
    private EconomyBridge ledger;
    private TransactionCoordinator coordinator;
    private RewardService rewards;
    private BoosterService boosters;
    private SellService sell;
    private MenuListener menus;
    private ExoSpawnersBridge exoSpawners;
    private boolean papi;
    private BukkitTask flushTask;
    private final List<BukkitTask> tasks = new ArrayList<>();
    private int generation;

    @Override
    public void onEnable() {
        for (String file : FILES) if (!new File(getDataFolder(), file).exists()) saveResource(file, false);
        try {
            settings = Settings.load(getDataFolder(), ++generation, this::getResource);
        } catch (ConfigException e) {
            getLogger().severe("ExoEconomy configuration is invalid; the plugin will stay disabled:");
            e.problems.forEach(p -> getLogger().severe(" - " + p));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        settings.warnings().forEach(w -> getLogger().warning(w));
        try {
            database = new Database(new File(getDataFolder(), settings.storageFile()).toPath(), settings.storageQueue(), getLogger());
            Database.RecoveryReport report = database.recover(settings.selling().returnEscrowAfterCrash());
            if (!report.cleanShutdown()) getLogger().warning("The previous shutdown was not clean (crash or kill). Recovery ran.");
            if (report.uncertainTransactions() > 0)
                getLogger().warning(report.uncertainTransactions() + " transaction(s) were interrupted and are now UNCERTAIN; nothing is retried automatically. Review with /exoeconomy tx list");
            if (report.recoveredEscrows() > 0)
                getLogger().warning(report.recoveredEscrows() + " open sell menu(s) were recovered as claims (" + (settings.selling().returnEscrowAfterCrash() ? "returned to players" : "held for review: /exoeconomy claims list review") + ")");

            ledger = new EconomyBridge(settings.currencyId(), settings.scale(), getLogger());
            ledger.connect(true); // retried (and failures logged) after all plugins have enabled
            coordinator = new TransactionCoordinator(database, ledger, r -> Bukkit.getScheduler().runTask(this, r),
                    System::currentTimeMillis, () -> UUID.randomUUID().toString(), getLogger());
            rewards = new RewardService(this, database.loadDaily(RewardService.today()));
            boosters = new BoosterService(this, database.loadBoosters());
            sell = new SellService(this);
        } catch (Exception | LinkageError e) {
            getLogger().log(Level.SEVERE, "Cannot start ExoEconomy storage", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        Items.init(new NamespacedKey(this, "menu_icon"));
        menus = new MenuListener();
        var pm = getServer().getPluginManager();
        pm.registerEvents(menus, this);
        pm.registerEvents(new ProvenanceListener(rewards.provenance(), new NamespacedKey(this, "falling_origin")), this);
        pm.registerEvents(new ActivityListener(this, rewards), this);
        pm.registerEvents(new PlayerListener(this), this);

        database.loadPlaced().whenComplete((rows, error) -> Bukkit.getScheduler().runTask(this, () -> {
            if (error != null) {
                getLogger().log(Level.SEVERE, "Could not load block provenance; mining rewards stay paused", error);
                return;
            }
            rewards.provenance().merge(rows);
            getLogger().info("Loaded provenance for " + rows.size() + " tracked blocks; mining rewards active.");
        }));

        command("sell", new SellCommand(this));
        command("boosters", new BoostersCommand(this));
        command("exoeconomy", new ExoEconomyCommand(this));
        registerShardsCommand();
        getServer().getServicesManager().register(ExoEconomyAPI.class, new ApiImpl(this), this, ServicePriority.Normal);

        scheduleFlush();
        tasks.add(Bukkit.getScheduler().runTaskTimer(this, () -> { boosters.tick(); menus.tick(); }, 20L, 20L));
        // After every plugin has enabled: retry the economy, hook optional plugins, audit prices.
        Bukkit.getScheduler().runTask(this, this::afterStartup);
    }

    private void afterStartup() {
        if (!ledger.available()) ledger.connect();
        connectIntegrations();
        List<PriceAudit.Finding> findings = PriceAudit.run(settings.catalogue());
        if (findings.isEmpty()) getLogger().info("Price audit: no recipe turns sellable inputs into more Shards (" + settings.catalogue().size() + " prices).");
        else getLogger().warning("Price audit: " + findings.size() + " recipe(s) sell for more than their inputs; see /exoeconomy audit. First: "
                + findings.get(0).recipe() + " (" + findings.get(0).outputValue() + " > " + findings.get(0).inputValue() + ")");
        for (Player p : Bukkit.getOnlinePlayers()) sell.deliverClaims(p, true);
        database.transactions(null, EnumSet.of(TxState.UNCERTAIN), 1).thenAccept(list -> {
            if (!list.isEmpty()) getLogger().warning("There are UNCERTAIN transactions awaiting review: /exoeconomy tx list uncertain");
        });
    }

    private void connectIntegrations() {
        if (settings.exoSkills()) sell.exoSkills(ExoSkillsBridge.connect(getLogger()));
        if (settings.exoSkills() && settings.selling().exoSkillsBonus() && !sell.exoSkillsConnected())
            getLogger().warning("selling.exoskills-sale-bonus is enabled but the ExoSkills sale bonus is unavailable; selling uses 1x.");
        if (exoSpawners == null && settings.exoSpawners()) exoSpawners = ExoSpawnersBridge.connect(this);
        if (!papi && settings.placeholderApi() && Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                papi = new PapiExpansion(this).register();
                if (papi) getLogger().info("PlaceholderAPI placeholders registered (%exoeconomy_...%).");
            } catch (LinkageError e) {
                getLogger().warning("PlaceholderAPI is installed but incompatible: " + e);
            }
        }
    }

    private void command(String name, TabExecutor executor) {
        PluginCommand cmd = Objects.requireNonNull(getCommand(name), "plugin.yml is missing /" + name);
        cmd.setExecutor(executor);
        cmd.setTabCompleter(executor);
    }

    private void registerShardsCommand() {
        if (!settings.shardsCommand()) return;
        var map = Bukkit.getCommandMap();
        if (map.getCommand(settings.shardsCommandName()) != null) {
            getLogger().warning("commands.shards is enabled but /" + settings.shardsCommandName()
                    + " already exists (probably ExcellentEconomy's own currency command); not registering a duplicate.");
            return;
        }
        map.register(getName().toLowerCase(Locale.ROOT), new ShardsCommand(this, settings.shardsCommandName(), settings.shardsAliases()));
    }

    private void scheduleFlush() {
        if (flushTask != null) flushTask.cancel();
        int ticks = settings.rewards().flushTicks();
        flushTask = Bukkit.getScheduler().runTaskTimer(this, rewards::flush, ticks, ticks);
    }

    /**
     * Reloads every file into a new snapshot. Returns null on success or the problems found
     * (the old configuration stays active). Currency id and storage file need a restart.
     * Open confirmation menus and armed sales keep the values the player saw.
     */
    public List<String> reload() {
        Settings next;
        try {
            next = Settings.load(getDataFolder(), ++generation, this::getResource);
        } catch (ConfigException e) {
            return e.problems;
        }
        List<String> notes = new ArrayList<>(next.warnings());
        if (!next.currencyId().equals(settings.currencyId()) || !next.scale().equals(settings.scale()))
            notes.add("economy.currency-id / economy.scale changes need a restart; still using '" + settings.currencyId() + "'");
        if (!next.storageFile().equals(settings.storageFile())) notes.add("storage.file changes need a restart");
        settings = next;
        boosters.reloadRules();
        scheduleFlush();
        for (var menu : menus.openMenus())
            if (menu instanceof BoosterShopMenu || menu instanceof ActiveBoostersMenu) menu.close();
        connectIntegrations();
        notes.forEach(n -> getLogger().warning(n));
        return null;
    }

    @Override
    public void onDisable() {
        if (menus != null) menus.closeAll();
        if (rewards != null) {
            try { rewards.shutdown(); } catch (RuntimeException e) { getLogger().log(Level.SEVERE, "Reward flush failed", e); }
        }
        if (boosters != null) boosters.shutdown();
        if (coordinator != null && database != null) {
            coordinator.close().forEach((id, beforeEconomy) -> {
                if (beforeEconomy) database.finish(id, TxState.ABORTED, null, null, "server stopping before economy call", List.of());
                else getLogger().warning("Transaction " + id + " was still waiting for ExcellentEconomy at shutdown; it will be UNCERTAIN on next start.");
            });
        }
        if (exoSpawners != null) exoSpawners.close();
        getServer().getServicesManager().unregisterAll(this);
        tasks.forEach(BukkitTask::cancel);
        if (database != null) {
            try {
                database.close();
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Storage shutdown problem", e);
            }
        }
    }

    // ------------------------------------------------------------------ accessors

    public Settings settings() { return settings; }
    public Database database() { return database; }
    public EconomyBridge ledger() { return ledger; }
    public TransactionCoordinator coordinator() { return coordinator; }
    public RewardService rewards() { return rewards; }
    public BoosterService boosters() { return boosters; }
    public SellService sell() { return sell; }
    public MenuListener menus() { return menus; }
    public String economyVersion() { return ledger.version(); }

    public String integrationSummary() {
        List<String> parts = new ArrayList<>();
        parts.add("ExoSkills sale bonus: " + (sell.exoSkillsConnected() ? (settings.selling().exoSkillsBonus() ? "on" : "available, off") : "unavailable"));
        parts.add("ExoSpawners: " + (exoSpawners != null ? (settings.rewards().slaying().exoSpawnersStackKills() ? "stack kills paid" : "hooked, off") : "not hooked"));
        parts.add("PlaceholderAPI: " + (papi ? "on" : "off"));
        return String.join(", ", parts);
    }

    public void notifyAdmins(String key, String... values) {
        settings.messages().send(Bukkit.getConsoleSender(), key, values);
        for (Player p : Bukkit.getOnlinePlayers())
            if (p.hasPermission("exoeconomy.admin.transactions") || p.hasPermission("exoeconomy.admin")) settings.messages().send(p, key, values);
    }
}
