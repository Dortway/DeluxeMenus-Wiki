package net.exoeconomy.command;

import net.exoeconomy.ExoEconomyPlugin;
import net.exoeconomy.api.BoosterInfo;
import net.exoeconomy.api.BoosterScope;
import net.exoeconomy.booster.BoosterService;
import net.exoeconomy.config.BoosterSettings;
import net.exoeconomy.config.Messages;
import net.exoeconomy.core.Money;
import net.exoeconomy.core.booster.BoosterSpec;
import net.exoeconomy.core.tx.TxKind;
import net.exoeconomy.core.tx.TxRecord;
import net.exoeconomy.core.tx.TxState;
import net.exoeconomy.sell.PriceAudit;
import net.exoeconomy.storage.Database;
import net.exoeconomy.util.Props;
import net.exoeconomy.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.Consumer;

/**
 * /exoeconomy (alias /exoeco): help and info for everyone; admin tools for boosters, transactions,
 * claims, the price audit and reloading. Database reads run on the storage thread and reply on the
 * server thread. Resolving a transaction requires a trailing "confirm" and only moves records that
 * are still in the state the admin inspected.
 */
public final class ExoEconomyCommand implements TabExecutor {
    private final ExoEconomyPlugin plugin;

    public ExoEconomyCommand(ExoEconomyPlugin plugin) { this.plugin = plugin; }

    private Messages msg() { return plugin.settings().messages(); }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("exoeconomy.use")) { msg().send(sender, "no-permission"); return true; }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "help" -> help(sender, label);
                case "info" -> info(sender);
                case "reload" -> { if (perm(sender, "exoeconomy.admin.reload")) reload(sender); }
                case "booster", "boosters" -> { if (perm(sender, "exoeconomy.admin.boosters")) booster(sender, args); }
                case "tx", "transaction", "transactions" -> { if (perm(sender, "exoeconomy.admin.transactions")) tx(sender, args); }
                case "claims", "claim" -> { if (perm(sender, "exoeconomy.admin.claims")) claims(sender, args); }
                case "audit" -> { if (perm(sender, "exoeconomy.admin.audit")) audit(sender); }
                case "storage" -> { if (perm(sender, "exoeconomy.admin")) storage(sender); }
                default -> help(sender, label);
            }
        } catch (IllegalArgumentException e) {
            msg().send(sender, "command-error", "error", e.getMessage());
        }
        return true;
    }

    private boolean perm(CommandSender sender, String node) {
        if (sender.hasPermission(node) || sender.hasPermission("exoeconomy.admin")) return true;
        msg().send(sender, "no-permission");
        return false;
    }

    private void help(CommandSender sender, String label) {
        for (String line : msg().lines("help.player")) sender.sendMessage(Text.color(line.replace("%label%", label)));
        if (sender.hasPermission("exoeconomy.admin") || sender.hasPermission("exoeconomy.admin.boosters") || sender.hasPermission("exoeconomy.admin.transactions"))
            for (String line : msg().lines("help.admin")) sender.sendMessage(Text.color(line.replace("%label%", label)));
    }

    private void info(CommandSender sender) {
        var ledger = plugin.ledger();
        msg().send(sender, "info", "version", plugin.getPluginMeta().getVersion(),
                "economy", ledger.available() ? "connected (ExcellentEconomy " + plugin.economyVersion() + ")" : "UNAVAILABLE: " + ledger.unavailableReason(),
                "currency", ledger.currencyId(), "scale", String.valueOf(ledger.scale()), "sell_currency", plugin.sellCurrencySummary(),
                "storage", plugin.database().healthy() ? "healthy (queue " + plugin.database().queued() + ")" : "FAILED: " + plugin.database().failure(),
                "boosters", String.valueOf(plugin.boosters().book().snapshot().size()),
                "prices", String.valueOf(plugin.settings().catalogue().size()),
                "provenance", plugin.rewards().provenance().loaded() ? plugin.rewards().provenance().size() + " blocks" : "loading",
                "integrations", plugin.integrationSummary());
    }

    private void reload(CommandSender sender) {
        List<String> result = plugin.reload();
        if (result == null) msg().send(sender, "reloaded");
        else {
            msg().send(sender, "reload-failed");
            result.forEach(p -> sender.sendMessage(Text.color("&c - " + p)));
        }
    }

    private void storage(CommandSender sender) {
        plugin.database().probe().thenAccept(ok -> Bukkit.getScheduler().runTask(plugin, () ->
                msg().send(sender, ok ? "storage-recovered" : "storage-still-failed")));
    }

    // ------------------------------------------------------------------ boosters

    private void booster(CommandSender sender, String[] args) {
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
        switch (action) {
            case "list" -> {
                UUID filter = args.length > 2 && !args[2].equalsIgnoreCase("global") ? player(args[2]).getUniqueId() : null;
                boolean globalOnly = args.length > 2 && args[2].equalsIgnoreCase("global");
                List<BoosterInfo> list = plugin.boosters().book().snapshot().stream()
                        .filter(b -> globalOnly ? b.scope() == BoosterScope.GLOBAL : filter == null || filter.equals(b.owner())).toList();
                msg().send(sender, "admin-booster-header", "count", String.valueOf(list.size()));
                for (BoosterInfo b : list) msg().send(sender, "admin-booster-line", plugin.boosters().values(b, null));
            }
            case "give" -> {
                if (args.length < 4) throw new IllegalArgumentException("/exoeco booster give <player|global> <tier>");
                BoosterSettings.Tier tier = plugin.settings().boosters().tiers().get(args[3]);
                if (tier == null) throw new IllegalArgumentException("Unknown tier " + args[3]);
                grant(sender, args[2], tier.spec());
            }
            case "givecustom" -> {
                if (args.length < 6) throw new IllegalArgumentException("/exoeco booster givecustom <player|global> <activity> <multiplier> <duration>");
                boolean global = args[2].equalsIgnoreCase("global");
                BoosterSpec spec = new BoosterSpec("admin", global ? BoosterScope.GLOBAL : BoosterScope.PERSONAL, args[3],
                        Money.multiplier(args[4], "multiplier"), Text.parseDuration(args[5]), BigDecimal.ZERO);
                grant(sender, args[2], spec);
            }
            case "cancel" -> {
                if (args.length < 3) throw new IllegalArgumentException("/exoeco booster cancel <id> [refund]");
                BoosterInfo b = plugin.boosters().find(args[2]).orElseThrow(() -> new IllegalArgumentException("No unique booster matches " + args[2]));
                boolean refund = args.length > 3 && args[3].equalsIgnoreCase("refund");
                if (!refund) {
                    plugin.boosters().cancel(b.id());
                    msg().send(sender, "admin-booster-cancelled", "id", BoosterService.shortId(b.id()));
                    return;
                }
                plugin.boosters().sourceTx(b.id()).whenComplete((source, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null || source == null || source.isEmpty()) { msg().send(sender, "admin-no-source-tx"); return; }
                    plugin.database().transaction(source.get()).whenComplete((rec, e2) -> Bukkit.getScheduler().runTask(plugin, () -> {
                        if (e2 != null || rec == null || rec.isEmpty() || rec.get().state() != TxState.COMMITTED) { msg().send(sender, "admin-no-source-tx"); return; }
                        TxRecord purchase = rec.get();
                        plugin.boosters().cancel(b.id());
                        plugin.coordinator().credit(TxKind.REFUND, purchase.id(), purchase.player(), purchase.amount(),
                                Props.of("reason", "booster cancelled by " + sender.getName(), "booster", b.id()),
                                (state, why) -> msg().send(sender, "admin-refund-result", "state", String.valueOf(state), "reason", why == null ? "" : why));
                    }));
                }));
            }
            default -> throw new IllegalArgumentException("/exoeco booster <list|give|givecustom|cancel>");
        }
    }

    private void grant(CommandSender sender, String target, BoosterSpec spec) {
        UUID owner = null;
        if (spec.scope() == BoosterScope.PERSONAL) {
            if (target.equalsIgnoreCase("global")) throw new IllegalArgumentException("That tier is personal; name a player");
            owner = player(target).getUniqueId();
        } else if (!target.equalsIgnoreCase("global")) {
            throw new IllegalArgumentException("That tier is global; use 'global'");
        }
        Object result = plugin.boosters().grant(spec, owner, null, sender.getName());
        if (result instanceof BoosterInfo info) msg().send(sender, "admin-booster-granted", plugin.boosters().values(info, sender.getName()));
        else msg().send(sender, String.valueOf(result));
    }

    private static OfflinePlayer player(String name) {
        try {
            return Bukkit.getOfflinePlayer(UUID.fromString(name));
        } catch (IllegalArgumentException ignored) { }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached == null) throw new IllegalArgumentException("Unknown player " + name + " (use an online name or a UUID)");
        return cached;
    }

    // ------------------------------------------------------------------ transactions

    private void tx(CommandSender sender, String[] args) {
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
        switch (action) {
            case "list" -> {
                Set<TxState> states = EnumSet.of(TxState.UNCERTAIN, TxState.PENDING);
                UUID player = null;
                for (int i = 2; i < args.length; i++) {
                    switch (args[i].toLowerCase(Locale.ROOT)) {
                        case "all" -> states = null;
                        case "uncertain" -> states = EnumSet.of(TxState.UNCERTAIN);
                        case "recent" -> states = null;
                        default -> player = player(args[i]).getUniqueId();
                    }
                }
                plugin.database().transactions(player, states, 20).whenComplete((list, error) -> reply(sender, error, () -> {
                    msg().send(sender, "admin-tx-header", "count", String.valueOf(list.size()));
                    for (TxRecord r : list) msg().send(sender, "admin-tx-line", txValues(r));
                }));
            }
            case "inspect" -> {
                if (args.length < 3) throw new IllegalArgumentException("/exoeco tx inspect <id>");
                withTx(sender, args[2], r -> plugin.database().children(r.id()).whenComplete((children, error) -> reply(sender, error, () -> {
                    msg().send(sender, "admin-tx-detail", txValues(r));
                    Props.read(r.details()).forEach((k, v) -> sender.sendMessage(Text.color("&7  " + k + ": &f" + v)));
                    for (TxRecord c : children) msg().send(sender, "admin-tx-child", txValues(c));
                    if (r.state() == TxState.UNCERTAIN) msg().send(sender, "admin-tx-resolve-hint", "id", BoosterService.shortId(r.id()),
                            "actions", String.join("|", actions(r.kind())));
                })));
            }
            case "resolve" -> {
                if (args.length < 5 || !args[args.length - 1].equalsIgnoreCase("confirm"))
                    throw new IllegalArgumentException("/exoeco tx resolve <id> <action> confirm");
                String how = args[3].toLowerCase(Locale.ROOT);
                withTx(sender, args[2], r -> resolve(sender, r, how));
            }
            default -> throw new IllegalArgumentException("/exoeco tx <list|inspect|resolve>");
        }
    }

    private static List<String> actions(TxKind kind) {
        return switch (kind) {
            case BOOSTER_PURCHASE -> List.of("charged", "not-charged", "refund");
            case SALE -> List.of("paid", "not-paid", "pay");
            case REFUND, PAYOUT -> List.of("paid", "not-paid");
        };
    }

    private void withTx(CommandSender sender, String id, Consumer<TxRecord> action) {
        plugin.database().transaction(id).whenComplete((found, error) -> reply(sender, error, () -> {
            if (found.isEmpty()) msg().send(sender, "admin-tx-not-found", "id", id);
            else action.accept(found.get());
        }));
    }

    /**
     * Admin decisions for an UNCERTAIN transaction. The admin checks ExcellentEconomy's
     * operations log or the player's balance history first; ExoEconomy never guesses.
     */
    private void resolve(CommandSender sender, TxRecord r, String how) {
        if (r.state() != TxState.UNCERTAIN) { msg().send(sender, "admin-tx-not-uncertain", "state", r.state().name()); return; }
        if (!actions(r.kind()).contains(how)) throw new IllegalArgumentException("Actions for " + r.kind() + ": " + actions(r.kind()));
        String note = "resolved by " + sender.getName() + ": " + how;
        Map<String, String> details = Props.read(r.details());
        Database db = plugin.database();
        Consumer<Throwable> done = error -> reply(sender, error, () -> msg().send(sender, "admin-tx-resolved", "id", BoosterService.shortId(r.id()), "action", how));
        switch (r.kind()) {
            case BOOSTER_PURCHASE -> {
                switch (how) {
                    case "charged" -> db.write(List.of(Database.resolve(r.id(), TxState.UNCERTAIN, TxState.RESOLVED_COMPLETED, note)))
                            .whenComplete((ok, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                                if (error != null) { done.accept(error); return; }
                                Object result = plugin.boosters().grantResolved(details, r.id());
                                if (result instanceof BoosterInfo) done.accept(null);
                                else msg().send(sender, "admin-tx-booster-refused", "reason", String.valueOf(result), "id", BoosterService.shortId(r.id()));
                            }));
                    case "not-charged" -> db.write(List.of(Database.resolve(r.id(), TxState.UNCERTAIN, TxState.RESOLVED_FAILED, note))).whenComplete((ok, e) -> done.accept(e));
                    case "refund" -> db.write(List.of(Database.resolve(r.id(), TxState.UNCERTAIN, TxState.RESOLVED_COMPLETED, note + " (refund issued)")))
                            .whenComplete((ok, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                                if (error != null) { done.accept(error); return; }
                                plugin.coordinator().credit(TxKind.REFUND, r.id(), r.player(), r.amount(), Props.of("reason", note),
                                        (state, why) -> msg().send(sender, "admin-refund-result", "state", String.valueOf(state), "reason", why == null ? "" : why));
                            }));
                    default -> { }
                }
            }
            case SALE -> {
                switch (how) {
                    case "paid" -> db.write(List.of(Database.resolve(r.id(), TxState.UNCERTAIN, TxState.RESOLVED_COMPLETED, note),
                            Database.deleteClaimsByTx(r.id(), Database.CLAIM_REVIEW))).whenComplete((ok, e) -> done.accept(e));
                    case "not-paid" -> db.write(List.of(Database.resolve(r.id(), TxState.UNCERTAIN, TxState.RESOLVED_FAILED, note),
                            Database.setClaimStateByTx(r.id(), Database.CLAIM_REVIEW, Database.CLAIM_CLAIMABLE))).whenComplete((ok, e) -> {
                        done.accept(e);
                        if (e == null) deliverIfOnline(r.player());
                    });
                    case "pay" -> plugin.sellCoordinator().credit(TxKind.PAYOUT, r.id(), r.player(), r.amount(), Props.of("reason", note), (state, why) -> {
                        msg().send(sender, "admin-refund-result", "state", String.valueOf(state), "reason", why == null ? "" : why);
                        if (state == TxState.COMMITTED)
                            db.write(List.of(Database.resolve(r.id(), TxState.UNCERTAIN, TxState.RESOLVED_COMPLETED, note + " (paid now)"),
                                    Database.deleteClaimsByTx(r.id(), Database.CLAIM_REVIEW))).whenComplete((ok, e) -> done.accept(e));
                    });
                    default -> { }
                }
            }
            case REFUND, PAYOUT -> db.write(List.of(Database.resolve(r.id(), TxState.UNCERTAIN,
                    how.equals("paid") ? TxState.RESOLVED_COMPLETED : TxState.RESOLVED_FAILED, note))).whenComplete((ok, e) -> done.accept(e));
        }
    }

    private void deliverIfOnline(UUID id) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(id);
            if (p != null) plugin.sell().deliverClaims(p, true);
        });
    }

    private String[] txValues(TxRecord r) {
        OfflinePlayer p = Bukkit.getOfflinePlayer(r.player());
        return new String[]{"id", BoosterService.shortId(r.id()), "full_id", r.id(), "kind", r.kind().name(), "state", r.state().name(),
                "amount", Money.plain(r.amount()), "currency", r.currency(), "player", p.getName() == null ? r.player().toString() : p.getName(),
                "time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(r.createdAt())),
                "before", r.balanceBefore() == null ? "-" : Money.plain(r.balanceBefore()),
                "after", r.balanceAfter() == null ? "-" : Money.plain(r.balanceAfter()),
                "parent", r.parent() == null ? "-" : BoosterService.shortId(r.parent()), "note", r.note() == null ? "" : r.note()};
    }

    // ------------------------------------------------------------------ claims

    private void claims(CommandSender sender, String[] args) {
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
        switch (action) {
            case "list" -> {
                UUID player = args.length > 2 && !args[2].equalsIgnoreCase("review") ? player(args[2]).getUniqueId() : null;
                String state = Arrays.stream(args).anyMatch(a -> a.equalsIgnoreCase("review")) ? Database.CLAIM_REVIEW : null;
                plugin.database().claims(player, state).whenComplete((list, error) -> reply(sender, error, () -> {
                    msg().send(sender, "admin-claims-header", "count", String.valueOf(list.size()));
                    for (Database.Claim c : list) {
                        OfflinePlayer p = Bukkit.getOfflinePlayer(c.player());
                        msg().send(sender, "admin-claim-line", "id", BoosterService.shortId(c.id()), "player", p.getName() == null ? c.player().toString() : p.getName(),
                                "state", c.state(), "reason", c.reason(), "tx", c.tx() == null ? "-" : BoosterService.shortId(c.tx()), "items", c.summary());
                    }
                }));
            }
            case "approve", "discard" -> {
                if (args.length < 3) throw new IllegalArgumentException("/exoeco claims " + action + " <id>" + (action.equals("discard") ? " confirm" : ""));
                if (action.equals("discard") && (args.length < 4 || !args[3].equalsIgnoreCase("confirm")))
                    throw new IllegalArgumentException("/exoeco claims discard <id> confirm");
                plugin.database().claims(null, Database.CLAIM_REVIEW).thenCombine(plugin.database().claims(null, Database.CLAIM_CLAIMABLE), (a, b) -> {
                    List<Database.Claim> all = new ArrayList<>(a);
                    all.addAll(b);
                    return all;
                }).whenComplete((all, error) -> reply(sender, error, () -> {
                    List<Database.Claim> match = all.stream().filter(c -> c.id().startsWith(args[2])).toList();
                    if (match.size() != 1) { msg().send(sender, "admin-claim-not-found", "id", args[2]); return; }
                    Database.Claim c = match.get(0);
                    if (c.tx() != null && action.equals("approve") && c.state().equals(Database.CLAIM_REVIEW)) {
                        msg().send(sender, "admin-claim-linked", "tx", BoosterService.shortId(c.tx()));
                        return;
                    }
                    var work = action.equals("approve")
                            ? Database.setClaimState(c.id(), Database.CLAIM_REVIEW, Database.CLAIM_CLAIMABLE)
                            : Database.deleteClaim(c.id(), c.state());
                    plugin.database().write(List.of(work)).whenComplete((ok, e) -> reply(sender, e, () -> {
                        msg().send(sender, action.equals("approve") ? "admin-claim-approved" : "admin-claim-discarded", "id", BoosterService.shortId(c.id()));
                        if (action.equals("approve")) deliverIfOnline(c.player());
                        else plugin.getLogger().info(sender.getName() + " discarded claim " + c.id() + " of " + c.player() + ": " + c.summary());
                    }));
                }));
            }
            default -> throw new IllegalArgumentException("/exoeco claims <list|approve|discard>");
        }
    }

    // ------------------------------------------------------------------ audit

    private void audit(CommandSender sender) {
        List<PriceAudit.Finding> findings = PriceAudit.run(plugin.settings().catalogue());
        msg().send(sender, findings.isEmpty() ? "audit-clean" : "audit-findings", "count", String.valueOf(findings.size()));
        findings.stream().limit(15).forEach(f -> msg().send(sender, "audit-line", "recipe", f.recipe(), "item", f.output().name(),
                "output", Money.plain(f.outputValue().setScale(4, java.math.RoundingMode.DOWN)), "input", Money.plain(f.inputValue().setScale(4, java.math.RoundingMode.DOWN))));
    }

    private void reply(CommandSender sender, Throwable error, Runnable action) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) msg().send(sender, "command-error", "error", String.valueOf(error.getCause() != null ? error.getCause().getMessage() : error.getMessage()));
            else action.run();
        });
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        boolean admin = sender.hasPermission("exoeconomy.admin");
        if (args.length == 1) {
            options.addAll(List.of("help", "info"));
            if (admin || sender.hasPermission("exoeconomy.admin.reload")) options.add("reload");
            if (admin || sender.hasPermission("exoeconomy.admin.boosters")) options.add("booster");
            if (admin || sender.hasPermission("exoeconomy.admin.transactions")) options.add("tx");
            if (admin || sender.hasPermission("exoeconomy.admin.claims")) options.add("claims");
            if (admin || sender.hasPermission("exoeconomy.admin.audit")) options.add("audit");
            if (admin) options.add("storage");
        } else if (args[0].equalsIgnoreCase("booster") && (admin || sender.hasPermission("exoeconomy.admin.boosters"))) {
            if (args.length == 2) options.addAll(List.of("list", "give", "givecustom", "cancel"));
            else if (args.length == 3 && !args[1].equalsIgnoreCase("cancel")) {
                options.add("global");
                Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
            } else if (args.length == 3) plugin.boosters().book().snapshot().forEach(b -> options.add(BoosterService.shortId(b.id())));
            else if (args.length == 4 && args[1].equalsIgnoreCase("give")) options.addAll(plugin.settings().boosters().tiers().keySet());
            else if (args.length == 4 && args[1].equalsIgnoreCase("givecustom")) { options.add(BoosterSpec.ALL); options.addAll(plugin.rewards().activityIds()); }
            else if (args.length == 4 && args[1].equalsIgnoreCase("cancel")) options.add("refund");
            else if (args.length == 5 && args[1].equalsIgnoreCase("givecustom")) options.addAll(List.of("1.5", "2", "3"));
            else if (args.length == 6 && args[1].equalsIgnoreCase("givecustom")) options.addAll(List.of("15m", "30m", "1h"));
        } else if (args[0].equalsIgnoreCase("tx") && (admin || sender.hasPermission("exoeconomy.admin.transactions"))) {
            if (args.length == 2) options.addAll(List.of("list", "inspect", "resolve"));
            else if (args.length == 3 && args[1].equalsIgnoreCase("list")) options.addAll(List.of("uncertain", "all", "recent"));
            else if (args.length == 4 && args[1].equalsIgnoreCase("resolve")) options.addAll(List.of("charged", "not-charged", "refund", "paid", "not-paid", "pay"));
            else if (args.length == 5 && args[1].equalsIgnoreCase("resolve")) options.add("confirm");
        } else if (args[0].equalsIgnoreCase("claims") && (admin || sender.hasPermission("exoeconomy.admin.claims"))) {
            if (args.length == 2) options.addAll(List.of("list", "approve", "discard"));
            else if (args.length == 3 && args[1].equalsIgnoreCase("list")) options.add("review");
            else if (args.length == 4 && args[1].equalsIgnoreCase("discard")) options.add("confirm");
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(last)).distinct().toList();
    }
}
