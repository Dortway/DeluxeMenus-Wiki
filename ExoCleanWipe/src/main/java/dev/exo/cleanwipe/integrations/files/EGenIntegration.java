package dev.exo.cleanwipe.integrations.files;

import dev.exo.cleanwipe.wipe.Backups;
import dev.exo.cleanwipe.wipe.Preview;
import dev.exo.cleanwipe.wipe.StartupIntegration;
import dev.exo.cleanwipe.wipe.StepContext;
import dev.exo.cleanwipe.wipe.StepResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * EGen 1.5.0: {@code machines.yml} ({@code machines.<id>.owner}), the virtual gen chest storage in
 * {@code chestdata.yml} ({@code chests.<id>.contents}) and per-player placement limits in {@code playerdata.yml}
 * ({@code limits.<uuid>}). The target's chests are emptied; the placed machines themselves stay (they are also
 * indexed in chunk data, so removing them from machines.yml would leave orphans; they are cleared with the island).
 */
public final class EGenIntegration implements StartupIntegration {

    private final Path machines;
    private final Path chests;
    private final Path limits;

    public EGenIntegration(Path machines, Path chests, Path limits) {
        this.machines = machines;
        this.chests = chests;
        this.limits = limits;
    }

    @Override
    public String id() {
        return "EGen";
    }

    @Override
    public String covers() {
        return "items in the player's generator chests and their placement limit";
    }

    @Override
    public Availability availability() {
        return Files.exists(machines) || Files.exists(chests) ? Availability.ok() : Availability.missing("no EGen data files");
    }

    private List<String> ownedMachines(UUID target) throws Exception {
        List<String> ids = new ArrayList<>();
        Map<String, Object> m = YamlFiles.section(YamlFiles.load(machines), "machines");
        if (m == null) {
            return ids;
        }
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (e.getValue() instanceof Map<?, ?> s && (target == null || target.toString().equals(String.valueOf(s.get("owner"))))) {
                ids.add(e.getKey());
            }
        }
        return ids;
    }

    private static int stacks(Object contents) {
        int n = 0;
        if (contents instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) {
                    n++;
                }
            }
        }
        return n;
    }

    @Override
    public Preview preview(UUID target) throws Exception {
        Preview p = new Preview();
        List<String> owned = ownedMachines(target);
        Map<String, Object> c = YamlFiles.section(YamlFiles.load(chests), "chests");
        int chestCount = 0;
        int stackCount = 0;
        if (c != null) {
            for (String id : owned) {
                if (c.get(id) instanceof Map<?, ?> s) {
                    chestCount++;
                    stackCount += stacks(s.get("contents"));
                }
            }
        }
        if (stackCount > 0) {
            p.line("gen chest item stacks", stackCount + " in " + chestCount + " chest(s)");
        }
        Map<String, Object> l = YamlFiles.section(YamlFiles.load(limits), "limits");
        if (l != null && target != null && l.containsKey(target.toString())) {
            p.line("custom placement limit", l.get(target.toString()));
        }
        if (!owned.isEmpty()) {
            p.warn(owned.size() + " placed EGen machine(s) stay in the world (cleared with the island if they are on it)");
        }
        return p;
    }

    @Override
    public Preview previewAll() throws Exception {
        return preview(null);
    }

    @Override
    public StepResult wipe(StepContext ctx, UUID target) throws Exception {
        return run(ctx, target);
    }

    @Override
    public StepResult wipeAll(StepContext ctx) throws Exception {
        return run(ctx, null);
    }

    private StepResult run(StepContext ctx, UUID target) throws Exception {
        List<String> owned = ownedMachines(target);
        Map<String, Object> chestRoot = YamlFiles.load(chests);
        Map<String, Object> chestSection = YamlFiles.section(chestRoot, "chests");
        Map<String, Object> limitRoot = YamlFiles.load(limits);
        Map<String, Object> limitSection = YamlFiles.section(limitRoot, "limits");

        Map<String, Object> savedChests = new LinkedHashMap<>();
        Map<String, Object> savedLimits = new LinkedHashMap<>();
        int stacks = 0;
        if (chestSection != null) {
            for (String id : (target == null ? new ArrayList<>(chestSection.keySet()) : owned)) {
                if (chestSection.get(id) instanceof Map<?, ?> s && stacks(s.get("contents")) > 0) {
                    savedChests.put(id, s.get("contents"));
                    stacks += stacks(s.get("contents"));
                }
            }
        }
        if (limitSection != null) {
            for (String key : new ArrayList<>(limitSection.keySet())) {
                if (target == null || key.equals(target.toString())) {
                    savedLimits.put(key, limitSection.get(key));
                }
            }
        }
        if (savedChests.isEmpty() && savedLimits.isEmpty()) {
            return StepResult.nothing("no gen chest items or limits");
        }
        Path dir = ctx.backupDirCreated();
        Map<String, Object> backup = new LinkedHashMap<>();
        backup.put("chests", savedChests);
        backup.put("limits", savedLimits);
        Backups.writeOnce(dir.resolve("restore.yml"), YamlFiles.dump(backup));
        Backups.copyOnce(chests, dir.resolve("chestdata.yml.before"));
        Backups.copyOnce(limits, dir.resolve("playerdata.yml.before"));

        if (!savedChests.isEmpty()) {
            for (String id : savedChests.keySet()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> s = (Map<String, Object>) chestSection.get(id);
                s.put("contents", new ArrayList<>());
            }
            Backups.replace(chests, YamlFiles.dump(chestRoot).getBytes(StandardCharsets.UTF_8));
        }
        if (!savedLimits.isEmpty()) {
            savedLimits.keySet().forEach(limitSection::remove);
            Backups.replace(limits, YamlFiles.dump(limitRoot).getBytes(StandardCharsets.UTF_8));
        }
        return StepResult.done("emptied " + savedChests.size() + " gen chest(s) (" + stacks + " stacks), reset "
                + savedLimits.size() + " limit(s)");
    }

    @Override
    @SuppressWarnings("unchecked")
    public StepResult restore(StepContext ctx, Path source) throws Exception {
        Path file = source.resolve("restore.yml");
        if (!Files.exists(file)) {
            return StepResult.nothing("no EGen backup");
        }
        Map<String, Object> backup = YamlFiles.load(file);
        Map<String, Object> savedChests = YamlFiles.section(backup, "chests");
        Map<String, Object> savedLimits = YamlFiles.section(backup, "limits");
        int restored = 0;
        if (savedChests != null && !savedChests.isEmpty()) {
            Map<String, Object> root = YamlFiles.load(chests);
            Map<String, Object> section = YamlFiles.section(root, "chests");
            if (section == null) {
                section = new LinkedHashMap<>();
                root.put("chests", section);
            }
            for (Map.Entry<String, Object> e : savedChests.entrySet()) {
                Object existing = section.get(e.getKey());
                Map<String, Object> s = existing instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
                s.put("contents", e.getValue());
                section.put(e.getKey(), s);
                restored++;
            }
            Backups.replace(chests, YamlFiles.dump(root).getBytes(StandardCharsets.UTF_8));
        }
        if (savedLimits != null && !savedLimits.isEmpty()) {
            Map<String, Object> root = YamlFiles.load(limits);
            Map<String, Object> section = YamlFiles.section(root, "limits");
            if (section == null) {
                section = new LinkedHashMap<>();
                root.put("limits", section);
            }
            section.putAll(savedLimits);
            restored += savedLimits.size();
            Backups.replace(limits, YamlFiles.dump(root).getBytes(StandardCharsets.UTF_8));
        }
        return restored == 0 ? StepResult.nothing("EGen backup was empty") : StepResult.done("restored " + restored + " EGen entr(ies)");
    }
}
