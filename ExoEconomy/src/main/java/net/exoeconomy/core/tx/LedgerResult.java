package net.exoeconomy.core.tx;

import java.math.BigDecimal;

/**
 * What happened when ExcellentEconomy was asked to move Shards.
 *
 * <p>ExcellentEconomy 2.8.0's API returns only a boolean or SUCCESS/FAILURE, with no transaction
 * id and no idempotency key. ExoEconomy therefore reads the balance before and after the call
 * (synchronously, on the server thread) and classifies the outcome:
 * <ul>
 *   <li>SUCCESS: the API reported success. {@code verified} is false if the balance did not move by
 *       exactly the amount (another plugin changed it at the same moment); kept for review.</li>
 *   <li>REJECTED: the API refused (or was never called) and the balance is unchanged.</li>
 *   <li>UNCERTAIN: the API refused or threw, but the balance changed. Never retried automatically.</li>
 * </ul>
 */
public record LedgerResult(Outcome outcome, boolean verified, BigDecimal before, BigDecimal after, String reason) {
    public enum Outcome { SUCCESS, REJECTED, UNCERTAIN }

    public static LedgerResult rejected(String reason, BigDecimal balance) {
        return new LedgerResult(Outcome.REJECTED, true, balance, balance, reason);
    }
    public boolean success() { return outcome == Outcome.SUCCESS; }
}
