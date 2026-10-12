package dev.exo.cleanwipe.wipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.exo.cleanwipe.storage.CoreStore;
import dev.exo.cleanwipe.storage.Records.JobState;
import dev.exo.cleanwipe.storage.Records.JobType;
import dev.exo.cleanwipe.storage.Records.Phase;
import dev.exo.cleanwipe.storage.Records.StepState;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Regression: startup wipes must see plugins that load after ExoCleanWipe (ExoVaults was reported as missing). */
class InstalledPluginsTest {

    @TempDir
    Path dir;

    private void jar(String file, String descriptor, String yaml) throws Exception {
        try (OutputStream out = Files.newOutputStream(dir.resolve(file)); ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry(descriptor));
            z.write(yaml.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
    }

    @Test
    void readsNamesFromJarsOnDisk() throws Exception {
        jar("ExoVaults-1.0.0.jar", "plugin.yml", "name: ExoVaults\nversion: '1.0.0'\nmain: dev.exo.vaults.ExoVaultsPlugin\n");
        jar("ExcellentEconomy-2.8.0 (1).jar", "paper-plugin.yml", "main: x\nversion: '2.8.0'\nname: ExcellentEconomy\n");
        Files.writeString(dir.resolve("broken.jar"), "not a zip");
        Set<String> names = InstalledPlugins.scan(dir);
        assertEquals(Set.of("exovaults", "excellenteconomy"), names);
    }

    @Test
    void retryRequeuesStepsSkippedAsNotInstalled() throws Exception {
        try (CoreStore store = new CoreStore(dir.resolve("core.db"))) {
            IntegrationRegistry reg = new IntegrationRegistry();
            reg.markUnavailable("ExoVaults", "plugin not installed");
            JobEngine e = new JobEngine(store, reg, dir.resolve("b"), Logger.getLogger("t"));
            UUID t = UUID.randomUUID();
            long job = e.create(JobType.PUNISH, null, "Mod", "duping", WipeOptions.DEFAULT,
                    List.of(new JobEngine.Target(t, "Steve", "primary")),
                    List.of(new JobEngine.StepPlan(t, "ExoVaults", Phase.STARTUP)), false, null);
            e.runStartupSteps(true);
            assertEquals(JobState.COMPLETED, store.job(job).orElseThrow().state());
            assertEquals(1, e.retry(job));
            assertEquals(JobState.WAITING_RESTART, store.job(job).orElseThrow().state());
            assertTrue(store.lockOf(t).isPresent(), "relocked until the vault step runs");
            assertEquals(StepState.PENDING, store.steps(job).get(0).state());
        }
    }
}
