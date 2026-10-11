package net.exoeconomy.core.tx;

public enum TxKind {
    /** Shards withdrawn for a booster. */
    BOOSTER_PURCHASE(Direction.CHARGE),
    /** Shards deposited for items sold through /sell. */
    SALE(Direction.PAYOUT),
    /** Shards returned for a parent charge (automatic or admin). At most one per parent. */
    REFUND(Direction.PAYOUT),
    /** Shards deposited by an admin resolving an uncertain sale. At most one per parent. */
    PAYOUT(Direction.PAYOUT);

    public enum Direction { CHARGE, PAYOUT }
    public final Direction direction;
    TxKind(Direction direction) { this.direction = direction; }
}
