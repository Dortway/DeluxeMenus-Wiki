package dev.exo.cleanwipe.wipe;

import dev.exo.cleanwipe.storage.Records.StepState;

/** Outcome of one integration for one target. {@code detail} is lowercase and shown to staff. */
public record StepResult(StepState state, String detail) {

    public static StepResult done(String detail) {
        return new StepResult(StepState.DONE, detail);
    }

    public static StepResult nothing(String detail) {
        return new StepResult(StepState.NOTHING, detail);
    }

    public static StepResult skipped(String detail) {
        return new StepResult(StepState.SKIPPED, detail);
    }

    public static StepResult blocked(String detail) {
        return new StepResult(StepState.BLOCKED, detail);
    }

    public static StepResult failed(String detail) {
        return new StepResult(StepState.FAILED, detail);
    }

    public static StepResult failed(Throwable t) {
        String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getClass().getSimpleName() + ": " + t.getMessage();
        return new StepResult(StepState.FAILED, msg.length() > 400 ? msg.substring(0, 400) : msg);
    }
}
