package net.exoeconomy.core.booster;

/** Result of planning a purchase against the current booster state. */
public record Plan(Kind kind, String targetId, String reason) {
    public enum Kind {
        /** Start a new booster now. */
        ACTIVATE,
        /** Add the duration to the existing booster {@code targetId}. */
        EXTEND,
        /** Store a new booster that starts when overlapping boosters end. */
        QUEUE,
        /** Not allowed; {@code reason} is a message key. */
        REJECT
    }
    static Plan activate() { return new Plan(Kind.ACTIVATE, null, null); }
    static Plan extend(String id) { return new Plan(Kind.EXTEND, id, null); }
    static Plan queue() { return new Plan(Kind.QUEUE, null, null); }
    static Plan reject(String reason) { return new Plan(Kind.REJECT, null, reason); }
    public boolean rejected() { return kind == Kind.REJECT; }
}
