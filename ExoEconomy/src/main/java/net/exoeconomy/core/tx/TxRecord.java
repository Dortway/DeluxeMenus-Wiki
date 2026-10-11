package net.exoeconomy.core.tx;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One journaled Shards movement.
 *
 * @param details key=value properties describing what was bought or sold (booster spec, item
 *                summary), enough for an admin to reconcile without the original menu
 */
public record TxRecord(String id, UUID player, TxKind kind, TxState state, BigDecimal amount, String currency,
                       String parent, String details, BigDecimal balanceBefore, BigDecimal balanceAfter,
                       long createdAt, long updatedAt, String note) {
    public static TxRecord pending(String id, UUID player, TxKind kind, BigDecimal amount, String currency,
                                   String parent, String details, long now) {
        return new TxRecord(id, player, kind, TxState.PENDING, amount, currency, parent, details, null, null, now, now, null);
    }
}
