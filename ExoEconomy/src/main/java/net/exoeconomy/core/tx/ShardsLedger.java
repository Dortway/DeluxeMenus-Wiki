package net.exoeconomy.core.tx;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The only way ExoEconomy moves or reads Shards. The production implementation delegates to
 * ExcellentEconomy's native currency API; ExoEconomy never stores or caches balances itself.
 * Synchronous methods take online players and must be called on the server thread.
 */
public interface ShardsLedger {
    boolean available();
    /** Human-readable reason when {@link #available()} is false. */
    String unavailableReason();
    String currencyId();
    /** Decimal places amounts are rounded down to before they reach ExcellentEconomy. */
    int scale();
    /** Maximum balance, if the currency has one. */
    Optional<BigDecimal> maxBalance();
    /** Balance of an online player; empty if offline or unavailable. */
    Optional<BigDecimal> balance(UUID player);
    LedgerResult withdraw(UUID player, BigDecimal amount);
    LedgerResult deposit(UUID player, BigDecimal amount);
    /** Deposit for a player who may be offline. Completes on any thread. */
    CompletableFuture<LedgerResult> depositAnywhere(UUID player, BigDecimal amount);
    String format(BigDecimal amount);
}
