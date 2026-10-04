package com.exoblacksmith;

import com.exoblacksmith.ability.MaskAbilities;
import com.exoblacksmith.ability.SetAbilities;
import com.exoblacksmith.ability.SummonService;
import com.exoblacksmith.ability.TempBlockService;
import com.exoblacksmith.command.AdminCommand;
import com.exoblacksmith.command.BlacksmithCommand;
import com.exoblacksmith.config.ConfigLoader;
import com.exoblacksmith.config.Registry;
import com.exoblacksmith.craft.CraftingService;
import com.exoblacksmith.craft.RuneService;
import com.exoblacksmith.effect.CooldownService;
import com.exoblacksmith.effect.DamageListener;
import com.exoblacksmith.effect.EquipmentService;
import com.exoblacksmith.effect.RuneEffects;
import com.exoblacksmith.effect.TempBuffs;
import com.exoblacksmith.gui.MenuListener;
import com.exoblacksmith.integration.HeadDrops;
import com.exoblacksmith.integration.ItemsAdderVisuals;
import com.exoblacksmith.integration.VisualProvider;
import com.exoblacksmith.item.ItemKeys;
import com.exoblacksmith.item.ItemService;
import com.exoblacksmith.item.RetiredUids;
import com.exoblacksmith.item.Signer;
import com.exoblacksmith.item.VanillaGuard;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public class ExoBlackSmithPlugin extends JavaPlugin {
    private volatile Registry registry;
    private volatile VisualProvider visuals = VisualProvider.NONE;
    private ItemService items;
    private EquipmentService equipment;
    private SummonService summons;
    private TempBlockService tempBlocks;
    private MaskAbilities maskAbilities;
    private RuneEffects runeEffects;
    private HeadDrops drops;
    private Services services;
    private CooldownService cooldowns;

    @Override
    public void onEnable() {
        for (String file : ConfigLoader.FILES) {
            if (!new File(getDataFolder(), file).exists()) {
                saveResource(file, false);
            }
        }
        ConfigLoader.Result result = loadConfig();
        result.warnings().forEach(w -> getLogger().warning(w));
        if (!result.ok()) {
            getLogger().severe("ExoBlackSmith configuration is invalid; the plugin will stay disabled:");
            result.errors().forEach(e -> getLogger().severe("  " + e));
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        registry = result.registry();

        byte[] secret;
        try {
            secret = loadSecret(getDataFolder().toPath().resolve("data").resolve("secret.key"));
        } catch (IOException e) {
            getLogger().severe("Could not read or create data/secret.key: " + e.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        ItemKeys keys = new ItemKeys(this);
        RetiredUids retired = new RetiredUids(getDataFolder().toPath().resolve("data").resolve("retired-uids.txt"), getLogger());
        items = new ItemService(keys, new Signer(secret), retired, this::registry, () -> visuals);
        CraftingService crafting = new CraftingService(items, this::registry, getLogger());
        RuneService runes = new RuneService(items, this::registry, getLogger());
        services = new Services(this, this::registry, items, crafting, runes);

        if (Bukkit.getPluginManager().getPlugin("ItemsAdder") != null) {
            ItemsAdderVisuals ia = new ItemsAdderVisuals(getLogger(), () -> Bukkit.getScheduler().runTask(this, this::refreshOnlineInventories));
            Bukkit.getPluginManager().registerEvents(ia, this);
            visuals = ia;
            getLogger().info("ItemsAdder detected; waiting for its data to load before applying custom visuals.");
        } else {
            getLogger().info("ItemsAdder not installed; using vanilla fallback visuals (player-head textures and item models).");
        }

        cooldowns = new CooldownService(keys);
        TempBuffs buffs = new TempBuffs();
        equipment = new EquipmentService(this, items, this::registry);
        summons = new SummonService(this, keys);
        tempBlocks = new TempBlockService(this, getDataFolder().toPath().resolve("data").resolve("temp-blocks.yml").toFile());
        maskAbilities = new MaskAbilities(this, this::registry, equipment, cooldowns, tempBlocks, summons, keys);
        runeEffects = new RuneEffects(this, this::registry, equipment, cooldowns, buffs);
        drops = new HeadDrops(this, items, this::registry);

        var pm = Bukkit.getPluginManager();
        pm.registerEvents(new MenuListener(this, this::registry), this);
        pm.registerEvents(new VanillaGuard(items, this::registry), this);
        pm.registerEvents(equipment, this);
        pm.registerEvents(new DamageListener(this::registry, equipment, cooldowns, buffs), this);
        pm.registerEvents(runeEffects, this);
        pm.registerEvents(summons, this);
        pm.registerEvents(tempBlocks, this);
        pm.registerEvents(maskAbilities, this);
        pm.registerEvents(new SetAbilities(this::registry, equipment, cooldowns, buffs), this);
        pm.registerEvents(drops, this);
        pm.registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
            public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
                cooldowns.forget(event.getPlayer().getUniqueId());
                buffs.clear(event.getPlayer().getUniqueId());
            }
        }, this);

        equipment.start();
        tempBlocks.start();
        summons.start();
        maskAbilities.start();
        runeEffects.start();
        drops.bindCustomEvent();

        command("blacksmith", new BlacksmithCommand(services));
        command("exoblacksmith", new AdminCommand(services, drops, this::reload));

        Bukkit.getOnlinePlayers().forEach(equipment::markDirty);
        getLogger().info("ExoBlackSmith enabled: " + registry.items().size() + " items, " + registry.recipes().size()
                + " recipes, " + retired.size() + " retired item ids.");
    }

    private void command(String name, org.bukkit.command.TabExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            throw new IllegalStateException("command " + name + " missing from plugin.yml");
        }
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    @Override
    public void onDisable() {
        MenuListener.closeAll();
        if (equipment != null) {
            equipment.shutdown();
        }
        if (summons != null) {
            summons.shutdown();
        }
        if (tempBlocks != null) {
            tempBlocks.shutdown();
        }
        if (maskAbilities != null) {
            maskAbilities.shutdown();
        }
        if (runeEffects != null) {
            runeEffects.stop();
        }
        if (drops != null) {
            drops.unbind();
        }
    }

    public Registry registry() {
        return registry;
    }

    /** Item creation/identification, crafting and rune services (public API for other plugins). */
    public Services services() {
        return services;
    }

    public EquipmentService equipment() {
        return equipment;
    }

    public CooldownService cooldowns() {
        return cooldowns;
    }

    /** Value-head drop API: {@code drops().grant(killer, type, deaths, fromSpawner, location)}. */
    public HeadDrops drops() {
        return drops;
    }

    private ConfigLoader.Result loadConfig() {
        return ConfigLoader.load(file -> {
            File f = new File(getDataFolder(), file);
            return f.exists() ? Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8) : null;
        }, file -> {
            var in = getResource(file);
            return in == null ? null : new java.io.InputStreamReader(in, StandardCharsets.UTF_8);
        });
    }

    /**
     * Validates every file first; the live configuration is only replaced if the whole set is valid.
     * Open menus and in-progress crafts keep working: recipe menus re-check their snapshot on confirm.
     */
    public ConfigLoader.Result reload() {
        ConfigLoader.Result result = loadConfig();
        if (result.ok()) {
            registry = result.registry();
            equipment.start();
            drops.bindCustomEvent();
            Bukkit.getOnlinePlayers().forEach(equipment::markDirty);
            getLogger().info("Configuration reloaded.");
        } else {
            getLogger().warning("Reload rejected; keeping the previous configuration:");
            result.errors().forEach(e -> getLogger().warning("  " + e));
        }
        return result;
    }

    /** Re-renders ExoBlackSmith items held by online players (e.g. once ItemsAdder visuals are ready). */
    private void refreshOnlineInventories() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            ItemStack[] contents = player.getInventory().getContents();
            for (int i = 0; i < contents.length; i++) {
                ItemStack stack = contents[i];
                if (stack != null && items.isTagged(stack) && items.refresh(stack)) {
                    player.getInventory().setItem(i, stack);
                }
            }
        }
    }

    static byte[] loadSecret(Path path) throws IOException {
        if (Files.exists(path)) {
            byte[] secret = HexFormat.of().parseHex(Files.readString(path, StandardCharsets.US_ASCII).trim());
            if (secret.length < 16) {
                throw new IOException("secret.key is too short");
            }
            return secret;
        }
        Files.createDirectories(path.getParent());
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        Files.writeString(path, HexFormat.of().formatHex(secret), StandardCharsets.US_ASCII);
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // non-POSIX filesystem
        }
        return secret;
    }
}
