package dev.exodaily.paper.command;

import dev.exodaily.core.config.ConfigLoader;
import dev.exodaily.core.progression.PlayerProfile;
import dev.exodaily.core.reward.RewardPosition;
import dev.exodaily.core.service.AdminService;
import dev.exodaily.core.storage.AssignmentRecord;
import dev.exodaily.core.storage.ClaimRecord;
import dev.exodaily.paper.ExoDailyPlugin;
import dev.exodaily.paper.Messenger;
import dev.exodaily.paper.Permissions;
import dev.exodaily.paper.Presentation;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.IntStream;

/** {@code /exodaily}: administration. Every sub-command requires {@code exodaily.admin}. */
public final class AdminCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS =
            List.of("help", "reload", "status", "setday", "reset", "reward", "pending", "resolve");

    private final ExoDailyPlugin plugin;
    private final Messenger messenger;

    public AdminCommand(ExoDailyPlugin plugin, Messenger messenger) {
        this.plugin = plugin;
        this.messenger = messenger;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        if (!sender.hasPermission(Permissions.ADMIN)) {
            messenger.send(sender, "no-permission");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            messenger.sendList(sender, "help");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (!sub.equals("reload") && !sub.equals("help") && !plugin.storageReady()) {
            messenger.send(sender, "storage-unavailable");
            return true;
        }
        switch (sub) {
            case "reload" -> plugin.reload(sender);
            case "status" -> {
                if (args.length != 2) {
                    usage(sender, "/exodaily status <player>");
                    return true;
                }
                resolveTarget(sender, args[1], (uuid, name) -> status(sender, uuid, name));
            }
            case "setday" -> {
                if (args.length != 3) {
                    usage(sender, "/exodaily setday <player> <day>");
                    return true;
                }
                int day;
                try {
                    day = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    usage(sender, "/exodaily setday <player> <day>");
                    return true;
                }
                resolveTarget(sender, args[1], (uuid, name) -> setDay(sender, uuid, name, day));
            }
            case "reset" -> {
                if (args.length < 2 || args.length > 3) {
                    usage(sender, "/exodaily reset <player> confirm");
                    return true;
                }
                if (args.length == 2 || !args[2].equalsIgnoreCase("confirm")) {
                    messenger.send(sender, "admin-reset-warning", Placeholder.unparsed("player", args[1]));
                    return true;
                }
                resolveTarget(sender, args[1], (uuid, name) -> reset(sender, uuid, name));
            }
            case "reward" -> {
                if (args.length != 3 || !args[1].equalsIgnoreCase("save")) {
                    usage(sender, "/exodaily reward save <id>");
                    return true;
                }
                saveReward(sender, args[2]);
            }
            case "pending" -> pending(sender);
            case "resolve" -> {
                if (args.length != 3) {
                    usage(sender, "/exodaily resolve <claim-id> <delivered|release>");
                    return true;
                }
                long id;
                try {
                    id = Long.parseLong(args[1].replace("#", ""));
                } catch (NumberFormatException e) {
                    usage(sender, "/exodaily resolve <claim-id> <delivered|release>");
                    return true;
                }
                String mode = args[2].toLowerCase(Locale.ROOT);
                if (!mode.equals("delivered") && !mode.equals("release")) {
                    usage(sender, "/exodaily resolve <claim-id> <delivered|release>");
                    return true;
                }
                resolve(sender, id, mode.equals("delivered"));
            }
            default -> messenger.sendList(sender, "help");
        }
        return true;
    }

    private void usage(CommandSender sender, String usage) {
        messenger.send(sender, "usage", Placeholder.unparsed("usage", usage));
    }

    private static String actor(CommandSender sender) {
        return sender instanceof Player player ? player.getName() + " (" + player.getUniqueId() + ")" : sender.getName();
    }

    // ------------------------------------------------------------------ target lookup

    private void resolveTarget(CommandSender sender, String name, BiConsumer<UUID, String> then) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            then.accept(online.getUniqueId(), online.getName());
            return;
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null) {
            then.accept(cached.getUniqueId(), cached.getName() != null ? cached.getName() : name);
            return;
        }
        plugin.storage(() -> plugin.admin().findByName(name), found -> {
            if (found.isPresent()) {
                then.accept(found.get(), name);
            } else {
                messenger.send(sender, "unknown-player", Placeholder.unparsed("player", name));
            }
        }, sender);
    }

    // ------------------------------------------------------------------ status

    private void status(CommandSender sender, UUID uuid, String name) {
        plugin.storage(() -> plugin.admin().status(uuid), result -> {
            if (result.isEmpty()) {
                messenger.send(sender, "admin-no-data", Placeholder.unparsed("player", name));
                return;
            }
            AdminService.Status status = result.get();
            Presentation p = plugin.presentation();
            PlayerProfile profile = status.profile();
            TagResolver base = TagResolver.resolver(
                    Placeholder.unparsed("player", status.lastName() != null ? status.lastName() : name),
                    Placeholder.unparsed("uuid", uuid.toString()),
                    Placeholder.unparsed("cycle", Integer.toString(status.state().cycleNumber())),
                    Placeholder.unparsed("day", Integer.toString(status.state().day())),
                    Placeholder.unparsed("cycle-length", Integer.toString(status.state().cycleLength())),
                    Placeholder.unparsed("cycle-start", status.state().cycleStart().toString()),
                    Placeholder.unparsed("first-use", profile.firstUseDate().toString()),
                    Placeholder.unparsed("premium", Boolean.toString(isPremium(uuid))));
            messenger.send(sender, "admin-status-header", base);
            messenger.send(sender, "admin-status-cycle", base);
            if (status.assignments().isEmpty()) {
                messenger.send(sender, "admin-status-not-assigned", base);
            }
            for (RewardPosition position : RewardPosition.values()) {
                AssignmentRecord assignment = status.assignments().get(position);
                if (assignment == null) {
                    continue;
                }
                ClaimRecord claim = status.claims().get(position);
                String state = claim == null ? "unclaimed" : claim.state().name().toLowerCase(Locale.ROOT);
                messenger.send(sender, "admin-status-position", base,
                        Placeholder.unparsed("position", Integer.toString(position.number())),
                        Placeholder.unparsed("reward-id", assignment.reward().id()),
                        Placeholder.component("reward", p.styler().render(assignment.reward().summary())),
                        Placeholder.unparsed("state", state));
            }
            for (ClaimRecord claim : status.uncertain()) {
                messenger.send(sender, "admin-status-uncertain", claimResolver(claim));
            }
        }, sender);
    }

    private static boolean isPremium(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        return player != null && player.hasPermission(Permissions.PREMIUM);
    }

    private static TagResolver claimResolver(ClaimRecord claim) {
        return TagResolver.resolver(
                Placeholder.unparsed("id", Long.toString(claim.id())),
                Placeholder.unparsed("uuid", claim.uuid().toString()),
                Placeholder.unparsed("cycle", Integer.toString(claim.cycle())),
                Placeholder.unparsed("day", Integer.toString(claim.day())),
                Placeholder.unparsed("position", Integer.toString(claim.position().number())),
                Placeholder.unparsed("date", claim.rewardDate().toString()),
                Placeholder.unparsed("reward-id", claim.rewardId()),
                Placeholder.unparsed("note", claim.note() == null ? "" : claim.note()));
    }

    // ------------------------------------------------------------------ mutations

    private void setDay(CommandSender sender, UUID uuid, String name, int day) {
        int max = plugin.presentation().config().settings().cycleLength();
        if (day < 1 || day > max) {
            messenger.send(sender, "admin-setday-invalid", Placeholder.unparsed("max", Integer.toString(max)));
            return;
        }
        plugin.storage(() -> {
            try {
                return Optional.of(plugin.admin().setDay(uuid, name, day, actor(sender)));
            } catch (IllegalArgumentException e) {
                return Optional.<PlayerProfile>empty();
            }
        }, result -> {
            if (result.isEmpty()) {
                messenger.send(sender, "admin-setday-invalid", Placeholder.unparsed("max", Integer.toString(max)));
                return;
            }
            plugin.menus().invalidate(uuid, "menu-admin-updated");
            messenger.send(sender, "admin-setday-success", Placeholder.unparsed("player", name),
                    Placeholder.unparsed("day", Integer.toString(day)),
                    Placeholder.unparsed("cycle", Integer.toString(result.get().cycleNumber())));
        }, sender);
    }

    private void reset(CommandSender sender, UUID uuid, String name) {
        plugin.storage(() -> plugin.admin().reset(uuid, name, actor(sender)), result -> {
            if (result.isEmpty()) {
                messenger.send(sender, "admin-no-data", Placeholder.unparsed("player", name));
                return;
            }
            plugin.menus().invalidate(uuid, "menu-admin-updated");
            messenger.send(sender, "admin-reset-success", Placeholder.unparsed("player", name),
                    Placeholder.unparsed("cycle", Integer.toString(result.get().cycleNumber())));
        }, sender);
    }

    private void pending(CommandSender sender) {
        plugin.storage(() -> plugin.admin().uncertain(10), claims -> {
            if (claims.isEmpty()) {
                messenger.send(sender, "admin-pending-none");
                return;
            }
            messenger.send(sender, "admin-pending-header", Placeholder.unparsed("count", Integer.toString(claims.size())));
            for (ClaimRecord claim : claims) {
                messenger.send(sender, "admin-pending-line", claimResolver(claim));
            }
        }, sender);
    }

    private void resolve(CommandSender sender, long id, boolean delivered) {
        plugin.storage(() -> plugin.admin().resolve(id, delivered, actor(sender)), result -> {
            if (result.isEmpty()) {
                messenger.send(sender, "admin-resolve-not-found", Placeholder.unparsed("id", Long.toString(id)));
                return;
            }
            plugin.menus().invalidate(result.get().uuid(), "menu-admin-updated");
            messenger.send(sender, delivered ? "admin-resolve-delivered" : "admin-resolve-released", claimResolver(result.get()));
        }, sender);
    }

    private void saveReward(CommandSender sender, String id) {
        if (!(sender instanceof Player player)) {
            messenger.send(sender, "players-only");
            return;
        }
        if (!ConfigLoader.ID_PATTERN.matcher(id).matches()) {
            messenger.send(sender, "admin-reward-invalid-id", Placeholder.unparsed("id", id));
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.isEmpty()) {
            messenger.send(sender, "admin-reward-no-item");
            return;
        }
        plugin.saveRewardFromItem(player, id, item.clone());
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
                                      @NotNull String[] args) {
        if (!sender.hasPermission(Permissions.ADMIN)) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (sub) {
                case "status", "setday", "reset" -> filter(onlineNames(), args[1]);
                case "reward" -> filter(List.of("save"), args[1]);
                default -> List.of();
            };
        }
        if (args.length == 3) {
            return switch (sub) {
                case "setday" -> {
                    Presentation p = plugin.presentation();
                    int max = p == null ? 30 : p.config().settings().cycleLength();
                    yield filter(IntStream.rangeClosed(1, max).mapToObj(Integer::toString).toList(), args[2]);
                }
                case "reset" -> filter(List.of("confirm"), args[2]);
                case "reward" -> {
                    Presentation p = plugin.presentation();
                    yield p == null ? List.of() : filter(new ArrayList<>(p.config().rewards().rewards().keySet()), args[2]);
                }
                case "resolve" -> filter(List.of("delivered", "release"), args[2]);
                default -> List.of();
            };
        }
        return List.of();
    }

    private static List<String> onlineNames() {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.add(player.getName());
        }
        return names;
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower)).sorted().toList();
    }
}
