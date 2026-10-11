package net.exoeconomy.economy;

import net.exoeconomy.core.Money;
import net.exoeconomy.core.tx.Ledger;
import net.exoeconomy.core.tx.LedgerResult;
import org.bukkit.Bukkit;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * {@link Ledger} for the selling currency backed by Vault ("money"). Vault works for offline
 * players too, so every call runs synchronously on the server thread.
 */
public final class VaultBridge implements Ledger {
    private final Logger log;
    private volatile VaultGateway gateway;
    private volatile String reason = "not connected yet";

    public VaultBridge(Logger log) { this.log = log; }

    public boolean connect(boolean quiet) {
        boolean was = gateway != null;
        String previous = reason;
        try {
            gateway = VaultGateway.connect();
            reason = null;
            if (!was) log.info("Selling pays in Vault money via '" + gateway.name + "' (" + gateway.scale + " decimals).");
            return true;
        } catch (ExcellentEconomyGateway.GatewayException e) {
            gateway = null;
            reason = e.getMessage();
        } catch (LinkageError | RuntimeException e) {
            gateway = null;
            reason = "Vault is not installed or incompatible (" + e + ")";
        }
        if (!quiet && (was || !reason.equals(previous))) log.severe("Selling currency unavailable: " + reason + ". /sell is disabled until it is fixed.");
        return false;
    }

    public void disconnect(String why) {
        if (gateway != null) log.warning("Vault economy disconnected: " + why);
        gateway = null;
        reason = why;
    }

    public String providerName() {
        VaultGateway g = gateway;
        return g == null ? "-" : g.name;
    }

    @Override public boolean available() {
        VaultGateway g = gateway;
        try {
            return g != null && g.enabled();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    @Override public String unavailableReason() { return available() ? null : reason; }

    @Override public String currencyId() {
        VaultGateway g = gateway;
        return "vault:" + (g == null ? "money" : g.name);
    }

    @Override public int scale() {
        VaultGateway g = gateway;
        return g == null ? 2 : g.scale;
    }

    @Override public Optional<BigDecimal> maxBalance() { return Optional.empty(); }

    @Override public Optional<BigDecimal> balance(UUID player) {
        VaultGateway g = gateway;
        if (g == null) return Optional.empty();
        try {
            return Optional.of(g.balance(player));
        } catch (RuntimeException | LinkageError e) {
            return Optional.empty();
        }
    }

    @Override public LedgerResult withdraw(UUID player, BigDecimal amount) {
        VaultGateway g = gateway;
        return g == null ? LedgerResult.rejected("economy-unavailable", null) : g.withdraw(player, Money.floor(amount, g.scale));
    }

    @Override public LedgerResult deposit(UUID player, BigDecimal amount) {
        VaultGateway g = gateway;
        return g == null ? LedgerResult.rejected("economy-unavailable", null) : g.deposit(player, Money.floor(amount, g.scale));
    }

    @Override public CompletableFuture<LedgerResult> depositAnywhere(UUID player, BigDecimal amount) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Vault deposits run on the server thread");
        return CompletableFuture.completedFuture(deposit(player, amount));
    }

    @Override public String format(BigDecimal amount) {
        VaultGateway g = gateway;
        if (g == null) return Money.plain(amount);
        try {
            return g.format(amount);
        } catch (RuntimeException e) {
            return Money.plain(amount);
        }
    }
}
