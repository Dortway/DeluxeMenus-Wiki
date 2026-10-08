package dev.exodaily.core.storage;

/** One administrative audit record. */
public record AuditEntry(long at, String actor, String action, String target, String details) {
}
