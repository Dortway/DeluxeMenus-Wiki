package dev.exo.cleanwipe.integrations.economy;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.exo.cleanwipe.wipe.Backups;
import dev.exo.cleanwipe.wipe.LiveIntegration;
import dev.exo.cleanwipe.wipe.Preview;
import dev.exo.cleanwipe.wipe.StepContext;
import dev.exo.cleanwipe.wipe.StepResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import su.nightexpress.excellenteconomy.api.ExcellentEconomyAPI;
import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;
import su.nightexpress.excellenteconomy.api.currency.operation.OperationResult;

/**
 * ExcellentEconomy 2.8.0 (money, shards, tokens, lightcoin on dogo) through its own API service
 * ({@code ExcellentEconomyAPI}, registered by the plugin). {@code setBalanceAsync(UUID, ...)} loads offline users from
 * its database and saves through the plugin's own cache, so nothing is written behind its back. Balances are saved
 * to {@code balances.json} before they are changed.
 */
public final class ExcellentEconomyIntegration implements LiveIntegration {

    /** UUIDs ExoCleanWipe is changing right now; the balance guard lets these through. */
    public static final Set<UUID> OWN_WRITES = ConcurrentHashMap.newKeySet();

    private final List<String> currencyFilter;
    private final boolean resetToStart;

    /** @param currencyFilter empty = every registered currency */
    public ExcellentEconomyIntegration(List<String> currencyFilter, boolean resetToStart) {
        this.currencyFilter = currencyFilter;
        this.resetToStart = resetToStart;
    }

    @Override
    public String id() {
        return "ExcellentEconomy";
    }

    @Override
    public String covers() {
        return "balances of " + (currencyFilter.isEmpty() ? "every currency" : String.join(", ", currencyFilter))
                + " (reset to " + (resetToStart ? "each currency's start value" : "zero") + ")";
    }

    @Override
    public boolean requiresOffline() {
        return false;
    }

    private ExcellentEconomyAPI api() {
        var reg = Bukkit.getServicesManager().getRegistration(ExcellentEconomyAPI.class);
        return reg == null ? null : reg.getProvider();
    }

    @Override
    public Availability availability() {
        if (!Bukkit.getPluginManager().isPluginEnabled("ExcellentEconomy")) {
            return Availability.missing("ExcellentEconomy is not enabled");
        }
        ExcellentEconomyAPI api = api();
        if (api == null) {
            return Availability.missing("ExcellentEconomy did not register its API service");
        }
        if (!api.canPerformOperations()) {
            return Availability.missing("ExcellentEconomy is not accepting operations (still loading or shutting down)");
        }
        for (String id : currencyFilter) {
            if (!api.hasCurrency(id)) {
                return Availability.missing("currency '" + id + "' from config does not exist in ExcellentEconomy");
            }
        }
        return Availability.ok();
    }

    private List<ExcellentCurrency> currencies(ExcellentEconomyAPI api) {
        List<ExcellentCurrency> out = new ArrayList<>();
        Collection<ExcellentCurrency> all = api.getCurrencies();
        for (ExcellentCurrency c : all) {
            if (currencyFilter.isEmpty() || currencyFilter.contains(c.getId())) {
                out.add(c);
            }
        }
        out.sort((a, b) -> a.getId().compareTo(b.getId()));
        return out;
    }

    private double target(ExcellentCurrency c) {
        return resetToStart ? c.getStartValue() : 0.0;
    }

    @Override
    public Preview preview(UUID target) throws Exception {
        Preview p = new Preview();
        ExcellentEconomyAPI api = api();
        if (api == null) {
            return p;
        }
        for (ExcellentCurrency c : currencies(api)) {
            double bal = api.getBalanceAsync(target, c).get();
            if (bal != target(c)) {
                p.line(c.getId(), c.formatValue(bal) + " -> " + c.formatValue(target(c)));
            }
        }
        return p;
    }

    @Override
    public Preview previewAll() {
        Preview p = new Preview();
        ExcellentEconomyAPI api = api();
        if (api != null) {
            for (ExcellentCurrency c : currencies(api)) {
                p.line(c.getId(), "every balance -> " + c.formatValue(target(c)));
            }
        }
        return p;
    }

    @Override
    public CompletableFuture<StepResult> wipe(StepContext ctx, UUID target) {
        ExcellentEconomyAPI api = api();
        if (api == null || !api.canPerformOperations()) {
            return CompletableFuture.completedFuture(StepResult.failed("ExcellentEconomy API unavailable"));
        }
        List<ExcellentCurrency> list = currencies(api);
        Map<String, Double> before = new LinkedHashMap<>();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (ExcellentCurrency c : list) {
            chain = chain.thenCompose(v -> api.getBalanceAsync(target, c).thenAccept(b -> before.put(c.getId(), b)));
        }
        return chain.thenCompose(v -> {
            try {
                Path dir = ctx.backupDirCreated();
                Path file = dir.resolve("balances.json");
                if (Files.exists(file)) {
                    // an earlier attempt already saved the real balances; keep those
                    before.clear();
                    before.putAll(readBalances(file));
                } else {
                    Backups.writeOnce(file, new Gson().toJson(before));
                }
            } catch (Exception e) {
                return CompletableFuture.completedFuture(StepResult.failed(e));
            }
            OWN_WRITES.add(target);
            List<String> changed = new ArrayList<>();
            List<String> failed = new ArrayList<>();
            CompletableFuture<Void> sets = CompletableFuture.completedFuture(null);
            for (ExcellentCurrency c : list) {
                sets = sets.thenCompose(x -> api.getBalanceAsync(target, c).thenCompose(now -> {
                    if (now == target(c)) {
                        return CompletableFuture.completedFuture(null);
                    }
                    return api.setBalanceAsync(target, c, target(c)).thenAccept(r -> {
                        if (r == OperationResult.SUCCESS) {
                            changed.add(c.getId() + " " + c.formatValue(before.getOrDefault(c.getId(), now)));
                        } else {
                            failed.add(c.getId());
                        }
                    });
                }));
            }
            return sets.handle((x, err) -> {
                OWN_WRITES.remove(target);
                if (err != null) {
                    return StepResult.failed(err);
                }
                if (!failed.isEmpty()) {
                    return StepResult.failed("ExcellentEconomy refused: " + String.join(", ", failed));
                }
                return changed.isEmpty() ? StepResult.nothing("balances already at reset value")
                        : StepResult.done("reset " + String.join(", ", changed));
            });
        });
    }

    private static Map<String, Double> readBalances(Path file) throws java.io.IOException {
        Map<String, Double> m = new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8),
                new TypeToken<Map<String, Double>>() { }.getType());
        return m == null ? Map.of() : m;
    }

    /**
     * Season reset through ExcellentEconomy's own reset (database and cached users) per currency.
     * The plugin's data.db is copied with VACUUM INTO first by the job service.
     */
    @Override
    public CompletableFuture<StepResult> wipeAll(StepContext ctx) {
        ExcellentEconomyAPI api = api();
        if (api == null || !api.canPerformOperations()) {
            return CompletableFuture.completedFuture(StepResult.failed("ExcellentEconomy API unavailable"));
        }
        if (!resetToStart) {
            return CompletableFuture.completedFuture(StepResult.failed(
                    "season reset only supports reset-to: start-value (ExcellentEconomy's own reset); set it in config.yml"));
        }
        List<String> done = new ArrayList<>();
        for (ExcellentCurrency c : currencies(api)) {
            api.currencyManager().resetBalances(Bukkit.getConsoleSender(), c);
            done.add(c.getId());
        }
        return CompletableFuture.completedFuture(StepResult.done("reset " + String.join(", ", done) + " for everyone"));
    }

    @Override
    public CompletableFuture<StepResult> restore(StepContext ctx, UUID target, Path source) {
        Path file = source.resolve("balances.json");
        ExcellentEconomyAPI api = api();
        if (!Files.exists(file)) {
            return CompletableFuture.completedFuture(StepResult.nothing("no balance backup"));
        }
        if (api == null || !api.canPerformOperations()) {
            return CompletableFuture.completedFuture(StepResult.failed("ExcellentEconomy API unavailable"));
        }
        Map<String, Double> saved;
        try {
            saved = readBalances(file);
        } catch (Exception e) {
            return CompletableFuture.completedFuture(StepResult.failed(e));
        }
        OWN_WRITES.add(target);
        List<String> failed = new ArrayList<>();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (Map.Entry<String, Double> e : saved.entrySet()) {
            ExcellentCurrency c = api.getCurrency(e.getKey());
            if (c == null) {
                failed.add(e.getKey() + " (currency gone)");
                continue;
            }
            chain = chain.thenCompose(x -> api.setBalanceAsync(target, c, e.getValue()).thenAccept(r -> {
                if (r != OperationResult.SUCCESS) {
                    failed.add(e.getKey());
                }
            }));
        }
        return chain.handle((x, err) -> {
            OWN_WRITES.remove(target);
            if (err != null) {
                return StepResult.failed(err);
            }
            return failed.isEmpty() ? StepResult.done("restored " + saved.size() + " balance(s)")
                    : StepResult.failed("could not restore: " + String.join(", ", failed));
        });
    }
}
