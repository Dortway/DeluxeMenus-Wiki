#!/usr/bin/env python3
"""Writes compile-only API stubs to stubs/src. These are NEVER packaged into the plugin jar.

They exist only so this project can be compiled and unit-tested in an environment that cannot
download Paper, ExcellentEconomy or PlaceholderAPI. Every signature mirrors the real API:
- Paper/Bukkit/Adventure: kinds (interface vs class) and descriptors cross-checked against the
  constant pool of ExoSkills 1.0.1, which was compiled against Paper 26.1.2.
- ExcellentEconomy: signatures copied from su.nightexpress.excellenteconomy 2.8.0 source.
- PlaceholderAPI: the long-stable PlaceholderExpansion contract.
- Vault: VaultAPI 1.7 Economy / EconomyResponse (methods used only).
The production build (build.ps1 / build.sh --real) compiles against the real server jars instead.
"""
import os, sys
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "src")
S = "{ throw new UnsupportedOperationException(\"compile-only stub\"); }"

files = {}
def f(path, body):
    files[path] = body

# ---------------------------------------------------------------- Adventure
f("net/kyori/adventure/audience/Audience.java", """package net.kyori.adventure.audience;
public interface Audience {
    default void sendMessage(net.kyori.adventure.text.Component message) {}
    default void sendActionBar(net.kyori.adventure.text.Component message) {}
}""")
f("net/kyori/adventure/text/Component.java", """package net.kyori.adventure.text;
public interface Component {
    static TextComponent empty() %s
    static TextComponent text(String content) %s
    default Component decoration(net.kyori.adventure.text.format.TextDecoration decoration, boolean flag) %s
}""" % (S, S, S))
f("net/kyori/adventure/text/TextComponent.java", """package net.kyori.adventure.text;
public interface TextComponent extends Component { default String content() { return ""; } }""")
f("net/kyori/adventure/text/format/TextDecoration.java", """package net.kyori.adventure.text.format;
public enum TextDecoration { OBFUSCATED, BOLD, STRIKETHROUGH, UNDERLINED, ITALIC }""")
f("net/kyori/adventure/text/serializer/legacy/LegacyComponentSerializer.java", """package net.kyori.adventure.text.serializer.legacy;
public interface LegacyComponentSerializer {
    static Builder builder() %s
    net.kyori.adventure.text.TextComponent deserialize(String input);
    interface Builder {
        Builder character(char legacyCharacter);
        Builder hexColors();
        Builder useUnusualXRepeatedCharacterHexFormat();
        LegacyComponentSerializer build();
    }
}""" % S)

# ---------------------------------------------------------------- Paper meta
f("io/papermc/paper/plugin/configuration/PluginMeta.java", """package io.papermc.paper.plugin.configuration;
public interface PluginMeta { String getName(); String getVersion(); }""")

# ---------------------------------------------------------------- org.bukkit core
f("org/bukkit/Bukkit.java", """package org.bukkit;
import java.util.*;
public final class Bukkit {
    private Bukkit() {}
    public static Server getServer() %(S)s
    public static org.bukkit.scheduler.BukkitScheduler getScheduler() %(S)s
    public static org.bukkit.plugin.PluginManager getPluginManager() %(S)s
    public static org.bukkit.plugin.ServicesManager getServicesManager() %(S)s
    public static org.bukkit.entity.Player getPlayer(UUID id) %(S)s
    public static org.bukkit.entity.Player getPlayerExact(String name) %(S)s
    public static Collection<? extends org.bukkit.entity.Player> getOnlinePlayers() %(S)s
    public static OfflinePlayer getOfflinePlayer(UUID id) %(S)s
    public static OfflinePlayer getOfflinePlayerIfCached(String name) %(S)s
    public static boolean isPrimaryThread() %(S)s
    public static int getCurrentTick() %(S)s
    public static org.bukkit.inventory.Inventory createInventory(org.bukkit.inventory.InventoryHolder owner, int size, net.kyori.adventure.text.Component title) %(S)s
    public static org.bukkit.command.ConsoleCommandSender getConsoleSender() %(S)s
    public static Iterator<org.bukkit.inventory.Recipe> recipeIterator() %(S)s
    public static org.bukkit.command.CommandMap getCommandMap() %(S)s
}""" % {"S": S})
f("org/bukkit/Server.java", """package org.bukkit;
public interface Server {
    org.bukkit.plugin.PluginManager getPluginManager();
    org.bukkit.plugin.ServicesManager getServicesManager();
    org.bukkit.scheduler.BukkitScheduler getScheduler();
}""")
f("org/bukkit/GameMode.java", "package org.bukkit;\npublic enum GameMode { CREATIVE, SURVIVAL, ADVENTURE, SPECTATOR }")
f("org/bukkit/Keyed.java", "package org.bukkit;\npublic interface Keyed { NamespacedKey getKey(); }")
f("org/bukkit/Location.java", "package org.bukkit;\npublic class Location { }")
f("org/bukkit/World.java", """package org.bukkit;
public interface World { java.util.UUID getUID(); String getName(); int getMaxHeight(); }""")
f("org/bukkit/OfflinePlayer.java", """package org.bukkit;
public interface OfflinePlayer { java.util.UUID getUniqueId(); String getName(); org.bukkit.entity.Player getPlayer(); boolean isOnline(); }""")
f("org/bukkit/NamespacedKey.java", """package org.bukkit;
public final class NamespacedKey {
    private final String namespace, key;
    public NamespacedKey(org.bukkit.plugin.Plugin plugin, String key) { this.namespace = plugin.getName().toLowerCase(java.util.Locale.ROOT); this.key = key; }
    private NamespacedKey(String namespace, String key) { this.namespace = namespace; this.key = key; }
    public static NamespacedKey fromString(String s) { int i = s.indexOf(':'); return i < 0 ? new NamespacedKey("minecraft", s) : new NamespacedKey(s.substring(0, i), s.substring(i + 1)); }
    public String getNamespace() { return namespace; }
    public String getKey() { return key; }
    @Override public boolean equals(Object o) { return o instanceof NamespacedKey k && k.namespace.equals(namespace) && k.key.equals(key); }
    @Override public int hashCode() { return namespace.hashCode() * 31 + key.hashCode(); }
    @Override public String toString() { return namespace + ":" + key; }
}""")
mats = ["AIR", "STONE", "COBBLESTONE", "DIRT", "SAND", "GLASS", "OAK_LOG", "OAK_PLANKS", "DIAMOND", "DIAMOND_BLOCK",
        "SUGAR_CANE", "CACTUS", "BAMBOO", "KELP", "KELP_PLANT", "WHEAT", "MELON", "PUMPKIN", "CLOCK", "BARRIER",
        "NETHER_STAR", "GRAY_STAINED_GLASS_PANE", "BLACK_STAINED_GLASS_PANE", "LIME_CONCRETE", "RED_CONCRETE",
        "YELLOW_CONCRETE", "LIME_DYE", "ORANGE_DYE", "GRAY_DYE", "GLASS_BOTTLE", "ARROW", "BOOK", "EMERALD",
        "EXPERIENCE_BOTTLE", "SHULKER_BOX", "BEDROCK"]
f("org/bukkit/Material.java", """package org.bukkit;
public enum Material {
    %s;
    public boolean isItem() { return this != AIR; }
    public boolean isBlock() { return true; }
    public boolean isAir() { return this == AIR; }
    public int getMaxStackSize() { return 64; }
    public static Material getMaterial(String name) { try { return valueOf(name); } catch (IllegalArgumentException e) { return null; } }
}""" % ", ".join(mats))

f("org/bukkit/block/Block.java", """package org.bukkit.block;
public interface Block {
    org.bukkit.World getWorld(); int getX(); int getY(); int getZ();
    org.bukkit.Material getType();
    org.bukkit.block.data.BlockData getBlockData();
    Block getRelative(BlockFace face);
}""")
f("org/bukkit/block/BlockFace.java", "package org.bukkit.block;\npublic enum BlockFace { NORTH, EAST, SOUTH, WEST, UP, DOWN, SELF }")
f("org/bukkit/block/BlockState.java", """package org.bukkit.block;
public interface BlockState { Block getBlock(); org.bukkit.Material getType(); org.bukkit.block.data.BlockData getBlockData(); }""")
f("org/bukkit/block/data/BlockData.java", "package org.bukkit.block.data;\npublic interface BlockData { }")
f("org/bukkit/block/data/Ageable.java", "package org.bukkit.block.data;\npublic interface Ageable extends BlockData { int getAge(); int getMaximumAge(); }")

# ---------------------------------------------------------------- commands
f("org/bukkit/command/CommandSender.java", """package org.bukkit.command;
public interface CommandSender extends net.kyori.adventure.audience.Audience { boolean hasPermission(String name); String getName(); }""")
f("org/bukkit/command/ConsoleCommandSender.java", "package org.bukkit.command;\npublic interface ConsoleCommandSender extends CommandSender { }")
f("org/bukkit/command/Command.java", """package org.bukkit.command;
public abstract class Command {
    protected Command(String name) { }
    protected Command(String name, String description, String usageMessage, java.util.List<String> aliases) { }
    public abstract boolean execute(CommandSender sender, String commandLabel, String[] args);
    public java.util.List<String> tabComplete(CommandSender sender, String alias, String[] args) throws IllegalArgumentException { return java.util.List.of(); }
    public void setPermission(String permission) { }
}""")
f("org/bukkit/command/defaults/BukkitCommand.java", """package org.bukkit.command.defaults;
public abstract class BukkitCommand extends org.bukkit.command.Command {
    protected BukkitCommand(String name, String description, String usageMessage, java.util.List<String> aliases) { super(name, description, usageMessage, aliases); }
}""")
f("org/bukkit/command/CommandExecutor.java", """package org.bukkit.command;
public interface CommandExecutor { boolean onCommand(CommandSender sender, Command command, String label, String[] args); }""")
f("org/bukkit/command/TabCompleter.java", """package org.bukkit.command;
public interface TabCompleter { java.util.List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args); }""")
f("org/bukkit/command/TabExecutor.java", "package org.bukkit.command;\npublic interface TabExecutor extends TabCompleter, CommandExecutor { }")
f("org/bukkit/command/PluginCommand.java", """package org.bukkit.command;
public final class PluginCommand extends Command {
    PluginCommand() { super("stub"); }
    public boolean execute(CommandSender s, String l, String[] a) %s
    public void setExecutor(CommandExecutor executor) %s
    public void setTabCompleter(TabCompleter completer) %s
}""" % (S, S, S))
f("org/bukkit/command/CommandMap.java", """package org.bukkit.command;
public interface CommandMap { boolean register(String fallbackPrefix, Command command); Command getCommand(String name); }""")

# ---------------------------------------------------------------- configuration
cfg_methods = """
    java.util.Set<String> getKeys(boolean deep);
    boolean contains(String path);
    boolean isConfigurationSection(String path);
    Object get(String path);
    Object get(String path, Object def);
    String getString(String path);
    String getString(String path, String def);
    java.util.List<String> getStringList(String path);
    boolean getBoolean(String path);
    boolean getBoolean(String path, boolean def);
    int getInt(String path);
    int getInt(String path, int def);
    long getLong(String path, long def);
    double getDouble(String path);
    double getDouble(String path, double def);
    ConfigurationSection getConfigurationSection(String path);
    ConfigurationSection createSection(String path);"""
f("org/bukkit/configuration/ConfigurationSection.java", "package org.bukkit.configuration;\npublic interface ConfigurationSection {" + cfg_methods + "\n}")
f("org/bukkit/configuration/InvalidConfigurationException.java", """package org.bukkit.configuration;
public class InvalidConfigurationException extends Exception { public InvalidConfigurationException(String m) { super(m); } }""")
impl = "\n".join("    public " + line.strip().rstrip(";") + " " + S for line in cfg_methods.strip().splitlines())
f("org/bukkit/configuration/file/YamlConfiguration.java", """package org.bukkit.configuration.file;
import org.bukkit.configuration.ConfigurationSection;
public class YamlConfiguration implements ConfigurationSection {
    public YamlConfiguration() { }
    public void load(java.io.File file) throws java.io.IOException, org.bukkit.configuration.InvalidConfigurationException %s
    public void load(java.io.Reader reader) throws java.io.IOException, org.bukkit.configuration.InvalidConfigurationException %s
%s
}""" % (S, S, impl.replace("public java.util.Set", "public java.util.Set")))

# ---------------------------------------------------------------- entities
f("org/bukkit/persistence/PersistentDataHolder.java", """package org.bukkit.persistence;
public interface PersistentDataHolder { PersistentDataContainer getPersistentDataContainer(); }""")
f("org/bukkit/persistence/PersistentDataContainer.java", """package org.bukkit.persistence;
public interface PersistentDataContainer {
    <P, C> void set(org.bukkit.NamespacedKey key, PersistentDataType<P, C> type, C value);
    <P, C> boolean has(org.bukkit.NamespacedKey key, PersistentDataType<P, C> type);
    <P, C> C get(org.bukkit.NamespacedKey key, PersistentDataType<P, C> type);
    java.util.Set<org.bukkit.NamespacedKey> getKeys();
}""")
f("org/bukkit/persistence/PersistentDataType.java", """package org.bukkit.persistence;
public interface PersistentDataType<P, C> {
    PersistentDataType<Byte, Byte> BYTE = null;
    PersistentDataType<String, String> STRING = null;
}""")
f("org/bukkit/entity/Entity.java", """package org.bukkit.entity;
public interface Entity extends org.bukkit.persistence.PersistentDataHolder, org.bukkit.command.CommandSender {
    java.util.UUID getUniqueId(); EntityType getType(); org.bukkit.Location getLocation(); org.bukkit.World getWorld();
    boolean fromMobSpawner();
}""")
f("org/bukkit/entity/LivingEntity.java", "package org.bukkit.entity;\npublic interface LivingEntity extends Entity { Player getKiller(); }")
f("org/bukkit/entity/FallingBlock.java", "package org.bukkit.entity;\npublic interface FallingBlock extends Entity { }")
f("org/bukkit/entity/HumanEntity.java", """package org.bukkit.entity;
public interface HumanEntity extends LivingEntity {
    org.bukkit.inventory.InventoryView getOpenInventory();
    org.bukkit.inventory.InventoryView openInventory(org.bukkit.inventory.Inventory inventory);
    void closeInventory();
    org.bukkit.inventory.PlayerInventory getInventory();
    org.bukkit.GameMode getGameMode();
}""")
f("org/bukkit/entity/Player.java", """package org.bukkit.entity;
public interface Player extends HumanEntity, org.bukkit.OfflinePlayer {
    void playSound(org.bukkit.Location location, String sound, float volume, float pitch);
}""")
f("org/bukkit/entity/EntityType.java", "package org.bukkit.entity;\npublic enum EntityType { ZOMBIE, SKELETON, COW, PLAYER }")

# ---------------------------------------------------------------- events
f("org/bukkit/event/Event.java", """package org.bukkit.event;
public abstract class Event {
    public Event() { }
    public Event(boolean isAsync) { }
    public abstract HandlerList getHandlers();
}""")
f("org/bukkit/event/Cancellable.java", "package org.bukkit.event;\npublic interface Cancellable { boolean isCancelled(); void setCancelled(boolean cancel); }")
f("org/bukkit/event/Listener.java", "package org.bukkit.event;\npublic interface Listener { }")
f("org/bukkit/event/EventPriority.java", "package org.bukkit.event;\npublic enum EventPriority { LOWEST, LOW, NORMAL, HIGH, HIGHEST, MONITOR }")
f("org/bukkit/event/EventHandler.java", """package org.bukkit.event;
import java.lang.annotation.*;
@Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME)
public @interface EventHandler { EventPriority priority() default EventPriority.NORMAL; boolean ignoreCancelled() default false; }""")
f("org/bukkit/event/HandlerList.java", """package org.bukkit.event;
public class HandlerList { public HandlerList() { } public static void unregisterAll(Listener listener) { } }""")
f("org/bukkit/event/EventException.java", "package org.bukkit.event;\npublic class EventException extends Exception { }")
f("org/bukkit/plugin/EventExecutor.java", """package org.bukkit.plugin;
public interface EventExecutor { void execute(org.bukkit.event.Listener listener, org.bukkit.event.Event event) throws org.bukkit.event.EventException; }""")

def event(pkg, name, extends, members, cancellable=False, abstract=False):
    impl = " implements org.bukkit.event.Cancellable" if cancellable else ""
    canc = ("\n    public boolean isCancelled() %s\n    public void setCancelled(boolean c) %s" % (S, S)) if cancellable else ""
    body = "\n".join("    public %s %s" % (m, S) for m in members)
    f("org/bukkit/event/%s/%s.java" % (pkg, name), "package org.bukkit.event.%s;\npublic %sclass %s extends %s%s {\n    protected %s() { }\n%s%s\n    public org.bukkit.event.HandlerList getHandlers() %s\n}" % (
        pkg, "abstract " if abstract else "", name, extends, impl, name, body, canc, S))

event("block", "BlockEvent", "org.bukkit.event.Event", ["org.bukkit.block.Block getBlock()"], abstract=True)
event("block", "BlockBreakEvent", "BlockEvent", ["org.bukkit.entity.Player getPlayer()"], True)
event("block", "BlockPlaceEvent", "BlockEvent", ["org.bukkit.block.Block getBlockPlaced()", "org.bukkit.entity.Player getPlayer()"], True)
event("block", "BlockMultiPlaceEvent", "BlockPlaceEvent", ["java.util.List<org.bukkit.block.BlockState> getReplacedBlockStates()"])
event("block", "BlockGrowEvent", "BlockEvent", ["org.bukkit.block.BlockState getNewState()"], True)
event("block", "BlockFormEvent", "BlockGrowEvent", [])
event("block", "EntityBlockFormEvent", "BlockFormEvent", [])
event("block", "BlockSpreadEvent", "BlockFormEvent", [])
event("block", "BlockFertilizeEvent", "BlockEvent", ["java.util.List<org.bukkit.block.BlockState> getBlocks()"], True)
event("block", "BlockPistonEvent", "BlockEvent", ["org.bukkit.block.BlockFace getDirection()"], True, abstract=True)
event("block", "BlockPistonExtendEvent", "BlockPistonEvent", ["java.util.List<org.bukkit.block.Block> getBlocks()"])
event("block", "BlockPistonRetractEvent", "BlockPistonEvent", ["java.util.List<org.bukkit.block.Block> getBlocks()"])
event("block", "BlockExplodeEvent", "BlockEvent", ["java.util.List<org.bukkit.block.Block> blockList()"], True)
event("block", "BlockBurnEvent", "BlockEvent", [], True)
event("block", "BlockFadeEvent", "BlockEvent", [], True)
event("block", "LeavesDecayEvent", "BlockEvent", [], True)
event("entity", "EntityEvent", "org.bukkit.event.Event", ["org.bukkit.entity.Entity getEntity()"], abstract=True)
f("org/bukkit/event/entity/CreatureSpawnEvent.java", """package org.bukkit.event.entity;
public class CreatureSpawnEvent extends EntityEvent implements org.bukkit.event.Cancellable {
    protected CreatureSpawnEvent() { }
    public org.bukkit.entity.LivingEntity getEntity() %(S)s
    public SpawnReason getSpawnReason() %(S)s
    public boolean isCancelled() %(S)s
    public void setCancelled(boolean c) %(S)s
    public org.bukkit.event.HandlerList getHandlers() %(S)s
    public enum SpawnReason { NATURAL, SPAWNER, BREEDING, CUSTOM }
}""" % {"S": S})
event("entity", "EntityChangeBlockEvent", "EntityEvent", ["org.bukkit.block.Block getBlock()", "org.bukkit.Material getTo()"], True)
f("org/bukkit/event/entity/EntityDeathEvent.java", """package org.bukkit.event.entity;
public class EntityDeathEvent extends EntityEvent implements org.bukkit.event.Cancellable {
    protected EntityDeathEvent() { }
    public org.bukkit.entity.LivingEntity getEntity() %(S)s
    public boolean isCancelled() %(S)s
    public void setCancelled(boolean c) %(S)s
    public org.bukkit.event.HandlerList getHandlers() %(S)s
}""" % {"S": S})
event("entity", "EntityExplodeEvent", "EntityEvent", ["java.util.List<org.bukkit.block.Block> blockList()"], True)
event("world", "StructureGrowEvent", "org.bukkit.event.Event", ["java.util.List<org.bukkit.block.BlockState> getBlocks()"], True)
f("org/bukkit/event/player/PlayerEvent.java", """package org.bukkit.event.player;
public abstract class PlayerEvent extends org.bukkit.event.Event {
    protected org.bukkit.entity.Player player;
    public PlayerEvent(org.bukkit.entity.Player who) { this.player = who; }
    public final org.bukkit.entity.Player getPlayer() { return player; }
}""")
for name, members, canc in [("PlayerJoinEvent", [], False), ("PlayerQuitEvent", [], False),
                            ("PlayerGameModeChangeEvent", ["org.bukkit.GameMode getNewGameMode()"], True),
                            ("PlayerHarvestBlockEvent", ["org.bukkit.block.Block getHarvestedBlock()", "java.util.List<org.bukkit.inventory.ItemStack> getItemsHarvested()"], True)]:
    body = "\n".join("    public %s %s" % (m, S) for m in members)
    c = (" implements org.bukkit.event.Cancellable", "\n    public boolean isCancelled() %s\n    public void setCancelled(boolean c) %s" % (S, S)) if canc else ("", "")
    f("org/bukkit/event/player/%s.java" % name, "package org.bukkit.event.player;\npublic class %s extends PlayerEvent%s {\n    protected %s() { super(null); }\n%s%s\n    public org.bukkit.event.HandlerList getHandlers() %s\n}" % (name, c[0], name, body, c[1], S))
f("org/bukkit/event/server/PluginDisableEvent.java", """package org.bukkit.event.server;
public class PluginDisableEvent extends org.bukkit.event.Event {
    public org.bukkit.plugin.Plugin getPlugin() %(S)s
    public org.bukkit.event.HandlerList getHandlers() %(S)s
}""" % {"S": S})
for n in ["ServiceRegisterEvent", "ServiceUnregisterEvent"]:
    f("org/bukkit/event/server/%s.java" % n, """package org.bukkit.event.server;
public class %s extends org.bukkit.event.Event {
    public org.bukkit.plugin.RegisteredServiceProvider<?> getProvider() %s
    public org.bukkit.event.HandlerList getHandlers() %s
}""" % (n, S, S))

f("org/bukkit/event/inventory/ClickType.java", """package org.bukkit.event.inventory;
public enum ClickType { LEFT, SHIFT_LEFT, RIGHT, SHIFT_RIGHT, WINDOW_BORDER_LEFT, WINDOW_BORDER_RIGHT, MIDDLE, NUMBER_KEY, DOUBLE_CLICK, DROP, CONTROL_DROP, CREATIVE, SWAP_OFFHAND, UNKNOWN }""")
f("org/bukkit/event/inventory/InventoryAction.java", """package org.bukkit.event.inventory;
public enum InventoryAction { NOTHING, PICKUP_ALL, PICKUP_SOME, PICKUP_HALF, PICKUP_ONE, PLACE_ALL, PLACE_SOME, PLACE_ONE, SWAP_WITH_CURSOR, DROP_ALL_CURSOR, DROP_ONE_CURSOR, DROP_ALL_SLOT, DROP_ONE_SLOT, MOVE_TO_OTHER_INVENTORY, HOTBAR_SWAP, CLONE_STACK, COLLECT_TO_CURSOR, UNKNOWN }""")
f("org/bukkit/event/inventory/InventoryEvent.java", """package org.bukkit.event.inventory;
public abstract class InventoryEvent extends org.bukkit.event.Event {
    public org.bukkit.inventory.InventoryView getView() %s
}""" % S)
f("org/bukkit/event/inventory/InventoryInteractEvent.java", """package org.bukkit.event.inventory;
public abstract class InventoryInteractEvent extends InventoryEvent implements org.bukkit.event.Cancellable {
    public org.bukkit.entity.HumanEntity getWhoClicked() %(S)s
    public boolean isCancelled() %(S)s
    public void setCancelled(boolean c) %(S)s
}""" % {"S": S})
f("org/bukkit/event/inventory/InventoryClickEvent.java", """package org.bukkit.event.inventory;
public class InventoryClickEvent extends InventoryInteractEvent {
    public ClickType getClick() %(S)s
    public InventoryAction getAction() %(S)s
    public int getRawSlot() %(S)s
    public int getSlot() %(S)s
    public org.bukkit.inventory.Inventory getClickedInventory() %(S)s
    public org.bukkit.inventory.ItemStack getCurrentItem() %(S)s
    public void setCurrentItem(org.bukkit.inventory.ItemStack item) %(S)s
    public org.bukkit.event.HandlerList getHandlers() %(S)s
}""" % {"S": S})
f("org/bukkit/event/inventory/InventoryDragEvent.java", """package org.bukkit.event.inventory;
public class InventoryDragEvent extends InventoryInteractEvent {
    public java.util.Set<Integer> getRawSlots() %(S)s
    public org.bukkit.event.HandlerList getHandlers() %(S)s
}""" % {"S": S})
f("org/bukkit/event/inventory/InventoryCloseEvent.java", """package org.bukkit.event.inventory;
public class InventoryCloseEvent extends InventoryEvent {
    public org.bukkit.event.HandlerList getHandlers() %s
}""" % S)

# ---------------------------------------------------------------- inventory
f("org/bukkit/inventory/InventoryHolder.java", "package org.bukkit.inventory;\npublic interface InventoryHolder { Inventory getInventory(); }")
f("org/bukkit/inventory/Inventory.java", """package org.bukkit.inventory;
public interface Inventory { int getSize(); ItemStack getItem(int index); void setItem(int index, ItemStack item); void clear(); InventoryHolder getHolder(); }""")
f("org/bukkit/inventory/PlayerInventory.java", """package org.bukkit.inventory;
public interface PlayerInventory extends Inventory { java.util.HashMap<Integer, ItemStack> addItem(ItemStack... items) throws IllegalArgumentException; }""")
f("org/bukkit/inventory/InventoryView.java", "package org.bukkit.inventory;\npublic interface InventoryView { Inventory getTopInventory(); }")
f("org/bukkit/inventory/ItemFlag.java", "package org.bukkit.inventory;\npublic enum ItemFlag { HIDE_ENCHANTS, HIDE_ATTRIBUTES, HIDE_UNBREAKABLE }")
f("org/bukkit/inventory/ItemStack.java", """package org.bukkit.inventory;
public class ItemStack implements Cloneable {
    public ItemStack(org.bukkit.Material type) %(S)s
    public org.bukkit.Material getType() %(S)s
    public int getAmount() %(S)s
    public void setAmount(int amount) %(S)s
    public int getMaxStackSize() %(S)s
    public boolean hasItemMeta() %(S)s
    public org.bukkit.inventory.meta.ItemMeta getItemMeta() %(S)s
    public boolean setItemMeta(org.bukkit.inventory.meta.ItemMeta meta) %(S)s
    public boolean isSimilar(ItemStack stack) %(S)s
    public ItemStack clone() %(S)s
    public byte[] serializeAsBytes() %(S)s
    public static ItemStack deserializeBytes(byte[] bytes) %(S)s
}""" % {"S": S})
f("org/bukkit/inventory/Recipe.java", "package org.bukkit.inventory;\npublic interface Recipe { ItemStack getResult(); }")
f("org/bukkit/inventory/RecipeChoice.java", """package org.bukkit.inventory;
public interface RecipeChoice {
    class MaterialChoice implements RecipeChoice { public java.util.List<org.bukkit.Material> getChoices() %s }
}""" % S)
f("org/bukkit/inventory/ShapedRecipe.java", """package org.bukkit.inventory;
public class ShapedRecipe implements Recipe, org.bukkit.Keyed {
    public ItemStack getResult() %(S)s
    public org.bukkit.NamespacedKey getKey() %(S)s
    public String[] getShape() %(S)s
    public java.util.Map<Character, RecipeChoice> getChoiceMap() %(S)s
}""" % {"S": S})
f("org/bukkit/inventory/ShapelessRecipe.java", """package org.bukkit.inventory;
public class ShapelessRecipe implements Recipe, org.bukkit.Keyed {
    public ItemStack getResult() %(S)s
    public org.bukkit.NamespacedKey getKey() %(S)s
    public java.util.List<RecipeChoice> getChoiceList() %(S)s
}""" % {"S": S})
f("org/bukkit/inventory/CookingRecipe.java", """package org.bukkit.inventory;
public abstract class CookingRecipe<T extends CookingRecipe<T>> implements Recipe, org.bukkit.Keyed {
    public ItemStack getResult() %(S)s
    public org.bukkit.NamespacedKey getKey() %(S)s
    public RecipeChoice getInputChoice() %(S)s
}""" % {"S": S})
f("org/bukkit/inventory/StonecuttingRecipe.java", """package org.bukkit.inventory;
public class StonecuttingRecipe implements Recipe, org.bukkit.Keyed {
    public ItemStack getResult() %(S)s
    public org.bukkit.NamespacedKey getKey() %(S)s
    public RecipeChoice getInputChoice() %(S)s
}""" % {"S": S})
f("org/bukkit/inventory/meta/ItemMeta.java", """package org.bukkit.inventory.meta;
public interface ItemMeta extends org.bukkit.persistence.PersistentDataHolder {
    void displayName(net.kyori.adventure.text.Component name);
    void lore(java.util.List<? extends net.kyori.adventure.text.Component> lore);
    void addItemFlags(org.bukkit.inventory.ItemFlag... flags);
    boolean hasDisplayName(); boolean hasLore(); boolean hasEnchants();
}""")
f("org/bukkit/inventory/meta/BlockStateMeta.java", "package org.bukkit.inventory.meta;\npublic interface BlockStateMeta extends ItemMeta { boolean hasBlockState(); }")
f("org/bukkit/inventory/meta/BundleMeta.java", "package org.bukkit.inventory.meta;\npublic interface BundleMeta extends ItemMeta { boolean hasItems(); }")
f("org/bukkit/inventory/meta/Damageable.java", "package org.bukkit.inventory.meta;\npublic interface Damageable extends ItemMeta { boolean hasDamage(); }")

# ---------------------------------------------------------------- plugin & scheduler
f("org/bukkit/plugin/Plugin.java", """package org.bukkit.plugin;
public interface Plugin { String getName(); boolean isEnabled(); io.papermc.paper.plugin.configuration.PluginMeta getPluginMeta(); org.bukkit.Server getServer(); java.util.logging.Logger getLogger(); }""")
f("org/bukkit/plugin/PluginManager.java", """package org.bukkit.plugin;
public interface PluginManager {
    void registerEvents(org.bukkit.event.Listener listener, Plugin plugin);
    void registerEvent(Class<? extends org.bukkit.event.Event> event, org.bukkit.event.Listener listener, org.bukkit.event.EventPriority priority, EventExecutor executor, Plugin plugin, boolean ignoreCancelled);
    void callEvent(org.bukkit.event.Event event) throws IllegalStateException;
    Plugin getPlugin(String name);
    boolean isPluginEnabled(String name);
    void disablePlugin(Plugin plugin);
}""")
f("org/bukkit/plugin/ServicePriority.java", "package org.bukkit.plugin;\npublic enum ServicePriority { Lowest, Low, Normal, High, Highest }")
f("org/bukkit/plugin/RegisteredServiceProvider.java", """package org.bukkit.plugin;
public class RegisteredServiceProvider<T> {
    public Class<T> getService() %(S)s
    public Plugin getPlugin() %(S)s
    public T getProvider() %(S)s
}""" % {"S": S})
f("org/bukkit/plugin/ServicesManager.java", """package org.bukkit.plugin;
public interface ServicesManager {
    <T> void register(Class<T> service, T provider, Plugin plugin, ServicePriority priority);
    void unregisterAll(Plugin plugin);
    <T> RegisteredServiceProvider<T> getRegistration(Class<T> service);
    <T> java.util.Collection<RegisteredServiceProvider<T>> getRegistrations(Class<T> service);
    java.util.Collection<Class<?>> getKnownServices();
    <T> T load(Class<T> service);
}""")
f("org/bukkit/plugin/java/JavaPlugin.java", """package org.bukkit.plugin.java;
public abstract class JavaPlugin implements org.bukkit.plugin.Plugin {
    public java.io.File getDataFolder() %(S)s
    public java.util.logging.Logger getLogger() %(S)s
    public org.bukkit.Server getServer() %(S)s
    public void saveResource(String resourcePath, boolean replace) %(S)s
    public java.io.InputStream getResource(String filename) %(S)s
    public org.bukkit.command.PluginCommand getCommand(String name) %(S)s
    public io.papermc.paper.plugin.configuration.PluginMeta getPluginMeta() %(S)s
    public final String getName() %(S)s
    public final boolean isEnabled() %(S)s
    public void onEnable() { }
    public void onDisable() { }
}""" % {"S": S})
f("org/bukkit/scheduler/BukkitTask.java", "package org.bukkit.scheduler;\npublic interface BukkitTask { void cancel(); }")
f("org/bukkit/scheduler/BukkitScheduler.java", """package org.bukkit.scheduler;
public interface BukkitScheduler {
    BukkitTask runTask(org.bukkit.plugin.Plugin plugin, Runnable task);
    BukkitTask runTaskLater(org.bukkit.plugin.Plugin plugin, Runnable task, long delay);
    BukkitTask runTaskTimer(org.bukkit.plugin.Plugin plugin, Runnable task, long delay, long period);
}""")

# ---------------------------------------------------------------- ExcellentEconomy 2.8.0 (subset, exact signatures)
f("su/nightexpress/excellenteconomy/api/ExcellentEconomyAPI.java", """package su.nightexpress.excellenteconomy.api;
import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;
import su.nightexpress.excellenteconomy.api.currency.operation.OperationResult;
public interface ExcellentEconomyAPI {
    boolean canPerformOperations();
    boolean hasCurrency(String id);
    java.util.Set<ExcellentCurrency> getCurrencies();
    ExcellentCurrency getCurrency(String id);
    java.util.Optional<ExcellentCurrency> currencyById(String id);
    java.util.concurrent.CompletableFuture<Double> getBalanceAsync(java.util.UUID playerId, ExcellentCurrency currency);
    double getBalance(org.bukkit.entity.Player player, ExcellentCurrency currency);
    java.util.concurrent.CompletableFuture<OperationResult> depositAsync(java.util.UUID playerId, ExcellentCurrency currency, double amount);
    boolean deposit(org.bukkit.entity.Player player, ExcellentCurrency currency, double amount);
    java.util.concurrent.CompletableFuture<OperationResult> withdrawAsync(java.util.UUID playerId, ExcellentCurrency currency, double amount);
    boolean withdraw(org.bukkit.entity.Player player, ExcellentCurrency currency, double amount);
}""")
f("su/nightexpress/excellenteconomy/api/currency/ExcellentCurrency.java", """package su.nightexpress.excellenteconomy.api.currency;
public interface ExcellentCurrency {
    String getId(); String getName(); boolean isDecimal(); boolean isInteger(); boolean isLimited(); boolean isUnlimited();
    double getMaxValue(); String format(double balance); double floorIfNeeded(double amount);
}""")
f("su/nightexpress/excellenteconomy/api/currency/operation/OperationResult.java", """package su.nightexpress.excellenteconomy.api.currency.operation;
public enum OperationResult { SUCCESS, FAILURE; public boolean success() { return this == SUCCESS; } }""")

# ---------------------------------------------------------------- VaultAPI 1.7 (subset, exact signatures)
f("net/milkbowl/vault/economy/Economy.java", """package net.milkbowl.vault.economy;
public interface Economy {
    boolean isEnabled(); String getName(); int fractionalDigits(); String format(double amount);
    double getBalance(org.bukkit.OfflinePlayer player);
    boolean has(org.bukkit.OfflinePlayer player, double amount);
    EconomyResponse withdrawPlayer(org.bukkit.OfflinePlayer player, double amount);
    EconomyResponse depositPlayer(org.bukkit.OfflinePlayer player, double amount);
}""")
f("net/milkbowl/vault/economy/EconomyResponse.java", """package net.milkbowl.vault.economy;
public class EconomyResponse {
    public final double amount; public final double balance; public final String errorMessage;
    public EconomyResponse(double amount, double balance, Object type, String errorMessage) { this.amount = amount; this.balance = balance; this.errorMessage = errorMessage; }
    public boolean transactionSuccess() %s
}""" % S)

# ---------------------------------------------------------------- PlaceholderAPI
f("me/clip/placeholderapi/expansion/PlaceholderExpansion.java", """package me.clip.placeholderapi.expansion;
public abstract class PlaceholderExpansion {
    public abstract String getIdentifier();
    public abstract String getAuthor();
    public abstract String getVersion();
    public boolean persist() { return false; }
    public String onRequest(org.bukkit.OfflinePlayer player, String params) { return null; }
    public boolean register() %s
}""" % S)

for path, body in files.items():
    full = os.path.join(ROOT, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w") as out:
        out.write("// Compile-only stub; see stubs/generate_stubs.py. Not shipped.\n" + body + "\n")
print(f"wrote {len(files)} stub files to {ROOT}")
