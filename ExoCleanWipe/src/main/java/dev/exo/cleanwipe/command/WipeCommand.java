package dev.exo.cleanwipe.command;

import dev.exo.cleanwipe.config.Settings;
import dev.exo.cleanwipe.identity.LinkReport;
import dev.exo.cleanwipe.punish.PunishmentService;
import dev.exo.cleanwipe.storage.AuditLog;
import dev.exo.cleanwipe.storage.CoreStore;
import dev.exo.cleanwipe.storage.Records.Job;
import dev.exo.cleanwipe.storage.Records.Lock;
import dev.exo.cleanwipe.storage.Records.Punishment;
import dev.exo.cleanwipe.storage.Records.Step;
import dev.exo.cleanwipe.storage.Records.StepState;
import dev.exo.cleanwipe.storage.Records.Target;
import dev.exo.cleanwipe.text.Messages;
import dev.exo.cleanwipe.util.Durations;
import dev.exo.cleanwipe.util.Inputs;
import dev.exo.cleanwipe.wipe.ActionService;
import dev.exo.cleanwipe.wipe.ActionService.Resolved;
import dev.exo.cleanwipe.wipe.Integration;
import dev.exo.cleanwipe.wipe.IntegrationRegistry;
import dev.exo.cleanwipe.wipe.JobEngine;
import dev.exo.cleanwipe.wipe.WipeOptions;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * {@code /wipe} and {@code /wipeall}. Every sub-command checks its own permission node; names are validated before
 * any lookup and reasons are cleaned; nothing typed by staff is ever passed to another command or into SQL text.
 */
public final class WipeCommand implements TabExecutor {

    private record Sub(String name, String permission, String usage, String description) {
    }

    private static final List<Sub> SUBS = List.of(
            new Sub("<player>", "exocleanwipe.wipe", "/wipe <player> all [+alt:<name>...] [--shared|--keep-shared]",
                    "wipe all possessions and progress (no ban)"),
            new Sub("punish", "exocleanwipe.punish", "/wipe punish <player> <duration> <reason...>", "wipe and suspend (e.g. 30days)"),
            new Sub("ippunish", "exocleanwipe.ippunish", "/wipe ippunish <player> [reason...]", "permanent account and address ban (no wipe)"),
            new Sub("unpunish", "exocleanwipe.unpunish", "/wipe unpunish <player>", "remove account suspensions (keeps ip punishments)"),
            new Sub("unippunish", "exocleanwipe.unippunish", "/wipe unippunish <player>", "remove the ip punishment and its restrictions"),
            new Sub("links", "exocleanwipe.links", "/wipe links <player>", "accounts seen on the same addresses"),
            new Sub("info", "exocleanwipe.links", "/wipe info <player>", "punishments, locks and name history"),
            new Sub("confirm", "exocleanwipe.use", "/wipe confirm <code> [wipeall]", "confirm a previewed action"),
            new Sub("cancel", "exocleanwipe.use", "/wipe cancel <code>", "cancel a preview"),
            new Sub("status", "exocleanwipe.status", "/wipe status [job]", "job progress and how to resolve problems"),
            new Sub("jobs", "exocleanwipe.status", "/wipe jobs", "recent jobs"),
            new Sub("integrations", "exocleanwipe.status", "/wipe integrations", "what is covered on this server"),
            new Sub("retry", "exocleanwipe.retry", "/wipe retry <job>", "re-run failed or blocked steps"),
            new Sub("unlock", "exocleanwipe.unlock", "/wipe unlock <player>", "release a lock left by a partial job"),
            new Sub("restore", "exocleanwipe.restore", "/wipe restore <job>", "put a job's backups back"));

    private final Plugin plugin;
    private final Settings settings;
    private final Messages messages;
    private final CoreStore store;
    private final ActionService actions;
    private final PunishmentService punishments;
    private final JobEngine engine;
    private final IntegrationRegistry registry;
    private final AuditLog audit;

    public WipeCommand(Plugin plugin, Settings settings, Messages messages, CoreStore store, ActionService actions,
                       PunishmentService punishments, JobEngine engine, IntegrationRegistry registry, AuditLog audit) {
        this.plugin = plugin;
        this.settings = settings;
        this.messages = messages;
        this.store = store;
        this.actions = actions;
        this.punishments = punishments;
        this.engine = engine;
        this.registry = registry;
        this.audit = audit;
    }

    private boolean deny(CommandSender s, String permission) {
        if (!s.hasPermission(permission)) {
            messages.send(s, "no-permission");
            return true;
        }
        return false;
    }

    private void usage(CommandSender s, String usage) {
        messages.send(s, "usage", Messages.values("usage", usage));
    }

    private void line(CommandSender s, String template, Object... values) {
        s.sendMessage(messages.line(template, Messages.values(values)));
    }

    private Optional<Resolved> target(CommandSender s, String input) {
        if (!Inputs.isValidName(input)) {
            try {
                UUID.fromString(input);
            } catch (IllegalArgumentException e) {
                messages.send(s, "invalid-name", Messages.values("name", input));
                return Optional.empty();
            }
        }
        Optional<Resolved> r = actions.resolve(input);
        if (r.isEmpty()) {
            messages.send(s, "unknown-player", Messages.values("name", input));
        }
        return r;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            if (command.getName().equalsIgnoreCase("wipeall")) {
                if (deny(sender, "exocleanwipe.wipeall")) {
                    return true;
                }
                if (args.length != 0) {
                    usage(sender, "/wipeall");
                    return true;
                }
                actions.previewWipeAll(sender);
                return true;
            }
            if (deny(sender, "exocleanwipe.use")) {
                return true;
            }
            if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
                help(sender);
                return true;
            }
            String sub = args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "punish" -> punish(sender, args);
                case "ippunish" -> ipPunish(sender, args);
                case "unpunish" -> unpunish(sender, args);
                case "unippunish" -> unippunish(sender, args);
                case "links" -> links(sender, args);
                case "info" -> info(sender, args);
                case "confirm" -> {
                    if (args.length < 2 || args.length > 3 || !Inputs.isValidToken(args[1].toUpperCase(Locale.ROOT))) {
                        usage(sender, "/wipe confirm <code> [wipeall]");
                    } else {
                        actions.confirm(sender, args[1].toUpperCase(Locale.ROOT), args.length == 3 ? args[2] : null);
                    }
                }
                case "cancel" -> {
                    if (args.length != 2 || !Inputs.isValidToken(args[1].toUpperCase(Locale.ROOT))) {
                        usage(sender, "/wipe cancel <code>");
                    } else {
                        actions.cancel(sender, args[1].toUpperCase(Locale.ROOT));
                    }
                }
                case "status" -> status(sender, args);
                case "jobs" -> jobs(sender);
                case "integrations" -> integrations(sender);
                case "retry" -> retry(sender, args);
                case "unlock" -> unlock(sender, args);
                case "restore" -> restore(sender, args);
                default -> wipe(sender, args);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "/wipe failed", e);
            line(sender, "<red>internal error: <e>. see the console.</red>", "e", String.valueOf(e.getMessage()));
        }
        return true;
    }

    private void help(CommandSender s) {
        line(s, "<accent>exocleanwipe</accent> <gray><sym:arrow> commands you can use</gray>");
        for (Sub sub : SUBS) {
            if (s.hasPermission(sub.permission())) {
                line(s, " <white><usage></white> <gray><sym:dot> <desc></gray>", "usage", sub.usage(), "desc", sub.description());
            }
        }
        if (s.hasPermission("exocleanwipe.wipeall")) {
            line(s, " <white>/wipeall</white> <gray><sym:dot> season reset of every player (no bans)</gray>");
        }
        line(s, "<gray>durations: 30days, 12hours, 45minutes, 1d12h, permanent. nothing changes before /wipe confirm.</gray>");
        line(s, "<gray>limits: items already handed to other accounts are not traced; an address is not proof of identity.</gray>");
    }

    // ------------------------------------------------------------------ wipe / punish

    /** Splits "+alt:name" and "--flag" tokens from the rest. */
    private record Parsed(List<String> words, List<String> alts, boolean shared, boolean keepShared, String error) {
    }

    private static Parsed parse(String[] args, int from) {
        List<String> words = new ArrayList<>();
        List<String> alts = new ArrayList<>();
        boolean shared = false;
        boolean keep = false;
        for (int i = from; i < args.length; i++) {
            String a = args[i];
            String lower = a.toLowerCase(Locale.ROOT);
            if (lower.startsWith("+alt:")) {
                alts.add(a.substring(5));
            } else if (lower.equals("--shared")) {
                shared = true;
            } else if (lower.equals("--keep-shared")) {
                keep = true;
            } else if (lower.startsWith("--")) {
                return new Parsed(words, alts, shared, keep, "unknown option " + a);
            } else {
                words.add(a);
            }
        }
        if (shared && keep) {
            return new Parsed(words, alts, shared, keep, "use either --shared or --keep-shared, not both");
        }
        return new Parsed(words, alts, shared, keep, null);
    }

    private Optional<List<Resolved>> resolveAlts(CommandSender s, List<String> alts) {
        if (alts.isEmpty()) {
            return Optional.of(List.of());
        }
        if (deny(s, "exocleanwipe.wipe.linked")) {
            return Optional.empty();
        }
        List<Resolved> out = new ArrayList<>();
        for (String a : alts) {
            Optional<Resolved> r = target(s, a);
            if (r.isEmpty()) {
                return Optional.empty();
            }
            out.add(r.get());
        }
        return Optional.of(out);
    }

    private void wipe(CommandSender s, String[] args) {
        if (deny(s, "exocleanwipe.wipe")) {
            return;
        }
        Parsed p = parse(args, 1);
        if (p.error() != null) {
            line(s, "<red><e></red>", "e", p.error());
            return;
        }
        if (p.words().size() != 1 || !p.words().get(0).equalsIgnoreCase("all")) {
            usage(s, "/wipe <player> all [+alt:<name>...] [--shared|--keep-shared]");
            return;
        }
        Optional<Resolved> t = target(s, args[0]);
        Optional<List<Resolved>> alts = t.isEmpty() ? Optional.empty() : resolveAlts(s, p.alts());
        if (t.isEmpty() || alts.isEmpty()) {
            return;
        }
        actions.previewWipe(s, ActionService.Kind.WIPE, t.get(), alts.get(), new WipeOptions(p.shared(), p.keepShared(), 0), null, "");
    }

    private void punish(CommandSender s, String[] args) {
        if (deny(s, "exocleanwipe.punish")) {
            return;
        }
        Parsed p = parse(args, 1);
        if (p.error() != null) {
            line(s, "<red><e></red>", "e", p.error());
            return;
        }
        if (p.words().size() < 3) {
            usage(s, "/wipe punish <player> <duration> <reason...>");
            return;
        }
        Duration d;
        try {
            d = Durations.parse(p.words().get(1), settings.maxPunish);
        } catch (IllegalArgumentException e) {
            messages.send(s, "invalid-duration", Messages.values("error", e.getMessage()));
            return;
        }
        String reason = Inputs.cleanReason(String.join(" ", p.words().subList(2, p.words().size())), settings.reasonMax);
        if (reason.isEmpty()) {
            messages.send(s, "missing-reason");
            return;
        }
        Optional<Resolved> t = target(s, p.words().get(0));
        Optional<List<Resolved>> alts = t.isEmpty() ? Optional.empty() : resolveAlts(s, p.alts());
        if (t.isEmpty() || alts.isEmpty()) {
            return;
        }
        actions.previewWipe(s, ActionService.Kind.PUNISH, t.get(), alts.get(), new WipeOptions(p.shared(), p.keepShared(), 0), d, reason);
    }

    private void ipPunish(CommandSender s, String[] args) {
        if (deny(s, "exocleanwipe.ippunish")) {
            return;
        }
        if (args.length < 2) {
            usage(s, "/wipe ippunish <player> [reason...]");
            return;
        }
        String reason = Inputs.cleanReason(String.join(" ", Arrays.copyOfRange(args, 2, args.length)), settings.reasonMax);
        if (reason.isEmpty()) {
            reason = "ban evasion";
        }
        Optional<Resolved> t = target(s, args[1]);
        if (t.isPresent()) {
            actions.previewIpPunish(s, t.get(), reason);
        }
    }

    private void unpunish(CommandSender s, String[] args) throws Exception {
        if (deny(s, "exocleanwipe.unpunish")) {
            return;
        }
        if (args.length != 2) {
            usage(s, "/wipe unpunish <player>");
            return;
        }
        Optional<Resolved> t = target(s, args[1]);
        if (t.isEmpty()) {
            return;
        }
        int n = punishments.removeAccount(t.get().uuid(), s.getName(), System.currentTimeMillis());
        audit.record(s.getName(), "unpunish", t.get().name() + "=" + t.get().uuid(), "", n > 0 ? "removed " + n : "nothing", "");
        if (n == 0) {
            messages.send(s, "nothing-to-remove", Messages.values("name", t.get().name(), "what", "account suspension"));
        } else {
            messages.send(s, "unpunished", Messages.values("name", t.get().name(), "count", n));
        }
        explainRemainingBlocks(s, t.get());
    }

    /**
     * After an unpunish, tells staff everything that still keeps the player out, so "unpunished but still can't join"
     * is never a mystery: the wipe lock of a /wipe punish job, an ip punishment, a season reset, a vanilla /ban.
     */
    private void explainRemainingBlocks(CommandSender s, Resolved t) throws Exception {
        long now = System.currentTimeMillis();
        List<String> blocks = new ArrayList<>();
        Optional<Lock> lock = store.lockOf(t.uuid());
        if (lock.isPresent()) {
            long jobId = lock.get().jobId();
            if (jobId == ActionService.FREEZE_JOB) {
                blocks.add("frozen by an open preview (cancel it with /wipe cancel <code>, or it expires by itself)");
            } else {
                String state = store.job(jobId).map(j -> j.state().name().toLowerCase(Locale.ROOT).replace('_', ' ')).orElse("unknown");
                String why = switch (state) {
                    case "waiting restart" -> "its wipe finishes at the next server restart";
                    case "partial" -> "its wipe stopped with problems (see /wipe status " + jobId + ")";
                    default -> "its wipe is still running (see /wipe status " + jobId + ")";
                };
                blocks.add("locked by job " + jobId + ": " + why + ". restart (or fix and /wipe retry " + jobId
                        + "), or /wipe unlock " + t.name() + " to let them in now (data not wiped yet can then be moved before the restart)");
            }
        }
        if (store.lockOf(null).isPresent()) {
            blocks.add("a season reset is running; nobody can join until it finishes");
        }
        for (Punishment p : store.activePunishments(t.uuid())) {
            if (p.inForce(now) && p.kind() == dev.exo.cleanwipe.storage.Records.PunishmentKind.IP_GROUP) {
                blocks.add("ip punishment " + punishments.reference(p.id()) + " still bans this account; remove it with /wipe unippunish " + t.name());
            }
        }
        try {
            var vanilla = Bukkit.getBanList(org.bukkit.BanList.Type.PROFILE);
            if (vanilla != null && Bukkit.getOfflinePlayer(t.uuid()).isBanned()) {
                blocks.add("also banned by vanilla /ban or another plugin (not exocleanwipe); use /pardon " + t.name() + " or that plugin");
            }
        } catch (Exception | LinkageError ignored) {
            // vanilla ban list unavailable (tests); nothing to report
        }
        if (blocks.isEmpty()) {
            line(s, "<gray><name> can join again.</gray>", "name", t.name());
            return;
        }
        line(s, "<red><sym:warn> <name> still cannot join:</red>", "name", t.name());
        for (String b : blocks) {
            line(s, " <gray><sym:dot> <b></gray>", "b", b);
        }
    }

    private void unippunish(CommandSender s, String[] args) throws Exception {
        if (deny(s, "exocleanwipe.unippunish")) {
            return;
        }
        if (args.length != 2) {
            usage(s, "/wipe unippunish <player>");
            return;
        }
        Optional<Resolved> t = target(s, args[1]);
        if (t.isEmpty()) {
            return;
        }
        PunishmentService.IpRemoval r = punishments.removeIpGroup(t.get().uuid(), s.getName(), System.currentTimeMillis());
        audit.record(s.getName(), "unippunish", t.get().name() + "=" + t.get().uuid(), "",
                r.punishments() > 0 ? "removed " + r.punishments() : "nothing", r.addresses() + " addresses, " + r.released() + " released");
        if (r.punishments() == 0) {
            messages.send(s, "nothing-to-remove", Messages.values("name", t.get().name(), "what", "ip punishment"));
        } else {
            messages.send(s, "unippunished", Messages.values("name", t.get().name(), "count", r.addresses(), "accounts", r.released()));
        }
        explainRemainingBlocks(s, t.get());
    }

    // ------------------------------------------------------------------ reports

    private static String date(long millis) {
        return millis <= 0 ? "-" : new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(millis));
    }

    private void links(CommandSender s, String[] args) throws Exception {
        if (deny(s, "exocleanwipe.links")) {
            return;
        }
        if (args.length != 2) {
            usage(s, "/wipe links <player>");
            return;
        }
        Optional<Resolved> t = target(s, args[1]);
        if (t.isEmpty()) {
            return;
        }
        boolean full = s.hasPermission("exocleanwipe.viewip");
        LinkReport.Report r = LinkReport.build(store, t.get().uuid(), settings.sharedNetworkThreshold);
        line(s, "<accent>exocleanwipe</accent> <gray><sym:arrow> linked accounts of <white><name></white> (<uuid>)</gray>",
                "name", t.get().name(), "uuid", t.get().uuid());
        List<String> names = new ArrayList<>();
        r.names().forEach(n -> names.add(n.name() + " (" + date(n.firstSeen()) + ")"));
        line(s, " <gray>names:</gray> <white><list></white>", "list", names.isEmpty() ? "-" : String.join(", ", names));
        for (LinkReport.AddressInfo a : r.addresses()) {
            line(s, " <gray>address</gray> <white><ip></white> <gray><sym:dot> <n> account(s) <sym:dot> last <last><shared></gray>",
                    "ip", full ? a.ip() : Inputs.maskIp(a.ip()), "n", a.accounts(), "last", date(a.lastSeen()),
                    "shared", a.sharedNetwork() ? " <sym:dot> shared network" : "");
        }
        if (r.linked().isEmpty()) {
            line(s, " <gray>no other account was seen on these addresses.</gray>");
        }
        for (LinkReport.Linked l : r.linked()) {
            String flags = punishments.accountBlock(l.uuid(), System.currentTimeMillis()).map(p -> " · suspended").orElse("");
            line(s, " <white><name></white> <gray>· <n> shared address(es) · last seen <last><flags><weak></gray>",
                    "name", l.name(), "n", l.sharedAddresses(), "last", date(l.lastSeen()), "flags", flags,
                    "weak", l.onSharedNetworkOnly() ? " · only via shared networks (weak)" : "");
        }
        line(s, "<gray>this is evidence, not proof. include an account in a wipe only after review: /wipe <name> all +alt:<other></gray>",
                "name", t.get().name(), "other", "<name>");
    }

    private void info(CommandSender s, String[] args) throws Exception {
        if (deny(s, "exocleanwipe.links")) {
            return;
        }
        if (args.length != 2) {
            usage(s, "/wipe info <player>");
            return;
        }
        Optional<Resolved> t = target(s, args[1]);
        if (t.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        line(s, "<accent>exocleanwipe</accent> <gray><sym:arrow> <white><name></white> (<uuid>)</gray>", "name", t.get().name(), "uuid", t.get().uuid());
        Optional<Lock> lock = store.lockOf(t.get().uuid());
        lock.ifPresent(l -> line(s, " <red><sym:lock> locked</red> <gray>by <why></gray>", "why", l.jobId() == ActionService.FREEZE_JOB ? "a preview" : l.reason()));
        for (Punishment p : store.history(t.get().uuid(), 10)) {
            String state = !p.active() ? "removed" : p.inForce(now) ? "active" : "expired";
            String remaining = p.permanent() ? "permanent" : Durations.format(Duration.ofMillis(Math.max(0, p.expiresAt() - now)));
            line(s, " <white><ref></white> <gray><kind> · <state> · <remaining> · by <actor> · <when> · reason</gray> <white><reason></white>",
                    "ref", punishments.reference(p.id()), "kind", p.kind() == dev.exo.cleanwipe.storage.Records.PunishmentKind.ACCOUNT ? "account" : "account + ip",
                    "state", state, "remaining", state.equals("active") ? remaining : "-", "actor", p.actorName(), "when", date(p.createdAt()),
                    "reason", p.reason());
        }
    }

    private void jobs(CommandSender s) throws Exception {
        if (deny(s, "exocleanwipe.status")) {
            return;
        }
        line(s, "<accent>exocleanwipe</accent> <gray><sym:arrow> recent jobs</gray>");
        for (Job j : store.recentJobs(10)) {
            line(s, " <white>job <id></white> <gray>· <type> · <state> · by <actor> · <when></gray>", "id", j.id(),
                    "type", j.type().name().toLowerCase(), "state", j.state().name().toLowerCase().replace('_', ' '),
                    "actor", j.actorName(), "when", date(j.createdAt()));
        }
    }

    private void status(CommandSender s, String[] args) throws Exception {
        if (deny(s, "exocleanwipe.status")) {
            return;
        }
        if (args.length == 1) {
            jobs(s);
            return;
        }
        long id = Inputs.parseReference(args[1]);
        Optional<Job> job = id < 0 ? Optional.empty() : store.job(id);
        if (job.isEmpty()) {
            line(s, "<red>unknown job <job></red>", "job", args[1]);
            return;
        }
        Job j = job.get();
        line(s, "<accent>exocleanwipe</accent> <gray><sym:arrow> job <white><id></white> · <type> · <white><state></white> · by <actor> · <when></gray>",
                "id", j.id(), "type", j.type().name().toLowerCase(), "state", j.state().name().toLowerCase().replace('_', ' '),
                "actor", j.actorName(), "when", date(j.createdAt()));
        if (j.reason() != null && !j.reason().isEmpty()) {
            line(s, " <gray>reason:</gray> <white><r></white>", "r", j.reason());
        }
        if (j.punishmentId() != null) {
            line(s, " <gray>punishment:</gray> <white><ref></white>", "ref", punishments.reference(j.punishmentId()));
        }
        List<Target> targets = store.targets(j.id());
        for (Target t : targets) {
            line(s, " <gray>target</gray> <white><name></white> <gray>(<uuid>, <role>)</gray>", "name", t.name(), "uuid", t.uuid(), "role", t.role());
        }
        int done = 0;
        int pending = 0;
        List<Step> problems = new ArrayList<>();
        for (Step st : store.steps(j.id())) {
            if (st.state() == StepState.PENDING) {
                pending++;
            } else if (st.state().problem()) {
                problems.add(st);
            } else {
                done++;
            }
        }
        line(s, " <gray>steps: <white><done></white> finished, <white><pending></white> pending, <white><problems></white> need attention</gray>",
                "done", done, "pending", pending, "problems", problems.size());
        List<Step> all = store.steps(j.id());
        int shown = 0;
        for (Step st : all) {
            if (st.state() == StepState.PENDING || shown > 25 && !st.state().problem()) {
                continue;
            }
            shown++;
            String who = st.uuid() == null ? "everyone" : targets.stream().filter(t -> t.uuid().equals(st.uuid())).map(Target::name).findFirst()
                    .orElse(st.uuid().toString());
            String color = st.state().problem() ? "red" : "gray";
            line(s, " <" + color + "><sym:dot> <integration> (<who>): <state></" + color + "> <gray><detail></gray>",
                    "integration", st.integration(), "who", who, "state", st.state().name().toLowerCase(),
                    "detail", st.detail() == null ? "" : "- " + st.detail());
        }
        switch (j.state()) {
            case WAITING_RESTART -> line(s, "<gray>next: restart the server. the remaining steps run while it starts, before those plugins load.</gray>");
            case PARTIAL -> {
                line(s, "<red>the accounts stay locked.</red> <gray>blocked: finish or resolve the listed transactions in that plugin. failed: check the console"
                        + " for the error and fix the cause. then <white>/wipe retry <id></white>. to release without finishing: <white>/wipe unlock <player></white>."
                        + " backups: <white><dir></white></gray>", "id", j.id(), "player", "<player>",
                        "dir", settings.backupDir.resolve("job-" + j.id()).toString());
            }
            case RUNNING -> line(s, "<gray>running; live steps continue in the background.</gray>");
            default -> {
            }
        }
    }

    private void integrations(CommandSender s) {
        if (deny(s, "exocleanwipe.status")) {
            return;
        }
        line(s, "<accent>exocleanwipe</accent> <gray><sym:arrow> integrations on this server</gray>");
        for (Integration i : registry.all()) {
            Integration.Availability a = i.availability();
            line(s, " <white><id></white> <gray>· <phase> · <covers></gray><status>", "id", i.id(),
                    "phase", i.phase().name().toLowerCase(), "covers", i.covers(), "status", a.available() ? "" : " · " + a.reason());
        }
        registry.unavailable().forEach((id, why) -> line(s, " <gray><id> · not covered · <why></gray>", "id", id, "why", why));
    }

    private void retry(CommandSender s, String[] args) throws Exception {
        if (deny(s, "exocleanwipe.retry")) {
            return;
        }
        long id = args.length == 2 ? Inputs.parseReference(args[1]) : -1;
        if (id < 0) {
            usage(s, "/wipe retry <job>");
            return;
        }
        int n = engine.retry(id);
        audit.record(s.getName(), "retry", "job " + id, "", n + " step(s) requeued", "");
        line(s, "<gray><n> step(s) of job <id> queued again. startup steps run at the next restart.</gray>", "n", n, "id", id);
    }

    private void unlock(CommandSender s, String[] args) throws Exception {
        if (deny(s, "exocleanwipe.unlock")) {
            return;
        }
        if (args.length != 2) {
            usage(s, "/wipe unlock <player>");
            return;
        }
        Optional<Resolved> t = target(s, args[1]);
        if (t.isEmpty()) {
            return;
        }
        Optional<Lock> lock = store.lockOf(t.get().uuid());
        if (lock.isEmpty()) {
            messages.send(s, "not-locked", Messages.values("name", t.get().name()));
            return;
        }
        store.unlock(t.get().uuid());
        audit.record(s.getName(), "unlock", t.get().name() + "=" + t.get().uuid(), "", "unlocked", "lock of job " + lock.get().jobId());
        messages.send(s, "unlocked", Messages.values("name", t.get().name()));
    }

    private void restore(CommandSender s, String[] args) {
        if (deny(s, "exocleanwipe.restore")) {
            return;
        }
        long id = args.length == 2 ? Inputs.parseReference(args[1]) : -1;
        if (id < 0) {
            usage(s, "/wipe restore <job>");
            return;
        }
        actions.previewRestore(s, id);
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("wipeall") || !sender.hasPermission("exocleanwipe.use")) {
            return List.of();
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (Sub sub : SUBS) {
                if (!sub.name().startsWith("<") && sender.hasPermission(sub.permission())) {
                    out.add(sub.name());
                }
            }
            if (sender.hasPermission("exocleanwipe.wipe")) {
                out.addAll(names(last));
            }
        } else {
            String first = args[0].toLowerCase(Locale.ROOT);
            Optional<Sub> sub = SUBS.stream().filter(x -> x.name().equals(first)).findFirst();
            if (sub.isPresent() && !sender.hasPermission(sub.get().permission())) {
                return List.of();
            }
            switch (first) {
                case "punish" -> {
                    if (args.length == 2) {
                        out.addAll(names(last));
                    } else if (args.length == 3) {
                        out.addAll(List.of("30days", "14days", "7days", "12hours", "45minutes", "permanent"));
                    } else {
                        out.addAll(List.of("duping", "--shared", "--keep-shared"));
                    }
                }
                case "ippunish", "unpunish", "unippunish", "links", "info", "unlock" -> {
                    if (args.length == 2) {
                        out.addAll(names(last));
                    }
                }
                case "confirm" -> {
                    if (args.length == 3 && sender.hasPermission("exocleanwipe.wipeall")) {
                        out.add("wipeall");
                    }
                }
                case "status", "retry", "restore" -> {
                    if (args.length == 2) {
                        try {
                            store.recentJobs(10).forEach(j -> out.add(Long.toString(j.id())));
                        } catch (Exception ignored) {
                            // no suggestions
                        }
                    }
                }
                default -> {
                    if (sender.hasPermission("exocleanwipe.wipe")) {
                        if (args.length == 2) {
                            out.add("all");
                        } else {
                            out.addAll(List.of("--shared", "--keep-shared"));
                            if (sender.hasPermission("exocleanwipe.wipe.linked")) {
                                out.add("+alt:");
                            }
                        }
                    }
                }
            }
        }
        return out.stream().filter(x -> x.toLowerCase(Locale.ROOT).startsWith(last)).distinct().limit(40).toList();
    }

    private List<String> names(String prefix) {
        List<String> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            out.add(p.getName());
        }
        if (Inputs.isValidName(prefix) || prefix.isEmpty()) {
            try {
                out.addAll(store.namesStartingWith(prefix, 20));
            } catch (Exception ignored) {
                // online names only
            }
        }
        return out;
    }
}
