package dev.exo.cleanwipe.wipe;

import dev.exo.cleanwipe.config.Settings;
import dev.exo.cleanwipe.integrations.files.EGenIntegration;
import dev.exo.cleanwipe.integrations.files.ExoSpawnersIntegration;
import dev.exo.cleanwipe.integrations.files.ExoVaultsIntegration;
import dev.exo.cleanwipe.integrations.sql.ChunkCollectorIntegration;
import dev.exo.cleanwipe.integrations.sql.EPlayerShopIntegration;
import dev.exo.cleanwipe.integrations.sql.PluginSpecs;
import dev.exo.cleanwipe.integrations.vanilla.PlayerDataEditor;
import dev.exo.cleanwipe.integrations.vanilla.VanillaIntegration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Predicate;

/**
 * Builds the integrations for the plugins that are actually installed. Startup integrations are built in
 * {@code onLoad} from paths alone; live integrations are built in {@code onEnable} and only when their plugin is
 * present, so their classes (which reference that plugin's API) are never loaded otherwise.
 */
public final class IntegrationRegistry {

    /** Integrations that exist in the code base, with the plugin that owns the data. */
    public static final List<String> STARTUP_IDS;

    static {
        List<String> ids = new ArrayList<>(PluginSpecs.IDS);
        ids.addAll(List.of("ExoVaults", "ChunkCollector", "EPlayerShop", "EGen", "ExoSpawners"));
        STARTUP_IDS = Collections.unmodifiableList(ids);
    }

    private final Map<String, Integration> all = new LinkedHashMap<>();
    private final Map<String, String> unavailable = new LinkedHashMap<>();

    public void register(Integration integration) {
        all.put(integration.id(), integration);
    }

    public void markUnavailable(String id, String reason) {
        unavailable.put(id, reason);
    }

    public Map<String, String> unavailable() {
        return Collections.unmodifiableMap(unavailable);
    }

    public Optional<Integration> get(String id) {
        return Optional.ofNullable(all.get(id));
    }

    public List<Integration> all() {
        return List.copyOf(all.values());
    }

    public List<StartupIntegration> startup() {
        List<StartupIntegration> out = new ArrayList<>();
        for (Integration i : all.values()) {
            if (i instanceof StartupIntegration s) {
                out.add(s);
            }
        }
        return out;
    }

    public List<LiveIntegration> live() {
        List<LiveIntegration> out = new ArrayList<>();
        for (Integration i : all.values()) {
            if (i instanceof LiveIntegration l) {
                out.add(l);
            }
        }
        return out;
    }

    /**
     * @param pluginInstalled tells whether a plugin jar is installed (by plugin name); in onLoad this is the plugin
     *                        manager's view of loaded plugins
     */
    public void registerStartup(Settings s, Predicate<String> pluginInstalled) {
        for (String id : STARTUP_IDS) {
            Settings.IntegrationConfig cfg = s.integration(id);
            if (!cfg.enabled()) {
                markUnavailable(id, "disabled in config.yml");
                continue;
            }
            if (!pluginInstalled.test(id)) {
                markUnavailable(id, "plugin not installed");
                continue;
            }
            Integration built = switch (id) {
                case "ExoVaults" -> new ExoVaultsIntegration(cfg.path("data"));
                case "ChunkCollector" -> new ChunkCollectorIntegration(cfg.path("database"));
                case "EPlayerShop" -> new EPlayerShopIntegration(cfg.path("database"));
                case "EGen" -> new EGenIntegration(cfg.path("machines"), cfg.path("chests"), cfg.path("limits"));
                case "ExoSpawners" -> new ExoSpawnersIntegration(cfg.path("players"));
                case "LightZone" -> lightZone(cfg);
                default -> PluginSpecs.create(id, cfg.path("database")).orElse(null);
            };
            if (built == null) {
                continue;
            }
            register(built);
        }
    }

    private Integration lightZone(Settings.IntegrationConfig cfg) {
        Path storage = cfg.path("storage-config");
        if (storage != null && Files.exists(storage)) {
            try {
                String text = Files.readString(storage);
                // storage.yml: "  type: SQLITE" (or MYSQL); only the SQLite file layout is supported
                for (String line : text.split("\n")) {
                    String t = line.trim();
                    if (t.startsWith("type:")) {
                        String type = t.substring(5).replace("\"", "").replace("'", "").trim();
                        if (!type.equalsIgnoreCase("SQLITE")) {
                            markUnavailable("LightZone", "LightZone uses " + type + " storage; only SQLITE is supported");
                            return null;
                        }
                        break;
                    }
                }
            } catch (Exception e) {
                markUnavailable("LightZone", "cannot read " + storage + ": " + e.getMessage());
                return null;
            }
        }
        return PluginSpecs.create("LightZone", cfg.path("database")).orElse(null);
    }

    public VanillaIntegration registerVanilla(Settings s, Path mainWorldFolder, Executor io) {
        VanillaIntegration v = new VanillaIntegration(mainWorldFolder,
                new PlayerDataEditor.Options(s.vanillaInventory, s.vanillaEnder, s.vanillaXp), s.vanillaAdvancements,
                s.vanillaStats, io);
        register(v);
        return v;
    }
}
