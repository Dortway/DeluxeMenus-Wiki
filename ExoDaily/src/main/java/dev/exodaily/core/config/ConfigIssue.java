package dev.exodaily.core.config;

/** A configuration problem pinned to a file and entry path. */
public record ConfigIssue(Severity severity, String file, String path, String message) {

    public enum Severity {
        ERROR,
        WARNING
    }

    public static ConfigIssue error(String file, String path, String message) {
        return new ConfigIssue(Severity.ERROR, file, path, message);
    }

    public static ConfigIssue warning(String file, String path, String message) {
        return new ConfigIssue(Severity.WARNING, file, path, message);
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    @Override
    public String toString() {
        return "[" + file + "] " + (path == null || path.isEmpty() ? "" : path + ": ") + message;
    }
}
