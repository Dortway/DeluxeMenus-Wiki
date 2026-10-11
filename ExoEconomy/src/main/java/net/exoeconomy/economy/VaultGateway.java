package net.exoeconomy.economy;

import net.exoeconomy.core.Money;
import net.exoeconomy.core.tx.LedgerResult;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The only class that touches Vault types (VaultAPI 1.7: Economy#getBalance/has/depositPlayer/
 * withdrawPlayer(OfflinePlayer, double), EconomyResponse#transactionSuccess, fractionalDigits, format).
 * Used for the /sell payout currency ("money"). Calls run on the server thread.
 */
final class VaultGateway {
    private final Economy economy;
    final String name;
    final int scale;

    private VaultGateway(Economy economy) {
        this.economy = economy;
        this.name = economy.getName();
        int digits = economy.fractionalDigits();
        this.scale = digits < 0 ? 2 : Math.min(digits, 4);
    }

    static VaultGateway connect() throws ExcellentEconomyGateway.GatewayException {
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null || rsp.getProvider() == null)
            throw new ExcellentEconomyGateway.GatewayException("No Vault economy provider is registered (install Vault and an economy plugin)");
        Economy economy = rsp.getProvider();
        if (!economy.isEnabled()) throw new ExcellentEconomyGateway.GatewayException("Vault economy '" + economy.getName() + "' is not enabled");
        return new VaultGateway(economy);
    }

    boolean enabled() { return economy.isEnabled(); }

    String format(BigDecimal amount) { return economy.format(amount.doubleValue()); }

    BigDecimal balance(UUID id) { return Money.ofDouble(economy.getBalance(player(id))); }

    private static OfflinePlayer player(UUID id) {
        OfflinePlayer online = Bukkit.getPlayer(id);
        return online != null ? online : Bukkit.getOfflinePlayer(id);
    }

    LedgerResult deposit(UUID id, BigDecimal amount) {
        requirePrimaryThread();
        OfflinePlayer p = player(id);
        BigDecimal before = Money.ofDouble(economy.getBalance(p));
        EconomyResponse response;
        try {
            response = economy.depositPlayer(p, amount.doubleValue());
        } catch (RuntimeException e) {
            return classify(false, before, balanceOrNull(p), amount, "exception: " + e);
        }
        boolean ok = response != null && response.transactionSuccess();
        return classify(ok, before, balanceOrNull(p), amount, ok ? null : "economy-refused");
    }

    LedgerResult withdraw(UUID id, BigDecimal amount) {
        requirePrimaryThread();
        OfflinePlayer p = player(id);
        BigDecimal before = Money.ofDouble(economy.getBalance(p));
        if (before.compareTo(amount) < 0) return LedgerResult.rejected("insufficient-funds", before);
        EconomyResponse response;
        try {
            response = economy.withdrawPlayer(p, amount.doubleValue());
        } catch (RuntimeException e) {
            return classify(false, before, balanceOrNull(p), amount.negate(), "exception: " + e);
        }
        boolean ok = response != null && response.transactionSuccess();
        return classify(ok, before, balanceOrNull(p), amount.negate(), ok ? null : "economy-refused");
    }

    private BigDecimal balanceOrNull(OfflinePlayer p) {
        try {
            return Money.ofDouble(economy.getBalance(p));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private LedgerResult classify(boolean ok, BigDecimal before, BigDecimal after, BigDecimal delta, String reason) {
        if (after == null) return new LedgerResult(ok ? LedgerResult.Outcome.SUCCESS : LedgerResult.Outcome.UNCERTAIN, false, before, null,
                ok ? "balance unreadable after success" : reason + "; balance unreadable");
        int s = Math.max(scale, 2);
        boolean moved = Money.sameAtScale(after, before.add(delta), s);
        if (ok) return new LedgerResult(LedgerResult.Outcome.SUCCESS, moved, before, after, moved ? null : "balance moved unexpectedly");
        if (Money.sameAtScale(after, before, s)) return LedgerResult.rejected(reason, before);
        return new LedgerResult(LedgerResult.Outcome.UNCERTAIN, false, before, after, reason + "; but the balance changed");
    }

    private static void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Vault calls must run on the server thread");
    }
}
