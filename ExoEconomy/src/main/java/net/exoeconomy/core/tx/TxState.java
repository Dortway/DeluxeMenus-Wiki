package net.exoeconomy.core.tx;

public enum TxState {
    /** Journaled; the ExcellentEconomy call has not finished (or the server stopped before it did). */
    PENDING,
    /** ExcellentEconomy reported success. */
    COMMITTED,
    /** ExcellentEconomy refused and the balance did not change. Nothing moved. */
    FAILED,
    /** Abandoned before ExcellentEconomy was called (menu closed, player left, offer changed). Nothing moved. */
    ABORTED,
    /** Outcome unknown; needs an admin decision. Never retried automatically. */
    UNCERTAIN,
    /** An admin decided the money moved. */
    RESOLVED_COMPLETED,
    /** An admin decided the money did not move. */
    RESOLVED_FAILED;

    public boolean open() { return this == PENDING || this == UNCERTAIN; }
}
