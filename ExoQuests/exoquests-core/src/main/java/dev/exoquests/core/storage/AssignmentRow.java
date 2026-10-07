package dev.exoquests.core.storage;

/** One persisted daily quest slot. */
public record AssignmentRow(int slot, String questId, int target, int reward, int progress, boolean completed) {
}
