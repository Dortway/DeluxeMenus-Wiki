package com.exoblacksmith.command;

import com.exoblacksmith.Services;
import com.exoblacksmith.config.ConfigLoader;
import com.exoblacksmith.config.Registry;
import com.exoblacksmith.config.model.HeadDef;
import com.exoblacksmith.config.model.ItemDef;
import com.exoblacksmith.integration.HeadDrops;
import com.exoblacksmith.item.ItemData;
import com.exoblacksmith.item.ItemService;
import com.exoblacksmith.util.Roman;
import com.exoblacksmith.util.Text;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * {@code /exoblacksmith give|givehead|drop|reload|validate|list|inspect}. Every sub-command except
 * {@code inspect} works from the console, command blocks and other plugins (dungeon rewards).
 * Player arguments accept a name, a UUID or a vanilla selector such as {@code @p}.
 */
public final class AdminCommand implements TabExecutor {
    private static final int MAX_GIVE = 36 * 64;

    private final Services services;
    private final HeadDrops drops;
    private final Supplier<ConfigLoader.Result> reloader;

    public AdminCommand(Services services, HeadDrops drops, Supplier<ConfigLoader.Result> reloader) {
        this.services = services;
        this.drops = drops;
        this.reloader = reloader;
    }

    private Registry reg() {
        return services.reg();
    }

    private boolean allowed(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        Text.send(sender, reg(), "no-permission");
        return false;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            Text.send(sender, reg(), "usage-admin");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give" -> give(sender, args, false);
            case "givehead" -> give(sender, args, true);
            case "drop" -> drop(sender, args);
            case "reload" -> reload(sender);
            case "validate" -> validate(sender);
            case "list" -> list(sender);
            case "inspect" -> inspect(sender);
            default -> Text.send(sender, reg(), "usage-admin");
        }
        return true;
    }

    private List<Player> players(CommandSender sender, String arg) {
        List<Player> out = new ArrayList<>();
        if (arg.startsWith("@")) {
            try {
                for (Entity e : Bukkit.selectEntities(sender, arg)) {
                    if (e instanceof Player p) {
                        out.add(p);
                    }
                }
            } catch (IllegalArgumentException ignored) {
                // invalid selector: fall through to "not found"
            }
            return out;
        }
        Player p = Bukkit.getPlayerExact(arg);
        if (p == null) {
            try {
                p = Bukkit.getPlayer(java.util.UUID.fromString(arg));
            } catch (IllegalArgumentException ignored) {
                // not a uuid
            }
        }
        if (p != null) {
            out.add(p);
        }
        return out;
    }

    private void give(CommandSender sender, String[] args, boolean headsOnly) {
        if (!allowed(sender, headsOnly ? "exoblacksmith.admin.givehead" : "exoblacksmith.admin.give")) {
            return;
        }
        if (args.length < 3) {
            Text.send(sender, reg(), headsOnly ? "usage-givehead" : "usage-give");
            return;
        }
        List<Player> targets = players(sender, args[1]);
        if (targets.isEmpty()) {
            Text.send(sender, reg(), "player-not-found", Map.of("player", args[1]));
            return;
        }
        String id = args[2].toLowerCase(Locale.ROOT);
        ItemDef def = headsOnly ? reg().head(id) : reg().item(id);
        if (def == null) {
            Text.send(sender, reg(), "unknown-item", Map.of("item", args[2]));
            return;
        }
        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                amount = -1;
            }
            if (amount < 1 || amount > MAX_GIVE) {
                Text.send(sender, reg(), "invalid-amount", Map.of("max", Integer.toString(MAX_GIVE)));
                return;
            }
        }
        int tier = 1;
        if (!headsOnly && args.length >= 5) {
            try {
                tier = Integer.parseInt(args[4]);
            } catch (NumberFormatException e) {
                tier = -1;
            }
            if (tier < 1 || tier > def.maxLevel()) {
                Text.send(sender, reg(), "invalid-tier", Map.of("item", id, "max", Integer.toString(def.maxLevel())));
                return;
            }
        }
        if (def.kind().unique() && amount > 36) {
            Text.send(sender, reg(), "invalid-amount", Map.of("max", "36"));
            return;
        }
        for (Player target : targets) {
            int overflow = deliver(target, def, tier, amount);
            String name = Text.plainName(def.name()) + (def.maxLevel() > 1 ? " " + Roman.of(tier) : "");
            Text.send(sender, reg(), "given", Map.of("player", target.getName(), "item", name, "amount", Integer.toString(amount)));
            if (!sender.equals(target)) {
                Text.send(target, reg(), "received", Map.of("item", name, "amount", Integer.toString(amount)));
            }
            if (overflow > 0) {
                Text.send(target, reg(), "inventory-overflow", Map.of("amount", Integer.toString(overflow)));
            }
            services.plugin().getLogger().info("[give] " + sender.getName() + " gave " + target.getName() + " "
                    + amount + "x " + id + " t" + tier);
        }
    }

    /** Gives items; anything that does not fit is dropped at the player's feet. Returns the dropped count. */
    private int deliver(Player target, ItemDef def, int tier, int amount) {
        ItemService items = services.items();
        List<ItemStack> stacks = new ArrayList<>();
        if (def.kind().unique()) {
            for (int i = 0; i < amount; i++) {
                stacks.add(items.create(def, tier, 1));
            }
        } else {
            int left = amount;
            while (left > 0) {
                ItemStack stack = items.create(def, tier, 1);
                int n = Math.min(left, stack.getMaxStackSize());
                stack.setAmount(n);
                stacks.add(stack);
                left -= n;
            }
        }
        int dropped = 0;
        for (ItemStack overflow : target.getInventory().addItem(stacks.toArray(new ItemStack[0])).values()) {
            dropped += overflow.getAmount();
            target.getWorld().dropItemNaturally(target.getLocation(), overflow);
        }
        return dropped;
    }

    private void drop(CommandSender sender, String[] args) {
        if (!allowed(sender, "exoblacksmith.admin.drop")) {
            return;
        }
        if (args.length < 3) {
            Text.send(sender, reg(), "usage-drop");
            return;
        }
        List<Player> targets = players(sender, args[1]);
        if (targets.isEmpty()) {
            Text.send(sender, reg(), "player-not-found", Map.of("player", args[1]));
            return;
        }
        EntityType type;
        try {
            type = EntityType.valueOf(args[2].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            Text.send(sender, reg(), "usage-drop");
            return;
        }
        int deaths = 1;
        if (args.length >= 4) {
            try {
                deaths = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                deaths = -1;
            }
            if (deaths < 1 || deaths > 10_000) {
                Text.send(sender, reg(), "invalid-amount", Map.of("max", "10000"));
                return;
            }
        }
        boolean spawner = args.length >= 5 && args[4].equalsIgnoreCase("spawner");
        for (Player target : targets) {
            int granted = drops.grant(target, type, deaths, spawner, target.getLocation());
            Text.send(sender, reg(), "drop-granted", Map.of("player", target.getName(), "amount", Integer.toString(granted),
                    "mob", type.name().toLowerCase(Locale.ROOT), "deaths", Integer.toString(deaths)));
        }
    }

    private void reload(CommandSender sender) {
        if (!allowed(sender, "exoblacksmith.admin.reload")) {
            return;
        }
        ConfigLoader.Result result = reloader.get();
        if (result.ok()) {
            Text.send(sender, reg(), "reload-success", Map.of("warnings", Integer.toString(result.warnings().size())));
            for (String warning : result.warnings()) {
                sender.sendMessage(net.kyori.adventure.text.Component.text("  ⚠ " + warning,
                        net.kyori.adventure.text.format.NamedTextColor.YELLOW));
            }
        } else {
            Text.send(sender, reg(), "reload-failed", Map.of("errors", Integer.toString(result.errors().size())));
            for (String error : result.errors()) {
                sender.sendMessage(net.kyori.adventure.text.Component.text("  ✘ " + error,
                        net.kyori.adventure.text.format.NamedTextColor.RED));
            }
        }
    }

    private void validate(CommandSender sender) {
        if (!allowed(sender, "exoblacksmith.admin.reload")) {
            return;
        }
        Registry r = reg();
        sender.sendMessage(net.kyori.adventure.text.Component.text("ExoBlackSmith: " + r.items().size() + " items, "
                + r.recipes().size() + " recipes; all required outputs are craftable and reachable (checked at load).",
                net.kyori.adventure.text.format.NamedTextColor.GREEN));
    }

    private void list(CommandSender sender) {
        if (!allowed(sender, "exoblacksmith.admin.give")) {
            return;
        }
        Map<String, List<String>> byKind = new java.util.TreeMap<>();
        for (ItemDef def : reg().items()) {
            byKind.computeIfAbsent(def.kind().name().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                    .add(def.id() + (def.maxLevel() > 1 ? " (1-" + def.maxLevel() + ")" : ""));
        }
        byKind.forEach((kind, ids) -> sender.sendMessage(net.kyori.adventure.text.Component.text(kind + ": " + String.join(", ", ids),
                net.kyori.adventure.text.format.NamedTextColor.GRAY)));
    }

    private void inspect(CommandSender sender) {
        if (!allowed(sender, "exoblacksmith.admin.inspect")) {
            return;
        }
        if (!(sender instanceof Player player)) {
            Text.send(sender, reg(), "player-only");
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        ItemData data = services.items().read(held);
        ItemService.Identified identified = services.items().identify(held);
        if (data == null) {
            Text.send(player, reg(), services.items().isTagged(held) ? "retired-item" : "inspect-none");
            return;
        }
        Text.send(player, reg(), "inspect", Map.of("id", data.id(), "kind", data.kind().name().toLowerCase(Locale.ROOT),
                "level", Integer.toString(data.level()), "uid", data.uid() == null ? "-" : data.uid(),
                "runes", data.runes().isEmpty() ? "-" : com.exoblacksmith.item.RuneSlot.encode(data.runes()),
                "valid", identified != null ? "authentic" : "rejected (unknown id, retired uid or stacked unique)"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        Set<String> options = new LinkedHashSet<>();
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            options.addAll(List.of("give", "givehead", "drop", "reload", "validate", "list", "inspect"));
        } else if (args.length == 2 && List.of("give", "givehead", "drop").contains(sub)) {
            Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
            options.add("@p");
        } else if (args.length == 3 && sub.equals("give")) {
            reg().items().forEach(d -> options.add(d.id()));
        } else if (args.length == 3 && sub.equals("givehead")) {
            reg().heads().forEach(h -> options.add(h.id()));
        } else if (args.length == 3 && sub.equals("drop")) {
            for (HeadDef head : reg().heads()) {
                if (head.mob() != null) {
                    options.add(head.mob().name().toLowerCase(Locale.ROOT));
                }
            }
        } else if (args.length == 4 && List.of("give", "givehead").contains(sub)) {
            options.addAll(List.of("1", "15", "64", "120"));
        } else if (args.length == 4 && sub.equals("drop")) {
            options.addAll(List.of("1", "10"));
        } else if (args.length == 5 && sub.equals("give")) {
            ItemDef def = reg().item(args[2].toLowerCase(Locale.ROOT));
            if (def != null) {
                for (int i = 1; i <= def.maxLevel(); i++) {
                    options.add(Integer.toString(i));
                }
            }
        } else if (args.length == 5 && sub.equals("drop")) {
            options.addAll(List.of("natural", "spawner"));
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(option);
            }
        }
        return out;
    }
}
