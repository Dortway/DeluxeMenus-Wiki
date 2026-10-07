package dev.exoquests.core;

import dev.exoquests.core.config.ConfigBundle;
import dev.exoquests.core.config.ConfigException;
import dev.exoquests.core.config.ConfigLoader;
import dev.exoquests.core.config.PlatformValidator;
import dev.exoquests.core.storage.Database;
import dev.exoquests.core.time.Clock;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

public final class TestSupport {

    public static final Logger LOG = Logger.getLogger("ExoQuestsTest");

    private TestSupport() {
    }

    public static Database open(Path dir) throws SQLException {
        return Database.open(dir.resolve("test.db"), "NORMAL", 5_000, LOG);
    }

    /** Copies the bundled default configuration files into {@code dir}. */
    public static void copyDefaults(Path dir) throws IOException {
        for (String f : ConfigLoader.FILES) {
            try (InputStream in = TestSupport.class.getClassLoader().getResourceAsStream(f)) {
                Files.copy(in, dir.resolve(f), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    public static ConfigBundle defaults(Path dir) throws IOException, ConfigException {
        copyDefaults(dir);
        return ConfigLoader.load(dir, PlatformValidator.PERMISSIVE);
    }

    /** A controllable clock. */
    public static final class MutableClock implements Clock {
        private final AtomicReference<Instant> now;

        public MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        @Override
        public Instant now() {
            return now.get();
        }

        public void set(Instant instant) {
            now.set(instant);
        }
    }

    /** Single thread standing in for the server main thread. */
    public static ExecutorService mainThread() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "fake-main");
            t.setDaemon(true);
            return t;
        });
    }

    /** Waits until queued database work and main-thread callbacks have drained. */
    public static void settle(Database db, ExecutorService main) throws Exception {
        for (int i = 0; i < 8; i++) {
            db.submit(c -> null).get(10, TimeUnit.SECONDS);
            main.submit(() -> { }).get(10, TimeUnit.SECONDS);
        }
    }
}
