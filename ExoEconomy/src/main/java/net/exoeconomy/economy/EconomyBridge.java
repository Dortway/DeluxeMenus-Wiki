package net.exoeconomy.economy;

import net.exoeconomy.core.Money;
import net.exoeconomy.core.tx.LedgerResult;
import net.exoeconomy.core.tx.ShardsLedger;
import org.bukkit.Bukkit;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * {@link ShardsLedger} backed by ExcellentEconomy when it is present and the configured currency
 * exists; otherwise reports unavailable and every economy-dependent action is refused.
 * Reconnects when ExcellentEconomy registers or unregisters its service.
 */
public final class EconomyBridge implements ShardsLedger {
    private final Logger log;
    private final String currencyId;
    private final String scaleSetting;
    private volatile ExcellentEconomyGateway gateway;
    private volatile String reason = "not connected yet";

    public EconomyBridge(String currencyId, String scaleSetting, Logger log) {
        this.currencyId = currencyId; this.scaleSetting = scaleSetting; this.log = log;
    }

    /** (Re)connects; logs only when the state changes. Returns true when connected. */
    public boolean connect() { return connect(false); }

    /** @param quiet do not log a failure (used for the early attempt before every plugin has enabled) */
    public boolean connect(boolean quiet) {
        boolean wasAvailable = gateway != null;
        String previous = reason;
        try {
            gateway = ExcellentEconomyGateway.connect(currencyId, scaleSetting, log);
            reason = null;
            if (!wasAvailable) log.info("Connected to " + gateway.providerName + " " + gateway.version + " currency '"
                    + gateway.currencyId() + "' (" + gateway.currencyName() + "), precision " + gateway.scale() + " decimals.");
            return true;
        } catch (ExcellentEconomyGateway.GatewayException e) {
            gateway = null;
            reason = e.getMessage();
        } catch (LinkageError | RuntimeException e) {
            gateway = null;
            reason = "ExcellentEconomy API not usable (" + e + "); ExoEconomy needs ExcellentEconomy 2.8.x";
        }
        if (!quiet && (wasAvailable || !reason.equals(previous)))
            log.severe("Shards economy unavailable: " + reason + ". Rewards, selling and booster purchases are disabled until it is fixed.");
        return false;
    }

    public void disconnect(String why) {
        if (gateway != null) log.warning("Shards economy disconnected: " + why);
        gateway = null;
        reason = why;
    }

    public String version() {
        ExcellentEconomyGateway g = gateway;
        return g == null ? "-" : g.version;
    }

    @Override public boolean available() {
        ExcellentEconomyGateway g = gateway;
        return g != null && g.canOperate();
    }

    @Override public String unavailableReason() {
        ExcellentEconomyGateway g = gateway;
        if (g == null) return reason;
        return g.canOperate() ? null : "ExcellentEconomy is not accepting operations right now";
    }

    @Override public String currencyId() {
        ExcellentEconomyGateway g = gateway;
        return g == null ? currencyId : g.currencyId();
    }

    @Override public int scale() {
        ExcellentEconomyGateway g = gateway;
        return g == null ? 0 : g.scale();
    }

    @Override public Optional<BigDecimal> maxBalance() {
        ExcellentEconomyGateway g = gateway;
        return g == null ? Optional.empty() : g.maxBalance();
    }

    @Override public Optional<BigDecimal> balance(UUID player) {
        ExcellentEconomyGateway g = gateway;
        if (g == null) return Optional.empty();
        try {
            return g.balance(player);
        } catch (RuntimeException | LinkageError e) {
            log.warning("Could not read balance: " + e);
            return Optional.empty();
        }
    }

    @Override public LedgerResult withdraw(UUID player, BigDecimal amount) {
        ExcellentEconomyGateway g = gateway;
        if (g == null) return LedgerResult.rejected("economy-unavailable", null);
        return g.withdraw(player, Money.floor(amount, g.scale()));
    }

    @Override public LedgerResult deposit(UUID player, BigDecimal amount) {
        ExcellentEconomyGateway g = gateway;
        if (g == null) return LedgerResult.rejected("economy-unavailable", null);
        return g.deposit(player, Money.floor(amount, g.scale()));
    }

    @Override public CompletableFuture<LedgerResult> depositAnywhere(UUID player, BigDecimal amount) {
        ExcellentEconomyGateway g = gateway;
        if (g == null) return CompletableFuture.completedFuture(LedgerResult.rejected("economy-unavailable", null));
        BigDecimal rounded = Money.floor(amount, g.scale());
        if (Bukkit.isPrimaryThread() && Bukkit.getPlayer(player) != null) return CompletableFuture.completedFuture(g.deposit(player, rounded));
        try {
            return g.depositAsync(player, rounded);
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(new LedgerResult(LedgerResult.Outcome.UNCERTAIN, false, null, null, "exception: " + e));
        }
    }

    @Override public String format(BigDecimal amount) {
        ExcellentEconomyGateway g = gateway;
        if (g == null) return Money.plain(amount);
        try {
            return g.format(amount);
        } catch (RuntimeException e) {
            return Money.plain(amount);
        }
    }
}
