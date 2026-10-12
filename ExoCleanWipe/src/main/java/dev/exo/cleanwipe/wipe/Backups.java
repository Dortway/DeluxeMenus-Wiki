package dev.exo.cleanwipe.wipe;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Backup files are written once: if a file already exists it came from an earlier attempt of the same step and
 * describes the data before that attempt changed anything, so it is kept. Writes go to a temporary file, are forced
 * to disk and then moved into place, so a crash never leaves a half-written backup behind.
 */
public final class Backups {

    private Backups() {
    }

    /** @return true if written now, false if it already existed */
    public static boolean writeOnce(Path file, byte[] content) throws IOException {
        if (Files.exists(file)) {
            return false;
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(content);
            while (buf.hasRemaining()) {
                ch.write(buf);
            }
            ch.force(true);
        }
        move(tmp, file);
        return true;
    }

    public static boolean writeOnce(Path file, String content) throws IOException {
        return writeOnce(file, content.getBytes(StandardCharsets.UTF_8));
    }

    /** Copies {@code source} to {@code target} unless the target exists. Missing sources are ignored. */
    public static boolean copyOnce(Path source, Path target) throws IOException {
        if (!Files.exists(source) || Files.exists(target)) {
            return false;
        }
        return writeOnce(target, Files.readAllBytes(source));
    }

    /** Atomic replace (falls back to a plain replace on file systems without atomic moves). */
    public static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Writes a file in place atomically (temp + fsync + move). */
    public static void replace(Path file, byte[] content) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".exocleanwipe.tmp");
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(content);
            while (buf.hasRemaining()) {
                ch.write(buf);
            }
            ch.force(true);
        }
        move(tmp, file);
    }
}
