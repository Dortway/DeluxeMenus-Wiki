package net.exoeconomy.economy;

import net.exoeconomy.core.Money;
import net.exoeconomy.core.tx.LedgerResult;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import su.nightexpress.excellenteconomy.api.ExcellentEconomyAPI;
import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;
import su.nightexpress.excellenteconomy.api.currency.operation.OperationResult;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * The only class that touches ExcellentEconomy types. Written against ExcellentEconomy 2.8.0
 * (su.nightexpress.excellenteconomy.api.ExcellentEconomyAPI, obtained from Bukkit's
 * ServicesManager). Methods used, all verified against the 2.8.0 source:
 * currencyById, getCurrencies, canPerformOperations, getBalance(Player, ExcellentCurrency),
 * withdraw/deposit(Player, ExcellentCurrency, double), getBalanceAsync/depositAsync(UUID, ExcellentCurrency, double),
 * and ExcellentCurrency getId, getName, isDecimal, isLimited, getMaxValue, format.
 *
 * <p>The API calls without an OperationContext use ExcellentEconomy's default "API" context, which
 * silences player/executor/console messages but still writes its own operations log.
 */
final class ExcellentEconomyGateway {
    private final ExcellentEconomyAPI api;
    private final ExcellentCurrency currency;
    private final int scale;
    final String version;
    final String providerName;

    private ExcellentEconomyGateway(ExcellentEconomyAPI api, ExcellentCurrency currency, int scale, String version, String providerName) {
        this.api = api; this.currency = currency; this.scale = scale; this.version = version; this.providerName = providerName;
    }

    static final class GatewayException extends Exception {
        GatewayException(String message) { super(message); }
    }

    static ExcellentEconomyGateway connect(String currencyId, String scaleSetting, java.util.logging.Logger log) throws GatewayException {
        RegisteredServiceProvider<ExcellentEconomyAPI> rsp = Bukkit.getServicesManager().getRegistration(ExcellentEconomyAPI.class);
        if (rsp == null) throw new GatewayException("ExcellentEconomy's API service is not registered (is ExcellentEconomy 2.8.x installed and enabled?)");
        ExcellentEconomyAPI api = rsp.getProvider();
        String version = rsp.getPlugin().getPluginMeta().getVersion();
        Optional<ExcellentCurrency> found = api.currencyById(currencyId);
        if (found.isEmpty()) {
            String ids = api.getCurrencies().stream().map(ExcellentCurrency::getId).sorted().collect(Collectors.joining(", "));
            throw new GatewayException("ExcellentEconomy has no currency '" + currencyId + "'. Available: [" + ids
                    + "]. Create it (plugins/ExcellentEconomy/currencies/" + currencyId + ".yml) or set economy.currency-id.");
        }
        ExcellentCurrency currency = found.get();
        int scale;
        if ("auto".equalsIgnoreCase(scaleSetting)) scale = currency.isDecimal() ? 2 : 0;
        else {
            scale = Integer.parseInt(scaleSetting);
            if (scale < 0 || scale > 4) throw new GatewayException("economy.scale must be auto or 0..4");
            if (!currency.isDecimal() && scale > 0) {
                log.warning("Currency '" + currencyId + "' is not decimal in ExcellentEconomy; using economy.scale 0 instead of " + scale);
                scale = 0;
            }
        }
        if (!version.startsWith("2.8")) log.warning("ExcellentEconomy " + version + " detected. ExoEconomy was written and verified against the 2.8.0 API; check the compatibility notes before production use.");
        return new ExcellentEconomyGateway(api, currency, scale, version, rsp.getPlugin().getName());
    }

    String currencyId() { return currency.getId(); }
    String currencyName() { return currency.getName(); }
    int scale() { return scale; }
    boolean canOperate() { return api.canPerformOperations(); }

    Optional<BigDecimal> maxBalance() {
        return currency.isLimited() ? Optional.of(Money.ofDouble(currency.getMaxValue())) : Optional.empty();
    }

    String format(BigDecimal amount) { return currency.format(amount.doubleValue()); }

    Optional<BigDecimal> balance(UUID id) {
        Player player = Bukkit.getPlayer(id);
        if (player == null) return Optional.empty();
        return Optional.of(Money.ofDouble(api.getBalance(player, currency)));
    }

    LedgerResult withdraw(UUID id, BigDecimal amount) {
        requirePrimaryThread();
        Player player = Bukkit.getPlayer(id);
        if (player == null) return LedgerResult.rejected("player-offline", null);
        if (!api.canPerformOperations()) return LedgerResult.rejected("economy-busy", null);
        BigDecimal before = Money.ofDouble(api.getBalance(player, currency));
        // ExcellentEconomy's withdraw behaviour on insufficient funds is not documented, so never ask for more than the balance.
        if (before.compareTo(amount) < 0) return LedgerResult.rejected("insufficient-funds", before);
        boolean ok;
        try {
            ok = api.withdraw(player, currency, amount.doubleValue());
        } catch (RuntimeException e) {
            return classify(false, before, balanceOrNull(player), amount.negate(), "exception: " + e);
        }
        return classify(ok, before, balanceOrNull(player), amount.negate(), ok ? null : "economy-refused");
    }

    LedgerResult deposit(UUID id, BigDecimal amount) {
        requirePrimaryThread();
        Player player = Bukkit.getPlayer(id);
        if (player == null) return LedgerResult.rejected("player-offline", null);
        if (!api.canPerformOperations()) return LedgerResult.rejected("economy-busy", null);
        BigDecimal before = Money.ofDouble(api.getBalance(player, currency));
        Optional<BigDecimal> max = maxBalance();
        // A limited currency may clamp the deposit silently; refuse instead of losing Shards.
        if (max.isPresent() && before.add(amount).compareTo(max.get()) > 0) return LedgerResult.rejected("balance-limit", before);
        boolean ok;
        try {
            ok = api.deposit(player, currency, amount.doubleValue());
        } catch (RuntimeException e) {
            return classify(false, before, balanceOrNull(player), amount, "exception: " + e);
        }
        return classify(ok, before, balanceOrNull(player), amount, ok ? null : "economy-refused");
    }

    /** Offline-capable deposit via the async API. Futures complete on ExcellentEconomy's threads. */
    CompletableFuture<LedgerResult> depositAsync(UUID id, BigDecimal amount) {
        if (!api.canPerformOperations()) return CompletableFuture.completedFuture(LedgerResult.rejected("economy-busy", null));
        return api.getBalanceAsync(id, currency).thenCompose(beforeRaw -> {
            BigDecimal before = Money.ofDouble(beforeRaw);
            Optional<BigDecimal> max = maxBalance();
            if (max.isPresent() && before.add(amount).compareTo(max.get()) > 0)
                return CompletableFuture.completedFuture(LedgerResult.rejected("balance-limit", before));
            return api.depositAsync(id, currency, amount.doubleValue())
                    .thenCompose(result -> api.getBalanceAsync(id, currency)
                            .thenApply(after -> classify(result == OperationResult.SUCCESS, before, Money.ofDouble(after), amount,
                                    result == OperationResult.SUCCESS ? null : "economy-refused")));
        });
    }

    private BigDecimal balanceOrNull(Player player) {
        try {
            return Money.ofDouble(api.getBalance(player, currency));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private LedgerResult classify(boolean ok, BigDecimal before, BigDecimal after, BigDecimal delta, String reason) {
        if (after == null) return new LedgerResult(ok ? LedgerResult.Outcome.SUCCESS : LedgerResult.Outcome.UNCERTAIN, false, before, null,
                ok ? "balance unreadable after success" : reason + "; balance unreadable");
        boolean moved = Money.sameAtScale(after, before.add(delta), Math.max(scale, 2));
        boolean unchanged = Money.sameAtScale(after, before, Math.max(scale, 2));
        if (ok) return new LedgerResult(LedgerResult.Outcome.SUCCESS, moved, before, after, moved ? null : "balance moved unexpectedly");
        if (unchanged) return LedgerResult.rejected(reason, before);
        return new LedgerResult(LedgerResult.Outcome.UNCERTAIN, false, before, after, reason + "; but the balance changed");
    }

    private static void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Synchronous ExcellentEconomy calls must run on the server thread");
    }
}
