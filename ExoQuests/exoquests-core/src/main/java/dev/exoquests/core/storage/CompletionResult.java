package dev.exoquests.core.storage;

/**
 * Outcome of a completion attempt.
 *
 * @param credited  true only for the single attempt that transitioned the slot to completed
 * @param pointsAwarded points actually added (can be lower than the reward at the balance cap)
 */
public record CompletionResult(boolean credited, int slot, String questId, int reward, long pointsAwarded,
                               long balance, String completionId) {

    public static CompletionResult notCredited(int slot) {
        return new CompletionResult(false, slot, null, 0, 0, -1, null);
    }
}
