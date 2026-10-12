package dev.exo.cleanwipe.wipe;

import dev.exo.cleanwipe.config.Settings;
import dev.exo.cleanwipe.identity.ProxyTrust;
import dev.exo.cleanwipe.integrations.vanilla.VanillaIntegration;
import dev.exo.cleanwipe.punish.PunishmentService;
import dev.exo.cleanwipe.storage.AuditLog;
import dev.exo.cleanwipe.storage.CoreStore;
import dev.exo.cleanwipe.storage.Records.Account;
import dev.exo.cleanwipe.storage.Records.IpEntry;
import dev.exo.cleanwipe.storage.Records.JobType;
import dev.exo.cleanwipe.storage.Records.Lock;
import dev.exo.cleanwipe.storage.Records.Phase;
import dev.exo.cleanwipe.text.Messages;
import dev.exo.cleanwipe.util.Durations;
import dev.exo.cleanwipe.util.Inputs;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Turns staff commands into previews, confirmation codes and jobs. Nothing destructive happens before a valid code is
 * confirmed by the same staff member. Previews are computed off the main thread.
 */
public final class ActionService {

    /** Job id used for preview freezes (released on cancel, expiry and plugin start). */
    public static final long FREEZE_JOB = -1;

    public enum Kind { WIPE, PUNISH, WIPE_ALL, IP_PUNISH, RESTORE }

    public record Resolved(UUID uuid, String name) {
    }

    /** Everything needed to run an action once it is confirmed. */
    public record Pending(Kind kind, String actorName, List<JobEngine.Target> targets, String reason, Duration duration,
                          WipeOptions options, List<String> ips, long restoreFrom, boolean frozen) {
    }

    private final Plugin plugin;
    private final Settings settings;
    private final Messages messages;
    private final CoreStore store;
    private final JobEngine engine;
    private final IntegrationRegistry registry;
    private final PunishmentService punishments;
    private final AuditLog audit;
    private final ProxyTrust trust;
    private final VanillaIntegration vanilla;
    private final Confirmations<Pending> confirmations = new Confirmations<>();

    public ActionService(Plugin plugin, Settings settings, Messages messages, CoreStore store, JobEngine engine,
                         IntegrationRegistry registry, PunishmentService punishments, AuditLog audit, ProxyTrust trust,
                         VanillaIntegration vanilla) {
        this.plugin = plugin;
        this.settings = settings;
        this.messages = messages;
        this.store = store;
        this.engine = engine;
        this.registry = registry;
        this.punishments = punishments;
        this.audit = audit;
        this.trust = trust;
        this.vanilla = vanilla;
    }

    // ------------------------------------------------------------------ helpers

    static UUID ownerOf(CommandSender s) {
        return s instanceof Player p ? p.getUniqueId() : null;
    }

    private void line(CommandSender to, String template, Object... values) {
        to.sendMessage(messages.line(template, Messages.values(values)));
    }

    private void async(Runnable r) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, r);
    }

    private void sync(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }

    /** Online exact name, then ExoCleanWipe's account history, then the server's profile cache. Never contacts Mojang. */
    public Optional<Resolved> resolve(String input) {
        try {
            UUID asUuid = UUID.fromString(input);
            Optional<Account> a = store.account(asUuid);
            OfflinePlayer op = Bukkit.getOfflinePlayer(asUuid);
            String name = a.map(Account::name).orElse(op.getName());
            if (name != null || op.hasPlayedBefore()) {
                return Optional.of(new Resolved(asUuid, name == null ? input : name));
            }
            return Optional.empty();
        } catch (IllegalArgumentException notUuid) {
            // fall through to names
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "lookup failed", e);
        }
        if (!Inputs.isValidName(input)) {
            return Optional.empty();
        }
        Player online = Bukkit.getPlayerExact(input);
        if (online != null) {
            return Optional.of(new Resolved(online.getUniqueId(), online.getName()));
        }
        try {
            List<Account> known = store.accountsByName(input);
            if (!known.isEmpty()) {
                return Optional.of(new Resolved(known.get(0).uuid(), known.get(0).name()));
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "lookup failed", e);
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(input);
        if (cached != null && cached.getName() != null) {
            return Optional.of(new Resolved(cached.getUniqueId(), cached.getName()));
        }
        return Optional.empty();
    }

    /** Job that currently locks this account (ignores preview freezes). */
    private Optional<Long> busy(UUID uuid) {
        try {
            return store.lockOf(uuid).map(Lock::jobId).filter(id -> id > 0);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ previews

    public void previewWipe(CommandSender sender, Kind kind, Resolved primary, List<Resolved> alts, WipeOptions options,
                            Duration duration, String reason) {
        List<JobEngine.Target> targets = new ArrayList<>();
        targets.add(new JobEngine.Target(primary.uuid(), primary.name(), "primary"));
        for (Resolved a : alts) {
            if (!a.uuid().equals(primary.uuid())) {
                targets.add(new JobEngine.Target(a.uuid(), a.name(), "linked"));
            }
        }
        for (JobEngine.Target t : targets) {
            Optional<Long> job = busy(t.uuid());
            if (job.isPresent()) {
                messages.send(sender, "busy", Messages.values("job", job.get()));
                return;
            }
        }
        boolean freeze = settings.freezeOnPreview;
        if (freeze) {
            freeze(targets);
        }
        UUID owner = ownerOf(sender);
        String actor = sender.getName();
        async(() -> {
            List<Component> lines = new ArrayList<>();
            boolean needsDecision = false;
            Set<String> linkedEvidence = linkedTo(primary.uuid());
            for (JobEngine.Target t : targets) {
                lines.add(messages.line("<accent><sym:dot></accent> <white><name></white> <gray>(<uuid>, <role>)</gray>",
                        Messages.values("name", t.name(), "uuid", t.uuid(), "role", t.role())));
                if (t.role().equals("linked") && !linkedEvidence.contains(t.uuid().toString())) {
                    lines.add(warnLine("not linked to " + primary.name() + " by any shared address; included only because you named it"));
                }
                needsDecision |= previewTarget(t.uuid(), lines, options);
            }
            for (String other : otherProfiles(primary, targets)) {
                lines.add(warnLine(other));
            }
            List<Component> footer = availabilityLines();
            boolean undecided = needsDecision;
            sync(() -> {
                String what = kind == Kind.PUNISH ? "wipe + " + Durations.format(duration) + " suspension" : "wipe";
                line(sender, "<accent>exocleanwipe</accent> <gray><sym:arrow> preview: <white><what></white> of <white><name></white>"
                        + (reason == null || reason.isEmpty() ? "" : " for <white><reason></white>") + "</gray>",
                        "what", what, "name", primary.name(), "reason", reason == null ? "" : reason);
                lines.forEach(sender::sendMessage);
                footer.forEach(sender::sendMessage);
                if (freeze) {
                    line(sender, "<gray>the target(s) are frozen (kicked and locked) until you confirm or the code expires.</gray>");
                }
                Pending p = new Pending(kind, actor, targets, reason, duration, options, List.of(), 0, freeze);
                issue(sender, owner, p, settings.confirmSeconds, "confirm-hint", undecided);
            });
        });
    }

    /**
     * Other stored profiles that may belong to the same person: earlier or later holders of the name, and the
     * offline-mode UUID of the name if data exists for it. They are listed, never included automatically.
     */
    private List<String> otherProfiles(Resolved primary, List<JobEngine.Target> targets) {
        List<String> out = new ArrayList<>();
        Set<UUID> included = new LinkedHashSet<>();
        targets.forEach(t -> included.add(t.uuid()));
        try {
            for (Account a : store.accountsByName(primary.name())) {
                if (!included.contains(a.uuid())) {
                    out.add("another account has also used the name " + primary.name() + ": " + a.name() + " (" + a.uuid()
                            + "). include it only if it is the same person: +alt:" + a.uuid());
                }
            }
            UUID offline = UUID.nameUUIDFromBytes(("OfflinePlayer:" + primary.name()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            boolean offlineData = vanilla != null && Files.exists(vanilla.dataDir().resolve(offline + ".dat"));
            if (!included.contains(offline) && (offlineData || store.isKnown(offline))) {
                out.add("an offline-mode profile of " + primary.name() + " has stored data (" + offline + "). include it with +alt:" + offline);
            }
        } catch (Exception e) {
            out.add("could not check other profiles: " + e.getMessage());
        }
        return out;
    }

    /** Returns true when a shared-island decision is required and missing. */
    private boolean previewTarget(UUID uuid, List<Component> lines, WipeOptions options) {
        boolean undecided = false;
        List<String> nothing = new ArrayList<>();
        for (Integration i : registry.all()) {
            Preview p;
            try {
                p = i.preview(uuid);
            } catch (Exception e) {
                lines.add(messages.line("   <red><sym:warn> <id>: preview failed (<error>)</red>",
                        Messages.values("id", i.id(), "error", String.valueOf(e.getMessage()))));
                continue;
            }
            if (p.isEmpty()) {
                nothing.add(i.id());
                continue;
            }
            String when = i.phase() == Phase.STARTUP ? " (next restart)" : "";
            for (Preview.Line l : p.lines()) {
                lines.add(messages.line("   <gray><id><when> <sym:arrow></gray> <white><label></white><gray>:</gray> <white><value></white>",
                        Messages.values("id", i.id(), "when", when, "label", l.label(), "value", l.value())));
            }
            for (Preview.SharedAccount s : p.shared()) {
                lines.add(messages.line("   <red><sym:warn> also affects</red> <white><name></white> <gray>(<relation>)</gray>",
                        Messages.values("name", s.name(), "relation", s.relation())));
            }
            for (String w : p.warnings()) {
                lines.add(warnLine(i.id() + ": " + w));
            }
            for (String b : p.blockers()) {
                lines.add(messages.line("   <red><sym:stop> <id> will refuse: <reason></red>", Messages.values("id", i.id(), "reason", b)));
            }
            if (p.requiresSharedDecision() && !options.disbandSharedIslands() && !options.keepSharedIslands()) {
                undecided = true;
                lines.add(messages.line("   <red><sym:stop> decide with --shared (disband for everyone) or --keep-shared before confirming</red>"));
            }
        }
        if (!nothing.isEmpty()) {
            lines.add(messages.line("   <gray>nothing stored in: <list></gray>", Messages.values("list", String.join(", ", nothing))));
        }
        return undecided;
    }

    private Component warnLine(String text) {
        return messages.line("   <red><sym:warn></red> <gray><text></gray>", Messages.values("text", text));
    }

    private List<Component> availabilityLines() {
        List<Component> out = new ArrayList<>();
        List<String> restart = new ArrayList<>();
        for (Integration i : registry.all()) {
            if (i.phase() == Phase.STARTUP) {
                restart.add(i.id());
            }
        }
        if (!restart.isEmpty()) {
            out.add(messages.line("<gray>applied at the next server start (account stays locked until then): <list></gray>",
                    Messages.values("list", String.join(", ", restart))));
        }
        if (!registry.unavailable().isEmpty()) {
            List<String> parts = new ArrayList<>();
            registry.unavailable().forEach((k, v) -> parts.add(k + " (" + v + ")"));
            out.add(messages.line("<gray>not covered: <list></gray>", Messages.values("list", String.join("; ", parts))));
        }
        out.add(messages.line("<gray>items already given to other accounts are not traced (see /wipe help).</gray>"));
        return out;
    }

    private Set<String> linkedTo(UUID uuid) {
        Set<String> out = new LinkedHashSet<>();
        try {
            for (IpEntry mine : store.ipsOf(uuid)) {
                for (IpEntry o : store.accountsOnIp(mine.ip())) {
                    out.add(o.uuid().toString());
                }
            }
        } catch (Exception ignored) {
            // report shows the error separately
        }
        return out;
    }

    private void issue(CommandSender sender, UUID owner, Pending p, int seconds, String hintKey, boolean undecided) {
        if (undecided) {
            // a preview without a decision still shows everything, but cannot be confirmed
            line(sender, "<red>run the command again with --shared or --keep-shared to get a confirmation code.</red>");
            if (p.frozen()) {
                unfreeze(p.targets());
            }
            return;
        }
        Confirmations.Entry<Pending> e = confirmations.issue(owner, seconds * 1000L, p, System.currentTimeMillis());
        sender.sendMessage(messages.chat(hintKey, Messages.values("token", e.token(), "seconds", seconds))
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand("/wipe confirm " + e.token())));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Confirmations.Entry<Pending> gone : confirmations.expire(System.currentTimeMillis())) {
                if (gone.payload().frozen()) {
                    unfreeze(gone.payload().targets());
                }
                CommandSender who = gone.owner() == null ? Bukkit.getConsoleSender() : Bukkit.getPlayer(gone.owner());
                if (who != null && gone.payload().frozen()) {
                    messages.send(who, "token-expired-unfreeze", Messages.values("token", gone.token(),
                            "name", gone.payload().targets().get(0).name()));
                }
            }
        }, seconds * 20L + 5);
    }

    private void freeze(List<JobEngine.Target> targets) {
        long now = System.currentTimeMillis();
        for (JobEngine.Target t : targets) {
            try {
                store.lock(t.uuid(), FREEZE_JOB, "frozen for preview", now);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "freeze failed", e);
            }
            Player p = Bukkit.getPlayer(t.uuid());
            if (p != null) {
                tuckLooseItems(p);
                p.kick(messages.screen("lock-screen", Messages.values("ref", "preview")));
            }
        }
    }

    /** Non-destructive: moves cursor and crafting-grid items into the inventory so the server saves them in the file. */
    private static void tuckLooseItems(Player p) {
        ItemStack cursor = p.getItemOnCursor();
        if (cursor != null && !cursor.isEmpty()) {
            Map<Integer, ItemStack> left = p.getInventory().addItem(cursor);
            p.setItemOnCursor(left.isEmpty() ? null : left.values().iterator().next());
        }
    }

    private void unfreeze(List<JobEngine.Target> targets) {
        for (JobEngine.Target t : targets) {
            try {
                Optional<Lock> l = store.lockOf(t.uuid());
                if (l.isPresent() && l.get().jobId() == FREEZE_JOB) {
                    store.unlock(t.uuid());
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "unfreeze failed", e);
            }
        }
    }

    public void previewWipeAll(CommandSender sender) {
        UUID owner = ownerOf(sender);
        async(() -> {
            List<Component> lines = new ArrayList<>();
            for (Integration i : registry.all()) {
                try {
                    Preview p = i.previewAll();
                    for (Preview.Line l : p.lines()) {
                        lines.add(messages.line("   <gray><id> <sym:arrow></gray> <white><label></white><gray>:</gray> <white><value></white>",
                                Messages.values("id", i.id(), "label", l.label(), "value", l.value())));
                    }
                    for (String w : p.warnings()) {
                        lines.add(warnLine(i.id() + ": " + w));
                    }
                } catch (Exception e) {
                    lines.add(warnLine(i.id() + ": preview failed (" + e.getMessage() + ")"));
                }
            }
            int accounts;
            try {
                Set<UUID> all = new LinkedHashSet<>(store.allAccountIds());
                all.addAll(vanilla.allPlayers());
                accounts = all.size();
            } catch (Exception e) {
                accounts = -1;
            }
            List<Component> footer = availabilityLines();
            int finalAccounts = accounts;
            sync(() -> {
                line(sender, "<accent>exocleanwipe</accent> <gray><sym:arrow> preview: <red>season reset</red> of every player"
                        + " (<white><n></white> known accounts). nobody is banned.</gray>", "n", finalAccounts);
                lines.forEach(sender::sendMessage);
                footer.forEach(sender::sendMessage);
                line(sender, "<gray>everyone is kicked and nobody can join until the reset has finished, including the restart.</gray>");
                Pending p = new Pending(Kind.WIPE_ALL, sender.getName(), List.of(), "season reset", null, WipeOptions.DEFAULT,
                        List.of(), 0, false);
                Confirmations.Entry<Pending> e = confirmations.issue(owner, settings.wipeAllConfirmSeconds * 1000L, p, System.currentTimeMillis());
                messages.send(sender, "confirm-hint-wipeall", Messages.values("token", e.token(), "seconds", settings.wipeAllConfirmSeconds));
            });
        });
    }

    public void previewIpPunish(CommandSender sender, Resolved target, String reason) {
        if (!trust.ipFeaturesEnabled()) {
            line(sender, "<red>ip punishments are disabled because forwarded addresses cannot be trusted: <why></red>",
                    "why", String.join("; ", trust.warnings()));
            return;
        }
        Player online = Bukkit.getPlayer(target.uuid());
        String current = online == null || online.getAddress() == null ? null : trust.usable(online.getAddress().getAddress());
        PunishmentService.IpPlan plan;
        try {
            plan = punishments.planIp(target.uuid(), current);
        } catch (Exception e) {
            line(sender, "<red>could not read address history: <e></red>", "e", String.valueOf(e.getMessage()));
            return;
        }
        boolean full = sender.hasPermission("exocleanwipe.viewip");
        line(sender, "<accent>exocleanwipe</accent> <gray><sym:arrow> preview: <red>permanent ip punishment</red> of <white><name></white>"
                + " for <white><reason></white>. no data is wiped.</gray>", "name", target.name(), "reason", reason);
        line(sender, "   <gray>addresses banned:</gray> <white><list></white>", "list",
                plan.banned().isEmpty() ? "none known" : String.join(", ", plan.banned().stream().map(ip -> full ? ip : Inputs.maskIp(ip)).toList()));
        if (!plan.skipped().isEmpty()) {
            line(sender, "   <gray>not banned (private/local): <list></gray>", "list", String.valueOf(plan.skipped().size()));
        }
        Map<UUID, Integer> others = new LinkedHashMap<>();
        plan.otherAccounts().forEach(o -> others.merge(o.uuid(), 1, Integer::sum));
        if (!others.isEmpty()) {
            List<String> names = new ArrayList<>();
            others.forEach((u, n) -> names.add(nameOf(u)));
            line(sender, "   <red><sym:warn> these accounts also used those addresses and will be refused while the ban lasts:</red> <white><list></white>",
                    "list", String.join(", ", names));
            line(sender, "   <gray>an address is not proof of identity (shared homes, schools, public wifi). review /wipe links first.</gray>");
        }
        if (plan.banned().isEmpty()) {
            line(sender, "<gray>the account ban still applies; no address will be banned.</gray>");
        }
        Pending p = new Pending(Kind.IP_PUNISH, sender.getName(), List.of(new JobEngine.Target(target.uuid(), target.name(), "primary")),
                reason, Durations.PERMANENT, WipeOptions.DEFAULT, plan.banned(), 0, false);
        issue(sender, ownerOf(sender), p, settings.confirmSeconds, "confirm-hint", false);
    }

    public void previewRestore(CommandSender sender, long jobId) {
        Path root = settings.backupDir.resolve("job-" + jobId);
        if (!Files.isDirectory(root)) {
            line(sender, "<red>no backups for job <job> in <dir></red>", "job", jobId, "dir", root);
            return;
        }
        List<JobEngine.Target> targets = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (!n.equals("all")) {
                    UUID u = UUID.fromString(n);
                    targets.add(new JobEngine.Target(u, nameOf(u), "primary"));
                }
            }
        } catch (Exception e) {
            line(sender, "<red>cannot read <dir>: <e></red>", "dir", root, "e", String.valueOf(e.getMessage()));
            return;
        }
        line(sender, "<accent>exocleanwipe</accent> <gray><sym:arrow> preview: restore backups of job <white><job></white> for <white><n></white> account(s)"
                + (Files.isDirectory(root.resolve("all")) ? " and the season-reset data" : "") + ".</gray>", "job", jobId, "n", targets.size());
        line(sender, "<gray>rows and files are put back as they were before the wipe; changes made since then to the same rows are overwritten."
                + " islands and essentials need the manual steps in docs/RECOVERY.md.</gray>");
        Pending p = new Pending(Kind.RESTORE, sender.getName(), targets, "restore of job " + jobId, null, WipeOptions.DEFAULT,
                List.of(), jobId, false);
        issue(sender, ownerOf(sender), p, settings.confirmSeconds, "confirm-hint", false);
    }

    private static String nameOf(UUID u) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(u);
        return op.getName() == null ? u.toString() : op.getName();
    }

    // ------------------------------------------------------------------ confirm / cancel

    public void cancel(CommandSender sender, String token) {
        UUID owner = ownerOf(sender);
        if (confirmations.check(token, owner, System.currentTimeMillis()) != Confirmations.Check.OK) {
            messages.send(sender, "token-unknown");
            return;
        }
        confirmations.remove(token).ifPresent(e -> {
            if (e.payload().frozen()) {
                unfreeze(e.payload().targets());
            }
        });
        messages.send(sender, "token-cancelled");
    }

    public void confirm(CommandSender sender, String token, String word) {
        UUID owner = ownerOf(sender);
        long now = System.currentTimeMillis();
        Confirmations.Check check = confirmations.check(token, owner, now);
        if (check == Confirmations.Check.WRONG_OWNER) {
            messages.send(sender, "token-wrong-sender");
            return;
        }
        if (check != Confirmations.Check.OK) {
            messages.send(sender, "token-unknown");
            return;
        }
        Pending p = confirmations.peek(token).orElseThrow().payload();
        if (p.kind() == Kind.WIPE_ALL && !"wipeall".equalsIgnoreCase(word)) {
            // the code stays valid: the staff member only forgot the word
            messages.send(sender, "wipeall-word-missing");
            return;
        }
        if (confirmations.consume(token, owner, now).isEmpty()) {
            messages.send(sender, "token-unknown");
            return;
        }
        try {
            switch (p.kind()) {
                case WIPE, PUNISH -> startWipe(sender, p);
                case WIPE_ALL -> startWipeAll(sender, p);
                case IP_PUNISH -> ipPunish(sender, p);
                case RESTORE -> startRestore(sender, p);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "action failed", e);
            line(sender, "<red>failed: <e>. nothing further was changed; see the console.</red>", "e", String.valueOf(e.getMessage()));
            audit.record(sender.getName(), p.kind().name(), targetsString(p.targets()), p.reason(), "error", String.valueOf(e));
        }
    }

    private static String targetsString(List<JobEngine.Target> targets) {
        List<String> parts = new ArrayList<>();
        for (JobEngine.Target t : targets) {
            parts.add(t.name() + "=" + t.uuid());
        }
        return String.join(",", parts);
    }

    private List<JobEngine.StepPlan> planSteps(List<JobEngine.Target> targets) {
        List<JobEngine.StepPlan> steps = new ArrayList<>();
        for (JobEngine.Target t : targets) {
            for (Integration i : registry.all()) {
                steps.add(new JobEngine.StepPlan(t.uuid(), i.id(), i.phase()));
            }
        }
        return steps;
    }

    private void startWipe(CommandSender sender, Pending p) throws Exception {
        for (JobEngine.Target t : p.targets()) {
            Optional<Long> job = busy(t.uuid());
            if (job.isPresent()) {
                messages.send(sender, "busy", Messages.values("job", job.get()));
                return;
            }
        }
        UUID actor = ownerOf(sender);
        JobType type = p.kind() == Kind.PUNISH ? JobType.PUNISH : JobType.WIPE;
        JobEngine.Target primary = p.targets().get(0);
        long now = System.currentTimeMillis();
        long jobId = engine.create(type, actor, sender.getName(), p.reason(), p.options(), p.targets(), planSteps(p.targets()), false,
                type == JobType.PUNISH
                        ? (c, id) -> punishments.createAccount(c, primary.uuid(), primary.name(), p.reason(), actor, sender.getName(), now,
                        p.duration(), id)
                        : null);
        kickTargets(jobId, p.targets());
        audit.record(sender.getName(), type.name().toLowerCase(), targetsString(p.targets()), p.reason(), "started",
                "job " + jobId + "; integrations " + registry.all().stream().map(Integration::id).toList()
                        + (type == JobType.PUNISH ? "; suspension " + Durations.format(p.duration()) : ""));
        messages.send(sender, "job-started", Messages.values("job", jobId, "what", type == JobType.PUNISH
                ? "wipe and " + Durations.format(p.duration()) + " suspension" : "wipe"));
        engine.evaluate(jobId);
    }

    private void kickTargets(long jobId, List<JobEngine.Target> targets) {
        for (JobEngine.Target t : targets) {
            Player online = Bukkit.getPlayer(t.uuid());
            if (online == null) {
                continue;
            }
            try {
                if (vanilla != null) {
                    vanilla.prepareOnline(online, engine.backupDir(jobId, t.uuid(), vanilla.id()));
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "could not back up live inventory of " + t.name(), e);
            }
            online.kick(messages.screen("lock-screen", Messages.values("ref", "job " + jobId)));
        }
    }

    private void startWipeAll(CommandSender sender, Pending p) throws Exception {
        Set<UUID> players = new LinkedHashSet<>(store.allAccountIds());
        if (vanilla != null) {
            players.addAll(vanilla.allPlayers());
        }
        List<JobEngine.StepPlan> steps = new ArrayList<>();
        for (Integration i : registry.all()) {
            boolean perPlayer = i.id().equals("Vanilla") || i.id().equals("Essentials");
            if (perPlayer) {
                for (UUID u : players) {
                    steps.add(new JobEngine.StepPlan(u, i.id(), i.phase()));
                }
            } else {
                steps.add(new JobEngine.StepPlan(null, i.id(), i.phase()));
            }
        }
        long jobId = engine.create(JobType.WIPE_ALL, ownerOf(sender), sender.getName(), "season reset", WipeOptions.DEFAULT,
                List.of(), steps, true, null);
        snapshotLiveDatabases(jobId);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (vanilla != null) {
                try {
                    vanilla.prepareOnline(online, engine.backupDir(jobId, online.getUniqueId(), vanilla.id()));
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "could not back up live inventory of " + online.getName(), e);
                }
            }
            online.kick(messages.screen("wipeall-screen"));
        }
        audit.record(sender.getName(), "wipeall", players.size() + " accounts", "season reset", "started", "job " + jobId);
        messages.send(sender, "job-started", Messages.values("job", jobId, "what", "season reset of " + players.size() + " accounts"));
        engine.evaluate(jobId);
    }

    /** Consistent copies (VACUUM INTO) of live plugins' databases before a season reset. */
    private void snapshotLiveDatabases(long jobId) {
        for (String[] db : List.of(new String[]{"ExcellentEconomy", "data.db"}, new String[]{"ESkyblock", "islands.db"})) {
            Plugin owner = Bukkit.getPluginManager().getPlugin(db[0]);
            if (owner == null) {
                continue;
            }
            Path src = owner.getDataFolder().toPath().resolve(db[1]);
            Path dst = engine.backupDir(jobId, null, db[0]).resolve(db[1] + ".before");
            try {
                Files.createDirectories(dst.getParent());
                if (Files.exists(src) && !Files.exists(dst)) {
                    try (var c = dev.exo.cleanwipe.storage.Sqlite.open(src, true); var st = c.createStatement()) {
                        st.execute("VACUUM INTO '" + dst.toAbsolutePath().toString().replace("'", "''") + "'");
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "could not snapshot " + src, e);
            }
        }
    }

    private void ipPunish(CommandSender sender, Pending p) throws Exception {
        JobEngine.Target t = p.targets().get(0);
        long now = System.currentTimeMillis();
        long id = punishments.createIpGroup(t.uuid(), t.name(), p.reason(), ownerOf(sender), sender.getName(), now, p.ips());
        int kicked = 0;
        for (Player online : Bukkit.getOnlinePlayers()) {
            String ip = online.getAddress() == null ? null : trust.usable(online.getAddress().getAddress());
            boolean target = online.getUniqueId().equals(t.uuid());
            if (target || (ip != null && p.ips().contains(ip))) {
                var punishment = store.punishment(id).orElseThrow();
                if (!target) {
                    store.recordRestricted(id, online.getUniqueId(), online.getName(), ip, now);
                }
                online.kick(target ? punishments.banScreen(punishment, now) : punishments.ipBanScreen(punishment));
                kicked++;
            }
        }
        audit.record(sender.getName(), "ippunish", t.name() + "=" + t.uuid(), p.reason(), "applied",
                punishments.reference(id) + "; " + p.ips().size() + " address(es); kicked " + kicked);
        messages.send(sender, "ippunished", Messages.values("name", t.name(), "count", p.ips().size(), "ref", punishments.reference(id)));
    }

    private void startRestore(CommandSender sender, Pending p) throws Exception {
        long from = p.restoreFrom();
        List<JobEngine.StepPlan> steps = new ArrayList<>();
        Path root = settings.backupDir.resolve("job-" + from);
        List<UUID> owners = new ArrayList<>();
        p.targets().forEach(t -> owners.add(t.uuid()));
        owners.add(null);
        for (UUID u : owners) {
            for (Integration i : registry.all()) {
                if (Files.isDirectory(engine.backupDir(from, u, i.id()))) {
                    steps.add(new JobEngine.StepPlan(u, i.id(), i.phase()));
                }
            }
        }
        if (steps.isEmpty()) {
            line(sender, "<red>job <job> has no restorable backups in <dir></red>", "job", from, "dir", root);
            return;
        }
        long jobId = engine.create(JobType.RESTORE, ownerOf(sender), sender.getName(), p.reason(),
                new WipeOptions(false, false, from), p.targets(), steps, false, null);
        for (JobEngine.Target t : p.targets()) {
            Player online = Bukkit.getPlayer(t.uuid());
            if (online != null) {
                online.kick(messages.screen("lock-screen", Messages.values("ref", "job " + jobId)));
            }
        }
        audit.record(sender.getName(), "restore", targetsString(p.targets()), p.reason(), "started", "job " + jobId + " from job " + from);
        messages.send(sender, "job-started", Messages.values("job", jobId, "what", "restore of job " + from));
        engine.evaluate(jobId);
    }

    /** Releases stale preview freezes (called on enable; tokens do not survive a restart). */
    public static int releaseFreezes(CoreStore store) throws Exception {
        return store.unlockJob(FREEZE_JOB);
    }
}
