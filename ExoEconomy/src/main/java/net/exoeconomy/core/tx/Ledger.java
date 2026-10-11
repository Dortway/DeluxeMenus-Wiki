package net.exoeconomy.core.tx;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * One currency ExoEconomy can read and move: the Shards currency (ExcellentEconomy) or the selling
 * currency (Vault money or another ExcellentEconomy currency). Implementations delegate to the
 * owning economy plugin; ExoEconomy never stores or caches balances itself.
 * Synchronous methods take online players and must be called on the server thread.
 */
public interface Ledger {
    boolean available();
    /** Human-readable reason when {@link #available()} is false. */
    String unavailableReason();
    String currencyId();
    /** Decimal places amounts are rounded down to before they reach the economy plugin. */
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
