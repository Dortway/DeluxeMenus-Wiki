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
import java.util.Map;
import java.util.UUID;

/** ExoSpawners 2.1.0 stores spawner tier unlocks and kill counters in {@code players.yml} under {@code players.<uuid>}. */
public final class ExoSpawnersIntegration implements StartupIntegration {

    private final Path players;

    public ExoSpawnersIntegration(Path players) {
        this.players = players;
    }

    @Override
    public String id() {
        return "ExoSpawners";
    }

    @Override
    public String covers() {
        return "spawner tier unlocks and kill progress";
    }

    @Override
    public Availability availability() {
        return Files.exists(players) ? Availability.ok() : Availability.missing("no " + players);
    }

    @Override
    public Preview preview(UUID target) throws Exception {
        Preview p = new Preview();
        Map<String, Object> s = YamlFiles.section(YamlFiles.load(players), "players");
        if (s != null) {
            long n = target == null ? s.size() : (s.containsKey(target.toString()) ? 1 : 0);
            if (n > 0) {
                p.line("spawner progress entries", n);
            }
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
        Map<String, Object> root = YamlFiles.load(players);
        Map<String, Object> s = YamlFiles.section(root, "players");
        if (s == null) {
            return StepResult.nothing("no spawner progress");
        }
        Map<String, Object> saved = new LinkedHashMap<>();
        for (String key : new ArrayList<>(s.keySet())) {
            if (target == null || key.equals(target.toString())) {
                saved.put(key, s.get(key));
            }
        }
        if (saved.isEmpty()) {
            return StepResult.nothing("no spawner progress");
        }
        Path dir = ctx.backupDirCreated();
        Backups.writeOnce(dir.resolve("restore.yml"), YamlFiles.dump(Map.of("players", saved)));
        saved.keySet().forEach(s::remove);
        Backups.replace(players, YamlFiles.dump(root).getBytes(StandardCharsets.UTF_8));
        return StepResult.done("removed " + saved.size() + " progress entr(ies)");
    }

    @Override
    public StepResult restore(StepContext ctx, Path source) throws Exception {
        Path file = source.resolve("restore.yml");
        if (!Files.exists(file)) {
            return StepResult.nothing("no ExoSpawners backup");
        }
        Map<String, Object> saved = YamlFiles.section(YamlFiles.load(file), "players");
        if (saved == null || saved.isEmpty()) {
            return StepResult.nothing("ExoSpawners backup was empty");
        }
        Map<String, Object> root = YamlFiles.load(players);
        Map<String, Object> s = YamlFiles.section(root, "players");
        if (s == null) {
            s = new LinkedHashMap<>();
            root.put("players", s);
        }
        s.putAll(saved);
        Backups.replace(players, YamlFiles.dump(root).getBytes(StandardCharsets.UTF_8));
        return StepResult.done("restored " + saved.size() + " progress entr(ies)");
    }
}
