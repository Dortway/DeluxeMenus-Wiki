package dev.exo.cleanwipe.config;

import dev.exo.cleanwipe.integrations.skyblock.SharedIslandPolicy;
import dev.exo.cleanwipe.util.Durations;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/** Validated view of config.yml. Invalid values fail loudly at startup instead of silently changing behaviour. */
public final class Settings {

    public record IntegrationConfig(boolean enabled, Map<String, Path> paths) {
        public Path path(String key) {
            return paths.get(key);
        }
    }

    public final String appeal;
    public final String referencePrefix;
    public final boolean symbols;
    public final Map<String, String> colors;
    public final Duration maxPunish;
    public final int reasonMax;
    public final int confirmSeconds;
    public final int wipeAllConfirmSeconds;
    public final int batchSize;
    public final int batchIntervalTicks;
    public final boolean freezeOnPreview;
    public final boolean liveWipes;
    public final int liveSettleSeconds;
    public final boolean vanillaInventory;
    public final boolean vanillaEnder;
    public final boolean vanillaXp;
    public final boolean vanillaAdvancements;
    public final boolean vanillaStats;
    public final List<String> currencies;
    public final boolean resetToStart;
    public final boolean essentialsMail;
    public final String essentialsMoney;
    public final SharedIslandPolicy sharedIsland;
    public final boolean backupRegions;
    public final Map<String, IntegrationConfig> integrations;
    public final List<UUID> wipeAllAllowJoin;
    public final Path backupDir;
    public final boolean trackIps;
    public final int ipRetentionDays;
    public final int sharedNetworkThreshold;
    public final boolean neverBanPrivate;
    public final String proxyMode;
    public final List<String> trustedProxies;
    public final boolean allowLegacyBungee;
    public final boolean vpnEnabled;
    public final String vpnProvider;
    public final String vpnKey;
    public final int vpnTimeoutMs;
    public final int vpnCacheHours;
    public final boolean vpnCheckAll;
    public final boolean vpnDeny;
    public final String vpnOnError;
    public final List<UUID> vpnExempt;
    public final Map<String, String> messages;

    public Settings(FileConfiguration c, Path serverRoot) {
        appeal = c.getString("server.appeal", "");
        referencePrefix = c.getString("server.reference-prefix", "ew-");
        symbols = c.getBoolean("appearance.symbols", true);
        colors = new HashMap<>();
        ConfigurationSection col = c.getConfigurationSection("appearance.colors");
        if (col != null) {
            for (String k : col.getKeys(false)) {
                colors.put(k, col.getString(k));
            }
        }
        maxPunish = Durations.parse(c.getString("limits.max-punish-duration", "3650days"), null);
        reasonMax = clamp(c.getInt("limits.reason-max-length", 120), 10, 256, "limits.reason-max-length");
        confirmSeconds = clamp(c.getInt("limits.confirm-seconds", 60), 10, 600, "limits.confirm-seconds");
        wipeAllConfirmSeconds = clamp(c.getInt("limits.wipeall-confirm-seconds", 45), 10, 300, "limits.wipeall-confirm-seconds");
        batchSize = clamp(c.getInt("limits.batch-size", 20), 1, 500, "limits.batch-size");
        batchIntervalTicks = clamp(c.getInt("limits.batch-interval-ticks", 10), 1, 200, "limits.batch-interval-ticks");
        freezeOnPreview = c.getBoolean("wipe.freeze-on-preview", true);
        liveWipes = c.getBoolean("wipe.live", true);
        liveSettleSeconds = clamp(c.getInt("wipe.live-settle-seconds", 5), 1, 60, "wipe.live-settle-seconds");
        vanillaInventory = c.getBoolean("wipe.vanilla.inventory", true);
        vanillaEnder = c.getBoolean("wipe.vanilla.ender-chest", true);
        vanillaXp = c.getBoolean("wipe.vanilla.experience", true);
        vanillaAdvancements = c.getBoolean("wipe.vanilla.advancements", false);
        vanillaStats = c.getBoolean("wipe.vanilla.statistics", false);
        Object cur = c.get("wipe.excellent-economy.currencies", "all");
        currencies = new ArrayList<>();
        if (cur instanceof List<?> list) {
            list.forEach(o -> currencies.add(String.valueOf(o)));
        } else if (!"all".equalsIgnoreCase(String.valueOf(cur))) {
            currencies.add(String.valueOf(cur));
        }
        String resetTo = c.getString("wipe.excellent-economy.reset-to", "start-value").toLowerCase(Locale.ROOT);
        if (!resetTo.equals("start-value") && !resetTo.equals("zero")) {
            throw new IllegalArgumentException("wipe.excellent-economy.reset-to must be start-value or zero");
        }
        resetToStart = resetTo.equals("start-value");
        essentialsMail = c.getBoolean("wipe.essentials.mail", true);
        essentialsMoney = c.getString("wipe.essentials.money", "auto");
        sharedIsland = switch (c.getString("wipe.eskyblock.owned-shared-island", "require-flag").toLowerCase(Locale.ROOT)) {
            case "require-flag" -> SharedIslandPolicy.REQUIRE_FLAG;
            case "disband" -> SharedIslandPolicy.DISBAND;
            case "keep" -> SharedIslandPolicy.KEEP;
            default -> throw new IllegalArgumentException("wipe.eskyblock.owned-shared-island must be require-flag, disband or keep");
        };
        backupRegions = c.getBoolean("wipe.eskyblock.backup-region-files", true);

        integrations = new LinkedHashMap<>();
        ConfigurationSection ints = c.getConfigurationSection("integrations");
        if (ints != null) {
            for (String id : ints.getKeys(false)) {
                ConfigurationSection s = ints.getConfigurationSection(id);
                if (s == null) {
                    continue;
                }
                Map<String, Path> paths = new HashMap<>();
                for (String key : s.getKeys(false)) {
                    if (!key.equals("enabled")) {
                        paths.put(key, resolve(serverRoot, s.getString(key)));
                    }
                }
                integrations.put(id, new IntegrationConfig(s.getBoolean("enabled", true), paths));
            }
        }
        wipeAllAllowJoin = uuids(c.getStringList("wipeall.allow-join"), "wipeall.allow-join");
        backupDir = resolve(serverRoot, c.getString("backups.directory", "plugins/ExoCleanWipe/backups"));
        trackIps = c.getBoolean("identity.track-ips", true);
        ipRetentionDays = Math.max(0, c.getInt("identity.ip-retention-days", 180));
        sharedNetworkThreshold = clamp(c.getInt("identity.shared-network-threshold", 5), 2, 1000, "identity.shared-network-threshold");
        neverBanPrivate = c.getBoolean("punishments.never-ban-private-addresses", true);
        proxyMode = c.getString("proxy.mode", "auto");
        trustedProxies = c.getStringList("proxy.trusted-proxy-addresses");
        allowLegacyBungee = c.getBoolean("proxy.allow-legacy-bungeecord-forwarding", false);
        vpnEnabled = c.getBoolean("vpn.enabled", false);
        vpnProvider = c.getString("vpn.provider", "proxycheck");
        String envKey = System.getenv("EXOCLEANWIPE_VPN_KEY");
        vpnKey = envKey != null && !envKey.isBlank() ? envKey : c.getString("vpn.api-key", "");
        vpnTimeoutMs = clamp(c.getInt("vpn.timeout-ms", 2500), 250, 10000, "vpn.timeout-ms");
        vpnCacheHours = clamp(c.getInt("vpn.cache-hours", 24), 1, 24 * 30, "vpn.cache-hours");
        vpnCheckAll = c.getString("vpn.check", "new-players").equalsIgnoreCase("all");
        vpnDeny = c.getString("vpn.action", "flag").equalsIgnoreCase("deny");
        vpnOnError = c.getString("vpn.on-error", "allow").toLowerCase(Locale.ROOT);
        if (!List.of("allow", "deny", "deny-new").contains(vpnOnError)) {
            throw new IllegalArgumentException("vpn.on-error must be allow, deny or deny-new");
        }
        vpnExempt = uuids(c.getStringList("vpn.exempt"), "vpn.exempt");
        messages = new HashMap<>();
        ConfigurationSection msg = c.getConfigurationSection("messages");
        if (msg != null) {
            for (String k : msg.getKeys(false)) {
                messages.put(k, msg.getString(k));
            }
        }
    }

    public IntegrationConfig integration(String id) {
        return integrations.getOrDefault(id, new IntegrationConfig(false, Map.of()));
    }

    private static Path resolve(Path root, String value) {
        Path p = Path.of(value);
        return p.isAbsolute() ? p : root.resolve(p).normalize();
    }

    private static int clamp(int v, int min, int max, String key) {
        if (v < min || v > max) {
            throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
        }
        return v;
    }

    private static List<UUID> uuids(List<String> raw, String key) {
        List<UUID> out = new ArrayList<>();
        for (String s : raw) {
            try {
                out.add(UUID.fromString(s));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(key + " contains an invalid uuid: " + s);
            }
        }
        return out;
    }
}
