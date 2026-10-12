package dev.exo.cleanwipe;

import dev.exo.cleanwipe.command.WipeCommand;
import dev.exo.cleanwipe.config.Settings;
import dev.exo.cleanwipe.identity.ProxyTrust;
import dev.exo.cleanwipe.identity.VpnService;
import dev.exo.cleanwipe.integrations.vanilla.VanillaIntegration;
import dev.exo.cleanwipe.punish.LoginGate;
import dev.exo.cleanwipe.punish.PunishmentService;
import dev.exo.cleanwipe.storage.AuditLog;
import dev.exo.cleanwipe.storage.CoreStore;
import dev.exo.cleanwipe.storage.Records.Job;
import dev.exo.cleanwipe.text.Messages;
import dev.exo.cleanwipe.wipe.ActionService;
import dev.exo.cleanwipe.wipe.IntegrationRegistry;
import dev.exo.cleanwipe.wipe.JobEngine;
import dev.exo.cleanwipe.wipe.LiveRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class ExoCleanWipePlugin extends JavaPlugin {

    private Settings settings;
    private CoreStore store;
    private IntegrationRegistry registry;
    private JobEngine engine;
    private LiveRunner runner;
    private ExecutorService io;
    private Messages messages;
    private boolean broken;

    /**
     * Runs before every plugin listed in loadbefore has loaded its data: applies queued startup steps (vault files,
     * the Exo plugin databases, EGen and ExoSpawners files) while nothing can cache or write them back.
     */
    @Override
    public void onLoad() {
        try {
            saveDefaultConfig();
            settings = new Settings(getConfig(), serverRoot());
            store = new CoreStore(getDataFolder().toPath().resolve("exocleanwipe.db"));
            registry = new IntegrationRegistry();
            registry.registerStartup(settings, name -> Bukkit.getPluginManager().getPlugin(name) != null);
            engine = new JobEngine(store, registry, settings.backupDir, getLogger());
            engine.setNotifier(e -> {
                String[] p = e.split(":");
                getLogger().info("job " + p[0] + " is now " + p[1].toLowerCase().replace('_', ' ')
                        + (p[2].equals("0") ? "" : " (" + p[2] + " step(s) need attention, see /wipe status " + p[0] + ")"));
            });
            int ran = engine.runStartupSteps(true);
            if (ran > 0) {
                getLogger().info("applied " + ran + " queued startup wipe step(s) before other plugins loaded");
            }
        } catch (Exception e) {
            broken = true;
            getLogger().log(Level.SEVERE, "ExoCleanWipe could not start; no wipe or punishment will run. Fix the error and restart.", e);
        }
    }

    private static Path serverRoot() {
        return Path.of("").toAbsolutePath();
    }

    @Override
    public void onEnable() {
        if (broken || store == null) {
            getLogger().severe("disabled because onLoad failed (see above). Logins are NOT checked against ExoCleanWipe punishments.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            enable();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "ExoCleanWipe failed to enable", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void enable() throws Exception {
        messages = new Messages(settings.messages, settings.colors, settings.symbols);
        io = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "ExoCleanWipe-io");
            t.setDaemon(true);
            return t;
        });
        ProxyTrust trust = ProxyTrust.evaluate(proxyInputs(), new HashSet<>(settings.trustedProxies));
        getLogger().info("address trust: " + trust.mode().name().toLowerCase() + (trust.ipFeaturesEnabled() ? "" : " (ip features disabled)"));
        trust.warnings().forEach(w -> getLogger().warning(w));

        // live integrations, only for installed plugins (their classes reference those plugins' APIs)
        Path level = Bukkit.getWorldContainer().toPath().resolve(Bukkit.getWorlds().get(0).getName());
        if (!Files.isDirectory(level.resolve("players")) && !Files.isDirectory(level.resolve("playerdata"))) {
            level = Bukkit.getWorlds().get(0).getWorldFolder().toPath();
        }
        VanillaIntegration vanilla = registry.registerVanilla(settings, level, io);
        if (Bukkit.getPluginManager().isPluginEnabled("ExcellentEconomy")) {
            registry.register(new dev.exo.cleanwipe.integrations.economy.ExcellentEconomyIntegration(settings.currencies, settings.resetToStart));
            getServer().getPluginManager().registerEvents(new dev.exo.cleanwipe.integrations.economy.BalanceGuard(store, getLogger()), this);
        } else {
            registry.markUnavailable("ExcellentEconomy", "plugin not enabled");
        }
        if (Bukkit.getPluginManager().isPluginEnabled("Essentials")) {
            registry.register(new dev.exo.cleanwipe.integrations.essentials.EssentialsIntegration(settings.essentialsMail, settings.essentialsMoney));
        } else {
            registry.markUnavailable("Essentials", "plugin not enabled");
        }
        if (Bukkit.getPluginManager().isPluginEnabled("ESkyblock")) {
            registry.register(new dev.exo.cleanwipe.integrations.skyblock.ESkyblockIntegration(settings.sharedIsland, settings.backupRegions));
        } else {
            registry.markUnavailable("ESkyblock", "plugin not enabled");
        }

        AuditLog audit = new AuditLog(getDataFolder().toPath().resolve("audit.log"), store, getLogger());
        PunishmentService punishments = new PunishmentService(store, messages, settings.referencePrefix, settings.appeal, settings.neverBanPrivate);
        VpnService vpn = null;
        if (settings.vpnEnabled) {
            if (!settings.vpnProvider.equalsIgnoreCase("proxycheck")) {
                getLogger().warning("vpn.provider '" + settings.vpnProvider + "' is not supported; vpn checks are off");
            } else {
                if (settings.vpnKey.isBlank()) {
                    getLogger().warning("vpn.api-key is empty: proxycheck.io allows only a small number of keyless lookups per day");
                }
                vpn = new VpnService(new VpnService.ProxyCheck(settings.vpnKey, settings.vpnTimeoutMs), store,
                        settings.vpnCacheHours * 3_600_000L, settings.vpnTimeoutMs, getLogger());
                getLogger().info("vpn checks enabled (proxycheck, " + (settings.vpnCheckAll ? "every login" : "new players")
                        + ", action " + (settings.vpnDeny ? "deny" : "flag") + ", on error " + settings.vpnOnError + ")");
            }
        }
        getServer().getPluginManager().registerEvents(
                new LoginGate(store, punishments, messages, settings, trust, vpn, this::staffAlert, getLogger()), this);

        int released = ActionService.releaseFreezes(store);
        if (released > 0) {
            getLogger().info("released " + released + " preview freeze(s) left from before the restart");
        }
        ActionService actions = new ActionService(this, settings, messages, store, engine, registry, punishments, audit, trust, vanilla);
        WipeCommand command = new WipeCommand(this, settings, messages, store, actions, punishments, engine, registry, audit);
        for (String name : new String[]{"wipe", "wipeall"}) {
            PluginCommand pc = Objects.requireNonNull(getCommand(name), name);
            pc.setExecutor(command);
            pc.setTabCompleter(command);
        }

        engine.setNotifier(this::onJobChange);
        runner = new LiveRunner(this, engine, settings.batchSize, settings.batchIntervalTicks,
                job -> messages.screen("lock-screen", Messages.values("ref", "job " + job)));
        runner.start();
        for (Job j : engine.openJobs()) {
            getLogger().info("resuming job " + j.id() + " (" + j.type().name().toLowerCase() + ", " + j.state().name().toLowerCase() + ")");
            engine.evaluate(j.id());
        }
        for (Job j : store.jobsIn(java.util.EnumSet.of(dev.exo.cleanwipe.storage.Records.JobState.PARTIAL))) {
            getLogger().warning("job " + j.id() + " is partial: its accounts stay locked. see /wipe status " + j.id());
        }
        if (settings.ipRetentionDays > 0) {
            long cutoff = System.currentTimeMillis() - settings.ipRetentionDays * 86_400_000L;
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    int n = store.purgeIpHistoryBefore(cutoff);
                    if (n > 0) {
                        getLogger().info("removed " + n + " ip history entr(ies) older than " + settings.ipRetentionDays + " days");
                    }
                } catch (Exception e) {
                    getLogger().log(Level.WARNING, "ip retention cleanup failed", e);
                }
            });
        }
        getLogger().info(registry.all().size() + " integration(s) active; " + registry.unavailable().size() + " not covered (see /wipe integrations)");
    }

    private ProxyTrust.Inputs proxyInputs() {
        Path root = serverRoot();
        YamlConfiguration spigot = YamlConfiguration.loadConfiguration(root.resolve("spigot.yml").toFile());
        YamlConfiguration paper = YamlConfiguration.loadConfiguration(root.resolve("config").resolve("paper-global.yml").toFile());
        String secret = paper.getString("proxies.velocity.secret", "");
        return new ProxyTrust.Inputs(Bukkit.getOnlineMode(), spigot.getBoolean("settings.bungeecord", false),
                paper.getBoolean("proxies.velocity.enabled", false), secret != null && !secret.isBlank(),
                paper.getBoolean("proxies.velocity.online-mode", true), settings.proxyMode, settings.allowLegacyBungee);
    }

    /** Notifier format from JobEngine: "jobId:STATE:problems:startupPending". */
    private void onJobChange(String event) {
        String[] p = event.split(":");
        Runnable r = () -> {
            String job = p[0];
            switch (p[1]) {
                case "COMPLETED" -> staff("job-finished", "job", job, "result", "completed, accounts unlocked");
                case "PARTIAL" -> staff("job-partial", "job", job, "result", p[2] + " step(s) blocked or failed");
                case "WAITING_RESTART" -> staff("job-waiting-restart", "job", job, "count", p[3]);
                default -> {
                }
            }
        };
        if (Bukkit.isPrimaryThread()) {
            r.run();
        } else if (isEnabled()) {
            Bukkit.getScheduler().runTask(this, r);
        }
    }

    private void staff(String key, Object... values) {
        Bukkit.getConsoleSender().sendMessage(messages.chat(key, Messages.values(values)));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("exocleanwipe.notify")) {
                messages.send(p, key, Messages.values(values));
            }
        }
    }

    public void staffAlert(String text) {
        Runnable r = () -> staff("staff-alert", "message", text);
        if (Bukkit.isPrimaryThread()) {
            r.run();
        } else if (isEnabled()) {
            Bukkit.getScheduler().runTask(this, r);
        }
    }

    @Override
    public void onDisable() {
        if (runner != null) {
            runner.stop();
        }
        if (io != null) {
            io.shutdown();
            try {
                io.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (store != null) {
            try {
                store.close();
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "closing database", e);
            }
        }
    }
}
